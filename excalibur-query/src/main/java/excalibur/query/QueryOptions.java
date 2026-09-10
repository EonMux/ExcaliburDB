package excalibur.query;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for ANN queries.
 *
 * <p>QueryOptions controls the behavior of approximate nearest neighbor searches,
 * including result limits, filtering thresholds, timeout, and whether to include
 * partially-indexed segments.</p>
 *
 * <h2>Default Values</h2>
 * <ul>
 *   <li>{@code topK}: 10</li>
 *   <li>{@code threshold}: 0.0f (no filtering)</li>
 *   <li>{@code includePartialSegments}: true</li>
 *   <li>{@code timeout}: 30 seconds</li>
 *   <li>{@code oversamplingFactor}: 2</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class QueryOptions {

    /**
     * Default number of results to return.
     */
    public static final int DEFAULT_TOP_K = 10;

    /**
     * Default minimum score threshold (no filtering).
     */
    public static final float DEFAULT_THRESHOLD = 0.0f;

    /**
     * Default timeout for query execution.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Default oversampling factor for HNSW search.
     * Higher values improve recall at the cost of latency.
     */
    public static final int DEFAULT_OVERSAMPLING_FACTOR = 2;

    /**
     * Default whether to include partially-indexed segments.
     */
    public static final boolean DEFAULT_INCLUDE_PARTIAL_SEGMENTS = true;

    private final int topK;
    private final float threshold;
    private final boolean includePartialSegments;
    private final Duration timeout;
    private final int oversamplingFactor;

    private QueryOptions(Builder builder) {
        this.topK = builder.topK;
        this.threshold = builder.threshold;
        this.includePartialSegments = builder.includePartialSegments;
        this.timeout = builder.timeout;
        this.oversamplingFactor = builder.oversamplingFactor;
    }

    /**
     * Creates a new QueryOptions with default values.
     *
     * @return QueryOptions with defaults
     */
    public static QueryOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a new builder for QueryOptions.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the number of results to return.
     *
     * @return The top-k value (positive integer)
     */
    public int topK() {
        return topK;
    }

    /**
     * Returns the minimum score a result must reach to be returned.
     *
     * <p>A floor on the normalized [0, 1] score, never a ceiling on raw distance. Every metric's
     * distance is converted to that scale before this is applied, so one number means the same
     * thing under cosine, L2 and inner product; 0 disables filtering.</p>
     *
     * @return The threshold value
     */
    public float threshold() {
        return threshold;
    }

    /**
     * Returns whether to include partially-indexed segments.
     *
     * <p>A partially-indexed segment is one still taking entries - INDEXING. A warming segment
     * has every entry in and a compacting one serves a frozen snapshot, so both answer a query
     * as an active segment does and are searched either way.</p>
     *
     * <p>When true, queries also search the indexing segments, using the structures those have
     * so far plus a write log tail scan for entries the graph has not absorbed. When false, each
     * segment held back is counted on the response as an excluded segment and lowers the reported
     * corpus coverage.</p>
     *
     * @return true to include partial segments
     */
    public boolean includePartialSegments() {
        return includePartialSegments;
    }

    /**
     * Returns the timeout for query execution.
     *
     * <p>Queries that exceed this duration are cancelled and return
     * partial results with a degraded flag.</p>
     *
     * @return The timeout duration
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the oversampling factor for HNSW search.
     *
     * <p>When searching each segment, the system requests k * oversamplingFactor
     * candidates from the HNSW graph to improve recall after merging.</p>
     *
     * @return The oversampling factor (positive integer)
     */
    public int oversamplingFactor() {
        return oversamplingFactor;
    }

    /**
     * Creates a new builder initialized with values from this QueryOptions.
     *
     * @return A new builder with copied values
     */
    public Builder toBuilder() {
        return new Builder()
            .topK(topK)
            .threshold(threshold)
            .includePartialSegments(includePartialSegments)
            .timeout(timeout)
            .oversamplingFactor(oversamplingFactor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryOptions that = (QueryOptions) o;
        return topK == that.topK &&
            Float.compare(that.threshold, threshold) == 0 &&
            includePartialSegments == that.includePartialSegments &&
            Objects.equals(timeout, that.timeout) &&
            oversamplingFactor == that.oversamplingFactor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(topK, threshold, includePartialSegments, timeout, oversamplingFactor);
    }

    @Override
    public String toString() {
        return String.format(
            "QueryOptions{topK=%d, threshold=%.3f, includePartial=%s, timeout=%s, oversampling=%d}",
            topK, threshold, includePartialSegments, timeout, oversamplingFactor);
    }

    /**
     * Builder for QueryOptions.
     */
    public static final class Builder {
        private int topK = DEFAULT_TOP_K;
        private float threshold = DEFAULT_THRESHOLD;
        private boolean includePartialSegments = DEFAULT_INCLUDE_PARTIAL_SEGMENTS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int oversamplingFactor = DEFAULT_OVERSAMPLING_FACTOR;

        private Builder() {}

        /**
         * Sets the number of results to return.
         *
         * @param topK The number of results (must be positive and at most
         *             {@link QueryRequest#MAX_TOP_K})
         * @return This builder
         * @throws IllegalArgumentException if topK is not in [1, {@link QueryRequest#MAX_TOP_K}]
         */
        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive: " + topK);
            }
            if (topK > QueryRequest.MAX_TOP_K) {
                throw new IllegalArgumentException(
                    "topK must be at most " + QueryRequest.MAX_TOP_K + ": " + topK);
            }
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum score threshold for results.
         *
         * <p><strong>Domain Correctness:</strong> The threshold must be in [0.0, 1.0]
         * because relevancy scores are in this range. Values outside this range
         * would result in either all results being filtered out (threshold &gt; 1.0)
         * or no filtering at all (threshold &lt; 0.0), which is likely a user error.</p>
         *
         * @param threshold The threshold value (must be in [0.0, 1.0])
         * @return This builder
         * @throws IllegalArgumentException if threshold is NaN or outside [0.0, 1.0]
         */
        public Builder threshold(float threshold) {
            // NaN check must be explicit since NaN comparisons return false
            if (Float.isNaN(threshold)) {
                throw new IllegalArgumentException("Threshold cannot be NaN");
            }
            if (threshold < 0.0f || threshold > 1.0f) {
                throw new IllegalArgumentException(
                    "Threshold must be in [0.0, 1.0], got: " + threshold);
            }
            this.threshold = threshold;
            return this;
        }

        /**
         * Sets whether to include partially-indexed segments.
         *
         * @param includePartialSegments true to include partial segments
         * @return This builder
         */
        public Builder includePartialSegments(boolean includePartialSegments) {
            this.includePartialSegments = includePartialSegments;
            return this;
        }

        /**
         * Sets the timeout for query execution.
         *
         * @param timeout The timeout duration (must not be null or negative)
         * @return This builder
         * @throws NullPointerException if timeout is null
         * @throws IllegalArgumentException if timeout is negative
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "Timeout cannot be null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Timeout cannot be negative: " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the oversampling factor for HNSW search.
         *
         * @param factor The oversampling factor (must be at least 1)
         * @return This builder
         * @throws IllegalArgumentException if factor is less than 1
         */
        public Builder oversamplingFactor(int factor) {
            if (factor < 1) {
                throw new IllegalArgumentException("Oversampling factor must be at least 1: " + factor);
            }
            this.oversamplingFactor = factor;
            return this;
        }

        /**
         * Builds the QueryOptions instance.
         *
         * @return A new QueryOptions with the configured values
         */
        public QueryOptions build() {
            return new QueryOptions(this);
        }
    }
}
