package com.eonmux.excalibur.core.trace;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-stage latency accounting.
 *
 * <p>Retrieval latency is always the sum of several stages, and without the breakdown the only
 * available action when a query is slow is to guess which one to optimise. These tests pin the
 * properties the breakdown has to have to be trustworthy: stages accumulate rather than overwrite,
 * concurrent stages are counted correctly, and a stage that threw still reports the time it spent.
 */
@DisplayName("QueryTrace")
class QueryTraceTest {

    @Test
    @DisplayName("timing a supplier records the stage and returns its value")
    void timesASupplier() {
        QueryTrace trace = new QueryTrace();

        String result = trace.time(Stage.PLAN, () -> "planned");

        assertThat(result).isEqualTo("planned");
        assertThat(trace.nanosIn(Stage.PLAN)).isPositive();
        assertThat(trace.entriesIn(Stage.PLAN)).isEqualTo(1);
    }

    @Test
    @DisplayName("timing a runnable records the stage")
    void timesARunnable() {
        QueryTrace trace = new QueryTrace();
        boolean[] ran = { false };

        trace.time(Stage.LINK, () -> ran[0] = true);

        assertThat(ran[0]).isTrue();
        assertThat(trace.nanosIn(Stage.LINK)).isPositive();
    }

    @Test
    @DisplayName("a stage entered twice accumulates rather than overwriting")
    void stagesAccumulate() {
        // Traversal runs once per hop, so overwriting would report only the last hop's cost.
        QueryTrace trace = new QueryTrace();

        trace.record(Stage.TRAVERSE, 1_000);
        trace.record(Stage.TRAVERSE, 2_500);

        assertThat(trace.nanosIn(Stage.TRAVERSE)).isEqualTo(3_500);
        assertThat(trace.entriesIn(Stage.TRAVERSE)).isEqualTo(2);
    }

    @Test
    @DisplayName("a stage that throws still records the time it spent")
    void failingStageIsStillTimed() {
        // A stage that failed slowly is exactly the one worth seeing in a trace.
        QueryTrace trace = new QueryTrace();

        assertThatThrownBy(() -> trace.time(Stage.RANK, () -> {
            throw new IllegalStateException("signal down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(trace.nanosIn(Stage.RANK)).isPositive();
        assertThat(trace.entriesIn(Stage.RANK)).isEqualTo(1);
    }

    @Test
    @DisplayName("stages that were never entered report nothing")
    void untouchedStagesAreZero() {
        QueryTrace trace = new QueryTrace();
        trace.record(Stage.PLAN, 100);

        assertThat(trace.nanosIn(Stage.SERIALIZE)).isZero();
        assertThat(trace.entriesIn(Stage.SERIALIZE)).isZero();
        assertThat(trace.byStage())
            .containsOnlyKeys(Stage.PLAN);
    }

    @Test
    @DisplayName("byStage is a read-only view")
    void byStageIsUnmodifiable() {
        QueryTrace trace = new QueryTrace();
        trace.record(Stage.PLAN, 1);

        assertThatThrownBy(() -> trace.byStage().put(Stage.RANK, 5L))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("elapsed time is wall clock, not the sum of the stages")
    void elapsedIsWallClock() {
        // Stages overlap when work runs concurrently, and time outside any stage belongs to
        // neither -- a gap between the two is itself the finding.
        QueryTrace trace = new QueryTrace();
        trace.record(Stage.PLAN, 1_000_000_000L);

        assertThat(trace.elapsedNanos())
            .as("recording a fabricated second must not move the clock")
            .isLessThan(1_000_000_000L);
    }

    @Test
    @DisplayName("concurrent stages are all counted")
    void concurrentRecordingIsSafe() throws Exception {
        // Ranking fans out across threads that each time their own work.
        QueryTrace trace = new QueryTrace();
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        trace.record(Stage.RANK, 10);
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(trace.nanosIn(Stage.RANK)).isEqualTo(10L * threads * perThread);
        assertThat(trace.entriesIn(Stage.RANK)).isEqualTo((long) threads * perThread);
    }

    @Test
    @DisplayName("a negative duration is refused")
    void negativeDurationRefused() {
        assertThatThrownBy(() -> new QueryTrace().record(Stage.PLAN, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString reports milliseconds for the stages that ran")
    void toStringSummarises() {
        QueryTrace trace = new QueryTrace();
        trace.record(Stage.PLAN, 1_500_000);
        trace.record(Stage.SERIALIZE, 2_000_000);

        String summary = trace.toString();

        assertThat(summary).contains("plan=1.50ms").contains("serialize=2.00ms").contains("total=");
        assertThat(summary).doesNotContain("rank");
    }

    @Test
    @DisplayName("the stages are exactly the ones the pipeline runs")
    void stagesAreThePipeline() {
        // Only stages something records. A stage nothing times is still a name a response can be
        // documented as carrying, and a client written against that documentation waits for a
        // number this node never produces - it does no generation, so it has no prefill or decode.
        assertThat(Stage.values()).extracting(Enum::name).containsExactly(
            "PLAN", "LINK", "TRAVERSE", "RANK", "SERIALIZE");
    }
}
