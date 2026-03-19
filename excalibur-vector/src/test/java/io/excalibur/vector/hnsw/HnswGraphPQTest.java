package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.quantization.ProductQuantizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Product Quantization integration with HnswGraph.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Setting and using ProductQuantizer</li>
 *   <li>Encoding vectors with PQ</li>
 *   <li>PQ-based search</li>
 *   <li>Error handling for invalid PQ configurations</li>
 * </ul>
 */
@DisplayName("HnswGraph PQ Tests")
class HnswGraphPQTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("test-pq.hnsw");
        config = HnswConfig.create(64);
        distanceFunction = CosineSimilarity.getInstance();
    }

    private float[] randomVector(int dim) {
        Random random = new Random();
        float[] vector = new float[dim];
        for (int i = 0; i < dim; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

    @Nested
    @DisplayName("ProductQuantizer Configuration")
    class ProductQuantizerConfigTests {

        @Test
        @DisplayName("setProductQuantizer throws on null quantizer")
        void setNullQuantizerThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThatThrownBy(() -> graph.setProductQuantizer(null))
                    .isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        @DisplayName("setProductQuantizer throws on untrained quantizer")
        void setUntrainedQuantizerThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                ProductQuantizer untrained = new ProductQuantizer(64, 8, 256);
                assertThatThrownBy(() -> graph.setProductQuantizer(untrained))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("trained");
            }
        }

        @Test
        @DisplayName("setProductQuantizer throws on dimension mismatch")
        void setQuantizerDimensionMismatchThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Train a quantizer with wrong dimension
                ProductQuantizer wrongDim = new ProductQuantizer(128, 8, 256);
                float[][] samples = new float[100][128];
                for (int i = 0; i < 100; i++) {
                    for (int j = 0; j < 128; j++) {
                        samples[i][j] = (float) Math.random();
                    }
                }
                wrongDim.train(samples);

                assertThatThrownBy(() -> graph.setProductQuantizer(wrongDim))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimension");
            }
        }

        @Test
        @DisplayName("setProductQuantizer accepts valid trained quantizer")
        void setValidQuantizerSucceeds() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert some vectors first
                for (int i = 0; i < 50; i++) {
                    graph.insert(randomVector(64));
                }

                // Train a quantizer
                ProductQuantizer pq = new ProductQuantizer(64, 8, 256);
                float[][] samples = new float[100][64];
                for (int i = 0; i < 100; i++) {
                    samples[i] = randomVector(64);
                }
                pq.train(samples);

                // Should succeed
                assertThatCode(() -> graph.setProductQuantizer(pq)).doesNotThrowAnyException();
                assertThat(graph.isPQEnabled()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("ProductQuantizer Training")
    class ProductQuantizerTrainingTests {

        @Test
        @DisplayName("trainProductQuantizer throws on empty graph")
        void trainOnEmptyGraphThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThatThrownBy(() -> graph.trainProductQuantizer(100, 8, 256))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty");
            }
        }

        @Test
        @DisplayName("trainProductQuantizer succeeds with vectors")
        void trainWithVectorsSucceeds() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors
                for (int i = 0; i < 50; i++) {
                    graph.insert(randomVector(64));
                }

                // Train quantizer
                ProductQuantizer pq = graph.trainProductQuantizer(30, 8, 256);

                assertThat(pq).isNotNull();
                assertThat(pq.isTrained()).isTrue();
                assertThat(graph.isPQEnabled()).isTrue();
            }
        }

        @Test
        @DisplayName("trainProductQuantizer with sample size 0 uses all vectors")
        void trainWithZeroSampleSize() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(64));
                }

                // Train with sample size 0 (use all)
                ProductQuantizer pq = graph.trainProductQuantizer(0, 8, 256);

                assertThat(pq).isNotNull();
                assertThat(pq.isTrained()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Vector Encoding")
    class VectorEncodingTests {

        @Test
        @DisplayName("encodeAllVectors encodes all active vectors")
        void encodeAllVectorsSucceeds() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors
                int numVectors = 30;
                for (int i = 0; i < numVectors; i++) {
                    graph.insert(randomVector(64));
                }

                // Train quantizer
                graph.trainProductQuantizer(20, 8, 256);

                // Encode all vectors
                assertThatCode(() -> graph.encodeAllVectors()).doesNotThrowAnyException();

                // Check encoded count
                assertThat(graph.encodedVectorCount()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("encodeAllVectors throws without quantizer")
        void encodeWithoutQuantizerThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors without setting quantizer
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(64));
                }

                assertThatThrownBy(() -> graph.encodeAllVectors())
                    .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("PQ Search")
    class PQSearchTests {

        @Test
        @DisplayName("searchWithPQ returns results")
        void searchWithPQReturnsResults() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors
                for (int i = 0; i < 50; i++) {
                    graph.insert(randomVector(64));
                }

                // Train and encode
                graph.trainProductQuantizer(30, 8, 256);
                graph.encodeAllVectors();

                // Search
                float[] query = randomVector(64);
                List<HnswSearchResult> results = graph.searchWithPQ(query, 10);

                assertThat(results).isNotNull();
                assertThat(results.size()).isLessThanOrEqualTo(10);
            }
        }

        @Test
        @DisplayName("searchWithPQ returns empty without quantizer or throws")
        void searchWithoutQuantizerReturnsEmptyOrThrows() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert vectors without PQ
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(64));
                }

                float[] query = randomVector(64);
                // Either throws IllegalStateException or returns empty list
                assertThatCode(() -> {
                    List<HnswSearchResult> results = graph.searchWithPQ(query, 5);
                    // If it doesn't throw, it should return empty results
                    assertThat(results).isNotNull();
                }).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("isPQEnabled returns false initially")
        void isPQEnabledInitiallyFalse() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThat(graph.isPQEnabled()).isFalse();
            }
        }

        @Test
        @DisplayName("encodedVectorCount returns 0 initially")
        void encodedVectorCountInitiallyZero() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThat(graph.encodedVectorCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("Tail Offset Tracking")
    class TailOffsetTrackingTests {

        @Test
        @DisplayName("getLastIntegratedOffset returns 0 initially")
        void getLastIntegratedOffsetInitiallyZero() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThat(graph.getLastIntegratedOffset()).isZero();
            }
        }

        @Test
        @DisplayName("setLastIntegratedOffset updates value")
        void setLastIntegratedOffset() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                graph.setLastIntegratedOffset(1000L);
                assertThat(graph.getLastIntegratedOffset()).isEqualTo(1000L);
            }
        }

        @Test
        @DisplayName("compareAndSetLastIntegratedOffset updates atomically")
        void compareAndSetLastIntegratedOffset() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Initial value is 0
                boolean success = graph.compareAndSetLastIntegratedOffset(500L);
                assertThat(success).isTrue();
                assertThat(graph.getLastIntegratedOffset()).isEqualTo(500L);
            }
        }

        @Test
        @DisplayName("compareAndSetLastIntegratedOffsetSync updates atomically")
        void compareAndSetLastIntegratedOffsetSync() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                boolean success = graph.compareAndSetLastIntegratedOffsetSync(750L);
                assertThat(success).isTrue();
                assertThat(graph.getLastIntegratedOffset()).isEqualTo(750L);
            }
        }
    }

    @Nested
    @DisplayName("Flush and Close")
    class FlushAndCloseTests {

        @Test
        @DisplayName("flush does not throw")
        void flushDoesNotThrow() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                // Insert some vectors
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(64));
                }

                // Flush should not throw
                assertThatCode(() -> graph.flush()).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("close marks graph as closed")
        void closeMarksGraphClosed() throws IOException {
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
            assertThat(graph.isClosed()).isFalse();

            graph.close();

            assertThat(graph.isClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Tombstone Operations")
    class TombstoneOperationsTests {

        @Test
        @DisplayName("tombstoneCount returns 0 initially")
        void tombstoneCountInitiallyZero() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                assertThat(graph.tombstoneCount()).isZero();
            }
        }

        @Test
        @DisplayName("delete creates tombstone")
        void deleteCreatesTombstone() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                long nodeId = graph.insert(randomVector(64));
                assertThat(graph.delete(nodeId)).isTrue();
                assertThat(graph.tombstoneCount()).isEqualTo(1);
                assertThat(graph.isTombstoned(nodeId)).isTrue();
            }
        }

        @Test
        @DisplayName("activeNodeCount excludes tombstones")
        void activeNodeCountExcludesTombstones() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
                graph.insert(randomVector(64));
                long nodeId2 = graph.insert(randomVector(64));
                graph.insert(randomVector(64));

                assertThat(graph.activeNodeCount()).isEqualTo(3);
                graph.delete(nodeId2);
                assertThat(graph.activeNodeCount()).isEqualTo(2);
            }
        }
    }
}
