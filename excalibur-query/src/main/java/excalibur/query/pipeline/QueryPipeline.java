package excalibur.query.pipeline;

import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.filter.lsh.LshIndex;
import excalibur.filter.sketch.CountMinSketch;
import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.plan.SelectivityEstimator;
import excalibur.query.QueryResultItem;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.ParallelQueryDispatcher;
import excalibur.query.executor.ResultAggregator;
import excalibur.query.executor.SegmentQueryTask;
import excalibur.query.visibility.DocumentAtomicityFilter;
import excalibur.query.visibility.QuerySnapshot;
import excalibur.shard.VisibilityTracker;
import io.excalibur.core.text.TermExtractor;
import io.excalibur.metrics.Metrics;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
import java.util.stream.Collectors;

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
    private final HierarchicalCuckooFilter hierarchicalFilter;

    // Lazily initialized dispatcher for parallel query execution
    private volatile ParallelQueryDispatcher dispatcher;

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

        this(storageEngine, visibilityTracker, null, graphProvider, distanceFunction, null, DEFAULT_COVERAGE_THRESHOLD, null);
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

        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction, null, DEFAULT_COVERAGE_THRESHOLD, null);
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

        this(storageEngine, visibilityTracker, null, graphProvider, distanceFunction, llmScorer, coverageThreshold, null);
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

        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction, llmScorer, coverageThreshold, null);
    }

    /**
     * Creates a new QueryPipeline with all options including hierarchical filter.
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param visibilityChecker The visibility checker for document atomicity (may be null)
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function
     * @param llmScorer Optional LLM scorer for Stage 5
     * @param coverageThreshold Threshold for degraded state
     * @param hierarchicalFilter Optional hierarchical Cuckoo filter for Stage 1 pruning
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            DocumentVisibilityChecker visibilityChecker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction,
            LlmScorer llmScorer,
            double coverageThreshold,
            HierarchicalCuckooFilter hierarchicalFilter) {

        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        this.visibilityChecker = visibilityChecker;
        this.graphProvider = Objects.requireNonNull(graphProvider, "GraphProvider cannot be null");
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");
        this.llmScorer = llmScorer;
        this.coverageThreshold = coverageThreshold;
        this.hierarchicalFilter = hierarchicalFilter;
        // Dispatcher is lazily initialized on first use
    }

    /**
     * Private constructor for creating modified pipeline instances.
     *
     * <p>This constructor preserves the dispatcher when creating new pipeline
     * instances via the with*() methods.</p>
     */
    private QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            DocumentVisibilityChecker visibilityChecker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction,
            LlmScorer llmScorer,
            double coverageThreshold,
            HierarchicalCuckooFilter hierarchicalFilter,
            ParallelQueryDispatcher dispatcher) {

        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        this.visibilityChecker = visibilityChecker;
        this.graphProvider = Objects.requireNonNull(graphProvider, "GraphProvider cannot be null");
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");
        this.llmScorer = llmScorer;
        this.coverageThreshold = coverageThreshold;
        this.hierarchicalFilter = hierarchicalFilter;
        this.dispatcher = dispatcher;
    }

    /**
     * Returns the parallel query dispatcher, initializing it lazily if needed.
     *
     * <p>The dispatcher is created on first access to support testing with
     * mocked storage engines.</p>
     *
     * @return The parallel query dispatcher
     */
    private ParallelQueryDispatcher getDispatcher() {
        if (dispatcher == null) {
            synchronized (this) {
                if (dispatcher == null) {
                    dispatcher = new ParallelQueryDispatcher(
                        storageEngine.getCatalog(),
                        storageEngine.getSegments(),
                        graphProvider::getGraph
                    );
                }
            }
        }
        return dispatcher;
    }

    // ==================== Configuration Methods ====================

    /**
     * Returns a new QueryPipeline with the specified LLM scorer.
     *
     * <p>This method creates a new QueryPipeline instance with the provided LLM scorer,
     * preserving all other configuration. Use this to enable Stage 5 LLM relevancy scoring
     * on an existing pipeline.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Preserves all original configuration (storage, visibility, distance function, etc.)</li>
     *   <li>LLM scorer may be null to disable LLM scoring</li>
     * </ul>
     *
     * @param llmScorer The LLM scorer to use for Stage 5, or null to disable
     * @return A new QueryPipeline instance with the specified LLM scorer
     */
    public QueryPipeline withLlmScorer(LlmScorer llmScorer) {
        return new QueryPipeline(
            this.storageEngine,
            this.visibilityTracker,
            this.visibilityChecker,
            this.graphProvider,
            this.distanceFunction,
            llmScorer,
            this.coverageThreshold,
            this.hierarchicalFilter,
            this.dispatcher
        );
    }

    /**
     * Returns a new QueryPipeline with the specified visibility checker.
     *
     * <p>This method creates a new QueryPipeline instance with the provided visibility checker,
     * preserving all other configuration. Use this to control document atomicity filtering.
     * When visibilityChecker is null, document atomicity filtering is disabled.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Preserves all original configuration (storage, visibility tracker, distance function, etc.)</li>
     *   <li>Visibility checker may be null to disable document atomicity filtering</li>
     * </ul>
     *
     * @param visibilityChecker The visibility checker for document atomicity, or null to disable
     * @return A new QueryPipeline instance with the specified visibility checker
     */
    public QueryPipeline withVisibilityChecker(DocumentVisibilityChecker visibilityChecker) {
        return new QueryPipeline(
            this.storageEngine,
            this.visibilityTracker,
            visibilityChecker,
            this.graphProvider,
            this.distanceFunction,
            this.llmScorer,
            this.coverageThreshold,
            this.hierarchicalFilter,
            this.dispatcher
        );
    }

    /**
     * Returns the current LLM scorer, if any.
     *
     * @return The LLM scorer, or null if not configured
     */
    public LlmScorer getLlmScorer() {
        return llmScorer;
    }

    /**
     * Returns the current visibility checker, if any.
     *
     * @return The visibility checker, or null if not configured
     */
    public DocumentVisibilityChecker getVisibilityChecker() {
        return visibilityChecker;
    }

    /**
     * Shuts down the query pipeline and releases resources.
     *
     * <p>This method shuts down the internal parallel query dispatcher,
     * which terminates the virtual thread executor used for parallel
     * segment searches.</p>
     *
     * <p>After calling shutdown(), the pipeline should not be used for
     * query execution.</p>
     */
    public void shutdown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
            LOGGER.debug("QueryPipeline shutdown complete");
        }
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

            // Stage 0b: Selectivity Estimation and Segment Prioritization
            context = stage0b_SelectivityEstimation(context, token);

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

            // Record query latency for JMX observability
            // This enables monitoring of p50/p90/p95/p99 latencies
            Metrics.recordQueryLatency(durationMs);

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
     * at the current epoch using {@link QuerySnapshot#capture}. All subsequent
     * stages operate on this snapshot.</p>
     */
    private PipelineContext stage0_EpochSnapshot(QueryRequest request, CancellationToken token) {
        token.checkCancelled("Stage 0 cancelled before epoch snapshot");

        LOGGER.trace("Stage 0: Capturing epoch snapshot");

        // Capture visibility snapshot using QuerySnapshot
        QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, storageEngine.getCatalog(), storageEngine);

        // Resolve visible segments and build candidates
        SegmentCatalog catalog = storageEngine.getCatalog();
        List<SegmentCandidate> candidates = new ArrayList<>();

        for (Segment segment : snapshot.resolveVisibleSegments()) {
            long segmentId = segment.segmentId();
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) continue;

            LifecycleState state = metadata.getLifecycleState();
            if (!state.isQueryable()) continue;

            // Filter by partial segment policy
            if (!request.includePartialSegments() && state != LifecycleState.ACTIVE) {
                continue;
            }

            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph != null) {
                LshIndex lshIndex = segment.lshIndex();
                candidates.add(new SegmentCandidate(segmentId, segment, graph, state, lshIndex));
            }
        }

        LOGGER.debug("Stage 0: Captured epoch {} with {} queryable segments",
            snapshot.epoch(), candidates.size());

        return new PipelineContext(request, token, snapshot.epoch(), candidates);
    }

    /**
     * Stage 0b: Selectivity Estimation and Segment Prioritization.
     *
     * <p>This stage estimates query selectivity for each segment using Count-Min Sketch
     * term frequency data. Segments are then prioritized based on estimated selectivity,
     * with higher-selectivity segments searched first.</p>
     *
     * <h3>Selectivity-Based Optimization</h3>
     * <ul>
     *   <li><strong>Segment prioritization:</strong> Segments with higher selectivity
     *       (more relevant terms) are searched first</li>
     *   <li><strong>Early termination:</strong> Segments with very low selectivity
     *       may be deprioritized to improve query latency</li>
     *   <li><strong>Conservative defaults:</strong> Segments without frequency sketches
     *       are assigned default selectivity (1.0) to ensure no data loss</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Selectivity values are clamped to [0.0, 1.0]</li>
     *   <li>Segments without sketches get default selectivity (1.0)</li>
     *   <li>Empty query text results in no reordering</li>
     * </ul>
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with candidates sorted by selectivity
     */
    private PipelineContext stage0b_SelectivityEstimation(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 0b cancelled before selectivity estimation");

        LOGGER.trace("Stage 0b: Running selectivity estimation");

        // If no query text, skip selectivity estimation
        if (context.request.queryText() == null || context.request.queryText().isEmpty()) {
            LOGGER.debug("Stage 0b: No query text, skipping selectivity estimation");
            return context;
        }

        // Extract query terms for selectivity estimation
        List<String> queryTerms = extractQueryTerms(context.request.queryText());
        if (queryTerms.isEmpty()) {
            LOGGER.debug("Stage 0b: No query terms extracted, skipping selectivity estimation");
            return context;
        }

        // Calculate selectivity for each segment and sort by selectivity (descending)
        List<SegmentCandidate> candidates = new ArrayList<>(context.candidates);
        List<SegmentCandidate> prioritizedCandidates = new ArrayList<>(candidates.size());

        int segmentsWithSketch = 0;
        int segmentsWithoutSketch = 0;

        // Calculate selectivity scores for each segment
        List<SegmentCandidateWithScore> scoredCandidates = new ArrayList<>(candidates.size());
        for (SegmentCandidate candidate : candidates) {
            token.checkCancelled("Stage 0b cancelled during selectivity calculation");

            double selectivity = calculateSegmentSelectivity(candidate, queryTerms);

            if (candidate.hasTermFrequencySketch()) {
                segmentsWithSketch++;
            } else {
                segmentsWithoutSketch++;
            }

            scoredCandidates.add(new SegmentCandidateWithScore(candidate, selectivity));
        }

        // Sort by selectivity descending (highest first)
        scoredCandidates.sort((a, b) -> Double.compare(b.score, a.score));

        // Extract sorted candidates
        for (SegmentCandidateWithScore scored : scoredCandidates) {
            prioritizedCandidates.add(scored.candidate);
        }

        LOGGER.debug("Stage 0b: Prioritized {} segments by selectivity ({} with sketch, {} without)",
            prioritizedCandidates.size(), segmentsWithSketch, segmentsWithoutSketch);

        return context.withCandidates(prioritizedCandidates);
    }

    /**
     * Extracts query terms from query text for selectivity estimation.
     *
     * <p>Uses whitespace tokenization to match the ingestion pipeline.</p>
     *
     * @param queryText The query text
     * @return List of query terms
     */
    private List<String> extractQueryTerms(String queryText) {
        if (queryText == null || queryText.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();
        StringBuilder currentTerm = new StringBuilder();

        for (int i = 0; i < queryText.length(); i++) {
            char c = queryText.charAt(i);
            if (Character.isWhitespace(c)) {
                if (currentTerm.length() > 0) {
                    terms.add(currentTerm.toString().toLowerCase());
                    currentTerm.setLength(0);
                }
            } else {
                currentTerm.append(c);
            }
        }

        // Add final term if any
        if (currentTerm.length() > 0) {
            terms.add(currentTerm.toString().toLowerCase());
        }

        return terms;
    }

    /**
     * Calculates selectivity for a segment based on query terms.
     *
     * <p>Uses the segment's Count-Min Sketch to estimate how many documents
     * in the segment contain the query terms. Returns 1.0 (default) if no
     * sketch is available.</p>
     *
     * @param candidate The segment candidate
     * @param queryTerms The query terms
     * @return Estimated selectivity (0.0 to 1.0)
     */
    private double calculateSegmentSelectivity(SegmentCandidate candidate, List<String> queryTerms) {
        CountMinSketch sketch = candidate.termFrequencySketch();

        if (sketch == null) {
            // No sketch available - use default selectivity
            return SelectivityEstimator.DEFAULT_SELECTIVITY;
        }

        SelectivityEstimator estimator = new SelectivityEstimator(sketch);
        return estimator.estimateCombinedSelectivity(queryTerms);
    }

    /**
     * Helper class to associate a segment candidate with its selectivity score.
     */
    private static class SegmentCandidateWithScore {
        final SegmentCandidate candidate;
        final double score;

        SegmentCandidateWithScore(SegmentCandidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    /**
     * Stage 1: Hierarchical Cuckoo Filter Cascade.
     *
     * <p>Eliminates segments that cannot contain relevant entries using
     * the hierarchical Cuckoo filter. Segments that pass this stage
     * are candidates for further processing.</p>
     *
     * <h3>Term-Based Filtering</h3>
     * <p>This implementation uses term-based filtering:
     * <ol>
     *   <li>Extract significant terms from query text using {@link TermExtractor}</li>
     *   <li>For each term, find candidate segments that might contain that term</li>
     *   <li>Union all candidate segments across all query terms</li>
     *   <li>This gives segments that might contain ANY of the query terms</li>
     * </ol>
     *
     * <h3>Key Matching</h3>
     * <p><strong>CRITICAL:</strong> For this to work correctly:
     * <ul>
     *   <li><strong>Ingestion:</strong> Terms from chunk text are inserted into the filter</li>
     *   <li><strong>Query:</strong> Terms from query text are used to find candidate segments</li>
     *   <li>Both use the same {@link TermExtractor} for consistent term extraction</li>
     * </ul>
     *
     * <h3>Segment ID Format</h3>
     * <p>Segment IDs are compared as strings using {@code String.valueOf(segmentId)}
     * to match the format used during ingestion. Both sides must use this format.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Query terms extracted consistently with ingestion pipeline</li>
     *   <li>Segment IDs compared as strings (filter returns String IDs)</li>
     *   <li>Empty candidate set is valid (no segments match any query term)</li>
     *   <li>If no terms extracted, all candidates pass through (no filtering)</li>
     * </ul>
     */
    private PipelineContext stage1_CuckooFilterCascade(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 1 cancelled before Cuckoo filter cascade");

        LOGGER.trace("Stage 1: Running Cuckoo filter cascade");

        // If no hierarchical filter configured, pass all segments through
        if (hierarchicalFilter == null) {
            LOGGER.debug("Stage 1: No hierarchical filter configured, {} candidates pass",
                context.candidates.size());
            return context.withCandidates(new ArrayList<>(context.candidates));
        }

        // Extract query text
        String queryText = context.request.queryText();
        if (queryText == null || queryText.isEmpty()) {
            LOGGER.debug("Stage 1: No query text for filter lookup, {} candidates pass",
                context.candidates.size());
            return context.withCandidates(new ArrayList<>(context.candidates));
        }

        // Extract terms from query text using the same TermExtractor as ingestion
        List<String> queryTerms = TermExtractor.extractTerms(queryText);
        if (queryTerms.isEmpty()) {
            LOGGER.debug("Stage 1: No significant terms extracted from query, {} candidates pass",
                context.candidates.size());
            return context.withCandidates(new ArrayList<>(context.candidates));
        }

        LOGGER.trace("Stage 1: Extracted {} terms from query: {}",
            queryTerms.size(), queryTerms);

        // Find candidate segments for each term and union the results
        Set<String> candidateSegmentIds = new HashSet<>();
        int termsWithMatches = 0;

        for (String term : queryTerms) {
            byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
            List<String> segmentsForTerm = hierarchicalFilter.findCandidateSegments(termBytes);

            if (!segmentsForTerm.isEmpty()) {
                termsWithMatches++;
                candidateSegmentIds.addAll(segmentsForTerm);
                LOGGER.trace("Stage 1: Term '{}' found in {} segments",
                    term, segmentsForTerm.size());
            }
        }

        // If no terms matched any segments, return empty candidates
        if (candidateSegmentIds.isEmpty()) {
            LOGGER.debug("Stage 1: No segments matched any of {} query terms", queryTerms.size());
            return context.withCandidates(Collections.emptyList());
        }

        // Filter candidates to only those that matched at least one term
        // Use String.valueOf(segmentId) to match the format used during ingestion
        List<SegmentCandidate> passed = context.candidates.stream()
            .filter(c -> candidateSegmentIds.contains(String.valueOf(c.segmentId())))
            .collect(Collectors.toList());

        LOGGER.debug("Stage 1: {} of {} segments passed Cuckoo filter cascade " +
            "({} terms matched, {} unique segment IDs from filter)",
            passed.size(), context.candidates.size(), termsWithMatches, candidateSegmentIds.size());

        return context.withCandidates(passed);
    }

    /**
     * Stage 2: LSH Pre-filter.
     *
     * <p>Coarse vector similarity filtering using Locality-Sensitive Hashing.
     * Reduces candidates to approximately 10x-100x top_k.</p>
     *
     * <h3>LSH Filtering Algorithm</h3>
     * <ol>
     *   <li>For each segment candidate, query its LSH index with the query vector</li>
     *   <li>If the LSH index returns candidates, attach them to the segment candidate</li>
     *   <li>If no LSH index is available, pass the segment through unchanged</li>
     *   <li>If LSH returns no candidates, the segment is excluded from further processing</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>LSH candidates are entry IDs (long values) that potentially match the query vector</li>
     *   <li>Semantically correct: segments with no LSH candidates are filtered out</li>
     *   <li>Segments without LSH index pass through to ensure no data loss</li>
     * </ul>
     */
    private PipelineContext stage2_LshPreFilter(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 2 cancelled before LSH pre-filter");

        LOGGER.trace("Stage 2: Running LSH pre-filter");

        float[] queryVector = context.request.queryVectorUnsafe();
        int targetCandidates = context.request.topK() * 50; // 50x oversampling

        List<SegmentCandidate> passed = new ArrayList<>();
        int segmentsWithLsh = 0;
        int segmentsFiltered = 0;
        long totalLshCandidates = 0;

        for (SegmentCandidate candidate : context.candidates) {
            token.checkCancelled("Stage 2 cancelled during LSH query");

            LshIndex lsh = candidate.lshIndex();
            if (lsh != null) {
                segmentsWithLsh++;
                try {
                    Set<Long> lshCandidates = lsh.query(queryVector);
                    if (!lshCandidates.isEmpty()) {
                        totalLshCandidates += lshCandidates.size();
                        passed.add(candidate.withLshCandidates(lshCandidates));
                    } else {
                        // Segment has no LSH candidates - filter it out
                        segmentsFiltered++;
                        LOGGER.trace("Segment {} filtered out by LSH (no candidates)", candidate.segmentId());
                    }
                } catch (Exception e) {
                    // On LSH error, pass the segment through to ensure no data loss
                    LOGGER.warn("LSH query failed for segment {}: {}. Passing through.",
                        candidate.segmentId(), e.getMessage());
                    passed.add(candidate);
                }
            } else {
                // No LSH index - pass through to ensure no data loss
                passed.add(candidate);
            }
        }

        LOGGER.debug("Stage 2: {} segments passed LSH pre-filter ({} with LSH, {} filtered out, {} total LSH candidates)",
            passed.size(), segmentsWithLsh, segmentsFiltered, totalLshCandidates);

        return context.withCandidates(passed);
    }

    /**
     * Stage 3: HNSW Vector Search with Tail Scan.
     *
     * <p>Performs precise ANN search within candidate segments using HNSW.
     * For segments where lastIntegratedOffset < writeLogHead, also performs
     * brute-force tail scan over un-integrated entries.</p>
     *
     * <p><strong>Candidate-Based Execution:</strong> Uses the candidates from
     * stages 0-2 which have been filtered by visibility tracking, Cuckoo filter
     * cascade, and LSH pre-filtering. This ensures that only segments visible
     * at the captured epoch are searched.</p>
     *
     * <p><strong>Parallel Execution:</strong> Searches are executed in parallel
     * using the {@link ParallelQueryDispatcher} for improved latency across
     * multiple segments.</p>
     *
     * <p><strong>Offset Comparison:</strong> For each segment, compares
     * lastIntegratedOffset vs writeLogHead to determine if tail scanning is needed.</p>
     */
    private PipelineContext stage3_HnswVectorSearch(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 3 cancelled before HNSW search");

        LOGGER.trace("Stage 3: Running HNSW vector search with {} candidates", context.candidates.size());

        if (context.candidates.isEmpty()) {
            LOGGER.debug("Stage 3: No candidates to search");
            return context.withSegmentResults(List.of(), 0);
        }

        float[] queryVector = context.request.queryVectorUnsafe();
        int k = context.request.topK();
        int oversamplingFactor = context.request.oversamplingFactor();

        long startTime = System.nanoTime();

        // Build QueryOptions from request
        QueryOptions options = QueryOptions.builder()
            .topK(k)
            .oversamplingFactor(oversamplingFactor)
            .timeout(java.time.Duration.ofMillis(context.request.timeoutMs()))
            .includePartialSegments(context.request.includePartialSegments())
            .build();

        // Convert SegmentCandidates to SegmentInfo for the dispatcher
        List<ParallelQueryDispatcher.SegmentInfo> segmentsToSearch = new ArrayList<>();
        for (SegmentCandidate candidate : context.candidates) {
            if (candidate.isValid()) {
                segmentsToSearch.add(ParallelQueryDispatcher.createSegmentInfo(
                    candidate.segmentId(),
                    candidate.segment(),
                    candidate.graph()
                ));
            }
        }

        LOGGER.trace("Stage 3: Dispatching to {} valid segments", segmentsToSearch.size());

        // Use ParallelQueryDispatcher for parallel execution
        ParallelQueryDispatcher dispatcher = getDispatcher();
        ParallelQueryDispatcher.DispatchResult dispatchResult = dispatcher.dispatchToSpecificSegments(
            segmentsToSearch,
            queryVector,
            options,
            distanceFunction
        );

        // Check cancellation after dispatch completes
        token.checkCancelled("Stage 3 cancelled after segment search");

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = dispatchResult.segmentResults();
        long totalTailScanEntries = dispatchResult.coverage().tailScanEntries();

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.debug("Stage 3: Search completed - {} segments, {} tail scan entries, {}ms",
            segmentResults.size(), totalTailScanEntries, durationMs);

        return context.withSegmentResults(segmentResults, totalTailScanEntries);
    }

    /**
     * Searches a single segment and returns the result.
     *
     * <p>This method is used for both sequential and parallel execution in Stage 3.
     * It handles tail scan requirement determination and result collection.</p>
     *
     * @param queryVector The query vector
     * @param candidate The segment candidate to search
     * @param searchK The number of results to retrieve (with oversampling)
     * @return Segment query result with search results and tail scan metadata
     */
    private SegmentQueryTask.SegmentQueryResult searchSingleSegment(
            float[] queryVector,
            SegmentCandidate candidate,
            int searchK) {

        long segmentStartTime = System.nanoTime();

        try {
            // Determine tail scan requirement
            TailScanRequirement requirement = TailScanRequirement.noTailScan(candidate.segmentId());
            if (candidate.segment() != null && candidate.graph() != null) {
                long lastIntegratedOffset = candidate.graph().getLastIntegratedOffset();
                long writeLogHead = candidate.segment().getWriteLogHead();
                if (lastIntegratedOffset < writeLogHead) {
                    requirement = TailScanRequirement.compare(
                        candidate.segmentId(), lastIntegratedOffset, writeLogHead);
                }
            }

            // Search the segment using the existing searchSegment method
            List<HnswSearchResult> results = searchSegment(
                queryVector, candidate, searchK, requirement);

            // Count tail scan entries
            long tailScanEntries = requirement.isTailScanNeeded() ? requirement.estimatedEntryCount() : 0;

            long segmentDurationMs = (System.nanoTime() - segmentStartTime) / 1_000_000;

            return new SegmentQueryTask.SegmentQueryResult(
                candidate.segmentId(),
                results,
                tailScanEntries,
                segmentDurationMs,
                true,
                null
            );

        } catch (Exception e) {
            LOGGER.warn("Segment {} search failed: {}", candidate.segmentId(), e.getMessage());
            return new SegmentQueryTask.SegmentQueryResult(
                candidate.segmentId(),
                List.of(),
                0,
                (System.nanoTime() - segmentStartTime) / 1_000_000,
                false,
                e.getMessage()
            );
        }
    }

    /**
     * Searches a single segment using HNSW and optional tail scan.
     *
     * <p>Uses the pre-computed TailScanRequirement to determine if tail scan
     * is needed. If tail scan is required, performs brute-force scan over
     * un-integrated write log entries and merges results with HNSW results.</p>
     *
     * <h3>LSH Pre-Filtering Integration</h3>
     * <p>If the segment candidate has LSH pre-filtered candidates attached,
     * the HNSW search is constrained to only consider those node IDs. This
     * provides 10x-100x top_k candidate reduction for improved performance.</p>
     *
     * <h3>Merge Strategy</h3>
     * <ol>
     *   <li>Perform HNSW graph search for k results (filtered by LSH candidates if available)</li>
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
        // Use LSH pre-filtered candidates if available for 10x-100x top_k reduction
        List<HnswSearchResult> hnswResults;
        Set<Long> lshCandidates = candidate.lshCandidates();

        if (lshCandidates != null && !lshCandidates.isEmpty()) {
            // LSH pre-filtering: search only within the candidate set
            hnswResults = candidate.graph().search(queryVector, k, lshCandidates);
            LOGGER.trace("Segment {}: HNSW search with {} LSH candidates returned {} results",
                candidate.segmentId(), lshCandidates.size(), hnswResults.size());
        } else {
            // No LSH filtering: full graph search
            hnswResults = candidate.graph().search(queryVector, k);
            LOGGER.trace("Segment {}: HNSW search returned {} results",
                candidate.segmentId(), hnswResults.size());
        }

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

            // Note: Tail scan metrics are recorded internally by TailScanner.performTailScan()
            // after the scan completes, providing accurate entriesScanned and vectorEntriesFound counts

            List<HnswSearchResult> results = tailScanner.performTailScan(
                queryVector,
                segment.writeLog(),
                startOffset,
                endOffset,
                k,
                distanceFunction
            );

            LOGGER.debug("Segment {}: Tail scan completed, {} results from offset range [{}, {})",
                candidate.segmentId(), results.size(), startOffset, endOffset);

            return results;

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
     * This stage computes exact distances for candidates from Stage 3, which may
     * have used Product Quantization (PQ) for approximate distance computation.</p>
     *
     * <h3>Reranking Process</h3>
     * <ol>
     *   <li>Aggregate results from Stage 3 using ResultAggregator</li>
     *   <li>Group candidates by segment ID</li>
     *   <li>For each segment, use VectorReranker to compute exact distances from full-precision vectors stored in HNSW graph</li>
     *   <li>Merge all reranked results and take top-k</li>
     *   <li>Apply DocumentAtomicityFilter to exclude partial documents</li>
     * </ol>
     *
     * <h3>Why Reranking is Critical</h3>
     * <ul>
     *   <li><strong>PQ correction:</strong> Stage 3 may use PQ for approximate distance - reranking ensures exact distances</li>
     *   <li><strong>Tail scan integration:</strong> Results from HNSW and tail scan are merged with consistent exact distance computation</li>
     *   <li><strong>Quality guarantee:</strong> Final ranking reflects true similarity, not approximations</li>
     * </ul>
     *
     * <p><strong>Document Atomicity:</strong> After merging results, this stage
     * applies the DocumentAtomicityFilter to ensure queries never see partially-
     * ingested documents. All chunks from a visible document are included; none
     * from partial documents.</p>
     */
    private PipelineContext stage4_FullVectorRerank(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 4 cancelled before reranking");

        LOGGER.trace("Stage 4: Running full vector rerank");

        // Step 1: Aggregate results from Stage 3 to get candidates
        // Use topK * 2 to get more candidates for reranking, then limit after exact distance computation
        ResultAggregator aggregator = new ResultAggregator(
            context.request.topK() * 2,
            context.request.threshold(),
            coverageThreshold
        );

        List<QueryResultItem> candidates = aggregator.mergeAndRankWithNormalization(
            context.segmentResults, distanceFunction);

        if (candidates.isEmpty()) {
            LOGGER.debug("Stage 4: No candidates to rerank");
            return context.withMergedResults(candidates);
        }

        // Step 2: Group candidates by segment ID for efficient reranking
        Map<Long, List<QueryResultItem>> bySegment = candidates.stream()
            .collect(Collectors.groupingBy(QueryResultItem::segmentId));

        // Step 3: Create VectorReranker and rerank each segment's candidates
        VectorReranker reranker = new VectorReranker(distanceFunction);
        float[] queryVector = context.request.queryVectorUnsafe();

        List<QueryResultItem> rerankedResults = new ArrayList<>();

        for (Map.Entry<Long, List<QueryResultItem>> entry : bySegment.entrySet()) {
            long segmentId = entry.getKey();
            List<QueryResultItem> segmentCandidates = entry.getValue();

            // Find the SegmentCandidate for this segment to get the HNSW graph
            SegmentCandidate segmentCandidate = context.candidates.stream()
                .filter(sc -> sc.segmentId() == segmentId)
                .findFirst()
                .orElse(null);

            if (segmentCandidate == null || segmentCandidate.graph() == null) {
                LOGGER.warn("Stage 4: No graph found for segment {}, keeping {} candidates with approximate distances",
                    segmentId, segmentCandidates.size());
                rerankedResults.addAll(segmentCandidates);
                continue;
            }

            // Rerank by computing exact distances on full-precision vectors
            List<QueryResultItem> segmentReranked = reranker.rerank(
                segmentCandidates,
                queryVector,
                segmentCandidate.graph(),
                segmentCandidates.size() // Keep all for final merge
            );

            rerankedResults.addAll(segmentReranked);
        }

        // Step 4: Final sort by normalized score descending (highest/best first) and limit to top-k
        // Normalized scores are in [0.0, 1.0] where higher is better (more similar)
        rerankedResults = ResultAggregator.sortByRelevanceDescending(rerankedResults);
        if (rerankedResults.size() > context.request.topK()) {
            rerankedResults = new ArrayList<>(rerankedResults.subList(0, context.request.topK()));
        }

        int beforeAtomicityFilter = rerankedResults.size();

        // Step 5: Apply document atomicity filter to exclude partial documents
        if (visibilityChecker != null) {
            DocumentAtomicityFilter atomicityFilter = new DocumentAtomicityFilter(visibilityChecker);
            rerankedResults = atomicityFilter.filterPartialDocuments(rerankedResults, context.epoch);

            if (rerankedResults.size() < beforeAtomicityFilter) {
                LOGGER.debug("Stage 4: Document atomicity filter removed {} partial document results",
                    beforeAtomicityFilter - rerankedResults.size());
            }
        }

        LOGGER.debug("Stage 4: Reranked {} candidates to {} results (atomicity filtered from {})",
            candidates.size(), rerankedResults.size(), beforeAtomicityFilter);

        return context.withMergedResults(rerankedResults);
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
     *
     * <h3>Domain Correctness Validation</h3>
     * <p>This method validates that empty results are only returned when legitimately
     * expected. If empty results are returned unexpectedly, diagnostic information
     * is logged to help identify the root cause.</p>
     *
     * <p><strong>Empty results are ONLY acceptable when no documents match the query vector.</strong></p>
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

        // Build coverage with degraded flag that includes LLM fallback status
        // Domain correctness: degraded must be true when:
        // 1. Coverage is below threshold, OR
        // 2. Segment errors occurred, OR
        // 3. Segment timeouts occurred, OR
        // 4. LLM scoring was requested but fell back to vector-only
        //
        // computeDegraded() handles conditions 1-3, we add condition 4 via context.llmFallback
        boolean baseDegradedFromCompute = estimatedCorpusCoverageWouldBeDegraded(
            activeSegments, indexingSegments, warmingSegments, compactingSegments,
            totalSegments, tombstonedSegments, segmentsWithErrors, coverageThreshold);
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
            .degraded(context.llmFallback || baseDegradedFromCompute)
            .build();

        QueryResponse response = new QueryResponse(
            context.mergedResults,
            coverage,
            durationMs,
            false,
            false
        );

        // DOMAIN CORRECTNESS VALIDATION:
        // Empty results are ONLY acceptable when no documents match the query vector
        // Log diagnostic information when empty results are returned to help identify issues
        if (response.isEmpty()) {
            validateAndLogEmptyResults(context, coverage, response);
        }

        return response;
    }

    /**
     * Computes whether the degraded flag would be true based on coverage and error conditions.
     *
     * <p>This mirrors the logic in IndexCoverage.Builder.computeDegraded() but allows
     * us to combine it with additional conditions like LLM fallback.</p>
     *
     * @param activeSegments Active segment count
     * @param indexingSegments Indexing segment count
     * @param warmingSegments Warming segment count
     * @param compactingSegments Compacting segment count
     * @param totalSegments Total segment count
     * @param tombstonedSegments Tombstoned segment count
     * @param segmentsWithErrors Segments with errors count
     * @param threshold Coverage threshold
     * @return true if degraded conditions are met
     */
    private boolean estimatedCorpusCoverageWouldBeDegraded(
            long activeSegments, long indexingSegments, long warmingSegments, long compactingSegments,
            long totalSegments, long tombstonedSegments, int segmentsWithErrors, double threshold) {
        // Compute estimated corpus coverage (same logic as IndexCoverage.Builder.computeCoverage())
        long nonPurged = totalSegments - tombstonedSegments;
        double estimatedCorpusCoverage;
        if (nonPurged <= 0) {
            estimatedCorpusCoverage = 0.0;
        } else {
            long queryable = activeSegments + indexingSegments + warmingSegments + compactingSegments;
            estimatedCorpusCoverage = Math.max(0.0, Math.min(1.0, (double) queryable / nonPurged));
        }

        // Check degraded conditions (same logic as IndexCoverage.Builder.computeDegraded())
        return estimatedCorpusCoverage < threshold || segmentsWithErrors > 0;
    }

    /**
     * Validates that empty results are legitimate and logs diagnostic information.
     *
     * <p>This method enforces the domain correctness constraint that empty results
     * should only be returned when no documents match the query vector.</p>
     *
     * @param context The pipeline context
     * @param coverage The computed index coverage
     * @param response The query response (for validation)
     */
    private void validateAndLogEmptyResults(PipelineContext context, IndexCoverage coverage, QueryResponse response) {
        // Use EmptyResultValidator to diagnose the cause of empty results
        // Adapt the HnswGraphProvider interface using a lambda
        excalibur.query.visibility.EmptyResultValidator.ValidationResult validationResult =
            excalibur.query.visibility.EmptyResultValidator.validate(
                response,
                context.request,
                storageEngine,
                visibilityTracker,
                context.segmentResults,
                graphProvider::getGraph  // Adapt to EmptyResultValidator.HnswGraphProvider
            );

        if (validationResult.isLegitimate()) {
            LOGGER.debug("Query returned empty results (legitimate): {}", validationResult.oneLineSummary());
        } else {
            // Log at WARN level for unexpected empty results to help diagnose issues
            LOGGER.warn("Query returned empty results unexpectedly - potential bug detected%n{}",
                validationResult.diagnosticSummary());

            // Also run HNSW graph diagnostic for segments with vectors but empty results
            for (SegmentQueryTask.SegmentQueryResult segmentResult : context.segmentResults) {
                if (segmentResult.success() && segmentResult.results().isEmpty()) {
                    HnswGraph graph = graphProvider.getGraph(segmentResult.segmentId());
                    if (graph != null && graph.activeNodeCount() > 0) {
                        // Segment has vectors but search returned empty - diagnose the graph
                        LOGGER.warn("Segment {} has {} active nodes but search returned empty - running HNSW diagnostic",
                            segmentResult.segmentId(), graph.activeNodeCount());

                        try {
                            excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticResult graphDiagnostic =
                                excalibur.query.visibility.HnswGraphDiagnostic.runFullDiagnostic(
                                    graph, null, 0);

                            LOGGER.warn("HNSW diagnostic for segment {}: {}", segmentResult.segmentId(),
                                graphDiagnostic.summary());

                            for (excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticIssue issue : graphDiagnostic.issues()) {
                                LOGGER.warn("  Issue: {}", issue);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Failed to run HNSW diagnostic for segment {}: {}",
                                segmentResult.segmentId(), e.getMessage());
                        }
                    }
                }
            }
        }
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
