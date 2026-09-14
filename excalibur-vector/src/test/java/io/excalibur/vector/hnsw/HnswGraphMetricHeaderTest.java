package io.excalibur.vector.hnsw;

import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.core.memory.Segment;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.InnerProduct;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that a graph remembers which metric built its edges and refuses to be
 * reopened under a different one.
 *
 * <p>Greedy descent only converges because every edge was chosen under the metric
 * in force when the node was inserted. Reading those edges back under another
 * metric still returns results, so the failure is silent: recall collapses and
 * nothing reports it. The header field exists to turn that into an error.</p>
 */
@DisplayName("HNSW graph metric header")
class HnswGraphMetricHeaderTest {

    private static final int DIMENSION = 8;
    private static final long MAX_NODES = 256;

    /** Mirrors the header layout in HnswGraph, so a legacy file can be forged by hand. */
    private static final long OFFSET_VERSION = 8;
    private static final long OFFSET_METRIC = 76;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A graph built with l2 reopens under l2 and still finds its vectors")
    void l2GraphReopensUnderL2() throws IOException {
        Path graphPath = tempDir.resolve("l2.hnsw");
        float[] target = vector(3);

        try (HnswGraph graph = newGraph(graphPath, L2Distance.getInstance())) {
            for (int i = 0; i < 8; i++) {
                graph.insert(vector(i));
            }
            graph.flush();
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
            assertThat(reopened.nodeCount()).isEqualTo(8);

            List<HnswSearchResult> results = reopened.search(target, 1);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).nodeId()).isEqualTo(3L);
            assertThat(results.get(0).distance()).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-4f));
        }
    }

    @Test
    @DisplayName("A graph built with cosine is refused when opened under l2")
    void cosineGraphIsRefusedUnderL2() throws IOException {
        Path graphPath = tempDir.resolve("cosine.hnsw");
        createGraphWith(graphPath, CosineSimilarity.getInstance());

        assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cosine")
            .hasMessageContaining("l2")
            .hasMessageContaining("reindex");
    }

    @Test
    @DisplayName("Re-attaching an existing graph with a different metric is refused too")
    void reattachingWithADifferentMetricIsRefused() throws IOException {
        Path graphPath = tempDir.resolve("reattach.hnsw");
        createGraphWith(graphPath, CosineSimilarity.getInstance());

        assertThatThrownBy(() -> newGraph(graphPath, InnerProduct.getInstance()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cosine")
            .hasMessageContaining("inner_product")
            .hasMessageContaining("reindex");
    }

    @Test
    @DisplayName("A header written before the metric field is treated as cosine")
    void legacyHeaderIsTreatedAsCosine() throws IOException {
        Path graphPath = tempDir.resolve("legacy.hnsw");
        createGraphWith(graphPath, CosineSimilarity.getInstance());
        stampLegacyHeader(graphPath);

        try (HnswGraph reopened = HnswGraph.open(graphPath, CosineSimilarity.getInstance())) {
            assertThat(reopened.nodeCount()).isEqualTo(8);
        }

        assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cosine")
            .hasMessageContaining("l2")
            .hasMessageContaining("reindex");
    }

    @Test
    @DisplayName("The header reports the metric the graph was built with")
    void headerInfoCarriesTheMetric() throws IOException {
        Path graphPath = tempDir.resolve("inspect.hnsw");

        try (HnswGraph graph = newGraph(graphPath, InnerProduct.getInstance());
             Segment segment = MemorySegmentFactory.allocateNative(8192)) {
            graph.insert(vector(1));
            graph.exportHeaderToSegment(segment);

            HnswGraph.HeaderInfo info = HnswGraph.readHeaderInfo(segment);
            assertThat(info).isNotNull();
            assertThat(info.distanceMetric()).isEqualTo("inner_product");
        }
    }

    private HnswGraph newGraph(Path path, io.excalibur.vector.distance.DistanceFunction metric)
            throws IOException {
        return new HnswGraph(path, HnswConfig.create(DIMENSION), metric, MAX_NODES);
    }

    private void createGraphWith(Path path, io.excalibur.vector.distance.DistanceFunction metric)
            throws IOException {
        try (HnswGraph graph = newGraph(path, metric)) {
            for (int i = 0; i < 8; i++) {
                graph.insert(vector(i));
            }
            graph.flush();
        }
    }

    /**
     * Rewrites the header as a version that predates the metric field: the version
     * goes back to 1 and the metric slot is zeroed, exactly as an untouched graph
     * from before the field existed would look.
     */
    private void stampLegacyHeader(Path path) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(OFFSET_VERSION);
            raf.writeInt(1);
            raf.seek(OFFSET_METRIC);
            raf.writeInt(0);
        }
    }

    private static float[] vector(int seed) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = (seed + 1) * (i + 1) * 0.125f;
        }
        return v;
    }
}
