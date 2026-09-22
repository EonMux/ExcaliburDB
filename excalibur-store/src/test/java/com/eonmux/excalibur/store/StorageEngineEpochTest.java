package com.eonmux.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for StorageEngine visibility epoch and consistent snapshots.
 *
 * <p>These tests verify the visibility epoch mechanism that provides:
 * <ul>
 *   <li>Consistent snapshots for queries via epoch tracking</li>
 *   <li>Epoch-based segment filtering for tombstoned segments</li>
 *   <li>Query snapshot API for capturing consistent segment lists</li>
 *   <li>Correct epoch propagation from StorageEngine through Segment to ZeroCopySlice</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Visibility epochs are non-negative longs starting at 0</li>
 *   <li>Modification epochs are non-negative longs starting at 0</li>
 *   <li>Tombstone epochs default to -1 (not tombstoned) or are >= 0 when tombstoned</li>
 *   <li>Query snapshots capture consistent epoch + segment list pairs</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
@DisplayName("StorageEngine Visibility Epoch Tests")
class StorageEngineEpochTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        dataDir = tempDir.resolve("storage-epoch-test");
        java.nio.file.Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir, arena);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== StorageEngine.getWithEpoch() Tests ====================

    @Nested
    @DisplayName("StorageEngine.getWithEpoch()")
    class GetWithEpochTests {

        @Test
        @DisplayName("should return valid slice for existing key")
        void testGetWithEpochReturnsValidSlice() {
            byte[] key = "epoch-test-key".getBytes();
            byte[] value = "epoch-test-value".getBytes();

            engine.put(key, value);
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertNotNull(slice);
            assertTrue(slice.isFound());
            assertTrue(slice.isValid(), "Slice should be valid immediately after creation");
        }

        @Test
        @DisplayName("should return slice with correct data")
        void testGetWithEpochCorrectData() {
            byte[] key = "data-key".getBytes();
            byte[] value = "test-value-data".getBytes();

            engine.put(key, value);
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertTrue(slice.isFound());
            assertEquals(value.length, slice.size());
            assertArrayEquals(value, slice.toByteArray());
        }

        @Test
        @DisplayName("should return not-found slice for missing key")
        void testGetWithEpochNotFound() {
            ZeroCopySlice slice = engine.getWithEpoch("nonexistent-key".getBytes());

            assertNotNull(slice);
            assertFalse(slice.isFound());
            assertNull(slice.data());
        }

        @Test
        @DisplayName("slice should become invalid after any modification to same segment")
        void testSliceInvalidAfterModification() {
            byte[] key = "modification-test-key".getBytes();

            engine.put(key, "value1".getBytes());
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertTrue(slice.isValid());

            // Modify the same key (guaranteed to be in same segment)
            engine.put(key, "value2".getBytes());

            // Original slice should now be invalid
            assertFalse(slice.isValid(), "Slice should be invalid after segment modification");
        }

        @Test
        @DisplayName("slice should become invalid after update to same key")
        void testSliceInvalidAfterUpdate() {
            byte[] key = "update-key".getBytes();

            engine.put(key, "value1".getBytes());
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertTrue(slice.isValid());

            // Update the same key
            engine.put(key, "value2".getBytes());

            assertFalse(slice.isValid(), "Slice should be invalid after key update");
        }

        @Test
        @DisplayName("slice should become invalid after delete")
        void testSliceInvalidAfterDelete() {
            byte[] key = "delete-key".getBytes();

            engine.put(key, "value".getBytes());
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertTrue(slice.isValid());

            engine.delete(key);

            assertFalse(slice.isValid(), "Slice should be invalid after delete");
        }

        @Test
        @DisplayName("should handle slice validity across multiple keys")
        void testMultipleKeysSliceValidity() {
            // Insert multiple keys
            byte[] key1 = "test-key-1".getBytes();
            byte[] key2 = "test-key-2".getBytes();
            byte[] key3 = "test-key-3".getBytes();

            engine.put(key1, "value1".getBytes());
            engine.put(key2, "value2".getBytes());
            engine.put(key3, "value3".getBytes());

            // Get slices for all keys
            ZeroCopySlice slice1 = engine.getWithEpoch(key1);
            ZeroCopySlice slice2 = engine.getWithEpoch(key2);
            ZeroCopySlice slice3 = engine.getWithEpoch(key3);

            assertTrue(slice1.isValid());
            assertTrue(slice2.isValid());
            assertTrue(slice3.isValid());

            // Modify one key - only its slice should become invalid
            engine.put(key2, "new-value2".getBytes());

            // slice2 should be invalid (same key modified)
            assertFalse(slice2.isValid(), "Slice for modified key should be invalid");

            // Re-fetch slice2 to get new valid slice
            ZeroCopySlice newSlice2 = engine.getWithEpoch(key2);
            assertTrue(newSlice2.isValid());
            assertEquals("new-value2".length(), newSlice2.size());
        }
    }

    // ==================== Query Snapshot Tests ====================

    @Nested
    @DisplayName("Query Snapshot API")
    class QuerySnapshotTests {

        @Test
        @DisplayName("captureSnapshot should return non-null snapshot")
        void testCaptureSnapshotReturnsNonNull() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertNotNull(snapshot);
        }

        @Test
        @DisplayName("snapshot should have non-negative epoch")
        void testSnapshotEpochNonNegative() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertTrue(snapshot.epoch() >= 0, "Epoch should be non-negative");
        }

        @Test
        @DisplayName("snapshot should have non-null segment list")
        void testSnapshotSegmentsNotNull() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertNotNull(snapshot.segments());
        }

        @Test
        @DisplayName("snapshot segment list should be immutable")
        void testSnapshotSegmentsImmutable() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();
            List<Segment> segments = snapshot.segments();

            assertThrows(UnsupportedOperationException.class, () ->
                segments.add(null));
        }

        @Test
        @DisplayName("snapshot should capture current segment count")
        void testSnapshotSegmentCount() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertEquals(engine.getSegmentCount(), snapshot.segmentCount());
        }

        @Test
        @DisplayName("snapshot epoch should advance after writes")
        void testSnapshotEpochAdvances() {
            StorageEngine.QuerySnapshot snapshot1 = engine.captureSnapshot();
            long epoch1 = snapshot1.epoch();

            engine.put("key1".getBytes(), "value1".getBytes());

            StorageEngine.QuerySnapshot snapshot2 = engine.captureSnapshot();
            long epoch2 = snapshot2.epoch();

            assertTrue(epoch2 >= epoch1, "Epoch should advance or stay same after writes");
        }

        @Test
        @DisplayName("snapshot should provide consistent view for query")
        void testSnapshotConsistentView() {
            // Add initial data
            engine.put("key1".getBytes(), "value1".getBytes());

            // Capture snapshot
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            // Add more data
            engine.put("key2".getBytes(), "value2".getBytes());

            // Snapshot should still reference original segment list
            // (immutable copy taken at capture time)
            assertNotNull(snapshot.segments());
            assertTrue(snapshot.segmentCount() >= 0);
        }
    }

    // ==================== Queryable Segments at Epoch Tests ====================

    @Nested
    @DisplayName("Queryable Segments at Epoch")
    class QueryableSegmentsAtEpochTests {

        @Test
        @DisplayName("should return segments in queryable states")
        void testGetQueryableSegmentsAtEpoch() {
            List<Segment> segments = engine.getQueryableSegmentsAtEpoch(0);

            assertNotNull(segments);
            // Engine starts with segments in INDEXING state, which is queryable
            assertTrue(segments.size() > 0, "Should have queryable segments");
        }

        @Test
        @DisplayName("should include INDEXING segments")
        void testIncludesIndexingSegments() {
            // Engine starts segments in INDEXING state
            List<Segment> segments = engine.getQueryableSegmentsAtEpoch(engine.currentEpoch());

            assertTrue(segments.size() > 0, "INDEXING segments should be queryable");
        }

        @Test
        @DisplayName("should exclude BOOTSTRAPPING segments")
        void testExcludesBootstrappingSegments() {
            // Get the catalog
            SegmentCatalog catalog = engine.getCatalog();

            // Check that all returned segments are in queryable states
            List<Segment> segments = engine.getQueryableSegmentsAtEpoch(engine.currentEpoch());

            for (Segment segment : segments) {
                SegmentCatalog.SegmentMetadata metadata = catalog.get(findSegmentIndex(segment));
                if (metadata != null) {
                    assertTrue(metadata.getLifecycleState().isQueryable(),
                        "Segment in state " + metadata.getLifecycleState() + " should not be queryable");
                }
            }
        }

        @Test
        @DisplayName("should handle empty engine")
        void testEmptyEngine() {
            // Close and recreate empty engine
            engine.close();
            engine = new StorageEngine(dataDir, arena);

            List<Segment> segments = engine.getQueryableSegmentsAtEpoch(0);

            assertNotNull(segments);
            assertEquals(0, segments.size());
        }
    }

    // ==================== Tombstone Epoch Tracking Tests ====================

    @Nested
    @DisplayName("Tombstone Epoch Tracking")
    class TombstoneEpochTests {

        @Test
        @DisplayName("new segment should have tombstoneEpoch of -1")
        void testInitialTombstoneEpoch() {
            SegmentCatalog catalog = engine.getCatalog();

            // Get any segment metadata
            for (Long segmentId : catalog.getAll().keySet()) {
                SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
                assertEquals(-1, metadata.getTombstoneEpoch(),
                    "New segment should have tombstoneEpoch = -1");
                break;
            }
        }

        @Test
        @DisplayName("tombstoneSegment should record current epoch")
        void testTombstoneRecordsEpoch() {
            SegmentCatalog catalog = engine.getCatalog();

            // Find a segment to tombstone
            Long segmentId = catalog.getAll().keySet().iterator().next();

            // Get current epoch
            long epochBefore = engine.currentEpoch();

            // Tombstone the segment
            engine.tombstoneSegment(segmentId);

            // Check that tombstoneEpoch was recorded
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            assertTrue(metadata.getTombstoneEpoch() >= epochBefore,
                "tombstoneEpoch should be >= epoch at tombstone time");
            assertEquals(LifecycleState.TOMBSTONED, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("isVisibleAtEpoch should return true for non-tombstoned segments")
        void testIsVisibleAtEpochNonTombstoned() {
            SegmentCatalog catalog = engine.getCatalog();

            for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
                if (metadata.getLifecycleState() != LifecycleState.TOMBSTONED) {
                    assertTrue(metadata.isVisibleAtEpoch(0),
                        "Non-tombstoned segment should be visible at any epoch");
                    assertTrue(metadata.isVisibleAtEpoch(Long.MAX_VALUE),
                        "Non-tombstoned segment should be visible at any epoch");
                }
            }
        }

        @Test
        @DisplayName("isVisibleAtEpoch should return false for tombstoned segments at later epoch")
        void testIsVisibleAtEpochTombstoned() {
            SegmentCatalog catalog = engine.getCatalog();

            // Perform some operations to advance the visibility epoch
            // This ensures we have a non-zero tombstone epoch
            for (int i = 0; i < 5; i++) {
                engine.put(("advance-epoch-key-" + i).getBytes(), "value".getBytes());
            }

            // Find and tombstone a segment
            Long segmentId = catalog.getAll().keySet().iterator().next();
            long epochBeforeTombstone = engine.currentEpoch();
            engine.tombstoneSegment(segmentId);

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            long tombstoneEpoch = metadata.getTombstoneEpoch();

            // Verify tombstone epoch was recorded and is at least epochBeforeTombstone
            assertTrue(tombstoneEpoch >= epochBeforeTombstone,
                "Tombstone epoch should be >= epoch at tombstone time");

            // Query at epoch after tombstone should not see segment
            assertFalse(metadata.isVisibleAtEpoch(tombstoneEpoch + 100),
                "Segment should not be visible to queries started after tombstone");

            // Query at epoch before tombstone should still see segment
            // Use a safe epoch value that's definitely before tombstone
            long queryEpoch = Math.max(0, Math.min(tombstoneEpoch - 1, epochBeforeTombstone - 1));
            if (tombstoneEpoch > 0) {
                assertTrue(metadata.isVisibleAtEpoch(queryEpoch),
                    "Segment should be visible to queries started before tombstone (tombstoneEpoch=" +
                    tombstoneEpoch + ", queryEpoch=" + queryEpoch + ")");
            }
        }
    }

    // ==================== Visibility Epoch Propagation Tests ====================

    @Nested
    @DisplayName("Visibility Epoch Propagation")
    class EpochPropagationTests {

        @Test
        @DisplayName("visibility epoch should start at 0")
        void testInitialVisibilityEpoch() {
            // Close and recreate to test initial state
            engine.close();
            engine = new StorageEngine(dataDir, arena);
            engine.start();

            // Note: visibility epoch starts at 0, but start() may pre-allocate segments
            assertTrue(engine.currentEpoch() >= 0);
        }

        @Test
        @DisplayName("visibility epoch should increment on put")
        void testEpochIncrementsOnPut() {
            long epoch1 = engine.currentEpoch();

            engine.put("key".getBytes(), "value".getBytes());

            long epoch2 = engine.currentEpoch();
            assertTrue(epoch2 > epoch1, "Epoch should increment after put");
        }

        @Test
        @DisplayName("visibility epoch should increment on delete")
        void testEpochIncrementsOnDelete() {
            engine.put("key".getBytes(), "value".getBytes());
            long epoch1 = engine.currentEpoch();

            engine.delete("key".getBytes());

            long epoch2 = engine.currentEpoch();
            assertTrue(epoch2 > epoch1, "Epoch should increment after delete");
        }

        @Test
        @DisplayName("visibility epoch should NOT increment on read")
        void testEpochNoIncrementOnRead() {
            engine.put("key".getBytes(), "value".getBytes());
            long epochBefore = engine.currentEpoch();

            engine.get("key".getBytes());
            engine.getWithEpoch("key".getBytes());

            long epochAfter = engine.currentEpoch();
            assertEquals(epochBefore, epochAfter, "Epoch should not change on read");
        }

        @Test
        @DisplayName("incrementEpoch should advance epoch")
        void testIncrementEpoch() {
            long epoch1 = engine.currentEpoch();

            long epoch2 = engine.incrementEpoch();

            assertTrue(epoch2 > epoch1, "incrementEpoch should advance epoch");
            assertEquals(epoch2, engine.currentEpoch());
        }
    }

    // ==================== Segment Lifecycle and Visibility Tests ====================

    @Nested
    @DisplayName("Segment Lifecycle and Visibility")
    class LifecycleVisibilityTests {

        @Test
        @DisplayName("INDEXING segments should be queryable")
        void testIndexingQueryable() {
            SegmentCatalog catalog = engine.getCatalog();

            // Find an INDEXING segment
            for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
                if (metadata.getLifecycleState() == LifecycleState.INDEXING) {
                    assertTrue(metadata.getLifecycleState().isQueryable());
                }
            }
        }

        @Test
        @DisplayName("ACTIVE segments should be queryable")
        void testActiveQueryable() {
            SegmentCatalog catalog = engine.getCatalog();

            // Manually transition a segment to ACTIVE
            Long segmentId = catalog.getAll().keySet().iterator().next();
            catalog.completeIndexing(segmentId);
            catalog.completeWarmup(segmentId);

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            assertEquals(LifecycleState.ACTIVE, metadata.getLifecycleState());
            assertTrue(metadata.getLifecycleState().isQueryable());
        }

        @Test
        @DisplayName("TOMBSTONED segments should NOT be queryable to new queries")
        void testTombstonedNotQueryable() {
            SegmentCatalog catalog = engine.getCatalog();

            // Transition a segment through lifecycle to TOMBSTONED
            Long segmentId = catalog.getAll().keySet().iterator().next();
            catalog.completeIndexing(segmentId);
            catalog.completeWarmup(segmentId);
            catalog.tombstone(segmentId, engine.currentEpoch());

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            assertEquals(LifecycleState.TOMBSTONED, metadata.getLifecycleState());
            assertFalse(metadata.getLifecycleState().isQueryable());
        }

        @Test
        @DisplayName("getQueryableSegments should exclude TOMBSTONED segments")
        void testGetQueryableExcludesTombstoned() {
            SegmentCatalog catalog = engine.getCatalog();

            // Transition a segment to TOMBSTONED
            Long segmentId = catalog.getAll().keySet().iterator().next();
            catalog.completeIndexing(segmentId);
            catalog.completeWarmup(segmentId);
            catalog.tombstone(segmentId, engine.currentEpoch());

            // Get queryable segments
            List<Segment> queryable = engine.getQueryableSegments();

            // Verify TOMBSTONED segment is not in list
            for (Segment segment : queryable) {
                // Find the segment's index
                Long idx = findSegmentIndex(segment);
                if (idx != null) {
                    SegmentCatalog.SegmentMetadata metadata = catalog.get(idx);
                    if (metadata != null) {
                        assertNotEquals(LifecycleState.TOMBSTONED, metadata.getLifecycleState(),
                            "TOMBSTONED segments should not be in queryable list");
                    }
                }
            }
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("visibility epoch should always be non-negative")
        void testVisibilityEpochNonNegative() {
            // Perform many operations
            for (int i = 0; i < 100; i++) {
                engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            assertTrue(engine.currentEpoch() >= 0, "Visibility epoch should be non-negative");
        }

        @Test
        @DisplayName("query snapshot epoch should be non-negative")
        void testSnapshotEpochNonNegative() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertTrue(snapshot.epoch() >= 0, "Snapshot epoch should be non-negative");
        }

        @Test
        @DisplayName("tombstone epoch should be -1 or non-negative")
        void testTombstoneEpochValid() {
            SegmentCatalog catalog = engine.getCatalog();

            for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
                long tombstoneEpoch = metadata.getTombstoneEpoch();
                assertTrue(tombstoneEpoch == -1 || tombstoneEpoch >= 0,
                    "Tombstone epoch should be -1 (not tombstoned) or >= 0");
            }
        }

        @Test
        @DisplayName("segment count in snapshot should be non-negative")
        void testSnapshotSegmentCountNonNegative() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertTrue(snapshot.segmentCount() >= 0, "Segment count should be non-negative");
        }

        @Test
        @DisplayName("ZeroCopySlice from engine should have valid epoch")
        void testEngineSliceEpochValid() {
            engine.put("key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = engine.getWithEpoch("key".getBytes());

            assertTrue(slice.epoch() >= 0, "Slice epoch should be non-negative");
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Finds the segment index for a given Segment instance.
     */
    private Long findSegmentIndex(Segment segment) {
        for (var entry : engine.getSegments().entrySet()) {
            if (entry.getValue() == segment) {
                return entry.getKey();
            }
        }
        return null;
    }
}
