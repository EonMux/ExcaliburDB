package io.excalibur.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ConfigValidation.
 *
 * <p>Tests verify all validation rules for each configuration section.</p>
 */
@DisplayName("ConfigValidation")
class ConfigValidationTest {

    private Map<String, String> baseProps;

    @BeforeEach
    void setUp() {
        baseProps = new HashMap<>();
        // Set all required properties with valid defaults
        baseProps.put("excalibur.visibility.epoch-tick-ms", "100");
        baseProps.put("excalibur.visibility.query-timeout-ms", "30000");
        baseProps.put("excalibur.ingest.hnsw-queue-depth-limit", "100000");
        baseProps.put("excalibur.ingest.document-atomicity-timeout-ms", "2000");
        baseProps.put("excalibur.health.readiness-threshold", "0.5");
        baseProps.put("excalibur.health.healthy-threshold", "0.9");
        baseProps.put("excalibur.health.max-latency-ms", "5000");
        baseProps.put("excalibur.query.top-k-default", "100");
        baseProps.put("excalibur.query.recall-target", "0.95");
        baseProps.put("excalibur.query.threshold", "0.3");
        baseProps.put("excalibur.query.vector-dimensions", "1536");
        baseProps.put("excalibur.query.llm-timeout-ms", "60000");
        baseProps.put("excalibur.llm.base-url", "https://api.openai.com/v1");
        baseProps.put("excalibur.llm.embeddings-dimensions", "1536");
        baseProps.put("excalibur.shard.segment-load-factor", "0.7");
        baseProps.put("excalibur.shard.default-ef-construction", "128");
        baseProps.put("excalibur.shard.default-ef-search", "64");
        baseProps.put("excalibur.shard.hnsw-integration-queue-depth-limit", "100000");
    }

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("accepts valid configuration")
        void acceptsValidConfig() {
            Config config = new Config(baseProps);
            assertThatCode(() -> ConfigValidation.validate(config))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Visibility validation")
    class VisibilityValidation {

        @Test
        @DisplayName("rejects epoch-tick-ms below minimum")
        void rejectsEpochTickBelowMinimum() {
            baseProps.put("excalibur.visibility.epoch-tick-ms", "9");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility.epoch-tick-ms must be >= 10");
        }

        @Test
        @DisplayName("rejects epoch-tick-ms above maximum")
        void rejectsEpochTickAboveMaximum() {
            baseProps.put("excalibur.visibility.epoch-tick-ms", "10001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility.epoch-tick-ms must be <= 10000");
        }

        @Test
        @DisplayName("accepts epoch-tick-ms at boundaries")
        void acceptsEpochTickAtBoundaries() {
            baseProps.put("excalibur.visibility.epoch-tick-ms", "10");
            Config config1 = new Config(baseProps);
            assertThatCode(() -> ConfigValidation.validate(config1))
                .doesNotThrowAnyException();

            baseProps.put("excalibur.visibility.epoch-tick-ms", "10000");
            Config config2 = new Config(baseProps);
            assertThatCode(() -> ConfigValidation.validate(config2))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects query-timeout-ms below minimum")
        void rejectsQueryTimeoutBelowMinimum() {
            baseProps.put("excalibur.visibility.query-timeout-ms", "999");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility.query-timeout-ms must be >= 1000");
        }

        @Test
        @DisplayName("rejects query-timeout-ms above maximum")
        void rejectsQueryTimeoutAboveMaximum() {
            baseProps.put("excalibur.visibility.query-timeout-ms", "300001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility.query-timeout-ms must be <= 300000");
        }
    }

    @Nested
    @DisplayName("Ingest validation")
    class IngestValidation {

        @Test
        @DisplayName("rejects hnsw-queue-depth-limit below minimum")
        void rejectsHnswQueueDepthBelowMinimum() {
            baseProps.put("excalibur.ingest.hnsw-queue-depth-limit", "9999");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingest.hnsw-queue-depth-limit must be >= 10000");
        }

        @Test
        @DisplayName("rejects hnsw-queue-depth-limit above maximum")
        void rejectsHnswQueueDepthAboveMaximum() {
            baseProps.put("excalibur.ingest.hnsw-queue-depth-limit", "1000001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingest.hnsw-queue-depth-limit must be <= 1000000");
        }

        @Test
        @DisplayName("rejects document-timeout-ms below minimum")
        void rejectsDocumentTimeoutBelowMinimum() {
            baseProps.put("excalibur.ingest.document-atomicity-timeout-ms", "999");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingest.document-atomicity-timeout-ms must be >= 1000");
        }

        @Test
        @DisplayName("rejects document-timeout-ms above maximum")
        void rejectsDocumentTimeoutAboveMaximum() {
            baseProps.put("excalibur.ingest.document-atomicity-timeout-ms", "30001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingest.document-atomicity-timeout-ms must be <= 30000");
        }
    }

    @Nested
    @DisplayName("Health validation")
    class HealthValidation {

        @Test
        @DisplayName("rejects readiness-threshold below 0.0")
        void rejectsReadinessThresholdBelowZero() {
            baseProps.put("excalibur.health.readiness-threshold", "-0.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.readiness-threshold must be >= 0.0");
        }

        @Test
        @DisplayName("rejects readiness-threshold above 1.0")
        void rejectsReadinessThresholdAboveOne() {
            baseProps.put("excalibur.health.readiness-threshold", "1.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.readiness-threshold must be <= 1.0");
        }

        @Test
        @DisplayName("rejects healthy-threshold below 0.0")
        void rejectsHealthyThresholdBelowZero() {
            baseProps.put("excalibur.health.healthy-threshold", "-0.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.healthy-threshold must be >= 0.0");
        }

        @Test
        @DisplayName("rejects healthy-threshold above 1.0")
        void rejectsHealthyThresholdAboveOne() {
            baseProps.put("excalibur.health.healthy-threshold", "1.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.healthy-threshold must be <= 1.0");
        }

        @Test
        @DisplayName("rejects max-latency-ms below minimum")
        void rejectsMaxLatencyBelowMinimum() {
            baseProps.put("excalibur.health.max-latency-ms", "99");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.max-latency-ms must be >= 100");
        }

        @Test
        @DisplayName("rejects max-latency-ms above maximum")
        void rejectsMaxLatencyAboveMaximum() {
            baseProps.put("excalibur.health.max-latency-ms", "60001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.max-latency-ms must be <= 60000");
        }
    }

    @Nested
    @DisplayName("Query validation")
    class QueryValidation {

        @Test
        @DisplayName("rejects top-k-default below minimum")
        void rejectsTopKBelowMinimum() {
            baseProps.put("excalibur.query.top-k-default", "0");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.top-k-default must be >= 1");
        }

        @Test
        @DisplayName("rejects top-k-default above maximum")
        void rejectsTopKAboveMaximum() {
            baseProps.put("excalibur.query.top-k-default", "10001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.top-k-default must be <= 10000");
        }

        @Test
        @DisplayName("rejects recall-target below 0.0")
        void rejectsRecallTargetBelowZero() {
            baseProps.put("excalibur.query.recall-target", "-0.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.recall-target must be >= 0.0");
        }

        @Test
        @DisplayName("rejects recall-target above 1.0")
        void rejectsRecallTargetAboveOne() {
            baseProps.put("excalibur.query.recall-target", "1.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.recall-target must be <= 1.0");
        }

        @Test
        @DisplayName("rejects threshold below 0.0")
        void rejectsThresholdBelowZero() {
            baseProps.put("excalibur.query.threshold", "-0.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.threshold must be >= 0.0");
        }

        @Test
        @DisplayName("rejects threshold above 1.0")
        void rejectsThresholdAboveOne() {
            baseProps.put("excalibur.query.threshold", "1.1");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.threshold must be <= 1.0");
        }

        @Test
        @DisplayName("rejects vector-dimensions below minimum")
        void rejectsVectorDimensionsBelowMinimum() {
            baseProps.put("excalibur.query.vector-dimensions", "127");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.vector-dimensions must be >= 128");
        }

        @Test
        @DisplayName("rejects vector-dimensions above maximum")
        void rejectsVectorDimensionsAboveMaximum() {
            baseProps.put("excalibur.query.vector-dimensions", "4097");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.vector-dimensions must be <= 4096");
        }

        @Test
        @DisplayName("rejects llm-timeout-ms below minimum")
        void rejectsLlmTimeoutBelowMinimum() {
            baseProps.put("excalibur.query.llm-timeout-ms", "4999");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.llm-timeout-ms must be >= 5000");
        }

        @Test
        @DisplayName("rejects llm-timeout-ms above maximum")
        void rejectsLlmTimeoutAboveMaximum() {
            baseProps.put("excalibur.query.llm-timeout-ms", "300001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query.llm-timeout-ms must be <= 300000");
        }
    }

    @Nested
    @DisplayName("LLM validation")
    class LlmValidation {

        @Test
        @DisplayName("rejects invalid base-url (no protocol)")
        void rejectsInvalidBaseUrlNoProtocol() {
            baseProps.put("excalibur.llm.base-url", "invalid-url");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.base-url must be a valid URL");
        }

        @Test
        @DisplayName("accepts http base-url")
        void acceptsHttpBaseUrl() {
            baseProps.put("excalibur.llm.base-url", "http://localhost:8080");
            Config config = new Config(baseProps);

            assertThatCode(() -> ConfigValidation.validate(config))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts https base-url")
        void acceptsHttpsBaseUrl() {
            baseProps.put("excalibur.llm.base-url", "https://api.example.com/v1");
            Config config = new Config(baseProps);

            assertThatCode(() -> ConfigValidation.validate(config))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects embeddings-dimensions below minimum")
        void rejectsEmbeddingsDimensionsBelowMinimum() {
            baseProps.put("excalibur.llm.embeddings-dimensions", "127");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.embeddings-dimensions must be >= 128");
        }

        @Test
        @DisplayName("rejects embeddings-dimensions above maximum")
        void rejectsEmbeddingsDimensionsAboveMaximum() {
            baseProps.put("excalibur.llm.embeddings-dimensions", "4097");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.embeddings-dimensions must be <= 4096");
        }
    }

    @Nested
    @DisplayName("Shard validation")
    class ShardValidation {

        @Test
        @DisplayName("rejects load-factor below minimum")
        void rejectsLoadFactorBelowMinimum() {
            baseProps.put("excalibur.shard.segment-load-factor", "0.49");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.segment-load-factor must be >= 0.5");
        }

        @Test
        @DisplayName("rejects load-factor above maximum")
        void rejectsLoadFactorAboveMaximum() {
            baseProps.put("excalibur.shard.segment-load-factor", "0.91");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.segment-load-factor must be <= 0.9");
        }

        @Test
        @DisplayName("rejects ef-construction below minimum")
        void rejectsEfConstructionBelowMinimum() {
            baseProps.put("excalibur.shard.default-ef-construction", "15");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.default-ef-construction must be >= 16");
        }

        @Test
        @DisplayName("rejects ef-construction above maximum")
        void rejectsEfConstructionAboveMaximum() {
            baseProps.put("excalibur.shard.default-ef-construction", "1025");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.default-ef-construction must be <= 1024");
        }

        @Test
        @DisplayName("rejects ef-search below minimum")
        void rejectsEfSearchBelowMinimum() {
            baseProps.put("excalibur.shard.default-ef-search", "7");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.default-ef-search must be >= 8");
        }

        @Test
        @DisplayName("rejects ef-search above maximum")
        void rejectsEfSearchAboveMaximum() {
            baseProps.put("excalibur.shard.default-ef-search", "1025");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.default-ef-search must be <= 1024");
        }

        @Test
        @DisplayName("rejects hnsw-queue-limit below minimum")
        void rejectsHnswQueueLimitBelowMinimum() {
            baseProps.put("excalibur.shard.hnsw-integration-queue-depth-limit", "9999");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.hnsw-integration-queue-depth-limit must be >= 10000");
        }

        @Test
        @DisplayName("rejects hnsw-queue-limit above maximum")
        void rejectsHnswQueueLimitAboveMaximum() {
            baseProps.put("excalibur.shard.hnsw-integration-queue-depth-limit", "1000001");
            Config config = new Config(baseProps);

            assertThatThrownBy(() -> ConfigValidation.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard.hnsw-integration-queue-depth-limit must be <= 1000000");
        }
    }

    @Nested
    @DisplayName("getInstance()")
    class GetInstance {

        @Test
        @DisplayName("returns new instance")
        void returnsNewInstance() {
            ConfigValidation instance = ConfigValidation.getInstance();
            assertThat(instance).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateRequiredSections()")
    class ValidateRequiredSections {

        @Test
        @DisplayName("accepts config with all sections")
        void acceptsConfigWithAllSections() {
            Config config = new Config(baseProps);
            assertThatCode(() -> ConfigValidation.validateRequiredSections(config))
                .doesNotThrowAnyException();
        }
    }
}
