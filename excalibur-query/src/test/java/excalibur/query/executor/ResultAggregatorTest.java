package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResultItem;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ResultAggregator.
 */
class ResultAggregatorTest {

    /**
     * Test constructor with valid parameters.
     */
    @Test
    void testConstructor() {
        ResultAggregator aggregator = new ResultAggregator(10, 0.0f, 0.5);
        assertThat(aggregator.topK()).isEqualTo(10);
        assertThat(aggregator.threshold()).isEqualTo(0.0f);
        assertThat(aggregator.coverageThreshold()).isEqualTo(0.5);
    }

    /**
     * Test constructor from QueryOptions.
     */
    @Test
    void testConstructorFromQueryOptions() {
        QueryOptions options = QueryOptions.builder()
            .topK(25)
            .threshold(0.5f)
            .build();
        ResultAggregator aggregator = new ResultAggregator(options);
        assertThat(aggregator.topK()).isEqualTo(25);
        assertThat(aggregator.threshold()).isEqualTo(0.5f);
    }

    /**
     * Test constructor with default settings.
     */
    @Test
    void testConstructorWithDefaults() {
        ResultAggregator aggregator = new ResultAggregator(10);
        assertThat(aggregator.topK()).isEqualTo(10);
        assertThat(aggregator.threshold()).isEqualTo(0.0f);
    }

    /**
     * Test constructor rejects invalid topK.
     */
    @Test
    void testConstructorInvalidTopK() {
        assertThatThrownBy(() -> new ResultAggregator(0, 0.0f, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("topK must be positive");

        assertThatThrownBy(() -> new ResultAggregator(-1, 0.0f, 0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("topK must be positive");
    }

    /**
     * Test mergeAndRank with empty results.
     */
    @Test
    void testMergeAndRankEmptyResults() {
        ResultAggregator aggregator = new ResultAggregator(10);
        List<SegmentQueryTask.SegmentQueryResult> results = new ArrayList<>();
        List<QueryResultItem> merged = aggregator.mergeAndRank(results);
        assertThat(merged).isEmpty();
    }

    /**
     * Test mergeAndRank with null results throws.
     */
    @Test
    void testMergeAndRankNullResults() {
        ResultAggregator aggregator = new ResultAggregator(10);
        assertThatThrownBy(() -> aggregator.mergeAndRank(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Segment results cannot be null");
    }

    /**
     * Test mergeAndRank with single segment.
     */
    @Test
    void testMergeAndRankSingleSegment() {
        ResultAggregator aggregator = new ResultAggregator(10);

        List<HnswSearchResult> hnswResults = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.2f),
            new HnswSearchResult(3L, 0.3f)
        );

        SegmentQueryTask.SegmentQueryResult segmentResult = new SegmentQueryTask.SegmentQueryResult(
            1L, hnswResults, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(segmentResult));

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
    }

    /**
     * Test mergeAndRank with multiple segments.
     */
    @Test
    void testMergeAndRankMultipleSegments() {
        ResultAggregator aggregator = new ResultAggregator(5);

        // Segment 1: distances 0.1, 0.3, 0.5
        List<HnswSearchResult> segment1Results = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.3f),
            new HnswSearchResult(3L, 0.5f)
        );

        // Segment 2: distances 0.2, 0.4, 0.6
        List<HnswSearchResult> segment2Results = List.of(
            new HnswSearchResult(4L, 0.2f),
            new HnswSearchResult(5L, 0.4f),
            new HnswSearchResult(6L, 0.6f)
        );

        SegmentQueryTask.SegmentQueryResult result1 = new SegmentQueryTask.SegmentQueryResult(
            1L, segment1Results, 0, 10, true, null
        );
        SegmentQueryTask.SegmentQueryResult result2 = new SegmentQueryTask.SegmentQueryResult(
            2L, segment2Results, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(result1, result2));

        assertThat(merged).hasSize(5);
        // Should be sorted by distance
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
        assertThat(merged.get(3).score()).isEqualTo(0.4f);
        assertThat(merged.get(4).score()).isEqualTo(0.5f);
    }

    /**
     * Test mergeAndRank deduplicates by document ID.
     */
    @Test
    void testMergeAndRankDeduplication() {
        ResultAggregator aggregator = new ResultAggregator(10);

        // Both segments have node ID 1, but with different distances
        List<HnswSearchResult> segment1Results = List.of(
            new HnswSearchResult(1L, 0.1f),  // Better distance
            new HnswSearchResult(2L, 0.3f)
        );

        List<HnswSearchResult> segment2Results = List.of(
            new HnswSearchResult(1L, 0.5f),  // Worse distance, should be deduplicated
            new HnswSearchResult(3L, 0.2f)
        );

        SegmentQueryTask.SegmentQueryResult result1 = new SegmentQueryTask.SegmentQueryResult(
            1L, segment1Results, 0, 10, true, null
        );
        SegmentQueryTask.SegmentQueryResult result2 = new SegmentQueryTask.SegmentQueryResult(
            2L, segment2Results, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(result1, result2));

        // Should have 3 unique document IDs
        assertThat(merged).hasSize(3);

        // Document 1 should appear once with distance 0.1 (first encountered)
        assertThat(merged.stream().anyMatch(r -> r.documentId() == 1L && r.score() == 0.1f)).isTrue();
    }

    /**
     * Test mergeAndRank respects topK limit.
     */
    @Test
    void testMergeAndRankRespectsTopK() {
        ResultAggregator aggregator = new ResultAggregator(3);

        List<HnswSearchResult> segment1Results = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.3f),
            new HnswSearchResult(3L, 0.5f)
        );

        List<HnswSearchResult> segment2Results = List.of(
            new HnswSearchResult(4L, 0.2f),
            new HnswSearchResult(5L, 0.4f),
            new HnswSearchResult(6L, 0.6f)
        );

        SegmentQueryTask.SegmentQueryResult result1 = new SegmentQueryTask.SegmentQueryResult(
            1L, segment1Results, 0, 10, true, null
        );
        SegmentQueryTask.SegmentQueryResult result2 = new SegmentQueryTask.SegmentQueryResult(
            2L, segment2Results, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(result1, result2));

        // Should only return top 3
        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
    }

    /**
     * Test mergeAndRank applies threshold filter.
     */
    @Test
    void testMergeAndRankThresholdFilter() {
        ResultAggregator aggregator = new ResultAggregator(10, 0.35f, 0.5);

        List<HnswSearchResult> segmentResults = List.of(
            new HnswSearchResult(1L, 0.1f),  // Included
            new HnswSearchResult(2L, 0.2f),  // Included
            new HnswSearchResult(3L, 0.3f),  // Included
            new HnswSearchResult(4L, 0.4f),  // Excluded by threshold
            new HnswSearchResult(5L, 0.5f)   // Excluded by threshold
        );

        SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask.SegmentQueryResult(
            1L, segmentResults, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(result));

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
    }

    /**
     * Test mergeAndRank skips failed segments.
     */
    @Test
    void testMergeAndRankSkipsFailedSegments() {
        ResultAggregator aggregator = new ResultAggregator(10);

        List<HnswSearchResult> successResults = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.2f)
        );

        SegmentQueryTask.SegmentQueryResult successResult = new SegmentQueryTask.SegmentQueryResult(
            1L, successResults, 0, 10, true, null
        );

        SegmentQueryTask.SegmentQueryResult failedResult = new SegmentQueryTask.SegmentQueryResult(
            2L, Collections.emptyList(), 0, 10, false, "Search failed"
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(successResult, failedResult));

        // Should only include results from successful segment
        assertThat(merged).hasSize(2);
    }

    /**
     * Test mergeAndRank with empty segment results.
     */
    @Test
    void testMergeAndRankEmptySegmentResults() {
        ResultAggregator aggregator = new ResultAggregator(10);

        SegmentQueryTask.SegmentQueryResult emptyResult = new SegmentQueryTask.SegmentQueryResult(
            1L, Collections.emptyList(), 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(emptyResult));

        assertThat(merged).isEmpty();
    }

    /**
     * Test mergeAndRank with null result list in segment.
     */
    @Test
    void testMergeAndRankNullResultList() {
        ResultAggregator aggregator = new ResultAggregator(10);

        // SegmentQueryResult constructor handles null by using empty list
        SegmentQueryTask.SegmentQueryResult nullResult = new SegmentQueryTask.SegmentQueryResult(
            1L, null, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(nullResult));

        assertThat(merged).isEmpty();
    }

    /**
     * Test buildCoverage creates correct coverage metadata.
     */
    @Test
    void testBuildCoverage() {
        ResultAggregator aggregator = new ResultAggregator(10);

        List<HnswSearchResult> results1 = List.of(new HnswSearchResult(1L, 0.1f));
        List<HnswSearchResult> results2 = List.of(new HnswSearchResult(2L, 0.2f));

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = List.of(
            new SegmentQueryTask.SegmentQueryResult(1L, results1, 50, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(2L, results2, 25, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(3L, Collections.emptyList(), 0, 10, false, "Error")
        );

        IndexCoverage coverage = aggregator.buildCoverage(
            segmentResults,
            5,   // totalSegments
            2,   // activeSegments
            1,   // indexingSegments
            1,   // warmingSegments
            0,   // compactingSegments
            1    // tombstonedSegments
        );

        assertThat(coverage.totalSegments()).isEqualTo(5);
        assertThat(coverage.activeSegments()).isEqualTo(2);
        assertThat(coverage.indexingSegments()).isEqualTo(1);
        assertThat(coverage.warmingSegments()).isEqualTo(1);
        assertThat(coverage.segmentsSearched()).isEqualTo(2);
        assertThat(coverage.segmentsWithErrors()).isEqualTo(1);
        assertThat(coverage.tailScanEntries()).isEqualTo(75); // 50 + 25
    }

    /**
     * Test buildCoverage detects timeout errors.
     */
    @Test
    void testBuildCoverageDetectsTimeouts() {
        ResultAggregator aggregator = new ResultAggregator(10);

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = List.of(
            new SegmentQueryTask.SegmentQueryResult(1L, Collections.emptyList(), 0, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(2L, Collections.emptyList(), 0, 10, false, "Timeout exceeded"),
            new SegmentQueryTask.SegmentQueryResult(3L, Collections.emptyList(), 0, 10, false, "Other error")
        );

        IndexCoverage coverage = aggregator.buildCoverage(
            segmentResults, 3, 1, 0, 0, 0, 0
        );

        assertThat(coverage.segmentsTimedOut()).isEqualTo(1);
    }

    /**
     * Test static mergeResults method.
     */
    @Test
    void testStaticMergeResults() {
        List<HnswSearchResult> segment1 = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.3f)
        );

        List<HnswSearchResult> segment2 = List.of(
            new HnswSearchResult(3L, 0.2f),
            new HnswSearchResult(4L, 0.4f)
        );

        List<HnswSearchResult> merged = ResultAggregator.mergeResults(
            List.of(segment1, segment2), 3
        );

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).distance()).isEqualTo(0.1f);
        assertThat(merged.get(1).distance()).isEqualTo(0.2f);
        assertThat(merged.get(2).distance()).isEqualTo(0.3f);
    }

    /**
     * Test static mergeResults with null input.
     */
    @Test
    void testStaticMergeResultsNullInput() {
        List<HnswSearchResult> merged = ResultAggregator.mergeResults(null, 10);
        assertThat(merged).isEmpty();
    }

    /**
     * Test static mergeResults with empty input.
     */
    @Test
    void testStaticMergeResultsEmptyInput() {
        List<HnswSearchResult> merged = ResultAggregator.mergeResults(Collections.emptyList(), 10);
        assertThat(merged).isEmpty();
    }

    /**
     * Test static mergeResults with null segment in list.
     */
    @Test
    void testStaticMergeResultsNullSegment() {
        List<HnswSearchResult> segment1 = List.of(new HnswSearchResult(1L, 0.1f));

        // Use Arrays.asList which allows null elements (List.of does not)
        List<HnswSearchResult> merged = ResultAggregator.mergeResults(
            java.util.Arrays.asList(segment1, null), 10
        );

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).nodeId()).isEqualTo(1L);
    }

    /**
     * Test static mergeResults deduplicates.
     */
    @Test
    void testStaticMergeResultsDeduplicates() {
        List<HnswSearchResult> segment1 = List.of(
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(2L, 0.3f)
        );

        List<HnswSearchResult> segment2 = List.of(
            new HnswSearchResult(1L, 0.5f),  // Duplicate ID
            new HnswSearchResult(3L, 0.2f)
        );

        List<HnswSearchResult> merged = ResultAggregator.mergeResults(
            List.of(segment1, segment2), 10
        );

        assertThat(merged).hasSize(3);
    }

    /**
     * Test results are sorted correctly (smallest distance first).
     */
    @Test
    void testMergeAndRankSortingOrder() {
        ResultAggregator aggregator = new ResultAggregator(10);

        // Provide results in random order
        List<HnswSearchResult> segmentResults = List.of(
            new HnswSearchResult(5L, 0.9f),
            new HnswSearchResult(1L, 0.1f),
            new HnswSearchResult(3L, 0.5f),
            new HnswSearchResult(2L, 0.2f),
            new HnswSearchResult(4L, 0.7f)
        );

        SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask.SegmentQueryResult(
            1L, segmentResults, 0, 10, true, null
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(result));

        // Verify sorted order (smallest first)
        assertThat(merged).hasSize(5);
        assertThat(merged.get(0).documentId()).isEqualTo(1L);
        assertThat(merged.get(1).documentId()).isEqualTo(2L);
        assertThat(merged.get(2).documentId()).isEqualTo(3L);
        assertThat(merged.get(3).documentId()).isEqualTo(4L);
        assertThat(merged.get(4).documentId()).isEqualTo(5L);
    }

    /**
     * Test mergeAndRank handles large number of segments.
     */
    @Test
    void testMergeAndRankManySegments() {
        ResultAggregator aggregator = new ResultAggregator(5);

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            List<HnswSearchResult> results = List.of(
                new HnswSearchResult(i * 10L, i * 0.01f)
            );
            segmentResults.add(new SegmentQueryTask.SegmentQueryResult(i, results, 0, 10, true, null));
        }

        List<QueryResultItem> merged = aggregator.mergeAndRank(segmentResults);

        // Should only return top 5
        assertThat(merged).hasSize(5);

        // Verify they are the best 5 (lowest distances)
        for (int i = 0; i < 5; i++) {
            assertThat(merged.get(i).score()).isEqualTo(i * 0.01f);
        }
    }
}
