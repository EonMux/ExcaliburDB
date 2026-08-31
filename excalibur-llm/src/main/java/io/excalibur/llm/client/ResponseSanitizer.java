package io.excalibur.llm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes LLM response content by extracting valid JSON arrays from potentially
 * malformed or markdown-wrapped responses.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is immutable and methods are stateless.
 * Instances can be safely shared across threads.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Input: any string (possibly null, possibly malformed)</li>
 *   <li>Output: valid JSON array string OR null (if unparseable)</li>
 *   <li>Handles: markdown fences, leading/trailing whitespace, commentary before/after JSON</li>
 *   <li>Regex pattern: {@code \[[\s\S]*?\]} (non-greedy match)</li>
 *   <li>Must NOT throw on malformed input — returns null and logs at DEBUG level</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ResponseSanitizer sanitizer = new ResponseSanitizer();
 *
 * // Handles markdown fences
 * String result = sanitizer.sanitize("```json\n[{\"score\": 0.5}]\n```");
 * // Returns: [{"score": 0.5}]
 *
 * // Handles commentary
 * result = sanitizer.sanitize("Here are the scores:\n[{\"score\": 0.8}]\nDone!");
 * // Returns: [{"score": 0.8}]
 *
 * // Returns null for unparseable input
 * result = sanitizer.sanitize("no json here");
 * // Returns: null
 * }</pre>
 */
public final class ResponseSanitizer {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseSanitizer.class);

    /**
     * Pattern to extract JSON array from response.
     * Uses non-greedy matching to find the first complete JSON array.
     * Matches: [ ... ] where ... can contain any characters including newlines.
     */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[[\\s\\S]*?\\]");

    /** The key that marks a bracket pair as the score array rather than incidental prose. */
    private static final Pattern SCORE_KEY = Pattern.compile("\"score\"\\s*:");

    /**
     * Pattern to match markdown code fences with optional language specifier.
     * Matches: ```json, ```JSON, ``` or just ``` followed by content and closing ```
     */
    private static final Pattern MARKDOWN_FENCE_START = Pattern.compile("```(?:json|JSON)?\\s*\\r?\\n?");

    /**
     * Pattern to match closing markdown fence.
     */
    private static final Pattern MARKDOWN_FENCE_END = Pattern.compile("\\r?\\n?```");

    /**
     * Creates a new ResponseSanitizer instance.
     */
    public ResponseSanitizer() {
        // Stateless constructor
    }

    /**
     * Sanitizes LLM response content by extracting a valid JSON array.
     *
     * <p>This method handles common LLM response patterns:</p>
     * <ul>
     *   <li>Plain JSON array: {@code [{"index": 0, "score": 0.85}]}</li>
     *   <li>Markdown-wrapped: {@code ```json\n[{"index": 0, "score": 0.85}]\n```}</li>
     *   <li>With commentary: {@code Here are the scores:\n[{"index": 0, "score": 0.85}]}</li>
     *   <li>Mixed whitespace: handles various newline styles (\n, \r\n)</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Input: any string (possibly null, possibly malformed)</li>
     *   <li>Output: valid JSON array string OR null (if unparseable)</li>
     *   <li>Never throws exceptions — returns null on failure</li>
     *   <li>Logs failures at DEBUG level for troubleshooting</li>
     * </ul>
     *
     * @param content Raw content from LLM response (may be null or malformed)
     * @return Cleaned JSON array string, or null if no valid array found
     */
    public String sanitize(String content) {
        // Handle null/empty input gracefully
        if (content == null) {
            LOG.debug("Cannot sanitize null content");
            return null;
        }

        if (content.isBlank()) {
            LOG.debug("Cannot sanitize blank content");
            return null;
        }

        String cleaned = content.trim();

        // Step 1: Strip markdown code fences
        cleaned = stripMarkdownFences(cleaned);

        // Step 2: Try to find a JSON array using non-greedy pattern
        String extracted = extractJsonArray(cleaned);

        if (extracted != null) {
            LOG.debug("Successfully extracted JSON array of length {}", extracted.length());
            return extracted;
        }

        // Step 3: Check if the whole content looks like an array (after fence stripping)
        if (looksLikeJsonArray(cleaned)) {
            LOG.debug("Content appears to be a JSON array after fence stripping");
            return cleaned;
        }

        // No valid JSON array found
        LOG.debug("No JSON array found in content (length={})", content.length());
        return null;
    }

    /**
     * Checks if the given content appears to be a valid JSON array.
     *
     * <p>Performs a lightweight structural check without full JSON parsing.</p>
     *
     * @param content Content to check (must not be null)
     * @return true if content starts with '[' and ends with ']'
     */
    private boolean looksLikeJsonArray(String content) {
        return content.startsWith("[") && content.endsWith("]");
    }

    /**
     * Strips markdown code fences from the content.
     *
     * <p>Handles various fence formats:</p>
     * <ul>
     *   <li>{@code ```json\n...\n```}</li>
     *   <li>{@code ```JSON\n...\n```}</li>
     *   <li>{@code ```\n...\n```}</li>
     * </ul>
     *
     * @param content Content to clean (must not be null)
     * @return Content with fences removed, trimmed
     */
    private String stripMarkdownFences(String content) {
        // Remove opening fence (```json, ```JSON, or just ```)
        String cleaned = MARKDOWN_FENCE_START.matcher(content).replaceAll("");

        // Remove closing fence
        cleaned = MARKDOWN_FENCE_END.matcher(cleaned).replaceAll("");

        return cleaned.trim();
    }

    /**
     * Extracts the JSON array from the content using regex matching.
     *
     * <p>Non-greedy, so it finds the shortest complete array structure rather than swallowing
     * everything up to the last bracket in the response. Among the candidates it prefers the
     * first one that actually carries a {@code "score"} key: a model that prefaces its answer
     * with "Scores [0-1 scale]:" puts a bracket pair ahead of the real array, and returning
     * that one produced something that looked like a JSON array and parsed to nothing.</p>
     *
     * @param content Content to search (must not be null)
     * @return Extracted JSON array string, or null if not found
     */
    private String extractJsonArray(String content) {
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(content);

        String first = null;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (first == null) {
                first = candidate;
            }
            if (SCORE_KEY.matcher(candidate).find()) {
                return candidate;
            }
        }

        return first;
    }

    /**
     * Static convenience method for sanitizing content without creating an instance.
     *
     * <p>Equivalent to {@code new ResponseSanitizer().sanitize(content)} but
     * avoids object allocation for one-off use.</p>
     *
     * @param content Raw content from LLM response (may be null or malformed)
     * @return Cleaned JSON array string, or null if no valid array found
     */
    public static String sanitizeStatic(String content) {
        // Use a shared instance to avoid repeated allocations
        return Holder.INSTANCE.sanitize(content);
    }

    /**
     * Initialization-on-demand holder for lazy singleton pattern.
     * Provides a shared instance for static method use.
     */
    private static final class Holder {
        static final ResponseSanitizer INSTANCE = new ResponseSanitizer();
    }
}
