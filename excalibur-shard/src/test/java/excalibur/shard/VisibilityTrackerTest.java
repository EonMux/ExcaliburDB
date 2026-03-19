package excalibur.shard;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VisibilityTracker epoch-based visibility tracking.
 */
class VisibilityTrackerTest {

    private VisibilityTracker tracker;
    private SegmentCatalog catalog;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        tracker = new VisibilityTracker(catalog);
    }

    @Test
    @DisplayName("Initial epoch should be 0")
    void initialEpochShouldBeZero() {
        assertEquals(0L, tracker.currentEpoch());
    }

    @Test
    @DisplayName("captureEpoch should return current epoch atomically")
    void captureEpochShouldReturnCurrentEpoch() {
        long captured = tracker.captureEpoch();
        assertEquals(0L, captured);
        assertEquals(captured, tracker.currentEpoch());
    }

    @Test
    @DisplayName("advanceEpoch should increment epoch by 1")
    void advanceEpochShouldIncrement() {
        assertEquals(1L, tracker.advanceEpoch());
        assertEquals(1L, tracker.currentEpoch());

        assertEquals(2L, tracker.advanceEpoch());
        assertEquals(2L, tracker.currentEpoch());
    }

    @Test
    @DisplayName("registerSegment should track creation epoch")
    void registerSegmentShouldTrackCreationEpoch() {
        tracker.registerSegment(1L, 0L);
        tracker.registerSegment(2L, 5L);

        assertEquals(0L, tracker.getCreationEpoch(1L));
        assertEquals(5L, tracker.getCreationEpoch(2L));
        assertEquals(-1L, tracker.getCreationEpoch(999L)); // Not registered
    }

    @Test
    @DisplayName("markTombstoned should track tombstone epoch")
    void markTombstonedShouldTrackTombstoneEpoch() {
        tracker.registerSegment(1L, 0L);
        tracker.markTombstoned(1L, 10L);

        assertEquals(10L, tracker.getTombstoneEpoch(1L));
        assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(2L)); // Not tombstoned
    }

    @Test
    @DisplayName("visibleSegments should return segments visible at query epoch")
    void visibleSegmentsShouldReturnCorrectSegments() {
        // Register segments at different epochs
        tracker.registerSegment(1L, 0L);  // Created at epoch 0
        tracker.registerSegment(2L, 5L);  // Created at epoch 5
        tracker.registerSegment(3L, 8L);  // Created at epoch 8

        // Advance epoch
        tracker.advanceEpoch(); // epoch = 1
        tracker.advanceEpoch(); // epoch = 2
        tracker.advanceEpoch(); // epoch = 3
        tracker.advanceEpoch(); // epoch = 4
        tracker.advanceEpoch(); // epoch = 5

        // At epoch 2, only segment 1 should be visible
        Set<Long> visibleAt2 = tracker.visibleSegments(2L);
        assertEquals(1, visibleAt2.size());
        assertTrue(visibleAt2.contains(1L));

        // At epoch 5, segments 1 and 2 should be visible
        Set<Long> visibleAt5 = tracker.visibleSegments(5L);
        assertEquals(2, visibleAt5.size());
        assertTrue(visibleAt5.contains(1L));
        assertTrue(visibleAt5.contains(2L));
    }

    @Test
    @DisplayName("visibleSegments should exclude tombstoned segments after tombstone epoch")
    void visibleSegmentsShouldExcludeTombstonedSegments() {
        tracker.registerSegment(1L, 0L);
        tracker.registerSegment(2L, 0L);

        // Tombstone segment 2 at epoch 10
        tracker.markTombstoned(2L, 10L);

        // At epoch 5, both segments should be visible (tombstone hasn't happened yet)
        Set<Long> visibleAt5 = tracker.visibleSegments(5L);
        assertEquals(2, visibleAt5.size());

        // At epoch 10, only segment 1 should be visible (segment 2 tombstoned)
        Set<Long> visibleAt10 = tracker.visibleSegments(10L);
        assertEquals(1, visibleAt10.size());
        assertTrue(visibleAt10.contains(1L));

        // At epoch 15, only segment 1 should be visible
        Set<Long> visibleAt15 = tracker.visibleSegments(15L);
        assertEquals(1, visibleAt15.size());
        assertTrue(visibleAt15.contains(1L));
    }

    @Test
    @DisplayName("isSegmentVisible should correctly determine visibility")
    void isSegmentVisibleShouldCorrectlyDetermineVisibility() {
        tracker.registerSegment(1L, 5L);
        tracker.markTombstoned(1L, 15L);

        // Before creation - not visible
        assertFalse(tracker.isSegmentVisible(1L, 3L));

        // At creation - visible
        assertTrue(tracker.isSegmentVisible(1L, 5L));

        // After creation, before tombstone - visible
        assertTrue(tracker.isSegmentVisible(1L, 10L));

        // At tombstone epoch - not visible (epoch >= tombstoneEpoch)
        assertFalse(tracker.isSegmentVisible(1L, 15L));

        // After tombstone - not visible
        assertFalse(tracker.isSegmentVisible(1L, 20L));
    }

    @Test
    @DisplayName("currentVisibleSegments should return segments visible at current epoch")
    void currentVisibleSegmentsShouldWork() {
        tracker.registerSegment(1L, 0L);
        tracker.registerSegment(2L, 0L);

        Set<Long> visible = tracker.currentVisibleSegments();
        assertEquals(2, visible.size());
        assertTrue(visible.contains(1L));
        assertTrue(visible.contains(2L));
    }

    @Test
    @DisplayName("unregisterSegment should remove segment from tracking")
    void unregisterSegmentShouldRemoveSegment() {
        tracker.registerSegment(1L, 0L);
        assertTrue(tracker.isSegmentVisible(1L, 0L));

        tracker.unregisterSegment(1L);
        assertFalse(tracker.isSegmentVisible(1L, 0L));
    }

    @Test
    @DisplayName("getStats should return correct statistics")
    void getStatsShouldReturnCorrectStats() {
        tracker.registerSegment(1L, 0L);
        tracker.registerSegment(2L, 0L);
        tracker.markTombstoned(2L, 5L);
        tracker.advanceEpoch();
        tracker.advanceEpoch();

        VisibilityTracker.VisibilityStats stats = tracker.getStats();

        assertEquals(2L, stats.currentEpoch());
        assertEquals(2, stats.totalSegments());
        assertEquals(1, stats.tombstonedSegments());
        // At epoch 2, only segment 1 is visible (segment 2 is visible until epoch 5)
        assertEquals(2, stats.visibleSegments());
    }

    @Test
    @DisplayName("Thread safety - concurrent epoch advancement")
    void threadSafetyConcurrentEpochAdvancement() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 1000;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    tracker.advanceEpoch();
                }
            });
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Should have advanced threadCount * incrementsPerThread times
        assertEquals(threadCount * incrementsPerThread, tracker.currentEpoch());
    }
}
