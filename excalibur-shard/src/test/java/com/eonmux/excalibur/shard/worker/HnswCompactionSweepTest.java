package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an on-demand sweep over the registered HNSW graphs owes its caller.
 *
 * <p>A sweep is the only way an operator can reclaim graph space on request, so the answer it
 * returns is the only thing they have to go on. One unusable registration must not cost them
 * the rest of the run, and a count of rebuilt graphs must mean graphs where something was
 * actually reclaimed - otherwise "12 graphs compacted" after a forced run is a number with no
 * meaning behind it.</p>
 */
@DisplayName("On-demand HNSW compaction sweep")
class HnswCompactionSweepTest {

    @TempDir
    Path tempDir;

    private HnswCompactionWorker worker;
    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private Random random;
    private final List<HnswGraph> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        worker = new HnswCompactionWorker();
        config = HnswConfig.create(16);
        distanceFunction = CosineSimilarity.getInstance();
        random = new Random(7);
    }

    @AfterEach
    void tearDown() {
        worker.stop();
        for (HnswGraph graph : opened) {
            if (!graph.isClosed()) {
                graph.close();
            }
        }
    }

    private HnswGraph graph(String name, int vectors, int tombstones) throws IOException {
        HnswGraph graph = new HnswGraph(tempDir.resolve(name), config, distanceFunction, 1000);
        opened.add(graph);
        for (int i = 0; i < vectors; i++) {
            float[] vector = new float[config.dimension()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = random.nextFloat();
            }
            long nodeId = graph.insert(vector);
            graph.registerDocumentId(nodeId, "doc-" + name + "-" + i);
        }
        for (int i = 0; i < tombstones; i++) {
            graph.delete(i);
        }
        return graph;
    }

    @Test
    @DisplayName("a closed registration is counted and the sweep carries on to the rest")
    void aClosedRegistrationDoesNotAbortTheSweep() throws IOException {
        HnswGraph stale = graph("stale.hnsw", 4, 0);
        HnswGraph live = graph("live.hnsw", 10, 6);
        stale.close();

        worker.registerGraph(stale);
        worker.registerGraph(live);

        // A stale registration must not escape as an exception: the sweep is the operator's
        // only handle on reclamation, and one dead graph cannot cost them the rest of it.
        HnswCompactionWorker.GraphCompactionSummary summary = worker.compactNow(false);

        assertThat(summary.graphsConsidered())
            .as("both registrations were examined")
            .isEqualTo(2);
        assertThat(summary.graphsCompacted())
            .as("the live graph is over threshold and was rebuilt")
            .isEqualTo(1);
        assertThat(live.tombstoneCount())
            .as("the graph after the stale one in the sweep was actually reclaimed")
            .isZero();
        assertThat(worker.getRegisteredGraphCount())
            .as("a closed graph can never be compacted again, so it is retired")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("forcing a graph with nothing to reclaim reports it as skipped, not compacted")
    void forcingAGraphWithNoTombstonesIsNotACompaction() throws IOException {
        HnswGraph clean = graph("clean.hnsw", 8, 0);
        AtomicInteger notifications = new AtomicInteger();
        worker.setCompactionListener((g, removed) -> notifications.incrementAndGet());
        worker.registerGraph(clean);

        HnswCompactionWorker.GraphCompactionSummary summary = worker.compactNow(true);

        assertThat(summary.graphsConsidered()).isEqualTo(1);
        assertThat(summary.graphsCompacted())
            .as("nothing was reclaimed, so nothing was compacted")
            .isZero();
        assertThat(summary.graphsFailed()).isZero();
        assertThat(worker.getCompactionCount())
            .as("the lifetime counter must not grow for a graph that was left alone")
            .isZero();
        assertThat(notifications.get())
            .as("no node ID was renumbered, so no listener state needs discarding")
            .isZero();
    }

    @Test
    @DisplayName("forcing still rebuilds a graph the threshold would have left alone")
    void forcingStillRebuildsBelowThreshold() throws IOException {
        HnswGraph sparse = graph("sparse.hnsw", 20, 1);
        worker.registerGraph(sparse);

        assertThat(worker.compactNow(false).graphsCompacted())
            .as("one tombstone in twenty is below the 20% threshold")
            .isZero();

        HnswCompactionWorker.GraphCompactionSummary forced = worker.compactNow(true);

        assertThat(forced.graphsCompacted()).isEqualTo(1);
        assertThat(sparse.tombstoneCount()).isZero();
    }

    @Test
    @DisplayName("concurrent registration of one graph leaves one registration")
    void concurrentRegistrationDoesNotDuplicate() throws Exception {
        HnswGraph graph = graph("contended.hnsw", 2, 0);

        for (int attempt = 0; attempt < 50; attempt++) {
            HnswCompactionWorker contended = new HnswCompactionWorker();
            int registrars = 64;
            CountDownLatch ready = new CountDownLatch(registrars);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(registrars);

            for (int i = 0; i < registrars; i++) {
                Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        contended.registerGraph(graph);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(contended.getRegisteredGraphCount())
                .as("attempt %d: check-then-act lets two threads both add the same graph",
                    attempt)
                .isEqualTo(1);
        }
    }
}
