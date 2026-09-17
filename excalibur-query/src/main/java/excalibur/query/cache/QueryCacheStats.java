package excalibur.query.cache;

/**
 * What the query result cache has done, so an operator can see whether it is helping.
 *
 * <h2>Why all five numbers</h2>
 * <p>A hit count alone says nothing: a cache with a thousand hits and a million misses is
 * costing the node memory and a fingerprint per request to save one query in a thousand. The
 * pair of them gives the ratio. {@code evictions} says the capacity is too small for the
 * working set - a cache that is evicting on nearly every insert is churning rather than
 * caching. {@code invalidations} says the opposite: entries are being discarded because the
 * corpus keeps moving, which is what a cache in front of a continuously ingesting node looks
 * like, and no amount of capacity will fix it. {@code size} against {@code capacity} is how
 * much memory it is actually holding.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable. One snapshot, read at one instant.</p>
 *
 * @param hits          Lookups answered from the cache
 * @param misses        Lookups that were not, including those refused by a moved corpus
 * @param evictions     Entries dropped to stay inside the capacity
 * @param invalidations Entries discarded because the corpus they were computed against moved
 * @param size          Entries held right now
 * @param capacity      The most entries this cache will ever hold
 * @author ExcaliburDB
 */
public record QueryCacheStats(long hits, long misses, long evictions, long invalidations,
                              int size, int capacity) {

    /**
     * Rejects counts that could not have been measured.
     *
     * @throws IllegalArgumentException if any count is negative
     */
    public QueryCacheStats {
        if (hits < 0 || misses < 0 || evictions < 0 || invalidations < 0 || size < 0
                || capacity < 0) {
            throw new IllegalArgumentException("Cache counts must be non-negative");
        }
    }

    /**
     * How many lookups the cache has been asked to answer.
     *
     * @return The sum of hits and misses
     */
    public long lookups() {
        return hits + misses;
    }

    /**
     * The share of lookups the cache answered.
     *
     * <p>Zero for a cache nobody has asked anything, rather than undefined: an operator
     * charting this wants a number, and "no lookups yet" is honestly reported as no hits.</p>
     *
     * @return A ratio in [0, 1]
     */
    public double hitRatio() {
        long total = lookups();
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
