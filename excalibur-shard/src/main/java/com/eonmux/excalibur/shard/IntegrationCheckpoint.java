package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one repair that keeps a segment answering vector queries after its write log is replaced.
 *
 * <p>Two unrelated paths hand a segment a write log that its HNSW integration checkpoint was
 * never measured against - a compaction, and the left child of a split - and both fail the
 * same way, silently: the integration worker only looks for work while the checkpoint is below
 * the log's head, so a checkpoint left above it means the worker never looks again and every
 * subsequent write is stored, acknowledged and never indexed.</p>
 *
 * <p>The repair lives here rather than in either caller because it is the same repair, and
 * because the second caller was added years after the first went in without it.</p>
 */
final class IntegrationCheckpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationCheckpoint.class);

    /** Not instantiable: this is one operation, with no state of its own. */
    private IntegrationCheckpoint() {
    }

    /**
     * Rebases an integration checkpoint that outran the write log it points into.
     *
     * <p>The checkpoint is a byte offset into a specific write log. Two paths hand a segment a
     * different log than the one its checkpoint was measured against:
     * <ul>
     *   <li><b>compaction</b>, which rewrites the live entries into a new file;</li>
     *   <li><b>a split</b>, where the left child takes over the parent's HNSW file - checkpoint
     *       and all - while {@code prepareSplitChild} gives it a brand new, empty log.</li>
     * </ul>
     *
     * <p>In both the checkpoint is larger than the new log's head, and the integration worker
     * only looks for work when {@code lastIntegratedOffset < head}. Left alone it never finds
     * any: everything written to that segment from then on is stored, acknowledged, and never
     * indexed. Nothing fails; the segment simply stops answering vector queries about its own
     * newest data.
     *
     * <p>Rebasing onto the head is right rather than merely safe. In both cases the graph
     * already holds every vector that survived into the new file, so there is nothing below the
     * head left to integrate.
     *
     * @param newSegment The segment now holding the log
     * @param tracker    Its integration checkpoint
     * @param cause      What handed it a different log, for the log message
     */
    static void rebaseIfStale(Segment newSegment, TailOffsetTracker tracker,
                              String cause) {
        if (tracker == null) {
            return;
        }
        long checkpoint = tracker.getLastIntegratedOffset();
        long head = newSegment.getWriteLogHead();
        if (checkpoint <= head) {
            return;
        }
        tracker.resetTo(head);
        LOGGER.info("Rebased HNSW integration checkpoint for {} from {} to the new write log's "
            + "head {} after {}; without this every subsequent append would fall below the "
            + "checkpoint and never be integrated.",
            newSegment.path(), checkpoint, head, cause);
    }
}
