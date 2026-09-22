package com.eonmux.excalibur.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A write-log offset that can move forwards and never backwards.
 *
 * <p>Two of the numbers a segment's catalog entry carries are offsets into that segment's
 * write log: how far the HNSW integration worker has read, and how far the log itself has
 * been written. Both are advanced from whichever thread happened to do the work, both are
 * read lock-free by the query pipeline to decide whether a tail scan is needed, and for
 * both an offset that went backwards would mean re-reading records that were already
 * integrated - or, worse, declaring a tail empty while records sit in it.</p>
 *
 * <p>So both need the same thing: an atomic read, and an update that applies only when it
 * advances. Each was written out longhand as an {@link AtomicLong} plus its own
 * compare-and-set retry loop, twice over, in two pairs of near-identical methods. This is
 * that loop, once. The retry is what makes the maximum atomic: a plain
 * {@code if (new > get()) set(new)} lets two threads read the same current value and the
 * later write win with the smaller offset.</p>
 *
 * <p>Deliberately without any domain validation. Whether a negative offset is an
 * {@link IllegalArgumentException}, a silently ignored no-op or a {@code false} return
 * differs between the two callers, and that difference is part of each one's published
 * contract rather than a property of a monotonic counter - see
 * {@link SegmentCatalog.SegmentMetadata} for which does what.</p>
 *
 * <p><strong>Thread safety:</strong> fully thread-safe and lock-free.</p>
 */
final class MonotonicOffset {

    private final AtomicLong value = new AtomicLong(0);

    /**
     * Returns the current offset.
     *
     * @return The offset, which is a consistent snapshot rather than a torn read
     */
    long get() {
        return value.get();
    }

    /**
     * Sets the offset outright, whether or not it advances.
     *
     * <p>For recovery, which restores whatever was persisted rather than advancing past it.
     * Everything else uses {@link #advanceTo(long)}.</p>
     *
     * @param newOffset The offset to store
     */
    void set(long newOffset) {
        value.set(newOffset);
    }

    /**
     * Moves the offset to {@code newOffset}, if that is further along than where it is.
     *
     * @param newOffset The candidate offset
     * @return true if the offset was advanced, false if it was already at or beyond it
     */
    boolean advanceTo(long newOffset) {
        while (true) {
            long current = value.get();
            if (newOffset <= current) {
                return false;
            }
            if (value.compareAndSet(current, newOffset)) {
                return true;
            }
            // CAS failed, retry
        }
    }
}
