package io.excalibur.vector.hnsw;

import io.excalibur.config.Config;
import io.excalibur.config.ShardConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswConfigFactory}.
 *
 * <p>Tests verify that the factory correctly wires ShardConfig settings
 * to HnswConfig instances, ensuring property file values for efConstruction
 * and efSearch are applied to HNSW graphs.</p>
 */
class HnswConfigFactoryTest {

    @Test
    void shouldCreateFactoryWithDefaults() {
        HnswConfigFactory factory = HnswConfigFactory.withDefaults();

        assertThat(factory.getEfConstruction()).isEqualTo(ShardConfig.DEFAULT_EF_CONSTRUCTION);
        assertThat(factory.getEfSearch()).isEqualTo(ShardConfig.DEFAULT_EF_SEARCH);
    }

    @Test
    void shouldCreateFactoryFromShardConfig() {
        // Create config with custom efConstruction and efSearch
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-construction", "300");
        props.put("excalibur.shard.default-ef-search", "150");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.getEfConstruction()).isEqualTo(300);
        assertThat(factory.getEfSearch()).isEqualTo(150);
    }

    @Test
    void shouldCreateFactoryWithCustomValues() {
        HnswConfigFactory factory = new HnswConfigFactory(400, 200);

        assertThat(factory.getEfConstruction()).isEqualTo(400);
        assertThat(factory.getEfSearch()).isEqualTo(200);
    }

    @Test
    void shouldCreateHnswConfigWithCorrectParameters() {
        HnswConfigFactory factory = new HnswConfigFactory(250, 125);
        HnswConfig config = factory.create(1536);

        assertThat(config.dimension()).isEqualTo(1536);
        assertThat(config.efConstruction()).isEqualTo(250);
        assertThat(config.efSearch()).isEqualTo(125);
        // Verify defaults are preserved for other parameters
        assertThat(config.maxConnections()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS);
        assertThat(config.maxConnectionsLayer0()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0);
        assertThat(config.mL()).isEqualTo(HnswConfig.DEFAULT_ML);
    }

    @Test
    void shouldCreateHnswConfigWithCustomEfConstruction() {
        HnswConfigFactory factory = new HnswConfigFactory(200, 100);
        HnswConfig config = factory.createWithEfConstruction(768, 500);

        assertThat(config.dimension()).isEqualTo(768);
        assertThat(config.efConstruction()).isEqualTo(500);
        assertThat(config.efSearch()).isEqualTo(100); // Uses factory's efSearch
    }

    @Test
    void shouldRejectInvalidEfConstruction() {
        // Factory only validates minimum; upper bound is validated by HnswConfig
        assertThatThrownBy(() -> new HnswConfigFactory(0, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("efConstruction must be at least 1");
    }

    @Test
    void shouldRejectInvalidEfSearch() {
        // Factory only validates minimum; upper bound is validated by HnswConfig
        assertThatThrownBy(() -> new HnswConfigFactory(200, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("efSearch must be at least 1");
    }

    @Test
    void shouldRejectInvalidCustomEfConstruction() {
        HnswConfigFactory factory = HnswConfigFactory.withDefaults();

        assertThatThrownBy(() -> factory.createWithEfConstruction(128, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customEfConstruction must be at least 1");
    }

    @Test
    void shouldRejectNullShardConfig() {
        assertThatThrownBy(() -> HnswConfigFactory.fromShardConfig(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("shard config cannot be null");
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        HnswConfigFactory factory1 = new HnswConfigFactory(200, 100);
        HnswConfigFactory factory2 = new HnswConfigFactory(200, 100);
        HnswConfigFactory factory3 = new HnswConfigFactory(300, 150);

        assertThat(factory1).isEqualTo(factory2);
        assertThat(factory1).isNotEqualTo(factory3);
        assertThat(factory1.hashCode()).isEqualTo(factory2.hashCode());
    }

    @Test
    void shouldImplementToString() {
        HnswConfigFactory factory = new HnswConfigFactory(250, 125);
        String str = factory.toString();

        assertThat(str).contains("efConstruction=250");
        assertThat(str).contains("efSearch=125");
    }

    @Test
    void shouldUseConfigShardDefaults() {
        // Create config with no overrides - should use defaults
        Config config = new Config("test", Map.of(), new HashMap<>());
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        // ShardConfig defaults: efConstruction=128, efSearch=64
        assertThat(factory.getEfConstruction()).isEqualTo(ShardConfig.DEFAULT_EF_CONSTRUCTION);
        assertThat(factory.getEfSearch()).isEqualTo(ShardConfig.DEFAULT_EF_SEARCH);
    }

    @Test
    void shouldReadConnectivityFromShardConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-max-connections", "24");
        props.put("excalibur.shard.default-max-connections-layer0", "64");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.getMaxConnections()).isEqualTo(24);
        assertThat(factory.getMaxConnectionsLayer0()).isEqualTo(64);

        HnswConfig created = factory.create(128);
        assertThat(created.maxConnections()).isEqualTo(24);
        assertThat(created.maxConnectionsLayer0()).isEqualTo(64);
        assertThat(created.mL()).isEqualTo(HnswConfig.mLFor(24));
    }

    @Test
    void shouldResolveLayer0ToTwiceMaxConnectionsWhenUnset() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-max-connections", "20");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.getMaxConnectionsLayer0()).isEqualTo(40);
        assertThat(factory.create(64).maxConnectionsLayer0()).isEqualTo(40);
    }

    @Test
    void shouldCarryConnectivityThroughTheEfOverrides() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-max-connections", "12");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        assertThat(factory.createWithEfSearch(64, 99).maxConnections()).isEqualTo(12);
        assertThat(factory.createWithEfSearch(64, 99).maxConnectionsLayer0()).isEqualTo(24);
        assertThat(factory.createWithEfConstruction(64, 99).maxConnections()).isEqualTo(12);
        assertThat(factory.createWithEfConstruction(64, 99).maxConnectionsLayer0()).isEqualTo(24);
    }

    @Test
    void shouldKeepTheHnswDefaultsWhenConnectivityIsNotGiven() {
        HnswConfigFactory factory = new HnswConfigFactory(200, 100);

        assertThat(factory.getMaxConnections()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS);
        assertThat(factory.getMaxConnectionsLayer0())
            .isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0);
    }

    @Test
    void shouldProduceConsistentConfigs() {
        // Verify that the factory produces configs that are equivalent to
        // HnswConfig.fromShardConfig()
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-ef-construction", "350");
        props.put("excalibur.shard.default-ef-search", "175");

        Config config = new Config("test", Map.of(), props);
        HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());

        HnswConfig factoryConfig = factory.create(768);
        HnswConfig directConfig = HnswConfig.fromShardConfig(768, config.shard());

        // Both should produce equivalent configs
        assertThat(factoryConfig.efConstruction()).isEqualTo(directConfig.efConstruction());
        assertThat(factoryConfig.efSearch()).isEqualTo(directConfig.efSearch());
        assertThat(factoryConfig.dimension()).isEqualTo(directConfig.dimension());
        assertThat(factoryConfig.maxConnections()).isEqualTo(directConfig.maxConnections());
        assertThat(factoryConfig.maxConnectionsLayer0()).isEqualTo(directConfig.maxConnectionsLayer0());
        assertThat(factoryConfig.mL()).isEqualTo(directConfig.mL());
    }
}
