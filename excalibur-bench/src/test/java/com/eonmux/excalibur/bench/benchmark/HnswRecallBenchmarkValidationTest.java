package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the HNSW implementation really achieves the advertised recall@10.
 *
 * <h2>Why the dataset is much larger than efSearch</h2>
 * <p>HNSW's layer-0 beam search visits candidates until the beam of width
 * {@code efSearch} can no longer improve. When {@code efSearch} is greater than or equal
 * to the number of nodes in the graph, the beam never fills, the early-exit condition
 * can never fire, and the search degenerates into an exhaustive traversal of the
 * reachable component. Recall is then 1.0 for <em>any</em> edge structure, so a
 * {@code recall >= 0.99} assertion cannot detect a regression in neighbour selection or
 * pruning - the very thing that makes HNSW work.</p>
 *
 * <p>{@link #DATASET_SIZE} is therefore at least {@link #MIN_DATASET_TO_EF_RATIO} times
 * the efSearch under test, and {@link #searchIsApproximateNotExhaustive()} proves the
 * beam is genuinely selective at this size by showing that a narrow beam recovers
 * materially less than the true top-k.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Recall values must be in range [0.0, 1.0] - enforced by clamping</li>
 *   <li>Recall@10 increases with efSearch</li>
 *   <li>efSearch=200 achieves at least 99% recall@10</li>
 *   <li>Uses HnswRecallBenchmark.TARGET_RECALL_10 constant for consistency</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class HnswRecallBenchmarkValidationTest {

    /**
     * Corpus size. Must stay well above the efSearch values under test so that the
     * beam search is selective rather than exhaustive.
     */
    private static final int DATASET_SIZE = 2000;

    private static final int DIMENSION = 64;
    private static final int K = 10;
    private static final int QUERY_COUNT = 20;

    /**
     * Minimum corpus-size to efSearch ratio for a recall measurement to be meaningful.
     */
    private static final int MIN_DATASET_TO_EF_RATIO = 10;

    /**
     * The default efSearch the recall guarantee is stated for.
     */
    private static final int DEFAULT_EF_SEARCH = HnswConfig.DEFAULT_EF_SEARCH;

    /**
     * A deliberately narrow beam, used to prove the search is approximate at this
     * corpus size. Kept above K so the graph can still return a full result set.
     */
    private static final int NARROW_EF_SEARCH = 16;

    /**
     * An intermediate beam width, used for the monotonicity check.
     */
    private static final int MEDIUM_EF_SEARCH = 50;

    /**
     * Target recall@10 threshold - uses the same constant as HnswRecallBenchmark.
     * Value: 0.99 (99%)
     */
    private static final double TARGET_RECALL = HnswRecallBenchmark.TARGET_RECALL_10;

    @TempDir
    static Path tempDir;

    private static float[][] dataset;
    private static float[][] queries;
    private static List<Set<Long>> groundTruth;
    private static DistanceFunction distanceFunction;

    /**
     * One graph per efSearch under test. Built once: construction is independent of
     * efSearch, but the value is baked into the graph's persisted config.
     */
    private static Map<Integer, HnswGraph> graphs;

    @BeforeAll
    static void buildCorpus() throws IOException {
        distanceFunction = L2Distance.getInstance();

        Random random = new Random(42);
        dataset = new float[DATASET_SIZE][DIMENSION];
        for (int i = 0; i < DATASET_SIZE; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                dataset[i][d] = (float) random.nextGaussian();
            }
            normalize(dataset[i]);
        }

        random = new Random(123);
        queries = new float[QUERY_COUNT][DIMENSION];
        for (int i = 0; i < QUERY_COUNT; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                queries[i][d] = (float) random.nextGaussian();
            }
            normalize(queries[i]);
        }

        groundTruth = new ArrayList<>();
        for (int q = 0; q < QUERY_COUNT; q++) {
            groundTruth.add(computeTrueTopK(queries[q]));
        }

        graphs = new LinkedHashMap<>();
        for (int efSearch : new int[]{NARROW_EF_SEARCH, MEDIUM_EF_SEARCH, DEFAULT_EF_SEARCH}) {
            graphs.put(efSearch, buildGraph(efSearch));
        }
    }

    @AfterAll
    static void closeGraphs() {
        if (graphs == null) {
            return;
        }
        for (HnswGraph graph : graphs.values()) {
            graph.close();
        }
        graphs = null;
    }

    @Test
    @DisplayName("The corpus is large enough for the recall measurement to be meaningful")
    void datasetIsLargeEnoughForTheBeamToBeSelective() {
        // Guards the premise of every other test in this class. If this ratio is
        // reduced, the beam covers the whole graph and the recall assertions below
        // become tautologies.
        assertThat(DATASET_SIZE)
            .as("dataset must be at least %dx efSearch=%d for the beam to be selective",
                MIN_DATASET_TO_EF_RATIO, DEFAULT_EF_SEARCH)
            .isGreaterThanOrEqualTo(DEFAULT_EF_SEARCH * MIN_DATASET_TO_EF_RATIO);

        assertThat(graphs.get(DEFAULT_EF_SEARCH).nodeCount()).isEqualTo(DATASET_SIZE);
    }

    @Test
    @DisplayName("A narrow beam recovers materially less than the true top-k")
    void searchIsApproximateNotExhaustive() {
        double narrowRecall = measureRecall(graphs.get(NARROW_EF_SEARCH));

        System.out.printf("Recall@%d with efSearch=%d over %d vectors: %.4f%n",
            K, NARROW_EF_SEARCH, DATASET_SIZE, narrowRecall);

        // If the beam were traversing the whole reachable graph, a beam of 16 over 2000
        // nodes would still return the exact top-k. That it does not is the evidence
        // that the recall figures below measure graph quality, not exhaustive scanning.
        assertThat(narrowRecall)
            .as("efSearch=%d over %d vectors must be genuinely approximate (got %.2f%%)",
                NARROW_EF_SEARCH, DATASET_SIZE, narrowRecall * 100)
            .isLessThan(TARGET_RECALL);

        assertThat(narrowRecall)
            .as("even a narrow beam must find most neighbours - a collapse here means "
                + "the graph is disconnected, not merely approximate")
            .isGreaterThan(0.5);
    }

    @Test
    @DisplayName("efSearch=200 achieves at least 99% recall@10")
    void defaultEfSearchAchievesTargetRecall() {
        double recall = measureRecall(graphs.get(DEFAULT_EF_SEARCH));

        System.out.printf("Recall@%d with efSearch=%d over %d vectors: %.4f (%.2f%%)%n",
            K, DEFAULT_EF_SEARCH, DATASET_SIZE, recall, recall * 100);

        assertThat(recall)
            .as("Recall must be in range [0.0, 1.0]")
            .isBetween(0.0, 1.0);

        assertThat(recall)
            .as("Recall@10 must be >= 99%% with efSearch=%d over %d vectors (got %.2f%%)",
                DEFAULT_EF_SEARCH, DATASET_SIZE, recall * 100)
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    @Test
    @DisplayName("Recall increases monotonically with efSearch")
    void recallIncreasesWithEfSearch() {
        double narrowRecall = measureRecall(graphs.get(NARROW_EF_SEARCH));
        double mediumRecall = measureRecall(graphs.get(MEDIUM_EF_SEARCH));
        double defaultRecall = measureRecall(graphs.get(DEFAULT_EF_SEARCH));

        System.out.println("Recall@10 vs efSearch:");
        System.out.printf("  efSearch=%d: %.2f%%%n", NARROW_EF_SEARCH, narrowRecall * 100);
        System.out.printf("  efSearch=%d: %.2f%%%n", MEDIUM_EF_SEARCH, mediumRecall * 100);
        System.out.printf("  efSearch=%d: %.2f%%%n", DEFAULT_EF_SEARCH, defaultRecall * 100);

        assertThat(narrowRecall).isBetween(0.0, 1.0);
        assertThat(mediumRecall).isBetween(0.0, 1.0);
        assertThat(defaultRecall).isBetween(0.0, 1.0);

        assertThat(mediumRecall)
            .as("a wider beam must not lose neighbours a narrower one found")
            .isGreaterThan(narrowRecall);

        assertThat(defaultRecall)
            .as("a wider beam must not lose neighbours a narrower one found")
            .isGreaterThan(mediumRecall);
    }

    @Test
    @DisplayName("Search throughput is measurable and within the search latency target")
    void searchLatencyIsWithinTarget() {
        HnswGraph graph = graphs.get(DEFAULT_EF_SEARCH);

        for (int i = 0; i < 20; i++) {
            graph.search(queries[i % QUERY_COUNT], K);
        }

        int iterations = 200;
        double[] latenciesMs = new double[iterations];
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long queryStart = System.nanoTime();
            graph.search(queries[i % QUERY_COUNT], K);
            latenciesMs[i] = (System.nanoTime() - queryStart) / 1_000_000.0;
        }
        long elapsedNanos = System.nanoTime() - start;
        long qps = (long) (iterations * 1_000_000_000.0 / elapsedNanos);

        java.util.Arrays.sort(latenciesMs);
        double medianLatencyMs = latenciesMs[iterations / 2];

        System.out.printf("efSearch=%d: median %.4f ms/query, %d queries/sec%n",
            DEFAULT_EF_SEARCH, medianLatencyMs, qps);

        assertThat(qps)
            .as("QPS should be positive")
            .isPositive();

        // The registered HNSW search latency target. Asserting it here is what makes the
        // number mean something: the JMH benchmark reports it but nothing gated it. The
        // median is used rather than the mean so an unrelated GC pause on a loaded
        // machine cannot fail the build, while a real slowdown still does.
        assertThat(medianLatencyMs)
            .as("median search latency must stay within the %.1f ms target (got %.4f ms)",
                HNSW_SEARCH_LATENCY_TARGET_MS, medianLatencyMs)
            .isLessThanOrEqualTo(HNSW_SEARCH_LATENCY_TARGET_MS);
    }

    /**
     * The HNSW search latency target registered in BenchmarkTargetRegistry.
     */
    private static final double HNSW_SEARCH_LATENCY_TARGET_MS = 10.0;

    // ==================== Helpers ====================

    private static HnswGraph buildGraph(int efSearch) throws IOException {
        Path graphFile = tempDir.resolve("hnsw_ef" + efSearch + ".hnsw");
        HnswConfig config = HnswConfig.create(DIMENSION, efSearch);
        HnswGraph graph = new HnswGraph(graphFile, config, distanceFunction, DATASET_SIZE * 2L);
        for (int i = 0; i < DATASET_SIZE; i++) {
            graph.insert(dataset[i]);
        }
        return graph;
    }

    /**
     * Measures recall@k for the given graph against the brute-force ground truth.
     *
     * @param graph the HNSW graph to search
     * @return recall@k in range [0.0, 1.0]
     */
    private double measureRecall(HnswGraph graph) {
        int totalFound = 0;
        int totalExpected = QUERY_COUNT * K;

        for (int q = 0; q < QUERY_COUNT; q++) {
            Set<Long> resultIds = new HashSet<>();
            for (HnswSearchResult result : graph.search(queries[q], K)) {
                resultIds.add(result.nodeId());
            }
            for (Long trueId : groundTruth.get(q)) {
                if (resultIds.contains(trueId)) {
                    totalFound++;
                }
            }
        }

        return HnswRecallBenchmark.clampRecall((double) totalFound / totalExpected);
    }

    /**
     * Computes the true top-k nearest neighbours by brute force.
     */
    private static Set<Long> computeTrueTopK(float[] query) {
        PriorityQueue<double[]> heap = new PriorityQueue<>(
            (a, b) -> Double.compare(b[1], a[1]) // max-heap by distance
        );

        for (long i = 0; i < DATASET_SIZE; i++) {
            double distance = distanceFunction.compute(query, dataset[(int) i]);
            if (heap.size() < K) {
                heap.offer(new double[]{i, distance});
            } else if (distance < heap.peek()[1]) {
                heap.poll();
                heap.offer(new double[]{i, distance});
            }
        }

        Set<Long> topK = new HashSet<>();
        while (!heap.isEmpty()) {
            topK.add((long) heap.poll()[0]);
        }
        return topK;
    }

    /**
     * Normalizes a vector to unit length.
     */
    private static void normalize(float[] vector) {
        float norm = 0.0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }
}
