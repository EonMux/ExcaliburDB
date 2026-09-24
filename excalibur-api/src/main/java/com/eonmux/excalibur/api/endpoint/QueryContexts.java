package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.ResultText;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.retrieval.snippet.QueryTerms;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a node can offer a request beyond the search, and what it therefore has to refuse.
 *
 * <h2>Why a node-level object</h2>
 * <p>Result text and matched terms depend on what is inside this node rather than on what was
 * asked: both need chunk storage, which a query router does not keep. The chunk-text source is
 * wired after the handler exists and is read by every request, so it lives in one place that the
 * query path and the BM25-only path share - a refusal that only one of them makes is the
 * difference between a node that cannot serve a field and a node that serves it on alternate
 * requests. The expansion report needs nothing from the node: it is read from the answer.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The chunk-text source is set during startup and read by every
 * request, so it is volatile; everything built per request is built fresh.</p>
 */
final class QueryContexts {

    private final ApiSecurityConfig limits;
    private final ResultText resultText;
    private volatile Function<String, String> chunkText;

    /**
     * Creates the factory over a deployment's bounds.
     *
     * @param limits     Bounds applied to client-supplied values
     * @param resultText Decides whether a request may have result text at all
     * @throws NullPointerException if either argument is null
     */
    QueryContexts(ApiSecurityConfig limits, ResultText resultText) {
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        this.resultText = Objects.requireNonNull(resultText, "resultText cannot be null");
    }

    /**
     * Gives this node a chunk-text source, or takes it away.
     *
     * @param chunkText Reads a chunk's text by identifier, or null to serve identifiers only
     */
    void chunkText(Function<String, String> chunkText) {
        this.chunkText = chunkText;
        this.resultText.source(chunkText);
    }

    /**
     * This node's chunk-text source.
     *
     * <p>Read by relevancy scoring as well as by the passages: a model judges a candidate on
     * its text, and a node that keeps none has nothing to put in front of it.</p>
     *
     * @return Reads a chunk's text by identifier, or null when this node keeps no chunk storage
     */
    Function<String, String> chunkText() {
        return chunkText;
    }

    /**
     * Resolves one request's presentation fields, refusing what this node cannot serve.
     *
     * @param requestBody The raw request
     * @param parsed      The validated request
     * @return The context every path of this request will answer through
     * @throws IllegalArgumentException if a field is out of range or asks for what this node
     *                                  cannot produce
     */
    QueryContext of(String requestBody, QueryRequestParser.ParsedQuery parsed) {
        QuerySearchOptions options = QuerySearchOptions.parse(requestBody, limits, parsed.topK(),
            parsed.includeText());
        // The gate for result text, and the one message a node with no chunk storage answers
        // with. The bounded reader it hands back is deliberately not the one used below: a
        // query-centred window cannot be chosen out of text that was already clipped from the
        // start, so the passage reader does its own unbounded read and bounds the window.
        resultText.resolverFor(parsed.includeText());
        Function<String, String> source = this.chunkText;
        requireChunkStorageToExplain(options, source);
        List<String> terms = options.explain() || options.snippet()
            ? QueryTerms.of(parsed.query(), QueryTerms.MAX_TERMS)
            : List.of();
        return new QueryContext(options,
            ResultPassages.forRequest(source, limits.maxResultTextChars(), parsed.includeText(),
                options, terms),
            parsed.query(), parsed.topK());
    }

    /**
     * Refuses an explanation this node has no way to produce.
     *
     * <p>Matched terms are read out of the passage a result names, so a node that keeps no
     * chunk storage cannot produce them. Answering with an empty list would say the passage
     * contained none of the query's words, which is a different statement from not having
     * looked - the same reason {@code include_text} is refused rather than dropped.</p>
     *
     * @param options The request's presentation fields
     * @param source  This node's chunk-text source, or null when it keeps none
     */
    private static void requireChunkStorageToExplain(QuerySearchOptions options,
                                                     Function<String, String> source) {
        if (options.explain() && source == null) {
            throw new IllegalArgumentException("Field 'explain' is not supported on this node: "
                + "it keeps no chunk storage, so it cannot say which of the query's terms a "
                + "result's passage contains");
        }
    }
}
