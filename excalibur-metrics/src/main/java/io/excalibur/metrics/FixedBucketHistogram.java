package io.excalibur.metrics;

import java.util.Arrays;

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

    /**
     * Largest latency the buckets can place, in milliseconds.
     *
     * <p>One bucket per millisecond, so a sample of exactly this many milliseconds is already
     * past the last of them. Anything at or above it is counted as overflow rather than
     * folded into the top bucket - see {@link #getOverflowCount()}.</p>
     */
    public static final long RANGE_MS = NUM_BUCKETS;

    private static final FixedBucketHistogram histogram = new FixedBucketHistogram();
    
    private long[] buckets;
    private long totalEntries;
    private volatile long currentEpoch;

    // Samples past the last bucket, kept as real numbers rather than clamped into it. The
    // clamp made a 45-second query and a ten-second one the same measurement, so a tail
    // getting steadily worse past ten seconds charted as a flat line at the top bucket.
    private long overflowCount;
    private long overflowSum;
    private long overflowMin;
    private long overflowMax;

    // FP rate metrics - per-epoch statistics
    private static final int NUM_FP_RATES = 10000;
    private long[] fpRateBuckets;
    private long fpRateEntries;
    private volatile long fpRateEpoch;
    
    /**
     * Creates a new fixed-bucket histogram.
     */
    public FixedBucketHistogram() {
        this.buckets = new long[NUM_BUCKETS];
        this.totalEntries = 0;
        this.fpRateBuckets = new long[NUM_FP_RATES];
        this.fpRateEntries = 0;
        this.overflowMin = Long.MAX_VALUE;
    }
    
    /**
     * Records a measurement in milliseconds.
     *
     * @param latencyMs Latency in milliseconds
     */
    public void record(long latencyMs) {
        synchronized (this) {
            place(latencyMs);
        }
    }
    
    /**
     * Records a measurement and associates it with the current epoch.
     *
     * @param latencyMs Latency in milliseconds
     * @param epoch Current visibility epoch
     */
    public void record(long latencyMs, long epoch) {
        synchronized (this) {
            place(latencyMs);
            this.currentEpoch = epoch;
        }
    }

    /**
     * Files one sample, in a bucket or in the overflow tally. Caller holds the lock.
     */
    private void place(long latencyMs) {
        if (latencyMs >= RANGE_MS) {
            overflowCount++;
            overflowSum += latencyMs;
            overflowMin = Math.min(overflowMin, latencyMs);
            overflowMax = Math.max(overflowMax, latencyMs);
        } else {
            buckets[getBucketIndex(latencyMs)]++;
        }
        totalEntries++;
    }
    
    /**
     * Records an FP rate measurement, stamping it with the epoch this histogram
     * was last told about via {@link #setEpoch(long)} or {@link #recordFpRate(double, long)}.
     *
     * @param fpRate False positive rate as a value between 0.0 and 1.0
     */
    public void recordFpRate(double fpRate) {
        recordFpRate(fpRate, this.currentEpoch);
    }

    /**
     * Records an FP rate measurement and stamps it with the supplied epoch,
     * which also becomes this histogram's current epoch.
     *
     * @param fpRate False positive rate as a value between 0.0 and 1.0
     * @param epoch Visibility epoch the measurement was taken in
     */
    public void recordFpRate(double fpRate, long epoch) {
        double normalizedRate = Math.max(0.0, Math.min(1.0, fpRate));
        // Clamp index to prevent ArrayIndexOutOfBoundsException when normalizedRate == 1.0
        int bucketIndex = Math.min((int) (normalizedRate * NUM_FP_RATES), NUM_FP_RATES - 1);
        synchronized (this) {
            fpRateBuckets[bucketIndex]++;
            fpRateEntries++;
            this.currentEpoch = epoch;
            this.fpRateEpoch = epoch;
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
     * Gets the percentile value.
     *
     * @param percentile Percentile (0-100)
     * @return Value at the given percentile in milliseconds
     */
    public long getPercentile(double percentile) {
        synchronized (this) {
            if (totalEntries == 0) return 0;

            double targetCount = totalEntries * percentile / 100.0;
            int bucketIndex = findBucketAtTarget(targetCount);
            if (bucketIndex < 0) {
                // The target lies among the samples the buckets could not place. Their exact
                // spread is not recorded, so the honest answer is the edge of the range:
                // "at least this". getOverflowCount() and getMaximum() say how far past it.
                return RANGE_MS;
            }

            // Convert bucket index back to latency value
            return (long) (bucketIndex * 10000L / NUM_BUCKETS);
        }
    }

    /**
     * How many recorded latencies were past the last bucket.
     *
     * <p>Percentiles cannot resolve these and report {@link #RANGE_MS} for any that falls
     * among them, so this is the count that tells an operator a flat percentile is a ceiling
     * rather than a measurement. {@link #getMaximum()} and {@link #getAverage()} do count
     * them at their real size.</p>
     *
     * @return Samples at or above {@link #RANGE_MS} milliseconds
     */
    public long getOverflowCount() {
        synchronized (this) {
            return overflowCount;
        }
    }

    // ============================================
    // Instance methods for JMX access
    // ============================================

    /**
     * Gets the total number of entries recorded (instance method).
     */
    public long getTotalCount() {
        synchronized (this) {
            return totalEntries;
        }
    }

    /**
     * Gets the average latency (instance method).
     */
    public double getAverage() {
        synchronized (this) {
            if (totalEntries == 0) return 0.0;
            long sum = 0;
            for (int i = 0; i < NUM_BUCKETS; i++) {
                sum += buckets[i] * (i * 10000L / NUM_BUCKETS);
            }
            // Overflowing samples count at their real size. Folding them into the top bucket
            // instead would pull the average toward the ceiling from above, which is the one
            // direction that hides a bad tail.
            return (double) (sum + overflowSum) / totalEntries;
        }
    }

    /**
     * Gets the minimum latency recorded (instance method).
     */
    public long getMinimum() {
        synchronized (this) {
            int firstBucket = findFirstNonZeroBucket();
            if (firstBucket == NUM_BUCKETS) {
                return overflowCount == 0 ? 0 : overflowMin;
            }
            return (long) (firstBucket * 10000.0 / NUM_BUCKETS);
        }
    }

    /**
     * Gets the maximum latency recorded (instance method).
     */
    public long getMaximum() {
        synchronized (this) {
            if (overflowCount > 0) {
                return overflowMax;
            }
            int lastBucket = findLastNonZeroBucket();
            if (lastBucket == -1) return 0;
            return (long) (lastBucket * 10000.0 / NUM_BUCKETS);
        }
    }

    /**
     * Gets the average FP rate (instance method).
     */
    public double getAverageFpRate() {
        synchronized (this) {
            if (fpRateEntries == 0) return 0.0;
            long sum = 0;
            for (int i = 0; i < NUM_FP_RATES; i++) {
                sum += fpRateBuckets[i] * i;
            }
            return (double) sum / fpRateEntries / NUM_FP_RATES;
        }
    }

    /**
     * Gets the epoch the most recent FP rate measurement was stamped with (instance method).
     */
    public long getFpRateEpoch() {
        return fpRateEpoch;
    }

    /**
     * Gets the epoch this histogram was last told about (instance method).
     */
    public long getCurrentEpoch() {
        return currentEpoch;
    }

    /**
     * Gets the FP rate count (instance method).
     */
    public long getFpRateCount() {
        synchronized (this) {
            return fpRateEntries;
        }
    }

    /**
     * Gets the maximum FP rate recorded (instance method).
     */
    public double getMaxFpRate() {
        synchronized (this) {
            for (int i = NUM_FP_RATES - 1; i >= 0; i--) {
                if (fpRateBuckets[i] > 0) {
                    return (double) i / NUM_FP_RATES;
                }
            }
            return 0.0;
        }
    }

    // Convenience methods for common percentiles (static for Metrics access)
    /**
     * Gets the p0 (minimum) latency value.
     * This is the minimum recorded latency, representing the 0th percentile.
     *
     * @return the minimum latency in milliseconds
     */
    public static long getP0() {
        return histogram.getMinimum();
    }

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
     * Gets the singleton histogram instance for direct access.
     *
     * @return The singleton histogram instance
     */
    public static FixedBucketHistogram getInstance() {
        return histogram;
    }

    /**
     * Discards all recorded measurements and FP rate samples.
     */
    public void reset() {
        synchronized (this) {
            Arrays.fill(buckets, 0L);
            Arrays.fill(fpRateBuckets, 0L);
            totalEntries = 0;
            fpRateEntries = 0;
            overflowCount = 0;
            overflowSum = 0;
            overflowMin = Long.MAX_VALUE;
            overflowMax = 0;
        }
    }

    /**
     * Finds the bucket index for a given latency.
     * Caps the index to prevent ArrayIndexOutOfBoundsException for large latencies.
     *
     * @param latency Latency in ms
     * @return Bucket index (0 to NUM_BUCKETS-1)
     */
    private int getBucketIndex(long latency) {
        int index = (int) (latency * NUM_BUCKETS / 10000.0);
        return Math.max(0, Math.min(NUM_BUCKETS - 1, index));
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
     * Correctly accumulates counts across buckets to find the percentile position.
     *
     * @param target Target cumulative count
     * @return Bucket index where cumulative count reaches or exceeds target, or -1 when the
     *         target lies past every bucket, among the overflowing samples
     */
    private int findBucketAtTarget(double target) {
        long cumulative = 0;
        for (int i = 0; i < NUM_BUCKETS; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                return i;
            }
        }
        return -1;
    }
}