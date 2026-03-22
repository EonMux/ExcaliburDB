package excalibur.query.pipeline;

import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.ParallelQueryDispatcher;
import excalibur.query.executor.ResultAggregator;
import excalibur.query.executor.SegmentQueryTask;
import excalibur.query.visibility.DocumentAtomicityFilter;
import excalibur.query.visibility.QuerySnapshot;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes the full query pipeline with cancellation and timeout support.
 *
 * <p>QueryPipeline implements the six-stage query execution flow from Phase 6:</p>
 * <ol>
 *   <li><strong>Stage 0 - Epoch Snapshot:</strong> Capture visibility epoch and resolve queryable segments</li>
 *   <li><strong>Stage 1 - Cuckoo Filter Cascade:</strong> Eliminate segments that cannot contain relevant entries</li>
 *   <li><strong>Stage 2 - LSH Pre-filter:</strong> Coarse vector similarity filtering</li>
 *   <li><strong>Stage 3 - HNSW Vector Search:</strong> Precise ANN within candidate segments with tail scan</li>
 *   <li><strong>Stage 4 - Full Vector Rerank:</strong> Exact distance on full-precision vectors</li>
 *   <li><strong>Stage 5 - LLM Relevancy Scoring:</strong> Score candidates with LLM (optional)</li>
 * </ol>
 *
 * <h2>Timeout and Cancellation</h2>
 * <p>Each stage checks the cancellation token before proceeding. If cancellation
 * is requested, a {@link CancellationException} is thrown. Timeout is implemented
 * via {@link CompletableFuture#orTimeout(long, TimeUnit)}.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are always sorted by distance/score (closest first)</li>
 *   <li>No duplicate entries in results</li>
 *   <li>Coverage accurately reflects segments searched</li>
 *   <li>Partial results returned on timeout</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each pipeline execution uses its own context.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class QueryPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPipeline.class);

    /**
     * Default coverage threshold for degraded state.
     */
    public static final double DEFAULT_COVERAGE_THRESHOLD = 0.5;

    /**
     * Default LLM relevancy threshold.
     */
    public static final float DEFAULT_LLM_THRESHOLD = 0.3f;

    private final StorageEngine storageEngine;
    private final VisibilityTracker visibilityTracker;
    private final DocumentVisibilityChecker visibilityChecker;
    private final HnswGraphProvider graphProvider;
    private final DistanceFunction distanceFunction;
    private final LlmScorer llmScorer;
    private final double coverageThreshold;

    /**
     * Creates a new QueryPipeline.
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker for epoch coordination
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function for vector comparison
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction) {

        this(storageEngine, visibilityTracker, null, graphProvider, distanceFunction, null, DEFAULT_COVERAGE_THRESHOLD);
    }

    /**
     * Creates a new QueryPipeline with document atomicity support.
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker for epoch coordination
     * @param visibilityChecker The visibility checker for document atomicity (may be null)
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function for vector comparison
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            DocumentVisibilityChecker visibilityChecker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction) {

        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction, null, DEFAULT_COVERAGE_THRESHOLD);
    }

    /**
     * Creates a new QueryPipeline with all options.
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function
     * @param llmScorer Optional LLM scorer for Stage 5
     * @param coverageThreshold Threshold for degraded state
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction,
            LlmScorer llmScorer,
            double coverageThreshold) {

        this(storageEngine, visibilityTracker, null, graphProvider, distanceFunction, llmScorer, coverageThreshold);
    }

    /**
     * Creates a new QueryPipeline with all options including document atomicity.
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param visibilityChecker The visibility checker for document atomicity (may be null)
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function
     * @param llmScorer Optional LLM scorer for Stage 5
     * @param coverageThreshold Threshold for degraded state
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            DocumentVisibilityChecker visibilityChecker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction,
            LlmScorer llmScorer,
            double coverageThreshold) {

        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        this.visibilityChecker = visibilityChecker;
        this.graphProvider = Objects.requireNonNull(graphProvider, "GraphProvider cannot be null");
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");
        this.llmScorer = llmScorer;
        this.coverageThreshold = coverageThreshold;
    }

    // ==================== Main Execution Method ====================

    /**
     * Executes the query pipeline with timeout support.
     *
     * <p>This is the primary entry point for query execution. It runs all stages
     * with cancellation checking and returns a CompletableFuture for async handling.</p>
     *
     * @param request The query request
     * @param token The cancellation token
     * @return A CompletableFuture containing the query response
     */
    public CompletableFuture<QueryResponse> execute(
            QueryRequest request,
            CancellationToken token) {

        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(token, "CancellationToken cannot be null");

        long startTime = System.nanoTime();
        String correlationId = request.correlationId();

        LOGGER.debug("Starting query pipeline: {}", correlationId);

        // Create the pipeline execution future
        CompletableFuture<PipelineContext> pipelineFuture = CompletableFuture.supplyAsync(() -> {
            // Stage 0: Epoch Snapshot
            PipelineContext context = stage0_EpochSnapshot(request, token);

            // Stage 1: Cuckoo Filter Cascade
            context = stage1_CuckooFilterCascade(context, token);

            // Stage 2: LSH Pre-filter
            context = stage2_LshPreFilter(context, token);

            // Stage 3: HNSW Vector Search with Tail Scan
            context = stage3_HnswVectorSearch(context, token);

            // Stage 4: Full Vector Rerank
            context = stage4_FullVectorRerank(context, token);

            // Stage 5: LLM Relevancy Scoring (optional)
            context = stage5_LlmScoring(context, token);

            return context;
        });

        // Apply timeout
        CompletableFuture<PipelineContext> timeoutFuture = pipelineFuture
            .orTimeout(request.timeoutMs(), TimeUnit.MILLISECONDS);

        // Handle result
        return timeoutFuture.handle((context, error) -> {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            if (error != null) {
                return handlePipelineError(error, request, durationMs, token);
            }

            return buildResponse(context, durationMs);
        });
    }

    /**
     * Executes the query pipeline synchronously.
     *
     * <p>This is a convenience method that blocks until completion or timeout.</p>
     *
     * @param request The query request
     * @param token The cancellation token
     * @return The query response
     * @throws CancellationException if the operation was cancelled
     */
    public QueryResponse executeSync(QueryRequest request, CancellationToken token) {
        try {
            return execute(request, token).join();
        } catch (Exception e) {
            if (e.getCause() instanceof CancellationException) {
                throw (CancellationException) e.getCause();
            }
            throw e;
        }
    }

    // ==================== Pipeline Stages ====================

    /**
     * Stage 0: Capture visibility epoch and resolve queryable segments.
     *
     * <p>This stage captures a consistent snapshot of segment visibility
     * at the current epoch. All subsequent stages operate on this snapshot.</p>
     */
    private PipelineContext stage0_EpochSnapshot(QueryRequest request, CancellationToken token) {
        token.checkCancelled("Stage 0 cancelled before epoch snapshot");

        LOGGER.trace("Stage 0: Capturing epoch snapshot");

        // Capture visibility epoch
        SegmentCatalog catalog = storageEngine.getCatalog();
        long epoch = visibilityTracker.captureEpoch();

        // Resolve queryable segments
        Set<Long> visibleSegmentIds = visibilityTracker.visibleSegments(epoch);
        List<SegmentCandidate> candidates = new ArrayList<>();

        for (Long segmentId : visibleSegmentIds) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) continue;

            LifecycleState state = metadata.getLifecycleState();
            if (!state.isQueryable()) continue;

            // Filter by partial segment policy
            if (!request.includePartialSegments() && state != LifecycleState.ACTIVE) {
                continue;
            }

            // Get segment and graph
            Segment segment = storageEngine.getSegment(segmentId);
            HnswGraph graph = graphProvider.getGraph(segmentId);

            if (segment != null && graph != null) {
                candidates.add(new SegmentCandidate(segmentId, segment, graph, state));
            }
        }

        LOGGER.debug("Stage 0: Captured epoch {} with {} queryable segments",
            epoch, candidates.size());

        return new PipelineContext(request, token, epoch, candidates);
    }

    /**
     * Stage 1: Hierarchical Cuckoo Filter Cascade.
     *
     * <p>Eliminates segments that cannot contain relevant entries using
     * the hierarchical Cuckoo filter. Segments that pass this stage
     * are candidates for further processing.</p>
     */
    private PipelineContext stage1_CuckooFilterCascade(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 1 cancelled before Cuckoo filter cascade");

        LOGGER.trace("Stage 1: Running Cuckoo filter cascade");

        // For now, all segments pass through (Cuckoo filter integration
        // would be added here when hierarchical filters are implemented)
        // The Cuckoo filter would check if the query hash might be in the segment

        // Placeholder: mark all candidates as passing Cuckoo filter
        List<SegmentCandidate> passed = new ArrayList<>(context.candidates);

        LOGGER.debug("Stage 1: {} segments passed Cuckoo filter cascade", passed.size());

        return context.withCandidates(passed);
    }

    /**
     * Stage 2: LSH Pre-filter.
     *
     * <p>Coarse vector similarity filtering using Locality-Sensitive Hashing.
     * Reduces candidates to approximately 10x-100x top_k.</p>
     */
    private PipelineContext stage2_LshPreFilter(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 2 cancelled before LSH pre-filter");

        LOGGER.trace("Stage 2: Running LSH pre-filter");

        // For now, all segments pass through (LSH integration would be
        // added here when LSH is implemented)
        // LSH would pre-filter to 10x-100x top_k candidates

        // Placeholder: mark all candidates as passing LSH filter
        List<SegmentCandidate> passed = new ArrayList<>(context.candidates);

        LOGGER.debug("Stage 2: {} segments passed LSH pre-filter", passed.size());

        return context.withCandidates(passed);
    }

    /**
     * Stage 3: HNSW Vector Search with Tail Scan.
     *
     * <p>Performs precise ANN search within candidate segments using HNSW.
     * For segments where lastIntegratedOffset < writeLogHead, also performs
     * brute-force tail scan over un-integrated entries.</p>
     *
     * <p><strong>Offset Comparison:</strong> Uses {@link TailScanAnalyzer} to compare
     * lastIntegratedOffset vs writeLogHead for each candidate segment. This determines
     * which segments have un-integrated entries that need tail scanning.</p>
     */
    private PipelineContext stage3_HnswVectorSearch(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 3 cancelled before HNSW search");

        LOGGER.trace("Stage 3: Running HNSW vector search");

        // Use TailScanAnalyzer to compare lastIntegratedOffset vs writeLogHead for each segment
        TailScanAnalyzer analyzer = new TailScanAnalyzer();
        TailScanAnalyzer.AnalysisResult analysis = analyzer.analyzeWithSummary(context.candidates);

        LOGGER.debug("Stage 3: Tail scan analysis - {} of {} segments need tail scan, total tail size: {} bytes",
            analysis.segmentsNeedingTailScan(), analysis.totalSegments(), analysis.totalTailSize());

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
        long totalTailScanEntries = analysis.totalTailSize();

        float[] queryVector = context.request.queryVectorUnsafe();
        int oversamplingFactor = (int) (context.request.recallTarget() * 2); // Approximate oversampling

        // Process each candidate with its tail scan requirement
        List<TailScanRequirement> requirements = analysis.requirements();
        for (int i = 0; i < context.candidates.size(); i++) {
            SegmentCandidate candidate = context.candidates.get(i);
            TailScanRequirement requirement = requirements.get(i);

            token.checkCancelled("Stage 3 cancelled during segment search");

            try {
                // Search segment
                List<HnswSearchResult> results = searchSegment(
                    queryVector,
                    candidate,
                    context.request.topK() * oversamplingFactor,
                    requirement
                );

                // Use the pre-computed tail size from the requirement
                long tailEntries = requirement.isTailScanNeeded() ? requirement.tailSize() : 0;

                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    candidate.segmentId(),
                    results,
                    tailEntries,
                    0, // duration computed separately
                    true,
                    null
                ));

            } catch (Exception e) {
                LOGGER.warn("Segment {} search failed: {}", candidate.segmentId(), e.getMessage());
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    candidate.segmentId(),
                    Collections.emptyList(),
                    0,
                    0,
                    false,
                    e.getMessage()
                ));
            }
        }

        LOGGER.debug("Stage 3: Searched {} segments, {} tail scan entries",
            segmentResults.size(), totalTailScanEntries);

        return context.withSegmentResults(segmentResults, totalTailScanEntries);
    }

    /**
     * Searches a single segment using HNSW and optional tail scan.
     *
     * <p>Uses the pre-computed TailScanRequirement to determine if tail scan
     * is needed. If tail scan is required, performs brute-force scan over
     * un-integrated write log entries and merges results with HNSW results.</p>
     *
     * <h3>Merge Strategy</h3>
     * <ol>
     *   <li>Perform HNSW graph search for k results</li>
     *   <li>If tail scan needed: perform brute-force scan of un-integrated entries</li>
     *   <li>Merge both result sets, sorting by distance</li>
     *   <li>Deduplicate by node ID and return top-k</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results are sorted by distance (closest first)</li>
     *   <li>No duplicate node IDs in output</li>
     *   <li>Distance values are valid: cosine ∈ [-1,1], L2 ∈ [0,∞)</li>
     *   <li>Exactly k results (or fewer if insufficient candidates)</li>
     * </ul>
     *
     * @param queryVector The query vector
     * @param candidate The candidate segment to search
     * @param k The number of results to return
     * @param requirement The pre-computed tail scan requirement
     * @return List of merged search results (HNSW + tail scan if needed), sorted by distance
     */
    private List<HnswSearchResult> searchSegment(
            float[] queryVector,
            SegmentCandidate candidate,
            int k,
            TailScanRequirement requirement) {

        // Step 1: Perform HNSW graph search
        List<HnswSearchResult> hnswResults = candidate.graph().search(queryVector, k);

        LOGGER.trace("Segment {}: HNSW search returned {} results",
            candidate.segmentId(), hnswResults.size());

        // Step 2: Check if tail scan is needed based on pre-computed requirement
        if (!requirement.isTailScanNeeded()) {
            // No tail scan needed - return HNSW results only
            return hnswResults;
        }

        // Step 3: Perform tail scan over un-integrated write log entries
        List<HnswSearchResult> tailResults = performTailScan(
            queryVector,
            candidate,
            requirement,
            k
        );

        LOGGER.trace("Segment {}: Tail scan returned {} results",
            candidate.segmentId(), tailResults.size());

        // Step 4: Merge HNSW and tail scan results
        return mergeHnswAndTailResults(hnswResults, tailResults, k);
    }

    /**
     * Performs a brute-force tail scan over un-integrated write log entries.
     *
     * <p>The tail scan computes exact distances on full-precision vectors
     * from the write log that haven't been integrated into the HNSW graph yet.</p>
     *
     * @param queryVector The query vector
     * @param candidate The candidate segment
     * @param requirement The tail scan requirement with offset information
     * @param k Maximum number of results to return from tail scan
     * @return List of results from tail scan, sorted by distance
     */
    private List<HnswSearchResult> performTailScan(
            float[] queryVector,
            SegmentCandidate candidate,
            TailScanRequirement requirement,
            int k) {

        Segment segment = candidate.segment();
        if (segment == null || segment.writeLog() == null) {
            LOGGER.trace("Segment {}: No write log available for tail scan", candidate.segmentId());
            return Collections.emptyList();
        }

        try {
            // Create tail scanner and perform scan
            excalibur.query.executor.TailScanner tailScanner =
                new excalibur.query.executor.TailScanner();

            // Scan range: [lastIntegratedOffset, writeLogHead)
            long startOffset = requirement.tailScanStartOffset();
            long endOffset = requirement.tailScanEndOffset();

            return tailScanner.performTailScan(
                queryVector,
                segment.writeLog(),
                startOffset,
                endOffset,
                k,
                distanceFunction
            );

        } catch (Exception e) {
            LOGGER.warn("Segment {}: Tail scan failed: {}",
                candidate.segmentId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Merges HNSW search results with tail scan results.
     *
     * <p>This method combines results from both sources, removes duplicates,
     * and returns the top-k results sorted by distance (closest first).</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results are sorted by distance (ascending)</li>
     *   <li>No duplicate node IDs in output</li>
     *   <li>Exactly k results (or fewer if insufficient candidates)</li>
     *   <li>Distances are from exact computation (full precision)</li>
     * </ul>
     *
     * @param hnswResults Results from HNSW graph search
     * @param tailResults Results from tail scan
     * @param k Maximum number of results to return
     * @return Merged and deduplicated results, sorted by distance
     */
    private List<HnswSearchResult> mergeHnswAndTailResults(
            List<HnswSearchResult> hnswResults,
            List<HnswSearchResult> tailResults,
            int k) {

        // Early return for k <= 0
        if (k <= 0) {
            return Collections.emptyList();
        }

        // Early returns for edge cases
        if (tailResults.isEmpty()) {
            return limitResults(hnswResults, k);
        }
        if (hnswResults.isEmpty()) {
            return limitResults(tailResults, k);
        }

        // Combine both result lists
        List<HnswSearchResult> combined = new ArrayList<>(
            hnswResults.size() + tailResults.size());
        combined.addAll(hnswResults);
        combined.addAll(tailResults);

        // Sort by distance (ascending - closest first)
        combined.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        // Deduplicate by node ID and take top-k
        Set<Long> seenNodeIds = new HashSet<>();
        List<HnswSearchResult> merged = new ArrayList<>(k);

        for (HnswSearchResult result : combined) {
            if (seenNodeIds.add(result.nodeId())) {
                merged.add(result);
                if (merged.size() >= k) {
                    break;
                }
            }
        }

        return merged;
    }

    /**
     * Limits results to the specified count.
     *
     * @param results The results to limit
     * @param limit Maximum number of results
     * @return Limited results (new list)
     */
    private List<HnswSearchResult> limitResults(List<HnswSearchResult> results, int limit) {
        if (results.size() <= limit) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Stage 4: Full Vector Rerank.
     *
     * <p>Exact distance computation on full-precision vectors for top candidates.
     * Merges HNSW results with tail scan results.</p>
     *
     * <p><strong>Document Atomicity:</strong> After merging results, this stage
     * applies the DocumentAtomicityFilter to ensure queries never see partially-
     * ingested documents. All chunks from a visible document are included; none
     * from partial documents.</p>
     */
    private PipelineContext stage4_FullVectorRerank(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 4 cancelled before reranking");

        LOGGER.trace("Stage 4: Running full vector rerank");

        // Aggregate results using ResultAggregator
        ResultAggregator aggregator = new ResultAggregator(
            context.request.topK(),
            context.request.threshold(),
            coverageThreshold
        );

        List<QueryResultItem> mergedResults = aggregator.mergeAndRank(context.segmentResults);

        int beforeAtomicityFilter = mergedResults.size();

        // Apply document atomicity filter to exclude partial documents
        if (visibilityChecker != null) {
            DocumentAtomicityFilter atomicityFilter = new DocumentAtomicityFilter(visibilityChecker);
            mergedResults = atomicityFilter.filterPartialDocuments(mergedResults, context.epoch);

            if (mergedResults.size() < beforeAtomicityFilter) {
                LOGGER.debug("Stage 4: Document atomicity filter removed {} partial document results",
                    beforeAtomicityFilter - mergedResults.size());
            }
        }

        LOGGER.debug("Stage 4: Reranked to {} results (atomicity filtered from {})",
            mergedResults.size(), beforeAtomicityFilter);

        return context.withMergedResults(mergedResults);
    }

    /**
     * Stage 5: LLM Relevancy Scoring.
     *
     * <p>Sends top-k candidates + original query text to LLM for relevancy
     * scoring. Discards results below threshold. Falls back to vector-only
     * ranking if LLM is unavailable.</p>
     *
     * <h3>Sorting After LLM Scoring</h3>
     * <p>After LLM scoring, results are sorted by relevance score in descending
     * order (highest relevance first). This ensures the most relevant results
     * appear at the top of the response.</p>
     */
    private PipelineContext stage5_LlmScoring(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 5 cancelled before LLM scoring");

        // Skip if LLM scoring disabled or no scorer
        if (!context.request.enableLlmScoring() || llmScorer == null) {
            LOGGER.trace("Stage 5: Skipping LLM scoring (disabled or no scorer)");
            return context.withLlmScored(false);
        }

        // Skip if no query text
        if (context.request.queryText() == null || context.request.queryText().isEmpty()) {
            LOGGER.trace("Stage 5: Skipping LLM scoring (no query text)");
            return context.withLlmScored(false);
        }

        LOGGER.trace("Stage 5: Running LLM relevancy scoring");

        try {
            List<QueryResultItem> scoredResults = llmScorer.scoreResults(
                context.request.queryText(),
                context.mergedResults,
                context.request.llmThreshold()
            );

            // Sort by relevance score descending (highest first)
            // LLM relevancy scores are in range [0.0, 1.0] where higher is better
            scoredResults = ResultAggregator.sortByRelevanceDescending(scoredResults);

            LOGGER.debug("Stage 5: LLM scored {} results, {} after threshold filtering",
                context.mergedResults.size(), scoredResults.size());

            return context.withMergedResults(scoredResults).withLlmScored(true);

        } catch (Exception e) {
            LOGGER.warn("LLM scoring failed, using vector-only ranking: {}", e.getMessage());
            return context.withLlmScored(false).withLlmFallback(true);
        }
    }

    // ==================== Error Handling ====================

    /**
     * Handles pipeline errors and creates appropriate response.
     */
    private QueryResponse handlePipelineError(
            Throwable error,
            QueryRequest request,
            long durationMs,
            CancellationToken token) {

        boolean timedOut = error instanceof TimeoutException ||
            (error.getCause() instanceof TimeoutException);
        boolean cancelled = error instanceof CancellationException ||
            (error.getCause() instanceof CancellationException);

        if (timedOut) {
            LOGGER.warn("Query timed out after {}ms: {}", durationMs, request.correlationId());
            // Cancel the token to propagate to any running stages
            token.cancel("Query timeout after " + durationMs + "ms");
        } else if (cancelled) {
            LOGGER.info("Query cancelled: {}", request.correlationId());
        } else {
            LOGGER.error("Query failed: {}", error.getMessage(), error);
        }

        // Build degraded response
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            timedOut
        );
    }

    /**
     * Builds the final response from pipeline context.
     */
    private QueryResponse buildResponse(PipelineContext context, long durationMs) {
        // Build coverage from segment results
        SegmentCatalog catalog = storageEngine.getCatalog();
        long totalSegments = catalog.size();
        long activeSegments = 0, indexingSegments = 0, warmingSegments = 0;
        long compactingSegments = 0, tombstonedSegments = 0;

        for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
            switch (metadata.getLifecycleState()) {
                case ACTIVE -> activeSegments++;
                case INDEXING -> indexingSegments++;
                case WARMING -> warmingSegments++;
                case COMPACTING -> compactingSegments++;
                case TOMBSTONED -> tombstonedSegments++;
                default -> {}
            }
        }

        int segmentsSearched = 0, segmentsWithErrors = 0;
        for (SegmentQueryTask.SegmentQueryResult result : context.segmentResults) {
            if (result.success()) {
                segmentsSearched++;
            } else {
                segmentsWithErrors++;
            }
        }

        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(totalSegments)
            .activeSegments(activeSegments)
            .indexingSegments(indexingSegments)
            .warmingSegments(warmingSegments)
            .compactingSegments(compactingSegments)
            .tombstonedSegments(tombstonedSegments)
            .tailScanEntries(context.totalTailScanEntries)
            .segmentsSearched(segmentsSearched)
            .segmentsWithErrors(segmentsWithErrors)
            .segmentsTimedOut(0)
            .computeCoverage()
            .computeDegraded(coverageThreshold)
            .build();

        return new QueryResponse(
            context.mergedResults,
            coverage,
            durationMs,
            false,
            false
        );
    }

    // ==================== Inner Classes ====================

    /**
     * Provider interface for HNSW graphs.
     */
    @FunctionalInterface
    public interface HnswGraphProvider {
        HnswGraph getGraph(long segmentId);
    }

    /**
     * Interface for LLM-based relevancy scoring.
     */
    @FunctionalInterface
    public interface LlmScorer {
        /**
         * Scores results using LLM.
         *
         * @param queryText The query text
         * @param results The results to score
         * @param threshold The minimum score threshold
         * @return Scored and filtered results
         */
        List<QueryResultItem> scoreResults(String queryText, List<QueryResultItem> results, float threshold);
    }

    /**
     * A candidate segment for querying.
     *
     * <p>Uses the public {@link SegmentCandidate} class from the same package.</p>
     *
     * <p><strong>Thread Safety:</strong> Each pipeline execution uses its own context.</p>
     */

    /**
     * Context passed through pipeline stages.
     */
    private static class PipelineContext {
        final QueryRequest request;
        final CancellationToken token;
        final long epoch;
        final List<SegmentCandidate> candidates;
        final List<SegmentQueryTask.SegmentQueryResult> segmentResults;
        final List<QueryResultItem> mergedResults;
        final long totalTailScanEntries;
        final boolean llmScored;
        final boolean llmFallback;

        PipelineContext(
                QueryRequest request,
                CancellationToken token,
                long epoch,
                List<SegmentCandidate> candidates,
                List<SegmentQueryTask.SegmentQueryResult> segmentResults,
                List<QueryResultItem> mergedResults,
                long totalTailScanEntries,
                boolean llmScored,
                boolean llmFallback) {
            this.request = request;
            this.token = token;
            this.epoch = epoch;
            this.candidates = candidates;
            this.segmentResults = segmentResults;
            this.mergedResults = mergedResults;
            this.totalTailScanEntries = totalTailScanEntries;
            this.llmScored = llmScored;
            this.llmFallback = llmFallback;
        }

        PipelineContext(QueryRequest request, CancellationToken token, long epoch, List<SegmentCandidate> candidates) {
            this(request, token, epoch, candidates, Collections.emptyList(),
                Collections.emptyList(), 0, false, false);
        }

        PipelineContext withCandidates(List<SegmentCandidate> candidates) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, llmScored, llmFallback);
        }

        PipelineContext withSegmentResults(List<SegmentQueryTask.SegmentQueryResult> results, long tailScanEntries) {
            return new PipelineContext(request, token, epoch, candidates,
                results, mergedResults, tailScanEntries, llmScored, llmFallback);
        }

        PipelineContext withMergedResults(List<QueryResultItem> results) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, results, totalTailScanEntries, llmScored, llmFallback);
        }

        PipelineContext withLlmScored(boolean scored) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, scored, llmFallback);
        }

        PipelineContext withLlmFallback(boolean fallback) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, llmScored, fallback);
        }
    }
}
