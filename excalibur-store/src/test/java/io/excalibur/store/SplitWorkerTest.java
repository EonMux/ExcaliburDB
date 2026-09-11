package io.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SplitWorker background split execution.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Split detection and triggering</li>
 *   <li>Background split execution</li>
 *   <li>Listener notification on split completion</li>
 *   <li>Concurrent split safety</li>
 *   <li>Manual split request handling</li>
 * </ul>
 */
@DisplayName("SplitWorker Background Split Tests")
class SplitWorkerTest {

    /**
     * How long a split is allowed to take before the test calls it broken.
     *
     * <p>Generous on purpose. Every use here is waiting for work that either happens in
     * well under a second or does not happen at all, so a longer timeout costs nothing on
     * a passing run and stops a machine running several test JVMs at once from reporting
     * a working split as a failure.
     */
    private static final long SPLIT_TIMEOUT_SECONDS = 30;


    @TempDir
    Path tempDir;

    private Arena arena;
    private SplitWorker splitWorker;
    private Path segmentFile;
    private Segment segment;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segmentFile = tempDir.resolve("worker-test-segment.bin");
        // Create a small segment that will fill quickly
        segment = new Segment(segmentFile, 64 * 1024, arena);
    }

    @AfterEach
    void tearDown() {
        if (splitWorker != null) {
            splitWorker.stop();
        }
        if (segment != null && !segment.isClosed()) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Lifecycle Tests ====================

    @Nested
    @DisplayName("Lifecycle Management")
    class LifecycleTests {

        @Test
        @DisplayName("SplitWorker starts and stops correctly")
        void testStartStop() {
            AtomicBoolean listenerCalled = new AtomicBoolean(false);
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                listenerCalled.set(true);
            }, () -> 0);

            assertFalse(splitWorker.isRunning(), "Worker should not be running initially");

            assertTrue(splitWorker.start(), "Start should return true");
            assertTrue(splitWorker.isRunning(), "Worker should be running after start");

            // Second start should return false
            assertFalse(splitWorker.start(), "Second start should return false");

            splitWorker.stop();
            assertFalse(splitWorker.isRunning(), "Worker should not be running after stop");
        }

        @Test
        @DisplayName("SplitWorker rejects null arena")
        void testNullArena() {
            assertThrows(NullPointerException.class, () -> {
                new SplitWorker(null, (parent, left, right) -> {}, () -> 0);
            }, "Constructor should reject null arena");
        }

        @Test
        @DisplayName("SplitWorker rejects null listener")
        void testNullListener() {
            assertThrows(NullPointerException.class, () -> {
                new SplitWorker(arena, null, () -> 0);
            }, "Constructor should reject null listener");
        }

        @Test
        @DisplayName("SplitWorker validates split interval")
        void testInvalidSplitInterval() {
            assertThrows(IllegalArgumentException.class, () -> {
                new SplitWorker(arena, (parent, left, right) -> {}, () -> 0, 50, 1);
            }, "Should reject split interval below minimum");
        }

        @Test
        @DisplayName("SplitWorker validates max concurrent splits")
        void testInvalidMaxConcurrent() {
            assertThrows(IllegalArgumentException.class, () -> {
                new SplitWorker(arena, (parent, left, right) -> {}, () -> 0, 1000, 0);
            }, "Should reject max concurrent below 1");

            assertThrows(IllegalArgumentException.class, () -> {
                new SplitWorker(arena, (parent, left, right) -> {}, () -> 0, 1000, 20);
            }, "Should reject max concurrent above limit");
        }
    }

    // ==================== Segment Registration Tests ====================

    @Nested
    @DisplayName("Segment Registration")
    class RegistrationTests {

        @Test
        @DisplayName("Can register and unregister segments")
        void testRegistration() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);

            assertEquals(0, splitWorker.getRegisteredSegmentCount());

            splitWorker.registerSegment(segment);
            assertEquals(1, splitWorker.getRegisteredSegmentCount());

            // Duplicate registration should be ignored
            splitWorker.registerSegment(segment);
            assertEquals(1, splitWorker.getRegisteredSegmentCount());

            splitWorker.unregisterSegment(segment);
            assertEquals(0, splitWorker.getRegisteredSegmentCount());
        }

        @Test
        @DisplayName("Registering null segment throws exception")
        void testNullRegistration() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);

            assertThrows(NullPointerException.class, () -> {
                splitWorker.registerSegment(null);
            });
        }
    }

    // ==================== Manual Split Tests ====================

    @Nested
    @DisplayName("Manual Split Request")
    class ManualSplitTests {

        @Test
        @DisplayName("Manual split request triggers split")
        void testManualSplit() throws Exception {
            CountDownLatch splitLatch = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                leftChildRef.set(left);
                rightChildRef.set(right);
                splitLatch.countDown();
            }, () -> 0);

            // Fill segment to have entries to split
            for (int i = 0; i < 100; i++) {
                segment.put(("manual-key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            // Request split
            splitWorker.requestSplit(segment);

            // Wait for split to complete
            assertTrue(splitLatch.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Split should complete within timeout");

            // The listener runs before the parent is retired - deliberately, so the children
            // are published before anything routes away from the parent - so the callback
            // firing is not yet the parent reaching SPLIT_COMPLETE.
            assertTrue(awaitSplitComplete(segment), "Segment should be SPLIT_COMPLETE");
            assertNotNull(leftChildRef.get(), "Left child should be created");
            assertNotNull(rightChildRef.get(), "Right child should be created");

            // Clean up children
            if (leftChildRef.get() != null) leftChildRef.get().close();
            if (rightChildRef.get() != null) rightChildRef.get().close();
        }

        @Test
        @DisplayName("Manual split of null segment throws exception")
        void testManualSplitNull() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);

            assertThrows(NullPointerException.class, () -> {
                splitWorker.requestSplit(null);
            });
        }
    }

    // ==================== Automatic Split Tests ====================

    @Nested
    @DisplayName("Automatic Split Detection")
    class AutomaticSplitTests {

        @Test
        @DisplayName("SplitWorker detects segment needing split")
        void testAutomaticSplit() throws Exception {
            CountDownLatch splitLatch = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                leftChildRef.set(left);
                rightChildRef.set(right);
                splitLatch.countDown();
            }, () -> 0);
            splitWorker.setSplitIntervalMs(100); // Check frequently

            // Register segment
            splitWorker.registerSegment(segment);

            // Fill segment to just under threshold (0.7 = 70%)
            // Note: puts are rejected at >= 70% load factor, We need to reach
            // exactly the threshold for shouldSplit() to return true.
            // Calculate entries to reach just under 70% threshold
            int slotCount = segment.slotCount();
            int entriesToInsert = (int) Math.ceil(slotCount * Segment.LOAD_FACTOR_THRESHOLD) - 1;

            for (int i = 0; i < entriesToInsert; i++) {
                byte[] key = ("auto-key-" + String.format("%04d", i)).getBytes();
                byte[] value = ("auto-value-" + i).getBytes();
                try {
                    segment.put(key, value);
                } catch (IllegalStateException e) {
                    // Segment hit threshold - stop inserting
                    break;
                }
            }

            // Establish the precondition before waiting on anything. Without this the test
            // could only ever pass: it used to treat "no split happened" as an acceptable
            // outcome, so a worker that detected nothing at all looked exactly like a healthy
            // one -- and the wait had to be short to keep that non-outcome cheap.
            assertTrue(segment.shouldSplit(),
                "the segment must be over its threshold, or there is nothing for the worker "
                    + "to detect and this test asserts nothing");

            // Start worker
            splitWorker.start();

            // A liveness guard, not a latency budget: the split is expected within a few
            // 100ms check intervals, and the timeout only has to be long enough that a loaded
            // machine cannot make a working split look like a broken one.
            assertTrue(splitLatch.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the worker must detect a segment that is over its threshold");

            // Waited for, not asserted outright: the latch is counted down from inside the
            // listener, and the worker marks the parent complete only after the listener returns.
            // Reading the state the instant the latch fires is a race the test thread sometimes
            // wins on a loaded machine, which fails a split that in fact worked.
            assertTrue(awaitSplitComplete(segment),
                "Segment should be SPLIT_COMPLETE after auto split");
            assertNotNull(leftChildRef.get(), "Left child should be created");
            assertNotNull(rightChildRef.get(), "Right child should be created");

            leftChildRef.get().close();
            rightChildRef.get().close();
        }
    }

    /**
     * Waits for a split parent to reach SPLIT_COMPLETE.
     *
     * @param segment The parent that was split
     * @return true if it completed within the timeout
     * @throws InterruptedException if the wait is interrupted
     */
    private static boolean awaitSplitComplete(Segment segment) throws InterruptedException {
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(SPLIT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (segment.isSplitComplete()) {
                return true;
            }
            Thread.sleep(5);
        }
        return segment.isSplitComplete();
    }

    // ==================== Statistics Tests ====================

    @Nested
    @DisplayName("Statistics Tracking")
    class StatisticsTests {

        @Test
        @DisplayName("SplitWorker tracks split count")
        void testSplitCount() throws Exception {
            CountDownLatch splitLatch = new CountDownLatch(1);

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                splitLatch.countDown();
            }, () -> 0);

            assertEquals(0, splitWorker.getSplitCount());
            assertEquals(0, splitWorker.getEntriesSplit());

            // Fill and request split
            for (int i = 0; i < 50; i++) {
                segment.put(("stat-key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            splitWorker.requestSplit(segment);
            assertTrue(splitLatch.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Split should be published");

            // The listener runs before the parent is retired and the statistics are
            // recorded, so wait for the split to finish rather than racing it.
            long deadline = System.currentTimeMillis() + 5000;
            while (splitWorker.getSplitCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            assertEquals(1, splitWorker.getSplitCount(), "Split count should be 1");
            assertEquals(50, splitWorker.getEntriesSplit(), "Entries split should match entry count");
            assertTrue(splitWorker.getLastSplitTime() > 0, "Last split time should be set");
        }
    }

    // ==================== Configuration Tests ====================

    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {

        @Test
        @DisplayName("Can get and set split interval")
        void testSplitInterval() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);

            assertEquals(SplitWorker.DEFAULT_SPLIT_INTERVAL_MS, splitWorker.getSplitIntervalMs());

            splitWorker.setSplitIntervalMs(5000);
            assertEquals(5000, splitWorker.getSplitIntervalMs());

            // Invalid value
            assertThrows(IllegalArgumentException.class, () -> {
                splitWorker.setSplitIntervalMs(50);
            });
        }

        @Test
        @DisplayName("Can get and set max concurrent splits")
        void testMaxConcurrentSplits() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);

            assertEquals(SplitWorker.DEFAULT_MAX_CONCURRENT_SPLITS, splitWorker.getMaxConcurrentSplits());

            splitWorker.setMaxConcurrentSplits(4);
            assertEquals(4, splitWorker.getMaxConcurrentSplits());

            // Invalid value
            assertThrows(IllegalArgumentException.class, () -> {
                splitWorker.setMaxConcurrentSplits(0);
            });
        }
    }

    // ==================== Entry Distribution Tests ====================

    @Nested
    @DisplayName("Entry Distribution")
    class EntryDistributionTests {

        @Test
        @DisplayName("Split distributes entries between children")
        void testEntryDistribution() throws Exception {
            CountDownLatch splitLatch = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                leftChildRef.set(left);
                rightChildRef.set(right);
                splitLatch.countDown();
            }, () -> 0);

            // Insert entries with known distribution
            int entryCount = 200;
            for (int i = 0; i < entryCount; i++) {
                segment.put(("dist-key-" + String.format("%04d", i)).getBytes(),
                           ("dist-value-" + i).getBytes());
            }

            splitWorker.requestSplit(segment);
            assertTrue(splitLatch.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Split should complete");

            Segment leftChild = leftChildRef.get();
            Segment rightChild = rightChildRef.get();

            assertNotNull(leftChild, "Left child should exist");
            assertNotNull(rightChild, "Right child should exist");

            // Both children should have some entries
            assertTrue(leftChild.entryCount() > 0, "Left child should have entries");
            assertTrue(rightChild.entryCount() > 0, "Right child should have entries");

            // Total entries should match
            int totalInChildren = leftChild.entryCount() + rightChild.entryCount();
            assertEquals(entryCount, totalInChildren, "All entries should be in children");

            // Verify all entries are retrievable
            int foundCount = 0;
            for (int i = 0; i < entryCount; i++) {
                byte[] key = ("dist-key-" + String.format("%04d", i)).getBytes();
                byte[] expectedValue = ("dist-value-" + i).getBytes();

                MemorySegment leftResult = leftChild.get(key);
                MemorySegment rightResult = rightChild.get(key);

                if (leftResult != null) {
                    assertArrayEquals(expectedValue, leftResult.toArray(ValueLayout.JAVA_BYTE));
                    foundCount++;
                } else if (rightResult != null) {
                    assertArrayEquals(expectedValue, rightResult.toArray(ValueLayout.JAVA_BYTE));
                    foundCount++;
                }
            }

            assertEquals(entryCount, foundCount, "All entries should be retrievable from children");

            leftChild.close();
            rightChild.close();
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Default split interval is reasonable (>= 100ms)")
        void testDefaultSplitInterval() {
            assertTrue(SplitWorker.DEFAULT_SPLIT_INTERVAL_MS >= SplitWorker.MIN_SPLIT_INTERVAL_MS,
                "Default split interval should be at least minimum");
        }

        @Test
        @DisplayName("Min split interval is 100ms")
        void testMinSplitInterval() {
            assertEquals(100, SplitWorker.MIN_SPLIT_INTERVAL_MS,
                "Min split interval should be 100ms as per spec");
        }

        @Test
        @DisplayName("Max concurrent splits limit is reasonable")
        void testMaxConcurrentLimit() {
            assertTrue(SplitWorker.MAX_CONCURRENT_SPLITS_LIMIT <= 8,
                "Max concurrent splits limit should be <= 8");
        }

        @Test
        @DisplayName("toString provides useful debug info")
        void testToString() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0);
            String str = splitWorker.toString();

            assertTrue(str.contains("SplitWorker"), "toString should contain class name");
            assertTrue(str.contains("running"), "toString should contain running state");
            assertTrue(str.contains("registeredSegments"), "toString should contain segment count");
        }
    }
}
