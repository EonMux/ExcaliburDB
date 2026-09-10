package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Dispatches queries in parallel to multiple segments using virtual threads.
 *
 * <p>ParallelQueryDispatcher is responsible for:</p>
 * <ul>
 *   <li>Identifying queryable segments based on lifecycle state</li>
 *   <li>Dispatching search tasks in parallel using virtual threads</li>
 *   <li>Collecting results with timeout enforcement</li>
 *   <li>Aggregating results and computing coverage metadata</li>
 * </ul>
 *
 * <h2>Segment Selection</h2>
 * <p>Queryable segments are those in states: INDEXING, WARMING, ACTIVE, COMPACTING.
 * Segments in BOOTSTRAPPING, TOMBSTONED, or PURGED states are excluded.</p>
 *
 * <h2>Timeout Handling</h2>
 * <p>Queries that exceed the configured timeout return partial results with
 * the degraded flag set. Segments that time out are counted in the coverage
 * metadata.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Multiple queries can be dispatched concurrently.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class ParallelQueryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelQueryDispatcher.class);

    /**
     * Default timeout for query execution.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Default coverage threshold for degraded state.
     */
    public static final double DEFAULT_COVERAGE_THRESHOLD = 0.5;

    private final SegmentCatalog catalog;
    private final Map<Long, Segment> segments;
    private final HnswGraphProvider graphProvider;
    private final ExecutorService executor;

    /**
     * Creates a new ParallelQueryDispatcher.
     *
     * @param catalog The segment catalog
     * @param segments Map of segment ID to segment instance
     * @param graphProvider Provider for HNSW graphs by segment ID
     * @throws NullPointerException if any parameter is null
     */
    public ParallelQueryDispatcher(
            SegmentCatalog catalog,
            Map<Long, Segment> segments,
            HnswGraphProvider graphProvider) {

        this.catalog = Objects.requireNonNull(catalog, "Catalog cannot be null");
        this.segments = new ConcurrentHashMap<>(Objects.requireNonNull(segments, "Segments cannot be null"));
        this.graphProvider = Objects.requireNonNull(graphProvider, "GraphProvider cannot be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Dispatches a query to all queryable segments.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Identifies queryable segments based on lifecycle state</li>
     *   <li>Creates SegmentQueryTask for each segment</li>
     *   <li>Dispatches tasks in parallel using virtual threads</li>
     *   <li>Collects results with timeout enforcement</li>
     *   <li>Aggregates results using ResultAggregator</li>
     * </ol>
     *
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function
     * @return QueryResponse with results and coverage
     */
    public QueryResponse dispatch(
            float[] queryVector,
            QueryOptions options,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(options, "Query options cannot be null");
        Objects.requireNonNull(distanceFunction, "Distance function cannot be null");

        long startTime = System.nanoTime();

        // Get queryable segments
        SegmentSelection selection = getQueryableSegments(options.includePartialSegments());
        List<SegmentInfo> queryableSegments = selection.searchable();

        if (queryableSegments.isEmpty()) {
            LOGGER.debug("No queryable segments available");
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(catalog.size())
                .segmentsExcluded(selection.excluded())
                .computeCoverage()
                .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
                .build();
            return QueryResponse.empty(coverage, 0);
        }

        LOGGER.trace("Dispatching query to {} segments", queryableSegments.size());

        // Create tasks for each segment
        List<Callable<SegmentQueryTask.SegmentQueryResult>> tasks = new ArrayList<>();
        for (SegmentInfo info : queryableSegments) {
            SegmentQueryTask task = new SegmentQueryTask(
                info.segmentId,
                info.segment,
                info.graph,
                queryVector,
                options.topK(),
                options.oversamplingFactor(),
                distanceFunction
            );
            tasks.add(task);
        }

        // Dispatch tasks in parallel with timeout
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
        List<Future<SegmentQueryTask.SegmentQueryResult>> futures;

        try {
            futures = executor.invokeAll(tasks, options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Query dispatch interrupted");
            return createErrorResponse(options, System.nanoTime() - startTime);
        }

        // Collect results
        int segmentsTimedOut = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<SegmentQueryTask.SegmentQueryResult> future = futures.get(i);
            SegmentInfo segmentInfo = queryableSegments.get(i);

            try {
                if (future.isDone()) {
                    SegmentQueryTask.SegmentQueryResult result = future.get();
                    segmentResults.add(result);
                } else {
                    // Timeout - create error result
                    segmentsTimedOut++;
                    segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                        segmentInfo.segmentId,
                        List.of(),
                        0,
                        options.timeout().toMillis(),
                        false,
                        "Timeout"
                    ));
                    future.cancel(true);
                }
            } catch (ExecutionException e) {
                LOGGER.warn("Segment {} query failed: {}", segmentInfo.segmentId, e.getCause().getMessage());
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    e.getCause().getMessage()
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    "Interrupted"
                ));
            } catch (java.util.concurrent.CancellationException e) {
                // Future was cancelled due to invokeAll timeout
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    "Timeout"
                ));
            }
        }

        // Aggregate results
        ResultAggregator aggregator = new ResultAggregator(options);
        List<QueryResultItem> mergedResults = aggregator.mergeAndRank(segmentResults);

        // Build coverage
        IndexCoverage coverage = buildCoverage(segmentResults, segmentsTimedOut, selection.excluded());

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.trace("Query completed: {} results, {}ms, coverage={}",
            mergedResults.size(), durationMs, coverage.estimatedCorpusCoverage());

        return new QueryResponse(mergedResults, coverage, durationMs);
    }

    /**
     * Gets all segments that are queryable, with a count of the ones held back.
     *
     * <p>Queryable segments are in INDEXING, WARMING, ACTIVE, or COMPACTING states. When
     * {@code includePartial} is false the partially-indexed ones are dropped - that is INDEXING
     * alone, since a warming segment has every entry in and a compacting one serves a frozen
     * snapshot, and both answer a query exactly as an active segment does.</p>
     *
     * <p>A segment the catalog counts as live corpus and this query does not read is a gap in the
     * answer. It is counted here so the response can say so; a dropped segment is not an error
     * anywhere else, and coverage taken from the catalog alone cannot see it.</p>
     *
     * @param includePartial Whether to include partially-indexed segments
     * @return The segments to search and the number withheld from this query
     */
    private SegmentSelection getQueryableSegments(boolean includePartial) {
        List<SegmentInfo> queryable = new ArrayList<>();
        int excluded = 0;

        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            long segmentId = entry.getKey();
            Segment segment = entry.getValue();

            // Check lifecycle state
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) {
                continue;
            }

            LifecycleState state = metadata.getLifecycleState();

            // Check if queryable
            if (!state.isQueryable()) {
                continue;
            }

            // If not including partial, hold back the segments still taking entries
            if (!includePartial && state.isPartiallyIndexed()) {
                excluded++;
                continue;
            }

            // Get HNSW graph
            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph == null) {
                LOGGER.trace("No graph available for segment {}", segmentId);
                excluded++;
                continue;
            }

            queryable.add(new SegmentInfo(segmentId, segment, graph));
        }

        return new SegmentSelection(queryable, excluded);
    }

    /**
     * The segments a query may search, and how many of the live corpus it may not.
     *
     * @param searchable The segments to fan out to
     * @param excluded How many queryable segments were withheld from this query
     */
    private record SegmentSelection(List<SegmentInfo> searchable, int excluded) {}

    /**
     * Builds index coverage from segment results.
     *
     * @param segmentResults One result per segment the query fanned out to
     * @param segmentsTimedOut How many of those did not answer in time
     * @param segmentsExcluded How many queryable segments the query never reached
     */
    private IndexCoverage buildCoverage(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            int segmentsTimedOut,
            int segmentsExcluded) {

        long totalSegments = catalog.size();
        long activeSegments = 0;
        long indexingSegments = 0;
        long warmingSegments = 0;
        long compactingSegments = 0;
        long tombstonedSegments = 0;
        long totalTailScanEntries = 0;
        int segmentsSearched = 0;
        int segmentsWithErrors = 0;

        // Count segment states
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

        // Count search results
        for (SegmentQueryTask.SegmentQueryResult result : segmentResults) {
            if (result.success()) {
                segmentsSearched++;
                totalTailScanEntries += result.tailScanEntries();
            } else {
                segmentsWithErrors++;
            }
        }

        return IndexCoverage.builder()
            .totalSegments(totalSegments)
            .activeSegments(activeSegments)
            .indexingSegments(indexingSegments)
            .warmingSegments(warmingSegments)
            .compactingSegments(compactingSegments)
            .tombstonedSegments(tombstonedSegments)
            .tailScanEntries(totalTailScanEntries)
            .segmentsSearched(segmentsSearched)
            .segmentsExcluded(segmentsExcluded)
            .segmentsWithErrors(segmentsWithErrors)
            .segmentsTimedOut(segmentsTimedOut)
            .computeCoverage()
            .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
            .build();
    }

    /**
     * Creates an error response for failed queries.
     */
    private QueryResponse createErrorResponse(QueryOptions options, long durationNanos) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .computeCoverage()
            .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
            .build();

        return new QueryResponse(
            List.of(),
            coverage,
            durationNanos / 1_000_000
        );
    }

    /**
     * Dispatches a query to all queryable segments and returns raw segment results.
     *
     * <p>This method is similar to {@link #dispatch(float[], QueryOptions, DistanceFunction)}
     * but returns the raw segment results alongside the merged results. This is useful
     * for pipelines that need access to per-segment results for subsequent stages
     * (e.g., reranking, LLM scoring).</p>
     *
     * <p>Every queryable segment is searched whole. Narrowing one to a pre-filter's candidates is
     * {@link #dispatchToSpecificSegments(List, float[], QueryOptions, DistanceFunction)}, which
     * takes the set per segment: HNSW node ids are dense and start at zero in every graph, so a
     * set built against one segment names entirely different vectors in the next.</p>
     *
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function
     * @return DispatchResult with the raw per-segment results and coverage
     */
    public DispatchResult dispatchRaw(
            float[] queryVector,
            QueryOptions options,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(options, "Query options cannot be null");
        Objects.requireNonNull(distanceFunction, "Distance function cannot be null");

        long startTime = System.nanoTime();

        // Get queryable segments
        SegmentSelection selection = getQueryableSegments(options.includePartialSegments());
        List<SegmentInfo> queryableSegments = selection.searchable();

        if (queryableSegments.isEmpty()) {
            LOGGER.debug("No queryable segments available");
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(catalog.size())
                .segmentsExcluded(selection.excluded())
                .computeCoverage()
                .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
                .build();
            return new DispatchResult(
                List.of(),
                coverage,
                0
            );
        }

        LOGGER.trace("Dispatching query to {} segments", queryableSegments.size());

        List<Callable<SegmentQueryTask.SegmentQueryResult>> tasks = new ArrayList<>();
        for (SegmentInfo info : queryableSegments) {
            SegmentQueryTask task = new SegmentQueryTask(
                info.segmentId(),
                info.segment(),
                info.graph(),
                queryVector,
                options.topK(),
                options.oversamplingFactor(),
                distanceFunction,
                info.lshCandidates()
            );
            tasks.add(task);
        }

        // Dispatch tasks in parallel with timeout
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
        List<Future<SegmentQueryTask.SegmentQueryResult>> futures;

        try {
            futures = executor.invokeAll(tasks, options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Query dispatch interrupted");
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(catalog.size())
                .segmentsExcluded(selection.excluded())
                .degraded(true)
                .computeCoverage()
                .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
                .build();
            return new DispatchResult(
                List.of(),
                coverage,
                (System.nanoTime() - startTime) / 1_000_000
            );
        }

        // Collect results
        int segmentsTimedOut = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<SegmentQueryTask.SegmentQueryResult> future = futures.get(i);
            SegmentInfo segmentInfo = queryableSegments.get(i);

            try {
                if (future.isDone()) {
                    SegmentQueryTask.SegmentQueryResult result = future.get();
                    segmentResults.add(result);
                } else {
                    // Timeout - create error result
                    segmentsTimedOut++;
                    segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                        segmentInfo.segmentId,
                        List.of(),
                        0,
                        options.timeout().toMillis(),
                        false,
                        "Timeout"
                    ));
                    future.cancel(true);
                }
            } catch (ExecutionException e) {
                LOGGER.warn("Segment {} query failed: {}", segmentInfo.segmentId, e.getCause().getMessage());
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    e.getCause().getMessage()
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    "Interrupted"
                ));
            } catch (java.util.concurrent.CancellationException e) {
                // Future was cancelled due to invokeAll timeout
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId,
                    List.of(),
                    0,
                    0,
                    false,
                    "Timeout"
                ));
            }
        }

        // Build coverage
        IndexCoverage coverage = buildCoverage(segmentResults, segmentsTimedOut, selection.excluded());

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.trace("Query completed: {} segments, {}ms, coverage={}",
            segmentResults.size(), durationMs, coverage.estimatedCorpusCoverage());

        return new DispatchResult(
            segmentResults,
            coverage,
            durationMs
        );
    }

    /**
     * Result of a parallel dispatch operation.
     *
     * <p>Per-segment results, unmerged. Merging belongs to the caller, because only the caller
     * knows what scale the scores have to end up on: {@link excalibur.query.pipeline.QueryPipeline}
     * normalises each distance to [0, 1] before it ranks or filters, and a merge performed here
     * would either be discarded or, worse, applied a second time on the raw distance scale.</p>
     */
    public static final class DispatchResult {
        private final List<SegmentQueryTask.SegmentQueryResult> segmentResults;
        private final IndexCoverage coverage;
        private final long durationMs;

        public DispatchResult(
                List<SegmentQueryTask.SegmentQueryResult> segmentResults,
                IndexCoverage coverage,
                long durationMs) {
            this.segmentResults = segmentResults != null
                ? List.copyOf(segmentResults)
                : List.of();
            this.coverage = coverage;
            this.durationMs = durationMs;
        }

        /**
         * Returns the raw results from each segment.
         */
        public List<SegmentQueryTask.SegmentQueryResult> segmentResults() {
            return segmentResults;
        }

        /**
         * Returns the index coverage information.
         */
        public IndexCoverage coverage() {
            return coverage;
        }

        /**
         * Returns the total duration in milliseconds.
         */
        public long durationMs() {
            return durationMs;
        }
    }

    /**
     * Shuts down the dispatcher.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Registers a new segment for querying.
     *
     * @param segmentId The segment ID
     * @param segment The segment instance
     */
    public void registerSegment(long segmentId, Segment segment) {
        segments.put(segmentId, segment);
    }

    /**
     * Unregisters a segment from querying.
     *
     * @param segmentId The segment ID
     */
    public void unregisterSegment(long segmentId) {
        segments.remove(segmentId);
    }

    /**
     * Returns the number of registered segments.
     */
    public int segmentCount() {
        return segments.size();
    }

    /**
     * Provider interface for HNSW graphs.
     */
    @FunctionalInterface
    public interface HnswGraphProvider {
        /**
         * Returns the HNSW graph for a segment.
         *
         * @param segmentId The segment ID
         * @return The HNSW graph, or null if not available
         */
        HnswGraph getGraph(long segmentId);
    }

    /**
     * Dispatches a query to specific segments and returns raw segment results.
     *
     * <p>This method is designed for use by {@link excalibur.query.pipeline.QueryPipeline}
     * when it has already filtered segments through earlier pipeline stages
     * (visibility tracking, Cuckoo filter cascade, LSH pre-filtering).</p>
     *
     * <p>Unlike {@link #dispatchRaw(float[], QueryOptions, DistanceFunction)},
     * this method accepts specific segments to search rather than determining
     * queryable segments automatically.</p>
     *
     * @param segmentsToSearch The segments to search, each with the graph to search it in and the
     *                         node ids a pre-filter narrowed it to, or a null set for a whole-graph
     *                         search
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function
     * @return DispatchResult with the raw per-segment results and coverage
     */
    public DispatchResult dispatchToSpecificSegments(
            List<SegmentInfo> segmentsToSearch,
            float[] queryVector,
            QueryOptions options,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(segmentsToSearch, "Segments to search cannot be null");
        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(options, "Query options cannot be null");
        Objects.requireNonNull(distanceFunction, "Distance function cannot be null");

        long startTime = System.nanoTime();

        if (segmentsToSearch.isEmpty()) {
            LOGGER.debug("No segments to search");
            IndexCoverage coverage = IndexCoverage.builder()
                .computeCoverage()
                .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
                .build();
            return new DispatchResult(
                List.of(),
                coverage,
                0
            );
        }

        LOGGER.trace("Dispatching query to {} specific segments", segmentsToSearch.size());

        // Create tasks for each segment
        List<Callable<SegmentQueryTask.SegmentQueryResult>> tasks = new ArrayList<>();
        for (SegmentInfo info : segmentsToSearch) {
            SegmentQueryTask task = new SegmentQueryTask(
                info.segmentId(),
                info.segment(),
                info.graph(),
                queryVector,
                options.topK(),
                options.oversamplingFactor(),
                distanceFunction,
                info.lshCandidates()
            );
            tasks.add(task);
        }

        // Dispatch tasks in parallel with timeout
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
        List<Future<SegmentQueryTask.SegmentQueryResult>> futures;

        try {
            futures = executor.invokeAll(tasks, options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Query dispatch interrupted");
            IndexCoverage coverage = IndexCoverage.builder()
                .degraded(true)
                .computeCoverage()
                .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
                .build();
            return new DispatchResult(
                List.of(),
                coverage,
                (System.nanoTime() - startTime) / 1_000_000
            );
        }

        // Collect results
        int segmentsTimedOut = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<SegmentQueryTask.SegmentQueryResult> future = futures.get(i);
            SegmentInfo segmentInfo = segmentsToSearch.get(i);

            try {
                if (future.isDone()) {
                    SegmentQueryTask.SegmentQueryResult result = future.get();
                    segmentResults.add(result);
                } else {
                    // Timeout - create error result
                    segmentsTimedOut++;
                    segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                        segmentInfo.segmentId(),
                        List.of(),
                        0,
                        options.timeout().toMillis(),
                        false,
                        "Timeout"
                    ));
                    future.cancel(true);
                }
            } catch (ExecutionException e) {
                LOGGER.warn("Segment {} query failed: {}", segmentInfo.segmentId(), e.getCause().getMessage());
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId(),
                    List.of(),
                    0,
                    0,
                    false,
                    e.getCause().getMessage()
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId(),
                    List.of(),
                    0,
                    0,
                    false,
                    "Interrupted"
                ));
            } catch (java.util.concurrent.CancellationException e) {
                // Future was cancelled due to invokeAll timeout
                segmentsTimedOut++;
                segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                    segmentInfo.segmentId(),
                    List.of(),
                    0,
                    0,
                    false,
                    "Timeout"
                ));
            }
        }

        // Build coverage
        IndexCoverage coverage = buildCoverageForSpecificSegments(segmentResults, segmentsTimedOut);

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.trace("Query completed: {} segments, {}ms, coverage={}",
            segmentResults.size(), durationMs, coverage.estimatedCorpusCoverage());

        return new DispatchResult(
            segmentResults,
            coverage,
            durationMs
        );
    }

    /**
     * Builds index coverage from segment results for specific segment searches.
     *
     * <p>Unlike {@link #buildCoverage(List, int, int)}, this method doesn't count
     * segments by lifecycle state since we're only searching specific segments.</p>
     */
    private IndexCoverage buildCoverageForSpecificSegments(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            int segmentsTimedOut) {

        long totalSegments = catalog.size();
        long totalTailScanEntries = 0;
        int segmentsSearched = 0;
        int segmentsWithErrors = 0;

        // Count search results
        for (SegmentQueryTask.SegmentQueryResult result : segmentResults) {
            if (result.success()) {
                segmentsSearched++;
                totalTailScanEntries += result.tailScanEntries();
            } else {
                segmentsWithErrors++;
            }
        }

        return IndexCoverage.builder()
            .totalSegments(totalSegments)
            .tailScanEntries(totalTailScanEntries)
            .segmentsSearched(segmentsSearched)
            .segmentsWithErrors(segmentsWithErrors)
            .segmentsTimedOut(segmentsTimedOut)
            .computeCoverage()
            .computeDegraded(DEFAULT_COVERAGE_THRESHOLD)
            .build();
    }

    /**
     * Creates a SegmentInfo for use with {@link #dispatchToSpecificSegments}, searching the whole
     * graph.
     *
     * @param segmentId The segment ID
     * @param segment The segment instance
     * @param graph The HNSW graph for the segment
     * @return A new SegmentInfo instance
     */
    public static SegmentInfo createSegmentInfo(long segmentId, Segment segment, HnswGraph graph) {
        return new SegmentInfo(segmentId, segment, graph, null);
    }

    /**
     * Creates a SegmentInfo carrying the node ids a pre-filter narrowed this segment to.
     *
     * @param segmentId The segment ID
     * @param segment The segment instance
     * @param graph The HNSW graph for the segment
     * @param lshCandidates The node ids the pre-filter named for this segment, or null when no
     *                      pre-filter ran and the whole graph is to be searched
     * @return A new SegmentInfo instance
     */
    public static SegmentInfo createSegmentInfo(long segmentId, Segment segment, HnswGraph graph,
                                                Set<Long> lshCandidates) {
        return new SegmentInfo(segmentId, segment, graph, lshCandidates);
    }

    /**
     * A segment to search, and the node ids a pre-filter has already narrowed it to.
     *
     * <p>{@code lshCandidates} is what the LSH stage decided about this particular segment, and it
     * has to be carried per segment rather than shared: each segment has its own index, its own
     * probe, and its own node id space. A null set means no pre-filter ran and the whole graph is
     * to be walked; that is the only reading of "no candidates" that can be acted on, since an
     * empty set is the pre-filter's way of saying the segment holds nothing worth visiting.</p>
     *
     * @param segmentId The routing index the storage engine holds the segment at
     * @param segment The segment to search
     * @param graph The segment's HNSW graph
     * @param lshCandidates The node ids the pre-filter named, or null when it did not run
     */
    public record SegmentInfo(long segmentId, Segment segment, HnswGraph graph,
                              Set<Long> lshCandidates) {

        /**
         * A segment to search in full, with no pre-filter narrowing it.
         *
         * @param segmentId The routing index the storage engine holds the segment at
         * @param segment The segment to search
         * @param graph The segment's HNSW graph
         */
        public SegmentInfo(long segmentId, Segment segment, HnswGraph graph) {
            this(segmentId, segment, graph, null);
        }
    }
}
