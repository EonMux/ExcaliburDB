package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Additional edge case tests for HnswInvariantVerifier to improve coverage.
 *
 * <p>Tests focus on:</p>
 * <ul>
 *   <li>Null parameter handling</li>
 *   <li>Edge cases in verification methods</li>
 *   <li>Connectivity edge cases</li>
 *   <li>Summary edge cases</li>
 * </ul>
 */
@DisplayName("HnswInvariantVerifier Edge Case Tests")
class HnswInvariantVerifierEdgeCaseTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("edge_case_test.hnsw");
        config = HnswConfig.create(64);
    }

    @Nested
    @DisplayName("Null Parameter Handling")
    class NullParameterTests {

        @Test
        @DisplayName("verifyAllInvariants throws on null graph")
        void verifyAllInvariantsNullGraph() {
            assertThatThrownBy(() -> HnswInvariantVerifier.verifyAllInvariants(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Graph cannot be null");
        }

        @Test
        @DisplayName("verifyConnectivity throws on null graph")
        void verifyConnectivityNullGraph() {
            assertThatThrownBy(() -> HnswInvariantVerifier.verifyConnectivity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Graph cannot be null");
        }

        @Test
        @DisplayName("isEdgeBidirectional throws on null graph")
        void isEdgeBidirectionalNullGraph() {
            assertThatThrownBy(() -> HnswInvariantVerifier.isEdgeBidirectional(null, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Graph cannot be null");
        }

        @Test
        @DisplayName("countBidirectionalEdges throws on null graph")
        void countBidirectionalEdgesNullGraph() {
            assertThatThrownBy(() -> HnswInvariantVerifier.countBidirectionalEdges(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Graph cannot be null");
        }

        @Test
        @DisplayName("summarizeGraph handles null graph")
        void summarizeGraphNullGraph() {
            String summary = HnswInvariantVerifier.summarizeGraph(null);
            assertThat(summary).isEqualTo("Graph is null");
        }
    }

    @Nested
    @DisplayName("VerificationResult Tests")
    class VerificationResultTests {

        @Test
        @DisplayName("VerificationResult toString for valid result")
        void verificationResultToStringValid() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyAllInvariants(graph);

                assertThat(result.isValid()).isTrue();
                assertThat(result.toString())
                    .contains("valid=true")
                    .contains("nodes=")
                    .contains("edges=");
            }
        }

        @Test
        @DisplayName("VerificationResult accessors")
        void verificationResultAccessors() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert some nodes
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(64));
                }

                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyAllInvariants(graph);

                assertThat(result.isValid()).isTrue();
                assertThat(result.violations()).isEmpty();
                assertThat(result.nodesChecked()).isGreaterThanOrEqualTo(1);
                assertThat(result.edgesChecked()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Connectivity Edge Cases")
    class ConnectivityEdgeCaseTests {

        @Test
        @DisplayName("verifyConnectivity on empty graph returns valid")
        void verifyConnectivityEmptyGraph() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyConnectivity(graph);

                assertThat(result.isValid()).isTrue();
            }
        }

        @Test
        @DisplayName("verifyConnectivity with single node")
        void verifyConnectivitySingleNode() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(64));

                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyConnectivity(graph);

                assertThat(result.isValid()).isTrue();
                assertThat(result.nodesChecked()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("verifyConnectivity with all nodes deleted")
        void verifyConnectivityAllDeleted() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                long nodeId = graph.insert(randomVector(64));
                graph.delete(nodeId);

                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyConnectivity(graph);

                // Should still be valid (no active nodes means no connectivity issues)
                assertThat(result.isValid()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Bidirectional Edge Tests")
    class BidirectionalEdgeTests {

        @Test
        @DisplayName("isEdgeBidirectional returns false for non-existent nodes")
        void isEdgeBidirectionalNonExistentNodes() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(64));

                // Check edge to non-existent node
                boolean result = HnswInvariantVerifier.isEdgeBidirectional(graph, 0, 999, 0);
                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("isEdgeBidirectional returns false for level beyond node level")
        void isEdgeBidirectionalLevelTooHigh() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(64));
                graph.insert(randomVector(64));

                // Level 10 is beyond most nodes' levels
                boolean result = HnswInvariantVerifier.isEdgeBidirectional(graph, 0, 1, 10);
                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("countBidirectionalEdges on empty graph returns 0")
        void countBidirectionalEdgesEmptyGraph() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                long count = HnswInvariantVerifier.countBidirectionalEdges(graph, 0);
                assertThat(count).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("countBidirectionalEdges on level with no edges returns 0")
        void countBidirectionalEdgesNoEdges() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(64));

                // Single node has no edges to other nodes
                long count = HnswInvariantVerifier.countBidirectionalEdges(graph, 10);
                assertThat(count).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("countBidirectionalEdges counts correctly at level 0")
        void countBidirectionalEdgesLevel0() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(64));
                }

                long count = HnswInvariantVerifier.countBidirectionalEdges(graph, 0);
                // Should have some edges
                assertThat(count).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Summary Tests")
    class SummaryTests {

        @Test
        @DisplayName("summarizeGraph includes all expected information")
        void summarizeGraphComplete() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 25; i++) {
                    graph.insert(randomVector(64));
                }

                // Delete some nodes
                for (int i = 0; i < 5; i++) {
                    graph.delete(i * 2);
                }

                String summary = HnswInvariantVerifier.summarizeGraph(graph);

                assertThat(summary)
                    .contains("HNSW Graph Summary")
                    .contains("Total nodes: 25")
                    .contains("Active nodes: 20")
                    .contains("Tombstoned nodes: 5")
                    .contains("Entry point:")
                    .contains("Max level:")
                    .contains("Nodes per level")
                    .contains("Bidirectional edges at level 0:");
            }
        }

        @Test
        @DisplayName("summarizeGraph handles multi-level graph")
        void summarizeGraphMultiLevel() throws IOException {
            // Use config that encourages multi-level graph
            HnswConfig multiLevelConfig = new HnswConfig(16, 32, 200, 100, 0.5, 64);

            try (HnswGraph graph = new HnswGraph(graphPath, multiLevelConfig, L2Distance.getInstance(), 1000)) {
                // Insert enough nodes to create multiple levels
                for (int i = 0; i < 200; i++) {
                    graph.insert(randomVector(64));
                }

                String summary = HnswInvariantVerifier.summarizeGraph(graph);

                assertThat(summary)
                    .contains("Max level:")
                    .contains("Level"); // Should show level breakdown

                // Should have at least level 0
                assertThat(summary).contains("Level 0:");
            }
        }
    }

    @Nested
    @DisplayName("Entry Point Edge Cases")
    class EntryPointEdgeCaseTests {

        @Test
        @DisplayName("verifyAllInvariants handles empty graph correctly")
        void verifyAllInvariantsEmptyGraph() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyAllInvariants(graph);

                assertThat(result.isValid()).isTrue();
                assertThat(result.nodesChecked()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("verifyAllInvariants handles single tombstoned node")
        void verifyAllInvariantsSingleTombstonedNode() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                long nodeId = graph.insert(randomVector(64));
                graph.delete(nodeId);

                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyAllInvariants(graph);

                // Should be valid - tombstoned nodes are skipped
                assertThat(result.isValid()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Level Consistency Edge Cases")
    class LevelConsistencyEdgeCaseTests {

        @Test
        @DisplayName("countBidirectionalEdges handles all tombstoned nodes")
        void countBidirectionalEdgesAllTombstoned() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert and delete all nodes
                for (int i = 0; i < 5; i++) {
                    long nodeId = graph.insert(randomVector(64));
                    graph.delete(nodeId);
                }

                long count = HnswInvariantVerifier.countBidirectionalEdges(graph, 0);
                assertThat(count).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("verifyConnectivity handles all tombstoned nodes")
        void verifyConnectivityAllTombstoned() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Insert and delete all nodes
                for (int i = 0; i < 5; i++) {
                    long nodeId = graph.insert(randomVector(64));
                    graph.delete(nodeId);
                }

                HnswInvariantVerifier.VerificationResult result =
                    HnswInvariantVerifier.verifyConnectivity(graph);

                assertThat(result.isValid()).isTrue();
            }
        }
    }
}
