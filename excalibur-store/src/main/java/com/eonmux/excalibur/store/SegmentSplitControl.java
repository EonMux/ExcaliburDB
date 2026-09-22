package com.eonmux.excalibur.store;

import java.util.Objects;

/**
 * The split state machine of one {@link Segment}, and the question that starts it.
 *
 * <p>A segment moves NORMAL to SPLITTING to SPLIT_COMPLETE, or back to NORMAL if the split is
 * abandoned, and each transition is made under the segment's write lock. That is not
 * incidental: writes check the state inside the same lock, so a transition and a write can
 * never interleave, and a write that arrives during a split is refused rather than applied to
 * a segment whose contents have already been copied into the children.</p>
 *
 * <h2>Two refusals that mean different things</h2>
 * <p>A SPLITTING segment refuses with {@link IllegalStateException}: the split may still be
 * abandoned, and the caller should treat it as a transient failure. A SPLIT_COMPLETE segment
 * refuses with {@link SegmentRetiredException}, which tells the routing layer to resolve the
 * key again - the difference between a failed write and one that lands in the right child. A
 * retired parent no longer appears in the directory and its file has been replaced by the
 * left child's, so a mutation applied to it lands in an inode nothing can reach and is gone
 * at process exit.</p>
 *
 * <h2>Full is one predicate, not two</h2>
 * <p>{@link #isFull()} is both the insert path's refusal and the split trigger, deliberately.
 * When they were defined separately they disagreed by a single entry, and the disagreement
 * disabled automatic splitting outright; that method carries the whole story.</p>
 *
 * <p><strong>Thread safety:</strong> every transition holds the segment's write lock. The
 * state field and the snapshot reference are volatile, so a reader outside the lock sees a
 * consistent pair - the state is published last on the way in and first on the way out.</p>
 */
final class SegmentSplitControl {

    /** The segment whose split this governs. */
    private final Segment owner;

    /**
     * Binds this state machine to its segment.
     *
     * @param owner The owning segment
     */
    SegmentSplitControl(Segment owner) {
        this.owner = owner;
    }

    /**
     * Whether the next insert would be refused for load factor.
     *
     * <p>The one definition of full, because the two that existed disagreed by an entry and
     * the disagreement disabled automatic splitting entirely. Inserts were refused when
     * {@code entryCount + 1} reached {@code ceil(slotCount * threshold)}, so the largest
     * {@code entryCount} a segment could reach was one below that - and
     * {@link Segment#shouldSplit()} asked whether {@code loadFactor() >= threshold}, which
     * that entry count is always just short of. A segment could therefore fill up, refuse
     * every subsequent write, and never once report that it wanted splitting. The
     * {@link SplitWorker} scanned it every 100ms and always saw false.</p>
     *
     * <p>Defining both in terms of the same predicate makes the split trigger fire on exactly
     * the insert after the last accepted one, so the worker has the segment queued before a
     * client is ever told the segment is full.</p>
     *
     * @return true when a further insert would exceed the threshold
     */
    boolean isFull() {
        // Integer arithmetic avoids floating-point precision issues at the boundary.
        return owner.entryCount + 1 >= (long) Math.ceil(owner.slotCount * owner.loadFactorThreshold);
    }

    /**
     * Validates that the segment is not in SPLITTING state.
     *
     * <p>Writes are rejected during splitting to ensure copy-on-write semantics.
     * Readers continue to see the pre-split snapshot.</p>
     *
     * @throws IllegalStateException if segment is in SPLITTING state
     */
    void validateNotSplitting() {
        if (owner.splitState == Segment.SplitState.SPLITTING) {
            throw new IllegalStateException(
                "Cannot modify segment during split: " + owner.path);
        }
        // A retired parent no longer appears in the directory and its file has been replaced by
        // the left child's, so a mutation applied here lands in an inode nothing can reach and
        // is gone at process exit. A writer that resolved this segment before the split
        // published still holds a reference to it; refusing tells the caller to route again
        // rather than losing the write silently.
        if (owner.splitState == Segment.SplitState.SPLIT_COMPLETE) {
            throw new SegmentRetiredException(
                "Segment was replaced by its split children: " + owner.path);
        }
    }

    /**
     * Rejects a mutation the segment can no longer honour.
     *
     * <p>Both conditions are set by holders of this segment's write lock, so this has to be
     * called with that lock held to mean anything. Called before acquiring it, it answers a
     * question about a moment that has passed by the time the write actually happens.
     *
     * <p>Split state is checked first. A retired parent is closed shortly after it is replaced,
     * and "closed" says nothing about where the caller should go instead - while "replaced by
     * its split children" tells the routing layer to resolve again, which is the difference
     * between a failed write and one that lands in the right child.
     *
     * @throws SegmentRetiredException if the segment was replaced by its split children
     * @throws IllegalStateException if the segment is closed or splitting
     */
    void requireWritable() {
        validateNotSplitting();
        owner.validateNotClosed();
    }

    /**
     * Freezes the segment for a copy-on-write split and hands out the frozen view.
     *
     * <p>Two snapshots are taken of the same instant and they are not the same object. The
     * internal one serves this segment's own reads through {@link SegmentSnapshotReads} while
     * the split runs; the returned one is what the split worker reads to build the children.
     * Both are read-only views of the live mapping, taken under the write lock, so no write
     * can be half-applied across them.</p>
     *
     * @return The snapshot the caller partitions
     */
    Segment.SegmentSplitSnapshot beginSplit() {
        owner.validateNotClosed();

        owner.lock.writeLock().lock();
        try {
            if (owner.splitState != Segment.SplitState.NORMAL) {
                throw new IllegalStateException(
                    "Cannot begin split: segment is in state " + owner.splitState);
            }

            // Create snapshot of current state
            owner.splitSnapshot = new Segment.SegmentSnapshot(
                owner.segment,
                owner.modificationEpoch
            );

            // Transition to splitting state
            owner.splitState = Segment.SplitState.SPLITTING;

            return new Segment.SegmentSplitSnapshot(
                owner.segment,
                owner.slotCount,
                owner.entryCount,
                owner.slotTableSize,
                owner.path,
                owner.format
            );
        } finally {
            owner.lock.writeLock().unlock();
        }
    }

    /**
     * Records the children and retires the parent.
     *
     * <p>The epoch is bumped last, which invalidates every zero-copy slice handed out from
     * this segment: after this point the parent's file is about to be replaced by the left
     * child's, so a slice into it is a view of an inode nothing can reach.</p>
     *
     * @param leftChild  The left child
     * @param rightChild The right child
     * @param splitBit   The hash bit the entries were partitioned on
     */
    void completeSplit(Segment leftChild, Segment rightChild, int splitBit) {
        Objects.requireNonNull(leftChild, "Left child cannot be null");
        Objects.requireNonNull(rightChild, "Right child cannot be null");
        if (splitBit < 0 || splitBit > 63) {
            throw new IllegalArgumentException("Split bit must be in [0, 63], got: " + splitBit);
        }

        owner.lock.writeLock().lock();
        try {
            if (owner.splitState != Segment.SplitState.SPLITTING) {
                throw new IllegalStateException(
                    "Cannot complete split: segment is in state " + owner.splitState);
            }

            owner.leftChild = leftChild;
            owner.rightChild = rightChild;
            owner.splitBit = splitBit;
            owner.splitSnapshot = null;
            owner.splitState = Segment.SplitState.SPLIT_COMPLETE;

            owner.modificationEpoch++; // Invalidate any cached slices
        } finally {
            owner.lock.writeLock().unlock();
        }
    }

    /**
     * Returns a segment that was being split to normal service.
     *
     * @throws IllegalStateException if the segment is not splitting
     */
    void abortSplit() {
        owner.lock.writeLock().lock();
        try {
            if (owner.splitState != Segment.SplitState.SPLITTING) {
                throw new IllegalStateException(
                    "Cannot abort split: segment is in state " + owner.splitState);
            }

            owner.splitSnapshot = null;
            owner.splitState = Segment.SplitState.NORMAL;
        } finally {
            owner.lock.writeLock().unlock();
        }
    }
}
