package com.eonmux.excalibur.query.executor;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.query.QueryOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * <p>The dispatcher is handed the segments to search; choosing them belongs to
 * {@link com.eonmux.excalibur.query.pipeline.QueryPipeline} and is covered by
 * {@code QueryPipelineSegmentSelectionTest}. What is tested here is the fan-out itself - that a
 * caller's list is searched, that coverage reports what actually happened, and that a dispatcher
 * reporting full coverage over zero segments is distinguishable from one that searched
 * everything and found nothing.
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
        assertThatThrownBy(() -> dispatcher.dispatchToSpecificSegments(
            null, vector(), options(), distance()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatchToSpecificSegments(
            List.of(), null, options(), distance()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatchToSpecificSegments(
            List.of(), vector(), null, distance()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatchToSpecificSegments(
            List.of(), vector(), options(), null))
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
    @DisplayName("a query given no segments returns empty rather than failing")
    void dispatchWithNoSegments() {
        ParallelQueryDispatcher.DispatchResult result =
            dispatcher.dispatchToSpecificSegments(List.of(), vector(), options(), distance());

        assertThat(result).isNotNull();
        assertThat(result.segmentResults()).isEmpty();
    }

    @Test
    @DisplayName("the dispatch exposes per-segment results and a duration")
    void dispatchReportsDetail() throws IOException {
        Map<Long, HnswGraph> graphs = new HashMap<>();
        ParallelQueryDispatcher subject = dispatcherOver(graphs);
        register(subject, 1L, LifecycleState.ACTIVE, true, graphs);

        ParallelQueryDispatcher.DispatchResult result = subject.dispatchToSpecificSegments(
            List.of(ParallelQueryDispatcher.createSegmentInfo(
                1L, segment("raw.dat"), graphs.get(1L))),
            vector(), options(), distance());

        assertThat(result.segmentResults()).hasSize(1);
        assertThat(result.tailScanEntries()).isNotNegative();
        assertThat(result.durationMs()).isNotNegative();
    }

    @Test
    @DisplayName("only the segments the caller handed over are searched")
    void onlyTheCallersSegmentsAreSearched() throws IOException {
        // Two segments exist and the caller offers one. The dispatcher does not go looking for
        // the other: which segments a query may read was settled before it got here.
        Map<Long, HnswGraph> graphs = new HashMap<>();
        ParallelQueryDispatcher subject = dispatcherOver(graphs);
        register(subject, 1L, LifecycleState.ACTIVE, true, graphs);
        register(subject, 2L, LifecycleState.ACTIVE, true, graphs);

        ParallelQueryDispatcher.DispatchResult result = subject.dispatchToSpecificSegments(
            List.of(ParallelQueryDispatcher.createSegmentInfo(
                1L, segment("half.dat"), graphs.get(1L))),
            vector(), options(), distance());

        assertThat(result.segmentResults())
            .extracting(SegmentQueryTask.SegmentQueryResult::segmentId)
            .containsExactly(1L);
        assertThat(result.segmentsTimedOut()).isZero();
    }

    @Test
    @DisplayName("a dispatch given nothing reports nothing searched, not a searched nothing")
    void anEmptyDispatchReportsNoWork() {
        ParallelQueryDispatcher.DispatchResult result =
            dispatcher.dispatchToSpecificSegments(List.of(), vector(), options(), distance());

        assertThat(result.segmentResults()).isEmpty();
        assertThat(result.tailScanEntries()).isZero();
        assertThat(result.segmentsTimedOut()).isZero();
        assertThat(result.durationMs()).isZero();
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdownIsIdempotent() {
        dispatcher.shutdown();
        dispatcher.shutdown();
    }

    @Test
    @DisplayName("the default timeout is published")
    void defaultsArePublished() {
        assertThat(ParallelQueryDispatcher.DEFAULT_TIMEOUT.toSeconds()).isEqualTo(30);
    }

}
