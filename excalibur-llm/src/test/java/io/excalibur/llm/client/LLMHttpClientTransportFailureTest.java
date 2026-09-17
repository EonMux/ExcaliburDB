package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport failures and the exceptions {@link LLMHttpClient} documents for them.
 *
 * <p>Covers the ways a request can fail short of a parseable reply, and insists each
 * one arrives as an {@link LLMException} carrying the right {@code ErrorType}, status
 * code and {@code isRetryable} flag rather than leaking a raw {@code IOException}: a
 * handler that throws and drops the connection, a retry budget spent against a server
 * that never recovers, a server slower than the configured timeout, a server that has
 * stopped, and a URL with nothing listening on it. 5xx and 429 are marked retryable;
 * 400 and 401 are not.</p>
 */
@Timeout(value = 10)
class LLMHttpClientTransportFailureTest extends LLMHttpClientTestBase {

    // ============= Exception Documentation Tests ==================
    // Tests that verify HTTP requests return valid responses or throw documented exceptions

    @Test
    @DisplayName("Exception: server handler exception results in network error")
    void serverHandlerException_resultsInNetworkError() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0) // No retries for faster test
                .build();
        client = new LLMHttpClient(config);

        // Create a handler that throws a runtime exception
        // The mock server closes the connection when handler throws, resulting in network error
        server.setEmbeddingHandler(exchange -> {
            throw new RuntimeException("Simulated server error");
        });

        // Server-side exceptions close the connection - client sees network error
        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));

        // Verify it's a network error (connection closed when handler throws)
        assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType());
    }

    @Test
    @DisplayName("Exception: all retry attempts exhausted throws LLMException with proper type")
    void allRetriesExhausted_throwsLLMException() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(2)
                .retryBaseDelay(Duration.ofMillis(10))
                .build();
        client = new LLMHttpClient(config);

        // Handler that always fails with retryable error
        server.setEmbeddingHandler(server.errorHandler(503, "Service Unavailable"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));

        // Verify documented exception type
        assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
        assertEquals(503, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("503"));
    }

    @Test
    @DisplayName("Exception: LLMException contains retryable flag for transient errors")
    void llmException_retryableFlag_correctForTransientErrors() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new LLMHttpClient(config);

        // Test 5xx server error (retryable)
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));
        LLMException serverError = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertTrue(serverError.isRetryable(), "5xx errors should be retryable");

        // Test 429 rate limit (retryable)
        server.setEmbeddingHandler(server.errorHandler(429, "Too Many Requests"));
        LLMException rateLimit = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertTrue(rateLimit.isRetryable(), "429 rate limit should be retryable");
    }

    @Test
    @DisplayName("Exception: LLMException contains retryable flag for non-retryable errors")
    void llmException_retryableFlag_correctForNonRetryableErrors() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new LLMHttpClient(config);

        // Test 401 unauthorized (not retryable)
        server.setEmbeddingHandler(server.unauthorizedHandler());
        LLMException authError = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertFalse(authError.isRetryable(), "401 auth error should not be retryable");

        // Test 400 bad request (not retryable)
        server.setEmbeddingHandler(server.errorHandler(400, "Bad Request"));
        LLMException badRequest = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));
        assertFalse(badRequest.isRetryable(), "400 bad request should not be retryable");
    }

    // ============= Timeout Handling Tests =============

    @Test
    @DisplayName("Timeout: client timeout with slow server throws NETWORK error")
    void generateEmbedding_slowServer_throwsNetworkError() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofMillis(50)) // Short timeout
                .maxRetries(0)
                .build();
        client = new LLMHttpClient(config);

        // Handler that delays longer than timeout (200ms > 50ms timeout)
        server.setEmbeddingHandler(server.delayedHandler(
                server.successEmbeddingHandler(new float[]{0.1f, 0.2f}), 200));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));

        assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType(),
                "Timeout should result in NETWORK error type");
        assertTrue(ex.getMessage().contains("timed out"),
                "Exception message should mention timeout");
    }

    @Test
    @DisplayName("Timeout: relevancy scoring with slow server throws NETWORK error")
    void scoreRelevancy_slowServer_throwsNetworkError() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofMillis(50))
                .maxRetries(0)
                .build();
        client = new LLMHttpClient(config);

        // Handler that delays longer than timeout (200ms > 50ms timeout)
        server.setChatHandler(server.delayedHandler(
                server.successRelevancyHandler(0.5), 200));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));

        assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType(),
                "Timeout should result in NETWORK error type");
    }

    // ============= Connection Failure Tests =============

    @Test
    @DisplayName("Connection: server stopped throws NETWORK error")
    void generateEmbedding_serverStopped_throwsNetworkError() throws IOException {
        // Create a client pointing to a server that will be stopped
        MockLLMServer tempServer = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(tempServer.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        LLMHttpClient tempClient = new LLMHttpClient(config);

        // Stop the server before making request
        tempServer.stop();

        // Request should fail with NETWORK error
        LLMException ex = assertThrows(LLMException.class,
                () -> tempClient.generateEmbedding("Test"));

        assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType(),
                "Connection refused should result in NETWORK error type");
    }

    @Test
    @DisplayName("Connection: invalid URL throws NETWORK error")
    void generateEmbedding_invalidUrl_throwsNetworkError() {
        config = LLMClientConfig.builder()
                .baseUrl("http://localhost:1") // Invalid port - no server there
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofMillis(500))
                .maxRetries(0)
                .build();
        LLMHttpClient tempClient = new LLMHttpClient(config);

        LLMException ex = assertThrows(LLMException.class,
                () -> tempClient.generateEmbedding("Test"));

        assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType(),
                "Connection to invalid URL should result in NETWORK error type");
    }
}
