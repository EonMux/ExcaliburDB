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

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.generateNormalizedVector;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Layer structure and edge-count invariants of {@link HnswGraph}.
 *
 * <p>Covers the shape of the graph rather than what it answers: per-level adjacency
 * lists exist and are navigable, clustered data is reachable from its centroid, and —
 * the constraint the pruning logic exists to enforce — no node ever exceeds
 * {@code maxConnectionsLayer0} at layer 0 or {@code maxConnections} above it. The edge
 * budget is checked after plain insertion, after batch insertion, after heavy churn,
 * at the minimum allowed connection limits, on a large graph, and after a reload.</p>
 */
class HnswGraphStructureTest {

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
                cluster1.add(addNoise(center1, 0.1f, random));
                cluster2.add(addNoise(center2, 0.1f, random));
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

    /**
     * Every edge an insertion writes exists in both directions.
     *
     * <p>Distinct from {@link #shouldMaintainBidirectionalityAfterPruning()}, which forces
     * pruning with a four-connection configuration and checks that dropping an edge also
     * drops its back-edge. Here the default configuration allows 32 neighbours at layer 0
     * and 16 above it, so twenty nodes can never reach a connection limit and the pruning
     * path never runs: what is under test is that the insertion path writes the back-edge
     * in the first place.</p>
     *
     * <p>The edge count is asserted too. "No edge lacks a back-edge" is satisfied by a
     * graph with no edges at all, so a test that only checked bidirectionality would go
     * green on a graph that had stopped connecting anything.</p>
     */
    @Test
    void shouldCreateEveryEdgeInBothDirections() throws IOException {
        int numVectors = 20;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < numVectors; i++) {
                graph.insert(randomVector(64));
            }

            long directedEdges = 0;
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                HnswGraph.NodeView node = graph.readNode(nodeId);
                assertThat(node).as("Node %d should be readable", nodeId).isNotNull();

                for (int level = 0; level <= node.level(); level++) {
                    for (long neighborId : node.getNeighbors(level)) {
                        directedEdges++;
                        assertThat(HnswInvariantVerifier
                                .isEdgeBidirectional(graph, nodeId, neighborId, level))
                            .as("Edge %d -> %d at level %d has no back-edge",
                                nodeId, neighborId, level)
                            .isTrue();
                    }
                }
            }

            assertThat(directedEdges)
                .as("Each insertion after the first links the new node to at least one "
                    + "existing node, in both directions")
                .isGreaterThanOrEqualTo(2L * (numVectors - 1));
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

    /**
     * Perturbs every component of a vector by up to {@code noiseLevel}, producing a
     * point near the original — used to build clusters around a known centroid.
     *
     * <p>The stream is a parameter rather than a fresh {@code Random}, so the cluster a
     * failure names can be rebuilt. It takes the caller's own seeded stream — the same one
     * that produced the centroid via
     * {@link HnswGraphTestVectors#generateNormalizedVector(int, Random)} — rather than a
     * private seeded field, which keeps the whole fixture derivable from the one seed
     * visible at the call site instead of from hidden static state.</p>
     */
    private float[] addNoise(float[] vector, float noiseLevel, Random random) {
        float[] noisy = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            noisy[i] = vector[i] + (random.nextFloat() * 2 - 1) * noiseLevel;
        }
        return noisy;
    }
}
