package io.excalibur.core.metrics;

import java.util.Map;
import java.util.HashMap;

/**
 * Fixed-bucket histogram for latency distribution analysis.
 * 
 * Thread-safety: This class is thread-safe. Use synchronized access or
 * AtomicLongCounter for concurrent updates.
 * 
 * Domain Correctness:
 * - Bucket counts must be monotonically non-decreasing
 * - Total count must equal sum of all bucket counts
 * - Percentiles are calculated from sorted bucket values
 * - Bucket sizes must be positive
 * 
 * @see Metrics
 */
public final class FixedBucketHistogram {
    
    private final int bucketCount;
    private final double[] bucketBounds;
    private final int[] bucketCounts;
    private final AtomicLongCounter totalCounter;
    
    public FixedBucketHistogram(int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("Bucket count must be positive");
        }
        
        this.bucketCount = bucketCount;
        
        // Create bucket bounds (logarithmic spacing for better precision)
        double minLog = Math.log10(Math.min(0.001, 1.0 / bucketCount));
        double maxLog = Math.log10(bucketCount);
        double logStep = (maxLog - minLog) / (bucketCount - 1);
        
        this.bucketBounds = new double[bucketCount + 1];
        for (int i = 0; i <= bucketCount; i++) {
            this.bucketBounds[i] = Math.pow(10, minLog + i * logStep);
        }
        
        this.bucketCounts = new int[bucketCount];
        this.totalCounter = new AtomicLongCounter(0);
    }
    
    /**
     * Records a latency measurement.
     * 
     * @param latencyLatency in milliseconds (must be non-negative)
     * @throws IllegalArgumentException if latency is negative
     */
    public void record(long latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("Latency must be non-negative: " + latencyMs);
        }
        
        synchronized(this) {
            // Find appropriate bucket
            int bucketIdx = findBucket(latencyMs);
            bucketCounts[bucketIdx]++;
            totalCounter.increment();
        }
    }
    
    /**
     * Records a latency measurement using AtomicLongCounter for thread safety.
     * 
     * @param latencyLatency in milliseconds (must be non-negative)
     */
    public synchronized void recordAtomic(long latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("Latency must be non-negative: " + latencyMs);
        }
        
        int bucketIdx = findBucket(latencyMs);
        bucketCounts[bucketIdx]++;
        totalCounter.increment();
    }
    
    /**
     * Finds the appropriate bucket index for a given latency.
     * 
     * @param latencyMs Latency in milliseconds
     * @return Bucket index (0 to bucketCount-1)
     */
    private int findBucket(long latencyMs) {
        // Binary search for bucket index
        int low = 0;
        int high = bucketCount - 1;
        
        while (low < high) {
            int mid = (low + high) / 2;
            if (latencyMs >= bucketBounds[mid]) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        
        return low;
    }
    
    /**
     * Returns the total number of recorded measurements.
     * 
     * @return Total count (monotonically increasing)
     */
    public long getTotalCount() {
        return totalCounter.get();
    }
    
    /**
     * Returns the count in a specific bucket.
     * 
     * @param bucketIdx Bucket index (0 to bucketCount-1)
     * @return Bucket count (monotonically non-decreasing)
     */
    public int getBucketCount(int bucketIdx) {
        if (bucketIdx < 0 || bucketIdx >= bucketCount) {
            throw new IndexOutOfBoundsException("Bucket index out of range: " + bucketIdx);
        }
        return bucketCounts[bucketIdx];
    }
    
    /**
     * Returns the upper bound of a bucket.
     * 
     * @param bucketIdx Bucket index (0 to bucketCount-1)
     * @return Upper bound in milliseconds
     */
    public double getBucketUpperBound(int bucketIdx) {
        if (bucketIdx < 0 || bucketIdx >= bucketCount) {
            throw new IndexOutOfBoundsException("Bucket index out of range: " + bucketIdx);
        }
        return bucketBounds[bucketIdx + 1];
    }
    
    /**
     * Returns the p50 (median) latency.
     * 
     * @return 50th percentile latency in milliseconds
     */
    public double getP50() {
        return calculatePercentile(50);
    }
    
    /**
     * Returns the p99 latency.
     * 
     * @return 99th percentile latency in milliseconds
     */
    public double getP99() {
        return calculatePercentile(99);
    }
    
    /**
     * Returns the p99.9 latency.
     * 
     * @return 99.9th percentile latency in milliseconds
     */
    public double getP999() {
        return calculatePercentile(99.9);
    }
    
    /**
     * Returns the p95 latency.
     * 
     * @return 95th percentile latency in milliseconds
     */
    public double getP95() {
        return calculatePercentile(95);
    }
    
    /**
     * Returns the p90 latency.
     * 
     * @return 90th percentile latency in milliseconds
     */
    public double getP90() {
        return calculatePercentile(90);
    }
    
    /**
     * Calculates the specified percentile.
     * 
     * @param percentile Target percentile (e.g., 50, 90, 95, 99, 99.9)
     * @return Percentile latency in milliseconds
     * 
     * Domain Correctness:
     * - Percentile values must be between minimum and maximum recorded latencies
     * - Percentile is calculated by finding the value at rank (percentile/100) * total
     */
    private double calculatePercentile(double percentile) {
        if (getTotalCount() == 0) {
            return 0.0;
        }
        
        synchronized(this) {
            // Find bucket index for percentile
            int percentileBucketIdx = findPercentileBucket((int)(percentile * 100));
            
            // Find exact value in bucket using binary search
            long totalInBucket = bucketCounts[percentileBucketIdx];
            long targetRank = (long)(getTotalCount() * percentile / 100.0);
            long targetOffset = targetRank - totalInBucket + 1;
            
            // Linear scan within bucket for exact value
            double latency = 0;
            for (long offset = 0; offset < targetOffset && offset < totalInBucket; offset++) {
                latency = getLatencyAtOffset(offset);
            }
            
            // Return upper bound if at end of bucket
            return latency >= getBucketUpperBound(percentileBucketIdx) 
                ? getBucketUpperBound(percentileBucketIdx) 
                : latency;
        }
    }
    
    /**
     * Finds the bucket index for a given percentile rank.
     * 
     * @param percentileRank 0-100 percentile rank
     * @return Bucket index
     */
    private int findPercentileBucket(int percentileRank) {
        double cumulativePercentile = 0.0;
        
        for (int i = 0; i < bucketCount; i++) {
            double bucketPercentile = (double)bucketCounts[i] / totalCounter.get() * 100.0;
            cumulativePercentile += bucketPercentile;
            
            if (cumulativePercentile >= percentileRank) {
                return i;
            }
        }
        
        return bucketCount - 1;
    }
    
    /**
     * Returns latency at a specific offset within a bucket.
     * Uses linear interpolation for approximate values.
     * 
     * @param offset Offset within bucket (0 to bucketCount-1)
     * @return Approximate latency at offset
     */
    private double getLatencyAtOffset(long offset) {
        // Estimate latency using bucket bounds
        double lowerBound = bucketBounds[(int)offset];
        double upperBound = bucketBounds[(int)offset + 1];
        
        // Linear interpolation within bucket
        double fraction = (offset + 1) / (double)bucketCounts[(int)offset];
        
        return lowerBound + fraction * (upperBound - lowerBound);
    }
    
    /**
     * Returns statistics as a map.
     * 
     * @return Map containing latency statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_count", getTotalCount());
        stats.put("p50", getP50());
        stats.put("p90", getP90());
        stats.put("p95", getP95());
        stats.put("p99", getP99());
        stats.put("p99.9", getP999());
        stats.put("min", getTotalCount() > 0 ? getMinLatency() : 0.0);
        stats.put("max", getTotalCount() > 0 ? getMaxLatency() : 0.0);
        return stats;
    }
    
    /**
     * Returns minimum recorded latency.
     * 
     * @return Minimum latency (non-negative)
     */
    public double getMinLatency() {
        if (getTotalCount() == 0) return 0.0;
        synchronized(this) {
            return bucketBounds[findFirstBucketWithCount()];
        }
    }
    
    /**
     * Returns maximum recorded latency.
     * 
     * @return Maximum latency (non-negative)
     */
    public double getMaxLatency() {
        if (getTotalCount() == 0) return 0.0;
        synchronized(this) {
            return bucketBounds[bucketCount];
        }
    }
    
    /**
     * Finds first bucket with non-zero count.
     * 
     * @return First non-empty bucket index
     */
    private int findFirstBucketWithCount() {
        for (int i = 0; i < bucketCount; i++) {
            if (bucketCounts[i] > 0) {
                return i;
            }
        }
        return 0;
    }
}
