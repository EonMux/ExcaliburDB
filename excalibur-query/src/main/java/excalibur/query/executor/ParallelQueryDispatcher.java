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
        List<SegmentInfo> queryableSegments = getQueryableSegments(options.includePartialSegments());

        if (queryableSegments.isEmpty()) {
            LOGGER.debug("No queryable segments available");
            IndexCoverage coverage = IndexCoverage.builder()
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
            }
        }

        // Aggregate results
        ResultAggregator aggregator = new ResultAggregator(options);
        List<QueryResultItem> mergedResults = aggregator.mergeAndRank(segmentResults);

        // Build coverage
        IndexCoverage coverage = buildCoverage(segmentResults, segmentsTimedOut);

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.trace("Query completed: {} results, {}ms, coverage={}",
            mergedResults.size(), durationMs, coverage.estimatedCorpusCoverage());

        return new QueryResponse(mergedResults, coverage, durationMs);
    }

    /**
     * Gets all segments that are queryable.
     *
     * <p>Queryable segments are in INDEXING, WARMING, ACTIVE, or COMPACTING states.
     * If includePartial is false, only ACTIVE segments are included.</p>
     *
     * @param includePartial Whether to include partially-indexed segments
     * @return List of queryable segment info
     */
    private List<SegmentInfo> getQueryableSegments(boolean includePartial) {
        List<SegmentInfo> queryable = new ArrayList<>();

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

            // If not including partial, only ACTIVE segments
            if (!includePartial && state != LifecycleState.ACTIVE) {
                continue;
            }

            // Get HNSW graph
            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph == null) {
                LOGGER.trace("No graph available for segment {}", segmentId);
                continue;
            }

            queryable.add(new SegmentInfo(segmentId, segment, graph));
        }

        return queryable;
    }

    /**
     * Builds index coverage from segment results.
     */
    private IndexCoverage buildCoverage(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            int segmentsTimedOut) {

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
     * Internal class holding segment information for querying.
     */
    private record SegmentInfo(long segmentId, Segment segment, HnswGraph graph) {}
}
