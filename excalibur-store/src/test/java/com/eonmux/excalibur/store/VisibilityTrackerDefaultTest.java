package com.eonmux.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VisibilityTracker.Default implementation.
 */
@DisplayName("VisibilityTracker.Default Tests")
class VisibilityTrackerDefaultTest {

    private VisibilityTracker.Default tracker;

    @BeforeEach
    void setUp() {
        tracker = new VisibilityTracker.Default();
    }

    @Nested
    @DisplayName("Epoch Operations")
    class EpochOperations {

        @Test
        @DisplayName("Should start with epoch 0")
        void shouldStartWithEpoch0() {
            assertEquals(0L, tracker.currentEpoch());
        }

        @Test
        @DisplayName("Should advance epoch")
        void shouldAdvanceEpoch() {
            long newEpoch = tracker.advanceEpoch();
            assertEquals(1L, newEpoch);
            assertEquals(1L, tracker.currentEpoch());
        }

        @Test
        @DisplayName("Should advance epoch multiple times")
        void shouldAdvanceEpochMultipleTimes() {
            for (int i = 1; i <= 10; i++) {
                long newEpoch = tracker.advanceEpoch();
                assertEquals(i, newEpoch);
            }
            assertEquals(10L, tracker.currentEpoch());
        }

        @Test
        @DisplayName("Should capture current epoch")
        void shouldCaptureCurrentEpoch() {
            assertEquals(0L, tracker.captureEpoch());

            tracker.advanceEpoch();
            assertEquals(1L, tracker.captureEpoch());

            tracker.advanceEpoch();
            tracker.advanceEpoch();
            assertEquals(3L, tracker.captureEpoch());
        }

        @Test
        @DisplayName("Capture epoch should not advance epoch")
        void captureEpochShouldNotAdvanceEpoch() {
            tracker.captureEpoch();
            assertEquals(0L, tracker.currentEpoch());

            tracker.captureEpoch();
            tracker.captureEpoch();
            assertEquals(0L, tracker.currentEpoch());
        }
    }

    @Nested
    @DisplayName("Segment Registration")
    class SegmentRegistration {

        @Test
        @DisplayName("Register segment should be no-op")
        void registerSegmentShouldBeNoOp() {
            // Should not throw
            assertDoesNotThrow(() -> tracker.registerSegment(1L, 0L));
            assertDoesNotThrow(() -> tracker.registerSegment(100L, 50L));
        }

        @Test
        @DisplayName("Mark tombstoned should be no-op")
        void markTombstonedShouldBeNoOp() {
            // Should not throw
            assertDoesNotThrow(() -> tracker.markTombstoned(1L, 10L));
            assertDoesNotThrow(() -> tracker.markTombstoned(100L, 200L));
        }
    }

    @Nested
    @DisplayName("Visibility Checks")
    class VisibilityChecks {

        @Test
        @DisplayName("Visible segments should return empty set")
        void visibleSegmentsShouldReturnEmptySet() {
            Set<Long> visible = tracker.visibleSegments(0L);
            assertTrue(visible.isEmpty());

            tracker.advanceEpoch();
            visible = tracker.visibleSegments(1L);
            assertTrue(visible.isEmpty());
        }

        @Test
        @DisplayName("Is segment visible should always return true")
        void isSegmentVisibleShouldAlwaysReturnTrue() {
            assertTrue(tracker.isSegmentVisible(1L, 0L));
            assertTrue(tracker.isSegmentVisible(1L, 100L));
            assertTrue(tracker.isSegmentVisible(999L, 0L));
            assertTrue(tracker.isSegmentVisible(-1L, 0L));
        }

        @Test
        @DisplayName("Get creation epoch should return 0")
        void getCreationEpochShouldReturn0() {
            assertEquals(0L, tracker.getCreationEpoch(1L));
            assertEquals(0L, tracker.getCreationEpoch(100L));
            assertEquals(0L, tracker.getCreationEpoch(-1L));
        }

        @Test
        @DisplayName("Get tombstone epoch should return MAX_VALUE")
        void getTombstoneEpochShouldReturnMaxValue() {
            assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(1L));
            assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(100L));
            assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(-1L));
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("Should be thread-safe for epoch operations")
        void shouldBeThreadSafeForEpochOperations() throws InterruptedException {
            int numThreads = 10;
            int advancesPerThread = 1000;
            Thread[] threads = new Thread[numThreads];

            for (int i = 0; i < numThreads; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < advancesPerThread; j++) {
                        tracker.advanceEpoch();
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Should have exactly numThreads * advancesPerThread advances
            assertEquals(numThreads * advancesPerThread, tracker.currentEpoch());
        }

        @Test
        @DisplayName("Should be thread-safe for concurrent read/write")
        void shouldBeThreadSafeForConcurrentReadWrite() throws InterruptedException {
            int numReaders = 5;
            int numWriters = 5;
            int operationsPerThread = 1000;
            Thread[] threads = new Thread[numReaders + numWriters];

            // Reader threads
            for (int i = 0; i < numReaders; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < operationsPerThread; j++) {
                        long epoch = tracker.currentEpoch();
                        assertTrue(epoch >= 0);
                        tracker.captureEpoch();
                        tracker.visibleSegments(epoch);
                        tracker.isSegmentVisible(j, epoch);
                    }
                });
            }

            // Writer threads
            for (int i = 0; i < numWriters; i++) {
                threads[numReaders + i] = new Thread(() -> {
                    for (int j = 0; j < operationsPerThread; j++) {
                        tracker.advanceEpoch();
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Should have numWriters * operationsPerThread advances
            assertEquals(numWriters * operationsPerThread, tracker.currentEpoch());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle large segment IDs")
        void shouldHandleLargeSegmentIds() {
            assertTrue(tracker.isSegmentVisible(Long.MAX_VALUE, 0L));
            assertEquals(0L, tracker.getCreationEpoch(Long.MAX_VALUE));
            assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("Should handle negative segment IDs")
        void shouldHandleNegativeSegmentIds() {
            assertTrue(tracker.isSegmentVisible(-1L, 0L));
            assertTrue(tracker.isSegmentVisible(Long.MIN_VALUE, 0L));
            assertEquals(0L, tracker.getCreationEpoch(-1L));
            assertEquals(Long.MAX_VALUE, tracker.getTombstoneEpoch(-1L));
        }

        @Test
        @DisplayName("Should handle large query epochs")
        void shouldHandleLargeQueryEpochs() {
            assertTrue(tracker.isSegmentVisible(1L, Long.MAX_VALUE));
            Set<Long> visible = tracker.visibleSegments(Long.MAX_VALUE);
            assertTrue(visible.isEmpty());
        }

        @Test
        @DisplayName("Should handle epoch overflow gracefully")
        void shouldHandleEpochOverflowGracefully() {
            // Advance to near Long.MAX_VALUE
            for (long i = 0; i < 100; i++) {
                tracker.advanceEpoch();
            }
            assertTrue(tracker.currentEpoch() > 0);
        }
    }
}
