package com.eonmux.excalibur.config;

import java.util.Locale;

/**
 * Configuration validator that checks configuration values for correctness.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class holds no state and is never instantiated. Every entry point is static, so it
 * is safe to call from any thread.</p>
 *
 * <h2>What this adds to a section's own checking</h2>
 * <p>A section rejects a value it cannot parse as it reads the property, but for six of the
 * seven sections that is a type check and little more: the domain those keys have to fall in
 * is declared beside them as {@code MIN_*}/{@code MAX_*} constants, and this is the pass that
 * applies them. On top of that come the invariants no single section can see - that the
 * readiness threshold does not exceed the healthy one, that the query's vector dimensions
 * agree with the embedding model's, that an API key is not about to be sent in clear to a host
 * that is not loopback.</p>
 *
 * <p>{@link GraphConfig} is the seventh, and deliberately has no pass here. It declares no
 * {@code MIN_*}/{@code MAX_*} constants: every bound it has is applied in its constructor as
 * the key is read, and it shares no invariant with another section. A {@code validateGraph}
 * could therefore only restate bounds that are already enforced - a second copy that drifts
 * from the one in force - or do nothing at all. A section's bounds are checked in exactly one
 * place; for six sections that place is here, and for the graph it is parse time.</p>
 *
 * <p>Where a bound has a {@code MIN_*}/{@code MAX_*} constant it is read from the section that
 * declares it, never restated. The numbers are deliberately not repeated in prose anywhere: a
 * second copy is a copy that drifts, and a stale one misleads an operator more than no copy at
 * all.</p>
 *
 * @author ExcaliburDB
 */
public final class ConfigValidation {

    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    private ConfigValidation() {
        // Utility class
    }

    /**
     * Validates the configuration and throws IllegalStateException if invalid.
     *
     * @param config Configuration to validate
     * @throws IllegalStateException if any configuration value is invalid
     */
    public static void validate(Config config) {
        validateVisibility(config.visibility());
        validateIngest(config.ingest());
        validateHealth(config.health());
        validateQuery(config.query());
        validateLlm(config.llm());
        validateShard(config.shard());
        // No validateGraph: GraphConfig applies every bound it has in its own constructor and
        // shares no invariant with another section. See the class comment.
        validateDimensionsAgree(config.query(), config.llm());
    }

    /**
     * Requires the index and the embedding model to agree on how wide a vector is.
     *
     * <p>Two keys describe one number. {@code query.vector-dimensions} sizes the HNSW graphs and
     * the segment layout; {@code llm.embeddings-dimensions} is what the embedding client requires
     * back from the model. Each was range-checked on its own and the two were never compared, so
     * changing embedding model and updating one of them left a node that started cleanly and then
     * failed on contact - either every insert rejected by the graph, or every response rejected
     * by the client. Both are per-document failures discovered long after startup, by which time
     * the cause is several thousand log lines away.</p>
     *
     * <p>They are two keys rather than one because they answer to different things: the index
     * width is a property of data already on disk, and the model width is a property of the
     * endpoint. Which is exactly why a disagreement is worth refusing at startup rather than
     * silently resolving in favour of one of them.</p>
     *
     * @param query The query configuration
     * @param llm   The LLM configuration
     * @throws IllegalStateException if the two disagree
     */
    private static void validateDimensionsAgree(QueryConfig query, LlmConfig llm) {
        int index = query.getVectorDimensions();
        int model = llm.getEmbeddingsDimensions();
        if (index != model) {
            throw new IllegalStateException(
                "query.vector-dimensions (" + index + ") and llm.embeddings-dimensions ("
                    + model + ") must be equal: the first sizes the index, the second is what "
                    + "the embedding model returns, and a vector of one width cannot be stored "
                    + "in an index of the other. Set both to the width of '"
                    + llm.getEmbeddingModel() + "'. Changing this on a node that has already "
                    + "indexed anything requires a reindex, because the stored vectors are the "
                    + "old width.");
        }
    }

    /**
     * Validates visibility configuration.
     *
     * @param visibility Visibility configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateVisibility(VisibilityConfig visibility) {
        requireInRange("visibility.epoch-tick-ms", visibility.getEpochTickMs(),
            VisibilityConfig.MIN_EPOCH_TICK_MS, VisibilityConfig.MAX_EPOCH_TICK_MS);
        requireInRange("visibility.query-timeout-ms", visibility.getQueryTimeoutMs(),
            VisibilityConfig.MIN_QUERY_TIMEOUT_MS, VisibilityConfig.MAX_QUERY_TIMEOUT_MS);
    }

    /**
     * Validates ingest configuration.
     *
     * @param ingest Ingest configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateIngest(IngestConfig ingest) {
        requireInRange("ingest.document-atomicity-timeout-ms", ingest.getDocumentTimeoutMs(),
            IngestConfig.MIN_DOCUMENT_TIMEOUT_MS, IngestConfig.MAX_DOCUMENT_TIMEOUT_MS);

        // The shard module's reindex scheduler rejects an interval outside this window
        // when a source registers, which happens inside the /v1/ingest request path and
        // after the source has already been recorded. Reject it at startup instead.
        requireInRange("ingest.reindex-interval-ms", ingest.getReindexIntervalMs(),
            IngestConfig.MIN_REINDEX_INTERVAL_MS, IngestConfig.MAX_REINDEX_INTERVAL_MS);
    }

    /**
     * Validates health configuration.
     *
     * @param health Health configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateHealth(HealthConfig health) {
        double readinessThreshold = health.getReadinessThreshold();
        requireInRange("health.readiness-threshold", readinessThreshold,
            HealthConfig.MIN_THRESHOLD, HealthConfig.MAX_THRESHOLD);

        double healthyThreshold = health.getHealthyThreshold();
        requireInRange("health.healthy-threshold", healthyThreshold,
            HealthConfig.MIN_THRESHOLD, HealthConfig.MAX_THRESHOLD);

        // A share of capacity, like the two above, and range-checked the same way. A value near
        // 1.0 is not refused: a deployment that wants a node out of rotation while its volumes
        // are anything but nearly empty is entitled to say so, and refusing it here would be
        // this file deciding an operational policy it cannot see the reasons for.
        requireInRange("health.min-free-disk-ratio", health.getMinFreeDiskRatio(),
            HealthConfig.MIN_THRESHOLD, HealthConfig.MAX_THRESHOLD);

        // Semantic invariant: readiness is a lower bar than full health, so
        // readiness-threshold must not exceed healthy-threshold. Otherwise a
        // coverage value can report healthy=true, ready=false, which violates
        // the contract that ready is always at least as permissive as healthy.
        if (readinessThreshold > healthyThreshold) {
            throw new IllegalStateException(
                "health.readiness-threshold must be <= health.healthy-threshold, got: readiness="
                    + readinessThreshold + ", healthy=" + healthyThreshold);
        }

        requireInRange("health.max-latency-ms", health.getMaxLatencyMs(),
            HealthConfig.MIN_MAX_LATENCY_MS, HealthConfig.MAX_MAX_LATENCY_MS);
    }

    /**
     * Validates query configuration.
     *
     * @param query Query configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateQuery(QueryConfig query) {
        requireInRange("query.top-k-default", query.getDefaultTopK(),
            QueryConfig.MIN_TOP_K, QueryConfig.MAX_TOP_K);
        requireInRange("query.threshold", query.getThreshold(),
            QueryConfig.MIN_PROBABILITY, QueryConfig.MAX_PROBABILITY);
        requireInRange("query.vector-dimensions", query.getVectorDimensions(),
            QueryConfig.MIN_VECTOR_DIMENSIONS, QueryConfig.MAX_VECTOR_DIMENSIONS);
        requireInRange("query.llm-timeout-ms", query.getLlmTimeoutMs(),
            QueryConfig.MIN_LLM_TIMEOUT_MS, QueryConfig.MAX_LLM_TIMEOUT_MS);
        requireInRange("query.expansion-timeout-ms", query.getExpansionTimeoutMs(),
            QueryConfig.MIN_EXPANSION_TIMEOUT_MS, QueryConfig.MAX_EXPANSION_TIMEOUT_MS);
    }

    /**
     * Validates LLM configuration.
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlm(LlmConfig llm) {
        validateLlmEndpoint(llm);
        validateLlmLimits(llm);
    }

    /**
     * Validates the LLM endpoint and the safety of sending the credential to it.
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlmEndpoint(LlmConfig llm) {
        String baseUrl = llm.getBaseUrl();
        if (baseUrl == null) {
            return;
        }
        if (!isValidUrl(baseUrl)) {
            throw new IllegalStateException(
                "llm.base-url must be a valid URL (http:// or https://), got: " + baseUrl);
        }

        // The API key is attached as a Bearer header to every embedding and scoring
        // request. Plain http:// exists for a local model server, which needs no
        // credential; combined with a key and a remote host it puts that key on the
        // wire in cleartext.
        String apiKey = llm.getApiKey();
        String host = hostOf(baseUrl);
        if (apiKey != null && !apiKey.isEmpty()
                && baseUrl.startsWith(HTTP_PREFIX) && !isLoopbackHost(host)) {
            throw new IllegalStateException(
                "llm.api-key would be sent in cleartext: llm.base-url uses http:// with the "
                    + "non-loopback host '" + host + "'. Use https://, or leave llm.api-key empty.");
        }
    }

    /**
     * Validates the numeric bounds the LLM client enforces at construction time.
     *
     * <p>These are checked here so a violation aborts startup, rather than surfacing
     * later where the client is built inside a catch block that degrades the LLM
     * subsystems to a silent no-op.</p>
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlmLimits(LlmConfig llm) {
        requireInRange("llm.embeddings-dimensions", llm.getEmbeddingsDimensions(),
            LlmConfig.MIN_EMBEDDINGS_DIMENSIONS, LlmConfig.MAX_EMBEDDINGS_DIMENSIONS);
        requireInRange("llm.timeout-ms", llm.getTimeoutMs(),
            LlmConfig.MIN_TIMEOUT_MS, LlmConfig.MAX_TIMEOUT_MS);
        requireInRange("llm.max-retries", llm.getMaxRetries(),
            LlmConfig.MIN_MAX_RETRIES, LlmConfig.MAX_MAX_RETRIES);
        requireInRange("llm.failure-threshold", llm.getFailureThreshold(),
            LlmConfig.MIN_FAILURE_THRESHOLD, LlmConfig.MAX_FAILURE_THRESHOLD);
        requireInRange("llm.cooldown-period-ms", llm.getCooldownPeriodMs(),
            LlmConfig.MIN_COOLDOWN_PERIOD_MS, LlmConfig.MAX_COOLDOWN_PERIOD_MS);
        requireInRange("llm.max-prompts-per-query", llm.getMaxPromptsPerQuery(),
            LlmConfig.MIN_MAX_PROMPTS_PER_QUERY, LlmConfig.MAX_MAX_PROMPTS_PER_QUERY);
        requireInRange("llm.relevancy-threshold", llm.getRelevancyThreshold(),
            LlmConfig.MIN_RELEVANCY_THRESHOLD, LlmConfig.MAX_RELEVANCY_THRESHOLD);
        requireInRange("llm.max-batch-tokens", llm.getMaxBatchTokens(),
            LlmConfig.MIN_MAX_BATCH_TOKENS, LlmConfig.MAX_MAX_BATCH_TOKENS);
        requireInRange("llm.retry-base-delay-ms", llm.getRetryBaseDelayMs(),
            LlmConfig.MIN_RETRY_BASE_DELAY_MS, LlmConfig.MAX_RETRY_BASE_DELAY_MS);
    }

    /**
     * Validates shard configuration.
     *
     * @param shard Shard configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateShard(ShardConfig shard) {
        requireInRange("shard.segment-load-factor", shard.getLoadFactor(),
            ShardConfig.MIN_LOAD_FACTOR, ShardConfig.MAX_LOAD_FACTOR);
        requireInRange("shard.default-ef-construction", shard.getEfConstruction(),
            ShardConfig.MIN_EF_CONSTRUCTION, ShardConfig.MAX_EF_CONSTRUCTION);
        requireInRange("shard.default-ef-search", shard.getEfSearch(),
            ShardConfig.MIN_EF_SEARCH, ShardConfig.MAX_EF_SEARCH);
        requireInRange("shard.hnsw-integration-queue-depth-limit", shard.getHnswQueueLimit(),
            ShardConfig.MIN_HNSW_QUEUE_DEPTH_LIMIT, ShardConfig.MAX_HNSW_QUEUE_DEPTH_LIMIT);
    }

    /**
     * Requires an integral setting to fall within an inclusive range.
     *
     * @param key Setting name used in the failure message
     * @param value Configured value
     * @param min Inclusive lower bound
     * @param max Inclusive upper bound
     * @throws IllegalStateException if the value is out of range
     */
    private static void requireInRange(String key, long value, long min, long max) {
        if (value < min) {
            throw new IllegalStateException(key + " must be >= " + min + ", got: " + value);
        }
        if (value > max) {
            throw new IllegalStateException(key + " must be <= " + max + ", got: " + value);
        }
    }

    /**
     * Requires a floating-point setting to fall within an inclusive range.
     *
     * <p>No NaN guard: {@link PropertyParser#parseDouble} refuses every non-finite value as
     * it reads the property, so nothing that reaches here can fail an ordered comparison the
     * way NaN would.</p>
     *
     * @param key Setting name used in the failure message
     * @param value Configured value
     * @param min Inclusive lower bound
     * @param max Inclusive upper bound
     * @throws IllegalStateException if the value is out of range
     */
    private static void requireInRange(String key, double value, double min, double max) {
        if (value < min) {
            throw new IllegalStateException(key + " must be >= " + min + ", got: " + value);
        }
        if (value > max) {
            throw new IllegalStateException(key + " must be <= " + max + ", got: " + value);
        }
    }

    /**
     * Validates a URL format.
     *
     * @param url The URL to validate
     * @return true if the URL is valid
     */
    private static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX)) {
            return false;
        }

        // Reject a scheme with no authority ("http://") and an authority carrying
        // embedded userinfo ("http://user:pass@host"): neither is a usable endpoint,
        // and the latter is a credential in the config value.
        String authority = authorityOf(url);
        return !authority.isEmpty() && authority.indexOf('@') < 0;
    }

    /**
     * Extracts the authority component of a URL (everything between the scheme
     * separator and the path, query or fragment).
     *
     * @param url The URL
     * @return The authority, or an empty string if there is none
     */
    private static String authorityOf(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }

        int start = schemeEnd + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return url.substring(start, end);
    }

    /**
     * Extracts the host of a URL, without the port.
     *
     * @param url The URL
     * @return The host, or an empty string if there is none
     */
    private static String hostOf(String url) {
        String authority = authorityOf(url);

        // Bracketed IPv6 literal: the colons inside the brackets are not a port
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            return closingBracket > 0 ? authority.substring(0, closingBracket + 1) : authority;
        }

        int portSeparator = authority.indexOf(':');
        return portSeparator >= 0 ? authority.substring(0, portSeparator) : authority;
    }

    /**
     * Checks whether a host resolves to the loopback interface.
     *
     * @param host Host extracted from a URL
     * @return true if traffic to this host never leaves the machine
     */
    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
            || normalized.endsWith(".localhost")
            || normalized.equals("::1")
            || normalized.equals("[::1]")
            || normalized.startsWith("127.");
    }
}
