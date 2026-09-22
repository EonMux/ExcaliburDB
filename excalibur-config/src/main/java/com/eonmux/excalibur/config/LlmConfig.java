package com.eonmux.excalibur.config;

import java.util.Map;
import java.util.Objects;

/**
 * LLM client configuration.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li><code>excalibur.llm.base-url</code> - Base URL for the LLM API (default: OpenAI)</li>
 *   <li><code>excalibur.llm.api-key</code> - API key for authentication (default: empty)</li>
 *   <li><code>excalibur.llm.chat-model</code> - Model for chat completions (default: gpt-4o-mini)</li>
 *   <li><code>excalibur.llm.embedding-model</code> - Model for embeddings (default: text-embedding-3-small)</li>
 *   <li><code>excalibur.llm.embeddings-enabled</code> - Whether the corpus and queries are embedded (default: true)</li>
 *   <li><code>excalibur.llm.embeddings-dimensions</code> - Embedding vector dimensions (default: 1536)</li>
 *   <li><code>excalibur.llm.timeout-ms</code> - HTTP request timeout (default: 60000)</li>
 *   <li><code>excalibur.llm.max-retries</code> - Maximum retry attempts (default: 3)</li>
 *   <li><code>excalibur.llm.failure-threshold</code> - Failures before circuit opens (default: 5)</li>
 *   <li><code>excalibur.llm.cooldown-period-ms</code> - Circuit breaker cooldown (default: 60000)</li>
 *   <li><code>excalibur.llm.max-prompts-per-query</code> - Max prompts for relevancy scoring (default: 10)</li>
 *   <li><code>excalibur.llm.relevancy-threshold</code> - Minimum relevancy score (default: 0.3)</li>
 *   <li><code>excalibur.llm.max-batch-tokens</code> - Max tokens per batch (default: 4000)</li>
 *   <li><code>excalibur.llm.retry-base-delay-ms</code> - Base delay for exponential backoff (default: 500)</li>
 * </ul>
 */
public final class LlmConfig {

    // Default OpenAI base URL
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    // Default chat model
    public static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";

    // Default embedding model
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    // Default API key (empty for optional)
    public static final String DEFAULT_API_KEY = "";

    // Default embeddings dimensions
    public static final int DEFAULT_EMBEDDINGS_DIMENSIONS = 1536;

    // Whether a configured key is spent on embedding the corpus. On by default: a key is
    // normally set because vector retrieval is wanted. Turning it off keeps the chat model
    // and drops the embedding pass, which is the lexical-only deployment - BM25 over the
    // chunks with the model spent on expanding the query instead.
    public static final boolean DEFAULT_EMBEDDINGS_ENABLED = true;

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

    // Default retry base delay in milliseconds
    public static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

    // Minimum and maximum values for validation
    public static final int MIN_EMBEDDINGS_DIMENSIONS = 128;
    public static final int MAX_EMBEDDINGS_DIMENSIONS = 4096;
    public static final int MIN_TIMEOUT_MS = 1000;
    public static final int MAX_TIMEOUT_MS = 300000;
    public static final int MIN_MAX_RETRIES = 0;
    public static final int MAX_MAX_RETRIES = 10;
    public static final int MIN_FAILURE_THRESHOLD = 1;
    public static final int MAX_FAILURE_THRESHOLD = 100;
    public static final long MIN_COOLDOWN_PERIOD_MS = 1000L;
    public static final long MAX_COOLDOWN_PERIOD_MS = 600000L;
    public static final int MIN_MAX_PROMPTS_PER_QUERY = 1;
    public static final int MAX_MAX_PROMPTS_PER_QUERY = 100;
    public static final double MIN_RELEVANCY_THRESHOLD = 0.0;
    public static final double MAX_RELEVANCY_THRESHOLD = 1.0;
    public static final int MIN_MAX_BATCH_TOKENS = 100;
    public static final int MAX_MAX_BATCH_TOKENS = 100000;
    public static final long MIN_RETRY_BASE_DELAY_MS = 10L;
    public static final long MAX_RETRY_BASE_DELAY_MS = 30000L;

    // All fields final for immutability
    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final String embeddingModel;
    private final int embeddingsDimensions;
    private final boolean embeddingsEnabled;
    private final int timeoutMs;
    private final int maxRetries;
    private final int failureThreshold;
    private final long cooldownPeriodMs;
    private final int maxPromptsPerQuery;
    private final double relevancyThreshold;
    private final int maxBatchTokens;
    private final long retryBaseDelayMs;

    /**
     * Creates an Llm config from properties.
     *
     * @param props The properties map
     */
    public LlmConfig(Map<String, String> props) {
        this.baseUrl = PropertyParser.parseString(props, "excalibur.llm.base-url", DEFAULT_BASE_URL);
        this.apiKey = PropertyParser.parseString(props, "excalibur.llm.api-key", DEFAULT_API_KEY);
        this.chatModel = PropertyParser.parseString(props, "excalibur.llm.chat-model", DEFAULT_CHAT_MODEL);
        this.embeddingModel = PropertyParser.parseString(props, "excalibur.llm.embedding-model", DEFAULT_EMBEDDING_MODEL);
        this.embeddingsDimensions = PropertyParser.parseInt(props, "excalibur.llm.embeddings-dimensions", DEFAULT_EMBEDDINGS_DIMENSIONS);
        this.embeddingsEnabled = PropertyParser.parseBoolean(props, "excalibur.llm.embeddings-enabled", DEFAULT_EMBEDDINGS_ENABLED);
        this.timeoutMs = PropertyParser.parseInt(props, "excalibur.llm.timeout-ms", DEFAULT_TIMEOUT_MS);
        this.maxRetries = PropertyParser.parseInt(props, "excalibur.llm.max-retries", DEFAULT_MAX_RETRIES);
        this.failureThreshold = PropertyParser.parseInt(props, "excalibur.llm.failure-threshold", DEFAULT_FAILURE_THRESHOLD);
        this.cooldownPeriodMs = PropertyParser.parseLong(props, "excalibur.llm.cooldown-period-ms", DEFAULT_COOLDOWN_PERIOD_MS);
        this.maxPromptsPerQuery = PropertyParser.parseInt(props, "excalibur.llm.max-prompts-per-query", DEFAULT_MAX_PROMPTS_PER_QUERY);
        this.relevancyThreshold = PropertyParser.parseDouble(props, "excalibur.llm.relevancy-threshold", DEFAULT_RELEVANCY_THRESHOLD);
        this.maxBatchTokens = PropertyParser.parseInt(props, "excalibur.llm.max-batch-tokens", DEFAULT_MAX_BATCH_TOKENS);
        this.retryBaseDelayMs = PropertyParser.parseLong(props, "excalibur.llm.retry-base-delay-ms", DEFAULT_RETRY_BASE_DELAY_MS);
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
     * Whether this node embeds its corpus and its queries.
     *
     * <p>False leaves the key configured and the chat model reachable while producing no
     * vectors, so retrieval is BM25 over the chunk index with the query expanded by the
     * chat model. Meaningless without a key: nothing is embedded either way.</p>
     *
     * @return true if documents and query text are embedded
     */
    public boolean isEmbeddingsEnabled() {
        return embeddingsEnabled;
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

    /**
     * Gets the base delay for exponential backoff retries in milliseconds.
     * This is the initial delay before the first retry, which doubles with each
     * subsequent retry attempt.
     *
     * @return Retry base delay in milliseconds
     */
    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    @Override
    public String toString() {
        return "Llm{baseUrl='" + baseUrl + "', apiKey='***', chatModel='" + chatModel +
               "', embeddingModel='" + embeddingModel + "', embeddingsDimensions=" + embeddingsDimensions +
               ", timeoutMs=" + timeoutMs + ", maxRetries=" + maxRetries +
               ", failureThreshold=" + failureThreshold + ", cooldownPeriodMs=" + cooldownPeriodMs +
               ", maxPromptsPerQuery=" + maxPromptsPerQuery + ", relevancyThreshold=" + relevancyThreshold +
               ", maxBatchTokens=" + maxBatchTokens + ", retryBaseDelayMs=" + retryBaseDelayMs + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LlmConfig other = (LlmConfig) o;
        return embeddingsDimensions == other.embeddingsDimensions
            && embeddingsEnabled == other.embeddingsEnabled
            && timeoutMs == other.timeoutMs
            && maxRetries == other.maxRetries
            && failureThreshold == other.failureThreshold
            && cooldownPeriodMs == other.cooldownPeriodMs
            && maxPromptsPerQuery == other.maxPromptsPerQuery
            && Double.compare(relevancyThreshold, other.relevancyThreshold) == 0
            && maxBatchTokens == other.maxBatchTokens
            && retryBaseDelayMs == other.retryBaseDelayMs
            && Objects.equals(baseUrl, other.baseUrl)
            && Objects.equals(apiKey, other.apiKey)
            && Objects.equals(chatModel, other.chatModel)
            && Objects.equals(embeddingModel, other.embeddingModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, apiKey, chatModel, embeddingModel, embeddingsDimensions,
            embeddingsEnabled, timeoutMs, maxRetries, failureThreshold, cooldownPeriodMs,
            maxPromptsPerQuery, relevancyThreshold, maxBatchTokens, retryBaseDelayMs);
    }
}
