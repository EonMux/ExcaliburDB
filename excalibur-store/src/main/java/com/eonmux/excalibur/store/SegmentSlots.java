package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SlotChecksum;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * The slot table of one {@link Segment}: Robin Hood probing, placement, displacement and the
 * checksum that makes a pointer trustworthy.
 *
 * <p>A slot is 24 bytes of metadata - a key hash, a distance, a flag byte, a six-byte offset
 * into the data region, and a CRC32 over three of those four. Nothing in the data region is
 * ever reached except through a slot, which is why the slot table is a subject in its own
 * right rather than an index over the records: a record's own checksum says nothing about the
 * pointer that found it, and before the slot carried one of its own a flipped bit here turned
 * a present, sound entry into a missing one.</p>
 *
 * <h2>Robin Hood placement</h2>
 * <p>An insertion carries its entry along the probe chain, and whenever it meets an occupant
 * that travelled less far than it has, it takes the slot and carries the occupant onwards.
 * This bounds the worst-case probe length, and it is cheap because a displacement moves slot
 * metadata only - the displaced entry keeps the data-region record it already owns, so a
 * chain of length k consumes one record, not k + 1.</p>
 *
 * <p>That is also what makes an insertion reversible, which the write path depends on: the
 * log can still refuse the record at its fsync after the placement has been made, and
 * {@link #undoInsert(RobinHoodPlacement)} puts every displaced slot back and releases the
 * appended record. Victims are restored newest-first so that a slot overwritten twice ends up
 * holding its original occupant.</p>
 *
 * <h2>What is checksummed, and when it is checked</h2>
 * <p>The distance byte is outside the slot checksum, because Robin Hood displacement of an
 * unrelated key rewrites it and it would otherwise invalidate a slot no one touched; it is
 * held redundantly in the record as well. Everything else in the slot is covered, an empty
 * slot included, so that a flipped occupancy bit is a mismatch rather than a slot that
 * suddenly claims to hold something.</p>
 *
 * <p>The probe loop itself consults no checksum. Two points do: a slot whose key hash matches,
 * before its data offset is followed, and every slot a probe examined before it reports the
 * key absent - because absence is the one answer a damaged slot table can fabricate out of a
 * present entry. Slots walked past on the way to a hit are left alone; a slot belonging to
 * another key cannot change this key's answer, and paying for them would put a checksum on
 * every probe of the hottest path in the engine.</p>
 *
 * <p><strong>Thread safety:</strong> none of its own. Every method here assumes the caller
 * holds the segment's read lock for a read or its write lock for a write - the single
 * {@link java.util.concurrent.locks.ReentrantReadWriteLock} that {@link Segment} owns, which
 * is shared rather than copied precisely so that a public entry point can take it once and
 * everything underneath can run inside it.</p>
 */
final class SegmentSlots {

    /** The segment whose slot table this is. */
    private final Segment owner;

    /**
     * Binds this slot table to its segment.
     *
     * @param owner The owning segment
     */
    SegmentSlots(Segment owner) {
        this.owner = owner;
    }

    /**
     * Finds the slot index for a key, or returns -1 if not found.
     *
     * <p>The probe loop itself consults no checksum. Two points do. A slot whose key hash
     * matches is verified before the record it names is reached through it, because from
     * there on its data offset is followed. And a probe that is about to report the key
     * absent verifies every slot it examined, because absence is the one answer a damaged
     * slot table can fabricate out of a present entry - which is the whole reason the slot
     * checksum exists. Slots walked past on the way to a hit are left alone: a slot
     * belonging to another key cannot change this key's answer, and paying for them would
     * put a checksum on every probe of the hottest path in the engine.</p>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @return Slot index if found, -1 if not found
     * @throws CorruptSegmentEntryException if a slot the answer depends on fails its checksum
     */
    int findSlot(long keyHash, byte[] key) {
        int idealSlot = (int) (keyHash & (owner.slotCount - 1)); // hash % slotCount for power of 2

        for (int distance = 0; distance < Segment.MAX_PROBE_LENGTH; distance++) {
            int slotIndex = (idealSlot + distance) & (owner.slotCount - 1);
            long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;

            // Check if slot is occupied
            byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
            if ((flags & Segment.FLAG_OCCUPIED) == 0) {
                // Empty slot - key not found
                return notFound(owner.segment, idealSlot, distance + 1);
            }

            // Check if we've gone past all entries with smaller distances
            byte slotDistance = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DISTANCE_OFFSET);
            if (slotDistance < distance) {
                // Would have found it by now due to Robin Hood ordering
                return notFound(owner.segment, idealSlot, distance + 1);
            }

            // Check if hash matches
            long slotKeyHash = owner.segment.get(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET);
            if (slotKeyHash != keyHash) {
                continue;
            }

            // The record is about to be reached through this slot's data offset, so the
            // slot has to be shown to be the slot that was written before it is followed.
            verifySlot(owner.segment, slotIndex);

            // Hash matches - verify key bytes
            if (owner.records.keyMatches(slotIndex, key)) {
                return slotIndex;
            }
        }

        return notFound(owner.segment, idealSlot, Segment.MAX_PROBE_LENGTH);
    }

    /**
     * Answers a probe that found nothing, once absence has been shown to be the truth.
     *
     * @param mem       The mapping the probe read, live or snapshot
     * @param idealSlot The slot the probe started at
     * @param probes    How many slots the probe examined
     * @return -1, meaning the key is not in this segment
     * @throws CorruptSegmentEntryException if any slot the probe examined fails its checksum
     */
    int notFound(MemorySegment mem, int idealSlot, int probes) {
        if (owner.format.hasSlotChecksum()) {
            int damaged = SlotChecksum.firstDamagedSlot(mem, owner.slotCount, idealSlot, probes);
            if (damaged >= 0) {
                throw slotFailure(mem, damaged);
            }
        }
        return -1;
    }

    /**
     * Checks a slot against the checksum stored in it, before its data offset is followed.
     *
     * @param mem       The mapping the slot lives in, live or snapshot
     * @param slotIndex The slot to check
     * @throws CorruptSegmentEntryException if the slot does not match its checksum
     */
    void verifySlot(MemorySegment mem, int slotIndex) {
        if (owner.format.hasSlotChecksum()
                && !SlotChecksum.matches(mem, SlotChecksum.offsetOf(slotIndex))) {
            throw slotFailure(mem, slotIndex);
        }
    }

    /**
     * Checks a slot against the record it names, where the record is being read anyway.
     *
     * <p>Not on the lookup path. A point read never reads the record's key hash or its
     * distance byte, so this would be two fresh reads on the hottest path in the engine to
     * find something {@link #verifySlot(MemorySegment, int)} already finds in a current-format file. It is
     * worth its cost on the maintenance readers, which read both fields regardless and
     * which are the one place where a slot that no longer reaches its record turns into a
     * record that is gone for good.</p>
     *
     * @param mem         The mapping both live in
     * @param slotIndex   The slot
     * @param entryOffset Absolute offset of the record the slot names
     * @throws CorruptSegmentEntryException if the slot and the record disagree
     */
    void verifySlotAgainstEntry(MemorySegment mem, int slotIndex, long entryOffset) {
        long slotOffset = SlotChecksum.offsetOf(slotIndex);
        String fault = SegmentIntegrityChecker.slotEntryFault(mem, slotOffset, entryOffset,
            owner.format);
        if (fault != null) {
            throw CorruptSegmentEntryException.slotEntryMismatch(owner.path, slotIndex,
                entryOffset - owner.slotTableSize, fault);
        }
    }

    /**
     * Describes a slot that failed its checksum.
     *
     * @param mem       The mapping the slot lives in
     * @param slotIndex The damaged slot
     * @return The failure to throw
     */
    CorruptSegmentEntryException slotFailure(MemorySegment mem, int slotIndex) {
        long slotOffset = SlotChecksum.offsetOf(slotIndex);
        return CorruptSegmentEntryException.slotChecksumMismatch(owner.path, slotIndex,
            SlotChecksum.dataRegionOffset(mem, slotOffset),
            SlotChecksum.stored(mem, slotOffset), SlotChecksum.compute(mem, slotOffset));
    }

    /**
     * Inserts a new entry using Robin Hood hashing.
     *
     * <p>Robin Hood hashing: When inserting, if the current slot occupant has a
     * smaller distance from its ideal slot than the new entry, we swap them.
     * This ensures that entries with longer probe distances "steal" from
     * entries with shorter distances, minimizing maximum probe length.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Robin Hood swap occurs when: {@code new_entry.distance > existing_entry.distance}</li>
     *   <li>After swap, displaced entry's distance is incremented by 1</li>
     *   <li>Distance values are maintained in range [0, 127]</li>
     * </ul>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @param value The value bytes
     * @return What the placement changed, so that it can be taken back if the write-ahead log
     *         then refuses the record for this entry
     */
    RobinHoodPlacement insertEntry(long keyHash, byte[] key, byte[] value) {
        // The incoming entry's record is appended to the data region exactly once.
        // A Robin Hood displacement then moves only slot metadata: the displaced
        // entry keeps the data-region record it already owns, so a probe chain of
        // length k consumes one record, not k + 1.
        int entrySize = owner.records.entryHeaderSize() + key.length + value.length + Segment.ENTRY_TRAILER_SIZE;
        long newRecordOffset = owner.dataOffset;
        owner.records.writeEntryAtOffset(owner.slotTableSize + newRecordOffset, keyHash, 0, key, value);
        owner.dataOffset += entrySize;

        // Metadata of the entry currently being carried along the probe chain.
        long carriedHash = keyHash;
        long carriedRecordOffset = newRecordOffset;
        byte carriedFlags = Segment.FLAG_OCCUPIED;

        // Ideal slot of the carried entry; recalculated after every displacement
        // because the displaced entry hashes elsewhere.
        int currentIdealSlot = (int) (keyHash & (owner.slotCount - 1));
        int distance = 0;

        // Victims overwritten so far, newest last, so a failure part-way through
        // the chain can be unwound instead of losing a pre-existing entry.
        List<SlotSnapshot> displaced = null;

        while (distance < Segment.MAX_PROBE_LENGTH) {
            int slotIndex = (currentIdealSlot + distance) & (owner.slotCount - 1);
            long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;

            byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
            if ((flags & Segment.FLAG_OCCUPIED) == 0) {
                // Found an empty slot - the chain terminates here
                writeSlotMetadata(slotIndex, carriedHash, distance, carriedFlags, carriedRecordOffset);
                owner.entryCount++;
                owner.liveEntryCount++;
                return new RobinHoodPlacement(slotIndex, newRecordOffset, displaced);
            }

            // Robin Hood swap condition: the occupant travelled less far than we have
            int slotDistance = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DISTANCE_OFFSET) & 0xFF;
            if (slotDistance < distance) {
                SlotSnapshot victim = new SlotSnapshot(
                    slotIndex,
                    owner.segment.get(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET),
                    slotDistance,
                    flags,
                    readDataOffset(slotIndex));

                writeSlotMetadata(slotIndex, carriedHash, distance, carriedFlags, carriedRecordOffset);

                if (displaced == null) {
                    displaced = new ArrayList<>();
                }
                displaced.add(victim);

                // Carry the victim onwards from the slot after the one we took
                carriedHash = victim.keyHash();
                carriedRecordOffset = victim.dataRegionOffset();
                carriedFlags = victim.flags();
                currentIdealSlot = (int) (carriedHash & (owner.slotCount - 1));
                distance = slotDistance + 1;

                if (distance > Segment.MAX_DISTANCE) {
                    rollbackInsert(displaced, newRecordOffset);
                    throw new SegmentCapacityExceededException(
                        SegmentCapacityExceededException.Reason.DISTANCE_OVERFLOW,
                        "Distance overflow: displaced entry would have distance " + distance +
                        " which exceeds maximum " + Segment.MAX_DISTANCE + ". " +
                        "Hash table is too full or has pathological collision pattern.");
                }

                // The distance was already advanced for the new position
                continue;
            }

            distance++;
        }

        rollbackInsert(displaced, newRecordOffset);
        throw new SegmentCapacityExceededException(
            SegmentCapacityExceededException.Reason.PROBE_EXHAUSTED,
            "Failed to insert after " + Segment.MAX_PROBE_LENGTH + " probes. Load factor: " + owner.loadFactor());
    }

    /**
     * Restores every slot overwritten by a failed insertion and releases the data
     * region record that was appended for the incoming entry.
     *
     * <p>Victims are restored newest-first so that a slot overwritten more than once
     * ends up holding its original occupant.</p>
     *
     * @param displaced      Victims in the order they were overwritten, or null if none
     * @param recordOffset   Data region offset of the record appended for this insert
     */
    void rollbackInsert(List<SlotSnapshot> displaced, long recordOffset) {
        if (displaced != null) {
            for (int i = displaced.size() - 1; i >= 0; i--) {
                SlotSnapshot victim = displaced.get(i);
                writeSlotMetadata(victim.slotIndex(), victim.keyHash(), victim.distance(),
                    victim.flags(), victim.dataRegionOffset());
            }
        }
        // Nothing references the record we appended, so the space is reclaimed.
        owner.dataOffset = recordOffset;
    }

    /**
     * Takes back an insertion that completed but whose log record the device then refused.
     *
     * <p>The landing slot is cleared before the victims are restored. It cannot be one of
     * them - see {@link RobinHoodPlacement} - but clearing it first means that if it ever
     * were, the victim's own contents would be what survived, which is the safe way round.</p>
     *
     * @param placement What the insertion changed
     */
    void undoInsert(RobinHoodPlacement placement) {
        clearSlot(placement.landingSlot());
        rollbackInsert(placement.displaced(), placement.recordOffset());
        owner.entryCount--;
        owner.liveEntryCount--;
    }

    /**
     * Points a slot at an existing data region record.
     *
     * <p>The record's distance byte is kept in sync with the slot's, so a record moved
     * by Robin Hood displacement stays self-describing without being re-appended.</p>
     *
     * @param slotIndex        The slot to write
     * @param keyHash          The entry's key hash
     * @param distance         Distance from the entry's ideal slot, in [0, {@link Segment#MAX_DISTANCE}]
     * @param flags            Slot flags to store (preserves the tombstone bit)
     * @param dataRegionOffset Offset of the entry's record within the data region
     * @throws IllegalStateException if distance is out of range
     */
    void writeSlotMetadata(int slotIndex, long keyHash, int distance,
                                   byte flags, long dataRegionOffset) {
        validateDistance(distance);

        owner.segment.set(Segment.BYTE_LAYOUT, owner.slotTableSize + dataRegionOffset + Segment.ENTRY_DISTANCE_OFFSET, (byte) distance);

        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        owner.segment.set(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET, keyHash);
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DISTANCE_OFFSET, (byte) distance);
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET, flags);
        writeDataOffset(slotIndex, dataRegionOffset);
        refreshSlotChecksum(slotIndex);
    }

    /**
     * Rewrites a slot's checksum over the bytes the slot now holds.
     *
     * <p>Called from every path that changes a slot's key hash, flag byte or data offset,
     * and from none that changes only its distance - the one field the checksum leaves out,
     * so that displacing an unrelated key does not invalidate it. In a format that carries
     * no slot checksum this writes nothing, which is what keeps such a file byte-for-byte
     * as it was found.</p>
     *
     * @param slotIndex The slot whose bytes have just changed
     */
    void refreshSlotChecksum(int slotIndex) {
        if (owner.format.hasSlotChecksum()) {
            SlotChecksum.store(owner.segment, SlotChecksum.offsetOf(slotIndex));
        }
    }

    /**
     * Updates an existing entry with a new value.
     *
     * @param slotIndex The slot index of the existing entry
     * @param key The key (for validation)
     * @param value The new value
     */
    void updateEntry(int slotIndex, byte[] key, byte[] value) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;

        // Read current data offset
        long dataRegionOffset = readDataOffset(slotIndex);

        // Read existing entry to get key length and distance
        long entryOffset = owner.slotTableSize + dataRegionOffset;
        int existingKeyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int distance = owner.segment.get(Segment.BYTE_LAYOUT, entryOffset + Segment.ENTRY_DISTANCE_OFFSET) & 0xFF;

        // Calculate sizes
        int newEntrySize = owner.records.entryHeaderSize() + key.length + value.length + Segment.ENTRY_TRAILER_SIZE;
        int existingEntrySize = owner.records.entryHeaderSize() + existingKeyLen +
            owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET) + Segment.ENTRY_TRAILER_SIZE;

        // Check if we can update in place
        if (newEntrySize <= existingEntrySize) {
            // Update in place, preserving distance
            long keyHash = owner.segment.get(Segment.LONG_LAYOUT, entryOffset + Segment.ENTRY_KEY_HASH_OFFSET);
            owner.records.writeEntryAtOffset(entryOffset, keyHash, distance, key, value);
        } else {
            // Need to append a new record and repoint the slot at it
            owner.records.requireDataSpace(newEntrySize);
            long newDataOffset = owner.dataOffset;
            long keyHash = owner.segment.get(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET);
            owner.records.writeEntryAtOffset(owner.slotTableSize + newDataOffset, keyHash, distance, key, value);

            // Update slot's data offset
            writeDataOffset(slotIndex, newDataOffset);
            owner.dataOffset += newEntrySize;
        }

        // Clear tombstone flag in case we're resurrecting a deleted entry
        byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
        if ((flags & Segment.FLAG_TOMBSTONE) != 0) {
            owner.liveEntryCount++;
        }
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET, (byte) (flags & ~Segment.FLAG_TOMBSTONE));

        // Covers both changes this method can make to the slot: a data offset repointed at
        // an appended record, and the tombstone bit cleared by a resurrection.
        refreshSlotChecksum(slotIndex);
    }

    /**
     * Writes the 6-byte data offset to a slot.
     *
     * <p>Leaves the slot's checksum stale: the data offset is one of the fields it covers,
     * so every caller is responsible for calling {@link #refreshSlotChecksum(int)} once it
     * has finished changing the slot. Refreshing here instead would recompute the checksum
     * twice on the path that writes a slot's other fields as well.</p>
     *
     * @param slotIndex        The slot to write
     * @param dataRegionOffset Offset of the entry's record within the data region
     */
    void writeDataOffset(int slotIndex, long dataRegionOffset) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        // Write as 6 bytes (48 bits) - sufficient for large segments
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET,
            (byte) (dataRegionOffset & 0xFF));
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET + 1,
            (byte) ((dataRegionOffset >> 8) & 0xFF));
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET + 2,
            (byte) ((dataRegionOffset >> 16) & 0xFF));
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET + 3,
            (byte) ((dataRegionOffset >> 24) & 0xFF));
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET + 4,
            (byte) ((dataRegionOffset >> 32) & 0xFF));
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DATA_OFFSET_OFFSET + 5,
            (byte) ((dataRegionOffset >> 40) & 0xFF));
    }

    /**
     * Reads the 6-byte data offset from a slot.
     */
    long readDataOffset(int slotIndex) {
        return SlotChecksum.dataRegionOffset(owner.segment, SlotChecksum.offsetOf(slotIndex));
    }

    /**
     * Checks if a slot is tombstoned.
     */
    boolean isSlotTombstoned(int slotIndex) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
        return (flags & Segment.FLAG_TOMBSTONE) != 0;
    }

    /**
     * Marks a slot and its entry as tombstoned.
     *
     * <p>Callers must have verified the slot is occupied and not already tombstoned,
     * so this method is the single live-to-tombstoned transition point and is where
     * {@code liveEntryCount} is decremented. {@code entryCount} is unchanged: the slot
     * stays occupied until compaction rebuilds the segment.</p>
     */
    void markAsTombstoned(int slotIndex) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;

        // Update slot flags
        byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET, (byte) (flags | Segment.FLAG_TOMBSTONE));
        refreshSlotChecksum(slotIndex);

        // Update entry tombstone byte
        long dataRegionOffset = readDataOffset(slotIndex);
        long entryOffset = owner.slotTableSize + dataRegionOffset;

        // Read key length and value length to find tombstone byte position
        int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
        long tombstoneOffset = entryOffset + owner.records.keyBytesOffset() + keyLen + valueLen;

        owner.segment.set(Segment.BYTE_LAYOUT, tombstoneOffset, (byte) 1);

        owner.liveEntryCount--;
    }

    /**
     * Clears all slots in the slot table.
     */
    void clearSlotTable() {
        for (int i = 0; i < owner.slotCount; i++) {
            clearSlot(i);
        }
    }

    /**
     * Returns a slot to the state a never-used slot is in.
     *
     * <p>Clearing the occupied bit is what makes a probe stop here, so the data offset can be
     * left as it is: nothing reads it while the slot is unoccupied. It is still covered by
     * the slot's checksum, because the checksum has to describe whatever bytes the slot
     * actually holds for a later change to any of them to show up.</p>
     *
     * @param slotIndex The slot to clear
     */
    void clearSlot(int slotIndex) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        owner.segment.set(Segment.LONG_LAYOUT, slotOffset + Segment.SLOT_KEY_HASH_OFFSET, 0L);
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_DISTANCE_OFFSET, (byte) 0);
        owner.segment.set(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET, (byte) 0);
        // An empty slot is checksummed too, so that a flipped occupancy bit is a mismatch
        // rather than a slot that suddenly claims to hold something.
        refreshSlotChecksum(slotIndex);
    }

    /**
     * Checks if a slot is occupied.
     */
    boolean isSlotOccupied(int slotIndex) {
        long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
        byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
        return (flags & Segment.FLAG_OCCUPIED) != 0;
    }

    /**
     * Finds the slot index of the Nth occupied (non-tombstoned) entry.
     * Must be called while holding the read lock.
     *
     * @param n The 0-based entry index
     * @return The slot index, or -1 if not found
     */
    int findNthOccupiedSlot(int n) {
        int found = 0;
        for (int slot = 0; slot < owner.slotCount; slot++) {
            long slotOffset = (long) slot * Segment.SLOT_SIZE;
            byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
            if ((flags & Segment.FLAG_OCCUPIED) != 0 && (flags & Segment.FLAG_TOMBSTONE) == 0) {
                if (found == n) {
                    return slot;
                }
                found++;
            }
        }
        return -1;
    }

    /**
     * Reads the data offset from a slot.
     *
     * @param slotIndex The slot index
     * @return Data offset
     */
    int readSlotDataOffset(int slotIndex) {
        long dataOffset = readDataOffset(slotIndex);
        return (int) dataOffset;
    }

    /**
     * Reads the entry size from a slot.
     *
     * @param slotIndex The slot index
     * @return Entry size in bytes
     */
    int readSlotEntrySize(int slotIndex) {
        int dataOffset = readSlotDataOffset(slotIndex);
        return owner.records.readEntrySize(dataOffset);
    }

    /**
     * Validates that a distance value is in the valid range [0, MAX_DISTANCE].
     *
     * <p>Domain correctness constraint: distance must be in range [0, 127]
     * to fit in an unsigned byte.</p>
     *
     * @param distance The distance to validate
     * @throws IllegalStateException if distance is out of range [0, 127]
     */
    void validateDistance(int distance) {
        if (distance < 0 || distance > Segment.MAX_DISTANCE) {
            throw new IllegalStateException(
                "Distance must be in range [0, " + Segment.MAX_DISTANCE + "], got: " + distance +
                ". This indicates the hash table is too full or has a pathological collision pattern.");
        }
    }

    /**
     * Counts the occupied slots that are tombstoned.
     *
     * @return The count
     */
    int tombstoneCount() {
        owner.lock.readLock().lock();
        try {
            int count = 0;
            for (int i = 0; i < owner.slotCount; i++) {
                if (isSlotOccupied(i) && isSlotTombstoned(i)) {
                    count++;
                }
            }
            return count;
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * Adds up the record sizes behind the tombstoned slots.
     *
     * @return Estimated reclaimable bytes
     */
    long estimatedReclaimableBytes() {
        owner.lock.readLock().lock();
        try {
            long bytes = 0;
            for (int i = 0; i < owner.slotCount; i++) {
                if (isSlotOccupied(i) && isSlotTombstoned(i)) {
                    bytes += readSlotEntrySize(i);
                }
            }
            return bytes;
        } finally {
            owner.lock.readLock().unlock();
        }
    }
}
