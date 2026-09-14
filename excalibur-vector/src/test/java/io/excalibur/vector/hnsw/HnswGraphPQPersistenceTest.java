package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.quantization.PQCodebookFile;
import io.excalibur.vector.quantization.ProductQuantizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quantisation has to outlive the process that trained it, and the compaction that renumbers
 * the nodes it describes.
 *
 * <p>A codebook lived only on the heap: a segment that was warmed, quantised and then reopened
 * came back with no quantiser and no way to acquire one, because retraining only ever happens
 * during warming and a reopened segment is already active. The training and encoding a node paid
 * for were thrown away on every restart.</p>
 *
 * <p>Compaction had the same shape of problem from the other end. It renumbers nodes, so the
 * codes - which are keyed by node id - have to be re-keyed or they describe the wrong vectors.
 * They were dropped instead, which was safe and left the segment quantiser-in-name-only until
 * something re-encoded it, and nothing did.</p>
 */
@DisplayName("Quantiser persistence")
class HnswGraphPQPersistenceTest {

    private static final int DIMENSION = 16;
    private static final int CORPUS_SIZE = 60;
    private static final int SUBSPACES = 4;
    private static final int CENTROIDS = 16;

    @TempDir
    Path tempDir;

    private Path graphPath;
    private Path codebookPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("persist.hnsw");
        codebookPath = tempDir.resolve("persist.hnsw" + HnswGraph.PRODUCT_QUANTIZER_SUFFIX);
        config = HnswConfig.create(DIMENSION);
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    @DisplayName("encoding a graph writes its codebook beside it")
    void encodingWritesTheCodebook() throws IOException {
        try (HnswGraph graph = newGraph()) {
            fillAndQuantise(graph);
        }

        assertThat(codebookPath).exists();
        assertThat(PQCodebookFile.read(codebookPath, DIMENSION).isTrained()).isTrue();
    }

    @Test
    @DisplayName("a reopened graph quantises without being retrained")
    void reopenedGraphStillQuantises() throws IOException {
        float[] query = vector(11);
        List<HnswSearchResult> before;
        try (HnswGraph graph = newGraph()) {
            fillAndQuantise(graph);
            before = graph.searchWithPQThenRerank(query, 5, 4);
        }

        try (HnswGraph reopened = newGraph()) {
            assertThat(reopened.isPQEnabled())
                .as("the codebook beside the graph is the quantiser this segment was given")
                .isTrue();
            assertThat(reopened.encodedVectorCount())
                .as("reading the codebook must not drag a full encoding pass onto the "
                    + "startup path")
                .isZero();

            assertThat(reopened.searchWithPQThenRerank(query, 5, 4))
                .extracting(HnswSearchResult::nodeId, HnswSearchResult::distance)
                .containsExactlyElementsOf(before.stream()
                    .map(result -> org.assertj.core.groups.Tuple.tuple(
                        result.nodeId(), result.distance()))
                    .toList());

            assertThat(reopened.encodedVectorCount())
                .as("the codes come back as the quantised beam visits nodes, without the "
                    + "training that produced them being repeated")
                .isPositive();
        }
    }

    @Test
    @DisplayName("open() restores the codebook as well")
    void openRestoresTheCodebook() throws IOException {
        try (HnswGraph graph = newGraph()) {
            fillAndQuantise(graph);
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(reopened.isPQEnabled()).isTrue();

            reopened.encodeAllVectors();
            assertThat(reopened.encodedVectorCount())
                .as("a caller that knows quantised search is on can pay the encoding up front")
                .isEqualTo(CORPUS_SIZE);
        }
    }

    @Test
    @DisplayName("a graph that was never quantised stays unquantised")
    void noCodebookMeansNoQuantiser() throws IOException {
        try (HnswGraph graph = newGraph()) {
            fill(graph);
        }

        assertThat(codebookPath).doesNotExist();
        try (HnswGraph reopened = newGraph()) {
            assertThat(reopened.isPQEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("a codebook belonging to another index is refused, and the graph still opens")
    void aForeignCodebookIsRefused() throws IOException {
        try (HnswGraph graph = newGraph()) {
            fill(graph);
        }
        PQCodebookFile.write(codebookPath, foreignQuantizer());

        try (HnswGraph reopened = newGraph()) {
            assertThat(reopened.isPQEnabled())
                .as("centroids from another vector space must not be adopted")
                .isFalse();
            assertThat(reopened.search(vector(3), 5))
                .as("the vectors are the data; a bad sidecar costs quantisation, not the index")
                .hasSize(5);
        }
    }

    @Test
    @DisplayName("a compaction leaves the segment quantised, re-keyed to the new numbering")
    void compactionKeepsQuantisationWorking() throws IOException {
        try (HnswGraph graph = newGraph()) {
            fillAndQuantise(graph);

            // Deleting from the front is what makes the renumbering shift: new node k is the
            // (k+1)-th survivor, whose old id is larger than k.
            for (int i = 0; i < 10; i++) {
                graph.delete(i);
            }
            assertThat(graph.compact(tempDir)).isEqualTo(10);

            assertThat(graph.isPQEnabled()).isTrue();
            assertThat(graph.encodedVectorCount())
                .as("every surviving node must still have codes, or quantised search silently "
                    + "degrades to a full-precision scan")
                .isEqualTo(CORPUS_SIZE - 10);
            assertThat(codebookPath)
                .as("the codebook survives a compaction: the vectors did not change")
                .exists();
        }
    }

    private HnswGraph newGraph() throws IOException {
        return new HnswGraph(graphPath, config, distanceFunction, 1000);
    }

    private static void fill(HnswGraph graph) {
        Random random = new Random(424242L);
        for (int i = 0; i < CORPUS_SIZE; i++) {
            float[] vector = new float[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = random.nextFloat() * 2 - 1;
            }
            graph.insert(vector);
        }
    }

    private static void fillAndQuantise(HnswGraph graph) {
        fill(graph);
        graph.trainProductQuantizer(CORPUS_SIZE, SUBSPACES, CENTROIDS);
        graph.encodeAllVectors();
    }

    /**
     * A quantiser for a vector space this graph does not hold.
     */
    private static ProductQuantizer foreignQuantizer() {
        ProductQuantizer quantizer = new ProductQuantizer(DIMENSION * 2, SUBSPACES, CENTROIDS);
        Random random = new Random(5150L);
        List<float[]> samples = new ArrayList<>();
        for (int i = 0; i < CENTROIDS * 4; i++) {
            float[] sample = new float[DIMENSION * 2];
            for (int d = 0; d < sample.length; d++) {
                sample[d] = random.nextFloat();
            }
            samples.add(sample);
        }
        quantizer.train(samples.toArray(new float[0][]));
        return quantizer;
    }

    private static float[] vector(int seed) {
        Random random = new Random(seed);
        float[] values = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            values[i] = random.nextFloat() * 2 - 1;
        }
        return values;
    }
}
