package com.eonmux.excalibur.llm.client;

import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retry with exponential backoff in {@link LLMHttpClient}.
 *
 * <p>Covers which failures are worth trying again and how many times. A transient
 * network error, a 500 or a 429 is retried and the call ultimately succeeds; a 401 or
 * 403 is not retried at all, and the test asserts the server saw exactly one request
 * rather than merely that the call failed. Also covers the retry budget itself —
 * {@code maxRetries} defaults to 3, is validated into [0, 10], and {@code 0} means the
 * single attempt — that retries apply to embedding generation as well as scoring, that
 * {@code retryBaseDelay} is honoured, and that a call which only succeeded on a retry
 * still records a success with the circuit breaker.</p>
 *
 * <p>The first two tests pin the {@link MockLLMServer} handlers the rest of the suite
 * depends on: {@code sequenceHandler} and {@code failThenSucceedHandler} are exercised
 * against a client with retries disabled, so a later retry test that passes cannot be
 * passing because the fixture never failed in the first place.</p>
 */
@Timeout(value = 10)
class LLMHttpClientRetryTest extends LLMHttpClientTestBase {

    // ============= MockLLMServer Handler Tests =============

    @Test
    @DisplayName("MockLLMServer: sequence handler works correctly")
    void mockServer_sequenceHandler_worksCorrectly() throws IOException {
        // Create client with retries disabled to test server behavior (not client retry)
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test sequence handler
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        server.setChatHandler(server.sequenceHandler(
            server.errorHandler(500, "First"),
            server.successRelevancyHandler(0.9)
        ));

        // First call fails
        assertThrows(LLMException.class, () -> noRetryClient.scoreRelevancy("q", List.of("t")));

        // Second call succeeds
        List<RelevancyScore> scores = noRetryClient.scoreRelevancy("q", List.of("t"));
        assertEquals(0.9, scores.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("MockLLMServer: fail then succeed handler works correctly")
    void mockServer_failThenSucceedHandler_worksCorrectly() throws IOException {
        // Create client with retries disabled to test server behavior (not client retry)
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test fail-then-succeed handler
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        server.setChatHandler(server.failThenSucceedHandler(2, server.successRelevancyHandler(0.95)));

        // First two calls fail
        assertThrows(LLMException.class, () -> noRetryClient.scoreRelevancy("q", List.of("t")));
        assertThrows(LLMException.class, () -> noRetryClient.scoreRelevancy("q", List.of("t")));

        // Third call succeeds
        List<RelevancyScore> scores = noRetryClient.scoreRelevancy("q", List.of("t"));
        assertEquals(0.95, scores.get(0).getScore(), 0.001);
    }

    // ============= Retry with Exponential Backoff Tests =============

    @Test
    @DisplayName("Retry: transient network error triggers retry and eventually succeeds")
    void scoreRelevancy_networkError_retriesAndSucceeds() throws IOException {
        // Create config with 3 retries
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)  // Allow 3 retries
                .build();
        client = new LLMHttpClient(config);

        // Handler that fails once then succeeds
        server.setChatHandler(server.failThenSucceedHandler(1, server.successRelevancyHandler(0.88)));

        // This should succeed after retry (initial + 1 retry = 2 attempts)
        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

        assertEquals(1, scores.size());
        assertEquals(0.88, scores.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("Retry: HTTP 500 error triggers retry")
    void scoreRelevancy_http500_retriesAndSucceeds() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        // First call returns 500, second call succeeds
        server.setChatHandler(server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successRelevancyHandler(0.75)
        ));

        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

        assertEquals(1, scores.size());
        assertEquals(0.75, scores.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("Retry: HTTP 429 rate limited triggers retry")
    void scoreRelevancy_rateLimited_retriesAndSucceeds() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        // First call returns 429, second call succeeds
        server.setChatHandler(server.sequenceHandler(
                server.errorHandler(429, "Rate Limited"),
                server.successRelevancyHandler(0.82)
        ));

        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));

        assertEquals(1, scores.size());
        assertEquals(0.82, scores.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("Retry: HTTP 401 auth error does NOT trigger retry")
    void scoreRelevancy_http401_noRetry() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        // Set up sequence handler to track if retry happens
        // If retry happens, second call would succeed
        // If no retry, we should get 401 immediately
        AtomicInteger callCount = new AtomicInteger(0);
        server.setChatHandler(exchange -> {
            callCount.incrementAndGet();
            server.errorHandler(401, "Unauthorized").handle(exchange);
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));

        // Should have only one call (no retry)
        assertEquals(1, callCount.get(), "401 should not trigger retry");
        assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
    }

    @Test
    @DisplayName("Retry: HTTP 403 forbidden does NOT trigger retry")
    void scoreRelevancy_http403_noRetry() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        AtomicInteger callCount = new AtomicInteger(0);
        server.setChatHandler(exchange -> {
            callCount.incrementAndGet();
            server.errorHandler(403, "Forbidden").handle(exchange);
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));

        // Should have only one call (no retry)
        assertEquals(1, callCount.get(), "403 should not trigger retry");
        assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
    }

    @Test
    @DisplayName("Retry: exhausts retries and fails")
    void scoreRelevancy_exhaustsRetries_fails() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(1))  // Short timeout
                .maxRetries(2)  // Only 2 retries
                .build();
        client = new LLMHttpClient(config);

        // Always fail with 500
        server.setChatHandler(server.errorHandler(500, "Always fails"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));

        // Should have made 3 attempts (initial + 2 retries)
        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        assertEquals(3, requests.size(), "Should have made 3 attempts (initial + 2 retries)");
    }

    @Test
    @DisplayName("Retry: maxRetries=0 means no retry")
    void scoreRelevancy_zeroRetries_noRetry() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // No retries
                .build();
        client = new LLMHttpClient(config);

        AtomicInteger callCount = new AtomicInteger(0);
        server.setChatHandler(exchange -> {
            callCount.incrementAndGet();
            server.errorHandler(500, "Internal Server Error").handle(exchange);
        });

        assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));

        // Should have only one call (no retry)
        assertEquals(1, callCount.get(), "maxRetries=0 should not trigger any retry");
    }

    @Test
    @DisplayName("Retry: embedding generation also retries on network error")
    void generateEmbedding_networkError_retriesAndSucceeds() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        float[] expectedEmbedding = new float[]{0.5f, 0.3f, 0.2f};

        // First call fails, second succeeds
        server.setEmbeddingHandler(server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successEmbeddingHandler(expectedEmbedding)
        ));

        float[] result = client.generateEmbedding("Test text");

        assertNotNull(result);
        assertEquals(expectedEmbedding.length, result.length);
        for (int i = 0; i < expectedEmbedding.length; i++) {
            assertEquals(expectedEmbedding[i], result[i], 0.0001f);
        }
    }

    @Test
    @DisplayName("Retry: config default maxRetries is 3")
    void config_defaultMaxRetries_is3() {
        LLMClientConfig defaultConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .build();

        assertEquals(3, defaultConfig.getMaxRetries(),
                "Default maxRetries should be 3");
    }

    @Test
    @DisplayName("Retry: config validates maxRetries range [0, 10]")
    void config_maxRetries_validated() {
        // Valid: 0
        LLMClientConfig config0 = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .maxRetries(0)
                .build();
        assertEquals(0, config0.getMaxRetries());

        // Valid: 10
        LLMClientConfig config10 = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .maxRetries(10)
                .build();
        assertEquals(10, config10.getMaxRetries());

        // Invalid: -1
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .maxRetries(-1)
                        .build());

        // Invalid: 11
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .maxRetries(11)
                        .build());
    }

    @Test
    @DisplayName("Retry: success after retry does not affect circuit breaker")
    void retry_successAfterRetry_noCircuitBreakerImpact() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        // First call fails with 500, second succeeds
        server.setChatHandler(server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successRelevancyHandler(0.9)
        ));

        // Call with circuit breaker protection
        List<RelevancyScore> scores = client.scoreRelevancyWithCircuitBreaker(
                "query", List.of("test"), null);

        assertEquals(1, scores.size());
        assertEquals(0.9, scores.get(0).getScore(), 0.001);

        // Circuit breaker should still be CLOSED (success was recorded)
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
        assertEquals(0, client.getCircuitBreaker().getFailureCount());
    }

    // ============= Retry Logic Tests (Additional) =============

    /**
     * A budget of three retries is a ceiling, not a quota: the loop stops at the first
     * success.
     *
     * <p>This is what separates the test from
     * {@link #generateEmbedding_networkError_retriesAndSucceeds()}, which sets up the same
     * fail-then-succeed sequence and checks only that the embedding comes back. The server
     * would happily serve a third request — {@code sequenceHandler} cycles — so without
     * counting the requests, a client that kept retrying past a success would pass both.</p>
     */
    @Test
    @DisplayName("Retry: success on second attempt does not consume the remaining retries")
    void retry_successOnSecondAttempt() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .retryBaseDelay(Duration.ofMillis(10))
                .build();
        client = new LLMHttpClient(config);

        float[] expectedEmbedding = new float[]{0.5f, 0.3f, 0.2f};

        // First call fails, second succeeds
        server.setEmbeddingHandler(server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successEmbeddingHandler(expectedEmbedding)
        ));

        float[] result = client.generateEmbedding("Test");

        assertNotNull(result);
        assertEquals(expectedEmbedding.length, result.length);
        for (int i = 0; i < expectedEmbedding.length; i++) {
            assertEquals(expectedEmbedding[i], result[i], 0.0001f);
        }

        assertEquals(2, server.getCapturedEmbeddingRequests().size(),
                "The successful second attempt should end the retry loop, "
                        + "leaving the two remaining retries unused");
    }

    @Test
    @DisplayName("Retry: exhausts all attempts and throws final error")
    void retry_exhaustsAllAttempts_throwsFinalError() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(2) // 2 retries allowed
                .retryBaseDelay(Duration.ofMillis(10)) // Fast retry for testing
                .build();
        client = new LLMHttpClient(config);

        // All 3 attempts (1 initial + 2 retries) fail
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertTrue(ex.getErrorType() == LLMException.ErrorType.HTTP_ERROR);
        assertEquals(500, ex.getStatusCode());
        // Note: generateEmbedding does NOT use the circuit breaker - only scoreRelevancyWithCircuitBreaker does
        // The retry logic is internal to executeRequest and doesn't record to circuit breaker
    }

    @Test
    @DisplayName("Retry: non-retryable error AUTH_ERROR does not trigger retry")
    void retry_nonRetryableError_noRetry() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .build();
        client = new LLMHttpClient(config);

        // 401 Unauthorized should NOT trigger retry
        server.setEmbeddingHandler(server.unauthorizedHandler());

        // Track request count before
        int requestsBefore = server.getCapturedEmbeddingRequests().size();

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertTrue(ex.getErrorType() == LLMException.ErrorType.AUTH_ERROR);
        assertEquals(401, ex.getStatusCode());

        // Verify only one request was made (no retry)
        int requestsAfter = server.getCapturedEmbeddingRequests().size();
        assertEquals(requestsBefore + 1, requestsAfter);
    }

    /**
     * The client waits longer before each successive retry, and the growth is measured.
     *
     * <p>Measured from the server side: every attempt stamps its arrival, and the wait
     * between attempts is the gap between consecutive stamps. The gaps carry the round
     * trip as well as the sleep, which is why the assertions are on ordering and a loose
     * ratio rather than on durations.</p>
     *
     * <p>Those bounds are not arbitrary. The delay is
     * {@code min(base * 2^attempt, 30s)} scattered by ±25% and floored at {@code base}, so
     * the first wait lies in [1.00, 1.25]·base, the second in [1.50, 2.50]·base and the
     * third in [3.00, 5.00]·base. The ranges do not overlap, so each wait must exceed the
     * one before it however the jitter falls, and the third must be at least 2.4× the
     * first — asserted at 1.8× to leave room for the round trip riding along in both
     * measurements.</p>
     */
    @Test
    @DisplayName("Retry: exponential backoff delay increases with each attempt")
    void retry_exponentialBackoffDelay_increases() throws IOException {
        long baseDelayMs = 120;
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .retryBaseDelay(Duration.ofMillis(baseDelayMs))
                .build();
        client = new LLMHttpClient(config);

        List<Long> arrivalsNanos = new CopyOnWriteArrayList<>();
        server.setEmbeddingHandler(timestamping(arrivalsNanos,
                server.errorHandler(500, "Internal Server Error")));

        assertThrows(LLMException.class, () -> client.generateEmbedding("Test"));

        assertEquals(4, arrivalsNanos.size(),
                "maxRetries=3 should produce one initial attempt and three retries");

        long firstWait = gapMillis(arrivalsNanos, 0);
        long secondWait = gapMillis(arrivalsNanos, 1);
        long thirdWait = gapMillis(arrivalsNanos, 2);

        assertTrue(firstWait >= baseDelayMs,
                "First retry should wait at least the base delay, waited " + firstWait + "ms");
        assertTrue(secondWait > firstWait,
                "Second wait (" + secondWait + "ms) should exceed the first (" + firstWait + "ms)");
        assertTrue(thirdWait > secondWait,
                "Third wait (" + thirdWait + "ms) should exceed the second (" + secondWait + "ms)");
        assertTrue(thirdWait >= firstWait * 18 / 10,
                "Doubling twice should leave the third wait (" + thirdWait
                        + "ms) at least 1.8x the first (" + firstWait + "ms)");
    }

    /**
     * The delay the client actually waits is the one the configuration asked for.
     *
     * <p>Asserting that {@code getRetryBaseDelay()} returns what the builder was handed
     * tests the builder. This runs the same single-retry scenario twice, at 60ms and at
     * 250ms, and checks the measured wait against each: at least the configured base,
     * because the delay calculation floors it there, and under twice the base, because the
     * jitter tops out at 1.25×. A client that ignored the setting and used the 500ms
     * default — or any other fixed number — could not land inside both windows.</p>
     */
    @Test
    @DisplayName("Retry: configurable retryBaseDelay is used")
    void retry_configurableBaseDelay_used() throws IOException {
        long shortWait = measureSingleRetryWaitMillis(60);
        long longWait = measureSingleRetryWaitMillis(250);

        assertTrue(shortWait < longWait,
                "A larger configured base delay should produce a longer wait: "
                        + shortWait + "ms vs " + longWait + "ms");
    }

    /**
     * Runs one fail-then-succeed embedding call and returns how long the client waited
     * between the two attempts, asserting the wait matches the configured base delay.
     *
     * @param baseDelayMs The retry base delay to configure, in milliseconds
     * @return The measured wait between the two attempts, in milliseconds
     * @throws IOException if the call fails for a reason other than the planted 500
     */
    private long measureSingleRetryWaitMillis(long baseDelayMs) throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(1)
                .retryBaseDelay(Duration.ofMillis(baseDelayMs))
                .build();
        client = new LLMHttpClient(config);

        float[] expectedEmbedding = new float[]{0.5f};
        List<Long> arrivalsNanos = new CopyOnWriteArrayList<>();
        server.setEmbeddingHandler(timestamping(arrivalsNanos, server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successEmbeddingHandler(expectedEmbedding)
        )));

        float[] result = client.generateEmbedding("Test");
        assertNotNull(result);
        assertEquals(expectedEmbedding.length, result.length);

        assertEquals(2, arrivalsNanos.size(), "The call should have taken two attempts");
        long waited = gapMillis(arrivalsNanos, 0);

        assertTrue(waited >= baseDelayMs,
                "Waited " + waited + "ms before retrying, less than the configured base delay of "
                        + baseDelayMs + "ms");
        assertTrue(waited < baseDelayMs * 2,
                "Waited " + waited + "ms before retrying, far more than the configured base delay of "
                        + baseDelayMs + "ms (jitter tops out at 1.25x)");
        return waited;
    }

    /**
     * Wraps a handler so that every request it serves stamps its arrival time first.
     *
     * @param arrivalsNanos Where to record arrivals, in {@link System#nanoTime()} units
     * @param delegate The handler that produces the response
     * @return A handler that records then delegates
     */
    private static HttpHandler timestamping(List<Long> arrivalsNanos, HttpHandler delegate) {
        return exchange -> {
            arrivalsNanos.add(System.nanoTime());
            delegate.handle(exchange);
        };
    }

    /**
     * Returns the elapsed milliseconds between two consecutive recorded arrivals.
     *
     * @param arrivalsNanos The recorded arrival times
     * @param index The index of the earlier of the two arrivals
     * @return The gap in milliseconds
     */
    private static long gapMillis(List<Long> arrivalsNanos, int index) {
        return TimeUnit.NANOSECONDS.toMillis(
                arrivalsNanos.get(index + 1) - arrivalsNanos.get(index));
    }
}
