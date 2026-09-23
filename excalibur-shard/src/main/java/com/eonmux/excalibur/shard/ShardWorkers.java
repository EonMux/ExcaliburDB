package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.shard.worker.CuckooMaintenanceWorker;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.shard.worker.HnswIntegrationWorker;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.shard.worker.WarmingPhaseWorker;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.time.Duration;

/**
 * The shard's background workers, and the order they are allowed to come up and go down in.
 *
 * <p>The order is the reason this exists. Seven loops write to the same segments, graphs and
 * filters, and each one depends on something the previous one has already made safe:</p>
 * <ul>
 *   <li>The epoch tick scheduler starts first and stops last. Everything else publishes work
 *       that becomes visible at an epoch, so the counter has to be advancing before any of it
 *       runs; and {@link EpochTickScheduler#stop()} fences an already-dispatched tick under
 *       its own lock, so once it has returned the epoch cannot advance again - which is only
 *       a safe thing to guarantee after every producer of visible work has already stopped.</li>
 *   <li>Everything else stops in exactly the reverse of the order it started in, so no worker
 *       is ever left feeding a worker that has already gone away.</li>
 * </ul>
 *
 * <p>Held as one object rather than as seven fields on the orchestrator because that order is
 * the invariant, and an invariant spread across two methods in a two-thousand-line class is
 * one a later edit reorders without noticing.</p>
 */
final class ShardWorkers {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardWorkers.class);

    private final HnswIntegrationWorker hnsw;
    private final CuckooMaintenanceWorker cuckoo;
    private final CuckooMetricsWorker cuckooMetrics;
    private final WarmingPhaseWorker warming;
    private final HnswCompactionWorker graphCompaction;
    private final SegmentReindexScheduler reindex;
    private final EpochTickScheduler epochTicks;

    /**
     * Background tombstone cleanup, or null when the storage engine exposes no Arena.
     *
     * <p>Compaction maps its replacement segment out of that Arena, and the segment stays
     * readable only while the Arena is open - so it has to outlive every reader of every
     * segment compaction ever produced, which is longer than the orchestrator lives. Only the
     * engine that owns the segments can hold an Arena to that contract, so when it offers none
     * there is no compaction: dead space is then reclaimed by reindex alone, which is slower,
     * not wrong.</p>
     */
    private final CompactionWorker segmentCompaction;

    /**
     * Builds every worker, wired to the callbacks it reports through.
     *
     * @param catalog    The segment catalog the workers index into
     * @param engine     The storage engine that owns the segments
     * @param visibility The epoch counter writes become visible against
     * @param epochIsolation Where the integration worker records when each node became visible
     * @param tuning     The queue limits and loop intervals
     * @param compaction When compaction runs and what it considers worth rewriting
     * @param listeners  What each worker calls back into
     */
    ShardWorkers(SegmentCatalog catalog, StorageEngine engine, VisibilityTracker visibility,
                 EpochIsolationValidator epochIsolation, WorkerTuning tuning,
                 CompactionSettings compaction, ShardWorkerListeners listeners) {
        // Create epoch tick scheduler for periodic epoch advancement
        this.epochTicks = new EpochTickScheduler(visibility, tuning.epochTickIntervalMs());

        // Create workers with listeners
        // Pass epoch isolation validator and visibility tracker to HNSW worker for node-level epoch tracking
        this.hnsw = new HnswIntegrationWorker(catalog,
            listeners.integration(),
            epochIsolation,
            visibility,
            100, tuning.hnswQueueDepthLimit(), 100);

        // The gauges report queue health as a fraction of a limit, and that limit lived in a
        // static nobody guaranteed matched the one being enforced: two config keys describe this
        // quantity and the construction paths did not agree on which. Publishing it from the
        // object that enforces it is what makes the gauge unable to disagree with reality -
        // "healthy" cannot be reported at the moment writes are being refused.
        com.eonmux.excalibur.metrics.Metrics.setHnswQueueLimit(tuning.hnswQueueDepthLimit());

        this.cuckoo = new CuckooMaintenanceWorker(catalog, engine,
            listeners.filterCleanup(),
            tuning.cuckooMaintenanceIntervalMs(), 1000, 0.001);

        this.reindex = new SegmentReindexScheduler(catalog,
            listeners.reindex(),
            tuning.reindexCheckIntervalMs(), 3600000);

        // Background tombstone cleanup, allocating replacement segments out of the storage
        // engine's Arena. See the segmentCompaction field for why no other Arena will do.
        Arena arena = engine.arena();
        if (arena != null) {
            this.segmentCompaction = new CompactionWorker(arena,
                listeners.segmentCompaction(),
                compaction.segmentIntervalMs(),
                compaction.segmentTombstoneRatio(),
                compaction.segmentWriteLogBytes(),
                compaction.maxConcurrentSegmentCompactions());
            LOGGER.info("CompactionWorker initialized over the storage engine's Arena");
        } else {
            this.segmentCompaction = null;
            LOGGER.warn("Storage engine exposes no Arena; background tombstone compaction is "
                + "disabled. Dead space is reclaimed by reindex only.");
        }

        // Create warming phase worker for PQ codebook training
        // This worker handles the WARMING → ACTIVE transition with PQ training
        this.warming = new WarmingPhaseWorker(catalog,
            listeners.warming(),
            100, 100, null);
        LOGGER.info("WarmingPhaseWorker initialized for PQ training");

        // Create HNSW compaction worker for removing tombstoned nodes, on the configured
        // schedule and threshold.
        this.graphCompaction = compaction.newGraphWorker();
        this.graphCompaction.setCompactionListener(listeners.graphCompaction());
        LOGGER.info("HnswCompactionWorker initialized for graph compaction");

        // Create Cuckoo metrics worker for FP rate monitoring
        // Uses same interval as CuckooMaintenanceWorker for consistency
        this.cuckooMetrics = new CuckooMetricsWorker(tuning.cuckooMaintenanceIntervalMs());
        LOGGER.info("CuckooMetricsWorker initialized for FP rate monitoring");
    }

    /**
     * Starts every worker, in the only order they may be started in.
     *
     * <p>Epoch ticks first, so nothing publishes work into a counter that is standing still;
     * then the loops that produce that work, each after whatever it hands its output to.</p>
     */
    void start() {
        // Start epoch tick scheduler first for visibility tracking
        epochTicks.start();

        // Start workers in order
        cuckoo.start();
        cuckooMetrics.start();
        hnsw.start();
        warming.start();
        graphCompaction.start();
        reindex.start();
        if (segmentCompaction != null) {
            segmentCompaction.start();
        }
    }

    /**
     * Stops every worker, in exactly the reverse of the order {@link #start()} used.
     *
     * <p>Epoch ticks stop last. {@link EpochTickScheduler#stop()} fences a tick that has
     * already been dispatched, so after it returns the epoch cannot advance - a promise that
     * only holds if every worker that makes new work visible has already stopped.</p>
     *
     * <p>Every worker is asked, and waited for with what is left of {@code wait}, even after one
     * has reported that it outlasted it: stopping the rest is still worth doing, and the answer
     * is the conjunction. One wait for all of them rather than one each, so a caller that hands
     * this what its shutdown budget has left is never kept past it; a worker reached once the
     * wait is spent is still asked to stop, and answers from whether it already has. Workers are
     * stopped by a signal they read at safe points, never by an interrupt, so a false here means
     * work is still running against the storage and the arena it is mapped out of must stay
     * open.</p>
     *
     * @param wait How long to wait for all of them together
     * @return true if every worker has ended
     */
    boolean stop(Duration wait) {
        // Wraps rather than saturates for a wait near Long.MAX_VALUE nanoseconds; left()
        // subtracts the current time first, which undoes it.
        long deadline = System.nanoTime() + wait.toNanos();
        boolean ended = true;
        // Stop workers in reverse order
        if (segmentCompaction != null) {
            ended &= segmentCompaction.stop(left(deadline));
        }
        ended &= reindex.stop(left(deadline));
        ended &= graphCompaction.stop(left(deadline));
        ended &= warming.stop(left(deadline));
        ended &= hnsw.stop(left(deadline));
        // Sampling and JMX only: nothing it runs touches storage, so it cannot hold the arena.
        cuckooMetrics.stop(left(deadline));
        ended &= cuckoo.stop(left(deadline));

        // Stop epoch tick scheduler last. Its stop fences any dispatched tick under its own
        // lock, so it has always ended when it returns.
        epochTicks.stop();
        return ended;
    }

    /**
     * What is left of a wait that ends at {@code deadline}.
     *
     * @param deadline The {@link System#nanoTime()} the wait ends at
     * @return The time left, zero once it has passed
     */
    static Duration left(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /**
     * Releases the epoch tick scheduler's executor.
     *
     * <p>Separate from {@link #stop(Duration)} because a stopped orchestrator can be started again,
     * and only the close path is allowed to make that impossible.</p>
     */
    void close() {
        epochTicks.close();
    }

    /**
     * Registers a segment for background tombstone cleanup, if compaction is available.
     *
     * @param segment The segment
     */
    void registerForCompaction(Segment segment) {
        if (segmentCompaction != null) {
            segmentCompaction.registerSegment(segment);
        }
    }

    /**
     * Unregisters a segment from background tombstone cleanup, if compaction is available.
     *
     * @param segment The segment
     */
    void unregisterFromCompaction(Segment segment) {
        if (segmentCompaction != null) {
            segmentCompaction.unregisterSegment(segment);
        }
    }

    /**
     * Returns how many compactions have run, which is none when compaction is unavailable.
     *
     * @return The compaction count
     */
    long segmentCompactionCount() {
        return segmentCompaction != null ? segmentCompaction.getCompactionCount() : 0L;
    }

    /**
     * Returns the HNSW integration worker.
     *
     * @return The integration worker, never null
     */
    HnswIntegrationWorker hnsw() {
        return hnsw;
    }

    /**
     * Returns the Cuckoo maintenance worker.
     *
     * @return The maintenance worker, never null
     */
    CuckooMaintenanceWorker cuckoo() {
        return cuckoo;
    }

    /**
     * Returns the Cuckoo false-positive sampling worker.
     *
     * @return The metrics worker, never null
     */
    CuckooMetricsWorker cuckooMetrics() {
        return cuckooMetrics;
    }

    /**
     * Returns the warming phase worker that trains PQ codebooks.
     *
     * @return The warming worker, never null
     */
    WarmingPhaseWorker warming() {
        return warming;
    }

    /**
     * Returns the worker that rebuilds HNSW graphs to drop tombstoned nodes.
     *
     * @return The graph compaction worker, never null
     */
    HnswCompactionWorker graphCompaction() {
        return graphCompaction;
    }

    /**
     * Returns the scheduler that triggers re-indexing of stale sources.
     *
     * @return The re-index scheduler, never null
     */
    SegmentReindexScheduler reindex() {
        return reindex;
    }

    /**
     * Returns the worker that rewrites segment files to drop tombstoned entries.
     *
     * @return The compaction worker, or null when the storage engine exposes no Arena
     */
    CompactionWorker segmentCompaction() {
        return segmentCompaction;
    }

    /**
     * Returns the scheduler that advances the visibility epoch.
     *
     * @return The epoch tick scheduler, never null
     */
    EpochTickScheduler epochTicks() {
        return epochTicks;
    }
}
