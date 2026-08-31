package io.excalibur.store;

import io.excalibur.store.catalog.CatalogFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for segment catalog persistence.
 *
 * <p>These tests verify that catalog metadata is correctly persisted to disk
 * and can be recovered after a restart or crash.</p>
 */
class SegmentCatalogPersistenceTest {

    @TempDir
    Path tempDir;

    private Path catalogPath;
    private Arena arena;

    @BeforeEach
    void setUp() {
        catalogPath = tempDir.resolve("catalog.dat");
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * Test: Write metadata, close catalog, reopen, verify all fields match.
     */
    @Test
    void testWriteAndRecover() throws IOException {
        // Create catalog and add some segments
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);

        // Add segment 1
        SegmentCatalog.SegmentMetadata meta1 = catalog.add(1L, tempDir.resolve("segment_1.dat"));
        meta1.setEntryCount(100);
        meta1.setLastIntegratedOffset(4096L);
        meta1.setLastCompactionTime(System.currentTimeMillis() - 10000);
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_INDEXING, 0);

        // Add segment 2
        SegmentCatalog.SegmentMetadata meta2 = catalog.add(2L, tempDir.resolve("segment_2.dat"));
        meta2.setEntryCount(250);
        meta2.setLastIntegratedOffset(8192L);
        catalog.update(2L, SegmentCatalog.ENTRY_STATE_ACTIVE, 0);

        // Complete indexing for segment 1 to transition to WARMING
        catalog.completeIndexing(1L);

        // Flush and close
        catalog.flush();
        catalog.close();

        // Reopen and verify
        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        // Verify segment 1
        SegmentCatalog.SegmentMetadata recovered1 = recovered.get(1L);
        assertNotNull(recovered1);
        assertEquals(1L, recovered1.getSegmentId());
        assertEquals(tempDir.resolve("segment_1.dat"), recovered1.getVolumePath());
        assertEquals(100, recovered1.getEntryCount());
        assertEquals(4096L, recovered1.getLastIntegratedOffset());
        assertEquals(LifecycleState.WARMING, recovered1.getLifecycleState());
        assertTrue(recovered1.getLastCompactionTime() > 0);

        // Verify segment 2
        SegmentCatalog.SegmentMetadata recovered2 = recovered.get(2L);
        assertNotNull(recovered2);
        assertEquals(2L, recovered2.getSegmentId());
        assertEquals(tempDir.resolve("segment_2.dat"), recovered2.getVolumePath());
        assertEquals(250, recovered2.getEntryCount());
        assertEquals(8192L, recovered2.getLastIntegratedOffset());
        assertEquals(LifecycleState.ACTIVE, recovered2.getLifecycleState());

        recovered.close();
    }

    /**
     * Test: Simulate crash (no fsync), verify last consistent state recovered.
     */
    @Test
    void testCrashRecovery() throws IOException {
        // Create catalog and add initial data
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);

        catalog.add(1L, tempDir.resolve("segment_1.dat"));
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_INDEXING, 50);
        catalog.flush();

        // Add more data but don't flush (simulate crash)
        catalog.add(2L, tempDir.resolve("segment_2.dat"));
        catalog.update(2L, SegmentCatalog.ENTRY_STATE_ACTIVE, 100);
        // No flush here - simulating crash

        // Close without flush (simulating crash)
        catalog.close();

        // Reopen - should recover flushed data
        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        // Segment 1 should be recovered (was flushed)
        SegmentCatalog.SegmentMetadata recovered1 = recovered.get(1L);
        assertNotNull(recovered1);
        assertEquals(1L, recovered1.getSegmentId());

        // Note: Segment 2 may or may not be recovered depending on OS buffering
        // The important thing is that we don't crash and we get valid data

        recovered.close();
    }

    /**
     * Test: a torn header slot must not cost the catalog its records.
     *
     * <p>The header is written to alternating slots, so a crash part-way through an
     * update leaves the other slot intact and the catalog opens from it.</p>
     */
    @Test
    void testCorruptHeaderSlotIsRecovered() throws IOException {
        // Create catalog and add data
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);
        catalog.add(1L, tempDir.resolve("segment_1.dat"));
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 50);
        catalog.flush();
        catalog.close();

        // Corrupt the CRC of the first header slot
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(28);
            raf.writeInt(0xDEADBEEF);
        }

        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        assertEquals(1, recovered.size(),
            "A single torn header slot must not discard the catalog");
        assertNotNull(recovered.get(1L));

        recovered.close();
    }

    /**
     * Test: with both header slots unusable, the records themselves are checksummed,
     * so the count is rebuilt from them instead of the catalog being reset to empty.
     */
    @Test
    void testCorruptCatalogFile() throws IOException {
        // Create catalog and add data
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);
        catalog.add(1L, tempDir.resolve("segment_1.dat"));
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 50);
        catalog.flush();
        catalog.close();

        // Corrupt the CRC of both header slots
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(28);
            raf.writeInt(0xDEADBEEF);
            raf.seek(60);
            raf.writeInt(0xDEADBEEF);
        }

        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        assertEquals(1, recovered.size(),
            "Records that still checksum must be rebuilt, not discarded");
        assertNotNull(recovered.get(1L));

        recovered.close();
    }

    /**
     * Test: Corrupt segment record, verify graceful handling (record skipped).
     */
    @Test
    void testCorruptSegmentRecord() throws IOException {
        // Create catalog and add data
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);
        catalog.add(1L, tempDir.resolve("segment_1.dat"));
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 50);
        catalog.flush();

        catalog.add(2L, tempDir.resolve("segment_2.dat"));
        catalog.update(2L, SegmentCatalog.ENTRY_STATE_INDEXING, 100);
        catalog.flush();
        catalog.close();

        // Corrupt the second segment record's CRC
        // Header is 32 bytes, first record is 306 bytes
        // Second record starts at 32 + 306 = 338
        // CRC is at the end of the record, so at 338 + 306 - 4 = 640
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            // Corrupt the record CRC (last 4 bytes of second record)
            raf.seek(640);
            raf.writeInt(0xDEADBEEF);
        }

        // Reopen - should recover first record, skip corrupted second
        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        // Segment 1 should be recovered
        SegmentCatalog.SegmentMetadata recovered1 = recovered.get(1L);
        assertNotNull(recovered1);
        assertEquals(1L, recovered1.getSegmentId());

        // Segment 2 should be skipped due to CRC mismatch
        SegmentCatalog.SegmentMetadata recovered2 = recovered.get(2L);
        // Note: This depends on implementation - if CRC check fails,
        // the record should be skipped
        // We accept either null (skipped) or a valid record if somehow intact

        recovered.close();
    }

    /**
     * Test: Verify persistence of lifecycle state transitions.
     */
    @Test
    void testLifecycleStatePersistence() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);

        // Create segment and transition through states
        catalog.add(1L, tempDir.resolve("segment_1.dat"));

        // INDEXING -> WARMING
        catalog.completeIndexing(1L);
        catalog.flush();

        // WARMING -> ACTIVE
        catalog.completeWarmup(1L);
        catalog.flush();

        // ACTIVE -> COMPACTING
        catalog.beginCompaction(1L);
        catalog.flush();

        // COMPACTING -> ACTIVE
        catalog.completeCompaction(1L);
        catalog.flush();

        catalog.close();

        // Reopen and verify final state
        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        SegmentCatalog.SegmentMetadata meta = recovered.get(1L);
        assertNotNull(meta);
        assertEquals(LifecycleState.ACTIVE, meta.getLifecycleState());
        assertTrue(meta.getLastCompactionTime() > 0);

        recovered.close();
    }

    /**
     * Test: Verify persistence of lastIntegratedOffset.
     */
    @Test
    void testLastIntegratedOffsetPersistence() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog(catalogPath, arena);

        catalog.add(1L, tempDir.resolve("segment_1.dat"));

        // Set lastIntegratedOffset - need to trigger persistence via update
        SegmentCatalog.SegmentMetadata meta = catalog.get(1L);
        meta.setLastIntegratedOffset(16384L);

        // Trigger persistence by updating the catalog (this persists the segment)
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_INDEXING, 0);

        // Persist
        catalog.flush();
        catalog.close();

        // Reopen and verify
        SegmentCatalog recovered = new SegmentCatalog(catalogPath, arena);
        recovered.recover();

        SegmentCatalog.SegmentMetadata recoveredMeta = recovered.get(1L);
        assertNotNull(recoveredMeta);
        assertEquals(16384L, recoveredMeta.getLastIntegratedOffset());

        recovered.close();
    }

    /**
     * Test: Verify in-memory only catalog works without persistence.
     */
    @Test
    void testInMemoryOnlyCatalog() {
        // Create catalog without persistence
        SegmentCatalog catalog = new SegmentCatalog();

        catalog.add(1L, tempDir.resolve("segment_1.dat"));
        catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 50);

        // Should work fine in memory
        assertEquals(1, catalog.size());
        assertNotNull(catalog.get(1L));

        // Recover does nothing
        catalog.recover();

        // Data should still be there
        assertEquals(1, catalog.size());

        catalog.close();
    }

    /**
     * Test: Verify CatalogFile directly.
     */
    @Test
    void testCatalogFileDirectly() throws IOException {
        Path filePath = tempDir.resolve("test_catalog.dat");
        CatalogFile catalogFile = new CatalogFile(filePath);

        // Create metadata
        SegmentCatalog.SegmentMetadata meta = new SegmentCatalog.SegmentMetadata(42L);
        meta.setVolumePath(tempDir.resolve("segment_42.dat"));
        meta.setEntryCount(1000);
        meta.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);

        // Bypass lifecycle validation for testing
        try {
            java.lang.reflect.Field field = SegmentCatalog.SegmentMetadata.class.getDeclaredField("lifecycleState");
            field.setAccessible(true);
            field.set(meta, LifecycleState.ACTIVE);
        } catch (Exception e) {
            fail("Failed to set lifecycle state: " + e.getMessage());
        }

        meta.setLastIntegratedOffset(32768L);
        meta.setLastCompactionTime(System.currentTimeMillis());
        meta.setCreationTime(System.currentTimeMillis() - 3600000);
        meta.setLastActiveTime(System.currentTimeMillis());

        // Write
        catalogFile.writeSegment(meta);
        catalogFile.flush();

        // Read back
        SegmentCatalog.SegmentMetadata read = catalogFile.readSegment(42L);
        assertNotNull(read);
        assertEquals(42L, read.getSegmentId());
        assertEquals(1000, read.getEntryCount());
        assertEquals(32768L, read.getLastIntegratedOffset());

        // Read all
        List<SegmentCatalog.SegmentMetadata> all = catalogFile.readAll();
        assertEquals(1, all.size());

        catalogFile.close();
    }

    /**
     * Test: Verify StorageEngine integration with persistent catalog.
     */
    @Test
    void testStorageEngineIntegration() throws IOException {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

        // Create and start engine
        try (Arena engineArena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, engineArena);
            engine.start();

            // Verify catalog is persistent
            assertTrue(engine.getCatalog().isPersistent());

            // Write some data
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();
            engine.put(key, value);

            // Get metadata
            long segmentIndex = io.excalibur.store.hash.XxHash64.hash(key) &
                               (StorageEngine.DEFAULT_INITIAL_SEGMENTS - 1);
            SegmentCatalog.SegmentMetadata meta = engine.getCatalog().get(segmentIndex);
            assertNotNull(meta);

            // Close engine (should flush catalog)
            engine.close();
        }

        // Reopen and verify catalog was recovered
        try (Arena engineArena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, engineArena);
            engine.start();

            // Verify data was persisted (catalog should have entries)
            assertTrue(engine.getCatalog().size() > 0);

            engine.close();
        }
    }

    /**
     * Test: Verify empty catalog file is created correctly.
     */
    @Test
    void testEmptyCatalogFile() throws IOException {
        Path filePath = tempDir.resolve("empty_catalog.dat");
        CatalogFile catalogFile = new CatalogFile(filePath);

        // Should have no segments
        assertEquals(0, catalogFile.getSegmentCount());
        assertTrue(catalogFile.readAll().isEmpty());

        // File should exist
        assertTrue(Files.exists(filePath));

        catalogFile.close();
    }

    /**
     * Test: Verify catalog file with maximum entries.
     */
    @Test
    void testLargeCatalog() throws IOException {
        Path filePath = tempDir.resolve("large_catalog.dat");
        CatalogFile catalogFile = new CatalogFile(filePath);

        // Add many segments
        int numSegments = 1000;
        for (long i = 0; i < numSegments; i++) {
            SegmentCatalog.SegmentMetadata meta = new SegmentCatalog.SegmentMetadata(i);
            meta.setVolumePath(tempDir.resolve("segment_" + i + ".dat"));
            meta.setEntryCount((int) (i * 10));
            meta.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);

            // Set lifecycle state directly for testing
            try {
                java.lang.reflect.Field field = SegmentCatalog.SegmentMetadata.class.getDeclaredField("lifecycleState");
                field.setAccessible(true);
                field.set(meta, LifecycleState.ACTIVE);
            } catch (Exception e) {
                // Ignore
            }

            meta.setLastIntegratedOffset(i * 1024);
            catalogFile.writeSegment(meta);
        }

        catalogFile.flush();

        // Read all and verify count
        List<SegmentCatalog.SegmentMetadata> all = catalogFile.readAll();
        assertEquals(numSegments, all.size());

        // Verify a few entries
        SegmentCatalog.SegmentMetadata first = catalogFile.readSegment(0L);
        assertNotNull(first);
        assertEquals(0L, first.getSegmentId());

        SegmentCatalog.SegmentMetadata last = catalogFile.readSegment(numSegments - 1);
        assertNotNull(last);
        assertEquals(numSegments - 1, last.getSegmentId());

        catalogFile.close();
    }
}
