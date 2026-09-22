package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests that a segment detects damage to the entries it hands back, tells damage apart from
 * absence, surveys a whole file on demand, and keeps reading files written before it did any
 * of that.
 */
@DisplayName("Segment entry checksum tests")
class SegmentChecksumTest {

    @TempDir
    Path tempDir;

    private static final byte[] KEY = "checksum-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE =
        "VALUE-PAYLOAD-0123456789-ABCDEFGH".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ABSENT_KEY = "never-written".getBytes(StandardCharsets.UTF_8);

    /** Slot count a raw legacy file is hand-built with. */
    private static final int LEGACY_SLOT_COUNT = Segment.MIN_SEGMENT_CAPACITY;

    /** Data region size the segment constructor always adds. */
    private static final long MIN_DATA_SIZE = 1024L * 1024L;

    /** Entry header width of the format that predates checksums. */
    private static final int LEGACY_ENTRY_HEADER_SIZE = 17;

    private static final long CAPACITY = 4L * 1024 * 1024;

    // ==================== Detection on the read path ====================

    @Nested
    @DisplayName("Detection on the read path")
    class ReadPathDetection {

        @Test
        @DisplayName("get must not hand back a value whose bytes on disk were altered")
        void getRefusesCorruptedValue() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            flipByteInside(file, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> segment.get(KEY));
                assertAll(
                    () -> assertFalse(failure.isStructural(),
                        "a flipped payload byte is a mismatch, not a malformed record"),
                    () -> assertEquals(file, failure.segmentPath()),
                    () -> assertTrue(failure.expectedChecksum() != failure.actualChecksum(),
                        "the two checksums must differ for the read to have been refused"));
            }
        }

        @Test
        @DisplayName("a damaged entry is reported, an absent one is still absent")
        void damageIsNotFlattenedIntoAbsence() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            flipByteInside(file, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertThrows(CorruptSegmentEntryException.class, () -> segment.get(KEY),
                    "a key whose bytes are damaged must not read as missing");
                assertNull(segment.get(ABSENT_KEY),
                    "a key that was never written must still read as missing");
            }
        }

        @Test
        @DisplayName("damage to the key bytes is reported rather than read as absence")
        void corruptedKeyIsNotMistakenForAbsence() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            flipByteInside(file, KEY);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertThrows(CorruptSegmentEntryException.class, () -> segment.get(KEY),
                    "the slot hash still matches, so the entry is damaged rather than absent");
            }
        }

        @Test
        @DisplayName("a length that cannot be is reported as structural damage")
        void impossibleLengthIsReportedAsStructural() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            overwriteValueLength(file, Integer.MAX_VALUE - 8);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                CorruptSegmentEntryException failure = assertThrows(
                    CorruptSegmentEntryException.class, () -> segment.get(KEY));
                assertTrue(failure.isStructural(),
                    "a record running past the end of the file cannot be read at all");
            }
        }

        @Test
        @DisplayName("an intact entry reads back unchanged")
        void intactEntryReadsBack() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                MemorySegment slice = segment.get(KEY);
                assertNotNull(slice);
                assertArrayEquals(VALUE, slice.toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }

    // ==================== Whole-segment verification ====================

    @Nested
    @DisplayName("Whole-segment verification")
    class Verification {

        @Test
        @DisplayName("a sound segment verifies clean and counts every entry")
        void soundSegmentVerifiesClean() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 64; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }

                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(SegmentFormat.V3, report.format()),
                    () -> assertEquals(64, report.entriesScanned()),
                    () -> assertEquals(64, report.entriesVerified()),
                    () -> assertEquals(0, report.entriesUnverifiable()),
                    () -> assertEquals(segment.slotCount(), report.slotsScanned()));
            }
        }

        @Test
        @DisplayName("verification names every damaged entry rather than stopping at the first")
        void verificationNamesEveryDamagedEntry() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 8; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }

            flipByteInside(file, valueOf(2));
            flipByteInside(file, valueOf(5));

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                List<SegmentIntegrityReport.DamagedEntry> damaged = report.damaged();
                assertAll(
                    () -> assertFalse(report.isHealthy()),
                    () -> assertEquals(2, report.damagedCount(), () -> damaged.toString()),
                    () -> assertEquals(8, report.entriesScanned()),
                    () -> assertEquals(6, report.entriesVerified()),
                    () -> assertTrue(damaged.stream().noneMatch(
                            SegmentIntegrityReport.DamagedEntry::structural),
                        "flipped payload bytes are mismatches, not malformed records"),
                    () -> assertTrue(damaged.stream().allMatch(
                            d -> d.expectedChecksum() != d.actualChecksum()),
                        "each damaged entry must carry the two disagreeing checksums"));
            }
        }

        @Test
        @DisplayName("verification reports a record that runs past the end of the file")
        void verificationReportsStructuralDamage() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeOneEntry(file, KEY, VALUE);
            overwriteValueLength(file, Integer.MAX_VALUE - 8);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(1, report.damagedCount()),
                    () -> assertTrue(report.damaged().get(0).structural()),
                    () -> assertEquals(0, report.entriesVerified()));
            }
        }

        @Test
        @DisplayName("deleting an entry leaves the rest of the segment verifiable")
        void deleteDoesNotDisturbChecksums() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                for (int i = 0; i < 16; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                assertTrue(segment.delete(keyOf(3)));
                assertTrue(segment.delete(keyOf(11)));

                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertTrue(report.isHealthy(), () -> report.damaged().toString());
                assertEquals(16, report.entriesScanned(),
                    "a tombstoned slot stays occupied and is still checked");
            }
        }

        @Test
        @DisplayName("Robin Hood displacement leaves every entry verifiable")
        void displacementDoesNotDisturbChecksums() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                // Enough entries that probe chains form and records are displaced, which
                // rewrites the distance byte of records nothing else touched.
                for (int i = 0; i < 4000; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertTrue(report.isHealthy(), () -> report.damaged().toString());
                assertEquals(4000, report.entriesVerified());
            }
        }

        @Test
        @DisplayName("overwriting a value refreshes its checksum")
        void overwriteRefreshesChecksum() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                segment.put(KEY, VALUE);
                byte[] shorter = "tiny".getBytes(StandardCharsets.UTF_8);
                segment.put(KEY, shorter);
                assertArrayEquals(shorter, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));

                byte[] longer = new byte[VALUE.length * 4];
                java.util.Arrays.fill(longer, (byte) 'z');
                segment.put(KEY, longer);
                assertArrayEquals(longer, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));

                assertTrue(segment.verifyIntegrity().isHealthy());
            }
        }
    }

    // ==================== Entries copied by maintenance ====================

    @Nested
    @DisplayName("Entries copied by maintenance")
    class CopyPaths {

        @Test
        @DisplayName("a compaction copy produces a segment that verifies")
        void compactionCopyProducesVerifiableSegment() throws IOException {
            Path source = tempDir.resolve("segment_0.dat");
            Path rebuilt = tempDir.resolve("segment_0.dat.compact");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(source, CAPACITY, arena);
                 Segment replacement = new Segment(rebuilt, CAPACITY, arena)) {
                for (int i = 0; i < 128; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                assertTrue(segment.delete(keyOf(7)));

                int copiedCount = 0;
                try (Segment.EntryIterator entries = segment.liveEntries()) {
                    while (entries.hasNext()) {
                        var entry = entries.next();
                        replacement.putWithoutLock(entry.key(), entry.value());
                        copiedCount++;
                    }
                }
                final int copied = copiedCount;

                SegmentIntegrityReport report = replacement.verifyIntegrity();
                assertAll(
                    () -> assertEquals(127, copied),
                    () -> assertTrue(report.isHealthy(), () -> report.damaged().toString()),
                    () -> assertEquals(127, report.entriesVerified()));
            }
        }

        @Test
        @DisplayName("a compaction copy refuses to carry a damaged entry forward")
        void compactionCopyRefusesDamagedEntry() throws IOException {
            Path source = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(source, CAPACITY, arena)) {
                for (int i = 0; i < 8; i++) {
                    segment.put(keyOf(i), valueOf(i));
                }
                segment.flush();
            }
            flipByteInside(source, valueOf(4));

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(source, arena)) {
                assertThrows(CorruptSegmentEntryException.class, () -> {
                    try (Segment.EntryIterator entries = segment.liveEntries()) {
                        while (entries.hasNext()) {
                            entries.next();
                        }
                    }
                }, "a rewrite must not silently drop or silently copy a damaged entry");
            }
        }

        @Test
        @DisplayName("split children built from the snapshot verify")
        void splitChildrenVerify() throws IOException {
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
                    () -> assertEquals(256, left.verifyIntegrity().entriesVerified()
                        + right.verifyIntegrity().entriesVerified()));
            }
        }
    }

    // ==================== Files written before checksums existed ====================

    @Nested
    @DisplayName("Files written before checksums existed")
    class BackwardCompatibility {

        @Test
        @DisplayName("a file created now is the current format, across a reopen")
        void createdFilesAreCurrentFormat() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                assertEquals(SegmentFormat.V3, segment.format());
                segment.put(KEY, VALUE);
                segment.flush();
            }
            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertEquals(SegmentFormat.V3, segment.format());
                assertArrayEquals(VALUE, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));
            }
        }

        @Test
        @DisplayName("a legacy file is read as legacy, not mis-parsed as checksummed")
        void legacyFileIsReadAsLegacy() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeLegacySegment(file, KEY, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertAll(
                    () -> assertEquals(SegmentFormat.V1, segment.format()),
                    () -> assertArrayEquals(VALUE,
                        segment.get(KEY).toArray(ValueLayout.JAVA_BYTE)),
                    () -> assertNull(segment.get(ABSENT_KEY)));
            }
        }

        @Test
        @DisplayName("a legacy file's entries are reported unverifiable, not sound")
        void legacyEntriesAreReportedUnverifiable() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeLegacySegment(file, KEY, VALUE);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                SegmentIntegrityReport report = segment.verifyIntegrity();
                assertAll(
                    () -> assertEquals(SegmentFormat.V1, report.format()),
                    () -> assertEquals(1, report.entriesScanned()),
                    () -> assertEquals(0, report.entriesVerified()),
                    () -> assertEquals(1, report.entriesUnverifiable()),
                    () -> assertTrue(report.isHealthy(),
                        "nothing was found wrong, which is not the same as sound"));
            }
        }

        @Test
        @DisplayName("writes to a legacy file stay in the legacy format and read back")
        void legacyFileKeepsItsFormatOnWrite() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            writeLegacySegment(file, KEY, VALUE);

            byte[] second = "second-key".getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                segment.put(second, VALUE);
                assertAll(
                    () -> assertEquals(SegmentFormat.V1, segment.format()),
                    () -> assertArrayEquals(VALUE,
                        segment.get(KEY).toArray(ValueLayout.JAVA_BYTE)),
                    () -> assertArrayEquals(VALUE,
                        segment.get(second).toArray(ValueLayout.JAVA_BYTE)));
            }
        }

        @Test
        @DisplayName("a legacy file that holds nothing is upgraded on open")
        void emptyLegacyFileIsUpgraded() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            Files.write(file, new byte[(int) (legacySlotTableSize() + MIN_DATA_SIZE)]);

            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertEquals(SegmentFormat.V3, segment.format(),
                    "there is no record in the data region to mis-describe");
                segment.put(KEY, VALUE);
                assertArrayEquals(VALUE, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));
                assertTrue(segment.verifyIntegrity().isHealthy());
                assertEquals(1, segment.verifyIntegrity().entriesVerified());
            }
        }

        @Test
        @DisplayName("a segment with no entries keeps its header across a reopen")
        void emptySegmentKeepsItsHeaderAcrossReopen() throws IOException {
            Path file = tempDir.resolve("segment_0.dat");
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(file, CAPACITY, arena)) {
                segment.flush();
            }
            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                segment.put(KEY, VALUE);
                assertEquals(SegmentFormat.V3, segment.format());
                assertTrue(segment.verifyIntegrity().isHealthy());
            }
            try (Arena arena = Arena.ofShared();
                 Segment segment = Segment.openExisting(file, arena)) {
                assertEquals(SegmentFormat.V3, segment.format(),
                    "the first write must not have landed on top of the header");
                assertArrayEquals(VALUE, segment.get(KEY).toArray(ValueLayout.JAVA_BYTE));
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
        return ("generated-key-" + n).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the value of the nth generated entry.
     *
     * @param n The entry number
     * @return The value bytes
     */
    private static byte[] valueOf(int n) {
        return ("generated-value-payload-" + n + "-padding").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the slot table size of a hand-built legacy file.
     *
     * @return The size in bytes
     */
    private static long legacySlotTableSize() {
        return (long) LEGACY_SLOT_COUNT * SegmentLayout.SLOT_SIZE;
    }

    /**
     * Flips one bit inside the stored copy of a byte run in a segment file.
     *
     * @param file  The segment file to damage
     * @param marker The bytes to find and damage
     * @throws IOException if the file cannot be read or written
     */
    private static void flipByteInside(Path file, byte[] marker) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int at = indexOf(bytes, marker);
        if (at < 0) {
            throw new IllegalStateException("Marker bytes not found in " + file);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(at + 4);
            byte original = raf.readByte();
            raf.seek(at + 4);
            raf.writeByte(original ^ 0x20);
        }
    }

    /**
     * Rewrites the value length of the entry holding {@link #VALUE}.
     *
     * @param file   The segment file to damage
     * @param length The length to claim
     * @throws IOException if the file cannot be read or written
     */
    private static void overwriteValueLength(Path file, int length) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int at = indexOf(bytes, KEY);
        if (at < 0) {
            throw new IllegalStateException("Key bytes not found in " + file);
        }
        // The key bytes sit at the end of the record header, so the two length fields are a
        // fixed distance behind them.
        long valueLenOffset = at - (SegmentFormat.V3.keyBytesOffset()
            - SegmentLayout.ENTRY_VALUE_LEN_OFFSET);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(valueLenOffset);
            raf.write(littleEndian(length));
        }
    }

    /**
     * Builds a segment file in the format that predates checksums.
     *
     * <p>Hand-written rather than produced by an older build: the point is to prove that the
     * current reader recognises a file it did not write and does not mis-parse it.</p>
     *
     * @param file  The file to create
     * @param key   The single key the file holds
     * @param value That key's value
     * @throws IOException if the file cannot be written
     */
    private static void writeLegacySegment(Path file, byte[] key, byte[] value)
            throws IOException {
        long slotTableSize = legacySlotTableSize();
        byte[] image = new byte[(int) (slotTableSize + MIN_DATA_SIZE)];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);

        long keyHash = hashOf(key);
        int slot = (int) (keyHash & (LEGACY_SLOT_COUNT - 1));
        int slotOffset = slot * SegmentLayout.SLOT_SIZE;
        buffer.putLong(slotOffset + SegmentLayout.SLOT_KEY_HASH_OFFSET, keyHash);
        buffer.put(slotOffset + SegmentLayout.SLOT_DISTANCE_OFFSET, (byte) 0);
        buffer.put(slotOffset + SegmentLayout.SLOT_FLAGS_OFFSET, SegmentLayout.FLAG_OCCUPIED);
        for (int i = 0; i < 6; i++) {
            buffer.put(slotOffset + SegmentLayout.SLOT_DATA_OFFSET_OFFSET + i, (byte) 0);
        }

        int entryOffset = (int) slotTableSize;
        buffer.putLong(entryOffset + SegmentLayout.ENTRY_KEY_HASH_OFFSET, keyHash);
        buffer.putInt(entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET, key.length);
        buffer.putInt(entryOffset + SegmentLayout.ENTRY_VALUE_LEN_OFFSET, value.length);
        buffer.put(entryOffset + SegmentLayout.ENTRY_DISTANCE_OFFSET, (byte) 0);
        System.arraycopy(key, 0, image, entryOffset + LEGACY_ENTRY_HEADER_SIZE, key.length);
        System.arraycopy(value, 0, image,
            entryOffset + LEGACY_ENTRY_HEADER_SIZE + key.length, value.length);

        Files.write(file, image);
        assertNotEqualToHeaderMagic(image, (int) slotTableSize);
    }

    /**
     * Asserts that a hand-built legacy file does not accidentally look checksummed.
     *
     * @param image          The file image
     * @param dataRegionBase Offset at which the data region begins
     */
    private static void assertNotEqualToHeaderMagic(byte[] image, int dataRegionBase) {
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(dataRegionBase) == SegmentDataHeader.MAGIC) {
            fail("hand-built legacy image collides with the data region header magic");
        }
    }

    /**
     * Computes the key hash a segment would store for a key.
     *
     * @param key The key
     * @return The normalized hash
     */
    private static long hashOf(byte[] key) {
        return com.eonmux.excalibur.store.SegmentConfig.normalizeKeyHash(
            com.eonmux.excalibur.store.hash.XxHash64.hash(key));
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

    /**
     * Returns the first index at which {@code needle} occurs in {@code haystack}.
     *
     * @param haystack Bytes to search
     * @param needle   Bytes to find
     * @return The index, or -1 if absent
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
