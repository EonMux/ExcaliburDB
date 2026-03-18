package io.excalibur.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration loader that reads properties files and applies environment variable overrides.
 * 
 * <h2>Thread-Safety</h2>
 * <p>This class is NOT thread-safe. Configuration loading is a blocking operation.</p>
 * <p>The resulting Config object is immutable and thread-safe.</p>
 * 
 * <h2>Override Resolution</h2>
 * <p>Environment variables override properties file values. The following pattern is used:
 * <ul>
 *   <li>Property key: <code>excalibur.visibility.epoch-tick-ms</code></li>
 *   <li>Environment variable: <code>EXCALIBUR_VISIBILITY_EPOCH_TICK_MS</code></li>
 * </ul>
 * The key transformation replaces <code>-</code> with <code>_</code> and converts to uppercase.</p>
 * 
 * @author ExcaliburDB
 */
public final class ConfigLoader {
    
    /**
     * Loads configuration from a properties file with environment variable overrides.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Additional environment variable overrides
     * @return Loaded configuration object
     * @throws IOException if the file cannot be read
     * @throws IllegalStateException if the file format is invalid
     */
    public static Config load(String configPath, Map<String, String> envOverrides) throws IOException {
        if (configPath == null || configPath.isEmpty()) {
            throw new IllegalArgumentException("Config path cannot be null or empty");
        }
        
        // Resolve path relative to current directory if not absolute
        Path configFilePath = Paths.get(configPath).toAbsolutePath();
        
        // Read properties from file
        Map<String, String> properties = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(configFilePath)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                
                // Parse key=value pairs
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    throw new IllegalStateException("Invalid property format: " + line);
                }
                
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                
                // Remove surrounding quotes from value
                if (value.length() >= 2 && 
                    (value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                
                properties.put(key, value);
            }
        }
        
        // Apply environment variable overrides
        Map<String, String> effectiveOverrides = applyEnvOverrides(properties, envOverrides);
        
        // Create config instance
        Config config = new Config(configPath, effectiveOverrides);
        
        // Parse all configuration values
        config.parse(overrides);
        
        return config;
    }
    
    /**
     * Parses configuration values from effective overrides.
     *
     * @param overrides Map of effective overrides
     */
    private void parse(Map<String, String> overrides) {
        // Visibility configuration
        String visibilityEpochTickMs = overrides.get("excalibur.visibility.epoch-tick-ms");
        if (visibilityEpochTickMs != null) {
            try {
                long value = Long.parseLong(visibilityEpochTickMs);
                this.visibility.visibilityEpochTickMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid visibility.epoch-tick-ms: " + visibilityEpochTickMs);
            }
        }
        
        String visibilityQueryTimeoutMs = overrides.get("excalibur.visibility.query-timeout-ms");
        if (visibilityQueryTimeoutMs != null) {
            try {
                int value = Integer.parseInt(visibilityQueryTimeoutMs);
                this.visibility.visibilityQueryTimeoutMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid visibility.query-timeout-ms: " + visibilityQueryTimeoutMs);
            }
        }
        
        // Ingest configuration
        String ingestHnswQueueDepthLimit = overrides.get("excalibur.ingest.hnsw-queue-depth-limit");
        if (ingestHnswQueueDepthLimit != null) {
            try {
                long value = Long.parseLong(ingestHnswQueueDepthLimit);
                this.ingest.hnswQueueDepthLimit(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid ingest.hnsw-queue-depth-limit: " + ingestHnswQueueDepthLimit);
            }
        }
        
        String ingestDocumentTimeoutMs = overrides.get("excalibur.ingest.document-atomicity-timeout-ms");
        if (ingestDocumentTimeoutMs != null) {
            try {
                int value = Integer.parseInt(ingestDocumentTimeoutMs);
                this.ingest.documentTimeoutMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid ingest.document-atomicity-timeout-ms: " + ingestDocumentTimeoutMs);
            }
        }
        
        // Health configuration
        String healthReadinessThreshold = overrides.get("excalibur.health.readiness-threshold");
        if (healthReadinessThreshold != null) {
            try {
                double value = Double.parseDouble(healthReadinessThreshold);
                this.health.readinessThreshold(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid health.readiness-threshold: " + healthReadinessThreshold);
            }
        }
        
        String healthHealthyThreshold = overrides.get("excalibur.health.healthy-threshold");
        if (healthHealthyThreshold != null) {
            try {
                double value = Double.parseDouble(healthHealthyThreshold);
                this.health.healthyThreshold(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid health.healthy-threshold: " + healthHealthyThreshold);
            }
        }
        
        String healthMaxLatencyMs = overrides.get("excalibur.health.max-latency-ms");
        if (healthMaxLatencyMs != null) {
            try {
                int value = Integer.parseInt(healthMaxLatencyMs);
                this.health.maxLatencyMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid health.max-latency-ms: " + healthMaxLatencyMs);
            }
        }
        
        // Query configuration
        String queryDefaultTopK = overrides.get("excalibur.query.top-k-default");
        if (queryDefaultTopK != null) {
            try {
                int value = Integer.parseInt(queryDefaultTopK);
                this.query.defaultTopK(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid query.top-k-default: " + queryDefaultTopK);
            }
        }
        
        String queryRecallTarget = overrides.get("excalibur.query.recall-target");
        if (queryRecallTarget != null) {
            try {
                double value = Double.parseDouble(queryRecallTarget);
                this.query.recallTarget(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid query.recall-target: " + queryRecallTarget);
            }
        }
        
        String queryThreshold = overrides.get("excalibur.query.threshold");
        if (queryThreshold != null) {
            try {
                double value = Double.parseDouble(queryThreshold);
                this.query.threshold(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid query.threshold: " + queryThreshold);
            }
        }
        
        String queryIncludePartialSegments = overrides.get("excalibur.query.include-partial-segments");
        if (queryIncludePartialSegments != null) {
            this.query.includePartialSegments(Boolean.parseBoolean(queryIncludePartialSegments));
        }
        
        String queryVectorDimensions = overrides.get("excalibur.query.vector-dimensions");
        if (queryVectorDimensions != null) {
            try {
                int value = Integer.parseInt(queryVectorDimensions);
                this.query.vectorDimensions(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid query.vector-dimensions: " + queryVectorDimensions);
            }
        }
        
        String queryLlmTimeoutMs = overrides.get("excalibur.query.llm-timeout-ms");
        if (queryLlmTimeoutMs != null) {
            try {
                int value = Integer.parseInt(queryLlmTimeoutMs);
                this.query.llmTimeoutMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid query.llm-timeout-ms: " + queryLlmTimeoutMs);
            }
        }
        
        // LLM configuration
        String llmBaseUrl = overrides.get("excalibur.llm.base-url");
        if (llmBaseUrl != null) {
            this.llm.baseUrl(llmBaseUrl);
        }
        
        String llmApiKey = overrides.get("excalibur.llm.api-key");
        if (llmApiKey != null) {
            this.llm.apiKey(llmApiKey);
        }
        
        String llmModel = overrides.get("excalibur.llm.model");
        if (llmModel != null) {
            this.llm.model(llmModel);
        }
        
        String llmEmbeddingsDimensions = overrides.get("excalibur.llm.embeddings-dimensions");
        if (llmEmbeddingsDimensions != null) {
            try {
                int value = Integer.parseInt(llmEmbeddingsDimensions);
                this.llm.embeddingsDimensions(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid llm.embeddings-dimensions: " + llmEmbeddingsDimensions);
            }
        }
        
        String llmTimeoutMs = overrides.get("excalibur.llm.timeout-ms");
        if (llmTimeoutMs != null) {
            try {
                int value = Integer.parseInt(llmTimeoutMs);
                this.llm.timeoutMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid llm.timeout-ms: " + llmTimeoutMs);
            }
        }
        
        // Shard configuration
        String shardLoadFactor = overrides.get("excalibur.shard.segment-load-factor");
        if (shardLoadFactor != null) {
            try {
                double value = Double.parseDouble(shardLoadFactor);
                this.shard.loadFactor(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid shard.segment-load-factor: " + shardLoadFactor);
            }
        }
        
        String shardEfConstruction = overrides.get("excalibur.shard.default-ef-construction");
        if (shardEfConstruction != null) {
            try {
                int value = Integer.parseInt(shardEfConstruction);
                this.shard.efConstruction(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid shard.default-ef-construction: " + shardEfConstruction);
            }
        }
        
        String shardEfSearch = overrides.get("excalibur.shard.default-ef-search");
        if (shardEfSearch != null) {
            try {
                int value = Integer.parseInt(shardEfSearch);
                this.shard.efSearch(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid shard.default-ef-search: " + shardEfSearch);
            }
        }
        
        String shardCompactDelayMs = overrides.get("excalibur.shard.compaction-delay-ms");
        if (shardCompactDelayMs != null) {
            try {
                int value = Integer.parseInt(shardCompactDelayMs);
                this.shard.compactDelayMs(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid shard.compaction-delay-ms: " + shardCompactDelayMs);
            }
        }
        
        String shardHnswQueueLimit = overrides.get("excalibur.shard.hnsw-integration-queue-depth-limit");
        if (shardHnswQueueLimit != null) {
            try {
                int value = Integer.parseInt(shardHnswQueueLimit);
                this.shard.hnswQueueLimit(value);
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid shard.hnsw-integration-queue-depth-limit: " + shardHnswQueueLimit);
            }
        }
    }
    
    /**
     * Applies environment variable overrides to configuration overrides.
     *
     * @param baseOverrides Base overrides (from properties file)
     * @param envOverrides Additional environment variable overrides
     * @return Merged overrides with env vars taking precedence
     */
    private static Map<String, String> applyEnvOverrides(Map<String, String> baseOverrides, 
                                                          Map<String, String> envOverrides) {
        Map<String, String> result = new HashMap<>(baseOverrides);
        
        for (Map.Entry<String, String> entry : envOverrides.entrySet()) {
            String propKey = transformEnvKey(entry.getKey());
            if (result.containsKey(propKey)) {
                result.put(propKey, entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * Transforms environment variable name to property key format.
     *
     * <p>E.g., <code>EXCALIBUR_VISIBILITY_EPOCH_TICK_MS</code> -> <code>excalibur.visibility.epoch-tick-ms</code></p>
     *
     * @param envKey Environment variable key
     * @return Property key format
     */
    private static String transformEnvKey(String envKey) {
        // Convert EXCALIBUR_ prefix to excalibur.
        if (envKey.startsWith("EXCALIBUR_")) {
            envKey = envKey.substring(9);
        }
        
        // Replace underscores with dots
        String key = envKey.replace("_", ".");
        
        // Replace dots with hyphens for property format
        key = key.replace(".", "-");
        
        // Convert to lowercase
        return key.toLowerCase();
    }
}
