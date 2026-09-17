package excalibur.api.endpoint;

import io.excalibur.retrieval.expand.QueryExpander;

/**
 * What one request asked for beyond the search, resolved before the search runs.
 *
 * <h2>Why it is resolved first</h2>
 * <p>Every one of these decisions can refuse the request - a page deeper than the node ranks,
 * a window wider than the text it serves, an explanation a node with no chunk storage cannot
 * produce - and every one of them is a property of the request rather than of the corpus.
 * Deciding them after the search means a full retrieval pass runs for an answer that is then
 * thrown away, which on a node that cannot honour the field is every request that sends it.</p>
 *
 * <h2>Why the three paths share one</h2>
 * <p>The fused answer, the vector-only answer and the BM25-only answer all page the same way,
 * total the same way and explain the same way, and they are chosen between after the request
 * has been read. A context built once and handed to whichever path answers is what stops the
 * three drifting apart - which is the failure this route has already had, when a bound applied
 * on one path was missing from the other.</p>
 *
 * @param options  The presentation fields, already validated
 * @param passages Reads each result's text, or null when nothing needs it
 * @param expander The expander the searching half uses, or null when this node wires none
 * @param query    The query text, as validated
 * @param topK     The page size the request asked for
 */
public record QueryContext(QuerySearchOptions options, ResultPassages passages,
                           QueryExpander expander, String query, int topK) {

    /**
     * Checks the context describes a request that can be answered.
     */
    public QueryContext {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive: " + topK);
        }
    }

    /**
     * How deep the engine has to rank before this request's page can be cut out of it.
     *
     * @return The search depth
     */
    public int depth() {
        return options.depth(topK);
    }

    /**
     * How many of the best results this request asked to skip.
     *
     * @return The offset of the page
     */
    public int offset() {
        return options.offset();
    }

    /**
     * Whether the request asked to be told why its results are what they are.
     *
     * @return true when an explanation was asked for
     */
    public boolean explaining() {
        return options.explain();
    }

    /**
     * The same validated request, asking the engine for the depth this page needs.
     *
     * <p>{@code top_k} is the size of the page the caller wants; the depth is what has to be
     * ranked for that page to exist. Handing the pipeline the page size would return the first
     * page again under every offset.</p>
     *
     * @param parsed The validated request
     * @return The request as the engine should see it
     */
    public QueryRequestParser.ParsedQuery deepened(QueryRequestParser.ParsedQuery parsed) {
        if (options.offset() == 0) {
            return parsed;
        }
        return new QueryRequestParser.ParsedQuery(parsed.query(), depth(), parsed.threshold(),
            parsed.timeoutMs(), parsed.includePartial(), parsed.enableLlmScoring(),
            parsed.llmThreshold(), parsed.oversamplingFactor(), parsed.includeText());
    }

    /**
     * The page this request's answer returns.
     *
     * @param produced How many results the engine ranked
     * @return The page
     */
    public ResultPage page(int produced) {
        return ResultPage.of(options.offset(), topK, produced);
    }

    /**
     * Everything this answer says beyond the ranking.
     *
     * @param produced    How many results the engine ranked, before the page was cut
     * @param complete    Whether the search covered everything it was meant to
     * @param explanation Why the results are what they are, or null when none was asked for
     * @return The answer
     */
    public QueryAnswer answer(int produced, boolean complete, QueryExplanation explanation) {
        return new QueryAnswer(passages, page(produced),
            ResultTotal.of(produced, depth(), complete), explanation);
    }

    /**
     * What the query was widened with, for an answer that asked to be explained.
     *
     * @param widened Whether a signal that widens queries actually ran for this answer
     * @return The report, never null
     */
    public QueryExpansion expansion(boolean widened) {
        return QueryExpansion.of(expander, query, widened);
    }
}
