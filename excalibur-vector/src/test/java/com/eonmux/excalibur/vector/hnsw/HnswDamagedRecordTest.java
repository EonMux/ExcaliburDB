package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How graph walks and adjacency edits behave when a record names something that is not there.
 *
 * <p>The walks trust the ids they read from the mapping only as far as the node index vouches
 * for them: a damaged neighbour id must cost a walk nothing, and an edit must never write past
 * the record it addresses.</p>
 */
@DisplayName("HNSW damaged records")
class HnswDamagedRecordTest {

    private static final int DIMENSION = 16;
    private static final int NODE_COUNT = 300;

    /** Far past every node, and far past what a walk should ever size anything by. */
    private static final int DAMAGED_ID = Integer.MAX_VALUE - 1;

    /**
     * What one search may allocate. A visited set sized by a damaged id near
     * {@code Integer.MAX_VALUE} is 256 MB; a healthy search here allocates about 60 KB.
     */
    private static final long SEARCH_ALLOCATION_CEILING = 16L * 1024 * 1024;

    @TempDir
    Path tempDir;

    private HnswGraph graph;

    @BeforeEach
    void buildGraph() throws IOException {
        graph = new HnswGraph(tempDir.resolve("damaged.hnsw"), HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), NODE_COUNT + 16);
        Random random = new Random(314L);
        for (int i = 0; i < NODE_COUNT; i++) {
            graph.insert(randomVector(random));
        }
    }

    @AfterEach
    void closeGraph() {
        if (graph != null) {
            graph.close();
        }
    }

    @Test
    @DisplayName("a neighbour id past every node is skipped without sizing the walk by it")
    void shouldSkipADamagedNeighbourId() {
        damageEveryFirstNeighbour();
        float[] query = randomVector(new Random(1L));

        long allocated = leastAllocatedBy(() -> assertThat(graph.search(query, 10)).hasSize(10));

        assertThat(allocated).isLessThan(SEARCH_ALLOCATION_CEILING);
    }

    @Test
    @DisplayName("the quantised walk skips a damaged neighbour id the same way")
    void shouldSkipADamagedNeighbourIdInTheQuantisedWalk() {
        graph.trainProductQuantizer(0, 4, 16);
        damageEveryFirstNeighbour();
        float[] query = randomVector(new Random(2L));

        long allocated = leastAllocatedBy(() -> assertThat(graph.searchWithPQ(query, 10)).hasSize(10));

        assertThat(allocated).isLessThan(SEARCH_ALLOCATION_CEILING);
    }

    @Test
    @DisplayName("reading the vector of an id that names no node is refused rather than read from elsewhere")
    void shouldRefuseToReadAnUnknownNode() {
        graph.lock.readLock().lock();
        try {
            HnswTraversal traversal = HnswTraversal.open(graph);
            float[] query = new float[DIMENSION];

            assertThatThrownBy(() -> traversal.distance(query, NODE_COUNT + 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(NODE_COUNT + 5));
            assertThatThrownBy(() -> traversal.vector(-1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> traversal.readVector(DAMAGED_ID, new float[DIMENSION]))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    @Test
    @DisplayName("writing a layer a node does not have is refused")
    void shouldRefuseToWriteALayerTheNodeLacks() {
        long nodeId = nodeAtLevelZero();

        graph.lock.writeLock().lock();
        try {
            HnswTraversal traversal = HnswTraversal.open(graph);
            HnswNeighborLinker linker = new HnswNeighborLinker(graph);

            assertThatThrownBy(() -> linker.writeConnections(traversal, nodeId, 1, List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layer 1");
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    @Test
    @DisplayName("a link offered at a layer the node lacks is declined, leaving the bytes past its record alone")
    void shouldDeclineALinkAtALayerTheNodeLacks() {
        long nodeId = nodeAtLevelZero();

        graph.lock.writeLock().lock();
        try {
            HnswTraversal traversal = HnswTraversal.open(graph);
            long pastRecord = HnswNodeRecords.adjacencyOffset(
                graph.config, traversal.recordOffset(nodeId), 1);
            int span = Integer.BYTES * (1 + HnswNodeRecords.maxConnectionsAt(graph.config, 1));
            byte[] before = graph.mappedFile.readBytes(pastRecord, span);

            new HnswNeighborLinker(graph).addConnection(traversal, nodeId, 1, 0L);

            assertThat(graph.mappedFile.readBytes(pastRecord, span)).isEqualTo(before);
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Overwrites the first level-0 neighbour of every node, so every walk meets one.
     */
    private void damageEveryFirstNeighbour() {
        graph.lock.writeLock().lock();
        try {
            HnswTraversal traversal = HnswTraversal.open(graph);
            for (long id = 0; id < NODE_COUNT; id++) {
                long adjacency = HnswNodeRecords.adjacencyOffset(
                    graph.config, traversal.recordOffset(id), 0);
                if (graph.mappedFile.readInt(adjacency) > 1) {
                    graph.mappedFile.writeInt(adjacency + Integer.BYTES, DAMAGED_ID);
                }
            }
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    private long nodeAtLevelZero() {
        for (long id = 0; id < NODE_COUNT; id++) {
            if (HnswNodeRecords.readEntryLevel(graph.mappedFile, HnswNodeRecords.indexOffset(id)) == 0) {
                return id;
            }
        }
        throw new IllegalStateException("no level-0 node in the corpus");
    }

    /**
     * The least one run of {@code action} allocates on this thread, once warmed up.
     *
     * <p>The first runs of a path also allocate for class loading and the coverage agent; the
     * least of several warmed runs is what the path itself costs.</p>
     */
    private static long leastAllocatedBy(Runnable action) {
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        for (int warmup = 0; warmup < 5; warmup++) {
            action.run();
        }
        long least = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long before = threads.getThreadAllocatedBytes(thread);
            action.run();
            least = Math.min(least, threads.getThreadAllocatedBytes(thread) - before);
        }
        return least;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int d = 0; d < DIMENSION; d++) {
            vector[d] = (float) random.nextGaussian();
        }
        return vector;
    }
}
