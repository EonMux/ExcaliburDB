package com.eonmux.excalibur.query.executor;

import com.eonmux.excalibur.query.QueryOptions;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
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
     * What one fan-out observed.
     *
     * <p>Per-segment results, unmerged. Merging belongs to the caller, because only the caller
     * knows what scale the scores have to end up on: {@link com.eonmux.excalibur.query.pipeline.QueryPipeline}
     * normalises each distance to [0, 1] before it ranks or filters, and a merge performed here
     * would either be discarded or, worse, applied a second time on the raw distance scale.</p>
     *
     * <p>Coverage is not reported here either, and for the same reason. Corpus coverage is a
     * statement about the whole catalog against the segments a query was allowed to read, and the
     * dispatcher has seen only the list it was handed - a coverage figure computed from that would
     * describe the caller's selection back to it as though it were the index. The two facts the
     * dispatcher alone knows are below; the caller builds the census.</p>
     *
     * @param segmentResults   One result per segment searched, in the order dispatched
     * @param tailScanEntries  Write-log entries the searches scanned beyond the graphs
     * @param segmentsTimedOut How many segments did not answer within the query's timeout
     * @param durationMs       Wall-clock time the fan-out took
     */
    public record DispatchResult(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            long tailScanEntries,
            int segmentsTimedOut,
            long durationMs) {

        public DispatchResult {
            segmentResults = segmentResults == null ? List.of() : List.copyOf(segmentResults);
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
     * <p>The dispatcher fans out; it does not decide what to fan out to. Which segments a query
     * may read is settled by {@link com.eonmux.excalibur.query.pipeline.QueryPipeline}'s earlier stages -
     * visibility, lifecycle state, the Cuckoo filter cascade, LSH pre-filtering - and arrives
     * here already chosen, so there is one answer to "was this segment searched" rather than two
     * that can drift apart.</p>
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

        return dispatchToSpecificSegments(segmentsToSearch, queryVector, options,
            distanceFunction, PqSearchSettings.DISABLED);
    }

    /**
     * Dispatches a query that may be answered from each segment's product quantizer.
     *
     * @param segmentsToSearch The segments to search, each with the graph to search it in and the
     *                         node ids a pre-filter narrowed it to, or a null set for a whole-graph
     *                         search
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function
     * @param pqSearch Whether a segment holding a trained quantizer may be searched through it,
     *                 and how widely
     * @return DispatchResult with the raw per-segment results and coverage
     */
    public DispatchResult dispatchToSpecificSegments(
            List<SegmentInfo> segmentsToSearch,
            float[] queryVector,
            QueryOptions options,
            DistanceFunction distanceFunction,
            PqSearchSettings pqSearch) {

        Objects.requireNonNull(segmentsToSearch, "Segments to search cannot be null");
        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(options, "Query options cannot be null");
        Objects.requireNonNull(distanceFunction, "Distance function cannot be null");
        Objects.requireNonNull(pqSearch, "PQ search settings cannot be null");

        long startTime = System.nanoTime();

        if (segmentsToSearch.isEmpty()) {
            LOGGER.debug("No segments to search");
            return new DispatchResult(List.of(), 0L, 0, 0L);
        }

        LOGGER.trace("Dispatching query to {} specific segments", segmentsToSearch.size());

        List<Callable<SegmentQueryTask.SegmentQueryResult>> tasks =
            searchTasks(segmentsToSearch, queryVector, options, distanceFunction, pqSearch);

        // Dispatch tasks in parallel with timeout
        List<Future<SegmentQueryTask.SegmentQueryResult>> futures;
        try {
            futures = executor.invokeAll(tasks, options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Query dispatch interrupted");
            return nothingSearched(segmentsToSearch.size(), startTime);
        }

        SegmentAnswers answers = collectAnswers(futures, segmentsToSearch, options);

        long tailScanEntries = tailScanEntries(answers.results());
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.trace("Query completed: {} segments, {}ms, {} timed out",
            answers.results().size(), durationMs, answers.segmentsTimedOut());

        return new DispatchResult(answers.results(), tailScanEntries,
            answers.segmentsTimedOut(), durationMs);
    }

    /**
     * Builds one search task per segment.
     *
     * @param segmentsToSearch The segments to search, each with the graph to search it in and
     *                         the node ids a pre-filter narrowed it to
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function
     * @param pqSearch Whether a segment holding a trained quantizer may be searched through it
     * @return The tasks to run in parallel
     */
    private static List<Callable<SegmentQueryTask.SegmentQueryResult>> searchTasks(
            List<SegmentInfo> segmentsToSearch,
            float[] queryVector,
            QueryOptions options,
            DistanceFunction distanceFunction,
            PqSearchSettings pqSearch) {

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
                info.lshCandidates(),
                pqSearch
            );
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * Gathers what each segment returned, standing in for the ones that did not answer.
     *
     * <p>Every future is accounted for, in the order the segments were dispatched in, so that a
     * segment which errored or ran out of time is reported as unanswered rather than dropped.
     * A dropped segment is indistinguishable from one that was searched and held nothing.</p>
     *
     * @param futures The futures {@code invokeAll} returned, one per dispatched segment
     * @param segmentsToSearch The segments, in the same order as the futures
     * @param options Query options, for the timeout to report against a segment that hit it
     * @return One result per segment, and how many of them ran out of time
     */
    private static SegmentAnswers collectAnswers(
            List<Future<SegmentQueryTask.SegmentQueryResult>> futures,
            List<SegmentInfo> segmentsToSearch,
            QueryOptions options) {

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
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
                segmentResults.add(unanswered(segmentInfo, e.getCause().getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                segmentsTimedOut++;
                segmentResults.add(unanswered(segmentInfo, "Interrupted"));
            } catch (java.util.concurrent.CancellationException e) {
                // Future was cancelled due to invokeAll timeout
                segmentsTimedOut++;
                segmentResults.add(unanswered(segmentInfo, "Timeout"));
            }
        }

        return new SegmentAnswers(segmentResults, segmentsTimedOut);
    }

    /**
     * A stand-in for a segment that did not answer.
     *
     * <p>Marked as a failure rather than returned as an empty answer: a segment that errored,
     * was interrupted or was cancelled has said nothing about the corpus, and the stages
     * downstream drop a failed segment's results instead of reading its silence as a segment
     * searched and found to hold nothing.</p>
     *
     * @param segmentInfo The segment that did not answer
     * @param errorMessage Why it did not
     * @return The stand-in result
     */
    private static SegmentQueryTask.SegmentQueryResult unanswered(SegmentInfo segmentInfo,
                                                                  String errorMessage) {
        return new SegmentQueryTask.SegmentQueryResult(
            segmentInfo.segmentId(),
            List.of(),
            0,
            0,
            false,
            errorMessage
        );
    }

    /**
     * The dispatch result for a fan-out that never got to run.
     *
     * <p>Every segment is reported as unanswered rather than as searched-and-empty: an
     * interrupted fan-out has no evidence about the corpus, and saying nothing matched
     * would be the one reading the caller cannot tell from a real empty result.</p>
     *
     * @param segmentCount How many segments were to be searched
     * @param startTime When the dispatch began, in nanoseconds
     * @return A result reporting every segment as unanswered
     */
    private static DispatchResult nothingSearched(int segmentCount, long startTime) {
        return new DispatchResult(
            List.of(), 0L, segmentCount,
            (System.nanoTime() - startTime) / 1_000_000);
    }

    /**
     * Totals the write-log entries the tail scans read.
     *
     * <p>Counted only for the segments that answered: a failed segment's result carries
     * whatever its task had recorded before it failed, which is not work the query can claim
     * to have covered the corpus with.</p>
     *
     * @param segmentResults The per-segment results
     * @return The total entries scanned
     */
    private static long tailScanEntries(List<SegmentQueryTask.SegmentQueryResult> segmentResults) {
        long tailScanEntries = 0;
        for (SegmentQueryTask.SegmentQueryResult result : segmentResults) {
            if (result.success()) {
                tailScanEntries += result.tailScanEntries();
            }
        }
        return tailScanEntries;
    }

    /**
     * What the fan-out came back with.
     *
     * @param results One result per dispatched segment, in dispatch order
     * @param segmentsTimedOut How many of them ran out of time or were cancelled
     */
    private record SegmentAnswers(List<SegmentQueryTask.SegmentQueryResult> results,
                                  int segmentsTimedOut) {
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
