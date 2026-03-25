package excalibur.ingest.buffer;

import excalibur.ingest.processor.TextChunk;
import io.excalibur.store.log.WriteLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * <p>Chunks are written to the write log with the following format:</p>
 * <pre>
 *   key: documentId:chunkIndex
 *   value: chunk text content
 * </pre>
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

    private static final String CHUNK_ID_SEPARATOR = ":";

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
        DocumentBuffer buffer = documentBuffers.computeIfAbsent(documentId, k -> new DocumentBuffer());

        buffer.lock.lock();
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
        DocumentBuffer buffer = documentBuffers.computeIfAbsent(documentId, k -> new DocumentBuffer());

        buffer.lock.lock();
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

        DocumentBuffer buffer = documentBuffers.get(documentId);
        if (buffer == null || buffer.chunks.isEmpty()) {
            throw new IllegalStateException("No chunks buffered for document: " + documentId);
        }

        commitLock.lock();
        try {
            buffer.lock.lock();
            try {
                List<WriteLog.BatchEntry> batchEntries = new ArrayList<>(buffer.chunks.size());
                List<String> chunkIds = new ArrayList<>(buffer.chunks.size());

                for (TextChunk chunk : buffer.chunks) {
                    String chunkId = makeChunkId(documentId, chunk.getChunkIndex());
                    byte[] key = chunkId.getBytes(StandardCharsets.UTF_8);
                    byte[] value = chunk.getText().getBytes(StandardCharsets.UTF_8);

                    batchEntries.add(WriteLog.BatchEntry.put(key, value));
                    chunkIds.add(chunkId);
                }

                WriteLog.BatchAppendResult result = writeLog.appendBatch(batchEntries);

                AtomicDocumentBatch batch = new AtomicDocumentBatch(
                    documentId,
                    result.startOffset(),
                    result.endOffset(),
                    Collections.unmodifiableList(chunkIds),
                    result.entryIds()
                );

                buffer.chunks.clear();
                documentBuffers.remove(documentId);

                LOGGER.debug("Committed document {} atomically with {} chunks at contiguous offsets [{}, {})",
                    documentId, chunkIds.size(), result.startOffset(), result.endOffset());

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
        Objects.requireNonNull(embeddings, "embeddings must not be null");

        DocumentBuffer buffer = documentBuffers.get(documentId);
        if (buffer == null || buffer.chunks.isEmpty()) {
            throw new IllegalStateException("No chunks buffered for document: " + documentId);
        }

        commitLock.lock();
        try {
            buffer.lock.lock();
            try {
                List<WriteLog.BatchEntry> batchEntries = new ArrayList<>(buffer.chunks.size());
                List<String> chunkIds = new ArrayList<>(buffer.chunks.size());

                for (TextChunk chunk : buffer.chunks) {
                    int chunkIndex = chunk.getChunkIndex();
                    float[] embedding = embeddings.get(chunkIndex);
                    if (embedding == null) {
                        throw new IllegalStateException(
                            "Missing embedding for chunk " + chunkIndex + " of document " + documentId);
                    }

                    String chunkId = makeChunkId(documentId, chunkIndex);
                    byte[] key = chunkId.getBytes(StandardCharsets.UTF_8);
                    byte[] value = encodeChunkWithEmbedding(chunk.getText(), embedding);

                    batchEntries.add(WriteLog.BatchEntry.put(key, value));
                    chunkIds.add(chunkId);
                }

                WriteLog.BatchAppendResult result = writeLog.appendBatch(batchEntries);

                AtomicDocumentBatch batch = new AtomicDocumentBatch(
                    documentId,
                    result.startOffset(),
                    result.endOffset(),
                    Collections.unmodifiableList(chunkIds),
                    result.entryIds()
                );

                buffer.chunks.clear();
                documentBuffers.remove(documentId);

                LOGGER.debug("Committed document {} atomically with {} chunks and embeddings at contiguous offsets [{}, {})",
                    documentId, chunkIds.size(), result.startOffset(), result.endOffset());

                return batch;
            } finally {
                buffer.lock.unlock();
            }
        } finally {
            commitLock.unlock();
        }
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

        DocumentBuffer buffer = documentBuffers.remove(documentId);
        if (buffer == null) {
            return 0;
        }

        buffer.lock.lock();
        try {
            int discarded = buffer.chunks.size();
            buffer.chunks.clear();
            LOGGER.debug("Aborted document {} with {} buffered chunks", documentId, discarded);
            return discarded;
        } finally {
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
     */
    private static String makeChunkId(String documentId, int chunkIndex) {
        return documentId + CHUNK_ID_SEPARATOR + chunkIndex;
    }

    /**
     * Encodes chunk text with embedding into a byte array.
     *
     * <p>Format (LITTLE-ENDIAN): [4 bytes: dimension][dimension * 4 bytes: embedding floats][text bytes]</p>
     *
     * <p><strong>Byte Order:</strong> This method uses LITTLE_ENDIAN byte order for both
     * the dimension integer and the embedding floats. This matches the TailScanner's
     * expected format and ensures consistency between ingestion and query pipelines.</p>
     *
     * @param text The chunk text to encode
     * @param embedding The embedding vector
     * @return Encoded byte array
     */
    private static byte[] encodeChunkWithEmbedding(String text, float[] embedding) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Use ByteBuffer with LITTLE_ENDIAN to match TailScanner format
        // Format: [dimension:4][vector:dim*4][text:variable]
        ByteBuffer buffer = ByteBuffer.allocate(4 + embedding.length * Float.BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(embedding.length);  // dimension as little-endian
        for (float f : embedding) {
            buffer.putFloat(f);  // floats as little-endian
        }
        buffer.put(textBytes);

        return buffer.array();
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
        if (value == null || value.length < 4) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();

            // Validate dimension
            if (dimension != expectedDimension) {
                return null;
            }

            int expectedBytes = 4 + dimension * Float.BYTES;
            if (value.length < expectedBytes) {
                return null;
            }

            float[] embedding = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                embedding[i] = buffer.getFloat();
            }

            return embedding;

        } catch (Exception e) {
            return null;
        }
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
        if (value == null || value.length < 4) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();

            int vectorEndOffset = 4 + dimension * Float.BYTES;
            if (value.length < vectorEndOffset) {
                return null;
            }

            // Extract text bytes (everything after the vector)
            byte[] textBytes = new byte[value.length - vectorEndOffset];
            System.arraycopy(value, vectorEndOffset, textBytes, 0, textBytes.length);

            return new String(textBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the expected encoded size for a chunk with embedding.
     *
     * @param textLength The text length in bytes
     * @param dimension The embedding dimension
     * @return The total encoded size in bytes
     */
    public static int getEncodedSize(int textLength, int dimension) {
        return 4 + dimension * Float.BYTES + textLength;
    }

    /**
     * Encodes chunk text with embedding into a byte array.
     *
     * <p>This is a public version of the encoding method for use by tests and
     * other components that need to encode data in the same format as the
     * ingestion pipeline.</p>
     *
     * <p>Format (LITTLE-ENDIAN): [dimension:4][vector:dim*4][text:variable]</p>
     *
     * @param text The chunk text to encode
     * @param embedding The embedding vector
     * @return Encoded byte array
     * @throws NullPointerException if text or embedding is null
     */
    public static byte[] encodeChunkWithTextAndEmbedding(String text, float[] embedding) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(embedding, "embedding must not be null");

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Use ByteBuffer with LITTLE_ENDIAN to match TailScanner format
        ByteBuffer buffer = ByteBuffer.allocate(4 + embedding.length * Float.BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(embedding.length);
        for (float f : embedding) {
            buffer.putFloat(f);
        }
        buffer.put(textBytes);

        return buffer.array();
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
        Objects.requireNonNull(embedding, "embedding must not be null");

        ByteBuffer buffer = ByteBuffer.allocate(4 + embedding.length * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(embedding.length);
        for (float f : embedding) {
            buffer.putFloat(f);
        }

        return buffer.array();
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
