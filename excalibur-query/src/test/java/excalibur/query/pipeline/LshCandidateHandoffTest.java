package excalibur.query.pipeline;

import excalibur.filter.lsh.LshConfig;
import excalibur.filter.lsh.LshIndex;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import excalibur.query.executor.CancellationToken;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pre-filter decides has to reach the search it is supposed to narrow - and only when it
 * is entitled to narrow it.
 *
 * <p>Stage 2 probes every hash table of every candidate segment's pre-filter and records the node
 * ids that came back. Stage 3 is where that set is worth something: {@code HnswGraph.search(query,
 * k, candidateIds)} walks only those nodes, which is the whole reason the index is built,
 * persisted and carried across compactions. The two are joined by a value that has to survive
 * being turned into a dispatcher input; if it does not, nothing fails - stage 2 still does its
 * work, and the search still returns correct results, just from the whole graph. The pre-filter is
 * then pure cost: probed on every query, and never once consulted.</p>
 *
 * <p>The other half of the contract is when a set may be acted on at all. An index holding fewer
 * entries than its graph has nodes has missed some of them, and the nodes it missed are exactly
 * the ones its answer cannot mention. Narrowing a search to such an answer drops matches with no
 * error anywhere - the same failure {@link PrefilterCoverage} already refuses to allow when the
 * answer is empty, in the milder form of losing some results rather than all of them.</p>
 */
@DisplayName("LSH candidate hand-off")
class LshCandidateHandoffTest {

    private static final int DIMENSION = 8;
    private static final int NODE_COUNT = 12;
    private static final int TOP_K = 10;

    /** Fixed hyperplanes, so which nodes share the query's bucket is the same on every run. */
    private static final long LSH_SEED = 20240517L;

    @Test
    @DisplayName("a search is confined to the nodes the pre-filter named")
    void theSearchIsConfinedToThePrefilterCandidates(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            Segment segment = engine.getSegment(0L);
            assertThat(segment).isNotNull();

            HnswGraph graph = new HnswGraph(dataDir.resolve("probe.hnsw"),
                HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 256);

            // Spread around the circle, so the hyperplanes actually separate them: an
            // unrestricted top-10 of twelve returns ten, a bucket holds a handful.
            float[][] vectors = new float[NODE_COUNT][];
            long[] nodeIds = new long[NODE_COUNT];
            for (int i = 0; i < NODE_COUNT; i++) {
                vectors[i] = fan(i * 2 * Math.PI / NODE_COUNT);
                nodeIds[i] = graph.insert(vectors[i]);
            }
            segment.attachHnswGraph(graph);

            // Every node the graph holds, so the index is entitled to narrow.
            LshIndex prefilter = tightPrefilter();
            for (int i = 0; i < NODE_COUNT; i++) {
                prefilter.insert(vectors[i], nodeIds[i]);
            }
            segment.attachLshIndex(prefilter);

            Set<Long> named = prefilter.query(vectors[0]);
            assertThat(named)
                .as("the fixture is only meaningful while the pre-filter names fewer nodes than "
                    + "an unrestricted search would return")
                .isNotEmpty()
                .hasSizeLessThan(TOP_K);

            QueryResponse response = query(engine, vectors[0]);

            assertThat(response.results()).extracting(QueryResultItem::nodeId)
                .as("the search must walk the candidates the pre-filter named, not the whole graph")
                .isNotEmpty()
                .isSubsetOf(boxed(named));

            graph.close();
        }
    }

    @Test
    @DisplayName("an index that has not seen the whole graph does not narrow the search")
    void anIncompleteIndexDoesNotNarrow(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            Segment segment = engine.getSegment(0L);
            assertThat(segment).isNotNull();

            HnswGraph graph = new HnswGraph(dataDir.resolve("probe.hnsw"),
                HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 256);

            // A tight fan: every one of them is close enough to the query that an unrestricted
            // top-10 returns ten of them.
            float[][] vectors = new float[NODE_COUNT][];
            long[] nodeIds = new long[NODE_COUNT];
            for (int i = 0; i < NODE_COUNT; i++) {
                vectors[i] = fan(i * 0.05);
                nodeIds[i] = graph.insert(vectors[i]);
            }
            segment.attachHnswGraph(graph);

            // One node of twelve: the state of an index whose writer lost entries, or that was
            // flushed behind the graph beside it and reopened. Its answer is not wrong, it is
            // incomplete, and the eleven nodes it never saw are unreachable through it.
            LshIndex prefilter = tightPrefilter();
            prefilter.insert(vectors[0], nodeIds[0]);
            segment.attachLshIndex(prefilter);

            assertThat(prefilter.query(vectors[0]))
                .as("the query vector is the one vector the pre-filter holds")
                .containsExactly(nodeIds[0]);

            QueryResponse response = query(engine, vectors[0]);

            assertThat(response.results())
                .as("an index covering one node of twelve must not cut the search down to it")
                .hasSize(TOP_K);

            graph.close();
        }
    }

    /**
     * Runs a query through the whole pipeline, keyed the way the running system keys it.
     */
    private static QueryResponse query(StorageEngine engine, float[] queryVector) {
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
     * A pre-filter with fixed hyperplanes and no multi-probe widening, so a bucket is a bucket.
     */
    private static LshIndex tightPrefilter() {
        return new LshIndex(LshConfig.builder(DIMENSION)
            .numHashTables(4)
            .numHashFunctions(8)
            .numProbes(1)
            .seed(LSH_SEED)
            .build());
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

    private static Long[] boxed(Set<Long> ids) {
        return ids.toArray(new Long[0]);
    }
}
