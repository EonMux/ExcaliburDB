package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import com.eonmux.excalibur.llm.json.JsonWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Relevancy scoring through {@link LLMHttpClient}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Returned scores are in range [0.0, 1.0]</li>
 *   <li>Returned indices correspond to original candidate order</li>
 *   <li>Empty candidates return empty scores</li>
 *   <li>HTTP errors are properly categorized</li>
 *   <li>Malformed responses are handled gracefully</li>
 * </ul>
 *
 * <p>Covers the whole chat-completion round trip for {@code scoreRelevancy}: the request
 * it builds (model, prompt, auth header, correlation id, JSON escaping of quotes,
 * control characters and Unicode), what it accepts back once the sanitizer has stripped
 * markdown fences and prose, which replies it refuses outright, and how it treats an
 * index that identifies no candidate it sent.</p>
 *
 * <h2>Determinism</h2>
 * <p>All tests are deterministic - same mock responses always produce the same results.</p>
 */
@Timeout(value = 10)
class LLMHttpClientRelevancyTest extends LLMHttpClientTestBase {

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
    void scoreRelevancy_missingIndexField_isAParseFailure() {
        // A reply nothing could be read from is reported as a parse failure rather than as an
        // empty ranking, so the caller falls back to the vector order instead of answering with
        // nothing and calling it a success.
        server.setChatHandler(server.successChatHandler("[{\"score\": 0.5}]"));

        assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));
    }

    @Test
    void scoreRelevancy_missingScoreField_isAParseFailure() {
        server.setChatHandler(server.successChatHandler("[{\"index\": 0}]"));

        assertThrows(LLMException.class, () -> client.scoreRelevancy("query", List.of("test")));
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
    void scoreRelevancy_indexOutOfBounds_discardsScore() {
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

        // Index 5 identifies no candidate we sent. Clamping it to 0 would assert that the model
        // scored "Only one" when it did not, so the score is discarded instead.
        assertEquals(0, scores.size());
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
}
