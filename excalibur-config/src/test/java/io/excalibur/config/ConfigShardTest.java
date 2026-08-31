package io.excalibur.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Config.Shard configuration class.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Default values are correct per specification</li>
 *   <li>Custom values are parsed from properties</li>
 *   <li>Domain validation rejects invalid values</li>
 *   <li>ConfigValidation enforces range constraints</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class ConfigShardTest {

    // ==================== Default Values Tests ====================

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTests {

        @Test
        @DisplayName("loadFactor defaults to 0.7")
        void loadFactor_defaultValue() {
            Config config = new Config(new HashMap<>());
            assertEquals(0.7, config.shard().getLoadFactor(), 0.001);
        }

        @Test
        @DisplayName("efConstruction defaults to 128")
        void efConstruction_defaultValue() {
            Config config = new Config(new HashMap<>());
            assertEquals(128, config.shard().getEfConstruction());
        }

        @Test
        @DisplayName("efSearch defaults to 64")
        void efSearch_defaultValue() {
            Config config = new Config(new HashMap<>());
            assertEquals(64, config.shard().getEfSearch());
        }

        @Test
        @DisplayName("compactDelayMs defaults to 10000")
        void compactDelayMs_defaultValue() {
            Config config = new Config(new HashMap<>());
            assertEquals(10000, config.shard().getCompactDelayMs());
        }

        @Test
        @DisplayName("hnswQueueLimit defaults to 100000")
        void hnswQueueLimit_defaultValue() {
            Config config = new Config(new HashMap<>());
            assertEquals(100000, config.shard().getHnswQueueLimit());
        }
    }

    // ==================== Custom Values Tests ====================

    @Nested
    @DisplayName("Custom Values from Properties")
    class CustomValuesTests {

        @Test
        @DisplayName("loadFactor can be customized via excalibur.shard.segment-load-factor")
        void loadFactor_customValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "0.8");
            Config config = new Config(props);
            assertEquals(0.8, config.shard().getLoadFactor(), 0.001);
        }

        @Test
        @DisplayName("efConstruction can be customized via excalibur.shard.default-ef-construction")
        void efConstruction_customValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-construction", "256");
            Config config = new Config(props);
            assertEquals(256, config.shard().getEfConstruction());
        }

        @Test
        @DisplayName("efSearch can be customized via excalibur.shard.default-ef-search")
        void efSearch_customValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-search", "128");
            Config config = new Config(props);
            assertEquals(128, config.shard().getEfSearch());
        }

        @Test
        @DisplayName("compactDelayMs can be customized via excalibur.shard.compaction-delay-ms")
        void compactDelayMs_customValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.compaction-delay-ms", "5000");
            Config config = new Config(props);
            assertEquals(5000, config.shard().getCompactDelayMs());
        }

        @Test
        @DisplayName("hnswQueueLimit can be customized via excalibur.shard.hnsw-integration-queue-depth-limit")
        void hnswQueueLimit_customValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.hnsw-integration-queue-depth-limit", "200000");
            Config config = new Config(props);
            assertEquals(200000, config.shard().getHnswQueueLimit());
        }

        @Test
        @DisplayName("All shard properties can be set simultaneously")
        void allProperties_customValues() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "0.65");
            props.put("excalibur.shard.default-ef-construction", "300");
            props.put("excalibur.shard.default-ef-search", "150");
            props.put("excalibur.shard.compaction-delay-ms", "15000");
            props.put("excalibur.shard.hnsw-integration-queue-depth-limit", "50000");

            Config config = new Config(props);

            assertEquals(0.65, config.shard().getLoadFactor(), 0.001);
            assertEquals(300, config.shard().getEfConstruction());
            assertEquals(150, config.shard().getEfSearch());
            assertEquals(15000, config.shard().getCompactDelayMs());
            assertEquals(50000, config.shard().getHnswQueueLimit());
        }
    }

    // ==================== Domain Validation Tests ====================

    @Nested
    @DisplayName("Domain Validation")
    class DomainValidationTests {

        @Test
        @DisplayName("loadFactor <= 0 is rejected")
        void loadFactor_rejectsZero() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "0");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("loadFactor < 0 is rejected")
        void loadFactor_rejectsNegative() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "-0.5");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("loadFactor > 1.0 is rejected")
        void loadFactor_rejectsGreaterThanOne() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "1.5");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("loadFactor = 1.0 is accepted (boundary)")
        void loadFactor_acceptsOne() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "1.0");
            Config config = new Config(props);
            assertEquals(1.0, config.shard().getLoadFactor(), 0.001);
        }

        @Test
        @DisplayName("loadFactor with unparseable value is rejected")
        void loadFactor_invalidValue_isRejected() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "not-a-number");
            // Falling back to the default would make a typo indistinguishable from
            // correct configuration, so the misconfiguration is reported instead
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("loadFactor with blank value falls back to default")
        void loadFactor_blankValue_fallsBackToDefault() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "  ");
            Config config = new Config(props);
            assertEquals(0.7, config.shard().getLoadFactor(), 0.001);
        }

        @Test
        @DisplayName("efConstruction < 1 is rejected")
        void efConstruction_rejectsZero() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-construction", "0");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("efConstruction = 1 is accepted (minimum)")
        void efConstruction_acceptsMinimum() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-construction", "1");
            Config config = new Config(props);
            assertEquals(1, config.shard().getEfConstruction());
        }

        @Test
        @DisplayName("efSearch < 1 is rejected")
        void efSearch_rejectsZero() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-search", "0");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("efSearch = 1 is accepted (minimum)")
        void efSearch_acceptsMinimum() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-search", "1");
            Config config = new Config(props);
            assertEquals(1, config.shard().getEfSearch());
        }

        @Test
        @DisplayName("compactDelayMs < 0 is rejected")
        void compactDelayMs_rejectsNegative() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.compaction-delay-ms", "-100");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("compactDelayMs = 0 is accepted (immediate compaction)")
        void compactDelayMs_acceptsZero() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.compaction-delay-ms", "0");
            Config config = new Config(props);
            assertEquals(0, config.shard().getCompactDelayMs());
        }

        @Test
        @DisplayName("hnswQueueLimit < 1 is rejected")
        void hnswQueueLimit_rejectsZero() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.hnsw-integration-queue-depth-limit", "0");
            assertThrows(IllegalArgumentException.class, () -> new Config(props));
        }

        @Test
        @DisplayName("hnswQueueLimit = 1 is accepted (minimum)")
        void hnswQueueLimit_acceptsMinimum() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.hnsw-integration-queue-depth-limit", "1");
            Config config = new Config(props);
            assertEquals(1, config.shard().getHnswQueueLimit());
        }
    }

    // ==================== Immutability Tests ====================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Modifying props map after Config creation does not affect Shard values")
        void immutability_propsModification() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "0.75");
            Config config = new Config(props);

            // Modify the original map
            props.put("excalibur.shard.segment-load-factor", "0.9");
            props.clear();

            // Config should still have original value
            assertEquals(0.75, config.shard().getLoadFactor(), 0.001);
        }

        @Test
        @DisplayName("Shard toString contains all values")
        void toString_containsAllValues() {
            Config config = new Config(new HashMap<>());
            String str = config.shard().toString();

            assertTrue(str.contains("loadFactor=0.7"));
            assertTrue(str.contains("efConstruction=128"));
            assertTrue(str.contains("efSearch=64"));
            assertTrue(str.contains("compactDelayMs=10000"));
            assertTrue(str.contains("hnswQueueLimit=100000"));
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Very large efConstruction is accepted")
        void efConstruction_acceptsLargeValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.default-ef-construction", "1000");
            Config config = new Config(props);
            assertEquals(1000, config.shard().getEfConstruction());
        }

        @Test
        @DisplayName("Very large hnswQueueLimit is accepted")
        void hnswQueueLimit_acceptsLargeValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.hnsw-integration-queue-depth-limit", "10000000");
            Config config = new Config(props);
            assertEquals(10000000, config.shard().getHnswQueueLimit());
        }

        @Test
        @DisplayName("Very small loadFactor is accepted (near zero)")
        void loadFactor_acceptsSmallValue() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", "0.001");
            Config config = new Config(props);
            assertEquals(0.001, config.shard().getLoadFactor(), 0.0001);
        }

        @Test
        @DisplayName("Whitespace in property values is trimmed")
        void whitespace_isTrimmed() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.shard.segment-load-factor", " 0.8 ");
            props.put("excalibur.shard.default-ef-construction", " 256 ");
            Config config = new Config(props);
            assertEquals(0.8, config.shard().getLoadFactor(), 0.001);
            assertEquals(256, config.shard().getEfConstruction());
        }
    }
}
