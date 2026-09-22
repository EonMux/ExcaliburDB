package com.eonmux.excalibur.ingest.pipeline;

/**
 * Storage writer that returns write log offsets for atomic batch publication.
 *
 * <p>This interface extends the basic {@link DocumentReindexer.StorageWriter} to
 * return the write log offset after each write. The offset is required for
 * {@link DocumentBatchBuffer} to track offsets for atomic publication via
 * {@link DocumentBatchPublisher}.</p>
 *
 * <h2>Write Log Offset</h2>
 * <p>The offset returned by {@link #writeChunkWithOffset(String, String, float[])}
 * represents the position in the write log where the chunk was written. This offset
 * is used to:</p>
 * <ul>
 *   <li>Track which chunks belong to a single document batch</li>
 *   <li>Enable tail scanning for entries not yet integrated into HNSW</li>
 *   <li>Support crash recovery by replaying from specific offsets</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Returned offset must be non-negative</li>
 *   <li>Offsets must be monotonically increasing within a segment</li>
 *   <li>Each chunk gets a unique offset</li>
 *   <li>After successful write, offset is immediately queryable</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Writes from concurrent threads must
 * return unique, monotonically increasing offsets.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 * @see DocumentBatchBuffer
 * @see DocumentBatchPublisher
 */
@FunctionalInterface
public interface OffsetAwareStorageWriter {

    /**
     * Writes a chunk with its embedding to storage and returns the write log offset.
     *
     * <p>This method writes the chunk data to the underlying storage and returns
     * the offset in the write log where the entry was recorded. The offset is
     * required for atomic batch publication.</p>
     *
     * @param chunkId Unique chunk identifier (format: "{documentId}:chunk:{index}")
     * @param content Chunk text content (UTF-8 encoded)
     * @param embedding Embedding vector (dimension matches configured model)
     * @return The write log offset where this chunk was written, or -1 if write failed
     * @throws NullPointerException if chunkId or content is null
     * @throws IllegalArgumentException if embedding dimension doesn't match configuration
     */
    long writeChunkWithOffset(String chunkId, String content, float[] embedding);

    /**
     * Writes a chunk with its embedding to a specific segment and returns the write log offset.
     *
     * <p>This method is used during rolling replacement reindexing to write data
     * to a newly created segment. The segmentId parameter allows routing writes
     * to the correct segment during reindexing operations.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>segmentId must be a valid, existing segment ID</li>
     *   <li>Returned offset must be non-negative</li>
     *   <li>Offsets must be monotonically increasing within a segment</li>
     * </ul>
     *
     * @param segmentId The target segment ID for this write
     * @param chunkId Unique chunk identifier (format: "{documentId}:chunk:{index}")
     * @param content Chunk text content (UTF-8 encoded)
     * @param embedding Embedding vector (dimension matches configured model)
     * @return The write log offset where this chunk was written, or -1 if write failed
     * @throws NullPointerException if chunkId or content is null
     * @throws IllegalArgumentException if segmentId is invalid or embedding dimension doesn't match
     */
    default long writeChunkWithOffset(long segmentId, String chunkId, String content, float[] embedding) {
        // Default implementation delegates to the segment-agnostic method
        // Implementations that support segment targeting should override this
        return writeChunkWithOffset(chunkId, content, embedding);
    }

    /**
     * Creates a segment-targeted view of this writer.
     *
     * <p>This method returns a new OffsetAwareStorageWriter that routes all writes
     * to the specified segment. This is used during rolling replacement reindexing
     * to ensure data is written to the newly created segment.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * OffsetAwareStorageWriter segmentWriter = baseWriter.forSegment(newSegmentId);
     * // All writes via segmentWriter go to newSegmentId
     * }</pre>
     *
     * @param segmentId The target segment ID for all writes
     * @return A new OffsetAwareStorageWriter that writes to the specified segment
     */
    /**
     * Whether writes targeted at a segment can actually reach it.
     *
     * <p>A segment ID is not on its own a promise that a segment exists to receive writes: the
     * catalog can mint metadata for one the storage engine has no routing slot for, and every
     * write aimed at it then fails one chunk at a time. Callers that are about to do expensive
     * work per chunk - fetching, chunking and embedding - should ask first, so the answer costs
     * one lookup rather than one embedding call per chunk of every document in the source.</p>
     *
     * <p>The default is {@code true}, for writers that do not route by segment at all.</p>
     *
     * @param segmentId The segment writes would be targeted at
     * @return true if a write to that segment can succeed
     */
    default boolean canWriteTo(long segmentId) {
        return true;
    }

    default OffsetAwareStorageWriter forSegment(long segmentId) {
        final OffsetAwareStorageWriter delegate = this;
        return new OffsetAwareStorageWriter() {
            @Override
            public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
                return delegate.writeChunkWithOffset(segmentId, chunkId, content, embedding);
            }

            @Override
            public long writeChunkWithOffset(long segId, String chunkId, String content, float[] embedding) {
                // Ignore the provided segId and always use the bound segmentId
                return delegate.writeChunkWithOffset(segmentId, chunkId, content, embedding);
            }

            @Override
            public OffsetAwareStorageWriter forSegment(long newSegmentId) {
                // Create a new binding for the new segment
                return delegate.forSegment(newSegmentId);
            }

            @Override
            public boolean canWriteTo(long ignoredSegmentId) {
                // Every write through this view goes to the bound segment, whatever it is asked
                // about, so that is the only segment whose reachability means anything here.
                return delegate.canWriteTo(segmentId);
            }
        };
    }

    /**
     * Adapts a basic StorageWriter to OffsetAwareStorageWriter using a counter.
     *
     * <p><strong>WARNING:</strong> This adapter uses an atomic counter to simulate
     * offsets. It does NOT provide real write log offsets and should only be used
     * for testing or when the underlying storage doesn't support offset tracking.</p>
     *
     * @param delegate The basic storage writer to adapt
     * @return An OffsetAwareStorageWriter that returns sequential counter values
     */
    static OffsetAwareStorageWriter fromBasicWriter(DocumentReindexer.StorageWriter delegate) {
        final java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong(0);

        return (chunkId, content, embedding) -> {
            boolean success = delegate.writeChunk(chunkId, content, embedding);
            if (success) {
                return counter.getAndIncrement();
            }
            return -1L;
        };
    }
}
