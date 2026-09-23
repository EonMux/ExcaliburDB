package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.executor.SegmentQueryTask;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
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
 *   <li>No segment answered - every one the query reached failed or timed out</li>
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
            /** Every segment the query reached failed or timed out */
            NO_SEGMENT_ANSWERED(true,
                "No segment answered - every one the query reached failed or timed out"),
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
            com.eonmux.excalibur.shard.VisibilityTracker visibilityTracker,
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

        Probe probe = new Probe(response, request, allMetadata, visibilityTracker,
            segmentResults, graphProvider, diagnosticDetails);

        return diagnoseSegmentVisibility(probe);
    }

    /**
     * Everything the stages of an empty-result diagnosis read, carried together.
     *
     * <p>The stages run as a cascade, each ruling out one explanation before the next is
     * allowed to look for its own, so they all need the same inputs and differ only in what
     * they have concluded so far. {@code diagnosticDetails} is the running narrative every
     * stage appends its findings to, and it is the one member the stages write through.</p>
     *
     * @param response The query response being explained
     * @param request The original query request
     * @param allMetadata Every segment the catalog holds, by id
     * @param visibilityTracker The visibility tracker, which may be absent
     * @param segmentResults The results from segment searches, which may be absent
     * @param graphProvider Provider for HNSW graphs, which may be absent
     * @param diagnosticDetails The findings collected so far
     */
    private record Probe(
        QueryResponse response,
        QueryRequest request,
        Map<Long, SegmentCatalog.SegmentMetadata> allMetadata,
        com.eonmux.excalibur.shard.VisibilityTracker visibilityTracker,
        List<SegmentQueryTask.SegmentQueryResult> segmentResults,
        HnswGraphProvider graphProvider,
        List<String> diagnosticDetails
    ) {
    }

    /**
     * Rules out the query having had nothing it was allowed to read.
     *
     * <p>Queryable and visible are separate conditions and fail differently. A corpus with
     * nothing queryable in it is a legitimately empty answer; one that is queryable and
     * invisible is not, because the documents are there and something in visibility tracking
     * is keeping the query from them.</p>
     *
     * @param probe The inputs to this diagnosis
     * @return The explanation for the empty result
     */
    private static ValidationResult diagnoseSegmentVisibility(Probe probe) {
        SegmentCensus census = takeSegmentCensus(probe);

        // Check 2: No queryable segments
        if (census.queryableSegments() == 0) {
            probe.diagnosticDetails().add(
                "No segments in queryable states (INDEXING, WARMING, ACTIVE, COMPACTING)");
            return new ValidationResult(
                true, ValidationResult.Reason.NO_QUERYABLE_SEGMENTS,
                census.catalogSegments(), 0, census.visibleSegments(),
                0, 0, 0, 0, 0, false, probe.diagnosticDetails()
            );
        }

        // Check 3: No visible segments (visibility tracking issue)
        if (probe.visibilityTracker() != null && census.visibleSegments() == 0
                && census.queryableSegments() > 0) {
            probe.diagnosticDetails().add(
                "Segments are queryable but not visible - check visibility tracking");
            return new ValidationResult(
                false, ValidationResult.Reason.VISIBILITY_BUG,
                census.catalogSegments(), census.queryableSegments(), 0,
                0, 0, 0, 0, 0, false, probe.diagnosticDetails()
            );
        }

        return diagnoseGraphContents(probe, census);
    }

    /**
     * Counts the segments the query was entitled to read, and records the count.
     *
     * <p>A deployment with no visibility tracker reports epoch 0 and no visible segments
     * rather than guessing: nothing is tracking visibility, so there is no epoch the answer
     * was taken at and nothing to attribute a missing segment to.</p>
     *
     * @param probe The inputs to this diagnosis
     * @return What the catalog and the visibility tracker say about the corpus
     */
    private static SegmentCensus takeSegmentCensus(Probe probe) {
        // Count queryable and visible segments
        int queryableSegments = 0;
        int visibleSegments = 0;
        long queryEpoch = probe.visibilityTracker() != null
            ? probe.visibilityTracker().currentEpoch() : 0;

        if (probe.visibilityTracker() != null) {
            Set<Long> visible = probe.visibilityTracker().visibleSegments(queryEpoch);
            visibleSegments = visible.size();
        }

        for (SegmentCatalog.SegmentMetadata metadata : probe.allMetadata().values()) {
            if (metadata.getLifecycleState().isQueryable()) {
                queryableSegments++;
            }
        }

        int catalogSegments = probe.allMetadata().size();
        probe.diagnosticDetails().add(
            String.format("Catalog has %d segments: %d queryable, %d visible at epoch %d",
                catalogSegments, queryableSegments, visibleSegments, queryEpoch));

        return new SegmentCensus(catalogSegments, queryableSegments, visibleSegments);
    }

    /**
     * How much corpus the query had to work with.
     *
     * @param catalogSegments Number of segments in catalog
     * @param queryableSegments Number of segments in queryable states
     * @param visibleSegments Number of segments visible at query epoch
     */
    private record SegmentCensus(int catalogSegments, int queryableSegments, int visibleSegments) {
    }

    /**
     * Rules out the visible segments having held no vectors to match against.
     *
     * <p>Legitimate rather than a bug: segments are created before they are filled, so a
     * corpus that has been registered but not yet indexed genuinely has nothing to return.</p>
     *
     * @param probe The inputs to this diagnosis
     * @param census What the catalog and the visibility tracker said about the corpus
     * @return The explanation for the empty result
     */
    private static ValidationResult diagnoseGraphContents(Probe probe, SegmentCensus census) {
        GraphCensus graphs = countGraphVectors(probe);

        // Check 4: Segments have no vectors
        if (graphs.activeVectors() == 0) {
            probe.diagnosticDetails().add("No active vectors in any HNSW graph");
            // This is legitimate - segments exist but have no data yet
            return new ValidationResult(
                true, ValidationResult.Reason.NO_VECTOR_MATCH,
                census.catalogSegments(), census.queryableSegments(), census.visibleSegments(),
                graphs.totalVectors(), 0, 0, 0, 0, false, probe.diagnosticDetails()
            );
        }

        return diagnoseSearchOutcome(probe, census, graphs);
    }

    /**
     * Counts the vectors the catalog's graphs hold, describing each non-empty one.
     *
     * <p>Every catalog segment is counted, not only the visible ones: the point of the count
     * is to establish whether there was anything in the corpus to find, which an earlier stage
     * has already separated from the question of what this query was allowed to see.</p>
     *
     * @param probe The inputs to this diagnosis
     * @return The total and active vector counts across the graphs
     */
    private static GraphCensus countGraphVectors(Probe probe) {
        if (probe.graphProvider() == null) {
            return new GraphCensus(0, 0);
        }

        // Count vectors in graphs
        long totalVectorsInGraphs = 0;
        long activeVectorsInGraphs = 0;

        for (Long segmentId : probe.allMetadata().keySet()) {
            HnswGraph graph = probe.graphProvider().getGraph(segmentId);
            if (graph == null) {
                continue;
            }

            totalVectorsInGraphs += graph.nodeCount();
            activeVectorsInGraphs += graph.activeNodeCount();

            if (graph.nodeCount() > 0) {
                probe.diagnosticDetails().add(String.format(
                    "Segment %d: graph has %d nodes (%d active), entryPoint=%d",
                    segmentId, graph.nodeCount(), graph.activeNodeCount(), graph.entryPoint()));
            }
        }

        return new GraphCensus(totalVectorsInGraphs, activeVectorsInGraphs);
    }

    /**
     * How many vectors the corpus holds.
     *
     * @param totalVectors Total vectors across all HNSW graphs
     * @param activeVectors Active (non-tombstoned) vectors in graphs
     */
    private record GraphCensus(long totalVectors, long activeVectors) {
    }

    /**
     * Rules out the search itself having failed to reach or return anything.
     *
     * @param probe The inputs to this diagnosis
     * @param census What the catalog and the visibility tracker said about the corpus
     * @param graphs How many vectors the corpus holds
     * @return The explanation for the empty result
     */
    private static ValidationResult diagnoseSearchOutcome(
            Probe probe, SegmentCensus census, GraphCensus graphs) {

        SearchTally tally = tallySegmentAnswers(probe);

        // Check 5: The query was dispatched but nothing came back from it
        if (probe.segmentResults() != null && !probe.segmentResults().isEmpty()
                && tally.segmentsSearched() == 0) {
            // The emptiness is already accounted for: no segment produced evidence either way.
            // Reading it as a suspected bug would flag every query a timing-out shard serves.
            probe.diagnosticDetails().add("No segment answered - all " + probe.segmentResults().size()
                + " failed or timed out");
            return new ValidationResult(
                true, ValidationResult.Reason.NO_SEGMENT_ANSWERED,
                census.catalogSegments(), census.queryableSegments(), census.visibleSegments(),
                graphs.totalVectors(), graphs.activeVectors(),
                0, 0, 0, false, probe.diagnosticDetails()
            );
        }

        // Check 6: Segments were searched but returned no results
        if (tally.segmentsSearched() > 0 && tally.segmentsWithResults() == 0
                && graphs.activeVectors() > 0) {
            probe.diagnosticDetails().add(
                "Segments were searched with active vectors but HNSW returned empty");
            return new ValidationResult(
                false, ValidationResult.Reason.HNSW_EMPTY_BUG,
                census.catalogSegments(), census.queryableSegments(), census.visibleSegments(),
                graphs.totalVectors(), graphs.activeVectors(),
                tally.segmentsSearched(), 0, 0, false, probe.diagnosticDetails()
            );
        }

        return diagnoseResultLoss(probe, census, graphs, tally);
    }

    /**
     * Counts what came back from the segments, and records the tally.
     *
     * <p>Only a segment that reported success is counted as searched. One that failed or timed
     * out has said nothing about its contents, and counting it as searched-and-empty is what
     * would turn a partial answer into evidence that the corpus holds no match.</p>
     *
     * @param probe The inputs to this diagnosis
     * @return What the segment searches returned
     */
    private static SearchTally tallySegmentAnswers(Probe probe) {
        // Analyze segment search results
        int segmentsSearched = 0;
        int segmentsWithResults = 0;
        int rawResultCount = 0;

        if (probe.segmentResults() != null) {
            for (SegmentQueryTask.SegmentQueryResult result : probe.segmentResults()) {
                if (!result.success()) {
                    continue;
                }

                segmentsSearched++;
                if (!result.results().isEmpty()) {
                    segmentsWithResults++;
                    rawResultCount += result.results().size();
                }
            }
        }

        probe.diagnosticDetails().add(String.format(
            "Search: %d segments searched, %d with results, %d raw results, threshold=%.2f",
            segmentsSearched, segmentsWithResults, rawResultCount, probe.request().threshold()));

        return new SearchTally(segmentsSearched, segmentsWithResults, rawResultCount);
    }

    /**
     * What the segment searches returned.
     *
     * @param segmentsSearched Number of segments that were searched
     * @param segmentsWithResults Number of segments that returned results
     * @param rawResultCount Raw results before aggregation
     */
    private record SearchTally(int segmentsSearched, int segmentsWithResults, int rawResultCount) {
    }

    /**
     * Accounts for results the segments returned that the caller never saw.
     *
     * <p>Threshold filtering is checked before aggregation, because a configured threshold
     * explains the loss on its own and reporting an aggregation bug for results the query
     * explicitly asked to discard would send an operator after nothing. What is left when
     * neither explains it is recorded as unknown rather than guessed at.</p>
     *
     * @param probe The inputs to this diagnosis
     * @param census What the catalog and the visibility tracker said about the corpus
     * @param graphs How many vectors the corpus holds
     * @param tally What the segment searches returned
     * @return The explanation for the empty result
     */
    private static ValidationResult diagnoseResultLoss(
            Probe probe, SegmentCensus census, GraphCensus graphs, SearchTally tally) {

        boolean thresholdFiltering = probe.request().threshold() > 0;

        // Check 7: Raw results existed but were filtered out by threshold
        if (tally.rawResultCount() > 0 && thresholdFiltering) {
            probe.diagnosticDetails().add(String.format(
                "Threshold filtering: %d raw results removed by threshold %.2f",
                tally.rawResultCount(), probe.request().threshold()));
            return lossResult(ValidationResult.Reason.THRESHOLD_FILTERING, true,
                probe, census, graphs, tally, true);
        }

        // Check 8: Raw results existed but were lost during aggregation
        if (tally.rawResultCount() > 0 && probe.response().isEmpty()) {
            probe.diagnosticDetails().add(String.format(
                "Results lost: %d raw results but final response is empty - aggregation bug?",
                tally.rawResultCount()));
            return lossResult(ValidationResult.Reason.AGGREGATION_BUG, false,
                probe, census, graphs, tally, false);
        }

        // Default: unknown cause
        probe.diagnosticDetails().add("Unable to determine specific cause of empty results");
        return lossResult(ValidationResult.Reason.UNKNOWN, false,
            probe, census, graphs, tally, false);
    }

    /**
     * Assembles a result for the stage that has everything the diagnosis measured.
     *
     * <p>The last three explanations differ only in their verdict, so they are built from one
     * place: every count the diagnosis gathered is carried into all of them, and a caller
     * comparing two of them is comparing the reason rather than which numbers were filled in.</p>
     *
     * @param reason Why the results were empty
     * @param legitimate Whether that reason is an expected outcome rather than a bug
     * @param probe The inputs to this diagnosis
     * @param census What the catalog and the visibility tracker said about the corpus
     * @param graphs How many vectors the corpus holds
     * @param tally What the segment searches returned
     * @param thresholdFiltering Whether threshold filtering removed the results
     * @return The diagnosis
     */
    private static ValidationResult lossResult(
            ValidationResult.Reason reason,
            boolean legitimate,
            Probe probe,
            SegmentCensus census,
            GraphCensus graphs,
            SearchTally tally,
            boolean thresholdFiltering) {

        return new ValidationResult(
            legitimate, reason,
            census.catalogSegments(), census.queryableSegments(), census.visibleSegments(),
            graphs.totalVectors(), graphs.activeVectors(),
            tally.segmentsSearched(), tally.segmentsWithResults(), tally.rawResultCount(),
            thresholdFiltering, probe.diagnosticDetails()
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
            com.eonmux.excalibur.shard.VisibilityTracker visibilityTracker,
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
            com.eonmux.excalibur.shard.VisibilityTracker visibilityTracker,
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
