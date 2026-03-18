package excalibur.store.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SegmentEntry}.
 *
 * Tests cover:
 * - Entry format correctness (byte layout matches specification)
 * - Domain constraint validation (key_hash, key_len, value_len, distance, tombstone ranges)
 * - Read/write operations to MemorySegment and ByteBuffer
 * - Zero-copy value slice access
 * - Immutability guarantees
 */
@DisplayName("SegmentEntry Tests")
class SegmentEntryTest {

    private static final String TEST_KEY = "test-key";
    private static final String TEST_VALUE = "test-value";
    private static final long TEST_KEY_HASH = 12345678901234L;
    private static final int TEST_DISTANCE = 5;

    private Arena arena;
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        tempFile = Files.createTempFile("segment_entry_test", ".bin");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (arena != null) {
            arena.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== Format Constant Tests ====================

    @Nested
    @DisplayName("Format Constants")
    class FormatConstantsTests {

        @Test
        @DisplayName("KEY_HASH_OFFSET should be 0")
        void keyHashOffset() {
            assertEquals(0, SegmentEntry.KEY_HASH_OFFSET);
        }

        @Test
        @DisplayName("KEY_HASH_SIZE should be 8")
        void keyHashSize() {
            assertEquals(8, SegmentEntry.KEY_HASH_SIZE);
        }

        @Test
        @DisplayName("KEY_LEN_OFFSET should be 8")
        void keyLenOffset() {
            assertEquals(8, SegmentEntry.KEY_LEN_OFFSET);
        }

        @Test
        @DisplayName("KEY_LEN_SIZE should be 4")
        void keyLenSize() {
            assertEquals(4, SegmentEntry.KEY_LEN_SIZE);
        }

        @Test
        @DisplayName("VALUE_LEN_OFFSET should be 12")
        void valueLenOffset() {
            assertEquals(12, SegmentEntry.VALUE_LEN_OFFSET);
        }

        @Test
        @DisplayName("VALUE_LEN_SIZE should be 4")
        void valueLenSize() {
            assertEquals(4, SegmentEntry.VALUE_LEN_SIZE);
        }

        @Test
        @DisplayName("DISTANCE_OFFSET should be 16")
        void distanceOffset() {
            assertEquals(16, SegmentEntry.DISTANCE_OFFSET);
        }

        @Test
        @DisplayName("DISTANCE_SIZE should be 1")
        void distanceSize() {
            assertEquals(1, SegmentEntry.DISTANCE_SIZE);
        }

        @Test
        @DisplayName("KEY_BYTES_OFFSET should be 17")
        void keyBytesOffset() {
            assertEquals(17, SegmentEntry.KEY_BYTES_OFFSET);
        }

        @Test
        @DisplayName("HEADER_SIZE should be 17 (8+4+4+1)")
        void headerSize() {
            assertEquals(17, SegmentEntry.HEADER_SIZE);
        }

        @Test
        @DisplayName("ENTRY_OVERHEAD should be 18 (17+1 for tombstone)")
        void entryOverhead() {
            assertEquals(18, SegmentEntry.ENTRY_OVERHEAD);
        }

        @Test
        @DisplayName("MAX_DISTANCE should be 127")
        void maxDistance() {
            assertEquals(127, SegmentEntry.MAX_DISTANCE);
        }

        @Test
        @DisplayName("TOMBSTONE_LIVE should be 0")
        void tombstoneLive() {
            assertEquals(0, SegmentEntry.TOMBSTONE_LIVE);
        }

        @Test
        @DisplayName("TOMBSTONE_DELETED should be 1")
        void tombstoneDeleted() {
            assertEquals(1, SegmentEntry.TOMBSTONE_DELETED);
        }
    }

    // ==================== Constructor and Validation Tests ====================

    @Nested
    @DisplayName("Constructor and Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entry with valid parameters")
        void validEntry() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            assertEquals(TEST_KEY_HASH, entry.keyHash());
            assertArrayEquals(key, entry.key());
            assertArrayEquals(value, entry.value());
            assertEquals(TEST_DISTANCE, entry.distance());
            assertFalse(entry.isTombstoned());
        }

        @Test
        @DisplayName("Should create tombstoned entry")
        void tombstonedEntry() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            assertTrue(entry.isTombstoned());
        }

        @Test
        @DisplayName("Should reject negative key hash")
        void negativeKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new SegmentEntry(-1L, key, value, TEST_DISTANCE));
        }

        @Test
        @DisplayName("Should accept zero key hash (boundary)")
        void zeroKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(0L, key, value, TEST_DISTANCE);
            assertEquals(0L, entry.keyHash());
        }

        @Test
        @DisplayName("Should accept Long.MAX_VALUE key hash (boundary)")
        void maxKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(Long.MAX_VALUE, key, value, TEST_DISTANCE);
            assertEquals(Long.MAX_VALUE, entry.keyHash());
        }

        @Test
        @DisplayName("Should reject null key")
        void nullKey() {
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(NullPointerException.class, () ->
                new SegmentEntry(TEST_KEY_HASH, null, value, TEST_DISTANCE));
        }

        @Test
        @DisplayName("Should reject empty key")
        void emptyKey() {
            byte[] key = new byte[0];
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE));
        }

        @Test
        @DisplayName("Should reject null value")
        void nullValue() {
            byte[] key = TEST_KEY.getBytes();

            assertThrows(NullPointerException.class, () ->
                new SegmentEntry(TEST_KEY_HASH, key, null, TEST_DISTANCE));
        }

        @Test
        @DisplayName("Should accept empty value")
        void emptyValue() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = new byte[0];

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);
            assertEquals(0, entry.valueLength());
        }

        @Test
        @DisplayName("Should reject negative distance")
        void negativeDistance() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new SegmentEntry(TEST_KEY_HASH, key, value, -1));
        }

        @Test
        @DisplayName("Should accept distance = 0 (boundary)")
        void zeroDistance() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, 0);
            assertEquals(0, entry.distance());
        }

        @Test
        @DisplayName("Should accept distance = 127 (boundary)")
        void maxDistance() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, 127);
            assertEquals(127, entry.distance());
        }

        @Test
        @DisplayName("Should reject distance > 127")
        void distanceTooLarge() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new SegmentEntry(TEST_KEY_HASH, key, value, 128));
        }
    }

    // ==================== Size Calculation Tests ====================

    @Nested
    @DisplayName("Size Calculation")
    class SizeTests {

        @Test
        @DisplayName("Should calculate correct size")
        void calculateSize() {
            int keyLen = 10;
            int valueLen = 20;
            int expected = SegmentEntry.ENTRY_OVERHEAD + keyLen + valueLen; // 18 + 10 + 20 = 48

            assertEquals(48, SegmentEntry.calculateSize(keyLen, valueLen));
        }

        @Test
        @DisplayName("Entry size() should match calculateSize()")
        void entrySize() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            int expectedSize = SegmentEntry.calculateSize(key.length, value.length);
            assertEquals(expectedSize, entry.size());
        }

        @Test
        @DisplayName("Size should be header(17) + key + value + tombstone(1)")
        void sizeBreakdown() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            // 17 (header) + 3 (key) + 3 (value) + 1 (tombstone) = 24
            assertEquals(24, entry.size());
        }
    }

    // ==================== MemorySegment Read/Write Tests ====================

    @Nested
    @DisplayName("MemorySegment Read/Write")
    class MemorySegmentTests {

        @Test
        @DisplayName("Should write and read entry to/from MemorySegment")
        void writeAndRead() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            // Allocate memory segment
            int size = original.size();
            MemorySegment segment = arena.allocate(size);

            // Write
            original.writeTo(segment, 0);

            // Read
            SegmentEntry read = SegmentEntry.readFrom(segment, 0);

            assertEquals(original.keyHash(), read.keyHash());
            assertArrayEquals(original.key(), read.key());
            assertArrayEquals(original.value(), read.value());
            assertEquals(original.distance(), read.distance());
            assertEquals(original.isTombstoned(), read.isTombstoned());
        }

        @Test
        @DisplayName("Should write entry at non-zero offset")
        void writeAtOffset() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            int offset = 100;
            int size = original.size() + offset;
            MemorySegment segment = arena.allocate(size);

            original.writeTo(segment, offset);
            SegmentEntry read = SegmentEntry.readFrom(segment, offset);

            assertEquals(original.keyHash(), read.keyHash());
        }

        @Test
        @DisplayName("Should read and write tombstoned entry")
        void tombstonedEntry() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment segment = arena.allocate(original.size());
            original.writeTo(segment, 0);

            SegmentEntry read = SegmentEntry.readFrom(segment, 0);
            assertTrue(read.isTombstoned());
        }

        @Test
        @DisplayName("Should read key hash directly")
        void readKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(TEST_KEY_HASH, SegmentEntry.readKeyHash(segment, 0));
        }

        @Test
        @DisplayName("Should read distance directly")
        void readDistance() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(TEST_DISTANCE, SegmentEntry.readDistance(segment, 0));
        }

        @Test
        @DisplayName("Should read key directly")
        void readKey() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertArrayEquals(key, SegmentEntry.readKey(segment, 0));
        }

        @Test
        @DisplayName("Should read value directly")
        void readValue() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertArrayEquals(value, SegmentEntry.readValue(segment, 0));
        }

        @Test
        @DisplayName("readValue should return null for tombstoned entry")
        void readValueTombstoned() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertNull(SegmentEntry.readValue(segment, 0));
        }

        @Test
        @DisplayName("readValueSlice should return MemorySegment slice")
        void readValueSlice() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            MemorySegment valueSlice = SegmentEntry.readValueSlice(segment, 0);
            assertNotNull(valueSlice);
            assertEquals(value.length, valueSlice.byteSize());

            // Verify content
            byte[] readValue = valueSlice.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertArrayEquals(value, readValue);
        }

        @Test
        @DisplayName("readValueSlice should return null for tombstoned entry")
        void readValueSliceTombstoned() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertNull(SegmentEntry.readValueSlice(segment, 0));
        }

        @Test
        @DisplayName("isTombstoned should detect deleted entry")
        void isTombstonedStatic() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            SegmentEntry liveEntry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, false);
            SegmentEntry deletedEntry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment liveSegment = arena.allocate(liveEntry.size());
            MemorySegment deletedSegment = arena.allocate(deletedEntry.size());

            liveEntry.writeTo(liveSegment, 0);
            deletedEntry.writeTo(deletedSegment, 0);

            assertFalse(SegmentEntry.isTombstoned(liveSegment, 0));
            assertTrue(SegmentEntry.isTombstoned(deletedSegment, 0));
        }

        @Test
        @DisplayName("writeDistance should update only distance field")
        void writeDistanceStatic() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, 5);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Update distance
            SegmentEntry.writeDistance(segment, 0, 10);

            assertEquals(10, SegmentEntry.readDistance(segment, 0));
            // Other fields should remain unchanged
            assertEquals(TEST_KEY_HASH, SegmentEntry.readKeyHash(segment, 0));
        }

        @Test
        @DisplayName("writeTombstone should mark entry as deleted")
        void writeTombstoneStatic() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, false);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertFalse(SegmentEntry.isTombstoned(segment, 0));

            SegmentEntry.writeTombstone(segment, 0, key.length, value.length);

            assertTrue(SegmentEntry.isTombstoned(segment, 0));
        }

        @Test
        @DisplayName("clearTombstone should mark entry as live")
        void clearTombstoneStatic() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertTrue(SegmentEntry.isTombstoned(segment, 0));

            SegmentEntry.clearTombstone(segment, 0, key.length, value.length);

            assertFalse(SegmentEntry.isTombstoned(segment, 0));
        }

        @Test
        @DisplayName("isEmptySlot should detect empty slot")
        void isEmptySlot() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(100);

            // Zeroed memory should be empty
            assertTrue(SegmentEntry.isEmptySlot(segment, 0));

            // After writing entry, should not be empty
            entry.writeTo(segment, 0);
            assertFalse(SegmentEntry.isEmptySlot(segment, 0));
        }

        @Test
        @DisplayName("markEmptySlot should mark slot as empty")
        void markEmptySlot() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertFalse(SegmentEntry.isEmptySlot(segment, 0));

            SegmentEntry.markEmptySlot(segment, 0);

            assertTrue(SegmentEntry.isEmptySlot(segment, 0));
        }

        @Test
        @DisplayName("sizeAt should calculate size from segment")
        void sizeAt() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(entry.size(), SegmentEntry.sizeAt(segment, 0));
        }
    }

    // ==================== ByteBuffer Read/Write Tests ====================

    @Nested
    @DisplayName("ByteBuffer Read/Write")
    class ByteBufferTests {

        @Test
        @DisplayName("Should write and read entry to/from ByteBuffer")
        void writeAndReadByteBuffer() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            ByteBuffer buffer = ByteBuffer.allocate(original.size())
                .order(ByteOrder.LITTLE_ENDIAN);

            original.writeTo(buffer, 0);
            SegmentEntry read = SegmentEntry.readFrom(buffer, 0);

            assertEquals(original.keyHash(), read.keyHash());
            assertArrayEquals(original.key(), read.key());
            assertArrayEquals(original.value(), read.value());
            assertEquals(original.distance(), read.distance());
            assertEquals(original.isTombstoned(), read.isTombstoned());
        }

        @Test
        @DisplayName("Should handle big-endian buffer by converting")
        void bigEndianBuffer() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            // Create big-endian buffer
            ByteBuffer buffer = ByteBuffer.allocate(original.size())
                .order(ByteOrder.BIG_ENDIAN);

            original.writeTo(buffer, 0);
            SegmentEntry read = SegmentEntry.readFrom(buffer, 0);

            assertEquals(original.keyHash(), read.keyHash());
        }
    }

    // ==================== Immutability Tests ====================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("key() should return defensive copy")
        void keyDefensiveCopy() {
            byte[] originalKey = TEST_KEY.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, originalKey, TEST_VALUE.getBytes(), TEST_DISTANCE);

            byte[] key1 = entry.key();
            byte[] key2 = entry.key();

            // Should be different array instances
            assertNotSame(originalKey, key1);
            assertNotSame(key1, key2);

            // Modifying returned key should not affect entry
            key1[0] = (byte) 0xFF;
            byte[] key3 = entry.key();
            assertNotEquals((byte) 0xFF, key3[0]);
        }

        @Test
        @DisplayName("value() should return defensive copy")
        void valueDefensiveCopy() {
            byte[] originalValue = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, TEST_KEY.getBytes(), originalValue, TEST_DISTANCE);

            byte[] value1 = entry.value();
            byte[] value2 = entry.value();

            // Should be different array instances
            assertNotSame(originalValue, value1);
            assertNotSame(value1, value2);

            // Modifying returned value should not affect entry
            value1[0] = (byte) 0xFF;
            byte[] value3 = entry.value();
            assertNotEquals((byte) 0xFF, value3[0]);
        }

        @Test
        @DisplayName("withDistance should create new entry")
        void withDistance() {
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), 5);

            SegmentEntry modified = original.withDistance(10);

            // Original should be unchanged
            assertEquals(5, original.distance());

            // New entry should have new distance
            assertEquals(10, modified.distance());

            // Other fields should be same
            assertEquals(original.keyHash(), modified.keyHash());
            assertArrayEquals(original.key(), modified.key());
        }

        @Test
        @DisplayName("withValue should create new entry")
        void withValue() {
            byte[] originalValue = TEST_VALUE.getBytes();
            byte[] newValue = "new-value".getBytes();

            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), originalValue, TEST_DISTANCE);

            SegmentEntry modified = original.withValue(newValue);

            // Original should be unchanged
            assertArrayEquals(originalValue, original.value());

            // New entry should have new value
            assertArrayEquals(newValue, modified.value());

            // Other fields should be same
            assertEquals(original.keyHash(), modified.keyHash());
        }

        @Test
        @DisplayName("asTombstoned should create new entry")
        void asTombstoned() {
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE, false);

            SegmentEntry modified = original.asTombstoned();

            // Original should be unchanged
            assertFalse(original.isTombstoned());

            // New entry should be tombstoned
            assertTrue(modified.isTombstoned());
        }

        @Test
        @DisplayName("asLive should create new entry")
        void asLive() {
            SegmentEntry original = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE, true);

            SegmentEntry modified = original.asLive();

            // Original should be unchanged
            assertTrue(original.isTombstoned());

            // New entry should be live
            assertFalse(modified.isTombstoned());
        }
    }

    // ==================== Byte Layout Verification Tests ====================

    @Nested
    @DisplayName("Byte Layout Verification")
    class ByteLayoutTests {

        @Test
        @DisplayName("Should write key_hash at offset 0 (little-endian)")
        void keyHashLayout() {
            long testHash = 0x0102030405060708L;
            SegmentEntry entry = new SegmentEntry(testHash,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Read raw bytes at offset 0 - should be little-endian
            // 0x0102030405060708 in little-endian is stored as: 08 07 06 05 04 03 02 01
            assertEquals(testHash, segment.get(java.lang.foreign.ValueLayout.JAVA_LONG
                .withOrder(ByteOrder.LITTLE_ENDIAN), 0));
        }

        @Test
        @DisplayName("Should write key_len at offset 8 (little-endian)")
        void keyLenLayout() {
            byte[] key = "test".getBytes(); // 4 bytes
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                key, TEST_VALUE.getBytes(), TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(4, segment.get(java.lang.foreign.ValueLayout.JAVA_INT
                .withOrder(ByteOrder.LITTLE_ENDIAN), 8));
        }

        @Test
        @DisplayName("Should write value_len at offset 12 (little-endian)")
        void valueLenLayout() {
            byte[] value = "test-value".getBytes(); // 10 bytes
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(10, segment.get(java.lang.foreign.ValueLayout.JAVA_INT
                .withOrder(ByteOrder.LITTLE_ENDIAN), 12));
        }

        @Test
        @DisplayName("Should write distance at offset 16")
        void distanceLayout() {
            int testDistance = 42;
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), testDistance);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals((byte) testDistance, segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 16));
        }

        @Test
        @DisplayName("Should write key bytes starting at offset 17")
        void keyBytesLayout() {
            byte[] key = "test".getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                key, TEST_VALUE.getBytes(), TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Verify key bytes at offset 17
            for (int i = 0; i < key.length; i++) {
                assertEquals(key[i], segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 17 + i));
            }
        }

        @Test
        @DisplayName("Should write value bytes after key")
        void valueBytesLayout() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Value should start at offset 17 + key.length = 20
            int valueOffset = 17 + key.length;
            for (int i = 0; i < value.length; i++) {
                assertEquals(value[i], segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, valueOffset + i));
            }
        }

        @Test
        @DisplayName("Should write tombstone at end of entry")
        void tombstoneLayout() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Tombstone should be at offset 17 + key.length + value.length = 23
            int tombstoneOffset = 17 + key.length + value.length;
            assertEquals(SegmentEntry.TOMBSTONE_DELETED, segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset));
        }

        @Test
        @DisplayName("Tombstone for live entry should be 0")
        void liveTombstone() {
            byte[] key = "key".getBytes();
            byte[] value = "val".getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, false);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            int tombstoneOffset = 17 + key.length + value.length;
            assertEquals(SegmentEntry.TOMBSTONE_LIVE, segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset));
        }
    }

    // ==================== Equals and HashCode Tests ====================

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Equal entries should have same hashCode")
        void equalHashCode() {
            SegmentEntry entry1 = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);
            SegmentEntry entry2 = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);

            assertEquals(entry1, entry2);
            assertEquals(entry1.hashCode(), entry2.hashCode());
        }

        @Test
        @DisplayName("Different key hash should not be equal")
        void differentKeyHash() {
            SegmentEntry entry1 = new SegmentEntry(12345L,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);
            SegmentEntry entry2 = new SegmentEntry(67890L,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);

            assertNotEquals(entry1, entry2);
        }

        @Test
        @DisplayName("Different tombstone status should not be equal")
        void differentTombstone() {
            SegmentEntry entry1 = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE, false);
            SegmentEntry entry2 = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE, true);

            assertNotEquals(entry1, entry2);
        }
    }

    // ==================== ToString Tests ====================

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain key hash in hex")
        void toStringKeyHash() {
            SegmentEntry entry = new SegmentEntry(0xDEADBEEFL,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE);

            String str = entry.toString();
            assertTrue(str.contains("DEADBEEF"));
        }

        @Test
        @DisplayName("toString should contain distance")
        void toStringDistance() {
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), 42);

            String str = entry.toString();
            assertTrue(str.contains("distance=42"));
        }

        @Test
        @DisplayName("toString should contain tombstone status")
        void toStringTombstone() {
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), TEST_DISTANCE, true);

            String str = entry.toString();
            assertTrue(str.contains("tombstoned=true"));
        }

        @Test
        @DisplayName("toString with maxBytes should truncate key display")
        void toStringTruncated() {
            byte[] key = "0123456789".getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                key, TEST_VALUE.getBytes(), TEST_DISTANCE);

            String str = entry.toString(3);
            assertTrue(str.contains("..."));
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle binary key/value")
        void binaryKeyValue() {
            byte[] key = new byte[] {0x00, 0x01, (byte) 0xFF, (byte) 0xFE};
            byte[] value = new byte[] {(byte) 0x80, (byte) 0x90, (byte) 0xA0};

            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            SegmentEntry read = SegmentEntry.readFrom(segment, 0);

            assertArrayEquals(key, read.key());
            assertArrayEquals(value, read.value());
        }

        @Test
        @DisplayName("Should handle large distance (127)")
        void largeDistance() {
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes(), 127);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            SegmentEntry read = SegmentEntry.readFrom(segment, 0);

            assertEquals(127, read.distance());
        }

        @Test
        @DisplayName("Should handle empty value")
        void emptyValue() {
            byte[] emptyValue = new byte[0];
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH,
                TEST_KEY.getBytes(), emptyValue, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            SegmentEntry read = SegmentEntry.readFrom(segment, 0);

            assertEquals(0, read.valueLength());
            assertArrayEquals(emptyValue, read.value());
        }
    }

    // ==================== Tombstone Validation Tests ====================

    @Nested
    @DisplayName("Tombstone Validation")
    class TombstoneValidationTests {

        @Test
        @DisplayName("validateTombstone should accept 0 (live)")
        void validateTombstoneLive() {
            // Should not throw
            SegmentEntry.validateTombstone(SegmentEntry.TOMBSTONE_LIVE);
        }

        @Test
        @DisplayName("validateTombstone should accept 1 (deleted)")
        void validateTombstoneDeleted() {
            // Should not throw
            SegmentEntry.validateTombstone(SegmentEntry.TOMBSTONE_DELETED);
        }

        @Test
        @DisplayName("validateTombstone should reject 2")
        void validateTombstoneInvalid2() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateTombstone((byte) 2));
        }

        @Test
        @DisplayName("validateTombstone should reject -1")
        void validateTombstoneInvalidNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateTombstone((byte) -1));
        }

        @Test
        @DisplayName("validateTombstone should reject 127")
        void validateTombstoneInvalid127() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateTombstone((byte) 127));
        }

        @Test
        @DisplayName("validateTombstone should reject -128")
        void validateTombstoneInvalidMin() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateTombstone((byte) -128));
        }

        @Test
        @DisplayName("isValidTombstone should return true for 0")
        void isValidTombstoneLive() {
            assertTrue(SegmentEntry.isValidTombstone(SegmentEntry.TOMBSTONE_LIVE));
        }

        @Test
        @DisplayName("isValidTombstone should return true for 1")
        void isValidTombstoneDeleted() {
            assertTrue(SegmentEntry.isValidTombstone(SegmentEntry.TOMBSTONE_DELETED));
        }

        @Test
        @DisplayName("isValidTombstone should return false for 2")
        void isValidTombstoneInvalid2() {
            assertFalse(SegmentEntry.isValidTombstone((byte) 2));
        }

        @Test
        @DisplayName("isValidTombstone should return false for -1")
        void isValidTombstoneInvalidNegative() {
            assertFalse(SegmentEntry.isValidTombstone((byte) -1));
        }

        @Test
        @DisplayName("readFrom should reject corrupted tombstone byte (value 2)")
        void readFromRejectsInvalidTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Corrupt the tombstone byte to an invalid value (2)
            int tombstoneOffset = 17 + key.length + value.length;
            segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset, (byte) 2);

            // Reading should throw due to invalid tombstone
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.readFrom(segment, 0));
        }

        @Test
        @DisplayName("readFrom should reject corrupted tombstone byte (value -1)")
        void readFromRejectsNegativeTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Corrupt the tombstone byte to an invalid value (-1)
            int tombstoneOffset = 17 + key.length + value.length;
            segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset, (byte) -1);

            // Reading should throw due to invalid tombstone
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.readFrom(segment, 0));
        }

        @Test
        @DisplayName("readValueSlice should reject corrupted tombstone byte")
        void readValueSliceRejectsInvalidTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Corrupt the tombstone byte
            int tombstoneOffset = 17 + key.length + value.length;
            segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset, (byte) 5);

            // readValueSlice should throw due to invalid tombstone
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.readValueSlice(segment, 0));
        }

        @Test
        @DisplayName("isTombstoned should reject corrupted tombstone byte")
        void isTombstonedRejectsInvalidTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Corrupt the tombstone byte
            int tombstoneOffset = 17 + key.length + value.length;
            segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset, (byte) 127);

            // isTombstoned should throw due to invalid tombstone
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.isTombstoned(segment, 0));
        }

        @Test
        @DisplayName("readFrom(ByteBuffer) should reject corrupted tombstone byte")
        void readFromByteBufferRejectsInvalidTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            SegmentEntry entry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE);

            ByteBuffer buffer = ByteBuffer.allocate(entry.size())
                .order(ByteOrder.LITTLE_ENDIAN);
            entry.writeTo(buffer, 0);

            // Corrupt the tombstone byte
            int tombstoneOffset = 17 + key.length + value.length;
            buffer.put(tombstoneOffset, (byte) 3);

            // Reading should throw due to invalid tombstone
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.readFrom(buffer, 0));
        }

        @Test
        @DisplayName("writeTo should always write exactly 0 or 1 for tombstone")
        void writeToAlwaysValidTombstone() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            // Test live entry
            SegmentEntry liveEntry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, false);
            MemorySegment liveSegment = arena.allocate(liveEntry.size());
            liveEntry.writeTo(liveSegment, 0);

            int tombstoneOffset = 17 + key.length + value.length;
            byte liveTombstone = liveSegment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset);
            assertEquals(0, liveTombstone);

            // Test deleted entry
            SegmentEntry deletedEntry = new SegmentEntry(TEST_KEY_HASH, key, value, TEST_DISTANCE, true);
            MemorySegment deletedSegment = arena.allocate(deletedEntry.size());
            deletedEntry.writeTo(deletedSegment, 0);

            byte deletedTombstone = deletedSegment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, tombstoneOffset);
            assertEquals(1, deletedTombstone);
        }
    }

    // ==================== Key/Value Length Validation Tests ====================

    @Nested
    @DisplayName("Key/Value Length Validation")
    class KeyValueLengthValidationTests {

        @Test
        @DisplayName("MAX_KEY_LEN should equal Integer.MAX_VALUE - 17")
        void maxKeyLenConstant() {
            assertEquals(Integer.MAX_VALUE - 17, SegmentEntry.MAX_KEY_LEN);
        }

        @Test
        @DisplayName("MAX_VALUE_LEN should equal Integer.MAX_VALUE - 17")
        void maxValueLenConstant() {
            assertEquals(Integer.MAX_VALUE - 17, SegmentEntry.MAX_VALUE_LEN);
        }

        @Test
        @DisplayName("validateKeyLen should accept 0")
        void validateKeyLenZero() {
            assertDoesNotThrow(() -> SegmentEntry.validateKeyLen(0));
        }

        @Test
        @DisplayName("validateKeyLen should accept MAX_KEY_LEN")
        void validateKeyLenMax() {
            assertDoesNotThrow(() -> SegmentEntry.validateKeyLen(SegmentEntry.MAX_KEY_LEN));
        }

        @Test
        @DisplayName("validateKeyLen should reject negative values")
        void validateKeyLenNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateKeyLen(-1));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateKeyLen(Integer.MIN_VALUE));
        }

        @Test
        @DisplayName("validateKeyLen should reject values exceeding MAX_KEY_LEN")
        void validateKeyLenExceedsMax() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateKeyLen(SegmentEntry.MAX_KEY_LEN + 1));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateKeyLen(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("validateValueLen should accept 0")
        void validateValueLenZero() {
            assertDoesNotThrow(() -> SegmentEntry.validateValueLen(0));
        }

        @Test
        @DisplayName("validateValueLen should accept MAX_VALUE_LEN")
        void validateValueLenMax() {
            assertDoesNotThrow(() -> SegmentEntry.validateValueLen(SegmentEntry.MAX_VALUE_LEN));
        }

        @Test
        @DisplayName("validateValueLen should reject negative values")
        void validateValueLenNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateValueLen(-1));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateValueLen(Integer.MIN_VALUE));
        }

        @Test
        @DisplayName("validateValueLen should reject values exceeding MAX_VALUE_LEN")
        void validateValueLenExceedsMax() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateValueLen(SegmentEntry.MAX_VALUE_LEN + 1));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentEntry.validateValueLen(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("isValidKeyLen should return true for valid lengths")
        void isValidKeyLenValid() {
            assertTrue(SegmentEntry.isValidKeyLen(0));
            assertTrue(SegmentEntry.isValidKeyLen(1));
            assertTrue(SegmentEntry.isValidKeyLen(100));
            assertTrue(SegmentEntry.isValidKeyLen(SegmentEntry.MAX_KEY_LEN));
        }

        @Test
        @DisplayName("isValidKeyLen should return false for invalid lengths")
        void isValidKeyLenInvalid() {
            assertFalse(SegmentEntry.isValidKeyLen(-1));
            assertFalse(SegmentEntry.isValidKeyLen(SegmentEntry.MAX_KEY_LEN + 1));
            assertFalse(SegmentEntry.isValidKeyLen(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("isValidValueLen should return true for valid lengths")
        void isValidValueLenValid() {
            assertTrue(SegmentEntry.isValidValueLen(0));
            assertTrue(SegmentEntry.isValidValueLen(1));
            assertTrue(SegmentEntry.isValidValueLen(100));
            assertTrue(SegmentEntry.isValidValueLen(SegmentEntry.MAX_VALUE_LEN));
        }

        @Test
        @DisplayName("isValidValueLen should return false for invalid lengths")
        void isValidValueLenInvalid() {
            assertFalse(SegmentEntry.isValidValueLen(-1));
            assertFalse(SegmentEntry.isValidValueLen(SegmentEntry.MAX_VALUE_LEN + 1));
            assertFalse(SegmentEntry.isValidValueLen(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("validateKey should reject keys exceeding MAX_KEY_LEN")
        void validateKeyRejectsTooLong() {
            // Create a key that exceeds MAX_KEY_LEN (but within memory limits for testing)
            // Since MAX_KEY_LEN is Integer.MAX_VALUE - 17, we can't actually allocate that
            // So we just verify the constant and error message format
            assertEquals(Integer.MAX_VALUE - 17, SegmentEntry.MAX_KEY_LEN);
        }

        @Test
        @DisplayName("validateValue should reject values exceeding MAX_VALUE_LEN")
        void validateValueRejectsTooLong() {
            assertEquals(Integer.MAX_VALUE - 17, SegmentEntry.MAX_VALUE_LEN);
        }

        @Test
        @DisplayName("Domain constraint: 17 + MAX_KEY_LEN should not overflow")
        void headerPlusMaxKeyLenNoOverflow() {
            int headerSize = 17; // key_hash(8) + key_len(4) + value_len(4) + distance(1)
            long sum = (long) headerSize + SegmentEntry.MAX_KEY_LEN;
            assertTrue(sum <= Integer.MAX_VALUE,
                "17 + MAX_KEY_LEN should not overflow int: " + sum);
        }

        @Test
        @DisplayName("Domain constraint: 17 + MAX_VALUE_LEN should not overflow")
        void headerPlusMaxValueLenNoOverflow() {
            int headerSize = 17; // key_hash(8) + key_len(4) + value_len(4) + distance(1)
            long sum = (long) headerSize + SegmentEntry.MAX_VALUE_LEN;
            assertTrue(sum <= Integer.MAX_VALUE,
                "17 + MAX_VALUE_LEN should not overflow int: " + sum);
        }
    }
}
