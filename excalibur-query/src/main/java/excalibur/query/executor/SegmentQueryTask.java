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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final Set<Long> lshCandidates;

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

        this(segmentId, segment, graph, queryVector, k, oversamplingFactor, distanceFunction, null);
    }

    /**
     * Creates a new SegmentQueryTask with LSH pre-filtered candidates.
     *
     * <p>This constructor allows passing a set of LSH pre-filtered candidate
     * node IDs to constrain the HNSW search. When LSH candidates are provided,
     * the HNSW search only considers those specific nodes rather than searching
     * the entire graph.</p>
     *
     * @param segmentId The segment identifier
     * @param segment The segment to search
     * @param graph The HNSW graph for the segment
     * @param queryVector The query vector
     * @param k The number of results to retrieve
     * @param oversamplingFactor The oversampling factor for HNSW search
     * @param distanceFunction The distance function
     * @param lshCandidates The set of LSH pre-filtered candidate node IDs (may be null or empty)
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
            DistanceFunction distanceFunction,
            Set<Long> lshCandidates) {

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
        this.lshCandidates = lshCandidates != null ? lshCandidates : Collections.emptySet();
    }

    @Override
    public SegmentQueryResult call() {
        long startTime = System.nanoTime();
        List<HnswSearchResult> results = new ArrayList<>();
        Map<Long, byte[]> payloads = Map.of();
        long tailScanEntries = 0;
        boolean success = false;
        String errorMessage = null;

        try {
            // Cooperative cancellation: a dispatcher timeout cancels this task with an
            // interrupt, so bail out before doing any work rather than searching a
            // segment nobody is waiting for.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Segment search cancelled before starting");
            }

            // Step 1: Capture the tail-scan range BEFORE searching.
            //
            // The graph search and the tail scan must together cover every entry. Reading
            // the watermark after the search would miss any entry integrated while the
            // search ran: too late for the search to see it, and already below the
            // watermark by the time the tail scan starts. Capturing it first can only
            // re-scan entries the search already returned, and those duplicates are
            // collapsed by mergeResults.
            long lastIntegratedOffset = graph.getLastIntegratedOffset();
            long writeLogHead = segment.getWriteLogHead();
            WriteLog writeLog = segment.writeLog();

            // Step 2: Perform HNSW graph search with oversampling
            int searchK = searchK();
            List<HnswSearchResult> hnswResults;

            // Use LSH pre-filtered candidates if available, otherwise do full search
            // CRITICAL: HnswGraph.search(query, k, emptySet) returns empty results,
            // so we must use search(query, k) when there are no candidates
            if (lshCandidates != null && !lshCandidates.isEmpty()) {
                hnswResults = graph.search(queryVector, searchK, lshCandidates);
                LOGGER.trace("Segment {}: HNSW search with {} LSH candidates returned {} results",
                    segmentId, lshCandidates.size(), hnswResults.size());
            } else {
                hnswResults = graph.search(queryVector, searchK);
                LOGGER.trace("Segment {}: HNSW search (full) returned {} results", segmentId, hnswResults.size());
            }

            // Step 3: Scan the captured tail range, if there is one
            if (writeLog != null && tailScanner.hasTailEntries(lastIntegratedOffset, writeLogHead)) {
                TailScanner.TailScanOutcome tailScan = tailScanner.scan(
                    queryVector,
                    writeLog,
                    lastIntegratedOffset,
                    writeLogHead,
                    k,
                    distanceFunction
                );

                // Report the work performed, not the number of results kept
                tailScanEntries = tailScan.entriesScanned();

                // The scan had each entry's value in hand, so its chunk text travels with
                // the results rather than being read back out of storage later.
                payloads = tailScan.payloads();
                LOGGER.trace("Segment {}: Tail scan read {} entries and returned {} results",
                    segmentId, tailScanEntries, tailScan.results().size());

                // Step 4: Merge HNSW and tail scan results
                results = mergeResults(hnswResults, tailScan.results(), k);
            } else {
                // No tail scan needed - just use HNSW results
                results = limitResults(hnswResults, k);
            }

            success = true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = "Interrupted";
            LOGGER.debug("Segment {} search cancelled", segmentId);
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
            errorMessage,
            payloads
        );
    }

    /**
     * Returns the oversampled search width, saturating instead of overflowing.
     *
     * <p>{@code k} is client-controlled and {@code oversamplingFactor} is configurable,
     * so the product can exceed {@link Integer#MAX_VALUE}; wrapping to a negative value
     * would turn the search width into nonsense.</p>
     */
    private int searchK() {
        long widened = (long) k * oversamplingFactor;
        return widened > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) widened;
    }

    /**
     * Merges HNSW and tail scan results, removing duplicates.
     *
     * <p>Both inputs carry {@link HnswSearchResult#distance()}, a dissimilarity where
     * smaller is closer for every metric, so a single ascending sort ranks them
     * correctly against each other.</p>
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
        private final Map<Long, byte[]> payloads;

        public SegmentQueryResult(
                long segmentId,
                List<HnswSearchResult> results,
                long tailScanEntries,
                long durationMs,
                boolean success,
                String errorMessage) {

            this(segmentId, results, tailScanEntries, durationMs, success, errorMessage, Map.of());
        }

        /**
         * Creates a segment result carrying the chunk text of its write-log hits.
         *
         * @param segmentId The segment identifier
         * @param results The search results
         * @param tailScanEntries The number of write-log entries the tail scan read
         * @param durationMs The search duration
         * @param success Whether the search succeeded
         * @param errorMessage The failure message, or null
         * @param payloads The chunk text of tail-scan results, keyed by node ID
         */
        public SegmentQueryResult(
                long segmentId,
                List<HnswSearchResult> results,
                long tailScanEntries,
                long durationMs,
                boolean success,
                String errorMessage,
                Map<Long, byte[]> payloads) {

            this.segmentId = segmentId;
            this.results = results != null ? Collections.unmodifiableList(new ArrayList<>(results)) : Collections.emptyList();
            this.tailScanEntries = tailScanEntries;
            this.durationMs = durationMs;
            this.success = success;
            this.errorMessage = errorMessage;
            this.payloads = payloads != null ? Map.copyOf(payloads) : Map.of();
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
         * Returns the chunk text of a tail-scan result, or null.
         *
         * <p>Graph results carry no payload here: their node ID resolves to a document,
         * not to the storage key of one chunk, so their text is resolved later by
         * {@code excalibur.query.payload.ResultPayloadResolver}.</p>
         *
         * @param nodeId The node ID of a result in {@link #results()}
         * @return The UTF-8 text bytes, or null when this result carries none
         */
        public byte[] payload(long nodeId) {
            return payloads.get(nodeId);
        }

        /**
         * Returns the chunk text of every tail-scan result, keyed by node ID.
         */
        public Map<Long, byte[]> payloads() {
            return payloads;
        }

        /**
         * Returns the number of results.
         */
        public int resultCount() {
            return results.size();
        }

        /**
         * Returns the number of write-log entries read by the tail scan.
         *
         * <p>This is the work performed, not the number of results kept: the scan reads
         * every entry in the un-integrated range but keeps only the closest k.</p>
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
