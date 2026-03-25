package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.LLMException.ErrorType;
import io.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case and integration tests for LLMHttpClient.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Error handling when circuit breaker is open</li>
 *   <li>Connection refused scenarios</li>
 *   <li>HTTP 5xx error retries</li>
 *   <li>Concurrent request handling</li>
 *   <li>Configuration edge cases</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP status codes: valid range [100, 599]</li>
 *   <li>Request timeout: positive value from configuration</li>
*   <li>Relevancy scores: each score in [0.0, 1.0]</li>
 *   <li>Circuit breaker: proper state transitions</li>
 * </ul>
 */
@Timeout(value = 30)
class LLMHttpClientEdgeCaseTest {

    private MockLLMServer server;
    private LLMClientConfig config;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== Circuit Breaker Edge Cases ====================

    @Nested
    @DisplayName("Circuit Breaker Edge Cases")
    class CircuitBreakerEdgeCases {

        @Test
        @DisplayName("scoreRelevancyWithCircuitBreaker: open circuit fails fast without HTTP call")
        void scoreRelevancyWithCircuitBreaker_openCircuit_failsFast() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .failureThreshold(1)
                    .build();
            client = new LLMHttpClient(config);

            // First, trip the circuit by causing a failure
            server.setChatHandler(server.errorHandler(500, "Internal Server Error"));
            assertThrows(LLMException.class, () ->
                client.scoreRelevancyWithCircuitBreaker("query", List.of("candidate"), "corr-1"));

            assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState());

            // Reset server to track if any further calls are made
            server.reset();
            server.setChatHandler(server.successRelevancyHandler(0.9));

            // Second call should fail fast with CIRCUIT_OPEN - no HTTP call
            int requestsBefore = server.getChatRequestCount();

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancyWithCircuitBreaker("query", List.of("candidate"), "corr-2"));

            assertEquals(ErrorType.CIRCUIT_OPEN, ex.getErrorType());
            assertEquals(requestsBefore, server.getChatRequestCount(),
                    "No HTTP request should be made when circuit is open");
        }

        @Test
        @DisplayName("Circuit breaker recovery after cooldown")
        void circuitBreaker_recoveryAfterCooldown() throws InterruptedException {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .failureThreshold(1)
                    .cooldownPeriod(Duration.ofMillis(200))
                    .build();
            client = new LLMHttpClient(config);

            // Trip the circuit via relevancy scoring
            server.setChatHandler(server.errorHandler(500, "Error"));
            assertThrows(LLMException.class, () ->
                client.scoreRelevancyWithCircuitBreaker("query", List.of("candidate"), "corr-1"));

            assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState());

            // Wait for cooldown to expire
            Thread.sleep(250);

            // Next request should succeed (HALF_OPEN)
            server.setChatHandler(server.successRelevancyHandler(0.85));
            List<RelevancyScore> result = client.scoreRelevancyWithCircuitBreaker("query", List.of("candidate"), "corr-2");

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals(0.85, result.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Circuit breaker resets after successful request")
        void circuitBreaker_resetsAfterSuccessfulRequest() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .failureThreshold(3)
                    .build();
            client = new LLMHttpClient(config);

            // Record some failures (but not enough to trip)
            server.setChatHandler(server.errorHandler(500, "Error"));
            assertThrows(LLMException.class, () ->
                client.scoreRelevancyWithCircuitBreaker("query", List.of("c1"), "corr-1"));
            assertThrows(LLMException.class, () ->
                client.scoreRelevancyWithCircuitBreaker("query", List.of("c2"), "corr-2"));

            assertEquals(2, client.getCircuitBreaker().getFailureCount());

            // Now succeed - should reset failure count
            server.setChatHandler(server.successRelevancyHandler(0.9));
            List<RelevancyScore> result = client.scoreRelevancyWithCircuitBreaker("query", List.of("c3"), "corr-3");

            assertNotNull(result);
            assertEquals(0, client.getCircuitBreaker().getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
        }
    }

    // ==================== Error Type Verification ====================

    @Nested
    @DisplayName("Error Type Verification")
    class ErrorTypeVerificationTests {

        @Test
        @DisplayName("HTTP 401 returns AUTH_ERROR")
        void http401_returnsAuthError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.unauthorizedHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.AUTH_ERROR, ex.getErrorType());
            assertEquals(401, ex.getStatusCode());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 403 returns AUTH_ERROR")
        void http403_returnsAuthError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.forbiddenHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.AUTH_ERROR, ex.getErrorType());
            assertEquals(403, ex.getStatusCode());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 400 returns INVALID_REQUEST")
        void http400_returnsInvalidRequest() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.badRequestHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.INVALID_REQUEST, ex.getErrorType());
            assertEquals(400, ex.getStatusCode());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 429 returns RATE_LIMITED")
        void http429_returnsRateLimited() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.rateLimitedHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.RATE_LIMITED, ex.getErrorType());
            assertEquals(429, ex.getStatusCode());
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 500 returns HTTP_ERROR")
        void http500_returnsHttpError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.internalServerErrorHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(500, ex.getStatusCode());
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 502 returns HTTP_ERROR (retryable)")
        void http502_returnsHttpError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(502, "Bad Gateway"));

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(502, ex.getStatusCode());
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP 503 returns HTTP_ERROR (retryable)")
        void http503_returnsHttpError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.serviceUnavailableHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(503, ex.getStatusCode());
            assertTrue(ex.isRetryable());
        }
    }

    // ==================== Concurrent Request Tests ====================

    @Nested
    @DisplayName("Concurrent Request Tests")
    class ConcurrentRequestTests {

        @Test
        @DisplayName("Concurrent relevancy scoring requests are thread-safe")
        void concurrentRelevancyScoringRequestsAreThreadSafe() throws Exception {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(10))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.successRelevancyHandler(0.85));

            int numThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));
                        if (scores.size() == 1 && scores.get(0).getScore() == 0.85) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(numThreads, successCount.get() + errorCount.get());
            assertTrue(successCount.get() >= numThreads * 0.8,
                "At least 80% of requests should succeed");
        }

        @Test
        @DisplayName("Concurrent embedding requests are thread-safe")
        void concurrentEmbeddingRequestsAreThreadSafe() throws Exception {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .embeddingDimension(3)
                    .timeout(Duration.ofSeconds(10))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f}));

            int numThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        float[] embedding = client.generateEmbedding("test " + threadId);
                        if (embedding != null && embedding.length == 3) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Log for debugging
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(numThreads, successCount.get());
        }

        @Test
        @DisplayName("Concurrent requests with circuit breaker under load")
        void concurrentRequestsWithCircuitBreakerUnderLoad() throws Exception {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(10))
                    .maxRetries(0)
                    .failureThreshold(1)  // Very low threshold to trigger quickly
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger failureCount = new AtomicInteger(0);
            AtomicInteger circuitOpenCount = new AtomicInteger(0);

            server.setChatHandler(exchange -> {
                // Always fail to trigger circuit breaker
                server.errorHandler(500, "Internal Server Error").handle(exchange);
            });

            int numThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int i = 0; i < numThreads; i++) {
                final String correlationId = "corr-" + i;
                executor.submit(() -> {
                    try {
                        client.scoreRelevancyWithCircuitBreaker("query", List.of("candidate"), correlationId);
                    } catch (LLMException e) {
                        failureCount.incrementAndGet();
                        if (e.getErrorType() == ErrorType.CIRCUIT_OPEN) {
                            circuitOpenCount.incrementAndGet();
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
            executor.shutdown();

            // All requests should fail (either HTTP error or CIRCUIT_OPEN)
            assertEquals(numThreads, failureCount.get(), "All requests should fail");

            // After concurrent failures, circuit breaker should be open
            assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState(),
                    "Circuit breaker should be open after failures");
        }
    }

    // ==================== Response Parsing Edge Cases ====================

    @Nested
    @DisplayName("Response Parsing Edge Cases")
    class ResponseParsingEdgeCases {

        @Test
        @DisplayName("Malformed JSON returns PARSE_ERROR")
        void malformedJson_returnsParseError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.malformedJsonHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.PARSE_ERROR, ex.getErrorType());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("Empty response returns PARSE_ERROR")
        void emptyResponse_returnsParseError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.emptyResponseHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.PARSE_ERROR, ex.getErrorType());
        }

        @Test
        @DisplayName("Missing content field returns PARSE_ERROR")
        void missingContentField_returnsParseError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.missingContentFieldHandler());

            LLMException ex = assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));

            assertEquals(ErrorType.PARSE_ERROR, ex.getErrorType());
        }

        @Test
        @DisplayName("Response with markdown fence extracts correctly")
        void responseWithMarkdownFence_extractsCorrectly() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.75));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.75, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Response with commentary extracts correctly")
        void responseWithCommentary_extractsCorrectly() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.successRelevancyWithCommentaryHandler(0.85));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.85, scores.get(0).getScore(), 0.001);
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness Tests")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("All relevancy scores are in valid range [0.0, 1.0]")
        void allRelevancyScoresInValidRange() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            // Return scores including out-of-range values
            server.setChatHandler(server.successRelevancyHandler(0.0, 0.5, 1.0, 1.5, -0.3));

            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("A", "B", "C", "D", "E"));

            for (RelevancyScore score : scores) {
                assertTrue(score.getScore() >= 0.0,
                    "Score must be >= 0.0, got: " + score.getScore());
                assertTrue(score.getScore() <= 1.0,
                    "Score must be <= 1.0, got: " + score.getScore());
            }
        }

        @Test
        @DisplayName("Embedding dimension matches configuration")
        void embeddingDimensionMatchesConfiguration() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .embeddingDimension(5)
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            float[] expected = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
            server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

            float[] result = client.generateEmbedding("test");

            assertEquals(5, result.length);
            assertArrayEquals(expected, result, 0.0001f);
        }

        @Test
        @DisplayName("Candidate text association is correct")
        void candidateTextAssociationIsCorrect() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            List<String> candidates = List.of("First doc", "Second doc", "Third doc");
            server.setChatHandler(server.successRelevancyHandler(0.9, 0.5, 0.1));

            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(3, scores.size());
            assertEquals("First doc", scores.get(0).getCandidateText());
            assertEquals("Second doc", scores.get(1).getCandidateText());
            assertEquals("Third doc", scores.get(2).getCandidateText());
        }

    }

    // ==================== Configuration Edge Cases ====================

    @Nested
    @DisplayName("Configuration Edge Cases")
    class ConfigurationEdgeCases {

        @Test
        @DisplayName("Null configuration throws NullPointerException")
        void nullConfiguration_throwsNullPointerException() {
            assertThrows(NullPointerException.class, () -> new LLMHttpClient(null));
        }

        @Test
        @DisplayName("Zero timeout throws IllegalArgumentException")
        void zeroTimeout_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofMillis(0))
                    .maxRetries(0)
                    .build());
        }

        @Test
        @DisplayName("Custom circuit breaker is used")
        void customCircuitBreaker_isUsed() {
            CircuitBreaker customCircuitBreaker = new CircuitBreaker(3, Duration.ofSeconds(30), "custom-test");
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .build();
            client = new LLMHttpClient(config, customCircuitBreaker);

            assertSame(customCircuitBreaker, client.getCircuitBreaker());
        }

    }

    // ==================== Integration Tests ====================

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Full pipeline: embed then score relevancy")
        void fullPipeline_embedThenScoreRelevancy() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .embeddingDimension(3)
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            // Generate embedding
            server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f}));
            float[] embedding = client.generateEmbedding("test document");

            assertNotNull(embedding);
            assertEquals(3, embedding.length);

            // Score relevancy
            server.setChatHandler(server.successRelevancyHandler(0.9));
            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test document"));

            assertEquals(1, scores.size());
            assertEquals(0.9, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Multiple candidates return correct indices")
        void multipleCandidates_returnCorrectIndices() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            List<String> candidates = List.of("doc1", "doc2", "doc3", "doc4", "doc5");
            server.setChatHandler(server.successRelevancyHandler(0.1, 0.2, 0.3, 0.4, 0.5));

            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(5, scores.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(i, scores.get(i).getIndex());
                assertEquals((i + 1) / 10.0, scores.get(i).getScore(), 0.001);
                assertEquals("doc" + (i + 1), scores.get(i).getCandidateText());
            }
        }

        @Test
        @DisplayName("Empty candidate list throws IllegalArgumentException")
        void emptyCandidateList_throwsIllegalArgumentException() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            assertThrows(IllegalArgumentException.class, () ->
                client.scoreRelevancy("query", Collections.emptyList()));
        }

        @Test
        @DisplayName("Null query throws NullPointerException")
        void nullQuery_throwsNullPointerException() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .embeddingModel("test-embedding")
                    .chatModel("test-chat")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.successRelevancyHandler(0.5));

            assertThrows(NullPointerException.class, () ->
                client.scoreRelevancy(null, List.of("candidate")));
        }
    }
}
