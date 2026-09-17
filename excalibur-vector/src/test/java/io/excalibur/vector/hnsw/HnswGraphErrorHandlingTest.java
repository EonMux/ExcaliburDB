package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.excalibur.vector.hnsw.HnswGraphTestVectors.randomVector;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for error handling and edge cases in HnswGraph.
 *
 * <p>These tests improve coverage by exercising:</p>
 * <ul>
 *   <li>Version checking</li>
 *   <li>Error handling paths</li>
 *   <li>File corruption detection</li>
 * </ul>
 */
@DisplayName("HnswGraph Error Handling Tests")
class HnswGraphErrorHandlingTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private static final int DIMENSION = 64;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("error_test.hnsw");
        config = HnswConfig.create(DIMENSION);
    }

    @Nested
    @DisplayName("File Version Checking")
    class VersionCheckTests {

        @Test
        @DisplayName("Valid version is accepted")
        void validVersion() throws IOException {
            // Create a valid graph
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));
                graph.flush();
            }

            // Reopen - should succeed
            try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
                assertThat(reopened.nodeCount()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Invalid magic number throws exception")
        void invalidMagicNumber() throws IOException {
            // Create a valid graph first
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));
                graph.flush();
            }

            // Corrupt the magic number (first 8 bytes)
            try (RandomAccessFile raf = new RandomAccessFile(graphPath.toFile(), "rw")) {
                raf.seek(0);
                raf.writeLong(0xDEADBEEFCAFEBABEL); // Invalid magic
            }

            // The header check must surface as IOException. It previously surfaced as
            // IllegalStateException("Already closed") because the cleanup path closed
            // the arena twice, masking the real failure.
            assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad magic number");
        }

        @Test
        @DisplayName("Invalid version throws exception")
        void invalidVersion() throws IOException {
            // Create a valid graph first
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));
                graph.flush();
            }

            // Corrupt the version number (offset 8)
            try (RandomAccessFile raf = new RandomAccessFile(graphPath.toFile(), "rw")) {
                raf.seek(8); // OFFSET_VERSION
                raf.writeInt(99999); // Invalid version
            }

            assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported HNSW version");
        }
    }

    @Nested
    @DisplayName("File Corruption Detection")
    class CorruptionDetectionTests {

        @Test
        @DisplayName("Empty file is handled")
        void emptyFile() throws IOException {
            // Create an empty file
            Files.write(graphPath, new byte[0]);

            // An unreadable header is an open failure, reported as IOException rather
            // than leaking the cleanup path's IllegalStateException.
            assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("Non-existent file is handled")
        void nonExistentFile() {
            Path nonExistent = tempDir.resolve("nonexistent.hnsw");

            assertThatThrownBy(() -> HnswGraph.open(nonExistent, L2Distance.getInstance()))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("Truncated file throws exception")
        void truncatedFile() throws IOException {
            // Create a valid graph first
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));
                graph.flush();
            }

            // Truncate the file to just the header
            try (FileChannel channel = FileChannel.open(graphPath,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.truncate(100); // Truncate to just header
            }

            // Try to open - should throw due to truncated file
            assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
                .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Insert with null vector throws IllegalArgumentException")
        void insertNullVector() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                assertThatThrownBy(() -> graph.insert(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
            }
        }

        @Test
        @DisplayName("Insert with wrong dimension throws IAE")
        void insertWrongDimension() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                float[] wrongDim = new float[32]; // Should be 64
                assertThatThrownBy(() -> graph.insert(wrongDim))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimension");
            }
        }

        @Test
        @DisplayName("Delete non-existent node does not throw")
        void deleteNonExistent() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                // Should not throw - just ignore
                assertThatCode(() -> graph.delete(999999))
                    .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Read non-existent node returns null")
        void readNonExistentNode() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                HnswGraph.NodeView node = graph.readNode(999999);
                assertThat(node).isNull();
            }
        }

        @Test
        @DisplayName("Search empty graph returns empty list")
        void searchEmptyGraph() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);
                assertThat(results).isEmpty();
            }
        }

        @Test
        @DisplayName("Multiple flushes work correctly")
        void multipleFlushes() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 10; i++) {
                    graph.insert(randomVector(DIMENSION));
                    graph.flush();
                }

                assertThat(graph.nodeCount()).isEqualTo(10);
            }
        }

        @Test
        @DisplayName("InsertBatch with empty list does not throw")
        void insertBatchEmpty() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                List<float[]> empty = List.of();
                assertThatCode(() -> graph.insertBatch(empty))
                    .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("InsertBatch with null elements throws IllegalArgumentException")
        void insertBatchNullElements() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                List<float[]> vectors = new ArrayList<>();
                vectors.add(randomVector(DIMENSION));
                vectors.add(null);
                vectors.add(randomVector(DIMENSION));

                assertThatThrownBy(() -> graph.insertBatch(vectors))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
            }
        }
    }

    @Nested
    @DisplayName("Close and Reopen")
    class CloseReopenTests {

        @Test
        @DisplayName("Double close does not throw")
        void doubleClose() throws IOException {
            HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000);
            graph.insert(randomVector(DIMENSION));
            graph.close();
            // Second close should not throw
            assertThatCode(() -> graph.close())
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Operations after close throw")
        void operationsAfterClose() throws IOException {
            HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000);
            graph.insert(randomVector(DIMENSION));
            graph.close();

            // Most operations should throw after close
            assertThatThrownBy(() -> graph.insert(randomVector(DIMENSION)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Search Edge Cases")
    class SearchEdgeCaseTests {

        @Test
        @DisplayName("Search with topK 1 returns at most 1 result")
        void searchTopKOne() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 1);
                assertThat(results.size()).isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Search with large topK returns available nodes")
        void searchLargeTopK() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 5; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 1000);
                // Should return at most 5 results (the number of nodes)
                assertThat(results.size()).isLessThanOrEqualTo(5);
            }
        }

        @Test
        @DisplayName("Search after all deletions returns empty")
        void searchAfterAllDeletions() throws IOException {
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 5; i++) {
                    graph.insert(randomVector(DIMENSION));
                }

                // Delete all nodes
                for (long i = 0; i < 5; i++) {
                    graph.delete(i);
                }

                List<HnswSearchResult> results = graph.search(randomVector(DIMENSION), 10);
                assertThat(results).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Persistence Tests")
    class PersistenceTests {

        @Test
        @DisplayName("Graph persists and reopens correctly")
        void graphPersistsAndReopens() throws IOException {
            // Create and populate graph
            try (HnswGraph graph = new HnswGraph(graphPath, config, L2Distance.getInstance(), 1000)) {
                for (int i = 0; i < 20; i++) {
                    graph.insert(randomVector(DIMENSION));
                }
                graph.flush();
            }

            // Reopen and verify
            try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
                assertThat(reopened.nodeCount()).isEqualTo(20);

                // Search should work
                List<HnswSearchResult> results = reopened.search(randomVector(DIMENSION), 5);
                assertThat(results.size()).isLessThanOrEqualTo(5);
            }
        }

        @Test
        @DisplayName("Config persists correctly")
        void configPersistsCorrectly() throws IOException {
            HnswConfig customConfig = new HnswConfig(32, 64, 100, 100, 0.36, DIMENSION);

            // Create graph with custom config
            try (HnswGraph graph = new HnswGraph(graphPath, customConfig, L2Distance.getInstance(), 1000)) {
                graph.insert(randomVector(DIMENSION));
                graph.flush();
            }

            // Reopen and verify config
            try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
                assertThat(reopened.config().dimension()).isEqualTo(DIMENSION);
                assertThat(reopened.config().maxConnections()).isEqualTo(32);
            }
        }
    }
}
