package excalibur.llm.client;

import io.excalibur.config.Config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for LLM client connections.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and therefore thread-safe. Once constructed,
 * the configuration values cannot be changed.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP timeout must be positive (&gt; 0)</li>
 *   <li>Retry count must be in range [0, 10]</li>
 *   <li>Circuit breaker failure threshold must be in range [1, 100]</li>
 *   <li>Cooldown period must be positive (&gt; 0ms)</li>
 *   <li>maxPromptsPerQuery must be in range [1, 100]</li>
 *   <li>Relevancy threshold must be in range [0.0, 1.0]</li>
 *   <li>Max batch tokens must be positive (&gt; 0), default 4000</li>
 * </ul>
 */
public final class LLMClientConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final String embeddingModel;
    private final Duration timeout;
    private final int maxRetries;
    private final int failureThreshold;
    private final Duration cooldownPeriod;
    private final int maxPromptsPerQuery;
    private final double relevancyThreshold;
    private final int embeddingDimension;
    private final int maxBatchTokens;
    private final Duration retryBaseDelay;

    private LLMClientConfig(Builder builder) {
        this.baseUrl = validateBaseUrl(builder.baseUrl);
        this.apiKey = validateApiKey(builder.apiKey);
        this.chatModel = validateModel(builder.chatModel, "gpt-4o-mini");
        this.embeddingModel = validateModel(builder.embeddingModel, "text-embedding-3-small");
        this.timeout = validateTimeout(builder.timeout);
        this.maxRetries = validateRetryCount(builder.maxRetries);
        this.failureThreshold = validateFailureThreshold(builder.failureThreshold);
        this.cooldownPeriod = validateCooldownPeriod(builder.cooldownPeriod);
        this.maxPromptsPerQuery = validateMaxPromptsPerQuery(builder.maxPromptsPerQuery);
        this.relevancyThreshold = validateRelevancyThreshold(builder.relevancyThreshold);
        this.embeddingDimension = validateEmbeddingDimension(builder.embeddingDimension);
        this.maxBatchTokens = validateMaxBatchTokens(builder.maxBatchTokens);
        this.retryBaseDelay = validateRetryBaseDelay(builder.retryBaseDelay);
    }

    // Getters
    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public Duration getCooldownPeriod() {
        return cooldownPeriod;
    }

    public int getMaxPromptsPerQuery() {
        return maxPromptsPerQuery;
    }

    public double getRelevancyThreshold() {
        return relevancyThreshold;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    /**
     * Returns the maximum number of tokens per batch for relevancy scoring requests.
     * <p>
     * This value is used by the batch handler to split large candidate sets
     * into multiple API calls when the estimated token count exceeds this limit.
     * Token estimation uses the approximation: character count / 4.0.
     * </p>
     *
     * @return Max batch tokens (always positive, default 4000)
     */
    public int getMaxBatchTokens() {
        return maxBatchTokens;
    }

    // Validation methods with domain correctness constraints
    private static String validateBaseUrl(String url) {
        Objects.requireNonNull(url, "Base URL cannot be null");
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be empty");
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        // Remove trailing slash for consistency
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String validateApiKey(String key) {
        // API key can be null or empty for local endpoints (e.g., Ollama)
        return key == null ? "" : key.trim();
    }

    private static String validateModel(String model, String defaultModel) {
        if (model == null || model.trim().isEmpty()) {
            return defaultModel;
        }
        return model.trim();
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout == null) {
            return Duration.ofSeconds(30);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                "HTTP timeout must be positive (> 0), got: " + timeout.toMillis() + "ms");
        }
        return timeout;
    }

    private static int validateRetryCount(int count) {
        if (count < 0 || count > 10) {
            throw new IllegalArgumentException(
                "Retry count must be in range [0, 10], got: " + count);
        }
        return count;
    }

    private static int validateFailureThreshold(int threshold) {
        if (threshold < 1 || threshold > 100) {
            throw new IllegalArgumentException(
                "Circuit breaker failure threshold must be in range [1, 100], got: " + threshold);
        }
        return threshold;
    }

    /**
     * Default cooldown period for circuit breaker (60 seconds = 60000ms).
     * Domain correctness: this matches the Phase 7 specification default.
     */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofMillis(60000);

    private static Duration validateCooldownPeriod(Duration period) {
        if (period == null) {
            return DEFAULT_COOLDOWN;
        }
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException(
                "Cooldown period must be positive (> 0ms), got: " + period.toMillis() + "ms");
        }
        return period;
    }

    private static int validateMaxPromptsPerQuery(int max) {
        if (max < 1 || max > 100) {
            throw new IllegalArgumentException(
                "maxPromptsPerQuery must be in range [1, 100], got: " + max);
        }
        return max;
    }

    private static double validateRelevancyThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                "Relevancy threshold must be in range [0.0, 1.0], got: " + threshold);
        }
        return threshold;
    }

    private static int validateEmbeddingDimension(int dimension) {
        if (dimension < 1 || dimension > 4096) {
            throw new IllegalArgumentException(
                "Embedding dimension must be in range [1, 4096], got: " + dimension);
        }
        return dimension;
    }

    /**
     * Default max batch tokens (4000).
     * Domain correctness: this matches the Phase 7 specification default for batch handler.
     * This value balances context window usage with API call efficiency.
     */
    private static final int DEFAULT_MAX_BATCH_TOKENS = 4000;

    private static int validateMaxBatchTokens(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException(
                "Max batch tokens must be positive (> 0), got: " + tokens);
        }
        return tokens;
    }

    private static Duration validateRetryBaseDelay(Duration delay) {
        Objects.requireNonNull(delay, "Retry base delay cannot be null");
        if (delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException(
                "Retry base delay must be positive, got: " + delay);
        }
        return delay;
    }

    /**
     * Returns the retry base delay for exponential backoff.
     * <p>
     * This value is used as the base delay for calculating exponential backoff
     * when retrying failed requests. The delay doubles with each retry attempt.
     * </p>
     *
     * @return Retry base delay (always positive, default 500ms)
     */
    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    /**
     * Creates a default configuration for local development (Ollama).
     *
     * @return Default configuration pointing to localhost:11434
     */
    public static LLMClientConfig defaultLocal() {
        return builder()
            .baseUrl("http://localhost:11434")
            .apiKey("")
            .chatModel("llama3.2")
            .embeddingModel("nomic-embed-text")
            .build();
    }

    /**
     * Creates an LLMClientConfig from the application configuration.
     *
     * <p>This adapter method bridges the properties file configuration
     * ({@link Config.Llm}) to the runtime LLM client configuration.
     * It maps all configuration values from the properties file to
     * the corresponding fields in LLMClientConfig.</p>
     *
     * <h3>Domain Correctness</h3>
     * <p>All values from the config are validated through the builder's
     * validation methods, ensuring the resulting configuration is valid.</p>
     *
     * @param llmConfig The LLM configuration from the properties file
     * @return A new LLMClientConfig instance with values from the config
     * @throws NullPointerException if llmConfig is null
     */
    public static LLMClientConfig fromConfig(Config.Llm llmConfig) {
        Objects.requireNonNull(llmConfig, "llmConfig cannot be null");

        return builder()
            .baseUrl(llmConfig.getBaseUrl())
            .apiKey(llmConfig.getApiKey())
            .chatModel(llmConfig.getChatModel())
            .embeddingModel(llmConfig.getEmbeddingModel())
            .timeout(Duration.ofMillis(llmConfig.getTimeoutMs()))
            .maxRetries(llmConfig.getMaxRetries())
            .failureThreshold(llmConfig.getFailureThreshold())
            .cooldownPeriod(Duration.ofMillis(llmConfig.getCooldownPeriodMs()))
            .maxPromptsPerQuery(llmConfig.getMaxPromptsPerQuery())
            .relevancyThreshold(llmConfig.getRelevancyThreshold())
            .embeddingDimension(llmConfig.getEmbeddingsDimensions())
            .maxBatchTokens(llmConfig.getMaxBatchTokens())
            .build();
    }

    /**
     * Creates a new builder for constructing LLMClientConfig instances.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for LLMClientConfig.
     */
    public static final class Builder {
        private String baseUrl = "http://localhost:11434";
        private String apiKey = "";
        private String chatModel = "gpt-4o-mini";
        private String embeddingModel = "text-embedding-3-small";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private int failureThreshold = 5;
        private Duration cooldownPeriod = Duration.ofMillis(60000);
        private int maxPromptsPerQuery = 10;
        private double relevancyThreshold = 0.3;
        private int embeddingDimension = 1536;
        private int maxBatchTokens = 4000;
        private Duration retryBaseDelay = Duration.ofMillis(500);

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder chatModel(String chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder embeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder failureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder cooldownPeriod(Duration cooldownPeriod) {
            this.cooldownPeriod = cooldownPeriod;
            return this;
        }

        public Builder maxPromptsPerQuery(int maxPromptsPerQuery) {
            this.maxPromptsPerQuery = maxPromptsPerQuery;
            return this;
        }

        public Builder relevancyThreshold(double relevancyThreshold) {
            this.relevancyThreshold = relevancyThreshold;
            return this;
        }

        public Builder embeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
            return this;
        }

        /**
         * Sets the maximum number of tokens per batch for relevancy scoring requests.
         * <p>
         * When the estimated token count for a batch of candidates exceeds this limit,
         * the batch handler will split the candidates into multiple API calls.
         * </p>
         *
         * @param maxBatchTokens Maximum tokens per batch (must be positive)
         * @return This builder
         */
        public Builder maxBatchTokens(int maxBatchTokens) {
            this.maxBatchTokens = maxBatchTokens;
            return this;
        }

        /**
         * Sets the base delay for exponential backoff retries.
         *
         * @param retryBaseDelay Base delay for retries (must be positive)
         * @return This builder
         */
        public Builder retryBaseDelay(Duration retryBaseDelay) {
            this.retryBaseDelay = retryBaseDelay;
            return this;
        }

        public LLMClientConfig build() {
            return new LLMClientConfig(this);
        }
    }
}
