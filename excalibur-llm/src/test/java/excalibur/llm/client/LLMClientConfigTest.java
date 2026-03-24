package excalibur.llm.client;

import io.excalibur.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LLMClientConfig}.
 *
 * <p>Tests verify domain correctness for configuration values, especially:</p>
 * <ul>
 *   <li>Cooldown period: positive duration, default 60000ms</li>
 *   <li>Failure threshold: positive integer, default 5</li>
 *   <li>All other domain constraints</li>
 * </ul>
 */
@Timeout(value = 10)
class LLMClientConfigTest {

    @Nested
    @DisplayName("Default Configuration")
    class DefaultConfigurationTests {

        @Test
        @DisplayName("Default cooldown period is 60000ms (60 seconds)")
        void defaultCooldownPeriodIs60000ms() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(Duration.ofMillis(60000), config.getCooldownPeriod());
            assertEquals(60000, config.getCooldownPeriod().toMillis());
        }

        @Test
        @DisplayName("Default failure threshold is 5")
        void defaultFailureThresholdIs5() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(5, config.getFailureThreshold());
        }

        @Test
        @DisplayName("Default timeout is 30 seconds")
        void defaultTimeoutIs30Seconds() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(Duration.ofSeconds(30), config.getTimeout());
        }

        @Test
        @DisplayName("Default max retries is 3")
        void defaultMaxRetriesIs3() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(3, config.getMaxRetries());
        }

        @Test
        @DisplayName("Default max prompts per query is 10")
        void defaultMaxPromptsPerQueryIs10() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(10, config.getMaxPromptsPerQuery());
        }

        @Test
        @DisplayName("Default relevancy threshold is 0.3")
        void defaultRelevancyThresholdIs03() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(0.3, config.getRelevancyThreshold(), 0.001);
        }

        @Test
        @DisplayName("Default embedding dimension is 1536")
        void defaultEmbeddingDimensionIs1536() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(1536, config.getEmbeddingDimension());
        }

        @Test
        @DisplayName("Default max batch tokens is 4000")
        void defaultMaxBatchTokensIs4000() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(4000, config.getMaxBatchTokens());
        }
    }

    @Nested
    @DisplayName("Cooldown Period Validation")
    class CooldownPeriodValidationTests {

        @Test
        @DisplayName("Accepts valid positive cooldown period")
        void acceptsValidPositiveCooldownPeriod() {
            LLMClientConfig config = LLMClientConfig.builder()
                .cooldownPeriod(Duration.ofMillis(5000))
                .build();
            assertEquals(Duration.ofMillis(5000), config.getCooldownPeriod());
        }

        @Test
        @DisplayName("Accepts cooldown period of 1ms (minimum positive)")
        void acceptsCooldownPeriodOf1ms() {
            LLMClientConfig config = LLMClientConfig.builder()
                .cooldownPeriod(Duration.ofMillis(1))
                .build();
            assertEquals(Duration.ofMillis(1), config.getCooldownPeriod());
        }

        @Test
        @DisplayName("Rejects zero cooldown period")
        void rejectsZeroCooldownPeriod() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .cooldownPeriod(Duration.ZERO)
                    .build()
            );
            assertTrue(ex.getMessage().contains("positive"));
        }

        @Test
        @DisplayName("Rejects negative cooldown period")
        void rejectsNegativeCooldownPeriod() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .cooldownPeriod(Duration.ofMillis(-1000))
                    .build()
            );
            assertTrue(ex.getMessage().contains("positive"));
        }

        @Test
        @DisplayName("Accepts very long cooldown period (1 hour)")
        void acceptsVeryLongCooldownPeriod() {
            LLMClientConfig config = LLMClientConfig.builder()
                .cooldownPeriod(Duration.ofHours(1))
                .build();
            assertEquals(Duration.ofHours(1), config.getCooldownPeriod());
        }
    }

    @Nested
    @DisplayName("Max Batch Tokens Validation")
    class MaxBatchTokensValidationTests {

        @Test
        @DisplayName("Default max batch tokens is 4000")
        void defaultMaxBatchTokensIs4000() {
            LLMClientConfig config = LLMClientConfig.builder().build();
            assertEquals(4000, config.getMaxBatchTokens());
        }

        @Test
        @DisplayName("Accepts valid positive max batch tokens")
        void acceptsValidPositiveMaxBatchTokens() {
            LLMClientConfig config = LLMClientConfig.builder()
                .maxBatchTokens(8000)
                .build();
            assertEquals(8000, config.getMaxBatchTokens());
        }

        @Test
        @DisplayName("Accepts minimum max batch tokens (1)")
        void acceptsMinimumMaxBatchTokens() {
            LLMClientConfig config = LLMClientConfig.builder()
                .maxBatchTokens(1)
                .build();
            assertEquals(1, config.getMaxBatchTokens());
        }

        @Test
        @DisplayName("Accepts very large max batch tokens (100000)")
        void acceptsVeryLargeMaxBatchTokens() {
            LLMClientConfig config = LLMClientConfig.builder()
                .maxBatchTokens(100000)
                .build();
            assertEquals(100000, config.getMaxBatchTokens());
        }

        @Test
        @DisplayName("Rejects zero max batch tokens")
        void rejectsZeroMaxBatchTokens() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .maxBatchTokens(0)
                    .build()
            );
            assertTrue(ex.getMessage().contains("positive"));
        }

        @Test
        @DisplayName("Rejects negative max batch tokens")
        void rejectsNegativeMaxBatchTokens() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .maxBatchTokens(-100)
                    .build()
            );
            assertTrue(ex.getMessage().contains("positive"));
        }
    }

    @Nested
    @DisplayName("Failure Threshold Validation")
    class FailureThresholdValidationTests {

        @Test
        @DisplayName("Accepts minimum failure threshold (1)")
        void acceptsMinimumFailureThreshold() {
            LLMClientConfig config = LLMClientConfig.builder()
                .failureThreshold(1)
                .build();
            assertEquals(1, config.getFailureThreshold());
        }

        @Test
        @DisplayName("Accepts maximum failure threshold (100)")
        void acceptsMaximumFailureThreshold() {
            LLMClientConfig config = LLMClientConfig.builder()
                .failureThreshold(100)
                .build();
            assertEquals(100, config.getFailureThreshold());
        }

        @Test
        @DisplayName("Rejects failure threshold of 0")
        void rejectsFailureThresholdOf0() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .failureThreshold(0)
                    .build()
            );
            assertTrue(ex.getMessage().contains("[1, 100]"));
        }

        @Test
        @DisplayName("Rejects negative failure threshold")
        void rejectsNegativeFailureThreshold() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .failureThreshold(-5)
                    .build()
            );
            assertTrue(ex.getMessage().contains("[1, 100]"));
        }

        @Test
        @DisplayName("Rejects failure threshold above 100")
        void rejectsFailureThresholdAbove100() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .failureThreshold(101)
                    .build()
            );
            assertTrue(ex.getMessage().contains("[1, 100]"));
        }
    }

    @Nested
    @DisplayName("Builder Methods")
    class BuilderMethodTests {

        @Test
        @DisplayName("All builder methods work correctly")
        void allBuilderMethodsWorkCorrectly() {
            LLMClientConfig config = LLMClientConfig.builder()
                .baseUrl("https://api.example.com")
                .apiKey("test-key")
                .chatModel("gpt-4")
                .embeddingModel("text-embedding-3-large")
                .timeout(Duration.ofSeconds(60))
                .maxRetries(5)
                .failureThreshold(10)
                .cooldownPeriod(Duration.ofSeconds(120))
                .maxPromptsPerQuery(20)
                .relevancyThreshold(0.5)
                .embeddingDimension(3072)
                .maxBatchTokens(8000)
                .build();

            assertEquals("https://api.example.com", config.getBaseUrl());
            assertEquals("test-key", config.getApiKey());
            assertEquals("gpt-4", config.getChatModel());
            assertEquals("text-embedding-3-large", config.getEmbeddingModel());
            assertEquals(Duration.ofSeconds(60), config.getTimeout());
            assertEquals(5, config.getMaxRetries());
            assertEquals(10, config.getFailureThreshold());
            assertEquals(Duration.ofSeconds(120), config.getCooldownPeriod());
            assertEquals(20, config.getMaxPromptsPerQuery());
            assertEquals(0.5, config.getRelevancyThreshold(), 0.001);
            assertEquals(3072, config.getEmbeddingDimension());
            assertEquals(8000, config.getMaxBatchTokens());
        }
    }

    @Nested
    @DisplayName("Base URL Validation")
    class BaseUrlValidationTests {

        @Test
        @DisplayName("Rejects null base URL")
        void rejectsNullBaseUrl() {
            assertThrows(
                NullPointerException.class,
                () -> LLMClientConfig.builder()
                    .baseUrl(null)
                    .build()
            );
        }

        @Test
        @DisplayName("Rejects empty base URL")
        void rejectsEmptyBaseUrl() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .baseUrl("")
                    .build()
            );
            assertTrue(ex.getMessage().contains("empty"));
        }

        @Test
        @DisplayName("Rejects base URL without http/https scheme")
        void rejectsBaseUrlWithoutScheme() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.builder()
                    .baseUrl("example.com")
                    .build()
            );
            assertTrue(ex.getMessage().contains("http://") || ex.getMessage().contains("https://"));
        }

        @Test
        @DisplayName("Removes trailing slash from base URL")
        void removesTrailingSlashFromBaseUrl() {
            LLMClientConfig config = LLMClientConfig.builder()
                .baseUrl("https://api.example.com/")
                .build();
            assertEquals("https://api.example.com", config.getBaseUrl());
        }
    }

    @Nested
    @DisplayName("Static Factory Methods")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("defaultLocal() creates valid configuration")
        void defaultLocalCreatesValidConfig() {
            LLMClientConfig config = LLMClientConfig.defaultLocal();

            assertEquals("http://localhost:11434", config.getBaseUrl());
            assertEquals("", config.getApiKey());
            assertEquals("llama3.2", config.getChatModel());
            assertEquals("nomic-embed-text", config.getEmbeddingModel());
            // Cooldown should still be the default 60000ms
            assertEquals(Duration.ofMillis(60000), config.getCooldownPeriod());
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Configuration is immutable after construction")
        void configurationIsImmutable() {
            Duration originalCooldown = Duration.ofMillis(60000);
            LLMClientConfig config = LLMClientConfig.builder()
                .cooldownPeriod(originalCooldown)
                .build();

            // The returned Duration should be equal to what we set
            assertEquals(originalCooldown, config.getCooldownPeriod());
        }
    }

    @Nested
    @DisplayName("Config.Llm Integration")
    class ConfigLlmIntegrationTests {

        @Test
        @DisplayName("fromConfig() creates LLMClientConfig from Config.Llm")
        void fromConfigCreatesLlmClientConfigFromConfigLlm() {
            // Create a Config.Llm with custom values
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.llm.base-url", "https://api.custom.com/v1");
            props.put("excalibur.llm.api-key", "test-api-key");
            props.put("excalibur.llm.chat-model", "gpt-4");
            props.put("excalibur.llm.embedding-model", "text-embedding-3-large");
            props.put("excalibur.llm.embeddings-dimensions", "3072");
            props.put("excalibur.llm.timeout-ms", "120000");
            props.put("excalibur.llm.max-retries", "5");
            props.put("excalibur.llm.failure-threshold", "10");
            props.put("excalibur.llm.cooldown-period-ms", "90000");
            props.put("excalibur.llm.max-prompts-per-query", "20");
            props.put("excalibur.llm.relevancy-threshold", "0.5");
            props.put("excalibur.llm.max-batch-tokens", "8000");

            Config config = new Config(props);
            Config.Llm llmConfig = config.llm();

            LLMClientConfig clientConfig = LLMClientConfig.fromConfig(llmConfig);

            // Verify all values are correctly mapped
            assertEquals("https://api.custom.com/v1", clientConfig.getBaseUrl());
            assertEquals("test-api-key", clientConfig.getApiKey());
            assertEquals("gpt-4", clientConfig.getChatModel());
            assertEquals("text-embedding-3-large", clientConfig.getEmbeddingModel());
            assertEquals(Duration.ofMillis(120000), clientConfig.getTimeout());
            assertEquals(5, clientConfig.getMaxRetries());
            assertEquals(10, clientConfig.getFailureThreshold());
            assertEquals(Duration.ofMillis(90000), clientConfig.getCooldownPeriod());
            assertEquals(20, clientConfig.getMaxPromptsPerQuery());
            assertEquals(0.5, clientConfig.getRelevancyThreshold(), 0.001);
            assertEquals(3072, clientConfig.getEmbeddingDimension());
            assertEquals(8000, clientConfig.getMaxBatchTokens());
        }

        @Test
        @DisplayName("fromConfig() uses defaults for missing properties")
        void fromConfigUsesDefaultsForMissingProperties() {
            // Create Config.Llm with minimal properties
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.llm.base-url", "http://localhost:11434");

            Config config = new Config(props);
            Config.Llm llmConfig = config.llm();

            LLMClientConfig clientConfig = LLMClientConfig.fromConfig(llmConfig);

            // Verify defaults are applied
            assertEquals("http://localhost:11434", clientConfig.getBaseUrl());
            assertEquals("", clientConfig.getApiKey());
            assertEquals(Config.Llm.DEFAULT_CHAT_MODEL, clientConfig.getChatModel());
            assertEquals(Config.Llm.DEFAULT_EMBEDDING_MODEL, clientConfig.getEmbeddingModel());
            assertEquals(Duration.ofMillis(Config.Llm.DEFAULT_TIMEOUT_MS), clientConfig.getTimeout());
            assertEquals(Config.Llm.DEFAULT_MAX_RETRIES, clientConfig.getMaxRetries());
            assertEquals(Config.Llm.DEFAULT_FAILURE_THRESHOLD, clientConfig.getFailureThreshold());
            assertEquals(Duration.ofMillis(Config.Llm.DEFAULT_COOLDOWN_PERIOD_MS), clientConfig.getCooldownPeriod());
            assertEquals(Config.Llm.DEFAULT_MAX_PROMPTS_PER_QUERY, clientConfig.getMaxPromptsPerQuery());
            assertEquals(Config.Llm.DEFAULT_RELEVANCY_THRESHOLD, clientConfig.getRelevancyThreshold(), 0.001);
            assertEquals(Config.Llm.DEFAULT_EMBEDDINGS_DIMENSIONS, clientConfig.getEmbeddingDimension());
            assertEquals(Config.Llm.DEFAULT_MAX_BATCH_TOKENS, clientConfig.getMaxBatchTokens());
        }

        @Test
        @DisplayName("fromConfig() throws NullPointerException for null input")
        void fromConfigThrowsNullPointerExceptionForNullInput() {
            assertThrows(
                NullPointerException.class,
                () -> LLMClientConfig.fromConfig(null)
            );
        }

        @Test
        @DisplayName("fromConfig() validates all values through builder")
        void fromConfigValidatesAllValuesThroughBuilder() {
            // Create Config.Llm with invalid values
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.llm.base-url", "https://api.test.com");
            props.put("excalibur.llm.failure-threshold", "0"); // Invalid: must be >= 1

            Config config = new Config(props);
            Config.Llm llmConfig = config.llm();

            // Should throw due to validation in the builder
            assertThrows(
                IllegalArgumentException.class,
                () -> LLMClientConfig.fromConfig(llmConfig)
            );
        }
    }
}
