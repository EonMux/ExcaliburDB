package io.excalibur.store;

import io.excalibur.store.format.SlotChecksum;

import java.lang.foreign.MemorySegment;

/**
 * The read path a {@link Segment} uses while it is being split.
 *
 * <p>A split is copy-on-write: the moment it begins, the segment takes a read-only view of
 * its own mapping and serves every read from that view until the children are published. This
 * is a near-copy of the live read path in {@link SegmentSlots} and {@link SegmentRecords},
 * differing only in which {@link MemorySegment} it reads, and it is a separate class rather
 * than a parameter on the live path for one reason: the live path is the hottest code in the
 * engine, and a split lasts seconds. Paying a branch per probe, forever, to spare a few dozen
 * lines during a rare operation is the wrong trade.</p>
 *
 * <p>What it does share is every judgement about damage. Slot checksums, record checksums and
 * the "not this key, or damaged?" question are all resolved by calling back into
 * {@link SegmentSlots} and {@link SegmentRecords} with the snapshot's mapping, so a read
 * served during a split is checked exactly as strictly as one served outside it.</p>
 *
 * <p><strong>Thread safety:</strong> the caller holds the segment's read lock. The snapshot
 * itself is immutable - a {@code MemorySegment.asReadOnly()} view fixed at the instant the
 * split began - so nothing it reads can change underneath it.</p>
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
     * <p>This method performs the same lookup as {@link SegmentSlots#findSlot(long, byte[])} but reads
     * from the snapshot's read-only MemorySegment instead of the live segment.</p>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @return Slot index if found, -1 if not found
     */
    int findSlotInSnapshot(long keyHash, byte[] key) {
        MemorySegment snapshotSegment = owner.splitSnapshot.segment;
        int idealSlot = (int) (keyHash & (owner.slotCount - 1));

        for (int distance = 0; distance < Segment.MAX_PROBE_LENGTH; distance++) {
            int slotIndex = (idealSlot + distance) & (owner.slotCount - 1);
            long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;

            // Check if slot is occupied in snapshot
            byte flags = snapshotSegment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
            if ((flags & Segment.FLAG_OCCUPIED) == 0) {
                // Empty slot - key not found
                return owner.slots.notFound(snapshotSegment, idealSlot, distance + 1);
            }

            // Check if we've gone past all entries with smaller distances
            byte slotDistance = snapshotSegment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DISTANCE_OFFSET);
            if (slotDistance < distance) {
                // Would have found it by now due to Robin Hood ordering
                return owner.slots.notFound(snapshotSegment, idealSlot, distance + 1);
            }

            // Check if hash matches
            long slotKeyHash = snapshotSegment.get(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET);
            if (slotKeyHash != keyHash) {
                continue;
            }

            owner.slots.verifySlot(snapshotSegment, slotIndex);

            // Hash matches - verify key bytes in snapshot
            if (keyMatchesInSnapshot(slotIndex, key)) {
                return slotIndex;
            }
        }

        return owner.slots.notFound(snapshotSegment, idealSlot, Segment.MAX_PROBE_LENGTH);
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
     * Checks if the key in the snapshot slot matches the given key.
     *
     * @param slotIndex The slot index to check
     * @param key The key to compare against
     * @return true if the keys match
     */
    boolean keyMatchesInSnapshot(int slotIndex, byte[] key) {
        MemorySegment snapshotSegment = owner.splitSnapshot.segment;
        long dataRegionOffset = readDataOffsetFromSnapshot(slotIndex);
        long entryOffset = owner.slotTableSize + dataRegionOffset;

        // Read key length from snapshot
        int keyLen = snapshotSegment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        if (keyLen != key.length) {
            return owner.records.notThisKey(snapshotSegment, entryOffset, slotIndex);
        }

        // Compare key bytes in snapshot
        for (int i = 0; i < keyLen; i++) {
            byte entryByte = snapshotSegment.get(Segment.BYTE_LAYOUT, entryOffset + owner.records.keyBytesOffset() + i);
            if (entryByte != key[i]) {
                return owner.records.notThisKey(snapshotSegment, entryOffset, slotIndex);
            }
        }

        return true;
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
