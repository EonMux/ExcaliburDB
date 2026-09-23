package com.eonmux.excalibur.query.executor;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
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
    private final PqSearchSettings pqSearch;

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
     * Creates a new SegmentQueryTask with the nodes an LSH index named for the query.
     *
     * <p>The named nodes are measured exactly and ranked together with what the graph search
     * returns. They add to the pool the answer is drawn from and never restrict it: an LSH bucket
     * puts no bound on the distance of what it leaves out, so confining the search to it would
     * drop neighbours the graph would have found.</p>
     *
     * @param segmentId The segment identifier
     * @param segment The segment to search
     * @param graph The HNSW graph for the segment
     * @param queryVector The query vector
     * @param k The number of results to retrieve
     * @param oversamplingFactor The oversampling factor for HNSW search
     * @param distanceFunction The distance function
     * @param lshCandidates The node IDs an LSH index named for the query, to measure beside the
     *                      graph search (may be null or empty)
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

        this(segmentId, segment, graph, queryVector, k, oversamplingFactor, distanceFunction,
            lshCandidates, PqSearchSettings.DISABLED);
    }

    /**
     * Creates a new SegmentQueryTask that may search the segment through its quantizer.
     *
     * <p>The quantised search is taken only when this deployment asked for it and the segment
     * has a trained quantizer. It rescores what it finds on full-precision vectors, so its
     * distances rank against the exactly-measured LSH candidates and the tail scan's hits.</p>
     *
     * @param segmentId The segment identifier
     * @param segment The segment to search
     * @param graph The HNSW graph for the segment
     * @param queryVector The query vector
     * @param k The number of results to retrieve
     * @param oversamplingFactor The oversampling factor for HNSW search
     * @param distanceFunction The distance function
     * @param lshCandidates The node IDs an LSH index named for the query, to measure beside the
     *                      graph search (may be null or empty)
     * @param pqSearch Whether this search may use the segment's quantizer, and how widely
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
            Set<Long> lshCandidates,
            PqSearchSettings pqSearch) {

        this.pqSearch = Objects.requireNonNull(pqSearch, "PqSearchSettings cannot be null");
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
        SegmentReading reading = SegmentReading.nothing();
        boolean success = false;
        String errorMessage = null;

        try {
            reading = readSegment();
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
            reading.results(),
            reading.tailScanEntries(),
            durationMs,
            success,
            errorMessage,
            reading.payloads(),
            reading.complete()
        );
    }

    /**
     * Searches this segment's graph, and the part of its write log the graph has not caught
     * up with.
     *
     * @return What this segment has to say about the query
     * @throws InterruptedException if the task was cancelled before it began
     */
    private SegmentReading readSegment() throws InterruptedException {
        // Cooperative cancellation: a dispatcher timeout cancels this task with an
        // interrupt, so bail out before doing any work rather than searching a
        // segment nobody is waiting for.
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Segment search cancelled before starting");
        }

        // Step 1: Capture the tail-scan range BEFORE searching.
        TailRange tail = captureTailRange();

        // Step 2: Perform HNSW graph search with oversampling
        List<HnswSearchResult> hnswResults = searchGraph(searchK());

        // Step 3: Scan the captured tail range, if there is one
        if (tail.writeLog() != null
                && tailScanner.hasTailEntries(tail.lastIntegratedOffset(), tail.writeLogHead())) {
            return scanTailAndMerge(hnswResults, tail);
        }

        // No tail scan needed - just use HNSW results
        return SegmentReading.fromGraph(limitResults(hnswResults, k));
    }

    /**
     * Reads the stretch of write log the graph has not absorbed, before the search runs.
     *
     * <p>The graph search and the tail scan must together cover every entry. Reading
     * the watermark after the search would miss any entry integrated while the
     * search ran: too late for the search to see it, and already below the
     * watermark by the time the tail scan starts. Capturing it first can only
     * re-scan entries the search already returned, and those duplicates are
     * collapsed by mergeResults.</p>
     *
     * @return The offsets a tail scan would have to cover, and the log holding them
     */
    private TailRange captureTailRange() {
        return new TailRange(
            graph.getLastIntegratedOffset(),
            segment.getWriteLogHead(),
            segment.writeLog());
    }

    /**
     * Scans the captured tail range and ranks its hits against the graph's.
     *
     * @param hnswResults What the graph search returned
     * @param tail The tail range captured before the graph was searched
     * @return This segment's answer, drawn from both the graph and the write log
     */
    private SegmentReading scanTailAndMerge(List<HnswSearchResult> hnswResults, TailRange tail) {
        TailScanner.TailScanOutcome tailScan = tailScanner.scan(
            queryVector,
            tail.writeLog(),
            tail.lastIntegratedOffset(),
            tail.writeLogHead(),
            k,
            distanceFunction
        );

        // Report the work performed, not the number of results kept
        long tailScanEntries = tailScan.entriesScanned();
        boolean complete = sawWholeTail(tailScan);

        // The scan had each entry's value in hand, so its chunk text travels with
        // the results rather than being read back out of storage later.
        Map<Long, byte[]> payloads = tailScan.payloads();
        LOGGER.trace("Segment {}: Tail scan read {} entries and returned {} results",
            segmentId, tailScanEntries, tailScan.results().size());

        // Step 4: Merge HNSW and tail scan results
        List<HnswSearchResult> results =
            mergeResults(hnswResults, tailScan.results(), tailScan.touchedKeys(), k);

        return new SegmentReading(results, payloads, tailScanEntries, complete);
    }

    /**
     * Whether the tail scan reached the end of the range it was handed.
     *
     * <p>A scan cut short did not see the whole tail, so this segment's answer is
     * partial. It still carries everything the graph search found, which is why it
     * is not reported as a failure - a failed segment's results are discarded
     * wholesale - but a caller told the segment answered in full would be reading a
     * short answer as an exhaustive one.</p>
     *
     * @param tailScan What the scan returned, including why it stopped
     * @return true when this segment's answer covers everything it was asked about
     */
    private boolean sawWholeTail(TailScanner.TailScanOutcome tailScan) {
        if (tailScan.backpressureTriggered() || tailScan.cancelled()) {
            LOGGER.debug("Segment {}: tail scan stopped early (backpressure={}, "
                + "cancelled={})", segmentId, tailScan.backpressureTriggered(),
                tailScan.cancelled());
            return false;
        }
        return true;
    }

    /**
     * The stretch of a segment's write log that its graph has not absorbed yet.
     *
     * @param lastIntegratedOffset How far the graph has absorbed the log
     * @param writeLogHead How far the log has been written
     * @param writeLog The log to read the entries between them from, or null when the segment
     *                 keeps none
     */
    private record TailRange(long lastIntegratedOffset, long writeLogHead, WriteLog writeLog) {
    }

    /**
     * What one segment has to say about a query.
     *
     * @param results The ranked hits, closest first
     * @param payloads The chunk text of the write-log hits, keyed by node ID, which the tail
     *                 scan had in hand and which would otherwise be read back from storage
     * @param tailScanEntries How many write-log entries the tail scan read, which is work
     *                        performed rather than results kept
     * @param complete Whether the segment was searched in full, or answered from part of its
     *                 write log because the scan stopped early
     */
    private record SegmentReading(List<HnswSearchResult> results,
                                  Map<Long, byte[]> payloads,
                                  long tailScanEntries,
                                  boolean complete) {

        /**
         * A reading from a segment that was never searched, for a task that failed or was
         * cancelled before it could read anything.
         *
         * @return An empty reading
         */
        static SegmentReading nothing() {
            return new SegmentReading(new ArrayList<>(), Map.of(), 0, true);
        }

        /**
         * A reading answered entirely from the graph, the write log having no tail to scan.
         *
         * @param results The ranked hits from the graph
         * @return The reading
         */
        static SegmentReading fromGraph(List<HnswSearchResult> results) {
            return new SegmentReading(results, Map.of(), 0, true);
        }
    }

    /**
     * Searches the segment's graph, and measures the nodes its LSH index named beside it.
     *
     * <p>The two are ranked together by exact distance and cut to {@code searchK}. A named node
     * enters that cut only by being closer than a result the graph search returned, so the
     * answer is never worse than the graph search's alone; it is better wherever the search's
     * beam passed over a near neighbour the query's buckets caught.</p>
     *
     * @param searchK The oversampled number of results to ask the graph for
     * @return What the graph returned, with any closer named node ranked in, closest first
     */
    private List<HnswSearchResult> searchGraph(int searchK) {
        List<HnswSearchResult> walked = walkGraph(searchK);
        if (lshCandidates.isEmpty()) {
            return walked;
        }
        // HnswGraph.search(query, k, emptySet) means "nothing is admissible", so the named set
        // is consulted only when it holds something.
        List<HnswSearchResult> named = graph.search(queryVector, searchK, lshCandidates);
        LOGGER.trace("Segment {}: {} LSH candidates measured, {} ranked beside {} graph results",
            segmentId, lshCandidates.size(), named.size(), walked.size());
        return mergeResults(walked, named, Set.of(), searchK);
    }

    /**
     * Runs the graph search this task is configured for, over the whole graph.
     *
     * @param searchK The oversampled number of results to ask the graph for
     * @return What the graph returned, closest first
     */
    private List<HnswSearchResult> walkGraph(int searchK) {
        if (quantisedSearchApplies()) {
            List<HnswSearchResult> results =
                graph.searchWithPQThenRerank(queryVector, searchK, pqSearch.oversample());
            LOGGER.trace("Segment {}: quantised HNSW search (oversample {}) returned {} results",
                segmentId, pqSearch.oversample(), results.size());
            return results;
        }
        List<HnswSearchResult> results = graph.search(queryVector, searchK);
        LOGGER.trace("Segment {}: HNSW search returned {} results", segmentId, results.size());
        return results;
    }

    /**
     * Whether this segment's quantizer may answer this search.
     *
     * <p>A segment with no trained quantizer has no codes to rank by, so it falls back to the
     * exact search rather than failing: quantisation is trained per segment and a query fans
     * out across segments that are not all at the same point in their lifecycle.</p>
     */
    private boolean quantisedSearchApplies() {
        return pqSearch.enabled() && graph.isPQEnabled();
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
     * Merges two ranked result lists, one per chunk.
     *
     * <p>Used for the graph search against the LSH candidates, and for the graph's answer
     * against the tail scan's. Both inputs carry {@link HnswSearchResult#distance()}, a
     * dissimilarity where smaller is closer for every metric, so a single ascending sort ranks
     * them correctly against each other.</p>
     *
     * <p>A first-list hit on a chunk in {@code touchedKeys} is dropped: against a tail scan, the
     * tail holds that chunk's later value or its deletion, and the graph's node is the value
     * storage has since replaced. The tail's own hit for it, if any, stands in its place. What
     * remains is deduplicated by chunk, which collapses the same node found by both the walk and
     * the LSH candidates, a node the integration worker absorbed while this search ran with the
     * tail hit it came from, and the instant during a supersession when a chunk's old and new
     * nodes are both still reachable.</p>
     *
     * @param hnswResults What the graph returned
     * @param otherResults What the LSH candidates or the tail scan returned
     * @param touchedKeys Every chunk the tail scan read an entry for; empty for LSH candidates
     * @param limit How many results to keep
     * @return The merged results, closest first
     */
    private List<HnswSearchResult> mergeResults(
            List<HnswSearchResult> hnswResults,
            List<HnswSearchResult> otherResults,
            Set<String> touchedKeys,
            int limit) {

        List<HnswSearchResult> combined = new ArrayList<>(hnswResults.size() + otherResults.size());
        for (HnswSearchResult result : hnswResults) {
            if (result.documentId() == null || !touchedKeys.contains(result.documentId())) {
                combined.add(result);
            }
        }
        combined.addAll(otherResults);

        combined.sort((a, b) -> Float.compare(a.distance(), b.distance()));

        // An unnamed result has only its node ID to be told apart by
        java.util.Set<Object> seen = new java.util.HashSet<>();
        List<HnswSearchResult> deduped = new ArrayList<>();

        for (HnswSearchResult result : combined) {
            Object identity = result.documentId() != null ? result.documentId() : result.nodeId();
            if (seen.add(identity)) {
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
        private final boolean complete;

        public SegmentQueryResult(
                long segmentId,
                List<HnswSearchResult> results,
                long tailScanEntries,
                long durationMs,
                boolean success,
                String errorMessage) {

            this(segmentId, results, tailScanEntries, durationMs, success, errorMessage,
                Map.of(), true);
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

            this(segmentId, results, tailScanEntries, durationMs, success, errorMessage,
                payloads, true);
        }

        /**
         * Creates a segment result that can report having answered only in part.
         *
         * @param segmentId The segment identifier
         * @param results The search results
         * @param tailScanEntries The number of write-log entries the tail scan read
         * @param durationMs The search duration
         * @param success Whether the search succeeded
         * @param errorMessage The failure message, or null
         * @param payloads The chunk text of tail-scan results, keyed by node ID
         * @param complete Whether everything this segment holds was actually searched
         */
        public SegmentQueryResult(
                long segmentId,
                List<HnswSearchResult> results,
                long tailScanEntries,
                long durationMs,
                boolean success,
                String errorMessage,
                Map<Long, byte[]> payloads,
                boolean complete) {

            this.complete = complete;
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
         * Whether everything this segment holds was searched.
         *
         * <p>False when the write-log tail scan stopped early, at the backpressure limit or on
         * cancellation. The results are still real and still returned - this is not a failure,
         * whose results are discarded - but they were drawn from part of the segment.</p>
         *
         * @return true when the segment was searched in full
         */
        public boolean complete() {
            return complete;
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
         * <p>Graph results carry no payload here: their text is read from storage under the
         * chunk id they are named by, once selection has narrowed the set, by
         * {@code com.eonmux.excalibur.query.payload.ResultPayloadResolver}.</p>
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
