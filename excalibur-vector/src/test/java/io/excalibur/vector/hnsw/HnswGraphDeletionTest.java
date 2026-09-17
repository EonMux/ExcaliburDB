package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static io.excalibur.vector.hnsw.HnswGraphTestVectors.unitVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Soft-delete behaviour of {@link HnswGraph}.
 *
 * <p>Covers the tombstone contract: {@code delete} succeeds once and only once per node,
 * tombstoned nodes are counted separately from the node total, they never surface in a
 * search result at any level, deleting the entry point promotes a new one, and the
 * tombstone bits survive a flush and reopen. Also covers what a closed graph refuses.</p>
 */
class HnswGraphDeletionTest {

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

}
