package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.EntryChecksum;
import com.eonmux.excalibur.store.hash.XxHash64;

import java.lang.foreign.MemorySegment;

/**
 * The data region of one {@link Segment}: the variable-length entry records themselves, and
 * the checksum each one carries.
 *
 * <p>A record holds its key hash, the two lengths, a distance byte, a CRC32, then the key
 * bytes, the value bytes and a tombstone byte. Records are appended and never moved: an
 * overwrite that fits is written in place, one that does not is appended and the slot
 * repointed, and the space behind it is not reclaimed until a compaction rebuilds the file.
 * That is what lets Robin Hood displacement in {@link SegmentSlots} move slot metadata alone
 * and leave the record where it is.</p>
 *
 * <h2>The checksum, and the two bytes outside it</h2>
 * <p>The CRC32 is computed from the bytes that landed in the mapping rather than from the
 * arguments that produced them, so a stored checksum can never describe something other than
 * what is there. It covers the key hash, the two lengths, the key and the value - and not the
 * distance byte or the tombstone byte, both of which are rewritten in place after the record
 * is appended and both of which are held redundantly in the slot table.</p>
 *
 * <p>It is checked on every path that hands an entry's bytes back: a point read before the
 * slice is handed out, because after that the caller reads the mapping directly and nothing
 * stands between them and a flipped bit; and a key comparison that fails on a slot whose hash
 * matched, because that is either an honest collision or a record whose key bytes have
 * changed, and answering "not this key" to both would turn damage into absence. A damaged
 * record raises {@link CorruptSegmentEntryException} rather than returning null - a corrupt
 * entry and a missing one are different facts, and callers act on them differently.</p>
 *
 * <h2>Format width is read from the file, not assumed</h2>
 * <p>{@link #entryHeaderSize()} and {@link #keyBytesOffset()} come from the segment's
 * {@link com.eonmux.excalibur.store.format.SegmentFormat}, which is fixed for the life of the
 * mapping. A file written before checksums existed has a narrower header and no CRC field,
 * and is read and written at its own width so that it stays byte-for-byte as it was found.</p>
 *
 * <p><strong>Thread safety:</strong> none of its own; the caller holds the segment's read or
 * write lock, as it does for {@link SegmentSlots}.</p>
 */
final class SegmentRecords {

    /** The segment whose records these are. */
    private final Segment owner;

    /**
     * Binds this data region to its segment.
     *
     * @param owner The owning segment
     */
    SegmentRecords(Segment owner) {
        this.owner = owner;
    }

    /**
     * Writes an entry at the specified offset in the data region.
     *
     * <p>Entry format (as per spec):</p>
     * <ul>
     *   <li>key_hash (8 bytes, little-endian)</li>
     *   <li>key_len (4 bytes, little-endian)</li>
     *   <li>value_len (4 bytes, little-endian)</li>
     *   <li>distance (1 byte, unsigned, range [0, 127])</li>
     *   <li>crc32 (4 bytes, little-endian) - {@link com.eonmux.excalibur.store.format.SegmentFormat#V2} only</li>
     *   <li>key_bytes (variable)</li>
     *   <li>value_bytes (variable)</li>
     *   <li>tombstone (1 byte)</li>
     * </ul>
     *
     * @param offset The byte offset in the data region
     * @param keyHash The 64-bit key hash
     * @param distance Distance from ideal slot (must be in range [0, 127])
     * @param key The key bytes
     * @param value The value bytes
     * @throws IllegalStateException if distance is out of range [0, 127]
     */
    void writeEntryAtOffset(long offset, long keyHash, int distance, byte[] key, byte[] value) {
        // Domain correctness: validate distance before writing
        owner.slots.validateDistance(distance);

        owner.segment.set(Segment.LONG_LAYOUT, offset + Segment.ENTRY_KEY_HASH_OFFSET, keyHash);
        owner.segment.set(Segment.INT_LAYOUT, offset + Segment.ENTRY_KEY_LEN_OFFSET, key.length);
        owner.segment.set(Segment.INT_LAYOUT, offset + Segment.ENTRY_VALUE_LEN_OFFSET, value.length);
        owner.segment.set(Segment.BYTE_LAYOUT, offset + Segment.ENTRY_DISTANCE_OFFSET, (byte) distance);

        // Copy key bytes
        MemorySegment.copy(key, 0, owner.segment, Segment.BYTE_LAYOUT, offset + keyBytesOffset(), key.length);

        // Copy value bytes
        MemorySegment.copy(value, 0, owner.segment, Segment.BYTE_LAYOUT,
            offset + keyBytesOffset() + key.length, value.length);

        // Write tombstone (0 = live)
        owner.segment.set(Segment.BYTE_LAYOUT, offset + keyBytesOffset() + key.length + value.length, (byte) 0);

        // Last, and computed from the bytes that actually landed rather than from the
        // arguments, so the stored checksum can never describe something else.
        if (owner.format.hasChecksum()) {
            EntryChecksum.store(owner.segment, offset, owner.format);
        }
    }

    /**
     * Returns the width of this segment's entry header.
     *
     * @return The header width in bytes, which is also where an entry's key bytes begin
     */
    int entryHeaderSize() {
        return owner.format.entryHeaderSize();
    }

    /**
     * Returns the offset at which an entry's key bytes begin within its record.
     *
     * @return The offset in bytes
     */
    int keyBytesOffset() {
        return owner.format.keyBytesOffset();
    }

    /**
     * Checks an entry record against the checksum stored with it, before its bytes are used.
     *
     * <p>Structural damage is reported in every format, because a record that cannot be
     * read is damaged whether or not there is a checksum to weigh it against. A mismatch
     * can only be reported where a checksum was stored. Neither is ever turned into absence
     * - see {@link CorruptSegmentEntryException} for why those must stay distinguishable.</p>
     *
     * @param mem         The mapping to read, which may be a read-only snapshot view
     * @param entryOffset Absolute offset of the entry record
     * @param slotIndex   Slot the entry was reached through, for the report
     * @throws CorruptSegmentEntryException if the record does not match its checksum
     */
    void verifyEntry(MemorySegment mem, long entryOffset, int slotIndex) {
        String fault = SegmentIntegrityChecker.structuralFault(mem, entryOffset, owner.format);
        if (fault != null) {
            throw CorruptSegmentEntryException.malformed(owner.path, slotIndex,
                entryOffset - owner.slotTableSize, fault);
        }
        if (!owner.format.hasChecksum()) {
            return;
        }
        int stored = EntryChecksum.stored(mem, entryOffset, owner.format);
        int actual = EntryChecksum.compute(mem, entryOffset, owner.format);
        if (stored != actual) {
            throw CorruptSegmentEntryException.checksumMismatch(owner.path, slotIndex,
                entryOffset - owner.slotTableSize, stored, actual);
        }
    }

    /**
     * Checks if the key in the slot matches the given key.
     *
     * <p>Only ever asked of a slot whose stored hash already equals the key's, so a
     * comparison that then fails is one of two things: an honest hash collision between two
     * different keys, or a record whose key bytes are no longer what was written. Those
     * cannot be told apart by looking at the key, and answering "no" to both would turn
     * damage into absence - exactly the confusion the checksum exists to prevent. So a
     * failed comparison verifies the record before it answers.</p>
     *
     * @param slotIndex The slot to compare against
     * @param key       The key being looked up
     * @return true if the record holds this key
     * @throws CorruptSegmentEntryException if the record does not hold this key and does not
     *                                      match its checksum either
     */
    boolean keyMatches(int slotIndex, byte[] key) {
        long dataRegionOffset = owner.slots.readDataOffset(slotIndex);
        long entryOffset = owner.slotTableSize + dataRegionOffset;

        // Read key length
        int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        if (keyLen != key.length) {
            return notThisKey(owner.segment, entryOffset, slotIndex);
        }

        // Compare key bytes
        for (int i = 0; i < keyLen; i++) {
            byte entryByte = owner.segment.get(Segment.BYTE_LAYOUT, entryOffset + keyBytesOffset() + i);
            if (entryByte != key[i]) {
                return notThisKey(owner.segment, entryOffset, slotIndex);
            }
        }

        return true;
    }

    /**
     * Answers a key comparison that failed on a slot whose hash matched.
     *
     * @param mem         The mapping the record lives in
     * @param entryOffset Absolute offset of the record
     * @param slotIndex   The slot the record was reached through
     * @return false, once the record has been shown to be intact
     * @throws CorruptSegmentEntryException if the record is damaged
     */
    boolean notThisKey(MemorySegment mem, long entryOffset, int slotIndex) {
        verifyEntry(mem, entryOffset, slotIndex);
        return false;
    }

    /**
     * Returns a zero-copy MemorySegment slice of the value.
     *
     * <p>The record is verified before the slice is handed out, because the slice is the
     * last chance to say anything about it: once it is a view into the mapping, the caller
     * reads the bytes directly and nothing stands between them and a flipped bit.</p>
     *
     * @param slotIndex The slot holding the entry
     * @return A slice over the value bytes
     * @throws CorruptSegmentEntryException if the record does not match its checksum
     */
    MemorySegment getValueSlice(int slotIndex) {
        long dataRegionOffset = owner.slots.readDataOffset(slotIndex);
        long entryOffset = owner.slotTableSize + dataRegionOffset;
        verifyEntry(owner.segment, entryOffset, slotIndex);

        // Read key length and value length
        int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);

        // Create slice pointing to value bytes
        long valueOffset = entryOffset + keyBytesOffset() + keyLen;
        return owner.segment.asSlice(valueOffset, valueLen);
    }

    /**
     * Reads the entry size for a given entry index.
     *
     * @param dataOffset The data offset of the entry
     * @return Entry size in bytes
     */
    int readEntrySize(int dataOffset) {
        long keyOffset = owner.slotTableSize + dataOffset;
        int keyLen = owner.segment.get(Segment.INT_LAYOUT, keyOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen = owner.segment.get(Segment.INT_LAYOUT, keyOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
        return entryHeaderSize() + keyLen + valueLen + Segment.ENTRY_TRAILER_SIZE;
    }

    /**
     * Checks if there's enough space in the data region for the given entry size.
     *
     * @param entrySize The size of the entry to write
     * @return true if there's enough space, false otherwise
     */
    boolean hasDataSpace(int entrySize) {
        // The actual write position is: slotTableSize + dataOffset
        // We need to check if this + entrySize fits in the segment
        long requiredOffset = owner.slotTableSize + owner.dataOffset + entrySize;
        long segmentSize = owner.segment.byteSize();
        return requiredOffset <= segmentSize;
    }

    /**
     * Ensures the data region can hold one more entry record of the given size.
     *
     * @param entrySize Size of the record about to be appended
     * @throws SegmentCapacityExceededException if the data region is exhausted
     */
    void requireDataSpace(int entrySize) {
        if (!hasDataSpace(entrySize)) {
            throw new SegmentCapacityExceededException(
                SegmentCapacityExceededException.Reason.DATA_REGION,
                "Insufficient space in segment data region. Entry size: " + entrySize +
                ", available: " + (owner.segment.byteSize() - owner.slotTableSize - owner.dataOffset));
        }
    }

    /**
     * Computes a 64-bit hash for the key using xxHash64.
     *
     * <p>The hash is guaranteed to be positive (in range [0, Long.MAX_VALUE])
     * by masking off the sign bit using {@link SegmentConfig#normalizeKeyHash(long)}.</p>
     *
     * <h3>Domain Correctness</h3>
     * <p>This method ensures the returned hash is always in the range
     * [0, Long.MAX_VALUE] as required by the storage specification.
     * The xxHash64 algorithm produces uniformly distributed 64-bit values,
     * approximately half of which are negative when interpreted as signed longs.
     * By masking off the sign bit, we ensure all hashes are valid key_hash values.</p>
     *
     * @param key The key to hash
     * @return A normalized hash in range [0, Long.MAX_VALUE]
     */
    long computeHash(byte[] key) {
        long rawHash = XxHash64.hash(key);
        long keyHash = SegmentConfig.normalizeKeyHash(rawHash);

        // Assert invariant: key_hash must be in range [0, Long.MAX_VALUE]
        assert SegmentConfig.isValidKeyHash(keyHash) :
            "key_hash invariant violated: " + keyHash + " is not in range [0, Long.MAX_VALUE]";

        return keyHash;
    }

    void validateKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        // Nothing that reads this segment back can carry an empty key: neither a log record nor
        // the SegmentEntry the iterators build. Admitting one here writes an entry that no
        // compaction and no split can ever read past.
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (key.length > Integer.MAX_VALUE - entryHeaderSize() - Segment.ENTRY_TRAILER_SIZE - 4) {
            throw new IllegalArgumentException("Key too large: " + key.length + " bytes");
        }
    }

    void validateValue(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (value.length > Integer.MAX_VALUE - entryHeaderSize() - Segment.ENTRY_TRAILER_SIZE) {
            throw new IllegalArgumentException("Value too large: " + value.length + " bytes");
        }
    }

    /**
     * Validates an index into the live (non-tombstoned) entry space.
     *
     * @param index The index to validate
     * @throws IndexOutOfBoundsException if the index is outside [0, liveEntryCount)
     */
    void validateLiveIndex(int index) {
        if (index < 0 || index >= owner.liveEntryCount) {
            throw new IndexOutOfBoundsException(
                "Index " + index + " out of range [0, " + owner.liveEntryCount + ")");
        }
    }

    /**
     * Reads the key of the nth live entry.
     *
     * @param index The live entry index
     * @return The key bytes
     */
    byte[] readEntryKey(int index) {
        owner.lock.readLock().lock();
        try {
            // In a Robin Hood hash table, entries are NOT contiguous in slots 0..N.
            // We must scan all slots and find the Nth occupied (non-tombstoned) one.
            validateLiveIndex(index);
            int slotIndex = owner.slots.findNthOccupiedSlot(index);
            if (slotIndex < 0) {
                throw new IndexOutOfBoundsException(
                    "No live entry at index " + index + " in " + owner.path);
            }
            int dataOffset = owner.slots.readSlotDataOffset(slotIndex);
            long entryOffset = owner.slotTableSize + dataOffset;
            verifyEntry(owner.segment, entryOffset, slotIndex);
            int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
            byte[] key = new byte[keyLen];
            MemorySegment.copy(owner.segment, Segment.BYTE_LAYOUT, entryOffset + keyBytesOffset(),
                key, 0, keyLen);
            return key;
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * Reads the value of the nth live entry.
     *
     * @param index The live entry index
     * @return The value bytes
     */
    byte[] readEntryValue(int index) {
        owner.lock.readLock().lock();
        try {
            validateLiveIndex(index);
            int slotIndex = owner.slots.findNthOccupiedSlot(index);
            if (slotIndex < 0) {
                throw new IndexOutOfBoundsException(
                    "No live entry at index " + index + " in " + owner.path);
            }
            int dataOffset = owner.slots.readSlotDataOffset(slotIndex);
            long entryOffset = owner.slotTableSize + dataOffset;
            verifyEntry(owner.segment, entryOffset, slotIndex);
            int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
            int valueLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
            byte[] value = new byte[valueLen];
            MemorySegment.copy(owner.segment, Segment.BYTE_LAYOUT,
                entryOffset + keyBytesOffset() + keyLen, value, 0, valueLen);
            return value;
        } finally {
            owner.lock.readLock().unlock();
        }
    }
}
