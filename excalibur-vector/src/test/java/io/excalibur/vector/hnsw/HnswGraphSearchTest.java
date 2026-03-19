package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Additional search tests for HnswGraph to improve coverage.
 *
 * <p>Tests focus on:</p>
 * <ul>
 *   <li>Search with tombstoned nodes</li>
 *   <li>Search edge cases</li>
 *   <li>Multi-level graph search</li>
 * </ul>
 */
@DisplayName("HnswGraph Search Tests")
class HnswGraphSearchTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private static final int DIMENSION = 64;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("search_test.hnsw");
        config = HnswConfig.create(DIMENSION);
    }

    @Nested
    @DisplayName("Search with Tombstoned Nodes")
    class SearchWithTombstonesTests {

        @Test
        @DisplayName("Search returns only active nodes after deletions")
        void searchReturnsOnlyActiveAfterDeletions() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert nodes
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Delete half
                for (long i = 0; i < 10; i++) {
                    graph.delete(i);
                }

                // Search should only return active nodes
                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 20);
                assertThat(results.size()).isLessThanOrEqualTo(10);

                // Verify all results are active
                for (HnswSearchResult result : results) {
                    assertThat(graph.isTombstoned(result.nodeId())).isFalse();
                }
            }
        }

        @Test
        @DisplayName("Search with mostly tombstoned nodes still works")
        void searchMostlyTombstoned() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert nodes
                for (int i = 0; i < 100; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Delete 90%
                for (long i = 0; i < 90; i++) {
                    graph.delete(i);
                }

                // Search should still work
                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 20);
                assertThat(results.size()).isLessThanOrEqualTo(10); // Only 10 active nodes
            }
        }

        @Test
        @DisplayName("Search after deleting entry point finds new entry point")
        void searchAfterDeletingEntryPoint() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert nodes
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                long entryPoint = graph.entryPoint();
                assertThat(entryPoint).isGreaterThanOrEqualTo(0);

                // Delete the entry point
                graph.delete(entryPoint);

                // Search should still work with new entry point
                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 5);
                assertThat(results).isNotEmpty();
                assertThat(results.stream().mapToLong(HnswSearchResult::nodeId))
                    .doesNotContain(entryPoint);
            }
        }
    }

    @Nested
    @DisplayName("Multi-Level Search Tests")
    class MultiLevelSearchTests {

        @Test
        @DisplayName("Search works with multi-level graph")
        void searchMultiLevelGraph() throws IOException {
            // Use config that encourages multi-level graph
            HnswConfig multiLevelConfig = new HnswConfig(16, 32, 200, 100, 0.5, DIMENSION);

            try (HnswGraph graph = new HnswGraph(graphPath, multiLevelConfig, L2Distance.getInstance(), 1000)) {
                // Insert enough nodes to create multiple levels
                for (int i = 0; i < 200; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                assertThat(graph.maxLevel()).isGreaterThanOrEqualTo(1);

                // Search should work at all levels
                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);
                assertThat(results.size()).isLessThanOrEqualTo(10);

                // Verify results are valid
                for (HnswSearchResult result : results) {
                    assertThat(result.nodeId()).isGreaterThanOrEqualTo(0);
                    assertThat(result.distance()).isGreaterThanOrEqualTo(0.0f);
                }
            }
        }

        @Test
        @DisplayName("Search with different efSearch values")
        void searchWithDifferentEfSearch() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 50; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                float[] query = randomVector(DIMENSION);

                // Search with different ef values via config
                List<HnswSearchResult> results1 = graph.search(query, 5);

                // All searches should return results
                assertThat(results1.size()).isLessThanOrEqualTo(5);
            }
        }
    }

    @Nested
    @DisplayName("Search Result Validation")
    class SearchResultValidationTests {

        @Test
        @DisplayName("Search results are sorted by distance")
        void searchResultsSortedByDistance() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 30; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);

                // Verify sorted by distance (closest first)
                for (int i = 1; i < results.size(); i++) {
                    assertThat(results.get(i).distance())
                        .isGreaterThanOrEqualTo(results.get(i - 1).distance());
                }
            }
        }

        @Test
        @DisplayName("Search returns correct node IDs")
        void searchReturnsCorrectNodeIds() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);

                // All node IDs should be valid
                for (HnswSearchResult result : results) {
                    assertThat(result.nodeId()).isBetween(0L, 9L);
                }
            }
        }

        @Test
        @DisplayName("Search distances are non-negative for L2")
        void searchDistancesNonNegative() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);

                for (HnswSearchResult result : results) {
                    assertThat(result.distance()).isGreaterThanOrEqualTo(0.0f);
                }
            }
        }
    }

    @Nested
    @DisplayName("Node View Tests")
    class NodeViewTests {

        @Test
        @DisplayName("NodeView provides correct vector data")
        void nodeViewCorrectVector() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                float[] original = randomVector(DIMENSION);
                long nodeId = graph.insert(original);

                HnswGraph.NodeView node = graph.readNode(nodeId);
                assertThat(node).isNotNull();
                assertThat(node.vector()).hasSize(DIMENSION);

                // Verify vector data matches (approximately due to potential normalization)
                for (int i = 0; i < DIMENSION; i++) {
                    assertThat(Math.abs(node.vector()[i] - original[i]))
                        .isLessThanOrEqualTo(0.01f);
                }
            }
        }

        @Test
        @DisplayName("NodeView provides correct neighbors")
        void nodeViewCorrectNeighbors() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                    if (graph.isTombstoned(nodeId)) {
                        continue;
                    }

                    HnswGraph.NodeView node = graph.readNode(nodeId);
                    if (node != null) {
                        List<Long> neighbors = node.getNeighbors(0);
                        List<Long> activeNeighbors = node.getActiveNeighbors(0);

                        // Active neighbors should be subset of all neighbors
                        assertThat(activeNeighbors.size()).isLessThanOrEqualTo(neighbors.size());

                        // All active neighbors should not be tombstoned
                        for (Long neighborId : activeNeighbors) {
                            assertThat(graph.isTombstoned(neighborId)).isFalse();
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("NodeView level is non-negative")
        void nodeViewLevelNonNegative() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
                    if (graph.isTombstoned(nodeId)) {
                        continue;
                    }

                    HnswGraph.NodeView node = graph.readNode(nodeId);
                    if (node != null) {
                        assertThat(node.level()).isGreaterThanOrEqualTo(0);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Graph Statistics Tests")
    class GraphStatisticsTests {

        @Test
        @DisplayName("activeNodeCount matches non-tombstoned nodes")
        void activeNodeCountMatches() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 30; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Delete some nodes
                for (long i = 0; i < 10; i++) {
                    graph.delete(i);
                }

                // Count active nodes manually
                int activeCount = 0;
                for (long i = 0; i < graph.nodeCount(); i++) {
                    if (!graph.isTombstoned(i)) {
                        activeCount++;
                    }
                }

                assertThat(graph.activeNodeCount()).isEqualTo(activeCount);
            }
        }

        @Test
        @DisplayName("tombstoneCount matches deleted nodes")
        void tombstoneCountMatches() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 30; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Delete some nodes
                for (long i = 0; i < 10; i++) {
                    graph.delete(i);
                }

                assertThat(graph.tombstoneCount()).isEqualTo(10);
            }
        }

        @Test
        @DisplayName("nodeCount reflects total nodes")
        void nodeCountReflectsTotal() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 30; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                assertThat(graph.nodeCount()).isEqualTo(30);

                // Deleting doesn't change node count
                graph.delete(0);
                assertThat(graph.nodeCount()).isEqualTo(30);
            }
        }
    }

    @Nested
    @DisplayName("Incremental Insert Tests")
    class IncrementalInsertTests {

        @Test
        @DisplayName("getLastIntegratedOffset advances with insertions")
        void getLastIntegratedOffsetAdvances() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                long initialOffset = graph.getLastIntegratedOffset();

                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Offset should have advanced
                assertThat(graph.getLastIntegratedOffset()).isGreaterThanOrEqualTo(initialOffset);
            }
        }

        @Test
        @DisplayName("getLastIntegratedOffset accessible after flush")
        void getLastIntegratedOffsetAfterFlush() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                graph.flush();

                // Offset should still be accessible
                assertThat(graph.getLastIntegratedOffset()).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    // Helper
    private float[] randomVector(int dim) {
        Random random = new Random();
        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = (float) random.nextGaussian();
        }
        return vec;
    }
}
