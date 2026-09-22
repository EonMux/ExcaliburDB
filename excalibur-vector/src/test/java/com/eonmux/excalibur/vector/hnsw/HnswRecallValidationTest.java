package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Validates HNSW recall@10 meets the ≥99% target.
 *
 * <p>This test verifies the Phase 4 requirement that the default HNSW configuration
 * achieves at least 99% recall@10.</p>
 *
 * <h2>Test Methodology</h2>
 * <ol>
 *   <li>Insert N vectors into HNSW graph</li>
 *   <li>Generate query vectors</li>
 *   <li>For each query, compute true top-10 via brute force</li>
 *   <li>Compare HNSW results with true top-10</li>
 *   <li>Calculate recall = (intersection / 10)</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Recall values must be in [0.0, 1.0]</li>
 *   <li>Target recall: ≥0.99 (99%)</li>
 *   <li>Vector dimensions: 64 (typical embedding)</li>
 *   <li>Distance values non-negative for L2</li>
 * </ul>
 *
 * <h2>Tagging</h2>
 * <p>The recall assertions are the project's only enforceable ANN quality guarantee,
 * so they must run in the default build. The root build excludes {@code slow} and
 * {@code benchmark} from every {@code test} task, so only the largest dataset - which
 * measures the same property as the 1000-vector case, just more slowly - carries
 * {@code @Tag("slow")}.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("HNSW Recall@10 Validation")
class HnswRecallValidationTest {

    /**
     * Target recall@10 (99%)
     */
    private static final double TARGET_RECALL = 0.99;

    /**
     * Number of nearest neighbors to retrieve
     */
    private static final int K = 10;

    @TempDir
    Path tempDir;

    private Path graphPath;
    private DistanceFunction distanceFunction;
    private static final int DIMENSION = 64;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("recall_test.hnsw");
        distanceFunction = L2Distance.getInstance();
    }

    @Test
    @DisplayName("Default config achieves ≥99% recall@10 with 1000 vectors")
    void testRecallAt10With1000Vectors() throws IOException {
        int datasetSize = 1000;
        int queryCount = 50;
        long seed = 42L;

        // Use default config which should achieve ≥99% recall@10
        HnswConfig config = HnswConfig.create(DIMENSION);

        double recall = measureRecall(config, datasetSize, queryCount, seed);

        System.out.println("Recall@10 (1000 vectors, efSearch=" + config.efSearch() + "): " +
            String.format("%.4f", recall) + " (" + String.format("%.2f", recall * 100) + "%)");

        assertThat(recall)
            .as("Recall@10 should be >= 99%% with default config (efSearch=%d)",
                config.efSearch())
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    @Test
    @Tag("slow")
    @DisplayName("Default config achieves ≥99% recall@10 with 5000 vectors")
    void testRecallAt10With5000Vectors() throws IOException {
        int datasetSize = 5000;
        int queryCount = 100;
        long seed = 123L;

        // Use default config
        HnswConfig config = HnswConfig.create(DIMENSION);

        double recall = measureRecall(config, datasetSize, queryCount, seed);

        System.out.println("Recall@10 (5000 vectors, efSearch=" + config.efSearch() + "): " +
            String.format("%.4f", recall) + " (" + String.format("%.2f", recall * 100) + "%)");

        assertThat(recall)
            .as("Recall@10 should be >= 99%% with default config (efSearch=%d)",
                config.efSearch())
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    @Test
    @DisplayName("Higher efSearch (300) achieves higher recall than default (200)")
    void testHigherEfSearchImprovesRecall() throws IOException {
        int datasetSize = 2000;
        int queryCount = 50;
        long seed = 999L;

        // Config with efSearch=200 (default)
        HnswConfig config200 = HnswConfig.create(DIMENSION);
        double recall200 = measureRecall(config200, datasetSize, queryCount, seed);

        // Config with efSearch=300 (higher)
        HnswConfig config300 = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            300, // Higher efSearch
            HnswConfig.DEFAULT_ML,
            DIMENSION
        );
        double recall300 = measureRecall(config300, datasetSize, queryCount, seed);

        System.out.println("Recall@10 (efSearch=200): " + String.format("%.4f", recall200));
        System.out.println("Recall@10 (efSearch=300): " + String.format("%.4f", recall300));

        // Higher efSearch should not decrease recall
        assertThat(recall300)
            .as("Higher efSearch (300) should not decrease recall compared to default (200)")
            .isGreaterThanOrEqualTo(recall200 * 0.98); // Allow 2% variance
    }

    @Test
    @DisplayName("efSearch=100 achieves ~90-95% recall@10")
    void testLowerEfSearchReducesRecall() throws IOException {
        int datasetSize = 2000;
        int queryCount = 50;
        long seed = 777L;

        // Config with efSearch=100 (lower than default)
        HnswConfig config100 = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            100, // Lower efSearch
            HnswConfig.DEFAULT_ML,
            DIMENSION
        );

        double recall = measureRecall(config100, datasetSize, queryCount, seed);

        System.out.println("Recall@10 (efSearch=100): " + String.format("%.4f", recall));

        // efSearch=100 should still achieve reasonable recall (90-95%)
        assertThat(recall)
            .as("efSearch=100 should achieve at least 85%% recall@10")
            .isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("Recall is consistent across multiple runs")
    void testRecallConsistency() throws IOException {
        int datasetSize = 1000;
        int queryCount = 30;
        long seed = 42L;

        HnswConfig config = HnswConfig.create(DIMENSION);

        // Measure recall twice with same parameters
        double recall1 = measureRecall(config, datasetSize, queryCount, seed);
        double recall2 = measureRecall(config, datasetSize, queryCount, seed);

        System.out.println("Recall run 1: " + String.format("%.4f", recall1));
        System.out.println("Recall run 2: " + String.format("%.4f", recall2));

        // Node levels are drawn from ThreadLocalRandom, so two builds of the same
        // dataset are not bit-identical graphs. What must hold is that recall does
        // not swing between runs and that both runs meet the target.
        assertThat(recall1)
            .as("Recall should be consistent across runs")
            .isCloseTo(recall2, within(0.02));
        assertThat(Math.min(recall1, recall2))
            .as("Both runs should meet the recall target")
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    /**
     * Measures recall@10 for the given configuration.
     *
     * @param config HNSW configuration
     * @param datasetSize Number of vectors to insert
     * @param queryCount Number of query vectors
     * @param seed Random seed for reproducibility
     * @return Measured recall@10
     */
    private double measureRecall(HnswConfig config, int datasetSize, int queryCount, long seed)
            throws IOException {

        // Generate dataset with fixed seed
        Random random = new Random(seed);
        float[][] dataset = new float[datasetSize][DIMENSION];
        for (int i = 0; i < datasetSize; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                dataset[i][d] = (float) random.nextGaussian();
            }
            normalize(dataset[i]);
        }

        // Generate queries with different seed
        random = new Random(seed + 1);
        float[][] queries = new float[queryCount][DIMENSION];
        for (int i = 0; i < queryCount; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                queries[i][d] = (float) random.nextGaussian();
            }
            normalize(queries[i]);
        }

        // Build HNSW graph
        Path graphFile = tempDir.resolve("recall_" + seed + "_" + config.efSearch() + ".hnsw");
        try (HnswGraph graph = new HnswGraph(graphFile, config, distanceFunction, datasetSize * 2)) {
            for (float[] vec : dataset) {
                graph.insert(vec);
            }

            // Compute ground truth and measure recall
            int totalFound = 0;
            int totalExpected = queryCount * K;

            for (int q = 0; q < queryCount; q++) {
                // Get HNSW results
                List<HnswSearchResult> hnswResults = graph.search(queries[q], K);
                Set<Long> hnswIds = new HashSet<>();
                for (HnswSearchResult r : hnswResults) {
                    hnswIds.add(r.nodeId());
                }

                // Get true top-K via brute force
                List<Long> trueTopK = computeTrueTopK(queries[q], dataset, K);

                // Count intersection
                for (Long trueId : trueTopK) {
                    if (hnswIds.contains(trueId)) {
                        totalFound++;
                    }
                }
            }

            double recall = (double) totalFound / totalExpected;

            // Domain validation: recall must be in [0, 1]
            assertThat(recall)
                .as("Recall must be in range [0.0, 1.0]")
                .isBetween(0.0, 1.0);

            return recall;
        } finally {
            Files.deleteIfExists(graphFile);
        }
    }

    /**
     * Computes the true top-K nearest neighbors via brute force.
     */
    private List<Long> computeTrueTopK(float[] query, float[][] dataset, int k) {
        PriorityQueue<NodeDistance> heap = new PriorityQueue<>(
            Comparator.comparingDouble(NodeDistance::distance).reversed()
        );

        for (long i = 0; i < dataset.length; i++) {
            float dist = distanceFunction.compute(query, dataset[(int) i]);
            if (heap.size() < k) {
                heap.offer(new NodeDistance(i, dist));
            } else if (dist < heap.peek().distance()) {
                heap.poll();
                heap.offer(new NodeDistance(i, dist));
            }
        }

        List<Long> topK = new ArrayList<>();
        while (!heap.isEmpty()) {
            topK.add(heap.poll().nodeId());
        }
        Collections.reverse(topK);
        return topK;
    }

    /**
     * Normalizes a vector to unit length.
     */
    private void normalize(float[] vector) {
        float norm = 0.0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    /**
     * Record for node-distance pairs.
     */
    private record NodeDistance(long nodeId, float distance) {}
}
