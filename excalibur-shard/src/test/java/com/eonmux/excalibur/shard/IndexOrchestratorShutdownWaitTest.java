package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How long a shard's shutdown waits for its background work, and for whom.
 *
 * <p>The caller closes the arena the workers' segments are mapped out of once the shutdown
 * answers, and a node's shutdown hands it what its budget leaves the drains. So the wait has to
 * be exactly what it is handed: all of it for work that is still running, so a longer budget buys
 * a slow worker more time; and no more than that across every worker together, so the budget's
 * reserve for the durable writes is still there afterwards.</p>
 */
@DisplayName("Index orchestrator shutdown wait")
class IndexOrchestratorShutdownWaitTest {

    /** Far longer than any work here takes to stop once asked. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;
    private HnswGraph graph;
    private final CountDownLatch compacting = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (graph != null && !graph.isClosed()) {
            graph.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * Starts the orchestrator with its graph compaction held inside a compaction until
     * released - a worker that has been asked to stop and cannot yet.
     */
    private void startWithACompactionHeld() throws IOException, InterruptedException {
        HnswConfig config = HnswConfig.create(8);
        graph = new HnswGraph(tempDir.resolve("held.hnsw"), config,
            CosineSimilarity.getInstance(), 100);
        Random random = new Random(11);
        for (int i = 0; i < 12; i++) {
            float[] vector = new float[config.dimension()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = random.nextFloat();
            }
            long nodeId = graph.insert(vector);
            graph.registerDocumentId(nodeId, "doc-" + i);
        }
        for (int i = 0; i < 8; i++) {
            graph.delete(i);
        }
        HnswCompactionWorker graphCompaction = orchestrator.getHnswCompactionWorker();
        graphCompaction.registerGraph(graph);
        graphCompaction.setCompactionListener((compacted, removed) -> {
            compacting.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        orchestrator.start();
        assertThat(compacting.await(30, TimeUnit.SECONDS))
            .as("the graph compaction worker reached a compaction")
            .isTrue();
    }

    @Test
    @DisplayName("a worker still running is given the whole wait, once across every worker, and "
        + "a later shutdown waits again")
    void theWholeWaitIsGivenOnce() throws Exception {
        startWithACompactionHeld();
        Duration wait = Duration.ofMillis(400);

        long began = System.nanoTime();
        boolean ended = orchestrator.shutdown(wait);
        Duration took = Duration.ofNanos(System.nanoTime() - began);

        assertThat(ended)
            .as("a compaction still running holds the storage it is mapped out of")
            .isFalse();
        assertThat(took)
            .as("the held worker was given all of the wait")
            .isGreaterThanOrEqualTo(wait);
        assertThat(took)
            .as("the sweep, eight workers and the term filter rebuilds share the one wait "
                + "rather than each taking it again")
            .isLessThan(wait.multipliedBy(3));

        release.countDown();
        assertThat(orchestrator.shutdown(STOP_WAIT))
            .as("given long enough, a later shutdown sees the released compaction end")
            .isTrue();
    }

    @Test
    @DisplayName("an idle orchestrator stops at once, and a missing wait is refused")
    void anIdleOrchestratorStopsAtOnce() {
        orchestrator.start();

        assertThat(orchestrator.shutdown(STOP_WAIT)).isTrue();
        assertThatThrownBy(() -> orchestrator.shutdown(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> orchestrator.stop(null))
            .isInstanceOf(NullPointerException.class);
    }
}
