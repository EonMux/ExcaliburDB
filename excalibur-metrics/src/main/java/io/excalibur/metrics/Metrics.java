package io.excalibur.metrics;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics registry for ExcaliburDB.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe for concurrent access. All methods use
 * synchronized operations or atomic variables to ensure thread safety.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Query latency histogram with percentile support</li>
 *   <li>Throughput counters for ingestion and queries</li>
 *   <li>System health gauge</li>
 *   <li>JMX MBean registration</li>
 *   <li>Visibility epoch tracking</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class Metrics {

    // ============================================
    // Static MBean Instances for Notification Emission
    // ============================================

    private static final QueryLatencyMBean queryLatencyMBeanInstance = new QueryLatencyMBean();
    private static final SystemHealthMBean systemHealthMBeanInstance = new SystemHealthMBean();
    private static final IngestionQueueMBean ingestionQueueMBeanInstance = new IngestionQueueMBean();
    private static final VisibilityEpochMBean visibilityEpochMBeanInstance = new VisibilityEpochMBean();
    private static final CuckooFpRateMBean cuckooFpRateMBeanInstance = new CuckooFpRateMBean();
    private static final HnswQueueDepthMBean hnswQueueDepthMBeanInstance = new HnswQueueDepthMBean();
    private static final IngestionThroughputMBean ingestionThroughputMBeanInstance = new IngestionThroughputMBean();

    /**
     * Gets the QueryLatency MBean instance.
     *
     * @return The QueryLatency MBean
     */
    public static QueryLatencyMBean getQueryLatencyMBean() {
        return queryLatencyMBeanInstance;
    }

    /**
     * Gets the SystemHealth MBean instance.
     *
     * @return The SystemHealth MBean
     */
    public static SystemHealthMBean getSystemHealthMBean() {
        return systemHealthMBeanInstance;
    }

    /**
     * Gets the IngestionQueue MBean instance.
     *
     * @return The IngestionQueue MBean
     */
    public static IngestionQueueMBean getIngestionQueueMBean() {
        return ingestionQueueMBeanInstance;
    }

    /**
     * Gets the VisibilityEpoch MBean instance.
     *
     * @return The VisibilityEpoch MBean
     */
    public static VisibilityEpochMBean getVisibilityEpochMBean() {
        return visibilityEpochMBeanInstance;
    }

    /**
     * Gets the CuckooFpRate MBean instance.
     *
     * @return The CuckooFpRate MBean
     */
    public static CuckooFpRateMBean getCuckooFpRateMBean() {
        return cuckooFpRateMBeanInstance;
    }

    /**
     * Gets the HnswQueueDepth MBean instance.
     *
     * @return The HnswQueueDepth MBean
     */
    public static HnswQueueDepthMBean getHnswQueueDepthMBean() {
        return hnswQueueDepthMBeanInstance;
    }

    /**
     * Gets the IngestionThroughput MBean instance.
     *
     * @return The IngestionThroughput MBean
     */
    public static IngestionThroughputMBean getIngestionThroughputMBean() {
        return ingestionThroughputMBeanInstance;
    }

    // ============================================
    // Query Latency
    // ============================================

    private static final FixedBucketHistogram queryLatencyHistogram = new FixedBucketHistogram();

    /**
     * Gets the query latency histogram.
     */
    public static FixedBucketHistogram QueryLatency() {
        return queryLatencyHistogram;
    }

    /**
     * Gets the p0 (minimum) query latency.
     * This is the minimum recorded latency, representing the 0th percentile.
     */
    public static long getP0() {
        return queryLatencyHistogram.getMinimum();
    }

    /**
     * Gets the p50 query latency.
     */
    public static long getP50() {
        return queryLatencyHistogram.getPercentile(50.0);
    }

    /**
     * Gets the p90 query latency.
     */
    public static long getP90() {
        return queryLatencyHistogram.getPercentile(90.0);
    }

    /**
     * Gets the p95 query latency.
     */
    public static long getP95() {
        return queryLatencyHistogram.getPercentile(95.0);
    }

    /**
     * Gets the p99 query latency.
     */
    public static long getP99() {
        return queryLatencyHistogram.getPercentile(99.0);
    }

    /**
     * Gets the p99.9 query latency.
     */
    public static long getP999() {
        return queryLatencyHistogram.getPercentile(99.9);
    }

    /**
     * Records a query latency measurement.
     *
     * @param latencyMs Latency in milliseconds
     */
    public static void recordQueryLatency(long latencyMs) {
        queryLatencyHistogram.record(latencyMs);
        // Emit notification
        queryLatencyMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Query latency recorded: " + latencyMs + "ms",
            latencyMs
        );
    }

    /**
     * Records a query latency measurement with epoch.
     *
     * @param latencyMs Latency in milliseconds
     * @param epoch Current visibility epoch
     */
    public static void recordQueryLatency(long latencyMs, long epoch) {
        queryLatencyHistogram.record(latencyMs, epoch);
        // Emit notification
        queryLatencyMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Query latency recorded: " + latencyMs + "ms (epoch: " + epoch + ")",
            latencyMs
        );
    }

    // ============================================
    // Throughput Counters
    // ============================================

    private static final AtomicLong entriesIngested = new AtomicLong(0);
    private static final AtomicLong queriesExecuted = new AtomicLong(0);

    // Ingestion rate tracking
    private static final Object ingestionRateLock = new Object();
    private static volatile long lastRateCalculationTimestamp = System.currentTimeMillis();
    private static volatile long entriesIngestedAtLastRateCalculation = 0;
    private static volatile double currentIngestionRate = 0.0;
    private static volatile double peakIngestionRate = 0.0;

    /**
     * Gets the total number of entries ingested.
     */
    public static long getEntriesIngested() {
        return entriesIngested.get();
    }

    /**
     * Records an ingestion entry.
     */
    public static void recordIngestEntry() {
        recordIngestEntries(1);
    }

    /**
     * Records a batch of ingested entries.
     *
     * <p>One call per document rather than per chunk, so the ingest path pays for a single
     * notification however many chunks a document produced.</p>
     *
     * @param count How many entries were ingested; non-positive is ignored
     */
    public static void recordIngestEntries(long count) {
        if (count <= 0) {
            return;
        }
        long total = entriesIngested.addAndGet(count);
        // Emit notification for ingestion throughput tracking
        ingestionThroughputMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.INGESTION_THROUGHPUT,
            "Entries ingested: total=" + total,
            total
        );
    }

    /**
     * Gets the total number of queries executed.
     */
    public static long getQueriesExecuted() {
        return queriesExecuted.get();
    }

    /**
     * Records a query execution.
     */
    public static void recordQueryExecution() {
        queriesExecuted.incrementAndGet();
    }

    /**
     * Gets the current ingestion rate (entries per second).
     *
     * <p>Domain: Returns a value in range [0, Long.MAX_VALUE]. Typical values
     * for production systems: 100-10000 entries/second.</p>
     *
     * @return Current ingestion rate in entries per second
     */
    public static double getIngestionRate() {
        return currentIngestionRate;
    }

    /**
     * Gets the peak ingestion rate (entries per second) since startup.
     *
     * @return Peak ingestion rate in entries per second
     */
    public static double getPeakIngestionRate() {
        return peakIngestionRate;
    }

    /**
     * Updates the ingestion rate calculation.
     * Should be called periodically to track throughput over time.
     *
     * <p>Domain correctness: Rate is the number of entries ingested since the
     * previous call divided by the time elapsed since that call, so it reflects
     * current throughput rather than a lifetime average and drops to zero while
     * ingestion is idle. Peak rate is the maximum of those interval rates.
     * Values are non-negative.</p>
     */
    public static void updateIngestionRate() {
        synchronized (ingestionRateLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRateCalculationTimestamp;
            if (elapsed <= 0) {
                return;
            }
            long entries = entriesIngested.get();
            long ingestedInInterval = entries - entriesIngestedAtLastRateCalculation;
            currentIngestionRate = (ingestedInInterval * 1000.0) / elapsed;
            if (currentIngestionRate > peakIngestionRate) {
                peakIngestionRate = currentIngestionRate;
            }
            entriesIngestedAtLastRateCalculation = entries;
            lastRateCalculationTimestamp = now;
        }
    }

    // ============================================
    // System Health
    // ============================================

    /** Health score at or above which the system counts as healthy and ready. */
    private static final double HEALTHY_SCORE_THRESHOLD = 0.5;

    private static final AtomicReference<Double> healthScore = new AtomicReference<>(0.5);

    /**
     * Clamps a rate or score into [0.0, 1.0], mapping NaN to 0.0.
     *
     * <p>{@code Math.max}/{@code Math.min} alone let NaN through, and a NaN published as a
     * health score or a coverage figure is rejected downstream by the stats endpoint rather
     * than read as "unknown".</p>
     *
     * @param value Raw value
     * @return Value clamped to the valid domain
     */
    private static double clampToUnitInterval(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Gets the system health score (0.0-1.0).
     */
    public static double getHealthScore() {
        return healthScore.get();
    }

    /**
     * Sets the system health score.
     *
     * @param score Health score between 0.0 and 1.0
     */
    public static void setHealthScore(double score) {
        double clamped = clampToUnitInterval(score);
        healthScore.set(clamped);
        // Emit notification
        systemHealthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
            "System health score changed: " + clamped,
            clamped
        );
    }

    /**
     * Gets whether the system is healthy.
     *
     * <p>Derived from the health score rather than stored separately: a flag with its own
     * storage can be left behind by a score update, and then the {@code isHealthy} and
     * {@code ready} attributes of the same MBean contradict each other.</p>
     *
     * @return true when the health score is at or above the healthy threshold
     */
    public static boolean isHealthy() {
        return getHealthScore() >= HEALTHY_SCORE_THRESHOLD;
    }

    /**
     * Updates health based on latency threshold.
     *
     * <p>The score is {@code 1.0 - latencyMs / thresholdMs} clamped to
     * [0.0, 1.0], so a query at the latency budget scores 0.0 and an instant
     * one scores 1.0. {@link #isHealthy()} follows it in both directions.</p>
     *
     * @param latencyMs Query latency in ms
     * @param thresholdMs Latency threshold in ms, must be positive
     * @throws IllegalArgumentException if thresholdMs is not positive
     */
    public static void updateHealth(long latencyMs, long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("thresholdMs must be positive, got: " + thresholdMs);
        }
        double latencyRatio = (double) latencyMs / thresholdMs;
        healthScore.set(clampToUnitInterval(1.0 - latencyRatio));
    }

    // ============================================
    // Ingestion Queue Depth
    // ============================================

    // Note: queueDepth AtomicLong retained for backward compatibility but no longer used.
    // IngestionQueueMBean now delegates to HNSW queue depth methods.

    /**
     * Gets the current ingestion queue depth.
     * Delegates to HNSW queue depth for consistency with actual ingestion backpressure tracking.
     */
    public static long getQueueDepth() {
        return getHnswQueueDepth();
    }

    /**
     * Records the current queue depth.
     * This method updates HNSW depth tracking and emits INGESTION_QUEUE notification
     * to maintain consistency between IngestionQueueMBean and HnswQueueDepthMBean metrics.
     *
     * @param depth Current queue depth
     */
    public static void recordQueueDepth(long depth) {
        // Update HNSW depth tracking (used by both MBeans)
        hnswQueueDepth.set(depth);
        // Emit HNSW notification
        hnswQueueDepthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH,
            "HNSW queue depth: " + depth,
            depth
        );
        // Emit IngestionQueue notification for backward compatibility
        ingestionQueueMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE,
            "Ingestion queue depth: " + depth,
            depth
        );
    }

    /**
     * Gets the queue depth percentage.
     * Delegates to HNSW queue depth for consistency.
     *
     * @param limit Maximum queue depth limit
     * @return Percentage of limit
     */
    public static double getQueueDepthPercent(long limit) {
        return getHnswQueueDepthPercent(limit);
    }

    /**
     * Checks if the queue is under control (&lt; 50% of limit).
     * Delegates to HNSW queue for consistency.
     */
    public static boolean isUnderControl(long limit) {
        return isHnswUnderControl(limit);
    }

    /**
     * Checks if the queue is critical (&gt; 80% of limit).
     * Delegates to HNSW queue for consistency.
     */
    public static boolean isCritical(long limit) {
        return isHnswCritical(limit);
    }

    /**
     * Checks if the queue is full (&gt; 100% of limit).
     * Delegates to HNSW queue for consistency.
     */
    public static boolean isFull(long limit) {
        return isHnswFull(limit);
    }

    // ============================================
    // Visibility Epoch
    // ============================================

    private static final AtomicLong currentEpoch = new AtomicLong(0);

    /**
     * Gets the current visibility epoch.
     */
    public static long getCurrentEpoch() {
        return currentEpoch.get();
    }

    /**
     * Records a new epoch.
     *
     * @param epoch New epoch number
     */
    public static void recordEpoch(long epoch) {
        currentEpoch.set(epoch);
        // Keep the shared histogram's epoch in step so FP rate samples are stamped correctly
        FixedBucketHistogram.getInstance().setEpoch(epoch);
        // Emit notification
        visibilityEpochMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH,
            "Visibility epoch advanced: " + epoch,
            epoch
        );
    }

    // ============================================
    // Estimated Coverage
    // ============================================

    private static final AtomicReference<Double> estimatedCoverage = new AtomicReference<>(0.0);

    /**
     * Gets the estimated corpus coverage.
     */
    public static double getEstimatedCoverage() {
        return estimatedCoverage.get();
    }

    /**
     * Updates the estimated coverage.
     *
     * @param coverage Coverage value between 0.0 and 1.0
     */
    public static void setEstimatedCoverage(double coverage) {
        estimatedCoverage.set(clampToUnitInterval(coverage));
    }

    // ============================================
    // Tail Scan Metrics
    // ============================================

    /**
     * Total number of tail scan operations performed.
     * Each call to recordTailScanMetrics() increments this by 1.
     */
    private static final AtomicLong totalTailScanCount = new AtomicLong(0);

    /**
     * Total number of entries scanned across all tail scan operations.
     * This accumulates the entriesScanned values from all scans.
     */
    private static final AtomicLong totalTailScanned = new AtomicLong(0);

    /**
     * Maximum number of entries scanned in a single tail scan operation.
     * Updated atomically using CAS loop for thread safety.
     */
    private static final AtomicLong maxTailScanEntries = new AtomicLong(0);

    /**
     * Total number of scanned entries that carried a valid vector.
     * This accumulates the vectorEntriesFound values from all scans.
     */
    private static final AtomicLong totalVectorEntriesFound = new AtomicLong(0);

    /**
     * Records metrics from a tail scan operation.
     *
     * <p>This method should be called by TailScanner.performTailScan() after
     * completing a scan over un-integrated write log entries. It tracks:</p>
     * <ul>
     *   <li>Total number of tail scan operations performed</li>
     *   <li>Total entries scanned across all operations</li>
     *   <li>Maximum entries scanned in a single operation</li>
     *   <li>Total entries carrying a valid vector across all operations</li>
     * </ul>
     *
     * <p><strong>Domain correctness:</strong> Values should be non-negative.
     * Entries scanned represents log entries; vector entries found represents
     * entries with valid vectors. Typical values: 0-100,000 entries per scan
     * (bounded by backpressure limit).</p>
     *
     * @param entriesScanned Number of log entries scanned (including non-vector entries)
     * @param vectorEntriesFound Number of entries with valid vectors found
     */
    public static void recordTailScanMetrics(int entriesScanned, int vectorEntriesFound) {
        // Increment the count of tail scan operations
        totalTailScanCount.incrementAndGet();

        // Add to the total entries scanned
        totalTailScanned.addAndGet(entriesScanned);

        // Add to the total entries that carried a valid vector
        totalVectorEntriesFound.addAndGet(vectorEntriesFound);

        // Update max using CAS loop for thread safety without locks
        long currentMax = maxTailScanEntries.get();
        while (entriesScanned > currentMax) {
            if (maxTailScanEntries.compareAndSet(currentMax, entriesScanned)) {
                break;
            }
            currentMax = maxTailScanEntries.get();
        }
    }

    /**
     * Gets the average number of entries scanned per tail scan operation.
     *
     * <p>Domain: Returns a value in range [0, 100000] (bounded by backpressure limit).
     * Returns 0 if no tail scans have been performed.</p>
     *
     * @return Average entries scanned per operation, or 0 if no scans performed
     */
    public static long getAverageTailScanEntries() {
        long total = totalTailScanned.get();
        long count = totalTailScanCount.get();
        return count == 0 ? 0 : total / count;
    }

    /**
     * Gets the maximum number of entries scanned in a single tail scan operation.
     *
     * <p>Domain: Returns a value in range [0, 100000] (bounded by backpressure limit).
     * Returns 0 if no tail scans have been performed.</p>
     *
     * @return Maximum entries scanned in any single operation
     */
    public static long getMaxTailScanEntries() {
        return maxTailScanEntries.get();
    }

    /**
     * Gets the total number of tail scan operations performed.
     *
     * @return Total count of tail scan operations
     */
    public static long getTotalTailScanCount() {
        return totalTailScanCount.get();
    }

    /**
     * Gets the total number of scanned entries that carried a valid vector.
     *
     * <p>Together with {@link #getTotalTailScanned()} this gives the vector hit
     * ratio of tail scanning.</p>
     *
     * @return Total entries with a valid vector across all tail scans
     */
    public static long getTotalVectorEntriesFound() {
        return totalVectorEntriesFound.get();
    }

    // ============================================
    // HNSW Queue Depth
    // ============================================

    private static final AtomicLong hnswQueueDepth = new AtomicLong(0);

    /**
     * Default HNSW integration queue depth limit. Matches the default of
     * {@code excalibur.shard.hnsw-integration-queue-depth-limit}.
     */
    private static final long DEFAULT_HNSW_QUEUE_LIMIT = 100_000;

    private static final AtomicLong hnswQueueLimit = new AtomicLong(DEFAULT_HNSW_QUEUE_LIMIT);

    /**
     * Gets the HNSW integration queue depth limit that the queue MBeans
     * evaluate depth against.
     *
     * @return Configured queue depth limit
     */
    public static long getHnswQueueLimit() {
        return hnswQueueLimit.get();
    }

    /**
     * Sets the HNSW integration queue depth limit.
     * Callers should pass {@code excalibur.shard.hnsw-integration-queue-depth-limit}
     * so JMX verdicts match the limit that actually applies backpressure.
     *
     * @param limit Queue depth limit, must be positive
     * @throws IllegalArgumentException if limit is not positive
     */
    public static void setHnswQueueLimit(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("HNSW queue limit must be positive, got: " + limit);
        }
        hnswQueueLimit.set(limit);
    }

    /**
     * Gets the current HNSW queue depth.
     */
    public static long getHnswQueueDepth() {
        return hnswQueueDepth.get();
    }

    /**
     * Records the current HNSW queue depth.
     *
     * @param depth Current queue depth
     */
    public static void recordHnswDepth(long depth) {
        hnswQueueDepth.set(depth);
        // Emit notification
        hnswQueueDepthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH,
            "HNSW queue depth: " + depth,
            depth
        );
    }

    // ============================================
    // Cuckoo Filter FP Rate
    // ============================================

    /**
     * Records a Cuckoo filter false positive rate measurement.
     *
     * <p>This method should be called periodically by a background worker that
     * samples FP rates from active Cuckoo filters across all segments.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>FP rate must be between 0.0 and 1.0 (values are clamped)</li>
     *   <li>Expected range for well-configured filters: 0.0001 to 0.001 (0.01% to 0.1%)</li>
     * </ul>
     *
     * @param fpRate False positive rate between 0.0 and 1.0
     */
    public static void recordCuckooFpRate(double fpRate) {
        // Clamp to valid range [0.0, 1.0]
        double clamped = clampToUnitInterval(fpRate);

        FixedBucketHistogram.getInstance().recordFpRate(clamped, getCurrentEpoch());

        // Emit notification for JMX listeners
        cuckooFpRateMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.CUCKOO_FP_RATE,
            "Cuckoo FP rate recorded: " + clamped,
            clamped
        );
    }

    /**
     * Gets the HNSW queue depth percentage.
     *
     * @param limit Maximum queue depth limit
     * @return Percentage of limit
     */
    public static double getHnswQueueDepthPercent(long limit) {
        if (limit <= 0) {
            return 0.0;
        }
        long depth = getHnswQueueDepth();
        double percent = (double) depth / limit * 100.0;
        return Math.max(0.0, Math.min(100.0, percent));
    }

    /**
     * Checks if the HNSW queue is under control (&lt; 50% of limit).
     */
    public static boolean isHnswUnderControl(long limit) {
        return getHnswQueueDepth() < limit * 0.5;
    }

    /**
     * Checks if the HNSW queue is critical (&gt; 80% of limit).
     */
    public static boolean isHnswCritical(long limit) {
        return getHnswQueueDepth() > limit * 0.8;
    }

     /**
     * Checks if the HNSW queue is full (&gt; 100% of limit).
     */
    public static boolean isHnswFull(long limit) {
        return getHnswQueueDepth() > limit;
    }

    /**
     * Gets the total number of entries scanned across all tail scan operations.
     *
     * <p>This is the sum of all entriesScanned values reported via
     * {@link #recordTailScanMetrics(int, int)}. Use this to understand
     * cumulative tail scan activity over time.</p>
     *
     * @return Total entries scanned across all operations
     */
    public static long getTotalTailScanned() {
        return totalTailScanned.get();
    }

    // ============================================
    // JMX MBean Registration
    // ============================================

    /**
     * Registers the query latency MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerQueryLatencyMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(queryLatencyMBeanInstance, "io.excalibur:type=QueryLatency");
    }

    /**
     * Registers the system health MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerSystemHealthMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(systemHealthMBeanInstance, "io.excalibur:type=SystemHealth");
    }

    /**
     * Registers the ingestion queue MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerIngestionQueueMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(ingestionQueueMBeanInstance, "io.excalibur:type=IngestionQueue");
    }

    /**
     * Registers the visibility epoch MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerVisibilityEpochMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(visibilityEpochMBeanInstance, "io.excalibur:type=VisibilityEpoch");
    }

    /**
     * Registers the Cuckoo filter FP rate MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerCuckooFpRateMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(cuckooFpRateMBeanInstance, "io.excalibur:type=CuckooFpRate");
    }

    /**
     * Registers the HNSW queue depth MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerHnswQueueDepthMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(hnswQueueDepthMBeanInstance, "io.excalibur:type=HnswQueueDepth");
    }

    /**
     * Registers the ingestion throughput MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerIngestionThroughputMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(ingestionThroughputMBeanInstance, "io.excalibur:type=IngestionThroughput");
    }

    /**
     * Registers all metrics MBeans.
     *
     * @param registry Metrics registry instance
     * @throws Exception if registration fails
     */
    public static void registerAll(MetricsRegistry registry) throws Exception {
        registerQueryLatencyMBean(registry);
        registerSystemHealthMBean(registry);
        registerIngestionQueueMBean(registry);
        registerVisibilityEpochMBean(registry);
        registerCuckooFpRateMBean(registry);
        registerHnswQueueDepthMBean(registry);
        registerIngestionThroughputMBean(registry);
    }

    // ============================================
    // JMX MBean Implementations (DynamicMBean)
    // ============================================

    /**
     * JMX MBean for query latency statistics.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class QueryLatencyMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "QueryLatency",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("count", "long", "Total count", true, false, false),
            new MBeanAttributeInfo("p50", "long", "50th percentile latency (ms)", true, false, false),
            new MBeanAttributeInfo("p90", "long", "90th percentile latency (ms)", true, false, false),
            new MBeanAttributeInfo("p95", "long", "95th percentile latency (ms)", true, false, false),
            new MBeanAttributeInfo("p99", "long", "99th percentile latency (ms)", true, false, false),
            new MBeanAttributeInfo("p99.9", "long", "99.9th percentile latency (ms)", true, false, false),
            new MBeanAttributeInfo("average", "double", "Average latency (ms)", true, false, false),
            new MBeanAttributeInfo("minimum", "long", "Minimum latency (ms)", true, false, false),
            new MBeanAttributeInfo("maximum", "long", "Maximum latency (ms)", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("count", "p50", "p90", "p95", "p99", "p99.9", "average", "minimum", "maximum");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            FixedBucketHistogram hist = queryLatencyHistogram;
            switch (attribute) {
                case "count":
                    return hist.getTotalCount();
                case "p50":
                    return hist.getPercentile(50.0);
                case "p90":
                    return hist.getPercentile(90.0);
                case "p95":
                    return hist.getPercentile(95.0);
                case "p99":
                    return hist.getPercentile(99.0);
                case "p99.9":
                    return hist.getPercentile(99.9);
                case "average":
                    return hist.getAverage();
                case "minimum":
                    return hist.getMinimum();
                case "maximum":
                    return hist.getMaximum();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "Query Latency MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        // ============================================
        // NotificationEmitter interface implementation
        // ============================================

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for system health.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class SystemHealthMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "SystemHealth",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("health", "double", "System health score (0.0-1.0)", true, false, false),
            new MBeanAttributeInfo("isHealthy", "boolean", "Whether system is healthy", true, false, false),
            new MBeanAttributeInfo("ready", "boolean", "Whether system is ready (health >= 0.5)", true, false, false),
            new MBeanAttributeInfo("coverage", "double", "Estimated corpus coverage (0.0-1.0)", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("health", "isHealthy", "ready", "coverage");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            switch (attribute) {
                case "health":
                    return Metrics.getHealthScore();
                case "isHealthy":
                    return Metrics.isHealthy();
                case "ready":
                    return Metrics.getHealthScore() >= HEALTHY_SCORE_THRESHOLD;
                case "coverage":
                    return Metrics.getEstimatedCoverage();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "System Health MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        // ============================================
        // NotificationEmitter interface implementation
        // ============================================

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for ingestion queue.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class IngestionQueueMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "IngestionQueue",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("depth", "long", "Current queue depth", true, false, false),
            new MBeanAttributeInfo("percent", "double", "Queue depth as percentage of limit", true, false, false),
            new MBeanAttributeInfo("isUnderControl", "boolean", "Whether queue is under control (< 50%)", true, false, false),
            new MBeanAttributeInfo("isCritical", "boolean", "Whether queue is critical (> 80%)", true, false, false),
            new MBeanAttributeInfo("isFull", "boolean", "Whether queue is full (> 100%)", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("depth", "percent", "isUnderControl", "isCritical", "isFull");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            // Delegate to HNSW queue depth methods - ingestion queue is tracked via HNSW integration queue
            switch (attribute) {
                case "depth":
                    return Metrics.getHnswQueueDepth();
                case "percent":
                    return Metrics.getHnswQueueDepthPercent(Metrics.getHnswQueueLimit());
                case "isUnderControl":
                    return Metrics.isHnswUnderControl(Metrics.getHnswQueueLimit());
                case "isCritical":
                    return Metrics.isHnswCritical(Metrics.getHnswQueueLimit());
                case "isFull":
                    return Metrics.isHnswFull(Metrics.getHnswQueueLimit());
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "Ingestion Queue MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for visibility epoch.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class VisibilityEpochMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "VisibilityEpoch",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("epoch", "long", "Current visibility epoch", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("epoch");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            switch (attribute) {
                case "epoch":
                    return Metrics.getCurrentEpoch();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "Visibility Epoch MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        // ============================================
        // NotificationEmitter interface implementation
        // ============================================

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for Cuckoo filter false positive rate.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class CuckooFpRateMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "CuckooFpRate",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.CUCKOO_FP_RATE }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("fpRate", "double", "Current false positive rate", true, false, false),
            new MBeanAttributeInfo("fpRateEpoch", "long", "Epoch of last FP rate measurement", true, false, false),
            new MBeanAttributeInfo("fpRateCount", "long", "Count of FP rate measurements", true, false, false),
            new MBeanAttributeInfo("avgFpRate", "double", "Average false positive rate", true, false, false),
            new MBeanAttributeInfo("maxFpRate", "double", "Maximum false positive rate", true, false, false),
            new MBeanAttributeInfo("currentEpoch", "long", "Current epoch", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("fpRate", "fpRateEpoch", "fpRateCount", "avgFpRate", "maxFpRate", "currentEpoch");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            FixedBucketHistogram hist = FixedBucketHistogram.getInstance();
            switch (attribute) {
                case "fpRate":
                    return hist.getAverageFpRate();
                case "fpRateEpoch":
                    return hist.getFpRateEpoch();
                case "fpRateCount":
                    return hist.getFpRateCount();
                case "avgFpRate":
                    return hist.getAverageFpRate();
                case "maxFpRate":
                    return hist.getMaxFpRate();
                case "currentEpoch":
                    return hist.getCurrentEpoch();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "Cuckoo Filter False Positive Rate MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for HNSW graph queue depth.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class HnswQueueDepthMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "HnswQueueDepth",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("depth", "long", "Current HNSW queue depth", true, false, false),
            new MBeanAttributeInfo("percent", "double", "Queue depth as percentage of limit", true, false, false),
            new MBeanAttributeInfo("isUnderControl", "boolean", "Whether queue is under control (< 50%)", true, false, false),
            new MBeanAttributeInfo("isCritical", "boolean", "Whether queue is critical (> 80%)", true, false, false),
            new MBeanAttributeInfo("isFull", "boolean", "Whether queue is full (> 100%)", true, false, false),
            new MBeanAttributeInfo("averageTailScan", "long", "Average tail scan entries", true, false, false),
            new MBeanAttributeInfo("maxTailScan", "long", "Maximum tail scan entries", true, false, false),
            new MBeanAttributeInfo("totalTailScanned", "long", "Total tail scan entries", true, false, false),
            new MBeanAttributeInfo("totalVectorEntriesFound", "long", "Total tail scan entries with a valid vector", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        // ============================================
        // Backward-compatible methods (MBeanServerProvider interface)
        // ============================================

        /**
         * Adds a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info containing type and object name
         * @param observer The observer to receive notifications
         * @param putPending Whether to deliver pending notifications
         */
        public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        /**
         * Removes a notification listener using the custom observer interface.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @param info Notification info
         * @param observer The observer to remove
         */
        public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            broadcaster.removeNotificationListener(info, observer);
        }

        /**
         * Gets all available attribute names.
         * This method provides backward compatibility with the MBeanServerProvider interface.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("depth", "percent", "isUnderControl", "isCritical", "isFull", "averageTailScan", "maxTailScan", "totalTailScanned", "totalVectorEntriesFound");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            switch (attribute) {
                case "depth":
                    return Metrics.getHnswQueueDepth();
                case "percent":
                    return Metrics.getHnswQueueDepthPercent(Metrics.getHnswQueueLimit());
                case "isUnderControl":
                    return Metrics.isHnswUnderControl(Metrics.getHnswQueueLimit());
                case "isCritical":
                    return Metrics.isHnswCritical(Metrics.getHnswQueueLimit());
                case "isFull":
                    return Metrics.isHnswFull(Metrics.getHnswQueueLimit());
                case "averageTailScan":
                    return Metrics.getAverageTailScanEntries();
                case "maxTailScan":
                    return Metrics.getMaxTailScanEntries();
                case "totalTailScanned":
                    return Metrics.getTotalTailScanned();
                case "totalVectorEntriesFound":
                    return Metrics.getTotalVectorEntriesFound();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "HNSW Queue Depth MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }

    /**
     * JMX MBean for ingestion throughput statistics.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     *
     * <p>Provides metrics for tracking ingestion throughput including:
     * <ul>
     *   <li>Total entries ingested</li>
     *   <li>Total queries executed</li>
     *   <li>Current ingestion rate (entries/second)</li>
     *   <li>Peak ingestion rate</li>
     * </ul>
     * </p>
     */
    public static class IngestionThroughputMBean implements DynamicMBean, NotificationEmitter {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            this,
            "IngestionThroughput",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.INGESTION_THROUGHPUT }
        );

        private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("entriesIngested", "long", "Total entries ingested", true, false, false),
            new MBeanAttributeInfo("queriesExecuted", "long", "Total queries executed", true, false, false),
            new MBeanAttributeInfo("ingestionRate", "double", "Current ingestion rate (entries/sec)", true, false, false),
            new MBeanAttributeInfo("peakIngestionRate", "double", "Peak ingestion rate (entries/sec)", true, false, false)
        };

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        /**
         * Gets all available attribute names.
         *
         * @return Set of attribute names
         */
        public Set<String> getAttributes() {
            return Set.of("entriesIngested", "queriesExecuted", "ingestionRate", "peakIngestionRate");
        }

        // ============================================
        // DynamicMBean interface implementation
        // ============================================

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            switch (attribute) {
                case "entriesIngested":
                    return Metrics.getEntriesIngested();
                case "queriesExecuted":
                    return Metrics.getQueriesExecuted();
                case "ingestionRate":
                    // Update rate before reading
                    Metrics.updateIngestionRate();
                    return Metrics.getIngestionRate();
                case "peakIngestionRate":
                    return Metrics.getPeakIngestionRate();
                default:
                    throw new AttributeNotFoundException("Unknown attribute: " + attribute);
            }
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (Exception ignored) {
                    // Skip attributes that fail
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // All read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                this.getClass().getName(),
                "Ingestion Throughput MBean",
                ATTRIBUTES,
                null, // constructors
                null, // operations
                broadcaster.getNotificationInfo() // notifications
            );
        }

        // ============================================
        // NotificationEmitter interface implementation
        // ============================================

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
            broadcaster.addNotificationListener(listener, filter, handback);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
            broadcaster.removeNotificationListener(listener, filter, handback);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return broadcaster.getNotificationInfo();
        }
    }
}
