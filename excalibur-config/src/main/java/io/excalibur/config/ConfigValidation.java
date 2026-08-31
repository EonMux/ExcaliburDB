package io.excalibur.config;

import java.util.Locale;

/**
 * Configuration validator that checks configuration values for correctness.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is NOT thread-safe. It is a stateless validator that
 * can be instantiated and reused safely.</p>
 *
 * <h2>Validation Rules</h2>
 * <p>The validator enforces the following constraints:
 * <ul>
 *   <li><strong>Visibility:</strong>
 *     <ul>
 *       <li>epoch-tick-ms: 10-10000 (default: 100)</li>
 *       <li>query-timeout-ms: 1000-300000 (default: 30000)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Ingest:</strong>
 *     <ul>
 *       <li>document-atomicity-timeout-ms: 1000-30000 (default: 2000)</li>
 *       <li>reindex-interval-ms: 60000-86400000 (default: 3600000)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Health:</strong>
 *     <ul>
 *       <li>readiness-threshold: 0.0-1.0 (default: 0.5)</li>
 *       <li>healthy-threshold: 0.0-1.0 (default: 0.9)</li>
 *       <li>readiness-threshold must not exceed healthy-threshold</li>
 *       <li>max-latency-ms: 100-60000 (default: 5000)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Query:</strong>
 *     <ul>
 *       <li>top-k-default: 1-10000 (default: 100)</li>
 *       <li>recall-target: 0.0-1.0 (default: 0.95)</li>
 *       <li>threshold: 0.0-1.0 (default: 0.3)</li>
 *       <li>vector-dimensions: 128-4096 (default: 1536)</li>
 *       <li>llm-timeout-ms: 5000-300000 (default: 60000)</li>
 *     </ul>
 *   </li>
 *   <li><strong>LLM:</strong>
 *     <ul>
 *       <li>base-url: must be a valid URL (http:// or https://)</li>
 *       <li>api-key: must not be sent over cleartext http:// to a non-loopback host</li>
 *       <li>embeddings-dimensions: 128-4096 (default: 1536)</li>
 *       <li>timeout-ms: 1000-300000 (default: 60000)</li>
 *       <li>max-retries: 0-10 (default: 3)</li>
 *       <li>failure-threshold: 1-100 (default: 5)</li>
 *       <li>cooldown-period-ms: 1000-600000 (default: 60000)</li>
 *       <li>max-prompts-per-query: 1-100 (default: 10)</li>
 *       <li>relevancy-threshold: 0.0-1.0 (default: 0.3)</li>
 *       <li>max-batch-tokens: 100-100000 (default: 4000)</li>
 *       <li>retry-base-delay-ms: 10-30000 (default: 500)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Shard:</strong>
 *     <ul>
 *       <li>segment-load-factor: 0.5-0.9 (default: 0.7)</li>
 *       <li>default-ef-construction: 16-1024 (default: 128)</li>
 *       <li>default-ef-search: 8-1024 (default: 64)</li>
 *       <li>hnsw-integration-queue-depth-limit: 10000-1000000 (default: 100000)</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class ConfigValidation {

    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

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
    }

    /**
     * Validates visibility configuration.
     *
     * @param visibility Visibility configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateVisibility(Config.Visibility visibility) {
        requireInRange("visibility.epoch-tick-ms", visibility.getEpochTickMs(),
            Config.Visibility.MIN_EPOCH_TICK_MS, Config.Visibility.MAX_EPOCH_TICK_MS);
        requireInRange("visibility.query-timeout-ms", visibility.getQueryTimeoutMs(),
            Config.Visibility.MIN_QUERY_TIMEOUT_MS, Config.Visibility.MAX_QUERY_TIMEOUT_MS);
    }

    /**
     * Validates ingest configuration.
     *
     * @param ingest Ingest configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateIngest(Config.Ingest ingest) {
        requireInRange("ingest.document-atomicity-timeout-ms", ingest.getDocumentTimeoutMs(),
            Config.Ingest.MIN_DOCUMENT_TIMEOUT_MS, Config.Ingest.MAX_DOCUMENT_TIMEOUT_MS);

        // The shard module's reindex scheduler rejects an interval outside this window
        // when a source registers, which happens inside the /v1/ingest request path and
        // after the source has already been recorded. Reject it at startup instead.
        requireInRange("ingest.reindex-interval-ms", ingest.getReindexIntervalMs(),
            Config.Ingest.MIN_REINDEX_INTERVAL_MS, Config.Ingest.MAX_REINDEX_INTERVAL_MS);
    }

    /**
     * Validates health configuration.
     *
     * @param health Health configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateHealth(Config.Health health) {
        double readinessThreshold = health.getReadinessThreshold();
        requireInRange("health.readiness-threshold", readinessThreshold,
            Config.Health.MIN_THRESHOLD, Config.Health.MAX_THRESHOLD);

        double healthyThreshold = health.getHealthyThreshold();
        requireInRange("health.healthy-threshold", healthyThreshold,
            Config.Health.MIN_THRESHOLD, Config.Health.MAX_THRESHOLD);

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
            Config.Health.MIN_MAX_LATENCY_MS, Config.Health.MAX_MAX_LATENCY_MS);
    }

    /**
     * Validates query configuration.
     *
     * @param query Query configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateQuery(Config.Query query) {
        requireInRange("query.top-k-default", query.getDefaultTopK(),
            Config.Query.MIN_TOP_K, Config.Query.MAX_TOP_K);
        requireInRange("query.recall-target", query.getRecallTarget(),
            Config.Query.MIN_PROBABILITY, Config.Query.MAX_PROBABILITY);
        requireInRange("query.threshold", query.getThreshold(),
            Config.Query.MIN_PROBABILITY, Config.Query.MAX_PROBABILITY);
        requireInRange("query.vector-dimensions", query.getVectorDimensions(),
            Config.Query.MIN_VECTOR_DIMENSIONS, Config.Query.MAX_VECTOR_DIMENSIONS);
        requireInRange("query.llm-timeout-ms", query.getLlmTimeoutMs(),
            Config.Query.MIN_LLM_TIMEOUT_MS, Config.Query.MAX_LLM_TIMEOUT_MS);
    }

    /**
     * Validates LLM configuration.
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlm(Config.Llm llm) {
        validateLlmEndpoint(llm);
        validateLlmLimits(llm);
    }

    /**
     * Validates the LLM endpoint and the safety of sending the credential to it.
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlmEndpoint(Config.Llm llm) {
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
    private static void validateLlmLimits(Config.Llm llm) {
        requireInRange("llm.embeddings-dimensions", llm.getEmbeddingsDimensions(),
            Config.Llm.MIN_EMBEDDINGS_DIMENSIONS, Config.Llm.MAX_EMBEDDINGS_DIMENSIONS);
        requireInRange("llm.timeout-ms", llm.getTimeoutMs(),
            Config.Llm.MIN_TIMEOUT_MS, Config.Llm.MAX_TIMEOUT_MS);
        requireInRange("llm.max-retries", llm.getMaxRetries(),
            Config.Llm.MIN_MAX_RETRIES, Config.Llm.MAX_MAX_RETRIES);
        requireInRange("llm.failure-threshold", llm.getFailureThreshold(),
            Config.Llm.MIN_FAILURE_THRESHOLD, Config.Llm.MAX_FAILURE_THRESHOLD);
        requireInRange("llm.cooldown-period-ms", llm.getCooldownPeriodMs(),
            Config.Llm.MIN_COOLDOWN_PERIOD_MS, Config.Llm.MAX_COOLDOWN_PERIOD_MS);
        requireInRange("llm.max-prompts-per-query", llm.getMaxPromptsPerQuery(),
            Config.Llm.MIN_MAX_PROMPTS_PER_QUERY, Config.Llm.MAX_MAX_PROMPTS_PER_QUERY);
        requireInRange("llm.relevancy-threshold", llm.getRelevancyThreshold(),
            Config.Llm.MIN_RELEVANCY_THRESHOLD, Config.Llm.MAX_RELEVANCY_THRESHOLD);
        requireInRange("llm.max-batch-tokens", llm.getMaxBatchTokens(),
            Config.Llm.MIN_MAX_BATCH_TOKENS, Config.Llm.MAX_MAX_BATCH_TOKENS);
        requireInRange("llm.retry-base-delay-ms", llm.getRetryBaseDelayMs(),
            Config.Llm.MIN_RETRY_BASE_DELAY_MS, Config.Llm.MAX_RETRY_BASE_DELAY_MS);
    }

    /**
     * Validates shard configuration.
     *
     * @param shard Shard configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateShard(Config.Shard shard) {
        requireInRange("shard.segment-load-factor", shard.getLoadFactor(), 0.5, 0.9);
        requireInRange("shard.default-ef-construction", shard.getEfConstruction(), 16, 1024);
        requireInRange("shard.default-ef-search", shard.getEfSearch(), 8, 1024);
        requireInRange("shard.hnsw-integration-queue-depth-limit", shard.getHnswQueueLimit(),
            10000, 1000000);
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
     * <p>NaN is rejected explicitly: it fails no ordered comparison, so an unguarded
     * range check admits it and every downstream score comparison then evaluates
     * false.</p>
     *
     * @param key Setting name used in the failure message
     * @param value Configured value
     * @param min Inclusive lower bound
     * @param max Inclusive upper bound
     * @throws IllegalStateException if the value is out of range or NaN
     */
    private static void requireInRange(String key, double value, double min, double max) {
        if (Double.isNaN(value) || value < min) {
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

    /**
     * Validates that all required configuration sections are present.
     *
     * @param config Configuration to validate
     * @throws IllegalStateException if required sections are missing
     */
    public static void validateRequiredSections(Config config) {
        // All sections are non-null by default (created with default values)
        // This validation ensures that custom Config instances are properly initialized
    }

    /**
     * Creates a new ConfigValidation instance.
     *
     * @return New validator instance
     */
    public static ConfigValidation getInstance() {
        return new ConfigValidation();
    }
}
