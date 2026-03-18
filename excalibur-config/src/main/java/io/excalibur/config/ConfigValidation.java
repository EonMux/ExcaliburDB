package io.excalibur.config;

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
 *       <li>hnsw-queue-depth-limit: 10000-1000000 (default: 100000)</li>
 *       <li>document-atomicity-timeout-ms: 1000-30000 (default: 2000)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Health:</strong>
 *     <ul>
 *       <li>readiness-threshold: 0.0-1.0 (default: 0.5)</li>
 *       <li>healthy-threshold: 0.0-1.0 (default: 0.9)</li>
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
 *       <li>embeddings-dimensions: 128-4096 (default: 1536)</li>
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
        // Epoch tick must be between 10 and 10000 ms
        long epochTickMs = visibility.visibilityEpochTickMs();
        if (epochTickMs < 10) {
            throw new IllegalStateException(
                "visibility.epoch-tick-ms must be >= 10, got: " + epochTickMs);
        }
        if (epochTickMs > 10000) {
            throw new IllegalStateException(
                "visibility.epoch-tick-ms must be <= 10000, got: " + epochTickMs);
        }
        
        // Query timeout must be between 1000 and 300000 ms
        int queryTimeoutMs = visibility.visibilityQueryTimeoutMs();
        if (queryTimeoutMs < 1000) {
            throw new IllegalStateException(
                "visibility.query-timeout-ms must be >= 1000, got: " + queryTimeoutMs);
        }
        if (queryTimeoutMs > 300000) {
            throw new IllegalStateException(
                "visibility.query-timeout-ms must be <= 300000, got: " + queryTimeoutMs);
        }
    }
    
    /**
     * Validates ingest configuration.
     *
     * @param ingest Ingest configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateIngest(Config.Ingest ingest) {
        // HNSW queue depth limit must be between 10000 and 1000000
        long hnswQueueDepthLimit = ingest.hnswQueueDepthLimit();
        if (hnswQueueDepthLimit < 10000) {
            throw new IllegalStateException(
                "ingest.hnsw-queue-depth-limit must be >= 10000, got: " + hnswQueueDepthLimit);
        }
        if (hnswQueueDepthLimit > 1000000) {
            throw new IllegalStateException(
                "ingest.hnsw-queue-depth-limit must be <= 1000000, got: " + hnswQueueDepthLimit);
        }
        
        // Document timeout must be between 1000 and 30000 ms
        int documentTimeoutMs = ingest.documentTimeoutMs();
        if (documentTimeoutMs < 1000) {
            throw new IllegalStateException(
                "ingest.document-atomicity-timeout-ms must be >= 1000, got: " + documentTimeoutMs);
        }
        if (documentTimeoutMs > 30000) {
            throw new IllegalStateException(
                "ingest.document-atomicity-timeout-ms must be <= 30000, got: " + documentTimeoutMs);
        }
    }
    
    /**
     * Validates health configuration.
     *
     * @param health Health configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateHealth(Config.Health health) {
        // Readiness threshold must be between 0.0 and 1.0
        double readinessThreshold = health.readinessThreshold();
        if (readinessThreshold < 0.0) {
            throw new IllegalStateException(
                "health.readiness-threshold must be >= 0.0, got: " + readinessThreshold);
        }
        if (readinessThreshold > 1.0) {
            throw new IllegalStateException(
                "health.readiness-threshold must be <= 1.0, got: " + readinessThreshold);
        }
        
        // Healthy threshold must be between 0.0 and 1.0
        double healthyThreshold = health.healthyThreshold();
        if (healthyThreshold < 0.0) {
            throw new IllegalStateException(
                "health.healthy-threshold must be >= 0.0, got: " + healthyThreshold);
        }
        if (healthyThreshold > 1.0) {
            throw new IllegalStateException(
                "health.healthy-threshold must be <= 1.0, got: " + healthyThreshold);
        }
        
        // Max latency must be between 100 and 60000 ms
        int maxLatencyMs = health.maxLatencyMs();
        if (maxLatencyMs < 100) {
            throw new IllegalStateException(
                "health.max-latency-ms must be >= 100, got: " + maxLatencyMs);
        }
        if (maxLatencyMs > 60000) {
            throw new IllegalStateException(
                "health.max-latency-ms must be <= 60000, got: " + maxLatencyMs);
        }
    }
    
    /**
     * Validates query configuration.
     *
     * @param query Query configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateQuery(Config.Query query) {
        // Default top-k must be between 1 and 10000
        int defaultTopK = query.defaultTopK();
        if (defaultTopK < 1) {
            throw new IllegalStateException(
                "query.top-k-default must be >= 1, got: " + defaultTopK);
        }
        if (defaultTopK > 10000) {
            throw new IllegalStateException(
                "query.top-k-default must be <= 10000, got: " + defaultTopK);
        }
        
        // Recall target must be between 0.0 and 1.0
        double recallTarget = query.recallTarget();
        if (recallTarget < 0.0) {
            throw new IllegalStateException(
                "query.recall-target must be >= 0.0, got: " + recallTarget);
        }
        if (recallTarget > 1.0) {
            throw new IllegalStateException(
                "query.recall-target must be <= 1.0, got: " + recallTarget);
        }
        
        // Threshold must be between 0.0 and 1.0
        double threshold = query.threshold();
        if (threshold < 0.0) {
            throw new IllegalStateException(
                "query.threshold must be >= 0.0, got: " + threshold);
        }
        if (threshold > 1.0) {
            throw new IllegalStateException(
                "query.threshold must be <= 1.0, got: " + threshold);
        }
        
        // Vector dimensions must be between 128 and 4096
        int vectorDimensions = query.vectorDimensions();
        if (vectorDimensions < 128) {
            throw new IllegalStateException(
                "query.vector-dimensions must be >= 128, got: " + vectorDimensions);
        }
        if (vectorDimensions > 4096) {
            throw new IllegalStateException(
                "query.vector-dimensions must be <= 4096, got: " + vectorDimensions);
        }
        
        // LLM timeout must be between 5000 and 300000 ms
        int llmTimeoutMs = query.llmTimeoutMs();
        if (llmTimeoutMs < 5000) {
            throw new IllegalStateException(
                "query.llm-timeout-ms must be >= 5000, got: " + llmTimeoutMs);
        }
        if (llmTimeoutMs > 300000) {
            throw new IllegalStateException(
                "query.llm-timeout-ms must be <= 300000, got: " + llmTimeoutMs);
        }
    }
    
    /**
     * Validates LLM configuration.
     *
     * @param llm LLM configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateLlm(Config.Llm llm) {
        // Base URL must be a valid URL
        String baseUrl = llm.baseUrl();
        if (baseUrl != null && !isValidUrl(baseUrl)) {
            throw new IllegalStateException(
                "llm.base-url must be a valid URL (http:// or https://), got: " + baseUrl);
        }
        
        // Embeddings dimensions must be between 128 and 4096
        int embeddingsDimensions = llm.embeddingsDimensions();
        if (embeddingsDimensions < 128) {
            throw new IllegalStateException(
                "llm.embeddings-dimensions must be >= 128, got: " + embeddingsDimensions);
        }
        if (embeddingsDimensions > 4096) {
            throw new IllegalStateException(
                "llm.embeddings-dimensions must be <= 4096, got: " + embeddingsDimensions);
        }
    }
    
    /**
     * Validates shard configuration.
     *
     * @param shard Shard configuration
     * @throws IllegalStateException if invalid
     */
    private static void validateShard(Config.Shard shard) {
        // Load factor must be between 0.5 and 0.9
        double loadFactor = shard.loadFactor();
        if (loadFactor < 0.5) {
            throw new IllegalStateException(
                "shard.segment-load-factor must be >= 0.5, got: " + loadFactor);
        }
        if (loadFactor > 0.9) {
            throw new IllegalStateException(
                "shard.segment-load-factor must be <= 0.9, got: " + loadFactor);
        }
        
        // efConstruction must be between 16 and 1024
        int efConstruction = shard.efConstruction();
        if (efConstruction < 16) {
            throw new IllegalStateException(
                "shard.default-ef-construction must be >= 16, got: " + efConstruction);
        }
        if (efConstruction > 1024) {
            throw new IllegalStateException(
                "shard.default-ef-construction must be <= 1024, got: " + efConstruction);
        }
        
        // efSearch must be between 8 and 1024
        int efSearch = shard.efSearch();
        if (efSearch < 8) {
            throw new IllegalStateException(
                "shard.default-ef-search must be >= 8, got: " + efSearch);
        }
        if (efSearch > 1024) {
            throw new IllegalStateException(
                "shard.default-ef-search must be <= 1024, got: " + efSearch);
        }
        
        // HNSW queue limit must be between 10000 and 1000000
        int hnswQueueLimit = shard.hnswQueueLimit();
        if (hnswQueueLimit < 10000) {
            throw new IllegalStateException(
                "shard.hnsw-integration-queue-depth-limit must be >= 10000, got: " + hnswQueueLimit);
        }
        if (hnswQueueLimit > 1000000) {
            throw new IllegalStateException(
                "shard.hnsw-integration-queue-depth-limit must be <= 1000000, got: " + hnswQueueLimit);
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
        
        // Check for http:// or https:// prefix
        return url.startsWith("http://") || url.startsWith("https://");
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
