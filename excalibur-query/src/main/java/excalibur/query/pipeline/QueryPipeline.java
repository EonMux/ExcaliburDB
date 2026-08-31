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
import excalibur.query.payload.ResultPayloadResolver;
import excalibur.query.visibility.DocumentAtomicityFilter;
import excalibur.query.visibility.QuerySnapshot;
import excalibur.shard.visibility.EpochIsolationValidator;
import excalibur.query.validation.DomainCorrectnessValidator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.core.logging.StructuredLoggingHelper;
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
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Multiplier applied to topK when collecting Stage 4 rerank candidates.
     */
    private static final int RERANK_CANDIDATE_MULTIPLIER = 2;

    /**
     * Minimum interval between two runs of the empty-result diagnostics.
     */
    private static final long EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    /**
     * Minimum fraction of a segment's entries an LSH index must cover before its verdict
     * is treated as authoritative.
     *
     * <p>An index that has seen far fewer entries than the segment holds - because it is
     * still being populated, or was built after the segment - cannot rule a segment out:
     * an empty candidate set from such an index means "no information", not "no matches".</p>
     */
    private static final double LSH_AUTHORITATIVE_COVERAGE = 0.5;

    /**
     * Epoch handed to the document atomicity filter, meaning "no epoch bound".
     *
     * <p>The pipeline's own epoch ({@code PipelineContext.epoch}) comes from the shard's
     * {@link VisibilityTracker}, which counts segment lifecycle transitions and periodic
     * ticks. Document commit epochs come from the ingest-side visibility tracker, a
     * separate counter advanced once per published document. The two numbers have no
     * defined ordering relationship, so comparing a document's commit epoch against the
     * shard epoch decides visibility on whichever counter happens to be running faster -
     * dropping committed documents when ingest is ahead, and asserting nothing when the
     * shard tick is.</p>
     *
     * <p>Rather than perform a comparison across two unrelated domains, the filter is
     * applied without an epoch bound, which reduces it to exactly the guarantee it exists
     * to provide: a result is dropped only when its document is registered as not yet
     * complete. Epoch-based snapshot isolation is still enforced, in its own domain, by
     * the shard {@code EpochIsolationValidator} in Stage 3.</p>
     *
     * <p>Restoring an epoch bound here requires the query epoch to be drawn from the same
     * counter that stamps document commits - i.e. an accessor for the current commit epoch
     * on {@code DocumentVisibilityChecker}, captured in Stage 0 alongside the segment
     * epoch.</p>
     */
    private static final long DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED = Long.MAX_VALUE;

    private final StorageEngine storageEngine;
    private final VisibilityTracker visibilityTracker;
    private final DocumentVisibilityChecker visibilityChecker;
    private final HnswGraphProvider graphProvider;
    private final DistanceFunction distanceFunction;
    private final LlmScorer llmScorer;
    private final double coverageThreshold;
    private final HierarchicalCuckooFilter hierarchicalFilter;

    /**
     * Epoch isolation validator for filtering HNSW results to only include
     * nodes visible at the query's captured epoch.
     * May be null if epoch isolation is not configured.
     */
    private final EpochIsolationValidator epochIsolationValidator;

    /**
     * Resolves the stored chunk text of the results Stage 5 is about to score.
     *
     * <p>A search result identifies a node, not a document body, so without this the
     * relevancy scorer receives one empty string per candidate.</p>
     */
    private final ResultPayloadResolver payloadResolver;

    // Lazily initialized dispatcher for parallel query execution
    private volatile ParallelQueryDispatcher dispatcher;

    // Lazily initialized executor that runs the (blocking) pipeline stages. The pipeline
    // must not run on ForkJoinPool.commonPool: its stages block on segment I/O and on the
    // dispatcher, which starves every other commonPool user, and a commonPool task cannot
    // be interrupted when the query times out.
    private volatile ExecutorService pipelineExecutor;

    /**
     * Timestamp (nanoTime) after which empty-result diagnostics may run again.
     *
     * <p>The diagnostics walk whole graphs, so they are rate limited: a query storm that
     * legitimately returns nothing must not turn every response into a full graph scan.</p>
     */
    private final AtomicLong nextEmptyResultDiagnosticNanos = new AtomicLong(Long.MIN_VALUE);

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

        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction,
             llmScorer, coverageThreshold, hierarchicalFilter, null);
    }

    /**
     * Creates a new QueryPipeline with all options including epoch isolation.
     *
     * <p>This is the full constructor that accepts all configurable parameters including
     * the epoch isolation validator for preventing torn reads at the node level.</p>
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param visibilityChecker The visibility checker for document atomicity (may be null)
     * @param graphProvider Provider for HNSW graphs
     * @param distanceFunction The distance function
     * @param llmScorer Optional LLM scorer for Stage 5
     * @param coverageThreshold Threshold for degraded state
     * @param hierarchicalFilter Optional hierarchical Cuckoo filter for Stage 1 pruning
     * @param epochIsolationValidator Optional validator for node-level epoch filtering
     */
    public QueryPipeline(
            StorageEngine storageEngine,
            VisibilityTracker visibilityTracker,
            DocumentVisibilityChecker visibilityChecker,
            HnswGraphProvider graphProvider,
            DistanceFunction distanceFunction,
            LlmScorer llmScorer,
            double coverageThreshold,
            HierarchicalCuckooFilter hierarchicalFilter,
            EpochIsolationValidator epochIsolationValidator) {

        // Dispatcher and pipeline executor are lazily initialized on first use
        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction,
            llmScorer, coverageThreshold, hierarchicalFilter, epochIsolationValidator, null, null);
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
            EpochIsolationValidator epochIsolationValidator,
            ParallelQueryDispatcher dispatcher,
            ExecutorService pipelineExecutor) {

        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        this.visibilityChecker = visibilityChecker;
        this.graphProvider = Objects.requireNonNull(graphProvider, "GraphProvider cannot be null");
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");
        this.llmScorer = llmScorer;
        this.coverageThreshold = coverageThreshold;
        this.hierarchicalFilter = hierarchicalFilter;
        this.epochIsolationValidator = epochIsolationValidator;
        this.payloadResolver = new ResultPayloadResolver(this.storageEngine);
        this.dispatcher = dispatcher;
        this.pipelineExecutor = pipelineExecutor;
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

    /**
     * Returns the executor that runs pipeline stages, initializing it lazily.
     *
     * <p>A virtual-thread-per-task executor matches the rest of the server: the stages
     * are dominated by blocking waits, and a virtual thread parked on one costs nothing.
     * Crucially it also lets a timed-out query interrupt the thread actually running the
     * pipeline, which {@link CompletableFuture#supplyAsync(java.util.function.Supplier)}
     * on the common pool cannot do.</p>
     *
     * @return The pipeline executor
     */
    private ExecutorService getPipelineExecutor() {
        ExecutorService local = pipelineExecutor;
        if (local == null) {
            synchronized (this) {
                local = pipelineExecutor;
                if (local == null) {
                    local = Executors.newVirtualThreadPerTaskExecutor();
                    pipelineExecutor = local;
                }
            }
        }
        return local;
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
            this.epochIsolationValidator,
            this.dispatcher,
            this.pipelineExecutor
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
            this.epochIsolationValidator,
            this.dispatcher,
            this.pipelineExecutor
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
        }
        ExecutorService executor = pipelineExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        LOGGER.debug("QueryPipeline shutdown complete");
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

        // Set correlation ID in MDC for structured logging
        StructuredLoggingHelper.setCorrelationId(correlationId);

        LOGGER.debug("Starting query pipeline");

        // Holds the latest completed stage's context so that if the pipeline is
        // interrupted by a timeout or cancellation, handlePipelineError can return
        // any partial results that were already computed (the pipeline contract is
        // "partial results on timeout", not "empty results on timeout").
        AtomicReference<PipelineContext> partialContext = new AtomicReference<>();

        // Run the pipeline on the pipeline executor, keeping the underlying task handle so
        // a timeout can actually interrupt the work instead of just abandoning it.
        CompletableFuture<PipelineContext> pipelineFuture = new CompletableFuture<>();
        Future<?> pipelineTask = getPipelineExecutor().submit(() -> {
            try {
                pipelineFuture.complete(runStages(request, token, partialContext));
            } catch (Throwable t) {
                pipelineFuture.completeExceptionally(t);
            }
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
            // And the count the IngestionThroughput MBean reports, which had no writer outside
            // the tests: the MBean was registered and advertised four attributes that were zero
            // on a server that had served millions of queries.
            Metrics.recordQueryExecution();

            try {
                if (error != null) {
                    // Stop the abandoned pipeline: cancelling with an interrupt unblocks
                    // the stage that is running and stops the segment searches and tail
                    // scans it dispatched, which all check the interrupt flag.
                    pipelineTask.cancel(true);
                    return handlePipelineError(error, request, durationMs, token, partialContext.get());
                }

                return buildResponse(context, durationMs);
            } finally {
                // Clear MDC context after pipeline completes
                StructuredLoggingHelper.clearCorrelationId();
            }
        });
    }

    /**
     * Runs every pipeline stage in order, publishing each stage's context so that a
     * timeout or cancellation can still return the results computed so far.
     *
     * @param request The query request
     * @param token The cancellation token
     * @param partialContext Holder updated after each completed stage
     * @return The final pipeline context
     */
    private PipelineContext runStages(
            QueryRequest request,
            CancellationToken token,
            AtomicReference<PipelineContext> partialContext) {

        // Stage 0: Epoch Snapshot
        PipelineContext context = stage0_EpochSnapshot(request, token);
        partialContext.set(context);

        // Stage 0b: Selectivity Estimation and Segment Prioritization
        context = stage0b_SelectivityEstimation(context, token);
        partialContext.set(context);

        // Stage 1: Cuckoo Filter Cascade
        context = stage1_CuckooFilterCascade(context, token);
        partialContext.set(context);

        // Stage 2: LSH Pre-filter
        context = stage2_LshPreFilter(context, token);
        partialContext.set(context);

        // Stage 3: HNSW Vector Search with Tail Scan
        context = stage3_HnswVectorSearch(context, token);
        partialContext.set(context);

        // Stage 4: Full Vector Rerank
        context = stage4_FullVectorRerank(context, token);
        partialContext.set(context);

        // Stage 5: LLM Relevancy Scoring (optional)
        context = stage5_LlmScoring(context, token);
        partialContext.set(context);

        return context;
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
                    terms.add(currentTerm.toString().toLowerCase(Locale.ROOT));
                    currentTerm.setLength(0);
                }
            } else {
                currentTerm.append(c);
            }
        }

        // Add final term if any
        if (currentTerm.length() > 0) {
            terms.add(currentTerm.toString().toLowerCase(Locale.ROOT));
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

        // A segment is excluded only on the word of a filter that actually holds its terms.
        // The filter is built in memory as documents are published and is not reloaded at
        // startup, so on a fresh process it holds nothing at all -- and reading that silence as
        // "this term is in no segment" makes every query with a text term return zero results
        // until the corpus has been re-ingested. Nothing errors; the database simply answers
        // that it knows nothing. This is the same rule stage 2 already applies to an LSH index
        // that does not cover its segment: an index with no entries has no verdict to give.
        List<SegmentCandidate> passed = context.candidates.stream()
            .filter(c -> survivesTermFilter(hierarchicalFilter, c.segmentId(), candidateSegmentIds))
            .collect(Collectors.toList());

        LOGGER.debug("Stage 1: {} of {} segments passed Cuckoo filter cascade "
            + "({} terms matched, {} unique segment IDs from filter)",
            passed.size(), context.candidates.size(), termsWithMatches, candidateSegmentIds.size());

        return context.withCandidates(passed);
    }

    /**
     * Whether the hierarchical filter holds any terms for a segment.
     *
     * <p>Only a segment the filter has actually been populated for can be excluded by it. A
     * segment with no registered filter, or with one holding nothing, is a segment the filter
     * has no opinion about — which is not the same as one it has looked at and rejected.
     *
     * @param segmentId The segment's id
     * @return true when the filter's silence about this segment is meaningful
     */
    static boolean filterCovers(HierarchicalCuckooFilter filter, long segmentId) {
        if (filter == null) {
            return false;
        }
        excalibur.filter.CuckooFilter segmentFilter =
            filter.getSegmentFilter(String.valueOf(segmentId));
        return segmentFilter != null && segmentFilter.count() > 0;
    }

    /**
     * Whether a segment survives the term filter.
     *
     * <p>It does when a query term was found in it, or when the filter holds nothing for it and
     * so has nothing to say. Only the second half is subtle, and it is the half that was
     * missing: see {@link #filterCovers}.
     *
     * @param filter    The hierarchical filter, which may be null
     * @param segmentId The candidate segment
     * @param matched   Segment ids the query's terms were found in
     * @return true when the segment should be searched
     */
    static boolean survivesTermFilter(HierarchicalCuckooFilter filter, long segmentId,
                                      Set<String> matched) {
        return matched.contains(String.valueOf(segmentId)) || !filterCovers(filter, segmentId);
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
     *   <li>If the index covers the segment and returns no candidates, the segment is
     *       excluded from further processing</li>
     *   <li>If the index is empty or covers only a small fraction of the segment's
     *       entries, its verdict carries no information and the segment passes through</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>LSH candidates are entry IDs (long values) that potentially match the query vector</li>
     *   <li>A segment is only excluded on the word of an index that actually covers it -
     *       an empty or barely-populated index means "unknown", not "no matches"</li>
     *   <li>Segments without an LSH index pass through to ensure no data loss</li>
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
            if (lsh == null) {
                // No LSH index - pass through to ensure no data loss
                passed.add(candidate);
                continue;
            }

            segmentsWithLsh++;
            try {
                Set<Long> lshCandidates = lsh.query(queryVector);
                if (!lshCandidates.isEmpty()) {
                    totalLshCandidates += lshCandidates.size();
                    passed.add(candidate.withLshCandidates(lshCandidates));
                } else if (isLshAuthoritative(candidate, lsh)) {
                    // The index covers this segment and found nothing: a real exclusion
                    segmentsFiltered++;
                    LOGGER.trace("Segment {} filtered out by LSH (no candidates)", candidate.segmentId());
                } else {
                    // The index has seen little or none of this segment, so an empty
                    // candidate set says nothing about it. Treat it exactly like a
                    // missing index and pass the segment through.
                    LOGGER.debug("Segment {} LSH index covers {} of {} entries; " +
                        "empty result is not authoritative, passing through",
                        candidate.segmentId(), lsh.entryCount(), segmentEntryCount(candidate));
                    passed.add(candidate);
                }
            } catch (Exception e) {
                // On LSH error, pass the segment through to ensure no data loss
                LOGGER.warn("LSH query failed for segment {}: {}. Passing through.",
                    candidate.segmentId(), e.getMessage());
                passed.add(candidate);
            }
        }

        LOGGER.debug("Stage 2: {} segments passed LSH pre-filter ({} with LSH, {} filtered out, {} total LSH candidates)",
            passed.size(), segmentsWithLsh, segmentsFiltered, totalLshCandidates);

        return context.withCandidates(passed);
    }

    /**
     * Decides whether an empty LSH result may be used to exclude a segment.
     *
     * <p>An LSH index only speaks for the entries it has actually indexed. One that is
     * empty, or that holds far fewer entries than the segment does, has no basis for
     * ruling the segment out; treating its empty answer as authoritative would silently
     * drop every match the segment holds.</p>
     *
     * @param candidate The segment candidate
     * @param lsh The segment's LSH index
     * @return true if the index covers enough of the segment for an empty result to mean
     *         "no matches" rather than "unknown"
     */
    private boolean isLshAuthoritative(SegmentCandidate candidate, LshIndex lsh) {
        long indexed = lsh.entryCount();
        if (indexed <= 0) {
            return false;
        }

        long segmentEntries = segmentEntryCount(candidate);
        if (segmentEntries <= 0) {
            // Entry count unavailable: a populated index is the best evidence we have
            return true;
        }

        return indexed >= segmentEntries * LSH_AUTHORITATIVE_COVERAGE;
    }

    /**
     * Returns the number of entries a candidate's segment holds, or -1 if unknown.
     */
    private long segmentEntryCount(SegmentCandidate candidate) {
        Segment segment = candidate.segment();
        if (segment == null) {
            return -1;
        }
        try {
            return segment.entryCount();
        } catch (Exception e) {
            LOGGER.trace("Could not read entry count for segment {}: {}",
                candidate.segmentId(), e.getMessage());
            return -1;
        }
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
            return context.withSegmentResults(List.of(), 0, 0);
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
        int segmentsTimedOut = dispatchResult.coverage().segmentsTimedOut();

        // Apply epoch isolation filtering to HNSW results to prevent torn reads
        // Filter out nodes that were inserted after the query's captured epoch
        if (epochIsolationValidator != null) {
            long queryEpoch = context.epoch;
            int totalFiltered = 0;
            List<SegmentQueryTask.SegmentQueryResult> filteredResults = new ArrayList<>(segmentResults.size());

            for (SegmentQueryTask.SegmentQueryResult sr : segmentResults) {
                if (sr != null && sr.results() != null) {
                    // Scope the lookup by segment. Node IDs are only unique within one
                    // segment's graph, and the integration worker registers them per
                    // segment; the segment-less overload looks them up in an unrelated,
                    // never-populated scope, where every node resolves to "always
                    // visible" and the isolation guarantee silently disappears.
                    List<HnswSearchResult> filtered = epochIsolationValidator.filterResults(
                        sr.segmentId(), sr.results(), queryEpoch);
                    totalFiltered += sr.results().size() - filtered.size();

                    // Create new result with filtered list, keeping the tail-scan chunk
                    // text so the surviving results still carry their payloads
                    filteredResults.add(new SegmentQueryTask.SegmentQueryResult(
                        sr.segmentId(),
                        filtered,
                        sr.tailScanEntries(),
                        sr.durationMs(),
                        sr.success(),
                        sr.errorMessage(),
                        sr.payloads()
                    ));
                } else {
                    filteredResults.add(sr);
                }
            }

            if (totalFiltered > 0) {
                LOGGER.debug("Stage 3: Epoch isolation filtered out {} results not visible at epoch {}",
                    totalFiltered, queryEpoch);
            }

            segmentResults = filteredResults;
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.debug("Stage 3: Search completed - {} segments, {} tail scan entries, {}ms",
            segmentResults.size(), totalTailScanEntries, durationMs);

        return context.withSegmentResults(segmentResults, totalTailScanEntries, segmentsTimedOut);
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
        // Oversample so reranking and filtering have spare candidates to work with.
        // topK is client-supplied, so widen before multiplying and saturate instead of
        // wrapping to a negative candidate count.
        ResultAggregator aggregator = new ResultAggregator(
            rerankCandidateCount(context.request.topK()),
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

        // Step 4: Sort by normalized score descending (highest/best first).
        // Normalized scores are in [0.0, 1.0] where higher is better (more similar).
        rerankedResults = ResultAggregator.sortByRelevanceDescending(rerankedResults);

        int beforeAtomicityFilter = rerankedResults.size();

        // Step 5: Apply the document atomicity filter to the FULL candidate list, before
        // truncating. Filtering after truncation would return fewer than topK results
        // whenever a partial document happened to rank inside the first topK, even though
        // enough valid candidates were available.
        rerankedResults = applyAtomicityFilter(rerankedResults, context.epoch);

        // Step 6: Truncate to topK once the result set is final. When Stage 5 is going to
        // run, leave the oversampled list intact: its threshold filter would otherwise be
        // applied to an already-truncated list and return fewer than topK results even
        // though enough candidates passed the threshold.
        if (!llmScoringWillRun(context.request)) {
            rerankedResults = truncate(rerankedResults, context.request.topK());
        }

        LOGGER.debug("Stage 4: Reranked {} candidates to {} results (atomicity filtered from {})",
            candidates.size(), rerankedResults.size(), beforeAtomicityFilter);

        return context.withMergedResults(rerankedResults);
    }

    /**
     * Returns the number of candidates Stage 4 collects for reranking.
     *
     * @param topK The requested result count (client-controlled)
     * @return {@code topK * RERANK_CANDIDATE_MULTIPLIER}, saturated at {@link Integer#MAX_VALUE}
     */
    private static int rerankCandidateCount(int topK) {
        long widened = (long) topK * RERANK_CANDIDATE_MULTIPLIER;
        return widened > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) widened;
    }

    /**
     * Applies the document atomicity filter, if it can say anything useful.
     *
     * <p>The filter excludes results belonging to documents that are not yet fully
     * visible. It can only do that for documents the visibility checker knows about; when
     * the checker holds no registrations at all - which is the case whenever ingestion
     * runs its per-chunk path rather than atomic document batching - it has nothing to
     * compare against, and running it would express "unknown" as "not visible" for every
     * result in the response.</p>
     *
     * <h3>Which epoch bounds it</h3>
     * <p>The query's own captured epoch, when the checker publishes on the same clock the
     * query captured from - which {@link DocumentVisibilityChecker#sharesNodeEpochClock()}
     * reports. That is a real snapshot bound: a document committed after this query started is
     * excluded from it, so a long-running query does not pick up writes partway through.</p>
     *
     * <p>Otherwise {@link #DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED}, and the filter degrades to a
     * completeness check. Bounding by a foreign counter is worse than not bounding: the two
     * numbers are unrelated, so the comparison excludes or admits documents arbitrarily, and it
     * does so while looking exactly like a snapshot.</p>
     *
     * @param results The reranked results
     * @param queryEpoch The epoch this query captured in stage 0
     * @return The results that survive the filter, or the input unchanged when the filter
     *         is not configured or holds no document registrations
     */
    /**
     * The epoch to bound the atomicity filter by.
     *
     * <p>The query's own captured epoch when the checker publishes on the clock that epoch came
     * from, and {@link #DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED} otherwise. A rule rather than an
     * inline condition because getting it wrong is silent in both directions: bounding by a
     * foreign counter excludes documents arbitrarily, and never bounding gives up snapshot
     * isolation without saying so.
     *
     * @param checker    The visibility checker the filter will consult
     * @param queryEpoch The epoch captured in stage 0
     * @return The bound to filter with
     */
    static long atomicityBoundFor(DocumentVisibilityChecker checker, long queryEpoch) {
        if (checker == null || !checker.sharesNodeEpochClock()) {
            return DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;
        }
        // A negative epoch is not a snapshot anyone captured; the filter rejects it outright,
        // so falling back keeps a malformed context from failing the whole query.
        return queryEpoch >= 0 ? queryEpoch : DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;
    }

    private List<QueryResultItem> applyAtomicityFilter(List<QueryResultItem> results,
                                                       long queryEpoch) {
        if (visibilityChecker == null) {
            return results;
        }

        int registeredDocuments = visibilityChecker.getDocumentCount();
        if (registeredDocuments == 0) {
            LOGGER.trace("Stage 4: Skipping document atomicity filter - no documents registered");
            return results;
        }

        long bound = atomicityBoundFor(visibilityChecker, queryEpoch);
        boolean bounded = bound != DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;

        DocumentAtomicityFilter atomicityFilter = new DocumentAtomicityFilter(visibilityChecker);
        List<QueryResultItem> filtered = atomicityFilter.filterPartialDocuments(results, bound);

        if (filtered.size() < results.size()) {
            LOGGER.debug("Stage 4: Document atomicity filter removed {} partial document results "
                + "({} documents registered, bound={})", results.size() - filtered.size(),
                registeredDocuments, bounded ? String.valueOf(bound) : "unbounded");
        }

        return filtered;
    }

    /**
     * Stage 5: LLM Relevancy Scoring.
     *
     * <p>Sends top-k candidates + original query text to LLM for relevancy
     * scoring. Discards results below threshold. Falls back to vector-only
     * ranking if LLM is unavailable.</p>
     *
     * <h3>Candidate Text</h3>
     * <p>Stage 4 hands over results that identify nodes, not document bodies. This stage
     * resolves each surviving result's stored chunk text through
     * {@link ResultPayloadResolver} before scoring; a write-log tail hit already carries
     * its text from the scan and is left alone.</p>
     *
     * <h3>Sorting After LLM Scoring</h3>
     * <p>After LLM scoring, results are sorted by relevance score in descending
     * order (highest relevance first). This ensures the most relevant results
     * appear at the top of the response.</p>
     */
    private PipelineContext stage5_LlmScoring(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 5 cancelled before LLM scoring");

        if (!llmScoringWillRun(context.request)) {
            LOGGER.trace("Stage 5: Skipping LLM scoring (disabled, no scorer, or no query text)");
            // Stage 4 already truncated to topK in this case
            return context.withLlmScored(false);
        }

        LOGGER.trace("Stage 5: Running LLM relevancy scoring");

        int topK = context.request.topK();

        // Relevancy scoring compares the query against candidate TEXT, and a search result
        // identifies a node rather than a document body. Resolve the stored chunk text now
        // - after selection has narrowed the candidate set, because each resolution is a
        // storage read - so the scorer has something real to rank.
        List<QueryResultItem> candidates = payloadResolver.attachPayloads(context.mergedResults);

        try {
            List<QueryResultItem> scoredResults = llmScorer.scoreResults(
                context.request.queryText(),
                candidates,
                context.request.llmThreshold()
            );

            // Sort by relevance score descending (highest first)
            // LLM relevancy scores are in range [0.0, 1.0] where higher is better
            scoredResults = ResultAggregator.sortByRelevanceDescending(scoredResults);

            LOGGER.debug("Stage 5: LLM scored {} results, {} after threshold filtering",
                candidates.size(), scoredResults.size());

            // Truncate only now, so the threshold filter chose from the full candidate set
            return context.withMergedResults(truncate(scoredResults, topK)).withLlmScored(true);

        } catch (Exception e) {
            LOGGER.warn("LLM scoring failed, using vector-only ranking: {}", e.getMessage());
            // Stage 4 deliberately left the list oversampled for this stage; fall back to
            // the vector ranking, truncated to the requested size.
            return context
                .withMergedResults(truncate(candidates, topK))
                .withLlmScored(false)
                .withLlmFallback(true);
        }
    }

    /**
     * Returns whether Stage 5 will actually score this request.
     *
     * <p>Stage 4 consults this to decide whether it may truncate to topK or must leave
     * spare candidates for Stage 5's threshold filter, so the two must agree exactly.</p>
     *
     * @param request The query request
     * @return true if LLM relevancy scoring will run
     */
    private boolean llmScoringWillRun(QueryRequest request) {
        return request.enableLlmScoring()
            && llmScorer != null
            && request.queryText() != null
            && !request.queryText().isEmpty();
    }

    /**
     * Returns at most {@code limit} results, without copying when none are dropped.
     */
    private static List<QueryResultItem> truncate(List<QueryResultItem> results, int limit) {
        if (results.size() <= limit) {
            return results;
        }
        return new ArrayList<>(results.subList(0, limit));
    }

    // ==================== Error Handling ====================

    /**
     * Handles pipeline errors and creates appropriate response.
     *
     * <p>When the pipeline is interrupted by a timeout or cancellation, any results
     * that were already computed by earlier stages are returned as best-effort partial
     * results. Hard failures - and interruptions that happened before any stage completed -
     * produce an empty result set: a stage that threw did not produce an answer, and
     * whatever earlier stages left behind is not one either.</p>
     *
     * @param error         The error that terminated the pipeline
     * @param request       The original query request
     * @param durationMs    Elapsed time before the error was observed
     * @param token         Cancellation token (cancelled here to propagate to running stages)
     * @param partialContext The most recently completed stage's context, or {@code null}
     *                      if no stage completed before the error
     */
    private QueryResponse handlePipelineError(
            Throwable error,
            QueryRequest request,
            long durationMs,
            CancellationToken token,
            PipelineContext partialContext) {

        boolean timedOut = error instanceof TimeoutException ||
            (error.getCause() instanceof TimeoutException);
        boolean cancelled = error instanceof CancellationException ||
            (error.getCause() instanceof CancellationException);

        if (timedOut) {
            LOGGER.warn("Query timed out after {}ms", durationMs);
            // Cancel the token to propagate to any running stages
            token.cancel("Query timeout after " + durationMs + "ms");
        } else if (cancelled) {
            LOGGER.info("Query cancelled");
        } else {
            LOGGER.error("Query failed: {}", error.getMessage(), error);
        }

        // Return partial results only for an interruption, which is what the contract
        // promises. A hard failure means a stage did not do its job, so whatever earlier
        // stages happened to accumulate is not an answer to the query - returning it would
        // present an arbitrary prefix of the pipeline as a successful (if degraded) result.
        boolean interrupted = timedOut || cancelled;
        List<QueryResultItem> partialResults = (interrupted && partialContext != null)
            ? partialContext.mergedResults
            : Collections.emptyList();
        long partialTailScanEntries = (interrupted && partialContext != null)
            ? partialContext.totalTailScanEntries
            : 0L;

        // Build degraded response
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .tailScanEntries(partialTailScanEntries)
            .computeCoverage()
            .build();

        return new QueryResponse(
            partialResults,
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
            .segmentsTimedOut(context.segmentsTimedOut)
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
        // Validate the response meets all domain correctness constraints
        // This ensures: scores in [0.0, 1.0], coverage in [0.0, 1.0], results sorted, no duplicates
        DomainCorrectnessValidator.ValidationResult validationResult =
            DomainCorrectnessValidator.validateResponseWithResult(response);
        if (!validationResult.isValid()) {
            LOGGER.warn("Query response failed domain correctness validation: {}",
                validationResult.summary());
        }

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
     * <p>The diagnostics scan graphs, so they are rate limited to at most one run per
     * {@link #EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS}. Without that bound a workload
     * whose queries legitimately match nothing would attach a full graph walk to every
     * response, on the response thread and outside the query's own timeout.</p>
     *
     * @param context The pipeline context
     * @param coverage The computed index coverage
     * @param response The query response (for validation)
     */
    private void validateAndLogEmptyResults(PipelineContext context, IndexCoverage coverage, QueryResponse response) {
        if (!acquireEmptyResultDiagnosticSlot()) {
            LOGGER.debug("Query returned empty results; diagnostics rate limited");
            return;
        }

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
            return;
        }

        // Log at WARN level for unexpected empty results to help diagnose issues
        LOGGER.warn("Query returned empty results unexpectedly - potential bug detected%n{}",
            validationResult.diagnosticSummary());

        scheduleGraphDiagnostics(context);
    }

    /**
     * Reserves the next diagnostic slot, returning false when one ran too recently.
     */
    private boolean acquireEmptyResultDiagnosticSlot() {
        long now = System.nanoTime();
        long next = nextEmptyResultDiagnosticNanos.get();
        if (next != Long.MIN_VALUE && now - next < 0) {
            return false;
        }
        return nextEmptyResultDiagnosticNanos.compareAndSet(
            next, now + EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS);
    }

    /**
     * Runs the HNSW graph diagnostic off the query path.
     *
     * <p>{@code runFullDiagnostic} walks the whole graph and verifies its invariants,
     * which is unbounded relative to graph size. It must never run on the thread that is
     * completing a query response, so it is handed to the pipeline executor and its result
     * is logged whenever it finishes.</p>
     *
     * @param context The pipeline context whose segment results triggered the diagnostic
     */
    private void scheduleGraphDiagnostics(PipelineContext context) {
        List<Long> segmentsToDiagnose = new ArrayList<>();
        for (SegmentQueryTask.SegmentQueryResult segmentResult : context.segmentResults) {
            if (segmentResult.success() && segmentResult.results().isEmpty()) {
                segmentsToDiagnose.add(segmentResult.segmentId());
            }
        }

        if (segmentsToDiagnose.isEmpty()) {
            return;
        }

        try {
            getPipelineExecutor().execute(() -> runGraphDiagnostics(segmentsToDiagnose));
        } catch (RuntimeException e) {
            // Executor shut down mid-flight; diagnostics are best-effort
            LOGGER.debug("Could not schedule HNSW diagnostics: {}", e.getMessage());
        }
    }

    /**
     * Runs the HNSW graph diagnostic for each segment that returned nothing despite
     * holding active nodes, logging what it finds.
     *
     * @param segmentIds The segments to diagnose
     */
    private void runGraphDiagnostics(List<Long> segmentIds) {
        for (long segmentId : segmentIds) {
            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph == null || graph.activeNodeCount() <= 0) {
                continue;
            }

            LOGGER.warn("Segment {} has {} active nodes but search returned empty - running HNSW diagnostic",
                segmentId, graph.activeNodeCount());

            try {
                excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticResult graphDiagnostic =
                    excalibur.query.visibility.HnswGraphDiagnostic.runFullDiagnostic(graph, 0);

                LOGGER.warn("HNSW diagnostic for segment {}: {}", segmentId, graphDiagnostic.summary());

                for (excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticIssue issue
                        : graphDiagnostic.issues()) {
                    LOGGER.warn("  Issue: {}", issue);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to run HNSW diagnostic for segment {}: {}", segmentId, e.getMessage());
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
        final int segmentsTimedOut;
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
                int segmentsTimedOut,
                boolean llmScored,
                boolean llmFallback) {
            this.request = request;
            this.token = token;
            this.epoch = epoch;
            this.candidates = candidates;
            this.segmentResults = segmentResults;
            this.mergedResults = mergedResults;
            this.totalTailScanEntries = totalTailScanEntries;
            this.segmentsTimedOut = segmentsTimedOut;
            this.llmScored = llmScored;
            this.llmFallback = llmFallback;
        }

        PipelineContext(QueryRequest request, CancellationToken token, long epoch, List<SegmentCandidate> candidates) {
            this(request, token, epoch, candidates, Collections.emptyList(),
                Collections.emptyList(), 0, 0, false, false);
        }

        PipelineContext withCandidates(List<SegmentCandidate> candidates) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut, llmScored, llmFallback);
        }

        PipelineContext withSegmentResults(
                List<SegmentQueryTask.SegmentQueryResult> results, long tailScanEntries, int timedOut) {
            return new PipelineContext(request, token, epoch, candidates,
                results, mergedResults, tailScanEntries, timedOut, llmScored, llmFallback);
        }

        PipelineContext withMergedResults(List<QueryResultItem> results) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, results, totalTailScanEntries, segmentsTimedOut, llmScored, llmFallback);
        }

        PipelineContext withLlmScored(boolean scored) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut, scored, llmFallback);
        }

        PipelineContext withLlmFallback(boolean fallback) {
            return new PipelineContext(request, token, epoch, candidates,
                segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut, llmScored, fallback);
        }
    }
}
