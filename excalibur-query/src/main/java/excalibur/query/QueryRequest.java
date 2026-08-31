package excalibur.query;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Request object for executing ANN queries.
 *
 * <p>QueryRequest encapsulates all input parameters for a vector similarity
 * query, including the query vector, text representation, and configuration
 * options. It is the primary input type for the query pipeline.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a query request
 * float[] vector = embeddingClient.embed("search query text");
 * QueryRequest request = QueryRequest.builder()
 *     .queryText("search query text")
 *     .queryVector(vector)
 *     .topK(10)
 *     .recallTarget(0.95)
 *     .timeoutMs(5000)
 *     .includePartialSegments(true)
 *     .build();
 *
 * // Execute the query
 * QueryResponse response = queryExecutor.execute(request, cancellationToken);
 * }</pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Query vector must be non-null and non-empty</li>
 *   <li>topK must be positive and at most {@link #MAX_TOP_K}</li>
 *   <li>recallTarget must be in range [0.0, 1.0]</li>
 *   <li>timeoutMs must be positive</li>
 *   <li>threshold must be in range [0.0, 1.0]</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. The query vector is defensively
 * copied on construction and access.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class QueryRequest {

    /**
     * Largest result count a request may ask for.
     *
     * <p>topK is client-supplied and flows into collection sizings, oversampling
     * multipliers and per-segment search widths, so it is bounded at the request boundary
     * rather than left to overflow or to allocate on a client's say-so. Ten thousand
     * results is far beyond any realistic top-k query.</p>
     */
    public static final int MAX_TOP_K = 10_000;

    // ==================== Instance Fields ====================

    /**
     * The text representation of the query (for LLM relevancy scoring).
     * May be null if only vector search is needed.
     */
    private final String queryText;

    /**
     * The query vector for similarity search.
     * Must be non-null and non-empty.
     */
    private final float[] queryVector;

    /**
     * The number of results to return.
     */
    private final int topK;

    /**
     * Target recall for the query (0.0 to 1.0).
     * Higher values may result in longer query times.
     */
    private final double recallTarget;

    /**
     * Timeout for query execution in milliseconds.
     */
    private final long timeoutMs;

    /**
     * Whether to include partially-indexed segments.
     */
    private final boolean includePartialSegments;

    /**
     * Minimum score threshold for results.
     */
    private final float threshold;

    /**
     * Whether to enable LLM relevancy scoring.
     */
    private final boolean enableLlmScoring;

    /**
     * LLM relevancy threshold (0.0 to 1.0).
     */
    private final float llmThreshold;

    /**
     * Oversampling factor for HNSW search.
     * Higher values improve recall at the cost of latency.
     */
    private final int oversamplingFactor;

    /**
     * Request correlation ID for logging and tracing.
     */
    private final String correlationId;

    // ==================== Constructors ====================

    private QueryRequest(Builder builder) {
        this.queryText = builder.queryText;
        this.queryVector = builder.queryVector != null
            ? builder.queryVector.clone()
            : null;
        this.topK = builder.topK;
        this.recallTarget = builder.recallTarget;
        this.timeoutMs = builder.timeoutMs;
        this.includePartialSegments = builder.includePartialSegments;
        this.threshold = builder.threshold;
        this.enableLlmScoring = builder.enableLlmScoring;
        this.llmThreshold = builder.llmThreshold;
        this.oversamplingFactor = builder.oversamplingFactor;
        this.correlationId = builder.correlationId;

        validate();
    }

    /**
     * Validates the request parameters.
     *
     * @throws IllegalStateException if any parameter is invalid
     */
    private void validate() {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalStateException("Query vector cannot be null or empty");
        }
        if (topK <= 0) {
            throw new IllegalStateException("topK must be positive: " + topK);
        }
        if (topK > MAX_TOP_K) {
            throw new IllegalStateException("topK must be at most " + MAX_TOP_K + ": " + topK);
        }
        if (recallTarget < 0.0 || recallTarget > 1.0) {
            throw new IllegalStateException("recallTarget must be in [0.0, 1.0]: " + recallTarget);
        }
        if (timeoutMs <= 0) {
            throw new IllegalStateException("timeoutMs must be positive: " + timeoutMs);
        }
        if (threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalStateException("threshold must be in [0.0, 1.0]: " + threshold);
        }
        if (llmThreshold < 0.0f || llmThreshold > 1.0f) {
            throw new IllegalStateException("llmThreshold must be in [0.0, 1.0]: " + llmThreshold);
        }
        if (oversamplingFactor < 1) {
            throw new IllegalStateException("oversamplingFactor must be at least 1: " + oversamplingFactor);
        }
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a new builder for QueryRequest.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a QueryRequest with a query vector and default options.
     *
     * @param queryVector The query vector
     * @return A new QueryRequest
     */
    public static QueryRequest of(float[] queryVector) {
        return builder().queryVector(queryVector).build();
    }

    /**
     * Creates a QueryRequest with query text and vector.
     *
     * @param queryText The query text
     * @param queryVector The query vector
     * @return A new QueryRequest
     */
    public static QueryRequest of(String queryText, float[] queryVector) {
        return builder()
            .queryText(queryText)
            .queryVector(queryVector)
            .build();
    }

    // ==================== Accessors ====================

    /**
     * Returns the query text for LLM relevancy scoring.
     *
     * @return The query text, or null if not provided
     */
    public String queryText() {
        return queryText;
    }

    /**
     * Returns a copy of the query vector.
     *
     * @return The query vector
     */
    public float[] queryVector() {
        return queryVector.clone();
    }

    /**
     * Returns the query vector without copying (for internal use).
     *
     * @return The query vector (do not modify)
     */
    public float[] queryVectorUnsafe() {
        return queryVector;
    }

    /**
     * Returns the dimension of the query vector.
     *
     * @return The vector dimension
     */
    public int dimension() {
        return queryVector.length;
    }

    /**
     * Returns the number of results to return.
     *
     * @return The top-k value
     */
    public int topK() {
        return topK;
    }

    /**
     * Returns the target recall for the query.
     *
     * @return The recall target (0.0 to 1.0)
     */
    public double recallTarget() {
        return recallTarget;
    }

    /**
     * Returns the timeout for query execution.
     *
     * @return The timeout in milliseconds
     */
    public long timeoutMs() {
        return timeoutMs;
    }

    /**
     * Returns the timeout as a Duration.
     *
     * @return The timeout duration
     */
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    /**
     * Returns whether to include partially-indexed segments.
     *
     * @return true if partial segments should be included
     */
    public boolean includePartialSegments() {
        return includePartialSegments;
    }

    /**
     * Returns the minimum score threshold for results.
     *
     * @return The threshold value
     */
    public float threshold() {
        return threshold;
    }

    /**
     * Returns whether LLM relevancy scoring is enabled.
     *
     * @return true if LLM scoring is enabled
     */
    public boolean enableLlmScoring() {
        return enableLlmScoring;
    }

    /**
     * Returns the LLM relevancy threshold.
     *
     * @return The LLM threshold
     */
    public float llmThreshold() {
        return llmThreshold;
    }

    /**
     * Returns the oversampling factor for HNSW search.
     *
     * <p>Higher values improve recall at the cost of latency.
     * The oversampling factor determines how many candidates are
     * retrieved per segment: k * oversamplingFactor.</p>
     *
     * @return The oversampling factor (at least 1)
     */
    public int oversamplingFactor() {
        return oversamplingFactor;
    }

    /**
     * Returns the correlation ID for this request.
     *
     * @return The correlation ID, or null if not set
     */
    public String correlationId() {
        return correlationId;
    }

    // ==================== Conversion ====================

    /**
     * Converts this request to QueryOptions.
     *
     * @return QueryOptions with equivalent settings
     */
    public QueryOptions toQueryOptions() {
        return QueryOptions.builder()
            .topK(topK)
            .threshold(threshold)
            .includePartialSegments(includePartialSegments)
            .timeout(timeout())
            .oversamplingFactor(oversamplingFactor)
            .build();
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryRequest that = (QueryRequest) o;
        return topK == that.topK &&
            Double.compare(that.recallTarget, recallTarget) == 0 &&
            timeoutMs == that.timeoutMs &&
            includePartialSegments == that.includePartialSegments &&
            Float.compare(that.threshold, threshold) == 0 &&
            enableLlmScoring == that.enableLlmScoring &&
            Float.compare(that.llmThreshold, llmThreshold) == 0 &&
            oversamplingFactor == that.oversamplingFactor &&
            Objects.equals(queryText, that.queryText) &&
            Arrays.equals(queryVector, that.queryVector) &&
            Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(queryText, topK, recallTarget, timeoutMs,
            includePartialSegments, threshold, enableLlmScoring, llmThreshold,
            oversamplingFactor, correlationId);
        result = 31 * result + Arrays.hashCode(queryVector);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "QueryRequest{dim=%d, topK=%d, recall=%.2f, timeout=%dms, partial=%s, threshold=%.2f, oversampling=%d}",
            queryVector.length, topK, recallTarget, timeoutMs, includePartialSegments, threshold, oversamplingFactor);
    }

    // ==================== Builder ====================

    /**
     * Builder for QueryRequest.
     */
    public static final class Builder {
        private String queryText;
        private float[] queryVector;
        private int topK = 10;
        private double recallTarget = 0.95;
        private long timeoutMs = 30_000; // 30 seconds default
        private boolean includePartialSegments = true;
        private float threshold = 0.0f;
        private boolean enableLlmScoring = true;
        private float llmThreshold = 0.3f;
        private int oversamplingFactor = QueryOptions.DEFAULT_OVERSAMPLING_FACTOR;
        private String correlationId;

        private Builder() {}

        /**
         * Sets the query text for LLM relevancy scoring.
         *
         * @param queryText The query text
         * @return This builder
         */
        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        /**
         * Sets the query vector.
         *
         * @param queryVector The query vector (will be copied)
         * @return This builder
         */
        public Builder queryVector(float[] queryVector) {
            this.queryVector = queryVector != null ? queryVector.clone() : null;
            return this;
        }

        /**
         * Sets the number of results to return.
         *
         * @param topK The number of results (must be positive and at most {@link #MAX_TOP_K})
         * @return This builder
         * @throws IllegalArgumentException if topK is not in [1, {@link #MAX_TOP_K}]
         */
        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive: " + topK);
            }
            if (topK > MAX_TOP_K) {
                throw new IllegalArgumentException("topK must be at most " + MAX_TOP_K + ": " + topK);
            }
            this.topK = topK;
            return this;
        }

        /**
         * Sets the target recall for the query.
         *
         * @param recallTarget The recall target (0.0 to 1.0)
         * @return This builder
         */
        public Builder recallTarget(double recallTarget) {
            if (recallTarget < 0.0 || recallTarget > 1.0) {
                throw new IllegalArgumentException("recallTarget must be in [0.0, 1.0]: " + recallTarget);
            }
            this.recallTarget = recallTarget;
            return this;
        }

        /**
         * Sets the timeout for query execution.
         *
         * @param timeoutMs The timeout in milliseconds (must be positive)
         * @return This builder
         */
        public Builder timeoutMs(long timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Sets the timeout for query execution.
         *
         * @param timeout The timeout duration
         * @return This builder
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "Timeout cannot be null");
            return timeoutMs(timeout.toMillis());
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
         * Sets whether LLM relevancy scoring is enabled.
         *
         * @param enableLlmScoring true to enable LLM scoring
         * @return This builder
         */
        public Builder enableLlmScoring(boolean enableLlmScoring) {
            this.enableLlmScoring = enableLlmScoring;
            return this;
        }

        /**
         * Sets the LLM relevancy threshold.
         *
         * @param llmThreshold The LLM threshold
         * @return This builder
         */
        public Builder llmThreshold(float llmThreshold) {
            this.llmThreshold = llmThreshold;
            return this;
        }

        /**
         * Sets the correlation ID for this request.
         *
         * @param correlationId The correlation ID
         * @return This builder
         */
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * Sets the oversampling factor for HNSW search.
         *
         * <p>Higher values improve recall at the cost of latency.
         * Default value is {@link QueryOptions#DEFAULT_OVERSAMPLING_FACTOR} (2).</p>
         *
         * @param oversamplingFactor The oversampling factor (must be at least 1)
         * @return This builder
         * @throws IllegalArgumentException if oversamplingFactor is less than 1
         */
        public Builder oversamplingFactor(int oversamplingFactor) {
            if (oversamplingFactor < 1) {
                throw new IllegalArgumentException("oversamplingFactor must be at least 1: " + oversamplingFactor);
            }
            this.oversamplingFactor = oversamplingFactor;
            return this;
        }

        /**
         * Builds the QueryRequest.
         *
         * @return A new QueryRequest
         * @throws IllegalStateException if required fields are missing or invalid
         */
        public QueryRequest build() {
            return new QueryRequest(this);
        }
    }
}
