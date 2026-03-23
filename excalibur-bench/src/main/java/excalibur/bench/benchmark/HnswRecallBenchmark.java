package excalibur.bench.benchmark;

import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for measuring HNSW recall@10.
 *
 * <p>This benchmark validates the Phase 4 requirement of ≥99% recall@10
 * for the HNSW vector index implementation.</p>
 *
 * <h2>Recall Calculation</h2>
 * <p>Recall@k = |HNSW results ∩ True top-k| / k</p>
 *
 * <p>For each query, we compute the true top-k nearest neighbors via brute force
 * and compare with HNSW approximate results.</p>
 *
 * <h2>Performance Target</h2>
 * <p>Target: ≥99% recall@10 with default configuration (M=16, efConstruction=200, efSearch=200)</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Recall values in range [0.0, 1.0]</li>
 *   <li>Vector dimensions: 64, 128, 256 (typical embedding sizes)</li>
 *   <li>Dataset sizes: 1K, 10K, 100K vectors</li>
 *   <li>Distance values non-negative for L2</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>JMH handles thread safety for benchmark execution. The HnswGraph being tested
 * has its own thread-safety guarantees documented in its class Javadoc.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "-Xms4g",
    "-Xmx4g"
})
public class HnswRecallBenchmark {

    /**
     * Target recall@10 (99%)
     */
    public static final double TARGET_RECALL_10 = 0.99;

    /**
     * Number of nearest neighbors to retrieve (k=10)
     */
    public static final int K = 10;

    /**
     * Dataset sizes to test.
     */
    @Param({"1000", "10000"})
    public int datasetSize;

    /**
     * Vector dimensions to test.
     */
    @Param({"64", "128"})
    public int dimension;

    /**
     * efSearch values to test.
     * Default (200) should achieve ≥99% recall@10.
     */
    @Param({"100", "200", "300"})
    public int efSearch;

    // Benchmark state
    private Path tempFile;
    private HnswGraph graph;
    private float[][] dataset;
    private float[][] queries;
    private int queryCount = 100;

    // Pre-computed ground truth for recall calculation
    private List<List<Long>> groundTruth;

    // Distance function
    private DistanceFunction distanceFunction;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Create temp file for graph
        tempFile = Files.createTempFile("hnsw_recall_bench_", ".hnsw");

        // Use L2 distance (smaller is better)
        distanceFunction = L2Distance.getInstance();

        // Create configuration with specified efSearch
        HnswConfig config = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            efSearch,
            HnswConfig.DEFAULT_ML,
            dimension
        );

        // Create graph
        graph = new HnswGraph(tempFile, config, distanceFunction, datasetSize * 2);

        // Generate random dataset with fixed seed for reproducibility
        Random random = new Random(42);
        dataset = new float[datasetSize][dimension];

        System.out.println("Generating " + datasetSize + " vectors with dimension " + dimension + "...");
        for (int i = 0; i < datasetSize; i++) {
            for (int d = 0; d < dimension; d++) {
                dataset[i][d] = (float) random.nextGaussian();
            }
            // Normalize vectors for more meaningful distances
            normalize(dataset[i]);
        }

        // Insert vectors into graph
        System.out.println("Inserting vectors into HNSW graph (efConstruction=" +
            config.efConstruction() + ", M=" + config.maxConnections() + ")...");
        for (int i = 0; i < datasetSize; i++) {
            graph.insert(dataset[i]);
        }

        // Generate query vectors (different from dataset)
        System.out.println("Generating " + queryCount + " query vectors...");
        random = new Random(123); // Different seed for queries
        queries = new float[queryCount][dimension];
        for (int i = 0; i < queryCount; i++) {
            for (int d = 0; d < dimension; d++) {
                queries[i][d] = (float) random.nextGaussian();
            }
            normalize(queries[i]);
        }

        // Compute ground truth via brute force
        System.out.println("Computing ground truth via brute force...");
        groundTruth = new ArrayList<>();
        for (int q = 0; q < queryCount; q++) {
            groundTruth.add(computeTrueTopK(queries[q], K));
        }

        // Log setup info
        System.out.println();
        System.out.println("HNSW Recall Benchmark Setup:");
        System.out.println("  Dataset size: " + datasetSize);
        System.out.println("  Dimension: " + dimension);
        System.out.println("  efConstruction: " + config.efConstruction());
        System.out.println("  efSearch: " + efSearch);
        System.out.println("  M (maxConnections): " + config.maxConnections());
        System.out.println("  M0 (maxConnectionsLayer0): " + config.maxConnectionsLayer0());
        System.out.println("  Node count: " + graph.nodeCount());
        System.out.println("  Max level: " + graph.maxLevel());
        System.out.println("  Query count: " + queryCount);
        System.out.println("  K (top-k): " + K);
        System.out.println("  Target recall@10: >= " + (TARGET_RECALL_10 * 100) + "%");
        System.out.println();

        // Verify initial recall meets target
        double initialRecall = measureRecallInternal();
        System.out.println("Initial recall@10: " + String.format("%.4f", initialRecall) +
            " (" + String.format("%.2f", initialRecall * 100) + "%)");
        if (initialRecall < TARGET_RECALL_10) {
            System.out.println("WARNING: Initial recall below target! This may indicate a configuration issue.");
        }
        System.out.println();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (graph != null) {
            graph.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== Recall Measurement ====================

    /**
     * Helper method to measure recall without Blackhole (for setup/verification).
     */
    private double measureRecallInternal() {
        int totalFound = 0;
        int totalExpected = queryCount * K;

        for (int q = 0; q < queryCount; q++) {
            List<HnswSearchResult> results = graph.search(queries[q], K);
            Set<Long> resultIds = new HashSet<>();
            for (HnswSearchResult r : results) {
                resultIds.add(r.nodeId());
            }

            // Count how many true top-k are in the results
            for (Long trueId : groundTruth.get(q)) {
                if (resultIds.contains(trueId)) {
                    totalFound++;
                }
            }
        }

        return (double) totalFound / totalExpected;
    }

    /**
     * Benchmark: Measure recall@10 for HNSW search.
     * This is the primary metric for Phase 4 validation.
     */
    @Benchmark
    public double measureRecall(Blackhole bh) {
        int totalFound = 0;
        int totalExpected = queryCount * K;

        for (int q = 0; q < queryCount; q++) {
            List<HnswSearchResult> results = graph.search(queries[q], K);
            Set<Long> resultIds = new HashSet<>();
            for (HnswSearchResult r : results) {
                resultIds.add(r.nodeId());
            }

            // Count how many true top-k are in the results
            for (Long trueId : groundTruth.get(q)) {
                if (resultIds.contains(trueId)) {
                    totalFound++;
                }
            }
        }

        double recall = (double) totalFound / totalExpected;
        bh.consume(recall);
        return recall;
    }

    /**
     * Benchmark: Measure search latency (time per query).
     */
    @Benchmark
    public List<HnswSearchResult> searchLatency(Blackhole bh) {
        // Use round-robin through queries
        int queryIdx = (int) (System.nanoTime() % queryCount);
        return graph.search(queries[queryIdx], K);
    }

    /**
     * Benchmark: Measure queries per second throughput.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int searchThroughput(Blackhole bh) {
        int resultCount = 0;
        for (int q = 0; q < 100; q++) {
            List<HnswSearchResult> results = graph.search(queries[q % queryCount], K);
            resultCount += results.size();
        }
        bh.consume(resultCount);
        return resultCount;
    }

    // ==================== Helper Methods ====================

    /**
     * Computes the true top-k nearest neighbors via brute force.
     */
    private List<Long> computeTrueTopK(float[] query, int k) {
        PriorityQueue<NodeDistance> heap = new PriorityQueue<>(
            Comparator.comparingDouble(NodeDistance::distance).reversed()
        );

        for (long i = 0; i < datasetSize; i++) {
            float dist = distanceFunction.compute(query, dataset[(int) i]);
            if (heap.size() < k) {
                heap.offer(new NodeDistance(i, dist));
            } else if (dist < heap.peek().distance()) {
                heap.poll();
                heap.offer(new NodeDistance(i, dist));
            }
        }

        List<Long> topK = new ArrayList<>();
        while (!heap.isEmpty()) {
            topK.add(heap.poll().nodeId());
        }
        Collections.reverse(topK);
        return topK;
    }

    /**
     * Normalizes a vector to unit length.
     */
    private void normalize(float[] vector) {
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

    /**
     * Record for node-distance pairs in brute force search.
     */
    private record NodeDistance(long nodeId, float distance) {}

    // ==================== Validation Methods ====================

    /**
     * Validates that recall meets the target threshold.
     * Called externally to verify benchmark results.
     *
     * @param measuredRecall The measured recall value
     * @return true if recall meets target (≥99%)
     */
    public static boolean validateRecall(double measuredRecall) {
        return measuredRecall >= TARGET_RECALL_10;
    }

    /**
     * Gets the target recall@10 threshold.
     */
    public static double getTargetRecall() {
        return TARGET_RECALL_10;
    }
}
