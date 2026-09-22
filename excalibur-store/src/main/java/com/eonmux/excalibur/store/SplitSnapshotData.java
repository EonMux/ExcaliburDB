package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.EntryChecksum;
import com.eonmux.excalibur.store.format.SegmentEntry;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SlotChecksum;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The frozen contents behind a {@link Segment.SegmentSplitSnapshot}, and how they are read.
 *
 * <p>A split reads every entry of the parent and writes it into one of two children. That is
 * the whole of the work, and it is done outside every lock the parent holds: the snapshot is
 * a {@code MemorySegment.asReadOnly()} view taken at the instant the split began, so nothing
 * it reads can change underneath it and no reader has to be excluded while it runs.</p>
 *
 * <p>It lives here, rather than in the nested type callers name, because the nested type is
 * published API and this is not. Keeping the two apart means the walk can be read, changed
 * and reasoned about as the self-contained thing it is, while the class that
 * {@code excalibur-shard} and the compaction tooling compile against stays a handful of
 * signatures.</p>
 *
 * <h2>Nothing is copied forward unverified</h2>
 * <p>Every read checks three things before it hands bytes back: the slot's own checksum, the
 * record's, and that the two agree about the key they describe. An unverified read here would
 * copy damage into a freshly written file and give it a checksum asserting that it is sound -
 * turning a fault that {@link Segment#verifyIntegrity()} could still find into one that
 * nothing can. Each failure raises {@link CorruptSegmentEntryException} naming the slot and
 * the offset, and the split fails rather than completing over a hole.</p>
 *
 * <p>The slot is checked before its data offset is followed, because the offset is the part a
 * flipped bit turns into a read of some unrelated record that verifies perfectly well as
 * itself.</p>
 *
 * <p><strong>Thread safety:</strong> immutable, and safe for concurrent readers. The mapping
 * is read-only and the segment it came from cannot mutate while it is SPLITTING.</p>
 */
final class SplitSnapshotData {

    /** Read-only view of the parent's mapping, fixed when the split began. */
    private final MemorySegment segment;

    /** Number of slots in the frozen slot table. */
    private final int slotCount;

    /** Number of occupied slots at the instant of the snapshot. */
    private final int entryCount;

    /** Bytes the slot table occupies, which is where the data region begins. */
    private final long slotTableSize;

    /** Path of the file being split, used to name a fault. */
    private final Path path;

    /** On-disk format of the file being split, which fixes every offset read here. */
    private final SegmentFormat format;

    /**
     * Freezes a segment's contents for the duration of its split.
     *
     * @param segment       The mapping, which is made read-only here
     * @param slotCount     Number of slots
     * @param entryCount    Number of occupied slots
     * @param slotTableSize Size of the slot table region
     * @param path          Path to the segment file
     * @param format        On-disk entry format of the file being split
     */
    SplitSnapshotData(MemorySegment segment, int slotCount, int entryCount,
                      long slotTableSize, Path path, SegmentFormat format) {
        this.segment = segment.asReadOnly();
        this.slotCount = slotCount;
        this.entryCount = entryCount;
        this.slotTableSize = slotTableSize;
        this.path = path;
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * Returns the number of slots in the snapshot.
     *
     * @return Slot count
     */
    int slotCount() {
        return slotCount;
    }

    /**
     * Returns the number of occupied slots in the snapshot.
     *
     * @return Entry count
     */
    int entryCount() {
        return entryCount;
    }

    /**
     * Returns the path of the file being split.
     *
     * @return File path
     */
    Path path() {
        return path;
    }

    /**
     * Reads the entry held in one slot, verifying it before copying it out.
     *
     * @param index The slot index
     * @return The entry, or null if the index is out of range or the slot is empty
     * @throws CorruptSegmentEntryException if the slot, the record, or their agreement fails
     */
    SegmentEntry readEntry(int index) {
        if (index < 0 || index >= slotCount) {
            return null;
        }

        long slotOffset = (long) index * Segment.SLOT_SIZE;
        byte flags = segment.get(ValueLayout.JAVA_BYTE, slotOffset + Segment.SLOT_FLAGS_OFFSET);

        // Check if slot is occupied
        if ((flags & Segment.FLAG_OCCUPIED) == 0) {
            return null;
        }

        // The record is reached through this slot, so the slot is checked first: a split that
        // followed a damaged pointer would copy whatever it landed on into a fresh file and
        // give it a checksum saying it was sound.
        verifySlot(slotOffset, index);

        long dataRegionOffset = SlotChecksum.dataRegionOffset(segment, slotOffset);
        long entryOffset = slotTableSize + dataRegionOffset;
        verify(entryOffset, index);
        verifyAgreement(slotOffset, entryOffset, index);

        long keyHash = segment.get(Segment.LONG_LAYOUT, entryOffset + Segment.ENTRY_KEY_HASH_OFFSET);
        int keyLen = segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen = segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
        int distance =
            segment.get(Segment.BYTE_LAYOUT, entryOffset + Segment.ENTRY_DISTANCE_OFFSET) & 0xFF;

        byte[] key = new byte[keyLen];
        MemorySegment.copy(segment, Segment.BYTE_LAYOUT, entryOffset + format.keyBytesOffset(),
            key, 0, keyLen);

        byte[] value = new byte[valueLen];
        MemorySegment.copy(segment, Segment.BYTE_LAYOUT,
            entryOffset + format.keyBytesOffset() + keyLen, value, 0, valueLen);

        byte tombstone = segment.get(Segment.BYTE_LAYOUT,
            entryOffset + format.keyBytesOffset() + keyLen + valueLen);
        boolean tombstoned = (tombstone == 1);

        return new SegmentEntry(keyHash, key, value, distance, tombstoned);
    }

    /**
     * Walks every slot and collects what it finds.
     *
     * @param includeTombstones Whether to keep tombstoned entries
     * @return The entries, in slot order
     * @throws CorruptSegmentEntryException if any entry fails verification
     */
    List<SegmentEntry> entries(boolean includeTombstones) {
        List<SegmentEntry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < slotCount; i++) {
            SegmentEntry entry = readEntry(i);
            if (entry != null && (includeTombstones || !entry.isTombstoned())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Checks a slot against the checksum stored in it.
     *
     * @param slotOffset Absolute offset of the slot
     * @param slotIndex  The slot's index
     * @throws CorruptSegmentEntryException if the slot does not match its checksum
     */
    private void verifySlot(long slotOffset, int slotIndex) {
        if (!format.hasSlotChecksum()) {
            return;
        }
        int stored = SlotChecksum.stored(segment, slotOffset);
        int actual = SlotChecksum.compute(segment, slotOffset);
        if (stored != actual) {
            throw CorruptSegmentEntryException.slotChecksumMismatch(path, slotIndex,
                SlotChecksum.dataRegionOffset(segment, slotOffset), stored, actual);
        }
    }

    /**
     * Checks a slot against the record it names, both being read here anyway.
     *
     * @param slotOffset  Absolute offset of the slot
     * @param entryOffset Absolute offset of the record
     * @param slotIndex   The slot's index
     * @throws CorruptSegmentEntryException if the slot and the record disagree
     */
    private void verifyAgreement(long slotOffset, long entryOffset, int slotIndex) {
        String fault = SegmentIntegrityChecker.slotEntryFault(segment, slotOffset, entryOffset,
            format);
        if (fault != null) {
            throw CorruptSegmentEntryException.slotEntryMismatch(path, slotIndex,
                entryOffset - slotTableSize, fault);
        }
    }

    /**
     * Checks an entry record against the checksum stored with it.
     *
     * @param entryOffset Absolute offset of the record
     * @param slotIndex   Slot the record was reached through
     * @throws CorruptSegmentEntryException if the record does not match its checksum
     */
    private void verify(long entryOffset, int slotIndex) {
        String fault = SegmentIntegrityChecker.structuralFault(segment, entryOffset, format);
        if (fault != null) {
            throw CorruptSegmentEntryException.malformed(path, slotIndex,
                entryOffset - slotTableSize, fault);
        }
        if (!format.hasChecksum()) {
            return;
        }
        int stored = EntryChecksum.stored(segment, entryOffset, format);
        int actual = EntryChecksum.compute(segment, entryOffset, format);
        if (stored != actual) {
            throw CorruptSegmentEntryException.checksumMismatch(path, slotIndex,
                entryOffset - slotTableSize, stored, actual);
        }
    }
}
