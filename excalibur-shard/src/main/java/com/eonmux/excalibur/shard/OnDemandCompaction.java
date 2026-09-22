package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Compaction an operator asked for, run on the calling thread.
 *
 * <p>The background workers reclaim dead space on their own schedule, which is the wrong
 * answer after a bulk erasure: the space is wanted now, and the operator wants to be told what
 * came back. This runs the same policy - or ignores it entirely when forced - and answers with
 * a {@link CompactionReport} rather than a boolean, because the honest outcome of rewriting
 * several hundred segments is usually partial.</p>
 *
 * <p>It takes no lock of its own and needs none. Every segment rewrite is serialised by that
 * segment's own write lock and re-checks under it that the segment is still open and not
 * splitting, and every graph rebuild is serialised the same way; so a run that collides with
 * the background loop blocks, then finds the work already done and skips it. Neither caller
 * has to know the other exists.</p>
 *
 * <p>Interruption is the one thing it must observe. The shutdown drain interrupts a run that
 * has outstayed its grace period and then closes the arena every segment is mapped out of, so
 * a run that carried on would be copying entries out of memory being unmapped underneath it -
 * which ends the process rather than raising anything anyone can be told about.</p>
 */
final class OnDemandCompaction {

    private static final Logger LOGGER = LoggerFactory.getLogger(OnDemandCompaction.class);

    private final StorageEngine engine;
    private final ShardWorkers workers;

    /**
     * Creates the on-demand compaction entry point for one shard.
     *
     * @param engine  The storage engine holding the live segments
     * @param workers The workers whose policy and registrations the run borrows
     */
    OnDemandCompaction(StorageEngine engine, ShardWorkers workers) {
        this.engine = engine;
        this.workers = workers;
    }

    /**
     * Runs one on-demand compaction and reports what it did.
     *
     * @param force true to compact every live segment and graph regardless of the thresholds
     * @return What the run examined, reclaimed and failed on
     */
    CompactionReport run(boolean force) {
        long startNanos = System.nanoTime();

        SegmentCompactionTally segments = compactLiveSegments(force);
        HnswCompactionWorker.GraphCompactionSummary graphs = compactLiveGraphs(force);

        CompactionReport report = new CompactionReport(
            segments.considered(), segments.compacted(), segments.failed(),
            graphs.graphsConsidered(), graphs.graphsCompacted(), graphs.graphsFailed(),
            Duration.ofNanos(System.nanoTime() - startNanos));

        LOGGER.info("On-demand compaction (force={}): {}", force, report);
        return report;
    }

    /**
     * Compacts every distinct live segment the storage engine holds.
     *
     * <p>The engine's directory maps several indices onto one segment after a split, so the
     * segments are de-duplicated by identity first: compacting the same segment twice would
     * count a rewrite that did not happen, and the second attempt would find the segment
     * already closed.</p>
     *
     * @param force true to rewrite regardless of the thresholds
     * @return The counts for the segment half of the run
     */
    private SegmentCompactionTally compactLiveSegments(boolean force) {
        CompactionWorker worker = workers.segmentCompaction();
        Set<Segment> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(engine.getSegments().values());

        if (worker == null) {
            return noCompactionAvailable(live.size());
        }

        int compacted = 0;
        int failed = 0;
        for (Segment segment : live) {
            if (Thread.currentThread().isInterrupted()) {
                // The thread a forced run is on is interrupted by the shutdown path, which then
                // closes the arena every segment is mapped out of. Carrying on past that point
                // copies entries out of memory that is being unmapped, which ends the process
                // rather than raising anything a caller could be told about.
                LOGGER.warn("On-demand compaction was interrupted after {} of {} segment(s); the "
                    + "rest are left to the background worker", compacted + failed, live.size());
                break;
            }
            try {
                if (worker.compactNow(segment, force)) {
                    compacted++;
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                LOGGER.error("On-demand compaction failed for segment {}: {}",
                    segment.path(), e.getMessage(), e);
            }
        }
        return new SegmentCompactionTally(live.size(), compacted, failed);
    }

    /**
     * Reports a run against an engine that has no compaction worker at all.
     *
     * <p>The segments are counted as failed rather than skipped. A skip is a segment the policy
     * looked at and declined, and no policy was applied here: the engine exposes no arena, so
     * there is nothing that could rewrite them however much dead weight they hold. Reported as
     * skips, a run that reclaimed nothing and could never have reclaimed anything is
     * indistinguishable from a healthy run with nothing to do, and the operator who asked for
     * the space back is told the request succeeded.</p>
     *
     * @param live How many live segments the engine holds
     * @return The counts for a run that could not examine any of them
     */
    private static SegmentCompactionTally noCompactionAvailable(int live) {
        if (live > 0) {
            LOGGER.error("Compaction was requested but the storage engine exposes no Arena, so "
                + "none of the {} live segment(s) could be rewritten; dead space is reclaimed "
                + "by reindex only", live);
        }
        return new SegmentCompactionTally(live, 0, live);
    }

    /**
     * Rebuilds the registered HNSW graphs, unless the run has already been interrupted.
     *
     * <p>A graph rebuild is as long-running as a segment rewrite and maps out of the same
     * arena, so an interrupt has to be observed before the second half of a run starts and not
     * only between the segments of the first. Nothing is examined after an interrupt, which is
     * why this reports none considered rather than a count of graphs it skipped.</p>
     *
     * @param force true to rebuild every registered graph regardless of its tombstone ratio
     * @return The counts for the graph half of the run
     */
    private HnswCompactionWorker.GraphCompactionSummary compactLiveGraphs(boolean force) {
        if (Thread.currentThread().isInterrupted()) {
            LOGGER.warn("On-demand compaction was interrupted before the HNSW graphs were "
                + "examined; none were rebuilt");
            return new HnswCompactionWorker.GraphCompactionSummary(0, 0, 0);
        }
        return workers.graphCompaction().compactNow(force);
    }

    /**
     * The segment half of an on-demand compaction run.
     *
     * @param considered distinct live segments examined
     * @param compacted  segments rewritten
     * @param failed     segments whose rewrite raised an error
     */
    private record SegmentCompactionTally(int considered, int compacted, int failed) {
    }
}
