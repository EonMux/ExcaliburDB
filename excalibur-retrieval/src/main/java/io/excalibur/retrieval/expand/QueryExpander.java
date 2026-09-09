package io.excalibur.retrieval.expand;

import java.util.List;

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
 * query, so a retrieval path built on this degrades to plain BM25 rather than to nothing.
 */
public interface QueryExpander {

    /**
     * Expands a query into weighted search formulations.
     *
     * @param query The user's query
     * @return Formulations, always containing the original query, best first
     */
    List<SearchFormulation> expand(String query);

    /** An expander that adds nothing, for deployments with no model to call. */
    static QueryExpander identity() {
        return query -> List.of(SearchFormulation.original(query));
    }
}
