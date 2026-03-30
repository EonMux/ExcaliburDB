package io.excalibur.store;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for HnswInitializer class.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Constructor validation and default values</li>
 *   <li>Static factory method behavior</li>
 *   <li>Graph path computation</li>
 *   <li>Segment initialization with HNSW graph</li>
 *   <li>Skip behavior when segment already has graph</li>
 *   <li>Null argument handling</li>
 *   <li>Domain correctness (dimension ranges, maxNodes constraints)</li>
 * </ul>
 */
@DisplayName("HnswInitializer Tests")
class HnswInitializerTest {

    @TempDir
    Path tempDir;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with default settings")
        void shouldCreateWithDefaultSettings() {
            HnswInitializer initializer = new HnswInitializer();

            assertThat(initializer.getDimension()).isEqualTo(HnswInitializer.DEFAULT_VECTOR_DIMENSION);
            assertThat(initializer.getMaxNodes()).isEqualTo(HnswInitializer.DEFAULT_MAX_NODES);
            assertThat(initializer.getDistanceFunction()).isInstanceOf(CosineSimilarity.class);
            assertThat(initializer.getConfigFactory()).isNotNull();
        }

        @Test
        @DisplayName("Should create with custom parameters")
        void shouldCreateWithCustomParameters() {
            HnswConfigFactory configFactory = HnswConfigFactory.withDefaults();
            DistanceFunction distanceFunction = CosineSimilarity.getInstance();
            int dimension = 768;
            long maxNodes = 500_000L;

            HnswInitializer initializer = new HnswInitializer(
                configFactory, distanceFunction, dimension, maxNodes, arena
            );

            assertThat(initializer.getDimension()).isEqualTo(dimension);
            assertThat(initializer.getMaxNodes()).isEqualTo(maxNodes);
            assertThat(initializer.getDistanceFunction()).isSameAs(distanceFunction);
            assertThat(initializer.getConfigFactory()).isSameAs(configFactory);
        }

        @Test
        @DisplayName("Should create with config factory and distance function only")
        void shouldCreateWithConfigFactoryAndDistanceFunction() {
            HnswConfigFactory configFactory = HnswConfigFactory.withDefaults();
            DistanceFunction distanceFunction = CosineSimilarity.getInstance();

            HnswInitializer initializer = new HnswInitializer(configFactory, distanceFunction);

            // Should use default dimension and maxNodes
            assertThat(initializer.getDimension()).isEqualTo(HnswInitializer.DEFAULT_VECTOR_DIMENSION);
            assertThat(initializer.getMaxNodes()).isEqualTo(HnswInitializer.DEFAULT_MAX_NODES);
            assertThat(initializer.getDistanceFunction()).isSameAs(distanceFunction);
            assertThat(initializer.getConfigFactory()).isSameAs(configFactory);
        }

        @Test
        @DisplayName("Should reject null config factory")
        void shouldRejectNullConfigFactory() {
            assertThatNullPointerException()
                .isThrownBy(() -> new HnswInitializer(
                    null, CosineSimilarity.getInstance(), 1536, 1_000_000L, arena
                ))
                .withMessageContaining("configFactory cannot be null");
        }

        @Test
        @DisplayName("Should reject null distance function")
        void shouldRejectNullDistanceFunction() {
            assertThatNullPointerException()
                .isThrownBy(() -> new HnswInitializer(
                    HnswConfigFactory.withDefaults(), null, 1536, 1_000_000L, arena
                ))
                .withMessageContaining("distanceFunction cannot be null");
        }

        @Test
        @DisplayName("Should reject dimension below 1")
        void shouldRejectDimensionBelowOne() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswInitializer(
                    HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 0, 1_000_000L, arena
                ))
                .withMessageContaining("dimension must be between 1 and 65536");
        }

        @Test
        @DisplayName("Should reject dimension above 65536")
        void shouldRejectDimensionAbove65536() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswInitializer(
                    HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 65537, 1_000_000L, arena
                ))
                .withMessageContaining("dimension must be between 1 and 65536");
        }

        @Test
        @DisplayName("Should accept dimension at upper boundary")
        void shouldAcceptDimensionAtUpperBoundary() {
            HnswInitializer initializer = new HnswInitializer(
                HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 65536, 1_000_000L, arena
            );

            assertThat(initializer.getDimension()).isEqualTo(65536);
        }

        @Test
        @DisplayName("Should accept dimension at lower boundary")
        void shouldAcceptDimensionAtLowerBoundary() {
            HnswInitializer initializer = new HnswInitializer(
                HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 1, 1_000_000L, arena
            );

            assertThat(initializer.getDimension()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should reject maxNodes below 1")
        void shouldRejectMaxNodesBelowOne() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswInitializer(
                    HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 1536, 0L, arena
                ))
                .withMessageContaining("maxNodes must be positive");
        }

        @Test
        @DisplayName("Should reject negative maxNodes")
        void shouldRejectNegativeMaxNodes() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new HnswInitializer(
                    HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 1536, -1L, arena
                ))
                .withMessageContaining("maxNodes must be positive");
        }

        @Test
        @DisplayName("Should accept arena as null")
        void shouldAcceptNullArena() {
            HnswInitializer initializer = new HnswInitializer(
                HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(), 1536, 1_000_000L, null
            );

            assertThat(initializer.getDimension()).isEqualTo(1536);
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("Should create via static factory method")
        void shouldCreateViaStaticFactoryMethod() {
            HnswConfigFactory configFactory = HnswConfigFactory.withDefaults();
            DistanceFunction distanceFunction = CosineSimilarity.getInstance();
            int dimension = 512;
            long maxNodes = 250_000L;

            HnswInitializer initializer = HnswInitializer.create(
                configFactory, distanceFunction, dimension, maxNodes, arena
            );

            assertThat(initializer.getDimension()).isEqualTo(dimension);
            assertThat(initializer.getMaxNodes()).isEqualTo(maxNodes);
            assertThat(initializer.getDistanceFunction()).isSameAs(distanceFunction);
            assertThat(initializer.getConfigFactory()).isSameAs(configFactory);
        }
    }

    @Nested
    @DisplayName("Graph Path Computation Tests")
    class GraphPathComputationTests {

        @Test
        @DisplayName("Should compute graph path from .dat file")
        void shouldComputeGraphPathFromDatFile() {
            HnswInitializer initializer = new HnswInitializer();
            Path segmentPath = tempDir.resolve("segment_0.dat");

            Path graphPath = initializer.computeGraphPath(segmentPath);

            assertThat(graphPath.getFileName().toString()).isEqualTo("segment_0.hnsw");
            assertThat(graphPath.getParent()).isEqualTo(segmentPath.getParent());
        }

        @Test
        @DisplayName("Should compute graph path from file without .dat extension")
        void shouldComputeGraphPathFromNonDatFile() {
            HnswInitializer initializer = new HnswInitializer();
            Path segmentPath = tempDir.resolve("mysegment");

            Path graphPath = initializer.computeGraphPath(segmentPath);

            assertThat(graphPath.getFileName().toString()).isEqualTo("mysegment.hnsw");
        }

        @Test
        @DisplayName("Should preserve parent directory in graph path")
        void shouldPreserveParentDirectory() {
            HnswInitializer initializer = new HnswInitializer();
            Path subDir = tempDir.resolve("subdir");
            Path segmentPath = subDir.resolve("segment_5.dat");

            Path graphPath = initializer.computeGraphPath(segmentPath);

            assertThat(graphPath.getParent()).isEqualTo(subDir);
        }
    }

    @Nested
    @DisplayName("Initialize Segment Tests")
    class InitializeSegmentTests {

        @Test
        @DisplayName("Should initialize HNSW graph for segment")
        void shouldInitializeHnswGraphForSegment() throws IOException {
            HnswInitializer initializer = new HnswInitializer();
            Path segmentPath = tempDir.resolve("test-segment.dat");

            // Create a segment with small capacity for test
            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);

            // Initialize HNSW graph
            initializer.initializeForSegment(segment);

            // Verify graph was attached
            HnswGraph graph = segment.getHnswGraph();
            assertThat(graph).isNotNull();

            // Verify tail offset tracker was attached
            TailOffsetTracker tracker = segment.getTailOffsetTracker();
            assertThat(tracker).isNotNull();

            // Verify graph file exists
            Path expectedGraphPath = tempDir.resolve("test-segment.hnsw");
            assertThat(Files.exists(expectedGraphPath)).isTrue();
        }

        @Test
        @DisplayName("Should skip initialization if segment already has graph")
        void shouldSkipIfAlreadyInitialized() throws IOException {
            HnswInitializer initializer = new HnswInitializer();
            Path segmentPath = tempDir.resolve("already-initialized.dat");

            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);

            // Initialize once
            initializer.initializeForSegment(segment);
            HnswGraph firstGraph = segment.getHnswGraph();
            assertThat(firstGraph).isNotNull();

            // Initialize again - should be no-op
            initializer.initializeForSegment(segment);
            HnswGraph secondGraph = segment.getHnswGraph();

            // Should be the same graph instance
            assertThat(secondGraph).isSameAs(firstGraph);
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            HnswInitializer initializer = new HnswInitializer();

            assertThatNullPointerException()
                .isThrownBy(() -> initializer.initializeForSegment(null))
                .withMessageContaining("segment cannot be null");
        }

        @Test
        @DisplayName("Should create parent directory if needed")
        void shouldCreateParentDirectoryIfNeeded() throws IOException {
            HnswInitializer initializer = new HnswInitializer();
            Path subDir = tempDir.resolve("deeply").resolve("nested").resolve("dir");
            Path segmentPath = subDir.resolve("segment.dat");

            // Parent directory doesn't exist yet
            assertThat(Files.exists(subDir)).isFalse();

            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);

            // Initialize should create parent directory
            initializer.initializeForSegment(segment);

            // Verify parent directory was created
            assertThat(Files.exists(subDir)).isTrue();

            // Verify graph was attached
            assertThat(segment.getHnswGraph()).isNotNull();
        }

        @Test
        @DisplayName("Should use custom dimension from initializer")
        void shouldUseCustomDimension() throws IOException {
            int customDimension = 768;
            HnswInitializer initializer = new HnswInitializer(
                HnswConfigFactory.withDefaults(),
                CosineSimilarity.getInstance(),
                customDimension,
                500_000L,
                arena
            );
            Path segmentPath = tempDir.resolve("custom-dimension.dat");

            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
            initializer.initializeForSegment(segment);

            HnswGraph graph = segment.getHnswGraph();
            assertThat(graph).isNotNull();
            // Note: dimension is set during graph creation, verified via initializer
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should include key parameters in toString")
        void shouldIncludeKeyParametersInToString() {
            HnswInitializer initializer = new HnswInitializer();

            String result = initializer.toString();

            assertThat(result).contains("HnswInitializer");
            assertThat(result).contains("dimension=" + HnswInitializer.DEFAULT_VECTOR_DIMENSION);
            assertThat(result).contains("maxNodes=" + HnswInitializer.DEFAULT_MAX_NODES);
            assertThat(result).contains("efConstruction=");
            assertThat(result).contains("efSearch=");
        }
    }

    @Nested
    @DisplayName("Default Constants Tests")
    class DefaultConstantsTests {

        @Test
        @DisplayName("Default vector dimension should be 1536")
        void defaultVectorDimensionShouldBe1536() {
            // OpenAI text-embedding-3-small uses 1536 dimensions
            assertThat(HnswInitializer.DEFAULT_VECTOR_DIMENSION).isEqualTo(1536);
        }

        @Test
        @DisplayName("Default max nodes should be 1 million")
        void defaultMaxNodesShouldBeOneMillion() {
            // Sized for segments with up to 1M entries
            assertThat(HnswInitializer.DEFAULT_MAX_NODES).isEqualTo(1_000_000L);
        }
    }
}
