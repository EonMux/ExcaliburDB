package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HnswCompactionWorker}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Worker lifecycle (start/stop)</li>
 *   <li>Graph registration/unregistration</li>
 *   <li>Compaction threshold checking</li>
 *   <li>Configuration validation</li>
 * </ul>
 */
class HnswCompactionWorkerTest {

    /** Far longer than any work here takes to stop once asked. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private HnswCompactionWorker worker;
    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private Random random;

    @BeforeEach
    void setUp() {
        worker = new HnswCompactionWorker();
        config = HnswConfig.create(64);
        distanceFunction = CosineSimilarity.getInstance();
        random = new Random(42);
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.stop(STOP_WAIT);
        }
    }

    // ==================== Lifecycle Tests ====================

    @Test
    void shouldStartWorker() {
        boolean started = worker.start();
        assertThat(started).isTrue();
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void shouldNotStartTwice() {
        worker.start();
        boolean startedAgain = worker.start();
        assertThat(startedAgain).isFalse();
    }

    @Test
    void shouldStopWorker() {
        worker.start();
        worker.stop(STOP_WAIT);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void shouldStopIdempotently() {
        worker.stop(STOP_WAIT); // Should not throw
        worker.stop(STOP_WAIT); // Should not throw
        assertThat(worker.isRunning()).isFalse();
    }

    // ==================== Graph Registration Tests ====================

    @Test
    void shouldRegisterGraph() throws IOException {
        Path graphPath = tempDir.resolve("test.hnsw");
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            worker.registerGraph(graph);
            assertThat(worker.getRegisteredGraphCount()).isEqualTo(1);
        }
    }

    @Test
    void shouldNotRegisterNullGraph() {
        worker.registerGraph(null);
        assertThat(worker.getRegisteredGraphCount()).isZero();
    }

    @Test
    void shouldNotRegisterDuplicateGraph() throws IOException {
        Path graphPath = tempDir.resolve("test.hnsw");
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            worker.registerGraph(graph);
            worker.registerGraph(graph);
            assertThat(worker.getRegisteredGraphCount()).isEqualTo(1);
        }
    }

    @Test
    void shouldUnregisterGraph() throws IOException {
        Path graphPath = tempDir.resolve("test.hnsw");
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            worker.registerGraph(graph);
            worker.unregisterGraph(graph);
            assertThat(worker.getRegisteredGraphCount()).isZero();
        }
    }

    // ==================== Stopping Tests ====================

    @Test
    void stopReportsTheLoopEnded() {
        worker.start();
        assertThat(worker.stop(STOP_WAIT)).isTrue();
        assertThat(worker.stop(STOP_WAIT)).as("a second stop answers from the same state").isTrue();
    }

    @Test
    void stopOfAWorkerNeverStartedReportsEnded() {
        assertThat(worker.stop(STOP_WAIT)).isTrue();
    }

    @Test
    void stopWakesThePausingLoopWithoutWaitingOutTheInterval() {
        // The default interval is minutes; a stop that had to wait it out would outlast the
        // join bound and report the loop still running.
        worker.start();
        long startNanos = System.nanoTime();
        assertThat(worker.stop(STOP_WAIT)).isTrue();
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos))
            .isLessThan(5);
    }

    @Test
    void onDemandRunAskedToStopFirstExaminesNothing() throws IOException {
        try (HnswGraph first = new HnswGraph(tempDir.resolve("a.hnsw"), config, distanceFunction, 1000);
             HnswGraph second = new HnswGraph(tempDir.resolve("b.hnsw"), config, distanceFunction, 1000)) {
            worker.registerGraph(first);
            worker.registerGraph(second);

            HnswCompactionWorker.GraphCompactionSummary summary = worker.compactNow(true, () -> true);

            assertThat(summary.graphsConsidered()).isZero();
            assertThat(summary.graphsCompacted()).isZero();
            assertThat(summary.graphsFailed()).isZero();
        }
    }

    @Test
    void onDemandRunStopsBetweenGraphs() throws IOException {
        try (HnswGraph first = new HnswGraph(tempDir.resolve("a.hnsw"), config, distanceFunction, 1000);
             HnswGraph second = new HnswGraph(tempDir.resolve("b.hnsw"), config, distanceFunction, 1000)) {
            worker.registerGraph(first);
            worker.registerGraph(second);
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();

            // False before the first graph, true before the second.
            HnswCompactionWorker.GraphCompactionSummary summary =
                worker.compactNow(true, () -> reads.getAndIncrement() > 0);

            assertThat(summary.graphsConsidered())
                .as("the graph not reached is not counted as considered")
                .isEqualTo(1);
            assertThat(reads).hasValue(2);
            assertThat(first.isClosed()).isFalse();
            assertThat(second.isClosed()).isFalse();
        }
    }

    // ==================== Configuration Tests ====================

    @Test
    void shouldCreateWithCustomConfiguration() {
        HnswCompactionWorker customWorker = new HnswCompactionWorker(30000, 0.10);
        assertThat(customWorker.getIntervalMs()).isEqualTo(30000);
        assertThat(customWorker.getTombstoneThreshold()).isEqualTo(0.10);
    }

    @Test
    void shouldRejectIntervalBelowMinimum() {
        assertThatThrownBy(() -> new HnswCompactionWorker(1000, 0.20))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectThresholdBelowMinimum() {
        assertThatThrownBy(() -> new HnswCompactionWorker(60000, 0.01))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectThresholdAboveMaximum() {
        assertThatThrownBy(() -> new HnswCompactionWorker(60000, 0.60))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateInterval() {
        worker.setIntervalMs(30000);
        assertThat(worker.getIntervalMs()).isEqualTo(30000);
    }

    @Test
    void shouldRejectInvalidIntervalUpdate() {
        assertThatThrownBy(() -> worker.setIntervalMs(1000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateThreshold() {
        worker.setTombstoneThreshold(0.30);
        assertThat(worker.getTombstoneThreshold()).isEqualTo(0.30);
    }

    @Test
    void shouldRejectInvalidThresholdUpdate() {
        assertThatThrownBy(() -> worker.setTombstoneThreshold(0.01))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== Statistics Tests ====================

    @Test
    void shouldTrackCompactionCount() {
        assertThat(worker.getCompactionCount()).isZero();
        assertThat(worker.getTotalNodesRemoved()).isZero();
    }

    // ==================== Domain Correctness Tests ====================

    @Test
    void shouldUseDefaultConfigurationValues() {
        HnswCompactionWorker defaultWorker = new HnswCompactionWorker();
        assertThat(defaultWorker.getIntervalMs()).isEqualTo(60000); // 1 minute
        assertThat(defaultWorker.getTombstoneThreshold()).isEqualTo(0.20); // 20%
    }

    @Test
    void shouldHaveCorrectDefaultConstants() {
        assertThat(HnswCompactionWorker.DEFAULT_INTERVAL_MS).isEqualTo(60000);
        assertThat(HnswCompactionWorker.DEFAULT_TOMBSTONE_THRESHOLD).isEqualTo(0.20);
        assertThat(HnswCompactionWorker.MIN_INTERVAL_MS).isEqualTo(10000);
        assertThat(HnswCompactionWorker.MIN_TOMBSTONE_THRESHOLD).isEqualTo(0.05);
        assertThat(HnswCompactionWorker.MAX_TOMBSTONE_THRESHOLD).isEqualTo(0.50);
    }

    @Test
    void shouldProvideStringRepresentation() {
        worker.setIntervalMs(30000);
        worker.setTombstoneThreshold(0.25);
        String str = worker.toString();
        assertThat(str).contains("intervalMs=30000");
        assertThat(str).contains("tombstoneThreshold=0.25");
    }
}
