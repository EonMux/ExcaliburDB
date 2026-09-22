package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.SegmentDataCheck;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.format.SegmentLayout;
import com.eonmux.excalibur.store.format.SlotChecksum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slot table against the entries it points at.
 *
 * <p>An entry record's checksum is reached through the slot that points at it, so a walk
 * that trusts the pointer verifies the record and learns nothing about the pointer. These
 * pin the two checks that close that - the slot's own checksum, and the comparison of the
 * slot's key hash with the record's, which is the only one available in a file too old to
 * carry the first - and pin that neither is something a repair tool acts on by itself.</p>
 */
class SegmentSlotCheckTest {

    @Test
    void aFlippedSlotKeyHashIsCorruptRatherThanIntact(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = firstSegmentWithAnOccupiedSlot(dataDir);
        flipFirstOccupiedSlotKeyHash(segment);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.detail())
            .contains("slot checksum mismatch")
            .contains("cannot be reached");
    }

    @Test
    void aSlotKeyHashThatDisagreesWithItsEntryIsCorrupt(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = firstSegmentWithAnOccupiedSlot(dataDir);
        // The slot's own checksum is made to agree with the damaged bytes, so only the
        // comparison against the record can find this - which is the check that a file
        // written before slot checksums existed depends on entirely.
        flipFirstOccupiedSlotKeyHash(segment);
        restampSlotChecksum(segment, firstOccupiedSlot(segment));

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.detail())
            .contains("slot and entry disagree about the key hash")
            .contains("the slot is what is damaged")
            .contains("sound and unreachable");
    }

    @Test
    void aFlippedSlotDistanceIsCorruptAndNotOfferedForRepair(@TempDir Path dir)
            throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = firstSegmentWithAnOccupiedSlot(dataDir);
        // The distance byte is outside the slot checksum by design, so the slot still
        // verifies and only the record's copy of the same byte can find this.
        flipSlotDistance(segment);
        String name = segment.getFileName().toString();

        FileCheck check = SegmentDataCheck.check(segment, name);
        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir, null).scan());

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.detail())
            .contains("disagree about the probe distance")
            .contains("reads as not found");
        assertThat(plan.actions()).noneMatch(action -> action.relative().equals(name));
        assertThat(plan.needsOperator()).anyMatch(note -> note.contains(name));
    }

    @Test
    void aHealthySegmentReportsBothChecksumsMatched(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = firstSegmentWithAnOccupiedSlot(dataDir);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.version()).isEqualTo("v3");
        assertThat(check.detail()).contains("slot checksum");
        assertThat(check.needsOperator()).isFalse();
    }

    @Test
    void aDamagedSlotIsNotOfferedForRepair(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = firstSegmentWithAnOccupiedSlot(dataDir);
        flipFirstOccupiedSlotKeyHash(segment);
        String name = segment.getFileName().toString();

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir, null).scan());

        assertThat(plan.actions())
            .describedAs("neither side of a slot/entry disagreement can be shown right")
            .noneMatch(action -> action.relative().equals(name));
        assertThat(plan.needsOperator()).anyMatch(note -> note.contains(name));
    }

    @Test
    void aDamagedRecordIsStillOfferedForRepair(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);
        String name = segment.getFileName().toString();

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir, null).scan());

        assertThat(plan.actions())
            .describedAs("a record that has lost data is the case quarantine is for")
            .anyMatch(action -> action.relative().equals(name));
    }

    /**
     * Returns the first segment file in a directory that has an occupied slot.
     *
     * @param dataDir The data directory
     * @return The segment file
     * @throws IOException if no segment has one
     */
    private static Path firstSegmentWithAnOccupiedSlot(Path dataDir) throws IOException {
        for (Path segment : DataDirectoryFixture.segmentFiles(dataDir)) {
            if (firstOccupiedSlot(segment) >= 0) {
                return segment;
            }
        }
        throw new IOException("No segment under " + dataDir + " has an occupied slot");
    }

    /**
     * Returns the index of the first occupied slot in a segment file.
     *
     * @param segment The segment file
     * @return The slot index, or -1 if the table is empty
     * @throws IOException if the file cannot be read
     */
    private static int firstOccupiedSlot(Path segment) throws IOException {
        byte[] image = Files.readAllBytes(segment);
        int slotCount = Segment.deriveSlotCountForRecovery(image.length);
        for (int slot = 0; slot < slotCount; slot++) {
            int at = slot * SegmentLayout.SLOT_SIZE;
            if ((image[at + SegmentLayout.SLOT_FLAGS_OFFSET]
                    & SegmentLayout.FLAG_OCCUPIED) != 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Flips one bit of the first occupied slot's key hash, leaving its entry intact.
     *
     * @param segment The segment file to damage
     * @throws IOException if the file cannot be written
     */
    private static void flipFirstOccupiedSlotKeyHash(Path segment) throws IOException {
        long at = (long) firstOccupiedSlot(segment) * SegmentLayout.SLOT_SIZE
            + SegmentLayout.SLOT_KEY_HASH_OFFSET;
        byte[] image = Files.readAllBytes(segment);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { (byte) (image[(int) at] ^ 0x01) }), at);
        }
    }

    /**
     * Flips one bit of the first occupied slot's probe distance byte.
     *
     * @param segment The segment file to damage
     * @throws IOException if the file cannot be written
     */
    private static void flipSlotDistance(Path segment) throws IOException {
        long at = (long) firstOccupiedSlot(segment) * SegmentLayout.SLOT_SIZE
            + SegmentLayout.SLOT_DISTANCE_OFFSET;
        byte[] image = Files.readAllBytes(segment);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { (byte) (image[(int) at] ^ 0x04) }), at);
        }
    }

    /**
     * Rewrites a slot's checksum so that it agrees with the slot's damaged bytes.
     *
     * <p>Stands in for damage that a slot checksum cannot catch - a whole slot rewritten by
     * a torn write, or a file written by a build with a bug in it - and for every slot in a
     * file whose format has no slot checksum at all.</p>
     *
     * @param segment The segment file
     * @param slot    The slot to restamp
     * @throws IOException if the file cannot be mapped
     */
    private static void restampSlotChecksum(Path segment, int slot) throws IOException {
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ,
                 StandardOpenOption.WRITE)) {
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0,
                Files.size(segment), arena);
            SlotChecksum.store(mapped, SlotChecksum.offsetOf(slot));
            mapped.force();
        }
    }
}
