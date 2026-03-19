package excalibur.query.executor;

import io.excalibur.store.Segment;
import io.excalibur.store.WriteLog;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A callable task for searching a single segment.
 *
 * <p>SegmentQueryTask wraps the search logic for a single segment, including
 * HNSW graph search and optional tail scan for un-integrated entries. It is
 * designed to be executed in parallel across multiple segments.</p>
 *
 * <h2>Execution Model</h2>
 * <p>Each task is independent and can be executed concurrently. The task
 * captures the visibility epoch at creation time to ensure consistent
 * segment visibility.</p>
 *
 * <h2>Error Handling</h2>
 * <p>Tasks never throw exceptions - instead, errors are captured in the
 * SegmentQueryResult. This allows partial results to be returned even
 * when some segments fail.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for concurrent execution.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class SegmentQueryTask implements Callable<SegmentQueryTask.SegmentQueryResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentQueryTask.class);

    private final long segmentId;
    private final Segment segment;
    private final HnswGraph graph;
    private final float[] queryVector;
    private final int k;
    private final int oversamplingFactor;
    private final DistanceFunction distanceFunction;
    private final TailScanner tailScanner;

    /**
     * Creates a new SegmentQueryTask.
     *
     * @param segmentId The segment identifier
     * @param segment The segment to search
     * @param graph The HNSW graph for the segment
     * @param queryVector The query vector
     * @param k The number of results to retrieve
     * @param oversamplingFactor The oversampling factor for HNSW search
     * @param distanceFunction The distance function
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if k is not positive
     */
    public SegmentQueryTask(
            long segmentId,
            Segment segment,
            HnswGraph graph,
            float[] queryVector,
            int k,
            int oversamplingFactor,
            DistanceFunction distanceFunction) {

        this.segmentId = segmentId;
        this.segment = Objects.requireNonNull(segment, "Segment cannot be null");
        this.graph = Objects.requireNonNull(graph, "Graph cannot be null");
        this.queryVector = Objects.requireNonNull(queryVector, "Query vector cannot be null");
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (oversamplingFactor < 1) {
            throw new IllegalArgumentException("Oversampling factor must be at least 1: " + oversamplingFactor);
        }

        this.k = k;
        this.oversamplingFactor = oversamplingFactor;
        this.tailScanner = new TailScanner();
    }

    @Override
    public SegmentQueryResult call() {
        long startTime = System.nanoTime();
        List<HnswSearchResult> results = new ArrayList<>();
        long tailScanEntries = 0;
        boolean success = false;
        String errorMessage = null;

        try {
            // Step 1: Perform HNSW graph search with oversampling
            int searchK = k * oversamplingFactor;
            List<HnswSearchResult> hnswResults = graph.search(queryVector, searchK);

            LOGGER.trace("Segment {}: HNSW search returned {} results", segmentId, hnswResults.size());

            // Step 2: Check if tail scan is needed
            long lastIntegratedOffset = graph.getLastIntegratedOffset();
            long writeLogHead = segment.getWriteLogHead();
            WriteLog writeLog = segment.writeLog();

            if (writeLog != null && tailScanner.hasTailEntries(lastIntegratedOffset, writeLogHead)) {
                // Step 3: Perform tail scan over un-integrated entries
                List<HnswSearchResult> tailResults = tailScanner.performTailScan(
                    queryVector,
                    writeLog,
                    lastIntegratedOffset,
                    writeLogHead,
                    k,
                    distanceFunction
                );

                tailScanEntries = tailResults.size();
                LOGGER.trace("Segment {}: Tail scan returned {} results", segmentId, tailScanEntries);

                // Step 4: Merge HNSW and tail scan results
                results = mergeResults(hnswResults, tailResults, k);
            } else {
                // No tail scan needed - just use HNSW results
                results = limitResults(hnswResults, k);
            }

            success = true;

        } catch (Exception e) {
            errorMessage = e.getMessage();
            LOGGER.warn("Segment {} search failed: {}", segmentId, errorMessage);
            // Return partial results (empty) rather than throwing
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        return new SegmentQueryResult(
            segmentId,
            results,
            tailScanEntries,
            durationMs,
            success,
            errorMessage
        );
    }

    /**
     * Merges HNSW and tail scan results, removing duplicates.
     */
    private List<HnswSearchResult> mergeResults(
            List<HnswSearchResult> hnswResults,
            List<HnswSearchResult> tailResults,
            int limit) {

        // Combine and sort by distance
        List<HnswSearchResult> combined = new ArrayList<>(hnswResults.size() + tailResults.size());
        combined.addAll(hnswResults);
        combined.addAll(tailResults);

        combined.sort((a, b) -> Float.compare(a.distance(), b.distance()));

        // Deduplicate by node ID and limit to k
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<HnswSearchResult> deduped = new ArrayList<>();

        for (HnswSearchResult result : combined) {
            if (seen.add(result.nodeId())) {
                deduped.add(result);
                if (deduped.size() >= limit) {
                    break;
                }
            }
        }

        return deduped;
    }

    /**
     * Limits results to k entries.
     */
    private List<HnswSearchResult> limitResults(List<HnswSearchResult> results, int limit) {
        if (results.size() <= limit) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Result of a segment query task.
     *
     * <p>This class captures the results from searching a single segment,
     * including any errors that occurred.</p>
     */
    public static final class SegmentQueryResult {
        private final long segmentId;
        private final List<HnswSearchResult> results;
        private final long tailScanEntries;
        private final long durationMs;
        private final boolean success;
        private final String errorMessage;

        public SegmentQueryResult(
                long segmentId,
                List<HnswSearchResult> results,
                long tailScanEntries,
                long durationMs,
                boolean success,
                String errorMessage) {

            this.segmentId = segmentId;
            this.results = results != null ? Collections.unmodifiableList(new ArrayList<>(results)) : Collections.emptyList();
            this.tailScanEntries = tailScanEntries;
            this.durationMs = durationMs;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        /**
         * Returns the segment ID that was searched.
         */
        public long segmentId() {
            return segmentId;
        }

        /**
         * Returns the search results from this segment.
         */
        public List<HnswSearchResult> results() {
            return results;
        }

        /**
         * Returns the number of results.
         */
        public int resultCount() {
            return results.size();
        }

        /**
         * Returns the number of entries scanned from the tail.
         */
        public long tailScanEntries() {
            return tailScanEntries;
        }

        /**
         * Returns the query duration in milliseconds.
         */
        public long durationMs() {
            return durationMs;
        }

        /**
         * Returns whether the search succeeded.
         */
        public boolean success() {
            return success;
        }

        /**
         * Returns the error message if search failed, null otherwise.
         */
        public String errorMessage() {
            return errorMessage;
        }

        /**
         * Returns whether this result had an error.
         */
        public boolean hasError() {
            return !success;
        }

        @Override
        public String toString() {
            return String.format(
                "SegmentQueryResult{segmentId=%d, results=%d, tailScan=%d, duration=%dms, success=%s}",
                segmentId, results.size(), tailScanEntries, durationMs, success);
        }
    }
}
