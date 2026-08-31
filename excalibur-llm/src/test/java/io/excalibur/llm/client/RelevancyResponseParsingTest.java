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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for relevancy scoring response parsing.
 *
 * <h2>Scope</h2>
 * <p>Tests the full parsing pipeline from raw LLM response to List&lt;RelevancyScore&gt;:</p>
 * <ol>
 *   <li>Extract content from OpenAI chat completion response format</li>
 *   <li>Sanitize markdown fences and commentary</li>
 *   <li>Parse JSON array of score objects</li>
 *   <li>Validate and clamp scores/indices</li>
 *   <li>Associate candidate text with scores</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Scores: must be in [0.0, 1.0] after parsing (clamped if necessary)</li>
 *   <li>Indices: must be in [0, candidates.size()-1] (clamped if necessary)</li>
 *   <li>Candidate text: associated with each score by index</li>
 *   <li>Empty results: allowed for empty or unparseable arrays</li>
 * </ul>
 *
 * @see LLMHttpClient
 * @see ResponseSanitizer
 * @see RelevancyScore
 */
@Timeout(value = 30)
class RelevancyResponseParsingTest {

    private MockLLMServer server;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        LLMClientConfig config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .embeddingModel("test-embedding")
                .chatModel("test-chat")
                .maxRetries(0)  // Disable retries for faster tests
                .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== Standard Format Parsing ====================

    @Nested
    @DisplayName("Standard JSON Format")
    class StandardFormatTests {

        @Test
        @DisplayName("Single score object parses correctly")
        void singleScoreObject() {
            server.setChatHandler(server.successRelevancyHandler(0.85));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate 1"));

            assertEquals(1, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(0.85, scores.get(0).getScore(), 0.001);
            assertEquals("candidate 1", scores.get(0).getCandidateText());
        }

        @Test
        @DisplayName("Multiple score objects preserve order")
        void multipleScoreObjects() {
            server.setChatHandler(server.successRelevancyHandler(0.9, 0.3, 0.65, 0.42));

            List<String> candidates = List.of("doc A", "doc B", "doc C", "doc D");
            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(4, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(0.9, scores.get(0).getScore(), 0.001);
            assertEquals("doc A", scores.get(0).getCandidateText());

            assertEquals(1, scores.get(1).getIndex());
            assertEquals(0.3, scores.get(1).getScore(), 0.001);
            assertEquals("doc B", scores.get(1).getCandidateText());

            assertEquals(2, scores.get(2).getIndex());
            assertEquals(0.65, scores.get(2).getScore(), 0.001);
            assertEquals("doc C", scores.get(2).getCandidateText());

            assertEquals(3, scores.get(3).getIndex());
            assertEquals(0.42, scores.get(3).getScore(), 0.001);
            assertEquals("doc D", scores.get(3).getCandidateText());
        }

        @Test
        @DisplayName("Empty JSON array returns empty list")
        void emptyArray() {
            server.setChatHandler(server.successChatHandler("[]"));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertTrue(scores.isEmpty());
        }

        @Test
        @DisplayName("Compact JSON format (no whitespace) parses correctly")
        void compactFormat() {
            String compactJson = "[{\"index\":0,\"score\":0.75},{\"index\":1,\"score\":0.25}]";
            server.setChatHandler(server.successChatHandler(compactJson));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.75, scores.get(0).getScore(), 0.001);
            assertEquals(0.25, scores.get(1).getScore(), 0.001);
        }

        @Test
        @DisplayName("Pretty-printed JSON with newlines parses correctly")
        void prettyPrintedFormat() {
            String prettyJson = """
                [
                  {"index": 0, "score": 0.92},
                  {"index": 1, "score": 0.45}
                ]
                """;
            server.setChatHandler(server.successChatHandler(prettyJson));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.92, scores.get(0).getScore(), 0.001);
            assertEquals(0.45, scores.get(1).getScore(), 0.001);
        }
    }

    // ==================== Markdown Fence Handling ====================

    @Nested
    @DisplayName("Markdown Fence Handling")
    class MarkdownFenceTests {

        @Test
        @DisplayName("JSON wrapped in ```json fence extracts correctly")
        void jsonFence() {
            server.setChatHandler(server.successRelevancyWithMarkdownHandler(0.75));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.75, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("JSON wrapped in ``` (no language) extracts correctly")
        void plainFence() {
            String fenced = "```\n[{\"index\": 0, \"score\": 0.65}]\n```";
            server.setChatHandler(server.successChatHandler(fenced));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.65, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Multiple markdown blocks extracts first JSON array")
        void multipleFences() {
            String multiFence = """
                Some intro text.
                ```json
                [{"index": 0, "score": 0.8}]
                ```
                More text.
                ```
                Not JSON
                ```
                """;
            server.setChatHandler(server.successChatHandler(multiFence));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.8, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("JSON with fence and trailing newline extracts correctly")
        void fenceWithTrailingNewline() {
            String fenced = "```json\n[{\"index\": 0, \"score\": 0.55}]\n```\n";
            server.setChatHandler(server.successChatHandler(fenced));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.55, scores.get(0).getScore(), 0.001);
        }
    }

    // ==================== Commentary Extraction ====================

    @Nested
    @DisplayName("Commentary Extraction")
    class CommentaryTests {

        @Test
        @DisplayName("Text before JSON array is stripped")
        void textBeforeJson() {
            server.setChatHandler(server.successRelevancyWithCommentaryHandler(0.8));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.8, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Text after JSON array is stripped")
        void textAfterJson() {
            String response = "[{\"index\": 0, \"score\": 0.7}]\\nThat's all the results.";
            server.setChatHandler(server.successChatHandler(response));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.7, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Text before and after JSON array is stripped")
        void textBeforeAndAfter() {
            String response = "Here are the scores:\\n[{\"index\": 0, \"score\": 0.9}]\\nLet me know if you need more!";
            server.setChatHandler(server.successChatHandler(response));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.9, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Complex real-world LLM response with commentary and markdown")
        void complexRealWorldResponse() {
            String complex = """
                Based on my analysis of the candidates, here are the relevancy scores:

                ```json
                [
                  {"index": 0, "score": 0.92},
                  {"index": 1, "score": 0.45}
                ]
                ```

                The first passage is most relevant because it directly addresses the query topic.
                Let me know if you need any clarification!
                """;
            server.setChatHandler(server.successChatHandler(complex));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.92, scores.get(0).getScore(), 0.001);
            assertEquals(0.45, scores.get(1).getScore(), 0.001);
        }
    }

    // ==================== Score Edge Cases ====================

    @Nested
    @DisplayName("Score Edge Cases")
    class ScoreEdgeCaseTests {

        @Test
        @DisplayName("Score at lower boundary (0.0)")
        void scoreAtZero() {
            server.setChatHandler(server.successRelevancyHandler(0.0));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.0, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Score at upper boundary (1.0)")
        void scoreAtOne() {
            server.setChatHandler(server.successRelevancyHandler(1.0));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(1.0, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Score above 1.0 is clamped to 1.0")
        void scoreAboveOneClamped() {
            server.setChatHandler(server.successRelevancyHandler(1.5));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(1.0, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Score below 0.0 is clamped to 0.0")
        void scoreBelowZeroClamped() {
            server.setChatHandler(server.successRelevancyHandler(-0.3));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.0, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Scientific notation score parses correctly")
        void scientificNotationScore() {
            String json = "[{\"index\": 0, \"score\": 9.5e-1}, {\"index\": 1, \"score\": 1.2E-2}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.95, scores.get(0).getScore(), 0.001);
            assertEquals(0.012, scores.get(1).getScore(), 0.001);
        }

        @Test
        @DisplayName("Very precise decimal score preserves precision")
        void preciseDecimalScore() {
            String json = "[{\"index\": 0, \"score\": 0.123456789}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.123456789, scores.get(0).getScore(), 0.000000001);
        }

        @Test
        @DisplayName("Multiple scores at boundaries")
        void multipleBoundaryScores() {
            server.setChatHandler(server.successRelevancyHandler(0.0, 1.0, 0.5));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B", "C"));

            assertEquals(3, scores.size());
            assertEquals(0.0, scores.get(0).getScore(), 0.001);
            assertEquals(1.0, scores.get(1).getScore(), 0.001);
            assertEquals(0.5, scores.get(2).getScore(), 0.001);
        }

        @Test
        @DisplayName("Mixed valid and out-of-range scores")
        void mixedValidAndOutOfRangeScores() {
            String json = "[{\"index\": 0, \"score\": 0.5}, {\"index\": 1, \"score\": 1.5}, {\"index\": 2, \"score\": -0.5}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B", "C"));

            assertEquals(3, scores.size());
            assertEquals(0.5, scores.get(0).getScore(), 0.001);  // Valid
            assertEquals(1.0, scores.get(1).getScore(), 0.001);  // Clamped from 1.5
            assertEquals(0.0, scores.get(2).getScore(), 0.001);  // Clamped from -0.5
        }
    }

    // ==================== Index Handling ====================

    @Nested
    @DisplayName("Index Handling")
    class IndexHandlingTests {

        @Test
        @DisplayName("Index above upper bound is discarded, not clamped")
        void indexOutOfBoundsDiscarded() {
            // LLM returns index 5 but we only have 2 candidates
            String json = "[{\"index\": 5, \"score\": 0.8}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            // Clamping to index 1 would attribute this score to candidate "B", which the model
            // never scored. The score carries no recoverable meaning, so it is dropped.
            assertEquals(0, scores.size());
        }

        @Test
        @DisplayName("Negative index does not match regex pattern")
        void negativeIndexNotMatched() {
            // The regex pattern uses \d+ which only matches non-negative integers
            // So negative indices won't be parsed at all
            String json = "[{\"index\": -1, \"score\": 0.8}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            // Negative index doesn't match \d+ regex, so no scores parsed
            assertTrue(scores.isEmpty());
        }

        @Test
        @DisplayName("Non-sequential indices are preserved")
        void nonSequentialIndices() {
            String json = "[{\"index\": 2, \"score\": 0.9}, {\"index\": 0, \"score\": 0.7}]";
            server.setChatHandler(server.successChatHandler(json));

            List<String> candidates = List.of("A", "B", "C");
            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(2, scores.size());
            assertEquals(2, scores.get(0).getIndex());
            assertEquals("C", scores.get(0).getCandidateText());
            assertEquals(0, scores.get(1).getIndex());
            assertEquals("A", scores.get(1).getCandidateText());
        }

        @Test
        @DisplayName("Duplicate indices are allowed")
        void duplicateIndices() {
            String json = "[{\"index\": 0, \"score\": 0.9}, {\"index\": 0, \"score\": 0.7}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(0, scores.get(1).getIndex());
        }

        @Test
        @DisplayName("Large index is discarded and valid siblings survive")
        void largeIndexDiscarded() {
            String json = "[{\"index\": 999999, \"score\": 0.8}, {\"index\": 1, \"score\": 0.6}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B", "C"));

            // The unusable entry is dropped; the well-formed one in the same response is kept.
            assertEquals(1, scores.size());
            assertEquals(1, scores.get(0).getIndex());
            assertEquals("B", scores.get(0).getCandidateText());
        }
    }

    // ==================== Malformed Input Handling ====================

    @Nested
    @DisplayName("Malformed Input Handling")
    class MalformedInputTests {

        @Test
        @DisplayName("Missing index field is a parse failure, not an empty answer")
        void missingIndexField() {
            String json = "[{\"score\": 0.8}]";
            server.setChatHandler(server.successChatHandler(json));

            // An unreadable reply reported as an empty score list is indistinguishable from a
            // reply that scored nothing, so the caller cannot know to fall back to the vector
            // ranking - and the circuit breaker counts the exchange as a success.
            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Missing score field is a parse failure, not an empty answer")
        void missingScoreField() {
            String json = "[{\"index\": 0}]";
            server.setChatHandler(server.successChatHandler(json));

            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Wrong field names are a parse failure")
        void wrongFieldNames() {
            String json = "[{\"idx\": 0, \"value\": 0.8}]";
            server.setChatHandler(server.successChatHandler(json));

            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Extra fields in an object are tolerated")
        void extraFieldsAreTolerated() {
            // A model that explains itself is answering the question, and a parser that rejects
            // the reply over an extra key throws the whole ranking away.
            String json = "[{\"index\": 0, \"score\": 0.85, \"reason\": \"highly relevant\"}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(0.85, scores.get(0).getScore(), 1e-6);
        }

        @Test
        @DisplayName("Transposed fields are read")
        void transposedFieldsAreRead() {
            String json = "[{\"score\": 0.85, \"index\": 0}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.85, scores.get(0).getScore(), 1e-6);
        }

        @Test
        @DisplayName("Invalid JSON throws LLMException")
        void invalidJsonThrows() {
            server.setChatHandler(server.malformedJsonHandler());

            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Missing content field throws LLMException")
        void missingContentFieldThrows() {
            server.setChatHandler(server.missingContentFieldHandler());

            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Empty response body throws LLMException")
        void emptyResponseBodyThrows() {
            server.setChatHandler(server.emptyResponseHandler());

            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Non-array JSON returns empty list")
        void nonArrayJsonReturnsEmpty() {
            String objectJson = "{\"index\": 0, \"score\": 0.5}";
            server.setChatHandler(server.successChatHandler(objectJson));

            // ResponseSanitizer returns null for non-array
            assertThrows(LLMException.class, () ->
                client.scoreRelevancy("query", List.of("candidate")));
        }

        @Test
        @DisplayName("Partially malformed array parses valid entries")
        void partiallyMalformedArray() {
            // Second entry is malformed, first should parse
            String json = "[{\"index\": 0, \"score\": 0.8}, {broken}, {\"index\": 2, \"score\": 0.6}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B", "C"));

            // First and third entries should parse
            assertEquals(2, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(2, scores.get(1).getIndex());
        }
    }

    // ==================== Unicode and Special Characters ====================

    @Nested
    @DisplayName("Unicode and Special Characters")
    class UnicodeTests {

        @Test
        @DisplayName("Unicode in query text produces valid JSON")
        void unicodeInQuery() {
            server.setChatHandler(server.successRelevancyHandler(0.5));

            // Should not throw - JSON escaping handles unicode
            assertDoesNotThrow(() ->
                client.scoreRelevancy("日本語クエリ", List.of("candidate")));
        }

        @Test
        @DisplayName("Unicode in candidate text is preserved")
        void unicodeInCandidateText() {
            // Return 3 scores for 3 candidates
            server.setChatHandler(server.successRelevancyHandler(0.75, 0.65, 0.55));

            List<String> candidates = List.of("这是一个测试", "Ελληνικά", "مرحبا");
            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(3, scores.size());
            assertEquals("这是一个测试", scores.get(0).getCandidateText());
            assertEquals("Ελληνικά", scores.get(1).getCandidateText());
            assertEquals("مرحبا", scores.get(2).getCandidateText());
        }

        @Test
        @DisplayName("Escaped quotes in content are handled")
        void escapedQuotesInContent() {
            server.setChatHandler(server.successRelevancyHandler(0.5));

            // Candidate with quotes - should not break JSON
            assertDoesNotThrow(() ->
                client.scoreRelevancy("query", List.of("He said \"hello\"")));
        }

        @Test
        @DisplayName("Newlines in candidate text are handled")
        void newlinesInCandidateText() {
            server.setChatHandler(server.successRelevancyHandler(0.5));

            // Candidate with newlines - should not break JSON
            assertDoesNotThrow(() ->
                client.scoreRelevancy("query", List.of("Line1\nLine2\nLine3")));
        }

        @Test
        @DisplayName("Tab characters in candidate text are handled")
        void tabsInCandidateText() {
            server.setChatHandler(server.successRelevancyHandler(0.5));

            assertDoesNotThrow(() ->
                client.scoreRelevancy("query", List.of("Col1\tCol2\tCol3")));
        }

        @Test
        @DisplayName("Emoji in candidate text is preserved")
        void emojiInCandidateText() {
            server.setChatHandler(server.successRelevancyHandler(0.9));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("Hello 👋 World 🌍"));

            assertEquals(1, scores.size());
            assertEquals("Hello 👋 World 🌍", scores.get(0).getCandidateText());
        }
    }

    // ==================== Large Response Handling ====================

    @Nested
    @DisplayName("Large Response Handling")
    class LargeResponseTests {

        @Test
        @DisplayName("Large batch of scores parses correctly")
        void largeBatchOfScores() {
            int count = 100;
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) json.append(", ");
                json.append("{\"index\": ").append(i).append(", \"score\": ").append(i / 100.0).append("}");
            }
            json.append("]");
            server.setChatHandler(server.successChatHandler(json.toString()));

            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                candidates.add("Candidate " + i);
            }

            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals(count, scores.size());
            assertEquals(0, scores.get(0).getIndex());
            assertEquals(99, scores.get(99).getIndex());
            assertEquals("Candidate 50", scores.get(50).getCandidateText());
        }

        @Test
        @DisplayName("Long candidate text is preserved")
        void longCandidateText() {
            server.setChatHandler(server.successRelevancyHandler(0.85));

            // Create a very long candidate text
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("This is sentence ").append(i).append(". ");
            }
            String longText = sb.toString();

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of(longText));

            assertEquals(1, scores.size());
            assertEquals(longText, scores.get(0).getCandidateText());
        }
    }

    // ==================== Domain Correctness Verification ====================

    @Nested
    @DisplayName("Domain Correctness Verification")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("All returned scores are in valid range [0.0, 1.0]")
        void allScoresInValidRange() {
            // Return scores including edge cases
            String json = "[{\"index\": 0, \"score\": 0.0}, " +
                          "{\"index\": 1, \"score\": 0.5}, " +
                          "{\"index\": 2, \"score\": 1.0}, " +
                          "{\"index\": 3, \"score\": 1.5}, " +
                          "{\"index\": 4, \"score\": -0.5}]";
            server.setChatHandler(server.successChatHandler(json));

            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("A", "B", "C", "D", "E"));

            for (RelevancyScore score : scores) {
                assertTrue(score.getScore() >= 0.0,
                    "Score must be >= 0.0, got: " + score.getScore());
                assertTrue(score.getScore() <= 1.0,
                    "Score must be <= 1.0, got: " + score.getScore());
                assertTrue(Double.isFinite(score.getScore()),
                    "Score must be finite, got: " + score.getScore());
            }
        }

        @Test
        @DisplayName("All returned indices are in valid range")
        void allIndicesInValidRange() {
            // Return indices including edge cases
            String json = "[{\"index\": 0, \"score\": 0.5}, " +
                          "{\"index\": 100, \"score\": 0.5}, " +
                          "{\"index\": -5, \"score\": 0.5}]";
            server.setChatHandler(server.successChatHandler(json));

            List<String> candidates = List.of("A", "B", "C");
            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            for (RelevancyScore score : scores) {
                assertTrue(score.getIndex() >= 0,
                    "Index must be >= 0, got: " + score.getIndex());
                assertTrue(score.getIndex() < candidates.size(),
                    "Index must be < candidates.size(), got: " + score.getIndex());
            }
        }

        @Test
        @DisplayName("Candidate text association is correct")
        void candidateTextAssociationCorrect() {
            server.setChatHandler(server.successRelevancyHandler(0.9, 0.8, 0.7));

            List<String> candidates = List.of("First document", "Second document", "Third document");
            List<RelevancyScore> scores = client.scoreRelevancy("query", candidates);

            assertEquals("First document", scores.get(0).getCandidateText());
            assertEquals("Second document", scores.get(1).getCandidateText());
            assertEquals("Third document", scores.get(2).getCandidateText());
        }

        @Test
        @DisplayName("meetsThreshold works correctly after parsing")
        void meetsThresholdWorks() {
            server.setChatHandler(server.successRelevancyHandler(0.9, 0.3, 0.5));

            List<RelevancyScore> scores = client.scoreRelevancy("query",
                List.of("A", "B", "C"));

            assertTrue(scores.get(0).meetsThreshold(0.8));   // 0.9 >= 0.8
            assertFalse(scores.get(1).meetsThreshold(0.8));  // 0.3 < 0.8
            assertFalse(scores.get(2).meetsThreshold(0.8));  // 0.5 < 0.8
        }
    }

    // ==================== Thread Safety ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Conquent parsing is thread-safe")
        void concurrentParsing() throws InterruptedException {
            server.setChatHandler(server.successRelevancyHandler(0.75));

            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];
            List<RelevancyScore>[] results = new List[numThreads];
            Exception[] errors = new Exception[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        results[index] = client.scoreRelevancy("query", List.of("candidate"));
                    } catch (Exception e) {
                        errors[index] = e;
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // Verify no errors
            for (int i = 0; i < numThreads; i++) {
                assertNull(errors[i], "Thread " + i + " threw exception: " + errors[i]);
            }

            // Verify consistent results
            for (int i = 0; i < numThreads; i++) {
                assertEquals(1, results[i].size());
                assertEquals(0.75, results[i].get(0).getScore(), 0.001);
            }
        }
    }

    // ==================== Real-World Response Patterns ====================

    @Nested
    @DisplayName("Real-World Response Patterns")
    class RealWorldPatternsTests {

        @Test
        @DisplayName("GPT-4 style response with thinking")
        void gpt4StyleResponse() {
            String response = """
                I'll analyze each candidate for relevancy to the query about machine learning.

                ```json
                [
                  {"index": 0, "score": 0.95},
                  {"index": 1, "score": 0.42},
                  {"index": 2, "score": 0.78}
                ]
                ```

                The first candidate is highly relevant because it directly discusses ML algorithms.
                """;
            server.setChatHandler(server.successChatHandler(response));

            List<RelevancyScore> scores = client.scoreRelevancy("machine learning",
                List.of("A", "B", "C"));

            assertEquals(3, scores.size());
            assertEquals(0.95, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Claude style response with explanation")
        void claudeStyleResponse() {
            String response = """
                Based on my analysis:

                [{"index": 0, "score": 0.88}, {"index": 1, "score": 0.35}]

                The first passage contains the key information you're looking for.
                """;
            server.setChatHandler(server.successChatHandler(response));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.88, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Open-source LLM response (Llama/Mistral style)")
        void openSourceLlmStyleResponse() {
            // Response without [INST] tags since they might interfere with JSON extraction
            String response = """
                Here are my scores:

                [{"index": 0, "score": 0.92}, {"index": 1, "score": 0.15}]
                """;
            server.setChatHandler(server.successChatHandler(response));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("A", "B"));

            assertEquals(2, scores.size());
            assertEquals(0.92, scores.get(0).getScore(), 0.001);
        }

        @Test
        @DisplayName("Minimal response (just JSON array)")
        void minimalResponse() {
            server.setChatHandler(server.successChatHandler("[{\"index\":0,\"score\":0.5}]"));

            List<RelevancyScore> scores = client.scoreRelevancy("query", List.of("candidate"));

            assertEquals(1, scores.size());
            assertEquals(0.5, scores.get(0).getScore(), 0.001);
        }
    }
}
