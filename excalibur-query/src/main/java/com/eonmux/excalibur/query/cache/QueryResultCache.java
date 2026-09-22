package com.eonmux.excalibur.query.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded cache of query answers, valid only while the corpus has not moved.
 *
 * <p>An answer is any {@link CachedAnswer}: the vector pipeline's response, a BM25 ranking, or
 * a fused one. They share one capacity and one generation, because what they are answers about
 * is one corpus - a node whose routes kept separate generations could discard one route's
 * entries on a change and keep serving another's across it.</p>
 *
 * <h2>The invariant</h2>
 * <p><strong>Every entry in this cache was computed against exactly one corpus fingerprint,
 * and the cache answers only a lookup presenting that same fingerprint.</strong> The
 * fingerprint is read fresh, from the live corpus, immediately before each lookup; see
 * {@link CorpusVersion} for what it covers and why the visibility epoch on its own is not
 * enough. A lookup presenting a different fingerprint does not merely miss - it discards
 * every entry, because a corpus that moved invalidates all of them, not only the one that
 * happened to be asked for. So the cache holds one generation at a time, and the question
 * "could this entry be stale?" has one answer for the whole cache rather than one per
 * entry.</p>
 *
 * <p>A fingerprint that could not be taken ({@link CorpusVersion#UNREADABLE}) is neither
 * served from nor stored to. "I could not check whether anything changed" is not evidence
 * that nothing did.</p>
 *
 * <h2>What is refused</h2>
 * <p>{@link #cacheable(CachedAnswer)} is the rule, applied by {@link #put} so that no caller
 * can forget it, and each answer type says what it means for itself. A degraded, timed-out or
 * cancelled answer is never stored. Such an answer is
 * a report about one moment's trouble - a segment that errored, a tail scan that stopped
 * short, an LLM scorer that fell back - and replaying it to later callers turns a transient
 * fault into a persistent one that outlives its cause.</p>
 *
 * <h2>Bounded memory</h2>
 * <p>Strictly at most {@code capacity} entries, least-recently-used first out. An entry holds
 * one answer, whose rankings are bounded by the request's own depth and thus by
 * {@code excalibur.api.max-top-k}; the cache's worst case is therefore a small multiple of
 * {@code capacity x max-top-k} result items, and it is reached rather than approached, since
 * eviction happens on insert rather than on a timer. A cache that grows with the number of
 * distinct queries is an out-of-memory error with a delay, which is why there is no unbounded
 * mode.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>Thread-safe.</strong> Every read, write and count happens under one monitor, so
 * a statistics snapshot is internally consistent and the size bound holds under any number of
 * concurrent queries. The critical section is a single map operation.</p>
 *
 * @author ExcaliburDB
 */
public final class QueryResultCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryResultCache.class);

    /**
     * The smallest cache that is still a cache.
     *
     * <p>A capacity of zero is spelled by not configuring a cache at all, which costs nothing
     * per request; a zero-capacity cache would still take a fingerprint of the corpus on
     * every query and then throw the answer away.</p>
     */
    public static final int MIN_CAPACITY = 1;

    private final int capacity;
    private final Object lock = new Object();
    private final Map<QueryCacheKey, CachedAnswer> entries;

    /** The one corpus every entry currently held was computed against. */
    private CorpusVersion generation = CorpusVersion.UNREADABLE;

    private long hits;
    private long misses;
    private long evictions;
    private long invalidations;

    /**
     * Creates a cache holding at most {@code capacity} answers.
     *
     * @param capacity The most entries the cache may hold
     * @throws IllegalArgumentException if capacity is below {@link #MIN_CAPACITY}
     */
    public QueryResultCache(int capacity) {
        if (capacity < MIN_CAPACITY) {
            // Refused rather than clamped: a deployment that asked for a cache of zero meant
            // to turn caching off, and silently giving it one entry would leave it serving
            // cached answers it believes it disabled.
            throw new IllegalArgumentException(
                "Cache capacity must be at least " + MIN_CAPACITY + ": " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<QueryCacheKey, CachedAnswer> eldest) {
                if (size() <= QueryResultCache.this.capacity) {
                    return false;
                }
                evictions++;
                return true;
            }
        };
        LOGGER.info("Query result cache enabled with capacity {}", capacity);
    }

    /**
     * Returns the answer stored under {@code key}, if it is still an answer.
     *
     * <p>{@code version} must be the corpus fingerprint as it stands now, not the one the
     * entry was stored with: the whole point is to compare them. When they differ every entry
     * is discarded before this returns.</p>
     *
     * <p>{@code type} is the answer type the caller's route produces. The key names the route,
     * so an entry of another type can only be there if two routes built the same key, and that
     * is answered as a miss rather than as a cast failure in the caller.</p>
     *
     * @param key     The request, naming every input that can change the answer
     * @param version The corpus fingerprint read immediately before this lookup
     * @param type    The answer type the caller can read
     * @param <A>     The answer type
     * @return The cached answer marked {@link CachedAnswer#servedFromCache() from cache}, or
     *         null when there is none or the corpus has moved
     * @throws NullPointerException if any argument is null
     */
    public <A extends CachedAnswer> A get(QueryCacheKey key, CorpusVersion version,
                                          Class<A> type) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(type, "type must not be null");

        synchronized (lock) {
            if (!version.readable()) {
                misses++;
                return null;
            }
            if (!version.equals(generation)) {
                discardGeneration(version);
                misses++;
                return null;
            }
            CachedAnswer held = entries.get(key);
            if (held == null || !type.isInstance(held)) {
                misses++;
                return null;
            }
            hits++;
            return type.cast(held.servedFromCache());
        }
    }

    /**
     * Stores an answer against the corpus it was computed from.
     *
     * <p>Ignored silently in exactly three cases, each of them a refusal rather than a
     * failure: the answer is not {@link #cacheable(CachedAnswer)}, the fingerprint could not
     * be read, or the corpus moved while the query ran.</p>
     *
     * <p>That third case is the subtle one. A slow query fingerprints the corpus, takes a
     * second, and comes back to a cache that has since moved on to a later generation. Its
     * answer is not wrong, but it is an answer about a corpus that no longer exists, so it is
     * dropped. What must not happen instead is for it to be adopted: taking the older
     * fingerprint as the cache's generation would be sound - entries are still only served to
     * a lookup presenting the generation they were stored at - but it would throw away the
     * entries that are still answers, and on a node with any write traffic the cache would
     * spend its life being rolled backwards by whichever query happened to be slowest.</p>
     *
     * <p>The answer is stored as {@link CachedAnswer#forStorage()} gives it back. The query id
     * identifies the request that produced the answer, and handing a later caller an id
     * belonging to somebody else's query is a cancellation handle for a query they do not
     * own.</p>
     *
     * @param key      The request, naming every input that can change the answer
     * @param version  The corpus fingerprint read before the query ran
     * @param response The answer
     * @throws NullPointerException if the key or version is null
     */
    public void put(QueryCacheKey key, CorpusVersion version, CachedAnswer response) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(version, "version must not be null");

        if (!cacheable(response) || !version.readable()) {
            return;
        }

        synchronized (lock) {
            if (!version.equals(generation)) {
                if (generation.readable()) {
                    // The corpus moved while this query ran. See above: dropped rather than
                    // adopted, so a slow query cannot roll the cache back over the answers
                    // that are still current.
                    return;
                }
                // A cache with no generation yet - freshly built, or just cleared - adopts
                // the first one it is offered.
                generation = version;
            }
            entries.put(key, response.forStorage());
        }
    }

    /**
     * Whether an answer is one this cache may keep.
     *
     * <p>The rule, in one place, so that the cache and anything reasoning about it cannot
     * disagree: only an answer from a search that reached the whole corpus it meant to, within
     * its deadline, and was not withdrawn - which each answer type states for itself in
     * {@link CachedAnswer#cacheable()}, since what "reached the whole corpus" means differs
     * between a segment fan-out and a BM25 pass.</p>
     *
     * @param response The answer, which may be null
     * @return true when it may be stored
     */
    public static boolean cacheable(CachedAnswer response) {
        return response != null && response.cacheable();
    }

    /**
     * Discards everything, for a caller that knows the corpus is no longer what it was.
     *
     * <p>Not needed in the normal course - a moved fingerprint discards on its own - but a
     * node that is draining or reopening its storage has no reason to keep answers to
     * questions about a corpus it is putting down.</p>
     */
    public void clear() {
        synchronized (lock) {
            discardGeneration(CorpusVersion.UNREADABLE);
        }
    }

    /**
     * The most entries this cache will hold.
     *
     * @return The capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * What this cache has done since it was created.
     *
     * @return One consistent snapshot
     */
    public QueryCacheStats stats() {
        synchronized (lock) {
            return new QueryCacheStats(hits, misses, evictions, invalidations,
                entries.size(), capacity);
        }
    }

    /**
     * Throws away every entry and adopts a new corpus fingerprint.
     *
     * <p>Counted as invalidations rather than evictions: the two mean opposite things to
     * whoever reads them. An eviction says the cache is too small for the working set; an
     * invalidation says the corpus is moving faster than the cache can be useful, and no
     * amount of capacity will change that.</p>
     *
     * <p>The caller must hold {@link #lock}.</p>
     *
     * @param version The fingerprint every subsequent entry will be stored against
     */
    private void discardGeneration(CorpusVersion version) {
        int discarded = entries.size();
        if (discarded > 0) {
            entries.clear();
            invalidations += discarded;
            LOGGER.debug("Corpus moved from {} to {}; discarded {} cached answer(s)",
                generation, version, discarded);
        }
        generation = version;
    }
}
