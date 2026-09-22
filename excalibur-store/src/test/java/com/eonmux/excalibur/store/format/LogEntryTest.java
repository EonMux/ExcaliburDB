package com.eonmux.excalibur.store.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogEntry}.
 *
 * Tests cover:
 * - Entry format correctness (byte layout matches specification exactly)
 * - Domain constraint validation (opcode, lsn, timestamp, key_hash, key_len, value_len ranges)
 * - CRC32 computation and validation
 * - Read/write operations to MemorySegment and ByteBuffer
 * - PUT and DELETE operation handling
 */
@DisplayName("LogEntry Tests")
class LogEntryTest {

    private static final String TEST_KEY = "test-key";
    private static final String TEST_VALUE = "test-value";
    private static final long TEST_LSN = 12345L;
    /**
     * A fixed millisecond timestamp, so an entry's bytes are the same on every run.
     *
     * <p>The value is chosen so that the second byte of the encoded timestamp - offset 10 of an
     * entry, and the byte the CRC tests corrupt - is already {@code 0xFF}. That is the case a
     * corruption written as an assignment cannot express, and pinning it here means such a
     * corruption fails on every run instead of on one run in 256.</p>
     */
    private static final long TEST_TIMESTAMP = 1700000038656L;
    private static final long TEST_KEY_HASH = 12345678901234L;

    /**
     * Corrupts one byte of an entry by inverting it.
     *
     * <p>Assigning a chosen value instead corrupts nothing on the runs where the byte already
     * held it, and the CRC then validates - so the assertion that corruption is detected fails
     * on data the test did not choose. Inverting is the operation these tests actually mean:
     * whatever was there, something else is there now.</p>
     *
     * @param segment The entry's memory
     * @param offset  The byte to corrupt
     */
    private static void corruptByte(MemorySegment segment, long offset) {
        byte original = segment.get(ValueLayout.JAVA_BYTE, offset);
        segment.set(ValueLayout.JAVA_BYTE, offset, (byte) ~original);
    }

    private Arena arena;
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        tempFile = Files.createTempFile("log_entry_test", ".bin");
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
        @DisplayName("OPCODE_PUT should be 0x01")
        void opcodePut() {
            assertEquals(0x01, LogEntry.OPCODE_PUT);
        }

        @Test
        @DisplayName("OPCODE_DELETE should be 0x02")
        void opcodeDelete() {
            assertEquals(0x02, LogEntry.OPCODE_DELETE);
        }

        @Test
        @DisplayName("OPCODE_OFFSET should be 0")
        void opcodeOffset() {
            assertEquals(0, LogEntry.OPCODE_OFFSET);
        }

        @Test
        @DisplayName("OPCODE_SIZE should be 1")
        void opcodeSize() {
            assertEquals(1, LogEntry.OPCODE_SIZE);
        }

        @Test
        @DisplayName("LSN_OFFSET should be 1")
        void lsnOffset() {
            assertEquals(1, LogEntry.LSN_OFFSET);
        }

        @Test
        @DisplayName("LSN_SIZE should be 8")
        void lsnSize() {
            assertEquals(8, LogEntry.LSN_SIZE);
        }

        @Test
        @DisplayName("TIMESTAMP_OFFSET should be 9")
        void timestampOffset() {
            assertEquals(9, LogEntry.TIMESTAMP_OFFSET);
        }

        @Test
        @DisplayName("TIMESTAMP_SIZE should be 8")
        void timestampSize() {
            assertEquals(8, LogEntry.TIMESTAMP_SIZE);
        }

        @Test
        @DisplayName("KEY_HASH_OFFSET should be 17")
        void keyHashOffset() {
            assertEquals(17, LogEntry.KEY_HASH_OFFSET);
        }

        @Test
        @DisplayName("KEY_HASH_SIZE should be 8")
        void keyHashSize() {
            assertEquals(8, LogEntry.KEY_HASH_SIZE);
        }

        @Test
        @DisplayName("KEY_LEN_OFFSET should be 25")
        void keyLenOffset() {
            assertEquals(25, LogEntry.KEY_LEN_OFFSET);
        }

        @Test
        @DisplayName("KEY_LEN_SIZE should be 4")
        void keyLenSize() {
            assertEquals(4, LogEntry.KEY_LEN_SIZE);
        }

        @Test
        @DisplayName("VALUE_LEN_OFFSET should be 29")
        void valueLenOffset() {
            assertEquals(29, LogEntry.VALUE_LEN_OFFSET);
        }

        @Test
        @DisplayName("VALUE_LEN_SIZE should be 4")
        void valueLenSize() {
            assertEquals(4, LogEntry.VALUE_LEN_SIZE);
        }

        @Test
        @DisplayName("KEY_BYTES_OFFSET should be 33")
        void keyBytesOffset() {
            assertEquals(33, LogEntry.KEY_BYTES_OFFSET);
        }

        @Test
        @DisplayName("HEADER_SIZE should be 33 (1+8+8+8+4+4)")
        void headerSize() {
            assertEquals(33, LogEntry.HEADER_SIZE);
        }

        @Test
        @DisplayName("CRC32_SIZE should be 4")
        void crc32Size() {
            assertEquals(4, LogEntry.CRC32_SIZE);
        }

        @Test
        @DisplayName("ENTRY_OVERHEAD should be 37 (33+4)")
        void entryOverhead() {
            assertEquals(37, LogEntry.ENTRY_OVERHEAD);
        }
    }

    // ==================== Constructor and Validation Tests ====================

    @Nested
    @DisplayName("Constructor and Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should create PUT entry with valid parameters")
        void validPutEntry() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            assertEquals(LogEntry.OPCODE_PUT, entry.opcode());
            assertEquals(TEST_LSN, entry.lsn());
            assertEquals(TEST_TIMESTAMP, entry.timestamp());
            assertEquals(TEST_KEY_HASH, entry.keyHash());
            assertArrayEquals(key, entry.key());
            assertArrayEquals(value, entry.value());
            assertTrue(entry.isPut());
            assertFalse(entry.isDelete());
        }

        @Test
        @DisplayName("Should create DELETE entry with valid parameters")
        void validDeleteEntry() {
            byte[] key = TEST_KEY.getBytes();

            LogEntry entry = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key);

            assertEquals(LogEntry.OPCODE_DELETE, entry.opcode());
            assertEquals(TEST_LSN, entry.lsn());
            assertEquals(TEST_TIMESTAMP, entry.timestamp());
            assertEquals(TEST_KEY_HASH, entry.keyHash());
            assertArrayEquals(key, entry.key());
            assertEquals(0, entry.valueLength());
            assertFalse(entry.isPut());
            assertTrue(entry.isDelete());
        }

        @Test
        @DisplayName("Should reject invalid opcode")
        void invalidOpcode() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry((byte) 0x03, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value));
        }

        @Test
        @DisplayName("Should reject negative LSN")
        void negativeLsn() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, -1, TEST_TIMESTAMP, TEST_KEY_HASH, key, value));
        }

        @Test
        @DisplayName("Should accept LSN = 0 (boundary)")
        void zeroLsn() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = new LogEntry(LogEntry.OPCODE_PUT, 0, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);
            assertEquals(0, entry.lsn());
        }

        @Test
        @DisplayName("Should accept LSN = Long.MAX_VALUE (boundary)")
        void maxLsn() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = new LogEntry(LogEntry.OPCODE_PUT, Long.MAX_VALUE, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);
            assertEquals(Long.MAX_VALUE, entry.lsn());
        }

        @Test
        @DisplayName("Should reject non-positive timestamp")
        void nonPositiveTimestamp() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, 0, TEST_KEY_HASH, key, value));

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, -1, TEST_KEY_HASH, key, value));
        }

        @Test
        @DisplayName("Should reject negative key hash")
        void negativeKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, -1, key, value));
        }

        @Test
        @DisplayName("Should accept key hash = 0 (boundary)")
        void zeroKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, 0, key, value);
            assertEquals(0, entry.keyHash());
        }

        @Test
        @DisplayName("Should accept key hash = Long.MAX_VALUE (boundary)")
        void maxKeyHash() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, Long.MAX_VALUE, key, value);
            assertEquals(Long.MAX_VALUE, entry.keyHash());
        }

        @Test
        @DisplayName("Should reject null key")
        void nullKey() {
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(NullPointerException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, null, value));
        }

        @Test
        @DisplayName("Should reject empty key")
        void emptyKey() {
            byte[] key = new byte[0];
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value));
        }

        @Test
        @DisplayName("Should reject null value")
        void nullValue() {
            byte[] key = TEST_KEY.getBytes();

            assertThrows(NullPointerException.class, () ->
                new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, null));
        }

        @Test
        @DisplayName("Should accept empty value for PUT")
        void emptyValueForPut() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = new byte[0];

            LogEntry entry = new LogEntry(LogEntry.OPCODE_PUT, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);
            assertEquals(0, entry.valueLength());
        }

        @Test
        @DisplayName("Should reject non-empty value for DELETE")
        void nonEmptyValueForDelete() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            assertThrows(IllegalArgumentException.class, () ->
                new LogEntry(LogEntry.OPCODE_DELETE, TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value));
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
            int expected = LogEntry.ENTRY_OVERHEAD + keyLen + valueLen; // 37 + 10 + 20 = 67

            assertEquals(67, LogEntry.calculateSize(keyLen, valueLen));
        }

        @Test
        @DisplayName("Entry size() should match calculateSize()")
        void entrySize() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();

            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            int expectedSize = LogEntry.calculateSize(key.length, value.length);
            assertEquals(expectedSize, entry.size());
        }

        @Test
        @DisplayName("Size should be header(33) + key + value + crc32(4)")
        void sizeBreakdown() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes

            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            // 33 (header) + 3 (key) + 3 (value) + 4 (crc32) = 43
            assertEquals(43, entry.size());
        }

        @Test
        @DisplayName("DELETE entry should have 0 value length in size")
        void deleteSize() {
            byte[] key = "key".getBytes(); // 3 bytes

            LogEntry entry = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key);

            // 33 (header) + 3 (key) + 0 (value) + 4 (crc32) = 40
            assertEquals(40, entry.size());
        }
    }

    // ==================== MemorySegment Read/Write Tests ====================

    @Nested
    @DisplayName("MemorySegment Read/Write")
    class MemorySegmentTests {

        @Test
        @DisplayName("Should write and read PUT entry to/from MemorySegment")
        void writeAndReadPut() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry original = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(original.size());
            original.writeTo(segment, 0);

            LogEntry read = LogEntry.readFrom(segment, 0);

            assertEquals(original.opcode(), read.opcode());
            assertEquals(original.lsn(), read.lsn());
            assertEquals(original.timestamp(), read.timestamp());
            assertEquals(original.keyHash(), read.keyHash());
            assertArrayEquals(original.key(), read.key());
            assertArrayEquals(original.value(), read.value());
        }

        @Test
        @DisplayName("Should write and read DELETE entry to/from MemorySegment")
        void writeAndReadDelete() {
            byte[] key = TEST_KEY.getBytes();
            LogEntry original = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key);

            MemorySegment segment = arena.allocate(original.size());
            original.writeTo(segment, 0);

            LogEntry read = LogEntry.readFrom(segment, 0);

            assertEquals(LogEntry.OPCODE_DELETE, read.opcode());
            assertEquals(0, read.valueLength());
        }

        @Test
        @DisplayName("Should write entry at non-zero offset")
        void writeAtOffset() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry original = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            int offset = 100;
            MemorySegment segment = arena.allocate(original.size() + offset);

            original.writeTo(segment, offset);
            LogEntry read = LogEntry.readFrom(segment, offset);

            assertEquals(original.lsn(), read.lsn());
        }

        @Test
        @DisplayName("Should throw on CRC32 mismatch")
        void crc32Mismatch() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, 5);

            assertThrows(IllegalStateException.class, () ->
                LogEntry.readFrom(segment, 0));
        }

        @Test
        @DisplayName("readFromUnchecked should skip CRC32 validation")
        void readFromUnchecked() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, 5);

            // Should not throw - unchecked read
            LogEntry read = LogEntry.readFromUnchecked(segment, 0);
            assertNotNull(read);
        }

        @Test
        @DisplayName("validateCrc32 should return true for valid entry")
        void validateCrc32Valid() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertTrue(LogEntry.validateCrc32(segment, 0));
        }

        @Test
        @DisplayName("validateCrc32 should return false for corrupted entry")
        void validateCrc32Invalid() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, 10);

            assertFalse(LogEntry.validateCrc32(segment, 0));
        }

        @Test
        @DisplayName("sizeAt should calculate size from segment")
        void sizeAt() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(entry.size(), LogEntry.sizeAt(segment, 0));
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
            LogEntry original = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            ByteBuffer buffer = ByteBuffer.allocate(original.size())
                .order(ByteOrder.LITTLE_ENDIAN);

            original.writeTo(buffer, 0);
            LogEntry read = LogEntry.readFrom(buffer, 0);

            assertEquals(original.lsn(), read.lsn());
            assertArrayEquals(original.key(), read.key());
            assertArrayEquals(original.value(), read.value());
        }

        @Test
        @DisplayName("Should handle big-endian buffer by converting")
        void bigEndianBuffer() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry original = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            ByteBuffer buffer = ByteBuffer.allocate(original.size())
                .order(ByteOrder.BIG_ENDIAN);

            original.writeTo(buffer, 0);
            LogEntry read = LogEntry.readFrom(buffer, 0);

            assertEquals(original.lsn(), read.lsn());
        }
    }

    // ==================== Byte Layout Verification Tests ====================

    @Nested
    @DisplayName("Byte Layout Verification")
    class ByteLayoutTests {

        @Test
        @DisplayName("Should write opcode at offset 0")
        void opcodeLayout() {
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            assertEquals(LogEntry.OPCODE_PUT,
                segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        }

        @Test
        @DisplayName("Should write LSN at offset 1 (little-endian)")
        void lsnLayout() {
            long testLsn = 0x0102030405060708L;
            LogEntry entry = LogEntry.put(testLsn, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(testLsn, buffer.getLong(LogEntry.LSN_OFFSET));
        }

        @Test
        @DisplayName("Should write timestamp at offset 9 (little-endian)")
        void timestampLayout() {
            long testTimestamp = 0x0102030405060708L;
            LogEntry entry = LogEntry.put(TEST_LSN, testTimestamp, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(testTimestamp, buffer.getLong(LogEntry.TIMESTAMP_OFFSET));
        }

        @Test
        @DisplayName("Should write key_hash at offset 17 (little-endian)")
        void keyHashLayout() {
            // Use positive hash value to avoid validation error
            long testHash = 0x5EADBEEFCAFEBABEL; // Positive value
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, testHash,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(testHash, buffer.getLong(LogEntry.KEY_HASH_OFFSET));
        }

        @Test
        @DisplayName("Should write key_len at offset 25 (little-endian)")
        void keyLenLayout() {
            byte[] key = "test".getBytes(); // 4 bytes
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                key, TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(4, buffer.getInt(LogEntry.KEY_LEN_OFFSET));
        }

        @Test
        @DisplayName("Should write value_len at offset 29 (little-endian)")
        void valueLenLayout() {
            byte[] value = "test-value".getBytes(); // 10 bytes
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(10, buffer.getInt(LogEntry.VALUE_LEN_OFFSET));
        }

        @Test
        @DisplayName("Should write key bytes starting at offset 33")
        void keyBytesLayout() {
            byte[] key = "test".getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                key, TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            for (int i = 0; i < key.length; i++) {
                assertEquals(key[i],
                    segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 33 + i));
            }
        }

        @Test
        @DisplayName("Should write value bytes after key")
        void valueBytesLayout() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Value should start at offset 33 + key.length = 36
            int valueOffset = 33 + key.length;
            for (int i = 0; i < value.length; i++) {
                assertEquals(value[i],
                    segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, valueOffset + i));
            }
        }

        @Test
        @DisplayName("Should write CRC32 at end of entry (little-endian)")
        void crc32Layout() {
            byte[] key = "key".getBytes(); // 3 bytes
            byte[] value = "val".getBytes(); // 3 bytes
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // CRC32 should be at offset 33 + key.length + value.length = 39
            int crcOffset = 33 + key.length + value.length;

            // Read stored CRC32 using ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            int storedCrc = buffer.getInt(crcOffset);

            // Compute expected CRC32
            CRC32 crc = new CRC32();
            byte[] headerBytes = new byte[33];
            ByteBuffer headerView = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            headerView.put(0, LogEntry.OPCODE_PUT);
            headerView.putLong(1, TEST_LSN);
            headerView.putLong(9, TEST_TIMESTAMP);
            headerView.putLong(17, TEST_KEY_HASH);
            headerView.putInt(25, key.length);
            headerView.putInt(29, value.length);
            crc.update(headerBytes);
            crc.update(key);
            crc.update(value);
            int expectedCrc = (int) crc.getValue();

            assertEquals(expectedCrc, storedCrc);
        }

        @Test
        @DisplayName("DELETE entry should have value_len = 0")
        void deleteValueLenLayout() {
            byte[] key = "key".getBytes();
            LogEntry entry = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Use ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, buffer.getInt(LogEntry.VALUE_LEN_OFFSET));
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
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                originalKey, TEST_VALUE.getBytes());

            byte[] key1 = entry.key();
            byte[] key2 = entry.key();

            assertNotSame(originalKey, key1);
            assertNotSame(key1, key2);

            key1[0] = (byte) 0xFF;
            byte[] key3 = entry.key();
            assertNotEquals((byte) 0xFF, key3[0]);
        }

        @Test
        @DisplayName("value() should return defensive copy")
        void valueDefensiveCopy() {
            byte[] originalValue = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), originalValue);

            byte[] value1 = entry.value();
            byte[] value2 = entry.value();

            assertNotSame(originalValue, value1);
            assertNotSame(value1, value2);

            value1[0] = (byte) 0xFF;
            byte[] value3 = entry.value();
            assertNotEquals((byte) 0xFF, value3[0]);
        }
    }

    // ==================== Equals and HashCode Tests ====================

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Equal entries should have same hashCode")
        void equalHashCode() {
            LogEntry entry1 = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());
            LogEntry entry2 = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            assertEquals(entry1, entry2);
            assertEquals(entry1.hashCode(), entry2.hashCode());
        }

        @Test
        @DisplayName("Different opcode should not be equal")
        void differentOpcode() {
            LogEntry put = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());
            LogEntry delete = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes());

            assertNotEquals(put, delete);
        }

        @Test
        @DisplayName("Different LSN should not be equal")
        void differentLsn() {
            LogEntry entry1 = LogEntry.put(100L, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());
            LogEntry entry2 = LogEntry.put(200L, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            assertNotEquals(entry1, entry2);
        }
    }

    // ==================== ToString Tests ====================

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain opcode")
        void toStringOpcode() {
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            String str = entry.toString();
            assertTrue(str.contains("0x01"));
        }

        @Test
        @DisplayName("toString should contain LSN")
        void toStringLsn() {
            LogEntry entry = LogEntry.put(42L, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            String str = entry.toString();
            assertTrue(str.contains("lsn=42"));
        }

        @Test
        @DisplayName("toString with maxBytes should truncate key display")
        void toStringTruncated() {
            byte[] key = "0123456789".getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                key, TEST_VALUE.getBytes());

            String str = entry.toString(3);
            assertTrue(str.contains("..."));
        }

        @Test
        @DisplayName("toString should show PUT for put operation")
        void toStringPut() {
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            String str = entry.toString(10);
            assertTrue(str.contains("PUT"));
        }

        @Test
        @DisplayName("toString should show DELETE for delete operation")
        void toStringDelete() {
            LogEntry entry = LogEntry.delete(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes());

            String str = entry.toString(10);
            assertTrue(str.contains("DELETE"));
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

            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            LogEntry read = LogEntry.readFrom(segment, 0);

            assertArrayEquals(key, read.key());
            assertArrayEquals(value, read.value());
        }

        @Test
        @DisplayName("Should handle large LSN")
        void largeLsn() {
            LogEntry entry = LogEntry.put(Long.MAX_VALUE, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), TEST_VALUE.getBytes());

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            LogEntry read = LogEntry.readFrom(segment, 0);

            assertEquals(Long.MAX_VALUE, read.lsn());
        }

        @Test
        @DisplayName("Should handle empty value")
        void emptyValue() {
            byte[] emptyValue = new byte[0];
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH,
                TEST_KEY.getBytes(), emptyValue);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);
            LogEntry read = LogEntry.readFrom(segment, 0);

            assertEquals(0, read.valueLength());
            assertArrayEquals(emptyValue, read.value());
        }

        @Test
        @DisplayName("isPut and isDelete static methods should work")
        void staticIsPutIsDelete() {
            assertTrue(LogEntry.isPut(LogEntry.OPCODE_PUT));
            assertFalse(LogEntry.isPut(LogEntry.OPCODE_DELETE));
            assertFalse(LogEntry.isDelete(LogEntry.OPCODE_PUT));
            assertTrue(LogEntry.isDelete(LogEntry.OPCODE_DELETE));
        }
    }

    // ==================== CRC32 Correctness Tests ====================

    @Nested
    @DisplayName("CRC32 Correctness")
    class Crc32Tests {

        @Test
        @DisplayName("CRC32 should cover all bytes before CRC32 field")
        void crc32Coverage() {
            byte[] key = "key".getBytes();
            byte[] value = "value".getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            // Verify CRC32 is computed over all bytes before it
            int dataLen = LogEntry.HEADER_SIZE + key.length + value.length;

            // Read all data bytes
            byte[] dataBytes = new byte[dataLen];
            MemorySegment dataSlice = segment.asSlice(0, dataLen);
            dataSlice.asByteBuffer().get(dataBytes);

            // Compute expected CRC32
            CRC32 crc = new CRC32();
            crc.update(dataBytes);
            int expectedCrc = (int) crc.getValue();

            // Read stored CRC32 using ByteBuffer for unaligned access
            ByteBuffer buffer = segment.asSlice(0, entry.size()).asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
            int storedCrc = buffer.getInt(dataLen);

            assertEquals(expectedCrc, storedCrc);
        }

        @Test
        @DisplayName("CRC32 should detect corruption in header")
        void crc32DetectsHeaderCorruption() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, 5);

            assertFalse(LogEntry.validateCrc32(segment, 0));
        }

        @Test
        @DisplayName("CRC32 should detect corruption in key")
        void crc32DetectsKeyCorruption() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, LogEntry.KEY_BYTES_OFFSET);

            assertFalse(LogEntry.validateCrc32(segment, 0));
        }

        @Test
        @DisplayName("CRC32 should detect corruption in value")
        void crc32DetectsValueCorruption() {
            byte[] key = TEST_KEY.getBytes();
            byte[] value = TEST_VALUE.getBytes();
            LogEntry entry = LogEntry.put(TEST_LSN, TEST_TIMESTAMP, TEST_KEY_HASH, key, value);

            MemorySegment segment = arena.allocate(entry.size());
            entry.writeTo(segment, 0);

            corruptByte(segment, LogEntry.KEY_BYTES_OFFSET + key.length);

            assertFalse(LogEntry.validateCrc32(segment, 0));
        }
    }
}
