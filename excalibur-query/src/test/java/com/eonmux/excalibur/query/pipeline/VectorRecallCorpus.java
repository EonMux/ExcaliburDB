package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.lsh.LshConfig;
import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A multi-segment vector corpus searched three ways, so the query pipeline's recall can be read
 * against both exact search and the graph it is built on.
 *
 * <p>Every segment is set up the way a node that embeds its corpus leaves it once integration has
 * caught up: an HNSW graph holding every vector, and an LSH pre-filter holding one entry per graph
 * node under the graph's own ids. That is the state in which the pre-filter's answer is at its most
 * authoritative, and therefore the state in which a pre-filter that is allowed to cut a search short
 * does the most damage.</p>
 *
 * <p>The vectors are clustered and the clusters are scattered across segments by a random
 * placement, as documents are placed by key hash. A query's true neighbours therefore live in
 * several segments at once, and a segment's share of them can be a single vector - exactly what a
 * pre-filter judging segments one at a time cannot see.</p>
 *
 * <p>Three answers are compared for each query: the exact top-k over the whole corpus, the answer
 * each segment's graph gives on its own (merged across segments), and the answer the full pipeline
 * gives. The first is the truth; the second is the best the pipeline can be expected to do without
 * searching exhaustively, and the one it has to be held to.</p>
 */
final class VectorRecallCorpus implements AutoCloseable {

    /** The pre-filter shape a node runs with unless configured otherwise. */
    static final LshShape PRODUCTION_SHAPE = new LshShape(15, 16, 3);

    private static final DistanceFunction DISTANCE = CosineSimilarity.getInstance();

    /** How far a vector strays from its cluster's centre, per dimension. */
    private static final double CLUSTER_SPREAD = 0.08;

    /** Vectors per cluster, on average. */
    private static final int CLUSTER_SIZE = 40;

    private final Arena arena = Arena.ofShared();
    private final SegmentCatalog catalog;
    private final QueryPipeline pipeline;
    private final Map<Long, Segment> segments = new HashMap<>();
    private final Map<Long, HnswGraph> graphs = new HashMap<>();
    private final List<StoredVector> corpus = new ArrayList<>();
    private final List<float[]> centres = new ArrayList<>();
    private final int dimension;
    private final Random random;

    /**
     * The pre-filter's shape, as its three configuration keys set it.
     *
     * @param hashTables {@code excalibur.shard.lsh-hash-tables}
     * @param hashFunctions {@code excalibur.shard.lsh-hash-functions}
     * @param probes {@code excalibur.shard.lsh-probes}
     */
    record LshShape(int hashTables, int hashFunctions, int probes) {
    }

    /**
     * Recall@k of the pipeline and of the graphs alone, both measured against exact search.
     *
     * @param pipeline The pipeline's recall, averaged over the queries
     * @param graphsAlone Each segment's graph searched directly, merged, averaged over the queries
     * @param queries How many queries were measured
     */
    record Recall(double pipeline, double graphsAlone, int queries) {
    }

    private record StoredVector(long segmentId, long nodeId, float[] vector) {
    }

    private record Hit(long segmentId, long nodeId, float distance) {
    }

    /**
     * Builds the corpus: every segment's graph and pre-filter filled and fully integrated.
     *
     * @param dir Where the segments and graphs are written
     * @param segmentCount How many segments the corpus is spread over
     * @param vectorCount How many vectors, across all segments
     * @param dimension The vectors' dimension
     * @param shape The pre-filter's shape
     * @param seed Seeds the vectors, their placement and the pre-filter's hyperplanes
     * @throws IOException if a segment or graph cannot be created
     */
    VectorRecallCorpus(Path dir, int segmentCount, int vectorCount, int dimension,
                       LshShape shape, long seed) throws IOException {
        this.dimension = dimension;
        this.random = new Random(seed);
        this.catalog = new SegmentCatalog(dir.resolve("catalog.dat"));
        VisibilityTracker visibility = new VisibilityTracker(catalog);

        Map<Long, LshIndex> prefilters = new HashMap<>();
        for (long id = 1; id <= segmentCount; id++) {
            catalog.add(id, dir.resolve("vol-" + id + ".dat"));
            catalog.completeIndexing(id);
            catalog.completeWarmup(id);
            visibility.registerSegment(id, 0L);
            segments.put(id, new Segment(dir.resolve("s-" + id + ".dat"), 1024 * 1024, arena));
            graphs.put(id, new HnswGraph(dir.resolve("g-" + id + ".hnsw"),
                HnswConfig.create(dimension), DISTANCE, vectorCount + 1L));
            prefilters.put(id, new LshIndex(LshConfig.builder(dimension)
                .numHashTables(shape.hashTables())
                .numHashFunctions(shape.hashFunctions())
                .numProbes(shape.probes())
                .seed(seed + id)
                .build()));
        }

        int clusterCount = Math.max(1, vectorCount / CLUSTER_SIZE);
        for (int c = 0; c < clusterCount; c++) {
            centres.add(unit(gaussian()));
        }
        for (int i = 0; i < vectorCount; i++) {
            long segmentId = 1 + random.nextInt(segmentCount);
            float[] vector = nearCentre();
            long nodeId = graphs.get(segmentId).insert(vector);
            prefilters.get(segmentId).insert(vector, nodeId);
            corpus.add(new StoredVector(segmentId, nodeId, vector));
        }

        for (long id = 1; id <= segmentCount; id++) {
            Segment segment = segments.get(id);
            HnswGraph graph = graphs.get(id);
            // Integration has absorbed the whole write log, so no segment has an unindexed tail
            // and every pre-filter speaks for all of its segment.
            if (segment.getWriteLogHead() > graph.getLastIntegratedOffset()) {
                graph.setLastIntegratedOffset(segment.getWriteLogHead());
            }
            segment.attachHnswGraph(graph);
            segment.attachLshIndex(prefilters.get(id));
        }

        StorageEngine storageEngine = mock(StorageEngine.class);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.getSegment(anyLong()))
            .thenAnswer(invocation -> segments.get(invocation.<Long>getArgument(0)));
        this.pipeline = new QueryPipeline(storageEngine, visibility, graphs::get, DISTANCE);
    }

    /**
     * Measures recall@k over fresh queries drawn from the same clusters as the corpus.
     *
     * @param queryCount How many queries to run
     * @param k How many neighbours each query asks for
     * @return The pipeline's recall and the graphs' own, against exact search
     */
    Recall measure(int queryCount, int k) {
        double pipelineTotal = 0;
        double graphsTotal = 0;
        for (int q = 0; q < queryCount; q++) {
            float[] query = nearCentre();
            Set<String> truth = keys(exact(query, k));
            pipelineTotal += overlap(truth, pipelineAnswer(query, k)) / (double) truth.size();
            graphsTotal += overlap(truth, keys(graphsAlone(query, k))) / (double) truth.size();
        }
        return new Recall(pipelineTotal / queryCount, graphsTotal / queryCount, queryCount);
    }

    private Set<String> pipelineAnswer(float[] query, int k) {
        QueryResponse response = pipeline.executeSync(
            QueryRequest.builder().queryVector(query).topK(k).build(), new CancellationToken());
        Set<String> found = new HashSet<>();
        for (QueryResultItem item : response.results()) {
            found.add(key(item.segmentId(), item.nodeId()));
        }
        return found;
    }

    private List<Hit> exact(float[] query, int k) {
        return corpus.stream()
            .map(v -> new Hit(v.segmentId(), v.nodeId(), DISTANCE.distance(query, v.vector())))
            .sorted(Comparator.comparingDouble(Hit::distance))
            .limit(k)
            .toList();
    }

    private List<Hit> graphsAlone(float[] query, int k) {
        List<Hit> merged = new ArrayList<>();
        for (Map.Entry<Long, HnswGraph> entry : graphs.entrySet()) {
            for (HnswSearchResult result : entry.getValue().search(query, k)) {
                merged.add(new Hit(entry.getKey(), result.nodeId(), result.distance()));
            }
        }
        merged.sort(Comparator.comparingDouble(Hit::distance));
        return merged.subList(0, Math.min(k, merged.size()));
    }

    private static Set<String> keys(List<Hit> hits) {
        Set<String> keys = new HashSet<>();
        for (Hit hit : hits) {
            keys.add(key(hit.segmentId(), hit.nodeId()));
        }
        return keys;
    }

    private static String key(long segmentId, long nodeId) {
        return segmentId + ":" + nodeId;
    }

    private static int overlap(Set<String> truth, Set<String> found) {
        int hits = 0;
        for (String key : found) {
            if (truth.contains(key)) {
                hits++;
            }
        }
        return hits;
    }

    private float[] nearCentre() {
        float[] centre = centres.get(random.nextInt(centres.size()));
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (centre[i] + random.nextGaussian() * CLUSTER_SPREAD);
        }
        return unit(vector);
    }

    private float[] gaussian() {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    private static float[] unit(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    @Override
    public void close() {
        for (HnswGraph graph : graphs.values()) {
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
        catalog.close();
        arena.close();
    }
}
