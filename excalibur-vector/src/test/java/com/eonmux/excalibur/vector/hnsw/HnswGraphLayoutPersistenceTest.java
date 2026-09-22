package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a configured connection bound reaches new graphs while a graph already
 * on disk keeps the bound it was built with.
 *
 * <p>{@code maxConnections} fixes the size of every adjacency list in the file, so it
 * cannot be changed under an existing graph without misparsing every node. Raising it
 * in the configuration is therefore a statement about graphs not yet created.</p>
 */
@DisplayName("HNSW connection bounds and the persisted layout")
class HnswGraphLayoutPersistenceTest {

    private static final int DIMENSION = 8;
    private static final long MAX_NODES = 128;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A configured bound applies to a newly created graph")
    void configuredBoundAppliesToNewGraphs() throws IOException {
        HnswConfig config = HnswConfig.fromShardConfig(DIMENSION, shardWithMaxConnections(24, 64));
        Path graphPath = tempDir.resolve("new.hnsw");

        try (HnswGraph graph = new HnswGraph(graphPath, config, CosineSimilarity.getInstance(), MAX_NODES)) {
            graph.insert(vector(1));
            graph.flush();
            assertThat(graph.config().maxConnections()).isEqualTo(24);
            assertThat(graph.config().maxConnectionsLayer0()).isEqualTo(64);
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, CosineSimilarity.getInstance())) {
            assertThat(reopened.config().maxConnections()).isEqualTo(24);
            assertThat(reopened.config().maxConnectionsLayer0()).isEqualTo(64);
        }
    }

    @Test
    @DisplayName("An existing graph keeps the bound it was built with")
    void existingGraphKeepsItsPersistedBound() throws IOException {
        Path graphPath = tempDir.resolve("existing.hnsw");
        HnswConfig built = HnswConfig.fromShardConfig(DIMENSION, shardWithMaxConnections(16, 32));

        try (HnswGraph graph = new HnswGraph(graphPath, built, CosineSimilarity.getInstance(), MAX_NODES)) {
            graph.insert(vector(1));
            graph.insert(vector(2));
            graph.flush();
        }

        HnswConfig widened = HnswConfig.fromShardConfig(DIMENSION, shardWithMaxConnections(48, 96));
        try (HnswGraph reattached =
                 new HnswGraph(graphPath, widened, CosineSimilarity.getInstance(), MAX_NODES)) {
            assertThat(reattached.config().maxConnections()).isEqualTo(16);
            assertThat(reattached.config().maxConnectionsLayer0()).isEqualTo(32);
            assertThat(reattached.nodeCount()).isEqualTo(2);
        }
    }

    private static ShardConfig shardWithMaxConnections(int maxConnections, int layer0) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.default-max-connections", Integer.toString(maxConnections));
        props.put("excalibur.shard.default-max-connections-layer0", Integer.toString(layer0));
        return new Config("test", Map.of(), props).shard();
    }

    private static float[] vector(int seed) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = (seed + 1) * (i + 1) * 0.125f;
        }
        return v;
    }
}
