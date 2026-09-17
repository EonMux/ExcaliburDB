package io.excalibur.vector.hnsw;

import io.excalibur.config.Config;
import io.excalibur.config.ShardConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswConfig}.
 */
class HnswConfigTest {

    @Test
    void shouldCreateDefaultConfig() {
        HnswConfig config = HnswConfig.create(128);

        assertThat(config.dimension()).isEqualTo(128);
        assertThat(config.maxConnections()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS);
        assertThat(config.maxConnectionsLayer0()).isEqualTo(HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0);
        assertThat(config.efConstruction()).isEqualTo(HnswConfig.DEFAULT_EF_CONSTRUCTION);
        assertThat(config.efSearch()).isEqualTo(HnswConfig.DEFAULT_EF_SEARCH);
    }

    @Test
    void shouldCreateConfigWithCustomEfSearch() {
        HnswConfig config = HnswConfig.create(256, 50);

        assertThat(config.dimension()).isEqualTo(256);
        assertThat(config.efSearch()).isEqualTo(50);
    }

    @Test
    void shouldCreateCustomConfig() {
        HnswConfig config = new HnswConfig(
            32, 64, 100, 50, 0.5, 512
        );

        assertThat(config.maxConnections()).isEqualTo(32);
        assertThat(config.maxConnectionsLayer0()).isEqualTo(64);
        assertThat(config.efConstruction()).isEqualTo(100);
        assertThat(config.efSearch()).isEqualTo(50);
        assertThat(config.mL()).isEqualTo(0.5);
        assertThat(config.dimension()).isEqualTo(512);
    }

    @Test
    void shouldRejectInvalidMaxConnections() {
        assertThatThrownBy(() -> new HnswConfig(0, 32, 100, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HnswConfig(257, 512, 100, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMaxConnectionsLayer0() {
        // Must be >= maxConnections
        assertThatThrownBy(() -> new HnswConfig(16, 15, 100, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HnswConfig(16, 513, 100, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidEfConstruction() {
        assertThatThrownBy(() -> new HnswConfig(16, 32, 0, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);

        // The ceiling is HnswConfig.MAX_EF, aligned with the range ConfigValidation
        // accepts for shard.default-ef-search so a valid config cannot fail later.
        assertThatThrownBy(() -> new HnswConfig(16, 32, HnswConfig.MAX_EF + 1, 50, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidEfSearch() {
        assertThatThrownBy(() -> new HnswConfig(16, 32, 100, 0, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HnswConfig(16, 32, 100, HnswConfig.MAX_EF + 1, 0.5, 128))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidML() {
        assertThatThrownBy(() -> new HnswConfig(16, 32, 100, 50, 0.0, 128))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HnswConfig(16, 32, 100, 50, 1.5, 128))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDimension() {
        assertThatThrownBy(() -> HnswConfig.create(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HnswConfig.create(HnswConfig.MAX_DIMENSION + 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateWithEfSearch() {
        HnswConfig original = HnswConfig.create(128);
        HnswConfig modified = original.withEfSearch(200);

        assertThat(modified.efSearch()).isEqualTo(200);
        assertThat(modified.dimension()).isEqualTo(original.dimension());
        assertThat(modified.maxConnections()).isEqualTo(original.maxConnections());
    }

    @Test
    void shouldCalculateByteSizes() {
        HnswConfig config = HnswConfig.create(128);

        assertThat(config.vectorByteSize()).isEqualTo(128 * Float.BYTES);
        assertThat(config.adjacencyByteSize()).isEqualTo(config.maxConnections() * Integer.BYTES);
        assertThat(config.adjacencyByteSizeLayer0()).isEqualTo(config.maxConnectionsLayer0() * Integer.BYTES);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        HnswConfig config1 = HnswConfig.create(128);
        HnswConfig config2 = HnswConfig.create(128);
        HnswConfig config3 = HnswConfig.create(256);

        assertThat(config1).isEqualTo(config2);
        assertThat(config1).isNotEqualTo(config3);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void shouldImplementToString() {
        HnswConfig config = HnswConfig.create(128);
        String str = config.toString();

        assertThat(str).contains("dimension=128");
        assertThat(str).contains("maxConnections=");
        assertThat(str).contains("efConstruction=");
    }

    @Test
    void shouldReadConnectivityFromShardConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-max-connections", "32");
        props.put("excalibur.shard.default-max-connections-layer0", "96");

        Config config = new Config("test", Map.of(), props);
        HnswConfig hnsw = HnswConfig.fromShardConfig(256, config.shard());

        assertThat(hnsw.maxConnections()).isEqualTo(32);
        assertThat(hnsw.maxConnectionsLayer0()).isEqualTo(96);
        assertThat(hnsw.mL()).isEqualTo(HnswConfig.mLFor(32));
    }

    @Test
    void shouldUseShardDefaultsForConnectivityWhenUnset() {
        Config config = new Config("test", Map.of(), new HashMap<>());
        HnswConfig hnsw = HnswConfig.fromShardConfig(256, config.shard());

        assertThat(hnsw.maxConnections()).isEqualTo(ShardConfig.DEFAULT_MAX_CONNECTIONS);
        assertThat(hnsw.maxConnectionsLayer0())
            .isEqualTo(2 * ShardConfig.DEFAULT_MAX_CONNECTIONS);
    }

    @Test
    void shouldDeriveMlFromMaxConnections() {
        assertThat(HnswConfig.mLFor(HnswConfig.DEFAULT_MAX_CONNECTIONS))
            .isEqualTo(HnswConfig.DEFAULT_ML);
        assertThat(HnswConfig.mLFor(32)).isEqualTo(1.0 / Math.log(32));

        // At M=2 the reciprocal logarithm exceeds the (0, 1] range mL is defined over,
        // so the smallest permitted M still has to yield a usable factor.
        assertThat(HnswConfig.mLFor(HnswConfig.MIN_CONNECTIONS)).isEqualTo(1.0);
        assertThat(new HnswConfig(2, 4, 64, 64, HnswConfig.mLFor(2), 8).mL()).isEqualTo(1.0);
    }
}
