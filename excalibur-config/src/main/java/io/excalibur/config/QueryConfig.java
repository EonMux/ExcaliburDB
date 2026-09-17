package io.excalibur.config;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Query configuration.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li><code>excalibur.query.top-k-default</code> - Results returned when a request names
 *       no {@code top_k} (default: 100)</li>
 *   <li><code>excalibur.query.threshold</code> - Score floor for vector search
 *       (default: 0.0, meaning no floor)</li>
 *   <li><code>excalibur.query.vector-dimensions</code> - Dimensionality of the index
 *       (default: 1536)</li>
 *   <li><code>excalibur.query.llm-timeout-ms</code> - Budget for the relevancy-scoring call
 *       (default: 60000)</li>
 *   <li><code>excalibur.query.expansion-timeout-ms</code> - Budget for the query-expansion
 *       call (default: 5000)</li>
 *   <li><code>excalibur.query.expand-hybrid-queries</code> - Whether a node that embeds its
 *       corpus also expands the BM25 half of a hybrid query (default: false)</li>
 *   <li><code>excalibur.query.include-partial-segments</code> - Whether a segment still being
 *       built may contribute results (default: true)</li>
 *   <li><code>excalibur.query.hybrid-retrieval</code> - Whether a node holding both signals
 *       fuses them on {@code /v1/query} (default: true)</li>
 *   <li><code>excalibur.query.distance-metric</code> - Which distance the vector index is
 *       built and searched with (default: cosine)</li>
 *   <li><code>excalibur.query.rrf-k</code> - Reciprocal rank fusion's rank offset
 *       (default: 60)</li>
 *   <li><code>excalibur.query.per-signal-over-fetch</code> - How many times top_k each
 *       signal contributes to a fusion (default: 3)</li>
 * </ul>
 */
public final class QueryConfig {

    // Default top-k value
    public static final int DEFAULT_TOP_K = 100;

    // Default score floor for queries that name none. Zero means no filtering: a query
    // that returns nothing because of a floor nobody chose is harder to diagnose than one
    // that returns weak results.
    public static final double DEFAULT_THRESHOLD = 0.0;

    // Whether a node that has both an embedding model and a lexical index answers
    // /v1/query from both. On by default: the BM25 index over the chunks is written,
    // compacted and erasure-swept whether or not a query reads it, so a node holding
    // both signals and using one is paying for an index it never consults. Off restores
    // vector-only ranking, which is what a deployment tuned against cosine scores wants.
    public static final boolean DEFAULT_HYBRID_RETRIEVAL = true;

    // Default vector dimensions
    public static final int DEFAULT_VECTOR_DIMENSIONS = 1536;

    // Default LLM timeout
    public static final int DEFAULT_LLM_TIMEOUT_MS = 60000;

    /** Cosine similarity: the default, and what an unnormalised embedding model wants. */
    public static final String METRIC_COSINE = "cosine";

    /** Euclidean (L2) distance. */
    public static final String METRIC_L2 = "l2";

    /** Inner product, for models whose vectors carry magnitude that means something. */
    public static final String METRIC_INNER_PRODUCT = "inner_product";

    /** Metrics a vector index may be built and searched with. */
    public static final Set<String> METRICS =
        Set.of(METRIC_COSINE, METRIC_L2, METRIC_INNER_PRODUCT);

    /** The metric an index is built and searched with unless one is named. */
    public static final String DEFAULT_DISTANCE_METRIC = METRIC_COSINE;

    // Reciprocal rank fusion's rank offset. 60 is the value the method was published with
    // and the one every published comparison is against. Raising it flattens the weight
    // curve, so a document ranked tenth by one signal counts for nearly as much as one
    // ranked first; lowering it makes the top of each list dominate.
    public static final int DEFAULT_RRF_K = 60;

    // How many times top_k each signal contributes before fusion. A document has to be in
    // a signal's list to be fused at all, so this is what lets a result that is second-rate
    // on one signal and excellent on the other survive to be ranked.
    public static final int DEFAULT_PER_SIGNAL_OVER_FETCH = 3;

    // Minimum and maximum values for validation (used by ConfigValidation)
    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 10000;
    public static final double MIN_PROBABILITY = 0.0;
    public static final double MAX_PROBABILITY = 1.0;
    public static final int MIN_VECTOR_DIMENSIONS = 128;
    public static final int MAX_VECTOR_DIMENSIONS = 4096;
    public static final int MIN_LLM_TIMEOUT_MS = 5000;
    public static final int MAX_LLM_TIMEOUT_MS = 300000;

    /**
     * Budget for the query-expansion call, separate from the scoring one.
     *
     * <p>Expansion runs before the search it is expanding, inside a request the client is
     * holding a socket open for, and the search cannot start until it returns. Left on the
     * ingestion client's budget it inherited 60s and three retries - longer than any query
     * budget a caller can ask for, so one slow call to the chat model turned every query on
     * a lexical-only node into an empty timed-out answer. Small by default because an
     * expansion that has not arrived is one the query is better off without: the original
     * words are always searched for anyway.</p>
     */
    public static final int DEFAULT_EXPANSION_TIMEOUT_MS = 5000;

    /**
     * Whether a node holding both signals also expands the lexical one.
     *
     * <p>Off by default, which is the behaviour a node has always had: expansion is what
     * carries the meaning of a question when there are no vectors, and on a node that has
     * them the vector signal is already doing that job. The cost of leaving it off is that
     * the BM25 half of a hybrid query is plain BM25 - weaker than the same node would run
     * in lexical-only mode - and whether that matters depends on a corpus, so it is a
     * measurement rather than a default. Turning it on is what the retrieval benchmark's
     * {@code BM25_EXPANSION_VECTOR} and {@code FULL_HYBRID} strategies measure.</p>
     */
    public static final boolean DEFAULT_EXPAND_HYBRID_QUERIES = false;

    /**
     * Whether a repeated identical query may be answered without searching again.
     *
     * <p>Off. A cache is a bet that the same question arrives twice before the corpus
     * moves, and whether that bet pays is a property of a deployment's traffic rather
     * than something a default can know. A node that has not asked for one takes no
     * corpus fingerprint at all, so a request costs exactly what it did before.</p>
     */
    public static final boolean DEFAULT_CACHE_ENABLED = false;

    /**
     * How many answers the query result cache may hold.
     *
     * <p>Entries, not bytes: one entry holds one answer, whose result list is bounded by
     * {@code excalibur.api.max-top-k}, so the worst case is this many times that many
     * result items.</p>
     */
    public static final int DEFAULT_CACHE_MAX_ENTRIES = 1024;

    /** A cache of nothing is not a cache; it is the disabled setting written twice. */
    public static final int MIN_CACHE_MAX_ENTRIES = 1;

    /** Past this the setting stops bounding the heap it exists to bound. */
    public static final int MAX_CACHE_MAX_ENTRIES = 1_000_000;

    /** Below this, the call cannot complete often enough to be worth making. */
    public static final int MIN_EXPANSION_TIMEOUT_MS = 500;

    /**
     * Above this the budget stops bounding anything.
     *
     * <p>The ceiling a request may ask for is {@code excalibur.api.max-query-timeout-ms},
     * and an expansion budget past it can only ever be cut short by the query's own
     * deadline.</p>
     */
    public static final int MAX_EXPANSION_TIMEOUT_MS = 60000;

    // All fields final for immutability
    private final int defaultTopK;
    private final double threshold;
    private final int vectorDimensions;
    private final int llmTimeoutMs;
    private final int expansionTimeoutMs;
    private final boolean expandHybridQueries;
    private final boolean includePartialSegments;
    private final boolean hybridRetrieval;
    private final String distanceMetric;
    private final int rrfK;
    private final int perSignalOverFetch;
    private final boolean cacheEnabled;
    private final int cacheMaxEntries;

    /**
     * Creates a Query config from properties.
     *
     * @param props The properties map
     */
    public QueryConfig(Map<String, String> props) {
        this.defaultTopK = PropertyParser.parseInt(props, "excalibur.query.top-k-default", DEFAULT_TOP_K);
        this.threshold = PropertyParser.parseDouble(props, "excalibur.query.threshold", DEFAULT_THRESHOLD);
        this.vectorDimensions = PropertyParser.parseInt(props, "excalibur.query.vector-dimensions", DEFAULT_VECTOR_DIMENSIONS);
        this.llmTimeoutMs = PropertyParser.parseInt(props, "excalibur.query.llm-timeout-ms", DEFAULT_LLM_TIMEOUT_MS);
        this.expansionTimeoutMs = PropertyParser.parseInt(props,
            "excalibur.query.expansion-timeout-ms", DEFAULT_EXPANSION_TIMEOUT_MS);
        this.expandHybridQueries = PropertyParser.parseBoolean(props,
            "excalibur.query.expand-hybrid-queries", DEFAULT_EXPAND_HYBRID_QUERIES);
        this.includePartialSegments = PropertyParser.parseBoolean(props, "excalibur.query.include-partial-segments", true);
        this.hybridRetrieval = PropertyParser.parseBoolean(props, "excalibur.query.hybrid-retrieval", DEFAULT_HYBRID_RETRIEVAL);
        this.distanceMetric = PropertyParser.parseString(props,
            "excalibur.query.distance-metric", DEFAULT_DISTANCE_METRIC)
            .trim().toLowerCase(java.util.Locale.ROOT);
        if (!METRICS.contains(distanceMetric)) {
            // Refused rather than fallen back from. A node that silently searched by cosine
            // after being told to use l2 would answer every query, and every answer would be
            // ranked by a measure the operator did not choose.
            throw new IllegalArgumentException("excalibur.query.distance-metric must be one "
                + "of " + new java.util.TreeSet<>(METRICS) + ", got: " + distanceMetric);
        }
        this.rrfK = PropertyParser.parseInt(props, "excalibur.query.rrf-k", DEFAULT_RRF_K);
        if (rrfK < 1) {
            throw new IllegalArgumentException("excalibur.query.rrf-k must be at least 1, "
                + "got: " + rrfK);
        }
        this.perSignalOverFetch = PropertyParser.parseInt(props,
            "excalibur.query.per-signal-over-fetch", DEFAULT_PER_SIGNAL_OVER_FETCH);
        if (perSignalOverFetch < 1) {
            throw new IllegalArgumentException("excalibur.query.per-signal-over-fetch must "
                + "be at least 1, got: " + perSignalOverFetch);
        }
        this.cacheEnabled = PropertyParser.parseBoolean(props,
            "excalibur.query.cache.enabled", DEFAULT_CACHE_ENABLED);
        this.cacheMaxEntries = PropertyParser.parseInt(props,
            "excalibur.query.cache.max-entries", DEFAULT_CACHE_MAX_ENTRIES);
        if (cacheMaxEntries < MIN_CACHE_MAX_ENTRIES
            || cacheMaxEntries > MAX_CACHE_MAX_ENTRIES) {
            // Refused rather than clamped, and refused even when the cache is off: this
            // figure is the node's memory ceiling for cached answers, and a deployment
            // silently given a different one has sized its heap against a number that is
            // not in force.
            throw new IllegalArgumentException("excalibur.query.cache.max-entries must be "
                + "between " + MIN_CACHE_MAX_ENTRIES + " and " + MAX_CACHE_MAX_ENTRIES
                + ", got: " + cacheMaxEntries);
        }
    }

    /**
     * Gets the default top-k value.
     */
    public int getDefaultTopK() {
        return defaultTopK;
    }

    /**
     * Gets the threshold.
     */
    public double getThreshold() {
        return threshold;
    }

    /**
     * Gets the vector dimensions.
     */
    public int getVectorDimensions() {
        return vectorDimensions;
    }

    /**
     * Gets the LLM timeout.
     */
    public int getLlmTimeoutMs() {
        return llmTimeoutMs;
    }

    /**
     * Gets the budget one query-expansion call is allowed.
     *
     * @return Milliseconds
     */
    public int getExpansionTimeoutMs() {
        return expansionTimeoutMs;
    }

    /**
     * Gets whether a node that embeds its corpus also expands the lexical half.
     *
     * @return true if expansion runs on a hybrid node too
     */
    public boolean isExpandHybridQueries() {
        return expandHybridQueries;
    }

    /**
     * Gets whether a repeated identical query may be answered without searching again.
     *
     * @return true if the query result cache is enabled
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * Gets the most answers the query result cache may hold.
     *
     * @return The cache's entry ceiling
     */
    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    /**
     * Gets whether to include partial segments.
     */
    public boolean isIncludePartialSegments() {
        return includePartialSegments;
    }

    /**
     * Whether {@code /v1/query} fuses BM25 with vector search on a node that has both.
     *
     * <p>Has no effect on a node that holds only one of them: with no embedding model the
     * route answers from BM25, and with no lexical index it answers from vectors, whichever
     * way this is set.</p>
     *
     * @return true when both signals should answer, false for vector-only ranking
     */
    public boolean isHybridRetrieval() {
        return hybridRetrieval;
    }

    /**
     * Which distance the vector index is built and searched with.
     *
     * <p>The choice belongs to the embedding model: cosine for the usual normalised
     * models, inner product where a vector's magnitude carries meaning, l2 where the
     * model was trained against Euclidean distance. It is recorded in each graph's
     * header at creation, and a graph built with one metric refuses to open under
     * another rather than returning a silently worse ranking.</p>
     *
     * @return one of {@code cosine}, {@code l2} or {@code inner_product}
     */
    public String getDistanceMetric() {
        return distanceMetric;
    }

    /** @return reciprocal rank fusion's rank offset */
    public int getRrfK() {
        return rrfK;
    }

    /** @return how many times top_k each signal contributes to a fusion */
    public int getPerSignalOverFetch() {
        return perSignalOverFetch;
    }

    @Override
    public String toString() {
        return "Query{defaultTopK=" + defaultTopK +
               ", threshold=" + threshold + ", vectorDimensions=" + vectorDimensions +
               ", llmTimeoutMs=" + llmTimeoutMs
               + ", expansionTimeoutMs=" + expansionTimeoutMs
               + ", expandHybridQueries=" + expandHybridQueries
               + ", includePartialSegments=" + includePartialSegments +
               ", hybridRetrieval=" + hybridRetrieval
               + ", distanceMetric='" + distanceMetric + "', rrfK=" + rrfK
               + ", perSignalOverFetch=" + perSignalOverFetch
               + ", cacheEnabled=" + cacheEnabled
               + ", cacheMaxEntries=" + cacheMaxEntries + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        QueryConfig other = (QueryConfig) o;
        return defaultTopK == other.defaultTopK
            && Double.compare(threshold, other.threshold) == 0
            && vectorDimensions == other.vectorDimensions
            && llmTimeoutMs == other.llmTimeoutMs
            && expansionTimeoutMs == other.expansionTimeoutMs
            && expandHybridQueries == other.expandHybridQueries
            && includePartialSegments == other.includePartialSegments
            && hybridRetrieval == other.hybridRetrieval
            && rrfK == other.rrfK
            && perSignalOverFetch == other.perSignalOverFetch
            && cacheEnabled == other.cacheEnabled
            && cacheMaxEntries == other.cacheMaxEntries
            && Objects.equals(distanceMetric, other.distanceMetric);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultTopK, threshold, vectorDimensions, llmTimeoutMs,
            expansionTimeoutMs, expandHybridQueries, includePartialSegments, hybridRetrieval,
            distanceMetric, rrfK, perSignalOverFetch, cacheEnabled, cacheMaxEntries);
    }
}
