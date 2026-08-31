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
 */
@FunctionalInterface
public interface CompactionPolicy {

    /** Whether the store should be compacted now. */
    boolean shouldCompact(CompactionStats stats, Instant now);

    /**
     * What compaction has to decide on.
     *
     * @param totalEdges       edges in the store, tombstoned or not
     * @param tombstonedEdges  edges marked for removal but still present
     * @param oldestOutstanding when the longest-waiting erasure request arrived, if any
     * @param segmentCount     how many segments the store is spread over
     */
    record CompactionStats(long totalEdges, long tombstonedEdges,
                           Optional<Instant> oldestOutstanding, int segmentCount) {

        public CompactionStats {
            if (totalEdges < 0 || tombstonedEdges < 0) {
                throw new IllegalArgumentException("edge counts must not be negative");
            }
            if (tombstonedEdges > totalEdges) {
                throw new IllegalArgumentException(
                    "tombstonedEdges (" + tombstonedEdges + ") cannot exceed totalEdges ("
                        + totalEdges + ")");
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
            return totalEdges == 0 ? 0.0 : tombstonedEdges / (double) totalEdges;
        }
    }

    /**
     * The default: compact when any of three conditions holds.
     *
     * <p>Three, because each catches a case the others miss:
     * <ul>
     *   <li><b>Tombstone ratio.</b> Reclaims space and stops queries paying to skip dead edges.
     *       Alone, it never fires for a small erasure — which is exactly the case that matters
     *       most.</li>
     *   <li><b>Oldest outstanding request.</b> The obligation, not the volume. One edge under a
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
