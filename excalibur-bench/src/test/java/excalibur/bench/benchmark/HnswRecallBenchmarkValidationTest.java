package excalibur.bench.benchmark;

import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick validation test for HNSW recall@10 benchmark.
 *
 * <p>This test validates that the HNSW implementation achieves the target
 * recall@10 >= 99% with the default configuration (efSearch=200).</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Recall values must be in range [0.0, 1.0] - enforced by clamping</li>
 *   <li>Recall@10 should increase with higher efSearch</li>
 *   <li>efSearch=200 should achieve >= 99% recall@10</li>
 *   <li>Uses HnswRecallBenchmark.TARGET_RECALL_10 constant for consistency</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class HnswRecallBenchmarkValidationTest {

    private static final int DATASET_SIZE = 1000;
    private static final int DIMENSION = 64;
    private static final int K = 10;
    private static final int QUERY_COUNT = 50;

    /**
     * Target recall@10 threshold - uses the same constant as HnswRecallBenchmark.
     * Value: 0.99 (99%)
     */
    private static final double TARGET_RECALL = HnswRecallBenchmark.TARGET_RECALL_10;

    @TempDir
    Path tempDir;

    private float[][] dataset;
    private float[][] queries;
    private List<List<Long>> groundTruth;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() throws IOException {
        distanceFunction = L2Distance.getInstance();

        // Generate dataset
        Random random = new Random(42);
        dataset = new float[DATASET_SIZE][DIMENSION];
        for (int i = 0; i < DATASET_SIZE; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                dataset[i][d] = (float) random.nextGaussian();
            }
            normalize(dataset[i]);
        }

        // Generate queries
        random = new Random(123);
        queries = new float[QUERY_COUNT][DIMENSION];
        for (int i = 0; i < QUERY_COUNT; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                queries[i][d] = (float) random.nextGaussian();
            }
            normalize(queries[i]);
        }

        // Compute ground truth
        groundTruth = new ArrayList<>();
        for (int q = 0; q < QUERY_COUNT; q++) {
            groundTruth.add(computeTrueTopK(queries[q]));
        }
    }

    @Test
    @DisplayName("efSearch=200 should achieve >= 99% recall@10")
    void testDefaultEfSearchAchievesTargetRecall() throws IOException {
        Path tempFile = tempDir.resolve("hnsw_test_200.hnsw");

        // Create graph with efSearch=200 (default)
        HnswConfig config = HnswConfig.create(DIMENSION, 200);
        HnswGraph graph = new HnswGraph(tempFile, config, distanceFunction, DATASET_SIZE * 2);

        try {
            // Insert vectors
            for (int i = 0; i < DATASET_SIZE; i++) {
                graph.insert(dataset[i]);
            }

            // Measure recall
            double recall = measureRecall(graph);

            System.out.println("Recall@10 with efSearch=200: " + String.format("%.4f", recall) +
                " (" + String.format("%.2f", recall * 100) + "%)");

            // Domain correctness: recall must be in valid range
            assertThat(recall)
                .as("Recall must be in range [0.0, 1.0]")
                .isBetween(0.0, 1.0);

            // Performance target: recall must be >= 99%
            assertThat(recall)
                .as("Recall@10 must be >= 99%% with efSearch=200 (got %.2f%%)", recall * 100)
                .isGreaterThanOrEqualTo(TARGET_RECALL);
        } finally {
            graph.close();
        }
    }

    @Test
    @DisplayName("Recall should increase with higher efSearch")
    void testRecallIncreasesWithEfSearch() throws IOException {
        // Test efSearch=100
        Path tempFile100 = tempDir.resolve("hnsw_test_100.hnsw");
        HnswConfig config100 = HnswConfig.create(DIMENSION, 100);
        HnswGraph graph100 = new HnswGraph(tempFile100, config100, distanceFunction, DATASET_SIZE * 2);
        for (int i = 0; i < DATASET_SIZE; i++) {
            graph100.insert(dataset[i]);
        }
        double recall100 = measureRecall(graph100);
        graph100.close();

        // Test efSearch=200
        Path tempFile200 = tempDir.resolve("hnsw_test_200.hnsw");
        HnswConfig config200 = HnswConfig.create(DIMENSION, 200);
        HnswGraph graph200 = new HnswGraph(tempFile200, config200, distanceFunction, DATASET_SIZE * 2);
        for (int i = 0; i < DATASET_SIZE; i++) {
            graph200.insert(dataset[i]);
        }
        double recall200 = measureRecall(graph200);
        graph200.close();

        // Test efSearch=400
        Path tempFile400 = tempDir.resolve("hnsw_test_400.hnsw");
        HnswConfig config400 = HnswConfig.create(DIMENSION, 400);
        HnswGraph graph400 = new HnswGraph(tempFile400, config400, distanceFunction, DATASET_SIZE * 2);
        for (int i = 0; i < DATASET_SIZE; i++) {
            graph400.insert(dataset[i]);
        }
        double recall400 = measureRecall(graph400);
        graph400.close();

        System.out.println("Recall@10 vs efSearch:");
        System.out.println("  efSearch=100: " + String.format("%.2f%%", recall100 * 100));
        System.out.println("  efSearch=200: " + String.format("%.2f%%", recall200 * 100));
        System.out.println("  efSearch=400: " + String.format("%.2f%%", recall400 * 100));

        // Domain correctness: all recalls must be in valid range
        assertThat(recall100).isBetween(0.0, 1.0);
        assertThat(recall200).isBetween(0.0, 1.0);
        assertThat(recall400).isBetween(0.0, 1.0);

        // Recall should generally increase with efSearch
        assertThat(recall200)
            .as("Higher efSearch should yield higher recall")
            .isGreaterThanOrEqualTo(recall100);

        // efSearch=200 should meet target
        assertThat(recall200)
            .as("efSearch=200 should achieve >= 99%% recall")
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    @Test
    @DisplayName("Queries/sec should be measurable and positive")
    void testQpsIsMeasurable() throws IOException {
        Path tempFile = tempDir.resolve("hnsw_test_qps.hnsw");

        // Create graph with efSearch=200
        HnswConfig config = HnswConfig.create(DIMENSION, 200);
        HnswGraph graph = new HnswGraph(tempFile, config, distanceFunction, DATASET_SIZE * 2);

        try {
            // Insert vectors
            for (int i = 0; i < DATASET_SIZE; i++) {
                graph.insert(dataset[i]);
            }

            // Warm up
            for (int i = 0; i < 10; i++) {
                graph.search(queries[i % QUERY_COUNT], K);
            }

            // Measure QPS
            int iterations = 100;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                graph.search(queries[i % QUERY_COUNT], K);
            }
            long elapsed = System.nanoTime() - start;
            long qps = (iterations * 1_000_000_000L) / elapsed;

            System.out.println("Queries/sec with efSearch=200: " + qps);

            // Domain correctness: QPS should be positive
            assertThat(qps)
                .as("QPS should be positive")
                .isPositive();
        } finally {
            graph.close();
        }
    }

    /**
     * Measures recall@k for the given graph.
     *
     * <p>Domain correctness: Returns value clamped to range [0.0, 1.0]</p>
     *
     * @param graph The HNSW graph to search
     * @return Recall@k value in range [0.0, 1.0]
     */
    private double measureRecall(HnswGraph graph) {
        int totalFound = 0;
        int totalExpected = QUERY_COUNT * K;

        for (int q = 0; q < QUERY_COUNT; q++) {
            List<HnswSearchResult> results = graph.search(queries[q], K);
            Set<Long> resultIds = new HashSet<>();
            for (HnswSearchResult r : results) {
                resultIds.add(r.nodeId());
            }

            for (Long trueId : groundTruth.get(q)) {
                if (resultIds.contains(trueId)) {
                    totalFound++;
                }
            }
        }

        double rawRecall = (double) totalFound / totalExpected;

        // Clamp to valid range [0.0, 1.0] using the benchmark's utility method
        return HnswRecallBenchmark.clampRecall(rawRecall);
    }

    /**
     * Computes the true top-k nearest neighbors via brute force.
     */
    private List<Long> computeTrueTopK(float[] query) {
        PriorityQueue<long[]> heap = new PriorityQueue<>(
            (a, b) -> Long.compare(b[1], a[1]) // Max heap by distance
        );

        for (long i = 0; i < DATASET_SIZE; i++) {
            float dist = distanceFunction.compute(query, dataset[(int) i]);
            long distBits = Float.floatToIntBits(dist);
            if (heap.size() < K) {
                heap.offer(new long[]{i, distBits});
            } else if (dist < Float.intBitsToFloat((int) heap.peek()[1])) {
                heap.poll();
                heap.offer(new long[]{i, distBits});
            }
        }

        List<Long> topK = new ArrayList<>();
        while (!heap.isEmpty()) {
            topK.add(heap.poll()[0]);
        }
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
}
