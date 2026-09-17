package excalibur.query.cache;

import java.util.Objects;

/**
 * Everything about a request that can change the answer it gets.
 *
 * <h2>Why the key is this wide</h2>
 * <p>A cache key that is narrower than the set of inputs is not a smaller key, it is a wrong
 * one. Two requests that differ only in a field the key omits collide, and the second caller
 * is handed the first caller's answer with nothing in the response saying so. A key on the
 * query string alone would serve one caller's explained, offset page to another who asked for
 * neither.</p>
 *
 * <p>So the fields that steer the search are here - text, depth, thresholds, oversampling -
 * and so are the fields that only steer presentation: {@code offset}, {@code explain},
 * {@code snippet} and {@code snippetChars}. Presentation is rendered fresh on every answer,
 * so including those costs hit rate on a mixed workload and buys nothing today. It is worth
 * it: it makes the key the whole request, so the cache cannot be made unsafe by later
 * widening what is stored under it.</p>
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li>{@code timeout_ms}. A deadline does not change the correct answer, only whether the
 *       search finished - and {@link QueryResultCache} stores nothing from a search that did
 *       not.</li>
 *   <li>The correlation id and the query id. Per-request identifiers with no effect on the
 *       ranking; keying on them would make every request a miss.</li>
 *   <li>The query embedding. It is a function of the query text and the server's own
 *       configured model, and the text is already here.</li>
 * </ul>
 *
 * <h2>Scope, which is the part that must never be wrong</h2>
 * <p>{@code namespace} and {@code securityContext} come first because they are the two fields
 * whose omission leaks data rather than merely confusing a caller. A cached answer is served
 * only to a request naming the same namespace and presenting the same credential.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and safe to use as a map key from any thread.</p>
 *
 * @param namespace              The corpus partition the request names, or empty for a node
 *                               that partitions none
 * @param securityContext        An opaque identity for the credential the request presented,
 *                               or empty for an unauthenticated node
 * @param retrievalMode          Which signals answer: {@code vector}, {@code lexical} or
 *                               {@code hybrid}
 * @param queryText              The query as validated, before embedding
 * @param topK                   The page size asked for
 * @param offset                 How many of the best results the page skips
 * @param threshold              The score floor applied to the vector signal
 * @param includePartialSegments Whether a segment still being built may contribute
 * @param enableLlmScoring       Whether stage-5 relevancy scoring runs
 * @param llmThreshold           The relevancy floor stage 5 applies
 * @param oversamplingFactor     How far past {@code topK} the beam is widened
 * @param includeText            Whether each result carries the text behind its identifier
 * @param snippet                Whether that text is the matched window rather than the start
 * @param snippetChars           How wide that window is
 * @param explain                Whether each result says which signals produced it
 * @author ExcaliburDB
 */
public record QueryCacheKey(
    String namespace,
    String securityContext,
    String retrievalMode,
    String queryText,
    int topK,
    int offset,
    float threshold,
    boolean includePartialSegments,
    boolean enableLlmScoring,
    float llmThreshold,
    int oversamplingFactor,
    boolean includeText,
    boolean snippet,
    int snippetChars,
    boolean explain
) {

    /**
     * Normalises the scope fields and refuses bounds no request could have carried.
     *
     * <p>A null namespace and an empty one are the same statement - this node partitions
     * nothing - and collapsing them here stops two keys for one request. The numeric bounds
     * are already enforced at the HTTP boundary; repeating them is how a caller that builds a
     * key by hand finds out it built a wrong one.</p>
     *
     * @throws NullPointerException     if the query text or retrieval mode is null
     * @throws IllegalArgumentException if any bound is outside what a request may carry
     */
    public QueryCacheKey {
        namespace = namespace == null ? "" : namespace;
        securityContext = securityContext == null ? "" : securityContext;
        Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
        Objects.requireNonNull(queryText, "queryText must not be null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive: " + topK);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (oversamplingFactor < 1) {
            throw new IllegalArgumentException(
                "oversamplingFactor must be at least 1: " + oversamplingFactor);
        }
        if (snippetChars <= 0) {
            throw new IllegalArgumentException(
                "snippetChars must be positive: " + snippetChars);
        }
    }

    /**
     * Starts a new key.
     *
     * @return An empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts from this key, for a caller that wants one field different.
     *
     * @return A builder carrying every field of this key
     */
    public Builder toBuilder() {
        return new Builder()
            .namespace(namespace)
            .securityContext(securityContext)
            .retrievalMode(retrievalMode)
            .queryText(queryText)
            .topK(topK)
            .offset(offset)
            .threshold(threshold)
            .includePartialSegments(includePartialSegments)
            .enableLlmScoring(enableLlmScoring)
            .llmThreshold(llmThreshold)
            .oversamplingFactor(oversamplingFactor)
            .includeText(includeText)
            .snippet(snippet)
            .snippetChars(snippetChars)
            .explain(explain);
    }

    /**
     * Describes the key without reproducing the query.
     *
     * <p>The query text is a user's question and belongs in neither a log line nor a stack
     * trace, so it is named by length rather than quoted. Everything else here is a request
     * parameter and is safe to read.</p>
     *
     * @return A description safe to log
     */
    @Override
    public String toString() {
        return String.format(
            "QueryCacheKey{ns=%s, mode=%s, queryChars=%d, topK=%d, offset=%d, text=%s, "
                + "snippet=%s/%d, explain=%s}",
            namespace.isEmpty() ? "-" : namespace, retrievalMode, queryText.length(),
            topK, offset, includeText, snippet, snippetChars, explain);
    }

    /**
     * Builds a {@link QueryCacheKey} field by field.
     *
     * <p>Fifteen positional arguments is a constructor nobody reads correctly, and every one
     * of them being wrong is a cache that serves the wrong answer. Not thread-safe; build one
     * per request.</p>
     */
    public static final class Builder {

        private String namespace = "";
        private String securityContext = "";
        private String retrievalMode = "vector";
        private String queryText;
        private int topK = 1;
        private int offset;
        private float threshold;
        private boolean includePartialSegments = true;
        private boolean enableLlmScoring;
        private float llmThreshold;
        private int oversamplingFactor = 1;
        private boolean includeText;
        private boolean snippet;
        private int snippetChars = 1;
        private boolean explain;

        private Builder() {
        }

        /**
         * Sets the corpus partition the request names.
         *
         * @param namespace The namespace, or null on a node that partitions none
         * @return This builder
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the opaque identity of the credential the request presented.
         *
         * @param securityContext The identity, or null on an unauthenticated node
         * @return This builder
         */
        public Builder securityContext(String securityContext) {
            this.securityContext = securityContext;
            return this;
        }

        /**
         * Sets which signals answer the request.
         *
         * @param retrievalMode {@code vector}, {@code lexical} or {@code hybrid}
         * @return This builder
         */
        public Builder retrievalMode(String retrievalMode) {
            this.retrievalMode = retrievalMode;
            return this;
        }

        /**
         * Sets the query text as validated.
         *
         * @param queryText The query
         * @return This builder
         */
        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        /**
         * Sets the page size.
         *
         * @param topK The page size
         * @return This builder
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets how many of the best results the page skips.
         *
         * @param offset The offset
         * @return This builder
         */
        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        /**
         * Sets the score floor applied to the vector signal.
         *
         * @param threshold The floor
         * @return This builder
         */
        public Builder threshold(float threshold) {
            this.threshold = threshold;
            return this;
        }

        /**
         * Sets whether a segment still being built may contribute.
         *
         * @param includePartialSegments true to admit partial segments
         * @return This builder
         */
        public Builder includePartialSegments(boolean includePartialSegments) {
            this.includePartialSegments = includePartialSegments;
            return this;
        }

        /**
         * Sets whether stage-5 relevancy scoring runs.
         *
         * @param enableLlmScoring true to score
         * @return This builder
         */
        public Builder enableLlmScoring(boolean enableLlmScoring) {
            this.enableLlmScoring = enableLlmScoring;
            return this;
        }

        /**
         * Sets the relevancy floor stage 5 applies.
         *
         * @param llmThreshold The floor
         * @return This builder
         */
        public Builder llmThreshold(float llmThreshold) {
            this.llmThreshold = llmThreshold;
            return this;
        }

        /**
         * Sets how far past the page size the beam is widened.
         *
         * @param oversamplingFactor The factor
         * @return This builder
         */
        public Builder oversamplingFactor(int oversamplingFactor) {
            this.oversamplingFactor = oversamplingFactor;
            return this;
        }

        /**
         * Sets whether each result carries the text behind its identifier.
         *
         * @param includeText true to return text
         * @return This builder
         */
        public Builder includeText(boolean includeText) {
            this.includeText = includeText;
            return this;
        }

        /**
         * Sets whether result text is the matched window rather than the chunk's start.
         *
         * @param snippet true for a query-centred window
         * @return This builder
         */
        public Builder snippet(boolean snippet) {
            this.snippet = snippet;
            return this;
        }

        /**
         * Sets how wide the matched window is.
         *
         * @param snippetChars The window width in characters
         * @return This builder
         */
        public Builder snippetChars(int snippetChars) {
            this.snippetChars = snippetChars;
            return this;
        }

        /**
         * Sets whether each result says which signals produced it.
         *
         * @param explain true to explain
         * @return This builder
         */
        public Builder explain(boolean explain) {
            this.explain = explain;
            return this;
        }

        /**
         * Builds the key.
         *
         * @return The key
         * @throws NullPointerException     if the query text or retrieval mode is missing
         * @throws IllegalArgumentException if any bound is outside what a request may carry
         */
        public QueryCacheKey build() {
            return new QueryCacheKey(namespace, securityContext, retrievalMode, queryText,
                topK, offset, threshold, includePartialSegments, enableLlmScoring,
                llmThreshold, oversamplingFactor, includeText, snippet, snippetChars, explain);
        }
    }
}
