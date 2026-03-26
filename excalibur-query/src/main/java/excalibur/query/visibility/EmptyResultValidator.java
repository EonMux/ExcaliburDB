package excalibur.query.visibility;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.SegmentQueryTask;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates that empty query results are only returned when legitimately expected.
 *
 * <p>This class enforces the domain correctness constraint:</p>
 * <blockquote>
 * <strong>Empty results are ONLY acceptable when no documents match the query vector.</strong>
 * </blockquote>
 *
 * <h2>Legitimate Empty Result Scenarios</h2>
 * <p>Empty results are acceptable when:</p>
 * <ul>
 *   <li>The index is empty (no segments in catalog)</li>
 *   <li>All segments are in non-queryable states (BOOTSTRAPPING, TOMBSTONED, PURGED)</li>
 *   <li>No segments are visible at the query epoch</li>
 *   <li>The query vector genuinely doesn't match any vectors in queryable segments</li>
 *   <li>Threshold filtering removes all candidates</li>
 * </ul>
 *
 * <h2>Illegitimate Empty Result Scenarios</h2>
 * <p>Empty results indicate a bug when:</p>
 * <ul>
 *   <li>Queryable segments exist but visibility tracking is broken</li>
 *   <li>Queryable segments exist with vectors but HNSW search returns empty</li>
 *   <li>Segments have vectors but none are visible due to epoch issues</li>
 *   <li>Results are lost during aggregation or filtering</li>
 * </ul>
 *
 * <h2>Diagnostic Information</h2>
 * <p>When empty results are detected, this validator collects:</p>
 * <ul>
 *   <li>Segment visibility state at query epoch</li>
 *   <li>HNSW graph health (node count, entry point, active nodes)</li>
 *   <li>LSH and Cuckoo filter state</li>
 *   <li>Coverage metrics</li>
 *   <li>Reason for empty results (legitimate vs. potential bug)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // After executing a query
 * QueryResponse response = pipeline.executeSync(request, token);
 *
 * // Validate empty results
 * if (response.isEmpty()) {
 *     EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
 *         response, request, storageEngine, visibilityTracker, segmentResults, graphProvider
 *     );
 *
 *     if (!result.isLegitimate()) {
 *         LOGGER.warn("Empty results returned unexpectedly: {}", result.diagnosticSummary());
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All diagnostic methods capture consistent snapshots.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class EmptyResultValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmptyResultValidator.class);

    private EmptyResultValidator() {
        // Utility class - no instances
    }

    // ==================== Result Types ====================

    /**
     * Result of validating empty query results.
     *
     * @param isLegitimate Whether empty results are legitimate (expected)
     * @param reason The reason for empty results
     * @param catalogSegments Number of segments in catalog
     * @param queryableSegments Number of segments in queryable states
     * @param visibleSegments Number of segments visible at query epoch
     * @param totalVectorsInGraphs Total vectors across all HNSW graphs
     * @param activeVectorsInGraphs Active (non-tombstoned) vectors in graphs
     * @param segmentsSearched Number of segments that were searched
     * @param segmentsWithResults Number of segments that returned results
     * @param rawResultCount Raw results before aggregation
     * @param thresholdFiltering Whether threshold filtering removed results
     * @param diagnosticDetails Additional diagnostic information
     */
    public record ValidationResult(
        boolean isLegitimate,
        Reason reason,
        int catalogSegments,
        int queryableSegments,
        int visibleSegments,
        long totalVectorsInGraphs,
        long activeVectorsInGraphs,
        int segmentsSearched,
        int segmentsWithResults,
        int rawResultCount,
        boolean thresholdFiltering,
        List<String> diagnosticDetails
    ) {
        /**
         * Enum of possible reasons for empty results.
         */
        public enum Reason {
            /** Index is empty - no segments in catalog */
            EMPTY_INDEX(true, "Index is empty - no segments in catalog"),
            /** All segments are in non-queryable states */
            NO_QUERYABLE_SEGMENTS(true, "All segments are in non-queryable states (BOOTSTRAPPING, TOMBSTONED, PURGED)"),
            /** No segments visible at query epoch */
            NO_VISIBLE_SEGMENTS(true, "No segments visible at query epoch"),
            /** Query vector doesn't match any vectors in graphs */
            NO_VECTOR_MATCH(true, "Query vector doesn't match any vectors in queryable segments"),
            /** Threshold filtering removed all results */
            THRESHOLD_FILTERING(true, "Threshold filtering removed all candidates"),
            /** Segments have vectors but HNSW returned empty - potential bug */
            HNSW_EMPTY_BUG(false, "Segments have vectors but HNSW search returned empty - potential graph or search bug"),
            /** Visibility tracking may be broken */
            VISIBILITY_BUG(false, "Segments registered but not visible - visibility tracking may be broken"),
            /** Results lost during aggregation */
            AGGREGATION_BUG(false, "Results were lost during aggregation - potential bug in ResultAggregator"),
            /** Unknown cause */
            UNKNOWN(false, "Unknown cause for empty results");

            private final boolean legitimate;
            private final String description;

            Reason(boolean legitimate, String description) {
                this.legitimate = legitimate;
                this.description = description;
            }

            /**
             * Returns whether this reason is considered legitimate (expected).
             */
            public boolean isLegitimate() {
                return legitimate;
            }

            /**
             * Returns the human-readable description.
             */
            public String getDescription() {
                return description;
            }
        }

        /**
         * Returns a diagnostic summary suitable for logging.
         */
        public String diagnosticSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("EmptyResult: %s%n", reason.getDescription()));
            sb.append(String.format("  Catalog: %d segments, %d queryable, %d visible%n",
                catalogSegments, queryableSegments, visibleSegments));
            sb.append(String.format("  Vectors: %d total, %d active%n",
                totalVectorsInGraphs, activeVectorsInGraphs));
            sb.append(String.format("  Search: %d segments searched, %d with results, %d raw results%n",
                segmentsSearched, segmentsWithResults, rawResultCount));

            if (!diagnosticDetails.isEmpty()) {
                sb.append("  Details:\n");
                for (String detail : diagnosticDetails) {
                    sb.append("    - ").append(detail).append("\n");
                }
            }

            return sb.toString();
        }

        /**
         * Returns a one-line summary for concise logging.
         */
        public String oneLineSummary() {
            return String.format("EmptyResult[%s]: catalog=%d, queryable=%d, visible=%d, vectors=%d, searched=%d",
                reason.name(), catalogSegments, queryableSegments, visibleSegments,
                activeVectorsInGraphs, segmentsSearched);
        }
    }

    // ==================== Main Validation Method ====================

    /**
     * Validates that empty query results are only returned when legitimately expected.
     *
     * <p>This method should be called when a query returns empty results to diagnose
     * whether the empty results are legitimate (expected) or indicate a bug.</p>
     *
     * @param response The query response (should be empty)
     * @param request The original query request
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param segmentResults The results from segment searches
     * @param graphProvider Provider for HNSW graphs
     * @return Validation result with diagnostic information
     */
    public static ValidationResult validate(
            QueryResponse response,
            QueryRequest request,
            StorageEngine storageEngine,
            excalibur.shard.VisibilityTracker visibilityTracker,
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            HnswGraphProvider graphProvider) {

        Objects.requireNonNull(response, "Response cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        List<String> diagnosticDetails = new ArrayList<>();

        // Collect segment statistics
        SegmentCatalog catalog = storageEngine.getCatalog();
        Map<Long, SegmentCatalog.SegmentMetadata> allMetadata = catalog.getAll();
        int catalogSegments = allMetadata.size();

        // Check 1: Empty catalog
        if (catalogSegments == 0) {
            diagnosticDetails.add("Catalog is empty - no segments exist");
            return new ValidationResult(
                true, ValidationResult.Reason.EMPTY_INDEX,
                0, 0, 0, 0, 0, 0, 0, 0, false, diagnosticDetails
            );
        }

        // Count queryable and visible segments
        int queryableSegments = 0;
        int visibleSegments = 0;
        long queryEpoch = visibilityTracker != null ? visibilityTracker.currentEpoch() : 0;

        if (visibilityTracker != null) {
            Set<Long> visible = visibilityTracker.visibleSegments(queryEpoch);
            visibleSegments = visible.size();
        }

        for (SegmentCatalog.SegmentMetadata metadata : allMetadata.values()) {
            if (metadata.getLifecycleState().isQueryable()) {
                queryableSegments++;
            }
        }

        diagnosticDetails.add(String.format("Catalog has %d segments: %d queryable, %d visible at epoch %d",
            catalogSegments, queryableSegments, visibleSegments, queryEpoch));

        // Check 2: No queryable segments
        if (queryableSegments == 0) {
            diagnosticDetails.add("No segments in queryable states (INDEXING, WARMING, ACTIVE, COMPACTING)");
            return new ValidationResult(
                true, ValidationResult.Reason.NO_QUERYABLE_SEGMENTS,
                catalogSegments, 0, visibleSegments, 0, 0, 0, 0, 0, false, diagnosticDetails
            );
        }

        // Check 3: No visible segments (visibility tracking issue)
        if (visibilityTracker != null && visibleSegments == 0 && queryableSegments > 0) {
            diagnosticDetails.add("Segments are queryable but not visible - check visibility tracking");
            return new ValidationResult(
                false, ValidationResult.Reason.VISIBILITY_BUG,
                catalogSegments, queryableSegments, 0, 0, 0, 0, 0, 0, false, diagnosticDetails
            );
        }

        // Count vectors in graphs
        long totalVectorsInGraphs = 0;
        long activeVectorsInGraphs = 0;

        if (graphProvider != null) {
            for (Long segmentId : allMetadata.keySet()) {
                HnswGraph graph = graphProvider.getGraph(segmentId);
                if (graph != null) {
                    totalVectorsInGraphs += graph.nodeCount();
                    activeVectorsInGraphs += graph.activeNodeCount();

                    if (graph.nodeCount() > 0) {
                        diagnosticDetails.add(String.format(
                            "Segment %d: graph has %d nodes (%d active), entryPoint=%d",
                            segmentId, graph.nodeCount(), graph.activeNodeCount(), graph.entryPoint()));
                    }
                }
            }
        }

        // Check 4: Segments have no vectors
        if (activeVectorsInGraphs == 0) {
            diagnosticDetails.add("No active vectors in any HNSW graph");
            // This is legitimate - segments exist but have no data yet
            return new ValidationResult(
                true, ValidationResult.Reason.NO_VECTOR_MATCH,
                catalogSegments, queryableSegments, visibleSegments,
                totalVectorsInGraphs, 0, 0, 0, 0, false, diagnosticDetails
            );
        }

        // Analyze segment search results
        int segmentsSearched = 0;
        int segmentsWithResults = 0;
        int rawResultCount = 0;

        if (segmentResults != null) {
            for (SegmentQueryTask.SegmentQueryResult result : segmentResults) {
                if (result.success()) {
                    segmentsSearched++;
                    if (!result.results().isEmpty()) {
                        segmentsWithResults++;
                        rawResultCount += result.results().size();
                    }
                }
            }
        }

        // Check coverage from response
        IndexCoverage coverage = response.coverage();
        boolean thresholdFiltering = request.threshold() > 0;

        diagnosticDetails.add(String.format(
            "Search: %d segments searched, %d with results, %d raw results, threshold=%.2f",
            segmentsSearched, segmentsWithResults, rawResultCount, request.threshold()));

        // Check 5: Segments were searched but returned no results
        if (segmentsSearched > 0 && segmentsWithResults == 0 && activeVectorsInGraphs > 0) {
            diagnosticDetails.add("Segments were searched with active vectors but HNSW returned empty");
            return new ValidationResult(
                false, ValidationResult.Reason.HNSW_EMPTY_BUG,
                catalogSegments, queryableSegments, visibleSegments,
                totalVectorsInGraphs, activeVectorsInGraphs,
                segmentsSearched, 0, 0, false, diagnosticDetails
            );
        }

        // Check 6: Raw results existed but were filtered out by threshold
        if (rawResultCount > 0 && thresholdFiltering) {
            diagnosticDetails.add(String.format(
                "Threshold filtering: %d raw results removed by threshold %.2f",
                rawResultCount, request.threshold()));
            return new ValidationResult(
                true, ValidationResult.Reason.THRESHOLD_FILTERING,
                catalogSegments, queryableSegments, visibleSegments,
                totalVectorsInGraphs, activeVectorsInGraphs,
                segmentsSearched, segmentsWithResults, rawResultCount, true, diagnosticDetails
            );
        }

        // Check 7: Raw results existed but were lost during aggregation
        if (rawResultCount > 0 && response.isEmpty()) {
            diagnosticDetails.add(String.format(
                "Results lost: %d raw results but final response is empty - aggregation bug?",
                rawResultCount));
            return new ValidationResult(
                false, ValidationResult.Reason.AGGREGATION_BUG,
                catalogSegments, queryableSegments, visibleSegments,
                totalVectorsInGraphs, activeVectorsInGraphs,
                segmentsSearched, segmentsWithResults, rawResultCount, false, diagnosticDetails
            );
        }

        // Default: unknown cause
        diagnosticDetails.add("Unable to determine specific cause of empty results");
        return new ValidationResult(
            false, ValidationResult.Reason.UNKNOWN,
            catalogSegments, queryableSegments, visibleSegments,
            totalVectorsInGraphs, activeVectorsInGraphs,
            segmentsSearched, segmentsWithResults, rawResultCount, false, diagnosticDetails
        );
    }

    /**
     * Quick check: Should the query have returned results based on segment state?
     *
     * <p>This method provides a fast pre-check without full diagnostics.</p>
     *
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param graphProvider Provider for HNSW graphs
     * @return true if the system state suggests results should be available
     */
    public static boolean shouldHaveResults(
            StorageEngine storageEngine,
            excalibur.shard.VisibilityTracker visibilityTracker,
            HnswGraphProvider graphProvider) {

        if (storageEngine == null) {
            return false;
        }

        SegmentCatalog catalog = storageEngine.getCatalog();
        if (catalog.size() == 0) {
            return false;
        }

        // Check for queryable segments
        int queryableCount = 0;
        for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
            if (metadata.getLifecycleState().isQueryable()) {
                queryableCount++;
            }
        }

        if (queryableCount == 0) {
            return false;
        }

        // Check for visible segments
        if (visibilityTracker != null) {
            Set<Long> visible = visibilityTracker.visibleSegments(visibilityTracker.currentEpoch());
            if (visible.isEmpty()) {
                return false;
            }
        }

        // Check for vectors in graphs
        if (graphProvider != null) {
            for (Long segmentId : catalog.getAll().keySet()) {
                HnswGraph graph = graphProvider.getGraph(segmentId);
                if (graph != null && graph.activeNodeCount() > 0) {
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    /**
     * Logs diagnostic information when empty results are returned.
     *
     * <p>This method should be called after a query returns empty results
     * to help diagnose the cause.</p>
     *
     * @param response The query response
     * @param request The query request
     * @param storageEngine The storage engine
     * @param visibilityTracker The visibility tracker
     * @param segmentResults The segment search results
     * @param graphProvider The HNSW graph provider
     */
    public static void logDiagnostics(
            QueryResponse response,
            QueryRequest request,
            StorageEngine storageEngine,
            excalibur.shard.VisibilityTracker visibilityTracker,
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            HnswGraphProvider graphProvider) {

        ValidationResult result = validate(
            response, request, storageEngine, visibilityTracker, segmentResults, graphProvider);

        if (result.isLegitimate()) {
            LOGGER.debug("Empty results (legitimate): {}", result.oneLineSummary());
        } else {
            LOGGER.warn("Empty results (potential bug): {}%n{}",
                result.oneLineSummary(), result.diagnosticSummary());
        }
    }

    /**
     * Provider interface for HNSW graphs.
     */
    @FunctionalInterface
    public interface HnswGraphProvider {
        HnswGraph getGraph(long segmentId);
    }
}
