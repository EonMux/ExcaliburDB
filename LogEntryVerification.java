/**
 * Standalone verification for the log entry structure with checksum.
 */
public class LogEntryVerification {

    public static void main(String[] args) {
        System.out.println("=== Log Entry Structure Verification ===\n");

        testLogEntryType();
        testLogEntrySerialization();
        testChecksumVerification();
        testWriteLogEntry();

        System.out.println("\n=== All Verifications PASSED ===");
    }

    private static void testLogEntryType() {
        System.out.println("[1] Testing LogEntryType enum...");

        // Test toByte/fromByte
        assertLogEntryType(io.excalibur.store.entry.LogEntryType.PUT, 
                           io.excalibur.store.entry.LogEntryType.fromByte((byte) 0));
        assertLogEntryType(io.excalibur.store.entry.LogEntryType.DELETE,
                           io.excalibur.store.entry.LogEntryType.fromByte((byte) 1));
        assertLogEntryType(io.excalibur.store.entry.LogEntryType.COMMIT,
                           io.excalibur.store.entry.LogEntryType.fromByte((byte) 2));
        assertLogEntryType(io.excalibur.store.entry.LogEntryType.SNAPSHOT,
                           io.excalibur.store.entry.LogEntryType.fromByte((byte) 3));

        // Test isValid
        assertTrue(io.excalibur.store.entry.LogEntryType.isValid((byte) 0), "isValid(0) should be true");
        assertTrue(io.excalibur.store.entry.LogEntryType.isValid((byte) 3), "isValid(3) should be true");
        assertFalse(io.excalibur.store.entry.LogEntryType.isValid((byte) 4), "isValid(4) should be false");

        System.out.println("    ✓ LogEntryType tests passed\n");
    }

    private static void testLogEntrySerialization() {
        System.out.println("[2] Testing LogEntry serialization/deserialization...");

        // Create a PUT entry
        long entryId = 12345L;
        long keyHash = 0xABCDEF0123456789L;
        int keyLen = 10;
        int valueLen = 50;
        byte[] keyBytes = "testkey".getBytes();
        byte[] valueBytes = "testvalue".getBytes();

        io.excalibur.store.entry.LogEntry original = 
            io.excalibur.store.entry.LogEntry.put(entryId, keyHash, keyLen, valueLen, keyBytes, valueBytes);

        // Verify basic properties
        assertNotNull(original, "Original entry should not be null");
        assertEquals(io.excalibur.store.entry.LogEntryType.PUT, original.getType(), "Entry type should be PUT");
        assertEquals(entryId, original.getEntryId(), "Entry ID should match");
        assertTrue(original.verifyChecksum(), "Original entry should pass checksum");

        // Serialize and deserialize
        byte[] serialized = original.serialize();
        assertNotNull(serialized, "Serialized data should not be null");
        assertTrue(serialized.length >= 13, "Serialized size should be at least 13 bytes");

        io.excalibur.store.entry.LogEntry deserialized = io.excalibur.store.entry.LogEntry.deserialize(serialized);
        assertNotNull(deserialized, "Deserialized entry should not be null");

        // Verify deserialized properties
        assertEquals(original.getType(), deserialized.getType(), "Type should match");
        assertEquals(original.getEntryId(), deserialized.getEntryId(), "Entry ID should match");
        assertEquals(original.getPayloadChecksum(), deserialized.getPayloadChecksum(), "Checksum should match");
        assertEquals(original.getPayloadSize(), deserialized.getPayloadSize(), "Payload size should match");
        assertTrue(deserialized.verifyChecksum(), "Deserialized entry should pass checksum");

        // Test all entry types
        io.excalibur.store.entry.LogEntry deleteEntry = 
            io.excalibur.store.entry.LogEntry.delete(1L, 0L, 0, new byte[0]);
        assertTrue(deleteEntry.verifyChecksum(), "DELETE entry should pass checksum");

        io.excalibur.store.entry.LogEntry commitEntry = 
            io.excalibur.store.entry.LogEntry.commit(1L, 100, 1);
        assertTrue(commitEntry.verifyChecksum(), "COMMIT entry should pass checksum");

        io.excalibur.store.entry.LogEntry snapshotEntry = 
            io.excalibur.store.entry.LogEntry.snapshot(1L, 500, 100);
        assertTrue(snapshotEntry.verifyChecksum(), "SNAPSHOT entry should pass checksum");

        System.out.println("    ✓ LogEntry serialization tests passed\n");
    }

    private static void testChecksumVerification() {
        System.out.println("[3] Testing checksum verification...");

        // Create entries of all types
        io.excalibur.store.entry.LogEntry putEntry = 
            io.excalibur.store.entry.LogEntry.put(1L, 0L, 0, 0, new byte[0], new byte[0]);
        io.excalibur.store.entry.LogEntry deleteEntry = 
            io.excalibur.store.entry.LogEntry.delete(1L, 0L, 0, new byte[0]);
        io.excalibur.store.entry.LogEntry commitEntry = 
            io.excalibur.store.entry.LogEntry.commit(1L, 100, 1);
        io.excalibur.store.entry.LogEntry snapshotEntry = 
            io.excalibur.store.entry.LogEntry.snapshot(1L, 500, 100);

        // All should pass checksum
        assertTrue(putEntry.verifyChecksum(), "PUT entry should pass checksum");
        assertTrue(deleteEntry.verifyChecksum(), "DELETE entry should pass checksum");
        assertTrue(commitEntry.verifyChecksum(), "COMMIT entry should pass checksum");
        assertTrue(snapshotEntry.verifyChecksum(), "SNAPSHOT entry should pass checksum");

        // Verify checksum is non-zero (meaningful value)
        assertTrue(putEntry.getPayloadChecksum() != 0, "Checksum should be non-zero");

        System.out.println("    ✓ Checksum verification tests passed\n");
    }

    private static void testWriteLogEntry() {
        System.out.println("[4] Testing WriteLogEntry operations...");

        // Create a mock segment using MemorySegment interface
        io.excalibur.core.memory.MemorySegment segment = null;
        java.io.FileChannel channel = null;
        try {
            channel = java.nio.channels.FileChannel.open(
                java.nio.file.Paths.get("build/test.log"),
                java.nio.file.OpenOption.CREATE, java.nio.file.OpenOption.READ, java.nio.file.OpenOption.WRITE);
            segment = io.excalibur.core.memory.MemorySegment.mapFile(channel, 4096);

            // Create a WriteLogEntry
            io.excalibur.store.entry.WriteLogEntry writer = 
                new io.excalibur.store.entry.WriteLogEntry(segment, 0, 64 * 1024);

            // Verify initial state
            assertEquals(0, writer.getEntryCount(), "Initial entry count should be 0");
            assertTrue(writer.getLogSize() == 0, "Log size should be 0");

            // Append entries
            writer.appendPut(1L, 0L, 0, 0, new byte[0], new byte[0]);
            writer.appendPut(2L, 0L, 0, 0, new byte[0], new byte[0]);
            writer.appendDelete(3L, 0L, 0, new byte[0]);

            // Verify state after appends
            assertEquals(3, writer.getEntryCount(), "Entry count should be 3");
            assertTrue(writer.getLogSize() > 0, "Log size should be > 0");

            // Read entries
            io.excalibur.store.entry.LogEntry first = writer.getFirstEntry();
            assertNotNull(first, "First entry should not be null");
            assertEquals(io.excalibur.store.entry.LogEntryType.PUT, first.getType(), 
                         "First entry should be PUT");

            io.excalibur.store.entry.LogEntry last = writer.getLastEntry();
            assertNotNull(last, "Last entry should not be null");
            assertEquals(io.excalibur.store.entry.LogEntryType.DELETE, last.getType(), 
                         "Last entry should be DELETE");

            // Scan all
            io.excalibur.store.entry.LogEntry[] all = writer.scanAll();
            assertEquals(3, all.length, "Should have 3 entries");

            // Verify integrity
            assertTrue(writer.verifyIntegrity(), "All entries should have valid checksums");

            // Test rewind
            writer.rewind();
            assertEquals(0, writer.getEntryCount(), "After rewind, count should be 0");

            System.out.println("    ✓ WriteLogEntry tests passed\n");
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
            java.io.File testFile = new java.io.File("build/test.log");
            if (testFile.exists()) testFile.delete();
        }
    }

    // Helper methods for assertions
    private static void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertLogEntryType(io.excalibur.store.entry.LogEntryType expected, 
                                          io.excalibur.store.entry.LogEntryType actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
