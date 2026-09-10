package excalibur.query.executor;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResponse;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /** Dispatchers and graphs built per test, closed together at the end of it. */
    private final List<ParallelQueryDispatcher> extraDispatchers = new ArrayList<>();
    private final List<HnswGraph> openGraphs = new ArrayList<>();

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
        for (ParallelQueryDispatcher extra : extraDispatchers) {
            extra.shutdown();
        }
        for (HnswGraph graph : openGraphs) {
            try {
                graph.close();
            } catch (Exception ignored) {
                // The dispatcher never owns these; closing is only tidy-up.
            }
        }
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

    /** A query that asks to be spared partially-indexed segments. */
    private static QueryOptions strict() {
        return QueryOptions.builder().topK(5).includePartialSegments(false).build();
    }

    private static QueryOptions permissive() {
        return QueryOptions.builder().topK(5).includePartialSegments(true).build();
    }

    private HnswGraph graph(String name) throws IOException {
        HnswGraph created = new HnswGraph(
            tempDir.resolve(name), HnswConfig.create(DIMENSION), distance(), 1024);
        openGraphs.add(created);
        return created;
    }

    /**
     * Registers a segment with the catalog and the returned dispatcher, driven to {@code state}
     * through the transitions the catalog actually permits.
     */
    private void register(ParallelQueryDispatcher target, long segmentId, LifecycleState state,
            boolean withGraph, Map<Long, HnswGraph> graphs) throws IOException {
        catalog.add(segmentId, tempDir.resolve("vol-" + segmentId + ".dat"));
        if (state != LifecycleState.INDEXING) {
            catalog.completeIndexing(segmentId);
        }
        if (state == LifecycleState.ACTIVE || state == LifecycleState.COMPACTING) {
            catalog.completeWarmup(segmentId);
        }
        if (state == LifecycleState.COMPACTING) {
            catalog.beginCompaction(segmentId);
        }
        if (withGraph) {
            graphs.put(segmentId, graph("g-" + segmentId + ".hnsw"));
        }
        target.registerSegment(segmentId, segment("s-" + segmentId + ".dat"));
    }

    private ParallelQueryDispatcher dispatcherOver(Map<Long, HnswGraph> graphs) {
        ParallelQueryDispatcher created =
            new ParallelQueryDispatcher(catalog, new ConcurrentHashMap<>(), graphs::get);
        extraDispatchers.add(created);
        return created;
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

        ParallelQueryDispatcher.DispatchResult result =
            dispatcher.dispatchRaw(vector(), options(), distance());

        assertThat(result).isNotNull();
        assertThat(result.segmentResults()).isNotNull();
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

    /**
     * Segment selection is where a query's answer is decided long before any vector is compared:
     * a segment left out here is absent from the results with nothing logged, nothing thrown and -
     * until now - nothing said in the coverage the client reads back.
     */
    @Nested
    @DisplayName("Which segments a query is permitted to search")
    class SegmentSelection {

        @Test
        @DisplayName("a strict query reads a warming segment")
        void strictQueryReadsWarming() throws IOException {
            // Warming means every entry is in and a product quantizer is being trained. No
            // production query path reads a PQ code, so the answer a warming segment gives is
            // the answer an active one gives.
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.WARMING, true, graphs);

            QueryResponse response = subject.dispatch(vector(), strict(), distance());

            assertThat(response.coverage().segmentsSearched())
                .as("a warming segment holds a complete answer and must be searched")
                .isEqualTo(1);
            assertThat(response.coverage().segmentsExcluded()).isZero();
            assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a strict query reads a compacting segment")
        void strictQueryReadsCompacting() throws IOException {
            // Compaction reclaims tombstones behind a frozen snapshot, and the snapshot is whole.
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.COMPACTING, true, graphs);

            QueryResponse response = subject.dispatch(vector(), strict(), distance());

            assertThat(response.coverage().segmentsSearched()).isEqualTo(1);
            assertThat(response.coverage().segmentsExcluded()).isZero();
        }

        @Test
        @DisplayName("a strict query leaves an indexing segment out and reports it")
        void strictQueryExcludesIndexing() throws IOException {
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.ACTIVE, true, graphs);
            register(subject, 2L, LifecycleState.INDEXING, true, graphs);

            QueryResponse response = subject.dispatch(vector(), strict(), distance());

            assertThat(response.coverage().segmentsSearched()).isEqualTo(1);
            assertThat(response.coverage().segmentsExcluded())
                .as("the segment the query was not allowed to read is a gap, and gaps are counted")
                .isEqualTo(1);
            assertThat(response.coverage().estimatedCorpusCoverage())
                .as("half the live corpus was searched")
                .isEqualTo(0.5);
        }

        @Test
        @DisplayName("a permissive query reads every queryable state")
        void permissiveQueryReadsEverything() throws IOException {
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.INDEXING, true, graphs);
            register(subject, 2L, LifecycleState.WARMING, true, graphs);
            register(subject, 3L, LifecycleState.ACTIVE, true, graphs);
            register(subject, 4L, LifecycleState.COMPACTING, true, graphs);

            QueryResponse response = subject.dispatch(vector(), permissive(), distance());

            assertThat(response.coverage().segmentsSearched()).isEqualTo(4);
            assertThat(response.coverage().segmentsExcluded()).isZero();
            assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a segment with no graph is a gap, not full coverage")
        void aGraphlessSegmentIsAnExclusion() throws IOException {
            // Nothing about the catalog says a graph failed to load. The census counts the
            // segment as live corpus, the selection drops it, and the two disagree silently.
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.ACTIVE, true, graphs);
            register(subject, 2L, LifecycleState.ACTIVE, false, graphs);

            QueryResponse response = subject.dispatch(vector(), permissive(), distance());

            assertThat(response.coverage().segmentsSearched()).isEqualTo(1);
            assertThat(response.coverage().segmentsExcluded()).isEqualTo(1);
            assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("a query shown nothing says so rather than reporting an empty index")
        void aTotalBlackoutIsReported() throws IOException {
            // The window a rolling reindex opens: one segment, not yet complete, and a client
            // that asked for complete segments only. "No segments to search" and "you were not
            // allowed to search the one segment there is" are different answers.
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.INDEXING, true, graphs);

            QueryResponse response = subject.dispatch(vector(), strict(), distance());

            assertThat(response.results()).isEmpty();
            assertThat(response.coverage().totalSegments())
                .as("the index is not empty, and the response must not imply it is")
                .isEqualTo(1);
            assertThat(response.coverage().segmentsExcluded()).isEqualTo(1);
            assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(0.0);
            assertThat(response.coverage().degraded()).isTrue();
        }

        @Test
        @DisplayName("dispatchRaw selects segments the same way")
        void dispatchRawAppliesTheSameSelection() throws IOException {
            Map<Long, HnswGraph> graphs = new HashMap<>();
            ParallelQueryDispatcher subject = dispatcherOver(graphs);
            register(subject, 1L, LifecycleState.WARMING, true, graphs);
            register(subject, 2L, LifecycleState.INDEXING, true, graphs);

            ParallelQueryDispatcher.DispatchResult result =
                subject.dispatchRaw(vector(), strict(), distance());

            assertThat(result.segmentResults()).hasSize(1);
            assertThat(result.coverage().segmentsExcluded()).isEqualTo(1);
            assertThat(result.coverage().estimatedCorpusCoverage()).isEqualTo(0.5);
        }
    }
}
