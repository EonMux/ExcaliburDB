package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResultItem;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.DistanceValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Aggregates and merges results from multiple segment queries.
 *
 * <p>ResultAggregator takes the results from parallel segment searches and
 * combines them into a unified top-k result list. It handles deduplication,
 * score-based ranking, and coverage calculation.</p>
 *
 * <h2>Merge Strategy</h2>
 * <p>The aggregator uses a merge-sort approach:</p>
 * <ol>
 *   <li>Collect all results from all segments</li>
 *   <li>Sort by score/distance (depending on metric)</li>
 *   <li>Deduplicate by document ID</li>
 *   <li>Take top-k results</li>
 * </ol>
 *
 * <h2>Sorting Modes</h2>
 * <ul>
 *   <li><strong>Distance sorting (ascending):</strong> Used for L2 distance where lower is better</li>
 *   <li><strong>Relevance sorting (descending):</strong> Used for LLM relevancy scores where higher is better</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are always sorted (closest/smallest distance first OR highest relevance first)</li>
 *   <li>No duplicate document IDs in output</li>
 *   <li>Exactly k results (or fewer if insufficient candidates)</li>
 *   <li>Coverage is computed from segment statistics</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. Create a new instance for each aggregation
 * or synchronize access externally.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class ResultAggregator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultAggregator.class);

    /**
     * Default coverage threshold for degraded state detection.
     */
    public static final double DEFAULT_COVERAGE_THRESHOLD = 0.5;

    private final int topK;
    private final float threshold;
    private final double coverageThreshold;

    /**
     * Creates a new ResultAggregator.
     *
     * @param topK The number of results to return
     * @param threshold The minimum score threshold (0 for no filtering)
     * @param coverageThreshold The coverage threshold for degraded state
     */
    public ResultAggregator(int topK, float threshold, double coverageThreshold) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive: " + topK);
        }
        this.topK = topK;
        this.threshold = threshold;
        this.coverageThreshold = coverageThreshold;
    }

    /**
     * Creates a new ResultAggregator from QueryOptions.
     *
     * @param options The query options
     */
    public ResultAggregator(QueryOptions options) {
        this(options.topK(), options.threshold(), DEFAULT_COVERAGE_THRESHOLD);
    }

    /**
     * Creates a new ResultAggregator with default settings.
     *
     * @param topK The number of results to return
     */
    public ResultAggregator(int topK) {
        this(topK, 0.0f, DEFAULT_COVERAGE_THRESHOLD);
    }

    /**
     * Merges results from multiple segment queries with score normalization.
     *
     * <p>This method normalizes raw distance values to [0.0, 1.0] scores where
     * higher is always better, regardless of the underlying distance metric.</p>
     *
     * <h3>Normalization</h3>
     * <ul>
     *   <li>Cosine similarity [-1, 1] → normalized score [0, 1] via (x+1)/2</li>
     *   <li>L2 distance [0, inf) → normalized score (0, 1] via 1/(1+x)</li>
     *   <li>Inner product → normalized score via sigmoid</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>All scores are in range [0.0, 1.0]</li>
     *   <li>Results are sorted by score descending (highest/best first)</li>
     *   <li>Threshold is applied to normalized scores (filter out scores below threshold)</li>
     *   <li>No duplicate document IDs in output</li>
     * </ul>
     *
     * @param segmentResults Results from each segment
     * @param distanceFunction The distance function used (for normalization)
     * @return Aggregated and ranked results with normalized scores (at most topK items)
     */
    public List<QueryResultItem> mergeAndRankWithNormalization(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(segmentResults, "Segment results cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (segmentResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Phase 1: Collect all results, normalize scores, and deduplicate
        // Keep the best (highest normalized score) for each document
        Map<String, QueryResultItem> bestResultsByDocId = new HashMap<>();

        for (SegmentQueryTask.SegmentQueryResult segmentResult : segmentResults) {
            if (!segmentResult.success()) {
                LOGGER.debug("Skipping failed segment {} results", segmentResult.segmentId());
                continue;
            }

            for (HnswSearchResult hnswResult : segmentResult.results()) {
                // Normalize the raw distance to [0.0, 1.0] score
                float normalizedScore = DistanceValidator.validateClampAndNormalize(
                    hnswResult.distance(), distanceFunction);

                // Apply threshold filter on normalized score (lower = worse, so filter out)
                if (threshold > 0 && normalizedScore < threshold) {
                    continue;
                }

                // Skip items with NaN scores - they cannot be properly ranked
                if (Float.isNaN(normalizedScore)) {
                    LOGGER.debug("Skipping result with NaN score: nodeId={}, documentId={}",
                        hnswResult.nodeId(), hnswResult.documentId());
                    continue;
                }

                String documentIdStr = hnswResult.documentId();
                long nodeId = hnswResult.nodeId();

                // Create result item with normalized score
                QueryResultItem item = new QueryResultItem(
                    nodeId,
                    documentIdStr,
                    normalizedScore,
                    null,
                    segmentResult.segmentId(),
                    nodeId
                );

                // Keep the best (highest normalized score) result for each document
                String dedupeKey = documentIdStr != null ? documentIdStr : String.valueOf(nodeId);
                bestResultsByDocId.merge(dedupeKey, item, (existing, newItem) ->
                    newItem.score() > existing.score() ? newItem : existing
                );
            }
        }

        // Phase 2: Select top-k results using a min-heap
        // Min-heap: keep smallest at top so we can remove the worst
        PriorityQueue<QueryResultItem> topResults = new PriorityQueue<>(
            topK,
            Comparator.comparingDouble(QueryResultItem::score)
        );

        for (QueryResultItem item : bestResultsByDocId.values()) {
            if (topResults.size() < topK) {
                topResults.add(item);
            } else if (item.score() > topResults.peek().score()) {
                topResults.poll();
                topResults.add(item);
            }
        }

        // Phase 3: Convert heap to sorted list (highest score first)
        List<QueryResultItem> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(QueryResultItem::score).reversed());

        LOGGER.trace("Aggregated {} results from {} segments ({} unique documents)",
            results.size(), segmentResults.size(), bestResultsByDocId.size());

        return results;
    }

    /**
     * Merges results from multiple segment queries.
     *
     * <p>This method takes the list of segment query results and produces
     * a unified list of top-k QueryResultItems.</p>
     *
     * <h3>Deduplication Strategy</h3>
     * <p>When the same document ID appears in multiple segments (e.g., during re-indexing
     * with rolling replacement), this method keeps the result with the BEST score
     * (lowest distance for distance-based metrics). This ensures:</p>
     * <ul>
     *   <li>No duplicate document IDs in the final result set</li>
     *   <li>The best matching result is retained when duplicates exist</li>
     *   <li>Consistent behavior across segment overlap scenarios</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results are sorted by score (ascending - closest first)</li>
     *   <li>No duplicate document IDs in output</li>
     *   <li>When duplicates exist, the one with the lowest score is kept</li>
     *   <li>Exactly topK results (or fewer if insufficient candidates)</li>
     * </ul>
     *
     * @param segmentResults Results from each segment
     * @return Aggregated and ranked results (at most topK items)
     * @deprecated Use {@link #mergeAndRankWithNormalization} for proper [0,1] score normalization
     */
    @Deprecated
    public List<QueryResultItem> mergeAndRank(List<SegmentQueryTask.SegmentQueryResult> segmentResults) {
        Objects.requireNonNull(segmentResults, "Segment results cannot be null");

        if (segmentResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Phase 1: Collect all results and deduplicate, keeping the best score for each document
        // We use string document IDs for deduplication when available (actual document IDs like "doc1")
        // This correctly handles the case where the same document appears in multiple segments
        // during re-indexing or segment overlap scenarios
        // Fallback to nodeId-based deduplication for backward compatibility
        Map<String, QueryResultItem> bestResultsByDocIdStr = new HashMap<>();

        // Process results from all segments
        for (SegmentQueryTask.SegmentQueryResult segmentResult : segmentResults) {
            if (!segmentResult.success()) {
                LOGGER.debug("Skipping failed segment {} results", segmentResult.segmentId());
                continue;
            }

            for (HnswSearchResult hnswResult : segmentResult.results()) {
                // Apply threshold filter
                if (threshold > 0 && hnswResult.distance() > threshold) {
                    continue;
                }

                // Get the actual document ID string from the HNSW result
                // This is populated from the node-to-document mapping during search
                String documentIdStr = hnswResult.documentId();
                long nodeId = hnswResult.nodeId();

                // Create result item with string document ID for atomicity filtering
                QueryResultItem item = new QueryResultItem(
                    nodeId, // Numeric ID for backward compatibility
                    documentIdStr, // Actual string document ID
                    hnswResult.distance(),
                    null, // Payload would be fetched separately
                    segmentResult.segmentId(),
                    nodeId
                );

                // Keep the best (lowest distance) result for each document ID
                // Use string document ID for deduplication when available
                String dedupeKey = documentIdStr != null ? documentIdStr : String.valueOf(nodeId);
                bestResultsByDocIdStr.merge(dedupeKey, item, (existing, newItem) ->
                    newItem.score() < existing.score() ? newItem : existing
                );
            }
        }

        // Phase 2: Select top-k results using a max-heap
        // Max-heap: keep largest (worst) at top so we can remove it
        PriorityQueue<QueryResultItem> topResults = new PriorityQueue<>(
            topK,
            Comparator.comparingDouble(QueryResultItem::score).reversed()
        );

        for (QueryResultItem item : bestResultsByDocIdStr.values()) {
            if (topResults.size() < topK) {
                topResults.add(item);
            } else if (item.score() < topResults.peek().score()) {
                topResults.poll();
                topResults.add(item);
            }
        }

        // Phase 3: Convert heap to sorted list (smallest distance first)
        List<QueryResultItem> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(QueryResultItem::score));

        LOGGER.trace("Aggregated {} results from {} segments ({} unique documents)",
            results.size(), segmentResults.size(), bestResultsByDocIdStr.size());

        return results;
    }

    /**
     * Builds the index coverage from segment query results.
     *
     * @param segmentResults Results from each segment
     * @param totalSegments Total number of segments in the index
     * @param activeSegments Number of active segments
     * @param indexingSegments Number of indexing segments
     * @param warmingSegments Number of warming segments
     * @param compactingSegments Number of compacting segments
     * @param tombstonedSegments Number of tombstoned segments
     * @return IndexCoverage for this query
     */
    public IndexCoverage buildCoverage(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            long totalSegments,
            long activeSegments,
            long indexingSegments,
            long warmingSegments,
            long compactingSegments,
            long tombstonedSegments) {

        int segmentsSearched = 0;
        int segmentsWithErrors = 0;
        int segmentsTimedOut = 0;
        long totalTailScanEntries = 0;

        for (SegmentQueryTask.SegmentQueryResult result : segmentResults) {
            if (result.success()) {
                segmentsSearched++;
                totalTailScanEntries += result.tailScanEntries();
            } else {
                segmentsWithErrors++;
                // Check if error was a timeout (simplified check)
                if (result.errorMessage() != null &&
                    result.errorMessage().toLowerCase().contains("timeout")) {
                    segmentsTimedOut++;
                }
            }
        }

        // Build coverage
        IndexCoverage.Builder builder = IndexCoverage.builder()
            .totalSegments(totalSegments)
            .activeSegments(activeSegments)
            .indexingSegments(indexingSegments)
            .warmingSegments(warmingSegments)
            .compactingSegments(compactingSegments)
            .tombstonedSegments(tombstonedSegments)
            .tailScanEntries(totalTailScanEntries)
            .segmentsSearched(segmentsSearched)
            .segmentsWithErrors(segmentsWithErrors)
            .segmentsTimedOut(segmentsTimedOut);

        // Compute coverage and degraded state
        builder.computeCoverage();
        builder.computeDegraded(coverageThreshold);

        return builder.build();
    }

    /**
     * Aggregates HNSW search results from multiple segments.
     *
     * <p>This is a simpler method for when full segment context isn't needed.</p>
     *
     * <h3>Deduplication Strategy</h3>
     * <p>When the same node ID appears in multiple result sets, this method keeps
     * the result with the BEST (lowest) distance. This ensures no duplicates
     * in the output and the best matching result is always retained.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results are sorted by distance (ascending - closest first)</li>
     *   <li>No duplicate node IDs in output</li>
     *   <li>When duplicates exist, the one with the lowest distance is kept</li>
     *   <li>Exactly k results (or fewer if insufficient candidates)</li>
     * </ul>
     *
     * @param allResults Results from all segments
     * @param k Number of results to return
     * @return Top-k merged results
     */
    public static List<HnswSearchResult> mergeResults(
            List<List<HnswSearchResult>> allResults, int k) {

        if (allResults == null || allResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Phase 1: Deduplicate by keeping the best (lowest distance) for each node ID
        Map<Long, HnswSearchResult> bestResultsByNodeId = new HashMap<>();

        for (List<HnswSearchResult> segmentResults : allResults) {
            if (segmentResults == null) continue;

            for (HnswSearchResult result : segmentResults) {
                // Keep the best (lowest distance) result for each node ID
                bestResultsByNodeId.merge(result.nodeId(), result, (existing, newItem) ->
                    newItem.distance() < existing.distance() ? newItem : existing
                );
            }
        }

        // Phase 2: Select top-k using a max-heap
        PriorityQueue<HnswSearchResult> topResults = new PriorityQueue<>(
            k,
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        for (HnswSearchResult result : bestResultsByNodeId.values()) {
            if (topResults.size() < k) {
                topResults.add(result);
            } else if (result.distance() < topResults.peek().distance()) {
                topResults.poll();
                topResults.add(result);
            }
        }

        // Phase 3: Convert to sorted list
        List<HnswSearchResult> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        return results;
    }

    /**
     * Returns the configured top-k value.
     */
    public int topK() {
        return topK;
    }

    /**
     * Returns the configured threshold.
     */
    public float threshold() {
        return threshold;
    }

    /**
     * Returns the configured coverage threshold.
     */
    public double coverageThreshold() {
        return coverageThreshold;
    }

    // ==================== Relevance Score Sorting ====================

    /**
     * Sorts results by relevance score in descending order.
     *
     * <p>This method is used after LLM relevancy scoring, where higher scores
     * indicate better matches. Results are sorted from highest relevance to lowest.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results sorted by score descending (highest first)</li>
     *   <li>Relevance scores should be in range [0.0, 1.0]</li>
     *   <li>Original list is not modified (returns new sorted list)</li>
     * </ul>
     *
     * @param results The results to sort (may be null or empty)
     * @return New list sorted by relevance score descending
     */
    public static List<QueryResultItem> sortByRelevanceDescending(List<QueryResultItem> results) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }

        // Create a new list to avoid modifying the original
        List<QueryResultItem> sorted = new ArrayList<>(results);

        // Sort by relevance score descending (highest first)
        // Using reversed() because higher relevance score = better match
        sorted.sort(Comparator.comparingDouble(QueryResultItem::score).reversed());

        return sorted;
    }

    /**
     * Sorts results by distance in ascending order.
     *
     * <p>This method is used for vector distance metrics (L2, cosine distance)
     * where lower values indicate closer matches.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results sorted by distance ascending (closest first)</li>
     *   <li>L2 distance should be in range [0.0, ∞)</li>
     *   <li>Cosine distance should be in range [0.0, 2.0]</li>
     *   <li>Original list is not modified (returns new sorted list)</li>
     * </ul>
     *
     * @param results The results to sort (may be null or empty)
     * @return New list sorted by distance ascending
     */
    public static List<QueryResultItem> sortByDistanceAscending(List<QueryResultItem> results) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }

        // Create a new list to avoid modifying the original
        List<QueryResultItem> sorted = new ArrayList<>(results);

        // Sort by distance ascending (closest first)
        sorted.sort(Comparator.comparingDouble(QueryResultItem::score));

        return sorted;
    }

    /**
     * Sorts results based on the specified sorting mode.
     *
     * <p>Convenience method that delegates to the appropriate sorting method
     * based on the sorting mode.</p>
     *
     * @param results The results to sort
     * @param mode The sorting mode
     * @return New sorted list
     */
    public static List<QueryResultItem> sort(List<QueryResultItem> results, SortMode mode) {
        if (mode == SortMode.RELEVANCE_DESCENDING) {
            return sortByRelevanceDescending(results);
        } else {
            return sortByDistanceAscending(results);
        }
    }

    /**
     * Sorting mode for query results.
     */
    public enum SortMode {
        /**
         * Sort by distance ascending (lower is better).
         * Used for L2 distance and other distance metrics.
         */
        DISTANCE_ASCENDING,

        /**
         * Sort by relevance score descending (higher is better).
         * Used for LLM relevancy scores and similarity metrics.
         */
        RELEVANCE_DESCENDING
    }
}
