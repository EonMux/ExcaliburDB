package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;

/**
 * The fields of a {@code POST /v1/query} request that decide what an answer looks like.
 *
 * <h2>Why these are apart from the rest of the request</h2>
 * <p>Everything in {@link QueryRequestParser.ParsedQuery} steers the search: which vectors are
 * compared, how deep, for how long. Nothing here does. {@code offset} picks which slice of one
 * ranking is returned, {@code explain} and {@code snippet} decide how much of the reason for a
 * result is written down beside it. They are parsed and bounded in the same place and by the
 * same rules, because a bound applied on one retrieval path and not the other is the failure
 * this endpoint has already had once - but they are a different question, and mixing them into
 * the search request would put presentation into the thing the pipeline is handed.</p>
 *
 * <h2>What is bounded, and why refusal rather than trimming</h2>
 * <p>Each of these sizes something the caller does not pay for. {@code offset} deepens the
 * search that has to run before the page can be cut from it, so it is held to the same ceiling
 * as {@code top_k} and for the same reason. {@code snippet_chars} is a window onto result text
 * and is held to the same ceiling that text is. In both cases a request past the bound is
 * refused: a caller silently given a narrower window, or a shallower page than it asked for,
 * reads the answer as the corpus's rather than the server's.</p>
 *
 * @param offset       How many of the best results to skip before the page begins
 * @param explain      Whether each result says which signals produced it and what it matched
 * @param snippet      Whether result text is the window the query matched rather than the start
 * @param snippetChars Most characters one window may hold
 */
public record QuerySearchOptions(int offset, boolean explain, boolean snippet, int snippetChars) {

    /**
     * The window a request gets when it asks for a snippet without sizing one.
     *
     * <p>About two lines of prose: long enough to carry a matched sentence and the clause on
     * either side of it, short enough that a page of ten results is still a page rather than a
     * document. A caller wanting more asks for more, up to what the node serves.</p>
     */
    public static final int DEFAULT_SNIPPET_CHARS = 320;

    /**
     * The narrowest window that is still a window.
     *
     * <p>Beneath this a snippet cannot hold a matched term and any of its context, which is
     * the whole of what a snippet is for. Refused rather than widened, because a caller that
     * asked for sixteen characters and was handed three hundred has been answered a different
     * question.</p>
     */
    public static final int MIN_SNIPPET_CHARS = 16;

    /**
     * Checks the options describe a page and a window that can exist.
     */
    public QuerySearchOptions {
        if (offset < 0) {
            throw new IllegalArgumentException("Field 'offset' must not be negative: " + offset);
        }
        if (snippetChars < MIN_SNIPPET_CHARS) {
            throw new IllegalArgumentException("Field 'snippet_chars' must be at least "
                + MIN_SNIPPET_CHARS + ": " + snippetChars);
        }
    }

    /**
     * Parses and validates the presentation fields of one request body.
     *
     * @param json        The request body
     * @param limits      Bounds applied to client-supplied values
     * @param topK        The page size already validated out of the same body
     * @param includeText Whether the same body asked for result text
     * @return The validated options
     * @throws IllegalArgumentException if any field is out of range or asks for what another
     *                                  field did not enable
     */
    public static QuerySearchOptions parse(String json, ApiSecurityConfig limits, int topK,
                                           boolean includeText) {
        int offset = JsonText.integer(json, "offset", 0);
        if (offset < 0) {
            throw new IllegalArgumentException("Field 'offset' must not be negative: " + offset);
        }
        // The engine has to rank offset + top_k deep before the page can be cut out of it, and
        // that depth is what sizes the priority queues inside the pipeline. Bounding top_k
        // alone would leave the allocation to the caller under a different field name.
        if (offset > limits.maxTopK() - topK) {
            throw new IllegalArgumentException("Field 'offset' plus 'top_k' must be at most "
                + limits.maxTopK() + ": " + offset + " + " + topK);
        }
        boolean explain = JsonText.booleanValue(json, "explain", false);
        boolean snippet = JsonText.booleanValue(json, "snippet", false);
        return new QuerySearchOptions(offset, explain, snippet,
            snippetChars(json, limits, snippet, includeText));
    }

    /**
     * The window one request gets, refusing the ones that cannot be honoured.
     *
     * @param json        The request body
     * @param limits      Bounds applied to client-supplied values
     * @param snippet     Whether the request asked for a query-centred window
     * @param includeText Whether the request asked for result text at all
     * @return The window width in characters
     */
    private static int snippetChars(String json, ApiSecurityConfig limits, boolean snippet,
                                    boolean includeText) {
        int ceiling = limits.maxResultTextChars();
        int fallback = Math.max(MIN_SNIPPET_CHARS, Math.min(DEFAULT_SNIPPET_CHARS, ceiling));
        // A window onto text the request did not ask for is not a window onto anything.
        // Turning the text on unasked would return a field nobody requested, and ignoring the
        // flag would answer a different question from the one that was asked.
        if (snippet && !includeText) {
            throw new IllegalArgumentException(
                "Field 'snippet' requires 'include_text': a snippet is a window onto the text "
                + "of a result, and this request did not ask for any");
        }
        if (!JsonText.has(json, "snippet_chars")) {
            return fallback;
        }
        if (!snippet) {
            throw new IllegalArgumentException(
                "Field 'snippet_chars' requires 'snippet': it sizes the query-centred window, "
                + "and this request did not ask for one");
        }
        int chars = JsonText.integer(json, "snippet_chars", fallback);
        if (chars < MIN_SNIPPET_CHARS) {
            throw new IllegalArgumentException("Field 'snippet_chars' must be at least "
                + MIN_SNIPPET_CHARS + ": " + chars);
        }
        // The same ceiling result text itself is held to, excalibur.api.max-result-text-chars.
        // A window wider than the text bound would be a way to ask for more text than the node
        // serves by asking for a window onto it.
        if (chars > ceiling) {
            throw new IllegalArgumentException("Field 'snippet_chars' must be at most "
                + ceiling + ": " + chars);
        }
        return chars;
    }

    /**
     * How deep the engine has to rank for this page to be cut out of the result.
     *
     * @param topK The page size
     * @return The depth to ask the engine for
     */
    public int depth(int topK) {
        return offset + topK;
    }

    /**
     * Whether an answer has to read result passages at all.
     *
     * @param includeText Whether the request asked for result text
     * @return true when chunk storage has to be read for this request
     */
    public boolean readsPassages(boolean includeText) {
        return includeText || explain;
    }
}
