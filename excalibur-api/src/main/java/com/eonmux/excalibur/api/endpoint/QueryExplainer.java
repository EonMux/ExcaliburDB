package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.QueryResultItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the account an answer gives of itself.
 *
 * <h2>Why here and not in the renderer</h2>
 * <p>Which signal ranked a document where is a fact about the two rankings that were fused,
 * and both of them stop existing the moment the fused list is built. Working it out is a
 * question about retrieval; writing it down is a question about JSON, and the second one has
 * no way to answer the first.</p>
 *
 * <h2>Ranks are absolute</h2>
 * <p>Counted from the start of the whole ranking, not from the start of the page. A result
 * that is first on page three is not first in anything, and a rank that resets per page cannot
 * be compared against the rank of the same document under a different page size.</p>
 */
final class QueryExplainer {

    private QueryExplainer() {
    }

    /**
     * Explains a page of vector results.
     *
     * @param page      The results this answer returns, in order
     * @param offset    How many results were skipped before the page, so ranks stay absolute
     * @param passages  Reads what the query matched in each result's text
     * @param expansion What the query was widened with
     * @return The explanation
     */
    static QueryExplanation vector(List<QueryResultItem> page, int offset,
                                   ResultPassages passages, QueryExpansion expansion) {
        List<ResultExplanation> explained = new ArrayList<>(page.size());
        for (int i = 0; i < page.size(); i++) {
            QueryResultItem item = page.get(i);
            explained.add(new ResultExplanation(
                List.of(new ResultExplanation.Signal(HybridFusion.DENSE_SIGNAL,
                    offset + i + 1, item.score())),
                passages.of(item.externalId()).matchedTerms()));
        }
        return new QueryExplanation(expansion, explained);
    }

    /**
     * Explains a page of BM25 results.
     *
     * @param page      The hits this answer returns, in order
     * @param offset    How many hits were skipped before the page, so ranks stay absolute
     * @param passages  Reads what the query matched in each hit's text
     * @param expansion What the query was widened with
     * @return The explanation
     */
    static QueryExplanation lexical(List<LexicalQueryService.Hit> page, int offset,
                                    ResultPassages passages, QueryExpansion expansion) {
        List<ResultExplanation> explained = new ArrayList<>(page.size());
        for (int i = 0; i < page.size(); i++) {
            LexicalQueryService.Hit hit = page.get(i);
            explained.add(new ResultExplanation(
                List.of(new ResultExplanation.Signal(HybridFusion.LEXICAL_SIGNAL,
                    offset + i + 1, hit.score())),
                passages.of(hit.chunkId()).matchedTerms()));
        }
        return new QueryExplanation(expansion, explained);
    }

    /**
     * Explains a page of fused results, from the two rankings that produced them.
     *
     * <p>Each signal is looked up in its own ranking, which is where its own rank and its own
     * score still exist. A signal the fused result names but that cannot be found in its
     * ranking is left out rather than reported with a made-up position.</p>
     *
     * @param page      The fused results this answer returns, in order
     * @param dense     The whole vector ranking, best first
     * @param lexical   The whole BM25 ranking, best first
     * @param passages  Reads what the query matched in each result's text
     * @param expansion What the query was widened with
     * @return The explanation
     */
    static QueryExplanation hybrid(List<HybridFusion.Result> page, List<QueryResultItem> dense,
                                   List<LexicalQueryService.Hit> lexical,
                                   ResultPassages passages, QueryExpansion expansion) {
        Map<String, ResultExplanation.Signal> denseRanks = denseRanks(dense);
        Map<String, ResultExplanation.Signal> lexicalRanks = lexicalRanks(lexical);
        List<ResultExplanation> explained = new ArrayList<>(page.size());
        for (HybridFusion.Result result : page) {
            List<ResultExplanation.Signal> signals = new ArrayList<>(result.signals().size());
            for (String signal : result.signals()) {
                ResultExplanation.Signal contribution =
                    HybridFusion.LEXICAL_SIGNAL.equals(signal)
                        ? lexicalRanks.get(result.documentId())
                        : denseRanks.get(result.documentId());
                if (contribution != null) {
                    signals.add(contribution);
                }
            }
            explained.add(new ResultExplanation(signals,
                passages.of(result.documentId()).matchedTerms()));
        }
        return new QueryExplanation(expansion, explained);
    }

    /**
     * Where each document sat in the vector ranking, and what it scored there.
     *
     * <p>Keyed the way the fused result names the document. A vector result the pipeline could
     * not name carries its node id as its identifier, and two of those from different segments
     * can share a number; the better-ranked one wins, which is the one worth reporting.</p>
     *
     * @param dense The whole vector ranking, best first
     * @return The contribution of each document, by identifier
     */
    private static Map<String, ResultExplanation.Signal> denseRanks(List<QueryResultItem> dense) {
        Map<String, ResultExplanation.Signal> ranks = new HashMap<>();
        if (dense == null) {
            return ranks;
        }
        for (int i = 0; i < dense.size(); i++) {
            QueryResultItem item = dense.get(i);
            ranks.putIfAbsent(item.externalId(), new ResultExplanation.Signal(
                HybridFusion.DENSE_SIGNAL, i + 1, item.score()));
        }
        return ranks;
    }

    /**
     * Where each chunk sat in the BM25 ranking, and what it scored there.
     *
     * @param lexical The whole BM25 ranking, best first
     * @return The contribution of each chunk, by identifier
     */
    private static Map<String, ResultExplanation.Signal> lexicalRanks(
            List<LexicalQueryService.Hit> lexical) {
        Map<String, ResultExplanation.Signal> ranks = new HashMap<>();
        if (lexical == null) {
            return ranks;
        }
        for (int i = 0; i < lexical.size(); i++) {
            LexicalQueryService.Hit hit = lexical.get(i);
            ranks.putIfAbsent(hit.chunkId(), new ResultExplanation.Signal(
                HybridFusion.LEXICAL_SIGNAL, i + 1, hit.score()));
        }
        return ranks;
    }
}
