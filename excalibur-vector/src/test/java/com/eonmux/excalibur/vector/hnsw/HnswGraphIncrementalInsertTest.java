package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.bruteForceSearch;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.calculateRecall;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.generateNormalizedVector;
import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Incremental and batch insertion into {@link HnswGraph}.
 *
 * <p>Covers the live-indexing entry points that report what they did:
 * {@code insertWithResult} returns the node id, level, data offset, originating
 * write-log offset and the connections it made, and {@code insertBatch} /
 * {@code insertBatchWithResults} do the same for a whole batch. Also covers that
 * indexing this way — one vector at a time, at capacity, or mixed with a prior batch —
 * still produces a graph with usable recall.</p>
 */
class HnswGraphIncrementalInsertTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("test.hnsw");
        config = HnswConfig.create(64); // 64-dimensional vectors
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    void shouldInsertWithResult() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] vector = randomVector(64);
            IncrementalInsertResult result = graph.insertWithResult(vector);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.nodeId()).isEqualTo(0);
            assertThat(result.level()).isGreaterThanOrEqualTo(0);
            assertThat(result.dataOffset()).isGreaterThanOrEqualTo(0);
            assertThat(result.writeLogOffset()).isEqualTo(-1); // Not from write log
        }
    }

    @Test
    void shouldInsertWithResultAndWriteLogOffset() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            float[] vector = randomVector(64);
            long writeLogOffset = 12345L;
            IncrementalInsertResult result = graph.insertWithResult(vector, writeLogOffset);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.writeLogOffset()).isEqualTo(writeLogOffset);
        }
    }

    @Test
    void shouldTrackConnectionsInResult() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert first node - no connections
            IncrementalInsertResult result1 = graph.insertWithResult(randomVector(64));
            assertThat(result1.connectionsMade()).isEqualTo(0);

            // Insert second node - should connect to first
            IncrementalInsertResult result2 = graph.insertWithResult(randomVector(64));
            assertThat(result2.connectionsMade()).isGreaterThan(0);
            assertThat(result2.connectedNodes()).contains(0L);
        }
    }

    @Test
    void shouldInsertBatch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                vectors.add(randomVector(64));
            }

            List<Long> nodeIds = graph.insertBatch(vectors);

            assertThat(nodeIds).hasSize(50);
            assertThat(graph.nodeCount()).isEqualTo(50);

            // Verify all node IDs are sequential
            for (int i = 0; i < 50; i++) {
                assertThat(nodeIds.get(i)).isEqualTo(i);
            }
        }
    }

    @Test
    void shouldInsertBatchWithResults() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<float[]> vectors = new ArrayList<>();
            List<Long> writeLogOffsets = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                vectors.add(randomVector(64));
                writeLogOffsets.add((long) i * 100);
            }

            List<IncrementalInsertResult> results = graph.insertBatchWithResults(vectors, writeLogOffsets);

            assertThat(results).hasSize(20);
            for (int i = 0; i < 20; i++) {
                IncrementalInsertResult result = results.get(i);
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.nodeId()).isEqualTo(i);
                assertThat(result.writeLogOffset()).isEqualTo(i * 100);
            }
        }
    }

    @Test
    void shouldMaintainRecallAfterIncrementalInsertions() throws IOException {
        DistanceFunction distFn = L2Distance.getInstance();
        int dimension = 64;
        int numVectors = 200;
        int numQueries = 20;
        int k = 10;

        try (HnswGraph graph = new HnswGraph(graphPath, config, distFn, 1000)) {
            Random seededRandom = new Random(42);
            List<float[]> vectors = new ArrayList<>();

            // Insert vectors incrementally (simulating live indexing)
            for (int i = 0; i < numVectors; i++) {
                float[] vec = generateNormalizedVector(dimension, seededRandom);
                vectors.add(vec);
                IncrementalInsertResult result = graph.insertWithResult(vec);
                assertThat(result.isSuccess()).isTrue();
            }

            // Test recall
            Random queryRandom = new Random(99);
            double totalRecall = 0.0;

            for (int i = 0; i < numQueries; i++) {
                float[] query = generateNormalizedVector(dimension, queryRandom);
                List<Integer> groundTruth = bruteForceSearch(query, vectors, distFn, k);
                List<HnswSearchResult> hnswResults = graph.search(query, k);
                totalRecall += calculateRecall(hnswResults, groundTruth);
            }

            double averageRecall = totalRecall / numQueries;
            assertThat(averageRecall)
                .as("Incremental insertion should maintain reasonable recall (got %.2f%%)", averageRecall * 100)
                .isGreaterThanOrEqualTo(0.90);
        }
    }

    /**
     * A batch that exactly fills the graph is accepted in full.
     *
     * <p>The allowed side of the capacity boundary: {@code maxNodes} vectors fit, and the
     * last of them is not refused for being the last.</p>
     */
    @Test
    void shouldFillToCapacityWithBatchInsertion() throws IOException {
        long maxNodes = 100;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, maxNodes)) {
            List<Long> nodeIds = graph.insertBatch(vectors((int) maxNodes));

            assertThat(nodeIds).hasSize((int) maxNodes);
            assertThat(graph.nodeCount()).isEqualTo(maxNodes);
        }
    }

    /**
     * What a full graph does with the insert that would overflow it.
     *
     * <p>The refusal is not uniform across the insertion API, and the difference is
     * deliberate: {@code insert} and {@code insertBatch} throw
     * {@link IllegalStateException}, while the result-returning paths —
     * {@code insertWithResult} and {@code insertBatchWithResults} — answer
     * {@link IncrementalInsertResult#FAILED} so a caller draining a write log can record
     * the refusal and carry on. Both are published contracts, so both are pinned here.</p>
     *
     * <p>A batch is not atomic either: the vectors ahead of the one that crosses the line
     * are already in the graph when the exception is thrown, and they stay there. What the
     * graph must not do is grow past {@code maxNodes} or stop answering queries.</p>
     */
    @Test
    void shouldRefuseInsertionsBeyondCapacity() throws IOException {
        long maxNodes = 100;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, maxNodes)) {
            graph.insertBatch(vectors((int) maxNodes - 1));
            assertThat(graph.nodeCount()).isEqualTo(maxNodes - 1);

            // Three more vectors against one remaining slot: the first is taken, the second
            // crosses the line and the third never gets a chance.
            List<float[]> overflowing = vectors(3);
            assertThatThrownBy(() -> graph.insertBatch(overflowing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph is full: " + maxNodes + "/" + maxNodes);

            assertThat(graph.nodeCount())
                .as("The refused vectors must not be counted, and the accepted one must be")
                .isEqualTo(maxNodes);

            // Still a working index, not a wedged one.
            assertThat(graph.search(randomVector(64), 10)).hasSize(10);

            assertThatThrownBy(() -> graph.insert(randomVector(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph is full: " + maxNodes + "/" + maxNodes);

            assertThat(graph.insertWithResult(randomVector(64)))
                .as("insertWithResult reports the refusal instead of throwing")
                .isEqualTo(IncrementalInsertResult.FAILED);

            assertThat(graph.insertBatchWithResults(vectors(2), null))
                .as("insertBatchWithResults reports one refusal per vector")
                .containsExactly(IncrementalInsertResult.FAILED, IncrementalInsertResult.FAILED);

            assertThat(graph.nodeCount()).isEqualTo(maxNodes);
        }
    }

    /**
     * Builds {@code count} distinct 64-dimensional vectors to feed a batch insert.
     *
     * @param count How many vectors to build
     * @return A mutable list of freshly generated vectors
     */
    private static List<float[]> vectors(int count) {
        List<float[]> vectors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            vectors.add(randomVector(64));
        }
        return vectors;
    }

    @Test
    void shouldIncrementallyInsertAfterBatch() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Batch insert first 50
            List<float[]> batch = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                batch.add(randomVector(64));
            }
            graph.insertBatch(batch);
            assertThat(graph.nodeCount()).isEqualTo(50);

            // Incrementally insert more
            for (int i = 0; i < 25; i++) {
                IncrementalInsertResult result = graph.insertWithResult(randomVector(64));
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.nodeId()).isEqualTo(50 + i);
            }
            assertThat(graph.nodeCount()).isEqualTo(75);

            // Search should work across all nodes
            List<HnswSearchResult> results = graph.search(randomVector(64), 10);
            assertThat(results).isNotEmpty();
        }
    }

}
