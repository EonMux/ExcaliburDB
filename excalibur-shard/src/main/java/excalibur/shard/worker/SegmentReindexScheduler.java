package excalibur.shard.worker;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread scheduler for stale segment re-crawl scheduling.
 *
 * <p>This worker schedules and triggers re-indexing of segments based on
 * configurable per-source intervals. Re-indexing ensures that stale data
 * is refreshed and the index remains up-to-date.</p>
 *
 * <h2>Re-indexing Strategy</h2>
 * <p>Each data source can have a configurable re-index interval. When the
 * interval elapses, the scheduler triggers a re-index via the existing
 * ingestion pipeline:</p>
 * <ol>
 *   <li>Identify segments that need re-indexing based on age</li>
 *   <li>Trigger re-index via {@link ReindexListener#onReindexNeeded(long)}</li>
 *   <li>Track re-index progress and completion</li>
 *   <li>Report statistics for monitoring</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread scheduler</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Concurrent map for source scheduling</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Default re-index interval: 1 hour (configurable per source)</li>
 *   <li>Minimum re-index interval: 1 minute</li>
 *   <li>Maximum re-index interval: 24 hours</li>
 *   <li>Scheduler check interval: 1 minute</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see SegmentCatalog
 */
public class SegmentReindexScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentReindexScheduler.class);

    // ==================== Configuration Constants ====================

    /**
     * Default interval between scheduler checks in milliseconds.
     */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 60_000; // 1 minute

    /**
     * Default re-index interval for sources in milliseconds.
     */
    public static final long DEFAULT_REINDEX_INTERVAL_MS = TimeUnit.HOURS.toMillis(1); // 1 hour

    /**
     * Minimum allowed re-index interval in milliseconds.
     */
    public static final long MIN_REINDEX_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1); // 1 minute

    /**
     * Maximum allowed re-index interval in milliseconds.
     */
    public static final long MAX_REINDEX_INTERVAL_MS = TimeUnit.HOURS.toMillis(24); // 24 hours

    /**
     * Minimum check interval in milliseconds.
     */
    public static final long MIN_CHECK_INTERVAL_MS = 10_000; // 10 seconds

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final ReindexListener listener;
    private final Map<Long, SourceSchedule> sourceSchedules;
    private volatile ScheduledExecutorService scheduler;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;

    private final AtomicLong totalReindexesTriggered;
    private final AtomicLong totalReindexesCompleted;
    private final AtomicLong totalReindexesFailed;
    private final AtomicLong lastCheckTime;

    private volatile long checkIntervalMs;
    private volatile long defaultReindexIntervalMs;

    // ==================== Constructors ====================

    /**
     * Creates a new SegmentReindexScheduler with default configuration.
     *
     * @param catalog  The segment catalog for metadata access
     * @param listener Callback for re-index events
     * @throws NullPointerException if catalog or listener is null
     */
    public SegmentReindexScheduler(SegmentCatalog catalog, ReindexListener listener) {
        this(catalog, listener, DEFAULT_CHECK_INTERVAL_MS, DEFAULT_REINDEX_INTERVAL_MS);
    }

    /**
     * Creates a new SegmentReindexScheduler with custom configuration.
     *
     * @param catalog                 The segment catalog
     * @param listener                Callback for re-index events
     * @param checkIntervalMs         Interval between scheduler checks (ms)
     * @param defaultReindexIntervalMs Default re-index interval for sources (ms)
     * @throws NullPointerException    if catalog or listener is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public SegmentReindexScheduler(SegmentCatalog catalog, ReindexListener listener,
                                    long checkIntervalMs, long defaultReindexIntervalMs) {
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("Listener cannot be null");
        }

        validateCheckInterval(checkIntervalMs);
        validateReindexInterval(defaultReindexIntervalMs);

        this.catalog = catalog;
        this.listener = listener;
        this.sourceSchedules = new ConcurrentHashMap<>();

        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);

        this.totalReindexesTriggered = new AtomicLong(0);
        this.totalReindexesCompleted = new AtomicLong(0);
        this.totalReindexesFailed = new AtomicLong(0);
        this.lastCheckTime = new AtomicLong(0);

        this.checkIntervalMs = checkIntervalMs;
        this.defaultReindexIntervalMs = defaultReindexIntervalMs;

        // Use virtual thread executor
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("excalibur-reindex-scheduler").factory());

        LOGGER.info("SegmentReindexScheduler created with checkInterval={}ms, defaultReindexInterval={}ms",
            checkIntervalMs, defaultReindexIntervalMs);
    }

    // ==================== Configuration Validation ====================

    private void validateCheckInterval(long intervalMs) {
        if (intervalMs < MIN_CHECK_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Check interval must be at least " + MIN_CHECK_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateReindexInterval(long intervalMs) {
        if (intervalMs < MIN_REINDEX_INTERVAL_MS || intervalMs > MAX_REINDEX_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Re-index interval must be between " + MIN_REINDEX_INTERVAL_MS +
                " and " + MAX_REINDEX_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background re-index scheduler.
     *
     * <p>After starting, the scheduler will periodically check for:
     * <ul>
     *   <li>Sources that need re-indexing based on their interval</li>
     *   <li>Segments that are stale and need refreshing</li>
     * </ul>
     *
     * <p>If the scheduler is already running, this method does nothing.</p>
     *
     * @return true if the scheduler was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            // CRITICAL FIX: Create new scheduler if the previous one was terminated
            // Must check both isShutdown() and isTerminated() to handle race conditions
            // where shutdown() was called but termination is still in progress
            if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
                this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("excalibur-reindex-scheduler").factory());
            }

            scheduler.scheduleAtFixedRate(
                this::performSchedulerCheck,
                checkIntervalMs,
                checkIntervalMs,
                TimeUnit.MILLISECONDS
            );

            LOGGER.info("Segment re-index scheduler started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background re-index scheduler.
     *
     * <p>This method signals the scheduler to stop and waits for it to complete
     * any in-progress scheduling operations.</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Segment re-index scheduler stopped. Triggered: {}, completed: {}, failed: {}",
                totalReindexesTriggered.get(), totalReindexesCompleted.get(), totalReindexesFailed.get());
        }
    }

    /**
     * Checks if the scheduler is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Source Registration ====================

    /**
     * Registers a source for re-index scheduling.
     *
     * <p>The source will be re-indexed at its configured interval.</p>
     *
     * @param sourceId          The source ID
     * @param reindexIntervalMs The re-index interval for this source
     */
    public void registerSource(long sourceId, long reindexIntervalMs) {
        validateReindexInterval(reindexIntervalMs);
        sourceSchedules.put(sourceId, new SourceSchedule(sourceId, reindexIntervalMs));
        LOGGER.debug("Registered source {} for re-index scheduling with interval {}ms",
            sourceId, reindexIntervalMs);
    }

    /**
     * Registers a source for re-index scheduling without interval validation.
     *
     * <p><strong>For testing only.</strong> This method bypasses domain validation
     * to allow faster test execution with shorter intervals.</p>
     *
     * @param sourceId          The source ID
     * @param reindexIntervalMs The re-index interval for this source (no min/max enforced)
     */
    void registerSourceUnchecked(long sourceId, long reindexIntervalMs) {
        if (reindexIntervalMs <= 0) {
            throw new IllegalArgumentException("Reindex interval must be positive, got: " + reindexIntervalMs);
        }
        sourceSchedules.put(sourceId, new SourceSchedule(sourceId, reindexIntervalMs));
        LOGGER.debug("Registered source {} for re-index scheduling with interval {}ms (unchecked)",
            sourceId, reindexIntervalMs);
    }

    /**
     * Registers a source with the default re-index interval.
     *
     * @param sourceId The source ID
     */
    public void registerSource(long sourceId) {
        registerSource(sourceId, defaultReindexIntervalMs);
    }

    /**
     * Unregisters a source from re-index scheduling.
     *
     * @param sourceId The source ID
     */
    public void unregisterSource(long sourceId) {
        sourceSchedules.remove(sourceId);
        LOGGER.debug("Unregistered source {} from re-index scheduling", sourceId);
    }

    /**
     * Updates the re-index interval for a source.
     *
     * @param sourceId          The source ID
     * @param reindexIntervalMs The new re-index interval
     */
    public void updateReindexInterval(long sourceId, long reindexIntervalMs) {
        validateReindexInterval(reindexIntervalMs);
        SourceSchedule schedule = sourceSchedules.get(sourceId);
        if (schedule != null) {
            schedule.reindexIntervalMs = reindexIntervalMs;
            LOGGER.debug("Updated re-index interval for source {} to {}ms", sourceId, reindexIntervalMs);
        }
    }

    /**
     * Returns the number of registered sources.
     *
     * @return Registered source count
     */
    public int getRegisteredSourceCount() {
        return sourceSchedules.size();
    }

    // ==================== Manual Re-index ====================

    /**
     * Triggers an immediate re-index for a source.
     *
     * <p>This bypasses the normal scheduling and triggers re-index immediately.</p>
     *
     * @param sourceId The source ID to re-index
     * @return true if re-index was triggered successfully
     */
    public boolean triggerReindexNow(long sourceId) {
        SourceSchedule schedule = sourceSchedules.get(sourceId);
        if (schedule == null) {
            LOGGER.warn("Cannot trigger re-index: source {} not registered", sourceId);
            return false;
        }

        try {
            boolean triggered = listener.onReindexNeeded(sourceId);
            if (triggered) {
                totalReindexesTriggered.incrementAndGet();
                schedule.lastReindexTime = Instant.now();
                LOGGER.info("Triggered immediate re-index for source {}", sourceId);
            }
            return triggered;
        } catch (Exception e) {
            totalReindexesFailed.incrementAndGet();
            LOGGER.error("Failed to trigger re-index for source {}: {}", sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Marks a re-index as completed for a source.
     *
     * @param sourceId The source ID
     */
    public void markReindexComplete(long sourceId) {
        SourceSchedule schedule = sourceSchedules.get(sourceId);
        if (schedule != null) {
            schedule.lastReindexTime = Instant.now();
            schedule.reindexInProgress = false;
            totalReindexesCompleted.incrementAndGet();
            LOGGER.info("Re-index completed for source {}", sourceId);
        }
    }

    /**
     * Marks a re-index as failed for a source.
     *
     * @param sourceId The source ID
     * @param error    The error message
     */
    public void markReindexFailed(long sourceId, String error) {
        SourceSchedule schedule = sourceSchedules.get(sourceId);
        if (schedule != null) {
            schedule.reindexInProgress = false;
            totalReindexesFailed.incrementAndGet();
            LOGGER.error("Re-index failed for source {}: {}", sourceId, error);
        }
    }

    // ==================== Scheduler Check ====================

    /**
     * Performs a scheduler check to identify sources needing re-index.
     *
     * <p>This method is called periodically by the scheduler.</p>
     */
    private void performSchedulerCheck() {
        if (!running.get() || stopped.get()) {
            return;
        }

        lastCheckTime.set(System.currentTimeMillis());
        Instant now = Instant.now();
        int reindexesTriggered = 0;

        try {
            for (SourceSchedule schedule : sourceSchedules.values()) {
                // Skip if re-index is already in progress
                if (schedule.reindexInProgress) {
                    continue;
                }

                // Check if re-index is needed based on interval
                Duration timeSinceLastReindex = Duration.between(schedule.lastReindexTime, now);
                if (timeSinceLastReindex.toMillis() >= schedule.reindexIntervalMs) {
                    try {
                        // Mark as in progress BEFORE calling listener to prevent re-entry
                        schedule.reindexInProgress = true;
                        boolean triggered = listener.onReindexNeeded(schedule.sourceId);
                        if (triggered) {
                            // Only update counters if listener confirmed trigger
                            // Note: listener may have already called markReindexComplete(),
                            // which would have set reindexInProgress=false and updated lastReindexTime.
                            // We only increment triggered count if not already completed.
                            if (schedule.reindexInProgress) {
                                // Listener didn't complete synchronously, keep in-progress state
                                totalReindexesTriggered.incrementAndGet();
                                reindexesTriggered++;
                            }
                            LOGGER.info("Scheduled re-index triggered for source {} (last reindex: {} ago)",
                                schedule.sourceId, formatDuration(timeSinceLastReindex));
                        } else {
                            // Listener rejected, reset in-progress flag
                            schedule.reindexInProgress = false;
                        }
                    } catch (Exception e) {
                        schedule.reindexInProgress = false;
                        totalReindexesFailed.incrementAndGet();
                        LOGGER.error("Failed to trigger scheduled re-index for source {}: {}",
                            schedule.sourceId, e.getMessage());
                    }
                }
            }

            // Also check for stale segments in the catalog
            checkForStaleSegments();

        } catch (Exception e) {
            LOGGER.error("Error during scheduler check", e);
        }
    }

    /**
     * Checks for stale segments that may need re-indexing.
     */
    private void checkForStaleSegments() {
        // Get all ACTIVE segments
        java.util.List<Long> activeSegments = catalog.getSegmentsByState(LifecycleState.ACTIVE);

        for (Long segmentId : activeSegments) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) {
                continue;
            }

            // Check if segment hasn't been updated in a while
            long timeSinceCreation = System.currentTimeMillis() - metadata.getCreationTime();

            // If segment is very old (e.g., older than 24 hours), consider for re-index
            if (timeSinceCreation > MAX_REINDEX_INTERVAL_MS) {
                LOGGER.debug("Segment {} is potentially stale (age: {}ms)", segmentId, timeSinceCreation);
                // Note: Actual re-index triggering would depend on source mapping
            }
        }
    }

    /**
     * Formats a duration for logging.
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm", minutes);
        } else {
            return String.format("%ds", duration.toSeconds());
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of re-indexes triggered.
     *
     * @return Total re-indexes triggered
     */
    public long getTotalReindexesTriggered() {
        return totalReindexesTriggered.get();
    }

    /**
     * Returns the total number of re-indexes completed.
     *
     * @return Total re-indexes completed
     */
    public long getTotalReindexesCompleted() {
        return totalReindexesCompleted.get();
    }

    /**
     * Returns the total number of re-indexes failed.
     *
     * @return Total re-indexes failed
     */
    public long getTotalReindexesFailed() {
        return totalReindexesFailed.get();
    }

    /**
     * Returns the timestamp of the last scheduler check.
     *
     * @return Last check time in epoch milliseconds
     */
    public long getLastCheckTime() {
        return lastCheckTime.get();
    }

    /**
     * Gets the next scheduled re-index time for a source.
     *
     * @param sourceId The source ID
     * @return The next re-index time, or null if not scheduled
     */
    public Instant getNextReindexTime(long sourceId) {
        SourceSchedule schedule = sourceSchedules.get(sourceId);
        if (schedule == null) {
            return null;
        }
        return schedule.lastReindexTime.plusMillis(schedule.reindexIntervalMs);
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the check interval in milliseconds.
     *
     * @return Check interval
     */
    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /**
     * Sets the check interval in milliseconds.
     *
     * @param intervalMs The new interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setCheckIntervalMs(long intervalMs) {
        validateCheckInterval(intervalMs);
        this.checkIntervalMs = intervalMs;
    }

    /**
     * Returns the default re-index interval in milliseconds.
     *
     * @return Default re-index interval
     */
    public long getDefaultReindexIntervalMs() {
        return defaultReindexIntervalMs;
    }

    /**
     * Sets the default re-index interval in milliseconds.
     *
     * @param intervalMs The new default interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setDefaultReindexIntervalMs(long intervalMs) {
        validateReindexInterval(intervalMs);
        this.defaultReindexIntervalMs = intervalMs;
    }

    // ==================== Test-Friendly Methods ====================

    /**
     * Sets the check interval in milliseconds without validation.
     *
     * <p><strong>For testing only.</strong> This method bypasses domain validation
     * to allow faster test execution. Using intervals below {@link #MIN_CHECK_INTERVAL_MS}
     * in production is not recommended.</p>
     *
     * @param intervalMs The new interval (no minimum enforced)
     */
    void setCheckIntervalMsUnchecked(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Interval must be positive, got: " + intervalMs);
        }
        this.checkIntervalMs = intervalMs;
    }

    /**
     * Sets the default re-index interval in milliseconds without validation.
     *
     * <p><strong>For testing only.</strong> This method bypasses domain validation
     * to allow faster test execution. Using intervals below {@link #MIN_REINDEX_INTERVAL_MS}
     * in production is not recommended.</p>
     *
     * @param intervalMs The new default interval (no min/max enforced)
     */
    void setDefaultReindexIntervalMsUnchecked(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Interval must be positive, got: " + intervalMs);
        }
        this.defaultReindexIntervalMs = intervalMs;
    }

    // ==================== Listener Interface ====================

    /**
     * Callback interface for re-index events.
     */
    @FunctionalInterface
    public interface ReindexListener {

        /**
         * Called when a source needs to be re-indexed.
         *
         * @param sourceId The source ID to re-index
         * @return true if re-index was initiated successfully
         */
        boolean onReindexNeeded(long sourceId);
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for source schedule information.
     */
    private static class SourceSchedule {
        final long sourceId;
        volatile long reindexIntervalMs;
        volatile Instant lastReindexTime;
        volatile boolean reindexInProgress;

        SourceSchedule(long sourceId, long reindexIntervalMs) {
            this.sourceId = sourceId;
            this.reindexIntervalMs = reindexIntervalMs;
            this.lastReindexTime = Instant.EPOCH;
            this.reindexInProgress = false;
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "SegmentReindexScheduler{running=%s, sources=%d, triggered=%d, completed=%d, failed=%d}",
            running.get(), sourceSchedules.size(),
            totalReindexesTriggered.get(), totalReindexesCompleted.get(), totalReindexesFailed.get());
    }
}
