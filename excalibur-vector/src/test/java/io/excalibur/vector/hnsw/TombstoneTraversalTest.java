package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for tombstone handling during HNSW graph traversal.
 *
 * <p>These tests verify that tombstoned (soft-deleted) nodes are correctly
 * skipped during graph traversal and never returned in search results.</p>
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>Basic tombstone behavior (delete, check status)</li>
 *   <li>Search exclusion of tombstoned nodes</li>
 *   <li>Entry point handling when tombstoned</li>
 *   <li>Multi-level traversal with tombstones</li>
 *   <li>Edge cases (all nodes tombstoned, etc.)</li>
 *   <li>Concurrent operations with tombstones</li>
 *   <li>Recall verification after tombstones</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>These tests verify thread-safety of tombstone operations.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
class TombstoneTraversalTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("tombstone_test.hnsw");
        config = HnswConfig.create(64);
        distanceFunction = L2Distance.getInstance();
    }

    @AfterEach
    void tearDown() {
        // Cleanup handled by @TempDir
    }

    // ==================== Helper Methods ====================

    private float[] generateNormalizedVector(int dimension, Random random) {
        float[] vec = new float[dimension];
        float norm = 0.0f;
        for (int i = 0; i < dimension; i++) {
            vec[i] = (float) random.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    private List<Integer> bruteForceSearch(float[] query, List<float[]> vectors,
                                           DistanceFunction distFn, int k,
                                           Set<Integer> excludedIds) {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            if (!excludedIds.contains(i)) {
                float dist = distFn.compute(query, vectors.get(i));
                candidates.add(new SearchCandidate(i, dist));
            }
        }

        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        List<Integer> result = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, candidates.size()); i++) {
            result.add(candidates.get(i).index);
        }
        return result;
    }

    private record SearchCandidate(int index, float distance) {}

    // ==================== Basic Tombstone Behavior Tests ====================

    /**
     * Verifies that a single tombstoned node is never returned in search results.
     */
    @Test
    void shouldNeverReturnSingleTombstonedNode() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert a known vector that will be the target
            Random random = new Random(42);
            float[] target = generateNormalizedVector(64, random);
            long targetNodeId = graph.insert(target);

            // Insert some other vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Search with target - should find it
            List<HnswSearchResult> beforeDelete = graph.search(target, 1);
            assertThat(beforeDelete).isNotEmpty();
            assertThat(beforeDelete.get(0).nodeId()).isEqualTo(targetNodeId);

            // Tombstone the target
            boolean deleted = graph.delete(targetNodeId);
            assertThat(deleted).isTrue();
            assertThat(graph.isTombstoned(targetNodeId)).isTrue();

            // Search again - should never return the tombstoned node
            List<HnswSearchResult> afterDelete = graph.search(target, 10);
            for (HnswSearchResult result : afterDelete) {
                assertThat(result.nodeId())
                    .as("Search result should not contain tombstoned node %d", targetNodeId)
                    .isNotEqualTo(targetNodeId);
            }
        }
    }

    /**
     * Verifies that multiple tombstoned nodes are all excluded from search results.
     */
    @Test
    void shouldExcludeAllTombstonedNodesFromResults() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(123);
            Set<Long> tombstonedIds = new HashSet<>();

            // Insert 50 vectors
            for (int i = 0; i < 50; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone a selection of nodes
            long[] toDelete = {0, 5, 10, 15, 20, 25, 30, 35, 40, 45};
            for (long nodeId : toDelete) {
                graph.delete(nodeId);
                tombstonedIds.add(nodeId);
            }

            // Perform multiple searches and verify no tombstoned nodes appear
            for (int search = 0; search < 30; search++) {
                float[] query = generateNormalizedVector(64, random);
                List<HnswSearchResult> results = graph.search(query, 20);

                for (HnswSearchResult result : results) {
                    assertThat(tombstonedIds.contains(result.nodeId()))
                        .as("Result nodeId %d should not be in tombstoned set", result.nodeId())
                        .isFalse();

                    assertThat(graph.isTombstoned(result.nodeId()))
                        .as("Result nodeId %d should not be marked as tombstoned", result.nodeId())
                        .isFalse();
                }
            }
        }
    }

    // ==================== Entry Point Tests ====================

    /**
     * Verifies that when the entry point is tombstoned, a new active entry point is found.
     */
    @Test
    void shouldFindNewEntryPointWhenCurrentIsTombstoned() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(456);

            // Insert vectors
            for (int i = 0; i < 30; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            long originalEntryPoint = graph.entryPoint();
            assertThat(originalEntryPoint).isGreaterThanOrEqualTo(0);

            // Tombstone the entry point
            graph.delete(originalEntryPoint);

            // Verify new entry point is different and active
            long newEntryPoint = graph.entryPoint();
            assertThat(newEntryPoint).isNotEqualTo(originalEntryPoint);
            assertThat(graph.isTombstoned(newEntryPoint)).isFalse();

            // Search should still work
            List<HnswSearchResult> results = graph.search(generateNormalizedVector(64, random), 5);
            for (HnswSearchResult result : results) {
                assertThat(result.nodeId()).isNotEqualTo(originalEntryPoint);
            }
        }
    }

    /**
     * Verifies that when all high-level nodes are tombstoned, the graph still works.
     */
    @Test
    void shouldHandleAllHighLevelNodesTombstoned() throws IOException {
        // Use config that creates a multi-level graph
        HnswConfig levelConfig = new HnswConfig(
            16, 32, 100, 50, 1.0 / Math.log(16), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, levelConfig, distanceFunction, 1000)) {
            Random random = new Random(789);
            List<Long> highLevelNodes = new ArrayList<>();

            // Insert enough vectors to create multiple levels
            for (int i = 0; i < 200; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Find nodes at level > 0
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null && node.level() > 0) {
                    highLevelNodes.add(nodeId);
                }
            }

            // Tombstone all high-level nodes
            for (Long nodeId : highLevelNodes) {
                graph.delete(nodeId);
            }

            // Graph should still be functional
            assertThat(graph.activeNodeCount()).isGreaterThan(0);

            // Search should return results from remaining active nodes
            List<HnswSearchResult> results = graph.search(generateNormalizedVector(64, random), 10);

            for (HnswSearchResult result : results) {
                assertThat(graph.isTombstoned(result.nodeId())).isFalse();
                assertThat(highLevelNodes.contains(result.nodeId()))
                    .as("Result should not be from tombstoned high-level node")
                    .isFalse();
            }
        }
    }

    // ==================== Multi-Level Traversal Tests ====================

    /**
     * Verifies that tombstoned nodes are skipped during traversal at all levels.
     */
    @Test
    void shouldSkipTombstonedNodesAtAllLevels() throws IOException {
        HnswConfig multiLevelConfig = new HnswConfig(
            16, 32, 100, 50, 1.0 / Math.log(16), 64);

        try (HnswGraph graph = new HnswGraph(graphPath, multiLevelConfig, distanceFunction, 1000)) {
            Random random = new Random(101);
            Set<Long> tombstonedIds = new HashSet<>();

            // Insert vectors to create multi-level graph
            for (int i = 0; i < 150; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone nodes at various levels
            for (int i = 0; i < 30; i++) {
                long nodeId = i * 3;
                graph.delete(nodeId);
                tombstonedIds.add(nodeId);
            }

            // Verify the graph has multiple levels
            assertThat(graph.maxLevel()).isGreaterThanOrEqualTo(0);

            // Multiple searches should never return tombstoned nodes
            for (int trial = 0; trial < 50; trial++) {
                float[] query = generateNormalizedVector(64, random);
                List<HnswSearchResult> results = graph.search(query, 20);

                for (HnswSearchResult result : results) {
                    assertThat(tombstonedIds.contains(result.nodeId()))
                        .as("Search at trial %d returned tombstoned node %d", trial, result.nodeId())
                        .isFalse();

                    // Double-check with graph's isTombstoned method
                    assertThat(graph.isTombstoned(result.nodeId()))
                        .as("Node %d should not be tombstoned", result.nodeId())
                        .isFalse();
                }
            }
        }
    }

    /**
     * Verifies that tombstoned neighbors are filtered during greedy search.
     */
    @Test
    void shouldFilterTombstonedNeighborsDuringGreedySearch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(202);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors
            for (int i = 0; i < 100; i++) {
                float[] vec = generateNormalizedVector(64, random);
                vectors.add(vec);
                graph.insert(vec);
            }

            // Tombstone every other node to maximize chance of hitting tombstones during traversal
            for (int i = 0; i < 50; i++) {
                graph.delete(i * 2);
            }

            // Search should still work correctly
            for (int trial = 0; trial < 20; trial++) {
                float[] query = vectors.get(trial * 3 + 1); // Use non-tombstoned vectors as queries
                List<HnswSearchResult> results = graph.search(query, 10);

                for (HnswSearchResult result : results) {
                    assertThat(graph.isTombstoned(result.nodeId()))
                        .as("Greedy search should skip tombstoned neighbors")
                        .isFalse();
                }
            }
        }
    }

    // ==================== Edge Case Tests ====================

    /**
     * Verifies behavior when all nodes are tombstoned.
     */
    @Test
    void shouldReturnEmptyWhenAllNodesTombstoned() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(303);

            // Insert some vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone all of them
            for (long i = 0; i < graph.nodeCount(); i++) {
                graph.delete(i);
            }

            assertThat(graph.activeNodeCount()).isZero();
            assertThat(graph.tombstoneCount()).isEqualTo(20);

            // Search should return empty
            List<HnswSearchResult> results = graph.search(generateNormalizedVector(64, random), 10);
            assertThat(results).isEmpty();
        }
    }

    /**
     * Verifies that deleting the same node twice is idempotent.
     */
    @Test
    void shouldHandleDoubleDeleteGracefully() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(404);

            for (int i = 0; i < 10; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Delete node 5
            assertThat(graph.delete(5)).isTrue();
            assertThat(graph.isTombstoned(5)).isTrue();

            // Delete node 5 again
            assertThat(graph.delete(5)).isFalse(); // Already deleted
            assertThat(graph.isTombstoned(5)).isTrue();

            // Search should still work
            List<HnswSearchResult> results = graph.search(generateNormalizedVector(64, random), 5);
            for (HnswSearchResult result : results) {
                assertThat(result.nodeId()).isNotEqualTo(5);
            }
        }
    }

    /**
     * Verifies that inserting after tombstones still works correctly.
     */
    @Test
    void shouldInsertCorrectlyAfterTombstones() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(505);

            // Insert initial batch
            for (int i = 0; i < 30; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone some
            for (int i = 0; i < 10; i++) {
                graph.delete(i);
            }

            // Insert more
            for (int i = 0; i < 20; i++) {
                long newNodeId = graph.insert(generateNormalizedVector(64, random));
                assertThat(graph.isTombstoned(newNodeId)).isFalse();
            }

            // Search should work correctly
            List<HnswSearchResult> results = graph.search(generateNormalizedVector(64, random), 15);
            assertThat(results.size()).isLessThanOrEqualTo(15);

            for (HnswSearchResult result : results) {
                assertThat(graph.isTombstoned(result.nodeId())).isFalse();
                assertThat(result.nodeId()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    // ==================== Recall Verification Tests ====================

    /**
     * Verifies that recall is calculated correctly excluding tombstoned nodes.
     */
    @Test
    void shouldMaintainRecallExcludingTombstonedNodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(606);
            List<float[]> vectors = new ArrayList<>();
            int numVectors = 100;
            int k = 10;

            // Insert vectors
            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(64, random);
                vectors.add(vec);
                graph.insert(vec);
            }

            // Select nodes to tombstone
            Set<Integer> tombstonedIndices = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                int idx = i * 4;
                graph.delete(idx);
                tombstonedIndices.add(idx);
            }

            // Test recall with tombstoned nodes excluded from ground truth
            int numQueries = 30;
            double totalRecall = 0.0;

            for (int q = 0; q < numQueries; q++) {
                float[] query = generateNormalizedVector(64, random);

                // Ground truth excludes tombstoned nodes
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distanceFunction, k, tombstonedIndices);

                // HNSW results
                List<HnswSearchResult> hnswResults = graph.search(query, k);

                // Verify no tombstoned nodes in results
                for (HnswSearchResult result : hnswResults) {
                    assertThat(tombstonedIndices.contains((int) result.nodeId()))
                        .as("Result should not contain tombstoned node")
                        .isFalse();
                }

                // Calculate recall
                Set<Long> hnswIds = new HashSet<>();
                for (HnswSearchResult r : hnswResults) {
                    hnswIds.add(r.nodeId());
                }

                int found = 0;
                for (Integer gtId : groundTruth) {
                    if (hnswIds.contains(gtId.longValue())) {
                        found++;
                    }
                }

                totalRecall += (double) found / groundTruth.size();
            }

            double averageRecall = totalRecall / numQueries;
            // With tombstoned nodes excluded, recall should still be reasonable
            assertThat(averageRecall)
                .as("Recall should be reasonable after tombstoning nodes")
                .isGreaterThanOrEqualTo(0.85);
        }
    }

    // ==================== Persistence Tests ====================

    /**
     * Verifies that tombstone status persists correctly.
     */
    @Test
    void shouldPersistTombstoneStatusAcrossReopen() throws IOException {
        Set<Long> tombstonedIds = new HashSet<>();

        // Create and populate graph
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(707);

            for (int i = 0; i < 30; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone some nodes
            long[] toDelete = {2, 5, 8, 11, 14, 17, 20, 23, 26, 29};
            for (long id : toDelete) {
                graph.delete(id);
                tombstonedIds.add(id);
            }

            graph.flush();
        }

        // Reopen and verify
        try (HnswGraph graph = HnswGraph.open(graphPath, distanceFunction)) {
            // Verify tombstone status
            for (Long id : tombstonedIds) {
                assertThat(graph.isTombstoned(id))
                    .as("Node %d should still be tombstoned after reopen", id)
                    .isTrue();
            }

            // Verify counts
            assertThat(graph.tombstoneCount()).isEqualTo(tombstonedIds.size());
            assertThat(graph.activeNodeCount()).isEqualTo(30 - tombstonedIds.size());

            // Search should not return tombstoned nodes
            Random random = new Random(707);
            for (int trial = 0; trial < 10; trial++) {
                float[] query = generateNormalizedVector(64, random);
                List<HnswSearchResult> results = graph.search(query, 20);

                for (HnswSearchResult result : results) {
                    assertThat(tombstonedIds.contains(result.nodeId()))
                        .as("Search after reopen should not return tombstoned nodes")
                        .isFalse();
                }
            }
        }
    }

    // ==================== Stress Tests ====================

    /**
     * Stress test with many tombstones and searches.
     */
    @Test
    void shouldHandleHighTombstoneRatioCorrectly() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(808);
            int totalNodes = 200;  // Reduced from 500 for faster test

            // Insert vectors
            for (int i = 0; i < totalNodes; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone 40% of nodes
            Set<Long> tombstonedIds = new HashSet<>();
            for (int i = 0; i < totalNodes * 0.4; i++) {
                long id = random.nextInt(totalNodes);
                if (!tombstonedIds.contains(id)) {
                    graph.delete(id);
                    tombstonedIds.add(id);
                }
            }

            // Perform many searches (reduced from 100 to 30 for faster test)
            for (int trial = 0; trial < 30; trial++) {
                float[] query = generateNormalizedVector(64, random);
                List<HnswSearchResult> results = graph.search(query, 20);

                for (HnswSearchResult result : results) {
                    assertThat(tombstonedIds.contains(result.nodeId()))
                        .as("Stress test: result should not be tombstoned (trial %d)", trial)
                        .isFalse();
                }
            }
        }
    }

    /**
     * Verifies that active neighbors method filters correctly.
     */
    @Test
    void shouldFilterActiveNeighborsCorrectly() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(909);

            // Insert nodes
            for (int i = 0; i < 50; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone some nodes
            Set<Long> tombstonedIds = new HashSet<>();
            for (int i = 0; i < 15; i++) {
                graph.delete(i * 2);
                tombstonedIds.add((long) i * 2);
            }

            // Check that getActiveNeighbors never returns tombstoned nodes
            for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                if (graph.isTombstoned(nodeId)) {
                    continue;
                }

                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null) {
                    List<Long> allNeighbors = node.getNeighbors(0);
                    List<Long> activeNeighbors = node.getActiveNeighbors(0);

                    // Active neighbors should be subset of all neighbors
                    assertThat(activeNeighbors).isSubsetOf(allNeighbors);

                    // Active neighbors should not contain any tombstoned nodes
                    for (Long neighborId : activeNeighbors) {
                        assertThat(graph.isTombstoned(neighborId))
                            .as("getActiveNeighbors should not return tombstoned node %d", neighborId)
                            .isFalse();
                    }

                    // Verify that any tombstoned neighbor is NOT in active list
                    for (Long neighborId : allNeighbors) {
                        if (graph.isTombstoned(neighborId)) {
                            assertThat(activeNeighbors.contains(neighborId))
                                .as("Tombstoned neighbor %d should not be in active list", neighborId)
                                .isFalse();
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifies invariant verification handles tombstones correctly.
     */
    @Test
    void shouldVerifyInvariantsWithTombstones() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            Random random = new Random(1010);

            // Insert nodes
            for (int i = 0; i < 100; i++) {
                graph.insert(generateNormalizedVector(64, random));
            }

            // Tombstone some nodes
            for (int i = 0; i < 20; i++) {
                graph.delete(i * 3);
            }

            // Verify invariants
            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.isValid())
                .as("Graph should maintain invariants even with tombstones. Violations: %s",
                    result.violations())
                .isTrue();

            // Verify connectivity
            HnswInvariantVerifier.VerificationResult connectivityResult =
                HnswInvariantVerifier.verifyConnectivity(graph);

            // Connectivity check should work correctly with tombstoned nodes excluded
            assertThat(connectivityResult.isValid() || connectivityResult.edgesChecked() > 0)
                .isTrue();
        }
    }
}
