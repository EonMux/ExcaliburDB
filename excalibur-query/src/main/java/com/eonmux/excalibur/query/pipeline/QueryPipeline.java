package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.ParallelQueryDispatcher;
import com.eonmux.excalibur.query.executor.PqSearchSettings;
import com.eonmux.excalibur.query.payload.ResultPayloadResolver;
import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private final PqSearchSettings pqSearch;
    private final TermFilterCascade termFilterCascade;
    private final EpochSnapshotStage epochSnapshotStage;
    private final HnswSearchStage hnswSearchStage;
    private final RerankStage rerankStage;
    private final LlmScoringStage llmScoringStage;


    /**
     * Turns a pipeline outcome into the caller's response. Re-created with each pipeline, so
     * the empty-result diagnostics it owns start with a fresh rate limiter - which is what the
     * with*() methods have always handed back.
     */
    private final ResponseAssembler responseAssembler;

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

    /**
     * The counts every response reports about the corpus, including the degraded ones.
     */
    private final CoverageCounts coverageCounts;

    /**
     * Reads what a query would see right now, for the cache that has to decide whether an
     * earlier answer is still an answer.
     *
     * <p>Built here rather than by the caller because it reads the pipeline's own
     * collaborators through the pipeline's own accessors, which is the only way a fingerprint
     * can be guaranteed to move whenever a query's inputs do.</p>
     */
    private final CorpusFingerprint corpusFingerprint;

    /**
     * How long {@link #shutdown()} lets in-flight queries finish, and then how long it waits
     * after interrupting them.
     *
     * <p>Short, because a node that is stopping has to stop. Long enough that a query already
     * assembling its response returns it rather than being turned into a failure a fraction of
     * a second before it would have succeeded.</p>
     */
    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;

    /**
     * Whether {@link #shutdown()} has been called.
     *
     * <p>A null executor was the only "not running" signal, and a pipeline that had served no
     * query shut down without ever creating one - so the next query created a fresh executor and
     * submitted work against a storage engine the shutdown sequence was about to close.</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean shutdown =
        new java.util.concurrent.atomic.AtomicBoolean();

    // Lazily initialized dispatcher for parallel query execution
    private volatile ParallelQueryDispatcher dispatcher;

    // Lazily initialized executor that runs the (blocking) pipeline stages. The pipeline
    // must not run on ForkJoinPool.commonPool: its stages block on segment I/O and on the
    // dispatcher, which starves every other commonPool user, and a commonPool task cannot
    // be interrupted when the query times out.
    private volatile ExecutorService pipelineExecutor;

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

        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction,
            llmScorer, coverageThreshold, hierarchicalFilter, epochIsolationValidator,
            PqSearchSettings.DISABLED);
    }

    /**
     * Creates a new QueryPipeline that may answer from each segment's product quantizer.
     *
     * <p>Quantised search is a deployment's decision rather than a segment's: a quantizer is
     * trained on every segment that passes through warming, so a pipeline that took the
     * quantised path whenever one existed would change how it answers the moment a segment
     * finished warming, with nobody having asked. Every other constructor leaves it off.</p>
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
     * @param pqSearch Whether a segment holding a trained quantizer may be searched through it
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
            EpochIsolationValidator epochIsolationValidator,
            PqSearchSettings pqSearch) {

        // Dispatcher and pipeline executor are lazily initialized on first use
        this(storageEngine, visibilityTracker, visibilityChecker, graphProvider, distanceFunction,
            llmScorer, coverageThreshold, hierarchicalFilter, epochIsolationValidator, pqSearch,
            null, null);
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
            PqSearchSettings pqSearch,
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
        this.pqSearch = Objects.requireNonNull(pqSearch, "PqSearchSettings cannot be null");
        this.termFilterCascade = new TermFilterCascade(hierarchicalFilter);
        this.epochIsolationValidator = epochIsolationValidator;
        this.payloadResolver = new ResultPayloadResolver(this.storageEngine);
        this.coverageCounts = new CoverageCounts(this.storageEngine);
        this.corpusFingerprint = new CorpusFingerprint(this.storageEngine,
            this.visibilityTracker, this.graphProvider);
        this.epochSnapshotStage = new EpochSnapshotStage(this.storageEngine,
            this.visibilityTracker, this.graphProvider);
        this.hnswSearchStage = new HnswSearchStage(this::getDispatcher, this.distanceFunction,
            this.epochIsolationValidator, this::llmScoringWillRun, this.pqSearch);
        this.rerankStage = new RerankStage(this.distanceFunction, this.coverageThreshold,
            this.visibilityChecker, this::llmScoringWillRun);
        this.llmScoringStage = new LlmScoringStage(this.llmScorer, this.payloadResolver,
            this::llmScoringWillRun);
        this.responseAssembler = new ResponseAssembler(this.coverageCounts, this.coverageThreshold,
            new EmptyResultDiagnostics(this.storageEngine, this.visibilityTracker,
                this.graphProvider, this::getPipelineExecutor));
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
                // Inside the lock, so it cannot be read before shutdown() sets it and acted on
                // after shutdown() has finished looking for an executor to stop.
                if (shutdown.get()) {
                    throw new java.util.concurrent.RejectedExecutionException(
                        "QueryPipeline has been shut down");
                }
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
     * @param llmScorer The LLM scorer to use for Stage 5, or null to disable
     * @return A new QueryPipeline instance with the specified LLM scorer
     */
    public QueryPipeline withLlmScorer(LlmScorer llmScorer) {
        return copyWith(llmScorer, this.visibilityChecker);
    }

    /**
     * Returns a new QueryPipeline with the specified visibility checker.
     *
     * <p>This method creates a new QueryPipeline instance with the provided visibility checker,
     * preserving all other configuration. Use this to control document atomicity filtering.
     * When visibilityChecker is null, document atomicity filtering is disabled.</p>
     *
     * @param visibilityChecker The visibility checker for document atomicity, or null to disable
     * @return A new QueryPipeline instance with the specified visibility checker
     */
    public QueryPipeline withVisibilityChecker(DocumentVisibilityChecker visibilityChecker) {
        return copyWith(this.llmScorer, visibilityChecker);
    }

    /**
     * Copies this pipeline with one collaborator replaced.
     *
     * <p>The dispatcher and the pipeline executor are carried across rather than re-created,
     * so reconfiguring a pipeline that has already served a query does not strand the threads
     * it started.</p>
     *
     * @param scorer The LLM scorer the copy uses, or null to disable Stage 5
     * @param checker The visibility checker the copy uses, or null to disable atomicity
     *                filtering
     * @return The reconfigured copy
     */
    private QueryPipeline copyWith(LlmScorer scorer, DocumentVisibilityChecker checker) {
        return new QueryPipeline(this.storageEngine, this.visibilityTracker, checker,
            this.graphProvider, this.distanceFunction, scorer, this.coverageThreshold,
            this.hierarchicalFilter, this.epochIsolationValidator, this.pqSearch,
            this.dispatcher, this.pipelineExecutor);
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
        // Set first, and under the same lock the lazy creation takes, so a query racing this
        // either creates its executor before the stop below finds it or is refused outright.
        synchronized (this) {
            shutdown.set(true);
        }
        try {
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
        } finally {
            // In a finally, because the executor is the half that owns threads. Shutting the
            // dispatcher down first is the right order - it is what submits the work - but with
            // this outside a finally a dispatcher that failed on the way down left a pool of
            // pipeline threads running for the life of the process.
            stopPipelineExecutor();
        }
    }

    /**
     * Stops the executor queries run on, and waits for them.
     *
     * <p>Asked first and insisted on second. A query that is most of the way through has a
     * partial answer worth returning, and interrupting it outright turns an answer the caller
     * was about to receive into a failure - which is the wrong trade for the fraction of a
     * second it saves. The grace is small and bounded, because a node that is stopping has to
     * stop; past it the threads are interrupted.</p>
     *
     * <p>Both waits are awaited rather than assumed. Whoever calls {@code shutdown()} goes on to
     * close the storage engine these threads read through, so returning before they have stopped
     * is what turns an orderly shutdown into reads against a closed engine.</p>
     */
    private void stopPipelineExecutor() {
        ExecutorService executor = pipelineExecutor;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.debug("QueryPipeline shutdown complete");
                return;
            }
            executor.shutdownNow();
            if (!executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                // Said out loud: the caller is about to close resources these threads are still
                // reading through, and silence here is what makes that look like a storage bug.
                LOGGER.warn("QueryPipeline threads did not stop within {}ms of being interrupted",
                    SHUTDOWN_GRACE_MILLIS);
            }
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the thread that is shutting the node down
            // unable to observe that it was itself interrupted.
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether this pipeline has been shut down and its threads have stopped.
     *
     * @return true once {@link #shutdown()} has been called and the threads it owns have
     *         terminated; false before it is called, and false if they outlasted the grace
     *         period
     */
    public boolean isShutdown() {
        if (!shutdown.get()) {
            return false;
        }
        ExecutorService executor = pipelineExecutor;
        // isTerminated, not isShutdown: the latter is true the instant shutdown() is called,
        // so a pipeline whose threads outlasted the grace period - which is logged as a
        // warning - would still report itself stopped while they were demonstrably running.
        return executor == null || executor.isTerminated();
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
        QueryDeadline deadline = QueryDeadline.from(startTime, request.timeoutMs());
        String correlationId = request.correlationId();

        // Set correlation ID in MDC for structured logging
        StructuredLoggingHelper.setCorrelationId(correlationId);

        LOGGER.debug("Starting query pipeline");

        // Run the pipeline on the pipeline executor, keeping the underlying task handle so
        // a timeout can actually interrupt the work instead of just abandoning it.
        PipelineRun run = PipelineRun.start(getPipelineExecutor(),
            partialContext -> runStages(request, token, deadline, partialContext));

        // Apply timeout
        CompletableFuture<PipelineContext> timeoutFuture = run.result()
            .orTimeout(request.timeoutMs(), TimeUnit.MILLISECONDS);

        // Handle result
        return timeoutFuture.handle((context, error) ->
            responseAssembler.finish(request, token, run, context, error,
                (System.nanoTime() - startTime) / 1_000_000));
    }

    /**
     * Runs every pipeline stage in order, publishing each stage's context so that a
     * timeout or cancellation can still return the results computed so far.
     *
     * @param request The query request
     * @param token The cancellation token
     * @param deadline When this query is abandoned, which the stages that dispatch work of
     *                 their own must finish inside of
     * @param partialContext Holder updated after each completed stage
     * @return The final pipeline context
     */
    private PipelineContext runStages(
            QueryRequest request,
            CancellationToken token,
            QueryDeadline deadline,
            AtomicReference<PipelineContext> partialContext) {

        // Stage 0: Epoch Snapshot
        PipelineContext context = epochSnapshotStage.capture(request, token);
        partialContext.set(context);

        // Stage 0b: Selectivity Estimation and Segment Prioritization
        context = SegmentPrioritizer.prioritize(context, token);
        partialContext.set(context);

        // Stage 1: Cuckoo Filter Cascade
        context = termFilterCascade.apply(context, token);
        partialContext.set(context);

        // Stage 2: LSH Pre-filter
        context = LshPreFilter.apply(context, token);
        partialContext.set(context);

        // Stage 3: HNSW Vector Search with Tail Scan
        context = hnswSearchStage.search(context, token, deadline);
        partialContext.set(context);

        // Stage 4: Full Vector Rerank
        context = rerankStage.rerank(context, token);
        partialContext.set(context);

        // Stage 5: LLM Relevancy Scoring (optional)
        context = llmScoringStage.score(context, token);
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

    /**
     * Returns whether Stage 5 will actually score this request.
     *
     * <p>The single definition, handed to the three stages that must agree on it exactly:
     * Stage 3 widens the margin it leaves behind when scoring follows, Stage 4 decides whether
     * it may truncate to topK or must leave spare candidates for Stage 5's threshold filter,
     * and Stage 5 decides whether to score at all. A second copy of this rule would let them
     * disagree, and every way they can disagree silently returns fewer results than asked
     * for.</p>
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
    static List<QueryResultItem> truncate(List<QueryResultItem> results, int limit) {
        if (results.size() <= limit) {
            return results;
        }
        return new ArrayList<>(results.subList(0, limit));
    }

    /**
     * A coverage builder carrying what the index holds and nothing about any query.
     *
     * <p>For a caller that has to answer without a query having run - a request abandoned
     * before it reached the pipeline, or one the pipeline never came back from. Such a response
     * still has to say what the index holds: reporting no segments at all is indistinguishable
     * from an empty index.</p>
     *
     * @return A builder with the segment-state counts filled in, for the caller to finish
     */
    public IndexCoverage.Builder segmentCensus() {
        return coverageCounts.census().onto(IndexCoverage.builder());
    }

    /**
     * A fingerprint of everything a query starting now would read.
     *
     * <p>For the query result cache, which may serve an earlier answer only to a request
     * that observes the same fingerprint. It is deliberately not the visibility epoch: the
     * epoch moves on a timer when nothing has changed, and stands still when segment
     * compaction or graph integration has changed what a search returns. See
     * {@link com.eonmux.excalibur.query.cache.CorpusVersion} for the argument in full, and
     * {@code CorpusFingerprint} for what is read and why.</p>
     *
     * <p>Cheap: a set of visible segment ids and a fixed number of field reads per segment,
     * with no locks and no file access. Never throws - a corpus that cannot be read is
     * reported as {@link com.eonmux.excalibur.query.cache.CorpusVersion#UNREADABLE}, which the cache
     * neither serves from nor stores to.</p>
     *
     * @return The current corpus fingerprint, never null
     */
    public com.eonmux.excalibur.query.cache.CorpusVersion corpusVersion() {
        return corpusFingerprint.current();
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
}
