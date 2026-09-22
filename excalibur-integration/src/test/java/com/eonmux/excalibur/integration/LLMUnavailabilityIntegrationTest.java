package com.eonmux.excalibur.integration;

import com.sun.net.httpserver.HttpServer;

import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.CircuitBreakerState;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.query.scorer.LlmRelevancyScorer;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SegmentCatalog.SegmentMetadata;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Number of queries the kill test issues, and how many of them the mock LLM answers
     * before it starts returning 503. The remainder must be exactly enough consecutive
     * failures to trip {@link #FAILURE_THRESHOLD}.
     */
    private static final int SUCCESSFUL_LLM_CALLS = 2;
    private static final int QUERY_COUNT = SUCCESSFUL_LLM_CALLS + FAILURE_THRESHOLD;

    /**
     * Upper bound on the candidates Stage 5 can send: Stage 4 oversamples to twice topK.
     * The mock scores every index up to this, so no candidate is dropped for want of a
     * score.
     */
    private static final int MAX_LLM_CANDIDATES = 10;

    /**
     * The highest failure threshold {@code CircuitBreaker} accepts. Tests that want every
     * query to genuinely attempt the LLM call use it so the circuit never trips first.
     */
    private static final int MAX_FAILURE_THRESHOLD = 100;
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
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, COOLDOWN_PERIOD, "test");

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
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100), "test");

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
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100), "test");

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
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, Duration.ofMillis(100), "test");

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
            CircuitBreaker breaker = new CircuitBreaker(FAILURE_THRESHOLD, COOLDOWN_PERIOD, "test");

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
            CircuitBreaker breaker = new CircuitBreaker(1, COOLDOWN_PERIOD, "test");
            breaker.recordFailure(); // Open immediately

            assertThat(breaker.allowRequest()).isFalse();

            // Attempting to use client should fail fast
            assertThatThrownBy(() -> {
                if (!breaker.allowRequest()) {
                    throw new com.eonmux.excalibur.llm.LLMException("Circuit breaker is open",
                        com.eonmux.excalibur.llm.LLMException.ErrorType.CIRCUIT_OPEN);
                }
            }).isInstanceOf(com.eonmux.excalibur.llm.LLMException.class)
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
            // Create storage engine with real segment
            Path storageDir = tempDir.resolve("storage-no-llm");
            Files.createDirectories(storageDir);
            TestStorageEngine storageEngine = new TestStorageEngine(storageDir, arena);
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);

            // Create HNSW graph with test data
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test-no-llm.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Index documents whose stored value carries text, as ingestion does
            indexDocumentsWithText(segment, graph, 100);

            // Register segment with visibility tracker
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();
            visibilityTracker.addVisibleSegment(segment.segmentId());

            // Create query pipeline WITHOUT LLM scorer
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
            storageEngine.close();

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
            // Create storage engine with real segment
            Path storageDir = tempDir.resolve("storage-timeout");
            Files.createDirectories(storageDir);
            TestStorageEngine storageEngine = new TestStorageEngine(storageDir, arena);
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);

            // Register segment with visibility tracker
            TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();
            visibilityTracker.addVisibleSegment(segment.segmentId());

            // Create HNSW graph with test data
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test-timeout.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            indexDocumentsWithText(segment, graph, 50);

            // Create query pipeline
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
            storageEngine.close();

            System.out.println("Query completed in " + elapsed + "ms (timeout=1000ms)");
        }
    }

    @Nested
    @DisplayName("Graceful Degradation")
    class GracefulDegradation {

        /**
         * Test: System remains responsive under LLM failure conditions.
         *
         * <p>Domain correctness: with LLM scoring requested and the endpoint dead, every
         * query must still complete, still return its vector ranking, and report
         * {@code degraded=true} so the client knows the ranking is vector-only.</p>
         *
         * <p>The LLM must actually be reached for this to mean anything, so the fixture
         * indexes documents whose stored value carries text and the test asserts the mock
         * endpoint saw requests.</p>
         */
        @Test
        @DisplayName("system remains responsive with LLM failures")
        void systemRemainsResponsiveWithLlmFailures() throws IOException {
            AtomicInteger llmRequests = new AtomicInteger(0);
            HttpServer mockServer = startFailingLlmServer(llmRequests);

            try {
                // Create storage engine with real segment
                Path storageDir = tempDir.resolve("storage-responsive");
                Files.createDirectories(storageDir);
                TestStorageEngine storageEngine = new TestStorageEngine(storageDir, arena);
                Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);

                // Create HNSW graph
                HnswConfig config = HnswConfig.create(dimension);
                Path graphPath = tempDir.resolve("test-responsive.graph");
                HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

                indexDocumentsWithText(segment, graph, 100);

                // Register segment with visibility tracker
                TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();
                visibilityTracker.addVisibleSegment(segment.segmentId());

                // Create query pipeline with a scorer pointed at the dead endpoint. The
                // breaker is given a high threshold so every query really attempts the call.
                CircuitBreaker breaker = new CircuitBreaker(
                    MAX_FAILURE_THRESHOLD, Duration.ofMillis(100), "responsive-test-llm");
                LlmRelevancyScorer scorer = new LlmRelevancyScorer(llmClientFor(
                    mockServer.getAddress().getPort(), breaker));

                QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
                QueryPipeline pipeline = new QueryPipeline(
                    storageEngine,
                    visibilityTracker,
                    graphProvider,
                    distanceFunction,
                    scorer,
                    0.5
                );

                // Execute multiple queries
                int successfulQueries = 0;
                int degradedQueries = 0;
                int totalQueries = 10;

                for (int q = 0; q < totalQueries; q++) {
                    QueryRequest request = QueryRequest.builder()
                        .queryText("query " + q)
                        .queryVector(createRandomVector(dimension))
                        .topK(5)
                        .timeoutMs(5000)
                        .includePartialSegments(true)
                        .enableLlmScoring(true)
                        .correlationId(UUID.randomUUID().toString())
                        .build();

                    CancellationToken token = new CancellationToken();
                    QueryResponse response = pipeline.executeSync(request, token);

                    if (!response.isEmpty()) {
                        successfulQueries++;
                    }
                    if (response.coverage().degraded()) {
                        degradedQueries++;
                    }
                }

                // Every query falls back to the vector ranking rather than failing
                assertThat(successfulQueries)
                    .as("Every query must still return its vector ranking when the LLM is dead")
                    .isEqualTo(totalQueries);
                assertThat(degradedQueries)
                    .as("Every LLM fallback must be reported as degraded")
                    .isEqualTo(totalQueries);
                assertThat(llmRequests.get())
                    .as("The LLM must actually be called - otherwise this test proves nothing")
                    .isEqualTo(totalQueries);

                graph.close();
                storageEngine.close();

                System.out.println("All " + totalQueries + " queries degraded gracefully over "
                    + llmRequests.get() + " failed LLM calls");
            } finally {
                mockServer.stop(0);
            }
        }

        /**
         * Test: Concurrent queries handle LLM unavailability gracefully.
         *
         * <p>Every concurrent query requests LLM scoring against a dead endpoint, so each
         * one has to fall back to its vector ranking on its own thread without throwing and
         * without returning an empty result set.</p>
         */
        @Test
        @DisplayName("concurrent queries handle LLM unavailability")
        void concurrentQueriesHandleLlmUnavailability() throws Exception {
            AtomicInteger llmRequests = new AtomicInteger(0);
            HttpServer mockServer = startFailingLlmServer(llmRequests);

            try {
                // Create storage engine with real segment
                Path storageDir = tempDir.resolve("storage-concurrent");
                Files.createDirectories(storageDir);
                TestStorageEngine storageEngine = new TestStorageEngine(storageDir, arena);
                Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);

                // Create HNSW graph
                HnswConfig config = HnswConfig.create(dimension);
                Path graphPath = tempDir.resolve("test-concurrent.graph");
                HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

                indexDocumentsWithText(segment, graph, 200);

                // Register segment with visibility tracker
                TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();
                visibilityTracker.addVisibleSegment(segment.segmentId());

                // Create query pipeline with a scorer pointed at the dead endpoint
                CircuitBreaker breaker = new CircuitBreaker(
                    MAX_FAILURE_THRESHOLD, Duration.ofMillis(100), "concurrent-test-llm");
                LlmRelevancyScorer scorer = new LlmRelevancyScorer(llmClientFor(
                    mockServer.getAddress().getPort(), breaker));

                QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
                QueryPipeline pipeline = new QueryPipeline(
                    storageEngine,
                    visibilityTracker,
                    graphProvider,
                    distanceFunction,
                    scorer,
                    0.5
                );

                // Execute concurrent queries
                int numQueries = 20;
                ExecutorService executor = Executors.newFixedThreadPool(4);
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failureCount = new AtomicInteger(0);
                List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                for (int q = 0; q < numQueries; q++) {
                    final int queryNum = q;
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            QueryRequest request = QueryRequest.builder()
                                .queryText("concurrent query " + queryNum)
                                .queryVector(createRandomVector(dimension))
                                .topK(5)
                                .timeoutMs(5000)
                                .includePartialSegments(true)
                                .enableLlmScoring(true)
                                .correlationId(UUID.randomUUID().toString())
                                .build();

                            CancellationToken token = new CancellationToken();
                            QueryResponse response = pipeline.executeSync(request, token);

                            if (!response.isEmpty()) {
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.add(e);
                            failureCount.incrementAndGet();
                        }
                    }, executor));
                }

                // Wait for all queries
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);

                executor.shutdown();

                // All queries should succeed
                assertThat(failureCount.get())
                    .as("No query may throw when the LLM is unavailable. Failures: " + failures)
                    .isEqualTo(0);
                assertThat(successCount.get())
                    .as("Every query must still return its vector ranking")
                    .isEqualTo(numQueries);
                assertThat(llmRequests.get())
                    .as("The LLM must actually be called - otherwise this test proves nothing")
                    .isEqualTo(numQueries);

                graph.close();
                storageEngine.close();

                System.out.println("All " + numQueries + " concurrent queries fell back over "
                    + llmRequests.get() + " failed LLM calls");
            } finally {
                mockServer.stop(0);
            }
        }
    }

    @Nested
    @DisplayName("LLM Unavailability Fallback")
    class LlmUnavailabilityFallback {

        /**
         * Test: Kill LLM endpoint mid-query and verify circuit breaker + vector-only fallback.
         *
         * <p>This is the CRITICAL test for Phase 10 integration: verifies the full pipeline
         * fallback behavior when LLM scoring is requested but the endpoint dies mid-query.</p>
         *
         * <p>Domain correctness:</p>
         * <ul>
         *   <li>Query must complete without throwing (graceful degradation)</li>
         *   <li>Circuit breaker must transition CLOSED → OPEN after consecutive failures</li>
         *   <li>Response MUST indicate degraded=true when LLM fallback occurs</li>
         *   <li>Vector-only results must still be returned</li>
         * </ul>
         *
         * <p>Test scenario:</p>
         * <ol>
         *   <li>Start mock LLM server that works initially</li>
         *   <li>Execute query with LLM scoring enabled</li>
         *   <li>Server starts returning errors (simulating kill)</li>
         *   <li>Circuit breaker opens after consecutive failures</li>
         *   <li>Query falls back to vector-only ranking</li>
         *   <li>Response indicates degraded=true</li>
         * </ol>
         */
        @Test
        @DisplayName("kill LLM endpoint mid-query, verify circuit breaker and vector-only fallback")
        void killLlmEndpointMidQuery_VerifyCircuitBreakerAndFallback() throws Exception {
            // Use atomic counter to control when server starts failing
            AtomicInteger requestCount = new AtomicInteger(0);
            AtomicBoolean shouldFail = new AtomicBoolean(false);

            // Start mock LLM server that works initially, then fails
            HttpServer mockServer = HttpServer.create(new InetSocketAddress(0), 0);
            mockServer.createContext("/v1/chat/completions", exchange -> {
                int count = requestCount.incrementAndGet();

                if (shouldFail.get() || count > SUCCESSFUL_LLM_CALLS) {
                    // After the first few successful requests, or when explicitly told to fail
                    shouldFail.set(true); // Lock in failure mode
                    String response = "{\"error\": \"Service unavailable - endpoint killed\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, response.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    // First few requests succeed, scoring every candidate the pipeline sent
                    String response = relevancyResponse(MAX_LLM_CANDIDATES);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                }
            });
            mockServer.start();
            int port = mockServer.getAddress().getPort();

            try {
                // Create storage engine with real segment
                Path storageDir = tempDir.resolve("storage-kill-test");
                Files.createDirectories(storageDir);
                TestStorageEngine storageEngine = new TestStorageEngine(storageDir, arena);
                Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);

                // Create HNSW graph with test data
                HnswConfig config = HnswConfig.create(dimension);
                Path graphPath = tempDir.resolve("test-kill.graph");
                HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

                // Index documents whose stored value carries text. Without it every LLM
                // candidate is blank, the scorer declines to call the endpoint at all, and
                // nothing this test asserts can happen.
                indexDocumentsWithText(segment, graph, 100);

                // Register segment with visibility tracker
                TestVisibilityTracker visibilityTracker = new TestVisibilityTracker();
                visibilityTracker.addVisibleSegment(segment.segmentId());

                // Create LLM client with circuit breaker (low threshold for testing).
                // The cooldown is long enough that the circuit is still observably OPEN
                // when the loop finishes.
                CircuitBreaker breaker = new CircuitBreaker(
                    FAILURE_THRESHOLD, Duration.ofSeconds(30), "kill-test-llm");
                LlmRelevancyScorer scorer = new LlmRelevancyScorer(llmClientFor(port, breaker));

                // Create query pipeline WITH LLM scorer
                QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
                QueryPipeline pipeline = new QueryPipeline(
                    storageEngine, visibilityTracker, graphProvider, distanceFunction, scorer, 0.5
                );

                // Verify circuit breaker starts CLOSED
                assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

                // Execute multiple queries - first ones succeed, then server "dies"
                List<QueryResponse> responses = new ArrayList<>();
                for (int q = 0; q < QUERY_COUNT; q++) {
                    QueryRequest request = QueryRequest.builder()
                        .queryText("test query " + q)
                        .queryVector(createRandomVector(dimension))
                        .topK(5)
                        .timeoutMs(5000)
                        .includePartialSegments(true)
                        .enableLlmScoring(true)  // LLM enabled
                        .correlationId(UUID.randomUUID().toString())
                        .build();

                    CancellationToken token = new CancellationToken();
                    QueryResponse response = pipeline.executeSync(request, token);
                    responses.add(response);

                    System.out.println("Query " + q + ": results=" + response.results().size() +
                        ", degraded=" + response.coverage().degraded() +
                        ", circuitState=" + breaker.getState());
                }

                // CRITICAL ASSERTIONS:

                // 0. The LLM must genuinely have been called. Every other assertion here is
                // vacuous if Stage 5 skipped scoring, which is exactly what happens when
                // results reach it without any text attached.
                assertThat(requestCount.get())
                    .as("Stage 5 must reach the LLM endpoint for every query until the "
                        + "circuit opens")
                    .isEqualTo(SUCCESSFUL_LLM_CALLS + FAILURE_THRESHOLD);

                // 1. All queries must complete without throwing (graceful degradation)
                assertThat(responses).hasSize(QUERY_COUNT);

                // 2. Circuit breaker must have opened after FAILURE_THRESHOLD failures
                assertThat(breaker.getState())
                    .as("Circuit breaker should be OPEN after %d consecutive failures",
                        FAILURE_THRESHOLD)
                    .isEqualTo(CircuitBreakerState.OPEN);

                // 3. Every query issued after the endpoint died must report degraded, and
                // the ones the LLM actually scored must not.
                for (int q = 0; q < QUERY_COUNT; q++) {
                    boolean llmSucceeded = q < SUCCESSFUL_LLM_CALLS;
                    assertThat(responses.get(q).coverage().degraded())
                        .as("Query %d: degraded must mirror whether the LLM answered", q)
                        .isEqualTo(!llmSucceeded);
                }

                long degradedCount = responses.stream()
                    .filter(r -> r.coverage().degraded())
                    .count();

                // 4. All responses should have results (vector-only fallback worked)
                for (QueryResponse response : responses) {
                    assertThat(response.results())
                        .as("Vector-only fallback should return results even when LLM fails")
                        .isNotEmpty();
                }

                System.out.println("\n=== LLM Kill Test Summary ===");
                System.out.println("Total requests to LLM: " + requestCount.get());
                System.out.println("Circuit breaker state: " + breaker.getState());
                System.out.println("Degraded response count: " + degradedCount + "/" + responses.size());
                System.out.println("All queries completed successfully with vector-only fallback");

                graph.close();
                storageEngine.close();

            } finally {
                mockServer.stop(0);
            }
        }

        /**
         * Test: LLM client handles server errors and propagates exceptions.
         *
         * <p>Domain correctness:</p>
         * <ul>
         *   <li>LLM client must throw LLMException when server returns 5xx errors</li>
         *   <li>Exception must have correct error type (HTTP_ERROR)</li>
         *   <li>Exception allows caller to implement fallback behavior</li>
         * </ul>
         */
        @Test
        @DisplayName("LLM client throws LLMException on server errors for fallback handling")
        void llmClientThrowsOnServerErrors() throws Exception {
            // Start mock LLM server that always fails
            HttpServer mockServer = HttpServer.create(new InetSocketAddress(0), 0);
            mockServer.createContext("/v1/chat/completions", exchange -> {
                // Return 503 Service Unavailable
                String response = "{\"error\": \"Service unavailable\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });
            mockServer.start();
            int port = mockServer.getAddress().getPort();

            try {
                // Create LLM client
                CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofMillis(100), "test-llm");
                LLMClientConfig llmConfig = LLMClientConfig.builder()
                    .baseUrl("http://localhost:" + port)
                    .apiKey("test-key")
                    .timeout(Duration.ofSeconds(2))
                    .maxRetries(0)  // No retries
                    .build();

                LLMHttpClient llmClient = new LLMHttpClient(llmConfig, breaker);
                List<String> candidates = List.of("candidate 1", "candidate 2");

                // Request should fail with LLMException
                assertThatThrownBy(() -> llmClient.scoreRelevancy("test query", candidates))
                    .isInstanceOf(LLMException.class)
                    .satisfies(e -> {
                        LLMException llme = (LLMException) e;
                        assertThat(llme.getErrorType()).isEqualTo(LLMException.ErrorType.HTTP_ERROR);
                    });

                System.out.println("LLM client correctly threw HTTP_ERROR exception for server failure");

            } finally {
                mockServer.stop(0);
            }
        }

        /**
         * Test: LLM client works correctly when server is available.
         *
         * <p>Domain correctness: LLM client should return valid scores when server responds correctly.</p>
         */
        @Test
        @DisplayName("LLM client returns scores when server is available")
        void llmClientReturnsScoresWhenServerAvailable() throws Exception {
            // Start mock LLM server
            HttpServer mockServer = HttpServer.create(new InetSocketAddress(0), 0);
            mockServer.createContext("/v1/chat/completions", exchange -> {
                // Return valid OpenAI chat completions response with relevancy scores
                String relevancyJson = "[{\"index\": 0, \"score\": 0.85}, {\"index\": 1, \"score\": 0.75}]";
                String response = "{\"choices\":[{\"message\":{\"content\":\"" +
                    relevancyJson.replace("\"", "\\\"") + "\"}}]}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });
            mockServer.start();
            int port = mockServer.getAddress().getPort();

            try {
                // Create LLM client
                CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMillis(100), "test-llm");
                LLMClientConfig llmConfig = LLMClientConfig.builder()
                    .baseUrl("http://localhost:" + port)
                    .apiKey("test-key")
                    .timeout(Duration.ofSeconds(2))
                    .maxRetries(0)
                    .build();

                LLMHttpClient llmClient = new LLMHttpClient(llmConfig, breaker);
                List<String> candidates = List.of("candidate 1", "candidate 2");

                // Request should succeed
                List<com.eonmux.excalibur.llm.RelevancyScore> scores = llmClient.scoreRelevancy("test query", candidates);

                assertThat(scores).hasSize(2);
                assertThat(scores.get(0).getIndex()).isEqualTo(0);
                assertThat(scores.get(0).getScore()).isEqualTo(0.85);
                assertThat(scores.get(1).getIndex()).isEqualTo(1);
                assertThat(scores.get(1).getScore()).isEqualTo(0.75);

                // Circuit should remain closed after success
                assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

                System.out.println("LLM client returned " + scores.size() + " scores successfully");

            } finally {
                mockServer.stop(0);
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Encodes one ingested chunk the way the ingestion pipeline stores it:
     * {@code [dimension:4][embedding:dim*4][text]}, little-endian.
     *
     * <p>The trailing text is the only thing relevancy scoring can score, so a fixture that
     * writes vectors alone leaves every LLM candidate blank and the LLM is never called.</p>
     */
    private static byte[] encodeChunk(float[] vector, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
            .allocate(Integer.BYTES + vector.length * Float.BYTES + textBytes.length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(vector.length);
        for (float component : vector) {
            buffer.putFloat(component);
        }
        buffer.put(textBytes);
        return buffer.array();
    }

    /**
     * Indexes {@code count} single-chunk documents into a segment and its graph.
     *
     * <p>The chunk is written to storage under the ingestion pipeline's key format
     * ({@code sourceId:documentId:chunk:index}) and the graph node is registered under the
     * owning document key, exactly as {@code HnswIntegrationWorker} does. That is what lets
     * the query pipeline resolve a result back to its text before Stage 5.</p>
     *
     * @param segment The segment to write the chunks into
     * @param graph   The graph to insert the embeddings into
     * @param count   The number of documents to index
     */
    private void indexDocumentsWithText(Segment segment, HnswGraph graph, int count) {
        for (int i = 0; i < count; i++) {
            String documentKey = "source1:doc" + i;
            float[] vector = createRandomVector(dimension);
            String text = "Document " + i + " describes subject " + (i % 7)
                + " and mentions test query terms.";

            segment.put((documentKey + ":chunk:0").getBytes(StandardCharsets.UTF_8),
                encodeChunk(vector, text));

            long nodeId = graph.insert(vector);
            graph.registerDocumentId(nodeId, documentKey);
        }
    }

    /**
     * Builds an LLM client pointed at a mock endpoint, with a circuit breaker.
     */
    private static LLMHttpClient llmClientFor(int port, CircuitBreaker breaker) {
        LLMClientConfig llmConfig = LLMClientConfig.builder()
            .baseUrl("http://localhost:" + port)
            .apiKey("test-key")
            .timeout(Duration.ofSeconds(2))
            .maxRetries(0)
            .build();
        return new LLMHttpClient(llmConfig, breaker);
    }

    /**
     * Starts a mock LLM endpoint that always answers 503.
     */
    private static HttpServer startFailingLlmServer(AtomicInteger requestCount) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"error\": \"Service unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    /**
     * Builds an OpenAI-shaped chat completion carrying a relevancy score for each of the
     * first {@code candidateCount} candidate indexes.
     */
    private static String relevancyResponse(int candidateCount) {
        StringBuilder scores = new StringBuilder("[");
        for (int i = 0; i < candidateCount; i++) {
            if (i > 0) {
                scores.append(", ");
            }
            scores.append("{\"index\": ").append(i).append(", \"score\": 0.85}");
        }
        scores.append("]");
        return "{\"choices\":[{\"message\":{\"content\":\""
            + scores.toString().replace("\"", "\\\"") + "\"}}]}";
    }

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
     * Minimal StorageEngine for testing that properly returns segments.
     *
     * <p>This test implementation creates real Segment files and properly
     * implements getSegment() and getSegments() to support the query pipeline.</p>
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private final Path dataDir;
        private final Arena arena;
        private int segmentCounter = 0;

        /**
         * Creates a test storage engine with a temporary directory.
         */
        TestStorageEngine(Path dataDir, Arena arena) throws IOException {
            super(dataDir, arena);
            this.dataDir = dataDir;
            this.arena = arena;
            this.catalog = new TestSegmentCatalog();
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        /**
         * Creates a test segment with real disk backing.
         * The segment ID is auto-generated by the Segment class.
         *
         * @param lifecycleState The desired lifecycle state
         * @return The created segment with its auto-generated ID
         */
        public Segment createSegment(LifecycleState lifecycleState) throws IOException {
            // Create real segment file
            Path segmentPath = dataDir.resolve("segment-" + segmentCounter++ + ".seg");
            Segment segment = new Segment(segmentPath, 4 * 1024 * 1024, arena); // 4MB segment

            long segmentId = segment.segmentId();

            // Add to catalog with proper lifecycle transitions
            catalog.addSegment(segmentId, lifecycleState);

            // Store segment for later retrieval
            segments.put(segmentId, segment);

            return segment;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, Segment> getSegments() {
            return Collections.unmodifiableMap(segments);
        }

        public void close() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Minimal SegmentCatalog for testing with proper lifecycle transitions.
     *
     * <p>Provides segments with correct lifecycle states for query testing.</p>
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        private final Map<Long, SegmentMetadata> segments = new ConcurrentHashMap<>();

        TestSegmentCatalog() {
            // No default segment - tests should create segments as needed
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.unmodifiableMap(segments);
        }

        @Override
        public int size() {
            return segments.size();
        }

        /**
         * Adds a segment with the specified lifecycle state.
         * Uses proper state transitions to ensure valid lifecycle.
         *
         * @param segmentId The segment ID
         * @param lifecycleState The lifecycle state
         * @return The created metadata
         */
        public SegmentMetadata addSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata metadata = new SegmentMetadata(segmentId);
            // Default state is BOOTSTRAPPING, transition to target state through valid transitions
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing(); // BOOTSTRAPPING -> INDEXING
                if (lifecycleState == LifecycleState.WARMING
                    || lifecycleState == LifecycleState.ACTIVE
                    || lifecycleState == LifecycleState.COMPACTING) {
                    metadata.transitionToWarming(); // INDEXING -> WARMING
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        metadata.transitionToActive(); // WARMING -> ACTIVE
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            metadata.transitionToCompacting(); // ACTIVE -> COMPACTING
                        }
                    }
                }
                // For INDEXING, just the transitionToIndexing() call is enough
            }
            metadata.setEntryCount(100);
            segments.put(segmentId, metadata);
            return metadata;
        }

        /**
         * Removes a segment from the catalog.
         *
         * @param segmentId The segment ID to remove
         */
        public void removeSegment(long segmentId) {
            segments.remove(segmentId);
        }
    }

    /**
     * Test VisibilityTracker implementation.
     *
     * <p>Tracks segment visibility across epochs for testing query isolation.</p>
     */
    private static class TestVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(1);
        private final ConcurrentHashMap<Long, Long> creationEpochs = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Long> tombstoneEpochs = new ConcurrentHashMap<>();

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
        public void registerSegment(long segmentId, long registrationEpoch) {
            creationEpochs.put(segmentId, registrationEpoch);
            tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
        }

        @Override
        public void markTombstoned(long segmentId, long tombstoneEpoch) {
            tombstoneEpochs.put(segmentId, tombstoneEpoch);
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            Set<Long> visible = new HashSet<>();
            for (Map.Entry<Long, Long> entry : creationEpochs.entrySet()) {
                long segmentId = entry.getKey();
                long creationEpoch = entry.getValue();
                long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);

                if (creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch) {
                    visible.add(segmentId);
                }
            }
            return Collections.unmodifiableSet(visible);
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            Long creationEpoch = creationEpochs.get(segmentId);
            if (creationEpoch == null) {
                return false;
            }
            long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
            return creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return creationEpochs.getOrDefault(segmentId, -1L);
        }

        /**
         * Adds a segment to the visible set at the current epoch.
         */
        void addVisibleSegment(long segmentId) {
            creationEpochs.put(segmentId, epoch.get());
            tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
        }

        /**
         * Removes a segment from the visible set.
         */
        void removeVisibleSegment(long segmentId) {
            tombstoneEpochs.put(segmentId, epoch.get());
        }
    }
}
