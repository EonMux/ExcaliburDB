package io.excalibur.store;

import java.lang.foreign.MemorySegment;

/**
 * Every way of reading a key out of one {@link Segment}.
 *
 * <p>All six are the same three steps - hash the key, probe the slot table, slice the record -
 * and they differ only in what the caller has already done and what it wants back. Some take
 * the read lock and some require the caller to be holding it; some are handed a pre-computed
 * hash; one returns a bare slice and one wraps it with the epoch it was read at. Keeping them
 * together is what makes it obvious that they agree, because the ways they could quietly stop
 * agreeing - one forgetting the tombstone check, one forgetting to serve the split snapshot -
 * are exactly the bugs that return a deleted value or a pre-split value to one caller and not
 * another.</p>
 *
 * <h2>Reading during a split</h2>
 * <p>Only the two locking entry points consult the split snapshot, because they are the ones
 * a router reaches during a split; the caller-locked and pre-hashed variants are used inside
 * maintenance paths that already know what they are reading. The epoch stamped on a slice
 * taken from the snapshot is the snapshot's own, so validity is judged against the state the
 * bytes actually came from.</p>
 *
 * <p><strong>Thread safety:</strong> the locking variants take the segment's read lock, which
 * is the same lock every writer takes for writing - one instance per segment, shared with
 * every collaborator, and relied upon to be reentrant. The {@code WithoutLock} variants take
 * nothing and are only correct while the caller holds it.</p>
 */
final class SegmentLookups {

    /** The segment being read. */
    private final Segment owner;

    /**
     * Binds this read path to its segment.
     *
     * @param owner The owning segment
     */
    SegmentLookups(Segment owner) {
        this.owner = owner;
    }

    /**
     * Reads a key under the segment's read lock, from the split snapshot if one is in force.
     *
     * @param key The key
     * @return A slice over the value, or null when absent or tombstoned
     */
    MemorySegment get(byte[] key) {
        owner.validateNotClosed();
        owner.records.validateKey(key);

        owner.lock.readLock().lock();
        try {
            // Check if segment is splitting - serve from snapshot for consistent reads
            // This ensures copy-on-write semantics: reads see the pre-split snapshot
            // even if the underlying memory could be modified externally
            if (owner.splitState == Segment.SplitState.SPLITTING && owner.splitSnapshot != null) {
                return owner.snapshotReads.getFromSnapshot(key);
            }

            long keyHash = owner.records.computeHash(key);
            int slotIndex = owner.slots.findSlot(keyHash, key);

            if (slotIndex < 0) {
                return null; // Not found
            }

            // Check if tombstoned
            if (owner.slots.isSlotTombstoned(slotIndex)) {
                return null;
            }

            // Get the entry and return value slice
            return owner.records.getValueSlice(slotIndex);
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * Reads a key and stamps the result with the epoch that was current when it was read.
     *
     * <p>During a split the snapshot's epoch is used rather than the segment's, so a slice
     * taken from the frozen view is judged against the state it was actually read from.</p>
     *
     * @param key The key
     * @return A slice carrying the epoch, or a not-found slice
     */
    ZeroCopySlice getWithEpoch(byte[] key) {
        owner.validateNotClosed();
        owner.records.validateKey(key);

        owner.lock.readLock().lock();
        try {
            // Capture current epoch before reading
            // During split, use the snapshot's epoch for consistency
            long epoch = (owner.splitState == Segment.SplitState.SPLITTING && owner.splitSnapshot != null)
                ? owner.splitSnapshot.modificationEpoch
                : owner.modificationEpoch;

            // Check if segment is splitting - serve from snapshot for consistent reads
            if (owner.splitState == Segment.SplitState.SPLITTING && owner.splitSnapshot != null) {
                MemorySegment valueSlice = owner.snapshotReads.getFromSnapshot(key);
                if (valueSlice == null) {
                    return ZeroCopySlice.notFound(epoch, owner.segmentId, owner);
                }
                return new ZeroCopySlice(epoch, owner.segmentId, valueSlice, owner);
            }

            long keyHash = owner.records.computeHash(key);
            int slotIndex = owner.slots.findSlot(keyHash, key);

            if (slotIndex < 0) {
                return ZeroCopySlice.notFound(epoch, owner.segmentId, owner);
            }

            // Check if tombstoned
            if (owner.slots.isSlotTombstoned(slotIndex)) {
                return ZeroCopySlice.notFound(epoch, owner.segmentId, owner);
            }

            // Get the value slice
            MemorySegment valueSlice = owner.records.getValueSlice(slotIndex);
            return new ZeroCopySlice(epoch, owner.segmentId, valueSlice, owner);
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * Reads a key with no lock of its own; the caller holds one.
     *
     * @param key The key
     * @return A slice over the value, or null when absent or tombstoned
     */
    MemorySegment getWithoutLock(byte[] key) {
        owner.validateNotClosed();
        owner.records.validateKey(key);

        // Caller must hold lock - no lock acquisition here
        long keyHash = owner.records.computeHash(key);
        int slotIndex = owner.slots.findSlot(keyHash, key);

        if (slotIndex < 0) {
            return null; // Not found
        }

        // Check if tombstoned
        if (owner.slots.isSlotTombstoned(slotIndex)) {
            return null;
        }

        // Get the entry and return value slice
        return owner.records.getValueSlice(slotIndex);
    }

    /**
     * Reads a key whose hash the caller has already computed, under the read lock.
     *
     * @param key     The key
     * @param keyHash Its pre-computed hash
     * @return A slice over the value, or null when absent
     */
    MemorySegment getByHash(byte[] key, long keyHash) {
        owner.validateNotClosed();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        owner.lock.readLock().lock();
        try {
            int slotIndex = owner.slots.findSlot(keyHash, key);
            if (slotIndex < 0 || owner.slots.isSlotTombstoned(slotIndex)) {
                return null;
            }
            return owner.records.getValueSlice(slotIndex);
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * The same read with neither a lock nor a hash of its own: the fastest path there is.
     *
     * @param key     The key
     * @param keyHash Its pre-computed hash
     * @return A slice over the value, or null when absent
     */
    MemorySegment getByHashWithoutLock(byte[] key, long keyHash) {
        owner.validateNotClosed();
        // Caller must hold lock - no lock acquisition here
        int slotIndex = owner.slots.findSlot(keyHash, key);
        if (slotIndex < 0 || owner.slots.isSlotTombstoned(slotIndex)) {
            return null;
        }
        return owner.records.getValueSlice(slotIndex);
    }

    /**
     * Reads many keys under one acquisition of the read lock.
     *
     * @param keys   The keys
     * @param hashes Their pre-computed hashes
     * @return One slice per key, null where the key is absent
     */
    MemorySegment[] batchGetByHash(byte[][] keys, long[] hashes) {
        owner.validateNotClosed();
        if (keys == null || hashes == null) {
            throw new IllegalArgumentException("Keys and hashes cannot be null");
        }
        if (keys.length != hashes.length) {
            throw new IllegalArgumentException("Keys and hashes arrays must have same length");
        }

        MemorySegment[] results = new MemorySegment[keys.length];
        owner.lock.readLock().lock();
        try {
            for (int i = 0; i < keys.length; i++) {
                int slotIndex = owner.slots.findSlot(hashes[i], keys[i]);
                if (slotIndex >= 0 && !owner.slots.isSlotTombstoned(slotIndex)) {
                    results[i] = owner.records.getValueSlice(slotIndex);
                }
            }
        } finally {
            owner.lock.readLock().unlock();
        }
        return results;
    }
}
