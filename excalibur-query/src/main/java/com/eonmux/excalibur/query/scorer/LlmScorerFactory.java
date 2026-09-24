package com.eonmux.excalibur.query.scorer;

import com.eonmux.excalibur.query.pipeline.QueryPipeline.LlmScorer;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.LlmConfig;

import java.util.Objects;

/**
 * Factory for creating LLM-based relevancy scorers.
 *
 * <p>This factory provides convenient methods for creating {@link LlmScorer}
 * instances from various LLM client configurations.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>From an existing LLMHttpClient</h3>
 * <pre>{@code
 * LLMHttpClient llmClient = new LLMHttpClient(config);
 * LlmScorer scorer = LlmScorerFactory.fromClient(llmClient);
 * }</pre>
 *
 * <h3>From configuration</h3>
 * <pre>{@code
 * LlmScorer scorer = LlmScorerFactory.fromConfig(
 *     "https://api.openai.com",
 *     "sk-your-api-key",
 *     "gpt-4"
 * );
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All factory methods return thread-safe {@link LlmScorer} instances.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class LlmScorerFactory {

    private LlmScorerFactory() {
        // Factory class - no instances
    }

    /**
     * Creates an LLM scorer from an existing LLM HTTP client.
     *
     * <p>Use this method when you have already configured an LLMHttpClient
     * for other purposes (e.g., embeddings) and want to reuse it for relevancy scoring.</p>
     *
     * @param llmClient The LLM HTTP client (must not be null)
     * @return A new LlmScorer instance
     * @throws NullPointerException if llmClient is null
     */
    public static LlmScorer fromClient(LLMHttpClient llmClient) {
        Objects.requireNonNull(llmClient, "LLMHttpClient cannot be null");
        return new LlmRelevancyScorer(llmClient);
    }

    /**
     * Creates an LLM scorer from configuration parameters.
     *
     * <p>This is a convenience method that creates an LLMHttpClient internally
     * with the specified configuration.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>baseUrl: must be a valid HTTP/HTTPS URL</li>
     *   <li>apiKey: must not be null or empty for authenticated endpoints</li>
     *   <li>model: model identifier for the LLM (e.g., "gpt-4", "gpt-3.5-turbo")</li>
     * </ul>
     *
     * @param baseUrl The base URL of the OpenAI-compatible API (e.g., "https://api.openai.com")
     * @param apiKey The API key for authentication
     * @param model The model to use for relevancy scoring
     * @return A new LlmScorer instance
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if baseUrl is invalid
     */
    public static LlmScorer fromConfig(String baseUrl, String apiKey, String model) {
        Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        Objects.requireNonNull(apiKey, "apiKey cannot be null");
        Objects.requireNonNull(model, "model cannot be null");

        LLMClientConfig config = new LLMClientConfig.Builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .chatModel(model)
            .build();

        LLMHttpClient llmClient = new LLMHttpClient(config);
        return new LlmRelevancyScorer(llmClient);
    }

    /**
     * Creates an LLM scorer from a full LLMClientConfig.
     *
     * <p>Use this method when you need fine-grained control over the client
     * configuration (timeouts, circuit breaker settings, etc.).</p>
     *
     * @param config The LLM client configuration
     * @return A new LlmScorer instance
     * @throws NullPointerException if config is null
     */
    public static LlmScorer fromConfig(LLMClientConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        LLMHttpClient llmClient = new LLMHttpClient(config);
        return new LlmRelevancyScorer(llmClient);
    }

    /**
     * Creates an LLM scorer from the application's LlmConfig.
     *
     * <p>This is the primary factory method for production use, creating
     * an LLM scorer from the properties file configuration. All configuration
     * values (including circuit breaker settings, retries, and batch handling)
     * are read from the config.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Addressed at the chat endpoint ({@link LlmConfig#chatEndpoint()})</li>
     *   <li>Configuration values are validated through LLMClientConfig.forChat()</li>
     *   <li>Circuit breaker settings (failureThreshold, cooldownPeriod) are properly wired</li>
     *   <li>Batch handling (maxBatchTokens, maxPromptsPerQuery) is configured</li>
     * </ul>
     *
     * @param llmConfig The LLM configuration from the properties file
     * @return A new LlmScorer instance
     * @throws NullPointerException if llmConfig is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public static LlmScorer fromConfig(LlmConfig llmConfig) {
        Objects.requireNonNull(llmConfig, "llmConfig cannot be null");
        LLMClientConfig config = LLMClientConfig.forChat(llmConfig);
        LLMHttpClient llmClient = new LLMHttpClient(config);
        return new LlmRelevancyScorer(llmClient);
    }

    /**
     * Creates an LLM scorer from the application's Config with query-specific timeout.
     *
     * <p>This factory method allows specifying a query-specific LLM timeout that
     * overrides the general LLM timeout. This is useful when relevancy scoring
     * queries need different timeout settings than embedding generation.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Uses query-specific timeout from excalibur.query.llm-timeout-ms</li>
     *   <li>All other settings (the chat endpoint's baseUrl and apiKey, circuit breaker)
     *       from llmConfig</li>
     *   <li>Timeout must be positive (&gt; 0)</li>
     * </ul>
     *
     * @param llmConfig The LLM configuration from the properties file
     * @param queryLlmTimeoutMs The query-specific LLM timeout in milliseconds
     * @return A new LlmScorer instance
     * @throws NullPointerException if llmConfig is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public static LlmScorer fromConfigWithQueryTimeout(LlmConfig llmConfig, int queryLlmTimeoutMs) {
        Objects.requireNonNull(llmConfig, "llmConfig cannot be null");
        if (queryLlmTimeoutMs <= 0) {
            throw new IllegalArgumentException("queryLlmTimeoutMs must be positive: " + queryLlmTimeoutMs);
        }

        // The chat endpoint as configured, on the query-specific timeout
        LLMClientConfig config = LLMClientConfig.builderFor(llmConfig, llmConfig.chatEndpoint())
            .timeout(java.time.Duration.ofMillis(queryLlmTimeoutMs))
            .build();

        LLMHttpClient llmClient = new LLMHttpClient(config);
        return new LlmRelevancyScorer(llmClient);
    }
}
