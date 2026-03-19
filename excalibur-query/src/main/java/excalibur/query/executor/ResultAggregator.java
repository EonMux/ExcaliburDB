package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResultItem;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are always sorted (closest/smallest distance first)</li>
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
     * Merges results from multiple segment queries.
     *
     * <p>This method takes the list of segment query results and produces
     * a unified list of top-k QueryResultItems.</p>
     *
     * @param segmentResults Results from each segment
     * @return Aggregated and ranked results (at most topK items)
     */
    public List<QueryResultItem> mergeAndRank(List<SegmentQueryTask.SegmentQueryResult> segmentResults) {
        Objects.requireNonNull(segmentResults, "Segment results cannot be null");

        if (segmentResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Use a min-heap approach for efficient top-k selection
        // Max-heap: keep largest (worst) at top so we can remove it
        PriorityQueue<QueryResultItem> topResults = new PriorityQueue<>(
            topK,
            Comparator.comparingDouble(QueryResultItem::score).reversed()
        );

        // Track seen document IDs for deduplication
        Set<Long> seenDocumentIds = new HashSet<>();

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

                long documentId = hnswResult.nodeId();

                // Skip duplicates
                if (!seenDocumentIds.add(documentId)) {
                    continue;
                }

                // Create result item
                QueryResultItem item = new QueryResultItem(
                    documentId,
                    hnswResult.distance(),
                    null, // Payload would be fetched separately
                    segmentResult.segmentId(),
                    hnswResult.nodeId()
                );

                // Add to top-k heap
                if (topResults.size() < topK) {
                    topResults.add(item);
                } else if (hnswResult.distance() < topResults.peek().score()) {
                    topResults.poll();
                    topResults.add(item);
                }
            }
        }

        // Convert heap to sorted list (smallest distance first)
        List<QueryResultItem> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(QueryResultItem::score));

        LOGGER.trace("Aggregated {} results from {} segments",
            results.size(), segmentResults.size());

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
     * @param allResults Results from all segments
     * @param k Number of results to return
     * @return Top-k merged results
     */
    public static List<HnswSearchResult> mergeResults(
            List<List<HnswSearchResult>> allResults, int k) {

        if (allResults == null || allResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Use a max-heap for top-k selection
        PriorityQueue<HnswSearchResult> topResults = new PriorityQueue<>(
            k,
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        Set<Long> seenNodeIds = new HashSet<>();

        for (List<HnswSearchResult> segmentResults : allResults) {
            if (segmentResults == null) continue;

            for (HnswSearchResult result : segmentResults) {
                // Skip duplicates
                if (!seenNodeIds.add(result.nodeId())) {
                    continue;
                }

                if (topResults.size() < k) {
                    topResults.add(result);
                } else if (result.distance() < topResults.peek().distance()) {
                    topResults.poll();
                    topResults.add(result);
                }
            }
        }

        // Convert to sorted list
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
}
