package io.excalibur.vector.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswConfig}.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Default value correctness</li>
 *   <li>Parameter validation and error handling</li>
 *   <li>Configuration from properties map</li>
 *   <li>Immutability and wither methods</li>
 *   <li>Domain correctness of output values</li>
 * </ul>
 */
@DisplayName("HnswConfig")
class HnswConfigTest {

    @Nested
    @DisplayName("Default Configuration")
    class DefaultConfigTests {

        @Test
        @DisplayName("creates config with default values targeting 99% recall@10")
        void defaultConfig_hasCorrectDefaults() {
            HnswConfig config = new HnswConfig();

            assertThat(config.getEfConstruction()).isEqualTo(200);
            assertThat(config.getEfSearch()).isEqualTo(100);
            assertThat(config.getMaxConnections()).isEqualTo(16);
            assertThat(config.getMaxConnectionsLayer0()).isEqualTo(32);
        }

        @Test
        @DisplayName("default values are within recommended ranges for high recall")
        void defaultValues_withinRecommendedRanges() {
            // efConstruction should be >= 100 for good quality
            assertThat(HnswConfig.DEFAULT_EF_CONSTRUCTION).isGreaterThanOrEqualTo(100);

            // efSearch should be >= 50 for good recall
            assertThat(HnswConfig.DEFAULT_EF_SEARCH).isGreaterThanOrEqualTo(50);

            // M should be in [8, 64] per HNSW paper
            assertThat(HnswConfig.DEFAULT_MAX_CONNECTIONS).isBetween(8, 64);

            // Layer0 should be roughly 2*M
            assertThat(HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER_0)
                .isEqualTo(2 * HnswConfig.DEFAULT_MAX_CONNECTIONS);
        }
    }

    @Nested
    @DisplayName("Custom Configuration")
    class CustomConfigTests {

        @Test
        @DisplayName("accepts valid custom parameters")
        void acceptsValidParameters() {
            HnswConfig config = new HnswConfig(400, 200, 32, 64);

            assertThat(config.getEfConstruction()).isEqualTo(400);
            assertThat(config.getEfSearch()).isEqualTo(200);
            assertThat(config.getMaxConnections()).isEqualTo(32);
            assertThat(config.getMaxConnectionsLayer0()).isEqualTo(64);
        }

        @Test
        @DisplayName("accepts minimum valid values")
        void acceptsMinimumValues() {
            // efConstruction and efSearch minimum is 1
            // maxConnections minimum is 2
            HnswConfig config = new HnswConfig(1, 1, 2, 2);

            assertThat(config.getEfConstruction()).isEqualTo(1);
            assertThat(config.getEfSearch()).isEqualTo(1);
            assertThat(config.getMaxConnections()).isEqualTo(2);
            assertThat(config.getMaxConnectionsLayer0()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts maximum valid maxConnections")
        void acceptsMaxConnectionsLimit() {
            HnswConfig config = new HnswConfig(100, 50, 256, 512);

            assertThat(config.getMaxConnections()).isEqualTo(256);
        }
    }

    @Nested
    @DisplayName("Parameter Validation")
    class ValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        @DisplayName("rejects invalid efConstruction values")
        void rejectsInvalidEfConstruction(int value) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswConfig(value, 100, 16, 32))
                .withMessageContaining("efConstruction must be >=");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        @DisplayName("rejects invalid efSearch values")
        void rejectsInvalidEfSearch(int value) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswConfig(200, value, 16, 32))
                .withMessageContaining("efSearch must be >=");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, -1})
        @DisplayName("rejects invalid maxConnections values (too low)")
        void rejectsInvalidMaxConnectionsTooLow(int value) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswConfig(200, 100, value, 32))
                .withMessageContaining("maxConnections must be >=");
        }

        @Test
        @DisplayName("rejects maxConnections above limit")
        void rejectsMaxConnectionsAboveLimit() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswConfig(200, 100, 257, 32))
                .withMessageContaining("maxConnections must be <=");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, -1})
        @DisplayName("rejects invalid maxConnectionsLayer0 values")
        void rejectsInvalidMaxConnectionsLayer0(int value) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswConfig(200, 100, 16, value))
                .withMessageContaining("maxConnectionsLayer0 must be >=");
        }
    }

    @Nested
    @DisplayName("Configuration from Map")
    class FromMapTests {

        @Test
        @DisplayName("creates config from valid properties map")
        void createsFromValidMap() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "300");
            props.put("excalibur.vector.hnsw.ef-search", "150");
            props.put("excalibur.vector.hnsw.max-connections", "24");
            props.put("excalibur.vector.hnsw.max-connections-layer0", "48");

            HnswConfig config = HnswConfig.fromMap(props);

            assertThat(config.getEfConstruction()).isEqualTo(300);
            assertThat(config.getEfSearch()).isEqualTo(150);
            assertThat(config.getMaxConnections()).isEqualTo(24);
            assertThat(config.getMaxConnectionsLayer0()).isEqualTo(48);
        }

        @Test
        @DisplayName("uses defaults for missing properties")
        void usesDefaultsForMissingProperties() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "300");
            // Other properties missing

            HnswConfig config = HnswConfig.fromMap(props);

            assertThat(config.getEfConstruction()).isEqualTo(300);
            assertThat(config.getEfSearch()).isEqualTo(HnswConfig.DEFAULT_EF_SEARCH);
            assertThat(config.getMaxConnections()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS);
            assertThat(config.getMaxConnectionsLayer0()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER_0);
        }

        @Test
        @DisplayName("returns defaults for null map")
        void returnsDefaultsForNullMap() {
            HnswConfig config = HnswConfig.fromMap(null);

            assertThat(config.getEfConstruction()).isEqualTo(HnswConfig.DEFAULT_EF_CONSTRUCTION);
            assertThat(config.getEfSearch()).isEqualTo(HnswConfig.DEFAULT_EF_SEARCH);
        }

        @Test
        @DisplayName("handles invalid number format gracefully")
        void handlesInvalidNumberFormat() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "not-a-number");

            HnswConfig config = HnswConfig.fromMap(props);

            // Should fall back to default
            assertThat(config.getEfConstruction()).isEqualTo(HnswConfig.DEFAULT_EF_CONSTRUCTION);
        }

        @Test
        @DisplayName("handles empty string values")
        void handlesEmptyStringValues() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "");
            props.put("excalibur.vector.hnsw.ef-search", "   ");

            HnswConfig config = HnswConfig.fromMap(props);

            assertThat(config.getEfConstruction()).isEqualTo(HnswConfig.DEFAULT_EF_CONSTRUCTION);
            assertThat(config.getEfSearch()).isEqualTo(HnswConfig.DEFAULT_EF_SEARCH);
        }

        @Test
        @DisplayName("trims whitespace from values")
        void trimsWhitespace() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "  300  ");

            HnswConfig config = HnswConfig.fromMap(props);

            assertThat(config.getEfConstruction()).isEqualTo(300);
        }
    }

    @Nested
    @DisplayName("Effective efSearch")
    class EffectiveEfSearchTests {

        @Test
        @DisplayName("returns configured efSearch when larger than topK")
        void returnsConfiguredEfSearchWhenLarger() {
            HnswConfig config = new HnswConfig(200, 100, 16, 32);

            int effective = config.getEffectiveEfSearch(10);

            assertThat(effective).isEqualTo(100);
        }

        @Test
        @DisplayName("returns topK when larger than configured efSearch")
        void returnsTopKWhenLarger() {
            HnswConfig config = new HnswConfig(200, 50, 16, 32);

            int effective = config.getEffectiveEfSearch(100);

            assertThat(effective).isEqualTo(100);
        }

        @ParameterizedTest
        @CsvSource({
            "100, 10, 100",
            "50, 100, 100",
            "100, 100, 100",
            "10, 5, 10",
            "1, 10, 10"
        })
        @DisplayName("returns maximum of efSearch and topK")
        void returnsMaximum(int efSearch, int topK, int expected) {
            HnswConfig config = new HnswConfig(200, efSearch, 16, 32);

            assertThat(config.getEffectiveEfSearch(topK)).isEqualTo(expected);
        }

        @Test
        @DisplayName("handles edge case of topK=0 (returns at least 1)")
        void handlesTopKZero() {
            HnswConfig config = new HnswConfig(200, 100, 16, 32);

            int effective = config.getEffectiveEfSearch(0);

            // Should return at least 1
            assertThat(effective).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("handles negative topK gracefully")
        void handlesNegativeTopK() {
            HnswConfig config = new HnswConfig(200, 100, 16, 32);

            int effective = config.getEffectiveEfSearch(-5);

            // Should return at least 1
            assertThat(effective).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Wither Methods")
    class WitherTests {

        @Test
        @DisplayName("withEfConstruction creates new config with updated value")
        void withEfConstruction() {
            HnswConfig original = new HnswConfig(200, 100, 16, 32);

            HnswConfig modified = original.withEfConstruction(400);

            assertThat(original.getEfConstruction()).isEqualTo(200); // unchanged
            assertThat(modified.getEfConstruction()).isEqualTo(400);
            assertThat(modified.getEfSearch()).isEqualTo(100);
        }

        @Test
        @DisplayName("withEfSearch creates new config with updated value")
        void withEfSearch() {
            HnswConfig original = new HnswConfig(200, 100, 16, 32);

            HnswConfig modified = original.withEfSearch(200);

            assertThat(original.getEfSearch()).isEqualTo(100); // unchanged
            assertThat(modified.getEfSearch()).isEqualTo(200);
            assertThat(modified.getEfConstruction()).isEqualTo(200);
        }

        @Test
        @DisplayName("withMaxConnections creates new config with updated values")
        void withMaxConnections() {
            HnswConfig original = new HnswConfig(200, 100, 16, 32);

            HnswConfig modified = original.withMaxConnections(24, 48);

            assertThat(original.getMaxConnections()).isEqualTo(16); // unchanged
            assertThat(modified.getMaxConnections()).isEqualTo(24);
            assertThat(modified.getMaxConnectionsLayer0()).isEqualTo(48);
        }

        @Test
        @DisplayName("wither methods validate new values")
        void witherMethodsValidate() {
            HnswConfig original = new HnswConfig();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> original.withEfConstruction(0));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> original.withEfSearch(-1));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> original.withMaxConnections(1, 32));
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("config is immutable - original map changes don't affect config")
        void originalMapChangesDontAffectConfig() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.vector.hnsw.ef-construction", "200");

            HnswConfig config = HnswConfig.fromMap(props);

            // Modify original map
            props.put("excalibur.vector.hnsw.ef-construction", "9999");

            // Config should be unchanged
            assertThat(config.getEfConstruction()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Memory Estimation")
    class MemoryEstimationTests {

        @Test
        @DisplayName("estimates positive memory per node")
        void estimatesPositiveMemory() {
            HnswConfig config = new HnswConfig();

            long estimate = config.estimateMemoryPerNode();

            assertThat(estimate).isPositive();
        }

        @Test
        @DisplayName("higher connection count increases memory estimate")
        void higherConnectionsIncreaseMemory() {
            HnswConfig lowConfig = new HnswConfig(200, 100, 8, 16);
            HnswConfig highConfig = new HnswConfig(200, 100, 32, 64);

            assertThat(highConfig.estimateMemoryPerNode())
                .isGreaterThan(lowConfig.estimateMemoryPerNode());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("equal configs have equal hashCode")
        void equalConfigsHaveEqualHashCode() {
            HnswConfig config1 = new HnswConfig(200, 100, 16, 32);
            HnswConfig config2 = new HnswConfig(200, 100, 16, 32);

            assertThat(config1).isEqualTo(config2);
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }

        @Test
        @DisplayName("different configs are not equal")
        void differentConfigsNotEqual() {
            HnswConfig config1 = new HnswConfig(200, 100, 16, 32);
            HnswConfig config2 = new HnswConfig(300, 100, 16, 32);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("toString contains all parameters")
        void toStringContainsAllParameters() {
            HnswConfig config = new HnswConfig(200, 100, 16, 32);

            String str = config.toString();

            assertThat(str).contains("efConstruction=200");
            assertThat(str).contains("efSearch=100");
            assertThat(str).contains("maxConnections=16");
            assertThat(str).contains("maxConnectionsLayer0=32");
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("all getters return values in documented ranges")
        void allValuesInDocumentedRanges() {
            HnswConfig config = new HnswConfig();

            // efConstruction: typically [50, 500]
            assertThat(config.getEfConstruction()).isBetween(50, 500);

            // efSearch: typically [10, 500]
            assertThat(config.getEfSearch()).isBetween(10, 500);

            // maxConnections: [2, 256]
            assertThat(config.getMaxConnections()).isBetween(2, 256);

            // maxConnectionsLayer0: positive
            assertThat(config.getMaxConnectionsLayer0()).isPositive();
        }

        @Test
        @DisplayName("getEffectiveEfSearch always returns positive value")
        void effectiveEfSearchAlwaysPositive() {
            HnswConfig config = new HnswConfig(1, 1, 2, 2);

            assertThat(config.getEffectiveEfSearch(0)).isPositive();
            assertThat(config.getEffectiveEfSearch(-1)).isPositive();
            assertThat(config.getEffectiveEfSearch(1)).isPositive();
            assertThat(config.getEffectiveEfSearch(1000)).isPositive();
        }
    }
}
