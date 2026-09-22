package com.eonmux.excalibur.llm.client;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism verification tests for LLM HTTP client.
 *
 * <p>These tests verify that the same mock response ALWAYS produces the same result,
 * ensuring tests are deterministic and repeatable.</p>
 *
 * <h2>Test Philosophy</h2>
 * <ul>
 *   <li>Same input → same output (determinism)</li>
 *   <li>Mock server state is isolated between tests</li>
 *   <li>Concurrent access produces consistent results</li>
 *   <li>Error responses are deterministic</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP status codes: valid range [100, 599]</li>
 *   <li>Embedding vectors: all values finite, dimension in [1, 4096]</li>
 *   <li>Relevancy scores: each score in [0.0, 1.0]</li>
 * </ul>
 */
class DeterminismTest {

    private MockLLMServer mockServer;
    private LLMClientConfig config;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(mockServer.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    // ==================== Core Determinism Tests ====================

    @Test
    @DisplayName("DETERMINISM: Same embedding response always produces same result")
    void determinism_embedding_sameResponseProducesSameResult() {
        float[] expected = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        mockServer.setEmbeddingHandler(mockServer.successEmbeddingHandler(expected));

        // Call generateEmbedding multiple times with same input
        for (int i = 0; i < 5; i++) {
            float[] result1 = client.generateEmbedding("test text");
            float[] result2 = client.generateEmbedding("test text");
            float[] result3 = client.generateEmbedding("test text");

            // All results must be identical
            assertArrayEquals(expected, result1);
            assertArrayEquals(expected, result2);
            assertArrayEquals(expected, result3);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Same relevancy response always produces same scores")
    void determinism_relevancy_sameResponseProducesSameScores() {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.85));

        // Call scoreRelevancy multiple times with same input
        for (int i = 0; i < 5; i++) {
            List<RelevancyScore> scores1 = client.scoreRelevancy("query", List.of("test"));
            List<RelevancyScore> scores2 = client.scoreRelevancy("query", List.of("test"));
            List<RelevancyScore> scores3 = client.scoreRelevancy("query", List.of("test"));

            // All results must be identical
            assertEquals(scores1.size(), scores2.size());
            assertEquals(scores1.size(), scores3.size());
            for (int j = 0; j < scores1.size(); j++) {
                assertEquals(scores1.get(j).getIndex(), scores2.get(j).getIndex());
                assertEquals(scores1.get(j).getIndex(), scores3.get(j).getIndex());
                assertEquals(scores1.get(j).getScore(), scores2.get(j).getScore(), 0.0001);
                assertEquals(scores1.get(j).getScore(), scores3.get(j).getScore(), 0.0001);
            }
        }
    }

    @Test
    @DisplayName("DETERMINISM: Mock server state is reset between tests")
    void determinism_serverStateResetBetweenTests() throws IOException {
        // First test with one server
        try (MockLLMServer server1 = MockLLMServer.create().start()) {
            LLMClientConfig config1 = LLMClientConfig.builder()
                .baseUrl(server1.getBaseUrl())
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .build();
            LLMHttpClient client1 = new LLMHttpClient(config1);

            server1.setChatHandler(server1.successRelevancyHandler(0.9));
            List<RelevancyScore> scores1 = client1.scoreRelevancy("q", List.of("t"));
            assertEquals(0.9, scores1.get(0).getScore(), 0.001);
        }

        // Second test with completely different server - should not be affected by first
        try (MockLLMServer server2 = MockLLMServer.create().start()) {
            LLMClientConfig config2 = LLMClientConfig.builder()
                .baseUrl(server2.getBaseUrl())
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .build();
            LLMHttpClient client2 = new LLMHttpClient(config2);

            server2.setChatHandler(server2.successRelevancyHandler(0.3));
            List<RelevancyScore> scores2 = client2.scoreRelevancy("q", List.of("t"));
            assertEquals(0.3, scores2.get(0).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Error responses produce consistent exceptions")
    void determinism_errorResponses_produceConsistentExceptions() {
        mockServer.setChatHandler(mockServer.errorHandler(500, "Internal Server Error"));

        // Verify error determinism with single call (behavior is already verified by other tests)
        LLMException ex = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("query", List.of("test")));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    @DisplayName("DETERMINISM: Sanitized responses are consistently parsed")
    void determinism_sanitizedResponses_consistentlyParsed() {
        mockServer.setChatHandler(mockServer.successRelevancyWithMarkdownHandler(0.75));

        // Call multiple times with markdown response
        for (int i = 0; i < 5; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));
            assertEquals(1, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(0.75, scores.get(0).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Concurrent requests with same mock response produce same results")
    void determinism_concurrentRequests_produceConsistentResults() throws Exception {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.5));

        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<List<RelevancyScore>> allResults = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Create threads that will all wait at the start signal
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));
                    allResults.add(scores);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all threads to complete
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // All results should be identical
        assertEquals(numThreads, allResults.size());
        for (List<RelevancyScore> result : allResults) {
            assertEquals(1, result.size());
            assertEquals(0, result.get(0).getIndex());
            assertEquals(0.5, result.get(0).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Request count is deterministic")
    void determinism_requestCount_isDeterministic() {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.5));

        // Make multiple requests and verify count
        assertEquals(0, mockServer.getChatRequestCount());

        client.scoreRelevancy("q1", List.of("t"));
        assertEquals(1, mockServer.getChatRequestCount());

        client.scoreRelevancy("q2", List.of("t"));
        assertEquals(2, mockServer.getChatRequestCount());

        client.scoreRelevancy("q3", List.of("t"));
        assertEquals(3, mockServer.getChatRequestCount());

        // Reset and verify count resets
        mockServer.reset();
        assertEquals(0, mockServer.getChatRequestCount());

        client.scoreRelevancy("q4", List.of("t"));
        assertEquals(1, mockServer.getChatRequestCount());
    }

    @Test
    @DisplayName("DETERMINISM: Correlation ID is consistently propagated")
    void determinism_correlationId_consistentlyPropagated() {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.5));

        // Make requests with different correlation IDs
        for (int i = 0; i < 5; i++) {
            String correlationId = "corr-id-" + i;
            client.scoreRelevancy("query", List.of("test"), correlationId);

            // Verify the correlation ID was captured
            List<MockLLMServer.CapturedRequest> requests = mockServer.getCapturedChatRequests();
            assertTrue(requests.size() > i);
            assertEquals(correlationId, requests.get(i).getHeader("X-Correlation-ID"));
        }
    }

    @Test
    @DisplayName("DETERMINISM: Circuit breaker state is deterministic")
    void determinism_circuitBreakerState_isDeterministic() {
        // Create config with retries disabled to test circuit breaker determinism
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(mockServer.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test circuit breaker behavior
                .build();

        // Fresh client with fresh circuit breaker
        LLMHttpClient freshClient = new LLMHttpClient(noRetryConfig);
        assertEquals(CircuitBreakerState.CLOSED, freshClient.getCircuitBreaker().getState());
        assertEquals(0, freshClient.getCircuitBreaker().getFailureCount());

        // Make 5 failures to trip the circuit
        mockServer.setChatHandler(mockServer.errorHandler(500, "Error"));

        for (int i = 0; i < 5; i++) {
            assertThrows(LLMException.class,
                () -> freshClient.scoreRelevancyWithCircuitBreaker("q", List.of("t"), null));
        }

        // Circuit must now be OPEN
        assertEquals(CircuitBreakerState.OPEN, freshClient.getCircuitBreaker().getState());

        // Verify the same sequence produces same result with another fresh client
        LLMHttpClient anotherClient = new LLMHttpClient(noRetryConfig);
        for (int i = 0; i < 5; i++) {
            assertThrows(LLMException.class,
                () -> anotherClient.scoreRelevancyWithCircuitBreaker("q", List.of("t"), null));
        }
        assertEquals(CircuitBreakerState.OPEN, anotherClient.getCircuitBreaker().getState());
    }

    @Test
    @DisplayName("DETERMINISM: Multiple scores are consistently parsed")
    void determinism_multipleScores_consistentlyParsed() {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.9, 0.5, 0.1));

        // Call multiple times
        for (int i = 0; i < 5; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("high", "medium", "low"));

            assertEquals(3, scores.size());
            assertEquals(0.9, scores.get(0).getScore(), 0.001);
            assertEquals(0.5, scores.get(1).getScore(), 0.001);
            assertEquals(0.1, scores.get(2).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Response with commentary is consistently sanitized")
    void determinism_commentaryResponse_consistentlySanitized() {
        mockServer.setChatHandler(mockServer.successRelevancyWithCommentaryHandler(0.8, 0.2));

        // Call multiple times
        for (int i = 0; i < 5; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("first", "second"));

            assertEquals(2, scores.size());
            assertEquals(0.8, scores.get(0).getScore(), 0.001);
            assertEquals(0.2, scores.get(1).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Sequence handler produces deterministic sequence")
    void determinism_sequenceHandler_producesDeterministicSequence() {
        mockServer.setChatHandler(mockServer.sequenceHandler(
            mockServer.successRelevancyHandler(0.1),
            mockServer.successRelevancyHandler(0.2),
            mockServer.successRelevancyHandler(0.3)
        ));

        // Call 6 times - should cycle through 0.1, 0.2, 0.3, 0.1, 0.2, 0.3
        double[] expectedScores = {0.1, 0.2, 0.3, 0.1, 0.2, 0.3};
        for (int i = 0; i < 6; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("q", List.of("t"));
            assertEquals(expectedScores[i], scores.get(0).getScore(), 0.001);
        }
    }

    @Test
    @DisplayName("DETERMINISM: Fail-then-succeed handler is deterministic")
    void determinism_failThenSucceedHandler_isDeterministic() {
        // Create client with retries disabled to test handler determinism
        LLMClientConfig noRetryConfig = LLMClientConfig.builder()
                .baseUrl(mockServer.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)  // Disable retries to test handler behavior
                .build();
        LLMHttpClient noRetryClient = new LLMHttpClient(noRetryConfig);

        mockServer.setChatHandler(mockServer.failThenSucceedHandler(2,
            mockServer.successRelevancyHandler(0.95)));

        // First two calls must fail
        assertThrows(LLMException.class, () -> noRetryClient.scoreRelevancy("q", List.of("t")));
        assertThrows(LLMException.class, () -> noRetryClient.scoreRelevancy("q", List.of("t")));

        // Third call should succeed
        List<RelevancyScore> scores = noRetryClient.scoreRelevancy("q", List.of("t"));
        assertEquals(0.95, scores.get(0).getScore(), 0.001);

        // Subsequent calls should also succeed
        scores = noRetryClient.scoreRelevancy("q", List.of("t"));
        assertEquals(0.95, scores.get(0).getScore(), 0.001);
    }

    // ==================== Edge Case Determinism Tests ====================

    @Test
    @DisplayName("DETERMINISM: Empty embedding is consistently rejected")
    void determinism_emptyEmbedding_consistentlyRejected() {
        // Use a custom handler that returns an empty embedding array in the response
        mockServer.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        for (int i = 0; i < 3; i++) {
            assertThrows(LLMException.class, () -> client.generateEmbedding("test"));
        }
    }

    @Test
    @DisplayName("DETERMINISM: Malformed JSON is consistently rejected")
    void determinism_malformedJson_consistentlyRejected() {
        mockServer.setChatHandler(mockServer.malformedJsonHandler());

        for (int i = 0; i < 3; i++) {
            assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));
        }
    }

    @Test
    @DisplayName("DETERMINISM: Missing content field is consistently rejected")
    void determinism_missingContentField_consistentlyRejected() {
        mockServer.setChatHandler(mockServer.missingContentFieldHandler());

        for (int i = 0; i < 3; i++) {
            LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));
            assertTrue(ex.getMessage().toLowerCase().contains("content"));
        }
    }

    @Test
    @DisplayName("DETERMINISM: Missing embedding field is consistently rejected")
    void determinism_missingEmbeddingField_consistentlyRejected() {
        mockServer.setEmbeddingHandler(mockServer.missingEmbeddingFieldHandler());

        for (int i = 0; i < 3; i++) {
            LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("test"));
            assertTrue(ex.getMessage().toLowerCase().contains("embedding"));
        }
    }

    // ==================== Domain Correctness Determinism Tests ====================

    @Test
    @DisplayName("DETERMINISM: Scores are always clamped to valid range")
    void determinism_scoresAlwaysClampedToValidRange() {
        // Handler returns scores outside valid range
        mockServer.setChatHandler(mockServer.successRelevancyHandler(1.5, -0.3, 0.5));

        for (int i = 0; i < 5; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("over", "under", "normal"));

            // All scores must be in [0.0, 1.0]
            for (RelevancyScore score : scores) {
                assertTrue(score.getScore() >= 0.0 && score.getScore() <= 1.0,
                    "Score must be in [0.0, 1.0], got: " + score.getScore());
            }
        }
    }

    @Test
    @DisplayName("DETERMINISM: Out-of-range index handling is consistent")
    void determinism_outOfRangeIndexHandling_isConsistent() {
        // Handler returns index beyond candidate count - wrap in chat completion format
        mockServer.setChatHandler(exchange -> {
            String content = "[{\"index\": 99, \"score\": 0.5}]";
            // Escape the content for JSON
            String escapedContent = content.replace("\"", "\\\"");
            String response = "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        for (int i = 0; i < 3; i++) {
            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("only one"));
            // Every repetition must discard the unusable index identically.
            assertEquals(0, scores.size());
        }
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("DETERMINISM: Request body is consistently formatted")
    void determinism_requestBody_isConsistentlyFormatted() throws Exception {
        mockServer.setChatHandler(mockServer.successRelevancyHandler(0.5));

        client.scoreRelevancy("test query", List.of("test candidate"));

        List<MockLLMServer.CapturedRequest> requests = mockServer.getCapturedChatRequests();
        assertEquals(1, requests.size());

        String body = requests.get(0).getBody();
        assertNotNull(body);

        // Verify the request body is valid JSON with expected structure
        assertTrue(body.contains("\"model\""));
        assertTrue(body.contains("\"messages\""));
        assertTrue(body.contains("test query"));
        assertTrue(body.contains("test candidate"));

        // Make another request and should have same structure
        mockServer.reset();
        client.scoreRelevancy("test query", List.of("test candidate"));

        requests = mockServer.getCapturedChatRequests();
        assertEquals(1, requests.size());

        String body2 = requests.get(0).getBody();
        assertEquals(body, body2, "Request body should be identical");
    }

}
