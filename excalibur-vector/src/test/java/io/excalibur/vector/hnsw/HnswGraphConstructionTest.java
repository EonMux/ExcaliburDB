package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Construction and insertion contract for {@link HnswGraph}.
 *
 * <p>Covers what a freshly created graph looks like, which constructor arguments it
 * refuses, and which vectors {@code insert} accepts: node ids are handed out in order
 * from zero, and a vector of the wrong dimension or no vector at all is rejected rather
 * than stored.</p>
 */
class HnswGraphConstructionTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("test.hnsw");
        config = HnswConfig.create(64); // 64-dimensional vectors
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    void shouldCreateGraph() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            assertThat(graph.nodeCount()).isZero();
            assertThat(graph.entryPoint()).isEqualTo(-1);
            assertThat(graph.maxLevel()).isEqualTo(-1);
            assertThat(graph.config().dimension()).isEqualTo(64);
            assertThat(graph.isClosed()).isFalse();
        }

        assertThat(Files.exists(graphPath)).isTrue();
    }

    @Test
    void shouldRejectNullParameters() {
        assertThatThrownBy(() -> new HnswGraph(null, config, distanceFunction, 1000))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new HnswGraph(graphPath, null, distanceFunction, 1000))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new HnswGraph(graphPath, config, null, 1000))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidMaxNodes() {
        assertThatThrownBy(() -> new HnswGraph(graphPath, config, distanceFunction, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HnswGraph(graphPath, config, distanceFunction, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldInsertFirstVector() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] vector = randomVector(64);
            long nodeId = graph.insert(vector);

            assertThat(nodeId).isEqualTo(0);
            assertThat(graph.nodeCount()).isEqualTo(1);
            assertThat(graph.entryPoint()).isEqualTo(0);
        }
    }

    @Test
    void shouldInsertMultipleVectors() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 100; i++) {
                float[] vector = randomVector(64);
                long nodeId = graph.insert(vector);
                assertThat(nodeId).isEqualTo(i);
            }

            assertThat(graph.nodeCount()).isEqualTo(100);
        }
    }

    @Test
    void shouldRejectWrongDimensionVector() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] wrongDim = randomVector(32);
            assertThatThrownBy(() -> graph.insert(wrongDim))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension mismatch");
        }
    }

    @Test
    void shouldRejectNullVector() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            assertThatThrownBy(() -> graph.insert(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

}
