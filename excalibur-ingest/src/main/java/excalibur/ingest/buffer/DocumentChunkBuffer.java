package excalibur.ingest.buffer;

import io.excalibur.core.text.ChunkIdentifiers;
import excalibur.ingest.processor.TextChunk;
import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.store.WriteLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buffers document chunks in memory and writes them atomically to the write log.
 *
 * <p>This class implements document-atomic visibility for the ingestion pipeline.
 * All chunks belonging to a single document are buffered in memory and written
 * to the write log as a single atomic batch. The offset range of the batch is
 * tracked for visibility epoch publishing.</p>
 *
 * <h2>Document Atomicity</h2>
 * <p>A document is considered "atomic" when all its chunks are written to the
 * write log with contiguous offsets. This ensures that:</p>
 * <ul>
 *   <li>Queries never see partially-ingested documents</li>
 *   <li>Crash recovery can identify complete documents</li>
 *   <li>Visibility epoch can publish documents as atomic units</li>
 * </ul>
 *
 * <h2>Write Log Contract</h2>
 * <p>Chunks are appended to {@link io.excalibur.store.WriteLog} - the single write-ahead log
 * the storage engine, compaction, the tail scan and the HNSW integration worker all agree on -
 * as {@code PUT} entries keyed by chunk id:</p>
 * <pre>
 *   key:   sourceId:documentId:chunk:index
 *   value: [dimension:4][vector:dim*4][text:variable] (little-endian), or the raw chunk text
 *          when the document is committed without embeddings
 * </pre>
 * <p>Every append fsyncs before it returns, so a committed batch is durable by the time
 * {@link #commitDocument(String)} hands it back and the caller may publish it immediately.</p>
 *
 * <h2>Offset Range Tracking</h2>
 * <p>When a document is committed, the buffer returns an {@link AtomicDocumentBatch}
 * containing:</p>
 * <ul>
 *   <li>Document ID</li>
 *   <li>Start offset in write log</li>
 *   <li>End offset in write log</li>
 *   <li>Chunk IDs for registry tracking</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Per-document buffers use per-document locks</li>
 *   <li>Concurrent documents can be buffered in parallel</li>
 *   <li>Commit operations are atomic per document</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunks for one document have contiguous write log offsets</li>
 *   <li>Start offset &lt; end offset for committed batches</li>
 *   <li>Chunk IDs are unique per document: documentId:chunkIndex</li>
 *   <li>All offsets are non-negative</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class DocumentChunkBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentChunkBuffer.class);

    private final WriteLog writeLog;
    private final int maxChunksPerDocument;
    private final Map<String, DocumentBuffer> documentBuffers;
    private final ReentrantLock commitLock;

    /**
     * Creates a new DocumentChunkBuffer with the default maximum chunks per document.
     *
     * <p>Default maximum: 10,000 chunks per document. This prevents unbounded
     * memory consumption from extremely large documents.</p>
     *
     * @param writeLog The write log to append chunks to (must not be null)
     * @throws NullPointerException if writeLog is null
     */
    public DocumentChunkBuffer(WriteLog writeLog) {
        this(writeLog, 10000);
    }

    /**
     * Creates a new DocumentChunkBuffer with a custom maximum chunks per document.
     *
     * @param writeLog The write log to append chunks to (must not be null)
     * @param maxChunksPerDocument Maximum chunks allowed per document
     * @throws NullPointerException if writeLog is null
     * @throws IllegalArgumentException if maxChunksPerDocument is not positive
     */
    public DocumentChunkBuffer(WriteLog writeLog, int maxChunksPerDocument) {
        Objects.requireNonNull(writeLog, "writeLog must not be null");
        if (maxChunksPerDocument <= 0) {
            throw new IllegalArgumentException("maxChunksPerDocument must be positive: " + maxChunksPerDocument);
        }
        this.writeLog = writeLog;
        this.maxChunksPerDocument = maxChunksPerDocument;
        this.documentBuffers = new ConcurrentHashMap<>();
        this.commitLock = new ReentrantLock();

        LOGGER.debug("DocumentChunkBuffer created with maxChunksPerDocument={}", maxChunksPerDocument);
    }

    /**
     * Buffers a chunk for a document.
     *
     * <p>The chunk is stored in memory until {@link #commitDocument(String)} is called.
     * Multiple chunks for the same document can be buffered before committing.</p>
     *
     * @param chunk The text chunk to buffer (must not be null)
     * @throws NullPointerException if chunk is null
     * @throws IllegalStateException if maximum chunks per document exceeded
     */
    public void bufferChunk(TextChunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");

        String documentId = chunk.getDocumentId();
        DocumentBuffer buffer = acquireBuffer(documentId);

        try {
            if (buffer.chunks.size() >= maxChunksPerDocument) {
                throw new IllegalStateException(
                    "Maximum chunks per document exceeded: " + maxChunksPerDocument + 
                    " for document: " + documentId);
            }

            buffer.chunks.add(chunk);
            LOGGER.trace("Buffered chunk {} for document {}", chunk.getChunkIndex(), documentId);
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Resolves the buffer for a document and returns it with its lock already held.
     *
     * <p>Resolving the map entry and locking it cannot be two independent steps: a commit or an
     * abort running in between removes the entry, and the caller would then append to a buffer
     * nothing will ever read. The loop re-checks that the entry it locked is still the one the
     * map publishes and retries otherwise, so a chunk is either added to the live buffer or the
     * caller creates a fresh one.</p>
     *
     * @param documentId The document whose buffer is wanted
     * @return The live buffer for the document, with {@code buffer.lock} held by this thread
     */
    private DocumentBuffer acquireBuffer(String documentId) {
        while (true) {
            DocumentBuffer buffer = documentBuffers.computeIfAbsent(documentId, k -> new DocumentBuffer());
            buffer.lock.lock();
            if (documentBuffers.get(documentId) == buffer) {
                return buffer;
            }
            buffer.lock.unlock();
        }
    }

    /**
     * Buffers multiple chunks for a document.
     *
     * <p>This is more efficient than calling {@link #bufferChunk(TextChunk)}
     * multiple times when you have all chunks available upfront.</p>
     *
     * @param chunks The chunks to buffer (must not be null, may be empty)
     * @throws NullPointerException if chunks is null or contains null elements
     * @throws IllegalStateException if maximum chunks per document exceeded
     */
    public void bufferChunks(List<TextChunk> chunks) {
        Objects.requireNonNull(chunks, "chunks must not be null");

        if (chunks.isEmpty()) {
            return;
        }

        String documentId = chunks.get(0).getDocumentId();
        DocumentBuffer buffer = acquireBuffer(documentId);

        try {
            if (buffer.chunks.size() + chunks.size() > maxChunksPerDocument) {
                throw new IllegalStateException(
                    "Maximum chunks per document exceeded: " + maxChunksPerDocument + 
                    " for document: " + documentId);
            }

            for (TextChunk chunk : chunks) {
                Objects.requireNonNull(chunk, "chunk must not be null");
                if (!chunk.getDocumentId().equals(documentId)) {
                    throw new IllegalArgumentException(
                        "All chunks must be for the same document. Expected: " + documentId +
                        ", got: " + chunk.getDocumentId());
                }
                buffer.chunks.add(chunk);
            }

            LOGGER.debug("Buffered {} chunks for document {}", chunks.size(), documentId);
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Commits all buffered chunks for a document to the write log atomically.
     *
     * <p><strong>Atomicity guarantee:</strong> All chunks for the document are
     * written to the write log with contiguous offsets using a single atomic
     * batch append operation. No other writes can interleave within this batch.
     * If any chunk fails to write, an exception is thrown and no chunks are persisted.</p>
     *
     * <p><strong>Domain correctness:</strong> The returned batch has:</p>
     * <ul>
     *   <li>startOffset &lt; endOffset</li>
     *   <li>Chunk IDs in document order</li>
     *   <li>All offsets are non-negative</li>
     *   <li>All chunks have contiguous write log offsets</li>
     * </ul>
     *
     * @param documentId The document ID to commit (must not be null)
     * @return AtomicDocumentBatch containing offset range and chunk IDs
     * @throws NullPointerException if documentId is null
     * @throws IllegalStateException if no chunks are buffered for the document
     * @throws IOException if writing to the write log fails
     */
    public AtomicDocumentBatch commitDocument(String documentId) throws IOException {
        Objects.requireNonNull(documentId, "documentId must not be null");

        commitLock.lock();
        try {
            DocumentBuffer buffer = lockExistingBuffer(documentId);
            if (buffer == null) {
                throw new IllegalStateException("No chunks buffered for document: " + documentId);
            }
            try {
                if (buffer.chunks.isEmpty()) {
                    throw new IllegalStateException("No chunks buffered for document: " + documentId);
                }

                AtomicDocumentBatch batch = appendRun(documentId, buffer.chunks, null);

                buffer.chunks.clear();
                documentBuffers.remove(documentId);

                LOGGER.debug("Committed document {} atomically with {} chunks at contiguous offsets [{}, {})",
                    documentId, batch.getChunkCount(), batch.getStartOffset(), batch.getEndOffset());

                return batch;
            } finally {
                buffer.lock.unlock();
            }
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * Commits a document with pre-computed embeddings.
     *
     * <p>When embeddings are available, they are stored alongside the chunk text
     * in a combined format. The value format is:</p>
     * <pre>
     *   [4 bytes: embedding length][embedding bytes][chunk text]
     * </pre>
     *
     * <p><strong>Atomicity guarantee:</strong> All chunks are written with contiguous
     * offsets using atomic batch append. No interleaving from other writers.</p>
     *
     * @param documentId The document ID to commit
     * @param embeddings Map of chunk index to embedding vector
     * @return AtomicDocumentBatch containing offset range and chunk IDs
     * @throws NullPointerException if any parameter is null
     * @throws IllegalStateException if no chunks are buffered or embedding missing
     * @throws IOException if writing to the write log fails
     */
    public AtomicDocumentBatch commitDocumentWithEmbeddings(
            String documentId,
            Map<Integer, float[]> embeddings) throws IOException {
        Objects.requireNonNull(documentId, "documentId must not be null");

        commitLock.lock();
        try {
            DocumentBuffer buffer = lockExistingBuffer(documentId);
            if (buffer == null) {
                throw new IllegalStateException("No chunks buffered for document: " + documentId);
            }
            try {
                if (buffer.chunks.isEmpty()) {
                    throw new IllegalStateException("No chunks buffered for document: " + documentId);
                }

                AtomicDocumentBatch batch = appendRun(documentId, buffer.chunks, embeddings);

                buffer.chunks.clear();
                documentBuffers.remove(documentId);

                LOGGER.debug("Committed document {} atomically with {} chunks and embeddings at contiguous offsets [{}, {})",
                    documentId, batch.getChunkCount(), batch.getStartOffset(), batch.getEndOffset());

                return batch;
            } finally {
                buffer.lock.unlock();
            }
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * Appends one document's chunks to the write log as a contiguous run.
     *
     * <p>The write log serializes and fsyncs individual appends; it has no batch primitive.
     * Holding {@link #commitLock} across the whole run is therefore what makes the offsets
     * contiguous, and it is why a buffer must own the log it is given rather than share it with
     * another appender.</p>
     *
     * <p>Every value is encoded before the first append. A missing embedding discovered halfway
     * through would otherwise leave the document half-written, with the failed commit reporting
     * nothing to roll back.</p>
     *
     * @param documentId The document key the chunks belong to
     * @param chunks     The buffered chunks, in document order
     * @param embeddings Chunk index to embedding, or null to store the chunk text alone
     * @return The batch describing the appended run
     * @throws IllegalStateException if an embedding is required but absent
     * @throws IOException           if an append fails
     */
    private AtomicDocumentBatch appendRun(String documentId, List<TextChunk> chunks,
                                          Map<Integer, float[]> embeddings) throws IOException {
        List<String> chunkIds = new ArrayList<>(chunks.size());
        List<byte[]> values = new ArrayList<>(chunks.size());

        for (TextChunk chunk : chunks) {
            chunkIds.add(makeChunkId(documentId, chunk.getChunkIndex()));
            values.add(encodeValue(documentId, chunk, embeddings));
        }

        List<Long> entryIds = new ArrayList<>(chunks.size());
        long startOffset = writeLog.writeOffset();
        for (int i = 0; i < chunkIds.size(); i++) {
            byte[] key = chunkIds.get(i).getBytes(StandardCharsets.UTF_8);
            entryIds.add(writeLog.appendPut(key, values.get(i)));
        }
        long endOffset = writeLog.writeOffset();

        return new AtomicDocumentBatch(
            documentId,
            startOffset,
            endOffset,
            Collections.unmodifiableList(chunkIds),
            Collections.unmodifiableList(entryIds));
    }

    /**
     * Encodes the log value for one chunk.
     *
     * @param documentId The owning document, for the error message
     * @param chunk      The chunk to encode
     * @param embeddings Chunk index to embedding, or null to store the chunk text alone
     * @return The value bytes
     * @throws IllegalStateException if embeddings were supplied but this chunk has none
     */
    private static byte[] encodeValue(String documentId, TextChunk chunk,
                                      Map<Integer, float[]> embeddings) {
        if (embeddings == null) {
            // Codec form with dimension 0, not bare UTF-8. The tail scanner and the relevancy
            // scorer read every write-log value through ChunkPayloadCodec, so a value written
            // without the header is not a chunk with no vector to them -- it is a value they
            // cannot parse at all, and the text is lost rather than merely unembedded.
            return ChunkPayloadCodec.encodeTextOnly(chunk.getText());
        }

        float[] embedding = embeddings.get(chunk.getChunkIndex());
        if (embedding == null) {
            throw new IllegalStateException(
                "Missing embedding for chunk " + chunk.getChunkIndex() + " of document " + documentId);
        }
        return encodeChunkWithTextAndEmbedding(chunk.getText(), embedding);
    }

    /**
     * Aborts buffering for a document, discarding all buffered chunks.
     *
     * <p>This should be called when an error occurs during chunking or embedding
     * to release the buffered memory without writing to the log.</p>
     *
     * @param documentId The document ID to abort
     * @return The number of chunks that were discarded
     * @throws NullPointerException if documentId is null
     */
    public int abortDocument(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");

        DocumentBuffer buffer = lockExistingBuffer(documentId);
        if (buffer == null) {
            return 0;
        }

        try {
            documentBuffers.remove(documentId, buffer);
            int discarded = buffer.chunks.size();
            buffer.chunks.clear();
            LOGGER.debug("Aborted document {} with {} buffered chunks", documentId, discarded);
            return discarded;
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Locks the buffer currently mapped for a document, or returns null if there is none.
     *
     * <p>Like {@link #acquireBuffer(String)} this re-checks the map after taking the lock, so the
     * caller never operates on a buffer that a concurrent commit or abort has already retired.</p>
     *
     * @param documentId The document whose buffer is wanted
     * @return The live buffer with its lock held, or null if the document has no buffer
     */
    private DocumentBuffer lockExistingBuffer(String documentId) {
        while (true) {
            DocumentBuffer buffer = documentBuffers.get(documentId);
            if (buffer == null) {
                return null;
            }
            buffer.lock.lock();
            if (documentBuffers.get(documentId) == buffer) {
                return buffer;
            }
            buffer.lock.unlock();
        }
    }

    /**
     * Gets the number of buffered chunks for a document.
     *
     * @param documentId The document ID
     * @return Number of buffered chunks, or 0 if document not found
     */
    public int getBufferedChunkCount(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");

        DocumentBuffer buffer = documentBuffers.get(documentId);
        if (buffer == null) {
            return 0;
        }

        buffer.lock.lock();
        try {
            return buffer.chunks.size();
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Gets the total number of buffered chunks across all documents.
     *
     * @return Total buffered chunk count
     */
    public int getTotalBufferedChunks() {
        int total = 0;
        for (DocumentBuffer buffer : documentBuffers.values()) {
            buffer.lock.lock();
            try {
                total += buffer.chunks.size();
            } finally {
                buffer.lock.unlock();
            }
        }
        return total;
    }

    /**
     * Gets the number of documents currently being buffered.
     *
     * @return Document count
     */
    public int getBufferedDocumentCount() {
        return documentBuffers.size();
    }

    /**
     * Checks if a document has buffered chunks.
     *
     * @param documentId The document ID
     * @return true if chunks are buffered for this document
     */
    public boolean hasBufferedChunks(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");

        DocumentBuffer buffer = documentBuffers.get(documentId);
        if (buffer == null) {
            return false;
        }

        buffer.lock.lock();
        try {
            return !buffer.chunks.isEmpty();
        } finally {
            buffer.lock.unlock();
        }
    }

    /**
     * Clears all buffered chunks for all documents.
     *
     * <p>This is typically only used during shutdown or error recovery.</p>
     */
    public void clear() {
        for (DocumentBuffer buffer : documentBuffers.values()) {
            buffer.lock.lock();
            try {
                buffer.chunks.clear();
            } finally {
                buffer.lock.unlock();
            }
        }
        documentBuffers.clear();
        LOGGER.debug("Cleared all buffered chunks");
    }

    /**
     * Creates a chunk ID from document ID and chunk index.
     *
     * <p>Uses the module-wide convention so that the ids recorded on an
     * {@link AtomicDocumentBatch} are byte-for-byte the storage keys the chunks were written
     * under, and so the owning document can be recovered from any of them.</p>
     */
    private static String makeChunkId(String documentId, int chunkIndex) {
        return ChunkIdentifiers.chunkIdForDocumentKey(documentId, chunkIndex);
    }

    /**
     * Decodes an embedding vector from an encoded chunk value.
     *
     * <p>This method extracts the embedding vector from the value format:
     * [dimension:4][vector:dim*4][text:variable]</p>
     *
     * <p><strong>Byte Order:</strong> Uses LITTLE_ENDIAN to match the encoding format.</p>
     *
     * @param value The encoded value bytes
     * @param expectedDimension The expected vector dimension for validation
     * @return The embedding vector, or null if decoding fails or dimension mismatches
     */
    public static float[] decodeEmbedding(byte[] value, int expectedDimension) {
        return ChunkPayloadCodec.embeddingOf(value, expectedDimension);
    }

    /**
     * Decodes the chunk text from an encoded chunk value.
     *
     * <p>This method extracts the text portion from the value format:
     * [dimension:4][vector:dim*4][text:variable]</p>
     *
     * <p><strong>Byte Order:</strong> Uses LITTLE_ENDIAN to read the dimension header.</p>
     *
     * @param value The encoded value bytes
     * @return The chunk text, or null if decoding fails
     */
    public static String decodeText(byte[] value) {
        return ChunkPayloadCodec.textOf(value);
    }

    /**
     * Gets the expected encoded size for a chunk with embedding.
     *
     * @param textLength The text length in bytes
     * @param dimension The embedding dimension
     * @return The total encoded size in bytes
     */
    public static int getEncodedSize(int textLength, int dimension) {
        return ChunkPayloadCodec.HEADER_BYTES + dimension * Float.BYTES + textLength;
    }

    /**
     * Encodes chunk text with embedding into a byte array.
     *
     * <p>This is the single encoder used by every ingest write path, and is the exact inverse of
     * {@link #decodeEmbedding(byte[], int)} and {@link #decodeText(byte[])}.</p>
     *
     * <p>Format (LITTLE-ENDIAN): [dimension:4][vector:dim*4][text:variable]</p>
     *
     * <p><strong>Byte Order:</strong> LITTLE_ENDIAN for both the dimension integer and the
     * embedding floats, matching the format the TailScanner and HnswIntegrationWorker read.</p>
     *
     * @param text The chunk text to encode
     * @param embedding The embedding vector
     * @return Encoded byte array
     * @throws NullPointerException if text or embedding is null
     */
    public static byte[] encodeChunkWithTextAndEmbedding(String text, float[] embedding) {
        return ChunkPayloadCodec.encode(text, embedding);
    }

    /**
     * Encodes only the embedding vector without text.
     *
     * <p>This method is useful for tests and scenarios where only the vector
     * needs to be stored without accompanying text.</p>
     *
     * <p>Format (LITTLE-ENDIAN): [dimension:4][vector:dim*4]</p>
     *
     * @param embedding The embedding vector to encode
     * @return Encoded byte array containing only the vector
     * @throws NullPointerException if embedding is null
     */
    public static byte[] encodeVectorOnly(float[] embedding) {
        return ChunkPayloadCodec.encode(embedding);
    }

    /**
     * Per-document buffer for holding chunks before commit.
     */
    private static class DocumentBuffer {
        final List<TextChunk> chunks = new ArrayList<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    /**
     * Returns the write log used by this buffer.
     *
     * @return The write log
     */
    public WriteLog getWriteLog() {
        return writeLog;
    }

    /**
     * Returns the maximum chunks per document limit.
     *
     * @return Maximum chunk limit
     */
    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }
}
