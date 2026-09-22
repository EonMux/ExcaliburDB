package com.eonmux.excalibur.store.format;

import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32;

/**
 * Computes the CRC32 that a {@link SegmentFormat#V3} slot carries.
 *
 * <p>An entry record's checksum proves that the bytes of the record are the bytes that were
 * written. It proves nothing about the pointer the record was reached through, because that
 * pointer is what was followed to find the record in the first place. A flipped bit in a
 * slot's key hash makes a sound, checksummed, still-present entry read as absent; a flipped
 * bit in its data offset sends a read somewhere else in the data region; a flipped bit in
 * its flag byte makes an entry vanish or makes slack space look like an entry. This is the
 * checksum that closes that.</p>
 *
 * <h2>Coverage</h2>
 * <p>The checksum covers the slot's key hash, its flag byte and its six-byte data offset -
 * everything a lookup follows. It does <strong>not</strong> cover the distance byte that
 * sits between the key hash and the flags. Robin Hood displacement rewrites the distance of
 * a slot whose entry is otherwise untouched, so a checksum including it would go stale on a
 * write to an entirely different key; {@link EntryChecksum} leaves the record's copy of the
 * same byte out for the same reason. The distance byte is a probe hint, held redundantly in
 * the entry record, and a wrong one costs a lookup rather than an answer.</p>
 *
 * <h2>Why it lives in the reserved bytes</h2>
 * <p>A slot has always been 24 bytes of which the last 8 were reserved for alignment and
 * written nowhere. Putting the checksum there leaves the slot's size, the slot table's size
 * and therefore every segment file's geometry exactly as they were, so a version 3 file
 * differs from a version 2 file only in bytes that a version 2 file never wrote.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class SlotChecksum {

    /** Scratch space for {@link #compute}, reused so that a verification allocates nothing. */
    private static final ThreadLocal<byte[]> SCRATCH =
        ThreadLocal.withInitial(() -> new byte[SegmentLayout.SLOT_CHECKSUMMED_LENGTH]);


    /** Not instantiable: this class is a single computation. */
    private SlotChecksum() {
    }

    /**
     * Returns the offset of a slot within the slot table.
     *
     * @param slotIndex The slot index
     * @return The absolute offset of the slot, which is also its offset in the file
     */
    public static long offsetOf(int slotIndex) {
        return (long) slotIndex * SegmentLayout.SLOT_SIZE;
    }

    /**
     * Computes the checksum of the slot at an offset in a mapped segment file.
     *
     * @param mapped     The mapped segment file, or a read-only view of one
     * @param slotOffset Absolute offset of the slot
     * @return The CRC32 of the slot's key hash, flags and data offset
     * @throws IndexOutOfBoundsException if the slot does not lie inside the mapping
     */
    public static int compute(MemorySegment mapped, long slotOffset) {
        byte[] scratch = SCRATCH.get();
        MemorySegment.copy(mapped, SegmentLayout.BYTE_LAYOUT,
            slotOffset + SegmentLayout.SLOT_CHECKSUMMED_HEAD_OFFSET, scratch, 0,
            SegmentLayout.SLOT_CHECKSUMMED_HEAD_LENGTH);
        MemorySegment.copy(mapped, SegmentLayout.BYTE_LAYOUT,
            slotOffset + SegmentLayout.SLOT_CHECKSUMMED_TAIL_OFFSET, scratch,
            SegmentLayout.SLOT_CHECKSUMMED_HEAD_LENGTH,
            SegmentLayout.SLOT_CHECKSUMMED_TAIL_LENGTH);
        CRC32 crc = new CRC32();
        crc.update(scratch, 0, SegmentLayout.SLOT_CHECKSUMMED_LENGTH);
        return (int) crc.getValue();
    }

    /**
     * Reads the checksum stored in a slot.
     *
     * @param mapped     The mapped segment file, or a read-only view of one
     * @param slotOffset Absolute offset of the slot
     * @return The stored CRC32
     */
    public static int stored(MemorySegment mapped, long slotOffset) {
        return mapped.get(SegmentLayout.INT_LAYOUT,
            slotOffset + SegmentLayout.SLOT_CHECKSUM_OFFSET);
    }

    /**
     * Writes the checksum of an already-written slot into that slot.
     *
     * <p>Computed from the bytes that landed in the mapping rather than from the values the
     * caller meant to write, for the reason {@link EntryChecksum} gives: the stored checksum
     * then describes the slot on disk and the two cannot drift apart.</p>
     *
     * @param mapped     The mapped segment file
     * @param slotOffset Absolute offset of the slot
     */
    public static void store(MemorySegment mapped, long slotOffset) {
        mapped.set(SegmentLayout.INT_LAYOUT, slotOffset + SegmentLayout.SLOT_CHECKSUM_OFFSET,
            compute(mapped, slotOffset));
    }

    /**
     * Reports whether a slot's stored checksum describes its current bytes.
     *
     * @param mapped     The mapped segment file, or a read-only view of one
     * @param slotOffset Absolute offset of the slot
     * @return true if the slot holds up
     */
    public static boolean matches(MemorySegment mapped, long slotOffset) {
        return stored(mapped, slotOffset) == compute(mapped, slotOffset);
    }

    /**
     * Finds the first damaged slot in a probe window.
     *
     * <p>What a lookup that is about to answer "not found" asks. The probe itself runs at
     * full speed and consults no checksum; only when it is about to report absence does the
     * window it walked have to be shown sound, because absence is the one answer that
     * damage to the slot table can fabricate. The slots have just been read, so this second
     * pass touches no memory the first did not.</p>
     *
     * @param mapped    The mapped segment file, or a read-only view of one
     * @param slotCount Number of slots in the slot table, a power of two
     * @param idealSlot The slot the probe started at
     * @param probes    How many slots the probe examined, at least one
     * @return The index of the first slot whose checksum does not hold, or -1 if all do
     */
    public static int firstDamagedSlot(MemorySegment mapped, int slotCount, int idealSlot,
                                       int probes) {
        for (int distance = 0; distance < probes; distance++) {
            int slotIndex = (idealSlot + distance) & (slotCount - 1);
            if (!matches(mapped, offsetOf(slotIndex))) {
                return slotIndex;
            }
        }
        return -1;
    }

    /**
     * Reads the six-byte little-endian data region offset out of a slot.
     *
     * <p>Here rather than beside the other slot readers because these are bytes the
     * checksum covers: a fault this class reports has to be able to say what the slot was
     * pointing at when it was found wrong.</p>
     *
     * @param mapped     The mapped segment file, or a read-only view of one
     * @param slotOffset Absolute offset of the slot
     * @return The data region offset the slot points at
     */
    public static long dataRegionOffset(MemorySegment mapped, long slotOffset) {
        long offset = 0;
        for (int i = 0; i < SegmentLayout.SLOT_DATA_OFFSET_LENGTH; i++) {
            offset |= (mapped.get(SegmentLayout.BYTE_LAYOUT,
                slotOffset + SegmentLayout.SLOT_DATA_OFFSET_OFFSET + i) & 0xFFL) << (8 * i);
        }
        return offset;
    }
}
