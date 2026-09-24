package com.eonmux.excalibur.api.embedded;

import java.util.List;
import java.util.Objects;

/**
 * What a search answered, and how.
 *
 * @param route    The signals that produced the hits, which on a failure of one signal is not the
 *                 route the node plans - {@link ExcaliburEmbedded#route()} is that
 * @param hits     The chunks, best first
 * @param timedOut Whether the search ran out of its budget, so a short list is not a claim that
 *                 nothing else matched
 * @param degraded Whether the answer came from less than this node set out to use: a signal
 *                 that failed and left the other to answer, a vector search that did not reach
 *                 every segment, or a query expansion that fell back to the query as typed.
 *                 Measured against {@link ExcaliburEmbedded#route()}, so a node that has only
 *                 BM25 answering from BM25 is not degraded - unlike the route's
 *                 {@code coverage.degraded}, which is measured against a fully configured node
 */
public record SearchAnswer(SearchRoute route, List<SearchHit> hits, boolean timedOut,
                           boolean degraded) {

    /**
     * @throws NullPointerException if route or hits is null
     */
    public SearchAnswer {
        Objects.requireNonNull(route, "route cannot be null");
        hits = List.copyOf(hits);
    }
}
