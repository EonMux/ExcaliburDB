package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
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
 * How the query's deadline is spent across the stages that make up an answer.
 *
 * <p>The pipeline arms one deadline over the whole run. A stage that dispatches work of its own
 * therefore cannot be given the request's full timeout: measured from when that stage starts,
 * such a deadline necessarily falls after the pipeline's, so the pipeline aborts first and every
 * segment that <em>did</em> answer in time is discarded. The contract is partial results on
 * timeout, and these tests pin the behaviour that makes it true.</p>
 */
@DisplayName("Query deadline")
class QueryPipelineDeadlineTest {

    private static final int DIMENSION = 8;

    /** Longer than any timeout used here, so the segment can only ever be the slow one. */
    private static final long SLOW_SEARCH_MILLIS = 30_000;

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private VisibilityTracker visibility;
    private StorageEngine storageEngine;
    private QueryPipeline pipeline;

    private final Map<Long, Segment> segments = new HashMap<>();
    private final Map<Long, HnswGraph> graphs = new HashMap<>();
    private final List<HnswGraph> openGraphs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        visibility = new VisibilityTracker(catalog);

        storageEngine = mock(StorageEngine.class);
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
    @DisplayName("one unresponsive segment does not cost the answer the others already gave")
    void aStalledSegmentDoesNotEmptyTheResponse() throws IOException {
        register(1L, false);
        register(2L, true);

        QueryResponse response = pipeline.executeSync(query(1_000), new CancellationToken());

        assertThat(response.results())
            .as("the segment that answered in time is the answer; discarding it returns nothing "
                + "and calls it a timeout")
            .isNotEmpty();
        assertThat(response.coverage().segmentsTimedOut())
            .as("the segment that did not answer is named, so the caller knows the answer is partial")
            .isEqualTo(1);
        assertThat(response.isDegraded())
            .as("a partial answer is a degraded one")
            .isTrue();
        assertThat(response.coverage().segmentsWithErrors())
            .as("one segment had one problem, and it is already counted as a timeout")
            .isZero();
        assertThat(response.coverage().estimatedCorpusCoverage())
            .as("half the corpus is behind the segment that never answered")
            .isEqualTo(0.5);
    }

    @Test
    @DisplayName("a stalled fan-out is collected inside the request's deadline, not after it")
    void theFanOutEndsBeforeTheDeadline() throws IOException {
        register(1L, false);
        register(2L, true);

        long startNanos = System.nanoTime();
        QueryResponse response = pipeline.executeSync(query(1_000), new CancellationToken());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMillis)
            .as("the stalled segment must be abandoned in time for the merge to still run")
            .isLessThan(SLOW_SEARCH_MILLIS);
        assertThat(response.timedOut())
            .as("the pipeline came back with an answer rather than being cut off")
            .isFalse();
    }

    @Test
    @DisplayName("a corpus that is entirely unresponsive still reports the timeout")
    void anEntirelyStalledCorpusStillTimesOut() throws IOException {
        register(1L, true);

        QueryResponse response = pipeline.executeSync(query(1_000), new CancellationToken());

        assertThat(response.results()).isEmpty();
        assertThat(response.isDegraded()).isTrue();
        assertThat(response.coverage().segmentsTimedOut())
            .as("nothing answered, and the response says so rather than implying an empty index")
            .isEqualTo(1);
        assertThat(response.coverage().totalSegments())
            .as("the index is not empty, and a timed-out response must not imply it is")
            .isEqualTo(1);
        assertThat(response.coverage().estimatedCorpusCoverage())
            .as("nothing was read, so nothing is covered")
            .isZero();
    }

    @Test
    @DisplayName("a scorer that overruns hands back the ranking already made, cut to the size asked for")
    void aScorerThatOverrunsReturnsTheRankingSoFar() throws IOException {
        // Stage 4 leaves its list oversampled on purpose, because Stage 5's threshold filter is
        // supposed to choose from it. When Stage 5 never answers, that list is what the caller
        // gets - and the caller asked for topK results, not twice topK.
        register(1L, false, 5);
        register(2L, false, 5);
        register(3L, true, 5);
        QueryPipeline scoring = new QueryPipeline(
            storageEngine, visibility, graphs::get, L2Distance.getInstance(),
            (queryText, results, threshold) -> {
                try {
                    Thread.sleep(SLOW_SEARCH_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Scoring abandoned", e);
                }
                return results;
            },
            0.5);

        QueryResponse response = scoring.executeSync(scoredQuery(1_000), new CancellationToken());

        assertThat(response.results())
            .as("the ranking Stage 4 already produced is the answer")
            .isNotEmpty()
            .hasSizeLessThanOrEqualTo(5);
        assertThat(response.coverage().segmentsTimedOut())
            .as("the segment that never answered is still named on a partial response")
            .isEqualTo(1);
        assertThat(response.timedOut()).isTrue();
        assertThat(response.coverage().totalSegments())
            .as("a response cut short still knows what the index holds")
            .isEqualTo(3);
        assertThat(response.coverage().estimatedCorpusCoverage())
            .as("two of the three segments answered")
            .isEqualTo(2.0 / 3);
    }

    /**
     * Registers an active segment holding a handful of vectors.
     *
     * @param segmentId The segment id
     * @param stalled   Whether searching its graph blocks for longer than any deadline here
     */
    private void register(long segmentId, boolean stalled) throws IOException {
        register(segmentId, stalled, 5);
    }

    /**
     * Registers an active segment holding {@code vectors} vectors.
     *
     * @param segmentId The segment id
     * @param stalled   Whether searching its graph blocks for longer than any deadline here
     * @param vectors   How many vectors its graph holds
     */
    private void register(long segmentId, boolean stalled, int vectors) throws IOException {
        catalog.add(segmentId, tempDir.resolve("vol-" + segmentId + ".dat"));
        catalog.completeIndexing(segmentId);
        catalog.completeWarmup(segmentId);
        visibility.registerSegment(segmentId, 0L);

        Path segmentPath = tempDir.resolve("s-" + segmentId + ".dat");
        Files.deleteIfExists(segmentPath);
        segments.put(segmentId, new Segment(segmentPath, 1024 * 1024, arena));

        Path graphPath = tempDir.resolve("g-" + segmentId + ".hnsw");
        HnswGraph graph = stalled
            ? new StalledGraph(graphPath, HnswConfig.create(DIMENSION), L2Distance.getInstance())
            : new HnswGraph(graphPath, HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024);
        openGraphs.add(graph);
        for (int i = 0; i < vectors; i++) {
            graph.insert(vector(i / 100.0f));
        }
        graphs.put(segmentId, graph);
    }

    /** A graph whose search never returns within any deadline this test sets. */
    private static final class StalledGraph extends HnswGraph {

        StalledGraph(Path path, HnswConfig config, DistanceFunction distance) throws IOException {
            super(path, config, distance, 1024);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            try {
                Thread.sleep(SLOW_SEARCH_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Search abandoned", e);
            }
            return super.search(query, k);
        }
    }

    private static float[] vector(float offset) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = offset + i / (float) DIMENSION;
        }
        return v;
    }

    /** A query that also asks for relevancy scoring, so Stage 5 runs. */
    private static QueryRequest scoredQuery(long timeoutMs) {
        return QueryRequest.builder()
            .queryText("test")
            .queryVector(vector(0f))
            .topK(5)
            .timeoutMs(timeoutMs)
            .enableLlmScoring(true)
            .includePartialSegments(true)
            .build();
    }

    private static QueryRequest query(long timeoutMs) {
        return QueryRequest.builder()
            .queryVector(vector(0f))
            .topK(5)
            .timeoutMs(timeoutMs)
            .includePartialSegments(true)
            .build();
    }
}
