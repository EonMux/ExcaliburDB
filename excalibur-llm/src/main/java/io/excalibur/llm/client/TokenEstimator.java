package io.excalibur.llm.client;

/**
 * Token estimator for LLM context window management.
 *
 * <p>Provides a lightweight approximation of token count from text using
 * the formula: {@code estimatedTokens = characterCount / 4.0}.</p>
 *
 * <h2>Rationale</h2>
 * <p>This approximation is based on empirical observations that most
 * tokenizers (including GPT-style BPE tokenizers) produce approximately
 * 1 token per 4 characters for typical English text. This ratio varies:</p>
 * <ul>
 *   <li>English text: ~4 characters/token</li>
 *   <li>Code: ~3-4 characters/token (more special characters)</li>
 *   <li>Other languages: may vary significantly</li>
 * </ul>
 *
 * <p>This estimator prioritizes speed over precision. For production use
 * with critical token limits, consider adding a safety margin of 10-20%.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and all methods are static. It is inherently
 * thread-safe and can be called from any thread without synchronization.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Input: any string (null handled gracefully)</li>
 *   <li>Output: always non-negative integer (rounded up from division)</li>
 *   <li>Empty/null input returns 0</li>
 *   <li>Division factor is exactly 4.0</li>
 *   <li>Result is ceiling-rounded to avoid underestimation</li>
 * </ul>
 *
 * @see LLMHttpClient
 * @see excalibur.llm.client.LLMClientConfig#getMaxBatchTokens()
 */
public final class TokenEstimator {

    /**
     * Characters per token ratio for approximation.
     * Domain correctness: this is the exact divisor specified in the Phase 7 spec.
     */
    private static final double CHARACTERS_PER_TOKEN = 4.0;

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private TokenEstimator() {
        throw new UnsupportedOperationException("TokenEstimator is a utility class and cannot be instantiated");
    }

    /**
     * Estimates the token count for a given text.
     *
     * <p>Uses the approximation: {@code ceiling(characterCount / 4.0)}.</p>
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null input returns 0</li>
     *   <li>Empty string returns 0</li>
     *   <li>Non-empty input returns at least 1</li>
     *   <li>Result is always a non-negative integer</li>
     *   <li>Uses ceiling to avoid underestimation (safer for limit checks)</li>
     * </ul>
     *
     * @param text The text to estimate tokens for (may be null)
     * @return Estimated token count (always non-negative)
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int charCount = text.length();
        // Use ceiling to avoid underestimating - safer for context window limits
        return (int) Math.ceil(charCount / CHARACTERS_PER_TOKEN);
    }

    /**
     * Estimates the total token count for multiple texts.
     *
     * <p>Sums the character counts of all texts, then applies the
     * approximation formula once. This is more accurate than summing
     * individual estimates due to ceiling rounding.</p>
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null or empty array returns 0</li>
     *   <li>Null elements within array are treated as empty strings</li>
     *   <li>Result is always a non-negative integer</li>
     * </ul>
     *
     * @param texts Array of texts to estimate tokens for (may be null or contain nulls)
     * @return Estimated total token count (always non-negative)
     */
    public static int estimateTotalTokens(String[] texts) {
        if (texts == null || texts.length == 0) {
            return 0;
        }

        int totalChars = 0;
        for (String text : texts) {
            if (text != null) {
                totalChars += text.length();
            }
        }

        if (totalChars == 0) {
            return 0;
        }

        return (int) Math.ceil(totalChars / CHARACTERS_PER_TOKEN);
    }

    /**
     * Estimates the total token count for a list of texts.
     *
     * <p>Sums the character counts of all texts, then applies the
     * approximation formula once. This is more accurate than summing
     * individual estimates due to ceiling rounding.</p>
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null or empty list returns 0</li>
     *   <li>Null elements within list are treated as empty strings</li>
     *   <li>Result is always a non-negative integer</li>
     * </ul>
     *
     * @param texts List of texts to estimate tokens for (may be null or contain nulls)
     * @return Estimated total token count (always non-negative)
     */
    public static int estimateTotalTokens(java.util.List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }

        int totalChars = 0;
        for (String text : texts) {
            if (text != null) {
                totalChars += text.length();
            }
        }

        if (totalChars == 0) {
            return 0;
        }

        return (int) Math.ceil(totalChars / CHARACTERS_PER_TOKEN);
    }

    /**
     * Checks if a text would exceed a given token limit.
     *
     * <p>Convenience method for batch splitting logic.</p>
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null or empty text never exceeds limit</li>
     *   <li>Negative limit throws IllegalArgumentException</li>
     *   <li>Zero limit: only empty/null text passes</li>
     * </ul>
     *
     * @param text Text to check (may be null)
     * @param tokenLimit Maximum allowed tokens (must be non-negative)
     * @return true if estimated tokens exceed the limit
     * @throws IllegalArgumentException if tokenLimit is negative
     */
    public static boolean exceedsLimit(String text, int tokenLimit) {
        if (tokenLimit < 0) {
            throw new IllegalArgumentException("Token limit must be non-negative, got: " + tokenLimit);
        }
        return estimateTokens(text) > tokenLimit;
    }

    /**
     * Checks if the combined tokens of multiple texts would exceed a limit.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null or empty array never exceeds limit</li>
     *   <li>Negative limit throws IllegalArgumentException</li>
     * </ul>
     *
     * @param texts Array of texts to check (may be null)
     * @param tokenLimit Maximum allowed tokens (must be non-negative)
     * @return true if estimated total tokens exceed the limit
     * @throws IllegalArgumentException if tokenLimit is negative
     */
    public static boolean exceedsLimit(String[] texts, int tokenLimit) {
        if (tokenLimit < 0) {
            throw new IllegalArgumentException("Token limit must be non-negative, got: " + tokenLimit);
        }
        return estimateTotalTokens(texts) > tokenLimit;
    }

    /**
     * Checks if the combined tokens of a list of texts would exceed a limit.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Null or empty list never exceeds limit</li>
     *   <li>Negative limit throws IllegalArgumentException</li>
     * </ul>
     *
     * @param texts List of texts to check (may be null)
     * @param tokenLimit Maximum allowed tokens (must be non-negative)
     * @return true if estimated total tokens exceed the limit
     * @throws IllegalArgumentException if tokenLimit is negative
     */
    public static boolean exceedsLimit(java.util.List<String> texts, int tokenLimit) {
        if (tokenLimit < 0) {
            throw new IllegalArgumentException("Token limit must be non-negative, got: " + tokenLimit);
        }
        return estimateTotalTokens(texts) > tokenLimit;
    }

    /**
     * Returns the characters-per-token ratio used for estimation.
     *
     * <p>Useful for documentation or debugging.</p>
     *
     * @return The divisor used in token estimation (always 4.0)
     */
    public static double getCharactersPerTokenRatio() {
        return CHARACTERS_PER_TOKEN;
    }
}
