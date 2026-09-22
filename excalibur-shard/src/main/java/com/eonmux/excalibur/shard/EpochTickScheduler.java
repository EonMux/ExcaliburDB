package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Schedules periodic epoch ticks for visibility tracking.
 *
 * <p>The EpochTickScheduler uses a scheduled executor service with virtual threads
 * to advance the visibility epoch at a configurable interval (default 100ms).
 * This ensures that writes become visible to queries within one epoch tick
 * after write log fsync.</p>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>Tick interval: Configurable via {@code excalibur.visibility.epoch-tick-ms}</li>
 *   <li>Default interval: 100ms (per spec)</li>
 * </ul>
 *
 * <h2>Write Visibility Contract</h2>
 * <p>An ingested entry becomes queryable within one epoch tick (default 100ms)
 * of write log fsync. The pipeline:</p>
 * <ol>
 *   <li>Write log append</li>
 *   <li>Cuckoo filter insert</li>
 *   <li>LSH insert</li>
 *   <li>Enqueue for HNSW integration</li>
 *   <li>fsync</li>
 *   <li>Visible at next epoch tick</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Scheduler uses single-threaded executor (virtual thread)</li>
 *   <li>State transitions are atomic</li>
 *   <li>Statistics are tracked via atomic operations</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Tick interval must be positive</li>
 *   <li>Tick count starts at 0</li>
 *   <li>Scheduler must be started before ticks begin</li>
 *   <li>Scheduler can be stopped and restarted</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public class EpochTickScheduler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EpochTickScheduler.class);

    /**
     * Default epoch tick interval in milliseconds.
     * Spec requires 100ms default.
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 100;

    // ==================== Instance Fields ====================

    /**
     * The visibility tracker to advance on each tick.
     */
    private final VisibilityTracker visibilityTracker;

    /**
     * Scheduled executor for epoch ticks.
     * Uses virtual threads for lightweight scheduling.
     */
    private volatile ScheduledExecutorService scheduler;

    /**
     * The scheduled tick task.
     */
    private volatile ScheduledFuture<?> tickTask;

    /**
     * Tick interval in milliseconds.
     */
    private final long tickIntervalMs;

    /**
     * Flag indicating if the scheduler is running.
     */
    private final AtomicBoolean running;

    /**
     * Count of epoch ticks performed.
     */
    private final AtomicLong tickCount;

    /**
     * Timestamp of the last tick.
     */
    private final AtomicLong lastTickTimestamp;

    /**
     * Guards a tick against a concurrent stop, so {@link #stop()} returns only once no
     * tick can still advance the epoch.
     */
    private final Object tickLock = new Object();

    // ==================== Constructors ====================

    /**
     * Creates a new EpochTickScheduler with default interval (100ms).
     *
     * @param visibilityTracker The visibility tracker to advance on each tick
     * @throws NullPointerException if visibilityTracker is null
     */
    public EpochTickScheduler(VisibilityTracker visibilityTracker) {
        this(visibilityTracker, DEFAULT_TICK_INTERVAL_MS);
    }

    /**
     * Creates a new EpochTickScheduler with custom interval.
     *
     * @param visibilityTracker The visibility tracker to advance on each tick
     * @param tickIntervalMs    The tick interval in milliseconds
     * @throws NullPointerException if visibilityTracker is null
     * @throws IllegalArgumentException if tickIntervalMs is not positive
     */
    public EpochTickScheduler(VisibilityTracker visibilityTracker, long tickIntervalMs) {
        if (visibilityTracker == null) {
            throw new NullPointerException("VisibilityTracker cannot be null");
        }
        if (tickIntervalMs <= 0) {
            throw new IllegalArgumentException(
                "Tick interval must be positive: " + tickIntervalMs);
        }

        this.visibilityTracker = visibilityTracker;
        this.tickIntervalMs = tickIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("excalibur-epoch-tick-scheduler").factory()
        );
        this.running = new AtomicBoolean(false);
        this.tickCount = new AtomicLong(0);
        this.lastTickTimestamp = new AtomicLong(0);

        LOGGER.info("EpochTickScheduler created with interval {}ms", tickIntervalMs);
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the epoch tick scheduler.
     *
     * <p>After starting, the scheduler will advance the visibility epoch
     * every tick interval milliseconds.</p>
     *
     * @return true if the scheduler was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            LOGGER.info("Starting epoch tick scheduler with interval {}ms", tickIntervalMs);

            // A previous close() shut the executor down. Recreate it so a stop/start
            // cycle - an embedded server restart, or an integration test - does not
            // throw RejectedExecutionException out of the orchestrator's start().
            if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("excalibur-epoch-tick-scheduler").factory()
                );
            }

            tickTask = scheduler.scheduleAtFixedRate(
                this::performTick,
                tickIntervalMs, // Initial delay
                tickIntervalMs, // Period
                TimeUnit.MILLISECONDS
            );

            LOGGER.info("Epoch tick scheduler started successfully");
            return true;
        }
        return false;
    }

    /**
     * Stops the epoch tick scheduler.
     *
     * <p>After stopping, the epoch will no longer advance automatically.
     * The scheduler can be restarted with {@link #start()}.</p>
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping epoch tick scheduler...");

            if (tickTask != null) {
                tickTask.cancel(false);
                try {
                    // Wait for the task to fully terminate (with timeout)
                    tickTask.get(100, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Ignore interruptions and exceptions during shutdown
                    LOGGER.debug("Interrupted while waiting for tick task to complete", e);
                }
                tickTask = null;
            }

            // Fence out a tick that was already dispatched: cancel(false) lets it run to
            // completion, so without this the epoch could still advance after stop()
            // returned.
            synchronized (tickLock) {
                LOGGER.debug("Epoch tick scheduler quiesced");
            }

            LOGGER.info("Epoch tick scheduler stopped. Total ticks: {}, Current epoch: {}",
                tickCount.get(), visibilityTracker.currentEpoch());
        }
    }

    /**
     * Checks if the scheduler is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Tick Execution ====================

    /**
     * Performs a single epoch tick.
     *
     * <p>This method is called by the scheduler at each tick interval.
     * It advances the visibility epoch by one.</p>
     *
     * <p>Error handling ensures that a single tick failure does not
     * stop the scheduler.</p>
     */
    private void performTick() {
        synchronized (tickLock) {
            if (!running.get()) {
                // stop() was called while this tick was queued or dispatched.
                return;
            }

            try {
                long previousEpoch = visibilityTracker.currentEpoch();
                long newEpoch = visibilityTracker.advanceEpoch();

                tickCount.incrementAndGet();
                lastTickTimestamp.set(System.currentTimeMillis());

                // Record epoch in JMX metrics for observability
                Metrics.recordEpoch(newEpoch);

                // Calculate and record estimated coverage based on visible segment ratio
                VisibilityTracker.VisibilityStats stats = visibilityTracker.getStats();
                double coverage = stats.visibilityRatio();
                Metrics.setEstimatedCoverage(coverage);
                publishHealthScore(coverage);

                LOGGER.trace("Epoch tick: {} -> {}, coverage={}", previousEpoch, newEpoch, coverage);

            } catch (Exception e) {
                LOGGER.error("Error during epoch tick: {}", e.getMessage(), e);
                // Continue scheduling despite errors
            }
        }
    }

    /**
     * Requests an immediate epoch tick.
     *
     * <p>This can be used to advance the epoch immediately after
     * a write log fsync, rather than waiting for the next scheduled tick.</p>
     *
     * @return The new epoch value
     */
    public long tickNow() {
        long newEpoch = visibilityTracker.advanceEpoch();
        tickCount.incrementAndGet();
        lastTickTimestamp.set(System.currentTimeMillis());

        // Record epoch in JMX metrics for observability
        Metrics.recordEpoch(newEpoch);

        // Calculate and record estimated coverage based on visible segment ratio
        VisibilityTracker.VisibilityStats stats = visibilityTracker.getStats();
        double coverage = stats.visibilityRatio();
        Metrics.setEstimatedCoverage(coverage);
        publishHealthScore(coverage);

        LOGGER.trace("Immediate epoch tick: new epoch = {}, coverage = {}", newEpoch, coverage);
        return newEpoch;
    }

    /**
     * Publishes the health score the {@code SystemHealth} MBean reports.
     *
     * <p>The score had two writers: the bootstrap, which sets 0.5 once, and the HTTP health
     * endpoint. So it only moved when somebody polled {@code /health} over HTTP, and a
     * monitor watching this node through JMX alone - which is what those MBeans exist for -
     * read 0.5 for ever, through a total loss of the corpus included. The tick is where a
     * background signal belongs, and it is already where coverage is refreshed from.</p>
     *
     * <p>Coverage is the score, because it is the one health signal measured here on the
     * scale the MBean carries. It is the same number the health endpoint publishes, so the
     * two cannot disagree; the difference is that this one keeps moving with nobody
     * watching.</p>
     *
     * <p>A metrics sink that refuses the value must not stop the epoch advancing: visibility
     * is the node's job and reporting on it is not.</p>
     *
     * @param coverage The coverage just computed, in [0, 1]
     */
    private static void publishHealthScore(double coverage) {
        try {
            Metrics.setHealthScore(coverage);
        } catch (RuntimeException e) {
            LOGGER.debug("Could not publish the health score: {}", e.toString());
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the number of ticks performed.
     *
     * @return The tick count
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Returns the timestamp of the last tick.
     *
     * @return The last tick timestamp in milliseconds, or 0 if no ticks yet
     */
    public long getLastTickTimestamp() {
        return lastTickTimestamp.get();
    }

    /**
     * Returns the tick interval in milliseconds.
     *
     * @return The tick interval
     */
    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    /**
     * Returns statistics about the epoch tick scheduler.
     *
     * @return A statistics snapshot
     */
    public EpochTickStats getStats() {
        return new EpochTickStats(
            running.get(),
            tickCount.get(),
            lastTickTimestamp.get(),
            tickIntervalMs,
            visibilityTracker.currentEpoch()
        );
    }

    /**
     * Statistics snapshot for the epoch tick scheduler.
     *
     * @param running           Whether the scheduler is running
     * @param tickCount         Number of ticks performed
     * @param lastTickTimestamp Timestamp of the last tick (ms)
     * @param tickIntervalMs    Tick interval (ms)
     * @param currentEpoch      Current global epoch
     */
    public record EpochTickStats(
        boolean running,
        long tickCount,
        long lastTickTimestamp,
        long tickIntervalMs,
        long currentEpoch
    ) {
        /**
         * Returns the time since the last tick in milliseconds.
         *
         * @return Time since last tick, or -1 if no ticks yet
         */
        public long timeSinceLastTickMs() {
            if (lastTickTimestamp <= 0) {
                return -1;
            }
            return System.currentTimeMillis() - lastTickTimestamp;
        }
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("EpochTickScheduler closed");
    }

    @Override
    public String toString() {
        return String.format(
            "EpochTickScheduler{running=%s, interval=%dms, ticks=%d, epoch=%d}",
            running.get(), tickIntervalMs, tickCount.get(), visibilityTracker.currentEpoch()
        );
    }
}
