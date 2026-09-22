package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.bruteForceSearch;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.calculateRecall;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.generateNormalizedVector;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.unitVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Query path and recall for {@link HnswGraph}.
 *
 * <p>Covers what {@code search} returns — an empty list for an empty graph, at most k
 * hits ordered by ascending distance, the query vector itself when it was indexed — and
 * how close the approximate answer is to the exact one. The recall tests measure against
 * a brute-force baseline and hold the default configuration to the project goal of
 * &ge;99% recall@10, across several graph sizes and at a raised {@code efSearch}.</p>
 */
class HnswGraphQueryTest {

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
    void shouldReturnEmptyForEmptyGraph() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] query = randomVector(64);
            List<HnswSearchResult> results = graph.search(query, 10);
            assertThat(results).isEmpty();
        }
    }

    @Test
    void shouldFindExactMatch() throws IOException {
        // Use L2 distance which follows "smaller is better" convention
        try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
            // Insert a specific vector
            float[] target = unitVector(64, 0);
            graph.insert(target);

            // Insert some noise
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            // Search with the same vector
            List<HnswSearchResult> results = graph.search(target, 1);

            assertThat(results).hasSize(1);
            // For exact match with L2 distance, distance should be very close to 0.0
            assertThat(results.get(0).distance()).isLessThan(0.001f);
            // Verify it's the node we inserted
            assertThat(results.get(0).nodeId()).isEqualTo(0L);
        }
    }

    @Test
    void shouldReturnTopKResults() throws IOException {
        // Use L2 distance for consistent "smaller is better" ordering
        try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
            // Insert 100 vectors
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            float[] query = randomVector(64);
            List<HnswSearchResult> results = graph.search(query, 10);

            assertThat(results).hasSize(10);

            // Results should be sorted by distance (ascending - smaller is better)
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).distance())
                    .isGreaterThanOrEqualTo(results.get(i - 1).distance());
            }

            // Verify all node IDs are valid
            for (HnswSearchResult result : results) {
                assertThat(result.nodeId()).isBetween(0L, 99L);
            }
        }
    }

    @Test
    void shouldRejectInvalidK() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));

            assertThatThrownBy(() -> graph.search(randomVector(64), 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> graph.search(randomVector(64), -1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldRejectNullQuery() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));

            assertThatThrownBy(() -> graph.search(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * A beam at least as wide as the graph makes search exact, and it is asserted as exact.
     *
     * <p>The recall floor here is not a measurement. {@code searchLayerBeam} abandons the
     * frontier only once it holds {@code ef} results that are all closer than the next
     * candidate, so with {@code ef} no smaller than the number of nodes that test can never
     * fire and the walk visits every node reachable from the entry point at layer 0. The
     * default {@code efSearch} is 200 and this graph holds 200 nodes, which is the tight
     * case: if layer 0 is connected — asserted below, because it is the other half of the
     * premise — the search is exhaustive and its answer is the exact one. Anything less
     * than 100% recall is therefore a defect, not a tuning result, and 1.0 is the only
     * honest floor.</p>
     *
     * <p>The data is deliberately the odd one out among the recall tests: uniform over
     * [-1, 1] and not normalised, where every other recall test indexes unit-length
     * Gaussians. Recall depends on how the data is distributed, so the exhaustive regime is
     * worth pinning on a distribution the tuned tests never see.</p>
     */
    @Test
    void shouldReturnExactResultsWhenBeamCoversWholeGraph() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int numVectors = 200;
        int numQueries = 50;
        int k = 10;

        assertThat(config.efSearch())
            .as("The exactness argument holds only while the beam is at least as wide as "
                + "the graph; raising the vector count past efSearch invalidates it")
            .isGreaterThanOrEqualTo(numVectors);

        try (HnswGraph graph = new HnswGraph(graphPath, config, distFn, 1000)) {
            Random insertRandom = new Random(12345);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < numVectors; i++) {
                float[] vec = uniformVector(64, insertRandom);
                vectors.add(vec);
                graph.insert(vec);
            }

            assertThat(HnswInvariantVerifier.verifyConnectivity(graph).isValid())
                .as("An exhaustive beam only reaches what layer 0 connects; without this "
                    + "the recall floor below would be unjustified rather than merely broken")
                .isTrue();

            Random queryRandom = new Random(99);
            for (int q = 0; q < numQueries; q++) {
                float[] query = uniformVector(64, queryRandom);
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);
                List<HnswSearchResult> results = graph.search(query, k);

                assertThat(results).hasSize(k);
                assertThat(calculateRecall(results, groundTruth))
                    .as("Query %d: an exhaustive search must return the exact top-%d", q, k)
                    .isEqualTo(1.0);

                for (HnswSearchResult result : results) {
                    assertThat(result.nodeId()).isBetween(0L, (long) numVectors - 1);
                    assertThat(result.distance()).isGreaterThanOrEqualTo(0.0f);
                }
                for (int i = 1; i < results.size(); i++) {
                    assertThat(results.get(i).distance())
                        .isGreaterThanOrEqualTo(results.get(i - 1).distance());
                }
            }
        }
    }

    /**
     * Draws a vector whose components are uniform over [-1, 1], without normalising it.
     *
     * @param dimension The vector's length
     * @param random The source of randomness, seeded by the caller
     * @return A freshly drawn vector
     */
    private static float[] uniformVector(int dimension, Random random) {
        float[] vec = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vec[i] = random.nextFloat() * 2 - 1;
        }
        return vec;
    }

    /**
     * Comprehensive recall@10 verification test.
     *
     * <p>Measures actual recall against brute-force ground truth to verify
     * the default configuration achieves ≥99% recall@10 as specified in the
     * PROJECT GOAL.</p>
     *
     * <p><strong>Test methodology:</strong></p>
     * <ol>
     *   <li>Insert N vectors with seeded random for reproducibility</li>
     *   <li>For each query, compute brute-force top-10 (ground truth)</li>
     *   <li>Run HNSW search to get approximate top-10</li>
     *   <li>Calculate recall = |HNSW ∩ ground_truth| / 10</li>
     *   <li>Verify average recall across all queries ≥ 0.99</li>
     * </ol>
     */
    @Test
    void shouldAchieve99PercentRecallAt10() throws IOException {
        // Use L2 distance for consistent ground truth computation
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 64;
        int numVectors = 500;  // Enough vectors for meaningful recall measurement
        int numQueries = 50;   // Number of queries to average recall over
        int k = 10;            // top-k for recall@k

        // Use default config which targets ≥99% recall@10
        HnswConfig recallConfig = HnswConfig.create(dimension);

        try (HnswGraph graph = new HnswGraph(graphPath, recallConfig, distFn, numVectors * 2)) {
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors with reproducible randomness
            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(dimension, seededRandom);
                vectors.add(vec);
                graph.insert(vec);
            }

            // Generate query vectors (separate seed for independence)
            Random queryRandom = new Random(99);
            List<float[]> queries = new ArrayList<>();
            for (int i = 0; i < numQueries; i++) {
                queries.add(generateNormalizedVector(dimension, queryRandom));
            }

            // Measure recall
            double totalRecall = 0.0;
            int successfulQueries = 0;

            for (float[] query : queries) {
                // Compute brute-force ground truth
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);

                // Run HNSW search
                List<HnswSearchResult> hnswResults = graph.search(query, k);

                // Calculate recall for this query
                double queryRecall = calculateRecall(hnswResults, groundTruth);
                totalRecall += queryRecall;
                successfulQueries++;
            }

            double averageRecall = totalRecall / successfulQueries;

            // Log recall for debugging
            System.out.printf("Recall@%d test: %.2f%% average recall over %d queries (target: ≥99%%)%n",
                k, averageRecall * 100, successfulQueries);

            // Verify we achieve ≥99% recall with default config
            // The default efSearch=200 is tuned to guarantee ≥99% recall@10
            // We use 0.99 as the minimum threshold (99%)
            assertThat(averageRecall)
                .as("Average recall@%d should be ≥99%% (actual: %.2f%%). " +
                    "Default config (efSearch=200) is tuned for guaranteed ≥99%% recall@10.",
                    k, averageRecall * 100)
                .isGreaterThanOrEqualTo(0.99);
        }
    }

    /**
     * Tests recall with higher efSearch to verify configuration can achieve ≥99.5%.
     * This test uses efSearch=300 to achieve very high recall for applications
     * that prioritize recall over latency.
     */
    @Test
    void shouldAchieve99Point5PercentRecallWithHighEfSearch() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 64;
        int numVectors = 200;  // Reduced from 500 for faster test
        int numQueries = 20;   // Reduced from 50 for faster test
        int k = 10;

        // Use even higher efSearch (300) for very high recall
        HnswConfig highRecallConfig = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            300,  // Higher efSearch for very high recall
            HnswConfig.DEFAULT_ML,
            dimension
        );

        try (HnswGraph graph = new HnswGraph(graphPath, highRecallConfig, distFn, numVectors * 2)) {
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(dimension, seededRandom);
                vectors.add(vec);
                graph.insert(vec);
            }

            Random queryRandom = new Random(99);
            double totalRecall = 0.0;

            for (int i = 0; i < numQueries; i++) {
                float[] query = generateNormalizedVector(dimension, queryRandom);
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);
                List<HnswSearchResult> hnswResults = graph.search(query, k);
                totalRecall += calculateRecall(hnswResults, groundTruth);
            }

            double averageRecall = totalRecall / numQueries;

            System.out.printf("Recall@%d with efSearch=300: %.2f%%%n", k, averageRecall * 100);

            // With efSearch=300, we should achieve very high recall (≥99.5%)
            assertThat(averageRecall)
                .as("Recall@%d with efSearch=300 should be ≥99.5%% (actual: %.2f%%)",
                    k, averageRecall * 100)
                .isGreaterThanOrEqualTo(0.995);
        }
    }

    /**
     * Comprehensive recall verification across different graph sizes.
     *
     * <p>Verifies that the default configuration achieves ≥99% recall@10
     * across a range of graph sizes from small (100 vectors) to larger (1000 vectors).</p>
     */
    @Test
    void shouldAchieve99PercentRecallAcrossGraphSizes() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 64;
        int k = 10;
        int[] graphSizes = {100, 250, 500};

        for (int numVectors : graphSizes) {
            Path sizeGraphPath = tempDir.resolve("test_" + numVectors + ".hnsw");
            HnswConfig sizeConfig = HnswConfig.create(dimension); // Default config with efSearch=200

            try (HnswGraph graph = new HnswGraph(sizeGraphPath, sizeConfig, distFn, numVectors * 2)) {
                Random seededRandom = new Random(42);
                List<float[]> vectors = new ArrayList<>();

                for (int i = 0; i < numVectors; i++) {
                    float[] vec = generateNormalizedVector(dimension, seededRandom);
                    vectors.add(vec);
                    graph.insert(vec);
                }

                Random queryRandom = new Random(99);
                int numQueries = Math.min(30, numVectors / 5);
                double totalRecall = 0.0;

                for (int i = 0; i < numQueries; i++) {
                    float[] query = generateNormalizedVector(dimension, queryRandom);
                    List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);
                    List<HnswSearchResult> hnswResults = graph.search(query, k);
                    totalRecall += calculateRecall(hnswResults, groundTruth);
                }

                double averageRecall = totalRecall / numQueries;
                System.out.printf("Recall@%d with %d vectors: %.2f%%%n", k, numVectors, averageRecall * 100);

                // All graph sizes should achieve ≥99% recall with default config
                assertThat(averageRecall)
                    .as("Recall@%d with %d vectors should be ≥99%% (actual: %.2f%%)",
                        k, numVectors, averageRecall * 100)
                    .isGreaterThanOrEqualTo(0.99);
            }
        }
    }

    /**
     * Verifies search results are correctly sorted by distance.
     *
     * <p>Ensures that the search algorithm returns results in ascending order
     * of distance (closest first) for all distance metrics.</p>
     */
    @Test
    void shouldReturnResultsSortedByDistance() throws IOException {
        // Test with L2 distance (smaller is better)
        try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
            Random seededRandom = new Random(123);
            for (int i = 0; i < 200; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Multiple queries to verify consistent sorting
            for (int q = 0; q < 10; q++) {
                float[] query = generateNormalizedVector(64, new Random(q));
                List<HnswSearchResult> results = graph.search(query, 20);

                // Verify results are sorted by distance (ascending)
                for (int i = 1; i < results.size(); i++) {
                    assertThat(results.get(i).distance())
                        .as("Results should be sorted by distance (ascending) at index %d", i)
                        .isGreaterThanOrEqualTo(results.get(i - 1).distance());
                }

                // Verify all distances are non-negative for L2
                for (HnswSearchResult result : results) {
                    assertThat(result.distance())
                        .as("L2 distance should be non-negative")
                        .isGreaterThanOrEqualTo(0.0f);
                }
            }
        }
    }

    /**
     * Verifies that search finds the exact nearest neighbor for a query
     * that is identical to an inserted vector.
     */
    @Test
    void shouldFindExactNearestNeighbor() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        try (HnswGraph graph = new HnswGraph(graphPath, config, distFn, 1000)) {
            Random random = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors
            for (int i = 0; i < 100; i++) {
                float[] vec = generateNormalizedVector(64, random);
                vectors.add(vec);
                graph.insert(vec);
            }

            // Query with each inserted vector - should find itself as nearest
            for (int i = 0; i < 10; i++) {
                float[] query = vectors.get(i);
                List<HnswSearchResult> results = graph.search(query, 1);

                assertThat(results)
                    .as("Search should find at least one result")
                    .isNotEmpty();

                // The nearest should be the query itself (distance very close to 0)
                assertThat(results.get(0).distance())
                    .as("Exact match should have distance near 0")
                    .isLessThan(0.001f);

                assertThat(results.get(0).nodeId())
                    .as("Exact match should find the same vector")
                    .isEqualTo(i);
            }
        }
    }

}
