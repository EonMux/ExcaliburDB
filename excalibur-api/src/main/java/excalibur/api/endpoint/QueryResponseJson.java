package excalibur.api.endpoint;

import excalibur.api.protocol.JsonText;
import excalibur.api.retrieval.ChunkTextResolver;
import excalibur.api.retrieval.HybridFusion;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;

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
 * <p>Its scale follows {@code retrieval}, because there is no scale all three share. On
 * {@code "vector"} it is the similarity the index reported, on {@code "lexical"} the BM25 score,
 * and on {@code "hybrid"} a sum of reciprocal ranks - a small number near zero that orders results
 * and means nothing in absolute terms. A threshold tuned against cosine scores does not transfer
 * to a fused one, which is why the field that says so is not optional.</p>
 *
 * <h2>Text on a result</h2>
 * <p>Absent unless the request asked for it, and then present on every result of that answer -
 * {@code null} included, for a chunk storage no longer holds. A field that appears on some
 * results of one answer and not others cannot be read without checking for it each time, so the
 * request decides whether it is there and nothing else does.</p>
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
     * @param response What the query pipeline produced
     * @return The response body
     */
    public static String vector(QueryResponse response) {
        return vector(response, null);
    }

    /**
     * Renders an answer from vector search alone, optionally carrying each result's text.
     *
     * @param response What the query pipeline produced
     * @param text     Reads the text behind an identifier, or null when none was asked for
     * @return The response body
     */
    public static String vector(QueryResponse response, ChunkTextResolver text) {
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, response.queryId());

        // document_id carries the document's own identifier - the string the ingestion pipeline
        // assigned - not the HNSW node ID, which is an internal handle that compaction renumbers
        // and that no client can look anything up by.
        sb.append("\"results\": [");
        List<QueryResultItem> results = response.results();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            QueryResultItem item = results.get(i);
            sb.append("{\"document_id\": \"").append(JsonText.escape(item.externalId()))
                .append("\", \"score\": ").append(JsonText.number(item.score(), 6))
                .append(", \"segment_id\": ").append(item.segmentId());
            appendText(sb, text, item.externalId());
            sb.append("}");
        }
        sb.append("], ");
        sb.append("\"retrieval\": \"").append(VECTOR).append("\", ");
        appendCoverage(sb, response.coverage());
        appendTail(sb, response.timedOut(), response.cancelled(), response.queryTimeMs());
        return sb.toString();
    }

    /**
     * Renders an answer both signals contributed to, optionally carrying each result's text.
     *
     * <p>Coverage is the vector pipeline's, unchanged: it measures how much of the segment
     * catalog the query reached, which is a question about the vector index and is not made
     * truer or falser by a second signal having also run.</p>
     *
     * @param response What the query pipeline produced, for its coverage and timings
     * @param fused    The fused ranking, best first
     * @param text     Reads the text behind an identifier, or null when none was asked for
     * @return The response body
     */
    public static String hybrid(QueryResponse response, List<HybridFusion.Result> fused,
                                ChunkTextResolver text) {
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, response.queryId());

        sb.append("\"results\": [");
        for (int i = 0; i < fused.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            HybridFusion.Result result = fused.get(i);
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
            appendText(sb, text, result.documentId());
            sb.append("}");
        }
        sb.append("], ");
        sb.append("\"retrieval\": \"").append(HYBRID).append("\", ");
        appendCoverage(sb, response.coverage());
        appendTail(sb, response.timedOut(), response.cancelled(), response.queryTimeMs());
        return sb.toString();
    }

    /**
     * Renders an answer from the lexical index alone, optionally carrying each result's text.
     *
     * @param queryId     The identifier this query was registered under
     * @param hits        What BM25 returned, best first
     * @param coverage    What the index says it searched
     * @param requestBody The raw request, read for which unhonoured fields the caller sent
     * @param unhonoured  Fields this path parses and cannot act on
     * @param timedOut    Whether the request's budget ran out
     * @param cancelled   Whether the caller withdrew the request
     * @param durationMs  How long the request took
     * @param text        Reads the text behind an identifier, or null when none was asked for
     * @return The response body
     */
    public static String lexical(String queryId, List<LexicalQueryService.Hit> hits,
                                 IndexCoverage coverage, String requestBody,
                                 List<String> unhonoured, boolean timedOut, boolean cancelled,
                                 long durationMs, ChunkTextResolver text) {
        StringBuilder sb = new StringBuilder(256);
        appendQueryId(sb, queryId);

        sb.append("\"results\": [");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"document_id\": \"")
              .append(JsonText.escape(hits.get(i).chunkId()))
              .append("\", \"score\": ").append(JsonText.number(hits.get(i).score(), 6));
            appendText(sb, text, hits.get(i).chunkId());
            sb.append("}");
        }
        sb.append("], ");
        sb.append("\"retrieval\": \"").append(LEXICAL).append("\", ");
        appendCoverage(sb, coverage);
        appendIgnoredFields(sb, requestBody, unhonoured);
        appendTail(sb, timedOut, cancelled, durationMs);
        return sb.toString();
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
     * @param sb      The response under construction
     * @param text    The resolver, or null when the request did not ask for text
     * @param chunkId The identifier of the result being rendered
     */
    private static void appendText(StringBuilder sb, ChunkTextResolver text, String chunkId) {
        if (text == null) {
            return;
        }
        ChunkTextResolver.Resolved resolved = text.resolve(chunkId);
        sb.append(", \"text\": ");
        if (resolved.text() == null) {
            sb.append("null");
        } else {
            sb.append('"').append(JsonText.escape(resolved.text())).append('"');
        }
        sb.append(", \"text_truncated\": ").append(resolved.truncated());
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
     * Names the request fields a path accepted and could not act on.
     *
     * <p>Always emitted, empty list included: a caller cannot tell an unhonoured field from an
     * honoured one by its absence, and a field that only appears when something went wrong is
     * a field nobody looks for.</p>
     *
     * @param sb          The response being built
     * @param requestBody The raw request, read for which fields the caller actually sent
     * @param unhonoured  The fields this path cannot act on
     */
    private static void appendIgnoredFields(StringBuilder sb, String requestBody,
                                            List<String> unhonoured) {
        sb.append("\"ignored_fields\": [");
        boolean first = true;
        for (String field : unhonoured) {
            if (!JsonText.has(requestBody, field)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(JsonText.escape(field)).append('"');
            first = false;
        }
        sb.append("], ");
    }

    /**
     * Closes the envelope with what happened to the request itself.
     */
    private static void appendTail(StringBuilder sb, boolean timedOut, boolean cancelled,
                                   long durationMs) {
        sb.append("\"timed_out\": ").append(timedOut).append(", ");
        // A withdrawn request and an exhausted deadline produce the same empty, degraded
        // answer. Only this says which - or that neither happened and the corpus held no match.
        sb.append("\"cancelled\": ").append(cancelled).append(", ");
        sb.append("\"query_time_ms\": ").append(durationMs);
        sb.append("}");
    }
}
