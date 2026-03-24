package io.excalibur.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration data class representing all ExcaliburDB settings.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is IMMUTABLE and thread-safe. It is designed for use
 * as a configuration holder that will not change during the application's lifetime.
 * All fields are final and set only at construction time.</p>
 *
 * <h2>Configuration Sections</h2>
 * <p>The Config class contains all configuration sections defined in the
 * properties file and environment variables:</p>
 * <ul>
 *   <li><code>visibility</code> - Visibility epoch and query settings</li>
 *   <li><code>ingest</code> - Ingestion pipeline settings</li>
 *   <li><code>health</code> - Health check thresholds</li>
 *   <li><code>query</code> - Query parameters</li>
 *   <li><code>llm</code> - LLM client settings</li>
 *   <li><code>shard</code> - Shard management settings</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class Config {

    // Configuration sections - all final for immutability
    private final Visibility visibility;
    private final Ingest ingest;
    private final Health health;
    private final Query query;
    private final Llm llm;
    private final Shard shard;

    // Application settings - final for immutability
    private final String configPath;
    private final Map<String, String> envOverrides;

    /**
     * Creates a new Config instance from a properties map.
     *
     * @param props Map of property key-value pairs
     */
    public Config(Map<String, String> props) {
        this("excalibur.properties", Collections.emptyMap(), props);
    }

    /**
     * Creates a new Config instance with specified config path and environment overrides.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Map of environment variable overrides (format: "key" -> value)
     */
    public Config(String configPath, Map<String, String> envOverrides) {
        this(configPath, envOverrides, new HashMap<>());
    }

    /**
     * Creates a new Config instance with all parameters.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Map of environment variable overrides
     * @param props Map of property key-value pairs (from file)
     */
    public Config(String configPath, Map<String, String> envOverrides, Map<String, String> props) {
        this.configPath = Objects.requireNonNull(configPath, "configPath cannot be null");
        this.envOverrides = Collections.unmodifiableMap(
            new HashMap<>(envOverrides != null ? envOverrides : Collections.emptyMap())
        );

        // Initialize configuration sections with values from properties map
        Map<String, String> effectiveProps = props != null ? props : new HashMap<>();
        this.visibility = new Visibility(effectiveProps);
        this.ingest = new Ingest(effectiveProps);
        this.health = new Health(effectiveProps);
        this.query = new Query(effectiveProps);
        this.llm = new Llm(effectiveProps);
        this.shard = new Shard(effectiveProps);
    }

    /**
     * Gets the visibility configuration.
     */
    public Visibility visibility() {
        return visibility;
    }

    /**
     * Gets the ingest configuration.
     */
    public Ingest ingest() {
        return ingest;
    }

    /**
     * Gets the health configuration.
     */
    public Health health() {
        return health;
    }

    /**
     * Gets the query configuration.
     */
    public Query query() {
        return query;
    }

    /**
     * Gets the LLM configuration.
     */
    public Llm llm() {
        return llm;
    }

    /**
     * Gets the shard configuration.
     */
    public Shard shard() {
        return shard;
    }

    /**
     * Gets the path to the configuration file.
     */
    public String getConfigPath() {
        return configPath;
    }

    /**
     * Gets the environment variable overrides applied to configuration.
     */
    public Map<String, String> getEnvOverrides() {
        return envOverrides;
    }

    // ============================================
    // Visibility Configuration
    // ============================================

    /**
     * Visibility configuration for epoch-based query isolation.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Visibility {

        // Default epoch tick interval in milliseconds
        public static final long DEFAULT_EPOCH_TICK_MS = 100;

        // Query timeout in milliseconds
        public static final int DEFAULT_QUERY_TIMEOUT_MS = 30000;

        // All fields final for immutability
        private final long epochTickMs;
        private final int queryTimeoutMs;

        /**
         * Creates a Visibility config from properties.
         *
         * @param props The properties map
         */
        public Visibility(Map<String, String> props) {
            this.epochTickMs = parseLong(props, "excalibur.visibility.epoch-tick-ms", DEFAULT_EPOCH_TICK_MS);
            this.queryTimeoutMs = parseInt(props, "excalibur.visibility.query-timeout-ms", DEFAULT_QUERY_TIMEOUT_MS);
        }

        /**
         * Gets the epoch tick interval in milliseconds.
         *
         * @return The interval between visibility epoch ticks
         */
        public long getEpochTickMs() {
            return epochTickMs;
        }

        /**
         * Gets the visibility epoch number.
         *
         * @return Current epoch number
         */
        public long getEpoch() {
            return System.currentTimeMillis() / epochTickMs;
        }

        /**
         * Gets the time until the next epoch tick in milliseconds.
         *
         * @return Time remaining until next epoch
         */
        public long timeUntilEpochTick() {
            long now = System.currentTimeMillis();
            long elapsed = now % epochTickMs;
            return epochTickMs - elapsed;
        }

        /**
         * Gets the query timeout in milliseconds.
         *
         * @return Query timeout in milliseconds
         */
        public int getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        @Override
        public String toString() {
            return "Visibility{epochTickMs=" + epochTickMs + ", queryTimeoutMs=" + queryTimeoutMs + '}';
        }
    }

    // ============================================
    // Ingest Configuration
    // ============================================

    /**
     * Ingestion pipeline configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Ingest {

        // HNSW integration queue depth limit
        public static final long DEFAULT_QUEUE_DEPTH_LIMIT = 100000L;

        // Document atomicity timeout in milliseconds
        public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

        // Default for document atomicity enabled
        public static final boolean DEFAULT_DOCUMENT_ATOMICITY = true;

        // All fields final for immutability
        private final long hnswQueueDepthLimit;
        private final int documentTimeoutMs;
        private final boolean documentAtomicity;

        /**
         * Creates an Ingest config from properties.
         *
         * @param props The properties map
         */
        public Ingest(Map<String, String> props) {
            this.hnswQueueDepthLimit = parseLong(props, "excalibur.ingest.hnsw-queue-depth-limit", DEFAULT_QUEUE_DEPTH_LIMIT);
            this.documentTimeoutMs = parseInt(props, "excalibur.ingest.document-atomicity-timeout-ms", DEFAULT_DOCUMENT_TIMEOUT_MS);
            this.documentAtomicity = parseBoolean(props, "excalibur.ingest.document-atomicity", DEFAULT_DOCUMENT_ATOMICITY);
        }

        /**
         * Gets the HNSW queue depth limit.
         *
         * @return Maximum entries allowed in HNSW integration queue
         */
        public long getHnswQueueDepthLimit() {
            return hnswQueueDepthLimit;
        }

        /**
         * Gets the document atomicity timeout.
         */
        public int getDocumentTimeoutMs() {
            return documentTimeoutMs;
        }

        /**
         * Checks if document atomicity is enabled.
         *
         * <p>When enabled, a document's chunks are buffered and published
         * to the visibility epoch as a batch, ensuring queries never see
         * partially-ingested documents.</p>
         *
         * @return true if document atomicity is enabled
         */
        public boolean isDocumentAtomicityEnabled() {
            return documentAtomicity;
        }

        @Override
        public String toString() {
            return "Ingest{hnswQueueDepthLimit=" + hnswQueueDepthLimit +
                   ", documentTimeoutMs=" + documentTimeoutMs +
                   ", documentAtomicity=" + documentAtomicity + '}';
        }
    }

    // ============================================
    // Health Configuration
    // ============================================

    /**
     * Health check configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Health {

        // Readiness threshold for health endpoint
        public static final double DEFAULT_READINESS_THRESHOLD = 0.5;

        // Healthy threshold (higher coverage required)
        public static final double DEFAULT_HEALTHY_THRESHOLD = 0.9;

        // Maximum query latency in milliseconds
        public static final int DEFAULT_MAX_LATENCY_MS = 5000;

        // All fields final for immutability
        private final double readinessThreshold;
        private final double healthyThreshold;
        private final int maxLatencyMs;

        /**
         * Creates a Health config from properties.
         *
         * @param props The properties map
         */
        public Health(Map<String, String> props) {
            this.readinessThreshold = parseDouble(props, "excalibur.health.readiness-threshold", DEFAULT_READINESS_THRESHOLD);
            this.healthyThreshold = parseDouble(props, "excalibur.health.healthy-threshold", DEFAULT_HEALTHY_THRESHOLD);
            this.maxLatencyMs = parseInt(props, "excalibur.health.max-latency-ms", DEFAULT_MAX_LATENCY_MS);
        }

        /**
         * Gets the readiness threshold.
         *
         * @return Threshold below which application is considered not ready
         */
        public double getReadinessThreshold() {
            return readinessThreshold;
        }

        /**
         * Gets the healthy threshold.
         */
        public double getHealthyThreshold() {
            return healthyThreshold;
        }

        /**
         * Gets the maximum query latency.
         */
        public int getMaxLatencyMs() {
            return maxLatencyMs;
        }

        @Override
        public String toString() {
            return "Health{readinessThreshold=" + readinessThreshold + ", healthyThreshold=" + healthyThreshold + ", maxLatencyMs=" + maxLatencyMs + '}';
        }
    }

    // ============================================
    // Query Configuration
    // ============================================

    /**
     * Query configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Query {

        // Default top-k value
        public static final int DEFAULT_TOP_K = 100;

        // Default recall target
        public static final double DEFAULT_RECALL_TARGET = 0.95;

        // Default threshold for filtering results
        public static final double DEFAULT_THRESHOLD = 0.3;

        // Default vector dimensions
        public static final int DEFAULT_VECTOR_DIMENSIONS = 1536;

        // Default LLM timeout
        public static final int DEFAULT_LLM_TIMEOUT_MS = 60000;

        // All fields final for immutability
        private final int defaultTopK;
        private final double recallTarget;
        private final double threshold;
        private final int vectorDimensions;
        private final int llmTimeoutMs;
        private final boolean includePartialSegments;

        /**
         * Creates a Query config from properties.
         *
         * @param props The properties map
         */
        public Query(Map<String, String> props) {
            this.defaultTopK = parseInt(props, "excalibur.query.top-k-default", DEFAULT_TOP_K);
            this.recallTarget = parseDouble(props, "excalibur.query.recall-target", DEFAULT_RECALL_TARGET);
            this.threshold = parseDouble(props, "excalibur.query.threshold", DEFAULT_THRESHOLD);
            this.vectorDimensions = parseInt(props, "excalibur.query.vector-dimensions", DEFAULT_VECTOR_DIMENSIONS);
            this.llmTimeoutMs = parseInt(props, "excalibur.query.llm-timeout-ms", DEFAULT_LLM_TIMEOUT_MS);
            this.includePartialSegments = parseBoolean(props, "excalibur.query.include-partial-segments", true);
        }

        /**
         * Gets the default top-k value.
         */
        public int getDefaultTopK() {
            return defaultTopK;
        }

        /**
         * Gets the recall target.
         */
        public double getRecallTarget() {
            return recallTarget;
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
         * Gets whether to include partial segments.
         */
        public boolean isIncludePartialSegments() {
            return includePartialSegments;
        }

        @Override
        public String toString() {
            return "Query{defaultTopK=" + defaultTopK + ", recallTarget=" + recallTarget +
                   ", threshold=" + threshold + ", vectorDimensions=" + vectorDimensions +
                   ", llmTimeoutMs=" + llmTimeoutMs + ", includePartialSegments=" + includePartialSegments + '}';
        }
    }

    // ============================================
    // LLM Configuration
    // ============================================

    /**
     * LLM client configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     *
     * <h2>Configuration Properties</h2>
     * <ul>
     *   <li><code>excalibur.llm.base-url</code> - Base URL for the LLM API (default: OpenAI)</li>
     *   <li><code>excalibur.llm.api-key</code> - API key for authentication (default: empty)</li>
     *   <li><code>excalibur.llm.model</code> - Legacy generic model name (default: text-embedding-ada-002)</li>
     *   <li><code>excalibur.llm.chat-model</code> - Model for chat completions (default: gpt-4o-mini)</li>
     *   <li><code>excalibur.llm.embedding-model</code> - Model for embeddings (default: text-embedding-3-small)</li>
     *   <li><code>excalibur.llm.embeddings-dimensions</code> - Embedding vector dimensions (default: 1536)</li>
     *   <li><code>excalibur.llm.timeout-ms</code> - HTTP request timeout (default: 60000)</li>
     *   <li><code>excalibur.llm.max-retries</code> - Maximum retry attempts (default: 3)</li>
     *   <li><code>excalibur.llm.failure-threshold</code> - Failures before circuit opens (default: 5)</li>
     *   <li><code>excalibur.llm.cooldown-period-ms</code> - Circuit breaker cooldown (default: 60000)</li>
     *   <li><code>excalibur.llm.max-prompts-per-query</code> - Max prompts for relevancy scoring (default: 10)</li>
     *   <li><code>excalibur.llm.relevancy-threshold</code> - Minimum relevancy score (default: 0.3)</li>
     *   <li><code>excalibur.llm.max-batch-tokens</code> - Max tokens per batch (default: 4000)</li>
     * </ul>
     */
    public static final class Llm {

        // Default OpenAI base URL
        public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

        // Default model name (legacy)
        public static final String DEFAULT_MODEL = "text-embedding-ada-002";

        // Default chat model
        public static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";

        // Default embedding model
        public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

        // Default API key (empty for optional)
        public static final String DEFAULT_API_KEY = "";

        // Default embeddings dimensions
        public static final int DEFAULT_EMBEDDINGS_DIMENSIONS = 1536;

        // Default timeout in milliseconds
        public static final int DEFAULT_TIMEOUT_MS = 60000;

        // Default max retries
        public static final int DEFAULT_MAX_RETRIES = 3;

        // Default circuit breaker failure threshold
        public static final int DEFAULT_FAILURE_THRESHOLD = 5;

        // Default circuit breaker cooldown period in milliseconds
        public static final long DEFAULT_COOLDOWN_PERIOD_MS = 60000L;

        // Default max prompts per query for relevancy scoring
        public static final int DEFAULT_MAX_PROMPTS_PER_QUERY = 10;

        // Default relevancy threshold (0.0 - 1.0)
        public static final double DEFAULT_RELEVANCY_THRESHOLD = 0.3;

        // Default max batch tokens
        public static final int DEFAULT_MAX_BATCH_TOKENS = 4000;

        // All fields final for immutability
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final String chatModel;
        private final String embeddingModel;
        private final int embeddingsDimensions;
        private final int timeoutMs;
        private final int maxRetries;
        private final int failureThreshold;
        private final long cooldownPeriodMs;
        private final int maxPromptsPerQuery;
        private final double relevancyThreshold;
        private final int maxBatchTokens;

        /**
         * Creates an Llm config from properties.
         *
         * @param props The properties map
         */
        public Llm(Map<String, String> props) {
            this.baseUrl = parseString(props, "excalibur.llm.base-url", DEFAULT_BASE_URL);
            this.apiKey = parseString(props, "excalibur.llm.api-key", DEFAULT_API_KEY);
            this.model = parseString(props, "excalibur.llm.model", DEFAULT_MODEL);
            this.chatModel = parseString(props, "excalibur.llm.chat-model", DEFAULT_CHAT_MODEL);
            this.embeddingModel = parseString(props, "excalibur.llm.embedding-model", DEFAULT_EMBEDDING_MODEL);
            this.embeddingsDimensions = parseInt(props, "excalibur.llm.embeddings-dimensions", DEFAULT_EMBEDDINGS_DIMENSIONS);
            this.timeoutMs = parseInt(props, "excalibur.llm.timeout-ms", DEFAULT_TIMEOUT_MS);
            this.maxRetries = parseInt(props, "excalibur.llm.max-retries", DEFAULT_MAX_RETRIES);
            this.failureThreshold = parseInt(props, "excalibur.llm.failure-threshold", DEFAULT_FAILURE_THRESHOLD);
            this.cooldownPeriodMs = parseLong(props, "excalibur.llm.cooldown-period-ms", DEFAULT_COOLDOWN_PERIOD_MS);
            this.maxPromptsPerQuery = parseInt(props, "excalibur.llm.max-prompts-per-query", DEFAULT_MAX_PROMPTS_PER_QUERY);
            this.relevancyThreshold = parseDouble(props, "excalibur.llm.relevancy-threshold", DEFAULT_RELEVANCY_THRESHOLD);
            this.maxBatchTokens = parseInt(props, "excalibur.llm.max-batch-tokens", DEFAULT_MAX_BATCH_TOKENS);
        }

        /**
         * Gets the base URL.
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Gets the API key.
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Gets the legacy generic model name.
         * @deprecated Use {@link #getChatModel()} or {@link #getEmbeddingModel()} instead.
         */
        @Deprecated
        public String getModel() {
            return model;
        }

        /**
         * Gets the chat model for completions/relevancy scoring.
         */
        public String getChatModel() {
            return chatModel;
        }

        /**
         * Gets the embedding model.
         */
        public String getEmbeddingModel() {
            return embeddingModel;
        }

        /**
         * Gets the embeddings dimensions.
         */
        public int getEmbeddingsDimensions() {
            return embeddingsDimensions;
        }

        /**
         * Gets the timeout in milliseconds.
         */
        public int getTimeoutMs() {
            return timeoutMs;
        }

        /**
         * Gets the maximum retry attempts.
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Gets the circuit breaker failure threshold.
         * Number of consecutive failures before the circuit opens.
         */
        public int getFailureThreshold() {
            return failureThreshold;
        }

        /**
         * Gets the circuit breaker cooldown period in milliseconds.
         * Time to wait before attempting to close the circuit after it opens.
         */
        public long getCooldownPeriodMs() {
            return cooldownPeriodMs;
        }

        /**
         * Gets the maximum number of prompts per query for relevancy scoring.
         */
        public int getMaxPromptsPerQuery() {
            return maxPromptsPerQuery;
        }

        /**
         * Gets the relevancy threshold (0.0 - 1.0).
         * Results below this threshold are discarded.
         */
        public double getRelevancyThreshold() {
            return relevancyThreshold;
        }

        /**
         * Gets the maximum tokens per batch for relevancy scoring requests.
         */
        public int getMaxBatchTokens() {
            return maxBatchTokens;
        }

        @Override
        public String toString() {
            return "Llm{baseUrl='" + baseUrl + "', apiKey='***', chatModel='" + chatModel +
                   "', embeddingModel='" + embeddingModel + "', embeddingsDimensions=" + embeddingsDimensions +
                   ", timeoutMs=" + timeoutMs + ", maxRetries=" + maxRetries +
                   ", failureThreshold=" + failureThreshold + ", cooldownPeriodMs=" + cooldownPeriodMs +
                   ", maxPromptsPerQuery=" + maxPromptsPerQuery + ", relevancyThreshold=" + relevancyThreshold +
                   ", maxBatchTokens=" + maxBatchTokens + '}';
        }
    }

    // ============================================
    // Shard Configuration
    // ============================================

    /**
     * Shard management configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Shard {

        // Default segment load factor for splitting
        public static final double DEFAULT_LOAD_FACTOR = 0.7;

        // Default HNSW efConstruction
        public static final int DEFAULT_EF_CONSTRUCTION = 128;

        // Default HNSW efSearch
        public static final int DEFAULT_EF_SEARCH = 64;

        // Default compaction delay
        public static final int DEFAULT_COMPACT_DELAY_MS = 10000;

        // Default HNSW integration queue depth limit
        public static final int DEFAULT_HNSW_QUEUE_LIMIT = 100000;

        // All fields final for immutability
        private final double loadFactor;
        private final int efConstruction;
        private final int efSearch;
        private final int compactDelayMs;
        private final int hnswQueueLimit;

        /**
         * Creates a Shard config from properties.
         *
         * @param props The properties map
         * @throws IllegalArgumentException if loadFactor is not in (0.0, 1.0]
         */
        public Shard(Map<String, String> props) {
            double parsedLoadFactor = parseDouble(props, "excalibur.shard.segment-load-factor", DEFAULT_LOAD_FACTOR);
            // Domain correctness: load factor must be in (0.0, 1.0]
            // - 0 or negative: table would never split (broken behavior)
            // - > 1.0: impossible for open addressing (can't exceed 100% fill)
            if (Double.isNaN(parsedLoadFactor) || parsedLoadFactor <= 0.0 || parsedLoadFactor > 1.0) {
                throw new IllegalArgumentException(
                    "loadFactor must be in (0.0, 1.0], got: " + parsedLoadFactor);
            }
            this.loadFactor = parsedLoadFactor;

            int parsedEfConstruction = parseInt(props, "excalibur.shard.default-ef-construction", DEFAULT_EF_CONSTRUCTION);
            if (parsedEfConstruction < 1) {
                throw new IllegalArgumentException(
                    "efConstruction must be at least 1, got: " + parsedEfConstruction);
            }
            this.efConstruction = parsedEfConstruction;

            int parsedEfSearch = parseInt(props, "excalibur.shard.default-ef-search", DEFAULT_EF_SEARCH);
            if (parsedEfSearch < 1) {
                throw new IllegalArgumentException(
                    "efSearch must be at least 1, got: " + parsedEfSearch);
            }
            this.efSearch = parsedEfSearch;

            int parsedCompactDelayMs = parseInt(props, "excalibur.shard.compaction-delay-ms", DEFAULT_COMPACT_DELAY_MS);
            if (parsedCompactDelayMs < 0) {
                throw new IllegalArgumentException(
                    "compactDelayMs cannot be negative, got: " + parsedCompactDelayMs);
            }
            this.compactDelayMs = parsedCompactDelayMs;

            int parsedHnswQueueLimit = parseInt(props, "excalibur.shard.hnsw-integration-queue-depth-limit", DEFAULT_HNSW_QUEUE_LIMIT);
            if (parsedHnswQueueLimit < 1) {
                throw new IllegalArgumentException(
                    "hnswQueueLimit must be at least 1, got: " + parsedHnswQueueLimit);
            }
            this.hnswQueueLimit = parsedHnswQueueLimit;
        }

        /**
         * Gets the segment load factor.
         */
        public double getLoadFactor() {
            return loadFactor;
        }

        /**
         * Gets the efConstruction parameter.
         */
        public int getEfConstruction() {
            return efConstruction;
        }

        /**
         * Gets the efSearch parameter.
         */
        public int getEfSearch() {
            return efSearch;
        }

        /**
         * Gets the compaction delay.
         */
        public int getCompactDelayMs() {
            return compactDelayMs;
        }

        /**
         * Gets the HNSW queue limit.
         */
        public int getHnswQueueLimit() {
            return hnswQueueLimit;
        }

        @Override
        public String toString() {
            return "Shard{loadFactor=" + loadFactor + ", efConstruction=" + efConstruction +
                   ", efSearch=" + efSearch + ", compactDelayMs=" + compactDelayMs +
                   ", hnswQueueLimit=" + hnswQueueLimit + '}';
        }
    }

    // ============================================
    // Static Helper Methods
    // ============================================

    private static int parseInt(Map<String, String> props, String key, int defaultValue) {
        String value = props.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(Map<String, String> props, String key, long defaultValue) {
        String value = props.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDouble(Map<String, String> props, String key, double defaultValue) {
        String value = props.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(Map<String, String> props, String key, boolean defaultValue) {
        String value = props.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.trim().equalsIgnoreCase("true") ||
               value.trim().equalsIgnoreCase("1") ||
               value.trim().equalsIgnoreCase("yes");
    }

    private static String parseString(Map<String, String> props, String key, String defaultValue) {
        String value = props.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "Config{" +
                "configPath=\"" + configPath + "\"," +
                "visibility=" + visibility +
                ", ingest=" + ingest +
                ", health=" + health +
                ", query=" + query +
                ", llm=" + llm +
                ", shard=" + shard +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Config config = (Config) o;
        return Objects.equals(visibility, config.visibility) &&
               Objects.equals(ingest, config.ingest) &&
               Objects.equals(health, config.health) &&
               Objects.equals(query, config.query) &&
               Objects.equals(llm, config.llm) &&
               Objects.equals(shard, config.shard);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visibility, ingest, health, query, llm, shard);
    }
}
