package io.excalibur.core.metrics;

import java.util.Map;
import java.util.HashMap;

/**
 * Metrics collection for ExcaliburDB system health.
 * 
 * Thread-safety: All metrics are thread-safe using atomic operations
 * and synchronized access.
 * 
 * Domain Correctness:
 * - Counters must be monotonically increasing (never decrease)
 * - Gauges must return values within valid ranges
 * - Health status transitions must be consistent
 * 
 * @see HealthGauge
 * @see QueueGauge
 */
public final class Metrics {
    
    // Query latency histogram (fixed bucket for accurate percentiles)
    public static final FixedBucketHistogram QueryLatency = new FixedBucketHistogram(1000);
    
    // System health gauge (0.0 = unhealthy, 1.0 = healthy)
    public static final AtomicLongCounter Counter = new AtomicLongCounter(0);
    
    // HNSW integration queue depth gauge
    public static final AtomicLongCounter QueueDepth = new AtomicLongCounter(0);
    
    // Total entries ingested
    public static final AtomicLongCounter EntriesIngested = new AtomicLongCounter(0);
    
    // Total queries executed
    public static final AtomicLongCounter QueriesExecuted = new AtomicLongCounter(0);
    
    // Cuckoo filter false positive count
    public static final AtomicLongCounter CuckooFPRateCount = new AtomicLongCounter(0);
    
    // Cuckoo filter false negative count
    public static final AtomicLongCounter CuckooFNRateCount = new AtomicLongCounter(0);
    
    /**
     * Records a query latency measurement.
     * 
     * @param latencyMs Query latency in milliseconds
     */
    public synchronized static void recordQueryLatency(long latencyMs) {
        QueryLatency.record(latencyMs);
    }
    
    /**
     * Updates the system health score.
     * 
     * @param health Score between 0.0 and 1.0 (1.0 = fully healthy)
     */
    public static void setHealthScore(double health) {
        if (health < 0.0) health = 0.0;
        if (health > 1.0) health = 1.0;
        HealthGauge.setScore(health);
    }
    
    /**
     * Returns the current health score (0.0 to 1.0).
     * 
     * @return Health score (non-negative, bounded 0-1)
     */
    public static double getHealthScore() {
        return Math.min(1.0, Math.max(0.0, HealthGauge.getScore()));
    }
    
    /**
     * Returns the current HNSW integration queue depth.
     * 
     * @return Queue depth (non-negative)
     */
    public static long getQueueDepth() {
        return QueueDepth.get();
    }
    
    /**
     * Returns total entries ingested.
     * 
     * @return Total count (monotonically increasing)
     */
    public static long getEntriesIngested() {
        return EntriesIngested.get();
    }
    
    /**
     * Returns total queries executed.
     * 
     * @return Total count (monotonically increasing)
     */
    public static long getQueriesExecuted() {
        return QueriesExecuted.get();
    }
    
    /**
     * Records a Cuckoo filter false positive.
     * 
     * @param fpCount Number of false positives (non-negative)
     */
    public synchronized static void recordCuckooFalsePositive(long fpCount) {
        if (fpCount < 0) {
            throw new IllegalArgumentException("False positive count cannot be negative");
        }
        CuckooFPRateCount.add(fpCount);
    }
    
    /**
     * Records a Cuckoo filter false negative.
     * 
     * @param fnCount Number of false negatives (non-negative)
     */
    public synchronized static void recordCuckooFalseNegative(long fnCount) {
        if (fnCount < 0) {
            throw new IllegalArgumentException("False negative count cannot be negative");
        }
        CuckooFNRateCount.add(fnCount);
    }
    
    /**
     * Returns estimated false positive rate.
     * 
     * @return FP rate as probability (0.0 to 1.0)
     */
    public static double getCuckooFPRate() {
        long fp = CuckooFPRateCount.get();
        long total = Counter.get();
        return total > 0 ? (double)fp / total : 0.0;
    }
    
    /**
     * Returns estimated false negative rate.
     * 
     * @return FN rate as probability (0.0 to 1.0)
     */
    public static double getCuckooFNRate() {
        long fn = CuckooFNRateCount.get();
        long total = Counter.get();
        return total > 0 ? (double)fn / total : 0.0;
    }
    
    /**
     * Returns all metrics as a map for debugging.
     * 
     * @return Map of all metrics
     */
    public static Map<String, Object> getAllMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("query_latency", QueryLatency.getStats());
        metrics.put("health_score", getHealthScore());
        metrics.put("queue_depth", getQueueDepth());
        metrics.put("entries_ingested", getEntriesIngested());
        metrics.put("queries_executed", getQueriesExecuted());
        metrics.put("cuckoo_fp_rate", getCuckooFPRate());
        metrics.put("cuckoo_fn_rate", getCuckooFNRate());
        return metrics;
    }
}
