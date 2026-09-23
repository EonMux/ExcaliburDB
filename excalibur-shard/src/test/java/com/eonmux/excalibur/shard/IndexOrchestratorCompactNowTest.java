package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("an interrupt from a caller that still sends one is read as a stop")
    void anInterruptedRunStopsAndReportsHonestly() {
        int live = liveSegmentCount();

        // The shutdown path no longer interrupts a run, but a caller that does must still get
        // a run that stops at the next safe point rather than one that starts channel work on a
        // thread whose next blocking channel call would close its channel.
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
    @DisplayName("a run asked to stop before it starts examines nothing and interrupts nothing")
    void aRunAskedToStopFirstDoesNothing() {
        int live = liveSegmentCount();

        CompactionReport report = orchestrator.compactNow(true, () -> true);

        assertThat(report.segmentsConsidered()).isEqualTo(live);
        assertThat(report.segmentsCompacted()).isZero();
        assertThat(report.segmentsFailed()).isZero();
        assertThat(report.segmentsSkipped()).isEqualTo(live);
        assertThat(report.graphsConsidered()).isZero();
        assertThat(Thread.currentThread().isInterrupted())
            .as("the stop is a signal, not an interrupt")
            .isFalse();
    }

    /**
     * The shutdown drain used to stop a run by interrupting its thread, and an interrupt that
     * lands inside a rewrite's channel operation closes that channel for every thread using it.
     * The drain now sets a signal; the run reads it before each segment and before the graphs.
     */
    @Test
    @DisplayName("a stop requested during a run takes effect before the next segment")
    void aStopRequestedMidRunEndsAtTheNextSegment() {
        int live = liveSegmentCount();
        AtomicInteger reads = new AtomicInteger();

        // False before the first segment, true from then on.
        CompactionReport report = orchestrator.compactNow(true, () -> reads.getAndIncrement() > 0);

        assertThat(report.segmentsCompacted())
            .as("exactly the segment started before the stop was compacted")
            .isEqualTo(Math.min(1, live));
        assertThat(report.segmentsFailed()).isZero();
        assertThat(report.graphsConsidered())
            .as("no graph is examined once the run has been asked to stop")
            .isZero();
        for (Segment segment : storageEngine.getSegments().values()) {
            assertThat(segment.isClosed())
                .as("the segment the run did compact was replaced, not left half done")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a null stop signal is refused")
    void aNullStopSignalIsRefused() {
        assertThatThrownBy(() -> orchestrator.compactNow(true, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("shutdown reports every worker ended, and says so again when asked twice")
    void shutdownReportsWorkersEnded() {
        orchestrator.start();

        assertThat(orchestrator.shutdown()).isTrue();
        assertThat(orchestrator.isRunning()).isFalse();
        assertThat(orchestrator.shutdown()).isTrue();
    }

    @Test
    @DisplayName("shutdown of an orchestrator never started reports ended")
    void shutdownOfAnUnstartedOrchestrator() {
        assertThat(orchestrator.shutdown()).isTrue();
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
