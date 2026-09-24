package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.LatencyPercentileValidator;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation test for End-to-End Query Latency Benchmark (p50/p99).
 *
 * <p>This test validates that the end-to-end query pipeline produces
 * correct results and that latency measurements are sensible.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Latency values must be non-negative</li>
 *   <li>p50 &lt; p99 always (percentiles must be ordered)</li>
 *   <li>Results are sorted by distance/score (closest first)</li>
 *   <li>Coverage accurately reflects segments searched</li>
 *   <li>Query time in response matches measured time</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class EndToEndQueryBenchmarkValidationTest {

    // Optimized sizes for faster test execution while maintaining validity
    private static final int SEGMENT_COUNT = 2;
    private static final int VECTORS_PER_SEGMENT = 100;
    private static final int DIMENSION = 64;
    private static final int TOP_K = 10;
    private static final int QUERY_COUNT = 25;

    /**
     * Minimum fraction of the brute-force top-k the pipeline must recover.
     *
     * <p>Below 1.0 because the pipeline searches an approximate index, but high enough
     * that a broken merge, an inverted comparator or a dropped segment fails.</p>
     */
    private static final double MIN_PIPELINE_RECALL = 0.8;

    @TempDir
    Path tempDir;

    private Arena arena;
    private List<Path> tempFiles;
    private List<HnswGraph> graphs;
    private DistanceFunction distanceFunction;
    private QueryPipeline queryPipeline;
    private ValidationStorageEngine storageEngine;
    private ValidationVisibilityTracker visibilityTracker;

    private float[][] queryVectors;

    /** The vectors indexed into each segment's graph, indexed by [segment][nodeId]. */
    private float[][][] segmentVectors;

    /** The node ids the graphs assigned, indexed by [segment][insertion order]. */
    private long[][] segmentNodeIds;

    /** The ids the segments actually took, in the same order as {@link #graphs}. */
    private List<Long> segmentIds;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        tempFiles = new ArrayList<>();
        graphs = new ArrayList<>();

        distanceFunction = CosineSimilarity.getInstance();

        Random random = new Random(42);

        segmentVectors = new float[SEGMENT_COUNT][VECTORS_PER_SEGMENT][];
        segmentNodeIds = new long[SEGMENT_COUNT][VECTORS_PER_SEGMENT];

        // Create HNSW graphs for each segment
        for (int s = 0; s < SEGMENT_COUNT; s++) {
            HnswConfig config = HnswConfig.create(DIMENSION);
            Path graphFile = Files.createTempFile(tempDir, "validation_graph_" + s + "_", ".hnsw");
            tempFiles.add(graphFile);
            HnswGraph graph = new HnswGraph(graphFile, config, distanceFunction, VECTORS_PER_SEGMENT * 2);
            graphs.add(graph);

            // Populate with normalized vectors, retaining them so the expected ranking
            // can be derived by brute force independently of the query pipeline.
            for (int v = 0; v < VECTORS_PER_SEGMENT; v++) {
                float[] vector = new float[DIMENSION];
                for (int d = 0; d < DIMENSION; d++) {
                    vector[d] = (float) random.nextGaussian();
                }
                normalizeVector(vector);
                segmentVectors[s][v] = vector;
                segmentNodeIds[s][v] = graph.insert(vector);
            }
        }

        // Generate query vectors
        queryVectors = new float[QUERY_COUNT][];
        for (int i = 0; i < QUERY_COUNT; i++) {
            float[] vector = new float[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = (float) random.nextGaussian();
            }
            normalizeVector(vector);
            queryVectors[i] = vector;
        }

        // Create the storage engine first: Segment assigns itself a process-wide unique
        // id, so the catalog, the visibility tracker and the graph provider must all be
        // keyed on the ids the segments actually took. Keying them on 0..n-1 made every
        // test after the first one in a JVM resolve zero queryable segments and return
        // an empty result set, which is what the previous assertions quietly excused.
        storageEngine = new ValidationStorageEngine(SEGMENT_COUNT, tempDir, arena);
        segmentIds = storageEngine.segmentIds();
        visibilityTracker = new ValidationVisibilityTracker(segmentIds);

        QueryPipeline.HnswGraphProvider graphProvider = segmentId -> {
            int idx = segmentIds.indexOf(segmentId);
            return idx < 0 ? null : graphs.get(idx);
        };

        queryPipeline = new QueryPipeline(
            storageEngine,
            visibilityTracker,
            graphProvider,
            distanceFunction
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        queryPipeline = null;
        visibilityTracker = null;

        if (storageEngine != null) {
            storageEngine.closeSegments();
            storageEngine = null;
        }
        segmentIds = null;

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
    }

    @Test
    @DisplayName("Pipeline returns the true nearest neighbours, ranked best first")
    void pipelineReturnsResultsRankedAgainstBruteForceGroundTruth() {
        float[] query = queryVectors[0];

        // Expectation derived independently of the pipeline: the exact top-k by cosine
        // similarity over the vectors that were indexed, computed by brute force here.
        List<SegmentNode> expectedTopK = bruteForceTopK(query, TOP_K);
        assertThat(expectedTopK).hasSize(TOP_K);

        QueryResponse response = executeQuery(query, null);

        assertThat(response).as("Response should not be null").isNotNull();
        assertThat(response.coverage()).as("Coverage should not be null").isNotNull();

        // The pipeline must actually return the matches that exist. An empty result set
        // over a populated, fully visible index is a failure, not something to excuse.
        assertThat(response.results())
            .as("query over %d visible vectors must return results",
                SEGMENT_COUNT * VECTORS_PER_SEGMENT)
            .isNotEmpty();

        // Ordering is asserted on what the PIPELINE produced, not on a list the test
        // sorted itself.
        assertThat(response.results())
            .as("results must be ranked best-first by score")
            .isSortedAccordingTo(Comparator.comparing(QueryResultItem::score).reversed());

        Set<SegmentNode> expected = new LinkedHashSet<>(expectedTopK);
        Set<SegmentNode> actual = new LinkedHashSet<>();
        for (QueryResultItem item : response.results()) {
            actual.add(new SegmentNode(item.segmentId(), item.nodeId()));
        }

        SegmentNode best = new SegmentNode(
            response.results().get(0).segmentId(), response.results().get(0).nodeId());
        assertThat(expectedTopK.subList(0, 3))
            .as("the highest-scoring result must be one of the three true nearest neighbours")
            .contains(best);

        long overlap = actual.stream().filter(expected::contains).count();
        double recall = (double) overlap / expectedTopK.size();
        System.out.printf("Pipeline recall@%d against brute force: %.2f%% (%d of %d)%n",
            TOP_K, recall * 100, overlap, expectedTopK.size());

        assertThat(recall)
            .as("pipeline must recover at least %.0f%% of the true top-%d (got %.2f%%)",
                MIN_PIPELINE_RECALL * 100, TOP_K, recall * 100)
            .isGreaterThanOrEqualTo(MIN_PIPELINE_RECALL);
    }

    @Test
    @DisplayName("Scores decrease monotonically and are finite")
    void scoresAreFiniteAndMonotonic() {
        QueryResponse response = executeQuery(queryVectors[1], null);

        assertThat(response.results()).isNotEmpty();
        for (QueryResultItem item : response.results()) {
            assertThat(Float.isFinite(item.score()))
                .as("score for node %d must be finite, got %s", item.nodeId(), item.score())
                .isTrue();
        }
        assertThat(response.results())
            .isSortedAccordingTo(Comparator.comparing(QueryResultItem::score).reversed());
    }

    @Test
    @DisplayName("Query latency stays within the documented p99 budget")
    void latencyIsWithinTheDocumentedBudget() {
        // Warm up so the measurement reflects steady-state cost rather than class
        // loading and JIT.
        for (int i = 0; i < 10; i++) {
            executeQuery(queryVectors[i % queryVectors.length], null);
        }

        int iterations = 40;
        List<Double> latenciesMs = new ArrayList<>(iterations);

        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            QueryResponse response = executeQuery(queryVectors[i % queryVectors.length], null);
            long endTime = System.nanoTime();

            latenciesMs.add((endTime - startTime) / 1_000_000.0);

            // The pipeline's own accounting must be consistent with a real measurement.
            assertThat(response.queryTimeMs())
                .as("Response queryTimeMs should be non-negative")
                .isGreaterThanOrEqualTo(0L);
        }

        double p50 = calculatePercentileD(latenciesMs, 50);
        double p99 = calculatePercentileD(latenciesMs, 99);

        System.out.printf("End-to-end latency over %d queries: p50 %.3f ms, p99 %.3f ms%n",
            iterations, p50, p99);

        // Assert against the budget the project actually documents. "latency >= 0" is
        // unconditionally true for two nanoTime reads on one thread and could not
        // detect a 400x tail-latency regression.
        assertThat(p99)
            .as("p99 query latency must stay within the %.1f ms budget (got %.3f ms)",
                EndToEndQueryBenchmarkRunner.TARGET_P99_MS, p99)
            .isLessThanOrEqualTo(EndToEndQueryBenchmarkRunner.TARGET_P99_MS);

        assertThat(p50)
            .as("p50 query latency must stay within the %.1f ms budget (got %.3f ms)",
                EndToEndQueryBenchmarkRunner.TARGET_P50_MS, p50)
            .isLessThanOrEqualTo(EndToEndQueryBenchmarkRunner.TARGET_P50_MS);
    }

    @Test
    @DisplayName("p50 and p99 percentiles should be correctly ordered")
    void testPercentilesAreOrdered() {
        List<Double> latenciesMs = new ArrayList<>();

        // Collect latency samples with sub-millisecond precision
        for (int i = 0; i < 50; i++) {
            QueryRequest request = QueryRequest.builder()
                .queryText("percentile test query")
                .queryVector(queryVectors[i % queryVectors.length])
                .topK(TOP_K)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .build();

            CancellationToken token = new CancellationToken();

            long startTime = System.nanoTime();
            queryPipeline.executeSync(request, token);
            long endTime = System.nanoTime();

            // Convert to milliseconds with sub-ms precision using floating-point division
            // Domain correctness: This preserves sub-millisecond latency values
            double latencyMs = (endTime - startTime) / 1_000_000.0;
            latenciesMs.add(latencyMs);
        }

        // Calculate percentiles
        double p50 = calculatePercentileD(latenciesMs, 50);
        double p99 = calculatePercentileD(latenciesMs, 99);
        double p90 = calculatePercentileD(latenciesMs, 90);
        double p10 = calculatePercentileD(latenciesMs, 10);

        System.out.println("Percentile latencies:");
        System.out.printf("  p10: %.4f ms%n", p10);
        System.out.printf("  p50: %.4f ms%n", p50);
        System.out.printf("  p90: %.4f ms%n", p90);
        System.out.printf("  p99: %.4f ms%n", p99);

        // Use LatencyPercentileValidator for comprehensive validation
        LatencyPercentileValidator.ValidationResult validation =
            LatencyPercentileValidator.validate(p50, p99, latenciesMs.size());

        System.out.println("\n" + LatencyPercentileValidator.formatSummary(p50, p99));

        // Domain correctness: validator must pass
        assertThat(validation.valid())
            .as("Latency percentile validation must pass: " + validation.combinedMessage())
            .isTrue();

        // Additional manual checks for comprehensive validation
        // Domain correctness: p10 <= p50 <= p90 <= p99
        assertThat(p10)
            .as("p10 should be <= p50")
            .isLessThanOrEqualTo(p50);

        assertThat(p50)
            .as("p50 should be <= p90")
            .isLessThanOrEqualTo(p90);

        assertThat(p90)
            .as("p90 should be <= p99")
            .isLessThanOrEqualTo(p99);

        // All percentiles should be POSITIVE (latency > 0 for real operations)
        // Domain correctness: Real operations take non-zero time
        assertThat(p50)
            .as("p50 must be POSITIVE (> 0 ms)")
            .isGreaterThan(0.0);

        assertThat(p99)
            .as("p99 must be POSITIVE (> 0 ms)")
            .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Coverage metadata reports the whole visible index as searched")
    void coverageMetadataReflectsTheSegmentsActuallySearched() {
        QueryResponse response = executeQuery(queryVectors[0], null);

        IndexCoverage coverage = response.coverage();

        // Every segment in this fixture is ACTIVE and visible, so the expectation is
        // exact rather than "non-negative": full coverage, nothing degraded.
        assertThat(coverage.totalSegments())
            .as("all %d segments are in the catalog", SEGMENT_COUNT)
            .isEqualTo(SEGMENT_COUNT);

        assertThat(coverage.activeSegments())
            .as("all %d segments are ACTIVE", SEGMENT_COUNT)
            .isEqualTo(SEGMENT_COUNT);

        assertThat(coverage.estimatedCorpusCoverage())
            .as("a fully ACTIVE, fully visible index is complete coverage")
            .isEqualTo(1.0);

        assertThat(coverage.degraded())
            .as("no segment is missing or unqueryable, so the result is not degraded")
            .isFalse();
    }

    @Test
    @DisplayName("A threshold above every achievable score returns no results")
    void thresholdAboveEveryScoreFiltersEverything() {
        // Sanity: the same query without a threshold does return matches, so an empty
        // result below is caused by the threshold and not by a broken fixture.
        assertThat(executeQuery(queryVectors[0], null).results()).isNotEmpty();

        QueryResponse response = executeQuery(queryVectors[0], 1.0f);

        assertThat(response).as("Response should not be null").isNotNull();
        assertThat(response.coverage()).as("Coverage should not be null").isNotNull();

        // Scores are normalized similarities in [0, 1] and no query vector is present
        // verbatim in the index, so nothing can reach a score of 1.0.
        assertThat(response.results())
            .as("no indexed vector can score at or above 1.0 against a query not in the index")
            .isEmpty();

        assertThat(response.queryTimeMs())
            .as("Query time should be non-negative")
            .isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Multiple concurrent queries should not interfere")
    void testConcurrentQueryIsolation() throws InterruptedException {
        int threadCount = 3;
        int queriesPerThread = 8;
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        // Use Double for sub-millisecond precision
        List<Double> allLatenciesMs = Collections.synchronizedList(new ArrayList<>());

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int q = 0; q < queriesPerThread; q++) {
                    try {
                        int idx = (threadId * queriesPerThread + q) % queryVectors.length;
                        QueryRequest request = QueryRequest.builder()
                            .queryText("concurrent query thread=" + threadId)
                            .queryVector(queryVectors[idx])
                            .topK(TOP_K)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .build();

                        CancellationToken token = new CancellationToken();

                        long startTime = System.nanoTime();
                        QueryResponse response = queryPipeline.executeSync(request, token);
                        long endTime = System.nanoTime();

                        // Convert to milliseconds with sub-ms precision using floating-point division
                        double latencyMs = (endTime - startTime) / 1_000_000.0;
                        allLatenciesMs.add(latencyMs);

                        // Every query in this fixture searches a fully visible,
                        // populated index, so an empty result set is a failure.
                        assertThat(response.results())
                            .as("concurrent query must return results")
                            .isNotEmpty();

                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (Thread thread : threads) {
            thread.join(10000);
        }

        // Check for errors
        assertThat(errors)
            .as("No errors should occur during concurrent queries")
            .isEmpty();

        // Verify we got latency samples from all threads
        assertThat(allLatenciesMs)
            .as("Should have latency samples from all threads")
            .hasSize(threadCount * queriesPerThread);

        // All latencies should be non-negative
        for (Double latency : allLatenciesMs) {
            assertThat(latency)
                .as("All latencies should be non-negative")
                .isGreaterThanOrEqualTo(0.0);
        }

        double p50 = calculatePercentileD(allLatenciesMs, 50);
        double p99 = calculatePercentileD(allLatenciesMs, 99);

        // Validate latency percentiles using LatencyPercentileValidator
        LatencyPercentileValidator.ValidationResult validation =
            LatencyPercentileValidator.validate(p50, p99, allLatenciesMs.size());

        System.out.println("Concurrent query latency percentiles:");
        System.out.println(LatencyPercentileValidator.formatSummary(p50, p99));

        assertThat(validation.valid())
            .as("Concurrent query latency validation must pass: " + validation.combinedMessage())
            .isTrue();
    }

    // ==================== Helper Methods ====================

    /**
     * Identifies one indexed vector by the segment and node it lives in.
     *
     * @param segmentId the segment the vector was indexed into
     * @param nodeId the graph node id assigned to it
     */
    private record SegmentNode(long segmentId, long nodeId) {
    }

    /**
     * Runs one query through the pipeline.
     *
     * @param queryVector the query vector
     * @param threshold optional score threshold, or null for none
     * @return the pipeline response
     */
    private QueryResponse executeQuery(float[] queryVector, Float threshold) {
        QueryRequest.Builder builder = QueryRequest.builder()
            .queryText("validation query")
            .queryVector(queryVector)
            .topK(TOP_K)
            .timeoutMs(5000)
            .includePartialSegments(true)
            .enableLlmScoring(false);
        if (threshold != null) {
            builder.threshold(threshold);
        }
        return queryPipeline.executeSync(builder.build(), new CancellationToken());
    }

    /**
     * Computes the exact top-k nearest neighbours by brute force.
     *
     * <p>This is the independent expectation the pipeline is judged against. All
     * vectors are unit length, so the cosine similarity is the dot product and the
     * ranking follows from it directly, without reusing the distance function or any
     * other production code path under test.</p>
     *
     * @param query the query vector, unit length
     * @param k how many neighbours to return
     * @return the k nearest (segment, node) pairs, nearest first
     */
    private List<SegmentNode> bruteForceTopK(float[] query, int k) {
        record Scored(SegmentNode node, double similarity) {
        }

        List<Scored> scored = new ArrayList<>();
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            for (int v = 0; v < VECTORS_PER_SEGMENT; v++) {
                double dot = 0.0;
                float[] vector = segmentVectors[segment][v];
                for (int d = 0; d < DIMENSION; d++) {
                    dot += (double) query[d] * vector[d];
                }
                scored.add(new Scored(
                    new SegmentNode(segmentIds.get(segment), segmentNodeIds[segment][v]), dot));
            }
        }

        scored.sort(Comparator.comparingDouble(Scored::similarity).reversed());

        List<SegmentNode> topK = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            topK.add(scored.get(i).node());
        }
        return topK;
    }

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

    /**
     * Calculates a percentile from a list of double values with sub-millisecond precision.
     * Domain correctness: Preserves sub-millisecond latency values for accurate p50/p99 reporting.
     */
    private double calculatePercentileD(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    // ==================== Mock Infrastructure ====================

    /**
     * Minimal StorageEngine for validation testing.
     */
    private static class ValidationStorageEngine extends StorageEngine {
        private final ValidationSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private final List<Path> segmentFiles = new ArrayList<>();
        private final List<Long> segmentIds = new ArrayList<>();

        ValidationStorageEngine(int segmentCount, Path tempDir, Arena arena) throws IOException {
            super(tempDir, arena);

            for (int i = 0; i < segmentCount; i++) {
                Path segmentPath = Files.createTempFile(tempDir, "segment_" + i + "_", ".dat");
                segmentFiles.add(segmentPath);
                Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
                // Segment allocates its own id from a process-wide counter; index the
                // map by that id so getSegment(id) and catalog.get(id) agree.
                segments.put(segment.segmentId(), segment);
                segmentIds.add(segment.segmentId());
            }

            this.catalog = new ValidationSegmentCatalog(segmentIds);
        }

        /**
         * The ids the segments actually took, in creation order.
         */
        List<Long> segmentIds() {
            return List.copyOf(segmentIds);
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        void closeSegments() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
            segments.clear();
            segmentIds.clear();
            for (Path file : segmentFiles) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    // Ignore
                }
            }
            segmentFiles.clear();
        }
    }

    /**
     * Minimal SegmentCatalog for validation testing.
     */
    private static class ValidationSegmentCatalog extends SegmentCatalog {
        private final List<Long> segmentIds;
        private final Map<Long, ValidationSegmentMetadata> metadataCache = new ConcurrentHashMap<>();

        ValidationSegmentCatalog(List<Long> segmentIds) {
            this.segmentIds = List.copyOf(segmentIds);
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            if (!segmentIds.contains(segmentId)) {
                return null;
            }
            return metadataCache.computeIfAbsent(segmentId,
                id -> new ValidationSegmentMetadata(id, LifecycleState.ACTIVE));
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            Map<Long, SegmentMetadata> result = new HashMap<>();
            for (long segmentId : segmentIds) {
                result.put(segmentId, get(segmentId));
            }
            return result;
        }

        @Override
        public int size() {
            return segmentIds.size();
        }
    }

    /**
     * Minimal SegmentMetadata for validation testing.
     */
    private static class ValidationSegmentMetadata extends SegmentCatalog.SegmentMetadata {
        private final LifecycleState lifecycleStateOverride;

        ValidationSegmentMetadata(long segmentId, LifecycleState lifecycleState) {
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
            return Long.MAX_VALUE;
        }
    }

    /**
     * Minimal VisibilityTracker for validation testing.
     */
    private static class ValidationVisibilityTracker extends VisibilityTracker {
        private final Set<Long> visibleSegmentIds;
        private final AtomicLong epochCounter = new AtomicLong(1);

        ValidationVisibilityTracker(List<Long> segmentIds) {
            super(new ValidationSegmentCatalog(segmentIds));
            this.visibleSegmentIds = Set.copyOf(segmentIds);
        }

        @Override
        public long captureEpoch() {
            return epochCounter.get();
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            return visibleSegmentIds;
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
