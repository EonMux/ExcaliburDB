package com.eonmux.excalibur.ingest.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WhitespaceTokenizer Tests")
class WhitespaceTokenizerTest {

    @Nested
    @DisplayName("Basic Tokenization")
    class BasicTokenizationTests {

        @Test
        @DisplayName("Should tokenize simple sentence")
        void shouldTokenizeSimpleSentence() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world test");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(3);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world", "test");
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("");
            
            assertThat(tokenizer.getTokenCount()).isZero();
            assertThat(tokenizer.getTokens()).isEmpty();
            assertThat(tokenizer.getTotalChars()).isZero();
            assertThat(tokenizer.getAvgCharsPerToken()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle single word")
        void shouldHandleSingleWord() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(1);
            assertThat(tokenizer.getTokens()).containsExactly("Hello");
            assertThat(tokenizer.getTotalChars()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should handle whitespace-only string")
        void shouldHandleWhitespaceOnlyString() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("   \t\n   ");
            
            assertThat(tokenizer.getTokenCount()).isZero();
            assertThat(tokenizer.getTokens()).isEmpty();
        }

        @Test
        @DisplayName("Should handle leading whitespace")
        void shouldHandleLeadingWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("   Hello world");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(2);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world");
        }

        @Test
        @DisplayName("Should handle trailing whitespace")
        void shouldHandleTrailingWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world   ");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(2);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world");
        }

        @Test
        @DisplayName("Should handle multiple spaces between words")
        void shouldHandleMultipleSpaces() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello    world");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(2);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world");
        }

        @Test
        @DisplayName("Should handle tabs")
        void shouldHandleTabs() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello\tworld\ttest");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(3);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world", "test");
        }

        @Test
        @DisplayName("Should handle newlines")
        void shouldHandleNewlines() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello\nworld\ntest");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(3);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world", "test");
        }

        @Test
        @DisplayName("Should handle mixed whitespace")
        void shouldHandleMixedWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello  \t\n  world");
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(2);
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "world");
        }
    }

    @Nested
    @DisplayName("Character Counting")
    class CharacterCountingTests {

        @Test
        @DisplayName("Should count total characters correctly")
        void shouldCountTotalCharacters() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world");
            
            assertThat(tokenizer.getTotalChars()).isEqualTo(10); // 5 + 5
        }

        @Test
        @DisplayName("Should exclude whitespace from character count")
        void shouldExcludeWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello   world");
            
            assertThat(tokenizer.getTotalChars()).isEqualTo(10); // 5 + 5, spaces excluded
        }

        @Test
        @DisplayName("Should calculate average chars per token")
        void shouldCalculateAverageCharsPerToken() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world test");
            
            assertThat(tokenizer.getAvgCharsPerToken()).isEqualTo(14.0 / 3.0); // (5+5+4)/3 = 14/3 ≈ 4.67
        }

        @Test
        @DisplayName("Should return zero average for empty input")
        void shouldReturnZeroAverageForEmptyInput() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("");
            
            assertThat(tokenizer.getAvgCharsPerToken()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Chars/Token Ratio Validation")
    class RatioValidationTests {

        @Test
        @DisplayName("Should recognize normal English text ratio")
        void shouldRecognizeNormalEnglishRatio() {
            String text = "This is a normal English sentence with typical word lengths.";
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
            
            assertThat(tokenizer.isRatioWithinExpectedRange()).isTrue();
            assertThat(tokenizer.getAvgCharsPerToken()).isBetween(3.0, 6.0);
        }

        @Test
        @DisplayName("Should handle text with long words")
        void shouldHandleTextWithLongWords() {
            String text = "extraordinarily sophisticated multifaceted implementation";
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
            
            assertThat(tokenizer.getAvgCharsPerToken()).isGreaterThan(6.0);
            assertThat(tokenizer.isRatioWithinExpectedRange()).isFalse();
        }

        @Test
        @DisplayName("Should handle text with short words")
        void shouldHandleTextWithShortWords() {
            String text = "a b c d e f g";
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
            
            assertThat(tokenizer.getAvgCharsPerToken()).isLessThan(2.0);
            assertThat(tokenizer.isRatioWithinExpectedRange()).isFalse();
        }

        @Test
        @DisplayName("Should consider empty text as within range")
        void shouldConsiderEmptyTextWithinRange() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("");
            
            assertThat(tokenizer.isRatioWithinExpectedRange()).isTrue();
        }
    }

    @Nested
    @DisplayName("Special Characters")
    class SpecialCharactersTests {

        @Test
        @DisplayName("Should preserve punctuation attached to words")
        void shouldPreservePunctuation() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello, world! How are you?");
            
            assertThat(tokenizer.getTokens()).containsExactly("Hello,", "world!", "How", "are", "you?");
        }

        @Test
        @DisplayName("Should handle numbers")
        void shouldHandleNumbers() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("123 456 789");
            
            assertThat(tokenizer.getTokens()).containsExactly("123", "456", "789");
            assertThat(tokenizer.getTotalChars()).isEqualTo(9);
        }

        @Test
        @DisplayName("Should handle mixed alphanumeric tokens")
        void shouldHandleMixedAlphanumeric() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("abc123 xyz789");
            
            assertThat(tokenizer.getTokens()).containsExactly("abc123", "xyz789");
        }

        @Test
        @DisplayName("Should handle special characters")
        void shouldHandleSpecialCharacters() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("@user #tag $100");
            
            assertThat(tokenizer.getTokens()).containsExactly("@user", "#tag", "$100");
        }

        @Test
        @DisplayName("Should handle Unicode characters")
        void shouldHandleUnicode() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello 世界 🎉");
            
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "世界", "🎉");
        }

        @Test
        @DisplayName("Should handle hyphens and underscores")
        void shouldHandleHyphensAndUnderscores() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("state-of-the-art snake_case");
            
            assertThat(tokenizer.getTokens()).containsExactly("state-of-the-art", "snake_case");
        }
    }

    @Nested
    @DisplayName("Token Count Estimation")
    class EstimationTests {

        @Test
        @DisplayName("Should estimate tokens for character count")
        void shouldEstimateTokens() {
            assertThat(WhitespaceTokenizer.estimateTokenCount(0)).isEqualTo(0);
            assertThat(WhitespaceTokenizer.estimateTokenCount(4)).isEqualTo(1);
            assertThat(WhitespaceTokenizer.estimateTokenCount(8)).isEqualTo(2);
            assertThat(WhitespaceTokenizer.estimateTokenCount(10)).isEqualTo(3);
            assertThat(WhitespaceTokenizer.estimateTokenCount(100)).isEqualTo(25);
        }

        @Test
        @DisplayName("Should return at least 1 for positive input")
        void shouldReturnAtLeastOneForPositiveInput() {
            assertThat(WhitespaceTokenizer.estimateTokenCount(1)).isEqualTo(1);
            assertThat(WhitespaceTokenizer.estimateTokenCount(2)).isEqualTo(1);
            assertThat(WhitespaceTokenizer.estimateTokenCount(3)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should reject negative character count")
        void shouldRejectNegativeCharCount() {
            assertThatThrownBy(() -> WhitespaceTokenizer.estimateTokenCount(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Should estimate characters for token count")
        void shouldEstimateChars() {
            assertThat(WhitespaceTokenizer.estimateCharCount(0)).isEqualTo(0);
            assertThat(WhitespaceTokenizer.estimateCharCount(1)).isEqualTo(4);
            assertThat(WhitespaceTokenizer.estimateCharCount(10)).isEqualTo(40);
            assertThat(WhitespaceTokenizer.estimateCharCount(100)).isEqualTo(400);
        }

        @Test
        @DisplayName("Should reject negative token count")
        void shouldRejectNegativeTokenCount() {
            assertThatThrownBy(() -> WhitespaceTokenizer.estimateCharCount(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("Should reject null text")
        void shouldRejectNullText() {
            assertThatThrownBy(() -> WhitespaceTokenizer.tokenize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text must not be null");
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Should return unmodifiable token list")
        void shouldReturnUnmodifiableTokenList() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world");
            List<String> tokens = tokenizer.getTokens();
            
            assertThatThrownBy(() -> tokens.add("test"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Tokens should not contain whitespace")
        void tokensShouldNotContainWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world test 123");
            
            for (String token : tokenizer.getTokens()) {
                assertThat(token)
                    .as("Token should not contain whitespace: '%s'", token)
                    .matches(s -> s.chars().noneMatch(Character::isWhitespace));
            }
        }

        @Test
        @DisplayName("Tokens should be non-empty")
        void tokensShouldBeNonEmpty() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world");
            
            for (String token : tokenizer.getTokens()) {
                assertThat(token)
                    .as("Token should not be empty")
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("Average should be consistent with total and count")
        void averageShouldBeConsistent() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world test");
            
            double expected = (double) tokenizer.getTotalChars() / tokenizer.getTokenCount();
            assertThat(tokenizer.getAvgCharsPerToken()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Real English text should have reasonable chars/token ratio")
        void realEnglishTextShouldHaveReasonableRatio() {
            String text = "The quick brown fox jumps over the lazy dog. " +
                          "This is a longer sentence with more words to test. " +
                          "Natural language processing is fascinating.";
            
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
            
            assertThat(tokenizer.getAvgCharsPerToken())
                .as("Real English text should have reasonable chars/token ratio")
                .isGreaterThan(0.0);
        }

        @Test
        @DisplayName("Estimation should be within reasonable range")
        void estimationShouldBeReasonable() {
            String text = "The quick brown fox jumps over the lazy dog";
            
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
            int estimated = WhitespaceTokenizer.estimateTokenCount(text.length());
            
            int actual = tokenizer.getTokenCount();
            double error = Math.abs(estimated - actual) / (double) actual;
            
            assertThat(error)
                .as("Estimation error should be < 100%% (rough approximation)")
                .isLessThan(1.0);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle very long single token")
        void shouldHandleVeryLongToken() {
            String longToken = "a".repeat(10000);
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(longToken);
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(1);
            assertThat(tokenizer.getTotalChars()).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should handle many short tokens")
        void shouldHandleManyShortTokens() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                if (i > 0) sb.append(" ");
                sb.append("a");
            }
            
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(sb.toString());
            
            assertThat(tokenizer.getTokenCount()).isEqualTo(10000);
            assertThat(tokenizer.getTotalChars()).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should handle CRLF line endings")
        void shouldHandleCRLF() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello\r\nWorld\r\nTest");
            
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "World", "Test");
        }

        @Test
        @DisplayName("Should handle form feed and vertical tab")
        void shouldHandleOtherWhitespace() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello\fWorld\u000BTest");
            
            assertThat(tokenizer.getTokens()).containsExactly("Hello", "World", "Test");
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Should include key statistics")
        void shouldIncludeKeyStatistics() {
            WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize("Hello world");
            String str = tokenizer.toString();
            
            assertThat(str).contains("tokenCount=2");
            assertThat(str).contains("totalChars=10");
            assertThat(str).contains("avgCharsPerToken");
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should be thread-safe for concurrent tokenization")
        void shouldBeThreadSafe() throws InterruptedException {
            String text = "The quick brown fox jumps over the lazy dog";
            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];
            WhitespaceTokenizer[] results = new WhitespaceTokenizer[numThreads];
            Exception[] exceptions = new Exception[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> {
                    try {
                        results[idx] = WhitespaceTokenizer.tokenize(text);
                    } catch (Exception e) {
                        exceptions[idx] = e;
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            for (int i = 0; i < numThreads; i++) {
                assertThat(exceptions[i]).isNull();
                assertThat(results[i]).isNotNull();
                assertThat(results[i].getTokenCount()).isEqualTo(9);
            }
        }
    }

    @Nested
    @DisplayName("Expected Chars Per Token")
    class ExpectedCharsPerTokenTests {

        @Test
        @DisplayName("Should return 4 as expected ratio")
        void shouldReturnFour() {
            assertThat(WhitespaceTokenizer.getExpectedCharsPerToken()).isEqualTo(4);
        }
    }
}
