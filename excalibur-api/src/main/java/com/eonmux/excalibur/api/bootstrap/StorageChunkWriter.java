package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.pipeline.OffsetAwareStorageWriter;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes reindexed chunks into the storage engine and reports each one's write log offset.
 *
 * <h2>Routing</h2>
 * <p>Every write goes through {@link StorageEngine#putReportingSegment}, so the chunk lands in
 * the segment its key hashes to and stays reachable by key lookup. Pinning every chunk to
 * segment 0 - which is what a "write to the first available segment" shortcut does - makes every
 * reindexed chunk unreachable by the routed read path.</p>
 *
 * <h2>Where a chunk landed</h2>
 * <p>The write log offset is read from the segment the engine reports took the write, never
 * one worked out here. The engine may split the target while the write is in flight and retry
 * into a child, so recomputing the route from the key after the write can name a segment the
 * chunk is not in, and an offset read from that segment belongs to another write log.</p>
 *
 * <h2>Value encoding</h2>
 * <p>{@code [4:dimension][dimension*4:embedding floats][text bytes]}, little-endian,
 * matching what the HNSW integration worker and the tail scanner expect.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe; the storage engine performs its own locking.</p>
 *
 * @author ExcaliburDB
 * @see OffsetAwareStorageWriter
 */
public final class StorageChunkWriter implements OffsetAwareStorageWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageChunkWriter.class);

    /** Returned when a write could not be performed. */
    public static final long WRITE_FAILED = -1L;

    private final StorageEngine storageEngine;

    /**
     * Monotone fallback offsets for segments that have no write log attached. The
     * batch publisher only needs offsets to be unique and increasing per segment.
     */
    private final AtomicLong fallbackOffsets = new AtomicLong();

    /**
     * Creates a writer over a storage engine.
     *
     * @param storageEngine The engine to write into
     */
    public StorageChunkWriter(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    /**
     * Creates the tombstone adapter the upsert handler uses to retire superseded chunks.
     *
     * <p>Deleting through the engine routes the key to the segment that owns it, which
     * is the only way a tombstone can reach the chunk written by a previous ingest.</p>
     *
     * @param storageEngine The engine to delete from
     * @return A storage adapter backed by the engine's routed delete
     */
    public static UpsertHandler.StorageAdapter tombstoneAdapter(StorageEngine storageEngine) {
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        return key -> {
            if (key == null) {
                return false;
            }
            try {
                return storageEngine.delete(key);
            } catch (RuntimeException e) {
                LOGGER.warn("Tombstone failed for chunk key: {}", e.getMessage());
                return false;
            }
        };
    }

    /**
     * Writes a chunk wherever the engine routes it.
     *
     * @return The write log offset of the segment that took the write, or
     *         {@link #WRITE_FAILED}
     */
    @Override
    public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
        byte[] key = keyOf(chunkId);
        byte[] value = encode(content, embedding);
        long segmentId;
        try {
            segmentId = storageEngine.putReportingSegment(key, value);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to write chunk {}: {}", chunkId, e.getMessage());
            return WRITE_FAILED;
        }
        return offsetOf(storageEngine.getSegment(segmentId));
    }

    /**
     * Whether the storage engine holds a segment of its own under this ID.
     *
     * <p>Segment IDs minted by {@link com.eonmux.excalibur.store.SegmentCatalog#create} start at
     * {@link com.eonmux.excalibur.store.SegmentCatalog#FIRST_CREATED_SEGMENT_ID}, above every index
     * an {@code int}-wide routing directory can hold, so a created entry never names a slot of the
     * directory, however far it has split, and is never a write target.</p>
     *
     * <p>An index inside the directory is not necessarily one either. Doubling the directory
     * fills its upper half with buddy pointers to the segments already in the lower half, so an
     * index can resolve to a live segment that is filed under a lower index and routes other
     * keys. Asking whether the ID resolves cannot tell the two apart; asking whether the engine
     * holds a distinct segment <em>under that ID</em> can, and that is the question a caller
     * wanting a segment to fill is really asking.</p>
     *
     * @param segmentId The segment ID
     * @return true if the engine holds a segment of its own at that index
     */
    @Override
    public boolean canWriteTo(long segmentId) {
        return storageEngine.getSegments().containsKey(segmentId);
    }

    private long offsetOf(Segment segment) {
        if (segment == null) {
            return fallbackOffsets.getAndIncrement();
        }
        var writeLog = segment.writeLog();
        if (writeLog == null) {
            return fallbackOffsets.getAndIncrement();
        }
        return writeLog.writeOffset();
    }

    private static byte[] keyOf(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId cannot be null");
        return chunkId.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a chunk into the layout the HNSW integration worker and tail scanner read.
     *
     * <p>Delegated so that the reindex path and the ingest path cannot write two different
     * layouts into the same storage keys.
     *
     * <p>A chunk with no vector is a normal chunk, not a malformed one: a node with no embedding
     * endpoint chunks, stores and term-indexes its corpus and never embeds any of it, and
     * ingestion already writes those chunks with the layout's dimension-0 form. A reindex of
     * that corpus has to write the same form, or the run fails one chunk at a time on a node
     * that has nothing to embed with.</p>
     *
     * @param content   Chunk text
     * @param embedding Embedding vector, or null or empty on a chunk that was never embedded
     * @return The encoded value
     */
    static byte[] encode(String content, float[] embedding) {
        return embedding == null || embedding.length == 0
            ? ChunkPayloadCodec.encodeTextOnly(content)
            : ChunkPayloadCodec.encode(content, embedding);
    }
}
