package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.quantization.PQConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The quantiser shape an operator writes down is the shape the warming worker trains.
 *
 * <p>Every segment trains a product quantiser on its way from WARMING to ACTIVE, and the three
 * numbers that describe it - subspaces, centroids and the sample ceiling - decide the
 * codebook's memory, its recall and how long the transition takes. They were compile-time
 * constants: the one production construction of the warming worker passed no configuration at
 * all, so a deployment with 3072-dimensional embeddings trained the same eight subspaces as
 * one with 384.</p>
 */
@DisplayName("Product quantiser shape from configuration")
class IndexOrchestratorPqConfigTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    private PQConfig trainedShapeFor(Map<String, String> overrides) {
        Config config = new Config(new HashMap<>(overrides));
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, null, config.shard());
        return orchestrator.getWarmingWorker().getPqConfig();
    }

    @Test
    @DisplayName("the configured shape reaches the warming worker")
    void configuredShapeReachesTheWorker() {
        PQConfig trained = trainedShapeFor(Map.of(
            "excalibur.shard.pq-subspaces", "24",
            "excalibur.shard.pq-centroids", "64",
            "excalibur.shard.pq-training-samples", "5000"));

        assertThat(trained.numSubspaces()).isEqualTo(24);
        assertThat(trained.numCentroids()).isEqualTo(64);
        assertThat(trained.maxTrainingSamples()).isEqualTo(5000);
    }

    @Test
    @DisplayName("an unconfigured node trains exactly what it trained before")
    void defaultsAreUnchanged() {
        PQConfig trained = trainedShapeFor(Map.of());

        assertThat(trained.numSubspaces()).isEqualTo(PQConfig.DEFAULT_NUM_SUBSPACES);
        assertThat(trained.numCentroids()).isEqualTo(PQConfig.DEFAULT_NUM_CENTROIDS);
        assertThat(trained.maxTrainingSamples())
            .isEqualTo(PQConfig.DEFAULT_MAX_TRAINING_SAMPLES);
    }

    @Test
    @DisplayName("a sample ceiling of zero trains on every vector the segment has")
    void zeroSamplesMeansEveryVector() {
        PQConfig trained = trainedShapeFor(Map.of(
            "excalibur.shard.pq-training-samples", "0"));

        assertThat(trained.maxTrainingSamples()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("the configuration bounds are the quantiser's own bounds")
    void configurationBoundsMatchTheQuantiser() {
        // Two modules, two sets of constants: excalibur-config cannot see PQConfig, so the only
        // thing keeping its range checks honest is this assertion. Widening one side alone turns
        // a startup error naming the property into a constructor error three frames down.
        assertThat(ShardConfig.MIN_PQ_SUBSPACES).isEqualTo(PQConfig.MIN_NUM_SUBSPACES);
        assertThat(ShardConfig.MAX_PQ_SUBSPACES).isEqualTo(PQConfig.MAX_NUM_SUBSPACES);
        assertThat(ShardConfig.MIN_PQ_CENTROIDS).isEqualTo(PQConfig.MIN_NUM_CENTROIDS);
        assertThat(ShardConfig.MAX_PQ_CENTROIDS).isEqualTo(PQConfig.MAX_NUM_CENTROIDS);
        assertThat(ShardConfig.DEFAULT_PQ_SUBSPACES).isEqualTo(PQConfig.DEFAULT_NUM_SUBSPACES);
        assertThat(ShardConfig.DEFAULT_PQ_CENTROIDS).isEqualTo(PQConfig.DEFAULT_NUM_CENTROIDS);
        assertThat(ShardConfig.DEFAULT_PQ_TRAINING_SAMPLES)
            .isEqualTo(PQConfig.DEFAULT_MAX_TRAINING_SAMPLES);
    }

    @Test
    @DisplayName("a shape the quantiser refuses is refused at startup, naming the property")
    void anImpossibleShapeIsRefusedAtStartup() {
        assertThatThrownBy(() -> new Config(new HashMap<>(
            Map.of("excalibur.shard.pq-subspaces", "512"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excalibur.shard.pq-subspaces");
    }
}
