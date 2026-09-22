package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.PqSearchSettings;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment's decision to answer queries through the quantizer has to reach the segment.
 *
 * <p>The quantizer is trained during warming on every segment, and until it is consulted by the
 * query path that training and encoding is work the node does for nothing. The setting that
 * turns it on is read where the node is configured and has to travel the whole way down -
 * pipeline, stage, dispatcher, segment task - to the one place a graph is searched. Nothing
 * fails if it does not arrive: the searches are still correct, still full-precision, and the
 * flag is simply inert.</p>
 */
@DisplayName("Quantised search hand-off")
class QueryPipelinePqHandoffTest {

    private static final int DIMENSION = 8;
    private static final int NODE_COUNT = 40;
    private static final int TOP_K = 5;
    private static final int OVERSAMPLE = 4;

    @Test
    @DisplayName("the configured quantised search reaches the segment that can serve it")
    void theQuantisedSearchReachesTheSegment(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            RecordingGraph graph = quantisedGraph(engine, dataDir);

            QueryResponse exact = query(engine, PqSearchSettings.DISABLED);
            assertThat(graph.calls)
                .as("with quantised search off the path is the one the node takes today")
                .containsOnly("search");

            graph.calls.clear();
            QueryResponse quantised = query(engine, new PqSearchSettings(true, OVERSAMPLE));

            assertThat(graph.calls)
                .as("the pipeline's setting has to reach the graph, oversample and all")
                .containsOnly("rerank:" + OVERSAMPLE);
            assertThat(quantised.results()).extracting(QueryResultItem::nodeId)
                .as("rescored candidates are measured exactly, so the answer is the same one")
                .containsExactlyElementsOf(
                    exact.results().stream().map(QueryResultItem::nodeId).toList());

            graph.close();
        }
    }

    @Test
    @DisplayName("a segment with no quantizer is searched exactly rather than failing")
    void anUnquantisedSegmentFallsBack(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            RecordingGraph graph = unquantisedGraph(engine, dataDir);

            QueryResponse response = query(engine, new PqSearchSettings(true, OVERSAMPLE));

            assertThat(response.results()).hasSize(TOP_K);
            assertThat(graph.calls)
                .as("segments reach warming at different times; one without a quantizer is "
                    + "searched the ordinary way")
                .containsOnly("search");

            graph.close();
        }
    }

    /**
     * Attaches a graph whose vectors are encoded against a trained quantizer.
     */
    private static RecordingGraph quantisedGraph(StorageEngine engine, Path dataDir)
            throws IOException {
        RecordingGraph graph = unquantisedGraph(engine, dataDir);
        graph.trainProductQuantizer(NODE_COUNT, 4, 16);
        graph.encodeAllVectors();
        graph.calls.clear();
        return graph;
    }

    /**
     * Attaches a filled graph to segment 0 and returns it.
     */
    private static RecordingGraph unquantisedGraph(StorageEngine engine, Path dataDir)
            throws IOException {
        Segment segment = engine.getSegment(0L);
        assertThat(segment).isNotNull();

        RecordingGraph graph = new RecordingGraph(dataDir.resolve("probe.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 256);
        Random random = new Random(1234567L);
        for (int i = 0; i < NODE_COUNT; i++) {
            graph.insert(randomVector(random));
        }
        segment.attachHnswGraph(graph);
        graph.calls.clear();
        return graph;
    }

    /**
     * Runs a query through the whole pipeline, keyed the way the running system keys it.
     */
    private static QueryResponse query(StorageEngine engine, PqSearchSettings pqSearch) {
        engine.getCatalog().get(0L).setLifecycleState(LifecycleState.ACTIVE);
        VisibilityTracker visibility = new VisibilityTracker(engine.getCatalog());
        visibility.registerSegment(0L, visibility.currentEpoch());

        QueryPipeline pipeline = new QueryPipeline(
            engine,
            visibility,
            null,
            id -> {
                Segment routed = engine.getSegment(id);
                return routed == null ? null : routed.getHnswGraph();
            },
            CosineSimilarity.getInstance(),
            null,
            QueryPipeline.DEFAULT_COVERAGE_THRESHOLD,
            null,
            null,
            pqSearch);

        return pipeline.executeSync(
            QueryRequest.builder().queryVector(randomVector(new Random(99L))).topK(TOP_K).build(),
            new CancellationToken());
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

    /**
     * A graph that records which of its searches the pipeline chose.
     */
    private static final class RecordingGraph extends HnswGraph {

        private final List<String> calls = new ArrayList<>();

        RecordingGraph(Path path, HnswConfig config, DistanceFunction distanceFunction,
                       long maxNodes) throws IOException {
            super(path, config, distanceFunction, maxNodes);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            calls.add("search");
            return super.search(query, k);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
            calls.add("searchCandidates");
            return super.search(query, k, candidateIds);
        }

        @Override
        public List<HnswSearchResult> searchWithPQThenRerank(float[] query, int k, int oversample) {
            calls.add("rerank:" + oversample);
            return super.searchWithPQThenRerank(query, k, oversample);
        }
    }
}
