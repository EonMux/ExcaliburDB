package com.eonmux.excalibur.filter.lsh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LshConfig class.
 */
@DisplayName("LshConfig Tests")
class LshConfigTest {

    @Nested
    @DisplayName("Constants Tests")
    class ConstantsTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaults() {
            assertThat(LshConfig.DEFAULT_NUM_HASH_TABLES).isEqualTo(15);
            assertThat(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS).isEqualTo(16);
            assertThat(LshConfig.DEFAULT_NUM_PROBES).isEqualTo(3);
        }

        @Test
        @DisplayName("Should have correct bounds")
        void shouldHaveCorrectBounds() {
            assertThat(LshConfig.MAX_HASH_FUNCTIONS).isEqualTo(63);
            assertThat(LshConfig.MIN_HASH_FUNCTIONS).isEqualTo(1);
            assertThat(LshConfig.MIN_DIMENSIONS).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create config with dimensions only")
        void shouldCreateWithDimensionsOnly() {
            LshConfig config = new LshConfig(128);

            assertThat(config.dimensions()).isEqualTo(128);
            assertThat(config.numHashTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
            assertThat(config.numHashFunctions()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS);
            assertThat(config.numProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
        }

        @Test
        @DisplayName("Should create config with all parameters")
        void shouldCreateWithAllParameters() {
            LshConfig config = new LshConfig(768, 10, 12, 42L, 5);

            assertThat(config.dimensions()).isEqualTo(768);
            assertThat(config.numHashTables()).isEqualTo(10);
            assertThat(config.numHashFunctions()).isEqualTo(12);
            assertThat(config.seed()).isEqualTo(42L);
            assertThat(config.numProbes()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should reject dimensions less than minimum")
        void shouldRejectInvalidDimensions() {
            assertThatThrownBy(() -> new LshConfig(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must be >= " + LshConfig.MIN_DIMENSIONS);

            assertThatThrownBy(() -> new LshConfig(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must be >= " + LshConfig.MIN_DIMENSIONS);
        }

        @Test
        @DisplayName("Should reject zero hash tables")
        void shouldRejectZeroHashTables() {
            assertThatThrownBy(() -> new LshConfig(128, 0, 12, 42L, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashTables must be >= 1");
        }

        @Test
        @DisplayName("Should reject hash functions below minimum")
        void shouldRejectHashFunctionsBelowMin() {
            assertThatThrownBy(() -> new LshConfig(128, 10, 0, 42L, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashFunctions must be in range");
        }

        @Test
        @DisplayName("Should reject hash functions above maximum")
        void shouldRejectHashFunctionsAboveMax() {
            assertThatThrownBy(() -> new LshConfig(128, 10, 64, 42L, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashFunctions must be in range");
        }

        @Test
        @DisplayName("Should accept minimum hash functions")
        void shouldAcceptMinHashFunctions() {
            LshConfig config = new LshConfig(128, 10, LshConfig.MIN_HASH_FUNCTIONS, 42L, 3);
            assertThat(config.numHashFunctions()).isEqualTo(LshConfig.MIN_HASH_FUNCTIONS);
        }

        @Test
        @DisplayName("Should accept maximum hash functions")
        void shouldAcceptMaxHashFunctions() {
            LshConfig config = new LshConfig(128, 10, LshConfig.MAX_HASH_FUNCTIONS, 42L, 3);
            assertThat(config.numHashFunctions()).isEqualTo(LshConfig.MAX_HASH_FUNCTIONS);
        }

        @Test
        @DisplayName("Should reject zero probes")
        void shouldRejectZeroProbes() {
            assertThatThrownBy(() -> new LshConfig(128, 10, 12, 42L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numProbes must be >= 1");
        }
    }

    @Nested
    @DisplayName("Static Factory Tests")
    class StaticFactoryTests {

        @Test
        @DisplayName("Should create with static factory method")
        void shouldCreateWithStaticFactory() {
            LshConfig config = LshConfig.create(256, 12, 14, 4);

            assertThat(config.dimensions()).isEqualTo(256);
            assertThat(config.numHashTables()).isEqualTo(12);
            assertThat(config.numHashFunctions()).isEqualTo(14);
            assertThat(config.numProbes()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should create with seed using static factory")
        void shouldCreateWithSeedStaticFactory() {
            LshConfig config = LshConfig.createWithSeed(256, 12, 14, 999L);

            assertThat(config.dimensions()).isEqualTo(256);
            assertThat(config.numHashTables()).isEqualTo(12);
            assertThat(config.numHashFunctions()).isEqualTo(14);
            assertThat(config.seed()).isEqualTo(999L);
            assertThat(config.numProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build config with defaults")
        void shouldBuildWithDefaults() {
            LshConfig config = LshConfig.builder(128).build();

            assertThat(config.dimensions()).isEqualTo(128);
            assertThat(config.numHashTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
            assertThat(config.numHashFunctions()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS);
            assertThat(config.numProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
        }

        @Test
        @DisplayName("Should build config with custom values")
        void shouldBuildWithCustomValues() {
            LshConfig config = LshConfig.builder(768)
                .numHashTables(20)
                .numHashFunctions(18)
                .seed(12345L)
                .numProbes(7)
                .build();

            assertThat(config.dimensions()).isEqualTo(768);
            assertThat(config.numHashTables()).isEqualTo(20);
            assertThat(config.numHashFunctions()).isEqualTo(18);
            assertThat(config.seed()).isEqualTo(12345L);
            assertThat(config.numProbes()).isEqualTo(7);
        }

        @Test
        @DisplayName("Should reject invalid dimensions in builder")
        void shouldRejectInvalidDimensionsInBuilder() {
            assertThatThrownBy(() -> LshConfig.builder(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must be >= " + LshConfig.MIN_DIMENSIONS);
        }

        @Test
        @DisplayName("Should reject invalid numHashTables in builder")
        void shouldRejectInvalidNumHashTablesInBuilder() {
            assertThatThrownBy(() -> LshConfig.builder(128).numHashTables(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashTables must be >= 1");
        }

        @Test
        @DisplayName("Should reject invalid numHashFunctions in builder")
        void shouldRejectInvalidNumHashFunctionsInBuilder() {
            assertThatThrownBy(() -> LshConfig.builder(128).numHashFunctions(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashFunctions must be in range");

            assertThatThrownBy(() -> LshConfig.builder(128).numHashFunctions(64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashFunctions must be in range");
        }

        @Test
        @DisplayName("Should reject invalid numProbes in builder")
        void shouldRejectInvalidNumProbesInBuilder() {
            assertThatThrownBy(() -> LshConfig.builder(128).numProbes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numProbes must be >= 1");
        }
    }

    @Nested
    @DisplayName("Memory Estimation Tests")
    class MemoryEstimationTests {

        @Test
        @DisplayName("Should estimate hyperplane memory correctly")
        void shouldEstimateHyperplaneMemory() {
            LshConfig config = new LshConfig(768, 15, 16, 42L, 3);

            // Expected: 15 tables * 16 functions * 768 dimensions * 4 bytes (float)
            long expected = 15L * 16 * 768 * Float.BYTES;

            assertThat(config.estimatedHyperplaneMemory()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should estimate small config memory correctly")
        void shouldEstimateSmallConfigMemory() {
            LshConfig config = new LshConfig(128, 5, 8, 42L, 3);

            // Expected: 5 tables * 8 functions * 128 dimensions * 4 bytes
            long expected = 5L * 8 * 128 * Float.BYTES;

            assertThat(config.estimatedHyperplaneMemory()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(128, 10, 12, 42L, 3);

            assertThat(config1).isEqualTo(config2);
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different dimensions")
        void shouldNotBeEqualForDifferentDimensions() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(256, 10, 12, 42L, 3);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("Should not be equal for different hash tables")
        void shouldNotBeEqualForDifferentHashTables() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(128, 11, 12, 42L, 3);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("Should not be equal for different hash functions")
        void shouldNotBeEqualForDifferentHashFunctions() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(128, 10, 13, 42L, 3);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("Should not be equal for different seeds")
        void shouldNotBeEqualForDifferentSeeds() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(128, 10, 12, 43L, 3);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("Should not be equal for different probes")
        void shouldNotBeEqualForDifferentProbes() {
            LshConfig config1 = new LshConfig(128, 10, 12, 42L, 3);
            LshConfig config2 = new LshConfig(128, 10, 12, 42L, 4);

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        @DisplayName("Should be same instance")
        void shouldBeSameInstance() {
            LshConfig config = new LshConfig(128, 10, 12, 42L, 3);
            assertThat(config).isEqualTo(config);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            LshConfig config = new LshConfig(128, 10, 12, 42L, 3);
            assertThat(config).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different class")
        void shouldNotBeEqualToDifferentClass() {
            LshConfig config = new LshConfig(128, 10, 12, 42L, 3);
            assertThat(config).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should contain all fields in toString")
        void shouldContainAllFields() {
            LshConfig config = new LshConfig(768, 15, 16, 42L, 3);
            String str = config.toString();

            assertThat(str).contains("768");
            assertThat(str).contains("15");
            assertThat(str).contains("16");
            assertThat(str).contains("42");
            assertThat(str).contains("3");
            assertThat(str).contains("LshConfig");
        }
    }
}
