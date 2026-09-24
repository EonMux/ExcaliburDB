package com.eonmux.excalibur.retrieval.expand;

import java.util.List;

/**
 * What one query was widened into, and whether the widening was the one that was meant.
 *
 * <h2>Why the fallback is said and not just returned</h2>
 * <p>An expander never fails a search: a model that is down, slow, behind an open breaker or
 * answering with something unreadable leaves the query searched as typed. Returned bare, that
 * fallback is indistinguishable from an expander that looked at the query and chose to add
 * nothing - and the two are opposite facts to everything downstream. A deliberate answer may be
 * remembered and replayed; a fallback is the product of one moment's trouble, and replaying it
 * turns that moment into a persistent narrowing of recall that nothing reports. So the
 * expander says which it was, and what remembers answers - {@link CachingQueryExpander}, the
 * query result cache - refuses the second kind.</p>
 *
 * @param formulations The formulations to search, the user's own query first and the rest best
 *                     first; empty only for a query with no text
 * @param fellBack     Whether the expander meant to widen the query and could not, so the
 *                     formulations are the query as typed rather than its judgement
 */
public record Expansion(List<SearchFormulation> formulations, boolean fellBack) {

    /**
     * Copies the formulations so an expansion cannot change after a search has used it.
     */
    public Expansion {
        formulations = List.copyOf(formulations);
    }

    /**
     * An expansion the expander decided on, however much or little it added.
     *
     * @param formulations The formulations, the user's own query first
     * @return The expansion
     */
    public static Expansion of(List<SearchFormulation> formulations) {
        return new Expansion(formulations, false);
    }

    /**
     * The query as typed, standing in for an expansion that could not be had.
     *
     * @param query The user's query, not blank
     * @return The expansion, marked as a fallback
     */
    public static Expansion fellBack(String query) {
        return new Expansion(List.of(SearchFormulation.original(query)), true);
    }
}
