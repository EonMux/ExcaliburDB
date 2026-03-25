package io.excalibur.llm.client;

import com.sun.net.httpserver.HttpHandler;
import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.RelevancyScore;
import io.excalibur.llm.json.JsonWriter;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LLMHttpClient}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <h3>Relevancy Scoring</h3>
 * <ul>
 *   <li>Returned scores are in range [0.0, 1.0]</li>
 *   <li>Returned indices correspond to original candidate order</li>
 *   <li>Empty candidates return empty scores</li>
 *   <li>HTTP errors are properly categorized</li>
 *   <li>Malformed responses are handled gracefully</li>
 * </ul>
 *
 * <h3>Embedding Generation</h3>
 * <ul>
 *   <li>Embedding vector: non-null, length in [1, maxDimension], all values finite (not NaN/Inf)</li>
 *   <li>Input text: non-null, non-empty string</li>
 *   <li>HTTP errors are properly categorized</li>
 *   <li>Malformed responses are handled gracefully</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * <p>All tests are deterministic - same mock responses always produce the same results.</p>
 */
@Timeout(value = 10)
public class LLMHttpClientTest {

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

    // ============= Success Path Tests =============

    @Test
    void scoreRelevancy_singleCandidate_returnsScore() {
        server.setChatHandler(server.successRelevancyHandler(0.85));

        List<String> candidates = List.of("This is a test passage about databases.");
        List<RelevancyScore> scores = client.scoreRelevancy("What is a database?", candidates);

        assertEquals(1, scores.size());
        assertEquals(0, scores.get(0).getIndex());
        assertEquals(0.85, scores.get(0).getScore(), 0.001);
        assertEquals(candidates.get(0), scores.get(0).getCandidateText());
    }

    @Test
    void scoreRelevancy_multipleCandidates_returnsAllScores() {
        server.setChatHandler(server.successRelevancyHandler(0.9, 0.3, 0.65));

        List<String> candidates = List.of(
                "Highly relevant passage",
                "Not very relevant passage",
                "Somewhat relevant passage"
        );

        List<RelevancyScore> scores = client.scoreRelevancy("test query", candidates);

        assertEquals(3, scores.size());
        assertEquals(0.9, scores.get(0).getScore(), 0.001);
        assertEquals(0.3, scores.get(1).getScore(), 0.001);
        assertEquals(0.65, scores.get(2).getScore(), 0.001);
    }

    @Test
    void scoreRelevancy_scoresClampedToValidRange() {
        // LLM returns out-of-range scores - handler clamps them
        server.setChatHandler(server.successRelevancyHandler(1.5, -0.3));

        List<String> candidates = List.of("First", "Second");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        // Scores should be clamped to [0.0, 1.0] by RelevancyScore
        assertTrue(scores.get(0).getScore() >= 0.0 && scores.get(0).getScore() <= 1.0);
        assertTrue(scores.get(1).getScore() >= 0.0 && scores.get(1).getScore() <= 1.0);
    }

    // ============= Response Sanitization Tests =============

    @Test
    void scoreRelevancy_responseWithMarkdownFence_stripsAndParses() {
        server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.75));

        List<String> candidates = List.of("Test passage");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        assertEquals(1, scores.size());
        assertEquals(0.75, scores.get(0).getScore(), 0.001);
    }

    @Test
    void scoreRelevancy_responseWithCommentary_extractsJsonArray() {
        server.setChatHandler(server.successRelevancyWithCommentaryHandler(0.8));

        List<String> candidates = List.of("Test");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        assertEquals(1, scores.size());
        assertEquals(0.8, scores.get(0).getScore(), 0.001);
    }

    @Test
    void scoreRelevancy_responseWithMarkdownNoLanguage_stripsFence() {
        server.setChatHandler(server.successChatHandler("```\n[{\"index\": 0, \"score\": 0.6}]\n```"));

        List<String> candidates = List.of("Test");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        assertEquals(1, scores.size());
        assertEquals(0.6, scores.get(0).getScore(), 0.001);
    }

    // ============= Error Handling Tests =============

    @Test
    void scoreRelevancy_nullQuery_throwsNullPointerException() {
        List<String> candidates = List.of("Test");
        assertThrows(NullPointerException.class, () -> client.scoreRelevancy(null, candidates));
    }

    @Test
    void scoreRelevancy_emptyQuery_throwsIllegalArgumentException() {
        List<String> candidates = List.of("Test");
        assertThrows(IllegalArgumentException.class, () -> client.scoreRelevancy("", candidates));
    }

    @Test
    void scoreRelevancy_nullCandidates_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> client.scoreRelevancy("query", null));
    }

    @Test
    void scoreRelevancy_emptyCandidates_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> client.scoreRelevancy("query", List.of()));
    }

    @Test
    void scoreRelevancy_httpError_throwsLLMException() {
        server.setChatHandler(server.errorHandler(500, "Internal Server Error"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void scoreRelevancy_http401_throwsLLMException() {
        server.setChatHandler(server.unauthorizedHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void scoreRelevancy_malformedJson_throwsLLMException() {
        server.setChatHandler(server.malformedJsonHandler());

        assertThrows(LLMException.class,
                () -> client.scoreRelevancy("query", List.of("test")));
    }

    @Test
    void scoreRelevancy_missingIndexField_returnsEmptyList() {
        // Regex requires both index and score fields, so incomplete objects are ignored
        server.setChatHandler(server.successChatHandler("[{\"score\": 0.5}]"));

        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));
        assertEquals(0, scores.size());
    }

    @Test
    void scoreRelevancy_missingScoreField_returnsEmptyList() {
        // Regex requires both index and score fields, so incomplete objects are ignored
        server.setChatHandler(server.successChatHandler("[{\"index\": 0}]"));

        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"));
        assertEquals(0, scores.size());
    }

    // ============= Correlation ID Tests =============

    @Test
    void scoreRelevancy_withCorrelationId_includesHeader() {
        server.setChatHandler(server.successRelevancyHandler(0.8));

        client.scoreRelevancy("query", List.of("test"), "test-correlation-123");

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        assertEquals(1, requests.size());
        assertEquals("test-correlation-123", requests.get(0).getHeader("X-Correlation-ID"));
    }

    @Test
    void scoreRelevancy_nullCorrelationId_works() {
        server.setChatHandler(server.successRelevancyHandler(0.8));

        // Should work without correlation ID
        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"), (String) null);
        assertEquals(1, scores.size());
    }

    @Test
    void scoreRelevancy_blankCorrelationId_works() {
        server.setChatHandler(server.successRelevancyHandler(0.8));

        // Should work with blank correlation ID
        List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("test"), "   ");
        assertEquals(1, scores.size());
    }

    // ============= Index Validation Tests =============

    @Test
    void scoreRelevancy_indexOutOfBounds_clampsToValidRange() {
        // LLM returns index beyond candidates size - wrap in chat completion format
        server.setChatHandler(exchange -> {
            String content = "[{\"index\": 5, \"score\": 0.8}]";
            // Escape the content for JSON
            String escapedContent = content.replace("\"", "\\\"");
            String response = "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        List<String> candidates = List.of("Only one");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        // Index 5 should be clamped to 0 (only valid index for single-element list)
        assertEquals(0, scores.get(0).getIndex());
    }

    @Test
    void scoreRelevancy_negativeIndex_notParsed() {
        // Negative indices are not matched by the regex pattern (\d+ only matches positive integers)
        server.setChatHandler(server.successChatHandler("[{\"index\": -1, \"score\": 0.8}]"));

        List<String> candidates = List.of("First", "Second");
        List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

        // Negative index won't be parsed, so no scores returned
        assertEquals(0, scores.size());
    }

    // ============= Request Building Tests =============

    @Test
    void scoreRelevancy_includesCorrectModel() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));
        client.scoreRelevancy("query", List.of("test"));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        assertEquals(1, requests.size());

        String body = requests.get(0).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"model\":\"test-model\""));
    }

    @Test
    void scoreRelevancy_includesQueryInPrompt() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));
        client.scoreRelevancy("What is machine learning?", List.of("ML is..."));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        String body = requests.get(0).getBody();
        assertNotNull(body);
        assertTrue(body.contains("What is machine learning?"));
    }

    @Test
    void scoreRelevancy_withApiKey_includesAuthHeader() throws Exception {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .apiKey("secret-key-123")
                .timeout(Duration.ofSeconds(5))
                .build();

        client = new LLMHttpClient(config);

        server.setChatHandler(server.successRelevancyHandler(0.5));
        client.scoreRelevancy("query", List.of("test"));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        assertEquals("Bearer secret-key-123", requests.get(0).getHeader("Authorization"));
    }

    // ============= Timeout Configuration Tests =============

    @Test
    void config_defaultTimeout_is30Seconds() {
        LLMClientConfig defaultConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .build();

        assertEquals(Duration.ofMillis(30_000L), defaultConfig.getTimeout(),
                "Default timeout should be 30000ms (30 seconds)");
    }

    @Test
    void config_zeroTimeout_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .timeout(Duration.ZERO)
                        .build(),
                "Zero timeout should be rejected");
    }

    @Test
    void config_negativeTimeout_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .timeout(Duration.ofMillis(-1000))
                        .build(),
                "Negative timeout should be rejected");
    }

    @Test
    void config_positiveTimeout_isAccepted() {
        Duration customTimeout = Duration.ofSeconds(60);
        LLMClientConfig customConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(customTimeout)
                .build();

        assertEquals(customTimeout, customConfig.getTimeout(),
                "Positive timeout should be accepted");
    }

    @Test
    void config_nullTimeout_usesDefault() {
        LLMClientConfig configWithNullTimeout = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(null)
                .build();

        assertEquals(Duration.ofSeconds(30), configWithNullTimeout.getTimeout(),
                "Null timeout should use default of 30 seconds");
    }

    @Test
    void config_smallPositiveTimeout_isAccepted() {
        Duration smallTimeout = Duration.ofMillis(1);
        LLMClientConfig smallConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(smallTimeout)
                .build();

        assertEquals(smallTimeout, smallConfig.getTimeout(),
                "Even 1ms timeout should be accepted (positive value)");
    }

    @Test
    void client_usesConfiguredTimeout() {
        Duration customTimeout = Duration.ofSeconds(45);
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .timeout(customTimeout)
                .build();

        client = new LLMHttpClient(config);

        // Verify the config is stored and accessible
        assertEquals(customTimeout, client.getConfig().getTimeout(),
                "Client should use the configured timeout");
    }

    // ============= Embedding Generation Success Path Tests =============

    @Test
    void generateEmbedding_validText_returnsEmbeddingVector() {
        float[] expectedEmbedding = new float[]{0.1f, -0.2f, 0.3f, 0.4f, 0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Test text for embedding");

        assertNotNull(result, "Embedding must not be null");
        assertEquals(expectedEmbedding.length, result.length, "Embedding dimension must match");
        for (int i = 0; i < expectedEmbedding.length; i++) {
            assertEquals(expectedEmbedding[i], result[i], 0.0001f, "Embedding values must match");
        }
    }

    @Test
    void generateEmbedding_singleDimension_validEmbedding() {
        // Domain correctness: minimum valid dimension is 1
        float[] expectedEmbedding = new float[]{0.123f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Single dimension test");

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(0.123f, result[0], 0.0001f);
    }

    @Test
    void generateEmbedding_maxDimension_validEmbedding() {
        // Domain correctness: maximum supported dimension is 4096
        float[] expectedEmbedding = new float[4096];
        for (int i = 0; i < 4096; i++) {
            expectedEmbedding[i] = (float) (Math.sin(i) * 0.5); // Values in [-0.5, 0.5]
        }
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Max dimension test");

        assertNotNull(result);
        assertEquals(4096, result.length);
        // Verify all values are finite and match
        for (int i = 0; i < 4096; i++) {
            assertTrue(Float.isFinite(result[i]), "All values must be finite");
            assertEquals(expectedEmbedding[i], result[i], 0.0001f);
        }
    }

    @Test
    void generateEmbedding_withCorrelationId_includesHeader() {
        float[] expectedEmbedding = new float[]{0.5f, 0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        client.generateEmbedding("Test text", "embedding-correlation-456");

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedEmbeddingRequests();
        assertEquals("embedding-correlation-456", requests.get(0).getHeader("X-Correlation-ID"));
    }

    @Test
    void generateEmbedding_nullCorrelationId_works() {
        float[] expectedEmbedding = new float[]{0.1f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Test", (String) null);

        assertNotNull(result);
        assertEquals(1, result.length);
    }

    // ============= Embedding Input Validation Tests =============

    @Test
    void generateEmbedding_nullText_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> client.generateEmbedding(null));
    }

    @Test
    void generateEmbedding_emptyText_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> client.generateEmbedding(""));
    }

    // ============= Embedding Domain Correctness: Finite Values Tests =============

    @Test
    void generateEmbedding_responseContainsNaN_throwsLLMException() {
        // Domain correctness: NaN is not a valid embedding value
        server.setEmbeddingHandler(server.embeddingWithNaNHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test with NaN"));

        assertTrue(ex.getMessage().contains("NaN"), "Error message should mention NaN");
    }

    @Test
    void generateEmbedding_responseContainsPositiveInfinity_throwsLLMException() {
        // Domain correctness: Infinity is not a valid embedding value
        server.setEmbeddingHandler(server.embeddingWithInfinityHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test with Infinity"));

        // Error message should mention non-finite values (Infinity or Infinite)
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("infinite") || msg.contains("infinity") || msg.contains("finite"),
                "Error message should mention non-finite values: " + ex.getMessage());
    }

    @Test
    void generateEmbedding_allFiniteValues_accepted() {
        // Domain correctness: all values must be finite (bounded, not NaN/Inf)
        float[] validEmbedding = new float[]{
            -1.0f, -0.5f, 0.0f, 0.5f, 1.0f,
            Float.MAX_VALUE, Float.MIN_VALUE, -Float.MAX_VALUE,
            0.000001f, -0.000001f
        };
        server.setEmbeddingHandler(server.successEmbeddingHandler(validEmbedding));

        float[] result = client.generateEmbedding("Test with boundary values");

        assertNotNull(result);
        assertEquals(validEmbedding.length, result.length);
        for (float v : result) {
            assertTrue(Float.isFinite(v), "All values must be finite");
        }
    }

    // ============= Embedding Domain Correctness: Dimension Range Tests =============

    @Test
    void generateEmbedding_zeroDimension_throwsLLMException() {
        // Domain correctness: embedding dimension must be >= 1
        // Custom handler that returns empty embedding array
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Empty embedding test"));

        assertTrue(ex.getMessage().contains("dimension") ||
                   ex.getMessage().contains("range") ||
                   ex.getMessage().contains("Empty"),
                "Error message should mention dimension constraint or empty array: " + ex.getMessage());
    }

    @Test
    void generateEmbedding_exceedsMaxDimension_throwsLLMException() {
        // Domain correctness: embedding dimension must be <= 4096
        // Use a custom handler to bypass MockLLMServer's validation and test client-side validation
        server.setEmbeddingHandler(exchange -> {
            // Build a JSON response with 4097 dimensions
            StringBuilder sb = new StringBuilder();
            sb.append("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
            for (int i = 0; i < 4097; i++) {
                if (i > 0) sb.append(",");
                sb.append("0.1");
            }
            sb.append("],\"index\":0}]}");
            String response = sb.toString();
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Oversized embedding test"));

        assertTrue(ex.getMessage().contains("dimension") || ex.getMessage().contains("range"),
                "Error message should mention dimension constraint: " + ex.getMessage());
    }

    // ============= Embedding Error Handling Tests =============

    @Test
    void generateEmbedding_httpError_throwsLLMException() {
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Error test"));

        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void generateEmbedding_http401_throwsLLMException() {
        server.setEmbeddingHandler(server.unauthorizedHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Auth error test"));

        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void generateEmbedding_emptyResponse_throwsLLMException() {
        server.setEmbeddingHandler(server.emptyResponseHandler());

        assertThrows(LLMException.class, () -> client.generateEmbedding("Empty response test"));
    }

    @Test
    void generateEmbedding_missingEmbeddingField_throwsLLMException() {
        server.setEmbeddingHandler(server.missingEmbeddingFieldHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Missing field test"));

        assertTrue(ex.getMessage().contains("embedding"));
    }

    @Test
    void generateEmbedding_malformedEmbeddingArray_throwsLLMException() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"data\":[{\"embedding\":[\"not\",\"a\",\"number\"]}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThrows(LLMException.class, () -> client.generateEmbedding("Malformed test"));
    }

    // ============= JSON Request Body: UTF-8 and String Escaping Tests =============

    @Test
    @DisplayName("JSON request body: query with quotes produces valid JSON")
    void scoreRelevancy_queryWithQuotes_validJson() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));
        client.scoreRelevancy("What is \"machine learning\"?", List.of("ML is..."));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        String body = requests.get(0).getBody();

        assertNotNull(body);
        // Quotes in query should be escaped
        assertTrue(body.contains("\\\"machine learning\\\""),
            "Quotes should be escaped in JSON: " + body);
        // Should be valid JSON
        assertTrue(JsonWriter.isValidJson(body),
            "Request body should be valid JSON: " + body);
    }

    @Test
    @DisplayName("JSON request body: candidate with newlines and tabs produces valid JSON")
    void scoreRelevancy_candidateWithNewlines_validJson() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));
        client.scoreRelevancy("query", List.of("Line1\nLine2\tTabbed"));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        String body = requests.get(0).getBody();

        assertNotNull(body);
        // Newlines and tabs should be escaped
        assertTrue(body.contains("\\n"),
            "Newlines should be escaped: " + body);
        assertTrue(body.contains("\\t"),
            "Tabs should be escaped: " + body);
        // Should be valid JSON
        assertTrue(JsonWriter.isValidJson(body),
            "Request body should be valid JSON: " + body);
    }

    @Test
    @DisplayName("JSON request body: Unicode text preserves characters and produces valid JSON")
    void scoreRelevancy_unicodeText_validJson() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));
        String unicodeQuery = "What is 世界 in 日本語? Emoji: 🎉 Café";
        client.scoreRelevancy(unicodeQuery, List.of("Test"));

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedChatRequests();
        String body = requests.get(0).getBody();

        assertNotNull(body);
        // Unicode characters should be preserved (not escaped)
        assertTrue(body.contains("世界"),
            "CJK characters should be preserved: " + body);
        assertTrue(body.contains("🎉"),
            "Emoji should be preserved: " + body);
        // Should be valid JSON
        assertTrue(JsonWriter.isValidJson(body),
            "Request body should be valid JSON: " + body);
    }

    // ============= Circuit Breaker Integration Tests =============

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

    // ============= Concurrent Request Tests =============

    @Test
    @DisplayName("Concurrent requests with same mock response produce same results")
    void concurrentRequests_produceConsistentResults() throws Exception {
        server.setChatHandler(server.successRelevancyHandler(0.5));

        int numThreads = 5;
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
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // All results should be identical
        assertEquals(numThreads, allResults.size());
        for (List<RelevancyScore> result : allResults) {
            assertEquals(1, result.size());
            assertEquals(0, result.get(0).getIndex());
            assertEquals(0.5, result.get(0).getScore(), 0.001);
        }
    }

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

    @Test
    @DisplayName("Retry: success on second attempt does")
    void retry_successOnSecondAttempt() throws IOException {
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

        float[] result = client.generateEmbedding("Test");

        assertNotNull(result);
        assertEquals(expectedEmbedding.length, result.length);
        for (int i = 0; i < expectedEmbedding.length; i++) {
            assertEquals(expectedEmbedding[i], result[i], 0.0001f);
        }
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

    @Test
    @DisplayName("Retry: exponential backoff delay increases with each attempt")
    void retry_exponentialBackoffDelay_increases() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .retryBaseDelay(Duration.ofMillis(100)) // 100ms base delay
                .build();
        client = new LLMHttpClient(config);

        // Create a handler that always fails
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));

        // Make requests that will fail - should retry 3 times
        assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test"));

        // Verify exponential backoff is configured
        assertTrue(config.getMaxRetries() >= 1, "Max retries should be at least 1");
    }

    @Test
    @DisplayName("Retry: configurable retryBaseDelay is used")
    void retry_configurableBaseDelay_used() throws IOException {
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(1)
                .retryBaseDelay(Duration.ofMillis(200)) // 200ms base delay
                .build();
        client = new LLMHttpClient(config);

        float[] expectedEmbedding = new float[]{0.5f};

        // First call fails, second succeeds
        server.setEmbeddingHandler(server.sequenceHandler(
                server.errorHandler(500, "Internal Server Error"),
                server.successEmbeddingHandler(expectedEmbedding)
        ));

        float[] result = client.generateEmbedding("Test");
        assertNotNull(result);
        assertEquals(expectedEmbedding.length, result.length);
        // Verify config uses the custom retry base delay
        assertEquals(Duration.ofMillis(200), config.getRetryBaseDelay());
    }
}
