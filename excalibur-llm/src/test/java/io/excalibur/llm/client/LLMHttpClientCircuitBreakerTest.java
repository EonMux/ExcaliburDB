package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Circuit breaker behaviour of {@link LLMHttpClient}.
 *
 * <p>Covers what {@code scoreRelevancyWithCircuitBreaker} counts as a failure and what
 * it does not. The distinction that matters is the sanitized reply: a response that
 * only parsed because the sanitizer stripped a markdown fence is a success, and ten of
 * them in a row must leave the breaker closed with a zero failure count — otherwise a
 * chatty but perfectly usable model would take the reranker offline. Genuine HTTP
 * errors do count, five of them open the circuit, an open circuit fails fast with
 * {@code CIRCUIT_OPEN} instead of issuing a request, and one success resets the run.</p>
 */
@Timeout(value = 10)
class LLMHttpClientCircuitBreakerTest extends LLMHttpClientTestBase {

    @Test
    @DisplayName("Circuit breaker: client has circuit breaker initialized")
    void client_hasCircuitBreaker() {
        assertNotNull(client.getCircuitBreaker());
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
    }

    @Test
    @DisplayName("Circuit breaker: sanitized-but-valid response counts as SUCCESS not failure")
    void scoreRelevancyWithCircuitBreaker_sanitizedValidResponse_countsAsSuccess() {
        // Response with markdown fence - sanitizer will extract valid JSON
        server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.75));

        List<String> candidates = List.of("Test passage");
        List<RelevancyScore> scores = client.scoreRelevancyWithCircuitBreaker("query", candidates, "corr-123");

        // Verify parsing succeeded
        assertEquals(1, scores.size());
        assertEquals(0.75, scores.get(0).getScore(), 0.001);

        // Verify circuit breaker state is still CLOSED (success, not failure)
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
        assertEquals(0, client.getCircuitBreaker().getFailureCount());
    }

    @Test
    @DisplayName("Circuit breaker: HTTP error counts as failure")
    void scoreRelevancyWithCircuitBreaker_httpError_countsAsFailure() {
        server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

        assertThrows(LLMException.class,
                () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));

        // Verify failure was recorded
        assertEquals(1, client.getCircuitBreaker().getFailureCount());
    }

    @Test
    @DisplayName("Circuit breaker: multiple HTTP errors eventually open circuit")
    void scoreRelevancyWithCircuitBreaker_multipleErrors_opensCircuit() {
        // Configure client with no retries to avoid long delays
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0) // No retries to avoid timeout
                .build();
        client = new LLMHttpClient(config);

        server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

        // Record failures up to threshold (default is 5)
        for (int i = 0; i < 5; i++) {
            assertThrows(LLMException.class,
                    () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));
        }

        // Circuit should now be OPEN
        assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState());
    }

    @Test
    @DisplayName("Circuit breaker: open circuit fails fast")
    void scoreRelevancyWithCircuitBreaker_openCircuit_failsFast() {
        // Manually trip the circuit
        client.getCircuitBreaker().trip();

        // Request should fail immediately with CIRCUIT_OPEN error
        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));

        assertEquals(LLMException.ErrorType.CIRCUIT_OPEN, ex.getErrorType());
    }

    @Test
    @DisplayName("Circuit breaker: success after failures resets failure count")
    void scoreRelevancyWithCircuitBreaker_successAfterFailures_resetsCount() {
        // Configure client with no retries to avoid long delays
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0) // No retries to avoid timeout
                .build();
        client = new LLMHttpClient(config);

        // Record some failures (but not enough to trip)
        server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

        for (int i = 0; i < 3; i++) {
            assertThrows(LLMException.class,
                    () -> client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));
        }
        assertEquals(3, client.getCircuitBreaker().getFailureCount());

        // Now succeed
        server.setChatHandler(server.successRelevancyHandler(0.9));
        client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null);

        // Failure count should be reset
        assertEquals(0, client.getCircuitBreaker().getFailureCount());
    }

    @Test
    @DisplayName("Circuit breaker: multiple sanitized responses never trip circuit")
    void scoreRelevancyWithCircuitBreaker_multipleSanitizedResponses_neverTrips() {
        // Setup handler that always returns markdown-fenced JSON
        server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.5));

        // Make 10 successful calls with sanitized responses
        for (int i = 0; i < 10; i++) {
            List<RelevancyScore> scores = client.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null);
            assertEquals(1, scores.size());
        }

        // Circuit should still be CLOSED with 0 failures
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
        assertEquals(0, client.getCircuitBreaker().getFailureCount());
    }

    @Test
    @DisplayName("Circuit breaker: constructor with custom circuit breaker")
    void client_customCircuitBreaker_used() {
        CircuitBreaker customBreaker = new CircuitBreaker(3, Duration.ofSeconds(30), "custom");
        LLMHttpClient customClient = new LLMHttpClient(config, customBreaker);

        assertEquals(customBreaker, customClient.getCircuitBreaker());
        assertEquals(3, customClient.getCircuitBreaker().getFailureThreshold());
    }
}
