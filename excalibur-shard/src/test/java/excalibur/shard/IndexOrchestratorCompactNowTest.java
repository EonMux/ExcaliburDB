package excalibur.shard;

import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Compaction an operator can ask for, and an honest answer about what happened.
 *
 * <p>Reclaiming dead space was reachable only by waiting for the background loop. An operator
 * who has just erased a large document has no way to say "now", and no way to find out whether
 * anything was reclaimed. The entry point here is what an endpoint calls; what it must never do
 * is throw away the outcome - a run where one segment failed and thirty succeeded is a
 * different answer from either "it worked" or "it failed".</p>
 */
@DisplayName("Compaction on demand")
class IndexOrchestratorCompactNowTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    private int liveSegmentCount() {
        return (int) storageEngine.getSegments().values().stream().distinct().count();
    }

    @Test
    @DisplayName("an unforced run leaves segments the policy would not pick")
    void unforcedLeavesSegmentsAlone() {
        CompactionReport report = orchestrator.compactNow(false);

        assertThat(report.segmentsConsidered()).isEqualTo(liveSegmentCount());
        assertThat(report.segmentsCompacted()).isZero();
        assertThat(report.segmentsFailed()).isZero();
        assertThat(report.duration()).isNotNull();
        assertThat(report.duration().isNegative()).isFalse();
    }

    @Test
    @DisplayName("a forced run compacts every live segment, policy or not")
    void forcedCompactsEverySegment() {
        int live = liveSegmentCount();

        CompactionReport report = orchestrator.compactNow(true);

        assertThat(report.segmentsConsidered()).isEqualTo(live);
        assertThat(report.segmentsCompacted()).isEqualTo(live);
        assertThat(report.segmentsFailed()).isZero();
    }

    @Test
    @DisplayName("a segment that cannot be compacted is counted, not thrown")
    void aFailingSegmentIsCountedAndTheRunContinues() throws IOException {
        int live = liveSegmentCount();

        // A non-empty directory sitting where the compaction scratch file has to be created
        // fails that one segment's rewrite and nothing else.
        Path blocker = tempDir.resolve("segment_0.dat.compact");
        Files.createDirectory(blocker);
        Files.createFile(blocker.resolve("in-the-way"));

        CompactionReport report = orchestrator.compactNow(true);

        assertThat(report.segmentsConsidered()).isEqualTo(live);
        assertThat(report.segmentsFailed()).isEqualTo(1);
        assertThat(report.segmentsCompacted()).isEqualTo(live - 1);
    }

    @Test
    @DisplayName("an interrupted run stops where it is and reports only what it did")
    void anInterruptedRunStopsAndReportsHonestly() {
        int live = liveSegmentCount();

        // The shutdown path interrupts the thread a forced run is on and then closes the
        // storage engine's arena. A run that never looks at the flag carries on into segments
        // whose memory is being unmapped underneath it, which takes the JVM down rather than
        // raising anything the caller could report.
        Thread.currentThread().interrupt();
        try {
            CompactionReport report = orchestrator.compactNow(true);

            assertThat(report.segmentsConsidered()).isEqualTo(live);
            assertThat(report.segmentsCompacted()).isZero();
            assertThat(report.segmentsFailed()).isZero();
            assertThat(report.segmentsSkipped())
                .as("segments the run never reached are skipped, not compacted")
                .isEqualTo(live);
            assertThat(Thread.currentThread().isInterrupted())
                .as("the caller's interrupt has to outlive the call it interrupted")
                .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("it is safe to call while the background worker is running")
    void safeAlongsideTheBackgroundWorker() {
        orchestrator.start();

        assertThatCode(() -> orchestrator.compactNow(true)).doesNotThrowAnyException();

        for (Segment segment : storageEngine.getSegments().values()) {
            assertThat(segment.isClosed()).isFalse();
        }
    }
}
