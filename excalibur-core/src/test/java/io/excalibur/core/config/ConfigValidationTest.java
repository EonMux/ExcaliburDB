package io.excalibur.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for configuration validation in Config class.
 *
 * Each inner config class validates its parameters in the constructor.
 */
@DisplayName("Config Validation Tests")
class ConfigValidationTest {

    private Map<String, String> createEmptyProps() {
        return new HashMap<>();
    }

    @Nested
    @DisplayName("VisibilityConfig Validation")
    class VisibilityConfigValidation {

        @Test
        @DisplayName("Should accept valid default config")
        void shouldAcceptValidDefaultConfig() {
            Map<String, String> props = createEmptyProps();
            Config.VisibilityConfig config = new Config.VisibilityConfig(props);
            assertThat(config.getEpochTickMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should accept minimum valid epoch tick (10)")
        void shouldAcceptMinimumValidEpochTick() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "10");
            Config.VisibilityConfig config = new Config.VisibilityConfig(props);
            assertThat(config.getEpochTickMs()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Should accept maximum valid epoch tick (10000)")
        void shouldAcceptMaximumValidEpochTick() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "10000");
            Config.VisibilityConfig config = new Config.VisibilityConfig(props);
            assertThat(config.getEpochTickMs()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("Should reject epoch tick below minimum (< 10)")
        void shouldRejectEpochTickBelowMinimum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "5");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.VisibilityConfig(props))
                .withMessageContaining("excalibur.visibility.epoch-tick-ms must be between 10 and 10000");
        }

        @Test
        @DisplayName("Should reject epoch tick above maximum (> 10000)")
        void shouldRejectEpochTickAboveMaximum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "15000");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.VisibilityConfig(props))
                .withMessageContaining("excalibur.visibility.epoch-tick-ms must be between 10 and 10000");
        }
    }

    @Nested
    @DisplayName("QueryConfig Validation")
    class QueryConfigValidation {

        @Test
        @DisplayName("Should accept valid default config")
        void shouldAcceptValidDefaultConfig() {
            Map<String, String> props = createEmptyProps();
            Config.QueryConfig config = new Config.QueryConfig(props);
            assertThat(config.getDefaultTopK()).isEqualTo(10);
            assertThat(config.getDefaultThreshold()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("Should accept minimum valid top-k (1)")
        void shouldAcceptMinimumValidTopK() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-top-k", "1");
            Config.QueryConfig config = new Config.QueryConfig(props);
            assertThat(config.getDefaultTopK()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should accept maximum valid top-k (10000)")
        void shouldAcceptMaximumValidTopK() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-top-k", "10000");
            Config.QueryConfig config = new Config.QueryConfig(props);
            assertThat(config.getDefaultTopK()).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should reject top-k below minimum (< 1)")
        void shouldRejectTopKBelowMinimum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-top-k", "0");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.QueryConfig(props))
                .withMessageContaining("excalibur.query.default-top-k must be between 1 and 10000");
        }

        @Test
        @DisplayName("Should reject top-k above maximum (> 10000)")
        void shouldRejectTopKAboveMaximum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-top-k", "20000");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.QueryConfig(props))
                .withMessageContaining("excalibur.query.default-top-k must be between 1 and 10000");
        }

        @Test
        @DisplayName("Should accept minimum valid threshold (0.0)")
        void shouldAcceptMinimumValidThreshold() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-threshold", "0.0");
            Config.QueryConfig config = new Config.QueryConfig(props);
            assertThat(config.getDefaultThreshold()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should accept maximum valid threshold (1.0)")
        void shouldAcceptMaximumValidThreshold() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-threshold", "1.0");
            Config.QueryConfig config = new Config.QueryConfig(props);
            assertThat(config.getDefaultThreshold()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should reject threshold below minimum (< 0.0)")
        void shouldRejectThresholdBelowMinimum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-threshold", "-0.1");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.QueryConfig(props))
                .withMessageContaining("excalibur.query.default-threshold must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("Should reject threshold above maximum (> 1.0)")
        void shouldRejectThresholdAboveMaximum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-threshold", "1.5");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.QueryConfig(props))
                .withMessageContaining("excalibur.query.default-threshold must be between 0.0 and 1.0");
        }
    }

    @Nested
    @DisplayName("IngestConfig Validation")
    class IngestConfigValidation {

        @Test
        @DisplayName("Should accept valid default config")
        void shouldAcceptValidDefaultConfig() {
            Map<String, String> props = createEmptyProps();
            Config.IngestConfig config = new Config.IngestConfig(props);
            assertThat(config.getHnswQueueDepthLimit()).isEqualTo(100000);
            assertThat(config.isDocumentAtomicityEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should accept minimum valid HNSW queue depth (1000)")
        void shouldAcceptMinimumValidHnswQueueDepth() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "1000");
            Config.IngestConfig config = new Config.IngestConfig(props);
            assertThat(config.getHnswQueueDepthLimit()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Should accept maximum valid HNSW queue depth (1000000)")
        void shouldAcceptMaximumValidHnswQueueDepth() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "1000000");
            Config.IngestConfig config = new Config.IngestConfig(props);
            assertThat(config.getHnswQueueDepthLimit()).isEqualTo(1000000);
        }

        @Test
        @DisplayName("Should reject HNSW queue depth below minimum (< 1000)")
        void shouldRejectHnswQueueDepthBelowMinimum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "500");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.IngestConfig(props))
                .withMessageContaining("excalibur.ingest.hnsw-queue-depth-limit must be between 1000 and 1000000");
        }

        @Test
        @DisplayName("Should reject HNSW queue depth above maximum (> 1000000)")
        void shouldRejectHnswQueueDepthAboveMaximum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "2000000");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.IngestConfig(props))
                .withMessageContaining("excalibur.ingest.hnsw-queue-depth-limit must be between 1000 and 1000000");
        }
    }

    @Nested
    @DisplayName("HealthConfig Validation")
    class HealthConfigValidation {

        @Test
        @DisplayName("Should accept valid default config")
        void shouldAcceptValidDefaultConfig() {
            Map<String, String> props = createEmptyProps();
            Config.HealthConfig config = new Config.HealthConfig(props);
            assertThat(config.getReadinessThreshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should accept minimum valid readiness threshold (0.0)")
        void shouldAcceptMinimumValidReadinessThreshold() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.health.readiness-threshold", "0.0");
            Config.HealthConfig config = new Config.HealthConfig(props);
            assertThat(config.getReadinessThreshold()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should accept maximum valid readiness threshold (1.0)")
        void shouldAcceptMaximumValidReadinessThreshold() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.health.readiness-threshold", "1.0");
            Config.HealthConfig config = new Config.HealthConfig(props);
            assertThat(config.getReadinessThreshold()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should reject readiness threshold below minimum (< 0.0)")
        void shouldRejectReadinessThresholdBelowMinimum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.health.readiness-threshold", "-0.1");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.HealthConfig(props))
                .withMessageContaining("excalibur.health.readiness-threshold must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("Should reject readiness threshold above maximum (> 1.0)")
        void shouldRejectReadinessThresholdAboveMaximum() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.health.readiness-threshold", "1.5");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config.HealthConfig(props))
                .withMessageContaining("excalibur.health.readiness-threshold must be between 0.0 and 1.0");
        }
    }

    @Nested
    @DisplayName("Full Config Validation")
    class FullConfigValidation {

        @Test
        @DisplayName("Should accept valid default config")
        void shouldAcceptValidDefaultConfig() {
            Map<String, String> props = createEmptyProps();
            Config config = new Config(props);
            assertThat(config.enabled()).isTrue();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(100L);
            assertThat(config.query().getDefaultTopK()).isEqualTo(10);
            assertThat(config.ingest().getHnswQueueDepthLimit()).isEqualTo(100000);
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should reject config with invalid visibility settings")
        void shouldRejectConfigWithInvalidVisibilitySettings() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "5");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config(props))
                .withMessageContaining("excalibur.visibility.epoch-tick-ms");
        }

        @Test
        @DisplayName("Should reject config with invalid query settings")
        void shouldRejectConfigWithInvalidQuerySettings() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.query.default-threshold", "2.0");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config(props))
                .withMessageContaining("excalibur.query.default-threshold");
        }

        @Test
        @DisplayName("Should reject config with invalid ingest settings")
        void shouldRejectConfigWithInvalidIngestSettings() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "100");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config(props))
                .withMessageContaining("excalibur.ingest.hnsw-queue-depth-limit");
        }

        @Test
        @DisplayName("Should reject config with invalid health settings")
        void shouldRejectConfigWithInvalidHealthSettings() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.health.readiness-threshold", "-0.5");
            assertThatIllegalStateException()
                .isThrownBy(() -> new Config(props))
                .withMessageContaining("excalibur.health.readiness-threshold");
        }

        @Test
        @DisplayName("Should accept config with all valid custom settings")
        void shouldAcceptConfigWithAllValidCustomSettings() {
            Map<String, String> props = createEmptyProps();
            props.put("excalibur.visibility.epoch-tick-ms", "200");
            props.put("excalibur.query.default-top-k", "50");
            props.put("excalibur.query.default-threshold", "0.5");
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "500000");
            props.put("excalibur.health.readiness-threshold", "0.75");

            Config config = new Config(props);

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(200L);
            assertThat(config.query().getDefaultTopK()).isEqualTo(50);
            assertThat(config.query().getDefaultThreshold()).isEqualTo(0.5);
            assertThat(config.ingest().getHnswQueueDepthLimit()).isEqualTo(500000);
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.75);
        }
    }
}
