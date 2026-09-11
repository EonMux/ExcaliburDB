package excalibur.query.visibility;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HnswGraphDiagnostic utility.
 *
 * <p>These tests verify that the diagnostic correctly identifies:</p>
 * <ul>
 *   <li>Empty graphs</li>
 *   <li>Graphs with correct/incorrect lastIntegratedOffset</li>
 *   <li>Graphs with linked (bidirectional edges) vs unlinked nodes</li>
 *   <li>Graphs that can/cannot find results via self-search</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Healthy graph should have isHealthy() == true</li>
 *   <li>lastIntegratedOffset should be <= nodeCount</li>
 *   <li>Connectivity ratio should be 1.0 for fully connected graphs</li>
 *   <li>Self-search should return results for graphs with linked nodes</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class HnswGraphDiagnosticTest {

    @TempDir
    Path tempDir;

    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    @BeforeEach
    void setUp() {
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
        random = new Random(42);
    }

    @AfterEach
    void tearDown() {
        // Cleanup handled by @TempDir
    }

    @Nested
    @DisplayName("Empty Graph Diagnostics")
    class EmptyGraphDiagnostics {

        @Test
        @DisplayName("empty graph returns warning")
        void emptyGraphReturnsWarning() throws IOException {
            Path graphPath = tempDir.resolve("empty.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, 0);

            assertThat(result.nodeCount()).isEqualTo(0);
            assertThat(result.activeNodeCount()).isEqualTo(0);
            assertThat(result.entryPoint()).isEqualTo(-1);
            assertThat(result.isHealthy()).isFalse();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).code()).isEqualTo("EMPTY_GRAPH");

            graph.close();
        }
    }

    @Nested
    @DisplayName("Single Node Diagnostics")
    class SingleNodeDiagnostics {

        @Test
        @DisplayName("single node graph is healthy")
        void singleNodeGraphIsHealthy() throws IOException {
            Path graphPath = tempDir.resolve("single.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            float[] vector = createRandomVector(dimension);
            graph.insert(vector);
            graph.setLastIntegratedOffset(1);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, 1);

            assertThat(result.nodeCount()).isEqualTo(1);
            assertThat(result.activeNodeCount()).isEqualTo(1);
            assertThat(result.entryPoint()).isEqualTo(0);
            assertThat(result.isHealthy()).isTrue();
            assertThat(result.lastIntegratedOffset()).isEqualTo(1);

            graph.close();
        }
    }

    @Nested
    @DisplayName("Multi-Node Graph Diagnostics")
    class MultiNodeGraphDiagnostics {

        @Test
        @DisplayName("healthy multi-node graph passes all checks")
        void healthyMultiNodeGraphPassesAllChecks() throws IOException {
            Path graphPath = tempDir.resolve("multi.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            int numVectors = 50;
            for (int i = 0; i < numVectors; i++) {
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }
            graph.setLastIntegratedOffset(numVectors);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, numVectors);

            assertThat(result.nodeCount()).isEqualTo(numVectors);
            assertThat(result.activeNodeCount()).isEqualTo(numVectors);
            assertThat(result.entryPoint()).isGreaterThanOrEqualTo(0);
            assertThat(result.lastIntegratedOffset()).isEqualTo(numVectors);
            assertThat(result.writeLogHead()).isEqualTo(numVectors);
            assertThat(result.hasTailEntries()).isFalse();
            assertThat(result.integrationProgress()).isEqualTo(1.0);
            assertThat(result.edgeCount()).isGreaterThan(0);

            // Self-search should find results
            assertThat(result.searchResultCount()).isGreaterThan(0);

            // Should be healthy (may have minor warnings but no critical issues)
            long criticalIssues = result.issues().stream()
                .filter(i -> i.severity() == HnswGraphDiagnostic.Severity.CRITICAL)
                .count();
            assertThat(criticalIssues).isEqualTo(0);

            graph.close();
        }

        @Test
        @DisplayName("graph with tail entries detected correctly")
        void graphWithTailEntriesDetectedCorrectly() throws IOException {
            Path graphPath = tempDir.resolve("tail.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            int numVectors = 20;
            for (int i = 0; i < numVectors; i++) {
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }
            // Set offset to half - simulating partial integration
            graph.setLastIntegratedOffset(numVectors / 2);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, numVectors);

            assertThat(result.nodeCount()).isEqualTo(numVectors);
            assertThat(result.lastIntegratedOffset()).isEqualTo(numVectors / 2);
            assertThat(result.writeLogHead()).isEqualTo(numVectors);
            assertThat(result.hasTailEntries()).isTrue();
            assertThat(result.tailEntryCount()).isEqualTo(numVectors / 2);
            assertThat(result.integrationProgress()).isEqualTo(0.5);

            graph.close();
        }
    }

    @Nested
    @DisplayName("Offset Correctness")
    class OffsetCorrectness {

        @Test
        @DisplayName("isOffsetCorrect returns true for valid offset")
        void isOffsetCorrectReturnsTrueForValidOffset() throws IOException {
            Path graphPath = tempDir.resolve("offset.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }
            graph.setLastIntegratedOffset(10);

            assertThat(HnswGraphDiagnostic.isOffsetCorrect(graph, 10)).isTrue();
            assertThat(HnswGraphDiagnostic.isOffsetCorrect(graph, 20)).isTrue();
            assertThat(HnswGraphDiagnostic.isOffsetCorrect(graph, 100)).isTrue();

            graph.close();
        }

        @Test
        @DisplayName("isOffsetCorrect returns false for invalid offset")
        void isOffsetCorrectReturnsFalseForInvalidOffset() throws IOException {
            Path graphPath = tempDir.resolve("invalid-offset.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            graph.insert(createRandomVector(dimension));
            graph.setLastIntegratedOffset(50); // Set to value higher than writeLogHead

            assertThat(HnswGraphDiagnostic.isOffsetCorrect(graph, 10)).isFalse();

            graph.close();
        }

        @Test
        @DisplayName("analyzeOffset provides detailed analysis")
        void analyzeOffsetProvidesDetailedAnalysis() throws IOException {
            Path graphPath = tempDir.resolve("analyze.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            int numVectors = 30;
            for (int i = 0; i < numVectors; i++) {
                graph.insert(createRandomVector(dimension));
            }
            graph.setLastIntegratedOffset(20); // Partially integrated

            HnswGraphDiagnostic.OffsetAnalysis analysis =
                HnswGraphDiagnostic.analyzeOffset(graph);

            assertThat(analysis.offset()).isEqualTo(20);
            assertThat(analysis.nodeCount()).isEqualTo(numVectors);
            assertThat(analysis.tailSize()).isEqualTo(10);
            assertThat(analysis.hasTail()).isTrue();
            assertThat(analysis.integrationProgress()).isGreaterThan(0.6);

            graph.close();
        }
    }

    @Nested
    @DisplayName("Edge Detection")
    class EdgeDetection {

        @Test
        @DisplayName("hasEdges returns true for graph with edges")
        void hasEdgesReturnsTrueForGraphWithEdges() throws IOException {
            Path graphPath = tempDir.resolve("edges.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Need multiple vectors to have edges
            for (int i = 0; i < 5; i++) {
                graph.insert(createRandomVector(dimension));
            }

            assertThat(HnswGraphDiagnostic.hasEdges(graph)).isTrue();

            graph.close();
        }

        @Test
        @DisplayName("hasEdges returns true for empty graph (trivially OK)")
        void hasEdgesReturnsTrueForEmptyGraph() throws IOException {
            Path graphPath = tempDir.resolve("empty-edges.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            assertThat(HnswGraphDiagnostic.hasEdges(graph)).isTrue();

            graph.close();
        }
    }

    @Nested
    @DisplayName("Self-Search Test")
    class SelfSearchTest {

        @Test
        @DisplayName("canFindResults returns true for healthy graph")
        void canFindResultsReturnsTrueForHealthyGraph() throws IOException {
            Path graphPath = tempDir.resolve("search.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }

            assertThat(HnswGraphDiagnostic.canFindResults(graph)).isTrue();

            graph.close();
        }

        @Test
        @DisplayName("canFindResults returns true for empty graph (trivially OK)")
        void canFindResultsReturnsTrueForEmptyGraph() throws IOException {
            Path graphPath = tempDir.resolve("empty-search.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            assertThat(HnswGraphDiagnostic.canFindResults(graph)).isTrue();

            graph.close();
        }
    }

    @Nested
    @DisplayName("Report Formatting")
    class ReportFormatting {

        @Test
        @DisplayName("formatReport produces readable output")
        void formatReportProducesReadableOutput() throws IOException {
            Path graphPath = tempDir.resolve("report.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }
            graph.setLastIntegratedOffset(10);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, 10);

            String report = HnswGraphDiagnostic.formatReport(result);

            assertThat(report).contains("Node Count:");
            assertThat(report).contains("Entry Point:");
            assertThat(report).contains("Last Integrated Offset:");
            assertThat(report).contains("Integration Progress:");

            System.out.println(report);

            graph.close();
        }
    }

    @Nested
    @DisplayName("Diagnostic Summary")
    class DiagnosticSummary {

        @Test
        @DisplayName("summary provides concise information")
        void summaryProvidesConciseInformation() throws IOException {
            Path graphPath = tempDir.resolve("summary.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            int numVectors = 25;
            for (int i = 0; i < numVectors; i++) {
                graph.insert(createRandomVector(dimension));
            }
            graph.setLastIntegratedOffset(numVectors);

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, numVectors);

            String summary = result.summary();

            assertThat(summary).contains("nodes=" + numVectors);
            assertThat(summary).contains("active=" + numVectors);
            assertThat(summary).contains("offset=" + numVectors);
            assertThat(summary).contains("issues=");

            System.out.println("Summary: " + summary);

            graph.close();
        }
    }

    // ==================== Helper Methods ====================

    private float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        float sum = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
            sum += vector[i] * vector[i];
        }
        // Normalize
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    @Nested
    @DisplayName("A probe the graph could not answer")
    class ProbeFailures {

        @Test
        @DisplayName("a graph closed by compaction is not reported as broken traversal")
        void aGraphClosedByCompactionIsNotReportedAsBroken() throws IOException {
            // Compaction holds the write lock while the graph is closed and reopened, and
            // search() checks the closed flag before taking the read lock. A diagnostic that
            // lands in that window gets an exception, not an empty result - and reporting
            // "Graph traversal is broken" sends an operator after a fault that does not exist.
            Path graphPath = tempDir.resolve("compacting.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new UnreachableDuringCompaction(graphPath, config);
            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, 0);

            assertThat(result.issues())
                .as("the probe failed, not the graph")
                .noneMatch(issue -> "SELF_SEARCH_EMPTY".equals(issue.code()));
            assertThat(result.issues())
                .anyMatch(issue -> "SELF_SEARCH_UNAVAILABLE".equals(issue.code()));
            graph.close();
        }

        @Test
        @DisplayName("the unavailable probe names compaction as the likely cause")
        void theUnavailableProbeNamesCompaction() throws IOException {
            Path graphPath = tempDir.resolve("compacting-2.graph");
            HnswConfig config = HnswConfig.create(dimension);
            HnswGraph graph = new UnreachableDuringCompaction(graphPath, config);
            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }

            HnswGraphDiagnostic.GraphDiagnosticResult result =
                HnswGraphDiagnostic.runFullDiagnostic(graph, 0);

            assertThat(result.issues())
                .filteredOn(issue -> "SELF_SEARCH_UNAVAILABLE".equals(issue.code()))
                .allSatisfy(issue -> assertThat(issue.recommendation()).contains("ompaction"));
            graph.close();
        }

        /** A graph in the window where compaction has closed it and not yet reopened it. */
        private final class UnreachableDuringCompaction extends HnswGraph {

            UnreachableDuringCompaction(Path path, HnswConfig config) throws IOException {
                super(path, config, distanceFunction, 10000);
            }

            @Override
            public List<io.excalibur.vector.hnsw.HnswSearchResult> search(float[] query, int k) {
                throw new IllegalStateException("HNSW graph is closed");
            }
        }
    }

}
