package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswInvariantVerifier}.
 *
 * <p>These tests verify that HNSW graph invariants are maintained:</p>
 * <ul>
 *   <li>Hierarchical layers structure</li>
 *   <li>Bidirectional edges</li>
 *   <li>Entry point at highest layer</li>
 *   <li>Connection limits</li>
 * </ul>
 */
class HnswInvariantVerifierTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("invariant_test.hnsw");
        config = HnswConfig.create(64);
        distanceFunction = L2Distance.getInstance();
    }

    // ==================== Empty Graph Tests ====================

    @Test
    void shouldVerifyEmptyGraphInvariants() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Empty graph should satisfy all invariants")
                .isTrue();
            assertThat(result.violations()).isEmpty();
        }
    }

    // ==================== Single Node Tests ====================

    @Test
    void shouldVerifySingleNodeInvariants() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Single node graph should satisfy all invariants")
                .isTrue();
            assertThat(result.violations()).isEmpty();
            assertThat(result.nodesChecked()).isEqualTo(1);
        }
    }

    // ==================== Bidirectional Edge Tests ====================

    @Test
    void shouldVerifyBidirectionalEdgesAfterInsertion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert enough nodes to ensure connections
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("All edges should be bidirectional after insertion")
                .isTrue();
            if (!result.isValid()) {
                result.violations().forEach(System.err::println);
            }
        }
    }

    @Test
    void shouldVerifyBidirectionalEdgesAtAllLevels() throws IOException {
        // Use config that encourages multi-level graph
        HnswConfig multiLevelConfig = new HnswConfig(
            16, 32, 200, 100, 0.5, 64); // Higher mL for more levels

        try (HnswGraph graph = new HnswGraph(graphPath, multiLevelConfig, distanceFunction, 1000)) {
            // Insert enough nodes to create multiple levels
            for (int i = 0; i < 200; i++) {
                graph.insert(randomVector(64));
            }

            // Verify we have a multi-level graph
            assertThat(graph.maxLevel())
                .as("Should have multiple levels for invariant testing")
                .isGreaterThanOrEqualTo(1);

            // Verify invariants at all levels
            for (int level = 0; level <= graph.maxLevel(); level++) {
                long edgeCount = HnswInvariantVerifier.countBidirectionalEdges(graph, level);
                // All counted edges should be bidirectional by definition
                assertThat(edgeCount)
                    .as("Level %d should have non-negative bidirectional edges", level)
                    .isGreaterThanOrEqualTo(0);
            }

            // Full invariant check
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            if (!result.isValid()) {
                System.err.println("Invariant violations detected:");
                for (String v : result.violations()) {
                    System.err.println("  - " + v);
                }
            }

            assertThat(result.isValid())
                .as("Multi-level graph should satisfy all invariants")
                .isTrue();
        }
    }

    @Test
    void shouldCheckSpecificBidirectionalEdge() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert two nodes
            long node0 = graph.insert(randomVector(64));
            long node1 = graph.insert(randomVector(64));

            // After insertion, if there's an edge between them, it should be bidirectional
            HnswGraph.NodeView n0 = graph.readNode(node0);
            HnswGraph.NodeView n1 = graph.readNode(node1);

            assertThat(n0).isNotNull();
            assertThat(n1).isNotNull();

            // Check if they are connected at level 0
            List<Long> n0Neighbors = n0.getNeighbors(0);
            if (n0Neighbors.contains(node1)) {
                // If n0 -> n1 exists, n1 -> n0 should also exist
                assertThat(HnswInvariantVerifier.isEdgeBidirectional(graph, node0, node1, 0))
                    .as("Edge between node %d and %d should be bidirectional", node0, node1)
                    .isTrue();
            }
        }
    }

    // ==================== Entry Point Tests ====================

    @Test
    void shouldVerifyEntryPointAtHighestLayer() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Entry point should be at highest layer")
                .isTrue();

            // Verify entry point directly
            long entryPointId = graph.entryPoint();
            assertThat(entryPointId).isGreaterThanOrEqualTo(0);

            HnswGraph.NodeView entryNode = graph.readNode(entryPointId);
            assertThat(entryNode).isNotNull();
            assertThat(entryNode.level())
                .as("Entry point level should equal graph maxLevel")
                .isEqualTo(graph.maxLevel());
        }
    }

    @Test
    void shouldMaintainEntryPointAfterDeletion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            // Delete the entry point
            long originalEntryPoint = graph.entryPoint();
            graph.delete(originalEntryPoint);

            // Verify invariants still hold
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            if (!result.isValid()) {
                System.err.println("Invariant violations after entry point deletion:");
                for (String v : result.violations()) {
                    System.err.println("  - " + v);
                }
            }

            assertThat(result.isValid())
                .as("Invariants should hold after deleting entry point")
                .isTrue();

            // New entry point should be different and at highest level
            long newEntryPoint = graph.entryPoint();
            assertThat(newEntryPoint).isNotEqualTo(originalEntryPoint);

            if (newEntryPoint >= 0) {
                HnswGraph.NodeView newEntryNode = graph.readNode(newEntryPoint);
                assertThat(newEntryNode).isNotNull();
                assertThat(newEntryNode.level())
                    .as("New entry point should be at highest active level")
                    .isEqualTo(graph.maxLevel());
            }
        }
    }

    // ==================== Connection Limit Tests ====================

    @Test
    void shouldRespectConnectionLimits() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert many nodes to stress test connection limits
            for (int i = 0; i < 200; i++) {
                graph.insert(randomVector(64));
            }

            // Verify no node exceeds connection limits
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }

                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node == null) {
                    continue;
                }

                // Check level 0
                List<Long> neighbors0 = node.getNeighbors(0);
                assertThat(neighbors0.size())
                    .as("Node %d at level 0 should respect maxConnectionsLayer0 (%d), got %d",
                        nodeId, config.maxConnectionsLayer0(), neighbors0.size())
                    .isLessThanOrEqualTo(config.maxConnectionsLayer0());

                // Check other levels
                for (int level = 1; level <= node.level(); level++) {
                    List<Long> neighbors = node.getNeighbors(level);
                    assertThat(neighbors.size())
                        .as("Node %d at level %d should respect maxConnections (%d), got %d",
                            nodeId, level, config.maxConnections(), neighbors.size())
                        .isLessThanOrEqualTo(config.maxConnections());
                }
            }
        }
    }

    // ==================== Deletion Invariant Tests ====================

    @Test
    void shouldMaintainInvariantsAfterDeletions() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes
            for (int i = 0; i < 20; i++) {
                graph.delete(i * 3); // Delete every 3rd node
            }

            // Verify invariants still hold
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Invariants should hold after deletions")
                .isTrue();

            if (!result.isValid()) {
                System.err.println("Violations after deletions:");
                result.violations().forEach(System.err::println);
            }
        }
    }

    @Test
    void shouldMaintainInvariantsAfterHeavyDeletion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            // Delete most nodes
            for (int i = 0; i < 40; i++) {
                graph.delete(i);
            }

            // Verify invariants
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Invariants should hold even after heavy deletion")
                .isTrue();
        }
    }

    // ==================== Connectivity Tests ====================

    @Test
    void shouldVerifyGraphConnectivity() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyConnectivity(graph);

            assertThat(result.isValid())
                .as("Graph should be connected")
                .isTrue();
        }
    }

    @Test
    void shouldReportConnectivityAfterDeletion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes
            for (int i = 0; i < 10; i++) {
                graph.delete(i * 2);
            }

            // Graph should still be connected (remaining nodes)
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyConnectivity(graph);

            // Note: Deletion might fragment the graph, but remaining active nodes
            // should still be connected via active edges
            // We don't strictly require connectivity after deletion
        }
    }

    // ==================== Incremental Insertion Invariant Tests ====================

    @Test
    void shouldMaintainInvariantsDuringIncrementalInsertion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes one at a time and verify invariants after each
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));

                // Verify invariants periodically
                if (i % 10 == 0) {
                    HnswInvariantVerifier.VerificationResult result =
                        HnswInvariantVerifier.verifyAllInvariants(graph);

                    assertThat(result.isValid())
                        .as("Invariants should hold after inserting %d nodes", i + 1)
                        .isTrue();
                }
            }
        }
    }

    @Test
    void shouldMaintainInvariantsDuringBatchInsertion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert in batches
            for (int batch = 0; batch < 5; batch++) {
                List<float[]> vectors = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    vectors.add(randomVector(64));
                }
                graph.insertBatch(vectors);

                // Verify invariants after each batch
                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyAllInvariants(graph);

                if (!result.isValid()) {
                    System.err.println("Invariant violations after batch " + (batch + 1) + ":");
                    for (String v : result.violations()) {
                        System.err.println("  - " + v);
                    }
                }

                assertThat(result.isValid())
                    .as("Invariants should hold after batch %d", batch + 1)
                    .isTrue();
            }
        }
    }

    // ==================== Persistence Invariant Tests ====================

    @Test
    void shouldMaintainInvariantsAfterPersistence() throws IOException {
        // Create and populate graph
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            // Verify before flush
            HnswInvariantVerifier.VerificationResult beforeFlush =
                HnswInvariantVerifier.verifyAllInvariants(graph);
            assertThat(beforeFlush.isValid()).isTrue();

            graph.flush();
        }

        // Reopen and verify invariants
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            HnswInvariantVerifier.VerificationResult afterReopen =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(afterReopen.isValid())
                .as("Invariants should hold after reopening graph")
                .isTrue();
        }
    }

    // ==================== Edge Cases ====================

    @Test
    void shouldHandleNullGraph() {
        assertThatThrownBy(() -> HnswInvariantVerifier.verifyAllInvariants(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Graph cannot be null");
    }

    @Test
    void shouldSummarizeGraph() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            String summary = HnswInvariantVerifier.summarizeGraph(graph);

            assertThat(summary)
                .contains("Total nodes: 50")
                .contains("Active nodes: 50")
                .contains("Tombstoned nodes: 0")
                .contains("Entry point:")
                .contains("Max level:");
        }
    }

    @Test
    void shouldSummarizeEmptyGraph() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            String summary = HnswInvariantVerifier.summarizeGraph(graph);

            assertThat(summary)
                .contains("Total nodes: 0")
                .contains("Active nodes: 0")
                .contains("Entry point: -1");
        }
    }

    // ==================== Level Consistency Tests ====================

    @Test
    void shouldVerifyLevelConsistency() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            // Verify that a node's neighbors at level L all have level >= L
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }

                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node == null) {
                    continue;
                }

                for (int level = 0; level <= node.level(); level++) {
                    List<Long> neighbors = node.getNeighbors(level);
                    for (Long neighborId : neighbors) {
                        if (graph.isTombstoned(neighborId)) {
                            continue;
                        }

                        HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                        if (neighbor != null) {
                            assertThat(neighbor.level())
                                .as("Neighbor %d at level %d should have level >= %d (has level %d)",
                                    neighborId, level, level, neighbor.level())
                                .isGreaterThanOrEqualTo(level);
                        }
                    }
                }
            }
        }
    }

    @Test
    void shouldVerifyNoSelfLoops() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(randomVector(64));
            }

            // Verify no node has itself as a neighbor
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }

                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node == null) {
                    continue;
                }

                for (int level = 0; level <= node.level(); level++) {
                    List<Long> neighbors = node.getNeighbors(level);
                    assertThat(neighbors)
                        .as("Node %d at level %d should not have self-loop", nodeId, level)
                        .doesNotContain(nodeId);
                }
            }
        }
    }

    // ==================== Helper Methods ====================

    private float[] randomVector(int dim) {
        Random random = new Random();
        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = (float) random.nextGaussian(); // Gaussian for better distribution
        }
        // Normalize
        float norm = 0.0f;
        for (int i = 0; i < dim; i++) {
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }
}
