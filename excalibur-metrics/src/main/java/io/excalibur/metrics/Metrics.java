package io.excalibur.metrics;

import javax.management.MBeanServer;
import javax.management.Notification;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.ObjectName;

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
     * Gets the p50 query latency.
     */
    public static long getP50() {
        return FixedBucketHistogram.getP50();
    }
    
    /**
     * Gets the p90 query latency.
     */
    public static long getP90() {
        return FixedBucketHistogram.getP90();
    }
    
    /**
     * Gets the p95 query latency.
     */
    public static long getP95() {
        return FixedBucketHistogram.getP95();
    }
    
    /**
     * Gets the p99 query latency.
     */
    public static long getP99() {
        return FixedBucketHistogram.getP99();
    }
    
    /**
     * Gets the p99.9 query latency.
     */
    public static long getP999() {
        return FixedBucketHistogram.getP999();
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
        entriesIngested.incrementAndGet();
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
    
    // ============================================
    // System Health
    // ============================================
    
    private static final AtomicReference<Double> healthScore = new AtomicReference<>(0.5);
    private static final AtomicBoolean isHealthy = new AtomicBoolean(true);
    
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
        if (score < 0.0) score = 0.0;
        if (score > 1.0) score = 1.0;
        healthScore.set(score);
        // Emit notification
        systemHealthMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
            "System health score changed: " + score,
            score
        );
    }
    
    /**
     * Gets whether the system is healthy.
     */
    public static boolean isHealthy() {
        return isHealthy.get();
    }
    
    /**
     * Sets the system health status.
     *
     * @param healthy true if system is healthy
     */
    public static void setHealthy(boolean healthy) {
        isHealthy.set(healthy);
    }
    
    /**
     * Updates health based on latency threshold.
     *
     * @param latencyMs Query latency in ms
     * @param thresholdMs Latency threshold in ms
     */
    public static void updateHealth(long latencyMs, long thresholdMs) {
        double latencyRatio = (double) latencyMs / thresholdMs;
        double newScore = Math.max(0.0, 1.0 - latencyRatio);
        
        synchronized (healthScore) {
            if (newScore < healthScore.get() - 0.01) {
                isHealthy.set(newScore < 0.5);
            }
            healthScore.set(newScore);
        }
    }
    
    // ============================================
    // Ingestion Queue Depth
    // ============================================
    
    private static final AtomicLong queueDepth = new AtomicLong(0);
    
    /**
     * Gets the current ingestion queue depth.
     */
    public static long getQueueDepth() {
        return queueDepth.get();
    }
    
    /**
     * Records the current queue depth.
     *
     * @param depth Current queue depth
     */
    public static void recordQueueDepth(long depth) {
        queueDepth.set(depth);
        // Emit notification
        ingestionQueueMBeanInstance.getBroadcaster().sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE,
            "Ingestion queue depth: " + depth,
            depth
        );
    }
    
    /**
     * Gets the queue depth percentage.
     *
     * @param limit Maximum queue depth limit
     * @return Percentage of limit
     */
    public static double getQueueDepthPercent(long limit) {
        long depth = getQueueDepth();
        double percent = (double) depth / limit * 100.0;
        return Math.max(0.0, Math.min(100.0, percent));
    }
    
    /**
     * Checks if the queue is under control (< 50% of limit).
     */
    public static boolean isUnderControl(long limit) {
        return getQueueDepth() < limit * 0.5;
    }
    
    /**
     * Checks if the queue is critical (> 80% of limit).
     */
    public static boolean isCritical(long limit) {
        return getQueueDepth() > limit * 0.8;
    }
    
    /**
     * Checks if the queue is full (> 100% of limit).
     */
    public static boolean isFull(long limit) {
        return getQueueDepth() > limit;
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
        if (coverage < 0.0) coverage = 0.0;
        if (coverage > 1.0) coverage = 1.0;
        estimatedCoverage.set(coverage);
    }
    
    // ============================================
    // Tail Scan Metrics
    // ============================================
    
    private static final AtomicLong totalTailScanned = new AtomicLong(0);
    private static final AtomicLong tailScanEntries = new AtomicLong(0);
    private static final AtomicLong maxTailScanEntries = new AtomicLong(0);
    
    /**
     * Records a tail scan entry.
     *
     * @param segmentId Segment identifier
     */
    public static void recordTailScan(long segmentId) {
        long depth = getQueueDepth();
        totalTailScanned.addAndGet(depth);
        tailScanEntries.addAndGet(depth);
        long newMax = tailScanEntries.get();
        synchronized (maxTailScanEntries) {
            if (newMax > maxTailScanEntries.get()) {
                maxTailScanEntries.set(newMax);
            }
        }
        recordQueueDepth(depth);
        recordHnswDepth(depth);
    }
    
    /**
     * Gets the average tail scan entries.
     *
     * @return Average tail scan entries
     */
    public static long getAverageTailScanEntries() {
        long total = tailScanEntries.get();
        long count = totalTailScanned.get();
        synchronized (maxTailScanEntries) {
            if (count == 0) return 0;
            return total / count;
        }
    }
    
    /**
     * Gets the maximum tail scan entries.
     *
     * @return Maximum tail scan entries observed
     */
    public static long getMaxTailScanEntries() {
        synchronized (maxTailScanEntries) {
            return maxTailScanEntries.get();
        }
    }
    
    // ============================================
    // HNSW Queue Depth
    // ============================================
    
    private static final AtomicLong hnswQueueDepth = new AtomicLong(0);
    
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
    
    /**
     * Gets the HNSW queue depth percentage.
     *
     * @param limit Maximum queue depth limit
     * @return Percentage of limit
     */
    public static double getHnswQueueDepthPercent(long limit) {
        long depth = getHnswQueueDepth();
        double percent = (double) depth / limit * 100.0;
        return Math.max(0.0, Math.min(100.0, percent));
    }
    
    /**
     * Checks if the HNSW queue is under control (< 50% of limit).
     */
    public static boolean isHnswUnderControl(long limit) {
        return getHnswQueueDepth() < limit * 0.5;
    }
    
    /**
     * Checks if the HNSW queue is critical (> 80% of limit).
     */
    public static boolean isHnswCritical(long limit) {
        return getHnswQueueDepth() > limit * 0.8;
    }
    
     /**
     * Checks if the HNSW queue is full (> 100% of limit).
     */
    public static boolean isHnswFull(long limit) {
        return getHnswQueueDepth() > limit;
    }
    
    /**
     * Gets total tail scan entries.
     */
    public static long getTotalTailScanned() {
        return totalTailScanned.get();
    }
    
    // ============================================
    // JMX MBean Registration
    // ============================================
    
    /**
     * Registers all metrics MBeans.
    /**
     * Registers the query latency MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerQueryLatencyMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new QueryLatencyMBean(), "io.excalibur:type=QueryLatency");
    }
    
    /**
     * Registers the system health MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerSystemHealthMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new SystemHealthMBean(), "io.excalibur:type=SystemHealth");
    }
    
    /**
     * Registers the ingestion queue MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerIngestionQueueMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new IngestionQueueMBean(), "io.excalibur:type=IngestionQueue");
    }
    
    /**
     * Registers the visibility epoch MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerVisibilityEpochMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new VisibilityEpochMBean(), "io.excalibur:type=VisibilityEpoch");
    }
    
    // ============================================
    // JMX MBean Implementations
    // ============================================
    
    /**
     * JMX MBean for query latency statistics.
     */
    public static class QueryLatencyMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "QueryLatency",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            FixedBucketHistogram hist = FixedBucketHistogram.getInstance();
            switch (name) {
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
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    /**
     * JMX MBean for system health.
     */
    public static class SystemHealthMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "SystemHealth",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            switch (name) {
                case "health":
                    return Metrics.getHealthScore();
                case "isHealthy":
                    return Metrics.isHealthy();
                case "ready":
                    return Metrics.getHealthScore() >= 0.5;
                default:
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    /**
     * JMX MBean for ingestion queue.
     */
    public static class IngestionQueueMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "IngestionQueue",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            switch (name) {
                case "depth":
                    return Metrics.getQueueDepth();
                case "percent":
                    return Metrics.getQueueDepthPercent(1000000);
                case "isUnderControl":
                    return Metrics.isUnderControl(1000000);
                case "isCritical":
                    return Metrics.isCritical(1000000);
                case "isFull":
                    return Metrics.isFull(1000000);
                default:
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    /**
     * JMX MBean for visibility epoch.
     */
    public static class VisibilityEpochMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "VisibilityEpoch",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            switch (name) {
                case "epoch":
                    return Metrics.getCurrentEpoch();
                default:
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    // ============================================
    // Cuckoo Filter FP Rate MBean
    // ============================================
    
    /**
     * JMX MBean for Cuckoo filter false positive rate.
     */
    public static class CuckooFpRateMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "CuckooFpRate",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.CUCKOO_FP_RATE }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            FixedBucketHistogram hist = FixedBucketHistogram.getInstance();
            switch (name) {
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
                    return hist.getFpRateEpoch();
                default:
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    // ============================================
    // HNSW Queue Depth MBean
    // ============================================
    
    /**
     * JMX MBean for HNSW graph queue depth.
     */
    public static class HnswQueueDepthMBean implements MBeanServerProvider {

        private final MBeanNotificationBroadcaster broadcaster = new MBeanNotificationBroadcaster(
            "HnswQueueDepth",
            new String[]{ MBeanNotificationBroadcaster.NotificationTypes.HNSW_QUEUE_DEPTH }
        );

        public MBeanServer mbeanServer() {
            return ManagementFactory.getPlatformMBeanServer();
        }

        /**
         * Gets the notification broadcaster for this MBean.
         *
         * @return The notification broadcaster
         */
        public MBeanNotificationBroadcaster getBroadcaster() {
            return broadcaster;
        }

        @Override
        public Object getAttribute(String name) throws AttributeException {
            switch (name) {
                case "depth":
                    return Metrics.getHnswQueueDepth();
                case "percent":
                    return Metrics.getHnswQueueDepthPercent(100000);
                case "isUnderControl":
                    return Metrics.isHnswUnderControl(100000);
                case "isCritical":
                    return Metrics.isHnswCritical(100000);
                case "isFull":
                    return Metrics.isHnswFull(100000);
                case "averageTailScan":
                    return Metrics.getAverageTailScanEntries();
                case "maxTailScan":
                    return Metrics.getMaxTailScanEntries();
                case "totalTailScanned":
                    return Metrics.getTotalTailScanned();
                default:
                    throw new AttributeException(name, "Unknown attribute");
            }
        }

        @Override
        public void setAttribute(String name, Object value) throws AttributeException {
            throw new AttributeException(name, "Non-writable attribute");
        }

        @Override
        public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending
        ) {
            broadcaster.addNotificationListener(info, observer, putPending);
        }

        @Override
        public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer
        ) {
            broadcaster.removeNotificationListener(info, observer);
        }
    }
    
    // ============================================
    // Updated MBean Registration Methods
    // ============================================
    
    /**
     * Registers the Cuckoo filter FP rate MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerCuckooFpRateMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new CuckooFpRateMBean(), "io.excalibur:type=CuckooFpRate");
    }
    
    /**
     * Registers the HNSW queue depth MBean.
     *
     * @param registry Metrics registry
     * @throws Exception if registration fails
     */
    private static void registerHnswQueueDepthMBean(MetricsRegistry registry) throws Exception {
        registry.registerMBean(new HnswQueueDepthMBean(), "io.excalibur:type=HnswQueueDepth");
    }
    
    /**
     * Registers all metrics MBeans.
     *
     * @param registry Metrics registry instance
     * @throws Exception if registration fails
     */
    public static void registerAll(MetricsRegistry metricsRegistry) throws Exception {
        registerQueryLatencyMBean(metricsRegistry);
        registerSystemHealthMBean(metricsRegistry);
        registerIngestionQueueMBean(metricsRegistry);
        registerVisibilityEpochMBean(metricsRegistry);
        registerCuckooFpRateMBean(metricsRegistry);
        registerHnswQueueDepthMBean(metricsRegistry);
    }
}

