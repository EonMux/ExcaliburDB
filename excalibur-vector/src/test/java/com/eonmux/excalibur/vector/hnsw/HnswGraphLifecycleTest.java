package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * What a {@link HnswGraph} carries across a close and reopen, and how it shuts down.
 *
 * <p>Covers the durable state: the nodes and configuration written by {@code flush},
 * and the last-integrated write-log offset that tells a restarting indexer where to
 * resume — including that {@code compareAndSetLastIntegratedOffset} only ever moves it
 * forward. Also covers the refusal of a corrupt file, and that {@code close} is
 * idempotent and makes every subsequent operation fail loudly.</p>
 */
class HnswGraphLifecycleTest {

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
    void shouldTrackLastIntegratedOffset() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(0);

            graph.setLastIntegratedOffset(100);
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(100);

            graph.setLastIntegratedOffset(200);
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(200);
        }
    }

    @Test
    void shouldOnlyIncreaseLastIntegratedOffsetWithCompareAndSet() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.setLastIntegratedOffset(100);

            // compareAndSet should only allow increases
            boolean decreased = graph.compareAndSetLastIntegratedOffset(50);
            assertThat(decreased).isFalse();
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(100);

            boolean increased = graph.compareAndSetLastIntegratedOffset(200);
            assertThat(increased).isTrue();
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(200);
        }
    }

    @Test
    void shouldPersistLastIntegratedOffset() throws IOException {
        // Create and write offset
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));
            graph.setLastIntegratedOffset(12345);
            graph.flush();
        }

        // Reopen and verify
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(12345);
        }
    }

    @Test
    void shouldPersistGraph() throws IOException {
        // Create graph and insert vectors
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }
            graph.flush();
        }

        // Reopen and verify
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(graph.nodeCount()).isEqualTo(50);
            assertThat(graph.config().dimension()).isEqualTo(64);
        }
    }

    /**
     * A file whose first eight bytes are not the HNSW magic number is refused as such.
     *
     * <p>The type and the wording are both the contract. {@code open} is declared to throw
     * {@link IOException} and nothing else, and everything it catches on the way is
     * re-wrapped into one, so an unchecked exception escaping here — a null dereference on
     * a field the open path forgot to set, say — is a bug in {@code open} and not a
     * rejection. Accepting any {@code Exception} cannot tell those two apart, which is the
     * whole point of the test.</p>
     */
    @Test
    void shouldRejectInvalidFile() throws IOException {
        Files.write(graphPath, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

        assertThatThrownBy(() -> HnswGraph.open(graphPath, distanceFunction))
            .isExactlyInstanceOf(IOException.class)
            .hasMessage("Invalid HNSW file (bad magic number): " + graphPath);
    }

    @Test
    void shouldCloseProperly() throws IOException {
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
        assertThat(graph.isClosed()).isFalse();

        graph.close();
        assertThat(graph.isClosed()).isTrue();

        // Operations on closed graph should fail
        assertThatThrownBy(() -> graph.insert(randomVector(64)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");

        assertThatThrownBy(() -> graph.search(randomVector(64), 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void shouldAllowDoubleClose() throws IOException {
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
        graph.close();

        assertThatCode(graph::close)
            .as("close() must be idempotent")
            .doesNotThrowAnyException();
    }

}
