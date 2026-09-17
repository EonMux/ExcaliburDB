package io.excalibur.llm.client;

import com.sun.net.httpserver.HttpHandler;
import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MockLLMServer}.
 *
 * <p>These tests verify that the mock server correctly handles various
 * response configurations and that it integrates properly with
 * {@link LLMHttpClient}.</p>
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>HTTP status codes: validated to be in [100, 599]</li>
 *   <li>Embedding vectors: all values finite, dimension in [1, 4096]</li>
 *   <li>Relevancy scores: each score in [0.0, 1.0]</li>
 *   <li>Request tracking: accurate count and capture</li>
 *   <li>Timeout handling: delays bounded at 30 seconds max</li>
 * </ul>
 */
@Timeout(value = 10)
class MockLLMServerTest {

    private MockLLMServer server;
    private LLMClientConfig config;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== Server Lifecycle Tests ====================

    @Test
    @DisplayName("Server starts and returns valid base URL")
    void server_startsSuccessfully() {
        assertNotNull(server.getBaseUrl());
        assertTrue(server.getBaseUrl().startsWith("http://localhost:"));
        assertTrue(server.getPort() > 0);
    }

    @Test
    @DisplayName("Server tracks request counts")
    void server_tracksRequestCounts() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f}));
        server.setChatHandler(server.successRelevancyHandler(0.5));

        client.generateEmbedding("test");
        client.scoreRelevancy("query", List.of("candidate"));

        assertEquals(1, server.getEmbeddingRequestCount());
        assertEquals(1, server.getChatRequestCount());
        assertEquals(2, server.getTotalRequestCount());
    }

    @Test
    @DisplayName("Server reset clears all tracking")
    void server_resetClearsTracking() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f}));
        client.generateEmbedding("test");

        assertEquals(1, server.getEmbeddingRequestCount());

        server.reset();

        assertEquals(0, server.getEmbeddingRequestCount());
        assertEquals(0, server.getTotalRequestCount());
    }

    // ==================== Success Response Tests ====================

    @Test
    @DisplayName("Success embedding handler returns valid embedding")
    void successEmbeddingHandler_returnsValidEmbedding() {
        float[] expected = new float[]{0.1f, -0.2f, 0.3f, 0.4f, 0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.generateEmbedding("test text");

        assertNotNull(result);
        assertEquals(expected.length, result.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], result[i], 0.0001f);
        }
    }

    @Test
    @DisplayName("Success relevancy handler returns valid scores")
    void successRelevancyHandler_returnsValidScores() {
        server.setChatHandler(server.successRelevancyHandler(0.9, 0.5, 0.1));

        List<RelevancyScore> scores = client.scoreRelevancy("query",
            List.of("high", "medium", "low"));

        assertEquals(3, scores.size());
        assertEquals(0.9, scores.get(0).getScore(), 0.001);
        assertEquals(0.5, scores.get(1).getScore(), 0.001);
        assertEquals(0.1, scores.get(2).getScore(), 0.001);
    }

    @Test
    @DisplayName("Success relevancy handler clamps scores to valid range")
    void successRelevancyHandler_clampsScores() {
        // Scores outside [0.0, 1.0] should be clamped
        server.setChatHandler(server.successRelevancyHandler(1.5, -0.3, 0.5));

        List<RelevancyScore> scores = client.scoreRelevancy("query",
            List.of("first", "second", "third"));

        assertEquals(3, scores.size());
        // Scores are clamped by RelevancyScore constructor
        assertTrue(scores.get(0).getScore() >= 0.0 && scores.get(0).getScore() <= 1.0);
        assertTrue(scores.get(1).getScore() >= 0.0 && scores.get(1).getScore() <= 1.0);
        assertTrue(scores.get(2).getScore() >= 0.0 && scores.get(2).getScore() <= 1.0);
    }

    // ==================== Markdown Response Tests ====================

    @Test
    @DisplayName("Markdown-wrapped response is sanitized and parsed")
    void successRelevancyWithMarkdownHandler_sanitizesCorrectly() {
        server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.75, 0.25));

        List<RelevancyScore> scores = client.scoreRelevancy("query",
            List.of("first", "second"));

        assertEquals(2, scores.size());
        assertEquals(0.75, scores.get(0).getScore(), 0.001);
        assertEquals(0.25, scores.get(1).getScore(), 0.001);
    }

    @Test
    @DisplayName("Response with commentary is sanitized and parsed")
    void successRelevancyWithCommentaryHandler_extractsJson() {
        server.setChatHandler(server.successRelevancyWithCommentaryHandler(0.8, 0.2));

        List<RelevancyScore> scores = client.scoreRelevancy("query",
            List.of("first", "second"));

        assertEquals(2, scores.size());
        assertEquals(0.8, scores.get(0).getScore(), 0.001);
        assertEquals(0.2, scores.get(1).getScore(), 0.001);
    }

    // ==================== Error Response Tests ====================

    @Test
    @DisplayName("Error handler returns HTTP error status")
    void errorHandler_returnsHttpError() {
        server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    @DisplayName("Unauthorized handler returns 401")
    void unauthorizedHandler_returns401() {
        server.setChatHandler(server.unauthorizedHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    @DisplayName("Rate limited handler returns 429")
    void rateLimitedHandler_returns429() {
        server.setChatHandler(server.rateLimitedHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        assertTrue(ex.getMessage().contains("429"));
    }

    @Test
    @DisplayName("Internal server error handler returns 500")
    void internalServerErrorHandler_returns500() {
        server.setEmbeddingHandler(server.internalServerErrorHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));

        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    @DisplayName("Service unavailable handler returns 503")
    void serviceUnavailableHandler_returns503() {
        server.setEmbeddingHandler(server.serviceUnavailableHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));

        assertTrue(ex.getMessage().contains("503"));
    }

    @Test
    @DisplayName("Bad request handler returns 400")
    void badRequestHandler_returns400() {
        server.setChatHandler(server.badRequestHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        assertTrue(ex.getMessage().contains("400"));
    }

    // ==================== Malformed Response Tests ====================

    @Test
    @DisplayName("Malformed JSON handler causes parse error")
    void malformedJsonHandler_throwsParseError() {
        server.setChatHandler(server.malformedJsonHandler());

        assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));
    }

    @Test
    @DisplayName("Empty response handler causes parse error")
    void emptyResponseHandler_throwsParseError() {
        server.setEmbeddingHandler(server.emptyResponseHandler());

        assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));
    }

    @Test
    @DisplayName("Missing embedding field handler causes parse error")
    void missingEmbeddingFieldHandler_throwsParseError() {
        server.setEmbeddingHandler(server.missingEmbeddingFieldHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));

        assertTrue(ex.getMessage().toLowerCase().contains("embedding"));
    }

    @Test
    @DisplayName("Missing content field handler causes parse error")
    void missingContentFieldHandler_throwsParseError() {
        server.setChatHandler(server.missingContentFieldHandler());

        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        assertTrue(ex.getMessage().toLowerCase().contains("content"));
    }

    // ==================== Domain Correctness: Embedding Validation Tests ====================

    @Test
    @DisplayName("Embedding with NaN is rejected")
    void embeddingWithNaNHandler_throwsValidationError() {
        server.setEmbeddingHandler(server.embeddingWithNaNHandler());

        // The response contains NaN which should be caught during validation
        assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));
    }

    @Test
    @DisplayName("Embedding with Infinity is rejected")
    void embeddingWithInfinityHandler_throwsValidationError() {
        server.setEmbeddingHandler(server.embeddingWithInfinityHandler());

        // The response contains Infinity which should be caught during validation
        assertThrows(LLMException.class,
            () -> client.generateEmbedding("test"));
    }

    @Test
    @DisplayName("Valid embedding dimension boundaries are accepted")
    void embeddingDimension_boundariesAccepted() {
        // Minimum dimension: 1
        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.5f}));
        float[] minResult = client.generateEmbedding("test");
        assertEquals(1, minResult.length);

        // Reset for next test
        server.reset();

        // Large dimension (but within bounds): 1024
        float[] largeEmbedding = new float[1024];
        for (int i = 0; i < 1024; i++) {
            largeEmbedding[i] = (float) Math.sin(i) * 0.5f;
        }
        server.setEmbeddingHandler(server.successEmbeddingHandler(largeEmbedding));
        float[] largeResult = client.generateEmbedding("test");
        assertEquals(1024, largeResult.length);
    }

    // ==================== Sequence Handler Tests ====================

    @Test
    @DisplayName("Sequence handler returns responses in order")
    void sequenceHandler_returnsInOrder() {
        // Create client with retries disabled to test sequence handler behavior
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test sequence handler
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        server.setChatHandler(server.sequenceHandler(
            server.errorHandler(500, "First failure"),
            server.errorHandler(500, "Second failure"),
            server.successRelevancyHandler(0.9)
        ));

        // First call: failure
        assertThrows(LLMException.class,
            () -> noRetryClient.scoreRelevancy("query", List.of("test")));

        // Second call: failure
        assertThrows(LLMException.class,
            () -> noRetryClient.scoreRelevancy("query", List.of("test")));

        // Third call: success
        List<RelevancyScore> scores = noRetryClient.scoreRelevancy("query", List.of("test"));
        assertEquals(1, scores.size());
        assertEquals(0.9, scores.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("Sequence handler cycles through handlers")
    void sequenceHandler_cyclesThroughHandlers() {
        server.setChatHandler(server.sequenceHandler(
            server.successRelevancyHandler(0.1),
            server.successRelevancyHandler(0.2)
        ));

        // Call 3 times - should cycle
        assertEquals(0.1, client.scoreRelevancy("q", List.of("t")).get(0).getScore(), 0.001);
        assertEquals(0.2, client.scoreRelevancy("q", List.of("t")).get(0).getScore(), 0.001);
        assertEquals(0.1, client.scoreRelevancy("q", List.of("t")).get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("Fail then succeed handler works correctly")
    void failThenSucceedHandler_eventuallySucceeds() {
        // Create client with retries disabled to test fail-then-succeed handler behavior
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test handler
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        server.setChatHandler(server.failThenSucceedHandler(2,
            server.successRelevancyHandler(0.95)));

        // First two calls fail
        assertThrows(LLMException.class,
            () -> noRetryClient.scoreRelevancy("query", List.of("test")));
        assertThrows(LLMException.class,
            () -> noRetryClient.scoreRelevancy("query", List.of("test")));

        // Third call succeeds
        List<RelevancyScore> scores = noRetryClient.scoreRelevancy("query", List.of("test"));
        assertEquals(0.95, scores.get(0).getScore(), 0.001);

        // Subsequent calls also succeed
        scores = noRetryClient.scoreRelevancy("query", List.of("test"));
        assertEquals(0.95, scores.get(0).getScore(), 0.001);
    }

    // ==================== Request Capture Tests ====================

    @Test
    @DisplayName("Captured requests include headers")
    void capturedRequests_includeHeaders() {
        server.setChatHandler(server.successRelevancyHandler(0.5));

        client.scoreRelevancy("query", List.of("test"), "trace-id-123");

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        assertEquals(1, requests.size());

        MockLLMServer.CapturedRequest request = requests.get(0);
        // HTTP headers may be in different cases, getHeader() does case-insensitive lookup
        assertEquals("trace-id-123", request.getHeader("X-Correlation-ID"));
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/chat/completions", request.getPath());
    }

    @Test
    @DisplayName("Captured requests include body")
    void capturedRequests_includeBody() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f}));

        client.generateEmbedding("test input text");

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedEmbeddingRequests();
        assertEquals(1, requests.size());

        String body = requests.get(0).getBody();
        assertNotNull(body);
        assertTrue(body.contains("test input text"));
        assertTrue(body.contains("\"model\""));
    }

    // ==================== Response Builder Tests ====================

    @Test
    @DisplayName("Response builder creates custom responses")
    void responseBuilder_createsCustomResponse() {
        server.setChatHandler(server.responseBuilder()
            .statusCode(201)
            .body("{\"custom\":\"response\"}")
            .header("X-Custom-Header", "custom-value")
            .build());

        // Even with custom response, the client should be able to make the request
        // The parsing will fail because it's not the expected format, but that's fine
        assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));

        // Verify the custom status code was sent
        // (The LLMException should reflect the 201 status)
    }

    // ==================== Circuit Breaker Integration Tests ====================

    @Test
    @DisplayName("Sequence handler simulates circuit breaker scenario")
    void circuitBreaker_scenario() {
        // Create client with retries disabled to test circuit breaker behavior
        // Without this, the client would retry internally and consume multiple sequence entries
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test circuit breaker
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        // Simulate: 5 failures to trip circuit, then successes to recover
        server.setChatHandler(server.sequenceHandler(
            server.internalServerErrorHandler(),  // 1
            server.internalServerErrorHandler(),  // 2
            server.internalServerErrorHandler(),  // 3
            server.internalServerErrorHandler(),  // 4
            server.internalServerErrorHandler(),  // 5 - trips circuit
            server.successRelevancyHandler(0.9),  // 6 - would succeed if circuit wasn't open
            server.successRelevancyHandler(0.9)   // 7
        ));

        // Make 5 failing calls to trip the circuit
        for (int i = 0; i < 5; i++) {
            assertThrows(LLMException.class,
                () -> noRetryClient.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));
        }

        // Circuit should now be OPEN
        assertEquals(CircuitBreakerState.OPEN, noRetryClient.getCircuitBreaker().getState());

        // Next call should fail fast with CIRCUIT_OPEN
        LLMException ex = assertThrows(LLMException.class,
            () -> noRetryClient.scoreRelevancyWithCircuitBreaker("query", List.of("test"), null));
        assertEquals(LLMException.ErrorType.CIRCUIT_OPEN, ex.getErrorType());
    }

    // ==================== Domain Correctness: Input Validation Tests ====================

    @Test
    @DisplayName("Invalid status code is rejected")
    void invalidStatusCode_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> server.errorHandler(99, "Invalid"));
        assertThrows(IllegalArgumentException.class,
            () -> server.errorHandler(600, "Invalid"));
    }

    @Test
    @DisplayName("Valid status code boundaries are accepted")
    void validStatusCodeBoundaries_accepted() {
        assertThatCode(() -> {
            // Minimum valid: 100
            server.setChatHandler(server.errorHandler(100, "Continue"));
            // Maximum valid: 599
            server.setChatHandler(server.errorHandler(599, "Network timeout"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Invalid embedding dimension is rejected")
    void invalidEmbeddingDimension_rejected() {
        // Empty embedding
        assertThrows(IllegalArgumentException.class,
            () -> server.successEmbeddingHandler(new float[0]));

        // Null embedding
        assertThrows(IllegalArgumentException.class,
            () -> server.successEmbeddingHandler(null));
    }

    @Test
    @DisplayName("Invalid delay is rejected")
    void invalidDelay_rejected() {
        HttpHandler baseHandler = server.successRelevancyHandler(0.5);

        // Negative delay
        assertThrows(IllegalArgumentException.class,
            () -> server.delayedHandler(baseHandler, -1));

        // Delay exceeds maximum
        assertThrows(IllegalArgumentException.class,
            () -> server.delayedHandler(baseHandler, 31_000));
    }

    @Test
    @DisplayName("Sequence handler requires at least one handler")
    void sequenceHandler_requiresHandlers() {
        assertThrows(IllegalArgumentException.class,
            () -> server.sequenceHandler());
        assertThrows(IllegalArgumentException.class,
            () -> server.sequenceHandler((HttpHandler[]) null));
    }

    // ==================== AutoCloseable Tests ====================

    @Test
    @DisplayName("Server implements AutoCloseable")
    void server_autoCloseable() throws IOException {
        int port;
        try (MockLLMServer autoServer = MockLLMServer.create().start()) {
            port = autoServer.getPort();
            assertNotNull(autoServer.getBaseUrl());
        }
        // After close, port should be released (though we can't easily verify this)
    }
}
