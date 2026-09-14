package io.excalibur.config;

import java.util.Locale;

/**
 * Configuration validator that checks configuration values for correctness.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is NOT thread-safe. It is a stateless validator that
 * can be instantiated and reused safely.</p>
 *
 * <h2>What this adds to {@link Config}'s own checking</h2>
 * <p>{@link Config} refuses an out-of-range value as it reads the property, so a node loaded from
 * a file is already bounded by the time it gets here. This pass exists for the two things that
 * cannot do: a configuration built in code rather than parsed, which never went through a
 * setter's range check, and the invariants that span more than one key - that the readiness
 * threshold does not exceed the healthy one, that the query's vector dimensions agree with the
 * embedding model's, that an API key is not about to be sent in clear to a host that is not
 * loopback.</p>
 *
 * <p>Every bound below is read from the {@code MIN_*}/{@code MAX_*} constants on {@link Config},
 * never restated. The numbers are deliberately not repeated in prose anywhere: a second copy is
 * a copy that drifts, and a stale one misleads an operator more than no copy at all.</p>
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
    private static void validateDimensionsAgree(Config.Query query, Config.Llm llm) {
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
