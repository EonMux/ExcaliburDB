package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QuerySnapshot.SnapshotStats domain correctness.
 */
@DisplayName("QuerySnapshot.SnapshotStats")
class QuerySnapshotStatsTest {

    @Nested
    @DisplayName("isDegraded threshold check")
    class IsDegradedThresholdCheck {

        @Test
        @DisplayName("isDegraded returns true when coverage below default threshold (0.5)")
        void isDegradedWhenCoverageBelowDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                3,    // queryable
                1, 1, 1, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.3   // coverage (30%)
            );

            assertThat(stats.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("isDegraded returns false when coverage at default threshold (0.5)")
        void isNotDegradedWhenCoverageAtDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                5,    // queryable
                1, 1, 3, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.5   // coverage (50%)
            );

            assertThat(stats.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("isDegraded returns false when coverage above default threshold (0.5)")
        void isNotDegradedWhenCoverageAboveDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                8,    // queryable
                2, 2, 4, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.8   // coverage (80%)
            );

            assertThat(stats.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("isDegraded with custom threshold returns correct result")
        void isDegradedWithCustomThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                6,    // queryable
                2, 2, 2, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.6   // coverage (60%)
            );

            // Below 0.7 threshold
            assertThat(stats.isDegraded(0.7)).isTrue();

            // At or above 0.5 threshold
            assertThat(stats.isDegraded(0.5)).isFalse();

            // At or above 0.6 threshold
            assertThat(stats.isDegraded(0.6)).isFalse();
        }

        @Test
        @DisplayName("isDegraded handles zero coverage")
        void isDegradedHandlesZeroCoverage() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                0,    // totalVisible
                0,    // queryable
                0, 0, 0, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.0   // coverage (0%)
            );

            assertThat(stats.isDegraded()).isTrue();
            assertThat(stats.isDegraded(0.0)).isFalse();
        }

        @Test
        @DisplayName("isDegraded handles full coverage")
        void isDegradedHandlesFullCoverage() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                10,   // queryable
                0, 0, 10, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                1.0   // coverage (100%)
            );

            assertThat(stats.isDegraded()).isFalse();
            assertThat(stats.isDegraded(0.99)).isFalse();
        }
    }

    @Nested
    @DisplayName("Record accessor methods")
    class RecordAccessorMethods {

        @Test
        @DisplayName("returns all record values correctly")
        void returnsAllRecordValuesCorrectly() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                12345L, // epoch
                100,    // totalVisible
                80,     // queryable
                20,     // indexing
                10,     // warming
                45,     // active
                5,      // compacting
                20,     // tombstoned
                0.8     // coverage
            );

            assertThat(stats.epoch()).isEqualTo(12345L);
            assertThat(stats.totalVisible()).isEqualTo(100);
            assertThat(stats.queryable()).isEqualTo(80);
            assertThat(stats.indexing()).isEqualTo(20);
            assertThat(stats.warming()).isEqualTo(10);
            assertThat(stats.active()).isEqualTo(45);
            assertThat(stats.compacting()).isEqualTo(5);
            assertThat(stats.tombstoned()).isEqualTo(20);
            assertThat(stats.coverage()).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("Domain correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("coverage can be exactly 0.0")
        void coverageCanBeZero() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 0, 0, 0, 0, 0, 0, 0, 0.0
            );
            assertThat(stats.coverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("coverage can be exactly 1.0")
        void coverageCanBeOne() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 10, 10, 0, 0, 10, 0, 0, 1.0
            );
            assertThat(stats.coverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("queryable equals sum of individual states")
        void queryableEqualsSumOfStates() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 100, 50, 15, 10, 20, 5, 0, 0.5
            );

            int expectedQueryable = stats.indexing() + stats.warming() +
                stats.active() + stats.compacting();

            assertThat(stats.queryable()).isEqualTo(expectedQueryable);
        }
    }

    // ==================== Integration Tests ====================

    /**
     * Integration tests for QuerySnapshot.capture() end-to-end functionality.
     *
     * <p>These tests verify that the QuerySnapshot class correctly integrates
     * with VisibilityTracker, SegmentCatalog, and StorageEngine to provide
     * a consistent view of visible segments for query execution.</p>
     */
    @Nested
    @DisplayName("QuerySnapshot.capture() integration")
    class QuerySnapshotCaptureIntegration {

        private SegmentCatalog catalog;
        private VisibilityTracker visibilityTracker;
        private StorageEngine storageEngine;

        @BeforeEach
        void setUp() {
            catalog = new SegmentCatalog();
            visibilityTracker = new VisibilityTracker(catalog);
            storageEngine = mock(StorageEngine.class);
            when(storageEngine.getCatalog()).thenReturn(catalog);
        }

        @Test
        @DisplayName("capture() returns non-null snapshot with correct epoch")
        void captureReturnsNonNullSnapshotWithCorrectEpoch() {
            // Register a segment in the catalog
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).setLifecycleState(LifecycleState.ACTIVE);

            // Register with visibility tracker
            visibilityTracker.registerSegment(1L, 0L);

            // Capture snapshot
            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.epoch()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("resolveVisibleSegments() returns segments consistent with VisibilityTracker")
        void resolveVisibleSegmentsReturnsConsistentWithTracker() {
            // Setup segment 1 as ACTIVE
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).setLifecycleState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(1L, 0L);

            // Setup segment 2 as ACTIVE
            catalog.add(2L, Path.of("/test/segment2"));
            catalog.get(2L).setLifecycleState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(2L, 0L);

            // Create mock segments for storage engine
            Segment mockSegment1 = mock(Segment.class);
            when(mockSegment1.segmentId()).thenReturn(1L);
            when(mockSegment1.isClosed()).thenReturn(false);

            Segment mockSegment2 = mock(Segment.class);
            when(mockSegment2.segmentId()).thenReturn(2L);
            when(mockSegment2.isClosed()).thenReturn(false);

            when(storageEngine.getSegment(1L)).thenReturn(mockSegment1);
            when(storageEngine.getSegment(2L)).thenReturn(mockSegment2);

            // Capture snapshot
            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);

            // Resolve visible segments
            List<Segment> visibleSegments = snapshot.resolveVisibleSegments();

            // Verify visible segment IDs match visibility tracker
            Set<Long> trackerVisible = visibilityTracker.visibleSegments(snapshot.epoch());
            Set<Long> snapshotVisibleIds = visibleSegments.stream()
                .map(Segment::segmentId)
                .collect(java.util.stream.Collectors.toSet());

            assertThat(snapshotVisibleIds).containsExactlyElementsOf(trackerVisible);
        }

        @Test
        @DisplayName("getStats() produces correct counts matching catalog state")
        void getStatsProducesCorrectCountsMatchingCatalogState() {
            // Setup segments with different lifecycle states using proper transitions
            // Segment 1: INDEXING -> ACTIVE
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).transitionToActive();
            visibilityTracker.registerSegment(1L, 0L);

            // Segment 2: INDEXING (stays at INDEXING)
            catalog.add(2L, Path.of("/test/segment2"));
            visibilityTracker.registerSegment(2L, 0L);

            // Segment 3: INDEXING -> WARMING
            catalog.add(3L, Path.of("/test/segment3"));
            catalog.get(3L).transitionToWarming();
            visibilityTracker.registerSegment(3L, 0L);

            // Segment 4: INDEXING -> ACTIVE -> COMPACTING
            catalog.add(4L, Path.of("/test/segment4"));
            catalog.get(4L).transitionToActive();
            catalog.get(4L).transitionToCompacting();
            visibilityTracker.registerSegment(4L, 0L);

            // Create mock segments
            for (long id = 1; id <= 4; id++) {
                Segment mockSegment = mock(Segment.class);
                when(mockSegment.segmentId()).thenReturn(id);
                when(mockSegment.isClosed()).thenReturn(false);
                when(storageEngine.getSegment(id)).thenReturn(mockSegment);
            }

            // Capture snapshot and get stats
            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);
            QuerySnapshot.SnapshotStats stats = snapshot.getStats();

            // Verify counts match catalog state
            assertThat(stats.active()).isEqualTo(1);
            assertThat(stats.indexing()).isEqualTo(1);
            assertThat(stats.warming()).isEqualTo(1);
            assertThat(stats.compacting()).isEqualTo(1);
            assertThat(stats.queryable()).isEqualTo(4); // All 4 are queryable states
            assertThat(stats.tombstoned()).isEqualTo(0);
        }

        @Test
        @DisplayName("snapshot excludes tombstoned segments from future epochs")
        void snapshotExcludesTombstonedSegmentsFromFutureEpochs() {
            // Setup an active segment
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).setLifecycleState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(1L, 0L);

            // Capture snapshot at epoch 0
            long epoch0 = visibilityTracker.captureEpoch();
            QuerySnapshot snapshot0 = QuerySnapshot.atEpoch(epoch0, visibilityTracker, catalog, storageEngine);

            // Verify segment is visible at epoch 0
            assertThat(snapshot0.visibleSegmentIds()).contains(1L);

            // Advance epoch and tombstone the segment
            visibilityTracker.advanceEpoch(); // epoch = 1
            catalog.get(1L).setLifecycleState(LifecycleState.TOMBSTONED);
            visibilityTracker.markTombstoned(1L, 1L);

            // Capture snapshot at epoch 1
            long epoch1 = visibilityTracker.captureEpoch();
            QuerySnapshot snapshot1 = QuerySnapshot.atEpoch(epoch1, visibilityTracker, catalog, storageEngine);

            // Verify segment is NOT visible at epoch 1 (tombstoned)
            assertThat(snapshot1.visibleSegmentIds()).doesNotContain(1L);

            // But snapshot at epoch 0 should still show it (epoch isolation)
            assertThat(snapshot0.visibleSegmentIds()).contains(1L);
        }

        @Test
        @DisplayName("resolveVisibleSegments() filters out closed segments")
        void resolveVisibleSegmentsFiltersClosedSegments() {
            // Setup two segments
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).setLifecycleState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(1L, 0L);

            catalog.add(2L, Path.of("/test/segment2"));
            catalog.get(2L).setLifecycleState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(2L, 0L);

            // Create mock segments - one closed, one open
            Segment openSegment = mock(Segment.class);
            when(openSegment.segmentId()).thenReturn(1L);
            when(openSegment.isClosed()).thenReturn(false);

            Segment closedSegment = mock(Segment.class);
            when(closedSegment.segmentId()).thenReturn(2L);
            when(closedSegment.isClosed()).thenReturn(true); // Closed!

            when(storageEngine.getSegment(1L)).thenReturn(openSegment);
            when(storageEngine.getSegment(2L)).thenReturn(closedSegment);

            // Capture snapshot
            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);

            // Resolve visible segments - should exclude closed segment
            List<Segment> visibleSegments = snapshot.resolveVisibleSegments();

            assertThat(visibleSegments).hasSize(1);
            assertThat(visibleSegments.get(0).segmentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("snapshot with empty catalog returns empty segments")
        void snapshotWithEmptyCatalogReturnsEmptySegments() {
            // Capture snapshot with empty catalog
            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);

            assertThat(snapshot.visibleSegmentCount()).isEqualTo(0);
            assertThat(snapshot.resolveVisibleSegments()).isEmpty();

            QuerySnapshot.SnapshotStats stats = snapshot.getStats();
            assertThat(stats.totalVisible()).isEqualTo(0);
            assertThat(stats.queryable()).isEqualTo(0);
            assertThat(stats.coverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("coverage calculation is accurate")
        void coverageCalculationIsAccurate() {
            // Add segments with mixed states using proper transitions
            // Segment 1: ACTIVE
            catalog.add(1L, Path.of("/test/segment1"));
            catalog.get(1L).transitionToActive();
            visibilityTracker.registerSegment(1L, 0L);

            // Segment 2: ACTIVE
            catalog.add(2L, Path.of("/test/segment2"));
            catalog.get(2L).transitionToActive();
            visibilityTracker.registerSegment(2L, 0L);

            // Segment 3: INDEXING (queryable, but will remain in catalog)
            catalog.add(3L, Path.of("/test/segment3"));
            // Leave at INDEXING state (default from add())
            visibilityTracker.registerSegment(3L, 0L);

            // Create mock segments
            for (long id = 1; id <= 3; id++) {
                Segment mockSegment = mock(Segment.class);
                when(mockSegment.segmentId()).thenReturn(id);
                when(mockSegment.isClosed()).thenReturn(false);
                when(storageEngine.getSegment(id)).thenReturn(mockSegment);
            }

            QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);
            QuerySnapshot.SnapshotStats stats = snapshot.getStats();

            // All 3 segments are queryable (2 ACTIVE + 1 INDEXING)
            assertThat(stats.queryable()).isEqualTo(3);
            // Coverage = 3 queryable / 3 total = 1.0
            assertThat(stats.coverage()).isCloseTo(1.0, within(0.01));
        }
    }
}
