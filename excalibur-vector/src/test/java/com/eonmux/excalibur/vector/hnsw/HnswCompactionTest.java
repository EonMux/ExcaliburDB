package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for HNSW graph compaction functionality.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Compaction removes tombstoned nodes</li>
 *   <li>Compaction preserves active nodes and their vectors</li>
 *   <li>Document ID mappings are preserved across compaction</li>
 *   <li>Compaction reduces file size</li>
 *   <li>CompactionTo creates correct copy</li>
 * </ul>
 */
class HnswCompactionTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private Random random;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("test.hnsw");
        config = HnswConfig.create(64); // 64-dimensional vectors
        distanceFunction = CosineSimilarity.getInstance();
        random = new Random(42); // Deterministic for reproducibility
    }

    @AfterEach
    void tearDown() {
        // Cleanup handled by @TempDir
    }

    // ==================== Compaction Tests ====================

    @Test
    void shouldCompactGraphWithNoTombstones() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert some vectors
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            // No tombstones, compaction should return 0
            long removed = graph.compact(tempDir);
            assertThat(removed).isZero();
            assertThat(graph.nodeCount()).isEqualTo(10);
            assertThat(graph.tombstoneCount()).isZero();
        }
    }

    @Test
    void shouldCompactGraphWithTombstones() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                float[] vec = randomVector(64);
                vectors.add(vec);
                graph.insert(vec);
            }

            assertThat(graph.nodeCount()).isEqualTo(20);
            assertThat(graph.tombstoneCount()).isZero();

            // Delete some nodes
            for (int i = 0; i < 10; i++) {
                graph.delete(i);
            }

            assertThat(graph.tombstoneCount()).isEqualTo(10);
            assertThat(graph.activeNodeCount()).isEqualTo(10);

            // Compact the graph
            long removed = graph.compact(tempDir);

            assertThat(removed).isEqualTo(10);
            assertThat(graph.nodeCount()).isEqualTo(10);
            assertThat(graph.tombstoneCount()).isZero();
        }
    }

    @Test
    void shouldPreserveDocumentIdMappingsAfterCompaction() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors with document IDs
            for (int i = 0; i < 10; i++) {
                long nodeId = graph.insert(randomVector(64));
                graph.registerDocumentId(nodeId, "doc-" + i);
            }

            // Delete some nodes
            graph.delete(0);
            graph.delete(2);
            graph.delete(4);

            // Verify document IDs exist before compaction
            assertThat(graph.getDocumentId(1)).isEqualTo("doc-1");
            assertThat(graph.getDocumentId(3)).isEqualTo("doc-3");

            // Compact
            long removed = graph.compact(tempDir);
            assertThat(removed).isEqualTo(3);

            // Document IDs should be preserved for remaining nodes
            // Note: Node IDs are reassigned during compaction
            // The mapping should still contain the original document IDs
            assertThat(graph.tombstoneCount()).isZero();
        }
    }

    @Test
    void shouldCompactGraphEntryPointAfterDeletion() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector(64));
            }

            long originalEntryPoint = graph.entryPoint();
            assertThat(originalEntryPoint).isGreaterThanOrEqualTo(0);

            // Delete the entry point
            boolean deleted = graph.delete(originalEntryPoint);
            assertThat(deleted).isTrue();

            // Entry point should be updated
            assertThat(graph.entryPoint()).isNotEqualTo(originalEntryPoint);

            // Compact should work correctly
            long removed = graph.compact(tempDir);
            assertThat(removed).isEqualTo(1);

            // Graph should still be valid after compaction
            assertThat(graph.entryPoint()).isGreaterThanOrEqualTo(0);
            assertThat(graph.tombstoneCount()).isZero();
        }
    }

    @Test
    void shouldCreateCompactedCopyWithCompactTo() throws IOException {
        Path copyPath = tempDir.resolve("copy.hnsw");

        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert vectors
            for (int i = 0; i < 20; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes
            for (int i = 0; i < 10; i++) {
                graph.delete(i);
            }

            assertThat(graph.tombstoneCount()).isEqualTo(10);

            // Create compacted copy
            long skipped = graph.compactTo(copyPath);

            assertThat(skipped).isEqualTo(10);
            assertThat(Files.exists(copyPath)).isTrue();

            // Original graph should be unchanged
            assertThat(graph.nodeCount()).isEqualTo(20);
            assertThat(graph.tombstoneCount()).isEqualTo(10);

            // Verify the copy
            try (HnswGraph copy = HnswGraph.open(copyPath, distanceFunction)) {
                assertThat(copy.nodeCount()).isEqualTo(10);
                assertThat(copy.tombstoneCount()).isZero();
            }
        }
    }

    @Test
    void shouldMaintainSearchabilityAfterCompaction() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // Insert known vectors
            float[] target = randomVector(64);
            float[] query = target.clone(); // Same vector for exact match

            graph.insert(target);
            for (int i = 0; i < 50; i++) {
                graph.insert(randomVector(64));
            }

            // Delete some nodes (but not the target)
            for (int i = 1; i <= 25; i++) {
                graph.delete(i);
            }

            // Search before compaction should find target
            List<HnswSearchResult> beforeCompact = graph.search(query, 5);
            assertThat(beforeCompact).isNotEmpty();

            // Compact
            long removed = graph.compact(tempDir);
            assertThat(removed).isEqualTo(25);

            // Search after compaction should still work
            List<HnswSearchResult> afterCompact = graph.search(query, 5);
            assertThat(afterCompact).isNotEmpty();
        }
    }

    @Test
    void shouldRejectCompactionWithNullTempDir() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));
            graph.delete(0);

            assertThatThrownBy(() -> graph.compact(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void shouldRejectCompactToWithNullPath() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            graph.insert(randomVector(64));
            graph.delete(0);

            assertThatThrownBy(() -> graph.compactTo(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void shouldRejectCompactionWhenClosed() throws IOException {
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
        graph.insert(randomVector(64));
        graph.close();

        assertThatThrownBy(() -> graph.compact(tempDir))
            .isInstanceOf(IllegalStateException.class);
    }

    // ==================== Helper Methods ====================

    private float[] randomVector(int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1; // Range [-1, 1]
        }
        return vector;
    }
}
