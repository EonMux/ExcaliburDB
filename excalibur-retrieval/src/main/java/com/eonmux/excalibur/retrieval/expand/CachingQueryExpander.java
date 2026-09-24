package com.eonmux.excalibur.retrieval.expand;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers what a query was widened into, so that asking twice costs once.
 *
 * <h2>Why a query is expanded more than once at all</h2>
 * <p>Because callers ask the same question more than once. A caller paging through an answer
 * sends the query again for every page, since a ranked index has no cursor to resume, and the
 * expansion is a generation from a chat model: the expensive part of the query path, and one
 * that does not return the same answer twice. Generating it again per page would both multiply
 * the bill and rank each page by different terms, so that pages of one answer overlap and skip.
 * The answer is remembered rather than recomputed, and a hit costs neither.</p>
 *
 * <p>Within one query the expansion is resolved once and carried with the search, so nothing in
 * a single request reads it twice; this is about requests, not about the steps of one.</p>
 *
 * <h2>What is not remembered</h2>
 * <p>A fallback. When the delegate could not widen the query - the model timed out, failed,
 * sat behind an open breaker or answered with nothing usable - what it returns is the query as
 * typed, marked {@link Expansion#fellBack()}. That is the product of one moment's trouble, and
 * remembering it would replay the trouble to every later caller asking the same question for as
 * long as the entry survived: plain BM25, from a node whose model had long recovered. A fallback
 * costs the next caller one more attempt, which is what an outage should cost.</p>
 *
 * <h2>The bound</h2>
 * <p>Queries are caller-supplied strings, so an unbounded map of them is an unbounded
 * allocation. The cache holds a fixed number of the most recently expanded and evicts the
 * least recently used, which is the right shape for the traffic it sees: a caller paging through
 * an answer asks again within seconds, so the entry it needs is among the newest.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Reads and writes are serialised on the map, which is held only
 * for the lookup and the store - never across the underlying expansion, so a slow model call
 * cannot block another query's cache hit. Two queries for the same text that miss together
 * both expand, and the second store wins; that costs one extra generation and never a wrong
 * answer.</p>
 */
public final class CachingQueryExpander implements QueryExpander {

    /**
     * How many distinct queries are remembered.
     *
     * <p>Sized for the job rather than as a cache of the workload: the reads that have to hit
     * are the pages of an answer being read now, so the useful depth is the number of questions
     * being paged through at once, and this is well past that on any node these bounds
     * allow.</p>
     */
    public static final int DEFAULT_CAPACITY = 256;

    private final QueryExpander delegate;
    private final Map<String, Expansion> remembered;

    private CachingQueryExpander(QueryExpander delegate, int capacity) {
        this.delegate = delegate;
        this.remembered = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Expansion> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Wraps an expander so its answers can be read back without being recomputed.
     *
     * @param delegate The expander doing the work, or null for one that widens nothing
     * @return The caching expander
     */
    public static CachingQueryExpander over(QueryExpander delegate) {
        return over(delegate, DEFAULT_CAPACITY);
    }

    /**
     * Wraps an expander with an explicit cache depth.
     *
     * @param delegate The expander doing the work, or null for one that widens nothing
     * @param capacity How many distinct queries are remembered
     * @return The caching expander
     * @throws IllegalArgumentException if capacity is not positive
     */
    public static CachingQueryExpander over(QueryExpander delegate, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        return new CachingQueryExpander(
            delegate == null ? QueryExpander.identity() : delegate, capacity);
    }

    /**
     * The expander this cache answers for when it has not seen a query.
     *
     * <p>So what the node reports about expansion - its circuit breaker - can be read through
     * the cache, which has no state of that kind of its own.</p>
     *
     * @return The wrapped expander
     */
    public QueryExpander delegate() {
        return delegate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A remembered expansion is answered at once, whatever the budget. A miss spends the
     * budget on the delegate, and its answer is remembered unless it is a fallback.</p>
     */
    @Override
    public Expansion expand(String query, Duration budget) {
        Objects.requireNonNull(query, "query must not be null");
        synchronized (remembered) {
            Expansion hit = remembered.get(query);
            if (hit != null) {
                return hit;
            }
        }
        // Deliberately outside the lock. The delegate may be an outbound model call taking
        // seconds, and holding the map across it would make every other query queue behind a
        // cache it could have read in nanoseconds.
        Expansion expanded = delegate.expand(query, budget);
        if (!expanded.fellBack()) {
            synchronized (remembered) {
                remembered.put(query, expanded);
            }
        }
        return expanded;
    }
}
