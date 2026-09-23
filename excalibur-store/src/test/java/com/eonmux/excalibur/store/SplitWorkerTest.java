package com.eonmux.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    /** Far longer than any work here takes to stop once asked. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

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
    private final List<String> warnings = new ArrayList<>();
    private Handler warningCaptor;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segmentFile = tempDir.resolve("worker-test-segment.bin");
        // Create a small segment that will fill quickly
        segment = new Segment(segmentFile, 64 * 1024, arena);
        warningCaptor = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(SplitWorker.class.getName()).addHandler(warningCaptor);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(SplitWorker.class.getName()).removeHandler(warningCaptor);
        warnings.clear();

        if (splitWorker != null) {
            splitWorker.stop(STOP_WAIT);
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

            splitWorker.stop(STOP_WAIT);
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

    // ==================== Shutdown Tests ====================

    @Nested
    @DisplayName("Shutdown")
    class ShutdownTests {

        /**
         * A split requested while {@code stop()} is draining must not be started.
         *
         * <p>{@code stop()} drains the splits it knows about and then returns, and the owner of
         * the Arena closes it next. A request accepted after that point is handed a thread
         * nobody is waiting for, partitioning a parent into two children mapped out of an Arena
         * that is being unmapped - which ends the process rather than raising anything.</p>
         */
        @Test
        @DisplayName("a split requested while stop() is draining is not left running after it")
        void requestDuringShutdownIsNotLeftRunning() throws Exception {
            CountDownLatch insideFirstSplit = new CountDownLatch(1);
            CountDownLatch releaseFirstSplit = new CountDownLatch(1);
            CountDownLatch insideSecondSplit = new CountDownLatch(1);
            CountDownLatch releaseSecondSplit = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            Segment second = new Segment(tempDir.resolve("late-request.bin"), 64 * 1024, arena);

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                if (parent == segment) {
                    leftChildRef.set(left);
                    rightChildRef.set(right);
                    insideFirstSplit.countDown();
                    awaitRelease(releaseFirstSplit);
                } else {
                    insideSecondSplit.countDown();
                    awaitRelease(releaseSecondSplit);
                }
            }, () -> 0);

            fill(segment, "first-key-");
            fill(second, "second-key-");

            splitWorker.requestSplit(segment);
            assertTrue(insideFirstSplit.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the first split must reach the listener");

            // Shutdown is now under way and blocked on the split this test is holding, which is
            // the window a late request arrives in.
            Thread stopper = Thread.ofVirtual().name("stop-caller")
                .start(() -> splitWorker.stop(STOP_WAIT));
            assertFalse(stopper.join(Duration.ofSeconds(1)),
                "stop() must wait for the split it already has in flight");

            splitWorker.requestSplit(second);

            releaseFirstSplit.countDown();
            assertTrue(stopper.join(Duration.ofSeconds(SPLIT_TIMEOUT_SECONDS)),
                "stop() finished once the held split was released");

            // Two seconds is not a latency budget: if that request was dispatched, its thread is
            // already unblocked - the lock the first split held is free and stop() has returned -
            // and has only to be scheduled.
            boolean secondSplitRan = insideSecondSplit.await(2, TimeUnit.SECONDS);
            releaseSecondSplit.countDown();
            if (secondSplitRan) {
                // Let the split nobody is waiting for finish before this test's arena goes away,
                // so a failing run reports the defect instead of taking the JVM down with it.
                awaitSplitComplete(second);
            }

            assertFalse(secondSplitRan,
                "a split requested while stop() was draining was still running after stop() "
                    + "returned, with nothing left that would wait for it");
            // Either refusal is correct for a request in this window - it may land after
            // stop() has marked itself stopped, or in the narrower race where it is published
            // and then withdrawn - and both say the same operative thing.
            assertTrue(warnings.stream().anyMatch(message -> message.contains("nothing was started")),
                "the refused request says so rather than failing silently");

            closeChildren(leftChildRef, rightChildRef);
            if (!second.isClosed()) {
                second.close();
            }
        }

        /**
         * A split that outlives the budget leaves {@code stop()} returning, and saying so.
         */
        @Test
        @DisplayName("stop waits out the whole of its wait for a split, then gives up rather "
            + "than hanging")
        void stopBoundsTheWaitForARequestedSplit() throws Exception {
            CountDownLatch insideTheSplit = new CountDownLatch(1);
            CountDownLatch releaseTheSplit = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                leftChildRef.set(left);
                rightChildRef.set(right);
                insideTheSplit.countDown();
                awaitRelease(releaseTheSplit);
            }, () -> 0);
            Duration wait = Duration.ofMillis(200);

            fill(segment, "held-key-");

            splitWorker.requestSplit(segment);
            assertTrue(insideTheSplit.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the split must reach the listener");

            // Held for far longer than the wait: a shutdown that waited without a bound
            // would never come back from here.
            long startNanos = System.nanoTime();
            boolean endedWhileHeld = splitWorker.stop(wait);
            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            releaseTheSplit.countDown();

            // Quiesced before anything is asserted, never after. Nothing here proved the Arena
            // was safe to close - that is exactly what the warning says - and an assertion that
            // fired first would skip this and hand teardown a live split to unmap underneath.
            boolean finished = awaitSplitComplete(segment);
            closeChildren(leftChildRef, rightChildRef);

            assertFalse(endedWhileHeld,
                "a split still running when the wait runs out is reported, not claimed ended");
            assertTrue(elapsedNanos >= wait.toNanos(),
                "the split was given all of the wait stop was handed, took " + elapsedMs + "ms");
            assertTrue(elapsedMs < 5000,
                "stop returned on the wait it was handed rather than waiting out the split, took "
                    + elapsedMs + "ms");
            assertTrue(warnings.stream().anyMatch(msg -> msg.contains("still running after")),
                "an expired wait is not silent: the Arena is not safe to close and the log "
                    + "says so");
            assertTrue(finished, "the split finished once it was released");
            assertTrue(awaitStopped(splitWorker),
                "given long enough, stop sees the released split end");
        }

        /**
         * The loop is stopped by a signal it reads between segments, never by an interrupt: a
         * split writes its children through channels an interrupt would close for every thread
         * using them.
         */
        @Test
        @DisplayName("stop() never interrupts the loop's split and reports it running until it ends")
        void stopLeavesTheLoopsSplitUninterrupted() throws Exception {
            CountDownLatch insideTheSplit = new CountDownLatch(1);
            CountDownLatch releaseTheSplit = new CountDownLatch(1);
            AtomicBoolean interruptedInside = new AtomicBoolean();
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                leftChildRef.set(left);
                rightChildRef.set(right);
                insideTheSplit.countDown();
                awaitRelease(releaseTheSplit);
                interruptedInside.set(Thread.currentThread().isInterrupted());
            }, () -> 0);
            fillToThreshold(segment);
            assertTrue(segment.shouldSplit(), "the loop needs a segment over its threshold");
            splitWorker.registerSegment(segment);

            splitWorker.start();
            assertTrue(insideTheSplit.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the background loop must reach the listener");

            boolean endedWhileHeld = splitWorker.stop(Duration.ofMillis(200));

            releaseTheSplit.countDown();
            boolean finished = awaitSplitComplete(segment);
            closeChildren(leftChildRef, rightChildRef);

            assertFalse(endedWhileHeld,
                "a split still running when the wait runs out is reported, not claimed ended");
            assertTrue(finished, "the split finished once it was released");
            assertFalse(interruptedInside.get(),
                "stop() must not interrupt a thread that is writing through shared channels");
            assertTrue(awaitStopped(splitWorker),
                "once the loop has left its split, stop() reports the worker ended");
        }

        @Test
        @DisplayName("stop() wakes the loop from its pause without waiting out the interval")
        void stopWakesThePausingLoop() {
            splitWorker = new SplitWorker(arena, (parent, left, right) -> {}, () -> 0,
                TimeUnit.HOURS.toMillis(1), 1);
            splitWorker.start();

            long startNanos = System.nanoTime();
            assertTrue(splitWorker.stop(Duration.ofMinutes(5)));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos) < 60,
                "returned because the pause was woken, not because a budget or interval ran out");
        }

        /**
         * The budget covers the whole drain, not each split in it.
         *
         * <p>Splits are serialised, so three requests are one split in the listener and two
         * queued behind it on the execution lock - three live threads for {@code stop()} to
         * wait on. A budget spent per thread would hold shutdown for three times as long as
         * the operator configured, which is the sort of bound that stops being one as soon as
         * a store has more than a couple of segments in flight.</p>
         */
        @Test
        @DisplayName("the stop budget covers the whole drain, not each split separately")
        void stopBudgetIsSharedAcrossEverySplit() throws Exception {
            CountDownLatch insideFirstSplit = new CountDownLatch(1);
            CountDownLatch releaseFirstSplit = new CountDownLatch(1);
            AtomicReference<Segment> leftChildRef = new AtomicReference<>();
            AtomicReference<Segment> rightChildRef = new AtomicReference<>();

            Segment queuedA = new Segment(tempDir.resolve("queued-a.bin"), 64 * 1024, arena);
            Segment queuedB = new Segment(tempDir.resolve("queued-b.bin"), 64 * 1024, arena);

            splitWorker = new SplitWorker(arena, (parent, left, right) -> {
                if (parent == segment) {
                    leftChildRef.set(left);
                    rightChildRef.set(right);
                    insideFirstSplit.countDown();
                    awaitRelease(releaseFirstSplit);
                }
            }, () -> 0);

            fill(segment, "shared-key-");
            fill(queuedA, "queued-a-key-");
            fill(queuedB, "queued-b-key-");

            splitWorker.requestSplit(segment);
            assertTrue(insideFirstSplit.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the first split must reach the listener");
            splitWorker.requestSplit(queuedA);
            splitWorker.requestSplit(queuedB);

            long startNanos = System.nanoTime();
            splitWorker.stop(Duration.ofMillis(500));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            releaseFirstSplit.countDown();

            // Every split is waited out before the first assertion. stop() has by construction
            // returned with work still running here, so an assertion that fired first would
            // skip this and leave teardown unmapping the Arena underneath three live splits -
            // the very crash these tests exist for.
            boolean allFinished = awaitSplitComplete(segment)
                & awaitSplitComplete(queuedA)
                & awaitSplitComplete(queuedB);
            closeChildren(leftChildRef, rightChildRef);
            for (Segment queued : new Segment[] { queuedA, queuedB }) {
                if (!queued.isClosed()) {
                    queued.close();
                }
            }

            // One 500ms budget for three threads, not 500ms each: the halfway mark between
            // the two is wide enough that neither a loaded machine nor a fast one can be
            // mistaken for the other.
            assertTrue(elapsedMs < 1000,
                "the budget must cover the whole drain; stop() took " + elapsedMs + "ms for "
                    + "three in-flight splits on a 500ms budget");
            assertTrue(allFinished, "every split finished once the held one was released");
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
     * Waits for a latch a test controls, from inside a split listener.
     *
     * @param latch The latch the test counts down when the split may proceed
     */
    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(SPLIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the split");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while held inside the split", e);
        }
    }

    /**
     * Fills a segment with enough entries for a split to have something to partition.
     *
     * @param target The segment to fill
     * @param prefix Key prefix, so two segments in one test do not share keys
     */
    /**
     * Fills a segment up to the load factor at which it asks to be split; puts are refused at
     * the threshold, so the last one may be rejected.
     */
    private static void fillToThreshold(Segment target) {
        int entries = (int) Math.ceil(target.slotCount() * Segment.LOAD_FACTOR_THRESHOLD) - 1;
        for (int i = 0; i < entries; i++) {
            try {
                target.put(("threshold-key-" + String.format("%04d", i)).getBytes(),
                    ("threshold-value-" + i).getBytes());
            } catch (IllegalStateException atThreshold) {
                break;
            }
        }
    }

    private static void fill(Segment target, String prefix) {
        for (int i = 0; i < 100; i++) {
            target.put((prefix + i).getBytes(), ("value-" + i).getBytes());
        }
    }

    /**
     * Closes the children a split published, if it got that far.
     *
     * @param leftChildRef  Where the listener recorded the left child
     * @param rightChildRef Where the listener recorded the right child
     */
    private static void closeChildren(AtomicReference<Segment> leftChildRef,
                                      AtomicReference<Segment> rightChildRef) {
        if (leftChildRef.get() != null && !leftChildRef.get().isClosed()) {
            leftChildRef.get().close();
        }
        if (rightChildRef.get() != null && !rightChildRef.get().isClosed()) {
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

    /**
     * Asks a stopped worker again until it reports that its loop has ended.
     *
     * <p>Released work finishes on its own thread, so the first ask after the release can still
     * see it running; each ask waits within the wait it is handed.</p>
     */
    private static boolean awaitStopped(SplitWorker worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SPLIT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (worker.stop(STOP_WAIT)) {
                return true;
            }
        }
        return worker.stop(STOP_WAIT);
    }
}
