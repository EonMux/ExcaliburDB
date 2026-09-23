package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.lsh.LshConfig;
import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the LSH stage finds has to reach the search it supplements - and must never cut it short.
 *
 * <p>Stage 2 probes every candidate segment's LSH index and records the node ids that came back.
 * Stage 3 measures those nodes exactly beside its graph search, which recovers a near neighbour
 * the approximate search walked past. The two are joined by a value that has to survive being
 * turned into a dispatcher input; if it does not, nothing fails - the index is probed on every
 * query and never once consulted.</p>
 *
 * <p>The other half of the contract is what the index may not do. A bucket says which entries
 * hashed alongside the query and bounds nothing about the distance of the rest, so neither an
 * empty answer nor a short one says where a segment's nearest neighbours are. Excluding a segment
 * on the first, or confining its search to the second, drops results with no error anywhere - and
 * does so most readily on exactly the segments whose index is complete.</p>
 */
@DisplayName("LSH candidate hand-off")
class LshCandidateHandoffTest {

    private static final int DIMENSION = 8;
    private static final int NODE_COUNT = 12;
    private static final int TOP_K = 10;

    /** Fixed hyperplanes, so which nodes share the query's bucket is the same on every run. */
    private static final long LSH_SEED = 20240517L;

    @Test
    @DisplayName("a node the graph search walked past is recovered through the index")
    void theCandidatesReachTheSearch(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            MissingNodeGraph graph = new MissingNodeGraph(dataDir.resolve("probe.hnsw"));
            float[][] vectors = spread(graph, 2 * Math.PI / NODE_COUNT);
            long[] nodeIds = graph.inserted;
            // The approximate search never reports the query's own vector; only the index can.
            graph.hidden = nodeIds[0];

            QueryResponse response = query(engine, graph, prefilterOver(vectors, nodeIds), vectors[0]);

            assertThat(response.results()).extracting(QueryResultItem::nodeId)
                .as("the node the index named must be measured beside the graph search")
                .contains(nodeIds[0]);
            graph.close();
        }
    }

    @Test
    @DisplayName("a short candidate set does not confine the search to its members")
    void aShortAnswerDoesNotNarrow(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            MissingNodeGraph graph = new MissingNodeGraph(dataDir.resolve("probe.hnsw"));
            // Spread around the circle, so the hyperplanes actually separate them: a bucket
            // holds a handful of twelve, an unrestricted top-10 returns ten.
            float[][] vectors = spread(graph, 2 * Math.PI / NODE_COUNT);
            LshIndex prefilter = prefilterOver(vectors, graph.inserted);

            assertThat(prefilter.query(vectors[0]))
                .as("the fixture is only meaningful while the index names fewer nodes than "
                    + "an unrestricted search returns")
                .isNotEmpty()
                .hasSizeLessThan(TOP_K);

            QueryResponse response = query(engine, graph, prefilter, vectors[0]);

            assertThat(response.results())
                .as("a complete index naming a few nodes must not cut a top-%d down to them", TOP_K)
                .hasSize(TOP_K);
            graph.close();
        }
    }

    @Test
    @DisplayName("an empty answer from a complete index does not exclude the segment")
    void anEmptyAnswerDoesNotExclude(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            MissingNodeGraph graph = new MissingNodeGraph(dataDir.resolve("probe.hnsw"));
            // A tight fan, and a query pointing the opposite way: nothing shares its bucket, yet
            // the segment still holds its twelve nearest vectors.
            float[][] vectors = spread(graph, 0.05);
            LshIndex prefilter = prefilterOver(vectors, graph.inserted);
            float[] opposite = fan(Math.PI);

            assertThat(prefilter.query(opposite))
                .as("the fixture is only meaningful while the index names nothing")
                .isEmpty();

            QueryResponse response = query(engine, graph, prefilter, opposite);

            assertThat(response.results())
                .as("no bucket collision is not the same as no nearest neighbour")
                .hasSize(TOP_K);
            assertThat(response.coverage().segmentsExcluded()).isZero();
            graph.close();
        }
    }

    /**
     * Inserts twelve unit vectors fanned out from angle zero at the given step.
     */
    private static float[][] spread(MissingNodeGraph graph, double step) {
        float[][] vectors = new float[NODE_COUNT][];
        for (int i = 0; i < NODE_COUNT; i++) {
            vectors[i] = fan(i * step);
            graph.inserted[i] = graph.insert(vectors[i]);
        }
        return vectors;
    }

    /**
     * A pre-filter holding every node of the graph, as integration leaves it once caught up.
     */
    private static LshIndex prefilterOver(float[][] vectors, long[] nodeIds) throws IOException {
        LshIndex prefilter = new LshIndex(LshConfig.builder(DIMENSION)
            .numHashTables(4)
            .numHashFunctions(8)
            .numProbes(1)
            .seed(LSH_SEED)
            .build());
        for (int i = 0; i < vectors.length; i++) {
            prefilter.insert(vectors[i], nodeIds[i]);
        }
        return prefilter;
    }

    /**
     * Runs a query through the whole pipeline over segment 0, keyed the way the running system
     * keys it, with the graph fully integrated so the index speaks for the whole segment.
     */
    private static QueryResponse query(StorageEngine engine, HnswGraph graph, LshIndex prefilter,
                                       float[] queryVector) {
        Segment segment = engine.getSegment(0L);
        assertThat(segment).isNotNull();
        if (segment.getWriteLogHead() > graph.getLastIntegratedOffset()) {
            graph.setLastIntegratedOffset(segment.getWriteLogHead());
        }
        segment.attachHnswGraph(graph);
        segment.attachLshIndex(prefilter);

        engine.getCatalog().get(0L).setLifecycleState(LifecycleState.ACTIVE);
        VisibilityTracker visibility = new VisibilityTracker(engine.getCatalog());
        visibility.registerSegment(0L, visibility.currentEpoch());

        QueryPipeline pipeline = new QueryPipeline(
            engine,
            visibility,
            id -> {
                Segment routed = engine.getSegment(id);
                return routed == null ? null : routed.getHnswGraph();
            },
            CosineSimilarity.getInstance());

        return pipeline.executeSync(
            QueryRequest.builder().queryVector(queryVector).topK(TOP_K).build(),
            new CancellationToken());
    }

    /**
     * A unit vector at the given angle in the first two dimensions.
     */
    private static float[] fan(double radians) {
        float[] vector = new float[DIMENSION];
        vector[0] = (float) Math.cos(radians);
        vector[1] = (float) Math.sin(radians);
        return vector;
    }

    /**
     * A graph whose unrestricted search can be made to walk past one node, as an approximate
     * beam can, while a search over named nodes still measures it.
     */
    private static final class MissingNodeGraph extends HnswGraph {

        private final long[] inserted = new long[NODE_COUNT];
        private long hidden = -1;

        MissingNodeGraph(Path path) throws IOException {
            super(path, HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 256);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            return super.search(query, k + 1).stream()
                .filter(result -> result.nodeId() != hidden)
                .limit(k)
                .toList();
        }
    }
}
