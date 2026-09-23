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
import java.util.function.BooleanSupplier;

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
 * <h2>Stopping</h2>
 * <p>A run is stopped by the signal its caller hands in, read between segments and before and
 * between graphs - never by interrupting it. Each rewrite creates, maps, grows and forces files
 * through channels that the queries and writers after it go on to share, and an interrupt that
 * reached the run inside one of those operations would close the channel for all of them. So
 * the shutdown drain asks, waits for the run to reach the next of those points, and closes the
 * arena every segment is mapped out of only once the run has returned; a run that carried on
 * past a stop would be copying entries out of memory being unmapped underneath it, which ends
 * the process rather than raising anything anyone can be told about.</p>
 *
 * <p>An interrupt that reaches the thread anyway, from a caller that still sends one, is read
 * the same way as the signal, so the run leaves at the next safe point rather than starting
 * more channel work on a thread whose next blocking channel call would close its channel.</p>
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
     * @param force         true to compact every live segment and graph regardless of the
     *                      thresholds
     * @param stopRequested Read between segments and before and between graphs; once true the
     *                      run examines nothing more and reports what it had done
     * @return What the run examined, reclaimed and failed on
     */
    CompactionReport run(boolean force, BooleanSupplier stopRequested) {
        BooleanSupplier stop = stopOrInterrupt(stopRequested);
        long startNanos = System.nanoTime();

        SegmentCompactionTally segments = compactLiveSegments(force, stop);
        HnswCompactionWorker.GraphCompactionSummary graphs = compactLiveGraphs(force, stop);

        CompactionReport report = new CompactionReport(
            segments.considered(), segments.compacted(), segments.failed(),
            graphs.graphsConsidered(), graphs.graphsCompacted(), graphs.graphsFailed(),
            Duration.ofNanos(System.nanoTime() - startNanos));

        LOGGER.info("On-demand compaction (force={}): {}", force, report);
        return report;
    }

    /**
     * The caller's stop signal, or an interrupt of the thread the run is on.
     *
     * @param stopRequested The caller's signal
     * @return A supplier that is true once either has happened
     */
    private static BooleanSupplier stopOrInterrupt(BooleanSupplier stopRequested) {
        return () -> stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted();
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
     * @param stop  Read before each segment
     * @return The counts for the segment half of the run
     */
    private SegmentCompactionTally compactLiveSegments(boolean force, BooleanSupplier stop) {
        CompactionWorker worker = workers.segmentCompaction();
        Set<Segment> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(engine.getSegments().values());

        if (worker == null) {
            return noCompactionAvailable(live.size());
        }

        int compacted = 0;
        int failed = 0;
        for (Segment segment : live) {
            if (stop.getAsBoolean()) {
                // Between two rewrites is where stopping is safe: the last one has published or
                // failed, and the next has not opened a file. The shutdown path waits for this
                // return before closing the arena every segment is mapped out of.
                LOGGER.warn("On-demand compaction was asked to stop after {} of {} segment(s); "
                    + "the rest are left to the background worker", compacted + failed,
                    live.size());
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
     * Rebuilds the registered HNSW graphs, unless the run has already been asked to stop.
     *
     * <p>A graph rebuild is as long-running as a segment rewrite and maps out of the same
     * arena, so a stop has to be observed before the second half of a run starts and between
     * its graphs, not only between the segments of the first. Nothing is examined after a stop,
     * which is why graphs not reached are not counted as considered.</p>
     *
     * @param force true to rebuild every registered graph regardless of its tombstone ratio
     * @param stop  Read before the first graph and between graphs
     * @return The counts for the graph half of the run
     */
    private HnswCompactionWorker.GraphCompactionSummary compactLiveGraphs(boolean force,
                                                                        BooleanSupplier stop) {
        if (stop.getAsBoolean()) {
            LOGGER.warn("On-demand compaction was asked to stop before the HNSW graphs were "
                + "examined; none were rebuilt");
            return new HnswCompactionWorker.GraphCompactionSummary(0, 0, 0);
        }
        return workers.graphCompaction().compactNow(force, stop);
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
