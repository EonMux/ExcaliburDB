package excalibur.query.executor;

import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fan-out that turns one query into per-segment work.
 *
 * <p>This class had no tests at all, while Wave 2 changed how it cancels in-flight work and how it
 * reports coverage. Both are properties a query depends on and neither is visible from a passing
 * end-to-end test: a dispatcher that silently reports full coverage over zero segments looks
 * exactly like one that searched everything and found nothing.
 */
@DisplayName("ParallelQueryDispatcher")
class ParallelQueryDispatcherTest {

    private static final int DIMENSION = 8;

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private ParallelQueryDispatcher dispatcher;

    /** No segment carries a graph here; these tests are about dispatch, not vector search. */
    private static final ParallelQueryDispatcher.HnswGraphProvider NO_GRAPHS = segmentId -> null;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        dispatcher = new ParallelQueryDispatcher(catalog, new ConcurrentHashMap<>(), NO_GRAPHS);
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        if (catalog != null) {
            catalog.close();
        }
        if (arena != null && arena.scope().isAlive()) {
            arena.close();
        }
    }

    private Segment segment(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.deleteIfExists(path);
        return new Segment(path, 1024 * 1024, arena);
    }

    private static float[] vector() {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = i / (float) DIMENSION;
        }
        return v;
    }

    private static QueryOptions options() {
        return QueryOptions.builder().topK(5).build();
    }

    private static DistanceFunction distance() {
        return L2Distance.getInstance();
    }

    @Test
    @DisplayName("constructor arguments are all required")
    void argumentsValidated() {
        Map<Long, Segment> empty = new HashMap<>();

        assertThatThrownBy(() -> new ParallelQueryDispatcher(null, empty, NO_GRAPHS))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParallelQueryDispatcher(catalog, null, NO_GRAPHS))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParallelQueryDispatcher(catalog, empty, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("dispatch rejects null arguments rather than failing inside a worker")
    void dispatchValidates() {
        assertThatThrownBy(() -> dispatcher.dispatch(null, options(), distance()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(vector(), null, distance()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(vector(), options(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("segments can be registered and unregistered")
    void registration() throws IOException {
        assertThat(dispatcher.segmentCount()).isZero();

        Segment first = segment("a.dat");
        dispatcher.registerSegment(1L, first);
        assertThat(dispatcher.segmentCount()).isEqualTo(1);

        dispatcher.registerSegment(2L, segment("b.dat"));
        assertThat(dispatcher.segmentCount()).isEqualTo(2);

        dispatcher.unregisterSegment(1L);
        assertThat(dispatcher.segmentCount()).isEqualTo(1);

        // Unregistering something absent is not an error; a retiring segment can be removed by
        // more than one path.
        dispatcher.unregisterSegment(1L);
        assertThat(dispatcher.segmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a query against no segments returns empty rather than failing")
    void dispatchWithNoSegments() {
        QueryResponse response = dispatcher.dispatch(vector(), options(), distance());

        assertThat(response).isNotNull();
        assertThat(response.results()).isEmpty();
        assertThat(response.coverage()).isNotNull();
    }

    @Test
    @DisplayName("a query over segments with no graph completes and reports coverage")
    void dispatchWithGraphlessSegments() throws IOException {
        dispatcher.registerSegment(1L, segment("g1.dat"));
        dispatcher.registerSegment(2L, segment("g2.dat"));

        QueryResponse response = dispatcher.dispatch(vector(), options(), distance());

        assertThat(response).isNotNull();
        assertThat(response.results()).isEmpty();
        assertThat(response.coverage()).isNotNull();
    }

    @Test
    @DisplayName("dispatchRaw exposes per-segment results and a duration")
    void dispatchRawReportsDetail() throws IOException {
        dispatcher.registerSegment(1L, segment("raw.dat"));

        // Null candidates means "no LSH pre-filter", i.e. consider every registered segment.
        ParallelQueryDispatcher.DispatchResult result =
            dispatcher.dispatchRaw(vector(), options(), distance(), null);

        assertThat(result).isNotNull();
        assertThat(result.segmentResults()).isNotNull();
        assertThat(result.mergedResults()).isNotNull();
        assertThat(result.coverage()).isNotNull();
        assertThat(result.durationMs()).isNotNegative();
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdownIsIdempotent() {
        dispatcher.shutdown();
        dispatcher.shutdown();
    }

    @Test
    @DisplayName("the default timeout and coverage threshold are published")
    void defaultsArePublished() {
        assertThat(ParallelQueryDispatcher.DEFAULT_TIMEOUT.toSeconds()).isEqualTo(30);
        assertThat(ParallelQueryDispatcher.DEFAULT_COVERAGE_THRESHOLD).isEqualTo(0.5);
    }
}
