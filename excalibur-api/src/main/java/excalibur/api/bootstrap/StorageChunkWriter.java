package excalibur.api.bootstrap;

import excalibur.ingest.pipeline.OffsetAwareStorageWriter;
import excalibur.ingest.registry.UpsertHandler;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.hash.XxHash64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.excalibur.core.codec.ChunkPayloadCodec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes reindexed chunks into the storage engine and reports their write-log offsets.
 *
 * <h2>Routing</h2>
 * <p>The segment-agnostic write goes through {@link StorageEngine#put}, so the chunk
 * lands in the segment its key hashes to and stays reachable by key lookup. Pinning
 * every chunk to segment 0 - which is what a "write to the first available segment"
 * shortcut does - makes every reindexed chunk unreachable by the routed read path.</p>
 *
 * <p>The segment-targeted write is used during rolling replacement, where the caller
 * has created a new segment and every chunk of the source must land in it. That write
 * deliberately bypasses hash routing and addresses the segment directly.</p>
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

    @Override
    public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
        byte[] key = keyOf(chunkId);
        byte[] value = encode(content, embedding);
        try {
            storageEngine.put(key, value);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to write chunk {}: {}", chunkId, e.getMessage());
            return WRITE_FAILED;
        }
        return offsetOf(routedSegment(key));
    }

    /**
     * Whether the storage engine holds a segment of its own under this ID.
     *
     * <p>Segment IDs minted by {@link io.excalibur.store.SegmentCatalog#create} are drawn from a
     * counter the catalog advances past every ID it has issued, splits included, so a created
     * entry always names an index beyond the engine's canonical ones. On an engine that has never
     * split, that index is past the routing directory too and resolves to nothing.</p>
     *
     * <p>After a split it resolves anyway. Doubling the directory fills its upper half with buddy
     * pointers to the segments already in the lower half, so an out-of-range ID lands on a live
     * segment that is routing unrelated keys. Asking whether the ID resolves cannot tell the two
     * apart; asking whether the engine holds a distinct segment <em>under that ID</em> can, and
     * that is the question a caller wanting a segment to fill is really asking.</p>
     *
     * @param segmentId The routing index writes would be targeted at
     * @return true if the engine holds a segment of its own at that index
     */
    @Override
    public boolean canWriteTo(long segmentId) {
        return storageEngine.getSegments().containsKey(segmentId);
    }

    @Override
    public long writeChunkWithOffset(long segmentId, String chunkId, String content, float[] embedding) {
        byte[] key = keyOf(chunkId);
        byte[] value = encode(content, embedding);
        Segment segment = storageEngine.getSegment(segmentId);
        if (segment == null) {
            LOGGER.warn("Segment {} is not available for chunk {}", segmentId, chunkId);
            return WRITE_FAILED;
        }
        try {
            segment.put(key, value);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to write chunk {} into segment {}: {}", chunkId, segmentId, e.getMessage());
            return WRITE_FAILED;
        }
        return offsetOf(segment);
    }

    /**
     * Resolves the segment a key routes to under the engine's current routing width.
     *
     * @param key The chunk key
     * @return The routed segment, or null when routing cannot be resolved
     */
    Segment routedSegment(byte[] key) {
        int routingWidth = storageEngine.getSegmentCount();
        if (routingWidth <= 0) {
            return null;
        }
        int index = (int) (XxHash64.hash(key) & (routingWidth - 1));
        return storageEngine.getSegment(index);
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
     * @param content   Chunk text
     * @param embedding Embedding vector
     * @return The encoded value
     */
    static byte[] encode(String content, float[] embedding) {
        return ChunkPayloadCodec.encode(content, embedding);
    }
}
