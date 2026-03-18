package io.excalibur.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Fixed-bucket histogram for collecting latency measurements.
 * 
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe for concurrent measurements. It uses
 * synchronized operations to ensure thread safety.</p>
 * 
 * <h2>Usage</h2>
 * <p>Measurements are recorded using the record method. Percentiles
 * can be retrieved at any time:</p>
 * <pre>
 *   long start = System.currentTimeMillis();
 *   // ... operation ...
 *   long duration = System.currentTimeMillis() - start;
 *   histogram.record(duration);
 * </pre>
 * 
 * @author ExcaliburDB
 */
public final class FixedBucketHistogram {
    
    private static final int NUM_BUCKETS = 10000;
    private static final FixedBucketHistogram histogram = new FixedBucketHistogram();
    
    private long[] buckets;
    private int totalEntries;
    private volatile long currentEpoch;
    
    // FP rate metrics - per-epoch statistics
    private static final int NUM_FP_RATES = 10000;
    private long[] fpRateBuckets;
    private int fpRateEntries;
    private volatile long fpRateEpoch;
    
    /**
     * Creates a new fixed-bucket histogram.
     */
    public FixedBucketHistogram() {
        this.buckets = new long[NUM_BUCKETS];
        this.totalEntries = 0;
        this.fpRateBuckets = new long[NUM_FP_RATES];
        this.fpRateEntries = 0;
    }
    
    /**
     * Records a measurement in milliseconds.
     *
     * @param latencyMs Latency in milliseconds
     */
    public void record(long latencyMs) {
        int bucketIndex = getBucketIndex(latencyMs);
        synchronized (this) {
            buckets[bucketIndex]++;
            totalEntries++;
        }
    }
    
    /**
     * Records a measurement and associates it with the current epoch.
     *
     * @param latencyMs Latency in milliseconds
     * @param epoch Current visibility epoch
     */
    public void record(long latencyMs, long epoch) {
        int bucketIndex = getBucketIndex(latencyMs);
        synchronized (this) {
            buckets[bucketIndex]++;
            totalEntries++;
            this.currentEpoch = epoch;
        }
    }
    
    /**
     * Records an FP rate measurement for the current epoch.
     *
     * @param fpRate False positive rate as a value between 0.0 and 1.0
     */
    public void recordFpRate(double fpRate) {
        double normalizedRate = Math.max(0.0, Math.min(1.0, fpRate));
        int bucketIndex = (int) (normalizedRate * NUM_FP_RATES);
        synchronized (this) {
            fpRateBuckets[bucketIndex]++;
            fpRateEntries++;
            this.fpRateEpoch = currentEpoch;
        }
    }
    
    /**
     * Sets the current epoch.
     *
     * @param epoch New epoch number
     */
    public void setEpoch(long epoch) {
        synchronized (this) {
            this.currentEpoch = epoch;
        }
    }
    
    /**
     * Gets the time until next epoch tick.
     *
     * @return Time in ms until next epoch
     */
    public long timeUntilEpochTick() {
        return System.currentTimeMillis() % 100;  // Default epoch tick is 100ms
    }
    
    /**
     * Gets the percentile value.
     *
     * @param percentile Percentile (0-100)
     * @return Value at the given percentile
     */
    public long getPercentile(double percentile) {
        if (totalEntries == 0) return 0;
        
        double targetBucket = totalEntries * percentile / 100.0;
        int bucketIndex = findBucketAtTarget(targetBucket);
        
        // Interpolate between buckets
        int bucketStart = Math.max(0, (int) (bucketIndex * 10000.0 / NUM_BUCKETS));
        int bucketEnd = Math.min(NUM_BUCKETS - 1, bucketIndex);
        
        if (bucketStart == bucketEnd) {
            return (long) (bucketStart * NUM_BUCKETS / 10000.0);
        }
        
        double progress = (targetBucket - bucketStart * totalEntries) / 
                          ((bucketEnd - bucketStart) * totalEntries);
        
        long bucketValue = (long) (bucketStart * NUM_BUCKETS / 10000.0);
        long nextBucketValue = (long) (bucketEnd * NUM_BUCKETS / 10000.0);
        
        return (long) (bucketValue + progress * (nextBucketValue - bucketValue));
    }
    
    // Convenience methods for common percentiles (static for Metrics access)
    /**
     * Gets the p50 (median) percentile value.
     */
    public static long getP50() {
        return histogram.getPercentile(50.0);
    }
    
    /**
     * Gets the p90 percentile value.
     */
    public static long getP90() {
        return histogram.getPercentile(90.0);
    }
    
    /**
     * Gets the p95 percentile value.
     */
    public static long getP95() {
        return histogram.getPercentile(95.0);
    }
    
    /**
     * Gets the p99 percentile value.
     */
    public static long getP99() {
        return histogram.getPercentile(99.0);
    }
    
    /**
     * Gets the p99.9 percentile value.
     */
    public static long getP999() {
        return histogram.getPercentile(99.9);
    }
    
    /**
     * Gets the total number of entries recorded.
     */
    public static long getTotalCount() {
        return histogram.getTotalCount();
    }
    
    /**
     * Gets the average latency.
     */
    public static double getAverage() {
        return histogram.getAverage();
    }
    
    /**
     * Gets the minimum latency recorded.
     */
    public static long getMinimum() {
        return histogram.getMinimum();
    }
    
    /**
     * Gets the maximum latency recorded.
     */
    public static long getMaximum() {
        return histogram.getMaximum();
    }
    
    // FP rate statistics methods (static for Metrics access)
    /**
     * Gets the average FP rate across all epochs.
     */
    public static double getAverageFpRate() {
        return histogram.getAverageFpRate();
    }
    
    /**
     * Gets the FP rate for the current epoch.
     */
    public static double getFpRateEpoch() {
        return histogram.getFpRateEpoch();
    }
    
    /**
     * Gets the count of FP rate measurements.
     */
    public static int getFpRateCount() {
        return histogram.getFpRateCount();
    }
    
    /**
     * Gets the maximum FP rate recorded.
     */
    public static double getMaxFpRate() {
        return histogram.getMaxFpRate();
    }
    
    /**
     * Finds the bucket index for a given latency.
     *
     * @param latency Latency in ms
     * @return Bucket index
     */
    private int getBucketIndex(long latency) {
        return (int) (latency * NUM_BUCKETS / 10000.0);
    }
    
    /**
     * Finds the first non-zero bucket index.
     *
     * @return First non-zero bucket index, or NUM_BUCKETS if none
     */
    private int findFirstNonZeroBucket() {
        for (int i = 0; i < NUM_BUCKETS; i++) {
            if (buckets[i] > 0) return i;
        }
        return NUM_BUCKETS;
    }
    
    /**
     * Finds the last non-zero bucket index.
     *
     * @return Last non-zero bucket index, or -1 if none
     */
    private int findLastNonZeroBucket() {
        for (int i = NUM_BUCKETS - 1; i >= 0; i--) {
            if (buckets[i] > 0) return i;
        }
        return -1;
    }
    
    /**
     * Finds the bucket index that contains the target cumulative count.
     *
     * @param target Target cumulative count
     * @return Bucket index
     */
    private int findBucketAtTarget(double target) {
        for (int i = 0; i < NUM_BUCKETS; i++) {
            if (buckets[i] > 0) {
                double cumulative = i * totalEntries / NUM_BUCKETS;
                if (cumulative >= target) return i;
            }
        }
        return NUM_BUCKETS - 1;
    }
}