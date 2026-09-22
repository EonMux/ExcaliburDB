package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;

import java.util.List;

/**
 * Renders what {@code /v1/query} answers, whichever signals produced it.
 *
 * <h2>One shape, three answers</h2>
 * <p>The route has three retrieval paths - vector search, BM25, and the two fused - and a client
 * that has to branch on which one answered in order to read the result list or the coverage block
 * cannot use either field for what it is for. So the envelope is identical across all three, and
 * {@code retrieval} is what says which signals ran. It is always present: a field that appears
 * only on some answers is a field nobody reads.</p>
 *
 * <h2>What {@code score} means</h2>
 * <p>Its scale follows {@code retrieval} and {@code llm_scored}, because there is no scale every
 * answer shares. On {@code "vector"} it is the similarity the index reported. On
 * {@code "lexical"} and {@code "hybrid"} it is a sum of reciprocal ranks - the lexical index
 * fuses its own signals by rank too - a small number near zero that orders results and means
 * nothing in absolute terms. A threshold tuned against cosine scores does not transfer to either,
 * which is why the field that says so is not optional.</p>
 *
 * <p>{@code llm_scored} true replaces all three: a relevancy model read each candidate against
 * the question, and a result it judged carries that judgement, in {@code [0, 1]}, as its score.
 * A result it could not judge keeps its retrieval score and is ordered among the others by it.
 * The flag is on every answer, false included, for the same reason {@code retrieval} is.</p>
 *
 * <h2>How much there is, and how to get the rest</h2>
 * <p>{@code page} and {@code result_total} are on every answer, including the empty one a
 * timed-out query returns. A caller that has to test for them cannot use them for arithmetic,
 * and the pair is what separates "nothing matched" from "the first page happened to be empty".
 * The total says which of the two it is rather than leaving a caller to guess - see
 * {@link ResultTotal}.</p>
 *
 * <h2>Text on a result</h2>
 * <p>Absent unless the request asked for it, and then present on every result of that answer -
 * {@code null} included, for a chunk storage no longer holds. A field that appears on some
 * results of one answer and not others cannot be read without checking for it each time, so the
 * request decides whether it is there and nothing else does. A request that also asked for a
 * snippet gets {@code text_offset} and {@code highlights} beside it, on the same terms.</p>
 *
 * <h2>What the answer did not act on</h2>
 * <p>{@code ignored_fields} is on every answer, empty list included; which fields belong in it
 * is decided by {@link IgnoredFields}, and only rendered here.</p>
 *
 * <h2>Why a result was returned</h2>
 * <p>{@code explain} on a result, and {@code query_expansion} on the answer, both only when
 * the request asked. They cost a storage read per result, which is the same reason
 * {@code include_text} is opt-in.</p>
 */
public final class QueryResponseJson {

    /** Vector search answered alone. */
    public static final String VECTOR = "vector";

    /** BM25 answered alone. */
    public static final String LEXICAL = "lexical";

    /** Both signals ran and their rankings were fused. */
    public static final String HYBRID = "hybrid";

    private QueryResponseJson() {
    }

    /**
     * Renders an answer from vector search alone.
     *
     * @param response What the query pipeline produced, for its coverage and timings
     * @param page     The results this answer returns, already sliced to the page
     * @param answer   What this response says beyond the ranking
     * @return The response body
     */
    public static String vector(QueryResponse response, List<QueryResultItem> page,
                                QueryAnswer answer) {
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, response.queryId());

        // document_id carries the document's own identifier - the string the ingestion pipeline
        // assigned - not the HNSW node ID, which is an internal handle that compaction renumbers
        // and that no client can look anything up by.
        sb.append("\"results\": [");
        for (int i = 0; i < page.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            QueryResultItem item = page.get(i);
            sb.append("{\"document_id\": \"").append(JsonText.escape(item.externalId()))
                .append("\", \"score\": ").append(JsonText.number(item.score(), 6))
                .append(", \"segment_id\": ").append(item.segmentId());
            appendText(sb, answer, item.externalId());
            appendExplain(sb, answer, i);
            sb.append("}");
        }
        sb.append("], ");
        appendRetrieval(sb, VECTOR, response.llmScored());
        appendCoverage(sb, response.coverage());
        appendIgnoredFields(sb, answer.ignored());
        appendAnswer(sb, answer);
        appendTail(sb, response.timedOut(), response.cancelled(), response.queryTimeMs(),
            response.fromCache());
        return sb.toString();
    }

    /**
     * Renders an answer both signals contributed to.
     *
     * <p>Coverage is the vector pipeline's: it measures how much of the segment catalog the
     * query reached, which is a question about the vector index and is not made truer or falser
     * by a second signal having also run. The one thing added to it is relevancy scoring after
     * fusion - a scorer that was planned and did not finish degrades the answer it was meant to
     * order.</p>
     *
     * @param response What the fused search produced, for its coverage and timings
     * @param fused    The fused ranking, best first, sliced to the page being returned
     * @param answer   What this response says beyond the ranking
     * @return The response body
     */
    public static String hybrid(HybridResponse response, List<HybridFusion.Result> fused,
                                QueryAnswer answer) {
        QueryResponse vector = response.vector();
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, vector.queryId());

        sb.append("\"results\": [");
        for (int i = 0; i < fused.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            appendFused(sb, fused.get(i), answer, i);
        }
        sb.append("], ");
        appendRetrieval(sb, HYBRID, response.llmScored());
        appendCoverage(sb, response.coverage());
        appendIgnoredFields(sb, answer.ignored());
        appendAnswer(sb, answer);
        appendTail(sb, response.timedOut(), vector.cancelled(), vector.queryTimeMs(),
            vector.fromCache());
        return sb.toString();
    }

    /**
     * Renders one fused result.
     *
     * @param sb     The response under construction
     * @param result The fused result
     * @param answer What this response says beyond the ranking
     * @param index  Where the result sits in the page, for its explanation
     */
    private static void appendFused(StringBuilder sb, HybridFusion.Result result,
                                    QueryAnswer answer, int index) {
        sb.append("{\"document_id\": \"").append(JsonText.escape(result.documentId()))
            .append("\", \"score\": ").append(JsonText.number(result.score(), 6))
            // null rather than a number for a hit only BM25 returned: it was never in a
            // vector segment, and naming one would invent a provenance it does not have.
            .append(", \"segment_id\": ")
            .append(result.segmentId() == null ? "null" : result.segmentId().toString())
            .append(", \"signals\": [");
        List<String> signals = result.signals();
        for (int s = 0; s < signals.size(); s++) {
            if (s > 0) {
                sb.append(", ");
            }
            sb.append('"').append(JsonText.escape(signals.get(s))).append('"');
        }
        sb.append("]");
        appendText(sb, answer, result.documentId());
        appendExplain(sb, answer, index);
        sb.append("}");
    }

    /**
     * Renders an answer from the lexical index alone.
     *
     * @param response What the BM25 search produced, for its coverage and timings
     * @param hits     The ranking, best first, sliced to the page being returned
     * @param answer   What this response says beyond the ranking
     * @return The response body
     */
    public static String lexical(LexicalResponse response, List<LexicalQueryService.Hit> hits,
                                 QueryAnswer answer) {
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, response.queryId());

        sb.append("\"results\": [");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"document_id\": \"")
              .append(JsonText.escape(hits.get(i).chunkId()))
              .append("\", \"score\": ").append(JsonText.number(hits.get(i).score(), 6));
            appendText(sb, answer, hits.get(i).chunkId());
            appendExplain(sb, answer, i);
            sb.append("}");
        }
        sb.append("], ");
        appendRetrieval(sb, LEXICAL, response.llmScored());
        appendCoverage(sb, response.coverage());
        appendIgnoredFields(sb, answer.ignored());
        appendAnswer(sb, answer);
        appendTail(sb, response.timedOut(), response.cancelled(), response.queryTimeMs(),
            response.fromCache());
        return sb.toString();
    }

    /**
     * Says which signals produced the ranking, and whether a relevancy model then ordered it.
     *
     * <p>Side by side because together they are what {@code score} means; either one alone
     * leaves a caller unable to read the number.</p>
     *
     * @param sb        The response under construction
     * @param retrieval Which signals ran
     * @param llmScored Whether the scores are a relevancy model's judgement
     */
    private static void appendRetrieval(StringBuilder sb, String retrieval, boolean llmScored) {
        sb.append("\"retrieval\": \"").append(retrieval).append("\", ");
        sb.append("\"llm_scored\": ").append(llmScored).append(", ");
    }

    /**
     * Appends a result's text, when the request asked for any.
     *
     * <p>Emitted for every result of an answer that asked, including the ones storage could not
     * supply: {@code null} says the text is not there, which is a different thing from a caller
     * having to guess whether the field was omitted because there was none or because it never
     * asked. {@code text_truncated} rides alongside because a clipped passage that does not say
     * so gets quoted as the whole one.</p>
     *
     * <p>A request that asked for a query-centred window gets two more fields, on the same
     * terms: where the window starts in the chunk, and where the matched terms are inside it.
     * Without those a caller is shown a quotation it cannot locate and cannot highlight.</p>
     *
     * @param sb      The response under construction
     * @param answer  What this response says beyond the ranking
     * @param chunkId The identifier of the result being rendered
     */
    private static void appendText(StringBuilder sb, QueryAnswer answer, String chunkId) {
        ResultPassages passages = answer.passages();
        if (passages == null || !passages.rendersText()) {
            return;
        }
        ResultPassages.Passage passage = passages.of(chunkId);
        sb.append(", \"text\": ");
        if (passage.text() == null) {
            sb.append("null");
        } else {
            sb.append('"').append(JsonText.escape(passage.text())).append('"');
        }
        sb.append(", \"text_truncated\": ").append(passage.truncated());
        if (!passages.windowed()) {
            return;
        }
        sb.append(", \"text_offset\": ").append(passage.offset());
        sb.append(", \"highlights\": [");
        List<ResultPassages.Highlight> highlights = passage.highlights();
        for (int i = 0; i < highlights.size(); i++) {
            sb.append(i > 0 ? ", " : "")
              .append("{\"start\": ").append(highlights.get(i).start())
              .append(", \"length\": ").append(highlights.get(i).length()).append('}');
        }
        sb.append("]");
    }

    /**
     * Appends why one result was returned, when the request asked to be told.
     *
     * @param sb     The response under construction
     * @param answer What this response says beyond the ranking
     * @param index  Where the result sits in the page
     */
    private static void appendExplain(StringBuilder sb, QueryAnswer answer, int index) {
        QueryExplanation explanation = answer.explanation();
        if (explanation == null) {
            return;
        }
        ResultExplanation explained = explanation.at(index);
        if (explained == null) {
            return;
        }
        sb.append(", \"explain\": {\"signals\": [");
        List<ResultExplanation.Signal> signals = explained.signals();
        for (int i = 0; i < signals.size(); i++) {
            ResultExplanation.Signal signal = signals.get(i);
            sb.append(i > 0 ? ", " : "")
              .append("{\"signal\": \"").append(JsonText.escape(signal.name()))
              .append("\", \"rank\": ").append(signal.rank())
              .append(", \"score\": ").append(JsonText.number(signal.score(), 6)).append('}');
        }
        sb.append("], \"matched_terms\": [");
        appendStrings(sb, explained.matchedTerms());
        sb.append("]}");
    }

    /**
     * Opens the envelope, naming the query when one was registered.
     */
    private static void appendQueryId(StringBuilder sb, String queryId) {
        sb.append("{");
        if (queryId != null) {
            sb.append("\"query_id\": \"").append(JsonText.escape(queryId)).append("\", ");
        }
    }

    /**
     * Renders the coverage block.
     *
     * <p>One renderer for every retrieval path. A client that has to branch on which path
     * answered in order to read how much of the corpus was searched cannot use the field for
     * what it is for, so the shape is not allowed to differ between them.</p>
     *
     * @param sb       The response under construction
     * @param coverage What the query covered
     */
    private static void appendCoverage(StringBuilder sb, IndexCoverage coverage) {
        sb.append("\"coverage\": {");
        sb.append("\"total_segments\": ").append(coverage.totalSegments()).append(", ");
        sb.append("\"active_segments\": ").append(coverage.activeSegments()).append(", ");
        sb.append("\"indexing_segments\": ").append(coverage.indexingSegments()).append(", ");
        sb.append("\"warming_segments\": ").append(coverage.warmingSegments()).append(", ");
        sb.append("\"compacting_segments\": ").append(coverage.compactingSegments()).append(", ");
        sb.append("\"tombstoned_segments\": ").append(coverage.tombstonedSegments()).append(", ");
        sb.append("\"estimated_corpus_coverage\": ")
            .append(JsonText.number(coverage.estimatedCorpusCoverage(), 4)).append(", ");
        // What this query did, as opposed to what the catalog holds. Without these an empty
        // answer reads the same whether the query searched the whole corpus and found nothing or
        // was never allowed to look at any of it.
        sb.append("\"segments_searched\": ").append(coverage.segmentsSearched()).append(", ");
        sb.append("\"segments_excluded\": ").append(coverage.segmentsExcluded()).append(", ");
        sb.append("\"segments_with_errors\": ").append(coverage.segmentsWithErrors()).append(", ");
        sb.append("\"segments_timed_out\": ").append(coverage.segmentsTimedOut()).append(", ");
        sb.append("\"tail_scan_entries\": ").append(coverage.tailScanEntries()).append(", ");
        sb.append("\"degraded\": ").append(coverage.degraded());
        sb.append("}, ");
    }

    /**
     * Names what this answer accepted and could not act on.
     *
     * <p>Always emitted, empty list included: a caller cannot tell an unhonoured field from an
     * honoured one by its absence, and a field that only appears when something went wrong is
     * a field nobody looks for.</p>
     *
     * <p>Rendered, not decided, here. Which entries belong on one answer depends on what the
     * caller sent and on what this node's retrieval stages could do with it, and neither of
     * those is a question about JSON.</p>
     *
     * @param sb      The response being built
     * @param ignored What this answer did not act on
     */
    private static void appendIgnoredFields(StringBuilder sb, List<String> ignored) {
        sb.append("\"ignored_fields\": [");
        appendStrings(sb, ignored);
        sb.append("], ");
    }

    /**
     * Renders how much of the ranking this answer is, and how much there is.
     *
     * <p>Both blocks on every answer, and the expansion only when the request asked to be
     * told why - an expansion block on an answer nobody asked to have explained is a list of
     * terms with nothing beside it to read them against.</p>
     *
     * @param sb     The response being built
     * @param answer What this response says beyond the ranking
     */
    private static void appendAnswer(StringBuilder sb, QueryAnswer answer) {
        ResultPage page = answer.page();
        sb.append("\"page\": {\"offset\": ").append(page.offset())
          .append(", \"limit\": ").append(page.limit())
          .append(", \"returned\": ").append(page.returned())
          .append(", \"next_offset\": ")
          .append(page.nextOffset() == null ? "null" : page.nextOffset().toString())
          .append("}, ");
        sb.append("\"result_total\": {\"value\": ").append(answer.total().value())
          .append(", \"relation\": \"").append(answer.total().relation()).append("\"}, ");
        if (answer.explanation() == null) {
            return;
        }
        QueryExpansion expansion = answer.explanation().expansion();
        sb.append("\"query_expansion\": {\"status\": \"").append(expansion.status())
          .append("\", \"terms\": [");
        List<QueryExpansion.Term> terms = expansion.terms();
        for (int i = 0; i < terms.size(); i++) {
            sb.append(i > 0 ? ", " : "")
              .append("{\"term\": \"").append(JsonText.escape(terms.get(i).text()))
              .append("\", \"weight\": ").append(JsonText.number(terms.get(i).weight(), 2))
              .append('}');
        }
        sb.append("], \"terms_truncated\": ").append(expansion.truncated()).append("}, ");
    }

    /**
     * Writes a list of strings as JSON string literals, escaped.
     *
     * @param sb     The response being built
     * @param values The strings, every one of them caller-influenced
     */
    private static void appendStrings(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(JsonText.escape(values.get(i))).append('"');
        }
    }

    /**
     * Closes the envelope with what happened to the request itself.
     *
     * <p>{@code from_cache} is on every answer, false included. Without it beside
     * {@code query_time_ms}, a cache hit reads as a search that suddenly got faster: the
     * duration on a hit is how long this request took, not how long the search took, and
     * nothing else in the answer distinguishes the two.</p>
     */
    private static void appendTail(StringBuilder sb, boolean timedOut, boolean cancelled,
                                   long durationMs, boolean fromCache) {
        sb.append("\"timed_out\": ").append(timedOut).append(", ");
        // A withdrawn request and an exhausted deadline produce the same empty, degraded
        // answer. Only this says which - or that neither happened and the corpus held no match.
        sb.append("\"cancelled\": ").append(cancelled).append(", ");
        sb.append("\"from_cache\": ").append(fromCache).append(", ");
        sb.append("\"query_time_ms\": ").append(durationMs);
        sb.append("}");
    }
}
