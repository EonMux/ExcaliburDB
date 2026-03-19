/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - IndexCoverage: Thread-safe, immutable
 */

package io.excalibur.test;

import java.util.Collections;
import java.util.Objects;

/**
 * Immutable representation of query index coverage information.
 * 
 * This class provides visibility into the state of the index during queries,
 * including segment counts, lifecycle states, and estimated corpus coverage.
 */
public class IndexCoverage {
    
    private final int totalSegments;
    private final int activeSegments;
    private final int indexingSegments;
    private final int warmingSegments;
    private final double estimatedCoverage;
    private final boolean degraded;
    
    /**
     * Create a new IndexCoverage.
     *
     * @param totalSegments  Total number of segments in the index
     * @param activeSegments Number of ACTIVE segments (fully indexed and optimized)
     * @param indexingSegments Number of INDEXING segments (being populated)
     * @param warmingSegments Number of WARMING segments (being optimized)
     * @param estimatedCoverage Estimated fraction of corpus covered (must be in [0.0, 1.0])
     * @param degraded true if coverage is below readiness threshold
     * @throws IllegalArgumentException if estimatedCoverage is outside [0.0, 1.0] or any count is negative
     */
    public IndexCoverage(int totalSegments, int activeSegments, int indexingSegments,
                         int warmingSegments, double estimatedCoverage, boolean degraded) {
        // Domain correctness validation: coverage must be in [0.0, 1.0] (0-100%)
        // Note: NaN checks are explicit because NaN comparisons always return false
        if (Double.isNaN(estimatedCoverage) || estimatedCoverage < 0.0 || estimatedCoverage > 1.0) {
            throw new IllegalArgumentException(
                "estimatedCoverage must be in range [0.0, 1.0], got: " + estimatedCoverage);
        }
        if (totalSegments < 0) {
            throw new IllegalArgumentException("totalSegments cannot be negative: " + totalSegments);
        }
        if (activeSegments < 0) {
            throw new IllegalArgumentException("activeSegments cannot be negative: " + activeSegments);
        }
        if (indexingSegments < 0) {
            throw new IllegalArgumentException("indexingSegments cannot be negative: " + indexingSegments);
        }
        if (warmingSegments < 0) {
            throw new IllegalArgumentException("warmingSegments cannot be negative: " + warmingSegments);
        }
        this.totalSegments = totalSegments;
        this.activeSegments = activeSegments;
        this.indexingSegments = indexingSegments;
        this.warmingSegments = warmingSegments;
        this.estimatedCoverage = estimatedCoverage;
        this.degraded = degraded;
    }
    
    /**
     * Create an empty IndexCoverage.
     */
    public static IndexCoverage empty() {
        return new IndexCoverage(0, 0, 0, 0, 0.0, true);
    }
    
    /**
     * Create an IndexCoverage with all segments active.
     */
    public static IndexCoverage fullyActive(int segmentCount) {
        return new IndexCoverage(
            segmentCount, segmentCount, 0, 0, 1.0, false
        );
    }
    
    /**
     * Create an IndexCoverage with a warm-up segment.
     */
    public static IndexCoverage withWarmUp(int segmentCount) {
        return new IndexCoverage(
            segmentCount, segmentCount - 1, 0, 1, 1.0, false
        );
    }
    
    /**
     * Get the total number of segments.
     * 
     * @return The total segment count
     */
    public int getTotalSegments() {
        return totalSegments;
    }
    
    /**
     * Get the number of active (fully indexed) segments.
     * 
     * @return The active segment count
     */
    public int getActiveSegments() {
        return activeSegments;
    }
    
    /**
     * Get the number of indexing (being populated) segments.
     * 
     * @return The indexing segment count
     */
    public int getIndexingSegments() {
        return indexingSegments;
    }
    
    /**
     * Get the number of warming (being optimized) segments.
     * 
     * @return The warming segment count
     */
    public int getWarmingSegments() {
        return warmingSegments;
    }
    
    /**
     * Get the estimated corpus coverage.
     * 
     * @return The coverage fraction (0.0 to 1.0)
     */
    public double getEstimatedCoverage() {
        return estimatedCoverage;
    }
    
    /**
     * Check if the index is degraded.
     * 
     * @return true if coverage is below readiness threshold
     */
    public boolean isDegraded() {
        return degraded;
    }
    
    /**
     * Calculate active ratio.
     * 
     * @return The ratio of active segments to total (0.0 to 1.0)
     */
    public double getActiveRatio() {
        return totalSegments > 0 ? (double) activeSegments / totalSegments : 0.0;
    }
    
    /**
     * Calculate queryable ratio.
     * 
     * Segments in INDEXING and WARMING states are queryable.
     * 
     * @return The ratio of queryable segments to total (0.0 to 1.0)
     */
    public double getQueryableRatio() {
        int queryable = activeSegments + indexingSegments + warmingSegments;
        return totalSegments > 0 ? (double) queryable / totalSegments : 0.0;
    }
    
    @Override
    public String toString() {
        return "IndexCoverage{total=" + totalSegments + 
               ", active=" + activeSegments + 
               ", indexing=" + indexingSegments + 
               ", warming=" + warmingSegments + 
               ", coverage=" + String.format("%.2f", estimatedCoverage) + 
               ", degraded=" + degraded + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexCoverage)) return false;
        IndexCoverage that = (IndexCoverage) o;
        return totalSegments == that.totalSegments &&
               activeSegments == that.activeSegments &&
               indexingSegments == that.indexingSegments &&
               warmingSegments == that.warmingSegments &&
               Math.abs(estimatedCoverage - that.estimatedCoverage) < 1e-6 &&
               degraded == that.degraded;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(totalSegments, activeSegments, indexingSegments,
                           warmingSegments, estimatedCoverage, degraded);
    }
}
