package io.excalibur.llm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Extracts the JSON array a model was asked for from the prose, fences and commentary it
 * actually replied with.
 *
 * <h2>Why the scan is hand-written</h2>
 * <p>The obvious expression of "the first bracket pair" is a non-greedy regex, and it was one:
 * {@code \[[\s\S]*?\]}. Its cost is quadratic in the input, because every opening bracket is a
 * fresh start position from which the engine scans to the end of the input looking for a close
 * that a hostile - or merely truncated - reply never provides. Measured on this JVM: 91ms at
 * 8,000 characters, 366ms at 16,000, 1.5s at 32,000, 6.1s at 64,000. The input is a model
 * response bounded only by the HTTP client's cap, tens of megabytes, which puts the same scan
 * into the range of days and pins a request thread for all of it. Anything able to make the
 * node call its model could therefore stop it serving.</p>
 *
 * <p>A single forward pass has no start positions to retry, so its cost is linear and cannot be
 * provoked. It is also more correct than the pattern it replaces: it tracks JSON string
 * literals, so a bracket inside {@code "a ] b"} no longer ends the array half way through an
 * object.</p>
 *
 * <h2>Why the input is capped as well</h2>
 * <p>Linear is not the same as free, and a reply orders of magnitude larger than any score array
 * is not an answer to the question that was asked - it is something to refuse rather than to
 * search. The cap keeps the work per request bounded no matter how the HTTP response limit is
 * configured, and an over-cap reply reads as unparseable, which the caller already turns into a
 * parse error and a fallback to the vector ranking.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and immutable; a single instance may be shared across threads.</p>
 */
public final class ResponseSanitizer {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseSanitizer.class);

    /**
     * The largest reply this will look inside, in characters.
     *
     * <p>One score object runs to roughly thirty characters, so a megabyte covers a batch of
     * tens of thousands of candidates with room left for the commentary models insist on adding.
     * A reply past it is not a score array that grew; it is a model that ignored the
     * instruction, and mining it for a bracket pair would be guessing.</p>
     */
    public static final int MAX_INPUT_CHARS = 1024 * 1024;

    /**
     * Opening markdown fence, with the language tag models vary on.
     *
     * <p>Every quantifier here is greedy over a single character class and is followed only by
     * optional ones, so nothing the engine tries can fail and force it to retry: the match cost
     * is linear in the input.</p>
     */
    private static final Pattern MARKDOWN_FENCE_START = Pattern.compile("```(?:json|JSON)?\\s*\\r?\\n?");

    /** Closing markdown fence. */
    private static final Pattern MARKDOWN_FENCE_END = Pattern.compile("\\r?\\n?```");

    /** The key that marks a bracket pair as the score array rather than incidental prose. */
    private static final String SCORE_KEY = "\"score\"";

    /**
     * Creates a sanitizer.
     *
     * <p>Instances hold no state; {@link #sanitizeStatic(String)} exists so callers need not
     * allocate one.</p>
     */
    public ResponseSanitizer() {
        // Stateless
    }

    /**
     * Returns the JSON array carried by a model reply, or null if it carries none.
     *
     * <p>Handles the shapes models actually produce: a bare array, an array inside markdown
     * fences, an array surrounded by commentary, and any mixture of line endings. Where a reply
     * contains several bracket pairs the one carrying a {@code "score"} key wins, because a
     * model that prefaces its answer with something like "Scores [0-1 scale]:" puts a decoy pair
     * ahead of the real array; the first pair is the fallback when none carries scores.</p>
     *
     * <p>Never throws. A reply that cannot be read, or that is too large to be worth reading,
     * returns null, which the caller reports as a parse error rather than as an empty result -
     * an empty result would be indistinguishable from a model that scored nothing highly.</p>
     *
     * @param content Raw content from the model, possibly null, malformed or hostile
     * @return The extracted JSON array, or null if none could be found
     */
    public String sanitize(String content) {
        if (content == null) {
            LOG.debug("Cannot sanitize null content");
            return null;
        }

        if (content.isBlank()) {
            LOG.debug("Cannot sanitize blank content");
            return null;
        }

        if (content.length() > MAX_INPUT_CHARS) {
            LOG.warn("Refusing to sanitize a {} character response; the limit is {}",
                content.length(), MAX_INPUT_CHARS);
            return null;
        }

        String cleaned = stripMarkdownFences(content.trim());

        String extracted = extractJsonArray(cleaned);
        if (extracted != null) {
            LOG.debug("Extracted JSON array of length {}", extracted.length());
            return extracted;
        }

        if (looksLikeJsonArray(cleaned)) {
            LOG.debug("Content is itself a JSON array after fence stripping");
            return cleaned;
        }

        LOG.debug("No JSON array found in content (length={})", content.length());
        return null;
    }

    /**
     * Reports whether the content is bracketed as an array at both ends.
     *
     * <p>A structural check only; it is the last resort for a reply the forward scan could not
     * resolve, such as one whose brackets do not balance.</p>
     *
     * @param content Content to check, never null
     * @return true if the content opens and closes as an array
     */
    private boolean looksLikeJsonArray(String content) {
        return content.startsWith("[") && content.endsWith("]");
    }

    /**
     * Removes markdown code fences.
     *
     * @param content Content to clean, never null
     * @return The content without fences, trimmed
     */
    private String stripMarkdownFences(String content) {
        String cleaned = MARKDOWN_FENCE_START.matcher(content).replaceAll("");
        cleaned = MARKDOWN_FENCE_END.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * Finds the JSON array in the content in a single forward pass.
     *
     * <p>Each balanced bracket pair is examined once and the scan resumes after it, so no
     * character is visited more than a constant number of times regardless of how the brackets
     * are arranged. An unbalanced trailing bracket costs one scan to the end of the input and
     * ends the search, rather than being retried from every position after it.</p>
     *
     * @param content Content to search, never null
     * @return The chosen array, or null if no balanced bracket pair exists
     */
    private String extractJsonArray(String content) {
        String first = null;
        int cursor = 0;

        while (cursor < content.length()) {
            int open = content.indexOf('[', cursor);
            if (open < 0) {
                break;
            }

            int close = findMatchingBracket(content, open);
            if (close < 0) {
                // Nothing from this bracket onward balances, so no later bracket can either.
                break;
            }

            String candidate = content.substring(open, close + 1);
            if (first == null) {
                first = candidate;
            }
            if (carriesScoreKey(candidate)) {
                return candidate;
            }

            cursor = close + 1;
        }

        return first;
    }

    /**
     * Returns the index of the bracket closing the one at {@code open}, or -1 if unbalanced.
     *
     * <p>String literals are tracked so that a bracket inside a quoted value does not change the
     * depth, and backslash escapes are honoured so that a quote inside a string does not end it.
     * Without this, a perfectly ordinary score object carrying a bracket in its text was cut in
     * half and the remainder handed on as if it were an array.</p>
     *
     * @param content   The text being scanned
     * @param open      Index of the opening bracket
     * @return Index of the matching closing bracket, or -1
     */
    private int findMatchingBracket(String content, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = open; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Reports whether a bracket pair contains a {@code "score"} key.
     *
     * <p>Scanned rather than matched with a pattern so that the cost stays proportional to the
     * candidate, which matters because every candidate in a reply is tested.</p>
     *
     * @param candidate The bracket pair to test
     * @return true if a {@code "score"} key followed by a colon appears in it
     */
    private boolean carriesScoreKey(String candidate) {
        int at = candidate.indexOf(SCORE_KEY);
        while (at >= 0) {
            int after = at + SCORE_KEY.length();
            while (after < candidate.length() && Character.isWhitespace(candidate.charAt(after))) {
                after++;
            }
            if (after < candidate.length() && candidate.charAt(after) == ':') {
                return true;
            }
            at = candidate.indexOf(SCORE_KEY, at + 1);
        }
        return false;
    }

    /**
     * Sanitizes without requiring the caller to hold an instance.
     *
     * @param content Raw content from the model, possibly null or malformed
     * @return The extracted JSON array, or null if none could be found
     */
    public static String sanitizeStatic(String content) {
        return Holder.INSTANCE.sanitize(content);
    }

    /**
     * Holds the shared instance, created on first use.
     */
    private static final class Holder {
        static final ResponseSanitizer INSTANCE = new ResponseSanitizer();
    }
}
