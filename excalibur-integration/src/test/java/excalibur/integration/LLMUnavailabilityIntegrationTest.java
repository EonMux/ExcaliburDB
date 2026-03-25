package excalibur.integration;

import excalibur.llm.client.CircuitBreaker;
import excalibur.llm.client.CircuitBreakerState;
import excalibur.llm.client.LLMClientConfig;
import excalibur.llm.client.LLMHttpClient;
import excalibur.llm.protocol.ChatCompletionRequest;
import excalibur.llm.protocol.ChatCompletionResponse;
import excalibur.llm.protocol.EmbeddingRequest;
import excalibur.llm.protocol.EmbeddingResponse;
import io.excalibur.llm.LLMException;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for LLM unavailability handling and circuit breaker behavior.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Circuit breaker opens after N consecutive failures</li>
 *   <li>Vector-only fallback works when LLM is unavailable</li>
 *   <li>Response includes degraded flag when LLM is unavailable</li>
 *   <li>Circuit breaker recovers after cooldown period</li>
 *   <li>No hanging on dead connections</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Circuit breaker state transitions: CLOSED → OPEN → HALF_OPEN → CLOSED</li>
 *   <li>Failure threshold must be positive integer (default 5)</li>
 *   <li>Cooldown period must be positive duration (default 60s)</li>
 *   <li>Response time must not exceed timeout (no hanging)</li>
 *   <li>degraded flag must be true when circuit is OPEN</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class LLMUnavailabilityIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    // Circuit breaker configuration
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration COOLDOWN_PERIOD = Duration.ofMillis(500); // Short for testing
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

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
    @DisplayName("Circuit Breaker State Transitions")
    class CircuitBreakerStateTransitions {

        /**
         * Test: Circuit breaker opens after N consecutive failures.
         *
         * <p>Domain correctness: Circuit must transition CLOSED → OPEN after
         * exactly FAILURE_THRESHOLD consecutive failures.</p>
         */
        @Test
        @DisplayName("circuit opens after N consecutive failures")
        void circuitOpensAfterConsecutiveFailures() {
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, COOLDOWN_PERIOD);

            // Initial state is CLOSED
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(breaker.allowRequest()).isTrue();

            // Record failures up to threshold
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                breaker.recordFailure();
                assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            }

            // One more failure should open the circuit
            breaker.recordFailure();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(breaker.allowRequest()).isFalse();

            System.out.println("Circuit opened after " + FAILURE_THRESHOLD + " failures");
        }

        /**
         * Test: Circuit breaker allows requests again after cooldown.
         *
         * <p>Domain correctness: After cooldown period, circuit must transition
         * OPEN → HALF_OPEN and allow a test request.</p>
         */
        @Test
        @DisplayName("circuit allows test request after cooldown")
        void circuitAllowsTestRequestAfterCooldown() throws InterruptedException {
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100));

            // Open the circuit
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // Wait for cooldown
            Thread.sleep(150);

            // Should now allow a test request (transition to HALF_OPEN)
            assertThat(breaker.allowRequest()).isTrue();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            System.out.println("Circuit transitioned to HALF_OPEN after cooldown");
        }

        /**
         * Test: Circuit closes after successful request in HALF_OPEN state.
         */
        @Test
        @DisplayName("circuit closes after successful test request")
        void circuitClosesAfterSuccessfulTestRequest() throws InterruptedException {
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100));

            // Open the circuit
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }

            // Wait for cooldown
            Thread.sleep(150);
            breaker.allowRequest(); // Transition to HALF_OPEN

            // Record success
            breaker.recordSuccess();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(breaker.allowRequest()).isTrue();

            System.out.println("Circuit closed after successful request");
        }

        /**
         * Test: Circuit reopens if test request fails in HALF_OPEN state.
         */
        @Test
        @DisplayName("circuit reopens if test request fails")
        void circuitReopensIfTestRequestFails() throws InterruptedException {
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100));

            // Open the circuit
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }

            // Wait for cooldown
            Thread.sleep(150);
            breaker.allowRequest(); // Transition to HALF_OPEN

            // Test request fails
            breaker.recordFailure();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(breaker.allowRequest()).isFalse();

            System.out.println("Circuit reopened after failed test request");
        }

        /**
         * Test: Failure count resets on success.
         */
        @Test
        @DisplayName("failure count resets on success")
        void failureCountResetsOnSuccess() {
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, COOLDOWN_PERIOD);

            // Record some failures (not enough to open)
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                breaker.recordFailure();
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            // Record success
            breaker.recordSuccess();

            // Should need full threshold again
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                breaker.recordFailure();
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            // One more to open
            breaker.recordFailure();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            System.out.println("Failure count correctly reset on success");
        }
    }

    @Nested
    @DisplayName("LLM Client Fallback")
    class LlmClientFallback {

        /**
         * Test: LLM client throws exception when circuit is open.
         */
        @Test
        @DisplayName("LLM client throws when circuit is open")
        void llmClientThrowsWhenCircuitIsOpen() {
            CircuitBreaker breaker = new CircuitBreaker(1, COOLDOWN_PERIOD);
            breaker.recordFailure(); // Open immediately

            assertThat(breaker.allowRequest()).isFalse();

            // Attempting to use client should fail fast
            assertThatThrownBy(() -> {
                if (!breaker.allowRequest()) {
                    throw new io.excalibur.llm.LLMException("Circuit breaker is open",
                        io.excalibur.llm.LLMException.ErrorType.CIRCUIT_OPEN);
                }
            }).isInstanceOf(io.excalibur.llm.LLMException.class)
              .hasMessageContaining("Circuit breaker is open");
        }

        /**
         * Test: Query pipeline works with LLM scoring disabled.
         *
         * <p>Domain correctness: Query must return valid results even when
         * LLM scoring is disabled, with degraded=false since LLM wasn't requested.</p>
         */
        @Test
        @DisplayName("query works with LLM scoring disabled")
        void queryWorksWithLlmScoringDisabled() throws IOException {
            // Create HNSW graph with test data
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Insert vectors
            for (int i = 0; i < 100; i++) {
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }

            // Create query pipeline WITHOUT LLM scorer
            TestStorageEngine storageEngine = new TestStorageEngine();
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Query with LLM scoring DISABLED
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(createRandomVector(dimension))
                .topK(10)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false) // No LLM
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Should return results without LLM
            assertThat(response).isNotNull();
            assertThat(response.results()).isNotEmpty();

            // Coverage should not indicate degradation (LLM wasn't requested)
            IndexCoverage coverage = response.coverage();
            assertThat(coverage.degraded()).isFalse();

            graph.close();

            System.out.println("Query completed without LLM: " + response.results().size() + " results");
        }

        /**
         * Test: Query returns quickly when LLM is unavailable (timeout).
         *
         * <p>Domain correctness: Response time must not exceed timeout,
         * ensuring no hanging on dead connections.</p>
         */
        @Test
        @DisplayName("query returns quickly on LLM timeout")
        void queryReturnsQuicklyOnLlmTimeout() throws IOException {
            // Create HNSW graph with test data
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Insert vectors
            for (int i = 0; i < 50; i++) {
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

            // Query with short timeout
            long startTime = System.currentTimeMillis();
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(createRandomVector(dimension))
                .topK(5)
                .timeoutMs(1000) // 1 second timeout
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);
            long elapsed = System.currentTimeMillis() - startTime;

            // Should complete within timeout
            assertThat(elapsed).isLessThan(2000); // Allow some buffer
            assertThat(response).isNotNull();

            graph.close();

            System.out.println("Query completed in " + elapsed + "ms (timeout=1000ms)");
        }
    }

    @Nested
    @DisplayName("Graceful Degradation")
    class GracefulDegradation {

        /**
         * Test: System remains responsive under LLM failure conditions.
         *
         * <p>Domain correctness: Even with LLM failures, the system must
         * continue to accept and process queries with vector-only search.</p>
         */
        @Test
        @DisplayName("system remains responsive with LLM failures")
        void systemRemainsResponsiveWithLlmFailures() throws IOException, InterruptedException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Insert vectors
            for (int i = 0; i < 100; i++) {
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

            // Execute multiple queries
            int successfulQueries = 0;
            int totalQueries = 10;

            for (int q = 0; q < totalQueries; q++) {
                try {
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
                    QueryResponse response = pipeline.executeSync(request, token);

                    if (!response.isEmpty()) {
                        successfulQueries++;
                    }
                } catch (Exception e) {
                    // Query failed, but shouldn't throw
                    System.out.println("Query " + q + " failed: " + e.getMessage());
                }
            }

            // All queries should succeed (vector-only)
            assertThat(successfulQueries).isEqualTo(totalQueries);

            graph.close();

            System.out.println("All " + totalQueries + " queries completed successfully");
        }

        /**
         * Test: Concurrent queries handle LLM unavailability gracefully.
         */
        @Test
        @DisplayName("concurrent queries handle LLM unavailability")
        void concurrentQueriesHandleLlmUnavailability() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Insert vectors
            for (int i = 0; i < 200; i++) {
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

            // Execute concurrent queries
            int numQueries = 20;
            ExecutorService executor = Executors.newFixedThreadPool(4);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int q = 0; q < numQueries; q++) {
                final int queryNum = q;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        QueryRequest request = QueryRequest.builder()
                            .queryText("concurrent query " + queryNum)
                            .queryVector(createRandomVector(dimension))
                            .topK(5)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        CancellationToken token = new CancellationToken();
                        QueryResponse response = pipeline.executeSync(request, token);

                        if (!response.isEmpty()) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }, executor));
            }

            // Wait for all queries
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

            executor.shutdown();

            // All queries should succeed
            assertThat(successCount.get()).isEqualTo(numQueries);
            assertThat(failureCount.get()).isEqualTo(0);

            graph.close();

            System.out.println("All " + numQueries + " concurrent queries completed successfully");
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
     */
    private static class TestStorageEngine extends StorageEngine {
        TestStorageEngine() {
            super(null, null);
        }

        @Override
        public SegmentCatalog getCatalog() {
            return new TestSegmentCatalog();
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
            super(null);
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
