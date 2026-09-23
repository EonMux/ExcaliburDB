package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SlotChecksum;

import java.lang.foreign.MemorySegment;

/**
 * The read path a {@link Segment} uses while it is being split.
 *
 * <p>The moment a split begins, the segment takes a read-only view of its own mapping, and
 * {@link SegmentLookups#get} and {@link SegmentLookups#getWithEpoch} serve reads from that view
 * until the children are published. The view aliases the live mapping - it is not a copy -
 * and the segment refuses every write while it is splitting, so it holds exactly the bytes
 * the live mapping holds; see {@link SegmentLookups} for why the other read paths may read the
 * live mapping directly.</p>
 *
 * <p>The probe and every judgement about damage are shared with the live path: slots are
 * found by {@link SegmentSlots#findSlot(MemorySegment, long, byte[])} and records verified by
 * {@link SegmentRecords}, both handed the snapshot's mapping, so a read served during a split
 * is checked exactly as strictly as one served outside it. What remains here is only the
 * choice of mapping for the tombstone flag and the value slice.</p>
 *
 * <p><strong>Thread safety:</strong> the caller holds the segment's read lock. The snapshot
 * itself is a {@code MemorySegment.asReadOnly()} view, and nothing it reads can change
 * underneath it because the segment accepts no writes until the split ends.</p>
 */
final class SegmentSnapshotReads {

    /** The segment being split. */
    private final Segment owner;

    /**
     * Binds this read path to its segment.
     *
     * @param owner The owning segment
     */
    SegmentSnapshotReads(Segment owner) {
        this.owner = owner;
    }

    /**
     * Retrieves a value from the split snapshot for copy-on-write semantics.
     *
     * <p>This method is called when the segment is in SPLITTING state to ensure
     * reads see a consistent pre-split view of the data. The snapshot is an
     * immutable MemorySegment view created at the moment split began.</p>
     *
     * @param key The key to look up
     * @return MemorySegment slice pointing to the value in the snapshot,
     *         or null if not found or tombstoned
     */
    MemorySegment getFromSnapshot(byte[] key) {
        long keyHash = owner.records.computeHash(key);
        int slotIndex = findSlotInSnapshot(keyHash, key);

        if (slotIndex < 0) {
            return null; // Not found
        }

        // Check if tombstoned in snapshot
        if (isSlotTombstonedInSnapshot(slotIndex)) {
            return null;
        }

        // Get the value slice from snapshot
        return getValueSliceFromSnapshot(slotIndex);
    }

    /**
     * Finds a slot in the snapshot using Robin Hood hashing.
     *
     * <p>The same probe as {@link SegmentSlots#findSlot(long, byte[])}, with the same
     * verification, run against the snapshot's read-only mapping instead of the live one.</p>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @return Slot index if found, -1 if not found
     */
    int findSlotInSnapshot(long keyHash, byte[] key) {
        return owner.slots.findSlot(owner.splitSnapshot.segment, keyHash, key);
    }

    /**
     * Checks if a slot is tombstoned in the snapshot.
     *
     * @param slotIndex The slot index to check
     * @return true if the slot is tombstoned in the snapshot
     */
    boolean isSlotTombstonedInSnapshot(int slotIndex) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        byte flags = owner.splitSnapshot.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
        return (flags & Segment.FLAG_TOMBSTONE) != 0;
    }

    /**
     * Reads the 6-byte data offset from a slot in the snapshot.
     *
     * @param slotIndex The slot index
     * @return The data region offset
     */
    long readDataOffsetFromSnapshot(int slotIndex) {
        return SlotChecksum.dataRegionOffset(owner.splitSnapshot.segment,
            SlotChecksum.offsetOf(slotIndex));
    }

    /**
     * Returns a zero-copy MemorySegment slice of the value from the snapshot.
     *
     * @param slotIndex The slot index
     * @return MemorySegment slice pointing to the value in the snapshot
     */
    MemorySegment getValueSliceFromSnapshot(int slotIndex) {
        MemorySegment snapshotSegment = owner.splitSnapshot.segment;
        long dataRegionOffset = readDataOffsetFromSnapshot(slotIndex);
        long entryOffset = owner.slotTableSize + dataRegionOffset;
        owner.records.verifyEntry(snapshotSegment, entryOffset, slotIndex);

        // Read key length and value length from snapshot
        int keyLen = snapshotSegment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen = snapshotSegment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);

        // Create slice pointing to value bytes in snapshot
        long valueOffset = entryOffset + owner.records.keyBytesOffset() + keyLen;
        return snapshotSegment.asSlice(valueOffset, valueLen);
    }
}
