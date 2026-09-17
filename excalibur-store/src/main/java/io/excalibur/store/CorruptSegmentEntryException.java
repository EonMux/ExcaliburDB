package io.excalibur.store;

import java.nio.file.Path;

/**
 * Thrown when a segment cannot stand behind what a read of it would return.
 *
 * <p>A corrupt entry and an absent one are different facts, and a caller has to be able to
 * act on the difference: a key that is not there means the caller should write it or look
 * elsewhere, while a key whose bytes are damaged means the copy on this node is wrong and
 * another copy, or a restore, is needed. Returning {@code null} for both would tell a reader
 * that a document had been deleted when in truth it is still there and unreadable, and would
 * let a compaction quietly drop it from the segment it rebuilds. Returning the bytes
 * themselves is worse still: a single flipped bit becomes an answer.</p>
 *
 * <p>So a failed verification is raised, never swallowed and never flattened into absence.
 * It is unchecked because it is raised from {@link Segment#get(byte[])} and its siblings,
 * which sit on the query path and cannot declare a checked exception; it is nonetheless a
 * fault to be reported to an operator rather than a condition to be retried. The one place
 * that answers it with data instead of a throw is {@link Segment#verifyIntegrity()}, whose
 * whole purpose is to survey the damage rather than stop at the first of it.</p>
 *
 * <h2>Three kinds of damage</h2>
 * <p>A record can fail its checksum, which is what {@link #checksumMismatch} reports, or it
 * can be unreadable before a checksum is even relevant - pointing outside the file, or
 * declaring a length that cannot exist - which is what {@link #malformed} reports. The
 * second is detected in every format; the first only in one that stores checksums, because
 * entries written before checksums existed have nothing to be compared against. See
 * {@link io.excalibur.store.format.SegmentFormat}.</p>
 *
 * <p>The third is damage to the slot table rather than to a record, which
 * {@link #slotChecksumMismatch} and {@link #slotEntryMismatch} report. It is the same refusal for the same reason, and it
 * is raised on the same paths, but the fact it carries is different and an operator acts on
 * it differently: the record is very likely intact and merely unreachable, so the file is
 * a candidate for having its slot table rebuilt rather than for being replaced from a
 * backup. {@link #isSlotFault()} is what tells the two apart.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class CorruptSegmentEntryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path segmentPath;
    private final int slotIndex;
    private final long dataRegionOffset;
    private final boolean structural;
    private final boolean slotFault;
    private final int expectedChecksum;
    private final int actualChecksum;

    /**
     * Records damage to one entry.
     *
     * @param segmentPath      The segment file holding the entry
     * @param slotIndex        Slot the entry was reached through, or -1 if unknown
     * @param dataRegionOffset Offset of the entry record within the data region
     * @param structural       True if the record is unreadable rather than merely mismatched
     * @param slotFault        True if the slot table is what is damaged, not the record
     * @param expectedChecksum The checksum stored on disk, 0 if not applicable
     * @param actualChecksum   The checksum of the bytes present, 0 if not applicable
     * @param message          What was found wrong
     */
    protected CorruptSegmentEntryException(Path segmentPath, int slotIndex,
                                           long dataRegionOffset, boolean structural,
                                           boolean slotFault, int expectedChecksum,
                                           int actualChecksum, String message) {
        super(message);
        this.segmentPath = segmentPath;
        this.slotIndex = slotIndex;
        this.dataRegionOffset = dataRegionOffset;
        this.structural = structural;
        this.slotFault = slotFault;
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
    }

    /**
     * Reports a record whose bytes disagree with the checksum stored alongside them.
     *
     * @param segmentPath      The segment file holding the entry
     * @param slotIndex        Slot the entry was reached through, or -1 if unknown
     * @param dataRegionOffset Offset of the entry record within the data region
     * @param expectedChecksum The checksum stored in the record
     * @param actualChecksum   The checksum of the bytes actually present
     * @return The failure to throw
     */
    public static CorruptSegmentEntryException checksumMismatch(
            Path segmentPath, int slotIndex, long dataRegionOffset,
            int expectedChecksum, int actualChecksum) {
        return new CorruptSegmentEntryException(segmentPath, slotIndex, dataRegionOffset, false,
            false, expectedChecksum, actualChecksum, String.format(
                "Entry at data region offset %d (slot %d) of %s fails its checksum: "
                    + "stored %08x, computed %08x",
                dataRegionOffset, slotIndex, segmentPath, expectedChecksum, actualChecksum));
    }

    /**
     * Reports a record that cannot be read at all.
     *
     * @param segmentPath      The segment file holding the entry
     * @param slotIndex        Slot the entry was reached through, or -1 if unknown
     * @param dataRegionOffset Offset of the entry record within the data region
     * @param detail           What is wrong with the record
     * @return The failure to throw
     */
    public static CorruptSegmentEntryException malformed(
            Path segmentPath, int slotIndex, long dataRegionOffset, String detail) {
        return new CorruptSegmentEntryException(segmentPath, slotIndex, dataRegionOffset, true,
            false, 0, 0, String.format(
                "Entry at data region offset %d (slot %d) of %s is unreadable: %s",
                dataRegionOffset, slotIndex, segmentPath, detail));
    }

    /**
     * Reports a slot whose bytes disagree with the checksum stored alongside them.
     *
     * <p>Raised instead of answering "not found", which is what a damaged slot would
     * otherwise make a lookup say. The entry the slot used to name is very likely still
     * there and still sound; what has been lost is the way to reach it.</p>
     *
     * @param segmentPath      The segment file holding the slot
     * @param slotIndex        The damaged slot
     * @param dataRegionOffset The data region offset the slot claims, which is itself suspect
     * @param expectedChecksum The checksum stored in the slot
     * @param actualChecksum   The checksum of the bytes actually present
     * @return The failure to throw
     */
    public static CorruptSegmentEntryException slotChecksumMismatch(
            Path segmentPath, int slotIndex, long dataRegionOffset,
            int expectedChecksum, int actualChecksum) {
        return new CorruptSegmentEntryException(segmentPath, slotIndex, dataRegionOffset, false,
            true, expectedChecksum, actualChecksum, String.format(
                "Slot %d of %s fails its checksum: stored %08x, computed %08x. Its key hash, "
                    + "occupancy flag or data offset (claimed: %d) has changed, so the entry "
                    + "it named cannot be reached; the entry's own bytes may be sound",
                slotIndex, segmentPath, expectedChecksum, actualChecksum, dataRegionOffset));
    }

    /**
     * Reports a slot and the record it names disagreeing about a field both carry.
     *
     * <p>What finds a damaged slot in a file whose format carries no slot checksum, and
     * the only thing that finds a damaged probe-distance byte in any file. Raised where a
     * rewrite would otherwise carry the disagreement forward: a compaction copies what the
     * slot table reaches, so a slot that no longer reaches its record makes that record
     * disappear permanently.</p>
     *
     * @param segmentPath      The segment file
     * @param slotIndex        The slot
     * @param dataRegionOffset Offset of the record the slot names
     * @param detail           What the two disagree about, and what follows from it
     * @return The failure to throw
     */
    public static CorruptSegmentEntryException slotEntryMismatch(
            Path segmentPath, int slotIndex, long dataRegionOffset, String detail) {
        return new CorruptSegmentEntryException(segmentPath, slotIndex, dataRegionOffset, false,
            true, 0, 0, String.format(
                "Slot %d of %s does not agree with the entry at data region offset %d: %s",
                slotIndex, segmentPath, dataRegionOffset, detail));
    }

    /**
     * Returns the segment file holding the damaged entry.
     *
     * @return The segment path
     */
    public Path segmentPath() {
        return segmentPath;
    }

    /**
     * Returns the slot the damaged entry was reached through.
     *
     * @return The slot index, or -1 if the entry was reached without one
     */
    public int slotIndex() {
        return slotIndex;
    }

    /**
     * Returns the offset of the damaged record within the data region.
     *
     * @return The data region offset
     */
    public long dataRegionOffset() {
        return dataRegionOffset;
    }

    /**
     * Reports whether the record was unreadable rather than merely mismatched.
     *
     * @return true if the record's own structure is wrong
     */
    public boolean isStructural() {
        return structural;
    }

    /**
     * Reports whether the slot table is what is damaged, rather than the record.
     *
     * <p>Worth acting on differently: a damaged record has lost data and needs another
     * copy, while a damaged slot has lost only the pointer to a record that is probably
     * still intact.</p>
     *
     * @return true if a slot failed its checksum
     */
    public boolean isSlotFault() {
        return slotFault;
    }

    /**
     * Returns the checksum the record claims.
     *
     * @return The stored checksum, or 0 for structural damage
     */
    public int expectedChecksum() {
        return expectedChecksum;
    }

    /**
     * Returns the checksum of the bytes actually present.
     *
     * @return The computed checksum, or 0 for structural damage
     */
    public int actualChecksum() {
        return actualChecksum;
    }
}
