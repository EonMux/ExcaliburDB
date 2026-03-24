package io.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Write-Ahead Log (WAL) crash recovery with StorageEngine.
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>Segments created at runtime via createSegmentAtIndex() have WriteLog attached</li>
 *   <li>Segments created via preAllocateSegment() have WriteLog attached</li>
 *   <li>Data written to newly created segments survives a simulated crash</li>
 *   <li>WAL files are created with correct naming convention</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>Tests verify that:
 * <ul>
 *   <li>All segments have non-null WriteLog after creation</li>
 *   <li>WAL files are created in the data directory</li>
 *   <li>After crash and recovery, data is intact</li>
 *   <li>LSNs are monotonically increasing across segments</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
@DisplayName("StorageEngine Write-Ahead Log Integration Tests")
class StorageEngineWriteLogTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Arena arena;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Segment Creation with WriteLog Tests ====================

    @Nested
    @DisplayName("Segment Creation with WriteLog")
    class SegmentCreationWithWriteLogTests {

        @Test
        @DisplayName("New segments created by put() should have WriteLog attached")
        void testNewSegmentHasWriteLog() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Write data that will trigger segment creation
            byte[] key = "test-key-1".getBytes();
            byte[] value = "test-value-1".getBytes();
            engine.put(key, value);

            // Get the segment that was created
            Segment segment = engine.getSegment(0L);
            assertNotNull(segment, "Segment should be created");
            assertNotNull(segment.writeLog(), "Segment should have WriteLog attached");
            assertFalse(segment.writeLog().isClosed(), "WriteLog should be open");
        }

        @Test
        @DisplayName("Pre-allocated segments should have WriteLog attached")
        void testPreAllocatedSegmentHasWriteLog() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate a segment
            engine.preAllocateSegment(5);

            // Get the pre-allocated segment
            Segment segment = engine.getSegment(5L);
            assertNotNull(segment, "Pre-allocated segment should exist");
            assertNotNull(segment.writeLog(), "Pre-allocated segment should have WriteLog attached");
            assertFalse(segment.writeLog().isClosed(), "WriteLog should be open");
        }

        @Test
        @DisplayName("WAL file should be created on disk when segment is created")
        void testWalFileCreatedOnDisk() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Write data to trigger segment creation
            engine.put("test-key".getBytes(), "test-value".getBytes());

            // Verify WAL file exists
            Path walPath = dataDir.resolve("segment_0.wal");
            assertTrue(Files.exists(walPath), "WAL file should be created on disk");
            assertTrue(Files.size(walPath) > 0, "WAL file should not be empty");
        }

        @Test
        @DisplayName("Pre-allocated segment WAL file should be created on disk")
        void testPreAllocatedWalFileCreatedOnDisk() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment
            engine.preAllocateSegment(3);

            // Verify WAL file exists
            Path walPath = dataDir.resolve("segment_3.wal");
            assertTrue(Files.exists(walPath), "WAL file should be created on disk for pre-allocated segment");
        }
    }

    // ==================== Crash Recovery Tests ====================

    @Nested
    @DisplayName("Crash Recovery")
    class CrashRecoveryTests {

        @Test
        @DisplayName("Data written to newly created segment should survive crash")
        void testDataSurvivesCrash() throws IOException {
            // First session: write data
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            byte[] key1 = "crash-key-1".getBytes();
            byte[] value1 = "crash-value-1".getBytes();
            byte[] key2 = "crash-key-2".getBytes();
            byte[] value2 = "crash-value-2".getBytes();

            engine.put(key1, value1);
            engine.put(key2, value2);

            // Verify data is in the segment
            MemorySegment result1 = engine.get(key1);
            assertNotNull(result1, "Data should be written");
            assertArrayEquals(value1, result1.toArray(ValueLayout.JAVA_BYTE));

            // Simulate crash: close engine without clean shutdown
            // Note: We call close() here, but in a real crash the process would be killed
            // The WAL fsync ensures data is on disk
            engine.close();
            engine = null;

            // Second session: restart and recover
            arena.close();
            arena = Arena.ofShared();
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Verify data was recovered
            MemorySegment recovered1 = engine.get(key1);
            MemorySegment recovered2 = engine.get(key2);

            assertNotNull(recovered1, "Key1 should be recovered after restart");
            assertNotNull(recovered2, "Key2 should be recovered after restart");
            assertArrayEquals(value1, recovered1.toArray(ValueLayout.JAVA_BYTE),
                "Recovered value1 should match original");
            assertArrayEquals(value2, recovered2.toArray(ValueLayout.JAVA_BYTE),
                "Recovered value2 should match original");
        }

        @Test
        @DisplayName("Data written to pre-allocated segment should survive crash")
        void testPreAllocatedSegmentDataSurvivesCrash() throws IOException {
            // First session: pre-allocate and write
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            engine.preAllocateSegment(10);

            byte[] key = "prealloc-crash-key".getBytes();
            byte[] value = "prealloc-crash-value".getBytes();

            // Get the segment and write directly
            Segment segment = engine.getSegment(10L);
            assertNotNull(segment, "Pre-allocated segment should exist");
            segment.put(key, value);

            // Simulate crash
            engine.close();
            engine = null;

            // Second session: restart
            arena.close();
            arena = Arena.ofShared();
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // The data in segment 10 should be recovered when that segment is loaded
            // Note: Recovery happens during segment loading from existing WAL files
            Segment recoveredSegment = engine.getSegment(10L);
            assertNotNull(recoveredSegment, "Segment should be recovered");

            MemorySegment result = recoveredSegment.get(key);
            assertNotNull(result, "Data should be recovered from WAL");
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE),
                "Recovered value should match original");
        }

        @Test
        @DisplayName("Multiple segments should all have WAL protection")
        void testMultipleSegmentsHaveWalProtection() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate multiple segments
            for (int i = 0; i < 5; i++) {
                engine.preAllocateSegment(i);
            }

            // Verify all have WriteLog
            for (int i = 0; i < 5; i++) {
                Segment segment = engine.getSegment((long) i);
                assertNotNull(segment, "Segment " + i + " should exist");
                assertNotNull(segment.writeLog(), "Segment " + i + " should have WriteLog");
            }

            // Verify all WAL files exist on disk
            for (int i = 0; i < 5; i++) {
                Path walPath = dataDir.resolve("segment_" + i + ".wal");
                assertTrue(Files.exists(walPath), "WAL file for segment " + i + " should exist");
            }
        }
    }

    // ==================== WriteLog Functionality Tests ====================

    @Nested
    @DisplayName("WriteLog Functionality")
    class WriteLogFunctionalityTests {

        @Test
        @DisplayName("WriteLog should record put operations")
        void testWriteLogRecordsPuts() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly to ensure data goes there
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);

            segment.put("log-key-1".getBytes(), "log-value-1".getBytes());
            segment.put("log-key-2".getBytes(), "log-value-2".getBytes());

            WriteLog writeLog = segment.writeLog();

            assertNotNull(writeLog, "WriteLog should be attached");
            assertEquals(2, writeLog.entriesAppended(), "WriteLog should have 2 entries");
            assertTrue(writeLog.bytesWritten() > 0, "WriteLog should have written bytes");
        }

        @Test
        @DisplayName("WriteLog should record delete operations")
        void testWriteLogRecordsDeletes() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly to ensure data goes there
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);

            byte[] key = "delete-key".getBytes();
            segment.put(key, "delete-value".getBytes());
            segment.delete(key);

            WriteLog writeLog = segment.writeLog();

            assertEquals(2, writeLog.entriesAppended(), "WriteLog should have 2 entries (put + delete)");
        }

        @Test
        @DisplayName("WriteLog should have correct path")
        void testWriteLogPath() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);
            segment.put("path-key".getBytes(), "path-value".getBytes());

            WriteLog writeLog = segment.writeLog();

            Path expectedPath = dataDir.resolve("segment_0.wal");
            assertEquals(expectedPath, writeLog.path(), "WriteLog path should match expected");
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("LSNs should be unique and increasing within a segment")
        void testLsnsAreUniqueAndIncreasing() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);

            // Write multiple entries directly to segment 0
            for (int i = 0; i < 10; i++) {
                segment.put(("lsn-key-" + i).getBytes(), ("lsn-value-" + i).getBytes());
            }

            WriteLog writeLog = segment.writeLog();

            // Verify LSNs through recovery
            final List<Long> lsns = new ArrayList<>();
            writeLog.recover(entry -> lsns.add(entry.lsn()));

            assertEquals(10, lsns.size(), "Should have 10 entries");
            for (int i = 0; i < 10; i++) {
                assertEquals(i, lsns.get(i), "LSN should match expected value");
            }
        }

        @Test
        @DisplayName("Each segment should have independent LSN counter")
        void testIndependentLsnCounters() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate two segments
            engine.preAllocateSegment(0);
            engine.preAllocateSegment(1);

            // Write to both segments
            Segment segment0 = engine.getSegment(0L);
            Segment segment1 = engine.getSegment(1L);

            segment0.put("seg0-key".getBytes(), "seg0-value".getBytes());
            segment1.put("seg1-key".getBytes(), "seg1-value".getBytes());

            // Both should have LSN 0 (independent counters)
            assertEquals(1, segment0.writeLog().entriesAppended(), "Segment 0 should have 1 entry");
            assertEquals(1, segment1.writeLog().entriesAppended(), "Segment 1 should have 1 entry");
            assertEquals(1, segment0.writeLog().nextLsn(), "Segment 0 next LSN should be 1");
            assertEquals(1, segment1.writeLog().nextLsn(), "Segment 1 next LSN should be 1");
        }

        @Test
        @DisplayName("WAL entries should have valid timestamps")
        void testValidTimestamps() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);
            segment.put("timestamp-key".getBytes(), "timestamp-value".getBytes());

            WriteLog writeLog = segment.writeLog();

            final boolean[] hasValidTimestamp = {false};
            writeLog.recover(entry -> {
                assertTrue(entry.timestamp() > 0, "Timestamp should be positive");
                assertTrue(entry.timestamp() <= System.currentTimeMillis(),
                    "Timestamp should not be in the future");
                hasValidTimestamp[0] = true;
            });

            assertTrue(hasValidTimestamp[0], "Should have processed at least one entry");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Restarting engine should preserve WriteLog attachments")
        void testRestartPreservesWriteLogAttachments() throws IOException {
            // First start
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Pre-allocate segment 0 and write directly
            engine.preAllocateSegment(0);
            Segment segment1 = engine.getSegment(0L);
            segment1.put("restart-key".getBytes(), "restart-value".getBytes());

            assertNotNull(segment1.writeLog(), "WriteLog should be attached after first start");

            // Stop and restart
            engine.close();
            engine = null;

            arena.close();
            arena = Arena.ofShared();
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Write more data to segment 0
            engine.preAllocateSegment(0);
            Segment segment2 = engine.getSegment(0L);
            segment2.put("restart-key-2".getBytes(), "restart-value-2".getBytes());

            assertNotNull(segment2.writeLog(), "WriteLog should be attached after restart");
        }

        @Test
        @DisplayName("Large value should be logged to WAL")
        void testLargeValueLoggedToWal() throws IOException {
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Create a large value (10KB)
            byte[] largeValue = new byte[10 * 1024];
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i % 256);
            }

            // Pre-allocate segment 0 and write directly
            engine.preAllocateSegment(0);
            Segment segment = engine.getSegment(0L);
            segment.put("large-key".getBytes(), largeValue);

            WriteLog writeLog = segment.writeLog();

            assertEquals(1, writeLog.entriesAppended(), "Should have 1 entry");
            assertTrue(writeLog.bytesWritten() > largeValue.length,
                "Bytes written should exceed value size");
        }
    }
}
