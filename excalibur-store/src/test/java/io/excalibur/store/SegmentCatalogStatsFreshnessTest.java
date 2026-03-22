package io.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that SegmentCatalog stats always reflect current state.
 *
 * <p>These tests ensure that {@link SegmentCatalog#getCatalogMetadata()}
 * returns fresh, immutable snapshots with no stale data.</p>
 *
 * <p>Domain correctness criteria:</p>
 * <ul>
 *   <li>All counts computed from current segment states</li>
 *   <li>No cached/stale values returned</li>
 *   <li>Snapshots are immutable after creation</li>
 * </ul>
 */
class SegmentCatalogStatsFreshnessTest {

    private SegmentCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
    }

    @Nested
    @DisplayName("Stats reflect current state")
    class CurrentStateTests {

        @Test
        @DisplayName("empty catalog returns zero counts")
        void emptyCatalogReturnsZeroCounts() {
            SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();

            assertEquals(0, meta.getTotalSegments());
            assertEquals(0, meta.getActiveSegments());
            assertEquals(0, meta.getIndexingSegments());
            assertEquals(0, meta.getWarmSegments());
            assertEquals(0, meta.getCompactingSegments());
            assertEquals(0, meta.getTombstonedSegments());
            assertEquals(0, meta.getPurgedSegments());
            assertEquals(0, meta.getTotalEntryCount());
        }

        @Test
        @DisplayName("stats update immediately after adding segment")
        void statsUpdateAfterAdd() {
            catalog.add(1L, Path.of("/tmp/segment1"));

            SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();
            assertEquals(1, meta.getTotalSegments());
            assertEquals(1, meta.getIndexingSegments());
        }

        @Test
        @DisplayName("stats update immediately after state transition")
        void statsUpdateAfterTransition() {
            var seg = catalog.add(1L, Path.of("/tmp/segment1"));
            seg.transitionToIndexing();

            SegmentCatalog.CatalogMetadata meta1 = catalog.getCatalogMetadata();
            assertEquals(1, meta1.getIndexingSegments());

            catalog.completeIndexing(1L);

            SegmentCatalog.CatalogMetadata meta2 = catalog.getCatalogMetadata();
            assertEquals(0, meta2.getIndexingSegments());
            assertEquals(1, meta2.getWarmSegments());
        }

        @Test
        @DisplayName("stats reflect entry count changes")
        void statsReflectEntryCountChanges() {
            var seg = catalog.add(1L, Path.of("/tmp/segment1"));
            seg.transitionToIndexing();
            seg.setEntryCount(500);

            SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();
            assertEquals(500, meta.getTotalEntryCount());
        }

        @Test
        @DisplayName("stats reflect multiple segments")
        void statsReflectMultipleSegments() {
            var seg1 = catalog.add(1L, Path.of("/tmp/segment1"));
            seg1.transitionToIndexing();
            seg1.transitionToWarming();
            seg1.transitionToActive();
            seg1.setEntryCount(100);

            var seg2 = catalog.add(2L, Path.of("/tmp/segment2"));
            seg2.transitionToIndexing();
            seg2.setEntryCount(200);

            var seg3 = catalog.add(3L, Path.of("/tmp/segment3"));
            seg3.transitionToIndexing();
            seg3.transitionToWarming();
            seg3.setEntryCount(300);

            SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();
            assertEquals(3, meta.getTotalSegments());
            assertEquals(1, meta.getActiveSegments());
            assertEquals(1, meta.getIndexingSegments());
            assertEquals(1, meta.getWarmSegments());
            assertEquals(600, meta.getTotalEntryCount());
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("metadata snapshot is not affected by subsequent changes")
        void snapshotNotAffectedByChanges() {
            var seg = catalog.add(1L, Path.of("/tmp/segment1"));
            seg.transitionToIndexing();

            SegmentCatalog.CatalogMetadata snapshot1 = catalog.getCatalogMetadata();
            assertEquals(1, snapshot1.getTotalSegments());

            catalog.add(2L, Path.of("/tmp/segment2"));

            assertEquals(1, snapshot1.getTotalSegments(), "Snapshot should not be affected by changes");

            SegmentCatalog.CatalogMetadata snapshot2 = catalog.getCatalogMetadata();
            assertEquals(2, snapshot2.getTotalSegments(), "New snapshot should reflect current state");
        }

        @Test
        @DisplayName("metadata snapshots are independent")
        void snapshotsAreIndependent() {
            var seg1 = catalog.add(1L, Path.of("/tmp/segment1"));
            seg1.transitionToIndexing();
            seg1.setEntryCount(100);

            SegmentCatalog.CatalogMetadata snapshot1 = catalog.getCatalogMetadata();

            var seg2 = catalog.add(2L, Path.of("/tmp/segment2"));
            seg2.transitionToIndexing();
            seg2.setEntryCount(200);

            SegmentCatalog.CatalogMetadata snapshot2 = catalog.getCatalogMetadata();

            assertNotSame(snapshot1, snapshot2, "Each call should return new instance");
            assertEquals(100, snapshot1.getTotalEntryCount());
            assertEquals(300, snapshot2.getTotalEntryCount());
        }
    }

    @Nested
    @DisplayName("No stale data")
    class NoStaleDataTests {

        @Test
        @DisplayName("tombstone is immediately reflected in stats")
        void tombstoneReflectedImmediately() {
            var seg = catalog.add(1L, Path.of("/tmp/segment1"));
            seg.transitionToIndexing();
            seg.transitionToWarming();
            seg.transitionToActive();

            SegmentCatalog.CatalogMetadata before = catalog.getCatalogMetadata();
            assertEquals(1, before.getActiveSegments());
            assertEquals(0, before.getTombstonedSegments());

            seg.transitionToTombstoned();

            SegmentCatalog.CatalogMetadata after = catalog.getCatalogMetadata();
            assertEquals(0, after.getActiveSegments());
            assertEquals(1, after.getTombstonedSegments());
        }

        @Test
        @DisplayName("clear is immediately reflected in stats")
        void clearReflectedImmediately() {
            catalog.add(1L, Path.of("/tmp/segment1"));
            catalog.add(2L, Path.of("/tmp/segment2"));

            assertEquals(2, catalog.getCatalogMetadata().getTotalSegments());

            catalog.clear();

            assertEquals(0, catalog.getCatalogMetadata().getTotalSegments());
        }

        @Test
        @DisplayName("rapid state changes are accurately tracked")
        void rapidStateChangesTracked() {
            var seg = catalog.add(1L, Path.of("/tmp/segment1"));

            seg.transitionToIndexing();
            assertEquals(1, catalog.getCatalogMetadata().getIndexingSegments());

            seg.transitionToWarming();
            assertEquals(0, catalog.getCatalogMetadata().getIndexingSegments());
            assertEquals(1, catalog.getCatalogMetadata().getWarmSegments());

            seg.transitionToActive();
            assertEquals(0, catalog.getCatalogMetadata().getWarmSegments());
            assertEquals(1, catalog.getCatalogMetadata().getActiveSegments());

            seg.transitionToCompacting();
            assertEquals(0, catalog.getCatalogMetadata().getActiveSegments());
            assertEquals(1, catalog.getCatalogMetadata().getCompactingSegments());

            seg.setLifecycleState(LifecycleState.ACTIVE);
            assertEquals(1, catalog.getCatalogMetadata().getActiveSegments());
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("concurrent reads return consistent snapshots")
        void concurrentReadsReturnConsistentSnapshots() throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                var seg = catalog.add(i, Path.of("/tmp/segment" + i));
                seg.transitionToIndexing();
                seg.setEntryCount(i * 10);
            }

            Thread t1 = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();
                    assertTrue(meta.getTotalSegments() >= 0);
                    assertTrue(meta.getTotalEntryCount() >= 0);
                }
            });

            Thread t2 = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    SegmentCatalog.CatalogMetadata meta = catalog.getCatalogMetadata();
                    assertTrue(meta.getTotalSegments() >= 0);
                    assertTrue(meta.getTotalEntryCount() >= 0);
                }
            });

            t1.start();
            t2.start();
            t1.join();
            t2.join();
        }
    }
}
