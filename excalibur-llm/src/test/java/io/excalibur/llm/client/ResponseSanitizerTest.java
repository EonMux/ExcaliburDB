package io.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResponseSanitizer}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Input: any string (possibly null, possibly malformed)</li>
 *   <li>Output: valid JSON array string OR null (if unparseable)</li>
 *   <li>Handles: markdown fences, leading/trailing whitespace, commentary before/after JSON</li>
 *   <li>Must NOT throw on malformed input — returns null</li>
 * </ul>
 */
@Timeout(value = 10)
class ResponseSanitizerTest {

    private final ResponseSanitizer sanitizer = new ResponseSanitizer();

    // ============= Success Path Tests =============

    @Test
    void sanitize_plainJsonArray_returnsSameArray() {
        String input = "[{\"index\": 0, \"score\": 0.85}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.85}]", result);
    }

    @Test
    void sanitize_plainJsonArrayWithMultipleItems_returnsSameArray() {
        String input = "[{\"index\": 0, \"score\": 0.9}, {\"index\": 1, \"score\": 0.3}, {\"index\": 2, \"score\": 0.65}]";

        String result = sanitizer.sanitize(input);

        assertEquals(input, result);
    }

    @Test
    void sanitize_emptyJsonArray_returnsEmptyArray() {
        String input = "[]";

        String result = sanitizer.sanitize(input);

        assertEquals("[]", result);
    }

    // ============= Markdown Fence Tests =============

    @Test
    void sanitize_markdownFenceWithJson_stripsFence() {
        String input = "```json\n[{\"index\": 0, \"score\": 0.75}]\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.75}]", result);
    }

    @Test
    void sanitize_markdownFenceUppercaseJson_stripsFence() {
        String input = "```JSON\n[{\"index\": 0, \"score\": 0.75}]\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.75}]", result);
    }

    @Test
    void sanitize_markdownFenceNoLanguage_stripsFence() {
        String input = "```\n[{\"index\": 0, \"score\": 0.6}]\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.6}]", result);
    }

    @Test
    void sanitize_markdownFenceWithTrailingSpace_stripsFence() {
        String input = "```json   \n[{\"index\": 0, \"score\": 0.5}]\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    // ============= Commentary Handling Tests =============

    @Test
    void sanitize_commentaryBeforeJson_extractsJsonArray() {
        String input = "Here are the scores:\n[{\"index\": 0, \"score\": 0.8}]\nHope that helps!";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.8}]", result);
    }

    @Test
    void sanitize_commentaryAfterJson_extractsJsonArray() {
        String input = "[{\"index\": 0, \"score\": 0.7}]\nThat's all the results.";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.7}]", result);
    }

    @Test
    void sanitize_commentaryBeforeAndAfterJson_extractsJsonArray() {
        String input = "Let me analyze these for you.\n[{\"index\": 0, \"score\": 0.9}]\nAnalysis complete.";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.9}]", result);
    }

    @Test
    void sanitize_markdownFenceWithCommentary_extractsJsonArray() {
        String input = "Here's my response:\n```json\n[{\"index\": 0, \"score\": 0.85}]\n```\nLet me know if you need more!";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.85}]", result);
    }

    // ============= Whitespace Handling Tests =============

    @Test
    void sanitize_leadingWhitespace_trimsAndReturnsArray() {
        String input = "   \n  [{\"index\": 0, \"score\": 0.5}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_trailingWhitespace_trimsAndReturnsArray() {
        String input = "[{\"index\": 0, \"score\": 0.5}]  \n  ";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_windowsLineEndings_handlesCorrectly() {
        String input = "```json\r\n[{\"index\": 0, \"score\": 0.75}]\r\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.75}]", result);
    }

    @Test
    void sanitize_mixedLineEndings_handlesCorrectly() {
        String input = "```json\n[{\"index\": 0, \"score\": 0.75}]\r\n```";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.75}]", result);
    }

    // ============= Null and Empty Input Tests =============

    @Test
    void sanitize_nullInput_returnsNull() {
        String result = sanitizer.sanitize(null);

        assertNull(result, "Null input should return null");
    }

    @Test
    void sanitize_emptyString_returnsNull() {
        String result = sanitizer.sanitize("");

        assertNull(result, "Empty string should return null");
    }

    @Test
    void sanitize_whitespaceOnly_returnsNull() {
        String result = sanitizer.sanitize("   \n\t  ");

        assertNull(result, "Whitespace-only string should return null");
    }

    @Test
    void sanitize_tabOnly_returnsNull() {
        String result = sanitizer.sanitize("\t\t\t");

        assertNull(result, "Tab-only string should return null");
    }

    // ============= Malformed Input Tests =============

    @Test
    void sanitize_noJsonArray_returnsNull() {
        String input = "This is just plain text with no JSON";

        String result = sanitizer.sanitize(input);

        assertNull(result, "Text without JSON array should return null");
    }

    @Test
    void sanitize_jsonObjectNotArray_returnsNull() {
        String input = "{\"index\": 0, \"score\": 0.5}";

        String result = sanitizer.sanitize(input);

        assertNull(result, "JSON object (not array) should return null");
    }

    @Test
    void sanitize_incompleteJsonArrayNoClosingBracket_returnsNull() {
        // The regex requires a closing ] to match
        // Input without closing bracket returns null
        String input = "[{\"index\": 0";

        String result = sanitizer.sanitize(input);

        // No closing bracket means no valid array pattern match
        assertNull(result, "Incomplete array without closing bracket should return null");
    }

    @Test
    void sanitize_brokenJsonWithBrackets_extractsBetweenBrackets() {
        String input = "[this is not valid json but has brackets]";

        String result = sanitizer.sanitize(input);

        assertEquals("[this is not valid json but has brackets]", result);
    }

    @Test
    void sanitize_multipleArrays_extractsFirstArray() {
        String input = "[1, 2, 3] and [4, 5, 6]";

        String result = sanitizer.sanitize(input);

        // Non-greedy matches the first complete array
        assertEquals("[1, 2, 3]", result);
    }

    @Test
    void sanitize_nestedArrays_extractsFirstCompleteArray() {
        String input = "[[1, 2], [3, 4]]";

        String result = sanitizer.sanitize(input);

        // The whole outer array, because brackets are matched by depth. The previous non-greedy
        // pattern stopped at the first closing bracket and returned "[[1, 2]", which is not
        // valid JSON and only ever parsed to nothing.
        assertEquals("[[1, 2], [3, 4]]", result);
    }

    // ============= Complex Real-World LLM Responses =============

    @Test
    void sanitize_realWorldResponse1_extractsCorrectly() {
        String input = """
            Based on my analysis of the candidates, here are the relevancy scores:

            ```json
            [
              {"index": 0, "score": 0.92},
              {"index": 1, "score": 0.45},
              {"index": 2, "score": 0.78}
            ]
            ```

            The first passage is most relevant because it directly addresses the query topic.
            """;

        String result = sanitizer.sanitize(input);

        assertNotNull(result);
        assertTrue(result.contains("\"index\": 0"));
        assertTrue(result.contains("\"score\": 0.92"));
    }

    @Test
    void sanitize_realWorldResponse2_extractsCorrectly() {
        String input = """
            Sure! I'll score each candidate for you.

            [{"index": 0, "score": 0.85}, {"index": 1, "score": 0.30}]

            Let me know if you need any clarification!
            """;

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.85}, {\"index\": 1, \"score\": 0.30}]", result);
    }

    @Test
    void sanitize_realWorldResponse3_noMarkdown_extractsCorrectly() {
        String input = """
            Here are the relevancy scores as requested:

            [{"index": 0, "score": 0.95}]
            """;

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.95}]", result);
    }

    // ============= Thread Safety Tests =============

    @Test
    void sanitize_concurrentCalls_threadSafe() throws InterruptedException {
        String[] inputs = {
            "[{\"index\": 0, \"score\": 0.1}]",
            "```json\n[{\"index\": 1, \"score\": 0.2}]\n```",
            "Text [{\"index\": 2, \"score\": 0.3}] more text",
            null,
            "",
            "   [{\"index\": 3, \"score\": 0.4}]   "
        };

        Thread[] threads = new Thread[10];
        String[][] results = new String[10][inputs.length];

        for (int t = 0; t < 10; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < inputs.length; i++) {
                    results[threadIndex][i] = sanitizer.sanitize(inputs[i]);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all threads got consistent results
        for (int t = 1; t < 10; t++) {
            for (int i = 0; i < inputs.length; i++) {
                assertEquals(results[0][i], results[t][i],
                    "Thread " + t + " got different result for input " + i);
            }
        }
    }

    // ============= Static Method Tests =============

    @Test
    void sanitizeStatic_validInput_returnsSanitized() {
        String input = "```json\n[{\"index\": 0, \"score\": 0.5}]\n```";

        String result = ResponseSanitizer.sanitizeStatic(input);

        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    @Test
    void sanitizeStatic_nullInput_returnsNull() {
        String result = ResponseSanitizer.sanitizeStatic(null);

        assertNull(result);
    }

    @Test
    void sanitizeStatic_multipleCalls_usesSharedInstance() {
        // Verify the static method works correctly multiple times
        // (tests the lazy singleton holder pattern)
        String result1 = ResponseSanitizer.sanitizeStatic("[1, 2, 3]");
        String result2 = ResponseSanitizer.sanitizeStatic("[4, 5, 6]");
        String result3 = ResponseSanitizer.sanitizeStatic("no array here");

        assertEquals("[1, 2, 3]", result1);
        assertEquals("[4, 5, 6]", result2);
        assertNull(result3);
    }

    // ============= Domain Correctness Boundary Tests =============

    @Test
    void sanitize_minimalValidArray_returnsArray() {
        String input = "[]";

        String result = sanitizer.sanitize(input);

        assertEquals("[]", result);
    }

    @Test
    void sanitize_singleElementArray_returnsArray() {
        String input = "[1]";

        String result = sanitizer.sanitize(input);

        assertEquals("[1]", result);
    }

    @Test
    void sanitize_arrayWithSpecialCharacters_returnsArray() {
        String input = "[{\"text\": \"Hello\\nWorld\", \"score\": 0.5}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"text\": \"Hello\\nWorld\", \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_arrayWithUnicode_returnsArray() {
        String input = "[{\"text\": \"日本語\", \"score\": 0.5}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"text\": \"日本語\", \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_arrayWithEscapedQuotes_returnsArray() {
        String input = "[{\"text\": \"He said \\\"hello\\\"\", \"score\": 0.5}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"text\": \"He said \\\"hello\\\"\", \"score\": 0.5}]", result);
    }

    // ============= Edge Cases =============

    @Test
    void sanitize_markdownFenceAtStartOnly_stripsOpeningFence() {
        String input = "```json\n[{\"index\": 0, \"score\": 0.5}]";

        String result = sanitizer.sanitize(input);

        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_closingFenceOnly_returnsNull() {
        String input = "```\nnot an array\n```";

        String result = sanitizer.sanitize(input);

        assertNull(result, "Fenced content without array should return null");
    }

    @Test
    void sanitize_arrayInsideJsonObject_extractsArray() {
        String input = "{\"results\": [{\"index\": 0, \"score\": 0.5}]}";

        String result = sanitizer.sanitize(input);

        // Extracts the inner array
        assertEquals("[{\"index\": 0, \"score\": 0.5}]", result);
    }

    @Test
    void sanitize_emptyArrayWithCommentary_returnsEmptyArray() {
        String input = "No relevant results found:\n[]\nEnd of response.";

        String result = sanitizer.sanitize(input);

        assertEquals("[]", result);
    }

    @Test
    @DisplayName("prose containing brackets does not shadow the score array")
    void prefersTheBracketPairCarryingScores() {
        // A model that labels its answer puts a bracket pair ahead of the real array. Returning
        // the first pair produced something that passed for a JSON array and then parsed to no
        // scores at all - a query answered with nothing, reported as a success.
        String response = "Scores [0-1 scale]:\n[{\"index\": 0, \"score\": 0.9}]";

        assertEquals("[{\"index\": 0, \"score\": 0.9}]",
            ResponseSanitizer.sanitizeStatic(response));
    }

    @Test
    @DisplayName("falls back to the first bracket pair when none carries scores")
    void fallsBackToTheFirstPair() {
        assertEquals("[1, 2, 3]", ResponseSanitizer.sanitizeStatic("values: [1, 2, 3]"));
    }
}
