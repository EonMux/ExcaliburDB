package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentFormat;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a full walk of a segment found.
 *
 * <p>Produced by {@link Segment#verifyIntegrity()}. Unlike a read, which raises
 * {@link CorruptSegmentEntryException} at the first damaged entry, a verification runs to
 * the end and reports everything: an operator deciding whether to restore a segment needs
 * to know whether one entry is damaged or ten thousand, and a repair tool needs the list of
 * offsets rather than the first of them.</p>
 *
 * <p>Two separate things are weighed for each occupied slot, and they are counted
 * separately because they fail for different reasons and call for different actions. The
 * slot is the pointer, and {@code slotsVerified} plus {@code slotsUnverifiable} plus the
 * slot faults in {@code damaged} account for every occupied slot. The entry is what the
 * pointer reaches, and {@code entriesVerified + entriesUnverifiable + damaged.size() ==
 * entriesScanned} - an entry is not examined at all when the slot naming it is the thing
 * that is wrong, because there is then no reason to believe the record found there is the
 * record that slot was meant to name.</p>
 *
 * <p>Unverifiable, in either count, means sound as far as anything can tell: the file was
 * written in a format that carries no checksum for that structure, so its bytes have
 * nothing to be compared against. That is not the same statement as sound.</p>
 *
 * @param path                The segment file that was walked
 * @param format              The format the file is written in
 * @param slotsScanned        Number of slots in the slot table
 * @param entriesScanned      Number of occupied slots, tombstoned ones included
 * @param entriesVerified     Entries whose checksum matched
 * @param entriesUnverifiable Entries in a format that stores no entry checksum
 * @param slotsVerified       Occupied slots whose own checksum matched
 * @param slotsUnverifiable   Occupied slots in a format that stores no slot checksum
 * @param damaged             Every slot or entry that failed, in slot order, never null
 * @author ExcaliburDB
 * @since 2.0
 */
public record SegmentIntegrityReport(
    Path path,
    SegmentFormat format,
    int slotsScanned,
    int entriesScanned,
    int entriesVerified,
    int entriesUnverifiable,
    int slotsVerified,
    int slotsUnverifiable,
    List<DamagedEntry> damaged) {

    /**
     * Canonical constructor, defensively copying the damage list.
     *
     * @throws NullPointerException if any reference argument is null
     */
    public SegmentIntegrityReport {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
        damaged = List.copyOf(Objects.requireNonNull(damaged, "damaged"));
    }

    /**
     * Reports whether the walk found nothing wrong.
     *
     * <p>True of a file whose entries cannot be checked at all, because nothing was found
     * wrong with it. {@link #entriesUnverifiable()} and {@link #slotsUnverifiable()} are
     * what distinguish "sound" from "unexamined".</p>
     *
     * @return true if nothing was found damaged
     */
    public boolean isHealthy() {
        return damaged.isEmpty();
    }

    /**
     * Returns how many slots or entries were found damaged.
     *
     * @return The size of the damage list
     */
    public int damagedCount() {
        return damaged.size();
    }

    /**
     * Returns how many faults are damage to the slot table rather than to a record.
     *
     * @return The number of slot faults in the damage list
     */
    public int slotFaultCount() {
        return (int) damaged.stream().filter(DamagedEntry::isSlotFault).count();
    }

    /**
     * What sort of fault put an entry in the damage list.
     *
     * <p>They are told apart because an operator does different things about them. A record
     * that fails its own checksum has lost data and needs another copy. A slot that fails
     * its checksum, or that names a record whose key hash is not the slot's, has lost only
     * the pointer - the record itself may be perfectly sound, and simply unreachable, which
     * is a recoverable situation and not one to quarantine a whole file over.</p>
     */
    public enum DamageKind {

        /** The record could not be read at all: it points outside the file, or its lengths cannot be. */
        STRUCTURAL,

        /** The record's bytes disagree with the checksum stored in it. */
        ENTRY_CHECKSUM,

        /** The slot's bytes disagree with the checksum stored in it. */
        SLOT_CHECKSUM,

        /** The slot and the record it names disagree about which key it is. */
        SLOT_ENTRY_MISMATCH;

        /**
         * Reports whether this fault is damage to the slot table rather than to a record.
         *
         * @return true for the two slot faults
         */
        public boolean isSlotFault() {
            return this == SLOT_CHECKSUM || this == SLOT_ENTRY_MISMATCH;
        }
    }

    /**
     * One slot or entry that could not be trusted.
     *
     * <p>{@code expectedChecksum} and {@code actualChecksum} hold the two disagreeing values
     * for a checksum mismatch of either kind, and are both zero for a fault that has no
     * checksum to report - a structurally unreadable record, or a slot and record that
     * disagree about their key hash. {@code detail} always says what was found wrong.</p>
     *
     * @param slotIndex        Slot pointing at the record, or the damaged slot itself
     * @param dataRegionOffset Offset of the record within the data region, as the slot claims
     * @param keyHash          The record's key hash, or 0 if it could not be read
     * @param kind             What sort of fault this is
     * @param expectedChecksum The checksum stored on disk, 0 if there is none to report
     * @param actualChecksum   The checksum of the bytes present, 0 if there is none to report
     * @param detail           What was found wrong
     */
    public record DamagedEntry(
        int slotIndex,
        long dataRegionOffset,
        long keyHash,
        DamageKind kind,
        int expectedChecksum,
        int actualChecksum,
        String detail) {

        /**
         * Canonical constructor.
         *
         * @throws NullPointerException if kind or detail is null
         */
        public DamagedEntry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        /**
         * Reports whether the record is malformed rather than merely mismatched.
         *
         * @return true if the record's own structure is wrong
         */
        public boolean structural() {
            return kind == DamageKind.STRUCTURAL;
        }

        /**
         * Reports whether the slot table is what is damaged, rather than a record.
         *
         * @return true for a slot checksum failure or a slot/entry disagreement
         */
        public boolean isSlotFault() {
            return kind.isSlotFault();
        }
    }
}
