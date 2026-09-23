package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.bench.kernel.LatencySamples;
import com.eonmux.excalibur.bench.kernel.Vectors;
import com.eonmux.excalibur.bench.metric.LatencyPercentileValidator;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
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

/**
 * JMH benchmark for end-to-end query latency (p50/p99).
 *
 * <p>Measures the full query pipeline latency from request to response,
 * including all stages: epoch snapshot, LSH candidates, HNSW search, tail scan, reranking,
 * and optional LLM scoring.</p>
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
    "--add-modules=jdk.incubator.vector",
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

    // Latency samples for percentile calculation (Double for sub-millisecond precision)
    private LatencySamples latencySamples;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        arena = Arena.ofShared();
        tempFiles = new ArrayList<>();
        graphs = new ArrayList<>();
        latencySamples = new LatencySamples();

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
                graph.insert(Vectors.normalized(vector));
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
            queryVectors[i] = Vectors.normalized(vector);
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
     *   <li>Stage 1 - LSH Candidates: Name entries sharing the query's buckets, measured beside the graph search</li>
     *   <li>Stage 2 - HNSW Vector Search: Precise ANN within candidate segments with tail scan</li>
     *   <li>Stage 3 - Full Vector Rerank: Exact distance on full-precision vectors</li>
     *   <li>Stage 4 - LLM Relevancy Scoring: Score candidates with LLM (optional - disabled for benchmark)</li>
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
        // Use floating-point division for sub-millisecond precision
        // Domain correctness: Real latency should be positive, not truncated to 0
        double latencyMs = (endTime - startTime) / 1_000_000.0;

        // Collect sample for percentile calculation
        latencySamples.record(latencyMs);

        bh.consume(response);
        return (long) latencyMs;
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

            // Query failures propagate deliberately. Swallowing them here let this
            // benchmark keep reporting a throughput number while 100% of queries threw,
            // in which case it was measuring exception construction. JMH treats a thrown
            // benchmark as a failure, which is the correct outcome.
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
    @OperationsPerInvocation(100)
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
     * Gets the p50 latency from collected samples (in milliseconds).
     * Call this after benchmark completion to get percentiles.
     *
     * @return p50 latency in milliseconds, or 0.0 if no samples collected
     */
    public double getP50Latency() {
        return calculatePercentile(50);
    }

    /**
     * Gets the p99 latency from collected samples (in milliseconds).
     * Call this after benchmark completion to get percentiles.
     *
     * @return p99 latency in milliseconds, or 0.0 if no samples collected
     */
    public double getP99Latency() {
        return calculatePercentile(99);
    }

    /**
     * Gets the number of latency samples collected.
     *
     * @return sample count
     */
    public int getSampleCount() {
        return latencySamples.count();
    }

    /**
     * Validates the collected latency percentiles for domain correctness.
     *
     * <p>Ensures:</p>
     * <ul>
     *   <li>p50 &lt; p99 (strict ordering for non-constant distributions)</li>
     *   <li>Both values are non-negative</li>
     *   <li>Both values are in milliseconds (reasonable range)</li>
     * </ul>
     *
     * @return validation result indicating success or failure with details
     */
    public LatencyPercentileValidator.ValidationResult validateLatencyPercentiles() {
        return LatencyPercentileValidator.validate(getP50Latency(), getP99Latency(), latencySamples.count());
    }

    /**
     * Gets a formatted summary of latency percentiles with validation status.
     *
     * @return human-readable summary string
     */
    public String formatLatencySummary() {
        return LatencyPercentileValidator.formatSummary(getP50Latency(), getP99Latency());
    }

    /**
     * Calculates a latency percentile from collected samples.
     *
     * @param percentile the percentile to calculate (0-100)
     * @return the latency value in milliseconds, or 0.0 if no samples
     */
    private double calculatePercentile(int percentile) {
        return latencySamples.percentile(percentile);
    }

    // ==================== Helper Methods ====================

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

        @Override
        public int size() {
            return segmentCount;
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
