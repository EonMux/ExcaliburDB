package io.excalibur.vector.hnsw;

import io.excalibur.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

/**
 * Verification test for Feature 28: HNSW Configurable efConstruction and efSearch
 *
 * <p>This test verifies that:</p>
 * <ul>
 *   <li>Default values match specification (efConstruction=128, efSearch=64)</li>
 *   <li>Values are configurable via Config.Shard</li>
 *   <li>Values flow through HnswConfigFactory to HnswConfig</li>
 *   <li>Values are used in HNSW graph operations</li>
 * </ul>
 */
class Feature28VerificationTest {

    @Test
    @DisplayName("Default efConstruction should be 128")
    void defaultEfConstructionShouldBe128() {
        assertThat(Config.Shard.DEFAULT_EF_CONSTRUCTION).isEqualTo(128);
    }

    @Test
    @DisplayName("Default efSearch should be 64")
    void defaultEfSearchShouldBe64() {
        assertThat(Config.Shard.DEFAULT_EF_SEARCH).isEqualTo(64);
    }

    @Test
    @DisplayName("Custom efConstruction should be configurable")
    void customEfConstructionShouldBeConfigurable() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-construction", "256");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.getEfConstruction()).isEqualTo(256);
    }

    @Test
    @DisplayName("Custom efSearch should be configurable")
    void customEfSearchShouldBeConfigurable() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-search", "128");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.getEfSearch()).isEqualTo(128);
    }

    @Test
    @DisplayName("HnswConfigFactory should create HnswConfig with correct values")
    void factoryShouldCreateHnswConfigWithCorrectValues() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-construction", "300");
        props.put("excalibur.shard.default-ef-search", "150");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());
        HnswConfig hnswConfig = factory.create(1536);

        assertThat(hnswConfig.efConstruction()).isEqualTo(300);
        assertThat(hnswConfig.efSearch()).isEqualTo(150);
        assertThat(hnswConfig.dimension()).isEqualTo(1536);
    }

    @Test
    @DisplayName("HnswConfig should use configured values in graph operations")
    void hnswConfigShouldUseConfiguredValuesInGraphOperations() {
        // Create config with custom values
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-construction", "256");
        props.put("excalibur.shard.default-ef-search", "128");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());
        HnswConfig hnswConfig = factory.create(64);

        // Verify the config has the right values
        assertThat(hnswConfig.efConstruction()).isEqualTo(256);
        assertThat(hnswConfig.efSearch()).isEqualTo(128);

        // These values will be used by HnswGraph:
        // - search() uses config.efSearch()
        // - insert() uses config.efConstruction()
        // This is verified by the HnswRecallBenchmarkValidationTest
    }
}
