package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CompactionWorker}.
 *
 * <p>These tests verify that the compaction worker:</p>
 * <ul>
 *   <li>Correctly identifies segments that need compaction</li>
 *   <li>Performs non-blocking compaction (reads/writes continue during compaction)</li>
 *   <li>Removes tombstoned entries during compaction</li>
 *   <li>Properly starts and stops the background thread</li>
 *   <li>Handles configuration validation</li>
 * </ul>
 */
class CompactionWorkerTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private List<Segment> testSegments;
    private final List<String> warnings = new ArrayList<>();
    private Handler warningCaptor;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        testSegments = new ArrayList<>();
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
        Logger.getLogger(CompactionWorker.class.getName()).addHandler(warningCaptor);
    }

    @AfterEach
    void tearDown() throws IOException {
        Logger.getLogger(CompactionWorker.class.getName()).removeHandler(warningCaptor);
        warnings.clear();

        // Stop all workers and close segments
        for (Segment segment : testSegments) {
            if (!segment.isClosed()) {
                segment.close();
            }
        }
        testSegments.clear();

        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Configuration Validation Tests ====================

    @Test
    void constructor_withValidConfig_succeeds() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        CompactionWorker worker = new CompactionWorker(
            arena,
            (oldSeg, newSeg) -> {
                callbackInvoked.set(true);
                return CompactionOutcome.INSTALLED;
            },
            1000,  // 1 second interval
            0.15,  // 15% tombstone threshold
            16 * 1024 * 1024L,  // 16 MB write log threshold
            2  // max 2 concurrent compactions
        );

        assertThat(worker.getCompactionIntervalMs()).isEqualTo(1000);
        assertThat(worker.getTombstoneRatioThreshold()).isEqualTo(0.15);
        assertThat(worker.getWriteLogSizeThreshold()).isEqualTo(16 * 1024 * 1024L);
        assertThat(worker.getMaxConcurrentCompactions()).isEqualTo(2);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void constructor_withNullArena_throwsNPE() {
        assertThatThrownBy(() -> new CompactionWorker(null, (o, n) -> CompactionOutcome.INSTALLED))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Arena");
    }

    @Test
    void constructor_withNullListener_throwsNPE() {
        assertThatThrownBy(() -> new CompactionWorker(arena, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Listener");
    }

    @Test
    void constructor_withTooSmallInterval_throwsIAE() {
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 50, 0.2, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Compaction interval");
    }

    @Test
    void constructor_withInvalidTombstoneThreshold_throwsIAE() {
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, -0.1, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tombstone ratio threshold");

        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, 1.5, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tombstone ratio threshold");
    }

    @Test
    void constructor_withInvalidWriteLogThreshold_throwsIAE() {
        // Too small
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, 0.2, 1024, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Write log size threshold");

        // Too large
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, 0.2, 2L * 1024 * 1024 * 1024, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Write log size threshold");
    }

    @Test
    void constructor_withInvalidMaxConcurrent_throwsIAE() {
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, 0.2, 32 * 1024 * 1024L, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max concurrent compactions");

        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> CompactionOutcome.INSTALLED, 1000, 0.2, 32 * 1024 * 1024L, 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max concurrent compactions");
    }

    // ==================== Lifecycle Tests ====================

    @Test
    void start_startsBackgroundThread() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        assertThat(worker.start()).isTrue();
        assertThat(worker.isRunning()).isTrue();

        // Starting again should return false
        assertThat(worker.start()).isFalse();

        worker.stop();
    }

    @Test
    void stop_terminatesBackgroundThread() throws InterruptedException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        worker.start();
        assertThat(worker.isRunning()).isTrue();

        worker.stop();

        // Give time for thread to terminate
        Thread.sleep(100);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void stop_whenNotRunning_doesNothing() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        // Should not throw
        assertThatCode(worker::stop).doesNotThrowAnyException();
    }

    // ==================== Segment Registration Tests ====================

    @Test
    void registerSegment_addsToMonitoringList() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);

        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);
    }

    @Test
    void registerSegment_nullSegment_throwsNPE() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        assertThatThrownBy(() -> worker.registerSegment(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerSegment_duplicateSegment_doesNotDuplicate() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);
        worker.registerSegment(segment);

        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);
    }

    @Test
    void unregisterSegment_removesFromMonitoringList() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);
        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);

        worker.unregisterSegment(segment);
        assertThat(worker.getRegisteredSegmentCount()).isZero();
    }

    // ==================== Manual Compaction Tests ====================

    @Test
    @DisplayName("compactNow rewrites the segment and reports it to the listener")
    void compactNow_rewritesSegmentAndNotifiesListener() throws IOException {
        AtomicReference<Segment> oldSegmentRef = new AtomicReference<>();
        AtomicReference<Segment> newSegmentRef = new AtomicReference<>();

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            oldSegmentRef.set(oldSeg);
            newSegmentRef.set(newSeg);
            return CompactionOutcome.INSTALLED;
        });

        Segment segment = createTestSegment("test-segment");
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();
        segment.put(key, value);

        // Add a tombstone to trigger compaction eligibility
        byte[] deleteKey = "delete-key".getBytes();
        segment.put(deleteKey, "delete-value".getBytes());
        segment.delete(deleteKey);

        // Synchronously, so that what this test measures is the compaction rather than how
        // quickly a virtual thread got scheduled on a loaded machine.
        worker.compactNow(segment);

        assertThat(oldSegmentRef.get()).isEqualTo(segment);
        assertThat(newSegmentRef.get()).isNotNull();
        assertThat(newSegmentRef.get().isClosed()).isFalse();

        // Old segment should be closed
        assertThat(segment.isClosed()).isTrue();

        // New segment should have the original key but not the deleted key
        assertThat(newSegmentRef.get().get(key)).isNotNull();
        assertThat(newSegmentRef.get().get(deleteKey)).isNull();

        worker.stop();
    }

    @Test
    @DisplayName("a segment being split is not compacted")
    void compactNow_refusesASplittingSegment() throws IOException {
        AtomicReference<Segment> newSegmentRef = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSeg, newSeg) -> {
                newSegmentRef.set(newSeg);
                return CompactionOutcome.INSTALLED;
            });

        Segment segment = createTestSegment("splitting-segment");
        segment.put("test-key".getBytes(), "test-value".getBytes());
        byte[] deleteKey = "delete-key".getBytes();
        segment.put(deleteKey, "delete-value".getBytes());
        segment.delete(deleteKey);

        // The state a split reaches before it publishes its children. Both operations replace the
        // same directory entry, and compaction commits its half - the canonical file and the
        // write log - before the registry is asked to accept it, so a compaction that runs to
        // completion here has already discarded what the losing side needed.
        segment.beginSplit();

        worker.compactNow(segment);

        assertThat(newSegmentRef.get())
            .as("no replacement may be built for a segment that is being split")
            .isNull();
        assertThat(segment.isClosed())
            .as("the segment is still the one the split is working from")
            .isFalse();
        assertThat(Files.exists(Path.of(segment.path().toString() + ".compact")))
            .as("nothing may be left behind at the scratch path")
            .isFalse();

        worker.stop();
    }

    /**
     * Dispatch, proven by pinning the rewrite rather than by watching a callback go past.
     *
     * <p>This used to count a latch down inside the listener and then tear the arena down. The
     * listener is called from the middle of the rewrite - the swap is done but the old segment
     * has still to be retired and the write lock released - so the latch said the rewrite had
     * started, never that it had finished, and everything after it ran against an arena a live
     * rewrite was still mapped out of.</p>
     */
    @Test
    @DisplayName("requestCompaction dispatches the same work to a background thread")
    void requestCompaction_triggersCompaction() throws IOException, InterruptedException {
        CountDownLatch insideTheRewrite = new CountDownLatch(1);
        CountDownLatch releaseTheRewrite = new CountDownLatch(1);
        AtomicReference<Thread> rewriteThread = new AtomicReference<>();
        AtomicReference<Segment> replacement = new AtomicReference<>();

        CompactionWorker worker = new CompactionWorker(arena,
            (oldSeg, newSeg) -> {
                rewriteThread.set(Thread.currentThread());
                replacement.set(newSeg);
                insideTheRewrite.countDown();
                awaitRelease(releaseTheRewrite);
                return CompactionOutcome.INSTALLED;
            });

        Segment segment = compactableSegment("dispatch-segment");

        worker.requestCompaction(segment);

        // Reaching here with the rewrite pinned is the dispatch: the request returned while the
        // work it asked for was still running, on a thread that is not this one.
        assertThat(insideTheRewrite.await(30, TimeUnit.SECONDS))
            .as("the requested compaction reached the rewrite")
            .isTrue();
        assertThat(rewriteThread.get())
            .as("the rewrite runs on a thread of its own rather than the caller's")
            .isNotSameAs(Thread.currentThread());

        releaseTheRewrite.countDown();

        // Completion is what stop() waits for, and the only thing that makes the arena this
        // test closes in teardown safe to close.
        worker.stop();

        assertThat(worker.getCompactionCount())
            .as("the dispatched rewrite finished, and finished before stop() returned")
            .isEqualTo(1);
        assertThat(segment.isClosed())
            .as("the rewrite ran to the end and retired the segment it replaced")
            .isTrue();
        assertThat(replacement.get().get("test-key".getBytes()))
            .as("the replacement carries the live entry")
            .isNotNull();
    }

    @Test
    @DisplayName("stop() does not return while a requested compaction is still rewriting")
    void stop_awaitsACompactionStartedByRequestCompaction() throws IOException, InterruptedException {
        CountDownLatch insideTheRewrite = new CountDownLatch(1);
        CountDownLatch releaseTheRewrite = new CountDownLatch(1);

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            insideTheRewrite.countDown();
            awaitRelease(releaseTheRewrite);
            return CompactionOutcome.INSTALLED;
        });

        Segment segment = compactableSegment("stop-awaits-request");

        worker.requestCompaction(segment);
        assertThat(insideTheRewrite.await(30, TimeUnit.SECONDS))
            .as("the requested compaction reached the rewrite")
            .isTrue();

        // The rewrite is now provably in flight and pinned there by a latch this test owns, so
        // there is no race to lose: whatever stop() does next, it does with a compaction running.
        Thread stopper = Thread.ofVirtual().name("stop-caller").start(worker::stop);
        boolean stopReturnedMidRewrite = stopper.join(Duration.ofSeconds(1));

        releaseTheRewrite.countDown();
        assertThat(stopper.join(Duration.ofSeconds(30)))
            .as("stop() finished once the rewrite was released")
            .isTrue();

        assertThat(stopReturnedMidRewrite)
            .as("stop() returned while a requested compaction was still inside the rewrite; the "
                + "owner closes the segment's Arena next, under a live mapped-memory access")
            .isFalse();
        assertThat(worker.getCompactionCount())
            .as("the requested compaction had run to completion by the time stop() returned")
            .isEqualTo(1);
        assertThat(segment.isClosed())
            .as("the rewrite got as far as retiring the segment it replaced")
            .isTrue();
    }

    @Test
    @DisplayName("stop() gives up on a compaction that outlives its budget rather than hanging")
    void stop_boundsTheWaitForARequestedCompaction() throws IOException, InterruptedException {
        CountDownLatch insideTheRewrite = new CountDownLatch(1);
        CountDownLatch releaseTheRewrite = new CountDownLatch(1);

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            insideTheRewrite.countDown();
            awaitRelease(releaseTheRewrite);
            return CompactionOutcome.INSTALLED;
        }).withStopTimeoutMs(200);

        Segment segment = compactableSegment("stop-bounds-the-wait");

        worker.requestCompaction(segment);
        assertThat(insideTheRewrite.await(30, TimeUnit.SECONDS))
            .as("the requested compaction reached the rewrite")
            .isTrue();

        // The rewrite is held for longer than the budget and is never going to finish inside
        // it. A shutdown that waited without a bound would never come back from here.
        long startNanos = System.nanoTime();
        worker.stop();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        releaseTheRewrite.countDown();

        assertThat(elapsedMs)
            .as("stop() returned on its own budget rather than waiting out the rewrite")
            .isLessThan(5000);
        assertThat(warnings)
            .as("an expired wait is not silent: the Arena is not safe to close and the log says so")
            .anyMatch(message -> message.contains("still running after"));

        // Nothing here proved the Arena was safe to close - that is the point of the warning -
        // so the rewrite is waited out before teardown closes it.
        awaitCompactionCount(worker, 1);
    }

    @Test
    @DisplayName("requestCompaction after stop() starts nothing")
    void requestCompaction_afterStop_isRefused() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSeg, newSeg) -> CompactionOutcome.INSTALLED);

        Segment segment = compactableSegment("request-after-stop");

        worker.stop();
        worker.requestCompaction(segment);

        // A request accepted after shutdown is the same defect wearing a later timestamp: a
        // detached rewrite of a segment whose Arena the owner is already closing, with nothing
        // left that could wait for it.
        assertThat(warnings)
            .as("a request that arrives after shutdown is refused, and says so")
            .anyMatch(message -> message.contains("has been stopped"));
        assertThat(segment.isClosed())
            .as("no rewrite may be started after stop()")
            .isFalse();
        assertThat(worker.getCompactionCount()).isZero();
    }

    /**
     * A request the worker refuses must say so, because the caller records what it was told.
     *
     * <p>The lifecycle manager asks for a rewrite and marks the segment handled on the answer.
     * A refusal reported as a dispatch leaves the tombstoned space unreclaimed with nothing
     * left that will ask again.</p>
     */
    @Test
    @DisplayName("requestCompaction reports whether the rewrite was dispatched")
    void requestCompaction_reportsWhetherItWasDispatched() throws IOException, InterruptedException {
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSeg, newSeg) -> CompactionOutcome.INSTALLED);

        Segment live = compactableSegment("dispatch-reported");

        assertThat(worker.requestCompaction(live))
            .as("a live worker dispatches the rewrite and says so")
            .isTrue();

        // The dispatch must finish before the arena is torn down in teardown.
        awaitCompactionCount(worker, 1);

        worker.stop();
        Segment afterStop = compactableSegment("refusal-reported");

        assertThat(worker.requestCompaction(afterStop))
            .as("a stopped worker refuses the rewrite and says so")
            .isFalse();
        assertThat(worker.getCompactionCount())
            .as("the refused request started nothing")
            .isEqualTo(1);
    }

    // ==================== Statistics Tests ====================

    @Test
    void getCompactionCount_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        assertThat(worker.getCompactionCount()).isZero();
    }

    @Test
    void getBytesReclaimed_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        assertThat(worker.getBytesReclaimed()).isZero();
    }

    @Test
    void getLastCompactionTime_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);
        assertThat(worker.getLastCompactionTime()).isZero();
    }

    // ==================== Configuration Setters Tests ====================

    @Test
    void setCompactionIntervalMs_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        worker.setCompactionIntervalMs(2000);
        assertThat(worker.getCompactionIntervalMs()).isEqualTo(2000);
    }

    @Test
    void setCompactionIntervalMs_invalidValue_throws() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        assertThatThrownBy(() -> worker.setCompactionIntervalMs(50))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTombstoneRatioThreshold_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        worker.setTombstoneRatioThreshold(0.5);
        assertThat(worker.getTombstoneRatioThreshold()).isEqualTo(0.5);
    }

    /**
     * The value this used to use, 64 MiB, is a write log's entire capacity - a trigger there
     * can never fire, so it is no longer accepted. The ceiling is what a valid value means.
     */
    @Test
    void setWriteLogSizeThreshold_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        worker.setWriteLogSizeThreshold(CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD);
        assertThat(worker.getWriteLogSizeThreshold())
            .isEqualTo(CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD);
    }

    @Test
    void setMaxConcurrentCompactions_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        worker.setMaxConcurrentCompactions(8);
        assertThat(worker.getMaxConcurrentCompactions()).isEqualTo(8);
    }

    // ==================== Integration Tests ====================

    @Test
    void backgroundCompaction_compactsWhenTombstoneThresholdExceeded()
            throws IOException, InterruptedException {
        AtomicBoolean compactionOccurred = new AtomicBoolean(false);
        CountDownLatch compactionLatch = new CountDownLatch(1);

        // Create worker with very low tombstone threshold
        CompactionWorker worker = new CompactionWorker(
            arena,
            (oldSeg, newSeg) -> {
                compactionOccurred.set(true);
                compactionLatch.countDown();
                return CompactionOutcome.INSTALLED;
            },
            100,  // Check every 100ms
            0.05, // 5% tombstone threshold (very aggressive)
            32 * 1024 * 1024L,
            1
        );

        Segment segment = createTestSegment("auto-compact-test");

        // Add entries - more than 5% will be tombstoned
        for (int i = 0; i < 20; i++) {
            segment.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
        }

        // Delete 2 entries (10% tombstone ratio, exceeds 5% threshold)
        segment.delete("key-0".getBytes());
        segment.delete("key-1".getBytes());

        worker.registerSegment(segment);
        worker.start();

        // Wait for automatic compaction
        boolean compacted = compactionLatch.await(10, TimeUnit.SECONDS);
        assertThat(compacted).isTrue();

        worker.stop();
    }

    /**
     * A reader that overlaps a compaction is served the value, not an error and not a miss.
     *
     * <p>This used to start the reader and the compaction together and hope they overlapped,
     * with the listener sleeping 50ms to help. The overlap was therefore never guaranteed - and
     * whichever way the race fell, the test then tore the arena down on the strength of a latch
     * counted down inside the listener, which is the middle of the rewrite rather than its end.
     * Here the rewrite is pinned by a latch this test owns, so the read is issued while a
     * compaction is demonstrably in flight every run.</p>
     *
     * <p>Starting the reader is not the same as the reader having issued its read. A virtual
     * thread that has not been scheduled yet reaches {@code get} after the rewrite has retired
     * the parent and sees a closed segment - a race in the test, not a property of the engine,
     * and one that failed roughly one run in five. The rewrite is therefore held until the
     * reader is observably parked, which it can only be on the segment lock the rewrite holds.</p>
     *
     * <p>What the reader is entitled to is the value, whether it is served before the swap or
     * after it. It is not entitled to be served <em>during</em> the rewrite: the rewrite holds
     * the segment's write lock from the copy through to the registry swap, deliberately, so
     * that no write can be accepted and lost in between - so a read issued mid-rewrite waits
     * for it and is then served out of the retired mapping. Asserting otherwise would be
     * asserting a property this engine does not have.</p>
     */
    @Test
    @DisplayName("a read issued while a compaction is in flight is still served its value")
    void compaction_servesAReadThatOverlapsIt() throws IOException, InterruptedException {
        CountDownLatch insideTheRewrite = new CountDownLatch(1);
        CountDownLatch releaseTheRewrite = new CountDownLatch(1);
        AtomicBoolean readSucceeded = new AtomicBoolean(false);
        AtomicReference<RuntimeException> readFailure = new AtomicReference<>();

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            insideTheRewrite.countDown();
            awaitRelease(releaseTheRewrite);
            return CompactionOutcome.INSTALLED;
        });

        Segment segment = createTestSegment("non-blocking-test");
        byte[] key = "test-key".getBytes();
        segment.put(key, "test-value".getBytes());
        segment.delete("deleted-key".getBytes()); // Create a tombstone

        worker.requestCompaction(segment);
        assertThat(insideTheRewrite.await(30, TimeUnit.SECONDS))
            .as("the compaction is in flight before the read is issued")
            .isTrue();

        Thread readThread = Thread.ofVirtual().name("overlapping-reader").start(() -> {
            try {
                readSucceeded.set(segment.get(key) != null);
            } catch (RuntimeException e) {
                readFailure.set(e);
            }
        });

        awaitParkedOnTheSegmentLock(readThread);
        releaseTheRewrite.countDown();

        assertThat(readThread.join(Duration.ofSeconds(30)))
            .as("the overlapping read finished")
            .isTrue();

        // Completion, not the callback: stop() is what waits for the rewrite the request
        // dispatched, and teardown closes the arena it is mapped out of straight afterwards.
        worker.stop();

        assertThat(readFailure.get())
            .as("a compaction in flight must not turn a read into an error")
            .isNull();
        assertThat(readSucceeded.get())
            .as("the overlapping read was served the value")
            .isTrue();
        assertThat(worker.getCompactionCount())
            .as("the compaction completed before stop() returned")
            .isEqualTo(1);
    }

    @Test
    void toString_containsRelevantInfo() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> CompactionOutcome.INSTALLED);

        String str = worker.toString();

        assertThat(str).contains("CompactionWorker");
        assertThat(str).contains("registeredSegments=0");
        assertThat(str).contains("compactions=0");
        assertThat(str).contains("bytesReclaimed=0");
    }

    // ==================== Helper Methods ====================

    @Test
    void compaction_leavesLiveDataAtTheCanonicalSegmentName() throws IOException, InterruptedException {
        CountDownLatch compactionLatch = new CountDownLatch(1);
        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
                compactionLatch.countDown();
                return CompactionOutcome.INSTALLED;
            });

        Path segmentPath = tempDir.resolve("segment_0.dat");
        Path walPath = tempDir.resolve("segment_0.wal");
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        testSegments.add(segment);
        segment.attachWriteLog(new WriteLog(walPath));

        byte[] key = "keep-me".getBytes();
        segment.put(key, "keep-value".getBytes());
        segment.put("drop-me".getBytes(), "drop-value".getBytes());
        segment.delete("drop-me".getBytes());

        worker.requestCompaction(segment);
        assertThat(compactionLatch.await(30, TimeUnit.SECONDS)).isTrue();

        // The canonical name must still be the live segment: it is the only name
        // SegmentDiscovery matches on the next start.
        assertThat(Files.exists(segmentPath))
            .as("segment_0.dat must hold the compacted data after the swap")
            .isTrue();
        assertThat(Files.exists(walPath)).as("segment_0.wal must exist").isTrue();

        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .as("no scratch or double-extension file may survive a successful compaction")
                .noneMatch(name -> name.endsWith(".compact") || name.endsWith(".dat.dat")
                    || name.endsWith(".dat.wal"));
        }

        worker.stop();
    }

    @Test
    @DisplayName("compaction sizes the replacement from the original, not one region larger")
    void compaction_doesNotGrowTheSlotTable() throws IOException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Segment> replacement = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            replacement.set(newSeg);
            latch.countDown();
            return CompactionOutcome.INSTALLED;
        });

        Segment segment = createTestSegment("segment_slots");
        segment.attachWriteLog(new WriteLog(tempDir.resolve("segment_slots.wal")));
        int before = segment.slotCount();

        segment.put("keep".getBytes(), "value".getBytes());
        segment.put("drop".getBytes(), "value".getBytes());
        segment.delete("drop".getBytes());

        worker.requestCompaction(segment);
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

        // The Segment constructor adds its own 1MB data region on top of the slot table it
        // derives from the byte count. A caller that also adds one is sizing the slot table
        // from a byte count that includes it, so every generation of compaction doubles the
        // table while the data region it is meant to serve stays exactly as big as it was.
        assertThat(replacement.get().slotCount())
            .as("a compaction must not widen the slot table it was asked to reproduce")
            .isEqualTo(before);

        worker.stop();
    }

    /**
     * Waits for a latch a test controls, from inside a compaction listener.
     *
     * @param latch The latch the test counts down when the rewrite may proceed
     */
    /**
     * Waits until a thread has parked, so a read is known to be in flight and not merely started.
     *
     * <p>The reader's only park point is the segment lock the rewrite holds, so a parked reader
     * is a reader that has entered {@code get} and is waiting the rewrite out. Polling the thread
     * state is the only way to see that from outside: the read offers no hook of its own, and a
     * latch the reader counts down before calling {@code get} would still leave it descheduled
     * between the two.</p>
     *
     * @param thread The reader
     * @throws InterruptedException if this thread is interrupted while waiting
     */
    private static void awaitParkedOnTheSegmentLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                || state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError(
            "the reader never parked, so the read was never issued against the live rewrite");
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the rewrite");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while held inside the rewrite", e);
        }
    }

    /**
     * Waits for a worker to have finished the given number of rewrites.
     *
     * <p>The count is credited after a rewrite has returned, so reaching it means no compaction
     * is still reading or writing anything mapped out of the arena.</p>
     *
     * @param worker   The worker to watch
     * @param expected How many completed compactions to wait for
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitCompactionCount(CompactionWorker worker, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (worker.getCompactionCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(worker.getCompactionCount())
            .as("the rewrite finished before the arena was torn down")
            .isEqualTo(expected);
    }

    /**
     * Creates a segment with one live entry and one tombstone, so a rewrite has work to do.
     *
     * @param name Base name for the segment file
     * @return The segment, registered for teardown
     * @throws IOException if the segment cannot be created
     */
    private Segment compactableSegment(String name) throws IOException {
        Segment segment = createTestSegment(name);
        segment.put("test-key".getBytes(), "test-value".getBytes());
        byte[] deleteKey = "delete-key".getBytes();
        segment.put(deleteKey, "delete-value".getBytes());
        segment.delete(deleteKey);
        return segment;
    }

    private Segment createTestSegment(String name) throws IOException {
        Path segmentPath = tempDir.resolve(name + ".dat");
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        testSegments.add(segment);
        return segment;
    }
}
