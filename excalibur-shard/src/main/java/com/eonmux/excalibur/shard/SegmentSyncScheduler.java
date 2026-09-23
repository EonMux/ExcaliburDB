package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.SplitWorker;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the shard's registrations converging on the storage engine's segment directory.
 *
 * <p>Two mechanisms, because neither is sufficient alone. The split observer closes the window
 * a sweep cannot: between a split and the next sweep a query routes to a child with no graph
 * and no filter registered against it and simply finds nothing. The sweep is the backstop for
 * everything no event covers - a segment the engine created lazily on first write, or one
 * placed directly on disk.</p>
 *
 * <p>The two are deliberately bracketed around the workers: the sweep is the last thing
 * started and the first thing stopped, so it never registers index structures into a worker
 * set that is already going down, and a split notification arriving after the shard stopped
 * never registers structures nothing will ever close.</p>
 */
final class SegmentSyncScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentSyncScheduler.class);

    /**
     * Shortest period a sweep may be scheduled at, in milliseconds.
     *
     * <p>A sweep is not cheap and does not get cheaper with frequency. It re-reads the whole
     * segment directory, and every registration it retires costs a linear scan of all the
     * others, because the workers key by {@link com.eonmux.excalibur.store.Segment} identity and a
     * registration may only be retired once the last id referencing it is gone. That is
     * quadratic in registered segments, on one virtual thread, competing with the very
     * workers it exists to correct.</p>
     *
     * <p>A quarter of a second is well below the one-second default and still leaves the
     * sweep as a backstop rather than a load source; the urgent case - a split - is covered
     * by the observer, not by sweeping harder. Values below this are refused rather than
     * clamped, so an operator who asks for 10ms is told, instead of quietly getting 250.</p>
     */
    static final long MIN_INTERVAL_MS = 250;

    private final StorageEngine engine;
    private final SplitWorker.SplitListener observer;
    private final Runnable sweep;

    /**
     * How long to wait between passes. Read by {@link #start()}, so a change takes effect on
     * the next start rather than rescheduling a sweep that is already running.
     */
    private volatile long intervalMs;

    /**
     * The sweep's executor, recreated by {@link #start()} when a previous close shut it down.
     */
    private volatile ScheduledExecutorService executor;

    /**
     * The scheduled sweep, or null while stopped.
     */
    private volatile ScheduledFuture<?> task;

    /**
     * Creates the reconciliation scheduler.
     *
     * @param engine     The storage engine whose directory is reconciled against
     * @param observer   The split listener registered while the sweep is running
     * @param sweep      One reconciliation pass
     * @param intervalMs How long to wait between passes
     * @throws IllegalArgumentException if intervalMs is below {@link #MIN_INTERVAL_MS}
     */
    SegmentSyncScheduler(StorageEngine engine, SplitWorker.SplitListener observer,
                         Runnable sweep, long intervalMs) {
        this.engine = engine;
        this.observer = observer;
        this.sweep = sweep;
        this.intervalMs = checkInterval(intervalMs);
    }

    /**
     * Changes how often the sweep runs, from the next {@link #start()}.
     *
     * @param newIntervalMs How long to wait between passes
     * @throws IllegalArgumentException if newIntervalMs is below {@link #MIN_INTERVAL_MS}
     */
    void setIntervalMs(long newIntervalMs) {
        this.intervalMs = checkInterval(newIntervalMs);
    }

    /**
     * Refuses a period the sweep cannot afford to run at.
     *
     * @param candidate The proposed period
     * @return The same value, when it is legal
     * @throws IllegalArgumentException if candidate is below {@link #MIN_INTERVAL_MS}
     */
    private static long checkInterval(long candidate) {
        if (candidate < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Segment sync interval must be at least " + MIN_INTERVAL_MS + "ms, got: "
                    + candidate);
        }
        return candidate;
    }

    /**
     * Registers the split observer and schedules the reconciliation sweep.
     *
     * <p>The executor is recreated when a previous {@link #shutdown(Duration)} shut it down, so a
     * stop/start cycle does not hand the scheduler a terminated executor.</p>
     */
    void start() {
        // Register for split events as well as sweeping. The sweep alone converges eventually, but
        // between a split and the next sweep a query can route to a child that has no HNSW graph
        // or filter registered against it and simply find nothing. The observer closes that window;
        // the sweep stays as the backstop for everything a split event does not cover, such as
        // segments created directly on disk.
        engine.addSplitObserver(observer);

        ScheduledExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            current = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("excalibur-segment-sync").factory());
            executor = current;
        }

        task = current.scheduleWithFixedDelay(this::runSweep, intervalMs, intervalMs,
            TimeUnit.MILLISECONDS);
    }

    /**
     * Deregisters the split observer and cancels the sweep, without shutting its executor down.
     */
    void stop() {
        // Deregister first. A split notification arriving after the orchestrator stopped would
        // register index structures nothing will ever close.
        engine.removeSplitObserver(observer);

        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(false);
            task = null;
        }
    }

    /**
     * Shuts the sweep's executor down for good, waiting for a sweep in progress to finish.
     *
     * <p>Waited for, never interrupted. A sweep registers index structures for segments it has
     * found, which opens their HNSW graphs and maps their files through channels every query of
     * those graphs then reads; an interrupt landing inside that closes the channel it lands on
     * and leaves the graph unusable by anyone. A sweep that outlives the wait is reported,
     * because the caller closes the arena those graphs are mapped from next.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains.</p>
     *
     * @param wait How long to wait for a sweep in progress
     * @return true if no sweep is still running
     */
    boolean shutdown(Duration wait) {
        ScheduledExecutorService current = executor;
        if (current == null) {
            return true;
        }
        current.shutdown();
        try {
            if (current.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (current.isTerminated()) {
                return true;
            }
        }
        LOGGER.warn("A segment reconciliation sweep was still registering index structures "
            + "{}ms after shutdown", wait.toMillis());
        return false;
    }

    /**
     * Runs one reconciliation sweep.
     *
     * <p>A scheduled task that throws is never rescheduled, so a transient failure must
     * not be allowed to escape and silently retire the sweep for the process lifetime.</p>
     */
    private void runSweep() {
        try {
            sweep.run();
        } catch (RuntimeException e) {
            LOGGER.error("Segment directory reconciliation failed: {}", e.getMessage(), e);
        }
    }
}
