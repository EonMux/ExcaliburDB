package io.excalibur.config;

import java.util.Objects;

/**
 * Configuration data class representing all ExcaliburDB settings.
 * 
 * <h2>Thread-Safety</h2>
 * <p>This class is IMMUTABLE and thread-safe. It is designed for use
 * as a configuration holder that will not change during the application's lifetime.</p>
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
    
    // Configuration sections
    private final Visibility visibility;
    private final Ingest ingest;
    private final Health health;
    private final Query query;
    private final Llm llm;
    private final Shard shard;
    
    // Application settings
    private final String configPath;
    private final java.util.Map<String, String> envOverrides;
    
    /**
     * Creates a new Config instance with default values.
     */
    public Config() {
        this("excalibur.properties", new java.util.HashMap<>());
    }
    
    /**
     * Creates a new Config instance with specified config path and environment overrides.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Map of environment variable overrides (format: "key" -> value)
     */
    public Config(String configPath, java.util.Map<String, String> envOverrides) {
        this.configPath = Objects.requireNonNull(configPath);
        this.envOverrides = Objects.requireNonNull(envOverrides);
        
        // Initialize configuration sections
        this.visibility = new Visibility();
        this.ingest = new Ingest();
        this.health = new Health();
        this.query = new Query();
        this.llm = new Llm();
        this.shard = new Shard();
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
    public java.util.Map<String, String> getEnvOverrides() {
        return envOverrides;
    }
    
    // ============================================
    // Visibility Configuration
    // ============================================
    
    /**
     * Visibility configuration for epoch-based query isolation.
     */
    public static class Visibility {
        
        // Default epoch tick interval in milliseconds
        public static final long DEFAULT_EPOCH_TICK_MS = 100;
        
        // Query timeout in milliseconds
        public static final int DEFAULT_QUERY_TIMEOUT_MS = 30000;
        
        private long epochTickMs = DEFAULT_EPOCH_TICK_MS;
        private int queryTimeoutMs = DEFAULT_QUERY_TIMEOUT_MS;
        
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
        
        // ============================================
        // Visibility Configuration Accessors
        // ============================================
        
        /**
         * Gets the epoch tick interval.
         */
        public long visibilityEpochTickMs() {
            return epochTickMs;
        }
        
        /**
         * Sets the epoch tick interval.
         */
        public void visibilityEpochTickMs(long ms) {
            this.epochTickMs = Math.max(10, Math.min(10000, ms));
        }
        
        /**
         * Gets the query timeout.
         */
        public int visibilityQueryTimeoutMs() {
            return queryTimeoutMs;
        }
        
        /**
         * Sets the query timeout.
         */
        public void visibilityQueryTimeoutMs(int ms) {
            this.queryTimeoutMs = Math.max(1000, Math.min(300000, ms));
        }
    }
    
    // ============================================
    // Ingest Configuration
    // ============================================
    
    /**
     * Ingestion pipeline configuration.
     */
    public static class Ingest {
        
        // HNSW integration queue depth limit
        public static final long DEFAULT_QUEUE_DEPTH_LIMIT = 100000L;
        
        // Document atomicity timeout in milliseconds
        public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;
        
        private long hnswQueueDepthLimit = DEFAULT_QUEUE_DEPTH_LIMIT;
        private int documentTimeoutMs = DEFAULT_DOCUMENT_TIMEOUT_MS;
        
        /**
         * Gets the HNSW queue depth limit.
         * 
         * @return Maximum entries allowed in HNSW integration queue
         */
        public long getHnswQueueDepthLimit() {
            return hnswQueueDepthLimit;
        }
        
        /**
         * Gets the HNSW queue depth limit.
         */
        public long hnswQueueDepthLimit() {
            return hnswQueueDepthLimit;
        }
        
        /**
         * Sets the HNSW queue depth limit.
         */
        public void hnswQueueDepthLimit(long limit) {
            this.hnswQueueDepthLimit = Math.max(10000, Math.min(1000000, limit));
        }
        
        /**
         * Gets the document atomicity timeout.
         */
        public int getDocumentTimeoutMs() {
            return documentTimeoutMs;
        }
        
        /**
         * Sets the document atomicity timeout.
         */
        public void documentTimeoutMs(int ms) {
            this.documentTimeoutMs = Math.max(1000, Math.min(30000, ms));
        }
    }
    
    // ============================================
    // Health Configuration
    // ============================================
    
    /**
     * Health check configuration.
     */
    public static class Health {
        
        // Readiness threshold for health endpoint
        public static final double DEFAULT_READINESS_THRESHOLD = 0.5;
        
        // Healthy threshold (higher coverage required)
        public static final double DEFAULT_HEALTHY_THRESHOLD = 0.9;
        
        // Maximum query latency in milliseconds
        public static final int DEFAULT_MAX_LATENCY_MS = 5000;
        
        private double readinessThreshold = DEFAULT_READINESS_THRESHOLD;
        private double healthyThreshold = DEFAULT_HEALTHY_THRESHOLD;
        private int maxLatencyMs = DEFAULT_MAX_LATENCY_MS;
        
        /**
         * Gets the readiness threshold.
         * 
         * @return Threshold below which application is considered not ready
         */
        public double getReadinessThreshold() {
            return readinessThreshold;
        }
        
        /**
         * Gets the readiness threshold.
         */
        public double readinessThreshold() {
            return readinessThreshold;
        }
        
        /**
         * Sets the readiness threshold.
         */
        public void readinessThreshold(double threshold) {
            this.readinessThreshold = Math.max(0.0, Math.min(1.0, threshold));
        }
        
        /**
         * Gets the healthy threshold.
         */
        public double getHealthyThreshold() {
            return healthyThreshold;
        }
        
        /**
         * Gets the healthy threshold.
         */
        public double healthyThreshold() {
            return healthyThreshold;
        }
        
        /**
         * Sets the healthy threshold.
         */
        public void healthyThreshold(double threshold) {
            this.healthyThreshold = Math.max(0.0, Math.min(1.0, threshold));
        }
        
        /**
         * Gets the maximum query latency.
         */
        public int getMaxLatencyMs() {
            return maxLatencyMs;
        }
        
        /**
         * Sets the maximum query latency.
         */
        public void maxLatencyMs(int ms) {
            this.maxLatencyMs = Math.max(100, Math.min(60000, ms));
        }
    }
    
    // ============================================
    // Query Configuration
    // ============================================
    
    /**
     * Query configuration.
     */
    public static class Query {
        
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
        
        private int defaultTopK = DEFAULT_TOP_K;
        private double recallTarget = DEFAULT_RECALL_TARGET;
        private double threshold = DEFAULT_THRESHOLD;
        private int vectorDimensions = DEFAULT_VECTOR_DIMENSIONS;
        private int llmTimeoutMs = DEFAULT_LLM_TIMEOUT_MS;
        private boolean includePartialSegments = true;
        
        /**
         * Gets the default top-k value.
         */
        public int getDefaultTopK() {
            return defaultTopK;
        }
        
        /**
         * Gets the default top-k value.
         */
        public int defaultTopK() {
            return defaultTopK;
        }
        
        /**
         * Sets the default top-k value.
         */
        public void defaultTopK(int topK) {
            this.defaultTopK = Math.max(1, Math.min(10000, topK));
        }
        
        /**
         * Gets the recall target.
         */
        public double getRecallTarget() {
            return recallTarget;
        }
        
        /**
         * Sets the recall target.
         */
        public void recallTarget(double target) {
            this.recallTarget = Math.max(0.0, Math.min(1.0, target));
        }
        
        /**
         * Gets the threshold.
         */
        public double getThreshold() {
            return threshold;
        }
        
        /**
         * Sets the threshold.
         */
        public void threshold(double t) {
            this.threshold = Math.max(0.0, Math.min(1.0, t));
        }
        
        /**
         * Gets the vector dimensions.
         */
        public int getVectorDimensions() {
            return vectorDimensions;
        }
        
        /**
         * Sets the vector dimensions.
         */
        public void vectorDimensions(int dims) {
            this.vectorDimensions = Math.max(128, Math.min(4096, dims));
        }
        
        /**
         * Gets the LLM timeout.
         */
        public int getLlmTimeoutMs() {
            return llmTimeoutMs;
        }
        
        /**
         * Sets the LLM timeout.
         */
        public void llmTimeoutMs(int ms) {
            this.llmTimeoutMs = Math.max(5000, Math.min(300000, ms));
        }
        
        /**
         * Gets whether to include partial segments.
         */
        public boolean isIncludePartialSegments() {
            return includePartialSegments;
        }
        
        /**
         * Sets whether to include partial segments.
         */
        public void includePartialSegments(boolean include) {
            this.includePartialSegments = include;
        }
    }
    
    // ============================================
    // LLM Configuration
    // ============================================
    
    /**
     * LLM client configuration.
     */
    public static class Llm {
        
        // Default OpenAI base URL
        public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
        
        // Default model name
        public static final String DEFAULT_MODEL = "text-embedding-ada-002";
        
        // Default API key (empty for optional)
        public static final String DEFAULT_API_KEY = "";
        
        // Default embeddings dimensions
        public static final int DEFAULT_EMBEDDINGS_DIMENSIONS = 1536;
        
        // Default timeout in milliseconds
        public static final int DEFAULT_TIMEOUT_MS = 60000;
        
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey = DEFAULT_API_KEY;
        private String model = DEFAULT_MODEL;
        private int embeddingsDimensions = DEFAULT_EMBEDDINGS_DIMENSIONS;
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        
        /**
         * Gets the base URL.
         */
        public String getBaseUrl() {
            return baseUrl;
        }
        
        /**
         * Gets the base URL.
         */
        public String baseUrl() {
            return baseUrl;
        }
        
        /**
         * Sets the base URL.
         */
        public void baseUrl(String url) {
            if (url != null) {
                this.baseUrl = url.trim();
            }
        }
        
        /**
         * Gets the API key.
         */
        public String getApiKey() {
            return apiKey;
        }
        
        /**
         * Gets the API key.
         */
        public String apiKey() {
            return apiKey;
        }
        
        /**
         * Sets the API key.
         */
        public void apiKey(String key) {
            this.apiKey = key != null ? key.trim() : DEFAULT_API_KEY;
        }
        
        /**
         * Gets the model name.
         */
        public String getModel() {
            return model;
        }
        
        /**
         * Gets the model name.
         */
        public String model() {
            return model;
        }
        
        /**
         * Sets the model name.
         */
        public void model(String name) {
            if (name != null) {
                this.model = name.trim();
            }
        }
        
        /**
         * Gets the embeddings dimensions.
         */
        public int getEmbeddingsDimensions() {
            return embeddingsDimensions;
        }
        
        /**
         * Gets the embeddings dimensions.
         */
        public int embeddingsDimensions() {
            return embeddingsDimensions;
        }
        
        /**
         * Sets the embeddings dimensions.
         */
        public void embeddingsDimensions(int dims) {
            this.embeddingsDimensions = Math.max(128, Math.min(4096, dims));
        }
        
        /**
         * Gets the timeout.
         */
        public int getTimeoutMs() {
            return timeoutMs;
        }
        
        /**
         * Gets the timeout.
         */
        public int timeoutMs() {
            return timeoutMs;
        }
        
        /**
         * Sets the timeout.
         */
        public void timeoutMs(int ms) {
            this.timeoutMs = Math.max(5000, Math.min(300000, ms));
        }
    }
    
    // ============================================
    // Shard Configuration
    // ============================================
    
    /**
     * Shard management configuration.
     */
    public static class Shard {
        
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
        
        private double loadFactor = DEFAULT_LOAD_FACTOR;
        private int efConstruction = DEFAULT_EF_CONSTRUCTION;
        private int efSearch = DEFAULT_EF_SEARCH;
        private int compactDelayMs = DEFAULT_COMPACT_DELAY_MS;
        private int hnswQueueLimit = DEFAULT_HNSW_QUEUE_LIMIT;
        
        /**
         * Gets the segment load factor.
         */
        public double getLoadFactor() {
            return loadFactor;
        }
        
        /**
         * Gets the segment load factor.
         */
        public double segmentLoadFactor() {
            return loadFactor;
        }
        
        /**
         * Sets the segment load factor.
         */
        public void loadFactor(double factor) {
            this.loadFactor = Math.max(0.5, Math.min(0.9, factor));
        }
        
        /**
         * Gets the efConstruction parameter.
         */
        public int getEfConstruction() {
            return efConstruction;
        }
        
        /**
         * Gets the efConstruction parameter.
         */
        public int defaultEfConstruction() {
            return efConstruction;
        }
        
        /**
         * Sets the efConstruction parameter.
         */
        public void efConstruction(int value) {
            this.efConstruction = Math.max(16, Math.min(1024, value));
        }
        
        /**
         * Gets the efSearch parameter.
         */
        public int getEfSearch() {
            return efSearch;
        }
        
        /**
         * Gets the efSearch parameter.
         */
        public int defaultEfSearch() {
            return efSearch;
        }
        
        /**
         * Sets the efSearch parameter.
         */
        public void efSearch(int value) {
            this.efSearch = Math.max(8, Math.min(1024, value));
        }
        
        /**
         * Gets the compaction delay.
         */
        public int getCompactDelayMs() {
            return compactDelayMs;
        }
        
        /**
         * Sets the compaction delay.
         */
        public void compactDelayMs(int ms) {
            this.compactDelayMs = Math.max(1000, Math.min(60000, ms));
        }
        
        /**
         * Gets the HNSW queue limit.
         */
        public int getHnswQueueLimit() {
            return hnswQueueLimit;
        }
        
        /**
         * Sets the HNSW queue limit.
         */
        public void hnswQueueLimit(int limit) {
            this.hnswQueueLimit = Math.max(10000, Math.min(1000000, limit));
        }
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
        return visibility.equals(config.visibility) &&
               ingest.equals(config.ingest) &&
               health.equals(config.health) &&
               query.equals(config.query) &&
               llm.equals(config.llm) &&
               shard.equals(config.shard);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(visibility, ingest, health, query, llm, shard);
    }
}
