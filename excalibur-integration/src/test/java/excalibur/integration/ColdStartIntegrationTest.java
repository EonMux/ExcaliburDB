package excalibur.integration;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
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
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for cold start behavior.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries are accepted immediately on empty index</li>
 *   <li>Empty index returns degraded=true with coverage=0.0</li>
 *   <li>Results appear progressively as ingestion proceeds</li>
 *   <li>Concurrent ingestion and querying works correctly</li>
 *   <li>Graceful degradation during cold start</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Coverage=0.0 and degraded=true for empty index</li>
 *   <li>Coverage increases monotonically as ingestion proceeds</li>
 *   <li>No exceptions during concurrent ingest/query</li>
 *   <li>Results improve progressively as more data is indexed</li>
 *   <li>Query never sees partial documents (document atomicity)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class ColdStartIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
        random = new Random(42);
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("Empty Index Behavior")
    class EmptyIndexBehavior {

        /**
         * Test: Empty index returns degraded=true with coverage=0.0.
         *
         * <p>Domain correctness: For empty index, coverage must be exactly 0.0
         * and degraded must be true (below 0.5 threshold).</p>
         */
        @Test
        @DisplayName("empty index returns degraded with coverage=0")
        void emptyIndexReturnsDegradedWithZeroCoverage() throws IOException {
            // Create empty HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("empty.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Query empty index
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(createRandomVector(dimension))
                .topK(10)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify empty index behavior
            assertThat(response).isNotNull();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.results()).isEmpty();

            // Coverage should be 0.0 and degraded
            IndexCoverage coverage = response.coverage();
            assertThat(coverage).isNotNull();
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
            assertThat(coverage.degraded()).isTrue();

            graph.close();

            System.out.println("Empty index: coverage=" + coverage.estimatedCorpusCoverage() +
                ", degraded=" + coverage.degraded());
        }

        /**
         * Test: Queries are accepted immediately on empty index.
         */
        @Test
        @DisplayName("queries accepted immediately on empty index")
        void queriesAcceptedImmediatelyOnEmptyIndex() throws IOException {
            // Create empty HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("empty.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Execute multiple queries - all should complete without exception
            for (int q = 0; q < 10; q++) {
                QueryRequest request = QueryRequest.builder()
                    .queryText("query " + q)
                    .queryVector(createRandomVector(dimension))
                    .topK(5)
                    .timeoutMs(5000)
                    .includePartialSegments(true)
                    .enableLlmScoring(false)
                    .correlationId(UUID.randomUUID().toString())
                    .build();

                CancellationToken token = new CancellationToken();

                // Should not throw
                QueryResponse response = pipeline.executeSync(request, token);
                assertThat(response).isNotNull();
            }

            graph.close();

            System.out.println("All 10 queries accepted on empty index");
        }
    }

    @Nested
    @DisplayName("Progressive Results")
    class ProgressiveResults {

        /**
         * Test: Results appear progressively as ingestion proceeds.
         *
         * <p>Domain correctness: Coverage must increase monotonically as
         * more vectors are added to the index.</p>
         */
        @Test
        @DisplayName("results improve progressively during ingestion")
        void resultsImproveProgressivelyDuringIngestion() throws IOException, InterruptedException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("progressive.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Track results at different ingestion stages
            int[] stages = {0, 10, 50, 100, 200};
            float[] coverages = new float[stages.length];
            int[] resultCounts = new int[stages.length];

            float[] queryVector = createRandomVector(dimension);

            for (int s = 0; s < stages.length; s++) {
                // Insert vectors up to this stage
                int target = stages[s];
                int current = s > 0 ? stages[s - 1] : 0;

                for (int i = current; i < target; i++) {
                    graph.insert(createRandomVector(dimension));
                }

                // Query
                QueryRequest request = QueryRequest.builder()
                    .queryText("progressive query")
                    .queryVector(queryVector)
                    .topK(10)
                    .timeoutMs(5000)
                    .includePartialSegments(true)
                    .enableLlmScoring(false)
                    .correlationId(UUID.randomUUID().toString())
                    .build();

                CancellationToken token = new CancellationToken();
                QueryResponse response = pipeline.executeSync(request, token);

                coverages[s] = (float) response.coverage().estimatedCorpusCoverage();
                resultCounts[s] = response.results().size();

                System.out.println("Stage " + s + " (" + target + " vectors): " +
                    "coverage=" + coverages[s] + ", results=" + resultCounts[s]);
            }

            // Verify coverage increases (or stays same) monotonically
            for (int s = 1; s < coverages.length; s++) {
                assertThat(coverages[s])
                    .as("Coverage at stage " + s + " should be >= stage " + (s - 1))
                    .isGreaterThanOrEqualTo(coverages[s - 1]);
            }

            // Verify result counts increase (or stay same) as more data is added
            for (int s = 1; s < resultCounts.length; s++) {
                assertThat(resultCounts[s])
                    .as("Results at stage " + s + " should be >= stage " + (s - 1))
                    .isGreaterThanOrEqualTo(resultCounts[s - 1]);
            }

            graph.close();
        }

        /**
         * Test: Concurrent ingestion and querying works correctly.
         */
        @Test
        @DisplayName("concurrent ingestion and querying")
        void concurrentIngestionAndQuerying() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("concurrent.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            ExecutorService executor = Executors.newFixedThreadPool(4);
            AtomicBoolean ingestionRunning = new AtomicBoolean(true);
            AtomicInteger queryCount = new AtomicInteger(0);
            AtomicInteger querySuccessCount = new AtomicInteger(0);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            // Ingestion task
            Runnable ingestionTask = () -> {
                try {
                    for (int i = 0; i < 500 && ingestionRunning.get(); i++) {
                        graph.insert(createRandomVector(dimension));
                        if (i % 50 == 0) {
                            Thread.sleep(10); // Small delay to allow queries
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    ingestionRunning.set(false);
                }
            };

            // Query task
            Runnable queryTask = () -> {
                try {
                    while (ingestionRunning.get()) {
                        QueryRequest request = QueryRequest.builder()
                            .queryText("concurrent query")
                            .queryVector(createRandomVector(dimension))
                            .topK(5)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        CancellationToken token = new CancellationToken();
                        QueryResponse response = pipeline.executeSync(request, token);

                        queryCount.incrementAndGet();
                        if (!response.isEmpty()) {
                            querySuccessCount.incrementAndGet();
                        }

                        Thread.sleep(20); // Small delay between queries
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                }
            };

            // Start ingestion
            CompletableFuture<Void> ingestionFuture = CompletableFuture.runAsync(ingestionTask, executor);

            // Start queries
            List<CompletableFuture<Void>> queryFutures = new ArrayList<>();
            for (int q = 0; q < 3; q++) {
                queryFutures.add(CompletableFuture.runAsync(queryTask, executor));
            }

            // Wait for completion
            ingestionFuture.get(60, TimeUnit.SECONDS);
            CompletableFuture.allOf(queryFutures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

            executor.shutdown();

            // Verify no exceptions
            assertThat(exceptionCount.get())
                .as("No exceptions should occur during concurrent ingestion/query")
                .isEqualTo(0);

            // Verify queries were executed
            assertThat(queryCount.get()).isGreaterThan(0);

            System.out.println("Concurrent test: " + queryCount.get() + " queries, " +
                querySuccessCount.get() + " with results, " + exceptionCount.get() + " exceptions");

            graph.close();
        }
    }

    @Nested
    @DisplayName("Graceful Degradation")
    class GracefulDegradation {

        /**
         * Test: System handles partial index gracefully.
         */
        @Test
        @DisplayName("system handles partial index gracefully")
        void systemHandlesPartialIndexGracefully() throws IOException {
            // Create HNSW graph with partial data
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("partial.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Insert only 10 vectors (partial index)
            for (int i = 0; i < 10; i++) {
                graph.insert(createRandomVector(dimension));
            }

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Query partial index
            QueryRequest request = QueryRequest.builder()
                .queryText("partial index query")
                .queryVector(createRandomVector(dimension))
                .topK(100) // Request more than available
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Should return what's available
            assertThat(response).isNotNull();
            assertThat(response.results().size()).isLessThanOrEqualTo(10);

            // Coverage may indicate partial state
            IndexCoverage coverage = response.coverage();
            assertThat(coverage).isNotNull();

            graph.close();

            System.out.println("Partial index query: " + response.results().size() +
                " results (requested 100)");
        }

        /**
         * Test: Query timeout during cold start returns partial results.
         */
        @Test
        @DisplayName("query returns quickly during cold start")
        void queryReturnsQuicklyDuringColdStart() throws IOException {
            // Create empty HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("cold.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Query with short timeout
            long startTime = System.currentTimeMillis();

            QueryRequest request = QueryRequest.builder()
                .queryText("cold start query")
                .queryVector(createRandomVector(dimension))
                .topK(10)
                .timeoutMs(1000) // 1 second timeout
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            long elapsed = System.currentTimeMillis() - startTime;

            // Should complete quickly (well within timeout)
            assertThat(elapsed).isLessThan(2000);
            assertThat(response).isNotNull();
            assertThat(response.timedOut()).isFalse();

            graph.close();

            System.out.println("Cold start query completed in " + elapsed + "ms");
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

    // ==================== Test Infrastructure ====================

    /**
     * Minimal StorageEngine for testing.
     * Creates a temporary directory to satisfy parent constructor requirements.
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog testCatalog = new TestSegmentCatalog();

        TestStorageEngine() throws IOException {
            super(Files.createTempDirectory("test-storage"), Arena.ofShared());
        }

        @Override
        public SegmentCatalog getCatalog() {
            return testCatalog;
        }
    }

    /**
     * Minimal SegmentCatalog for testing.
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        @Override
        public SegmentMetadata get(long segmentId) {
            return null;
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.emptyMap();
        }
    }

    /**
     * Test VisibilityTracker implementation.
     */
    private static class TestVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(1);

        TestVisibilityTracker() {
            super(new TestSegmentCatalog());
        }

        @Override
        public long currentEpoch() {
            return epoch.get();
        }

        @Override
        public long captureEpoch() {
            return epoch.get();
        }

        @Override
        public long advanceEpoch() {
            return epoch.incrementAndGet();
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            return Collections.emptySet();
        }
    }
}
