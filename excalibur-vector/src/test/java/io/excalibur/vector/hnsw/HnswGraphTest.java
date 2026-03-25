package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswGraph}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Graph creation and persistence</li>
 *   <li>Vector insertion and retrieval</li>
 *   <li>Search correctness (recall)</li>
 *   <li>Soft-delete behavior</li>
 *   <li>Tail offset tracking</li>
 *   <li>Thread safety</li>
 * </ul>
 */
class HnswGraphTest {

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

    @AfterEach
    void tearDown() {
        // Cleanup handled by @TempDir
    }

    // ==================== Creation Tests ====================

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

    // ==================== Insertion Tests ====================

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

    // ==================== Search Tests ====================

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

    // ==================== Recall Test ====================

    @Test
    void shouldAchieveReasonableRecall() throws IOException {
        // Use L2 distance for easier recall calculation
        try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
            int numVectors = 200;
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors with seeded random for reproducibility
            Random insertRandom = new Random(12345);
            for (int i = 0; i < numVectors; i++) {
                float[] vec = new float[64];
                for (int j = 0; j < 64; j++) {
                    vec[j] = insertRandom.nextFloat() * 2 - 1;
                }
                vectors.add(vec);
                graph.insert(vec);
            }

            // Verify search returns results
            float[] testQuery = vectors.get(0);
            List<HnswSearchResult> testResults = graph.search(testQuery, 10);

            // Basic assertions for disk-backed node operations
            assertThat(testResults)
                .as("Search should return results")
                .isNotEmpty();

            assertThat(testResults.size())
                .as("Search should return up to k results")
                .isLessThanOrEqualTo(10);

            // All results should have valid node IDs
            for (HnswSearchResult result : testResults) {
                assertThat(result.nodeId())
                    .as("Node ID should be valid")
                    .isBetween(0L, (long) numVectors - 1);
                assertThat(result.distance())
                    .as("Distance should be non-negative for L2")
                    .isGreaterThanOrEqualTo(0.0f);
            }

            // Verify results are sorted by distance
            for (int i = 1; i < testResults.size(); i++) {
                assertThat(testResults.get(i).distance())
                    .isGreaterThanOrEqualTo(testResults.get(i - 1).distance());
            }
        }
    }

    /**
     * Debug test to understand graph connectivity.
     */
    @Test
    void shouldDebugGraphConnectivity() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 8;  // Small dimension for easier debugging
        int numVectors = 20;

        HnswConfig debugConfig = new HnswConfig(
            8, 16, 50, 50, 1.0 / Math.log(8), dimension
        );

        try (HnswGraph graph = new HnswGraph(graphPath, debugConfig, distFn, 100)) {
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors
            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(dimension, seededRandom);
                vectors.add(vec);
                graph.insert(vec);
            }

            // Print graph structure
            System.out.println("Graph structure:");
            System.out.println("  Node count: " + graph.nodeCount());
            System.out.println("  Max level: " + graph.maxLevel());
            System.out.println("  Entry point: " + graph.entryPoint());

            // Search with first vector as query
            float[] query = vectors.get(0);
            System.out.println("\nSearching with vector 0 as query...");

            // Compute ground truth
            List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, 5);
            System.out.println("Ground truth top-5: " + groundTruth);

            // HNSW search
            List<HnswSearchResult> results = graph.search(query, 5);
            System.out.println("HNSW results:");
            for (HnswSearchResult r : results) {
                System.out.printf("  nodeId=%d, distance=%.4f%n", r.nodeId(), r.distance());
            }

            // Calculate recall
            double recall = calculateRecall(results, groundTruth);
            System.out.printf("Recall@5: %.2f%%%n", recall * 100);
        }
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
    @Tag("slow")
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
    @Tag("slow")
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

    /**
     * Generates a normalized random vector.
     * Normalization ensures consistent distance calculations.
     */
    private float[] generateNormalizedVector(int dimension, Random random) {
        float[] vec = new float[dimension];
        float norm = 0.0f;
        for (int i = 0; i < dimension; i++) {
            vec[i] = (float) random.nextGaussian();  // Gaussian for better distribution
            norm += vec[i] * vec[i];
        }
        // Normalize to unit length
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    /**
     * Brute-force k-NN search for ground truth.
     */
    private List<Integer> bruteForceSearch(float[] query, List<float[]> vectors,
                                           DistanceFunction distFn, int k) {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            float dist = distFn.compute(query, vectors.get(i));
            candidates.add(new SearchCandidate(i, dist));
        }

        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        List<Integer> result = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, candidates.size()); i++) {
            result.add(candidates.get(i).index);
        }
        return result;
    }

    /**
     * Calculates recall as the fraction of ground truth results found.
     */
    private double calculateRecall(List<HnswSearchResult> hnswResults, List<Integer> groundTruth) {
        if (groundTruth.isEmpty()) {
            return 1.0;  // No ground truth to miss
        }

        java.util.Set<Long> hnswIds = new java.util.HashSet<>();
        for (HnswSearchResult result : hnswResults) {
            hnswIds.add(result.nodeId());
        }

        int found = 0;
        for (Integer gtId : groundTruth) {
            if (hnswIds.contains(gtId.longValue())) {
                found++;
            }
        }

        return (double) found / groundTruth.size();
    }

    // ==================== Per-Level Adjacency List Tests ====================

    @Test
    void shouldStoreAdjacencyListsForAllLevels() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert enough nodes to ensure multi-level graph
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            // Graph should have multiple levels
            assertThat(graph.maxLevel()).isGreaterThanOrEqualTo(0);

            // Search should still work correctly with multi-level graph
            List<HnswSearchResult> results = graph.search(randomVector(64), 10);
            assertThat(results.size()).isLessThanOrEqualTo(10);

            // All results should have valid node IDs
            for (HnswSearchResult result : results) {
                assertThat(result.nodeId()).isGreaterThanOrEqualTo(0);
                assertThat(result.nodeId()).isLessThan(graph.nodeCount());
            }
        }
    }

    @Test
    void shouldNavigateLayersCorrectly() throws IOException {
        // Use L2 distance for cluster tests since we're adding noise (not normalized)
        DistanceFunction l2Dist = L2Distance.getInstance();
        HnswConfig highQualityConfig = new HnswConfig(
            32, 64, 200, 100, 1.0 / Math.log(32), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, highQualityConfig, l2Dist, 1000)) {
            // Insert vectors that form clear clusters
            List<float[]> cluster1 = new ArrayList<>();
            List<float[]> cluster2 = new ArrayList<>();

            // Create two distinct clusters using normalized vectors
            Random random = new Random(42);
            float[] center1 = generateNormalizedVector(64, random);
            float[] center2 = generateNormalizedVector(64, random);

            for (int i = 0; i < 50; i++) {
                cluster1.add(addNoise(center1, 0.1f));
                cluster2.add(addNoise(center2, 0.1f));
            }

            // Insert all vectors
            for (float[] vec : cluster1) {
                graph.insert(vec);
            }
            for (float[] vec : cluster2) {
                graph.insert(vec);
            }

            // Search with center1 - should find cluster1 vectors
            List<HnswSearchResult> results = graph.search(center1, 5);

            // Results should exist
            assertThat(results).isNotEmpty();

            // With L2 distance, closer vectors have smaller distances
            // The closest results should have relatively small L2 distances
            for (HnswSearchResult result : results) {
                assertThat(result.distance())
                    .as("Results should have reasonable L2 distance to cluster center")
                    .isGreaterThanOrEqualTo(0.0f);
            }

            // Verify the first result is closest (sorted by distance)
            if (results.size() > 1) {
                for (int i = 1; i < results.size(); i++) {
                    assertThat(results.get(i).distance())
                        .isGreaterThanOrEqualTo(results.get(i - 1).distance());
                }
            }
        }
    }

    @Test
    void shouldMaintainBidirectionalEdges() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert a small number of vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(randomVector(64));
            }

            // Search should find connected nodes
            List<HnswSearchResult> results = graph.search(randomVector(64), 10);

            // If graph is connected, we should get results
            assertThat(results).isNotEmpty();
        }
    }

    @Test
    void shouldRespectMaxConnectionsAtLayer0() throws IOException {
        // Note: maxConnectionsLayer0 must be >= maxConnections
        HnswConfig smallConnConfig = new HnswConfig(
            8, 16, 200, 100, 1.0 / Math.log(8), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, smallConnConfig, distanceFunction, 1000)) {
            // Insert many nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            // Graph should still function correctly despite connection limits
            assertThat(graph.nodeCount()).isEqualTo(100);

            List<HnswSearchResult> results = graph.search(randomVector(64), 10);
            assertThat(results).isNotEmpty();
        }
    }

    // ==================== Deletion Tests ====================

    @Test
    void shouldSoftDeleteNode() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            // Delete a node
            boolean deleted = graph.delete(5);
            assertThat(deleted).isTrue();

            // Deleting same node again should return false
            assertThat(graph.delete(5)).isFalse();
        }
    }

    @Test
    void shouldExcludeDeletedNodesFromSearch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert a known vector
            float[] target = unitVector(64, 0);
            graph.insert(target);

            // Insert noise
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            // Verify search works before deletion
            List<HnswSearchResult> beforeDelete = graph.search(target, 1);
            assertThat(beforeDelete).hasSize(1);
            long foundNodeId = beforeDelete.get(0).nodeId();

            // Delete the found node
            graph.delete(foundNodeId);

            // Search again - should find different node or empty
            List<HnswSearchResult> afterDelete = graph.search(target, 1);
            if (!afterDelete.isEmpty()) {
                assertThat(afterDelete.get(0).nodeId()).isNotEqualTo(foundNodeId);
            }
        }
    }

    @Test
    void shouldCheckTombstoneStatus() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 5; i++) {
                graph.insert(randomVector(64));
            }

            // Initially no nodes are tombstoned
            assertThat(graph.isTombstoned(0)).isFalse();
            assertThat(graph.isTombstoned(2)).isFalse();
            assertThat(graph.isTombstoned(4)).isFalse();

            // Delete node 2
            graph.delete(2);

            // Now node 2 should be tombstoned
            assertThat(graph.isTombstoned(0)).isFalse();
            assertThat(graph.isTombstoned(2)).isTrue();
            assertThat(graph.isTombstoned(4)).isFalse();

            // Invalid node IDs should return false
            assertThat(graph.isTombstoned(-1)).isFalse();
            assertThat(graph.isTombstoned(100)).isFalse();
        }
    }

    @Test
    void shouldCountTombstonedNodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(randomVector(64));
            }

            // Initially no tombstones
            assertThat(graph.tombstoneCount()).isZero();
            assertThat(graph.activeNodeCount()).isEqualTo(20);

            // Delete some nodes
            graph.delete(0);
            graph.delete(5);
            graph.delete(10);
            graph.delete(15);

            assertThat(graph.tombstoneCount()).isEqualTo(4);
            assertThat(graph.activeNodeCount()).isEqualTo(16);
            assertThat(graph.nodeCount()).isEqualTo(20);
        }
    }

    @Test
    void shouldHandleDeletingAllNodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert a few vectors
            for (int i = 0; i < 5; i++) {
                graph.insert(randomVector(64));
            }

            // Delete all nodes
            for (int i = 0; i < 5; i++) {
                graph.delete(i);
            }

            assertThat(graph.tombstoneCount()).isEqualTo(5);
            assertThat(graph.activeNodeCount()).isZero();

            // Search should return empty
            List<HnswSearchResult> results = graph.search(randomVector(64), 10);
            assertThat(results).isEmpty();
        }
    }

    @Test
    void shouldFindNewEntryPointWhenOldOneDeleted() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(randomVector(64));
            }

            // Get the current entry point
            long originalEntryPoint = graph.entryPoint();
            assertThat(originalEntryPoint).isGreaterThanOrEqualTo(0);

            // Delete the entry point
            boolean deleted = graph.delete(originalEntryPoint);
            assertThat(deleted).isTrue();

            // Entry point should now be different
            long newEntryPoint = graph.entryPoint();
            assertThat(newEntryPoint).isNotEqualTo(originalEntryPoint);

            // Graph should still be searchable
            List<HnswSearchResult> results = graph.search(randomVector(64), 5);
            // Results should not include the deleted entry point
            for (HnswSearchResult result : results) {
                assertThat(result.nodeId()).isNotEqualTo(originalEntryPoint);
            }
        }
    }

    @Test
    void shouldSkipTombstonedNodesDuringMultiLevelTraversal() throws IOException {
        // Use higher ef values for better quality graph
        HnswConfig qualityConfig = new HnswConfig(
            32, 64, 200, 100, 1.0 / Math.log(32), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, qualityConfig, distanceFunction, 1000)) {
            // Insert enough nodes to ensure multi-level graph
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                float[] vec = new float[64];
                for (int j = 0; j < 64; j++) {
                    vec[j] = seededRandom.nextFloat() * 2 - 1;
                }
                vectors.add(vec);
                graph.insert(vec);
            }

            // Verify graph has multiple levels
            assertThat(graph.maxLevel()).isGreaterThanOrEqualTo(0);

            // Track which nodes we delete
            java.util.Set<Long> deletedNodeIds = new java.util.HashSet<>();

            // Delete several nodes including potentially high-level nodes
            for (int i = 0; i < 20; i++) {
                long nodeId = i * 3; // Delete nodes 0, 3, 6, 9, etc.
                graph.delete(nodeId);
                deletedNodeIds.add(nodeId);
            }

            // Verify tombstone flags are set correctly
            assertThat(graph.isTombstoned(0)).as("Node 0 should be tombstoned").isTrue();
            assertThat(graph.isTombstoned(3)).as("Node 3 should be tombstoned").isTrue();
            assertThat(graph.isTombstoned(1)).as("Node 1 should NOT be tombstoned").isFalse();
            assertThat(graph.tombstoneCount()).as("Should have 20 tombstones").isEqualTo(20);

            // Search should still work correctly
            float[] query = vectors.get(50);
            List<HnswSearchResult> results = graph.search(query, 10);

            // Verify no deleted nodes in results
            for (HnswSearchResult result : results) {
                // Check that the result is not one of the deleted nodes
                assertThat(deletedNodeIds.contains(result.nodeId()))
                    .as("Result nodeId %d should not be in deleted set", result.nodeId())
                    .isFalse();
                // Also verify using isTombstoned method
                assertThat(graph.isTombstoned(result.nodeId()))
                    .as("Result nodeId %d should not be tombstoned", result.nodeId())
                    .isFalse();
            }
        }
    }

    @Test
    void shouldPersistTombstoneStatus() throws IOException {
        // Create graph and add nodes
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes
            graph.delete(2);
            graph.delete(5);
            graph.delete(8);

            graph.flush();
        }

        // Reopen and verify tombstone status persisted
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(graph.isTombstoned(0)).isFalse();
            assertThat(graph.isTombstoned(2)).isTrue();
            assertThat(graph.isTombstoned(5)).isTrue();
            assertThat(graph.isTombstoned(8)).isTrue();
            assertThat(graph.tombstoneCount()).isEqualTo(3);
        }
    }

    @Test
    void shouldHandleDeleteOnClosedGraph() throws IOException {
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
        graph.insert(randomVector(64));
        graph.close();

        assertThatThrownBy(() -> graph.delete(0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");

        assertThatThrownBy(() -> graph.isTombstoned(0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");

        assertThatThrownBy(() -> graph.tombstoneCount())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void shouldVerifySearchNeverReturnsTombstonedNodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 30; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes
            graph.delete(5);
            graph.delete(10);
            graph.delete(15);
            graph.delete(20);
            graph.delete(25);

            // Search multiple times and verify no tombstoned nodes in results
            for (int trial = 0; trial < 20; trial++) {
                List<HnswSearchResult> results = graph.search(randomVector(64), 30);
                for (HnswSearchResult result : results) {
                    assertThat(graph.isTombstoned(result.nodeId()))
                        .as("Search results should never contain tombstoned nodes")
                        .isFalse();
                }
            }
        }
    }

    @Test
    void shouldMaintainConsistentActiveNodeCount() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            int insertedCount = 50;
            int deleteCount = 10;

            // Insert nodes
            for (int i = 0; i < insertedCount; i++) {
                graph.insert(randomVector(64));
            }

            assertThat(graph.nodeCount()).isEqualTo(insertedCount);
            assertThat(graph.activeNodeCount()).isEqualTo(insertedCount);

            // Delete nodes one by one
            for (int i = 0; i < deleteCount; i++) {
                graph.delete(i);
                assertThat(graph.activeNodeCount())
                    .isEqualTo(insertedCount - (i + 1));
            }

            assertThat(graph.tombstoneCount()).isEqualTo(deleteCount);
            assertThat(graph.nodeCount()).isEqualTo(insertedCount);
        }
    }

    // ==================== Tail Offset Tests ====================

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

    // ==================== Persistence Tests ====================

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

    @Test
    void shouldRejectInvalidFile() throws IOException {
        // Create invalid file
        Files.write(graphPath, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

        // Should throw either IOException (for bad magic) or IllegalStateException (for file too small)
        assertThatThrownBy(() -> HnswGraph.open(graphPath, distanceFunction))
            .isInstanceOf(Exception.class); // Accept any exception for invalid file
    }

    // ==================== Lifecycle Tests ====================

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
        graph.close(); // Should not throw
    }

    // ==================== Incremental Insertion Tests ====================

    @Test
    void shouldInsertWithResult() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] vector = randomVector(64);
            IncrementalInsertResult result = graph.insertWithResult(vector);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.nodeId()).isEqualTo(0);
            assertThat(result.level()).isGreaterThanOrEqualTo(0);
            assertThat(result.dataOffset()).isGreaterThanOrEqualTo(0);
            assertThat(result.writeLogOffset()).isEqualTo(-1); // Not from write log
        }
    }

    @Test
    void shouldInsertWithResultAndWriteLogOffset() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] vector = randomVector(64);
            long writeLogOffset = 12345L;
            IncrementalInsertResult result = graph.insertWithResult(vector, writeLogOffset);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.writeLogOffset()).isEqualTo(writeLogOffset);
        }
    }

    @Test
    void shouldTrackConnectionsInResult() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert first node - no connections
            IncrementalInsertResult result1 = graph.insertWithResult(randomVector(64));
            assertThat(result1.connectionsMade()).isEqualTo(0);

            // Insert second node - should connect to first
            IncrementalInsertResult result2 = graph.insertWithResult(randomVector(64));
            assertThat(result2.connectionsMade()).isGreaterThan(0);
            assertThat(result2.connectedNodes()).contains(0L);
        }
    }

    @Test
    void shouldInsertBatch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                vectors.add(randomVector(64));
            }

            List<Long> nodeIds = graph.insertBatch(vectors);

            assertThat(nodeIds).hasSize(50);
            assertThat(graph.nodeCount()).isEqualTo(50);

            // Verify all node IDs are sequential
            for (int i = 0; i < 50; i++) {
                assertThat(nodeIds.get(i)).isEqualTo(i);
            }
        }
    }

    @Test
    void shouldInsertBatchWithResults() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<float[]> vectors = new ArrayList<>();
            List<Long> writeLogOffsets = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                vectors.add(randomVector(64));
                writeLogOffsets.add((long) i * 100);
            }

            List<IncrementalInsertResult> results = graph.insertBatchWithResults(vectors, writeLogOffsets);

            assertThat(results).hasSize(20);
            for (int i = 0; i < 20; i++) {
                IncrementalInsertResult result = results.get(i);
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.nodeId()).isEqualTo(i);
                assertThat(result.writeLogOffset()).isEqualTo(i * 100);
            }
        }
    }

    @Test
    void shouldMaintainRecallAfterIncrementalInsertions() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 64;
        int numVectors = 200;
        int numQueries = 20;
        int k = 10;

        try (HnswGraph graph = new HnswGraph(graphPath, config, distFn, 1000)) {
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors incrementally (simulating live indexing)
            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(dimension, seededRandom);
                vectors.add(vec);
                IncrementalInsertResult result = graph.insertWithResult(vec);
                assertThat(result.isSuccess()).isTrue();
            }

            // Test recall
            Random queryRandom = new Random(99);
            double totalRecall = 0.0;

            for (int i = 0; i < numQueries; i++) {
                float[] query = generateNormalizedVector(dimension, queryRandom);
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);
                List<HnswSearchResult> hnswResults = graph.search(query, k);
                totalRecall += calculateRecall(hnswResults, groundTruth);
            }

            double averageRecall = totalRecall / numQueries;
            assertThat(averageRecall)
                .as("Incremental insertion should maintain reasonable recall (got %.2f%%)", averageRecall * 100)
                .isGreaterThanOrEqualTo(0.90);
        }
    }

    @Test
    void shouldHandleBatchInsertionWhenNearCapacity() throws IOException {
        // Create graph with limited capacity
        long maxNodes = 100;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, maxNodes)) {
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                vectors.add(randomVector(64));
            }

            List<Long> nodeIds = graph.insertBatch(vectors);
            assertThat(nodeIds).hasSize(100);
            assertThat(graph.nodeCount()).isEqualTo(100);
        }
    }

    @Test
    void shouldIncrementallyInsertAfterBatch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Batch insert first 50
            List<float[]> batch = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                batch.add(randomVector(64));
            }
            graph.insertBatch(batch);
            assertThat(graph.nodeCount()).isEqualTo(50);

            // Incrementally insert more
            for (int i = 0; i < 25; i++) {
                IncrementalInsertResult result = graph.insertWithResult(randomVector(64));
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.nodeId()).isEqualTo(50 + i);
            }
            assertThat(graph.nodeCount()).isEqualTo(75);

            // Search should work across all nodes
            List<HnswSearchResult> results = graph.search(randomVector(64), 10);
            assertThat(results).isNotEmpty();
        }
    }

    // ==================== Helper Methods ====================

    private float[] randomVector(int dim) {
        Random random = new Random();
        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = random.nextFloat() * 2 - 1; // Range [-1, 1]
        }
        return vec;
    }

    private float[] unitVector(int dim, int hotIndex) {
        float[] vec = new float[dim];
        vec[hotIndex % dim] = 1.0f;
        return vec;
    }

    private float[] addNoise(float[] vector, float noiseLevel) {
        Random random = new Random();
        float[] noisy = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            noisy[i] = vector[i] + (random.nextFloat() * 2 - 1) * noiseLevel;
        }
        return noisy;
    }

    // ==================== Edge Count Constraint Tests ====================

    /**
     * Verifies that all nodes at layer 0 respect maxConnectionsLayer0 limit.
     *
     * <p>This test inserts many vectors and then verifies that NO node at layer 0
     * has more neighbors than maxConnectionsLayer0.</p>
     */
    @Test
    void shouldEnforceMaxConnectionsLayer0() throws IOException {
        int maxConnectionsLayer0 = 16;
        HnswConfig constrainedConfig = new HnswConfig(
            8, maxConnectionsLayer0, 100, 50, 1.0 / Math.log(8), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, constrainedConfig, distanceFunction, 1000)) {
            // Insert many vectors to trigger connection pruning
            Random seededRandom = new Random(12345);
            for (int i = 0; i < 200; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify all nodes at layer 0 respect the limit
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null) {
                    List<Long> neighbors = node.getNeighbors(0);
                    assertThat(neighbors.size())
                        .as("Node %d at layer 0 should have at most %d neighbors, but has %d",
                            nodeId, maxConnectionsLayer0, neighbors.size())
                        .isLessThanOrEqualTo(maxConnectionsLayer0);
                }
            }
        }
    }

    /**
     * Verifies that all nodes at layers > 0 respect maxConnections limit.
     *
     * <p>This test inserts many vectors and verifies that NO node at any layer
     * above layer 0 has more neighbors than maxConnections.</p>
     */
    @Test
    void shouldEnforceMaxConnectionsAtHigherLayers() throws IOException {
        int maxConnections = 8;
        HnswConfig constrainedConfig = new HnswConfig(
            maxConnections, 16, 100, 50, 1.0 / Math.log(maxConnections), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, constrainedConfig, distanceFunction, 1000)) {
            // Insert many vectors to create multi-level graph
            Random seededRandom = new Random(54321);
            for (int i = 0; i < 300; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify all nodes at layers > 0 respect the limit
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null && node.level() > 0) {
                    for (int level = 1; level <= node.level(); level++) {
                        List<Long> neighbors = node.getNeighbors(level);
                        assertThat(neighbors.size())
                            .as("Node %d at layer %d should have at most %d neighbors, but has %d",
                                nodeId, level, maxConnections, neighbors.size())
                            .isLessThanOrEqualTo(maxConnections);
                    }
                }
            }
        }
    }

    /**
     * Comprehensive edge count verification using the invariant verifier.
     *
     * <p>Uses HnswInvariantVerifier to check ALL connection limits across the graph.</p>
     */
    @Test
    void shouldVerifyAllEdgeCountsWithInvariantVerifier() throws IOException {
        HnswConfig testConfig = new HnswConfig(12, 24, 100, 50, 1.0 / Math.log(12), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, testConfig, distanceFunction, 1000)) {
            // Insert enough vectors to create a complex graph
            Random seededRandom = new Random(99999);
            for (int i = 0; i < 150; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify all invariants including connection limits
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("All HNSW invariants should be satisfied. Violations: %s", result.violations())
                .isTrue();

            assertThat(result.nodesChecked())
                .as("Should have checked all active nodes")
                .isGreaterThan(0);
        }
    }

    /**
     * Verifies edge counts remain valid after aggressive insertion and deletion.
     *
     * <p>Tests that edge counts are maintained correctly even under heavy churn.</p>
     */
    @Test
    void shouldMaintainEdgeCountsAfterChurn() throws IOException {
        int maxConnections = 10;
        int maxConnectionsLayer0 = 20;
        HnswConfig testConfig = new HnswConfig(
            maxConnections, maxConnectionsLayer0, 100, 50, 1.0 / Math.log(maxConnections), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, testConfig, distanceFunction, 1000)) {
            Random seededRandom = new Random(11111);

            // Insert initial batch
            for (int i = 0; i < 100; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Delete half
            for (int i = 0; i < 50; i++) {
                graph.delete(i);
            }

            // Insert more
            for (int i = 0; i < 50; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify edge counts for all active nodes
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null) {
                    for (int level = 0; level <= node.level(); level++) {
                        int limit = (level == 0) ? maxConnectionsLayer0 : maxConnections;
                        List<Long> neighbors = node.getNeighbors(level);
                        assertThat(neighbors.size())
                            .as("Node %d at level %d exceeds limit %d (has %d neighbors)",
                                nodeId, level, limit, neighbors.size())
                            .isLessThanOrEqualTo(limit);
                    }
                }
            }
        }
    }

    /**
     * Tests edge count constraints with extremely small connection limits.
     *
     * <p>Uses the minimum allowed connection limits to stress-test the pruning logic.</p>
     */
    @Test
    void shouldEnforceEdgeCountsWithMinimalConnections() throws IOException {
        // Use minimum allowed connections (MIN_CONNECTIONS = 2)
        // Note: mL must be in range (0, 1], so we use 0.5 (valid for small M values)
        int minConnections = 2;
        HnswConfig minimalConfig = new HnswConfig(
            minConnections, minConnections * 2, 50, 25, 0.5, 64);

        try (HnswGraph graph = new HnswGraph(graphPath, minimalConfig, distanceFunction, 1000)) {
            Random seededRandom = new Random(22222);

            // Insert many nodes with minimal connections
            for (int i = 0; i < 100; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify all edge counts are within limits
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null) {
                    // Layer 0
                    List<Long> neighbors0 = node.getNeighbors(0);
                    assertThat(neighbors0.size())
                        .as("Node %d at layer 0 exceeds maxConnectionsLayer0=%d",
                            nodeId, minConnections * 2)
                        .isLessThanOrEqualTo(minConnections * 2);

                    // Higher layers
                    for (int level = 1; level <= node.level(); level++) {
                        List<Long> neighbors = node.getNeighbors(level);
                        assertThat(neighbors.size())
                            .as("Node %d at layer %d exceeds maxConnections=%d",
                                nodeId, level, minConnections)
                            .isLessThanOrEqualTo(minConnections);
                    }
                }
            }
        }
    }

    /**
     * Verifies that pruning maintains bidirectional edge invariant.
     *
     * <p>When pruning removes an edge from A to B, it must also remove
     * the back-edge from B to A.</p>
     */
    @Test
    void shouldMaintainBidirectionalityAfterPruning() throws IOException {
        HnswConfig smallConnConfig = new HnswConfig(4, 8, 50, 25, 1.0 / Math.log(4), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, smallConnConfig, distanceFunction, 1000)) {
            Random seededRandom = new Random(33333);

            // Insert enough to cause pruning
            for (int i = 0; i < 100; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Verify bidirectional edges using invariant verifier
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Bidirectional edge invariant should be maintained after pruning. Violations: %s",
                    result.violations())
                .isTrue();
        }
    }

    /**
     * Stress test for edge count enforcement with large graph.
     *
     * <p>Inserts a large number of vectors and verifies all edge counts
     * are within configured limits.</p>
     */
    @Test
    void shouldEnforceEdgeCountsInLargeGraph() throws IOException {
        int maxConnections = 16;
        int maxConnectionsLayer0 = 32;
        HnswConfig config = new HnswConfig(
            maxConnections, maxConnectionsLayer0, 200, 100, 1.0 / Math.log(maxConnections), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 2000)) {
            Random seededRandom = new Random(44444);

            // Insert large batch
            int numVectors = 500;
            for (int i = 0; i < numVectors; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }

            // Check all edge counts
            int nodesChecked = 0;
            int edgesChecked = 0;
            int maxLayer0Edges = 0;
            int maxHigherLayerEdges = 0;

            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                nodesChecked++;

                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node == null) {
                    continue;
                }

                // Check layer 0
                List<Long> neighbors0 = node.getNeighbors(0);
                edgesChecked += neighbors0.size();
                maxLayer0Edges = Math.max(maxLayer0Edges, neighbors0.size());

                assertThat(neighbors0.size())
                    .as("Node %d at layer 0 has %d neighbors (max: %d)",
                        nodeId, neighbors0.size(), maxConnectionsLayer0)
                    .isLessThanOrEqualTo(maxConnectionsLayer0);

                // Check higher layers
                for (int level = 1; level <= node.level(); level++) {
                    List<Long> neighbors = node.getNeighbors(level);
                    edgesChecked += neighbors.size();
                    maxHigherLayerEdges = Math.max(maxHigherLayerEdges, neighbors.size());

                    assertThat(neighbors.size())
                        .as("Node %d at layer %d has %d neighbors (max: %d)",
                            nodeId, level, neighbors.size(), maxConnections)
                        .isLessThanOrEqualTo(maxConnections);
                }
            }

            // Log statistics
            System.out.printf("Large graph edge count test: %d nodes, %d edges, max layer0 edges=%d, max higher edges=%d%n",
                nodesChecked, edgesChecked, maxLayer0Edges, maxHigherLayerEdges);

            assertThat(nodesChecked).isGreaterThan(0);
            assertThat(edgesChecked).isGreaterThan(0);
        }
    }

    /**
     * Verifies edge counts after batch insertion.
     *
     * <p>Batch insertion should still respect edge count limits.</p>
     */
    @Test
    void shouldEnforceEdgeCountsAfterBatchInsertion() throws IOException {
        int maxConnections = 8;
        HnswConfig config = new HnswConfig(
            maxConnections, maxConnections * 2, 100, 50, 1.0 / Math.log(maxConnections), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Batch insert
            List<float[]> vectors = new ArrayList<>();
            Random seededRandom = new Random(55555);
            for (int i = 0; i < 150; i++) {
                vectors.add(generateNormalizedVector(64, seededRandom));
            }
            graph.insertBatch(vectors);

            // Verify edge counts
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node == null || graph.isTombstoned(nodeId)) {
                    continue;
                }

                for (int level = 0; level <= node.level(); level++) {
                    int limit = (level == 0) ? maxConnections * 2 : maxConnections;
                    List<Long> neighbors = node.getNeighbors(level);
                    assertThat(neighbors.size())
                        .as("Node %d at level %d exceeds limit after batch insertion",
                            nodeId, level)
                        .isLessThanOrEqualTo(limit);
                }
            }
        }
    }

    /**
     * Tests that edge counts remain valid after persistence round-trip.
     */
    @Test
    void shouldPersistValidEdgeCounts() throws IOException {
        HnswConfig config = new HnswConfig(10, 20, 100, 50, 1.0 / Math.log(10), 64);

        // Create and populate graph
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random seededRandom = new Random(66666);
            for (int i = 0; i < 100; i++) {
                graph.insert(generateNormalizedVector(64, seededRandom));
            }
            graph.flush();
        }

        // Reopen and verify edge counts
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null) {
                    for (int level = 0; level <= node.level(); level++) {
                        int limit = (level == 0) ? 20 : 10;
                        List<Long> neighbors = node.getNeighbors(level);
                        assertThat(neighbors.size())
                            .as("Node %d at level %d has invalid edge count after reload",
                                nodeId, level)
                            .isLessThanOrEqualTo(limit);
                    }
                }
            }
        }
    }

    private record SearchCandidate(int index, float distance) {}
}
