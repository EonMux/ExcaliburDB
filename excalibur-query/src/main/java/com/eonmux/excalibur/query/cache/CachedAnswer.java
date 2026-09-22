package com.eonmux.excalibur.query.cache;

/**
 * An answer {@link QueryResultCache} may hold.
 *
 * <h2>Why an interface rather than one response type</h2>
 * <p>{@code POST /v1/query} answers through three retrieval routes - vector search, BM25, and
 * the two fused - and each produces a different shape: a pipeline response, a lexical ranking,
 * a fused ranking with the two it was built from. What the cache needs from any of them is the
 * same three things, and none of them is the shape: whether the answer is fit to be replayed,
 * a copy with nothing in it that belongs to one request, and a copy that says it was replayed.
 * One cache over all three is what keeps one capacity, one set of statistics and one corpus
 * generation for the node rather than three that can disagree.</p>
 *
 * <p>An implementation is immutable: the cache hands the same stored instance to every lookup
 * that finds it, and relies on {@link #servedFromCache()} returning a new value rather than
 * marking the stored one.</p>
 *
 * @author ExcaliburDB
 */
public interface CachedAnswer {

    /**
     * Whether this answer may be replayed to a later caller.
     *
     * <p>Only an answer from a search that reached the whole corpus it meant to, inside its
     * deadline, with every stage that was meant to run having run, and not withdrawn. An answer
     * that is a report of one moment's trouble - a segment that errored, a relevancy scorer
     * that fell back, a deadline that cut a stage short - must say false, or the trouble
     * outlives its cause.</p>
     *
     * @return true when the answer may be stored
     */
    boolean cacheable();

    /**
     * This answer with everything that identifies the request that produced it removed.
     *
     * <p>The query id above all: handing a later caller an id belonging to somebody else's
     * query is a cancellation handle for a query they do not own.</p>
     *
     * @return The answer as it may be stored
     */
    CachedAnswer forStorage();

    /**
     * This answer, marked as having come out of the cache rather than out of a search.
     *
     * @return A copy reporting {@code from_cache}
     */
    CachedAnswer servedFromCache();
}
