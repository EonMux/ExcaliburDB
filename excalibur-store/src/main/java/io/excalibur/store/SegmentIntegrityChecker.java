package io.excalibur.store;

import io.excalibur.store.SegmentIntegrityReport.DamageKind;
import io.excalibur.store.SegmentIntegrityReport.DamagedEntry;
import io.excalibur.store.format.EntryChecksum;
import io.excalibur.store.format.SegmentFormat;
import io.excalibur.store.format.SegmentLayout;
import io.excalibur.store.format.SlotChecksum;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks every slot and entry of a mapped segment file and reports what does not hold up.
 *
 * <p>Separate from {@link Segment} because it is the thing a repair tool is built on, not
 * part of serving reads: it takes a mapping and describes it, holds no state, and never
 * writes. Where a read stops at the first damaged entry by raising
 * {@link CorruptSegmentEntryException}, this runs the file to the end, because the useful
 * question for an operator is how much is wrong rather than whether anything is.</p>
 *
 * <p>Each occupied slot is weighed in three steps, in the order in which one makes the next
 * one mean anything.</p>
 * <ol>
 *   <li>The slot itself, against the checksum stored in it. A file whose format carries no
 *       slot checksum skips this and its slots are counted unverifiable. A slot that fails
 *       ends the examination of that slot: the record it names cannot be assumed to be the
 *       record it was meant to name, so checksumming whatever lies there would answer a
 *       question nobody asked.</li>
 *   <li>The record, first for whether it can be read at all and then against its own
 *       checksum, exactly as a point read does.</li>
 *   <li>The slot against the record, which is the check neither side can make alone. The
 *       key hash and the probe distance are each stored in both places, and
 *       {@code Segment.writeSlotMetadata} is the only thing in the engine that changes
 *       either copy of either - it writes both together - so a disagreement is damage.
 *       This is what catches a damaged slot in a file too old to carry a slot checksum, it
 *       is the only thing that catches a damaged distance byte in any file, and it costs
 *       nothing here because both sides have already been read.</li>
 * </ol>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class SegmentIntegrityChecker {

    /** Not instantiable: this class is a single walk over a mapping. */
    private SegmentIntegrityChecker() {
    }

    /**
     * Verifies every occupied slot and the entry record it points at.
     *
     * <p>The caller must hold the segment's read lock, or otherwise guarantee that nothing
     * is writing to the mapping, for the counts to describe a single moment.</p>
     *
     * @param mapped        The mapped segment file
     * @param slotCount     Number of slots in the slot table
     * @param slotTableSize Size of the slot table, which is where the data region begins
     * @param format        The format the file is written in
     * @param path          The file, for the report
     * @return What the walk found
     */
    public static SegmentIntegrityReport verify(MemorySegment mapped, int slotCount,
                                                long slotTableSize, SegmentFormat format,
                                                Path path) {
        List<DamagedEntry> damaged = new ArrayList<>();
        int scanned = 0;
        int verified = 0;
        int unverifiable = 0;
        int slotsVerified = 0;
        int slotsUnverifiable = 0;

        for (int slot = 0; slot < slotCount; slot++) {
            if (!isOccupied(mapped, slot)) {
                continue;
            }
            scanned++;

            DamagedEntry slotFault = inspectSlot(mapped, slot, format);
            if (slotFault != null) {
                damaged.add(slotFault);
                continue;
            }
            if (format.hasSlotChecksum()) {
                slotsVerified++;
            } else {
                slotsUnverifiable++;
            }

            DamagedEntry fault = inspect(mapped, slot, slotTableSize, format);
            if (fault != null) {
                damaged.add(fault);
            } else if (format.hasChecksum()) {
                verified++;
            } else {
                unverifiable++;
            }
        }

        return new SegmentIntegrityReport(path, format, slotCount, scanned, verified,
            unverifiable, slotsVerified, slotsUnverifiable, damaged);
    }

    /**
     * Examines a slot against the checksum stored in it.
     *
     * @param mapped The mapped segment file
     * @param slot   The slot index
     * @param format The format the file is written in
     * @return The fault found, or null if the slot holds up or carries nothing to check
     */
    private static DamagedEntry inspectSlot(MemorySegment mapped, int slot,
                                            SegmentFormat format) {
        if (!format.hasSlotChecksum()) {
            return null;
        }
        long slotOffset = SlotChecksum.offsetOf(slot);
        int stored = SlotChecksum.stored(mapped, slotOffset);
        int actual = SlotChecksum.compute(mapped, slotOffset);
        if (stored == actual) {
            return null;
        }
        return new DamagedEntry(slot, SlotChecksum.dataRegionOffset(mapped, slotOffset),
            mapped.get(SegmentLayout.LONG_LAYOUT, slotOffset + SegmentLayout.SLOT_KEY_HASH_OFFSET),
            DamageKind.SLOT_CHECKSUM, stored, actual, String.format(
                "slot checksum mismatch: stored %08x, computed %08x. The slot's key hash, "
                    + "occupancy flag or data offset has changed, so the entry it named is "
                    + "unreachable; the entry's own bytes may be perfectly sound",
                stored, actual));
    }

    /**
     * Examines the record a slot points at, and then the slot against that record.
     *
     * @param mapped        The mapped segment file
     * @param slot          The slot index
     * @param slotTableSize Offset at which the data region begins
     * @param format        The format the file is written in
     * @return The fault found, or null if the record holds up
     */
    private static DamagedEntry inspect(MemorySegment mapped, int slot, long slotTableSize,
                                        SegmentFormat format) {
        long slotOffset = SlotChecksum.offsetOf(slot);
        long dataRegionOffset = SlotChecksum.dataRegionOffset(mapped, slotOffset);
        long entryOffset = slotTableSize + dataRegionOffset;

        String structural = structuralFault(mapped, entryOffset, format);
        if (structural != null) {
            return new DamagedEntry(slot, dataRegionOffset, 0L, DamageKind.STRUCTURAL, 0, 0,
                structural);
        }

        long keyHash = mapped.get(SegmentLayout.LONG_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_KEY_HASH_OFFSET);
        if (format.hasChecksum()) {
            int stored = EntryChecksum.stored(mapped, entryOffset, format);
            int actual = EntryChecksum.compute(mapped, entryOffset, format);
            if (stored != actual) {
                return new DamagedEntry(slot, dataRegionOffset, keyHash,
                    DamageKind.ENTRY_CHECKSUM, stored, actual, String.format(
                        "checksum mismatch: stored %08x, computed %08x", stored, actual));
            }
        }

        String disagreement = slotEntryFault(mapped, slotOffset, entryOffset, format);
        if (disagreement == null) {
            return null;
        }
        return new DamagedEntry(slot, dataRegionOffset, keyHash,
            DamageKind.SLOT_ENTRY_MISMATCH, 0, 0, disagreement);
    }

    /**
     * Compares the two fields a slot and the record it names each hold a copy of.
     *
     * <p>Shared with {@link Segment}'s maintenance readers, the way
     * {@link #structuralFault} is shared with its read path, so that a walk and a rewrite
     * say the same thing about the same slot.</p>
     *
     * <p>Both copies of both fields are written by one method,
     * {@code Segment.writeSlotMetadata}, which writes them together; nothing else in the
     * engine changes either copy of either while the slot is reachable. A disagreement is
     * therefore damage rather than a state the writer can produce.</p>
     *
     * <p>The two differ in what can be concluded, which is why the wording differs. A key
     * hash disagreement in a checksummed format is decidable: the record's copy is under
     * the record's checksum, which has already been weighed by the time this runs, so the
     * slot is the damaged side. A distance disagreement is not decidable in any format,
     * because the distance byte is deliberately outside both checksums - Robin Hood
     * displacement rewrites it on records it does not otherwise touch - so all that can be
     * said is that one of the two copies is wrong.</p>
     *
     * @param mapped      The mapped segment file
     * @param slotOffset  Absolute offset of the slot
     * @param entryOffset Absolute offset of the record the slot names
     * @param format      The format the file is written in
     * @return What the two disagree about, or null if they agree
     */
    static String slotEntryFault(MemorySegment mapped, long slotOffset, long entryOffset,
                                 SegmentFormat format) {
        long slotKeyHash = mapped.get(SegmentLayout.LONG_LAYOUT,
            slotOffset + SegmentLayout.SLOT_KEY_HASH_OFFSET);
        long entryKeyHash = mapped.get(SegmentLayout.LONG_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_KEY_HASH_OFFSET);
        if (slotKeyHash != entryKeyHash) {
            String provenance = format.hasChecksum()
                ? "the entry verified against its own checksum, so its key hash is the "
                    + "trustworthy one and the slot is what is damaged"
                : "neither copy carries a checksum in this format, so which of the two is "
                    + "damaged cannot be established from the file alone";
            return String.format(
                "slot and entry disagree about the key hash: the slot says %016x, the entry "
                    + "says %016x. %s. The entry itself is sound and unreachable: every "
                    + "lookup of its key reads as not found",
                slotKeyHash, entryKeyHash, provenance);
        }

        int slotDistance = mapped.get(SegmentLayout.BYTE_LAYOUT,
            slotOffset + SegmentLayout.SLOT_DISTANCE_OFFSET) & 0xFF;
        int entryDistance = mapped.get(SegmentLayout.BYTE_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_DISTANCE_OFFSET) & 0xFF;
        if (slotDistance != entryDistance) {
            return String.format(
                "slot and entry disagree about the probe distance: the slot says %d, the "
                    + "entry says %d. The distance byte is outside both checksums, because "
                    + "displacement rewrites it on records it does not otherwise touch, so "
                    + "neither copy can be shown to be the right one. A wrong slot distance "
                    + "cuts a probe chain short, and every key that hashes before this slot "
                    + "then reads as not found",
                slotDistance, entryDistance);
        }
        return null;
    }

    /**
     * Reports whether a record can be read at all, before its checksum is consulted.
     *
     * <p>Shared with {@link Segment}'s read path so that a walk and a point read say the
     * same thing about the same record. Applies to every format: a record that points
     * outside the file or declares an impossible length is damaged whether or not the file
     * has checksums, and a well-formed record can never fail this - a key is never empty,
     * and a record that was written fits in the file it was written to.</p>
     *
     * @param mapped      The mapped segment file
     * @param entryOffset Absolute offset of the record
     * @param format      The format the file is written in
     * @return What is wrong with the record, or null if it is readable
     */
    static String structuralFault(MemorySegment mapped, long entryOffset,
                                  SegmentFormat format) {
        long size = mapped.byteSize();
        if (entryOffset < 0 || entryOffset + format.entryHeaderSize() > size) {
            return "entry header at " + entryOffset + " lies outside the " + size + "-byte file";
        }
        int keyLen = mapped.get(SegmentLayout.INT_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET);
        int valueLen = mapped.get(SegmentLayout.INT_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_VALUE_LEN_OFFSET);
        if (keyLen < 1) {
            return "entry declares key length " + keyLen + ", and no key may be empty";
        }
        if (valueLen < 0) {
            return "entry declares negative value length " + valueLen;
        }
        long end = entryOffset + format.entryHeaderSize() + (long) keyLen + valueLen
            + SegmentLayout.ENTRY_TRAILER_SIZE;
        if (end > size) {
            return "entry runs to " + end + ", past the end of the " + size + "-byte file";
        }
        return null;
    }

    /**
     * Reports whether a slot is occupied.
     *
     * @param mapped The mapped segment file
     * @param slot   The slot index
     * @return true if the slot's occupied flag is set
     */
    private static boolean isOccupied(MemorySegment mapped, int slot) {
        byte flags = mapped.get(SegmentLayout.BYTE_LAYOUT,
            SlotChecksum.offsetOf(slot) + SegmentLayout.SLOT_FLAGS_OFFSET);
        return (flags & SegmentLayout.FLAG_OCCUPIED) != 0;
    }
}
