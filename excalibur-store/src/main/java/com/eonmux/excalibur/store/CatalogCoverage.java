package com.eonmux.excalibur.store;

/**
 * How much of what this node holds a query can actually read.
 *
 * <p>The node-level readiness signal, derived from the per-state segment counts a
 * {@link SegmentCatalog.CatalogMetadata} snapshot carries. It is worth its own class
 * because the arithmetic is two lines and the reason for it is not: an earlier definition
 * that looked equally sensible reported a healthy node as a third broken.</p>
 *
 * <h2>The live corpus is both halves</h2>
 * <p>The numerator is what a query may read - INDEXING, WARMING, ACTIVE or COMPACTING. The
 * denominator is what this node holds and has not retired, where retired means TOMBSTONED
 * or PURGED.</p>
 *
 * <p>Subtracting only the purged ones leaves a denominator that is really the whole
 * catalog, because PURGED is not a state the catalog ever holds:
 * {@link SegmentCatalog#remove(long)} sets it and drops the record in the same breath, and
 * recovery skips any that was persisted. A tombstoned segment would then sit in the
 * denominator and never in the numerator, so a node that had compacted a third of its
 * segments would report a third of its corpus permanently gone - with no state it can
 * reach to recover, because reclamation may legitimately never come.</p>
 *
 * <h2>What it can and cannot say</h2>
 * <p>Every state the catalog holds is either queryable or retired, so on a node whose
 * segments are all accounted for this reads 1.0. What it detects is a segment stranded in
 * a state no query reads, and an index with no live corpus at all. It is not a warm-up
 * gauge: a segment still absorbing its write log is queryable and is searched, so it
 * counts, and the per-query {@code estimated_corpus_coverage} is where a partial answer
 * shows up instead.</p>
 *
 * <p>Zero when there is no live corpus. An empty index has nothing to read, which is the
 * reading that keeps "coverage is low, this node is not ready" true in every case it is
 * consulted.</p>
 */
final class CatalogCoverage {

    private CatalogCoverage() {
    }

    /**
     * The live corpus: everything the catalog holds that is not retired.
     *
     * @param total      Every segment the catalog holds
     * @param tombstoned Segments awaiting reclamation
     * @param purged     Segments whose disk has been freed
     * @return The count, which may be zero
     */
    static long liveSegments(long total, long tombstoned, long purged) {
        return total - tombstoned - purged;
    }

    /**
     * The share of the live corpus a query can read, clamped into [0, 1].
     *
     * @param queryable Segments in a state a query may read
     * @param live      The live corpus, from {@link #liveSegments(long, long, long)}
     * @return The ratio, or 0.0 when there is no live corpus
     */
    static double estimatedCoverage(long queryable, long live) {
        if (live <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) queryable / live));
    }
}
