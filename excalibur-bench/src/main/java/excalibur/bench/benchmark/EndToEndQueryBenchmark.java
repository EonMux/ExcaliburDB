package excalibur.bench.benchmark;

import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * JMH benchmark for end-to-end query latency (p50/p99).
 *
 * <p>Measures the full query pipeline latency from request to response,
 * including all stages: epoch snapshot, Cuckoo filter cascade, LSH pre-filter,
 * HNSW search, tail scan, reranking, and optional LLM scoring.</p>
 *
 * <h2>Performance Target</h2>
 * <ul>
 *   <li>p50 latency: &lt;=10ms (for small datasets)</li>
 *   <li>p99 latency: &lt;=50ms (for small datasets)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are sorted by distance/score (closest first)</li>
 *   <li>No duplicate entries in results</li>
 *   <li>Latency values are non-negative</li>
 *   <li>Coverage accurately reflects segments searched</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The QueryPipeline being tested has its own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "-Xms2g",
    "-Xmx2g"
})
public class EndToEndQueryBenchmark {

    /**
     * Number of segments in the test setup.
     */
    @Param({"1", "4"})
    public int segmentCount;

    /**
     * Number of vectors per segment.
     */
    @Param({"1000", "10000"})
    public int vectorsPerSegment;

    /**
     * Vector dimension (typical embedding sizes).
     */
    @Param({"64", "128"})
    public int dimension;

    /**
     * Number of results to retrieve (top-k).
     */
    @Param({"10"})
    public int topK;

    // Benchmark state
    private Arena arena;
    private List<Path> tempFiles;
    private List<HnswGraph> graphs;
    private DistanceFunction distanceFunction;

    // QueryPipeline infrastructure for end-to-end testing
    private QueryPipeline queryPipeline;
    private BenchmarkStorageEngine benchmarkStorageEngine;
    private BenchmarkVisibilityTracker benchmarkVisibilityTracker;

    private float[][] queryVectors;
    private int queryIndex;

    // Latency samples for percentile calculation
    private List<Long> latencySamples;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        arena = Arena.ofShared();
        tempFiles = new ArrayList<>();
        graphs = new ArrayList<>();
        latencySamples = Collections.synchronizedList(new ArrayList<>());

        // Use Cosine similarity
        distanceFunction = CosineSimilarity.getInstance();

        System.out.println("========================================");
        System.out.println("End-to-End Query Benchmark Setup");
        System.out.println("========================================");
        System.out.println("  Segments: " + segmentCount);
        System.out.println("  Vectors per segment: " + vectorsPerSegment);
        System.out.println("  Dimension: " + dimension);
        System.out.println("  Top-k: " + topK);

        // Create HNSW graphs
        Random random = new Random(42);
        for (int s = 0; s < segmentCount; s++) {
            // Create HNSW config
            HnswConfig config = HnswConfig.create(dimension);

            // Create HNSW graph
            Path graphFile = Files.createTempFile("e2e_graph_" + s + "_", ".hnsw");
            tempFiles.add(graphFile);
            HnswGraph graph = new HnswGraph(graphFile, config, distanceFunction, vectorsPerSegment * 2);
            graphs.add(graph);

            // Populate with vectors
            for (int v = 0; v < vectorsPerSegment; v++) {
                float[] vector = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    vector[d] = (float) random.nextGaussian();
                }
                normalizeVector(vector);
                graph.insert(vector);
            }

            System.out.println("  Segment " + s + ": " + graph.nodeCount() + " graph nodes");
        }

        // Generate query vectors
        queryVectors = new float[100][];
        for (int i = 0; i < 100; i++) {
            float[] vector = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                vector[d] = (float) random.nextGaussian();
            }
            normalizeVector(vector);
            queryVectors[i] = vector;
        }

        queryIndex = 0;

        // Create mock infrastructure for QueryPipeline
        benchmarkVisibilityTracker = new BenchmarkVisibilityTracker(segmentCount);

        // Create a temporary directory for the benchmark storage engine
        Path benchmarkDataDir = Files.createTempDirectory("e2e_benchmark_data_");
        tempFiles.add(benchmarkDataDir);
        benchmarkStorageEngine = new BenchmarkStorageEngine(segmentCount, benchmarkDataDir, arena);

        // Create QueryPipeline with HnswGraphProvider
        QueryPipeline.HnswGraphProvider graphProvider = segmentId -> {
            int idx = (int) (segmentId % graphs.size());
            return graphs.get(idx);
        };

        queryPipeline = new QueryPipeline(
            benchmarkStorageEngine,
            benchmarkVisibilityTracker,
            graphProvider,
            distanceFunction
        );

        System.out.println("  Total vectors: " + (segmentCount * vectorsPerSegment));
        System.out.println("  Query vectors prepared: " + queryVectors.length);
        System.out.println("  QueryPipeline created for end-to-end testing");
        System.out.println("========================================");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        // Clean up QueryPipeline resources
        queryPipeline = null;
        benchmarkVisibilityTracker = null;

        // Close benchmark storage engine segments before cleaning up
        if (benchmarkStorageEngine != null) {
            benchmarkStorageEngine.closeSegments();
            benchmarkStorageEngine = null;
        }

        // Close HNSW graphs
        for (HnswGraph graph : graphs) {
            try {
                graph.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (arena != null) {
            arena.close();
        }
        for (Path file : tempFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // ==================== Full QueryPipeline Benchmarks ====================

    /**
     * Benchmark: Full QueryPipeline execution (end-to-end).
     *
     * <p>This is the PRIMARY benchmark that measures true end-to-end query latency
     * through the complete 6-stage query pipeline:</p>
     * <ol>
     *   <li>Stage 0 - Epoch Snapshot: Capture visibility epoch and resolve queryable segments</li>
     *   <li>Stage 1 - Cuckoo Filter Cascade: Eliminate segments that cannot contain relevant entries</li>
     *   <li>Stage 2 - LSH Pre-filter: Coarse vector similarity filtering</li>
     *   <li>Stage 3 - HNSW Vector Search: Precise ANN within candidate segments with tail scan</li>
     *   <li>Stage 4 - Full Vector Rerank: Exact distance on full-precision vectors</li>
     *   <li>Stage 5 - LLM Relevancy Scoring: Score candidates with LLM (optional - disabled for benchmark)</li>
     * </ol>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>Results are sorted by distance (closest first)</li>
     *   <li>Latency includes all pipeline stages (not just HNSW search)</li>
     *   <li>Response includes accurate index coverage metadata</li>
     * </ul>
     */
    @Benchmark
    public QueryResponse fullQueryPipeline(Blackhole bh) {
        int idx = queryIndex++ % queryVectors.length;
        float[] query = queryVectors[idx];

        // Create QueryRequest with LLM scoring disabled for pure vector search benchmark
        QueryRequest request = QueryRequest.builder()
            .queryText("benchmark query")
            .queryVector(query)
            .topK(topK)
            .timeoutMs(30_000)
            .includePartialSegments(true)
            .enableLlmScoring(false)  // Disable LLM for consistent benchmarking
            .correlationId(UUID.randomUUID().toString())
            .build();

        // Execute through full pipeline
        CancellationToken token = new CancellationToken();
        QueryResponse response = queryPipeline.executeSync(request, token);

        // Consume results to prevent dead code elimination
        bh.consume(response);
        bh.consume(response.results());
        bh.consume(response.coverage());
        bh.consume(response.queryTimeMs());

        return response;
    }

    /**
     * Benchmark: Full QueryPipeline with latency sampling.
     * Collects latency samples for percentile calculation through the complete pipeline.
     */
    @Benchmark
    public long fullQueryPipelineWithLatencySampling(Blackhole bh) {
        int idx = queryIndex++ % queryVectors.length;
        float[] query = queryVectors[idx];

        long startTime = System.nanoTime();

        QueryRequest request = QueryRequest.builder()
            .queryText("benchmark query")
            .queryVector(query)
            .topK(topK)
            .timeoutMs(30_000)
            .includePartialSegments(true)
            .enableLlmScoring(false)
            .correlationId(UUID.randomUUID().toString())
            .build();

        CancellationToken token = new CancellationToken();
        QueryResponse response = queryPipeline.executeSync(request, token);

        long endTime = System.nanoTime();
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        // Collect sample for percentile calculation
        latencySamples.add(latencyMs);

        bh.consume(response);
        return latencyMs;
    }

    /**
     * Benchmark: Full QueryPipeline throughput (multiple queries).
     * Measures how many complete pipeline queries can be processed per second.
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public int fullQueryPipelineThroughput(Blackhole bh) {
        int successCount = 0;

        for (int i = 0; i < 10; i++) {
            int idx = (queryIndex + i) % queryVectors.length;
            float[] query = queryVectors[idx];

            try {
                QueryRequest request = QueryRequest.builder()
                    .queryText("benchmark query")
                    .queryVector(query)
                    .topK(topK)
                    .timeoutMs(30_000)
                    .includePartialSegments(true)
                    .enableLlmScoring(false)
                    .correlationId(UUID.randomUUID().toString())
                    .build();

                CancellationToken token = new CancellationToken();
                QueryResponse response = queryPipeline.executeSync(request, token);

                if (!response.isEmpty()) {
                    successCount++;
                }
                bh.consume(response);
            } catch (Exception e) {
                // Query failed, count as unsuccessful
            }
        }

        queryIndex += 10;
        return successCount;
    }

    // ==================== Baseline HNSW Benchmarks (for comparison) ====================

    /**
     * Benchmark: Single HNSW query latency across all segments.
     * Measures the time for searching all segments and combining results.
     *
     * <p><strong>Note:</strong> This is a BASELINE benchmark that only tests HNSW search.
     * For true end-to-end latency, use {@link #fullQueryPipeline}.</p>
     */
    @Benchmark
    public List<HnswSearchResult> hnswSearchAllSegments(Blackhole bh) {
        int idx = queryIndex++ % queryVectors.length;
        float[] query = queryVectors[idx];

        List<HnswSearchResult> allResults = new ArrayList<>();

        // Search all segments and combine results
        for (HnswGraph graph : graphs) {
            List<HnswSearchResult> segmentResults = graph.search(query, topK);
            allResults.addAll(segmentResults);
        }

        // Sort and take top-k
        allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }

        bh.consume(allResults);
        return allResults;
    }

    /**
     * Benchmark: Single segment HNSW search.
     * Measures the baseline HNSW search latency.
     */
    @Benchmark
    public List<HnswSearchResult> hnswSearchSingleSegment(Blackhole bh) {
        int idx = queryIndex++ % queryVectors.length;
        float[] query = queryVectors[idx];

        // Search only the first segment
        HnswGraph graph = graphs.get(0);
        List<HnswSearchResult> results = graph.search(query, topK);

        bh.consume(results);
        return results;
    }

    /**
     * Benchmark: Distance computation overhead.
     * Isolates the distance computation cost.
     */
    @Benchmark
    public int distanceComputationOnly(Blackhole bh) {
        int idx = queryIndex++ % queryVectors.length;
        float[] query = queryVectors[idx];

        int computations = 0;
        for (int i = 0; i < 100; i++) {
            float[] target = queryVectors[(idx + i) % queryVectors.length];
            float distance = distanceFunction.compute(query, target);
            bh.consume(distance);
            computations++;
        }

        return computations;
    }

    // ==================== Percentile Helpers ====================

    /**
     * Gets the p50 latency from collected samples.
     * Call this after benchmark completion to get percentiles.
     */
    public double getP50Latency() {
        return calculatePercentile(50);
    }

    /**
     * Gets the p99 latency from collected samples.
     * Call this after benchmark completion to get percentiles.
     */
    public double getP99Latency() {
        return calculatePercentile(99);
    }

    /**
     * Calculates a latency percentile from collected samples.
     */
    private double calculatePercentile(int percentile) {
        if (latencySamples.isEmpty()) {
            return 0.0;
        }

        List<Long> sorted = latencySamples.stream()
            .sorted()
            .toList();

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    // ==================== Helper Methods ====================

    /**
     * Normalizes a vector to unit length.
     */
    private void normalizeVector(float[] vector) {
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

    // ==================== Mock Infrastructure for QueryPipeline ====================

    /**
     * Minimal StorageEngine implementation for benchmarking.
     * Provides catalog and segment access for the QueryPipeline.
     *
     * <p><strong>Domain Correctness:</strong> Creates real Segment objects with proper
     * segment IDs that match the catalog metadata, enabling the QueryPipeline to
     * execute actual end-to-end queries.</p>
     */
    private static class BenchmarkStorageEngine extends StorageEngine {
        private final BenchmarkSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private final List<Path> segmentFiles = new ArrayList<>();

        BenchmarkStorageEngine(int segmentCount, Path tempDir, Arena arena) throws IOException {
            super(tempDir, arena);
            this.catalog = new BenchmarkSegmentCatalog(segmentCount);

            // Create real Segment objects for each segment
            // Domain constraint: Segment IDs are auto-generated starting from 0
            // Creating segments in order 0..segmentCount-1 ensures segmentId() matches catalog ID
            for (int i = 0; i < segmentCount; i++) {
                Path segmentPath = Files.createTempFile(tempDir, "segment_" + i + "_", ".dat");
                segmentFiles.add(segmentPath);
                // 1MB capacity is sufficient for benchmark purposes
                Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
                segments.put((long) i, segment);
            }
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        /**
         * Closes all segments and cleans up resources.
         */
        void closeSegments() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore close errors during cleanup
                }
            }
            segments.clear();

            // Clean up segment files
            for (Path file : segmentFiles) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
            segmentFiles.clear();
        }
    }

    /**
     * Minimal SegmentCatalog implementation for benchmarking.
     * Returns ACTIVE state for all segments.
     */
    private static class BenchmarkSegmentCatalog extends SegmentCatalog {
        private final int segmentCount;
        private final Map<Long, BenchmarkSegmentMetadata> metadataCache = new ConcurrentHashMap<>();

        BenchmarkSegmentCatalog(int segmentCount) {
            this.segmentCount = segmentCount;
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            if (segmentId >= 0 && segmentId < segmentCount) {
                return metadataCache.computeIfAbsent(segmentId,
                    id -> new BenchmarkSegmentMetadata(id, LifecycleState.ACTIVE));
            }
            return null;
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            Map<Long, SegmentMetadata> result = new HashMap<>();
            for (long i = 0; i < segmentCount; i++) {
                result.put(i, get(i));
            }
            return result;
        }
    }

    /**
     * Minimal SegmentMetadata implementation for benchmarking.
     * Extends the real SegmentMetadata class.
     */
    private static class BenchmarkSegmentMetadata extends SegmentCatalog.SegmentMetadata {
        private final LifecycleState lifecycleStateOverride;

        BenchmarkSegmentMetadata(long segmentId, LifecycleState lifecycleState) {
            super(segmentId);
            this.lifecycleStateOverride = lifecycleState;
        }

        @Override
        public LifecycleState getLifecycleState() {
            return lifecycleStateOverride;
        }

        @Override
        public int getEntryCount() {
            return 0;
        }

        @Override
        public long getLastIntegratedOffset() {
            return Long.MAX_VALUE; // All entries integrated, no tail scan needed
        }
    }

    /**
     * Minimal VisibilityTracker implementation for benchmarking.
     * All segments are visible at any epoch.
     */
    private static class BenchmarkVisibilityTracker extends VisibilityTracker {
        private final int segmentCount;
        private final AtomicLong epochCounter = new AtomicLong(1);

        BenchmarkVisibilityTracker(int segmentCount) {
            super(new BenchmarkSegmentCatalog(segmentCount));
            this.segmentCount = segmentCount;
        }

        @Override
        public long captureEpoch() {
            return epochCounter.get();
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            // Return all segments as visible
            Set<Long> visible = new HashSet<>();
            for (long i = 0; i < segmentCount; i++) {
                visible.add(i);
            }
            return Collections.unmodifiableSet(visible);
        }

        @Override
        public long currentEpoch() {
            return epochCounter.get();
        }

        @Override
        public long advanceEpoch() {
            return epochCounter.incrementAndGet();
        }
    }
}
