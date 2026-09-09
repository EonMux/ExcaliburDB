package io.excalibur.graph.erasure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides when tombstoned data is actually removed from disk.
 *
 * <p>Pluggable because the right trigger is a deployment's decision, not the store's. A system
 * under a legal erasure obligation and one merely reclaiming space want different behaviour from
 * the same data, and hard-coding either makes the other impossible.
 *
 * <p>Stated in records rather than in any one store's unit, because the decision does not depend
 * on what a record is. A graph counts edges and a lexical index counts documents, and both ask the
 * same question of the same three numbers; naming the unit here would have forced one of them to
 * describe its data as the other's.
 */
@FunctionalInterface
public interface CompactionPolicy {

    /** Whether the store should be compacted now. */
    boolean shouldCompact(CompactionStats stats, Instant now);

    /**
     * What compaction has to decide on.
     *
     * @param totalRecords      records in the store, tombstoned or not
     * @param tombstonedRecords records marked for removal but still present
     * @param oldestOutstanding when the longest-waiting erasure request arrived, if any
     * @param segmentCount      how many segments the store is spread over
     */
    record CompactionStats(long totalRecords, long tombstonedRecords,
                           Optional<Instant> oldestOutstanding, int segmentCount) {

        public CompactionStats {
            if (totalRecords < 0 || tombstonedRecords < 0) {
                throw new IllegalArgumentException("record counts must not be negative");
            }
            if (tombstonedRecords > totalRecords) {
                throw new IllegalArgumentException(
                    "tombstonedRecords (" + tombstonedRecords + ") cannot exceed totalRecords ("
                        + totalRecords + ")");
            }
            if (oldestOutstanding == null) {
                throw new IllegalArgumentException("oldestOutstanding must not be null");
            }
            if (segmentCount < 0) {
                throw new IllegalArgumentException("segmentCount must not be negative");
            }
        }

        /** Fraction of the store that is tombstoned. */
        public double tombstoneRatio() {
            return totalRecords == 0 ? 0.0 : tombstonedRecords / (double) totalRecords;
        }
    }

    /**
     * The default: compact when any of three conditions holds.
     *
     * <p>Three, because each catches a case the others miss:
     * <ul>
     *   <li><b>Tombstone ratio.</b> Reclaims space and stops queries paying to skip dead records.
     *       Alone, it never fires for a small erasure — which is exactly the case that matters
     *       most.</li>
     *   <li><b>Oldest outstanding request.</b> The obligation, not the volume. One record under a
     *       deletion request has a deadline, and this is the only condition that respects it.</li>
     *   <li><b>Segment count.</b> Every segment is a set of mappings and a search that must visit
     *       it; unbounded growth degrades every query and eventually exhausts the mapping
     *       budget.</li>
     * </ul>
     *
     * @param tombstoneRatio    ratio at or above which to compact
     * @param maxErasureLatency how long an erasure request may remain unapplied
     * @param maxSegments       how many segments to tolerate
     */
    static CompactionPolicy standard(double tombstoneRatio, Duration maxErasureLatency,
                                     int maxSegments) {
        if (tombstoneRatio <= 0 || tombstoneRatio > 1 || Double.isNaN(tombstoneRatio)) {
            throw new IllegalArgumentException(
                "tombstoneRatio must be in (0, 1], got " + tombstoneRatio);
        }
        if (maxErasureLatency == null || maxErasureLatency.isNegative()) {
            throw new IllegalArgumentException("maxErasureLatency must be non-negative");
        }
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be at least 1");
        }
        return (stats, now) -> {
            if (stats.tombstoneRatio() >= tombstoneRatio) {
                return true;
            }
            if (stats.segmentCount() > maxSegments) {
                return true;
            }
            return stats.oldestOutstanding()
                .map(requestedAt -> !Duration.between(requestedAt, now).minus(maxErasureLatency)
                    .isNegative())
                .orElse(false);
        };
    }

    /** Never compacts. For measuring what compaction costs, not for production. */
    static CompactionPolicy never() {
        return (stats, now) -> false;
    }
}
