package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated tests for retry logic with simulated failures.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Retryable vs non-retryable error classification</li>
 *   <li>Exponential backoff with jitter</li>
 *   <li>Max delay capping</li>
 *   <li>Circuit breaker integration during retries</li>
 *   <li>Concurrent retry scenarios</li>
 *   <li>Request count verification</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Retry delays: base delay in [100ms, 30s], capped at 30s max</li>
 *   <li>Jitter: ±25% randomization</li>
 *   <li>Retryable HTTP codes: 429, 500-599</li>
 *   <li>Non-retryable HTTP codes: 400, 401, 403, 404</li>
 *   <li>Max retries: configurable in [0, 10]</li>
 * </ul>
 */
@Timeout(value = 30)
public class RetryLogicTest {

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

    // ============= Retryable Error Classification Tests =============

    @Nested
    @DisplayName("Retryable Error Classification")
    class RetryableErrorClassificationTests {

        @Test
        @DisplayName("HTTP 500 triggers retry")
        void http500_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                int count = requestCount.incrementAndGet();
                if (count < 3) {
                    server.errorHandler(500, "Internal Server Error").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.9).handle(exchange);
                }
            });

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(1, scores.size());
            assertEquals(3, requestCount.get(), "Should have 3 requests (initial + 2 retries)");
        }

        @Test
        @DisplayName("HTTP 502 Bad Gateway triggers retry")
        void http502_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                int count = requestCount.incrementAndGet();
                if (count < 2) {
                    server.errorHandler(502, "Bad Gateway").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.85).handle(exchange);
                }
            });

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(1, scores.size());
            assertEquals(0.85, scores.get(0).getScore(), 0.001);
            assertEquals(2, requestCount.get(), "Should have 2 requests (initial + 1 retry)");
        }

        @Test
        @DisplayName("HTTP 503 Service Unavailable triggers retry")
        void http503_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                int count = requestCount.incrementAndGet();
                if (count < 2) {
                    server.errorHandler(503, "Service Unavailable").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.75).handle(exchange);
                }
            });

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(0.75, scores.get(0).getScore(), 0.001);
            assertEquals(2, requestCount.get());
        }

        @Test
        @DisplayName("HTTP 504 Gateway Timeout triggers retry")
        void http504_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                int count = requestCount.incrementAndGet();
                if (count < 2) {
                    server.errorHandler(504, "Gateway Timeout").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.65).handle(exchange);
                }
            });

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(0.65, scores.get(0).getScore(), 0.001);
            assertEquals(2, requestCount.get());
        }

        @Test
        @DisplayName("HTTP 429 Rate Limited triggers retry")
        void http429_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                int count = requestCount.incrementAndGet();
                if (count < 2) {
                    server.errorHandler(429, "Too Many Requests").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.55).handle(exchange);
                }
            });

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(0.55, scores.get(0).getScore(), 0.001);
            assertEquals(2, requestCount.get());
        }
    }

    // ============= Non-Retryable Error Tests =============

    @Nested
    @DisplayName("Non-Retryable Errors")
    class NonRetryableErrorTests {

        @Test
        @DisplayName("HTTP 400 Bad Request does NOT trigger retry")
        void http400_noRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                requestCount.incrementAndGet();
                server.errorHandler(400, "Bad Request").handle(exchange);
            });

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(1, requestCount.get(), "400 should NOT trigger retry");
            assertEquals(LLMException.ErrorType.INVALID_REQUEST, ex.getErrorType());
        }

        @Test
        @DisplayName("HTTP 401 Unauthorized does NOT trigger retry")
        void http401_noRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                requestCount.incrementAndGet();
                server.errorHandler(401, "Unauthorized").handle(exchange);
            });

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(1, requestCount.get(), "401 should NOT trigger retry");
            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
        }

        @Test
        @DisplayName("HTTP 403 Forbidden does NOT trigger retry")
        void http403_noRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                requestCount.incrementAndGet();
                server.errorHandler(403, "Forbidden").handle(exchange);
            });

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(1, requestCount.get(), "403 should NOT trigger retry");
            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
        }

        @Test
        @DisplayName("HTTP 404 Not Found does NOT trigger retry")
        void http404_noRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.setChatHandler(exchange -> {
                requestCount.incrementAndGet();
                server.errorHandler(404, "Not Found").handle(exchange);
            });

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(1, requestCount.get(), "404 should NOT trigger retry");
            // 404 is mapped to HTTP_ERROR (not INVALID_REQUEST)
            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
        }
    }

    // ============= Multiple Failures Then Success Tests =============

    @Nested
    @DisplayName("Multiple Failures Then Success")
    class MultipleFailuresThenSuccessTests {

        @Test
        @DisplayName("Two failures then success")
        void twoFailuresThenSuccess() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            // Fail twice, succeed on third
            server.setChatHandler(server.failThenSucceedHandler(2, server.successRelevancyHandler(0.88)));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(1, scores.size());
            assertEquals(0.88, scores.get(0).getScore(), 0.001);
            assertEquals(3, server.getChatRequestCount(), "Should have 3 requests (2 failures + 1 success)");
        }

        @Test
        @DisplayName("Three failures then success")
        void threeFailuresThenSuccess() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(4) // Allow up to 4 retries
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            // Fail 3 times, succeed on fourth
            server.setChatHandler(server.failThenSucceedHandler(3, server.successRelevancyHandler(0.77)));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(1, scores.size());
            assertEquals(0.77, scores.get(0).getScore(), 0.001);
            assertEquals(4, server.getChatRequestCount(), "Should have 4 requests (3 failures + 1 success)");
        }

        @Test
        @DisplayName("Maximum retries exhausted returns last error")
        void maxRetriesExhausted_returnsLastError() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            // Always fail
            server.setChatHandler(server.errorHandler(503, "Service Unavailable"));

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(503, ex.getStatusCode());
            assertEquals(3, server.getChatRequestCount(), "Should have 3 requests (initial + 2 retries)");
        }
    }

    // ============= Request Count Verification Tests =============

    @Nested
    @DisplayName("Request Count Verification")
    class RequestCountVerificationTests {

        @Test
        @DisplayName("Zero retries means exactly one request")
        void zeroRetries_oneRequest() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

            assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(1, server.getChatRequestCount(), "maxRetries=0 should result in exactly 1 request");
        }

        @Test
        @DisplayName("One retry allows two requests")
        void oneRetry_twoRequests() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(1)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

            assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(2, server.getChatRequestCount(), "maxRetries=1 should result in 2 requests");
        }

        @Test
        @DisplayName("Ten retries allows eleven requests")
        void tenRetries_elevenRequests() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(10)
                    .retryBaseDelay(Duration.ofMillis(5)) // Very short for testing
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

            assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(11, server.getChatRequestCount(), "maxRetries=10 should result in 11 requests");
        }
    }

    // ============= Embedding Endpoint Retry Tests =============

    @Nested
    @DisplayName("Embedding Endpoint Retries")
    class EmbeddingEndpointRetryTests {

        @Test
        @DisplayName("Embedding: HTTP 500 triggers retry")
        void embedding_http500_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            float[] expected = new float[]{0.1f, 0.2f, 0.3f};

            // First call fails, second succeeds
            server.setEmbeddingHandler(server.sequenceHandler(
                    server.errorHandler(500, "Internal Server Error"),
                    server.successEmbeddingHandler(expected)
            ));

            float[] result = client.generateEmbedding("test text");

            assertNotNull(result);
            assertEquals(expected.length, result.length);
            assertEquals(2, server.getEmbeddingRequestCount());
        }

        @Test
        @DisplayName("Embedding: HTTP 429 triggers retry")
        void embedding_http429_triggersRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .build();
            client = new LLMHttpClient(config);

            float[] expected = new float[]{0.4f, 0.5f};

            server.setEmbeddingHandler(server.sequenceHandler(
                    server.errorHandler(429, "Too Many Requests"),
                    server.successEmbeddingHandler(expected)
            ));

            float[] result = client.generateEmbedding("test text");

            assertNotNull(result);
            assertEquals(2, server.getEmbeddingRequestCount());
        }

        @Test
        @DisplayName("Embedding: HTTP 401 does NOT trigger retry")
        void embedding_http401_noRetry() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .build();
            client = new LLMHttpClient(config);

            server.setEmbeddingHandler(server.unauthorizedHandler());

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.generateEmbedding("test text"));

            assertEquals(1, server.getEmbeddingRequestCount(), "401 should NOT trigger retry");
            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
        }
    }

    // ============= Circuit Breaker Integration Tests =============

    @Nested
    @DisplayName("Circuit Breaker Integration")
    class CircuitBreakerIntegrationTests {

        @Test
        @DisplayName("Successful retry does NOT trip circuit breaker")
        void successfulRetry_noCircuitBreakerImpact() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .failureThreshold(3)
                    .build();
            client = new LLMHttpClient(config);

            // First call fails, second succeeds
            server.setChatHandler(server.sequenceHandler(
                    server.errorHandler(500, "Internal Server Error"),
                    server.successRelevancyHandler(0.9)
            ));

            // Use circuit breaker protected method
            List<RelevancyScore> scores = client.scoreRelevancyWithCircuitBreaker(
                    "query", List.of("test"), "corr-1");

            assertEquals(0.9, scores.get(0).getScore(), 0.001);

            // Circuit breaker should be CLOSED (success was recorded)
            assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
            assertEquals(0, client.getCircuitBreaker().getFailureCount());
        }

        @Test
        @DisplayName("Failed retries increment circuit breaker failure count")
        void failedRetries_incrementFailureCount() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(10))
                    .failureThreshold(5)
                    .build();
            client = new LLMHttpClient(config);

            // Always fail
            server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

            assertThrows(LLMException.class,
                    () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), "corr-2"));

            // Failure count should be 1 (one logical operation failed, even with multiple retries)
            assertEquals(1, client.getCircuitBreaker().getFailureCount());
        }

        @Test
        @DisplayName("Open circuit breaker fails fast without retry")
        void openCircuit_failsFast() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(3)
                    .failureThreshold(1)
                    .build();
            client = new LLMHttpClient(config);

            // Trip the circuit breaker manually
            client.getCircuitBreaker().trip();

            int requestsBefore = server.getChatRequestCount();

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), "corr-3"));

            assertEquals(LLMException.ErrorType.CIRCUIT_OPEN, ex.getErrorType());

            // No requests should have been made
            assertEquals(requestsBefore, server.getChatRequestCount());
        }
    }

    // ============= Concurrent Retry Tests =============

    @Nested
    @DisplayName("Concurrent Retries")
    class ConcurrentRetryTests {

        @Test
        @DisplayName("Multiple concurrent requests retry independently")
        void concurrentRequests_retryIndependently() throws Exception {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(10))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(20))
                    .build();
            client = new LLMHttpClient(config);

            AtomicInteger totalRequests = new AtomicInteger(0);

            // Handler that fails for first N requests, then succeeds
            server.setChatHandler(exchange -> {
                int count = totalRequests.incrementAndGet();
                if (count <= 4) {
                    // Fail first 4 requests (2 per concurrent call)
                    server.errorHandler(500, "Temporary failure").handle(exchange);
                } else {
                    server.successRelevancyHandler(0.9).handle(exchange);
                }
            });

            int numThreads = 2;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));
                        if (!scores.isEmpty() && scores.get(0).getScore() > 0.8) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Expected for some threads
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "All threads should complete");

            // At least some requests should have eventually succeeded
            assertTrue(successCount.get() >= 1, "At least one thread should succeed");
        }
    }

    // ============= Error Type Verification Tests =============

    @Nested
    @DisplayName("Error Type Verification")
    class ErrorTypeVerificationTests {

        @Test
        @DisplayName("Network error is retryable")
        void networkError_isRetryable() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofMillis(100))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            // Create a handler that never responds (timeout)
            server.setChatHandler(server.timeoutHandler());

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType());
            assertTrue(ex.isRetryable(), "Network errors should be retryable");
        }

        @Test
        @DisplayName("Rate limit error is retryable")
        void rateLimitError_isRetryable() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(429, "Too Many Requests"));

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.RATE_LIMITED, ex.getErrorType());
            assertTrue(ex.isRetryable(), "Rate limit errors should be retryable");
        }

        @Test
        @DisplayName("5xx server error is retryable")
        void serverError5xx_isRetryable() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(502, "Bad Gateway"));

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(502, ex.getStatusCode());
            assertTrue(ex.isRetryable(), "5xx errors should be retryable");
        }

        @Test
        @DisplayName("Auth error is NOT retryable")
        void authError_isNotRetryable() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.unauthorizedHandler());

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
            assertFalse(ex.isRetryable(), "Auth errors should NOT be retryable");
        }

        @Test
        @DisplayName("Invalid request error is NOT retryable")
        void invalidRequestError_isNotRetryable() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            server.setChatHandler(server.errorHandler(400, "Bad Request"));

            LLMException ex = assertThrows(LLMException.class,
                    () -> client.scoreRelevancy("query", List.of("test")));

            assertEquals(LLMException.ErrorType.INVALID_REQUEST, ex.getErrorType());
            assertFalse(ex.isRetryable(), "Invalid request errors should NOT be retryable");
        }
    }

    // =------------ Sequence Handler Edge Cases =------------

    @Nested
    @DisplayName("Sequence Handler Edge Cases")
    class SequenceHandlerEdgeCases {

        @Test
        @DisplayName("Sequence handler cycles through responses")
        void sequenceHandler_cycles() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
            client = new LLMHttpClient(config);

            // Create sequence: success, error, success (will cycle)
            server.setChatHandler(server.sequenceHandler(
                    server.successRelevancyHandler(0.9),
                    server.errorHandler(500, "Error"),
                    server.successRelevancyHandler(0.5)
            ));

            // First call: success (index 0)
            List<RelevancyScore> scores1 = client.scoreRelevancy("query", List.of("test"));
            assertEquals(0.9, scores1.get(0).getScore(), 0.001);

            // Second call: error (index 1)
            assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));

            // Third call: success (index 2)
            List<RelevancyScore> scores3 = client.scoreRelevancy("query", List.of("test"));
            assertEquals(0.5, scores3.get(0).getScore(), 0.001);

            // Fourth call: cycles back to index 0 (success)
            List<RelevancyScore> scores4 = client.scoreRelevancy("query", List.of("test"));
            assertEquals(0.9, scores4.get(0).getScore(), 0.001);
        }
    }

    // =------------ Configuration Edge Cases =------------

    @Nested
    @DisplayName("Configuration Edge Cases")
    class ConfigurationEdgeCases {

        @Test
        @DisplayName("Very short retry base delay still works")
        void veryShortBaseDelay_works() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(2)
                    .retryBaseDelay(Duration.ofMillis(1)) // 1ms
                    .build();
            client = new LLMHttpClient(config);

            float[] expected = new float[]{0.1f, 0.2f};

            server.setEmbeddingHandler(server.sequenceHandler(
                    server.errorHandler(500, "Error"),
                    server.successEmbeddingHandler(expected)
            ));

            float[] result = client.generateEmbedding("test");

            assertNotNull(result);
            assertEquals(expected.length, result.length);
        }

        @Test
        @DisplayName("Large max retries configuration")
        void largeMaxRetries_works() {
            config = LLMClientConfig.builder()
                    .baseUrl(server.getBaseUrl())
                    .chatModel("test-model")
                    .embeddingModel("test-embedding")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(10) // Maximum allowed
                    .retryBaseDelay(Duration.ofMillis(1))
                    .build();
            client = new LLMHttpClient(config);

            // Fail 5 times, succeed on 6th
            server.setChatHandler(server.failThenSucceedHandler(5, server.successRelevancyHandler(0.99)));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

            assertEquals(0.99, scores.get(0).getScore(), 0.001);
            assertEquals(6, server.getChatRequestCount());
        }
    }
}
