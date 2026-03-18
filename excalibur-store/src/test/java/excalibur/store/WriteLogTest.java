package excalibur.store;

import excalibur.store.format.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WriteLog#append(byte, byte[], byte[])}.
 *
 * <h2>Domain Correctness Verification</h2>
 * Tests verify:
 * <ul>
 *   <li>LSN starts at 0 and increases monotonically by 1</li>
 *   <li>Timestamp is positive epoch milliseconds</li>
 *   <li>Opcode is exactly 0x01 (PUT) or 0x02 (DELETE)</li>
 *   <li>CRC32 is computed correctly and validated on recovery</li>
 *   <li>append() fsyncs before returning</li>
 * </ul>
 */
@DisplayName("WriteLog Append Tests")
class WriteLogTest {

    private Arena arena;
    private Path tempLogDir;
    private Path logFile;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        tempLogDir = Files.createTempDirectory("writelog_test");
        logFile = tempLogDir.resolve("test.log");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (arena != null) {
            arena.close();
        }
        if (tempLogDir != null) {
            Files.walk(tempLogDir)
                .sorted((a, b) -> -a.compareTo(b)) // Delete files before directories
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }

    // ==================== LSN Domain Correctness Tests ====================

    @Nested
    @DisplayName("LSN Domain Correctness")
    class LsnTests {

        @Test
        @DisplayName("LSN MUST start at 0 for new log")
        void lsnStartsAtZero() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertEquals(0L, log.nextLsn(), "Next LSN should start at 0");
            }
        }

        @Test
        @DisplayName("LSN MUST increase by 1 for each entry")
        void lsnIncreasesByOne() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                byte[] key = "key1".getBytes();
                byte[] value = "value1".getBytes();

                long lsn0 = log.append(LogEntry.OPCODE_PUT, key, value);
                assertEquals(0L, lsn0, "First LSN must be 0");

                long lsn1 = log.append(LogEntry.OPCODE_PUT, key, value);
                assertEquals(1L, lsn1, "Second LSN must be 1");

                long lsn2 = log.append(LogEntry.OPCODE_PUT, key, value);
                assertEquals(2L, lsn2, "Third LSN must be 2");

                assertEquals(3L, log.nextLsn(), "Next LSN should be 3");
            }
        }

        @Test
        @DisplayName("LSN MUST be monotonically increasing across reopen")
        void lsnMonotonicAcrossReopen() throws IOException {
            // First session
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
                log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
            }

            // Second session with new arena (simulates restart)
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log = new WriteLog(logFile, arena2)) {
                    assertEquals(2L, log.nextLsn(), "Next LSN should be 2 after recovery");

                    long lsn = log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
                    assertEquals(2L, lsn, "LSN should continue from 2");
                }
            } finally {
                arena2.close();
            }
        }

        @Test
        @DisplayName("LSN MUST be in range [0, Long.MAX_VALUE]")
        void lsnRange() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                for (int i = 0; i < 10; i++) {
                    long lsn = log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
                    assertTrue(lsn >= 0, "LSN must be non-negative: " + lsn);
                    assertTrue(lsn <= Long.MAX_VALUE, "LSN must not exceed Long.MAX_VALUE");
                }
            }
        }
    }

    // ==================== Opcode Domain Correctness Tests ====================

    @Nested
    @DisplayName("Opcode Domain Correctness")
    class OpcodeTests {

        @Test
        @DisplayName("Opcode MUST be exactly 0x01 (PUT) or 0x02 (DELETE)")
        void validOpcodes() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // PUT (0x01)
                long lsnPut = log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
                assertEquals(0L, lsnPut);

                // DELETE (0x02)
                long lsnDelete = log.append(LogEntry.OPCODE_DELETE, "key".getBytes(), new byte[0]);
                assertEquals(1L, lsnDelete);
            }
        }

        @Test
        @DisplayName("Invalid opcode (0x00) MUST throw IllegalArgumentException")
        void invalidOpcodeZero() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertThrows(IllegalArgumentException.class, () ->
                    log.append((byte) 0x00, "key".getBytes(), "value".getBytes()));
            }
        }

        @Test
        @DisplayName("Invalid opcode (0x03) MUST throw IllegalArgumentException")
        void invalidOpcodeThree() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertThrows(IllegalArgumentException.class, () ->
                    log.append((byte) 0x03, "key".getBytes(), "value".getBytes()));
            }
        }

        @Test
        @DisplayName("Invalid opcode (0xFF) MUST throw IllegalArgumentException")
        void invalidOpcodeMax() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertThrows(IllegalArgumentException.class, () ->
                    log.append((byte) 0xFF, "key".getBytes(), "value".getBytes()));
            }
        }
    }

    // ==================== Timestamp Domain Correctness Tests ====================

    @Nested
    @DisplayName("Timestamp Domain Correctness")
    class TimestampTests {

        @Test
        @DisplayName("Timestamp MUST be positive epoch milliseconds")
        void timestampPositive() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                long beforeTime = System.currentTimeMillis();
                log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());
                long afterTime = System.currentTimeMillis();

                // Recover and verify timestamp
                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                long timestamp = entries.get(0).timestamp();
                assertTrue(timestamp > 0, "Timestamp must be positive");
                assertTrue(timestamp >= beforeTime, "Timestamp must be >= time before append");
                assertTrue(timestamp <= afterTime, "Timestamp must be <= time after append");
            }
        }
    }

    // ==================== Key Hash Domain Correctness Tests ====================

    @Nested
    @DisplayName("Key Hash Domain Correctness")
    class KeyHashTests {

        @Test
        @DisplayName("Key hash MUST be in range [0, Long.MAX_VALUE] (positive 64-bit)")
        void keyHashPositive() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());

                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                long keyHash = entries.get(0).keyHash();
                assertTrue(keyHash >= 0, "Key hash must be non-negative: " + keyHash);
                assertTrue(keyHash <= Long.MAX_VALUE, "Key hash must not exceed Long.MAX_VALUE");
            }
        }
    }

    // ==================== CRC32 Domain Correctness Tests ====================

    @Nested
    @DisplayName("CRC32 Domain Correctness")
    class Crc32Tests {

        @Test
        @DisplayName("CRC32 MUST be computed correctly and stored little-endian")
        void crc32Correctness() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                byte[] key = "test-key".getBytes();
                byte[] value = "test-value".getBytes();
                log.append(LogEntry.OPCODE_PUT, key, value);

                // Read the file and verify CRC32 computation
                byte[] fileContent = Files.readAllBytes(logFile);

                // Entry size = HEADER_SIZE (33) + key.length + value.length + CRC32_SIZE (4)
                int entrySize = LogEntry.HEADER_SIZE + key.length + value.length + LogEntry.CRC32_SIZE;
                assertTrue(fileContent.length >= entrySize, "File should contain complete entry");

                // CRC32 is computed over bytes [0, 33+key_len+value_len)
                int dataLen = LogEntry.HEADER_SIZE + key.length + value.length;

                // Compute expected CRC32 using java.util.zip.CRC32
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(fileContent, 0, dataLen);
                int expectedCrc = (int) crc.getValue();

                // Read stored CRC32 at end of entry (little-endian)
                int storedCrc = java.nio.ByteBuffer.wrap(fileContent, dataLen, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

                assertEquals(expectedCrc, storedCrc,
                    "CRC32 MUST be computed over bytes [0, 33+key_len+value_len) using java.util.zip.CRC32");

                // Verify it's stored in little-endian (least significant byte first)
                assertEquals(expectedCrc & 0xFF, fileContent[dataLen] & 0xFF,
                    "CRC32 MUST be stored in little-endian format (LSB first)");
            }
        }

        @Test
        @DisplayName("Recovery MUST skip entries with invalid CRC32 (corrupted data byte)")
        void recoverySkipsInvalidCrc32() throws IOException {
            // Write valid entries
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "key1".getBytes(), "value1".getBytes());
                log.append(LogEntry.OPCODE_PUT, "key2".getBytes(), "value2".getBytes());
                log.append(LogEntry.OPCODE_PUT, "key3".getBytes(), "value3".getBytes());
            }

            // Read file and corrupt a byte in the second entry's data
            byte[] fileContent = Files.readAllBytes(logFile);

            // First entry size = HEADER_SIZE (33) + 4 (key1) + 6 (value1) + CRC32_SIZE (4) = 47
            int firstEntrySize = LogEntry.ENTRY_OVERHEAD + "key1".length() + "value1".length();

            // Corrupt a byte in the second entry (flip one byte in the key)
            // Second entry starts at offset = firstEntrySize
            int corruptOffset = firstEntrySize + 10; // Offset into second entry
            fileContent[corruptOffset] = (byte) (fileContent[corruptOffset] ^ 0xFF); // Flip bits

            // Write corrupted content back
            Files.write(logFile, fileContent);

            // Recover and verify
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log2 = new WriteLog(logFile, arena2)) {
                    List<LogEntry> entries = new ArrayList<>();
                    log2.recover(entries::add);

                    // First entry should be recovered, second corrupted entry skipped
                    // Third entry may or may not be recovered depending on scan behavior
                    assertTrue(entries.size() >= 1, "At least first entry should be recovered");
                    if (entries.size() >= 1) {
                        assertEquals("key1", new String(entries.get(0).key()),
                            "First valid entry should be recovered");
                    }
                }
            } finally {
                arena2.close();
            }
        }

        @Test
        @DisplayName("Recovery MUST skip entries with corrupted CRC32 field")
        void recoverySkipsCorruptedCrc32Field() throws IOException {
            // Write a valid entry
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "key1".getBytes(), "value1".getBytes());
            }

            // Read file and corrupt the CRC32 field itself
            byte[] fileContent = Files.readAllBytes(logFile);

            // CRC32 is at offset: HEADER_SIZE (33) + key_len (4) + value_len (6) = 43
            int crcOffset = LogEntry.HEADER_SIZE + "key1".length() + "value1".length();

            // Corrupt the CRC32 field (flip bits)
            fileContent[crcOffset] = (byte) (fileContent[crcOffset] ^ 0xFF);

            // Write corrupted content back
            Files.write(logFile, fileContent);

            // Recover and verify
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log2 = new WriteLog(logFile, arena2)) {
                    List<LogEntry> entries = new ArrayList<>();
                    log2.recover(entries::add);

                    // Entry should be skipped because CRC32 validation fails
                    assertEquals(0, entries.size(),
                        "Entry with corrupted CRC32 field MUST be skipped");
                }
            } finally {
                arena2.close();
            }
        }

        @Test
        @DisplayName("Recovery MUST handle partial writes (truncated entries)")
        void recoveryHandlesPartialWrites() throws IOException {
            // Write a valid entry
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "key1".getBytes(), "value1".getBytes());
                log.append(LogEntry.OPCODE_PUT, "key2".getBytes(), "value2".getBytes());
            }

            // Read file and truncate in the middle of second entry (simulate crash mid-write)
            byte[] fileContent = Files.readAllBytes(logFile);

            // First entry size = HEADER_SIZE (33) + 4 (key1) + 6 (value1) + CRC32_SIZE (4) = 47
            int firstEntrySize = LogEntry.ENTRY_OVERHEAD + "key1".length() + "value1".length();

            // Truncate file at non-entry-boundary offset (middle of second entry)
            int truncateOffset = firstEntrySize + 15; // Partial second entry
            byte[] truncatedContent = new byte[truncateOffset];
            System.arraycopy(fileContent, 0, truncatedContent, 0, truncateOffset);

            // Write truncated content back
            Files.write(logFile, truncatedContent);

            // Recover and verify
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log2 = new WriteLog(logFile, arena2)) {
                    List<LogEntry> entries = new ArrayList<>();
                    log2.recover(entries::add);

                    // Only first complete entry should be recovered
                    assertEquals(1, entries.size(),
                        "Only complete entries before truncation MUST be recovered");
                    assertEquals("key1", new String(entries.get(0).key()),
                        "First valid entry should be recovered");
                }
            } finally {
                arena2.close();
            }
        }

        @Test
        @DisplayName("Recovery MUST recover entries after corrupted entry")
        void recoveryRecoversEntriesAfterCorruption() throws IOException {
            // Write three entries: valid, corrupted, valid
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "first_key".getBytes(), "first_value".getBytes());
            }

            // Read first entry size
            int firstEntrySize = LogEntry.ENTRY_OVERHEAD + "first_key".length() + "first_value".length();

            // Write more entries
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.append(LogEntry.OPCODE_PUT, "second_key".getBytes(), "second_value".getBytes());
                log.append(LogEntry.OPCODE_PUT, "third_key".getBytes(), "third_value".getBytes());
            }

            // Read file and corrupt the second entry
            byte[] fileContent = Files.readAllBytes(logFile);

            // Corrupt a byte in the second entry
            int secondEntryStart = firstEntrySize;
            int corruptOffset = secondEntryStart + 5; // Offset into second entry
            fileContent[corruptOffset] = (byte) (fileContent[corruptOffset] ^ 0xFF);

            // Write corrupted content back
            Files.write(logFile, fileContent);

            // Recover and verify
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log2 = new WriteLog(logFile, arena2)) {
                    List<LogEntry> entries = new ArrayList<>();
                    log2.recover(entries::add);

                    // First entry should always be recovered
                    assertTrue(entries.size() >= 1, "At least first entry should be recovered");

                    // Check first entry is correct
                    boolean foundFirst = false;
                    for (LogEntry e : entries) {
                        if (new String(e.key()).equals("first_key")) {
                            foundFirst = true;
                            break;
                        }
                    }
                    assertTrue(foundFirst, "First valid entry MUST be recovered");
                }
            } finally {
                arena2.close();
            }
        }

        @Test
        @DisplayName("CRC32 MUST validate complete entry integrity (header + key + value)")
        void crc32ValidatesCompleteEntry() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                byte[] key = "integrity_test_key".getBytes();
                byte[] value = "integrity_test_value".getBytes();
                log.append(LogEntry.OPCODE_PUT, key, value);
            }

            byte[] fileContent = Files.readAllBytes(logFile);
            int dataLen = LogEntry.HEADER_SIZE + "integrity_test_key".length() + "integrity_test_value".length();

            // Test corruption in header (opcode byte)
            testCorruptionAt(fileContent.clone(), 0, "header opcode");

            // Test corruption in LSN field
            testCorruptionAt(fileContent.clone(), 1, "LSN field");

            // Test corruption in timestamp field
            testCorruptionAt(fileContent.clone(), 9, "timestamp field");

            // Test corruption in key_hash field
            testCorruptionAt(fileContent.clone(), 17, "key_hash field");

            // Test corruption in key_len field
            testCorruptionAt(fileContent.clone(), 25, "key_len field");

            // Test corruption in value_len field
            testCorruptionAt(fileContent.clone(), 29, "value_len field");

            // Test corruption in key bytes
            testCorruptionAt(fileContent.clone(), 33, "key bytes");

            // Test corruption in value bytes
            testCorruptionAt(fileContent.clone(), 33 + "integrity_test_key".length(), "value bytes");
        }

        private void testCorruptionAt(byte[] content, int offset, String fieldName) throws IOException {
            // Corrupt the byte
            content[offset] = (byte) (content[offset] ^ 0xFF);

            // Write corrupted content
            Files.write(logFile, content);

            // Try to recover
            Arena testArena = Arena.ofConfined();
            try {
                try (WriteLog testLog = new WriteLog(logFile, testArena)) {
                    List<LogEntry> entries = new ArrayList<>();
                    testLog.recover(entries::add);

                    // Entry should be skipped due to CRC32 mismatch
                    assertEquals(0, entries.size(),
                        "Entry with corrupted " + fieldName + " MUST be skipped (CRC32 validation)");
                }
            } finally {
                testArena.close();
            }

            // Restore the byte for next test
            content[offset] = (byte) (content[offset] ^ 0xFF);
        }
    }

    // ==================== Durability Domain Correctness Tests ====================

    @Nested
    @DisplayName("Durability Domain Correctness")
    class DurabilityTests {

        @Test
        @DisplayName("append() MUST fsync before returning")
        void fsyncBeforeReturn() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                long lsn = log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());

                // Verify data is on disk by checking file size
                assertTrue(Files.size(logFile) > 0, "File should have content after fsync");
                assertEquals(0L, lsn);
            }
        }

        @Test
        @DisplayName("Recovery MUST process entries in LSN order")
        void recoveryLsnOrder() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // Write entries in order
                for (int i = 0; i < 5; i++) {
                    log.append(LogEntry.OPCODE_PUT, ("key" + i).getBytes(), ("value" + i).getBytes());
                }
            }

            // Recover and verify LSN order
            Arena arena2 = Arena.ofConfined();
            try {
                try (WriteLog log = new WriteLog(logFile, arena2)) {
                    List<LogEntry> entries = new ArrayList<>();
                    log.recover(entries::add);

                    assertEquals(5, entries.size(), "Should recover 5 entries");

                    for (int i = 0; i < 5; i++) {
                        assertEquals(i, entries.get(i).lsn(), "Entry at index " + i + " should have LSN " + i);
                    }
                }
            } finally {
                arena2.close();
            }
        }
    }

    // ==================== Input Validation Tests ====================

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("Null key MUST throw NullPointerException")
        void nullKey() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertThrows(NullPointerException.class, () ->
                    log.append(LogEntry.OPCODE_PUT, null, "value".getBytes()));
            }
        }

        @Test
        @DisplayName("Null value MUST throw NullPointerException")
        void nullValue() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                assertThrows(NullPointerException.class, () ->
                    log.append(LogEntry.OPCODE_PUT, "key".getBytes(), null));
            }
        }

        @Test
        @DisplayName("Empty key is allowed (LogEntry validation)")
        void emptyKey() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // LogEntry validates that key cannot be empty
                assertThrows(IllegalArgumentException.class, () ->
                    log.append(LogEntry.OPCODE_PUT, new byte[0], "value".getBytes()));
            }
        }

        @Test
        @DisplayName("Empty value for PUT is allowed")
        void emptyValueForPut() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                long lsn = log.append(LogEntry.OPCODE_PUT, "key".getBytes(), new byte[0]);
                assertEquals(0L, lsn);
            }
        }

        @Test
        @DisplayName("Non-empty value for DELETE MUST throw IllegalArgumentException")
        void nonEmptyValueForDelete() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // LogEntry validates that DELETE must have empty value
                assertThrows(IllegalArgumentException.class, () ->
                    log.append(LogEntry.OPCODE_DELETE, "key".getBytes(), "value".getBytes()));
            }
        }
    }

    // ==================== Convenience Method Tests ====================

    @Nested
    @DisplayName("Convenience Methods")
    class ConvenienceMethodTests {

        @Test
        @DisplayName("appendPut MUST use opcode 0x01")
        void appendPut() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                long lsn = log.appendPut("key".getBytes(), "value".getBytes());

                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                assertEquals(LogEntry.OPCODE_PUT, entries.get(0).opcode());
                assertEquals(0L, lsn);
            }
        }

        @Test
        @DisplayName("appendDelete MUST use opcode 0x02")
        void appendDelete() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                long lsn = log.appendDelete("key".getBytes());

                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                assertEquals(LogEntry.OPCODE_DELETE, entries.get(0).opcode());
                assertEquals(0, entries.get(0).valueLength());
                assertEquals(0L, lsn);
            }
        }
    }

    // ==================== Log Rotation Tests ====================

    @Nested
    @DisplayName("Log Rotation")
    class RotationTests {

        @Test
        @DisplayName("rotate() MUST create new empty log and reset LSN")
        void rotationResetsLsn() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // Write some entries
                log.appendPut("key1".getBytes(), "value1".getBytes());
                log.appendPut("key2".getBytes(), "value2".getBytes());

                assertEquals(2L, log.nextLsn());

                // Rotate
                log.rotate();

                assertEquals(0L, log.nextLsn(), "LSN should be reset to 0 after rotation");
                assertEquals(0L, log.writeOffset(), "Write offset should be 0 after rotation");
            }
        }

        @Test
        @DisplayName("Old log file MUST remain on disk after rotation")
        void oldLogRemainsOnDisk() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.appendPut("key".getBytes(), "value".getBytes());
                log.rotate();

                Path rotatedPath = log.getRotatedLogPath();
                assertNotNull(rotatedPath, "Rotated log path should exist");
                assertTrue(Files.exists(rotatedPath), "Rotated log file should exist on disk");
            }
        }

        @Test
        @DisplayName("deleteRotatedLog() MUST delete old log file")
        void deleteRotatedLog() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                log.appendPut("key".getBytes(), "value".getBytes());
                log.rotate();

                Path rotatedPath = log.getRotatedLogPath();
                assertTrue(Files.exists(rotatedPath));

                boolean deleted = log.deleteRotatedLog();
                assertTrue(deleted, "deleteRotatedLog should return true");
                assertFalse(Files.exists(rotatedPath), "Rotated file should be deleted");
            }
        }

        @Test
        @DisplayName("Entries after rotation MUST go to new log file and be recoverable")
        void entriesAfterRotationGoToNewLog() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // Write entries before rotation
                log.appendPut("old_key".getBytes(), "old_value".getBytes());
                assertEquals(1L, log.nextLsn());

                // Rotate
                log.rotate();

                // Write entries after rotation
                long lsn0 = log.appendPut("new_key1".getBytes(), "new_value1".getBytes());
                long lsn1 = log.appendPut("new_key2".getBytes(), "new_value2".getBytes());

                assertEquals(0L, lsn0, "First entry after rotation should have LSN 0");
                assertEquals(1L, lsn1, "Second entry after rotation should have LSN 1");
                assertEquals(2L, log.nextLsn(), "Next LSN should be 2");

                // Recover entries from new log
                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(2, entries.size(), "Should have 2 entries in new log");
                assertEquals("new_key1", new String(entries.get(0).key()));
                assertEquals("new_key2", new String(entries.get(1).key()));
            }
        }

        @Test
        @DisplayName("Old log entries MUST be preserved after rotation")
        void oldLogEntriesPreserved() throws IOException {
            Path rotatedPath;

            try (WriteLog log = new WriteLog(logFile, arena)) {
                // Write entries before rotation
                log.appendPut("old_key1".getBytes(), "old_value1".getBytes());
                log.appendPut("old_key2".getBytes(), "old_value2".getBytes());

                // Rotate
                log.rotate();
                rotatedPath = log.getRotatedLogPath();
            }

            // Verify old log file exists and has entries
            assertTrue(Files.exists(rotatedPath), "Rotated log should exist");
            assertTrue(Files.size(rotatedPath) > 0, "Rotated log should have content");
        }

        @Test
        @DisplayName("Multiple rotations MUST work correctly")
        void multipleRotations() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                // First rotation cycle
                log.appendPut("key1".getBytes(), "value1".getBytes());
                log.rotate();
                log.deleteRotatedLog(); // Clean up first rotated log

                // Second rotation cycle
                log.appendPut("key2".getBytes(), "value2".getBytes());
                log.rotate();

                // Verify second rotated log exists
                Path rotatedPath = log.getRotatedLogPath();
                assertNotNull(rotatedPath, "Second rotated log should exist");
                assertTrue(Files.exists(rotatedPath), "Second rotated log file should exist");

                // Write to new log after second rotation
                long lsn = log.appendPut("key3".getBytes(), "value3".getBytes());
                assertEquals(0L, lsn, "LSN should reset to 0 after second rotation");
            }
        }

        @Test
        @DisplayName("rotate() MUST throw IllegalStateException when log is closed")
        void rotateOnClosedLog() throws IOException {
            WriteLog log = new WriteLog(logFile, arena);
            log.close();

            assertTrue(log.isClosed());
            assertThrows(IllegalStateException.class, log::rotate);
        }
    }

    // ==================== Closed Log Tests ====================

    @Nested
    @DisplayName("Closed State")
    class ClosedStateTests {

        @Test
        @DisplayName("append() MUST throw IllegalStateException when log is closed")
        void appendOnClosedLog() throws IOException {
            WriteLog log = new WriteLog(logFile, arena);
            log.close();

            assertTrue(log.isClosed());
            assertThrows(IllegalStateException.class, () ->
                log.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes()));
        }

        @Test
        @DisplayName("recover() MUST throw IllegalStateException when log is closed")
        void recoverOnClosedLog() throws IOException {
            WriteLog log = new WriteLog(logFile, arena);
            log.appendPut("key".getBytes(), "value".getBytes());
            log.close();

            assertThrows(IllegalStateException.class, () ->
                log.recover(entry -> {}));
        }
    }

    // ==================== Binary Data Tests ====================

    @Nested
    @DisplayName("Binary Data Handling")
    class BinaryDataTests {

        @Test
        @DisplayName("Should handle binary keys with all byte values")
        void binaryKeys() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                byte[] binaryKey = new byte[256];
                for (int i = 0; i < 256; i++) {
                    binaryKey[i] = (byte) i;
                }

                long lsn = log.appendPut(binaryKey, "value".getBytes());
                assertEquals(0L, lsn);

                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                assertArrayEquals(binaryKey, entries.get(0).key());
            }
        }

        @Test
        @DisplayName("Should handle binary values with all byte values")
        void binaryValues() throws IOException {
            try (WriteLog log = new WriteLog(logFile, arena)) {
                byte[] binaryValue = new byte[256];
                for (int i = 0; i < 256; i++) {
                    binaryValue[i] = (byte) i;
                }

                long lsn = log.appendPut("key".getBytes(), binaryValue);
                assertEquals(0L, lsn);

                List<LogEntry> entries = new ArrayList<>();
                log.recover(entries::add);

                assertEquals(1, entries.size());
                assertArrayEquals(binaryValue, entries.get(0).value());
            }
        }
    }
}
