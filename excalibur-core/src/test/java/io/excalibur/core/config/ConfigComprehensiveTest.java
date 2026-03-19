package io.excalibur.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Config class and all nested configuration classes.
 */
@DisplayName("Config Comprehensive Tests")
class ConfigComprehensiveTest {

    @Nested
    @DisplayName("Config Constructor Tests")
    class ConfigConstructorTests {

        @Test
        @DisplayName("Should create config with empty properties")
        void shouldCreateConfigWithEmptyProperties() {
            Map<String, String> props = new HashMap<>();
            Config config = new Config(props);

            assertThat(config.enabled()).isTrue();
            assertThat(config.visibility()).isNotNull();
            assertThat(config.query()).isNotNull();
            assertThat(config.ingest()).isNotNull();
            assertThat(config.health()).isNotNull();
        }

        @Test
        @DisplayName("Should create config with all properties")
        void shouldCreateConfigWithAllProperties() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.enabled", "false");
            props.put("excalibur.visibility.epoch-tick-ms", "200");
            props.put("excalibur.query.include-partial-segments", "false");
            props.put("excalibur.query.default-top-k", "20");
            props.put("excalibur.query.default-threshold", "0.5");
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "50000");
            props.put("excalibur.ingest.document-atomicity", "false");
            props.put("excalibur.health.readiness-threshold", "0.8");

            Config config = new Config(props);

            assertThat(config.enabled()).isFalse();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(200L);
            assertThat(config.query().includePartialSegments()).isFalse();
            assertThat(config.query().getDefaultTopK()).isEqualTo(20);
            assertThat(config.query().getDefaultThreshold()).isEqualTo(0.5);
            assertThat(config.ingest().getHnswQueueDepthLimit()).isEqualTo(50000);
            assertThat(config.ingest().isDocumentAtomicityEnabled()).isFalse();
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("Should use defaults for invalid values")
        void shouldUseDefaultsForInvalidValues() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "invalid");
            props.put("excalibur.query.default-top-k", "not-a-number");
            props.put("excalibur.query.default-threshold", "bad");
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "wrong");
            props.put("excalibur.health.readiness-threshold", "nope");

            Config config = new Config(props);

            // Should use defaults
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(100L);
            assertThat(config.query().getDefaultTopK()).isEqualTo(10);
            assertThat(config.query().getDefaultThreshold()).isEqualTo(0.3);
            assertThat(config.ingest().getHnswQueueDepthLimit()).isEqualTo(100000);
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("ParseBoolean Tests")
    class ParseBooleanTests {

        @Test
        @DisplayName("Should parse true variations")
        void shouldParseTrueVariations() {
            assertThat(Config.parseBoolean("true", false)).isTrue();
            assertThat(Config.parseBoolean("TRUE", false)).isTrue();
            assertThat(Config.parseBoolean("True", false)).isTrue();
            assertThat(Config.parseBoolean("1", false)).isTrue();
            assertThat(Config.parseBoolean("yes", false)).isTrue();
            assertThat(Config.parseBoolean("YES", false)).isTrue();
            assertThat(Config.parseBoolean("Yes", false)).isTrue();
        }

        @Test
        @DisplayName("Should return default for null")
        void shouldReturnDefaultForNull() {
            assertThat(Config.parseBoolean(null, true)).isTrue();
            assertThat(Config.parseBoolean(null, false)).isFalse();
        }

        @Test
        @DisplayName("Should return false for other values")
        void shouldReturnFalseForOtherValues() {
            assertThat(Config.parseBoolean("false", true)).isFalse();
            assertThat(Config.parseBoolean("0", true)).isFalse();
            assertThat(Config.parseBoolean("no", true)).isFalse();
            assertThat(Config.parseBoolean("random", true)).isFalse();
            assertThat(Config.parseBoolean("", true)).isFalse();
        }

        @Test
        @DisplayName("Should handle whitespace")
        void shouldHandleWhitespace() {
            assertThat(Config.parseBoolean("  true  ", false)).isTrue();
            assertThat(Config.parseBoolean("  1  ", false)).isTrue();
            assertThat(Config.parseBoolean("  yes  ", false)).isTrue();
        }
    }

    @Nested
    @DisplayName("VisibilityConfig Tests")
    class VisibilityConfigTests {

        @Test
        @DisplayName("Should use default epoch tick")
        void shouldUseDefaultEpochTick() {
            Map<String, String> props = new HashMap<>();
            Config.VisibilityConfig config = new Config(props).visibility();

            assertThat(config.getEpochTickMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should use custom epoch tick")
        void shouldUseCustomEpochTick() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "250");

            Config.VisibilityConfig config = new Config(props).visibility();

            assertThat(config.getEpochTickMs()).isEqualTo(250L);
        }

        @Test
        @DisplayName("Should calculate epoch from current time")
        void shouldCalculateEpochFromCurrentTime() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "1000");

            Config.VisibilityConfig config = new Config(props).visibility();

            long epoch = config.getEpoch();
            long expectedEpoch = System.currentTimeMillis() / 1000;
            // Allow for 1 epoch difference due to timing
            assertThat(epoch).isBetween(expectedEpoch - 1, expectedEpoch + 1);
        }

        @Test
        @DisplayName("Should calculate time until next epoch tick")
        void shouldCalculateTimeUntilNextEpochTick() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "100");

            Config.VisibilityConfig config = new Config(props).visibility();

            long timeUntil = config.timeUntilEpochTick();
            assertThat(timeUntil).isBetween(1L, 100L);
        }

        @Test
        @DisplayName("Should get next epoch")
        void shouldGetNextEpoch() {
            Map<String, String> props = new HashMap<>();
            Config.VisibilityConfig config = new Config(props).visibility();

            long currentEpoch = config.getEpoch();
            long nextEpoch = config.getNextEpoch();

            assertThat(nextEpoch).isEqualTo(currentEpoch + 1);
        }
    }

    @Nested
    @DisplayName("QueryConfig Tests")
    class QueryConfigTests {

        @Test
        @DisplayName("Should use defaults")
        void shouldUseDefaults() {
            Map<String, String> props = new HashMap<>();
            Config.QueryConfig config = new Config(props).query();

            assertThat(config.includePartialSegments()).isTrue();
            assertThat(config.getDefaultTopK()).isEqualTo(10);
            assertThat(config.getDefaultThreshold()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("Should use custom values")
        void shouldUseCustomValues() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.include-partial-segments", "false");
            props.put("excalibur.query.default-top-k", "50");
            props.put("excalibur.query.default-threshold", "0.75");

            Config.QueryConfig config = new Config(props).query();

            assertThat(config.includePartialSegments()).isFalse();
            assertThat(config.getDefaultTopK()).isEqualTo(50);
            assertThat(config.getDefaultThreshold()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Should parse include-partial-segments variations")
        void shouldParseIncludePartialSegmentsVariations() {
            Map<String, String> props1 = new HashMap<>();
            props1.put("excalibur.query.include-partial-segments", "1");
            assertThat(new Config(props1).query().includePartialSegments()).isTrue();

            Map<String, String> props2 = new HashMap<>();
            props2.put("excalibur.query.include-partial-segments", "yes");
            assertThat(new Config(props2).query().includePartialSegments()).isTrue();

            Map<String, String> props3 = new HashMap<>();
            props3.put("excalibur.query.include-partial-segments", "0");
            assertThat(new Config(props3).query().includePartialSegments()).isFalse();
        }
    }

    @Nested
    @DisplayName("IngestConfig Tests")
    class IngestConfigTests {

        @Test
        @DisplayName("Should use defaults")
        void shouldUseDefaults() {
            Map<String, String> props = new HashMap<>();
            Config.IngestConfig config = new Config(props).ingest();

            assertThat(config.getHnswQueueDepthLimit()).isEqualTo(100000);
            assertThat(config.isDocumentAtomicityEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should use custom values")
        void shouldUseCustomValues() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.ingest.hnsw-queue-depth-limit", "250000");
            props.put("excalibur.ingest.document-atomicity", "false");

            Config.IngestConfig config = new Config(props).ingest();

            assertThat(config.getHnswQueueDepthLimit()).isEqualTo(250000);
            assertThat(config.isDocumentAtomicityEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should parse document-atomicity variations")
        void shouldParseDocumentAtomicityVariations() {
            Map<String, String> props1 = new HashMap<>();
            props1.put("excalibur.ingest.document-atomicity", "yes");
            assertThat(new Config(props1).ingest().isDocumentAtomicityEnabled()).isTrue();

            Map<String, String> props2 = new HashMap<>();
            props2.put("excalibur.ingest.document-atomicity", "no");
            assertThat(new Config(props2).ingest().isDocumentAtomicityEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("HealthConfig Tests")
    class HealthConfigTests {

        @Test
        @DisplayName("Should use default threshold")
        void shouldUseDefaultThreshold() {
            Map<String, String> props = new HashMap<>();
            Config.HealthConfig config = new Config(props).health();

            assertThat(config.getReadinessThreshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should use custom threshold")
        void shouldUseCustomThreshold() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.9");

            Config.HealthConfig config = new Config(props).health();

            assertThat(config.getReadinessThreshold()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("Should check readiness correctly")
        void shouldCheckReadinessCorrectly() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.5");

            Config.HealthConfig config = new Config(props).health();

            assertThat(config.isReady(0.0)).isFalse();
            assertThat(config.isReady(0.49)).isFalse();
            assertThat(config.isReady(0.5)).isTrue();
            assertThat(config.isReady(0.51)).isTrue();
            assertThat(config.isReady(1.0)).isTrue();
        }

        @Test
        @DisplayName("Should handle edge case thresholds")
        void shouldHandleEdgeCaseThresholds() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.0");

            Config.HealthConfig config = new Config(props).health();

            assertThat(config.isReady(0.0)).isTrue();
            assertThat(config.isReady(0.001)).isTrue();
        }

        @Test
        @DisplayName("Should handle high threshold")
        void shouldHandleHighThreshold() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "1.0");

            Config.HealthConfig config = new Config(props).health();

            assertThat(config.isReady(0.99)).isFalse();
            assertThat(config.isReady(1.0)).isTrue();
        }
    }

    @Nested
    @DisplayName("Enabled Flag Tests")
    class EnabledFlagTests {

        @Test
        @DisplayName("Should default to enabled")
        void shouldDefaultToEnabled() {
            Map<String, String> props = new HashMap<>();
            Config config = new Config(props);

            assertThat(config.enabled()).isTrue();
        }

        @Test
        @DisplayName("Should parse enabled flag")
        void shouldParseEnabledFlag() {
            Map<String, String> props1 = new HashMap<>();
            props1.put("excalibur.enabled", "false");
            assertThat(new Config(props1).enabled()).isFalse();

            Map<String, String> props2 = new HashMap<>();
            props2.put("excalibur.enabled", "true");
            assertThat(new Config(props2).enabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Getter Tests")
    class GetterTests {

        @Test
        @DisplayName("Should return visibility config")
        void shouldReturnVisibilityConfig() {
            Config config = new Config(new HashMap<>());
            assertThat(config.visibility()).isNotNull();
            assertThat(config.visibility()).isInstanceOf(Config.VisibilityConfig.class);
        }

        @Test
        @DisplayName("Should return query config")
        void shouldReturnQueryConfig() {
            Config config = new Config(new HashMap<>());
            assertThat(config.query()).isNotNull();
            assertThat(config.query()).isInstanceOf(Config.QueryConfig.class);
        }

        @Test
        @DisplayName("Should return ingest config")
        void shouldReturnIngestConfig() {
            Config config = new Config(new HashMap<>());
            assertThat(config.ingest()).isNotNull();
            assertThat(config.ingest()).isInstanceOf(Config.IngestConfig.class);
        }

        @Test
        @DisplayName("Should return health config")
        void shouldReturnHealthConfig() {
            Config config = new Config(new HashMap<>());
            assertThat(config.health()).isNotNull();
            assertThat(config.health()).isInstanceOf(Config.HealthConfig.class);
        }
    }
}
