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
 */
public record SearchAnswer(SearchRoute route, List<SearchHit> hits, boolean timedOut) {

    /**
     * @throws NullPointerException if route or hits is null
     */
    public SearchAnswer {
        Objects.requireNonNull(route, "route cannot be null");
        hits = List.copyOf(hits);
    }
}
