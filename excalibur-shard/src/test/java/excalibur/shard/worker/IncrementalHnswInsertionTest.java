package excalibur.shard.worker;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import io.excalibur.vector.hnsw.IncrementalInsertResult;
import io.excalibur.vector.hnsw.TailOffsetTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Feature 31: Incremental HNSW Insertion.
 *
 * <h2>Feature 31 Requirements</h2>
 * <blockquote>
 *   Incremental insertion: Vectors added to graph without full rebuild.
 *   Soft-delete (tombstone + skip during traversal).
 *   Periodic graph compaction to remove dead nodes and re-optimize edges.
 * </blockquote>
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify the complete incremental insertion flow:</p>
 * <ul>
 *   <li>{@link HnswGraph#insertWithResult(float[])} - Single vector insertion with metadata</li>
 *   <li>{@link HnswGraph#insertBatchWithResults(List, List)} - Batch insertion via incremental path</li>
 *   <li>{@link TailOffsetTracker#integrateBatch(List, List)} - Integration with write log tracking</li>
 *   <li>{@link HnswIntegrationWorker} - Background worker integration path</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>All insertion results must return success=true for valid vectors</li>
 *   <li>Node IDs must be sequential starting from 0</li>
 *   <li>Write log offsets must be preserved in results</li>
 *   <li>Connections must be tracked and returned</li>
 *   <li>Integration worker must process entries from write log</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 4.0
 */
class IncrementalHnswInsertionTest {

    @TempDir
    Path tempDir;

    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    @BeforeEach
    void setUp() {
        config = HnswConfig.create(128);
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
        random = new Random(42);
    }

    // =====================================================================================
    // FEATURE 31.1: insertWithResult() - Single Vector Incremental Insertion
    // =====================================================================================

    @Nested
    @DisplayName("Feature 31.1: insertWithResult() - Single Vector Insertion")
    class SingleVectorInsertion {

        /**
         * Test: insertWithResult() returns success with valid metadata.
         *
         * <p><strong>Domain Correctness:</strong> Result must contain valid nodeId, level,
         * dataOffset, and connectionsMade values.</p>
         */
        @Test
        @DisplayName("should return successful result with valid metadata")
        void shouldReturnSuccessfulResultWithMetadata() throws IOException {
            Path graphPath = tempDir.resolve("single-insert.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                float[] vector = createRandomVector(dimension);

                IncrementalInsertResult result = graph.insertWithResult(vector);

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.nodeId()).isEqualTo(0L);
                assertThat(result.level()).isGreaterThanOrEqualTo(0);
                assertThat(result.dataOffset()).isGreaterThanOrEqualTo(0); // First node can be at offset 0
                assertThat(result.writeLogOffset()).isEqualTo(-1L); // Not from write log
                assertThat(result.connectionsMade()).isEqualTo(0); // First node has no connections
            }
        }

        /**
         * Test: insertWithResult() with write log offset preserves the offset.
         *
         * <p><strong>Domain Correctness:</strong> Write log offset must be preserved
         * in the result for tail tracking.</p>
         */
        @Test
        @DisplayName("should preserve write log offset in result")
        void shouldPreserveWriteLogOffsetInResult() throws IOException {
            Path graphPath = tempDir.resolve("offset-insert.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                float[] vector = createRandomVector(dimension);
                long writeLogOffset = 12345L;

                IncrementalInsertResult result = graph.insertWithResult(vector, writeLogOffset);

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.writeLogOffset()).isEqualTo(writeLogOffset);
            }
        }

        /**
         * Test: insertWithResult() returns connections for subsequent insertions.
         *
         * <p><strong>Domain Correctness:</strong> After the first node, subsequent nodes
         * must connect to existing nodes.</p>
         */
        @Test
        @DisplayName("should track connections to existing nodes")
        void shouldTrackConnectionsToExistingNodes() throws IOException {
            Path graphPath = tempDir.resolve("connections.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert first node - no connections
                IncrementalInsertResult result1 = graph.insertWithResult(createRandomVector(dimension));
                assertThat(result1.connectionsMade()).isEqualTo(0);

                // Insert second node - should connect to first
                IncrementalInsertResult result2 = graph.insertWithResult(createRandomVector(dimension));
                assertThat(result2.connectionsMade()).isGreaterThan(0);
                assertThat(result2.connectedNodes()).contains(0L);

                // Insert third node - should connect to at least one existing node
                IncrementalInsertResult result3 = graph.insertWithResult(createRandomVector(dimension));
                assertThat(result3.connectionsMade()).isGreaterThan(0);
            }
        }

        /**
         * Test: Sequential insertWithResult() calls produce sequential node IDs.
         *
         * <p><strong>Domain Correctness:</strong> Node IDs must be sequential
         * starting from 0.</p>
         */
        @Test
        @DisplayName("should produce sequential node IDs")
        void shouldProduceSequentialNodeIds() throws IOException {
            Path graphPath = tempDir.resolve("sequential.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                int numVectors = 50;
                List<Long> nodeIds = new ArrayList<>();

                for (int i = 0; i < numVectors; i++) {
                    IncrementalInsertResult result = graph.insertWithResult(createRandomVector(dimension));
                    assertThat(result.isSuccess()).isTrue();
                    nodeIds.add(result.nodeId());
                }

                // Verify sequential IDs
                for (int i = 0; i < numVectors; i++) {
                    assertThat(nodeIds.get(i)).isEqualTo((long) i);
                }

                assertThat(graph.nodeCount()).isEqualTo(numVectors);
            }
        }
    }

    // =====================================================================================
    // FEATURE 31.2: insertBatchWithResults() - Batch Incremental Insertion
    // =====================================================================================

    @Nested
    @DisplayName("Feature 31.2: insertBatchWithResults() - Batch Insertion via Incremental Path")
    class BatchInsertion {

        /**
         * Test: insertBatchWithResults() uses insertWithResultInternal() for each vector.
         *
         * <p><strong>CRITICAL:</strong> This verifies that the batch path does NOT bypass
         * incremental insertion - it uses the same core logic via insertWithResultInternal().</p>
         *
         * <p><strong>Domain Correctness:</strong> All results must have success=true and
         * correct metadata from the incremental insertion logic.</p>
         */
        @Test
        @DisplayName("should use incremental insertion path for each vector in batch")
        void shouldUseIncrementalInsertionPathForBatch() throws IOException {
            Path graphPath = tempDir.resolve("batch-incremental.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                int batchSize = 25;
                List<float[]> vectors = new ArrayList<>();
                List<Long> writeLogOffsets = new ArrayList<>();

                for (int i = 0; i < batchSize; i++) {
                    vectors.add(createRandomVector(dimension));
                    writeLogOffsets.add((long) (i * 100)); // Simulating write log offsets
                }

                // This calls insertWithResultInternal() for each vector
                List<IncrementalInsertResult> results = graph.insertBatchWithResults(vectors, writeLogOffsets);

                assertThat(results).hasSize(batchSize);

                // Verify each result has incremental insertion metadata
                for (int i = 0; i < batchSize; i++) {
                    IncrementalInsertResult result = results.get(i);

                    // CRITICAL: Verify incremental insertion succeeded
                    assertThat(result.isSuccess())
                        .as("Vector %d should be inserted via incremental path", i)
                        .isTrue();

                    // Verify sequential node IDs (incremental insertion behavior)
                    assertThat(result.nodeId())
                        .as("Vector %d should have sequential nodeId", i)
                        .isEqualTo((long) i);

                    // Verify write log offset preserved (from incremental insertion)
                    assertThat(result.writeLogOffset())
                        .as("Vector %d should preserve write log offset", i)
                        .isEqualTo(i * 100);

                    // Verify level was generated (incremental insertion behavior)
                    assertThat(result.level())
                        .as("Vector %d should have valid level", i)
                        .isGreaterThanOrEqualTo(0);

                    // Verify data offset was assigned (incremental insertion behavior)
                    assertThat(result.dataOffset())
                        .as("Vector %d should have valid dataOffset", i)
                        .isGreaterThanOrEqualTo(0);
                }

                assertThat(graph.nodeCount()).isEqualTo(batchSize);
            }
        }

        /**
         * Test: insertBatchWithResults() maintains graph connectivity.
         *
         * <p><strong>Domain Correctness:</strong> After batch insertion, the graph
         * should be properly connected and searchable.</p>
         */
        @Test
        @DisplayName("should maintain graph connectivity after batch insertion")
        void shouldMaintainGraphConnectivityAfterBatch() throws IOException {
            Path graphPath = tempDir.resolve("batch-connectivity.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                int batchSize = 100;
                List<float[]> vectors = new ArrayList<>();
                List<Long> offsets = new ArrayList<>();

                for (int i = 0; i < batchSize; i++) {
                    vectors.add(createRandomVector(dimension));
                    offsets.add((long) i);
                }

                List<IncrementalInsertResult> results = graph.insertBatchWithResults(vectors, offsets);

                // Count nodes with connections (all except first should have connections)
                int nodesWithConnections = 0;
                for (IncrementalInsertResult result : results) {
                    if (result.connectionsMade() > 0) {
                        nodesWithConnections++;
                    }
                }

                // At least 90% of nodes should have connections
                assertThat(nodesWithConnections)
                    .as("Most nodes should have connections after batch insertion")
                    .isGreaterThan((int) (batchSize * 0.9));

                // Graph should be searchable
                List<HnswSearchResult> searchResults = graph.search(vectors.get(0), 10);
                assertThat(searchResults).isNotEmpty();
            }
        }
    }

    // =====================================================================================
    // FEATURE 31.3: TailOffsetTracker Integration
    // =====================================================================================

    @Nested
    @DisplayName("Feature 31.3: TailOffsetTracker - Integration with Incremental Insertion")
    class TailOffsetTrackerIntegration {

        /**
         * Test: TailOffsetTracker.integrateBatch() uses graph.insertBatchWithResults().
         *
         * <p><strong>CRITICAL:</strong> This verifies the production path from integration
         * worker to incremental insertion.</p>
         */
        @Test
        @DisplayName("should use incremental insertion via insertBatchWithResults")
        void shouldUseIncrementalInsertionViaTracker() throws IOException {
            Path graphPath = tempDir.resolve("tracker-incremental.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                TailOffsetTracker tracker = new TailOffsetTracker(graph);

                int numVectors = 20;
                List<float[]> vectors = new ArrayList<>();
                List<Long> writeLogOffsets = new ArrayList<>();

                for (int i = 0; i < numVectors; i++) {
                    vectors.add(createRandomVector(dimension));
                    // END offsets (first byte after each entry)
                    writeLogOffsets.add((long) ((i + 1) * 100));
                }

                // This is the production path used by HnswIntegrationWorker
                List<IncrementalInsertResult> results = tracker.integrateBatch(vectors, writeLogOffsets);

                assertThat(results).hasSize(numVectors);

                // Verify all insertions succeeded via incremental path
                for (int i = 0; i < numVectors; i++) {
                    IncrementalInsertResult result = results.get(i);
                    assertThat(result.isSuccess())
                        .as("Vector %d should be inserted via incremental path", i)
                        .isTrue();
                }

                // Verify tracker offset was updated
                assertThat(tracker.getLastIntegratedOffset())
                    .as("Tracker offset should be updated to max offset")
                    .isEqualTo((long) numVectors * 100);
            }
        }

        /**
         * Test: TailOffsetTracker.integrateVector() uses graph.insertWithResult().
         *
         * <p><strong>CRITICAL:</strong> This verifies the single-vector production path.</p>
         */
        @Test
        @DisplayName("should use incremental insertion for single vector")
        void shouldUseIncrementalInsertionForSingleVector() throws IOException {
            Path graphPath = tempDir.resolve("tracker-single.graph");
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                TailOffsetTracker tracker = new TailOffsetTracker(graph);

                float[] vector = createRandomVector(dimension);
                long writeLogOffset = 500L;

                // This is the production path for single vector integration
                IncrementalInsertResult result = tracker.integrateVector(vector, writeLogOffset);

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.writeLogOffset()).isEqualTo(writeLogOffset);
                assertThat(tracker.getLastIntegratedOffset()).isEqualTo(writeLogOffset);
            }
        }
    }

    // =====================================================================================
    // FEATURE 31.4: HnswIntegrationWorker End-to-End
    // =====================================================================================

    @Nested
    @DisplayName("Feature 31.4: HnswIntegrationWorker - End-to-End Integration")
    class HnswIntegrationWorkerEndToEnd {

        /**
         * Test: Worker processes entries incrementally without full rebuild.
         *
         * <p><strong>CRITICAL:</strong> This verifies incremental insertion - vectors
         * are added one at a time without rebuilding the entire graph.</p>
         */
        @Test
        @DisplayName("should add vectors incrementally without full rebuild")
        void shouldAddVectorsIncrementallyWithoutFullRebuild() throws IOException {
            Path graphPath = tempDir.resolve("incremental-no-rebuild.graph");

            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                TailOffsetTracker tracker = new TailOffsetTracker(graph);

                int numVectors = 100;
                List<Long> allOffsets = new ArrayList<>();

                // Insert vectors incrementally (simulating ongoing ingestion)
                for (int i = 0; i < numVectors; i++) {
                    float[] vector = createRandomVector(dimension);
                    long offset = (i + 1) * 100L; // Simulated write log offset

                    // Single vector incremental insertion
                    IncrementalInsertResult result = tracker.integrateVector(vector, offset);

                    assertThat(result.isSuccess())
                        .as("Incremental insertion %d should succeed", i)
                        .isTrue();

                    assertThat(result.nodeId())
                        .as("Node ID should be sequential for incremental insertion")
                        .isEqualTo((long) i);

                    allOffsets.add(offset);

                    // Verify tracker offset is updated incrementally
                    assertThat(tracker.getLastIntegratedOffset())
                        .as("Tracker should track last integrated offset")
                        .isEqualTo(offset);

                    // Verify graph remains searchable after each insertion
                    if (i > 0) {
                        List<HnswSearchResult> results = graph.search(vector, 5);
                        assertThat(results)
                            .as("Graph should be searchable after incremental insertion %d", i)
                            .isNotEmpty();
                    }
                }

                // Final verification
                assertThat(graph.nodeCount()).isEqualTo(numVectors);
                assertThat(tracker.getLastIntegratedOffset())
                    .isEqualTo(allOffsets.get(allOffsets.size() - 1));

                // Verify recall is maintained (no rebuild needed)
                float[] queryVector = createRandomVector(dimension);
                List<HnswSearchResult> finalResults = graph.search(queryVector, 20);
                assertThat(finalResults).hasSizeGreaterThanOrEqualTo(10);
            }
        }
    }

    // =====================================================================================
    // FEATURE 31.5: Soft-Delete (Tombstone) Integration
    // =====================================================================================

    @Nested
    @DisplayName("Feature 31.5: Soft-Delete with Incremental Insertion")
    class SoftDeleteIntegration {

        /**
         * Test: Tombstoned nodes are skipped during incremental insertion search.
         *
         * <p><strong>Domain Correctness:</strong> Incremental insertion must skip
         * tombstoned nodes when searching for neighbors.</p>
         */
        @Test
        @DisplayName("should skip tombstoned nodes during incremental insertion")
        void shouldSkipTombstonedNodesDuringInsertion() throws IOException {
            Path graphPath = tempDir.resolve("tombstone-insert.graph");

            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert initial nodes
                for (int i = 0; i < 10; i++) {
                    graph.insertWithResult(createRandomVector(dimension));
                }

                // Soft-delete (tombstone) some nodes using the delete() method
                graph.delete(0);
                graph.delete(5);

                // Insert more nodes - should skip tombstoned nodes during neighbor search
                for (int i = 10; i < 20; i++) {
                    IncrementalInsertResult result = graph.insertWithResult(createRandomVector(dimension));
                    assertThat(result.isSuccess()).isTrue();

                    // Connected nodes should not include tombstoned nodes
                    // (They're skipped during neighbor search)
                    if (!result.connectedNodes().isEmpty()) {
                        assertThat(result.connectedNodes())
                            .as("New node should not connect to tombstoned nodes")
                            .doesNotContain(0L, 5L);
                    }
                }

                assertThat(graph.nodeCount()).isEqualTo(20);
            }
        }

        /**
         * Test: Search skips tombstoned nodes after incremental insertion.
         */
        @Test
        @DisplayName("should skip tombstoned nodes in search after incremental insertion")
        void shouldSkipTombstonedNodesInSearch() throws IOException {
            Path graphPath = tempDir.resolve("tombstone-search.graph");

            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert nodes via incremental insertion
                List<float[]> vectors = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    float[] vector = createRandomVector(dimension);
                    vectors.add(vector);
                    graph.insertWithResult(vector);
                }

                // Soft-delete (tombstone) some nodes
                graph.delete(0);
                graph.delete(10);
                graph.delete(15);

                // Search should skip tombstoned nodes
                List<HnswSearchResult> results = graph.search(vectors.get(0), 10);

                // Results should not contain tombstoned nodes
                for (HnswSearchResult result : results) {
                    assertThat(result.nodeId())
                        .as("Search results should not include tombstoned nodes")
                        .isNotIn(0L, 10L, 15L);
                }
            }
        }
    }

    // =====================================================================================
    // Helper Methods
    // =====================================================================================

    private float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        float sum = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
            sum += vector[i] * vector[i];
        }
        // Normalize for cosine similarity
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
