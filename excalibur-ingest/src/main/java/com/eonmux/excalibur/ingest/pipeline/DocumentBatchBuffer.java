package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Buffers chunks for a single document before atomic publication to visibility epoch.
 *
 * <h2>Purpose</h2>
 * <p>This class accumulates chunks and their write log offsets for a single document.
 * All chunks for a document are written to the write log first, then published
 * atomically to ensure queries never see partially-ingested documents.</p>
 *
 * <h2>Atomic Visibility Contract</h2>
 * <p>A document's chunks become visible to queries only when:
 * <ul>
 *   <li>All chunks have been successfully written to the write log</li>
 *   <li>The batch is explicitly published via {@link DocumentBatchPublisher}</li>
 *   <li>The visibility epoch advances after publication</li>
 * </ul>
 *
 * <h2>Write Log Offset Tracking</h2>
 * <p>Each chunk is tracked with:
 * <ul>
 *   <li>Chunk ID - unique identifier for the chunk</li>
 *   <li>Write log offset - position in the write log where the chunk was written</li>
 *   <li>Embedding vector - the vector representation (may be null if not yet embedded)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. Each buffer instance is intended to be used
 * by a single thread during the ingestion of a single document.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Document ID: non-null, non-empty string identifying the source document</li>
 *   <li>Source ID: non-null, non-empty string identifying the ingestion source</li>
 *   <li>Chunk offsets: contiguous, monotonically increasing write log offsets</li>
 *   <li>Buffer state: OPEN for accumulation, SEALED after complete, PUBLISHED after atomic commit</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public final class DocumentBatchBuffer {

    /**
     * Buffer state lifecycle.
     */
    public enum State {
        OPEN,
        SEALED,
        PUBLISHED,
        FAILED
    }

    private final String documentId;
    private final String sourceId;
    private final List<ChunkEntry> entries;
    private State state;
    private long startOffset;
    private long endOffset;
    private Throwable failureCause;

    /**
     * Creates a new buffer for accumulating document chunks.
     *
     * @param documentId The document ID being buffered
     * @param sourceId The source ID for this document
     * @throws NullPointerException if documentId or sourceId is null
     * @throws IllegalArgumentException if documentId or sourceId is empty
     */
    public DocumentBatchBuffer(String documentId, String sourceId) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must be non-null and non-empty");
        }
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("sourceId must be non-null and non-empty");
        }
        this.documentId = documentId;
        this.sourceId = sourceId;
        this.entries = new ArrayList<>();
        this.state = State.OPEN;
        this.startOffset = -1;
        this.endOffset = -1;
    }

    /**
     * Adds a chunk entry to the buffer.
     *
     * <p>The chunk must have been written to the write log before being added.
     * The write log offset is tracked to ensure contiguous offsets for atomicity.</p>
     *
     * @param chunkId The unique chunk identifier
     * @param textChunk The original text chunk
     * @param writeLogOffset The offset in the write log where this chunk was written
     * @param embedding The embedding vector (may be null if not yet embedded)
     * @throws IllegalStateException if buffer is not in OPEN state
     * @throws NullPointerException if chunkId or textChunk is null
     * @throws IllegalArgumentException if writeLogOffset is negative
     */
    public void addChunk(String chunkId, TextChunk textChunk, long writeLogOffset, float[] embedding) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(textChunk, "textChunk must not be null");
        if (state != State.OPEN) {
            throw new IllegalStateException("Cannot add chunks to buffer in state: " + state);
        }
        if (writeLogOffset < 0) {
            throw new IllegalArgumentException("writeLogOffset must be non-negative: " + writeLogOffset);
        }

        ChunkEntry entry = new ChunkEntry(chunkId, textChunk, writeLogOffset, embedding);
        entries.add(entry);

        if (startOffset < 0 || writeLogOffset < startOffset) {
            startOffset = writeLogOffset;
        }
        if (writeLogOffset > endOffset) {
            endOffset = writeLogOffset;
        }
    }

    /**
     * Adds a chunk entry without an embedding.
     *
     * @param chunkId The unique chunk identifier
     * @param textChunk The original text chunk
     * @param writeLogOffset The offset in the write log
     */
    public void addChunk(String chunkId, TextChunk textChunk, long writeLogOffset) {
        addChunk(chunkId, textChunk, writeLogOffset, null);
    }

    /**
     * Seals the buffer, preventing further additions.
     *
     * <p>After sealing, the buffer can be published atomically.</p>
     *
     * @throws IllegalStateException if buffer is not in OPEN state
     */
    public void seal() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Cannot seal buffer in state: " + state);
        }
        this.state = State.SEALED;
    }

    /**
     * Marks the buffer as published.
     *
     * <p>This should only be called by {@link DocumentBatchPublisher} after
     * successfully committing all chunks to the visibility epoch.</p>
     *
     * @throws IllegalStateException if buffer is not in SEALED state
     */
    public void markPublished() {
        if (state != State.SEALED) {
            throw new IllegalStateException("Cannot publish buffer in state: " + state);
        }
        this.state = State.PUBLISHED;
    }

    /**
     * Marks the buffer as failed.
     *
     * @param cause The failure cause
     */
    public void markFailed(Throwable cause) {
        this.state = State.FAILED;
        this.failureCause = cause;
    }

    /**
     * Returns the document ID.
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Returns the source ID.
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * Returns all chunk entries in the buffer.
     *
     * @return Immutable list of chunk entries
     */
    public List<ChunkEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns the number of chunks in the buffer.
     */
    public int getChunkCount() {
        return entries.size();
    }

    /**
     * Returns true if the buffer is empty.
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns the current state.
     */
    public State getState() {
        return state;
    }

    /**
     * Returns true if the buffer is open for additions.
     */
    public boolean isOpen() {
        return state == State.OPEN;
    }

    /**
     * Returns true if the buffer is sealed and ready for publication.
     */
    public boolean isSealed() {
        return state == State.SEALED;
    }

    /**
     * Returns true if the buffer has been published.
     */
    public boolean isPublished() {
        return state == State.PUBLISHED;
    }

    /**
     * Returns true if the buffer failed.
     */
    public boolean isFailed() {
        return state == State.FAILED;
    }

    /**
     * Returns the failure cause, if any.
     */
    public Throwable getFailureCause() {
        return failureCause;
    }

    /**
     * Returns the start offset in the write log.
     *
     * <p>This is the offset of the first chunk written for this document.</p>
     *
     * @return Start offset, or -1 if no chunks added
     */
    public long getStartOffset() {
        return startOffset;
    }

    /**
     * Returns the end offset in the write log.
     *
     * <p>This is the offset of the last chunk written for this document.</p>
     *
     * @return End offset, or -1 if no chunks added
     */
    public long getEndOffset() {
        return endOffset;
    }

    /**
     * Returns the offset range for all chunks in this buffer.
     *
     * <p>Domain constraint: {@code startOffset <= endOffset}, both non-negative if chunks exist.</p>
     *
     * @return Array of [startOffset, endOffset], or null if no chunks
     */
    public long[] getOffsetRange() {
        if (isEmpty()) {
            return null;
        }
        return new long[]{startOffset, endOffset};
    }

    /**
     * Returns all chunk IDs in this buffer.
     */
    public List<String> getChunkIds() {
        return entries.stream()
            .map(ChunkEntry::chunkId)
            .toList();
    }

    @Override
    public String toString() {
        return String.format("DocumentBatchBuffer[documentId=%s, sourceId=%s, chunks=%d, state=%s, offsets=[%d,%d]]",
            documentId, sourceId, entries.size(), state, startOffset, endOffset);
    }

    /**
     * Represents a single chunk entry in the buffer.
     *
     * @param chunkId Unique chunk identifier
     * @param textChunk The original text chunk
     * @param writeLogOffset Offset in the write log where this chunk was written
     * @param embedding The embedding vector (may be null)
     * @param entryId The unique entry ID for LSH indexing (defaults to -1 if not set)
     */
    public record ChunkEntry(
        String chunkId,
        TextChunk textChunk,
        long writeLogOffset,
        float[] embedding,
        long entryId
    ) {
        public ChunkEntry {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(textChunk, "textChunk must not be null");
            if (writeLogOffset < 0) {
                throw new IllegalArgumentException("writeLogOffset must be non-negative");
            }
        }

        /**
         * Compact constructor for backward compatibility (entryId defaults to -1).
         */
        public ChunkEntry(String chunkId, TextChunk textChunk, long writeLogOffset, float[] embedding) {
            this(chunkId, textChunk, writeLogOffset, embedding, -1);
        }

        public boolean hasEmbedding() {
            return embedding != null && embedding.length > 0;
        }

        public int getEmbeddingDimension() {
            return embedding != null ? embedding.length : 0;
        }

        /**
         * Returns true if this entry has a valid entry ID for LSH indexing.
         */
        public boolean hasEntryId() {
            return entryId >= 0;
        }

        /**
         * Creates a copy of this entry with a different entry ID.
         */
        public ChunkEntry withEntryId(long newEntryId) {
            return new ChunkEntry(chunkId, textChunk, writeLogOffset, embedding, newEntryId);
        }
    }
}
