package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        testSegments = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
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
            (oldSeg, newSeg) -> callbackInvoked.set(true),
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
        assertThatThrownBy(() -> new CompactionWorker(null, (o, n) -> {}))
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
            arena, (o, n) -> {}, 50, 0.2, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Compaction interval");
    }

    @Test
    void constructor_withInvalidTombstoneThreshold_throwsIAE() {
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, -0.1, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tombstone ratio threshold");

        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, 1.5, 32 * 1024 * 1024L, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tombstone ratio threshold");
    }

    @Test
    void constructor_withInvalidWriteLogThreshold_throwsIAE() {
        // Too small
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, 0.2, 1024, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Write log size threshold");

        // Too large
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, 0.2, 2L * 1024 * 1024 * 1024, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Write log size threshold");
    }

    @Test
    void constructor_withInvalidMaxConcurrent_throwsIAE() {
        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, 0.2, 32 * 1024 * 1024L, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max concurrent compactions");

        assertThatThrownBy(() -> new CompactionWorker(
            arena, (o, n) -> {}, 1000, 0.2, 32 * 1024 * 1024L, 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max concurrent compactions");
    }

    // ==================== Lifecycle Tests ====================

    @Test
    void start_startsBackgroundThread() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        assertThat(worker.start()).isTrue();
        assertThat(worker.isRunning()).isTrue();

        // Starting again should return false
        assertThat(worker.start()).isFalse();

        worker.stop();
    }

    @Test
    void stop_terminatesBackgroundThread() throws InterruptedException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        worker.start();
        assertThat(worker.isRunning()).isTrue();

        worker.stop();

        // Give time for thread to terminate
        Thread.sleep(100);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void stop_whenNotRunning_doesNothing() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        // Should not throw
        assertThatCode(worker::stop).doesNotThrowAnyException();
    }

    // ==================== Segment Registration Tests ====================

    @Test
    void registerSegment_addsToMonitoringList() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);

        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);
    }

    @Test
    void registerSegment_nullSegment_throwsNPE() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        assertThatThrownBy(() -> worker.registerSegment(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerSegment_duplicateSegment_doesNotDuplicate() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);
        worker.registerSegment(segment);

        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);
    }

    @Test
    void unregisterSegment_removesFromMonitoringList() throws IOException {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        Segment segment = createTestSegment("test-segment");

        worker.registerSegment(segment);
        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(1);

        worker.unregisterSegment(segment);
        assertThat(worker.getRegisteredSegmentCount()).isZero();
    }

    // ==================== Manual Compaction Tests ====================

    @Test
    void requestCompaction_triggersCompaction() throws IOException, InterruptedException {
        AtomicReference<Segment> oldSegmentRef = new AtomicReference<>();
        AtomicReference<Segment> newSegmentRef = new AtomicReference<>();
        CountDownLatch compactionLatch = new CountDownLatch(1);

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            oldSegmentRef.set(oldSeg);
            newSegmentRef.set(newSeg);
            compactionLatch.countDown();
        });

        Segment segment = createTestSegment("test-segment");
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();
        segment.put(key, value);

        // Add a tombstone to trigger compaction eligibility
        byte[] deleteKey = "delete-key".getBytes();
        segment.put(deleteKey, "delete-value".getBytes());
        segment.delete(deleteKey);

        worker.requestCompaction(segment);

        // Wait for compaction to complete
        boolean completed = compactionLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify compaction happened
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

    // ==================== Statistics Tests ====================

    @Test
    void getCompactionCount_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        assertThat(worker.getCompactionCount()).isZero();
    }

    @Test
    void getBytesReclaimed_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        assertThat(worker.getBytesReclaimed()).isZero();
    }

    @Test
    void getLastCompactionTime_initiallyZero() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});
        assertThat(worker.getLastCompactionTime()).isZero();
    }

    // ==================== Configuration Setters Tests ====================

    @Test
    void setCompactionIntervalMs_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        worker.setCompactionIntervalMs(2000);
        assertThat(worker.getCompactionIntervalMs()).isEqualTo(2000);
    }

    @Test
    void setCompactionIntervalMs_invalidValue_throws() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        assertThatThrownBy(() -> worker.setCompactionIntervalMs(50))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTombstoneRatioThreshold_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        worker.setTombstoneRatioThreshold(0.5);
        assertThat(worker.getTombstoneRatioThreshold()).isEqualTo(0.5);
    }

    @Test
    void setWriteLogSizeThreshold_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        worker.setWriteLogSizeThreshold(64 * 1024 * 1024L);
        assertThat(worker.getWriteLogSizeThreshold()).isEqualTo(64 * 1024 * 1024L);
    }

    @Test
    void setMaxConcurrentCompactions_validValue_updates() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

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

    @Test
    void compaction_doesNotBlockReads() throws IOException, InterruptedException {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch compactionComplete = new CountDownLatch(1);
        AtomicBoolean readSucceeded = new AtomicBoolean(false);

        CompactionWorker worker = new CompactionWorker(arena, (oldSeg, newSeg) -> {
            try {
                // Wait for read to start before completing compaction
                readStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(50); // Small delay to ensure read overlaps with compaction
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            compactionComplete.countDown();
        });

        Segment segment = createTestSegment("non-blocking-test");
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();
        segment.put(key, value);
        segment.delete("deleted-key".getBytes()); // Create a tombstone

        // Start read thread
        Thread readThread = Thread.ofVirtual().start(() -> {
            readStarted.countDown();
            // Read should succeed even during compaction
            var result = segment.get(key);
            if (result != null) {
                readSucceeded.set(true);
            }
        });

        // Trigger compaction
        worker.requestCompaction(segment);

        // Wait for both to complete
        readThread.join(5000);
        boolean compactionDone = compactionComplete.await(5, TimeUnit.SECONDS);

        assertThat(readSucceeded.get()).isTrue();
        assertThat(compactionDone).isTrue();

        worker.stop();
    }

    @Test
    void toString_containsRelevantInfo() {
        CompactionWorker worker = new CompactionWorker(arena, (o, n) -> {});

        String str = worker.toString();

        assertThat(str).contains("CompactionWorker");
        assertThat(str).contains("registeredSegments=0");
        assertThat(str).contains("compactions=0");
        assertThat(str).contains("bytesReclaimed=0");
    }

    // ==================== Helper Methods ====================

    private Segment createTestSegment(String name) throws IOException {
        Path segmentPath = tempDir.resolve(name + ".dat");
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        testSegments.add(segment);
        return segment;
    }
}
