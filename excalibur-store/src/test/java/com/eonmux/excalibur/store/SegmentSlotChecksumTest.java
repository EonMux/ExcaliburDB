package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;
import com.eonmux.excalibur.store.format.SlotChecksum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that damage to the slot table is reported rather than turned into absence.
 *
 * <p>An entry record carries its own checksum, but a record is only ever reached through the
 * slot that points at it. A flipped bit in a slot's key hash, its data offset or its occupancy
 * flag therefore makes a sound, checksummed, still-present entry read as simply missing - which
 * is the one answer a damaged store must never give.</p>
 */
@DisplayName("Segment slot checksum tests")
class SegmentSlotChecksumTest {

    @TempDir
    Path tempDir;

    private static final byte[] KEY = "slot-checksum-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE =
        "SLOT-VALUE-PAYLOAD-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ABSENT_KEY = "never-written-at-all".getBytes(StandardCharsets.UTF_8);

    /** Slot count every segment in this test is built with. */
    private static final int SLOTS = Segment.MIN_SEGMENT_CAPACITY;

    private static final long CAPACITY = Segment.capacityForSlots(SLOTS);

    /** Data region size the segment constructor always adds. */
    private static final long MIN_DATA_SIZE = 1024L * 1024L;

    /** Entry header width of the format that predates entry checksums. */
    private static final int V1_ENTRY_HEADER_SIZE = 17;

    /** Entry header width of the format that checksums entries but not slots. */
    private static final int V2_ENTRY_HEADER_SIZE = 21;

    // ==================== Detection on the read path ====================

    @Nested
    @DisplayName("Detection on the read path")
    class ReadPathDetection {

        @Test
        @DisplayName("a flipped bit in a slot's key hash is reported, not read as absence")
        void flippedSlotKeyHashIsReportedRatherThanReadAsAbsent() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            flipSlotByte(file, slotOf(file, KEY), SegmentLayout.SLOT_KEY_HASH_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> segment.get(KEY),
                    "the entry is intact and unreachable: that is damage, not absence");
                assertAll(
                    () -> assertTrue(failure.isSlotFault(),
                        "the slot table is what is damaged, not the record"),
                    () -> assertEquals(file, failure.segmentPath()),
                    () -> assertNotEquals(failure.expectedChecksum(), failure.actualChecksum()));
            }
        }

        @Test
        @DisplayName("a flipped bit in a slot's data offset is reported")
        void flippedSlotDataOffsetIsReported() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            flipSlotByte(file, slotOf(file, KEY), SegmentLayout.SLOT_DATA_OFFSET_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> segment.get(KEY),
                    "a slot pointing somewhere else must not hand back what it lands on");
                assertTrue(failure.isSlotFault());
            }
        }

        @Test
        @DisplayName("a cleared occupancy flag is reported, not read as absence")
        void clearedOccupancyFlagIsReportedRatherThanReadAsAbsent() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            clearSlotOccupancy(file, slotOf(file, KEY));

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> segment.get(KEY),
                    "an entry made invisible by a flipped flag has not been deleted");
                assertTrue(failure.isSlotFault());
            }
        }

        @Test
        @DisplayName("a key that was never written still reads as absent")
        void anAbsentKeyIsStillAbsent() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertNull(segment.get(ABSENT_KEY),
                    "verifying the probe window must not turn a miss into a failure");
                assertArrayEquals(VALUE, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));
            }
        }

        @Test
        @DisplayName("an occupancy flag set on an empty slot is reported by verification")
        void occupancyFlagSetOnAnEmptySlotIsReported() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            int victim = (slotOf(file, KEY) + 7) % SLOTS;
            setSlotOccupancy(file, victim);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertFalse(report.isHealthy(), "a slot that was never written "
                        + "cannot legitimately become occupied"),
                    () -> assertTrue(report.damaged().stream()
                        .anyMatch(d -> d.slotIndex() == victim)));
            }
        }
    }

    // ==================== The distance byte ====================

    @Nested
    @DisplayName("The distance byte, which displacement rewrites")
    class DistanceByte {

        @Test
        @DisplayName("rewriting a slot's distance byte does not change its checksum")
        void distanceIsOutsideTheSlotChecksum() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = arena.allocate(SegmentLayout.SLOT_SIZE);
                table.set(SegmentLayout.LONG_LAYOUT, SegmentLayout.SLOT_KEY_HASH_OFFSET,
                    0x0123456789ABCDEFL);
                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_FLAGS_OFFSET,
                    SegmentLayout.FLAG_OCCUPIED);
                for (int i = 0; i < 6; i++) {
                    table.set(SegmentLayout.BYTE_LAYOUT,
                        SegmentLayout.SLOT_DATA_OFFSET_OFFSET + i, (byte) (i + 1));
                }

                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_DISTANCE_OFFSET, (byte) 0);
                int atZero = SlotChecksum.compute(table, 0);
                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_DISTANCE_OFFSET, (byte) 37);
                int atThirtySeven = SlotChecksum.compute(table, 0);

                assertEquals(atZero, atThirtySeven, "Robin Hood displacement rewrites the "
                    + "distance of a slot it does not otherwise touch; a checksum covering "
                    + "it would go stale on a write to a different key");
            }
        }

        @Test
        @DisplayName("every other slot field is inside the checksum")
        void everyOtherFieldIsInsideTheSlotChecksum() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = arena.allocate(SegmentLayout.SLOT_SIZE);
                table.set(SegmentLayout.LONG_LAYOUT, SegmentLayout.SLOT_KEY_HASH_OFFSET, 99L);
                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_FLAGS_OFFSET,
                    SegmentLayout.FLAG_OCCUPIED);
                int base = SlotChecksum.compute(table, 0);

                table.set(SegmentLayout.LONG_LAYOUT, SegmentLayout.SLOT_KEY_HASH_OFFSET, 100L);
                int hashChanged = SlotChecksum.compute(table, 0);
                table.set(SegmentLayout.LONG_LAYOUT, SegmentLayout.SLOT_KEY_HASH_OFFSET, 99L);

                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_FLAGS_OFFSET,
                    (byte) (SegmentLayout.FLAG_OCCUPIED | SegmentLayout.FLAG_TOMBSTONE));
                int flagsChanged = SlotChecksum.compute(table, 0);
                table.set(SegmentLayout.BYTE_LAYOUT, SegmentLayout.SLOT_FLAGS_OFFSET,
                    SegmentLayout.FLAG_OCCUPIED);

                table.set(SegmentLayout.BYTE_LAYOUT,
                    SegmentLayout.SLOT_DATA_OFFSET_OFFSET + 3, (byte) 1);
                int offsetChanged = SlotChecksum.compute(table, 0);

                assertAll(
                    () -> assertNotEquals(base, hashChanged),
                    () -> assertNotEquals(base, flagsChanged),
                    () -> assertNotEquals(base, offsetChanged));
            }
        }

        @Test
        @DisplayName("real Robin Hood displacement leaves every slot verifying")
        void displacementLeavesEverySlotVerifying() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            int entries = (int) (SLOTS * 0.6);
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < entries; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(entries, report.slotsVerified()));
                segment.flush();
            }

            assertTrue(maxSlotDistance(file) > 0,
                "the fixture must actually displace slots, or it pins nothing");

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                for (int i = 0; i < entries; i++) {
                    assertArrayEquals(valueOf(i),
                        segment.get(keyOf(i)).toArray(ValueLayout.JAVA_BYTE));
                }
            }
        }

        @Test
        @DisplayName("a delete and a resurrection leave every slot verifying")
        void tombstoningLeavesEverySlotVerifying() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 64; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                assertTrue(segment.delete(keyOf(5)));
                segment.put(keyOf(9), valueOf(900));
                segment.put(keyOf(5), valueOf(500));
                assertTrue(segment.verifyIntegrity().isHealthy(),
                    () -> segment.verifyIntegrity().damaged().toString());
            }
        }
    }

    // ==================== Entries copied by maintenance ====================

    @Nested
    @DisplayName("Entries copied by maintenance")
    class CopyPaths {

        @Test
        @DisplayName("a compaction copy refuses to walk through a damaged slot")
        void compactionCopyRefusesDamagedSlot() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 16; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }
            flipSlotByte(file, slotOf(file, keyOf(4)), SegmentLayout.SLOT_KEY_HASH_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertThrows(CorruptSegmentEntryException.class, () -> {
                    try (Segment.EntryIterator entries = segment.liveEntries()) {
                        while (entries.hasNext()) {
                            entries.next();
                        }
                    }
                }, "a rewrite copies what the slot table reaches, so it must not accept a "
                    + "slot table it cannot vouch for");
            }
        }

        @Test
        @DisplayName("a split refuses to read through a damaged slot")
        void splitRefusesDamagedSlot() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 16; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }
            flipSlotByte(file, slotOf(file, keyOf(4)), SegmentLayout.SLOT_DATA_OFFSET_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                Segment.SegmentSplitSnapshot snapshot = segment.beginSplit();
                assertThrows(CorruptSegmentEntryException.class,
                    () -> snapshot.entries(false));
            }
        }

        @Test
        @DisplayName("split children's slots all verify")
        void splitChildrenSlotsVerify() throws IOException {
            Path parentPath = tempDir.resolve("segment_0.dat");
            Path leftPath = tempDir.resolve("segment_0.left");
            Path rightPath = tempDir.resolve("segment_0.right");
            try (Arena arena = Arena.ofShared();
                 Segment parent = new Segment(parentPath, CAPACITY, arena);
                 Segment left = new Segment(leftPath, CAPACITY, arena);
                 Segment right = new Segment(rightPath, CAPACITY, arena)) {
                for (int i = 0; i < 256; i++) {
                    parent.put(keyOf(i), valueOf(i));
                }

                Segment.SegmentSplitSnapshot snapshot = parent.beginSplit();
                int splitBit = 10;
                for (var entry : snapshot.entries(false)) {
                    if (Segment.shouldGoToRightChild(entry.keyHash(), splitBit)) {
                        right.putWithoutLock(entry.key(), entry.value());
                    } else {
                        left.putWithoutLock(entry.key(), entry.value());
                    }
                }
                parent.completeSplit(left, right, splitBit);

                assertAll(
                    () -> assertTrue(left.verifyIntegrity().isHealthy()),
                    () -> assertTrue(right.verifyIntegrity().isHealthy()),
                    () -> assertEquals(256, left.verifyIntegrity().slotsVerified()
                        + right.verifyIntegrity().slotsVerified()));
            }
        }
    }

    // ==================== The slot against the record it names ====================

    @Nested
    @DisplayName("The slot against the record it names")
    class SlotEntryAgreement {

        @Test
        @DisplayName("a flipped slot distance byte is reported, though no checksum covers it")
        void flippedSlotDistanceIsReported() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            int slot = slotOf(file, KEY);
            flipSlotByte(file, slot, SegmentLayout.SLOT_DISTANCE_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(1, report.slotsVerified(),
                        "the distance byte is outside the slot checksum, so the slot "
                            + "itself still verifies - only the record can find this"),
                    () -> assertFalse(report.isHealthy()),
                    () -> assertEquals(1, report.slotFaultCount()),
                    () -> assertTrue(report.damaged().get(0).detail().contains("distance")));
            }
        }

        @Test
        @DisplayName("a flipped slot distance byte stops a compaction copy")
        void flippedSlotDistanceStopsACompactionCopy() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 16; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }
            flipSlotByte(file, slotOf(file, keyOf(4)), SegmentLayout.SLOT_DISTANCE_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> {
                        try (Segment.EntryIterator entries = segment.liveEntries()) {
                            while (entries.hasNext()) {
                                entries.next();
                            }
                        }
                    }, "a wrong distance cuts a probe chain short, so some key reads as "
                        + "absent - and a rewrite would make that permanent");
                assertTrue(failure.isSlotFault());
            }
        }

        @Test
        @DisplayName("a rewrite of a legacy file refuses a slot its record disagrees with")
        void aRewriteOfALegacyFileRefusesADamagedSlot() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeV2Segment(file, KEY, VALUE);
            flipSlotByte(file, slotOf(file, KEY), SegmentLayout.SLOT_KEY_HASH_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertThrows(CorruptSegmentEntryException.class, () -> {
                    try (Segment.EntryIterator entries = segment.liveEntries()) {
                        while (entries.hasNext()) {
                            entries.next();
                        }
                    }
                }, "a file with no slot checksum still has two copies of the key hash, and "
                    + "the rewrite is the one place where losing an entry is permanent");
            }
        }

        @Test
        @DisplayName("replaying a write-ahead log leaves slot and record agreeing")
        void writeAheadLogReplayKeepsThemAgreeing() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            Path log = tempDir.resolve("segment_0.wal");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena);
                 WriteLog writeLog = new WriteLog(log)) {
                segment.attachWriteLog(writeLog);
                for (int i = 0; i < 400; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.delete(keyOf(17));
            }

            try (Arena arena = Arena.ofShared();
                 Segment replayed = new Segment(file, CAPACITY, arena);
                 WriteLog writeLog = new WriteLog(log)) {
                replayed.attachWriteLog(writeLog);
                replayed.recoverFromLog();

                SegmentIntegrityReport report = replayed.verifyIntegrity();
                assertAll(
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(400, report.slotsVerified()),
                    () -> assertNull(replayed.get(keyOf(17))),
                    () -> assertArrayEquals(valueOf(399),
                        replayed.get(keyOf(399)).toArray(ValueLayout.JAVA_BYTE)));
            }
        }

        @Test
        @DisplayName("an insert refused for capacity leaves slot and record agreeing")
        void arefusedInsertKeepsThemAgreeing() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                segment.flush();
            }

            int accepted = 0;
            boolean refused = false;
            try (Arena arena = Arena.ofShared();
                 // A threshold this high lets the probe chain, not the load factor, be
                 // what refuses the insert - which is the path that unwinds displacements.
                 Segment segment = Segment.openExisting(file, arena, 0.999)) {
                for (int i = 0; i < SLOTS; i++) {
                    try {
                        segment.put(keyOf(i), valueOf(i));
                        accepted++;
                    } catch (SegmentCapacityExceededException e) {
                        refused = true;
                        break;
                    }
                }

                SegmentIntegrityReport report = segment.verifyIntegrity();
                final int placed = accepted;
                assertAll(
                    () -> assertTrue(refusedInsertHappened(placed)),
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(placed, report.slotsVerified()));
                for (int i = 0; i < accepted; i++) {
                    assertArrayEquals(valueOf(i),
                        segment.get(keyOf(i)).toArray(ValueLayout.JAVA_BYTE),
                        "a rolled-back insert must not have disturbed an entry already there");
                }
            }
            assertTrue(refused, "the fixture must actually reach a refusal, or it pins nothing");
        }

        /**
         * Reports whether enough entries were placed for displacement chains to have formed.
         *
         * @param accepted How many inserts succeeded
         * @return true if the run is a meaningful one
         */
        private boolean refusedInsertHappened(int accepted) {
            return accepted > SLOTS / 2;
        }
    }

    // ==================== Format versioning ====================

    @Nested
    @DisplayName("Format versioning")
    class Versioning {

        @Test
        @DisplayName("a file created now is the slot-checksummed format, across a reopen")
        void createdFilesAreSlotChecksummed() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                assertEquals(SegmentFormat.V3, segment.format());
                for (int i = 0; i < 32; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }
            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(SegmentFormat.V3, segment.format()),
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(32, report.slotsVerified()),
                    () -> assertEquals(0, report.slotsUnverifiable()),
                    () -> assertEquals(32, report.entriesVerified()));
            }
        }

        @Test
        @DisplayName("a legacy v1 file is left byte-for-byte alone and its slots unverifiable")
        void legacyV1FileIsUntouched() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeV1Segment(file, KEY, VALUE);
            byte[] before = Files.readAllBytes(file);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(SegmentFormat.V1, segment.format()),
                    () -> assertEquals(0, report.slotsVerified()),
                    () -> assertEquals(1, report.slotsUnverifiable()),
                    () -> assertTrue(report.isHealthy()),
                    () -> assertArrayEquals(VALUE,
                        segment.get(KEY).toArray(ValueLayout.JAVA_BYTE)));
            }
            assertArrayEquals(before, Files.readAllBytes(file),
                "a legacy file is never upgraded in place");
        }

        @Test
        @DisplayName("a legacy v2 file is left byte-for-byte alone and its slots unverifiable")
        void legacyV2FileIsUntouched() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeV2Segment(file, KEY, VALUE);
            byte[] before = Files.readAllBytes(file);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(SegmentFormat.V2, segment.format()),
                    () -> assertEquals(1, report.entriesVerified(),
                        "a v2 file's entries are checksummed"),
                    () -> assertEquals(0, report.slotsVerified()),
                    () -> assertEquals(1, report.slotsUnverifiable(),
                        "a v2 file's slots carry nothing to check"),
                    () -> assertTrue(report.isHealthy()),
                    () -> assertArrayEquals(VALUE,
                        segment.get(KEY).toArray(ValueLayout.JAVA_BYTE)));
            }
            assertArrayEquals(before, Files.readAllBytes(file),
                "a legacy file is never upgraded in place");
        }

        @Test
        @DisplayName("a flipped slot hash in a legacy v2 file still reads as absence")
        void legacyV2SlotDamageIsStillUndetectable() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeV2Segment(file, KEY, VALUE);
            flipSlotByte(file, slotOf(file, KEY), SegmentLayout.SLOT_KEY_HASH_OFFSET);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertNull(segment.get(KEY),
                    "a file with no slot checksums has nothing to detect this with; "
                        + "a split or a compaction is what gives it one");
            }
        }

        @Test
        @DisplayName("rewriting a legacy file produces one whose slots verify")
        void aRewriteUpgradesTheFile() throws IOException {
            Path source = tempDir.resolve("segment_0.dat");
            Path rebuilt = tempDir.resolve("segment_1.dat");
            writeV2Segment(source, KEY, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(source, arena);
                 Segment replacement = new Segment(rebuilt, CAPACITY, arena)) {
                try (Segment.EntryIterator entries = segment.liveEntries()) {
                    while (entries.hasNext()) {
                        var entry = entries.next();
                        replacement.putWithoutLock(entry.key(), entry.value());
                    }
                }
                SegmentIntegrityReport report = replacement.verifyIntegrity();
                assertAll(
                    () -> assertEquals(SegmentFormat.V3, replacement.format()),
                    () -> assertEquals(1, report.slotsVerified()),
                    () -> assertTrue(report.isHealthy()));
            }
        }
    }

    // ==================== Helpers ====================

    /**
     * Writes a single entry into a fresh segment file and closes it.
     *
     * @param file  The file to create
     * @param key   The key to store
     * @param value The value to store
     * @throws IOException if the file cannot be written
     */
    private static void writeOneEntry(Path file, byte[] key, byte[] value) throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(file, CAPACITY, arena)) {
            segment.put(key, value);
            segment.flush();
        }
    }

    /**
     * Returns the key of the nth generated entry.
     *
     * @param n The entry number
     * @return The key bytes
     */
    private static byte[] keyOf(int n) {
        return ("slot-key-" + n).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the value of the nth generated entry.
     *
     * @param n The entry number
     * @return The value bytes
     */
    private static byte[] valueOf(int n) {
        return ("slot-value-payload-" + n).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Finds the slot a key occupies by walking the file's probe chain by hand.
     *
     * @param file The segment file
     * @param key  The key
     * @return The slot index
     * @throws IOException if the file cannot be read
     */
    private static int slotOf(Path file, byte[] key) throws IOException {
        byte[] image = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int slotCount = Segment.deriveSlotCountForRecovery(image.length);
        long keyHash = hashOf(key);
        int ideal = (int) (keyHash & (slotCount - 1));
        for (int distance = 0; distance < Segment.MAX_PROBE_LENGTH; distance++) {
            int slot = (ideal + distance) & (slotCount - 1);
            int at = slot * SegmentLayout.SLOT_SIZE;
            if ((buffer.get(at + SegmentLayout.SLOT_FLAGS_OFFSET)
                    & SegmentLayout.FLAG_OCCUPIED) == 0) {
                break;
            }
            if (buffer.getLong(at + SegmentLayout.SLOT_KEY_HASH_OFFSET) == keyHash) {
                return slot;
            }
        }
        throw new IllegalStateException("No slot in " + file + " holds the key");
    }

    /**
     * Returns the largest probe distance recorded in a file's slot table.
     *
     * @param file The segment file
     * @return The maximum distance byte over occupied slots
     * @throws IOException if the file cannot be read
     */
    private static int maxSlotDistance(Path file) throws IOException {
        byte[] image = Files.readAllBytes(file);
        int slotCount = Segment.deriveSlotCountForRecovery(image.length);
        int max = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            int at = slot * SegmentLayout.SLOT_SIZE;
            if ((image[at + SegmentLayout.SLOT_FLAGS_OFFSET]
                    & SegmentLayout.FLAG_OCCUPIED) == 0) {
                continue;
            }
            max = Math.max(max, image[at + SegmentLayout.SLOT_DISTANCE_OFFSET] & 0xFF);
        }
        return max;
    }

    /**
     * Flips one bit of one byte inside a slot.
     *
     * @param file         The segment file to damage
     * @param slot         The slot index
     * @param fieldOffset  Offset of the byte within the slot
     * @throws IOException if the file cannot be written
     */
    private static void flipSlotByte(Path file, int slot, int fieldOffset) throws IOException {
        long at = (long) slot * SegmentLayout.SLOT_SIZE + fieldOffset;
        byte[] image = Files.readAllBytes(file);
        writeByteAt(file, at, (byte) (image[(int) at] ^ 0x01));
    }

    /**
     * Clears a slot's occupied bit, leaving every other byte of the slot as it was.
     *
     * @param file The segment file to damage
     * @param slot The slot index
     * @throws IOException if the file cannot be written
     */
    private static void clearSlotOccupancy(Path file, int slot) throws IOException {
        long at = (long) slot * SegmentLayout.SLOT_SIZE + SegmentLayout.SLOT_FLAGS_OFFSET;
        byte[] image = Files.readAllBytes(file);
        writeByteAt(file, at, (byte) (image[(int) at] & ~SegmentLayout.FLAG_OCCUPIED));
    }

    /**
     * Sets a slot's occupied bit, leaving every other byte of the slot as it was.
     *
     * @param file The segment file to damage
     * @param slot The slot index
     * @throws IOException if the file cannot be written
     */
    private static void setSlotOccupancy(Path file, int slot) throws IOException {
        long at = (long) slot * SegmentLayout.SLOT_SIZE + SegmentLayout.SLOT_FLAGS_OFFSET;
        byte[] image = Files.readAllBytes(file);
        writeByteAt(file, at, (byte) (image[(int) at] | SegmentLayout.FLAG_OCCUPIED));
    }

    /**
     * Overwrites a single byte of a file in place.
     *
     * @param file  The file
     * @param at    Absolute offset
     * @param value The byte to write
     * @throws IOException if the file cannot be written
     */
    private static void writeByteAt(Path file, long at, byte value) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { value }), at);
        }
    }

    /**
     * Computes the key hash a segment would store for a key.
     *
     * @param key The key
     * @return The normalized hash
     */
    private static long hashOf(byte[] key) {
        return SegmentConfig.normalizeKeyHash(com.eonmux.excalibur.store.hash.XxHash64.hash(key));
    }

    /**
     * Builds a segment file in the format that predates checksums entirely.
     *
     * @param file  The file to create
     * @param key   The single key the file holds
     * @param value That key's value
     * @throws IOException if the file cannot be written
     */
    private static void writeV1Segment(Path file, byte[] key, byte[] value) throws IOException {
        long slotTableSize = (long) SLOTS * SegmentLayout.SLOT_SIZE;
        byte[] image = new byte[(int) (slotTableSize + MIN_DATA_SIZE)];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        long keyHash = hashOf(key);
        writeSlot(buffer, (int) (keyHash & (SLOTS - 1)), keyHash);

        int entry = (int) slotTableSize;
        buffer.putLong(entry + SegmentLayout.ENTRY_KEY_HASH_OFFSET, keyHash);
        buffer.putInt(entry + SegmentLayout.ENTRY_KEY_LEN_OFFSET, key.length);
        buffer.putInt(entry + SegmentLayout.ENTRY_VALUE_LEN_OFFSET, value.length);
        buffer.put(entry + SegmentLayout.ENTRY_DISTANCE_OFFSET, (byte) 0);
        System.arraycopy(key, 0, image, entry + V1_ENTRY_HEADER_SIZE, key.length);
        System.arraycopy(value, 0, image,
            entry + V1_ENTRY_HEADER_SIZE + key.length, value.length);

        Files.write(file, image);
    }

    /**
     * Builds a segment file in the format that checksums entries but not slots.
     *
     * <p>Hand-written rather than produced by an older build: the point is that the current
     * reader recognises a file it did not write, reads it at its own version, and leaves its
     * bytes alone.</p>
     *
     * @param file  The file to create
     * @param key   The single key the file holds
     * @param value That key's value
     * @throws IOException if the file cannot be written
     */
    private static void writeV2Segment(Path file, byte[] key, byte[] value) throws IOException {
        long slotTableSize = (long) SLOTS * SegmentLayout.SLOT_SIZE;
        byte[] image = new byte[(int) (slotTableSize + MIN_DATA_SIZE)];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        long keyHash = hashOf(key);

        int base = (int) slotTableSize;
        buffer.putInt(base, SegmentDataHeader.MAGIC);
        buffer.putInt(base + 4, SegmentFormat.V2.version());
        buffer.putInt(base + 8, 0);
        buffer.putInt(base + 12, dataHeaderChecksum(SegmentFormat.V2.version()));

        int entry = base + SegmentDataHeader.SIZE;
        writeSlot(buffer, (int) (keyHash & (SLOTS - 1)), keyHash);
        setSlotDataOffset(buffer, (int) (keyHash & (SLOTS - 1)), SegmentDataHeader.SIZE);

        buffer.putLong(entry + SegmentLayout.ENTRY_KEY_HASH_OFFSET, keyHash);
        buffer.putInt(entry + SegmentLayout.ENTRY_KEY_LEN_OFFSET, key.length);
        buffer.putInt(entry + SegmentLayout.ENTRY_VALUE_LEN_OFFSET, value.length);
        buffer.put(entry + SegmentLayout.ENTRY_DISTANCE_OFFSET, (byte) 0);
        System.arraycopy(key, 0, image, entry + V2_ENTRY_HEADER_SIZE, key.length);
        System.arraycopy(value, 0, image,
            entry + V2_ENTRY_HEADER_SIZE + key.length, value.length);

        CRC32 crc = new CRC32();
        crc.update(image, entry, SegmentLayout.ENTRY_CHECKSUMMED_HEADER_PREFIX);
        crc.update(image, entry + V2_ENTRY_HEADER_SIZE, key.length + value.length);
        buffer.putInt(entry + SegmentFormat.V2.checksumOffset(), (int) crc.getValue());

        Files.write(file, image);
    }

    /**
     * Writes an occupied slot pointing at data region offset zero.
     *
     * @param buffer  The file image
     * @param slot    The slot index
     * @param keyHash The key hash to store
     */
    private static void writeSlot(ByteBuffer buffer, int slot, long keyHash) {
        int at = slot * SegmentLayout.SLOT_SIZE;
        buffer.putLong(at + SegmentLayout.SLOT_KEY_HASH_OFFSET, keyHash);
        buffer.put(at + SegmentLayout.SLOT_DISTANCE_OFFSET, (byte) 0);
        buffer.put(at + SegmentLayout.SLOT_FLAGS_OFFSET, SegmentLayout.FLAG_OCCUPIED);
    }

    /**
     * Writes a slot's six-byte data region offset.
     *
     * @param buffer The file image
     * @param slot   The slot index
     * @param offset The data region offset
     */
    private static void setSlotDataOffset(ByteBuffer buffer, int slot, long offset) {
        int at = slot * SegmentLayout.SLOT_SIZE + SegmentLayout.SLOT_DATA_OFFSET_OFFSET;
        for (int i = 0; i < 6; i++) {
            buffer.put(at + i, (byte) ((offset >>> (8 * i)) & 0xFF));
        }
    }

    /**
     * Computes the data region header's checksum the way the header itself does.
     *
     * @param version The version field's value
     * @return The CRC32 of magic, version and the zero reserved field
     */
    private static int dataHeaderChecksum(int version) {
        CRC32 crc = new CRC32();
        crc.update(littleEndian(SegmentDataHeader.MAGIC));
        crc.update(littleEndian(version));
        crc.update(littleEndian(0));
        return (int) crc.getValue();
    }

    /**
     * Renders an int as four little-endian bytes.
     *
     * @param value The value
     * @return Its little-endian encoding
     */
    private static byte[] littleEndian(int value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }
}
