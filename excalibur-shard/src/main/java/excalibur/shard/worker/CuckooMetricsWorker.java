package excalibur.shard.worker;

import excalibur.filter.CuckooFilter;
import io.excalibur.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread worker for periodic Cuckoo filter false positive rate metrics collection.
 *
 * <p>This worker periodically samples false positive rates from all registered Cuckoo filters
 * and records them to the metrics system for JMX observability.</p>
 *
 * <h2>Metrics Collected</h2>
 * <ul>
 *   <li>Per-segment estimated FP rate (via {@link CuckooFilter#estimateFpRate()})</li>
 *   <li>Aggregate FP rate statistics (average, max, count)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread scheduler</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Uses CopyOnWriteArrayList for filter registration</li>
 *   <li>Read operations do not block writes</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Sampling interval: 1 second to 1 minute (default 5 seconds)</li>
 *   <li>FP rate values: 0.0 to 1.0 (clamped before recording)</li>
 *   <li>Expected FP rate for well-configured filters: ≤ 0.1%</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see CuckooFilter
 * @see Metrics#recordCuckooFpRate(double)
 */
public class CuckooMetricsWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CuckooMetricsWorker.class);

    // ==================== Configuration Constants ====================

    /**
     * Default interval between FP rate sampling cycles in milliseconds.
     * Domain constraint: Must be at least 1000ms (1 second).
     */
    public static final long DEFAULT_SAMPLING_INTERVAL_MS = 5000; // 5 seconds

    /**
     * Minimum allowed sampling interval in milliseconds.
     */
    public static final long MIN_SAMPLING_INTERVAL_MS = 1000; // 1 second

    /**
     * Maximum allowed sampling interval in milliseconds.
     */
    public static final long MAX_SAMPLING_INTERVAL_MS = 60000; // 1 minute

    // ==================== Instance Fields ====================

    private final CopyOnWriteArrayList<FilterEntry> registeredFilters;
    private final AtomicBoolean running;
    private final AtomicBoolean stopped;
    private final AtomicLong totalSamplesRecorded;
    private final AtomicLong lastSampleTime;
    private volatile long samplingIntervalMs;
    private volatile ScheduledExecutorService scheduler;

    // ==================== Constructors ====================

    /**
     * Creates a new CuckooMetricsWorker with default sampling interval (5 seconds).
     */
    public CuckooMetricsWorker() {
        this(DEFAULT_SAMPLING_INTERVAL_MS);
    }

    /**
     * Creates a new CuckooMetricsWorker with custom sampling interval.
     *
     * @param samplingIntervalMs Interval between sampling cycles (ms)
     * @throws IllegalArgumentException if sampling interval is invalid
     */
    public CuckooMetricsWorker(long samplingIntervalMs) {
        validateSamplingInterval(samplingIntervalMs);

        this.registeredFilters = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.totalSamplesRecorded = new AtomicLong(0);
        this.lastSampleTime = new AtomicLong(0);
        this.samplingIntervalMs = samplingIntervalMs;

        // Use virtual thread executor
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("excalibur-cuckoo-metrics").factory());

        LOGGER.info("CuckooMetricsWorker created with samplingInterval={}ms", samplingIntervalMs);
    }

    // ==================== Configuration Validation ====================

    private void validateSamplingInterval(long intervalMs) {
        if (intervalMs < MIN_SAMPLING_INTERVAL_MS || intervalMs > MAX_SAMPLING_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Sampling interval must be between " + MIN_SAMPLING_INTERVAL_MS +
                " and " + MAX_SAMPLING_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background metrics collection worker.
     *
     * <p>After starting, the worker will periodically:</p>
     * <ol>
     *   <li>Iterate through all registered Cuckoo filters</li>
     *   <li>Call {@link CuckooFilter#estimateFpRate()} on each</li>
     *   <li>Record the FP rate via {@link Metrics#recordCuckooFpRate(double)}</li>
     * </ol>
     *
     * <p>If the worker is already running, this method does nothing.</p>
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            // CRITICAL FIX: Create new scheduler if the previous one was terminated
            // Must check both isShutdown() and isTerminated() to handle race conditions
            // where shutdown() was called but termination is still in progress
            if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
                this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("excalibur-cuckoo-metrics").factory());
            }

            scheduler.scheduleAtFixedRate(
                this::performSamplingCycle,
                samplingIntervalMs,
                samplingIntervalMs,
                TimeUnit.MILLISECONDS
            );

            LOGGER.info("Cuckoo metrics worker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background metrics collection worker.
     *
     * <p>This method signals the worker to stop and waits for it to complete
     * any in-progress sampling operations.</p>
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

            LOGGER.info("Cuckoo metrics worker stopped. Total samples recorded: {}",
                totalSamplesRecorded.get());
        }
    }

    /**
     * Checks if the worker is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Filter Registration ====================

    /**
     * Registers a segment's Cuckoo filter for FP rate monitoring.
     *
     * @param segmentId The segment ID
     * @param filter   The Cuckoo filter for the segment
     */
    public void registerFilter(long segmentId, CuckooFilter filter) {
        if (filter == null) {
            return;
        }
        // Check if filter already registered
        boolean alreadyRegistered = false;
        for (FilterEntry entry : registeredFilters) {
            if (entry.segmentId == segmentId) {
                alreadyRegistered = true;
                break;
            }
        }
        if (!alreadyRegistered) {
            registeredFilters.add(new FilterEntry(segmentId, filter));
            LOGGER.debug("Registered Cuckoo filter for FP rate monitoring: segment {}", segmentId);
        }
    }

    /**
     * Unregisters a segment's Cuckoo filter from FP rate monitoring.
     *
     * @param segmentId The segment ID
     */
    public void unregisterFilter(long segmentId) {
        registeredFilters.removeIf(entry -> entry.segmentId == segmentId);
        LOGGER.debug("Unregistered Cuckoo filter from FP rate monitoring: segment {}", segmentId);
    }

    /**
     * Returns the number of registered filters being monitored.
     *
     * @return Registered filter count
     */
    public int getRegisteredFilterCount() {
        return registeredFilters.size();
    }

    // ==================== Sampling Cycle ====================

    /**
     * Performs a sampling cycle.
     *
     * <p>This method is called periodically by the scheduler and:</p>
     * <ol>
     *   <li>Iterates through all registered filters</li>
     *   <li>Estimates FP rate for each filter</li>
     *   <li>Records aggregate FP rate to metrics system</li>
     * </ol>
     */
    private void performSamplingCycle() {
        if (!running.get() || stopped.get()) {
            return;
        }

        if (registeredFilters.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int samplesRecorded = 0;
        double totalFpRate = 0.0;
        double maxFpRate = 0.0;
        int filtersWithEntries = 0;

        try {
            for (FilterEntry entry : registeredFilters) {
                try {
                    // Skip empty filters (no meaningful FP rate)
                    if (entry.filter.count() == 0) {
                        continue;
                    }

                    double fpRate = entry.filter.estimateFpRate();
                    totalFpRate += fpRate;
                    if (fpRate > maxFpRate) {
                        maxFpRate = fpRate;
                    }
                    filtersWithEntries++;
                    samplesRecorded++;

                    // Log per-segment FP rate at trace level for debugging
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Segment {} FP rate: {} (load factor: {})",
                            entry.segmentId, String.format("%.6f", fpRate),
                            String.format("%.4f", entry.filter.loadFactor()));
                    }
                } catch (Exception e) {
                    LOGGER.warn("Error estimating FP rate for segment {}: {}",
                        entry.segmentId, e.getMessage());
                }
            }

            // Record aggregate metrics if we have any samples
            if (filtersWithEntries > 0) {
                // Record the average FP rate across all non-empty filters
                double avgFpRate = totalFpRate / filtersWithEntries;
                Metrics.recordCuckooFpRate(avgFpRate);

                totalSamplesRecorded.addAndGet(samplesRecorded);
                lastSampleTime.set(System.currentTimeMillis());

                long duration = System.currentTimeMillis() - startTime;

                // Log at debug level to avoid spamming logs
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Cuckoo FP rate sampling complete: avg={}, max={}, " +
                        "filters={}, samples={}, duration={}ms",
                        String.format("%.6f", avgFpRate), String.format("%.6f", maxFpRate),
                        filtersWithEntries, samplesRecorded, duration);
                }

                // Warn if FP rate is higher than expected (>0.1%)
                if (avgFpRate > 0.001) {
                    LOGGER.warn("Cuckoo filter FP rate ({}%) exceeds target (0.1%). " +
                        "Consider increasing fingerprint bits or reducing load factor.",
                        String.format("%.4f", avgFpRate * 100));
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error during Cuckoo FP rate sampling cycle", e);
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of FP rate samples recorded.
     *
     * @return Total samples recorded
     */
    public long getTotalSamplesRecorded() {
        return totalSamplesRecorded.get();
    }

    /**
     * Returns the timestamp of the last sampling cycle.
     *
     * @return Last sample time in epoch milliseconds, or 0 if no samples taken
     */
    public long getLastSampleTime() {
        return lastSampleTime.get();
    }

    /**
     * Returns the sampling interval in milliseconds.
     *
     * @return Sampling interval
     */
    public long getSamplingIntervalMs() {
        return samplingIntervalMs;
    }

    /**
     * Sets the sampling interval in milliseconds.
     *
     * <p>Note: This only takes effect after the next restart of the worker.</p>
     *
     * @param intervalMs The new interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setSamplingIntervalMs(long intervalMs) {
        validateSamplingInterval(intervalMs);
        this.samplingIntervalMs = intervalMs;
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for a registered filter entry.
     */
    private static class FilterEntry {
        final long segmentId;
        final CuckooFilter filter;

        FilterEntry(long segmentId, CuckooFilter filter) {
            this.segmentId = segmentId;
            this.filter = filter;
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "CuckooMetricsWorker{running=%s, filters=%d, samplesRecorded=%d, intervalMs=%d}",
            running.get(), registeredFilters.size(), totalSamplesRecorded.get(), samplingIntervalMs);
    }
}
