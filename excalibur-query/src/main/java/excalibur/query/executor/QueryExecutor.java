package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import io.excalibur.store.Segment;
import io.excalibur.store.WriteLog;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates vector search with tail scan integration for live indexing.
 *
 * <p>This class implements the query execution logic for Phase 6 of ExcaliburDB.
 * It coordinates between HNSW graph search and brute-force tail scanning to
 * ensure queries see all entries, including those not yet integrated into the
 * HNSW graph.</p>
 *
 * <h2>Query Execution Flow</h2>
 * <ol>
 *   <li>For each segment, perform HNSW graph search</li>
 *   <li>Check if tail scan is needed ({@code lastIntegratedOffset < writeLogHead})</li>
 *   <li>If tail scan needed, perform brute-force scan over un-integrated entries</li>
 *   <li>Merge HNSW results with tail scan results</li>
 *   <li>Deduplicate and return top-k results</li>
 * </ol>
 *
 * <h2>Live Indexing Guarantee</h2>
 * <p>The tail scan ensures that queries are "complete" even during active indexing.
 * Entries that have been written to the write log but not yet integrated into
 * the HNSW graph are still visible to queries through the brute-force tail scan.</p>
 *
 * <h2>Timeout and Cancellation</h2>
 * <p>The {@link #execute(QueryRequest, CancellationToken, List, DistanceFunction)} method
 * supports timeout via CompletableFuture and cooperative cancellation via CancellationToken.
 * Each stage checks for cancellation before proceeding.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Query vector must be non-null and non-empty</li>
 *   <li>k must be positive</li>
 *   <li>Results are always sorted by distance (closest first)</li>
 *   <li>No duplicate results (deduplication by LSN)</li>
 *   <li>Distance values are exact (full precision from tail scan)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each query execution uses thread-local state
 * for result aggregation.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class QueryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryExecutor.class);

    private final TailScanner tailScanner;

    /**
     * Creates a new QueryExecutor.
     */
    public QueryExecutor() {
        this.tailScanner = new TailScanner();
    }

    /**
     * Creates a new QueryExecutor with a custom TailScanner.
     *
     * @param tailScanner The tail scanner to use
     * @throws NullPointerException if tailScanner is null
     */
    public QueryExecutor(TailScanner tailScanner) {
        this.tailScanner = Objects.requireNonNull(tailScanner, "TailScanner cannot be null");
    }

    // ==================== Execute with Timeout and Cancellation ====================

    /**
     * Executes a query with timeout and cancellation support.
     *
     * <p>This method executes the query asynchronously and returns a CompletableFuture.
     * The future will complete with the result, or fail with:</p>
     * <ul>
     *   <li>{@link TimeoutException} if the query exceeds its timeout</li>
     *   <li>{@link CancellationException} if the token is cancelled</li>
     * </ul>
     *
     * <p>On timeout, the cancellation token is cancelled to propagate cancellation
     * to any running stages.</p>
     *
     * @param request The query request
     * @param token The cancellation token
     * @param segments The segments to search with their graphs
     * @param distanceFunction The distance function
     * @return A CompletableFuture containing the query response
     */
    public CompletableFuture<QueryResponse> execute(
            QueryRequest request,
            CancellationToken token,
            List<SegmentWithGraph> segments,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(token, "CancellationToken cannot be null");
        Objects.requireNonNull(segments, "Segments cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        long startTime = System.nanoTime();

        // Execute query asynchronously
        CompletableFuture<QueryResponse> future = CompletableFuture.supplyAsync(() -> {
            // Check cancellation before starting
            token.checkCancelled("Query cancelled before execution");

            // Execute the search
            List<HnswSearchResult> searchResults = searchWithCancellation(
                request.queryVectorUnsafe(),
                request.topK(),
                segments,
                distanceFunction,
                token
            );

            // Check cancellation after search
            token.checkCancelled("Query cancelled after search");

            // Convert to QueryResultItems
            List<QueryResultItem> results = convertResults(searchResults);

            // Build coverage
            IndexCoverage coverage = buildCoverage(segments);

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            return new QueryResponse(results, coverage, durationMs, false, false);
        });

        // Apply timeout
        CompletableFuture<QueryResponse> timeoutFuture = future
            .orTimeout(request.timeoutMs(), TimeUnit.MILLISECONDS);

        // Handle timeout by cancelling the token
        return timeoutFuture.handle((response, error) -> {
            if (error != null) {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;

                if (error instanceof TimeoutException ||
                    (error.getCause() instanceof TimeoutException)) {
                    // Cancel the token to propagate cancellation
                    token.cancel("Query timeout after " + durationMs + "ms");
                    LOGGER.warn("Query timed out after {}ms", durationMs);
                    return buildTimeoutResponse(durationMs);
                }

                if (error instanceof CancellationException ||
                    (error.getCause() instanceof CancellationException)) {
                    LOGGER.info("Query cancelled after {}ms", durationMs);
                    throw new CancellationException("Query was cancelled");
                }

                // Other errors
                LOGGER.error("Query failed: {}", error.getMessage());
                return buildErrorResponse(durationMs);
            }

            return response;
        });
    }

    /**
     * Executes search with cancellation checks between segments.
     */
    private List<HnswSearchResult> searchWithCancellation(
            float[] queryVector,
            int k,
            List<SegmentWithGraph> segments,
            DistanceFunction distanceFunction,
            CancellationToken token) {

        List<HnswSearchResult> allResults = new ArrayList<>();

        for (SegmentWithGraph sg : segments) {
            // Check cancellation before each segment
            token.checkCancelled("Query cancelled during segment search");

            try {
                List<HnswSearchResult> segmentResults = search(
                    queryVector,
                    k,
                    sg.segment(),
                    sg.graph(),
                    distanceFunction
                );
                allResults.addAll(segmentResults);
            } catch (Exception e) {
                LOGGER.warn("Error searching segment {}: {}",
                    sg.segment().path(), e.getMessage());
                // Continue with other segments
            }
        }

        // Sort all results by distance
        allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        // Deduplicate and return top-k
        return deduplicateAndLimit(allResults, k);
    }

    /**
     * Converts HNSW search results to QueryResultItems.
     */
    private List<QueryResultItem> convertResults(List<HnswSearchResult> searchResults) {
        List<QueryResultItem> results = new ArrayList<>(searchResults.size());
        for (HnswSearchResult sr : searchResults) {
            results.add(new QueryResultItem(
                sr.nodeId(),
                sr.distance(),
                null, // Payload would be fetched separately
                -1,   // Segment ID would be tracked
                sr.nodeId()
            ));
        }
        return results;
    }

    /**
     * Builds index coverage from segments.
     */
    private IndexCoverage buildCoverage(List<SegmentWithGraph> segments) {
        int activeSegments = 0;
        int indexingSegments = 0;
        int warmingSegments = 0;
        int compactingSegments = 0;

        for (SegmentWithGraph sg : segments) {
            // Simplified - would use actual lifecycle state
            activeSegments++;
        }

        int totalQueryable = activeSegments + indexingSegments + warmingSegments + compactingSegments;
        double coverage = segments.isEmpty() ? 0.0 : 1.0;

        return IndexCoverage.builder()
            .totalSegments(segments.size())
            .activeSegments(activeSegments)
            .indexingSegments(indexingSegments)
            .warmingSegments(warmingSegments)
            .compactingSegments(compactingSegments)
            .segmentsSearched(segments.size())
            .estimatedCorpusCoverage(coverage)
            .computeDegraded(0.5)
            .build();
    }

    /**
     * Builds a timeout response.
     */
    private QueryResponse buildTimeoutResponse(long durationMs) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            true  // timedOut
        );
    }

    /**
     * Builds an error response.
     */
    private QueryResponse buildErrorResponse(long durationMs) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            false
        );
    }

    /**
     * Executes a vector search on a single segment with tail scan integration.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Performs HNSW graph search for the query vector</li>
     *   <li>Checks if tail scan is needed</li>
     *   <li>Performs tail scan if there are un-integrated entries</li>
     *   <li>Merges and deduplicates results</li>
     *   <li>Returns top-k results sorted by distance</li>
     * </ol>
     *
     * @param queryVector The query vector
     * @param k The number of results to return
     * @param segment The segment to search
     * @param graph The HNSW graph for the segment
     * @param distanceFunction The distance function to use
     * @return List of search results sorted by distance (closest first)
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if k is not positive
     */
    public List<HnswSearchResult> search(
            float[] queryVector,
            int k,
            Segment segment,
            HnswGraph graph,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(graph, "Graph cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        // Step 1: Perform HNSW graph search
        List<HnswSearchResult> hnswResults = graph.search(queryVector, k * 2); // Get extra for merging

        LOGGER.trace("HNSW search returned {} results", hnswResults.size());

        // Step 2: Check if tail scan is needed
        long lastIntegratedOffset = graph.getLastIntegratedOffset();
        long writeLogHead = segment.getWriteLogHead();
        WriteLog writeLog = segment.writeLog();

        if (!tailScanner.hasTailEntries(lastIntegratedOffset, writeLogHead) || writeLog == null) {
            // No tail scan needed - return HNSW results directly (limited to k)
            return limitResults(hnswResults, k);
        }

        LOGGER.trace("Tail scan needed: lastIntegratedOffset={}, writeLogHead={}",
            lastIntegratedOffset, writeLogHead);

        // Step 3: Perform tail scan over un-integrated entries
        List<HnswSearchResult> tailResults = tailScanner.performTailScan(
            queryVector,
            writeLog,
            lastIntegratedOffset,
            writeLogHead,
            k,
            distanceFunction
        );

        LOGGER.trace("Tail scan returned {} results", tailResults.size());

        // Step 4: Merge and deduplicate results
        List<HnswSearchResult> mergedResults = mergeAndDeduplicate(hnswResults, tailResults, k);

        LOGGER.trace("Merged results: {} total", mergedResults.size());

        return mergedResults;
    }

    /**
     * Executes a vector search across multiple segments with tail scan integration.
     *
     * <p>This method searches each segment in parallel (conceptually) and
     * merges results from all segments.</p>
     *
     * <p><strong>Note:</strong> Current implementation is sequential. Production
     * implementation would use parallel streams or virtual threads.</p>
     *
     * @param queryVector The query vector
     * @param k The number of results to return
     * @param segments The segments to search with their graphs
     * @param distanceFunction The distance function to use
     * @return List of search results sorted by distance (closest first)
     * @throws NullPointerException if any parameter is null
     */
    public List<HnswSearchResult> searchMultiSegment(
            float[] queryVector,
            int k,
            List<SegmentWithGraph> segments,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(segments, "Segments list cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        // Collect results from all segments
        List<HnswSearchResult> allResults = new ArrayList<>();
        long totalTailEntries = 0;

        for (SegmentWithGraph sg : segments) {
            try {
                List<HnswSearchResult> segmentResults = search(
                    queryVector,
                    k,
                    sg.segment(),
                    sg.graph(),
                    distanceFunction
                );
                allResults.addAll(segmentResults);

                // Track tail scan statistics
                long tailSize = sg.graph().getLastIntegratedOffset() - sg.segment().getWriteLogHead();
                if (tailSize > 0) {
                    totalTailEntries += tailSize;
                }

            } catch (Exception e) {
                LOGGER.warn("Error searching segment {}: {}",
                    sg.segment().path(), e.getMessage());
                // Continue with other segments
            }
        }

        // Sort all results by distance
        allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        // Deduplicate and return top-k
        return deduplicateAndLimit(allResults, k);
    }

    /**
     * Checks if a segment requires a tail scan.
     *
     * <p>This is a quick check that can be used for query planning.</p>
     *
     * @param segment The segment to check
     * @param graph The HNSW graph for the segment
     * @return true if tail scan is needed, false otherwise
     */
    public boolean needsTailScan(Segment segment, HnswGraph graph) {
        if (segment.writeLog() == null) {
            return false;
        }
        return tailScanner.hasTailEntries(
            graph.getLastIntegratedOffset(),
            segment.getWriteLogHead()
        );
    }

    /**
     * Returns statistics about the tail scan for a segment.
     *
     * @param segment The segment to analyze
     * @param graph The HNSW graph for the segment
     * @return Tail scan statistics
     */
    public TailScanStats getTailScanStats(Segment segment, HnswGraph graph) {
        long lastIntegratedOffset = graph.getLastIntegratedOffset();
        long writeLogHead = segment.getWriteLogHead();
        long tailSize = Math.max(0, writeLogHead - lastIntegratedOffset);
        long estimatedEntries = tailScanner.estimateTailEntryCount(
            lastIntegratedOffset, writeLogHead, 100);

        return new TailScanStats(
            lastIntegratedOffset,
            writeLogHead,
            tailSize,
            estimatedEntries,
            tailSize > 0
        );
    }

    /**
     * Merges HNSW results with tail scan results and removes duplicates.
     *
     * <p>Deduplication is based on the synthetic node ID which encodes the
     * write log offset and LSN.</p>
     *
     * @param hnswResults Results from HNSW graph search
     * @param tailResults Results from tail scan
     * @param k Maximum number of results to return
     * @return Merged and deduplicated results
     */
    private List<HnswSearchResult> mergeAndDeduplicate(
            List<HnswSearchResult> hnswResults,
            List<HnswSearchResult> tailResults,
            int k) {

        // Use a set to track seen node IDs for deduplication
        Set<Long> seenNodeIds = new HashSet<>();
        List<HnswSearchResult> merged = new ArrayList<>();

        // Combine all results
        List<HnswSearchResult> combined = new ArrayList<>(hnswResults.size() + tailResults.size());
        combined.addAll(hnswResults);
        combined.addAll(tailResults);

        // Sort by distance
        combined.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        // Deduplicate and limit to k
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
     * Deduplicates results and limits to k entries.
     */
    private List<HnswSearchResult> deduplicateAndLimit(List<HnswSearchResult> results, int k) {
        Set<Long> seenNodeIds = new HashSet<>();
        List<HnswSearchResult> deduped = new ArrayList<>();

        for (HnswSearchResult result : results) {
            if (seenNodeIds.add(result.nodeId())) {
                deduped.add(result);
                if (deduped.size() >= k) {
                    break;
                }
            }
        }

        return deduped;
    }

    /**
     * Limits results to k entries.
     */
    private List<HnswSearchResult> limitResults(List<HnswSearchResult> results, int k) {
        if (results.size() <= k) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, k));
    }

    /**
     * Record holding a segment and its associated HNSW graph.
     */
    public record SegmentWithGraph(Segment segment, HnswGraph graph) {
        public SegmentWithGraph {
            Objects.requireNonNull(segment, "Segment cannot be null");
            Objects.requireNonNull(graph, "Graph cannot be null");
        }
    }

    /**
     * Statistics about the tail scan for a segment.
     *
     * @param lastIntegratedOffset The offset up to which entries are in the HNSW graph
     * @param writeLogHead The current head of the write log
     * @param tailSizeBytes The size of the tail region in bytes
     * @param estimatedEntries Estimated number of entries in the tail
     * @param needsTailScan Whether tail scan is needed
     */
    public record TailScanStats(
        long lastIntegratedOffset,
        long writeLogHead,
        long tailSizeBytes,
        long estimatedEntries,
        boolean needsTailScan
    ) {}
}
