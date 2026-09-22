package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the orchestrator forgets when one of its segments' HNSW graphs is rebuilt.
 *
 * <p>A graph compaction re-inserts the surviving nodes, so every node id changes. Node-level
 * visibility records are keyed by node id; kept across the rebuild, they would hand live nodes
 * the epochs of unrelated dead ones and queries would filter rows by the wrong epoch. The
 * orchestrator has to drop the rebuilt segment's records - and only that segment's.</p>
 */
@DisplayName("HNSW graph compaction bookkeeping")
class IndexOrchestratorGraphCompactionTest {

    private static final long COMPACTED_SEGMENT = 0L;
    private static final long OTHER_SEGMENT = 1L;
    private static final long VISIBLE_EPOCH = 5L;

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;
    private HnswGraph graph;

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

    @Test
    @DisplayName("a rebuilt graph's node visibility records are dropped, and no other segment's")
    void rebuildDropsOnlyThatSegmentsNodeVisibility() throws IOException {
        graph = graphWithTombstones(10, 6);
        orchestrator.registerSegment(COMPACTED_SEGMENT, storageEngine.getSegment(COMPACTED_SEGMENT),
            graph, null, new TailOffsetTracker(graph));

        EpochIsolationValidator epochIsolation = orchestrator.getEpochIsolationValidator();
        epochIsolation.registerNodeRange(COMPACTED_SEGMENT, 0L, VISIBLE_EPOCH);
        epochIsolation.registerNodeRange(OTHER_SEGMENT, 0L, VISIBLE_EPOCH);

        HnswCompactionWorker.GraphCompactionSummary summary =
            orchestrator.getHnswCompactionWorker().compactNow(true);

        assertThat(summary.graphsCompacted())
            .as("the registered graph had tombstones to reclaim")
            .isEqualTo(1);
        assertThat(epochIsolation.getNodeVisibilityEpoch(COMPACTED_SEGMENT, 0L))
            .as("node ids were reassigned by the rebuild, so the old records must be gone")
            .isEqualTo(-1L);
        assertThat(epochIsolation.getNodeVisibilityEpoch(OTHER_SEGMENT, 0L))
            .as("a segment whose graph was not rebuilt keeps its records")
            .isEqualTo(VISIBLE_EPOCH);
    }

    /**
     * Builds a graph with some of its nodes deleted.
     *
     * @param vectors    Number of vectors to insert
     * @param tombstones Number of the first nodes to delete
     * @return The open graph
     * @throws IOException if the graph file cannot be created
     */
    private HnswGraph graphWithTombstones(int vectors, int tombstones) throws IOException {
        HnswConfig config = HnswConfig.create(16);
        HnswGraph built = new HnswGraph(tempDir.resolve("segment_0.hnsw"), config,
            CosineSimilarity.getInstance(), 1000);
        Random random = new Random(11);
        for (int i = 0; i < vectors; i++) {
            float[] vector = new float[config.dimension()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = random.nextFloat();
            }
            long nodeId = built.insert(vector);
            built.registerDocumentId(nodeId, "doc-" + i);
        }
        for (int i = 0; i < tombstones; i++) {
            built.delete(i);
        }
        return built;
    }
}
