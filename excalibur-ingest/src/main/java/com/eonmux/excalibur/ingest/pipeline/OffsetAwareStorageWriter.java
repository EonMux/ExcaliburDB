package com.eonmux.excalibur.ingest.pipeline;

import java.util.List;

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
     * Writes a chunk the caller has already tokenised, and reports where it landed.
     *
     * <p>The storage engine counts a chunk's terms into its segment's frequency sketch, and the
     * caller files the same terms in the membership filter under the segment the write landed
     * in. Handing them over here is what lets the text be tokenised once per chunk rather than
     * once for each, and reporting the segment is what lets the caller file them where the chunk
     * is: a hash-routed engine picks the segment, and nothing outside it can say afterwards which
     * one it picked.</p>
     *
     * <p>The default is for writers that route by no segment: the terms are ignored and the
     * placement carries no segment, so nothing is filed in the membership filter.</p>
     *
     * @param chunkId   Unique chunk identifier
     * @param content   Chunk text content
     * @param terms     {@link com.eonmux.excalibur.core.text.TermExtractor#extractTerms(String)}
     *                  of {@code content}, with repetition
     * @param embedding Embedding vector, or null on a node that embeds nothing
     * @return Where the chunk landed, or {@link ChunkPlacement#FAILED}
     */
    default ChunkPlacement writeChunk(String chunkId, String content, List<String> terms,
                                      float[] embedding) {
        return ChunkPlacement.unrouted(writeChunkWithOffset(chunkId, content, embedding));
    }

    /**
     * Whether a segment ID names a segment of the storage engine's own, one writes could land in.
     *
     * <p>A segment ID is not on its own a promise that a segment exists to receive writes: the
     * catalog can mint metadata for one the storage engine has no routing slot for. A rolling
     * replacement asks this of the segment it created before fetching, chunking and embedding
     * anything, so the answer costs one lookup rather than a crawl; {@link RollingReplacement}
     * sets out what it does with the answer.</p>
     *
     * <p>The default is {@code true}, for writers that do not route by segment at all.</p>
     *
     * @param segmentId The segment ID
     * @return true if the storage engine holds a segment of its own under that ID
     */
    default boolean canWriteTo(long segmentId) {
        return true;
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
