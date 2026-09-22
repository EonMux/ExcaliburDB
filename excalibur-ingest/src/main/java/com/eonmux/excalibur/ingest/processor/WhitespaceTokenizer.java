package com.eonmux.excalibur.ingest.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Whitespace-based tokenizer for text chunking.
 *
 * <p>Implements simple, dependency-free tokenization by splitting on whitespace.
 * No NLP libraries required. Tokens are defined as maximal sequences of non-whitespace
 * characters.</p>
 *
 * <h2>Token Definition</h2>
 * <p>A token is any sequence of one or more non-whitespace characters. Whitespace
 * includes spaces, tabs, newlines, and any character where {@link Character#isWhitespace(char)}
 * returns true.</p>
 *
 * <h2>Character-to-Token Estimation</h2>
 * <p>For estimation purposes (before actual tokenization), English text typically 
 * averages ~4 characters per token. This is used by the {@link #estimateTokenCount(int)} 
 * method for quick approximations. Actual whitespace-based tokenization may differ 
 * due to punctuation being attached to words.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The tokenizer has no mutable state, and the
 * {@link #tokenize(String)} method can be called concurrently from multiple threads.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Tokens: non-null, non-empty strings with no leading/trailing whitespace</li>
 *   <li>Token count: always &ge; 0</li>
 *   <li>Estimation uses ~4 chars/token as a rough heuristic</li>
 *   <li>Preserves original character content (no normalization)</li>
 * </ul>
 */
public final class WhitespaceTokenizer {

    private static final int EXPECTED_CHARS_PER_TOKEN = 4;
    private static final double CHARS_PER_TOKEN_TOLERANCE = 2.0;

    private final List<String> tokens;
    private final int totalChars;
    private final double avgCharsPerToken;

    private WhitespaceTokenizer(List<String> tokens, int totalChars, double avgCharsPerToken) {
        this.tokens = Collections.unmodifiableList(tokens);
        this.totalChars = totalChars;
        this.avgCharsPerToken = avgCharsPerToken;
    }

    /**
     * Tokenizes the input text by splitting on whitespace.
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}. Consecutive
     * whitespace characters are treated as a single delimiter.</p>
     *
     * @param text the text to tokenize (non-null)
     * @return a tokenizer instance containing the tokens and statistics
     * @throws NullPointerException if text is null
     */
    public static WhitespaceTokenizer tokenize(String text) {
        Objects.requireNonNull(text, "Text must not be null");

        if (text.isEmpty()) {
            return new WhitespaceTokenizer(Collections.emptyList(), 0, 0.0);
        }

        List<String> tokens = new ArrayList<>();
        int totalChars = 0;
        int start = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isWhitespace = Character.isWhitespace(c);

            if (!isWhitespace && start < 0) {
                start = i;
            } else if (isWhitespace && start >= 0) {
                String token = text.substring(start, i);
                tokens.add(token);
                totalChars += token.length();
                start = -1;
            }
        }

        if (start >= 0) {
            String token = text.substring(start);
            tokens.add(token);
            totalChars += token.length();
        }

        double avgCharsPerToken = tokens.isEmpty() ? 0.0 : (double) totalChars / tokens.size();
        return new WhitespaceTokenizer(tokens, totalChars, avgCharsPerToken);
    }

    /**
     * Returns the list of tokens extracted from the text.
     *
     * @return unmodifiable list of tokens (never null, may be empty)
     */
    public List<String> getTokens() {
        return tokens;
    }

    /**
     * Returns the number of tokens in the tokenized text.
     *
     * @return token count (&ge; 0)
     */
    public int getTokenCount() {
        return tokens.size();
    }

    /**
     * Returns the total number of characters across all tokens.
     *
     * <p>This excludes whitespace characters that were used as delimiters.</p>
     *
     * @return total character count in tokens (&ge; 0)
     */
    public int getTotalChars() {
        return totalChars;
    }

    /**
     * Returns the average number of characters per token.
     *
     * <p>For English text, this typically falls in the range 3-6 characters/token.
     * The expected value is ~4 characters/token.</p>
     *
     * @return average characters per token, or 0.0 if no tokens
     */
    public double getAvgCharsPerToken() {
        return avgCharsPerToken;
    }

    /**
     * Checks if the actual character-to-token ratio is within expected bounds.
     *
     * <p>This compares the actual ratio against the estimation heuristic of ~4 chars/token.
     * Text with unusual tokenization patterns (e.g., code, URLs, or highly
     * technical content) may fall outside this range. This method is primarily
     * useful for diagnostic purposes.</p>
     *
     * @return true if the ratio is within expected bounds, false otherwise
     */
    public boolean isRatioWithinExpectedRange() {
        if (tokens.isEmpty()) {
            return true;
        }
        return Math.abs(avgCharsPerToken - EXPECTED_CHARS_PER_TOKEN) <= CHARS_PER_TOKEN_TOLERANCE;
    }

    /**
     * Estimates the number of tokens for a given character count.
     *
     * <p>Uses the standard ~4 chars/token heuristic. This is a quick estimation
     * that doesn't require actual tokenization.</p>
     *
     * @param charCount the number of characters (&ge; 0)
     * @return estimated token count (&ge; 1 for positive input)
     * @throws IllegalArgumentException if charCount is negative
     */
    public static int estimateTokenCount(int charCount) {
        if (charCount < 0) {
            throw new IllegalArgumentException("Character count must be non-negative");
        }
        if (charCount == 0) {
            return 0;
        }
        return Math.max(1, (charCount + EXPECTED_CHARS_PER_TOKEN - 1) / EXPECTED_CHARS_PER_TOKEN);
    }

    /**
     * Estimates the number of characters for a given token count.
     *
     * <p>Uses the standard ~4 chars/token heuristic.</p>
     *
     * @param tokenCount the number of tokens (&ge; 0)
     * @return estimated character count (&ge; 0)
     * @throws IllegalArgumentException if tokenCount is negative
     */
    public static int estimateCharCount(int tokenCount) {
        if (tokenCount < 0) {
            throw new IllegalArgumentException("Token count must be non-negative");
        }
        return tokenCount * EXPECTED_CHARS_PER_TOKEN;
    }

    /**
     * Returns the expected characters-per-token ratio.
     *
     * @return expected ratio (4)
     */
    public static int getExpectedCharsPerToken() {
        return EXPECTED_CHARS_PER_TOKEN;
    }

    @Override
    public String toString() {
        return "WhitespaceTokenizer{" +
               "tokenCount=" + tokens.size() +
               ", totalChars=" + totalChars +
               ", avgCharsPerToken=" + String.format("%.2f", avgCharsPerToken) +
               '}';
    }
}
