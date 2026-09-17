package io.excalibur.store;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How full one segment is, and how much of that is dead: the two counts compaction is
 * scheduled on.
 *
 * <p>Both are adjusted by whichever ingest thread performed the write that changed them -
 * a put raises the entry count, a delete lowers it and raises the tombstone count - so
 * both are atomic rather than merely volatile: {@code count++} from two threads loses one
 * of the two increments, and a segment whose count has drifted below the truth is one that
 * never reaches the threshold that would split it.</p>
 *
 * <p>The tombstone count is reset to zero when a compaction completes, because compaction
 * is what removes the records it was counting. Nothing resets the entry count: the entries
 * are still there afterwards.</p>
 *
 * <p>{@link #tombstoneRatio()} is what a compaction policy reads. Its denominator is live
 * entries plus tombstones rather than the entry count alone, matching
 * {@code Segment.tombstoneCount() / Segment.entryCount()}, so the two sides of the system
 * agree on what "half of this segment is dead" means.</p>
 *
 * <p><strong>Thread safety:</strong> fully thread-safe and lock-free. The two counters are
 * independently atomic, so a ratio computed across both is a consistent reading of neither
 * - it is a statistic used to schedule work, not an invariant anything depends on.</p>
 */
final class SegmentOccupancy {

    /** Entries the segment holds, adjusted by a delta on every put and delete. */
    private final AtomicInteger entryCount = new AtomicInteger(0);

    /** Entries tombstoned since the last compaction. */
    private final AtomicInteger tombstoneCount = new AtomicInteger(0);

    /**
     * Returns the entry count.
     *
     * @return The count
     */
    int entries() {
        return entryCount.get();
    }

    /**
     * Sets the entry count outright.
     *
     * @param count The count to store
     */
    void entries(int count) {
        entryCount.set(count);
    }

    /**
     * Atomically adjusts the entry count by a delta.
     *
     * @param delta The change to apply (may be negative)
     * @return The updated entry count
     */
    int addEntries(int delta) {
        return entryCount.addAndGet(delta);
    }

    /**
     * Returns the number of entries tombstoned since the last compaction.
     *
     * @return The count
     */
    int tombstones() {
        return tombstoneCount.get();
    }

    /**
     * Atomically adjusts the tombstone count by a delta.
     *
     * @param delta Number of entries tombstoned
     * @return The updated tombstone count
     */
    int addTombstones(int delta) {
        return tombstoneCount.addAndGet(delta);
    }

    /**
     * Clears the tombstone count, as a completed compaction does.
     */
    void clearTombstones() {
        tombstoneCount.set(0);
    }

    /**
     * Returns the fraction of this segment's records that are tombstones.
     *
     * @return Tombstone ratio in [0.0, 1.0]; 0.0 when the segment holds no records
     */
    double tombstoneRatio() {
        int tombstones = tombstoneCount.get();
        long total = (long) entryCount.get() + tombstones;
        return total <= 0 ? 0.0 : (double) tombstones / total;
    }
}
