package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which segments stage 0 hands on, and what it says about the ones it does not.
 *
 * <p>Stage 0 decides the answer before a single vector is compared. A segment it drops is absent
 * from the results with nothing thrown and nothing logged as a failure, and coverage read off the
 * catalog alone counts that segment as searched - so a query permitted to read none of the corpus
 * reports the same coverage as one that read all of it. These tests pin the drop rules and the
 * count that makes the drops visible on the response.</p>
 */
@DisplayName("Stage 0 segment selection")
class QueryPipelineSegmentSelectionTest {

    private static final int DIMENSION = 8;

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private VisibilityTracker visibility;
    private QueryPipeline pipeline;

    private final Map<Long, Segment> segments = new HashMap<>();
    private final Map<Long, HnswGraph> graphs = new HashMap<>();
    private final List<HnswGraph> openGraphs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        visibility = new VisibilityTracker(catalog);

        StorageEngine storageEngine = mock(StorageEngine.class);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.getSegment(anyLong()))
            .thenAnswer(invocation -> segments.get(invocation.<Long>getArgument(0)));

        pipeline = new QueryPipeline(
            storageEngine, visibility, graphs::get, L2Distance.getInstance());
    }

    @AfterEach
    void tearDown() {
        for (HnswGraph graph : openGraphs) {
            try {
                graph.close();
            } catch (Exception ignored) {
                // The pipeline never owns these; closing is only tidy-up.
            }
        }
        for (Segment segment : segments.values()) {
            try {
                segment.close();
            } catch (Exception ignored) {
                // Same.
            }
        }
        if (catalog != null) {
            catalog.close();
        }
        if (arena != null && arena.scope().isAlive()) {
            arena.close();
        }
    }

    @Test
    @DisplayName("a strict query leaves an indexing segment out and reports it")
    void strictQueryExcludesIndexing() throws IOException {
        register(1L, LifecycleState.ACTIVE, true);
        register(2L, LifecycleState.INDEXING, true);

        QueryResponse response = pipeline.executeSync(strict(), new CancellationToken());

        assertThat(response.coverage().segmentsExcluded())
            .as("the segment the query was not allowed to read is a gap, and gaps are counted")
            .isEqualTo(1);
        assertThat(response.coverage().estimatedCorpusCoverage())
            .as("half the live corpus was searched")
            .isEqualTo(0.5);
    }

    @Test
    @DisplayName("a permissive query reads every queryable state and excludes none")
    void permissiveQueryReadsEverything() throws IOException {
        register(1L, LifecycleState.INDEXING, true);
        register(2L, LifecycleState.WARMING, true);
        register(3L, LifecycleState.ACTIVE, true);
        register(4L, LifecycleState.COMPACTING, true);

        QueryResponse response = pipeline.executeSync(permissive(), new CancellationToken());

        assertThat(response.coverage().segmentsExcluded()).isZero();
        assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a strict query reads a warming segment")
    void strictQueryReadsWarming() throws IOException {
        // Warming trains a product quantizer over entries that are all already in. No production
        // query path reads a PQ code, so a warming segment answers as an active one does.
        register(1L, LifecycleState.WARMING, true);

        QueryResponse response = pipeline.executeSync(strict(), new CancellationToken());

        assertThat(response.coverage().segmentsExcluded()).isZero();
        assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a strict query reads a compacting segment")
    void strictQueryReadsCompacting() throws IOException {
        // Compaction reclaims tombstones behind a frozen snapshot, and the snapshot is whole -
        // so the segment answers a strict query as completely as an active one does.
        register(1L, LifecycleState.COMPACTING, true);

        QueryResponse response = pipeline.executeSync(strict(), new CancellationToken());

        assertThat(response.coverage().segmentsExcluded()).isZero();
        assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a segment whose graph is missing is a gap, not full coverage")
    void aGraphlessSegmentIsAnExclusion() throws IOException {
        // Nothing about the catalog says a graph failed to load. The census counts the segment as
        // live corpus, stage 0 drops it, and the two disagree without either side noticing.
        register(1L, LifecycleState.ACTIVE, true);
        register(2L, LifecycleState.ACTIVE, false);

        QueryResponse response = pipeline.executeSync(permissive(), new CancellationToken());

        assertThat(response.coverage().segmentsExcluded()).isEqualTo(1);
        assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a query shown nothing says so rather than reporting an empty index")
    void aTotalBlackoutIsReported() throws IOException {
        register(1L, LifecycleState.INDEXING, true);

        QueryResponse response = pipeline.executeSync(strict(), new CancellationToken());

        assertThat(response.results()).isEmpty();
        assertThat(response.coverage().totalSegments())
            .as("the index is not empty, and the response must not imply it is")
            .isEqualTo(1);
        assertThat(response.coverage().segmentsExcluded()).isEqualTo(1);
        assertThat(response.coverage().estimatedCorpusCoverage()).isEqualTo(0.0);
        assertThat(response.coverage().degraded()).isTrue();
    }

    /**
     * Registers a segment with the catalog, the visibility tracker and the engine's segment map,
     * driven to {@code state} through the transitions the catalog actually permits.
     */
    private void register(long segmentId, LifecycleState state, boolean withGraph)
            throws IOException {
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
        visibility.registerSegment(segmentId, 0L);
        segments.put(segmentId, segment("s-" + segmentId + ".dat"));
        if (withGraph) {
            graphs.put(segmentId, graph("g-" + segmentId + ".hnsw"));
        }
    }

    private Segment segment(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.deleteIfExists(path);
        return new Segment(path, 1024 * 1024, arena);
    }

    private HnswGraph graph(String name) throws IOException {
        HnswGraph created = new HnswGraph(
            tempDir.resolve(name), HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024);
        openGraphs.add(created);
        return created;
    }

    private static float[] vector() {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = i / (float) DIMENSION;
        }
        return v;
    }

    /** A query that asks to be spared partially-indexed segments. */
    private static QueryRequest strict() {
        return QueryRequest.builder()
            .queryVector(vector())
            .topK(5)
            .includePartialSegments(false)
            .build();
    }

    private static QueryRequest permissive() {
        return QueryRequest.builder()
            .queryVector(vector())
            .topK(5)
            .includePartialSegments(true)
            .build();
    }
}
