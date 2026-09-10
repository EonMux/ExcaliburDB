package excalibur.ingest.erasure;

import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Retires an erased document's graph nodes, across every segment that holds any.
 *
 * <h2>Why this exists</h2>
 * <p>Erasure works from the registry, which names a document's chunks. Nothing maps a chunk back
 * to a graph node: a node is attributed to the document it came from, by the integration worker,
 * as it inserts. So the graphs are reached by document, and only the storage engine knows which
 * segments there are to ask.</p>
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
}
