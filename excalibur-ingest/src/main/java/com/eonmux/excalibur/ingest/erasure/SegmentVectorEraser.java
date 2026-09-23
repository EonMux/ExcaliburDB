package com.eonmux.excalibur.ingest.erasure;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;

/**
 * Retires an erased document's graph nodes, or a replaced version's surplus chunks' nodes, across
 * every segment that holds any.
 *
 * <h2>Why this exists</h2>
 * <p>Erasure works from the registry, which names a document's chunks. A graph node is attributed
 * to the chunk it is the vector of, and each graph indexes its nodes by the document those chunks
 * belong to as well, so one call per graph retires every node of the document - including a
 * superseded one the integration worker has not reached the DELETE of yet, which the registry no
 * longer names. Only the storage engine knows which segments there are to ask.</p>
 *
 * <h2>Why not leave it to the write log</h2>
 * <p>The chunk tombstones erasure writes reach each segment's log as DELETEs, and the integration
 * worker retires the nodes when it replays them. That is asynchronous, and erasure answers the
 * caller with a count it has to be able to stand behind: retiring here, and forcing the result to
 * disk, is what makes the answer true when it is given.</p>
 *
 * <h2>Why every segment</h2>
 * <p>A document's chunks are routed by key, and re-ingestion, splits and compaction all move them,
 * so there is no segment a document is guaranteed to be confined to. Asking a segment that holds
 * none of it costs a lookup that finds nothing.</p>
 *
 * <h2>What a failure costs</h2>
 * <p>A graph that will not take the write is logged and skipped, and the count the erasure reports
 * is short by what it could not retire. Failing the whole request instead would leave the segments
 * that did accept it retired and the caller with no number to act on.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond the storage engine it reads through. Each graph takes its own write lock, so
 * concurrent erasures and concurrent searches are safe.</p>
 */
public final class SegmentVectorEraser implements DocumentErasureService.VectorEraser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentVectorEraser.class);

    private final StorageEngine storage;

    private SegmentVectorEraser(StorageEngine storage) {
        this.storage = storage;
    }

    /**
     * An eraser reaching the graphs attached to a storage engine's segments.
     *
     * @param storage Where the segments live
     * @return The eraser the erasure service takes
     * @throws NullPointerException if storage is null
     */
    public static DocumentErasureService.VectorEraser of(StorageEngine storage) {
        Objects.requireNonNull(storage, "storage must not be null");
        return new SegmentVectorEraser(storage);
    }

    @Override
    public int erase(String documentKey) {
        if (documentKey == null) {
            return 0;
        }

        int retired = 0;
        for (Segment segment : storage.getSegments().values()) {
            HnswGraph graph = segment.getHnswGraph();
            if (graph == null || segment.isClosed()) {
                continue;
            }
            try {
                int fromSegment = graph.deleteDocument(documentKey);
                if (fromSegment > 0) {
                    // Synced here rather than left to the next checkpoint. The tombstone is in the
                    // mapping either way, so nothing in this process can still rank the document;
                    // what the sync buys is that a machine losing power afterwards does not bring
                    // it back, which is the answer the caller was given.
                    graph.flush();
                    retired += fromSegment;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Segment {} still ranks document {}: {}",
                    segment.segmentId(), documentKey, e.getMessage());
            }
        }
        return retired;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not forced to disk, unlike a document's erasure: the storage tombstones that follow reach
     * the write log as DELETEs, and a restart that lost these tombstones replays those and
     * retires the same nodes again.</p>
     */
    @Override
    public int eraseChunks(Collection<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (chunkIds.isEmpty()) {
            return 0;
        }

        int retired = 0;
        for (Segment segment : storage.getSegments().values()) {
            HnswGraph graph = segment.getHnswGraph();
            if (graph == null || segment.isClosed()) {
                continue;
            }
            try {
                retired += graph.deleteChunks(chunkIds);
            } catch (RuntimeException e) {
                LOGGER.warn("Segment {} keeps the vectors of {} superseded chunk(s) until its "
                    + "write log retires them: {}", segment.segmentId(), chunkIds.size(),
                    e.getMessage());
            }
        }
        return retired;
    }
}
