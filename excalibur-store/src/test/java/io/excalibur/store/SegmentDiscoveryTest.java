package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for segment discovery and registration.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Segment files are discovered correctly on startup</li>
 *   <li>Existing data is preserved when reopening segments</li>
 *   <li>Write-ahead logs are recovered during startup</li>
 *   <li>Lifecycle states are correctly set based on segment content</li>
 * </ul>
 */
class SegmentDiscoveryTest {

    @TempDir
    Path tempDir;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== SegmentDiscovery Tests ====================

    @Test
    void scanDirectory_shouldReturnEmptyListWhenNoSegments() {
        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertNotNull(segments);
        assertTrue(segments.isEmpty());
    }

    @Test
    void scanDirectory_shouldDiscoverSingleSegment() throws IOException {
        // Create a segment file
        Path segmentPath = tempDir.resolve("segment_0.dat");
        createTestSegmentFile(segmentPath, 1024 * 1024); // 1MB file

        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertEquals(1, segments.size());
        assertEquals(0, segments.get(0).segmentIndex());
        assertEquals(segmentPath, segments.get(0).path());
        assertTrue(segments.get(0).fileSize() > 0);
    }

    @Test
    void scanDirectory_shouldDiscoverMultipleSegments() throws IOException {
        // Create multiple segment files
        for (int i = 0; i < 5; i++) {
            Path segmentPath = tempDir.resolve("segment_" + i + ".dat");
            createTestSegmentFile(segmentPath, 1024 * 1024);
        }

        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertEquals(5, segments.size());
        // Verify all indices are present
        for (int i = 0; i < 5; i++) {
            final int index = i;
            assertTrue(segments.stream().anyMatch(s -> s.segmentIndex() == index),
                "Segment with index " + i + " should be discovered");
        }
    }

    @Test
    void scanDirectory_shouldDiscoverWriteLog() throws IOException {
        // Create segment file
        Path segmentPath = tempDir.resolve("segment_0.dat");
        createTestSegmentFile(segmentPath, 1024 * 1024);

        // Create write log file
        Path walPath = tempDir.resolve("segment_0.wal");
        Files.writeString(walPath, "test log content");

        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertEquals(1, segments.size());
        assertTrue(segments.get(0).hasWriteLog());
        assertEquals(walPath, segments.get(0).walPath());
    }

    @Test
    void scanDirectory_shouldIgnoreNonSegmentFiles() throws IOException {
        // Create segment file
        Path segmentPath = tempDir.resolve("segment_0.dat");
        createTestSegmentFile(segmentPath, 1024 * 1024);

        // Create non-segment files
        Files.writeString(tempDir.resolve("readme.txt"), "readme");
        Files.writeString(tempDir.resolve("catalog.dat"), "catalog");
        Files.writeString(tempDir.resolve("segment_backup.dat"), "backup");

        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertEquals(1, segments.size());
        assertEquals(0, segments.get(0).segmentIndex());
    }

    @Test
    void scanDirectory_shouldHandleLargeSegmentIndex() throws IOException {
        // Create segment with large index
        Path segmentPath = tempDir.resolve("segment_12345.dat");
        createTestSegmentFile(segmentPath, 1024 * 1024);

        SegmentDiscovery discovery = new SegmentDiscovery(tempDir);
        List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

        assertEquals(1, segments.size());
        assertEquals(12345, segments.get(0).segmentIndex());
    }

    @Test
    void scanDirectory_shouldThrowOnNullDirectory() {
        assertThrows(IllegalArgumentException.class, () -> {
            new SegmentDiscovery(null);
        });
    }

    // ==================== Segment.openExisting Tests ====================

    @Test
    void openExisting_shouldOpenSegmentWithoutTruncating() throws IOException {
        // First, create a segment and write some data
        Path segmentPath = tempDir.resolve("segment_0.dat");
        Segment original = new Segment(segmentPath, 1024 * 1024, arena);

        byte[] key = "testKey".getBytes();
        byte[] value = "testValue".getBytes();
        original.put(key, value);
        original.flush();
        original.close();

        // Verify file has content
        long fileSizeAfterWrite = Files.size(segmentPath);
        assertTrue(fileSizeAfterWrite > 0);

        // Now open existing segment
        Segment reopened = Segment.openExisting(segmentPath, arena);

        // Verify data is preserved
        assertEquals(1, reopened.entryCount());
        java.lang.foreign.MemorySegment retrievedValue = reopened.get(key);
        assertNotNull(retrievedValue);

        reopened.close();
    }

    @Test
    void openExisting_shouldThrowOnNonExistentFile() {
        Path nonExistent = tempDir.resolve("nonexistent.dat");
        assertThrows(IOException.class, () -> {
            Segment.openExisting(nonExistent, arena);
        });
    }

    @Test
    void openExisting_shouldThrowOnNullPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            Segment.openExisting(null, arena);
        });
    }

    @Test
    void openExisting_shouldThrowOnNullArena() throws IOException {
        Path segmentPath = tempDir.resolve("segment_0.dat");
        createTestSegmentFile(segmentPath, 1024 * 1024);

        assertThrows(IllegalArgumentException.class, () -> {
            Segment.openExisting(segmentPath, null);
        });
    }

    @Test
    void openExisting_shouldThrowOnTooSmallFile() throws IOException {
        Path smallFile = tempDir.resolve("segment_0.dat");
        Files.writeString(smallFile, "too small");

        assertThrows(IOException.class, () -> {
            Segment.openExisting(smallFile, arena);
        });
    }

    @Test
    void openExisting_shouldRecoverMultipleEntries() throws IOException {
        // Create segment and write multiple entries
        Path segmentPath = tempDir.resolve("segment_0.dat");
        Segment original = new Segment(segmentPath, 1024 * 1024, arena);

        int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            byte[] key = ("key" + i).getBytes();
            byte[] value = ("value" + i).getBytes();
            original.put(key, value);
        }
        original.flush();
        original.close();

        // Reopen and verify
        Segment reopened = Segment.openExisting(segmentPath, arena);
        assertEquals(numEntries, reopened.entryCount());

        // Verify all entries are accessible
        for (int i = 0; i < numEntries; i++) {
            byte[] key = ("key" + i).getBytes();
            java.lang.foreign.MemorySegment value = reopened.get(key);
            assertNotNull(value, "Entry " + i + " should be found");
        }

        reopened.close();
    }

    // ==================== StorageEngine Integration Tests ====================

    @Test
    void storageEngine_start_shouldDiscoverExistingSegments() throws IOException {
        // First, start the engine and write data through it (so hash routing is consistent)
        StorageEngine engine1 = new StorageEngine(tempDir, arena);
        engine1.start();

        byte[] key0 = "testKey0".getBytes();
        byte[] value0 = "testValue0".getBytes();
        byte[] key1 = "testKey1".getBytes();
        byte[] value1 = "testValue1".getBytes();
        byte[] key2 = "testKey2".getBytes();
        byte[] value2 = "testValue2".getBytes();

        engine1.put(key0, value0);
        engine1.put(key1, value1);
        engine1.put(key2, value2);
        engine1.flush();

        // Verify data is accessible before restart
        assertNotNull(engine1.get(key0), "Value0 should be found before restart");
        assertNotNull(engine1.get(key1), "Value1 should be found before restart");
        assertNotNull(engine1.get(key2), "Value2 should be found before restart");

        engine1.close();

        // Now start a new engine instance - should discover existing segments
        StorageEngine engine2 = new StorageEngine(tempDir, arena);
        engine2.start();

        // Verify data is still accessible after restart (through discovery)
        assertNotNull(engine2.get(key0), "Value0 should be found after restart");
        assertNotNull(engine2.get(key1), "Value1 should be found after restart");
        assertNotNull(engine2.get(key2), "Value2 should be found after restart");

        engine2.close();
    }

    @Test
    void storageEngine_start_shouldRecoverDataAfterRestart() throws IOException {
        // First run: write data using a separate arena
        byte[] key1 = "persistentKey1".getBytes();
        byte[] value1 = "persistentValue1".getBytes();
        byte[] key2 = "persistentKey2".getBytes();
        byte[] value2 = "persistentValue2".getBytes();

        try (Arena arena1 = Arena.ofShared()) {
            StorageEngine engine1 = new StorageEngine(tempDir, arena1);
            engine1.start();

            engine1.put(key1, value1);
            engine1.put(key2, value2);
            engine1.flush();

            // Verify data was written
            assertNotNull(engine1.get(key1), "Key1 should be found before restart");
            assertNotNull(engine1.get(key2), "Key2 should be found before restart");

            // Close engine (simulating shutdown)
            engine1.close();
        }

        // Second run: verify data persists with a new arena
        try (Arena arena2 = Arena.ofShared()) {
            StorageEngine engine2 = new StorageEngine(tempDir, arena2);
            engine2.start();

            // Verify data is still accessible
            java.lang.foreign.MemorySegment retrieved1 = engine2.get(key1);
            assertNotNull(retrieved1, "Key1 should be found after restart");

            java.lang.foreign.MemorySegment retrieved2 = engine2.get(key2);
            assertNotNull(retrieved2, "Key2 should be found after restart");

            engine2.close();
        }
    }

    @Test
    void storageEngine_start_shouldSetCorrectLifecycleState() throws IOException {
        // Create segment with data
        Path segmentPath = tempDir.resolve("segment_0.dat");
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        segment.put("key".getBytes(), "value".getBytes());
        segment.flush();
        segment.close();

        // Start engine and verify lifecycle state
        StorageEngine engine = new StorageEngine(tempDir, arena);
        engine.start();

        LifecycleState state = engine.getSegmentLifecycleState(0);
        // Segments with data should be ACTIVE (queryable)
        assertNotNull(state);
        assertTrue(state == LifecycleState.ACTIVE || state == LifecycleState.INDEXING,
            "Segment with data should be in ACTIVE or INDEXING state, was: " + state);

        engine.close();
    }

    @Test
    void storageEngine_start_shouldPreAllocateMissingSegments() throws IOException {
        // Create only segment 5 (leaving 0-4 missing)
        Path segmentPath = tempDir.resolve("segment_5.dat");
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        segment.flush();
        segment.close();

        // Start engine
        StorageEngine engine = new StorageEngine(tempDir, arena);
        engine.start();

        // Should have discovered segment 5 AND pre-allocated segments 0-63
        assertTrue(engine.getSegmentCount() >= 64,
            "Engine should have at least 64 segments after start (discovered + pre-allocated)");

        engine.close();
    }

    // ==================== Helper Methods ====================

    private void createTestSegmentFile(Path path, long size) throws IOException {
        try (var out = Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int toWrite = (int) Math.min(buffer.length, remaining);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
            }
        }
    }
}
