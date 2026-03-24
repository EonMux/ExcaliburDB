package excalibur.ingest.pipeline;

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
