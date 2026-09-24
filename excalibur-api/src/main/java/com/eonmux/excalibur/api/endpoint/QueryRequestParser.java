package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.query.QueryOptions;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.config.Config;

import java.util.Arrays;
import java.util.Objects;

/**
 * Turns the body of a {@code POST /v1/query} request into validated fields.
 *
 * <h2>Why this is not in the handler</h2>
 * <p>Two retrieval paths parse the same body, and every bound applied here exists because the
 * value sizes something: a priority queue, a result buffer, a deadline, or - in the case of the
 * query text - an outbound request the operator is billed for. A bound applied on one path and
 * not the other is the failure mode this exists to prevent, and it is easier to see that they
 * cannot diverge when there is one parser than when there are two copies of the same checks.</p>
 *
 * <h2>What it deliberately does not check</h2>
 * <p>Whether this node can run a vector search. That is a property of the deployment rather
 * than of the request, the handler answers it before either path is taken, and the lexical path
 * parses the very same body and needs no embedding at all. Refusing here made a node that could
 * answer from BM25 report itself unconfigured.</p>
 *
 * <p>Immutable and safe for concurrent use: one instance parses every request.</p>
 */
public final class QueryRequestParser {

    private final Config config;
    private final ApiSecurityConfig limits;
    private final long defaultTimeoutMs;

    /**
     * Creates a parser over a deployment's defaults and bounds.
     *
     * @param config           The configuration supplying defaults, or null for built-in ones
     * @param limits           Bounds applied to client-supplied values
     * @param defaultTimeoutMs Budget for a request that names none
     * @throws NullPointerException if limits is null
     */
    public QueryRequestParser(Config config, ApiSecurityConfig limits, long defaultTimeoutMs) {
        this.config = config;
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Parses and validates the JSON request body.
     *
     * <p><strong>Domain Correctness:</strong> validates that:</p>
     * <ul>
     *   <li>the body is one well-formed JSON object, so no field is read as absent because the
     *       walk to it broke on a typo before it</li>
     *   <li>{@code query} is present, non-empty, and no longer than
     *       {@code excalibur.api.max-query-chars} - the server embeds it with the
     *       same model ingestion used, so a client-supplied vector is not accepted</li>
     *   <li>{@code top_k} is positive and within the configured maximum</li>
     *   <li>{@code threshold} and {@code llm_threshold} are in [0.0, 1.0]</li>
     *   <li>{@code timeout_ms} is positive and within the configured maximum</li>
     *   <li>{@code oversampling_factor} is at least 1 and within the configured maximum</li>
     *   <li>{@code include_text} is a boolean, defaulting to false</li>
     *   <li>{@code source_ids}, when present, is a non-empty array of non-blank source ids, each
     *       within the source-id length bound, and at most {@link SourceScope#MAX_SOURCES}</li>
     * </ul>
     *
     * @param json The request body
     * @return The validated request fields
     * @throws IllegalArgumentException if any field is missing or out of range
     * @throws IllegalStateException if the server cannot embed query text
     */
    public ParsedQuery parse(String json) {
        // Walked whole before any field is read: source_ids confines the search, and a member
        // the readers cannot reach would otherwise read as absent - an unconfined search.
        JsonText.requireObject(json);
        String query = JsonText.string(json, "query");

        int defaultTopK = config != null
            ? config.query().getDefaultTopK()
            : com.eonmux.excalibur.config.QueryConfig.DEFAULT_TOP_K;
        int topK = JsonText.integer(json, "top_k", defaultTopK);

        // Use config default for threshold if config is provided
        float defaultThreshold = config != null
            ? (float) config.query().getThreshold()
            : 0.0f;
        float threshold = JsonText.floatValue(json, "threshold", defaultThreshold);
        long timeoutMs = JsonText.longValue(json, "timeout_ms", defaultTimeoutMs);

        // Use config default for include_partial_segments if config is provided
        boolean defaultIncludePartial = config != null
            ? config.query().isIncludePartialSegments()
            : true;
        boolean includePartial = JsonText.booleanValue(json, "include_partial_segments", defaultIncludePartial);

        boolean enableLlmScoring = JsonText.booleanValue(json, "enable_llm_scoring", true);

        // Use config default for llm_threshold if config is provided
        float defaultLlmThreshold = config != null
            ? (float) config.llm().getRelevancyThreshold()
            : 0.3f;
        float llmThreshold = JsonText.floatValue(json, "llm_threshold", defaultLlmThreshold);

        // Off unless asked for. The identifier-only answer is what existing clients parse, and
        // text is the larger half of a response - opting in is what keeps a caller that does not
        // read the field from paying for it on every query.
        boolean includeText = JsonText.booleanValue(json, "include_text", false);

        // Parse oversampling_factor with default from QueryOptions
        int oversamplingFactor = JsonText.integer(json, "oversampling_factor",
            QueryOptions.DEFAULT_OVERSAMPLING_FACTOR);

        // 'query' text is required. The server embeds it using the SAME model
        // that ingestion uses, guaranteeing the query vector lives in the same
        // embedding space as the indexed vectors. Accepting a pre-computed vector
        // from clients is NOT supported: the client cannot guarantee their vector
        // matches the server's ingestion embedding model, and a mismatch produces
        // silently wrong results. Embedding is a server invariant.
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Field 'query' is required and must be non-empty");
        }
        // Every other bound here exists because the value sizes something inside this process.
        // This one exists because the value leaves it: the text is the body of an embedding call,
        // and on the lexical path of an expansion call as well, so an unbounded query is an
        // outbound request whose size the caller picks and the operator is billed for. The
        // BM25 pass is bounded by it too - term count is not what top_k limits.
        if (query.length() > limits.maxQueryChars()) {
            throw new IllegalArgumentException("Field 'query' must be at most "
                + limits.maxQueryChars() + " characters: " + query.length());
        }
        // Deliberately no embedding-client check here. Parsing a request is not the same
        // question as whether this node can run a vector search over it: handle() answers that
        // before it reaches the vector path, and the lexical path parses the very same body and
        // needs no embedding at all. Refusing here made a node that could answer from BM25
        // report itself unconfigured.

        // Validate topK. This value sizes priority queues and result buffers inside
        // the pipeline, so an unbounded value is an allocation the caller controls.
        if (topK <= 0) {
            throw new IllegalArgumentException("Field 'top_k' must be positive: " + topK);
        }
        if (topK > limits.maxTopK()) {
            throw new IllegalArgumentException(
                "Field 'top_k' must be at most " + limits.maxTopK() + ": " + topK);
        }

        // Validate threshold
        if (Float.isNaN(threshold) || threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("Field 'threshold' must be in range [0.0, 1.0]: " + threshold);
        }

        // Validate timeoutMs
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Field 'timeout_ms' must be positive: " + timeoutMs);
        }
        if (timeoutMs > limits.maxQueryTimeoutMs()) {
            throw new IllegalArgumentException(
                "Field 'timeout_ms' must be at most " + limits.maxQueryTimeoutMs() + ": " + timeoutMs);
        }

        // Validate llmThreshold
        if (Float.isNaN(llmThreshold) || llmThreshold < 0.0f || llmThreshold > 1.0f) {
            throw new IllegalArgumentException("Field 'llm_threshold' must be in range [0.0, 1.0]: " + llmThreshold);
        }

        // Validate oversamplingFactor. Bounded above as well as below: the factor multiplies
        // top_k into the HNSW beam width, and the beam is what stops a search walking a whole
        // segment, so an unbounded factor turns a bounded top_k back into an unbounded search.
        if (oversamplingFactor < 1) {
            throw new IllegalArgumentException("Field 'oversampling_factor' must be at least 1: " + oversamplingFactor);
        }
        if (oversamplingFactor > limits.maxOversamplingFactor()) {
            throw new IllegalArgumentException("Field 'oversampling_factor' must be at most "
                + limits.maxOversamplingFactor() + ": " + oversamplingFactor);
        }

        return new ParsedQuery(query, topK, threshold, timeoutMs,
            includePartial, enableLlmScoring, llmThreshold, oversamplingFactor, includeText,
            sourceScope(json));
    }

    /**
     * Reads the sources a request is confined to.
     *
     * <p>Absent means every source. Present and empty is refused rather than read as every
     * source: a caller that built the list from a filter that matched nothing asked for nothing,
     * and answering from the whole corpus would hand it what it filtered out. An id no source is
     * registered under is not refused - it scopes to nothing, as {@code /v1/documents/list}
     * answers an unknown source with an empty page.</p>
     *
     * @param json The request body
     * @return The scope, {@link SourceScope#ALL} when the field is absent or null
     * @throws IllegalArgumentException if the field is empty, not an array of strings, names a
     *                                  blank or over-long id, or names more than
     *                                  {@link SourceScope#MAX_SOURCES} sources
     */
    private SourceScope sourceScope(String json) {
        String[] named = JsonText.stringArray(json, "source_ids");
        if (named == null) {
            return SourceScope.ALL;
        }
        // The rule an embedded node's scoped query is held to as well.
        return SourceIds.scope(Arrays.asList(named), "source_ids");
    }

    /**
     * The validated fields of one query request.
     *
     * <p>Holds everything the caller supplied, already range-checked, but not the
     * embedding: that is produced later, inside the request's timeout budget.</p>
     *
     * @param query               Raw query text
     * @param topK                Number of results requested
     * @param threshold           Minimum score
     * @param timeoutMs           Request budget in milliseconds
     * @param includePartial      Whether partially indexed segments may contribute
     * @param enableLlmScoring    Whether Stage 4 LLM scoring runs
     * @param llmThreshold        Minimum LLM relevancy score
     * @param oversamplingFactor  Candidate oversampling factor
     * @param includeText         Whether each result carries the text behind its identifier
     * @param sourceScope         The sources the answer is drawn from
     */
    public record ParsedQuery(String query, int topK, float threshold,
                               long timeoutMs, boolean includePartial, boolean enableLlmScoring,
                               float llmThreshold, int oversamplingFactor, boolean includeText,
                               SourceScope sourceScope) {

        /**
         * @throws NullPointerException if sourceScope is null; {@link SourceScope#ALL} names
         *                              every source
         */
        public ParsedQuery {
            Objects.requireNonNull(sourceScope, "sourceScope must not be null");
        }

        /**
         * The same request, ranked to another depth.
         *
         * @param depth            How many results to rank
         * @param enableLlmScoring Whether Stage 4 LLM scoring runs
         * @return The request
         */
        public ParsedQuery rankedTo(int depth, boolean enableLlmScoring) {
            return new ParsedQuery(query, depth, threshold, timeoutMs, includePartial,
                enableLlmScoring, llmThreshold, oversamplingFactor, includeText, sourceScope);
        }

        /**
         * Builds the pipeline request once the embedding is available.
         *
         * @param vector        The query embedding
         * @param correlationId Correlation ID for logging
         * @param budgetMs      What is left of the request's timeout, which is what the pipeline
         *                      has to work with rather than the timeout as asked for
         * @param maxDepth      The deepest the pipeline may rank to fill the answer past
         *                      documents it withholds - the node's {@code max-top-k}, the bound
         *                      on how deep any page may reach
         * @param readEpoch     The visibility epoch the search reads at, shared with the BM25
         *                      half and the cache fingerprint of the same request
         * @return The pipeline request
         */
        public QueryRequest toRequest(float[] vector, String correlationId, long budgetMs,
                                      int maxDepth, long readEpoch) {
            return QueryRequest.builder()
                .queryText(query)
                .queryVector(vector)
                .topK(topK)
                .threshold(threshold)
                .timeoutMs(budgetMs)
                .includePartialSegments(includePartial)
                .enableLlmScoring(enableLlmScoring)
                .llmThreshold(llmThreshold)
                .oversamplingFactor(oversamplingFactor)
                .correlationId(correlationId)
                // A client can only use a result it can name: the node id a result falls back
                // to without one is renumbered by compaction and names nothing it can fetch.
                .requireNamedResults(true)
                .sourceScope(sourceScope)
                .maxSearchDepth(Math.min(maxDepth, QueryRequest.MAX_TOP_K))
                .readEpoch(readEpoch)
                .build();
        }
    }
}
