package excalibur.query.executor;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryOptions;
import excalibur.query.QueryResultItem;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.Test;

import io.excalibur.vector.distance.CosineSimilarity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
     * A tail-scan hit arrives with the chunk text the scan already read. The aggregator has
     * to carry it onto the QueryResultItem: nothing downstream can recover it, because the
     * synthetic node ID encodes an LSN rather than the chunk's storage key.
     */
    @Test
    void testTailScanPayloadsAreCarriedOntoResults() {
        ResultAggregator aggregator = new ResultAggregator(10);

        long tailNodeId = Long.MIN_VALUE | 42L;
        List<HnswSearchResult> results = List.of(
            new HnswSearchResult(tailNodeId, -1.0f, "source1:doc1"),
            new HnswSearchResult(7L, -0.5f, "source1:doc2")
        );

        SegmentQueryTask.SegmentQueryResult segmentResult = new SegmentQueryTask.SegmentQueryResult(
            1L, results, 2, 10, true, null,
            Map.of(tailNodeId, "the tail chunk".getBytes(StandardCharsets.UTF_8))
        );

        List<QueryResultItem> merged = aggregator.mergeAndRankWithNormalization(
            List.of(segmentResult), CosineSimilarity.getInstance());

        QueryResultItem tailItem = merged.stream()
            .filter(item -> item.nodeId() == tailNodeId)
            .findFirst()
            .orElseThrow();
        QueryResultItem graphItem = merged.stream()
            .filter(item -> item.nodeId() == 7L)
            .findFirst()
            .orElseThrow();

        assertThat(new String(tailItem.payload(), StandardCharsets.UTF_8)).isEqualTo("the tail chunk");
        assertThat(graphItem.hasPayload())
            .as("graph hits carry no payload here; their text is resolved from storage later")
            .isFalse();
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

        // The same DOCUMENT appears in both segments with different distances. Identity
        // across segments is the document ID: node IDs are dense and per-graph, so two
        // segments both holding node 1 are two unrelated vectors.
        List<HnswSearchResult> segment1Results = List.of(
            new HnswSearchResult(1L, 0.1f, "doc1"),  // Better distance
            new HnswSearchResult(2L, 0.3f, "doc2")
        );

        List<HnswSearchResult> segment2Results = List.of(
            new HnswSearchResult(1L, 0.5f, "doc1"),  // Worse distance, should be deduplicated
            new HnswSearchResult(3L, 0.2f, "doc3")
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

        // doc1 should appear once with the BEST distance (0.1, not 0.5)
        assertThat(merged.stream().anyMatch(
            r -> "doc1".equals(r.documentIdStr()) && r.score() == 0.1f)).isTrue();
        // Ensure no duplicate with worse score
        assertThat(merged.stream().filter(r -> "doc1".equals(r.documentIdStr()))).hasSize(1);
    }

    /**
     * Test mergeAndRank keeps the BEST score when duplicates exist across segments.
     * This simulates re-indexing scenario where the same document appears in
     * both old and new segments with different distances.
     */
    @Test
    void testMergeAndRankKeepsBestScoreForDuplicates() {
        ResultAggregator aggregator = new ResultAggregator(10);

        // Segment 1 (old segment during re-index): doc 1 with WORSE distance
        List<HnswSearchResult> oldSegmentResults = List.of(
            new HnswSearchResult(1L, 0.9f, "doc1"),  // Worse distance - should be replaced
            new HnswSearchResult(2L, 0.5f, "doc2")
        );

        // Segment 2 (new segment during re-index): doc 1 with BETTER distance
        List<HnswSearchResult> newSegmentResults = List.of(
            new HnswSearchResult(1L, 0.1f, "doc1"),  // Better distance - should be kept
            new HnswSearchResult(3L, 0.2f, "doc3")
        );

        SegmentQueryTask.SegmentQueryResult oldResult = new SegmentQueryTask.SegmentQueryResult(
            1L, oldSegmentResults, 0, 10, true, null
        );
        SegmentQueryTask.SegmentQueryResult newResult = new SegmentQueryTask.SegmentQueryResult(
            2L, newSegmentResults, 0, 10, true, null
        );

        // Process in order: new segment first, then old segment
        // The aggregator should still keep the best score regardless of processing order
        List<QueryResultItem> merged = aggregator.mergeAndRank(List.of(newResult, oldResult));

        // Should have 3 unique document IDs
        assertThat(merged).hasSize(3);

        // doc1 should appear with the BEST distance (0.1), not the worse one (0.9)
        QueryResultItem doc1Result = merged.stream()
            .filter(r -> "doc1".equals(r.documentIdStr()))
            .findFirst()
            .orElseThrow();
        assertThat(doc1Result.score()).isEqualTo(0.1f);

        // Verify only one entry for doc1
        assertThat(merged.stream().filter(r -> "doc1".equals(r.documentIdStr()))).hasSize(1);
    }

    /**
     * Test mergeAndRank keeps best score regardless of segment processing order.
     * Verifies that processing order doesn't affect which duplicate is kept.
     */
    @Test
    void testMergeAndRankDeduplicationOrderIndependent() {
        ResultAggregator aggregator = new ResultAggregator(10);

        // Segment 1: doc1 with worse distance
        List<HnswSearchResult> segment1Results = List.of(
            new HnswSearchResult(1L, 0.8f, "doc1")   // Worse distance
        );

        // Segment 2: doc1 with better distance
        List<HnswSearchResult> segment2Results = List.of(
            new HnswSearchResult(7L, 0.2f, "doc1")   // Better distance, different node ID
        );

        SegmentQueryTask.SegmentQueryResult result1 = new SegmentQueryTask.SegmentQueryResult(
            1L, segment1Results, 0, 10, true, null
        );
        SegmentQueryTask.SegmentQueryResult result2 = new SegmentQueryTask.SegmentQueryResult(
            2L, segment2Results, 0, 10, true, null
        );

        // Process segment 1 FIRST (worse score comes first)
        List<QueryResultItem> mergedOrder1 = aggregator.mergeAndRank(List.of(result1, result2));

        // Process segment 2 FIRST (better score comes first)
        List<QueryResultItem> mergedOrder2 = aggregator.mergeAndRank(List.of(result2, result1));

        // Both should have the same result: doc 1 with the BEST score (0.2)
        assertThat(mergedOrder1).hasSize(1);
        assertThat(mergedOrder2).hasSize(1);
        assertThat(mergedOrder1.get(0).score()).isEqualTo(0.2f);
        assertThat(mergedOrder2.get(0).score()).isEqualTo(0.2f);
    }

    /**
     * Test mergeAndRank with multiple duplicates across many segments.
     * Verifies deduplication works correctly with complex overlap scenarios.
     */
    @Test
    void testMergeAndRankMultipleDuplicatesAcrossSegments() {
        ResultAggregator aggregator = new ResultAggregator(5);

        // Three segments, each containing some overlapping documents. Node IDs repeat
        // across segments because every graph numbers its nodes from zero; the document
        // ID is what makes two results the same document.
        List<HnswSearchResult> segment1 = List.of(
            new HnswSearchResult(1L, 0.5f, "doc1"),  // doc1: medium
            new HnswSearchResult(2L, 0.3f, "doc2"),  // doc2: best
            new HnswSearchResult(3L, 0.9f, "doc3")   // doc3: worst
        );

        List<HnswSearchResult> segment2 = List.of(
            new HnswSearchResult(1L, 0.2f, "doc1"),  // doc1: best
            new HnswSearchResult(2L, 0.6f, "doc2"),  // doc2: worst
            new HnswSearchResult(4L, 0.4f, "doc4")   // doc4: only in this segment
        );

        List<HnswSearchResult> segment3 = List.of(
            new HnswSearchResult(1L, 0.8f, "doc1"),  // doc1: worst
            new HnswSearchResult(3L, 0.1f, "doc3"),  // doc3: best
            new HnswSearchResult(5L, 0.7f, "doc5")   // doc5: only in this segment
        );

        List<SegmentQueryTask.SegmentQueryResult> results = List.of(
            new SegmentQueryTask.SegmentQueryResult(1L, segment1, 0, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(2L, segment2, 0, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(3L, segment3, 0, 10, true, null)
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(results);

        // Should have 5 unique documents
        assertThat(merged).hasSize(5);

        // Verify each document has its best score
        java.util.Map<String, Float> expectedScores = java.util.Map.of(
            "doc1", 0.2f,  // Best of {0.5, 0.2, 0.8}
            "doc2", 0.3f,  // Best of {0.3, 0.6}
            "doc3", 0.1f,  // Best of {0.9, 0.1}
            "doc4", 0.4f,  // Only one
            "doc5", 0.7f   // Only one
        );

        for (QueryResultItem item : merged) {
            float expected = expectedScores.get(item.documentIdStr());
            assertThat(item.score())
                .withFailMessage("Document %s should have score %.2f but got %.2f",
                    item.documentIdStr(), expected, item.score())
                .isEqualTo(expected);
        }

        // Verify no duplicates
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (QueryResultItem item : merged) {
            assertThat(seenIds.add(item.documentIdStr()))
                .withFailMessage("Duplicate document ID %s in results", item.documentIdStr())
                .isTrue();
        }
    }

    /**
     * Results that carry no document ID must NOT be deduplicated across segments.
     *
     * <p>Node IDs are dense and allocated per HNSW graph - every segment restarts them at
     * zero - so keying the fallback on a bare node ID collapses unrelated vectors from
     * different segments and silently discards legitimate top-k hits.</p>
     */
    @Test
    void testMergeAndRankDoesNotCollapseSameNodeIdAcrossSegments() {
        ResultAggregator aggregator = new ResultAggregator(10);

        List<SegmentQueryTask.SegmentQueryResult> results = List.of(
            new SegmentQueryTask.SegmentQueryResult(
                1L, List.of(new HnswSearchResult(0L, 0.1f)), 0, 10, true, null),
            new SegmentQueryTask.SegmentQueryResult(
                2L, List.of(new HnswSearchResult(0L, 0.2f)), 0, 10, true, null)
        );

        List<QueryResultItem> merged = aggregator.mergeAndRank(results);

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(QueryResultItem::segmentId)
            .containsExactlyInAnyOrder(1L, 2L);
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
     * Test static mergeResults keeps the BEST distance when duplicates exist.
     * Verifies that when the same node ID appears with different distances,
     * the one with the lowest (best) distance is kept.
     */
    @Test
    void testStaticMergeResultsKeepsBestDistanceForDuplicates() {
        // Segment 1: node 1 with worse distance
        List<HnswSearchResult> segment1 = List.of(
            new HnswSearchResult(1L, 0.9f),  // Worse distance
            new HnswSearchResult(2L, 0.3f)
        );

        // Segment 2: node 1 with better distance
        List<HnswSearchResult> segment2 = List.of(
            new HnswSearchResult(1L, 0.1f),  // Better distance - should be kept
            new HnswSearchResult(3L, 0.2f)
        );

        List<HnswSearchResult> merged = ResultAggregator.mergeResults(
            List.of(segment1, segment2), 10
        );

        // Should have 3 unique nodes
        assertThat(merged).hasSize(3);

        // Node 1 should have the BEST distance (0.1), not the worse one (0.9)
        HnswSearchResult node1Result = merged.stream()
            .filter(r -> r.nodeId() == 1L)
            .findFirst()
            .orElseThrow();
        assertThat(node1Result.distance()).isEqualTo(0.1f);

        // Verify only one entry for node 1
        assertThat(merged.stream().filter(r -> r.nodeId() == 1L)).hasSize(1);
    }

    /**
     * Test static mergeResults best distance is order-independent.
     * Verifies that processing order doesn't affect which duplicate is kept.
     */
    @Test
    void testStaticMergeResultsDeduplicationOrderIndependent() {
        List<HnswSearchResult> segment1 = List.of(
            new HnswSearchResult(1L, 0.8f)   // Worse distance
        );

        List<HnswSearchResult> segment2 = List.of(
            new HnswSearchResult(1L, 0.2f)   // Better distance
        );

        // Process segment 1 FIRST (worse score comes first)
        List<HnswSearchResult> mergedOrder1 = ResultAggregator.mergeResults(
            List.of(segment1, segment2), 10
        );

        // Process segment 2 FIRST (better score comes first)
        List<HnswSearchResult> mergedOrder2 = ResultAggregator.mergeResults(
            List.of(segment2, segment1), 10
        );

        // Both should have the same result: node 1 with the BEST distance (0.2)
        assertThat(mergedOrder1).hasSize(1);
        assertThat(mergedOrder2).hasSize(1);
        assertThat(mergedOrder1.get(0).distance()).isEqualTo(0.2f);
        assertThat(mergedOrder2.get(0).distance()).isEqualTo(0.2f);
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

    // ==================== Relevance Score Sorting Tests ====================

    /**
     * Test sortByRelevanceDescending with valid results.
     * Verifies results are sorted with highest relevance first.
     */
    @Test
    void testSortByRelevanceDescending() {
        // Create results with varying relevance scores
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.85f, null, 1L, 1L));  // High relevance
        results.add(new QueryResultItem(2L, 0.25f, null, 1L, 2L));  // Low relevance
        results.add(new QueryResultItem(3L, 0.95f, null, 1L, 3L));  // Highest relevance
        results.add(new QueryResultItem(4L, 0.50f, null, 1L, 4L));  // Medium relevance

        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(results);

        // Verify sorted order (highest first)
        assertThat(sorted).hasSize(4);
        assertThat(sorted.get(0).documentId()).isEqualTo(3L);  // 0.95
        assertThat(sorted.get(0).score()).isEqualTo(0.95f);
        assertThat(sorted.get(1).documentId()).isEqualTo(1L);  // 0.85
        assertThat(sorted.get(1).score()).isEqualTo(0.85f);
        assertThat(sorted.get(2).documentId()).isEqualTo(4L);  // 0.50
        assertThat(sorted.get(2).score()).isEqualTo(0.50f);
        assertThat(sorted.get(3).documentId()).isEqualTo(2L);  // 0.25
        assertThat(sorted.get(3).score()).isEqualTo(0.25f);
    }

    /**
     * Test sortByRelevanceDescending does not modify original list.
     */
    @Test
    void testSortByRelevanceDescendingImmutable() {
        List<QueryResultItem> original = new ArrayList<>();
        original.add(new QueryResultItem(1L, 0.5f));
        original.add(new QueryResultItem(2L, 0.9f));
        original.add(new QueryResultItem(3L, 0.1f));

        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(original);

        // Original list order should be unchanged
        assertThat(original.get(0).score()).isEqualTo(0.5f);
        assertThat(original.get(1).score()).isEqualTo(0.9f);
        assertThat(original.get(2).score()).isEqualTo(0.1f);

        // Sorted list should be different
        assertThat(sorted.get(0).score()).isEqualTo(0.9f);
        assertThat(sorted.get(1).score()).isEqualTo(0.5f);
        assertThat(sorted.get(2).score()).isEqualTo(0.1f);
    }

    /**
     * Test sortByRelevanceDescending with null input.
     */
    @Test
    void testSortByRelevanceDescendingNull() {
        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(null);
        assertThat(sorted).isEmpty();
    }

    /**
     * Test sortByRelevanceDescending with empty input.
     */
    @Test
    void testSortByRelevanceDescendingEmpty() {
        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(Collections.emptyList());
        assertThat(sorted).isEmpty();
    }

    /**
     * Test sortByRelevanceDescending with single item.
     */
    @Test
    void testSortByRelevanceDescendingSingleItem() {
        List<QueryResultItem> results = List.of(
            new QueryResultItem(1L, 0.75f)
        );

        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(results);

        assertThat(sorted).hasSize(1);
        assertThat(sorted.get(0).score()).isEqualTo(0.75f);
    }

    /**
     * Test sortByRelevanceDescending with identical scores.
     * When scores are equal, order should be stable.
     */
    @Test
    void testSortByRelevanceDescendingEqualScores() {
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.5f));
        results.add(new QueryResultItem(2L, 0.5f));
        results.add(new QueryResultItem(3L, 0.5f));

        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(results);

        assertThat(sorted).hasSize(3);
        // All should have same score
        assertThat(sorted.get(0).score()).isEqualTo(0.5f);
        assertThat(sorted.get(1).score()).isEqualTo(0.5f);
        assertThat(sorted.get(2).score()).isEqualTo(0.5f);
    }

    /**
     * Test sortByRelevanceDescending with boundary scores (0.0 and 1.0).
     */
    @Test
    void testSortByRelevanceDescendingBoundaryScores() {
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.0f));  // Minimum
        results.add(new QueryResultItem(2L, 1.0f));  // Maximum
        results.add(new QueryResultItem(3L, 0.5f));  // Middle

        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(results);

        assertThat(sorted).hasSize(3);
        assertThat(sorted.get(0).documentId()).isEqualTo(2L);  // 1.0
        assertThat(sorted.get(0).score()).isEqualTo(1.0f);
        assertThat(sorted.get(1).documentId()).isEqualTo(3L);  // 0.5
        assertThat(sorted.get(1).score()).isEqualTo(0.5f);
        assertThat(sorted.get(2).documentId()).isEqualTo(1L);  // 0.0
        assertThat(sorted.get(2).score()).isEqualTo(0.0f);
    }

    /**
     * Test sortByDistanceAscending with valid results.
     * Verifies results are sorted with lowest distance first.
     */
    @Test
    void testSortByDistanceAscending() {
        // Create results with varying distances (L2 distance: lower is better)
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.5f, null, 1L, 1L));
        results.add(new QueryResultItem(2L, 0.1f, null, 1L, 2L));  // Best
        results.add(new QueryResultItem(3L, 0.9f, null, 1L, 3L));  // Worst
        results.add(new QueryResultItem(4L, 0.3f, null, 1L, 4L));

        List<QueryResultItem> sorted = ResultAggregator.sortByDistanceAscending(results);

        // Verify sorted order (lowest first)
        assertThat(sorted).hasSize(4);
        assertThat(sorted.get(0).documentId()).isEqualTo(2L);  // 0.1
        assertThat(sorted.get(0).score()).isEqualTo(0.1f);
        assertThat(sorted.get(1).documentId()).isEqualTo(4L);  // 0.3
        assertThat(sorted.get(1).score()).isEqualTo(0.3f);
        assertThat(sorted.get(2).documentId()).isEqualTo(1L);  // 0.5
        assertThat(sorted.get(2).score()).isEqualTo(0.5f);
        assertThat(sorted.get(3).documentId()).isEqualTo(3L);  // 0.9
        assertThat(sorted.get(3).score()).isEqualTo(0.9f);
    }

    /**
     * Test sortByDistanceAscending with null input.
     */
    @Test
    void testSortByDistanceAscendingNull() {
        List<QueryResultItem> sorted = ResultAggregator.sortByDistanceAscending(null);
        assertThat(sorted).isEmpty();
    }

    /**
     * Test sortByDistanceAscending with empty input.
     */
    @Test
    void testSortByDistanceAscendingEmpty() {
        List<QueryResultItem> sorted = ResultAggregator.sortByDistanceAscending(Collections.emptyList());
        assertThat(sorted).isEmpty();
    }

    /**
     * Test sort method with RELEVANCE_DESCENDING mode.
     */
    @Test
    void testSortWithRelevanceDescendingMode() {
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.3f));
        results.add(new QueryResultItem(2L, 0.9f));

        List<QueryResultItem> sorted = ResultAggregator.sort(
            results, ResultAggregator.SortMode.RELEVANCE_DESCENDING);

        assertThat(sorted.get(0).score()).isEqualTo(0.9f);  // Highest first
        assertThat(sorted.get(1).score()).isEqualTo(0.3f);
    }

    /**
     * Test sort method with DISTANCE_ASCENDING mode.
     */
    @Test
    void testSortWithDistanceAscendingMode() {
        List<QueryResultItem> results = new ArrayList<>();
        results.add(new QueryResultItem(1L, 0.9f));
        results.add(new QueryResultItem(2L, 0.3f));

        List<QueryResultItem> sorted = ResultAggregator.sort(
            results, ResultAggregator.SortMode.DISTANCE_ASCENDING);

        assertThat(sorted.get(0).score()).isEqualTo(0.3f);  // Lowest first
        assertThat(sorted.get(1).score()).isEqualTo(0.9f);
    }

    /**
     * Test that LLM-relevancy scored results are sorted descending.
     * This simulates the flow in QueryPipeline.stage5_LlmScoring.
     */
    @Test
    void testLlmScoredResultsSortedDescending() {
        // Simulate results after LLM scoring with relevancy scores
        List<QueryResultItem> llmScoredResults = new ArrayList<>();
        llmScoredResults.add(new QueryResultItem(100L, 0.45f, "doc1".getBytes(), 1L, 100L));
        llmScoredResults.add(new QueryResultItem(101L, 0.92f, "doc2".getBytes(), 1L, 101L));
        llmScoredResults.add(new QueryResultItem(102L, 0.78f, "doc3".getBytes(), 2L, 102L));
        llmScoredResults.add(new QueryResultItem(103L, 0.31f, "doc4".getBytes(), 2L, 103L));
        llmScoredResults.add(new QueryResultItem(104L, 0.85f, "doc5".getBytes(), 3L, 104L));

        // Sort by relevance descending (as done in stage5_LlmScoring)
        List<QueryResultItem> sorted = ResultAggregator.sortByRelevanceDescending(llmScoredResults);

        // Verify results are sorted by relevance score descending
        assertThat(sorted).hasSize(5);

        // Highest relevance should be first
        assertThat(sorted.get(0).documentId()).isEqualTo(101L);
        assertThat(sorted.get(0).score()).isEqualTo(0.92f);

        assertThat(sorted.get(1).documentId()).isEqualTo(104L);
        assertThat(sorted.get(1).score()).isEqualTo(0.85f);

        assertThat(sorted.get(2).documentId()).isEqualTo(102L);
        assertThat(sorted.get(2).score()).isEqualTo(0.78f);

        assertThat(sorted.get(3).documentId()).isEqualTo(100L);
        assertThat(sorted.get(3).score()).isEqualTo(0.45f);

        assertThat(sorted.get(4).documentId()).isEqualTo(103L);
        assertThat(sorted.get(4).score()).isEqualTo(0.31f);
    }

    /**
     * Test SortMode enum values exist.
     */
    @Test
    void testSortModeEnum() {
        assertThat(ResultAggregator.SortMode.DISTANCE_ASCENDING)
            .isNotNull();
        assertThat(ResultAggregator.SortMode.RELEVANCE_DESCENDING)
            .isNotNull();
    }
}
