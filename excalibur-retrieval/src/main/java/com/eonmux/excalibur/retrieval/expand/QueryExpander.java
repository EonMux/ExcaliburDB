package com.eonmux.excalibur.retrieval.expand;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns one natural-language query into several ways of searching for it.
 *
 * <p>This is the seam that lets semantic work happen on the query instead of on the corpus. A
 * user asking why their service dies when the machine runs out of RAM is looking for documents
 * that say {@code OOM killer}, {@code ENOMEM}, {@code SIGKILL} or {@code cgroup memory limit} -
 * none of which share vocabulary with the question. Embedding the whole corpus is one way to
 * bridge that gap; generating the vocabulary at query time is another, and it is billions of
 * tokens cheaper because the query is a sentence and the corpus is not.
 *
 * <p>An expander must never fail a search. Every implementation returns at least the user's own
 * query, so a retrieval path built on this degrades to plain BM25 rather than to nothing - and
 * says, through {@link Expansion#fellBack()}, when that is what happened.
 *
 * <p>An expander is given a budget, because it runs inside a request somebody is holding open
 * and the search it serves cannot start until it returns. What it cannot finish inside the
 * budget it abandons, answering with the query as typed.
 */
public interface QueryExpander {

    /**
     * The budget of a caller with no deadline of its own: the expander's own bound is the only
     * one that applies.
     */
    Duration UNBOUNDED = ChronoUnit.FOREVER.getDuration();

    /**
     * Expands a query into weighted search formulations.
     *
     * @param query  The user's query
     * @param budget How long the caller can wait for the expansion; {@link #UNBOUNDED} when it
     *               has no deadline
     * @return The formulations, always containing the original query, best first, and whether
     *         they are a fallback
     */
    Expansion expand(String query, Duration budget);

    /**
     * An expander whose answer calls nothing that can fail or be slow.
     *
     * <p>For formulations worked out in-process - a fixed vocabulary, a test's scripted answer -
     * which are never a fallback and need none of the budget.</p>
     *
     * @param formulations The formulations for a query, the original first
     * @return The expander
     * @throws NullPointerException if formulations is null
     */
    static QueryExpander infallible(Function<String, List<SearchFormulation>> formulations) {
        Objects.requireNonNull(formulations, "formulations must not be null");
        return (query, budget) -> Expansion.of(formulations.apply(query));
    }

    /** An expander that adds nothing, for deployments with no model to call. */
    static QueryExpander identity() {
        return infallible(query -> List.of(SearchFormulation.original(query)));
    }
}
