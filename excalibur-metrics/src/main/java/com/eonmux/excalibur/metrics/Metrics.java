package com.eonmux.excalibur.metrics;

import java.util.concurrent.atomic.AtomicLong;

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
 * <h2>Structure</h2>
 * <p>This class is the one address every module records into and every reader scrapes, so it
 * stays a single facade even though the state behind it is not one thing. Each group of
 * values lives in its own collaborator in this package - {@link ThroughputCounters},
 * {@link HealthState}, {@link QueueDepthState}, {@link TailScanCounters} - because each has
 * its own update protocol to keep straight, and a protocol is easier to get right beside the
 * fields it governs than in the middle of a hundred unrelated accessors. The JMX beans below
 * are the published surface of the same values and hold no state of their own.</p>
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

    /**
     * The one histogram in the process that query latency is measured in.
     *
     * <p>This is {@link FixedBucketHistogram#getInstance()}, not a second instance. There used
     * to be two: latencies were recorded here while {@code FixedBucketHistogram}'s own static
     * percentile accessors read the singleton, which nothing ever recorded a latency into - so
     * {@code FixedBucketHistogram.getP50()} was a permanent zero published under the same name
     * as a real measurement, and {@link #recordEpoch(long)} advanced an epoch on a histogram
     * that held no latencies. One object means "p50" is one number however it is reached.</p>
     */
    private static final FixedBucketHistogram queryLatencyHistogram =
        FixedBucketHistogram.getInstance();

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

    private static final ThroughputCounters throughput = new ThroughputCounters();

    /**
     * Gets the total number of entries ingested.
     */
    public static long getEntriesIngested() {
        return throughput.entriesIngested();
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
        long total = throughput.addIngested(count);
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
        return throughput.queriesExecuted();
    }

    /**
     * Records a query execution.
     */
    public static void recordQueryExecution() {
        throughput.recordQueryExecution();
    }

    /**
     * Query results dropped because they carried no chunk identity.
     *
     * <p>A counter rather than a log line alone: each drop is a hit the node found and could
     * not return, it recurs on every query that reaches the same graph nodes until their
     * segments' graphs are restored or reindexed, and any increase means a node-to-chunk
     * mapping is missing entries.</p>
     */
    private static final AtomicLong unnamedResultsDropped =
        new AtomicLong();

    /**
     * Counts query results dropped because they carried no chunk identity.
     *
     * @param count How many were dropped; non-positive counts are ignored
     */
    public static void recordUnnamedResultsDropped(long count) {
        if (count > 0) {
            unnamedResultsDropped.addAndGet(count);
        }
    }

    /**
     * Gets how many query results were dropped because they carried no chunk identity.
     *
     * @return Lifetime count
     */
    public static long getUnnamedResultsDropped() {
        return unnamedResultsDropped.get();
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
        return throughput.currentRate();
    }

    /**
     * Gets the peak ingestion rate (entries per second) since startup.
     *
     * @return Peak ingestion rate in entries per second
     */
    public static double getPeakIngestionRate() {
        return throughput.peakRate();
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
        throughput.updateRate();
    }

    /**
     * Returns the ingestion rate for observers, re-sampling at most once per sample window.
     *
     * <p>This is what the JMX attribute reads. Unlike {@link #updateIngestionRate()} it is safe to
     * call from any number of observers at any frequency: reading the rate does not consume the
     * interval it was measured over, so two reads in the same window agree, and one console's
     * polling cannot zero the value another console is watching.</p>
     *
     * @return The current ingestion rate in entries per second
     */
    public static double sampledIngestionRate() {
        return throughput.sampledRate();
    }

    // ============================================
    // System Health
    // ============================================

    private static final HealthState health = new HealthState();

    /**
     * Gets the system health score (0.0-1.0).
     */
    public static double getHealthScore() {
        return health.score();
    }

    /**
     * Sets the system health score.
     *
     * @param score Health score between 0.0 and 1.0
     */
    public static void setHealthScore(double score) {
        double clamped = health.setScore(score);
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
        return health.isHealthy();
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
        health.setScore(1.0 - latencyRatio);
    }

    // ============================================
    // Ingestion Queue Depth
    // ============================================
    //
    // One number under two names. Everything here reads the HNSW integration backlog, which
    // is what ingestion backpressure is actually measured against; there is no separate
    // ingestion queue to report. The IngestionQueue MBean is an alias of HnswQueueDepth and
    // says so in its own description.

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
        queueDepth.setDepth(depth);
        // Emit HNSW notification
        hnswQueueDepthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH,
            "HNSW queue depth: " + depth,
            depth
        );
        // Emit the aliased notification too, so a listener on either name sees the change.
        // The text names the queue it came from rather than the bean it arrived on.
        ingestionQueueMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE,
            "HNSW integration backlog: " + depth,
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
     */
    public static boolean isUnderControl(long limit) {
        return isHnswUnderControl(limit);
    }

    /**
     * Checks if the queue is critical (&gt; 80% of limit).
     */
    public static boolean isCritical(long limit) {
        return isHnswCritical(limit);
    }

    /**
     * Checks if the queue is full (&gt; 100% of limit).
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
        // Keep the latency histogram's epoch in step with the node's
        queryLatencyHistogram.setEpoch(epoch);
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

    /**
     * Gets the estimated corpus coverage.
     */
    public static double getEstimatedCoverage() {
        return health.coverage();
    }

    /**
     * Updates the estimated coverage.
     *
     * @param coverage Coverage value between 0.0 and 1.0
     */
    public static void setEstimatedCoverage(double coverage) {
        health.setCoverage(coverage);
    }

    // ============================================
    // Tail Scan Metrics
    // ============================================

    private static final TailScanCounters tailScan = new TailScanCounters();

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
     * <p><strong>Domain correctness:</strong> Entries scanned represents log entries; vector
     * entries found represents entries with valid vectors. Typical values: 0-100,000 entries
     * per scan (bounded by backpressure limit). A negative count contributes nothing: these
     * totals are published as counters, and a counter that decreases is read as a restart by
     * every rate computed over it. The two counts are screened independently, and the scan is
     * counted either way.</p>
     *
     * @param entriesScanned Number of log entries scanned (including non-vector entries);
     *                       negative is ignored
     * @param vectorEntriesFound Number of entries with valid vectors found; negative is ignored
     */
    public static void recordTailScanMetrics(int entriesScanned, int vectorEntriesFound) {
        tailScan.record(entriesScanned, vectorEntriesFound);
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
        return tailScan.averageEntries();
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
        return tailScan.maxEntries();
    }

    /**
     * Gets the total number of tail scan operations performed.
     *
     * @return Total count of tail scan operations
     */
    public static long getTotalTailScanCount() {
        return tailScan.totalCount();
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
        return tailScan.totalVectorEntriesFound();
    }

    // ============================================
    // HNSW Queue Depth
    // ============================================

    private static final QueueDepthState queueDepth = new QueueDepthState();

    /**
     * Gets the HNSW integration queue depth limit that the queue MBeans
     * evaluate depth against.
     *
     * @return Configured queue depth limit
     */
    public static long getHnswQueueLimit() {
        return queueDepth.limit();
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
        queueDepth.setLimit(limit);
    }

    /**
     * Gets the current HNSW queue depth.
     */
    public static long getHnswQueueDepth() {
        return queueDepth.depth();
    }

    /**
     * Records the current HNSW queue depth.
     *
     * @param depth Current queue depth
     */
    public static void recordHnswDepth(long depth) {
        queueDepth.setDepth(depth);
        // Emit notification
        hnswQueueDepthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH,
            "HNSW queue depth: " + depth,
            depth
        );
    }

    /**
     * Gets the HNSW queue depth percentage.
     *
     * @param limit Maximum queue depth limit
     * @return Percentage of limit
     */
    public static double getHnswQueueDepthPercent(long limit) {
        return queueDepth.percentOf(limit);
    }

    /**
     * Checks if the HNSW queue is under control (&lt; 50% of limit).
     */
    public static boolean isHnswUnderControl(long limit) {
        return queueDepth.isUnderControl(limit);
    }

    /**
     * Checks if the HNSW queue is critical (&gt; 80% of limit).
     */
    public static boolean isHnswCritical(long limit) {
        return queueDepth.isCritical(limit);
    }

     /**
     * Checks if the HNSW queue is full (&gt; 100% of limit).
     */
    public static boolean isHnswFull(long limit) {
        return queueDepth.isFull(limit);
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
        return tailScan.totalScanned();
    }

    // ============================================
    // JMX MBean Registration
    // ============================================

    /**
     * Registers all metrics MBeans.
     *
     * @param registry Metrics registry instance
     * @throws Exception if registration fails
     */
    public static void registerAll(MetricsRegistry registry) throws Exception {
        MetricMBeanRegistrations.registerAll(registry);
    }

    // ============================================
    // JMX MBean Implementations (DynamicMBean)
    // ============================================

    /**
     * JMX MBean for query latency statistics.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class QueryLatencyMBean extends ObserverRegisteringMBean {

        /**
         * Creates the query latency MBean over the histogram the query path records into.
         */
        public QueryLatencyMBean() {
            super("QueryLatency", MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                "Query Latency MBean", MetricAttributeCatalog.queryLatency());
        }
    }

    /**
     * JMX MBean for system health.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class SystemHealthMBean extends ObserverRegisteringMBean {

        /**
         * Creates the system health MBean over the node's health score and coverage estimate.
         */
        public SystemHealthMBean() {
            super("SystemHealth", MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
                "System Health MBean", MetricAttributeCatalog.systemHealth());
        }
    }

    /**
     * JMX MBean that is an alias of {@code com.eonmux.excalibur:type=HnswQueueDepth}.
     *
     * <p>Every attribute here reads the same value that bean reads: the count of write-log
     * entries the HNSW integration worker has not absorbed yet. There is no separate
     * ingestion queue behind this name and nothing else writes the number.</p>
     *
     * <p>The name is kept because it is what existing dashboards poll, but the descriptions
     * say what it measures. Two beans reporting one number under two names read as two
     * subsystems in trouble at once, and send an operator looking for an ingestion queue that
     * does not exist.</p>
     */
    public static class IngestionQueueMBean extends ObserverRegisteringMBean {

        /**
         * Creates the ingestion queue MBean over the HNSW integration backlog it aliases.
         */
        public IngestionQueueMBean() {
            super("IngestionQueue", MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE,
                "Alias of com.eonmux.excalibur:type=HnswQueueDepth. Reports the HNSW integration "
                    + "backlog, not a separate ingestion queue - the two beans carry one number",
                MetricAttributeCatalog.ingestionQueue());
        }
    }

    /**
     * JMX MBean for visibility epoch.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class VisibilityEpochMBean extends ObserverRegisteringMBean {

        /**
         * Creates the visibility epoch MBean over the epoch queries read at.
         */
        public VisibilityEpochMBean() {
            super("VisibilityEpoch",
                MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH,
                "Visibility Epoch MBean", MetricAttributeCatalog.visibilityEpoch());
        }
    }

    /**
     * JMX MBean for HNSW graph queue depth.
     * Implements DynamicMBean and NotificationEmitter for JMX compliance.
     */
    public static class HnswQueueDepthMBean extends ObserverRegisteringMBean {

        /**
         * Creates the HNSW queue depth MBean over the integration backlog and its scan cost.
         */
        public HnswQueueDepthMBean() {
            super("HnswQueueDepth",
                MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH,
                "HNSW Queue Depth MBean", MetricAttributeCatalog.hnswQueueDepth());
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
    public static class IngestionThroughputMBean extends DynamicMetricMBean {

        /**
         * Creates the ingestion throughput MBean over the ingest and query counters.
         */
        public IngestionThroughputMBean() {
            super("IngestionThroughput",
                MBeanNotificationBroadcaster.NotificationTypes.INGESTION_THROUGHPUT,
                "Ingestion Throughput MBean", MetricAttributeCatalog.ingestionThroughput());
        }
    }
}
