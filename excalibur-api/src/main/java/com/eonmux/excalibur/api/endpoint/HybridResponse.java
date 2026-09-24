package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.cache.CachedAnswer;
import com.eonmux.excalibur.retrieval.expand.Expansion;

import java.util.List;
import java.util.Objects;

/**
 * What a fused answer produced, before a page is cut from it.
 *
 * <p>The fused ranking together with the two it was built from. Both inputs are kept because an
 * explanation reports each signal's own rank and score, and fusion is where those stop
 * existing; the vector half is kept whole because its coverage and timings are the answer's.
 * This is what {@code QueryResultCache} holds for a fused answer, so a repeated question skips
 * the embedding call, the segment fan-out, the BM25 search and the relevancy model together.</p>
 *
 * <h2>Scoring after fusion</h2>
 * <p>When relevancy scoring runs on a fused answer it runs on the fused ranking, and what it did
 * is recorded here rather than in the vector half: a scorer that fell back or ran out of time
 * degrades the whole answer, not the vector search that finished cleanly.</p>
 *
 * <h2>An expansion that fell back</h2>
 * <p>On a node that widens the BM25 half, a query the model could not widen was searched as
 * typed: the answer is real, and it came from a weaker lexical signal than this node runs. It is
 * reported as degraded coverage, like any other signal that did less than it was configured to,
 * and it is never cached - the next caller asking the same question is owed the widened
 * search.</p>
 *
 * @param vector            The vector pipeline's answer, whose coverage, timings and identity
 *                          this answer reports
 * @param lexical           The BM25 ranking that was fused, best first
 * @param expansion         What the BM25 half widened the query into, or null when its service
 *                          cannot say
 * @param fused             The fused ranking, best first, as deep as the request's page needs
 * @param exhausted         Whether fusion came back shallower than it was asked for, so
 *                          nothing past this ranking was left unconsidered; see {@link #total}
 * @param llmScored         Whether a relevancy model ordered the fused ranking
 * @param relevancyFellBack Whether scoring was planned and failed, leaving the fused order
 * @param relevancyTimedOut Whether the deadline passed before the scorer answered
 * @author ExcaliburDB
 */
public record HybridResponse(QueryResponse vector, List<LexicalQueryService.Hit> lexical,
                             Expansion expansion,
                             List<HybridFusion.Result> fused, boolean exhausted,
                             boolean llmScored,
                             boolean relevancyFellBack, boolean relevancyTimedOut)
        implements CachedAnswer {

    /**
     * Checks both rankings and the vector answer are present.
     */
    public HybridResponse {
        Objects.requireNonNull(vector, "vector must not be null");
        lexical = lexical == null ? List.of() : List.copyOf(lexical);
        fused = fused == null ? List.of() : List.copyOf(fused);
    }

    /**
     * An answer that was not scored after fusion.
     *
     * @param vector    The vector pipeline's answer
     * @param lexical   The BM25 ranking that was fused
     * @param expansion What the BM25 half widened the query into, or null
     * @param fused     The fused ranking
     * @param depth     How deep fusion was asked to rank
     * @return The answer
     */
    public static HybridResponse unscored(QueryResponse vector,
                                          List<LexicalQueryService.Hit> lexical,
                                          Expansion expansion,
                                          List<HybridFusion.Result> fused, int depth) {
        return new HybridResponse(vector, lexical, expansion, fused, fused.size() < depth, false,
            false, false);
    }

    /**
     * How many results there are, and how much that number is worth.
     *
     * <p>The vector search's coverage decides whether the corpus was reached, as it does for a
     * vector answer. When a relevancy threshold removed candidates, a short list is exact only
     * if fusion had run out of candidates to give it.</p>
     *
     * @param depth How deep the request's page reaches, being offset plus page size
     * @return The total
     */
    public ResultTotal total(int depth) {
        return ResultTotal.of(fused.size(), depth, exhausted
            && ResultTotal.searchWasComplete(coverage(), timedOut(), vector.cancelled()));
    }

    /**
     * The coverage this answer reports: the vector search's, marked degraded when scoring after
     * fusion was planned and did not finish, or when the BM25 half's expansion fell back.
     *
     * @return The coverage block
     */
    public IndexCoverage coverage() {
        return vector.coverage().orDegraded(
            relevancyFellBack || relevancyTimedOut || expansionFellBack());
    }

    /**
     * Whether the BM25 half meant to widen the query and could not, and so searched it as typed.
     *
     * @return true when its expansion fell back
     */
    public boolean expansionFellBack() {
        return expansion != null && expansion.fellBack();
    }

    /**
     * Whether a deadline cut this answer short, in the vector search or in its scoring.
     *
     * @return true when either ran out of time
     */
    public boolean timedOut() {
        return vector.timedOut() || relevancyTimedOut;
    }

    @Override
    public boolean cacheable() {
        return vector.cacheable() && !relevancyFellBack && !relevancyTimedOut
            && !expansionFellBack();
    }

    @Override
    public HybridResponse forStorage() {
        return withVector(vector.forStorage());
    }

    @Override
    public HybridResponse servedFromCache() {
        return withVector(vector.servedFromCache());
    }

    /**
     * This answer, under the identifier of the request it is being served to.
     *
     * @param queryId The request's query id, or null
     * @return A copy carrying it
     */
    public HybridResponse withQueryId(String queryId) {
        return withVector(vector.withQueryId(queryId));
    }

    /**
     * This answer, reporting how long the request serving it took.
     *
     * @param ms The duration in milliseconds
     * @return A copy carrying it
     */
    public HybridResponse withQueryTimeMs(long ms) {
        return withVector(vector.withQueryTimeMs(ms));
    }

    private HybridResponse withVector(QueryResponse replacement) {
        return new HybridResponse(replacement, lexical, expansion, fused, exhausted, llmScored,
            relevancyFellBack, relevancyTimedOut);
    }
}
