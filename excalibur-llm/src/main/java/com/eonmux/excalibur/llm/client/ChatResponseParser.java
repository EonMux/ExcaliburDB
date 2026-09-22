package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.LLMException.ErrorType;
import com.eonmux.excalibur.llm.RelevancyScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the parts of an OpenAI-shaped chat completion this system acts on.
 *
 * <p>Separate from the HTTP client because reading a reply and making a request fail for
 * entirely different reasons, and because the reading is where the subtle mistakes live: it is
 * worth being able to hand this a response body directly and see what it makes of it, without
 * standing up a server to deliver it.</p>
 *
 * <h2>Guessing is not allowed</h2>
 * <p>The scan is hand-written rather than delegated to a JSON parser, which the module has no
 * dependency on. That is tolerable only if every shape it does not understand is reported as
 * unreadable. The alternative was demonstrated by the version this replaces: asked for the
 * {@code content} of a tool-call-only message, where the field is {@code null}, it walked past
 * the null to the next quoted run in the body and returned the string {@code tool_calls} - the
 * name of the neighbouring key - as though the model had said it. Downstream nothing could tell
 * that apart from an answer.</p>
 *
 * <h2>Errors carry no reply text</h2>
 * <p>A failure message names the shape that was wrong, never the content that was in it. The
 * model's reply is written from ingested documents and the query that retrieved them, so an
 * exception quoting it copies document content into whatever reads the error - an API response,
 * a log, a bug report.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and immutable apart from the sanitizer it holds, which is itself stateless.</p>
 */
public final class ChatResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(ChatResponseParser.class);

    /** The assistant message object, used to anchor the search for its content. */
    private static final String MESSAGE_KEY = "\"message\"";

    /** The field holding what the assistant said. */
    private static final String CONTENT_KEY = "\"content\"";

    /**
     * One score object at a time, with its fields read separately.
     *
     * <p>A model's key order and extra keys are not something to depend on. A single pattern
     * requiring {@code {"index": N, "score": X}} in that order and nothing else matched nothing
     * at all for a reply that transposed the two fields or added a "reason".</p>
     */
    private static final Pattern SCORE_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*\\}");

    /** The {@code index} field of a score object. */
    private static final Pattern SCORE_INDEX_FIELD = Pattern.compile("\"index\"\\s*:\\s*(-?\\d+)");

    /** The {@code score} field of a score object. */
    private static final Pattern SCORE_VALUE_FIELD = Pattern.compile(
        "\"score\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)");

    private final ResponseSanitizer sanitizer;

    /**
     * Creates a parser with the default sanitizer.
     */
    public ChatResponseParser() {
        this(new ResponseSanitizer());
    }

    /**
     * Creates a parser reading model replies through the given sanitizer.
     *
     * @param sanitizer Strips fences and commentary from a reply, never null
     */
    public ChatResponseParser(ResponseSanitizer sanitizer) {
        this.sanitizer = sanitizer != null ? sanitizer : new ResponseSanitizer();
    }

    /**
     * Returns what the assistant said, as text.
     *
     * <p>Accepts only a JSON string. A {@code null} content - what providers send for a message
     * that is nothing but tool calls - is absent, not empty and certainly not the next field
     * along, and is reported as unreadable. So is a structured content block, which this parser
     * makes no attempt to flatten: returning one fragment of it would be a guess about which
     * fragment mattered.</p>
     *
     * @param responseBody The full chat completion body
     * @return The assistant's text, possibly empty, never null
     * @throws LLMException if the body carries no readable textual content
     */
    public String extractMessageContent(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from chat API", ErrorType.PARSE_ERROR);
        }

        int contentKeyAt = findContentKey(responseBody);
        if (contentKeyAt < 0) {
            throw new LLMException("No 'content' field in the assistant message",
                ErrorType.PARSE_ERROR);
        }

        int at = skipWhitespace(responseBody, contentKeyAt + CONTENT_KEY.length());
        if (at >= responseBody.length() || responseBody.charAt(at) != ':') {
            throw new LLMException("The 'content' field is not followed by a value",
                ErrorType.PARSE_ERROR);
        }

        at = skipWhitespace(responseBody, at + 1);
        if (at >= responseBody.length()) {
            throw new LLMException("The 'content' field has no value", ErrorType.PARSE_ERROR);
        }

        char first = responseBody.charAt(at);
        if (first == 'n' && responseBody.startsWith("null", at)) {
            throw new LLMException(
                "The assistant message carries no text; 'content' is null, which a provider "
                    + "sends for a reply that is only tool calls",
                ErrorType.PARSE_ERROR);
        }
        if (first != '"') {
            throw new LLMException(
                "The 'content' field is not a string; this client cannot read a structured "
                    + "content value",
                ErrorType.PARSE_ERROR);
        }

        int closingQuote = findClosingQuote(responseBody, at + 1);
        if (closingQuote < 0) {
            throw new LLMException("The 'content' string is never closed", ErrorType.PARSE_ERROR);
        }

        return unescapeJsonString(responseBody.substring(at + 1, closingQuote));
    }

    /**
     * Returns the relevancy scores carried by a chat completion.
     *
     * @param responseBody The full chat completion body
     * @param candidates   The passages that were scored, for index bounds and text lookup
     * @return One score per readable, in-range object in the reply
     * @throws LLMException if the reply carries no readable scores
     */
    public List<RelevancyScore> parseRelevancyResponse(String responseBody, List<String> candidates) {
        String content = extractMessageContent(responseBody);

        String sanitized = sanitizer.sanitize(content);
        if (sanitized == null || sanitized.isBlank()) {
            LOG.debug("No JSON array could be extracted from a {} character reply",
                content.length());
            throw new LLMException("Could not extract a JSON array from the model's reply",
                ErrorType.PARSE_ERROR);
        }

        return parseScoreArray(sanitized, candidates);
    }

    /**
     * Reads the score objects out of a JSON array.
     *
     * <p>An out-of-range index is discarded rather than clamped: clamping index 999 to the last
     * candidate would attribute the model's score to a document it never saw. An array in which
     * nothing at all could be read is an error, because returning an empty list reported success
     * to the circuit breaker and answered the query with nothing.</p>
     *
     * @param jsonArray  The array, already stripped of fences and commentary
     * @param candidates The passages that were scored
     * @return The scores that could be read and fell in range
     * @throws LLMException if the text is not an array, or carries no readable score object
     */
    public List<RelevancyScore> parseScoreArray(String jsonArray, List<String> candidates) {
        String content = jsonArray.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            throw new LLMException("The model's reply is not a JSON array", ErrorType.PARSE_ERROR);
        }

        content = content.substring(1, content.length() - 1).trim();
        List<RelevancyScore> scores = new ArrayList<>();
        if (content.isEmpty()) {
            return scores;
        }

        boolean anyObjectRead = false;
        Matcher matcher = SCORE_OBJECT_PATTERN.matcher(content);
        while (matcher.find()) {
            String object = matcher.group();
            Matcher indexField = SCORE_INDEX_FIELD.matcher(object);
            Matcher scoreField = SCORE_VALUE_FIELD.matcher(object);
            if (!indexField.find() || !scoreField.find()) {
                LOG.debug("Skipping a score object with no index/score pair");
                continue;
            }

            try {
                int index = Integer.parseInt(indexField.group(1));
                double score = Double.parseDouble(scoreField.group(1));
                anyObjectRead = true;

                if (index < 0 || index >= candidates.size()) {
                    LOG.warn("Discarding score for index {}; only {} candidates were sent",
                        index, candidates.size());
                    continue;
                }

                scores.add(new RelevancyScore(index, score, candidates.get(index)));
            } catch (NumberFormatException e) {
                LOG.debug("Skipping a score object whose numbers could not be read", e);
            }
        }

        if (!anyObjectRead) {
            throw new LLMException(
                "The model's reply is a JSON array of " + content.length()
                    + " characters but carries no readable score object",
                ErrorType.PARSE_ERROR);
        }

        return scores;
    }

    /**
     * Returns the index of the {@code "content"} key of the assistant message.
     *
     * <p>Anchored on the enclosing {@code "message"} object where there is one, so that a
     * provider echoing the request - whose prompt is itself carried in a {@code content} field -
     * cannot have its echo read as the answer.</p>
     *
     * @param responseBody The full response body
     * @return Index of the key, or -1 if absent
     */
    private int findContentKey(String responseBody) {
        int messageAt = responseBody.indexOf(MESSAGE_KEY);
        int from = messageAt >= 0 ? messageAt + MESSAGE_KEY.length() : 0;
        return responseBody.indexOf(CONTENT_KEY, from);
    }

    /**
     * Returns the first index at or after {@code from} that is not whitespace.
     *
     * @param text The text to scan
     * @param from Where to start
     * @return The index of the first non-whitespace character, or the text length
     */
    private int skipWhitespace(String text, int from) {
        int at = from;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    /**
     * Returns the index of the quote closing a JSON string, honouring backslash escapes.
     *
     * @param json  The text being scanned
     * @param start The first index inside the string
     * @return Index of the closing quote, or -1 if the string is never closed
     */
    private int findClosingQuote(String json, int start) {
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Resolves the JSON escape sequences in a string value.
     *
     * @param escaped The raw text between the quotes
     * @return The text with its escapes resolved
     */
    private String unescapeJsonString(String escaped) {
        if (escaped.indexOf('\\') < 0) {
            return escaped;
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\' || i + 1 >= escaped.length()) {
                sb.append(c);
                continue;
            }
            i = appendEscape(sb, escaped, i);
        }
        return sb.toString();
    }

    /**
     * Appends the character denoted by the escape sequence at {@code at}.
     *
     * @param sb      Where to append
     * @param escaped The raw text
     * @param at      Index of the backslash
     * @return The index of the last character consumed
     */
    private int appendEscape(StringBuilder sb, String escaped, int at) {
        char next = escaped.charAt(at + 1);
        switch (next) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
                return appendUnicodeEscape(sb, escaped, at);
            }
            default -> {
                // Not an escape this format defines; keep the backslash rather than invent one.
                sb.append('\\');
                return at;
            }
        }
        return at + 1;
    }

    /**
     * Appends the character denoted by a {@code \\uXXXX} escape.
     *
     * @param sb      Where to append
     * @param escaped The raw text
     * @param at      Index of the backslash
     * @return The index of the last character consumed
     */
    private int appendUnicodeEscape(StringBuilder sb, String escaped, int at) {
        if (at + 6 > escaped.length()) {
            sb.append('\\');
            return at;
        }
        try {
            sb.append((char) Integer.parseInt(escaped.substring(at + 2, at + 6), 16));
            return at + 5;
        } catch (NumberFormatException e) {
            sb.append('\\');
            return at;
        }
    }
}
