package excalibur.query;

import java.util.Objects;

/**
 * Metadata about index coverage for a query response.
 *
 * <p>IndexCoverage provides visibility into which segments were searched
 * and how complete the search was. This allows clients to understand
 * query quality and detect degraded states.</p>
 *
 * <h2>Coverage Calculation</h2>
 * <p>Estimated corpus coverage is the share of the live corpus this query was allowed to read:</p>
 * <pre>
 * queryable = activeSegments + indexingSegments + warmingSegments + compactingSegments
 * coverage  = (queryable - segmentsExcluded) / (totalSegments - tombstonedSegments)
 * </pre>
 * <p>{@code segmentsExcluded} is what separates this from a catalog census. A segment held back
 * because the caller asked for complete segments only, or dropped because its graph was not
 * available, is counted by the catalog and never searched; without subtracting it the response
 * reports full coverage over an answer the query never saw.</p>
 *
 * <h2>Degraded State</h2>
 * <p>A query is considered "degraded" when:</p>
 * <ul>
 *   <li>Coverage is below a configurable threshold (default 0.5)</li>
 *   <li>Some segments timed out during search</li>
 *   <li>Errors occurred during segment search</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All segment counts are non-negative</li>
 *   <li>estimatedCorpusCoverage is in range [0.0, 1.0]</li>
 *   <li>tailScanEntries is non-negative</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class IndexCoverage {

    private final long totalSegments;
    private final long activeSegments;
    private final long indexingSegments;
    private final long warmingSegments;
    private final long compactingSegments;
    private final long tombstonedSegments;
    private final double estimatedCorpusCoverage;
    private final long tailScanEntries;
    private final boolean degraded;
    private final int segmentsSearched;
    private final int segmentsExcluded;
    private final int segmentsWithErrors;
    private final int segmentsTimedOut;

    private IndexCoverage(Builder builder) {
        this.totalSegments = builder.totalSegments;
        this.activeSegments = builder.activeSegments;
        this.indexingSegments = builder.indexingSegments;
        this.warmingSegments = builder.warmingSegments;
        this.compactingSegments = builder.compactingSegments;
        this.tombstonedSegments = builder.tombstonedSegments;
        this.estimatedCorpusCoverage = builder.estimatedCorpusCoverage;
        this.tailScanEntries = builder.tailScanEntries;
        this.degraded = builder.degraded;
        this.segmentsSearched = builder.segmentsSearched;
        this.segmentsExcluded = builder.segmentsExcluded;
        this.segmentsWithErrors = builder.segmentsWithErrors;
        this.segmentsTimedOut = builder.segmentsTimedOut;
    }

    /**
     * Creates a new builder for IndexCoverage.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the total number of segments in the index.
     *
     * @return Total segment count
     */
    public long totalSegments() {
        return totalSegments;
    }

    /**
     * Returns the number of fully active segments.
     *
     * @return Active segment count
     */
    public long activeSegments() {
        return activeSegments;
    }

    /**
     * Returns the number of segments currently indexing.
     *
     * @return Indexing segment count
     */
    public long indexingSegments() {
        return indexingSegments;
    }

    /**
     * Returns the number of segments in warming state.
     *
     * @return Warming segment count
     */
    public long warmingSegments() {
        return warmingSegments;
    }

    /**
     * Returns the number of segments currently compacting.
     *
     * @return Compacting segment count
     */
    public long compactingSegments() {
        return compactingSegments;
    }

    /**
     * Returns the number of tombstoned segments.
     *
     * @return Tombstoned segment count
     */
    public long tombstonedSegments() {
        return tombstonedSegments;
    }

    /**
     * Returns the estimated corpus coverage.
     *
     * <p>Value is in range [0.0, 1.0], where 1.0 indicates full coverage.</p>
     *
     * @return Estimated coverage ratio
     */
    public double estimatedCorpusCoverage() {
        return estimatedCorpusCoverage;
    }

    /**
     * Returns the number of entries scanned from write log tails.
     *
     * <p>These are entries that exist in the write log but haven't been
     * integrated into the HNSW graph yet.</p>
     *
     * @return Tail scan entry count
     */
    public long tailScanEntries() {
        return tailScanEntries;
    }

    /**
     * Returns whether the query is in a degraded state.
     *
     * <p>A query is degraded when coverage is below threshold or errors occurred.</p>
     *
     * @return true if degraded
     */
    public boolean degraded() {
        return degraded;
    }

    /**
     * Returns the number of segments successfully searched.
     *
     * @return Segments searched count
     */
    public int segmentsSearched() {
        return segmentsSearched;
    }

    /**
     * Returns the number of segments the query was not permitted to search.
     *
     * <p>These are segments the catalog counts as live corpus that this query never looked at:
     * one held back because the caller asked for complete segments only, or one whose HNSW graph
     * was not available. They are gaps in the answer, not errors, and nothing else on the
     * response records them.</p>
     *
     * @return Segments excluded count
     */
    public int segmentsExcluded() {
        return segmentsExcluded;
    }

    /**
     * Returns the number of segments that encountered errors during search.
     *
     * @return Segments with errors count
     */
    public int segmentsWithErrors() {
        return segmentsWithErrors;
    }

    /**
     * Returns the number of segments that timed out during search.
     *
     * @return Segments timed out count
     */
    public int segmentsTimedOut() {
        return segmentsTimedOut;
    }

    /**
     * Computes the queryable segment count.
     *
     * @return Sum of active, indexing, warming, and compacting segments
     */
    public long queryableSegments() {
        return activeSegments + indexingSegments + warmingSegments + compactingSegments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexCoverage that = (IndexCoverage) o;
        return totalSegments == that.totalSegments &&
            activeSegments == that.activeSegments &&
            indexingSegments == that.indexingSegments &&
            warmingSegments == that.warmingSegments &&
            compactingSegments == that.compactingSegments &&
            tombstonedSegments == that.tombstonedSegments &&
            Double.compare(that.estimatedCorpusCoverage, estimatedCorpusCoverage) == 0 &&
            tailScanEntries == that.tailScanEntries &&
            degraded == that.degraded &&
            segmentsSearched == that.segmentsSearched &&
            segmentsExcluded == that.segmentsExcluded &&
            segmentsWithErrors == that.segmentsWithErrors &&
            segmentsTimedOut == that.segmentsTimedOut;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            totalSegments, activeSegments, indexingSegments, warmingSegments,
            compactingSegments, tombstonedSegments, estimatedCorpusCoverage,
            tailScanEntries, degraded, segmentsSearched, segmentsExcluded,
            segmentsWithErrors, segmentsTimedOut);
    }

    @Override
    public String toString() {
        return String.format(
            "IndexCoverage{total=%d, active=%d, indexing=%d, warming=%d, compacting=%d, " +
            "coverage=%.2f%%, tailScan=%d, degraded=%s, searched=%d, excluded=%d, errors=%d, " +
            "timeouts=%d}",
            totalSegments, activeSegments, indexingSegments, warmingSegments, compactingSegments,
            estimatedCorpusCoverage * 100, tailScanEntries, degraded,
            segmentsSearched, segmentsExcluded, segmentsWithErrors, segmentsTimedOut);
    }

    /**
     * Builder for IndexCoverage.
     */
    public static final class Builder {
        private long totalSegments = 0;
        private long activeSegments = 0;
        private long indexingSegments = 0;
        private long warmingSegments = 0;
        private long compactingSegments = 0;
        private long tombstonedSegments = 0;
        private double estimatedCorpusCoverage = 0.0;
        private long tailScanEntries = 0;
        private boolean degraded = false;
        private int segmentsSearched = 0;
        private int segmentsExcluded = 0;
        private int segmentsWithErrors = 0;
        private int segmentsTimedOut = 0;

        private Builder() {}

        /**
         * Sets the total number of segments.
         */
        public Builder totalSegments(long totalSegments) {
            if (totalSegments < 0) {
                throw new IllegalArgumentException("totalSegments cannot be negative: " + totalSegments);
            }
            this.totalSegments = totalSegments;
            return this;
        }

        /**
         * Sets the number of active segments.
         */
        public Builder activeSegments(long activeSegments) {
            if (activeSegments < 0) {
                throw new IllegalArgumentException("activeSegments cannot be negative: " + activeSegments);
            }
            this.activeSegments = activeSegments;
            return this;
        }

        /**
         * Sets the number of indexing segments.
         */
        public Builder indexingSegments(long indexingSegments) {
            if (indexingSegments < 0) {
                throw new IllegalArgumentException("indexingSegments cannot be negative: " + indexingSegments);
            }
            this.indexingSegments = indexingSegments;
            return this;
        }

        /**
         * Sets the number of warming segments.
         */
        public Builder warmingSegments(long warmingSegments) {
            if (warmingSegments < 0) {
                throw new IllegalArgumentException("warmingSegments cannot be negative: " + warmingSegments);
            }
            this.warmingSegments = warmingSegments;
            return this;
        }

        /**
         * Sets the number of compacting segments.
         */
        public Builder compactingSegments(long compactingSegments) {
            if (compactingSegments < 0) {
                throw new IllegalArgumentException("compactingSegments cannot be negative: " + compactingSegments);
            }
            this.compactingSegments = compactingSegments;
            return this;
        }

        /**
         * Sets the number of tombstoned segments.
         */
        public Builder tombstonedSegments(long tombstonedSegments) {
            if (tombstonedSegments < 0) {
                throw new IllegalArgumentException("tombstonedSegments cannot be negative: " + tombstonedSegments);
            }
            this.tombstonedSegments = tombstonedSegments;
            return this;
        }

        /**
         * Sets the estimated corpus coverage.
         *
         * @param coverage Coverage ratio (0.0 to 1.0)
         * @throws IllegalArgumentException if coverage is NaN or outside [0.0, 1.0]
         */
        public Builder estimatedCorpusCoverage(double coverage) {
            // Note: NaN checks are explicit because NaN comparisons always return false
            if (Double.isNaN(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("Coverage must be in range [0.0, 1.0]: " + coverage);
            }
            this.estimatedCorpusCoverage = coverage;
            return this;
        }

        /**
         * Sets the number of tail scan entries.
         */
        public Builder tailScanEntries(long tailScanEntries) {
            if (tailScanEntries < 0) {
                throw new IllegalArgumentException("tailScanEntries cannot be negative: " + tailScanEntries);
            }
            this.tailScanEntries = tailScanEntries;
            return this;
        }

        /**
         * Sets whether the query is degraded.
         */
        public Builder degraded(boolean degraded) {
            this.degraded = degraded;
            return this;
        }

        /**
         * Sets the number of segments successfully searched.
         */
        public Builder segmentsSearched(int segmentsSearched) {
            if (segmentsSearched < 0) {
                throw new IllegalArgumentException("segmentsSearched cannot be negative: " + segmentsSearched);
            }
            this.segmentsSearched = segmentsSearched;
            return this;
        }

        /**
         * Sets the number of segments the query was not permitted to search.
         */
        public Builder segmentsExcluded(int segmentsExcluded) {
            if (segmentsExcluded < 0) {
                throw new IllegalArgumentException("segmentsExcluded cannot be negative: " + segmentsExcluded);
            }
            this.segmentsExcluded = segmentsExcluded;
            return this;
        }

        /**
         * Sets the number of segments with errors.
         */
        public Builder segmentsWithErrors(int segmentsWithErrors) {
            if (segmentsWithErrors < 0) {
                throw new IllegalArgumentException("segmentsWithErrors cannot be negative: " + segmentsWithErrors);
            }
            this.segmentsWithErrors = segmentsWithErrors;
            return this;
        }

        /**
         * Sets the number of segments that timed out.
         */
        public Builder segmentsTimedOut(int segmentsTimedOut) {
            if (segmentsTimedOut < 0) {
                throw new IllegalArgumentException("segmentsTimedOut cannot be negative: " + segmentsTimedOut);
            }
            this.segmentsTimedOut = segmentsTimedOut;
            return this;
        }

        /**
         * Computes coverage from segment counts and sets it.
         *
         * <p>The numerator is what this query was allowed to read: the queryable segments the
         * catalog holds, less the ones it was not permitted to search. Counting the catalog alone
         * reports full coverage over a query that was shown nothing.</p>
         *
         * <p>The computed coverage is clamped to [0.0, 1.0] to ensure
         * domain correctness regardless of input values.</p>
         *
         * @return This builder
         */
        public Builder computeCoverage() {
            long nonPurged = totalSegments - tombstonedSegments;
            if (nonPurged <= 0) {
                this.estimatedCorpusCoverage = 0.0;
            } else {
                double raw = (double) (queryable() - segmentsExcluded) / nonPurged;
                // Clamp to [0.0, 1.0] - domain correctness guarantee
                this.estimatedCorpusCoverage = Math.max(0.0, Math.min(1.0, raw));
            }
            return this;
        }

        /**
         * Computes degraded flag based on coverage threshold.
         *
         * @param threshold The coverage threshold (default 0.5)
         * @return This builder
         */
        public Builder computeDegraded(double threshold) {
            this.degraded = estimatedCorpusCoverage < threshold ||
                segmentsWithErrors > 0 ||
                segmentsTimedOut > 0;
            return this;
        }

        /**
         * Adds a reason for degradation that the coverage numbers cannot see.
         *
         * <p>A stage can degrade a response without any segment being missing or failing - an LLM
         * scorer that fell back to vector-only ranking is the case this exists for. Such a reason
         * is ORed into the flag rather than assigned, so that adding one cannot erase a reason
         * {@link #computeDegraded(double)} already found; {@link #degraded(boolean)} assigns, and
         * is for a caller stating the whole verdict itself.</p>
         *
         * @param degraded true to mark the response degraded, false to leave the flag as it stands
         * @return This builder
         */
        public Builder orDegraded(boolean degraded) {
            this.degraded = this.degraded || degraded;
            return this;
        }

        /** The segments the catalog counts as readable, before this query's own exclusions. */
        private long queryable() {
            return activeSegments + indexingSegments + warmingSegments + compactingSegments;
        }

        /**
         * Builds the IndexCoverage instance.
         *
         * @return A new IndexCoverage
         */
        public IndexCoverage build() {
            return new IndexCoverage(this);
        }
    }
}
