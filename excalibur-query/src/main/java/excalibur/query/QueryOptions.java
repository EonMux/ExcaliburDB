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
     * Returns the minimum score threshold for results.
     *
     * <p>Results with scores below this threshold are filtered out.
     * For distance-based metrics, this is a maximum distance threshold.</p>
     *
     * @return The threshold value
     */
    public float threshold() {
        return threshold;
    }

    /**
     * Returns whether to include partially-indexed segments.
     *
     * <p>Partially-indexed segments are those in INDEXING or WARMING states.
     * When true, queries will search these segments using available structures
     * (write log tail scan for un-integrated entries).</p>
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
         * @param topK The number of results (must be positive)
         * @return This builder
         * @throws IllegalArgumentException if topK is not positive
         */
        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive: " + topK);
            }
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum score threshold for results.
         *
         * @param threshold The threshold value
         * @return This builder
         */
        public Builder threshold(float threshold) {
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
