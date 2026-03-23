package io.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TokenEstimator}.
 *
 * <p>Tests cover all public methods with focus on:</p>
 * <ul>
 *   <li>Domain correctness: outputs in sensible ranges</li>
 *   <li>Edge cases: null, empty, single character</li>
 *   <li>Boundary conditions: exact multiples of 4 characters</li>
 *   <li>Error handling: negative token limits</li>
 * </ul>
 */
@DisplayName("TokenEstimator")
@Timeout(value = 10)
class TokenEstimatorTest {

    // ==================== estimateTokens(String) Tests ====================

    @Nested
    @DisplayName("estimateTokens(String)")
    class EstimateTokensString {

        @Test
        @DisplayName("returns 0 for null input")
        void nullInput_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTokens((String) null));
        }

        @Test
        @DisplayName("returns 0 for empty string")
        void emptyString_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTokens(""));
        }

        @Test
        @DisplayName("returns 1 for single character")
        void singleCharacter_returnsOne() {
            assertEquals(1, TokenEstimator.estimateTokens("a"));
        }

        @Test
        @DisplayName("returns 1 for 4 characters (exact multiple)")
        void fourCharacters_returnsOne() {
            assertEquals(1, TokenEstimator.estimateTokens("abcd"));
        }

        @Test
        @DisplayName("returns 2 for 5 characters (ceiling rounding)")
        void fiveCharacters_returnsTwo() {
            // 5 / 4.0 = 1.25, ceiling = 2
            assertEquals(2, TokenEstimator.estimateTokens("abcde"));
        }

        @Test
        @DisplayName("returns 2 for 8 characters (exact multiple)")
        void eightCharacters_returnsTwo() {
            assertEquals(2, TokenEstimator.estimateTokens("abcdefgh"));
        }

        @Test
        @DisplayName("returns 3 for 9 characters (ceiling rounding)")
        void nineCharacters_returnsThree() {
            // 9 / 4.0 = 2.25, ceiling = 3
            assertEquals(3, TokenEstimator.estimateTokens("abcdefghi"));
        }

        @Test
        @DisplayName("returns 250 for 1000 characters")
        void thousandCharacters_returns250() {
            String text = "a".repeat(1000);
            assertEquals(250, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("returns 251 for 1001 characters (ceiling rounding)")
        void thousandOneCharacters_returns251() {
            String text = "a".repeat(1001);
            // 1001 / 4.0 = 250.25, ceiling = 251
            assertEquals(251, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("handles whitespace correctly")
        void whitespaceCharacters_countedNormally() {
            assertEquals(1, TokenEstimator.estimateTokens("    ")); // 4 spaces = 1 token
            assertEquals(2, TokenEstimator.estimateTokens("     ")); // 5 spaces = 2 tokens
        }

        @Test
        @DisplayName("handles unicode characters (each char counted as 1)")
        void unicodeCharacters_countedAsSingleChars() {
            // Unicode characters are counted by Java String length
            // Emoji might be 2 chars in Java, so this tests that behavior
            String text = "你好世界"; // 4 Chinese characters
            assertEquals(1, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("handles newlines correctly")
        void newlines_countedNormally() {
            String text = "a\nb\nc\n"; // 6 characters
            // 6 / 4.0 = 1.5, ceiling = 2
            assertEquals(2, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("handles tabs correctly")
        void tabs_countedNormally() {
            String text = "\t\t\t\t"; // 4 tabs
            assertEquals(1, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("always returns non-negative")
        void alwaysNonNegative() {
            // Even with unusual inputs, should never return negative
            assertTrue(TokenEstimator.estimateTokens("") >= 0);
            assertTrue(TokenEstimator.estimateTokens("a") >= 0);
            assertTrue(TokenEstimator.estimateTokens("  ") >= 0);
        }
    }

    // ==================== estimateTotalTokens(String[]) Tests ====================

    @Nested
    @DisplayName("estimateTotalTokens(String[])")
    class EstimateTotalTokensArray {

        @Test
        @DisplayName("returns 0 for null array")
        void nullArray_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTotalTokens((String[]) null));
        }

        @Test
        @DisplayName("returns 0 for empty array")
        void emptyArray_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTotalTokens(new String[0]));
        }

        @Test
        @DisplayName("handles array with null elements")
        void arrayWithNulls_treatedAsEmpty() {
            String[] texts = {null, null, null};
            assertEquals(0, TokenEstimator.estimateTotalTokens(texts));
        }

        @Test
        @DisplayName("handles mixed null and non-null elements")
        void mixedNullAndNonNull() {
            String[] texts = {null, "abcd", null, "efgh", null};
            // 8 chars total / 4.0 = 2 tokens
            assertEquals(2, TokenEstimator.estimateTotalTokens(texts));
        }

        @Test
        @DisplayName("sums all characters before dividing")
        void sumsBeforeDividing() {
            String[] texts = {"ab", "ab", "ab", "ab"}; // 8 chars total
            assertEquals(2, TokenEstimator.estimateTotalTokens(texts));
        }

        @Test
        @DisplayName("applies ceiling to total (not individual)")
        void ceilingAppliedToTotal() {
            String[] texts = {"a", "b", "c", "d", "e"}; // 5 chars total
            // 5 / 4.0 = 1.25, ceiling = 2
            // If we summed individual estimates: 1+1+1+1+1 = 5 (incorrect)
            assertEquals(2, TokenEstimator.estimateTotalTokens(texts));
        }
    }

    // ==================== estimateTotalTokens(List) Tests ====================

    @Nested
    @DisplayName("estimateTotalTokens(List)")
    class EstimateTotalTokensList {

        @Test
        @DisplayName("returns 0 for null list")
        void nullList_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTotalTokens((List<String>) null));
        }

        @Test
        @DisplayName("returns 0 for empty list")
        void emptyList_returnsZero() {
            assertEquals(0, TokenEstimator.estimateTotalTokens(Collections.emptyList()));
        }

        @Test
        @DisplayName("handles list with null elements")
        void listWithNulls_treatedAsEmpty() {
            List<String> texts = Arrays.asList(null, null, null);
            assertEquals(0, TokenEstimator.estimateTotalTokens(texts));
        }

        @Test
        @DisplayName("handles mixed null and non-null elements")
        void mixedNullAndNonNull() {
            List<String> texts = Arrays.asList(null, "abcd", null, "efgh", null);
            // 8 chars total / 4.0 = 2 tokens
            assertEquals(2, TokenEstimator.estimateTotalTokens(texts));
        }

        @Test
        @DisplayName("works with typical candidate list")
        void typicalCandidateList() {
            List<String> candidates = List.of(
                "First candidate text here",
                "Second candidate is longer",
                "Third one"
            );
            // Total: 26 + 25 + 9 = 60 chars
            // 60 / 4.0 = 15 tokens
            assertEquals(15, TokenEstimator.estimateTotalTokens(candidates));
        }
    }

    // ==================== exceedsLimit(String, int) Tests ====================

    @Nested
    @DisplayName("exceedsLimit(String, int)")
    class ExceedsLimitString {

        @Test
        @DisplayName("null never exceeds limit")
        void null_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit((String) null, 0));
            assertFalse(TokenEstimator.exceedsLimit((String) null, 100));
        }

        @Test
        @DisplayName("empty string never exceeds limit")
        void empty_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit("", 0));
            assertFalse(TokenEstimator.exceedsLimit("", 100));
        }

        @Test
        @DisplayName("single char exceeds limit of 0")
        void singleChar_exceedsZeroLimit() {
            assertTrue(TokenEstimator.exceedsLimit("a", 0));
        }

        @Test
        @DisplayName("single char does not exceed limit of 1")
        void singleChar_doesNotExceedLimitOne() {
            assertFalse(TokenEstimator.exceedsLimit("a", 1));
        }

        @Test
        @DisplayName("4 chars does not exceed limit of 1")
        void fourChars_doesNotExceedLimitOne() {
            assertFalse(TokenEstimator.exceedsLimit("abcd", 1));
        }

        @Test
        @DisplayName("5 chars exceeds limit of 1 (ceiling rounding)")
        void fiveChars_exceedsLimitOne() {
            // 5 / 4.0 = 1.25 tokens, exceeds limit of 1
            assertTrue(TokenEstimator.exceedsLimit("abcde", 1));
        }

        @Test
        @DisplayName("throws for negative limit")
        void negativeLimit_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                TokenEstimator.exceedsLimit("test", -1));
        }

        @Test
        @DisplayName("accepts zero limit")
        void zeroLimit_accepted() {
            assertFalse(TokenEstimator.exceedsLimit("", 0));
            assertTrue(TokenEstimator.exceedsLimit("a", 0));
        }
    }

    // ==================== exceedsLimit(String[], int) Tests ====================

    @Nested
    @DisplayName("exceedsLimit(String[], int)")
    class ExceedsLimitArray {

        @Test
        @DisplayName("null array never exceeds limit")
        void nullArray_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit((String[]) null, 0));
            assertFalse(TokenEstimator.exceedsLimit((String[]) null, 100));
        }

        @Test
        @DisplayName("empty array never exceeds limit")
        void emptyArray_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit(new String[0], 0));
        }

        @Test
        @DisplayName("throws for negative limit")
        void negativeLimit_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                TokenEstimator.exceedsLimit(new String[]{"test"}, -1));
        }

        @Test
        @DisplayName("checks combined tokens")
        void checksCombinedTokens() {
            String[] texts = {"ab", "ab", "ab", "ab", "a"}; // 9 chars = 3 tokens
            assertFalse(TokenEstimator.exceedsLimit(texts, 3));
            assertTrue(TokenEstimator.exceedsLimit(texts, 2));
        }
    }

    // ==================== exceedsLimit(List, int) Tests ====================

    @Nested
    @DisplayName("exceedsLimit(List, int)")
    class ExceedsLimitList {

        @Test
        @DisplayName("null list never exceeds limit")
        void nullList_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit((List<String>) null, 0));
            assertFalse(TokenEstimator.exceedsLimit((List<String>) null, 100));
        }

        @Test
        @DisplayName("empty list never exceeds limit")
        void emptyList_neverExceedsLimit() {
            assertFalse(TokenEstimator.exceedsLimit(Collections.emptyList(), 0));
        }

        @Test
        @DisplayName("throws for negative limit")
        void negativeLimit_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                TokenEstimator.exceedsLimit(List.of("test"), -1));
        }

        @Test
        @DisplayName("checks combined tokens for batch scenario")
        void batchScenario() {
            // Simulate a batch of candidates that might need splitting
            List<String> candidates = List.of(
                "This is candidate one with some text",
                "This is candidate two with more text",
                "This is candidate three"
            );
            // Total ~90 chars = 23 tokens

            assertFalse(TokenEstimator.exceedsLimit(candidates, 100));
            assertFalse(TokenEstimator.exceedsLimit(candidates, 25));
            assertTrue(TokenEstimator.exceedsLimit(candidates, 20));
        }
    }

    // ==================== getCharactersPerTokenRatio() Tests ====================

    @Nested
    @DisplayName("getCharactersPerTokenRatio()")
    class GetCharactersPerTokenRatio {

        @Test
        @DisplayName("returns 4.0 as specified")
        void returnsFourPointZero() {
            assertEquals(4.0, TokenEstimator.getCharactersPerTokenRatio());
        }

        @Test
        @DisplayName("ratio is consistent with estimation")
        void ratioIsConsistent() {
            String text = "a".repeat(100);
            int tokens = TokenEstimator.estimateTokens(text);
            double expected = Math.ceil(text.length() / TokenEstimator.getCharactersPerTokenRatio());
            assertEquals((int) expected, tokens);
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("token estimation is deterministic")
        void deterministic() {
            String text = "Test string for deterministic behavior check";
            int first = TokenEstimator.estimateTokens(text);
            int second = TokenEstimator.estimateTokens(text);
            int third = TokenEstimator.estimateTokens(text);
            assertEquals(first, second);
            assertEquals(second, third);
        }

        @Test
        @DisplayName("larger text always produces >= tokens")
        void monotonic() {
            String shortText = "short";
            String longText = "this is a much longer text with more characters";

            assertTrue(TokenEstimator.estimateTokens(longText) >= TokenEstimator.estimateTokens(shortText));
        }

        @Test
        @DisplayName("estimation is reasonable for typical English text")
        void reasonableForEnglishText() {
            // A typical sentence
            String sentence = "The quick brown fox jumps over the lazy dog.";
            int tokens = TokenEstimator.estimateTokens(sentence);

            // The sentence has 44 characters
            // 44 / 4.0 = 11 tokens
            // Real tokenizer would give ~9-10 tokens
            // Our approximation should be close (within 50% margin)
            assertTrue(tokens >= 7 && tokens <= 15,
                "Token estimate should be reasonable, got: " + tokens);
        }

        @Test
        @DisplayName("handles max batch tokens default (4000) correctly")
        void handlesDefaultMaxBatchTokens() {
            // 4000 tokens * 4 chars/token = 16000 characters
            String text = "a".repeat(16000);
            assertEquals(4000, TokenEstimator.estimateTokens(text));

            // Just over the limit
            String overLimit = "a".repeat(16001);
            assertEquals(4001, TokenEstimator.estimateTokens(overLimit));
            assertTrue(TokenEstimator.exceedsLimit(overLimit, 4000));
        }

        @Test
        @DisplayName("constructor is private (utility class pattern)")
        void constructorIsPrivate() throws Exception {
            // Verify constructor is private
            var constructor = TokenEstimator.class.getDeclaredConstructor();
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                "Constructor should be private for utility class pattern");

            // The implementation intentionally does not throw - private modifier
            // alone prevents normal instantiation. Reflection can still access it,
            // but that's not a concern for the utility class pattern.
            constructor.setAccessible(true);
            // Should not throw - just verify it can be called
            Object instance = constructor.newInstance();
            assertNotNull(instance);
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("handles very long string without overflow")
        void veryLongString() {
            // 1 million characters = 250,000 tokens
            String text = "a".repeat(1_000_000);
            assertEquals(250_000, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("handles string with only whitespace")
        void onlyWhitespace() {
            assertEquals(1, TokenEstimator.estimateTokens("    ")); // 4 spaces
            assertEquals(2, TokenEstimator.estimateTokens("     ")); // 5 spaces
            assertEquals(0, TokenEstimator.estimateTokens("")); // empty
        }

        @Test
        @DisplayName("handles control characters")
        void controlCharacters() {
            // Control characters are still counted as characters
            String text = "\u0000\u0001\u0002\u0003"; // 4 control chars
            assertEquals(1, TokenEstimator.estimateTokens(text));
        }

        @Test
        @DisplayName("handles emoji (may be surrogate pairs)")
        void emoji() {
            // In Java, some emoji are represented as surrogate pairs (2 chars)
            String emoji = "😀"; // This is 2 chars in Java (surrogate pair)
            assertEquals(1, TokenEstimator.estimateTokens(emoji));
        }

        @Test
        @DisplayName("handles mixed content")
        void mixedContent() {
            String mixed = "Hello 世界! 🌍\n\t" + "x".repeat(10);
            // Count all characters including surrogate pairs
            int tokenCount = TokenEstimator.estimateTokens(mixed);
            assertTrue(tokenCount > 0);
        }
    }
}
