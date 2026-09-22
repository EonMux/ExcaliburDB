package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentIntegrityChecker;
import com.eonmux.excalibur.store.SegmentIntegrityReport;
import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Verifies a {@code segment_N.dat} file without opening it as a segment.
 *
 * <p>The walk itself is {@link SegmentIntegrityChecker}, which is the store's own; nothing
 * about the entry format is restated here. What this adds is the read-only route to it:
 * {@code Segment.openExisting} maps read-write and stamps a data-region header into a
 * legacy file whose slot table is empty, which is right on a node's start-up path and
 * unacceptable on a checker's.</p>
 *
 * <p>The walk weighs the slot table as well as the records, and the difference decides what
 * this reports. A damaged record has lost data. A damaged slot has lost only the pointer to
 * a record that is very likely still sound and simply unreachable - which is not something
 * any repair here can act on, because nothing establishes which side of the disagreement is
 * the wrong one, and moving the whole file aside would set aside every intact entry in it
 * along with the one pointer that is wrong. That verdict is marked for an operator.</p>
 */
public final class SegmentDataCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "segment data";

    /** How many damaged entries are named individually before the list is summarised. */
    private static final int DAMAGE_SAMPLE = 5;

    private SegmentDataCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Walks every entry in a segment file.
     *
     * @param file     The segment file
     * @param relative Its path relative to the data directory
     * @return What the walk found
     */
    public static FileCheck check(Path file, String relative) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return unreadable(file, relative, -1, "its size could not be read: " + e);
        }
        if (size < SegmentLayout.SLOT_SIZE) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
                "the file is " + size + " bytes, too short to hold even one slot", size);
        }
        try (ReadOnlyMapping mapping = ReadOnlyMapping.of(file)) {
            return verdict(file, relative, size, mapping);
        } catch (IOException | RuntimeException e) {
            return unreadable(file, relative, size, "it could not be mapped and walked: " + e);
        }
    }

    /**
     * Derives the file's geometry, walks it, and phrases the outcome.
     *
     * @param file     The segment file
     * @param relative Its path relative to the data directory
     * @param size     The file's size
     * @param mapping  The read-only mapping
     * @return What the walk found
     */
    private static FileCheck verdict(Path file, String relative, long size,
                                     ReadOnlyMapping mapping) {
        int slotCount = Segment.deriveSlotCountForRecovery(size);
        long slotTableSize = (long) slotCount * SegmentLayout.SLOT_SIZE;
        if (slotTableSize > size) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
                "a slot table of " + slotTableSize + " bytes does not fit in " + size, size);
        }
        SegmentFormat format = SegmentDataHeader.detect(mapping.segment(), slotTableSize);
        SegmentIntegrityReport report = SegmentIntegrityChecker.verify(
            mapping.segment(), slotCount, slotTableSize, format, file);
        String version = "v" + format.version();
        if (!report.isHealthy()) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.CORRUPT,
                damageDetail(report), size, report.slotFaultCount() > 0);
        }
        if (!format.hasChecksum()) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.LEGACY_UNPROTECTED,
                report.entriesScanned() + " entries are structurally sound and every slot "
                    + "agrees with the record it names, but this format carries no checksum "
                    + "so their contents cannot be verified; a compaction or a split rewrites "
                    + "the file into one that can", size);
        }
        FileCheck sound = new FileCheck(file, relative, FORMAT, version, CheckStatus.INTACT,
            soundDetail(report), size);
        // Intact is the true statement about what was checked: every checksum this file
        // carries was found and matched. It is not the whole truth about what is proven,
        // because a format without slot checksums leaves the slot table unweighed - so the
        // second fact is carried separately rather than by bending the first.
        //
        // Keyed on the format rather than on report.slotsUnverifiable() being non-zero: a
        // legacy segment that happens to hold no entry yet has nothing unproven in it today
        // and no way to prove anything tomorrow, and a directory of such files passing
        // strict silently is the same hole one slot wide.
        return format.hasSlotChecksum() ? sound : sound.withUnverifiedContent();
    }

    /**
     * Phrases what a segment that nothing was found wrong with was actually shown to be.
     *
     * <p>Named precisely because the two checksummed versions prove different things. Both
     * prove that every record is the record that was written and that every slot names the
     * record it claims to. Only the newer one proves that a slot which says it is empty was
     * always empty: in the older one that bit is the one thing with nothing to weigh it
     * against, so an entry hidden by a flipped occupancy flag would be found here as
     * nothing at all.</p>
     *
     * @param report What the walk found
     * @return A detail naming what was proven and, where it matters, what was not
     */
    private static String soundDetail(SegmentIntegrityReport report) {
        if (report.format().hasSlotChecksum()) {
            return report.entriesVerified() + " of " + report.entriesScanned()
                + " occupied slots matched their slot checksum and their entry's checksum, "
                + "across " + report.slotsScanned() + " slots";
        }
        return report.entriesVerified() + " of " + report.entriesScanned()
            + " entries matched their checksum and are named by a slot that agrees with "
            + "them, across " + report.slotsScanned() + " slots; this format carries no slot "
            + "checksum, so a slot whose occupancy bit was lost would look like a slot that "
            + "never held anything - a compaction or a split rewrites the file into one "
            + "where it would not";
    }

    /**
     * Phrases what a damaged segment is damaged by.
     *
     * @param report What the walk found
     * @return A detail naming the scale of the damage and a sample of it
     */
    private static String damageDetail(SegmentIntegrityReport report) {
        String sample = report.damaged().stream()
            .limit(DAMAGE_SAMPLE)
            .map(entry -> "slot " + entry.slotIndex() + " at data offset "
                + entry.dataRegionOffset() + " (" + entry.detail() + ")")
            .collect(Collectors.joining("; "));
        String more = report.damagedCount() > DAMAGE_SAMPLE
            ? " and " + (report.damagedCount() - DAMAGE_SAMPLE) + " more" : "";
        return scale(report) + ": " + sample + more;
    }

    /**
     * Says how much is wrong, and how much of it is the slot table rather than the records.
     *
     * @param report What the walk found
     * @return The opening clause of the damage detail
     */
    private static String scale(SegmentIntegrityReport report) {
        int slotFaults = report.slotFaultCount();
        int entryFaults = report.damagedCount() - slotFaults;
        if (slotFaults == 0) {
            return entryFaults + " of " + report.entriesScanned() + " entries are damaged";
        }
        String slots = slotFaults + " of " + report.entriesScanned()
            + " occupied slots are damaged, so the entries they name cannot be reached "
            + "although their own bytes may be sound";
        return entryFaults == 0 ? slots : slots + ", and " + entryFaults + " entries are "
            + "damaged in themselves";
    }

    /**
     * Builds an unreadable verdict.
     *
     * @param file     The segment file
     * @param relative Its path relative to the data directory
     * @param size     The file's size, or -1 if unknown
     * @param detail   What stopped the read
     * @return The verdict
     */
    private static FileCheck unreadable(Path file, String relative, long size, String detail) {
        return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNREADABLE, detail, size);
    }
}
