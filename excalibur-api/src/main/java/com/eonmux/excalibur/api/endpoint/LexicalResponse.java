package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.cache.CachedAnswer;

import java.util.List;

/**
 * What an answer from BM25 alone produced, before a page is cut from it.
 *
 * <p>The lexical counterpart of the pipeline's {@code QueryResponse}: the ranking to the depth
 * the request needed, what the index says it searched, and what happened to the request on the
 * way. It is what {@code QueryResultCache} holds for a lexical answer, so it carries nothing that
 * belongs to one request's presentation - the page, the text and the explanation are built from
 * it again each time it is served.</p>
 *
 * <h2>When it may be cached</h2>
 * <p>Not on {@link IndexCoverage#degraded()}. On this route that flag is always set and means
 * the answer came from fewer signals than a fully configured node would use, which is a fact
 * about the node and as true of the next request as of this one. What makes a lexical answer a
 * report of one moment's trouble is a search that did not reach the whole index, a deadline, a
 * withdrawal, or a relevancy scorer that fell back; any one of those keeps it out.</p>
 *
 * @param queryId           The identifier this query was registered under, or null once stored
 * @param hits              The ranking, best first, as deep as the request's page needs
 * @param exhausted         Whether retrieval came back shallower than it was asked for, so
 *                          nothing past this ranking was left unconsidered - what lets the
 *                          total be exact; see {@link #total}
 * @param coverage          What the index says it searched
 * @param timedOut          Whether a deadline ended the search or its scoring
 * @param cancelled         Whether the caller withdrew the request
 * @param llmScored         Whether a relevancy model ordered the ranking
 * @param relevancyFellBack Whether scoring was planned and failed, leaving the BM25 order
 * @param queryTimeMs       How long the request took
 * @param fromCache         Whether this answer was replayed rather than searched
 * @author ExcaliburDB
 */
public record LexicalResponse(String queryId, List<LexicalQueryService.Hit> hits,
                              boolean exhausted, IndexCoverage coverage, boolean timedOut, boolean cancelled,
                              boolean llmScored, boolean relevancyFellBack, long queryTimeMs,
                              boolean fromCache) implements CachedAnswer {

    /**
     * Checks the answer carries a ranking and a coverage block.
     */
    public LexicalResponse {
        if (coverage == null) {
            throw new IllegalArgumentException("coverage must not be null");
        }
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /**
     * How many results there are, and how much that number is worth.
     *
     * <p>Exact only when the search reached the whole index inside its deadline and retrieval
     * ran out of matches before it ran out of depth. When a relevancy threshold then removed
     * candidates, a short list is exact only if the candidates were everything there was: a
     * threshold applied to a full candidate set says nothing about what lay past it.</p>
     *
     * @param depth How deep the request's page reaches, being offset plus page size
     * @return The total
     */
    public ResultTotal total(int depth) {
        return ResultTotal.of(hits.size(), depth,
            exhausted && ResultTotal.searchWasComplete(coverage, timedOut, cancelled));
    }

    @Override
    public boolean cacheable() {
        return !timedOut && !cancelled && !relevancyFellBack
            && coverage.estimatedCorpusCoverage() >= 1.0;
    }

    @Override
    public LexicalResponse forStorage() {
        return withQueryId(null);
    }

    @Override
    public LexicalResponse servedFromCache() {
        return new LexicalResponse(queryId, hits, exhausted, coverage, timedOut, cancelled, llmScored,
            relevancyFellBack, queryTimeMs, true);
    }

    /**
     * This answer, under the identifier of the request it is being served to.
     *
     * @param id The request's query id, or null
     * @return A copy carrying it
     */
    public LexicalResponse withQueryId(String id) {
        return new LexicalResponse(id, hits, exhausted, coverage, timedOut, cancelled, llmScored,
            relevancyFellBack, queryTimeMs, fromCache);
    }

    /**
     * This answer, reporting how long the request serving it took.
     *
     * @param ms The duration in milliseconds
     * @return A copy carrying it
     */
    public LexicalResponse withQueryTimeMs(long ms) {
        return new LexicalResponse(queryId, hits, exhausted, coverage, timedOut, cancelled, llmScored,
            relevancyFellBack, ms, fromCache);
    }
}
