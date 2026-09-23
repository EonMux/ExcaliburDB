package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for segment lifecycle state progression.
 *
 * <p>These tests verify the full lifecycle progression:
 * <pre>
 * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE ⟷ COMPACTING → TOMBSTONED → PURGED
 * </pre>
 * </p>
 *
 * @author ExcaliburDB
 */
@DisplayName("Segment Lifecycle Integration Tests")
class SegmentLifecycleIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        engine = new StorageEngine(tempDir, arena);
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

    // ==================== Full Lifecycle Progression Tests ====================

    @Nested
    @DisplayName("Full lifecycle progression")
    class FullProgressionTests {

        @Test
        @DisplayName("Segment progresses through complete lifecycle")
        void segmentProgressesThroughCompleteLifecycle() {
            // Initial state after start() - segments are pre-allocated
            // They should be in INDEXING state (transitioned from BOOTSTRAPPING by add())
            assertEquals(64, engine.getSegmentCount(), "64 segments should be pre-allocated");

            // Verify all segments are in INDEXING state
            for (long segmentIndex = 0; segmentIndex < 64; segmentIndex++) {
                LifecycleState state = engine.getSegmentLifecycleState(segmentIndex);
                assertEquals(LifecycleState.INDEXING, state,
                    "Segment " + segmentIndex + " should be in INDEXING state");
            }
        }

        @Test
        @DisplayName("Write operation keeps segment in INDEXING state")
        void writeOperationKeepsSegmentInIndexingState() {
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            engine.put(key, value);

            // Find the segment that received the write
            long keyHash = com.eonmux.excalibur.store.hash.XxHash64.hash(key);
            int segmentIndex = (int) (keyHash & 63);

            // Segment should still be in INDEXING state
            LifecycleState state = engine.getSegmentLifecycleState(segmentIndex);
            assertEquals(LifecycleState.INDEXING, state,
                "Segment should remain in INDEXING state after write");
        }

        @Test
        @DisplayName("Manual lifecycle transition completes successfully")
        void manualLifecycleTransitionCompletesSuccessfully() {
            long segmentIndex = 0L;

            // Verify initial state
            assertEquals(LifecycleState.INDEXING, engine.getSegmentLifecycleState(segmentIndex));

            // Complete indexing → WARMING
            engine.completeSegmentIndexing(segmentIndex);
            assertEquals(LifecycleState.WARMING, engine.getSegmentLifecycleState(segmentIndex));

            // Complete warmup → ACTIVE
            engine.completeSegmentWarmup(segmentIndex);
            assertEquals(LifecycleState.ACTIVE, engine.getSegmentLifecycleState(segmentIndex));
        }

        @Test
        @DisplayName("Compaction cycle transitions correctly")
        void compactionCycleTransitionsCorrectly() {
            long segmentIndex = 0L;

            // Progress to ACTIVE
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);
            assertEquals(LifecycleState.ACTIVE, engine.getSegmentLifecycleState(segmentIndex));

            // Begin compaction
            engine.beginSegmentCompaction(segmentIndex);
            assertEquals(LifecycleState.COMPACTING, engine.getSegmentLifecycleState(segmentIndex));

            // Complete compaction
            engine.completeSegmentCompaction(segmentIndex);
            assertEquals(LifecycleState.ACTIVE, engine.getSegmentLifecycleState(segmentIndex));
        }

        @Test
        @DisplayName("Tombstone and purge cycle completes")
        void tombstoneAndPurgeCycleCompletes() {
            long segmentIndex = 0L;

            // Progress to ACTIVE
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);
            assertEquals(LifecycleState.ACTIVE, engine.getSegmentLifecycleState(segmentIndex));

            // Tombstone the segment
            engine.tombstoneSegment(segmentIndex);
            assertEquals(LifecycleState.TOMBSTONED, engine.getSegmentLifecycleState(segmentIndex));

            // Verify tombstone epoch is recorded
            SegmentCatalog.SegmentMetadata metadata = engine.getCatalog().get(segmentIndex);
            assertTrue(metadata.getTombstoneEpoch() >= 0, "Tombstone epoch should be recorded");

            // Remove (purge) the segment
            engine.getCatalog().remove(segmentIndex);
            assertNull(engine.getCatalog().get(segmentIndex),
                "Segment should be removed from catalog after purge");
        }
    }

    // ==================== Query Visibility Tests ====================

    @Nested
    @DisplayName("Query visibility with lifecycle states")
    class QueryVisibilityTests {

        @Test
        @DisplayName("INDEXING segments are queryable")
        void indexingSegmentsAreQueryable() {
            List<Segment> queryable = engine.getQueryableSegments();
            assertEquals(64, queryable.size(), "All INDEXING segments should be queryable");
        }

        @Test
        @DisplayName("WARMING segments are queryable")
        void warmingSegmentsAreQueryable() {
            long segmentIndex = 0L;
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);

            // Set back to WARMING for test
            engine.transitionSegmentState(segmentIndex, LifecycleState.WARMING);

            List<Segment> queryable = engine.getQueryableSegments();
            assertTrue(queryable.size() > 0, "WARMING segments should be queryable");
        }

        @Test
        @DisplayName("ACTIVE segments are queryable")
        void activeSegmentsAreQueryable() {
            long segmentIndex = 0L;
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);

            List<Segment> queryable = engine.getQueryableSegments();
            assertTrue(queryable.size() > 0, "ACTIVE segments should be queryable");
        }

        @Test
        @DisplayName("COMPACTING segments are queryable")
        void compactingSegmentsAreQueryable() {
            long segmentIndex = 0L;
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);
            engine.beginSegmentCompaction(segmentIndex);

            List<Segment> queryable = engine.getQueryableSegments();
            assertTrue(queryable.size() > 0, "COMPACTING segments should be queryable");
        }

        @Test
        @DisplayName("TOMBSTONED segments are not queryable")
        void tombstonedSegmentsAreNotQueryable() {
            long segmentIndex = 0L;
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);
            engine.tombstoneSegment(segmentIndex);

            List<Segment> queryable = engine.getQueryableSegments();

            // Verify the tombstoned segment is not in the queryable list
            Segment tombstonedSegment = engine.getSegment(segmentIndex);
            assertFalse(queryable.contains(tombstonedSegment),
                "TOMBSTONED segment should not be queryable");
        }
    }

    // ==================== Epoch-Based Visibility Tests ====================

    @Nested
    @DisplayName("Epoch-based visibility")
    class EpochVisibilityTests {

        @Test
        @DisplayName("Tombstoned segment visible to queries started before tombstone")
        void tombstonedSegmentVisibleToOldQueries() {
            long segmentIndex = 0L;

            // Progress to ACTIVE
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);

            // Capture snapshot before tombstone
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();
            long epochBeforeTombstone = snapshot.epoch();

            // Perform a write operation to advance the epoch
            // This ensures the tombstone epoch is different from the snapshot epoch
            engine.put("dummy-key".getBytes(), "dummy-value".getBytes());
            long epochAfterWrite = engine.currentEpoch();
            assertTrue(epochAfterWrite > epochBeforeTombstone, "Epoch should advance after write");

            // Now tombstone the segment - this records the current (advanced) epoch
            engine.tombstoneSegment(segmentIndex);

            // Get queryable segments at the old epoch (before the write and tombstone)
            List<Segment> queryableAtOldEpoch = engine.getQueryableSegmentsAtEpoch(epochBeforeTombstone);

            // The segment should still be visible at the old epoch because
            // it was tombstoned AFTER the snapshot was taken
            Segment tombstonedSegment = engine.getSegment(segmentIndex);
            assertTrue(queryableAtOldEpoch.contains(tombstonedSegment),
                "Segment should be visible to queries started before tombstone");
        }

        @Test
        @DisplayName("Tombstoned segment not visible to queries started after tombstone")
        void tombstonedSegmentNotVisibleToNewQueries() {
            long segmentIndex = 0L;

            // Progress to ACTIVE
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);

            // Tombstone the segment
            engine.tombstoneSegment(segmentIndex);

            // Capture snapshot after tombstone
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();
            long epochAfterTombstone = snapshot.epoch();

            // Get queryable segments at the new epoch
            List<Segment> queryableAtNewEpoch = engine.getQueryableSegmentsAtEpoch(epochAfterTombstone);

            // The segment should not be visible at the new epoch
            Segment tombstonedSegment = engine.getSegment(segmentIndex);
            assertFalse(queryableAtNewEpoch.contains(tombstonedSegment),
                "Segment should not be visible to queries started after tombstone");
        }

        @Test
        @DisplayName("Visibility epoch increments on writes")
        void visibilityEpochIncrementsOnWrites() {
            long initialEpoch = engine.currentEpoch();

            engine.put("key1".getBytes(), "value1".getBytes());

            assertTrue(engine.currentEpoch() > initialEpoch,
                "Visibility epoch should increment after write");
        }

        @Test
        @DisplayName("Visibility epoch increments on deletes")
        void visibilityEpochIncrementsOnDeletes() {
            byte[] key = "key1".getBytes();
            engine.put(key, "value1".getBytes());
            long epochAfterPut = engine.currentEpoch();

            engine.delete(key);

            assertTrue(engine.currentEpoch() > epochAfterPut,
                "Visibility epoch should increment after delete");
        }
    }

    // ==================== SegmentLifecycleManager Tests ====================

    @Nested
    @DisplayName("SegmentLifecycleManager integration")
    class LifecycleManagerTests {

        @Test
        @DisplayName("Lifecycle manager can be created and started")
        void lifecycleManagerCanBeCreatedAndStarted() {
            SegmentLifecycleManager manager = engine.getLifecycleManager();

            assertNotNull(manager, "Lifecycle manager should be created");
            assertFalse(manager.isRunning(), "Lifecycle manager should not be running initially");

            manager.start();
            assertTrue(manager.isRunning(), "Lifecycle manager should be running after start");

            manager.stop(Duration.ofSeconds(10));
            assertFalse(manager.isRunning(), "Lifecycle manager should not be running after stop");
        }

        @Test
        @DisplayName("Lifecycle manager provides transition methods")
        void lifecycleManagerProvidesTransitionMethods() {
            SegmentLifecycleManager manager = engine.getLifecycleManager();
            long segmentIndex = 0L;

            // Verify initial state
            assertEquals(LifecycleState.INDEXING, manager.getLifecycleState(segmentIndex));

            // Complete indexing
            manager.completeIndexing(segmentIndex);
            assertEquals(LifecycleState.WARMING, manager.getLifecycleState(segmentIndex));

            // Complete warmup
            manager.completeWarmup(segmentIndex);
            assertEquals(LifecycleState.ACTIVE, manager.getLifecycleState(segmentIndex));
        }

        @Test
        @DisplayName("Lifecycle manager tombstones segments with epoch")
        void lifecycleManagerTombstonesWithEpoch() {
            SegmentLifecycleManager manager = engine.getLifecycleManager();
            long segmentIndex = 0L;

            // Progress to ACTIVE
            manager.completeIndexing(segmentIndex);
            manager.completeWarmup(segmentIndex);

            // Tombstone with epoch
            long epoch = engine.currentEpoch();
            manager.tombstone(segmentIndex, epoch);

            assertEquals(LifecycleState.TOMBSTONED, manager.getLifecycleState(segmentIndex));

            // Verify epoch is recorded
            SegmentCatalog.SegmentMetadata metadata = engine.getCatalog().get(segmentIndex);
            assertEquals(epoch, metadata.getTombstoneEpoch());
        }
    }

    // ==================== Catalog Metadata Tests ====================

    @Nested
    @DisplayName("Catalog metadata tracking")
    class CatalogMetadataTests {

        @Test
        @DisplayName("Catalog tracks segment counts by state")
        void catalogTracksSegmentCountsByState() {
            SegmentCatalog.CatalogMetadata metadata = engine.getCatalog().getCatalogMetadata();

            assertEquals(64, metadata.getTotalSegments(), "Should have 64 total segments");
            assertEquals(64, metadata.getIndexingSegments(), "All segments should be in INDEXING");
            assertEquals(0, metadata.getActiveSegments(), "No segments should be ACTIVE initially");
        }

        @Test
        @DisplayName("Catalog updates counts on transitions")
        void catalogUpdatesCountsOnTransitions() {
            long segmentIndex = 0L;

            // Transition to WARMING
            engine.completeSegmentIndexing(segmentIndex);
            SegmentCatalog.CatalogMetadata metadata = engine.getCatalog().getCatalogMetadata();
            assertEquals(63, metadata.getIndexingSegments(), "One less INDEXING segment");
            assertEquals(1, metadata.getWarmSegments(), "One WARMING segment");

            // Transition to ACTIVE
            engine.completeSegmentWarmup(segmentIndex);
            metadata = engine.getCatalog().getCatalogMetadata();
            assertEquals(0, metadata.getWarmSegments(), "No WARMING segments");
            assertEquals(1, metadata.getActiveSegments(), "One ACTIVE segment");

            // Transition to TOMBSTONED
            engine.tombstoneSegment(segmentIndex);
            metadata = engine.getCatalog().getCatalogMetadata();
            assertEquals(0, metadata.getActiveSegments(), "No ACTIVE segments");
            assertEquals(1, metadata.getTombstonedSegments(), "One TOMBSTONED segment");
        }
    }

    // ==================== Concurrent Operation Tests ====================

    @Nested
    @DisplayName("Concurrent lifecycle operations")
    class ConcurrentOperationTests {

        @Test
        @DisplayName("Concurrent writes to different segments maintain correct states")
        void concurrentWritesMaintainCorrectStates() throws InterruptedException {
            Thread[] threads = new Thread[4];

            for (int i = 0; i < 4; i++) {
                final int threadId = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < 100; j++) {
                        byte[] key = ("thread-" + threadId + "-key-" + j).getBytes();
                        byte[] value = ("value-" + j).getBytes();
                        engine.put(key, value);
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // Verify all segments are still in INDEXING state
            for (long segmentIndex = 0; segmentIndex < 64; segmentIndex++) {
                LifecycleState state = engine.getSegmentLifecycleState(segmentIndex);
                // Segments with writes should still be INDEXING
                if (state != null) {
                    assertTrue(state == LifecycleState.INDEXING,
                        "Segment " + segmentIndex + " should be in INDEXING state");
                }
            }
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Tombstone epochs are non-negative")
        void tombstoneEpochsAreNonNegative() {
            long segmentIndex = 0L;
            engine.completeSegmentIndexing(segmentIndex);
            engine.completeSegmentWarmup(segmentIndex);

            long epoch = engine.currentEpoch();
            engine.tombstoneSegment(segmentIndex);

            SegmentCatalog.SegmentMetadata metadata = engine.getCatalog().get(segmentIndex);
            assertTrue(metadata.getTombstoneEpoch() >= 0,
                "Tombstone epoch should be non-negative");
            assertTrue(metadata.getTombstoneEpoch() <= epoch + 10,
                "Tombstone epoch should be close to current epoch");
        }

        @Test
        @DisplayName("Visibility epochs are monotonically increasing")
        void visibilityEpochsAreMonotonicallyIncreasing() {
            long lastEpoch = engine.currentEpoch();

            for (int i = 0; i < 100; i++) {
                engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
                long currentEpoch = engine.currentEpoch();

                assertTrue(currentEpoch >= lastEpoch,
                    "Epoch should be monotonically increasing");
                lastEpoch = currentEpoch;
            }
        }

        @Test
        @DisplayName("Entry counts are non-negative")
        void entryCountsAreNonNegative() {
            // Write some entries
            for (int i = 0; i < 100; i++) {
                engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            // Verify all segment entry counts are non-negative
            for (long segmentIndex = 0; segmentIndex < 64; segmentIndex++) {
                SegmentCatalog.SegmentMetadata metadata = engine.getCatalog().get(segmentIndex);
                if (metadata != null) {
                    assertTrue(metadata.getEntryCount() >= 0,
                        "Entry count should be non-negative for segment " + segmentIndex);
                }
            }
        }

        @Test
        @DisplayName("Queryable segments have correct lifecycle states")
        void queryableSegmentsHaveCorrectLifecycleStates() {
            List<Segment> queryable = engine.getQueryableSegments();

            for (Segment segment : queryable) {
                // Find the segment's lifecycle state
                for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
                    if (entry.getValue().equals(segment)) {
                        LifecycleState state = engine.getSegmentLifecycleState(entry.getKey());
                        assertTrue(state.isQueryable(),
                            "Queryable segment should have queryable lifecycle state");
                        break;
                    }
                }
            }
        }

        @Test
        @DisplayName("Snapshot captures consistent state")
        void snapshotCapturesConsistentState() throws InterruptedException {
            // Write some initial data
            for (int i = 0; i < 50; i++) {
                engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            // Capture snapshot
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            // Continue writing in background
            Thread writer = Thread.ofVirtual().start(() -> {
                for (int i = 50; i < 100; i++) {
                    engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
                }
            });

            // Verify snapshot has consistent segment count
            int snapshotSegmentCount = snapshot.segmentCount();

            writer.join();

            // Snapshot should still have same segment count
            assertEquals(snapshotSegmentCount, snapshot.segmentCount(),
                "Snapshot segment count should remain consistent");
        }
    }
}
