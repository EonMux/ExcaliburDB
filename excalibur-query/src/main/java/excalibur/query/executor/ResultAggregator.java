package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResultItem;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.DistanceValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

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

    /**
     * Upper bound on the initial capacity handed to the top-k selection heap.
     *
     * <p>{@code topK} is client-controlled, so it must never be used directly as an
     * array-backed collection's initial capacity: a large value would allocate that
     * much memory before a single result had been produced. The heap grows on demand,
     * so capping the initial size costs nothing for genuinely large result sets.</p>
     */
    static final int MAX_HEAP_INITIAL_CAPACITY = 1024;

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
     * <p>Segment results carry {@link HnswSearchResult#distance()}, which is a monotone
     * dissimilarity (smaller is closer) for every metric. This method converts those
     * values to [0.0, 1.0] scores where higher is always better, regardless of the
     * underlying distance metric.</p>
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
        Map<String, QueryResultItem> bestResultsByDocId =
            bestPerDocument(segmentResults, distanceFunction);

        // Phase 2: Select top-k results using a min-heap
        PriorityQueue<QueryResultItem> topResults = selectTopK(bestResultsByDocId.values());

        // Phase 3: Convert heap to sorted list (highest score first)
        List<QueryResultItem> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(QueryResultItem::score).reversed());

        LOGGER.trace("Aggregated {} results from {} segments ({} unique documents)",
            results.size(), segmentResults.size(), bestResultsByDocId.size());

        return results;
    }

    /**
     * Keeps one result per document, the best-scoring one wherever several segments hold it.
     *
     * <p>A failed segment contributes nothing at all. Its result carries whatever its task had
     * recorded before it failed, and ranking a partial read alongside complete ones would put
     * an arbitrary subset of a segment's documents into the answer.</p>
     *
     * @param segmentResults Results from each segment
     * @param distanceFunction The distance function used, for normalization
     * @return The best result for each document, keyed as {@link #dedupeKey} describes
     */
    private Map<String, QueryResultItem> bestPerDocument(
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            DistanceFunction distanceFunction) {

        Map<String, QueryResultItem> bestResultsByDocId = new HashMap<>();

        for (SegmentQueryTask.SegmentQueryResult segmentResult : segmentResults) {
            if (!segmentResult.success()) {
                LOGGER.debug("Skipping failed segment {} results", segmentResult.segmentId());
                continue;
            }

            for (HnswSearchResult hnswResult : segmentResult.results()) {
                QueryResultItem item = rankable(segmentResult, hnswResult, distanceFunction);
                if (item == null) {
                    continue;
                }

                // Keep the best (highest normalized score) result for each document
                String dedupeKey = dedupeKey(hnswResult.documentId(), segmentResult.segmentId(),
                    hnswResult.nodeId());
                bestResultsByDocId.merge(dedupeKey, item, (existing, newItem) ->
                    newItem.score() > existing.score() ? newItem : existing
                );
            }
        }

        return bestResultsByDocId;
    }

    /**
     * Puts one segment hit onto the common score scale, or drops it.
     *
     * <p>Every rejection here is a hit that cannot be compared with the others rather than one
     * that compares badly: a distance that is not a number, a score that is not a number, or a
     * score the caller's threshold excludes. Letting the first two through would abort the
     * whole aggregation when {@link QueryResultItem} validates them, losing every other
     * segment's answer along with the bad hit.</p>
     *
     * @param segmentResult The segment the hit came from, which also carries its chunk text
     * @param hnswResult The hit as the segment reported it
     * @param distanceFunction The distance function used, for normalization
     * @return The hit on the common [0.0, 1.0] scale, or null when it cannot be ranked
     */
    private QueryResultItem rankable(SegmentQueryTask.SegmentQueryResult segmentResult,
                                     HnswSearchResult hnswResult,
                                     DistanceFunction distanceFunction) {
        // Drop non-finite distances at the source: they cannot be ranked and would
        // otherwise abort the whole aggregation when QueryResultItem validates them.
        if (!Float.isFinite(hnswResult.distance())) {
            LOGGER.debug("Skipping result with non-finite distance {}: nodeId={}, documentId={}",
                hnswResult.distance(), hnswResult.nodeId(), hnswResult.documentId());
            return null;
        }

        // distance() is an ordered dissimilarity (smaller is closer) for every metric,
        // so it must be converted back to the metric's natural scale before normalizing.
        float normalizedScore = DistanceValidator.validateClampAndNormalizeOrderedDistance(
            hnswResult.distance(), distanceFunction);

        // Skip items with NaN scores - they cannot be properly ranked
        if (Float.isNaN(normalizedScore)) {
            LOGGER.debug("Skipping result with NaN score: nodeId={}, documentId={}",
                hnswResult.nodeId(), hnswResult.documentId());
            return null;
        }

        // Apply threshold filter on normalized score (lower = worse, so filter out)
        if (threshold > 0 && normalizedScore < threshold) {
            return null;
        }

        String documentIdStr = hnswResult.documentId();
        long nodeId = hnswResult.nodeId();

        // Create result item with normalized score. A tail-scan hit carries the
        // chunk text the scan already read; a graph hit has none here and is
        // resolved from storage later, once selection has narrowed the set.
        return new QueryResultItem(
            nodeId,
            documentIdStr,
            normalizedScore,
            segmentResult.payload(nodeId),
            segmentResult.segmentId(),
            nodeId,
            hnswResult.generation()
        );
    }

    /**
     * Keeps the {@code topK} highest-scoring items, in a heap ordered worst-first.
     *
     * <p>A min-heap rather than a full sort: the deduplicated set is as large as every segment's
     * oversampled answer put together, and only its best {@code topK} can reach the caller.</p>
     *
     * @param items The deduplicated results, in no particular order
     * @return A heap holding at most {@code topK} items, the worst of them at its head
     */
    private PriorityQueue<QueryResultItem> selectTopK(Collection<QueryResultItem> items) {
        // Min-heap: keep smallest at top so we can remove the worst
        PriorityQueue<QueryResultItem> topResults = new PriorityQueue<>(
            heapCapacity(topK),
            Comparator.comparingDouble(QueryResultItem::score)
        );

        for (QueryResultItem item : items) {
            if (topResults.size() < topK) {
                topResults.add(item);
            } else if (item.score() > topResults.peek().score()) {
                topResults.poll();
                topResults.add(item);
            }
        }

        return topResults;
    }

    /**
     * Builds the deduplication key for a result.
     *
     * <p>The string document ID identifies a document across the whole corpus and is
     * used whenever it is available. Node IDs, in contrast, are dense and allocated
     * per HNSW graph - every segment restarts them at 0, and write-log tail scans
     * derive theirs from a per-segment LSN - so a bare node ID collides across
     * segments and would silently discard a legitimate hit from another segment.
     * The fallback key is therefore scoped by segment.</p>
     *
     * @param documentIdStr The string document ID, or null when unknown
     * @param segmentId The segment that produced the result
     * @param nodeId The node ID within that segment
     * @return A key that is unique per document, or per (segment, node) when the
     *         document ID is unknown
     */
    private static String dedupeKey(String documentIdStr, long segmentId, long nodeId) {
        if (documentIdStr != null) {
            return documentIdStr;
        }
        return segmentId + ":" + nodeId;
    }

    /**
     * Returns a safe initial capacity for a top-k selection heap.
     *
     * @param topK The requested result count (client-controlled)
     * @return The initial capacity, capped at {@link #MAX_HEAP_INITIAL_CAPACITY}
     */
    private static int heapCapacity(int topK) {
        return Math.max(1, Math.min(topK, MAX_HEAP_INITIAL_CAPACITY));
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
            } else if (result.errorMessage() != null
                    && result.errorMessage().toLowerCase(Locale.ROOT).contains("timeout")) {
                // One failure is one count. A segment reported as both an error and a timeout
                // reads as two segments in trouble, and is subtracted from coverage twice.
                segmentsTimedOut++;
            } else {
                segmentsWithErrors++;
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
     * <p><strong>Node IDs must share one address space.</strong> Every HNSW graph
     * allocates node IDs from zero, so results from different segments collide here.
     * Use this method only to merge result lists that come from a single segment
     * (for example an HNSW search and that segment's write-log tail scan); to merge
     * across segments use {@link #mergeAndRankWithNormalization}, which deduplicates
     * on a segment-scoped key.</p>
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

        if (allResults == null || allResults.isEmpty() || k <= 0) {
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
            heapCapacity(k),
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
