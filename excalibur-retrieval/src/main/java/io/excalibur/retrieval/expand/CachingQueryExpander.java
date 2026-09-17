package io.excalibur.retrieval.expand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers what a query was widened into, so that asking twice costs once.
 *
 * <h2>Why a query is expanded twice at all</h2>
 * <p>Once by the ranker, which needs the formulations to search with, and once by the query
 * route, which needs them to tell the caller what the query was widened with. Those are two
 * different consumers of one decision, and the decision is a generation from a chat model: the
 * expensive part of the query path, and one that does not return the same answer twice. Running
 * it again to describe it would both double the bill and risk describing an expansion that is
 * not the one the search used.</p>
 *
 * <p>So the answer is remembered rather than recomputed, and the same instance is given to the
 * index and to whatever reports on it. A hit is what makes the report free and exact; a miss
 * costs no more than the underlying expander would have.</p>
 *
 * <h2>The bound</h2>
 * <p>Queries are caller-supplied strings, so an unbounded map of them is an unbounded
 * allocation. The cache holds a fixed number of the most recently expanded and evicts the
 * least recently used, which is the right shape for the traffic it sees: the reporting read
 * follows its own search by milliseconds, so the entry it needs is always the newest one.</p>
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
     * <p>Sized for the job rather than as a cache of the workload: the only read that has to
     * hit is the one describing the search that just ran, so the useful depth is the number of
     * queries in flight at once, and this is well past that on any node these bounds allow.</p>
     */
    public static final int DEFAULT_CAPACITY = 256;

    private final QueryExpander delegate;
    private final Map<String, List<SearchFormulation>> remembered;

    private CachingQueryExpander(QueryExpander delegate, int capacity) {
        this.delegate = delegate;
        this.remembered = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<String, List<SearchFormulation>> eldest) {
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

    @Override
    public List<SearchFormulation> expand(String query) {
        Objects.requireNonNull(query, "query must not be null");
        synchronized (remembered) {
            List<SearchFormulation> hit = remembered.get(query);
            if (hit != null) {
                return hit;
            }
        }
        // Deliberately outside the lock. The delegate may be an outbound model call taking
        // seconds, and holding the map across it would make every other query queue behind a
        // cache it could have read in nanoseconds.
        List<SearchFormulation> expanded = List.copyOf(delegate.expand(query));
        synchronized (remembered) {
            remembered.put(query, expanded);
        }
        return expanded;
    }

    @Override
    public int maxExpandableChars() {
        return delegate.maxExpandableChars();
    }
}
