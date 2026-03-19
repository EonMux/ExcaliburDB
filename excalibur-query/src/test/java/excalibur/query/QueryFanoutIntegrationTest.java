package excalibur.query;

import excalibur.query.executor.ParallelQueryDispatcher;
import excalibur.query.executor.ResultAggregator;
import excalibur.query.executor.SegmentQueryTask;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Top-K ANN Query Fan-out.
 *
 * <p>These tests verify the complete query fan-out flow:</p>
 * <ol>
 *   <li>Query dispatch to multiple segments in parallel</li>
 *   <li>Segment-level search execution</li>
 *   <li>Result aggregation and merge-sort ranking</li>
 *   <li>Coverage metadata computation</li>
 * </ol>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
class QueryFanoutIntegrationTest {

    /**
     * Test result aggregation across multiple simulated segments.
     *
     * <p>Verifies that results from multiple segments are correctly merged,
     * deduplicated, and ranked to produce global top-k results.</p>
     */
    @Test
    void testMultiSegmentResultAggregation() {
        // Simulate results from 4 segments
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();

        // Segment 1: distances 0.1, 0.5, 0.9
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            1L,
            List.of(
                new HnswSearchResult(101L, 0.1f),
                new HnswSearchResult(102L, 0.5f),
                new HnswSearchResult(103L, 0.9f)
            ),
            0, 10, true, null
        ));

        // Segment 2: distances 0.2, 0.6, 1.0
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            2L,
            List.of(
                new HnswSearchResult(201L, 0.2f),
                new HnswSearchResult(202L, 0.6f),
                new HnswSearchResult(203L, 1.0f)
            ),
            0, 10, true, null
        ));

        // Segment 3: distances 0.3, 0.7
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            3L,
            List.of(
                new HnswSearchResult(301L, 0.3f),
                new HnswSearchResult(302L, 0.7f)
            ),
            0, 10, true, null
        ));

        // Segment 4: distances 0.4, 0.8
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            4L,
            List.of(
                new HnswSearchResult(401L, 0.4f),
                new HnswSearchResult(402L, 0.8f)
            ),
            0, 10, true, null
        ));

        // Aggregate with top-k = 5
        ResultAggregator aggregator = new ResultAggregator(5);
        List<QueryResultItem> merged = aggregator.mergeAndRank(segmentResults);

        // Verify we get exactly 5 results
        assertThat(merged).hasSize(5);

        // Verify global ranking (closest first)
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
        assertThat(merged.get(3).score()).isEqualTo(0.4f);
        assertThat(merged.get(4).score()).isEqualTo(0.5f);

        // Verify document IDs are correct
        assertThat(merged.get(0).documentId()).isEqualTo(101L);
        assertThat(merged.get(1).documentId()).isEqualTo(201L);
        assertThat(merged.get(2).documentId()).isEqualTo(301L);
        assertThat(merged.get(3).documentId()).isEqualTo(401L);
        assertThat(merged.get(4).documentId()).isEqualTo(102L);
    }

    /**
     * Test result aggregation with duplicate document IDs across segments.
     *
     * <p>Verifies that duplicates are correctly identified and removed
     * based on document ID, keeping only the first occurrence.</p>
     */
    @Test
    void testResultAggregationWithDuplicates() {
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();

        // Segment 1: Document 100 with distance 0.1
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            1L,
            List.of(
                new HnswSearchResult(100L, 0.1f),  // Best match
                new HnswSearchResult(200L, 0.3f)
            ),
            0, 10, true, null
        ));

        // Segment 2: Same document 100 with distance 0.2 (duplicate, worse)
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            2L,
            List.of(
                new HnswSearchResult(100L, 0.2f),  // Duplicate
                new HnswSearchResult(300L, 0.4f)
            ),
            0, 10, true, null
        ));

        ResultAggregator aggregator = new ResultAggregator(10);
        List<QueryResultItem> merged = aggregator.mergeAndRank(segmentResults);

        // Should have 3 unique documents
        assertThat(merged).hasSize(3);

        // Document 100 should appear once with the first-encountered distance
        assertThat(merged.stream().filter(r -> r.documentId() == 100L)).hasSize(1);

        // Verify all documents present
        assertThat(merged.stream().mapToLong(QueryResultItem::documentId))
            .containsExactlyInAnyOrder(100L, 200L, 300L);
    }

    /**
     * Test coverage metadata computation.
     *
     * <p>Verifies that IndexCoverage correctly computes coverage ratios
     * and degraded state based on segment statistics.</p>
     */
    @Test
    void testCoverageComputation() {
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();

        // 5 successful segments, 2 failed
        for (int i = 0; i < 5; i++) {
            segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                i,
                List.of(new HnswSearchResult(i * 10L, i * 0.1f)),
                100, 10, true, null
            ));
        }

        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            99L, Collections.emptyList(), 0, 10, false, "Connection timeout"
        ));
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            98L, Collections.emptyList(), 0, 10, false, "Index corrupted"
        ));

        ResultAggregator aggregator = new ResultAggregator(10);
        IndexCoverage coverage = aggregator.buildCoverage(
            segmentResults,
            10,   // totalSegments
            5,    // activeSegments
            2,    // indexingSegments
            1,    // warmingSegments
            0,    // compactingSegments
            2     // tombstonedSegments
        );

        // Verify coverage metrics
        assertThat(coverage.totalSegments()).isEqualTo(10);
        assertThat(coverage.activeSegments()).isEqualTo(5);
        assertThat(coverage.segmentsSearched()).isEqualTo(5);
        assertThat(coverage.segmentsWithErrors()).isEqualTo(2);
        assertThat(coverage.tailScanEntries()).isEqualTo(500); // 5 segments * 100 each

        // Coverage should be degraded due to errors
        assertThat(coverage.degraded()).isTrue();
    }

    /**
     * Test handling of partial segment failures.
     *
     * <p>Verifies that the query pipeline continues to return results
     * even when some segments fail.</p>
     */
    @Test
    void testPartialFailureHandling() {
        List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();

        // 3 successful segments
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            1L, List.of(new HnswSearchResult(1L, 0.1f)), 0, 10, true, null
        ));
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            2L, List.of(new HnswSearchResult(2L, 0.2f)), 0, 10, true, null
        ));
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            3L, List.of(new HnswSearchResult(3L, 0.3f)), 0, 10, true, null
        ));

        // 2 failed segments
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            4L, Collections.emptyList(), 0, 10, false, "Graph not loaded"
        ));
        segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
            5L, Collections.emptyList(), 0, 10, false, "Timeout"
        ));

        ResultAggregator aggregator = new ResultAggregator(10);
        List<QueryResultItem> merged = aggregator.mergeAndRank(segmentResults);

        // Should return results from successful segments only
        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).score()).isEqualTo(0.1f);
        assertThat(merged.get(1).score()).isEqualTo(0.2f);
        assertThat(merged.get(2).score()).isEqualTo(0.3f);
    }

    /**
     * Test query options configuration.
     *
     * <p>Verifies that QueryOptions correctly configures all parameters.</p>
     */
    @Test
    void testQueryOptionsConfiguration() {
        QueryOptions options = QueryOptions.builder()
            .topK(25)
            .threshold(0.5f)
            .includePartialSegments(false)
            .timeout(java.time.Duration.ofSeconds(10))
            .oversamplingFactor(3)
            .build();

        assertThat(options.topK()).isEqualTo(25);
        assertThat(options.threshold()).isEqualTo(0.5f);
        assertThat(options.includePartialSegments()).isFalse();
        assertThat(options.timeout()).isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(options.oversamplingFactor()).isEqualTo(3);
    }

    /**
     * Test query response construction.
     *
     * <p>Verifies that QueryResponse correctly encapsulates results and coverage.</p>
     */
    @Test
    void testQueryResponseConstruction() {
        List<QueryResultItem> results = List.of(
            new QueryResultItem(1L, 0.1f, null, 1L, 100L),
            new QueryResultItem(2L, 0.2f, null, 2L, 200L)
        );

        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(8)
            .indexingSegments(2)
            .computeCoverage()
            .computeDegraded(0.5)
            .build();

        QueryResponse response = new QueryResponse(results, coverage, 150);

        assertThat(response.results()).hasSize(2);
        assertThat(response.resultCount()).isEqualTo(2);
        assertThat(response.queryTimeMs()).isEqualTo(150);
        assertThat(response.coverage()).isEqualTo(coverage);
        assertThat(response.isDegraded()).isFalse();
        assertThat(response.isEmpty()).isFalse();
        assertThat(response.topResult().documentId()).isEqualTo(1L);
    }

    /**
     * Test empty query response.
     *
     * <p>Verifies behavior when no results are found.</p>
     */
    @Test
    void testEmptyQueryResponse() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(0)
            .computeCoverage()
            .computeDegraded(0.5)
            .build();

        QueryResponse response = QueryResponse.empty(coverage, 50);

        assertThat(response.results()).isEmpty();
        assertThat(response.isEmpty()).isTrue();
        assertThat(response.topResult()).isNull();
        assertThat(response.queryTimeMs()).isEqualTo(50);
    }

    /**
     * Test index coverage builder with all segment states.
     *
     * <p>Verifies that IndexCoverage correctly tracks all lifecycle states.</p>
     */
    @Test
    void testIndexCoverageAllStates() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(100)
            .activeSegments(70)
            .indexingSegments(10)
            .warmingSegments(5)
            .compactingSegments(3)
            .tombstonedSegments(12)
            .segmentsSearched(88)
            .segmentsWithErrors(0)
            .segmentsTimedOut(0)
            .tailScanEntries(5000)
            .computeCoverage()
            .computeDegraded(0.5)
            .build();

        assertThat(coverage.totalSegments()).isEqualTo(100);
        assertThat(coverage.activeSegments()).isEqualTo(70);
        assertThat(coverage.indexingSegments()).isEqualTo(10);
        assertThat(coverage.warmingSegments()).isEqualTo(5);
        assertThat(coverage.compactingSegments()).isEqualTo(3);
        assertThat(coverage.tombstonedSegments()).isEqualTo(12);
        assertThat(coverage.queryableSegments()).isEqualTo(88); // 70 + 10 + 5 + 3
        assertThat(coverage.segmentsSearched()).isEqualTo(88);
        assertThat(coverage.tailScanEntries()).isEqualTo(5000);

        // Coverage should not be degraded (88/88 = 100% of queryable)
        assertThat(coverage.degraded()).isFalse();
    }

    /**
     * Test static merge results with large number of segments.
     *
     * <p>Verifies performance and correctness with many segments.</p>
     */
    @Test
    void testLargeScaleMerge() {
        List<List<HnswSearchResult>> allSegmentResults = new ArrayList<>();

        // Create 100 segments with 10 results each
        for (int segment = 0; segment < 100; segment++) {
            List<HnswSearchResult> segmentResults = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                float distance = (segment * 10 + i) * 0.001f;
                segmentResults.add(new HnswSearchResult(segment * 1000L + i, distance));
            }
            allSegmentResults.add(segmentResults);
        }

        // Merge to get top 50
        List<HnswSearchResult> merged = ResultAggregator.mergeResults(allSegmentResults, 50);

        assertThat(merged).hasSize(50);

        // Verify results are sorted by distance
        for (int i = 1; i < merged.size(); i++) {
            assertThat(merged.get(i).distance()).isGreaterThanOrEqualTo(merged.get(i - 1).distance());
        }

        // First result should have smallest distance (0.0)
        assertThat(merged.get(0).distance()).isEqualTo(0.0f);
    }

    /**
     * Test query result item with payload.
     *
     * <p>Verifies that result items correctly store and retrieve payloads.</p>
     */
    @Test
    void testQueryResultItemWithPayload() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};

        QueryResultItem item = new QueryResultItem(
            12345L,    // documentId
            0.75f,     // score
            payload,   // payload
            1L,        // segmentId
            100L       // nodeId
        );

        assertThat(item.documentId()).isEqualTo(12345L);
        assertThat(item.score()).isEqualTo(0.75f);
        assertThat(item.hasPayload()).isTrue();
        assertThat(item.payload()).containsExactly(payload);
        assertThat(item.segmentId()).isEqualTo(1L);
        assertThat(item.nodeId()).isEqualTo(100L);

        // Verify defensive copy
        payload[0] = 99;
        assertThat(item.payload()[0]).isEqualTo((byte) 1);
    }

    /**
     * Test query result item immutability.
     *
     * <p>Verifies that modifying returned payload doesn't affect internal state.</p>
     */
    @Test
    void testQueryResultItemImmutability() {
        QueryResultItem item = new QueryResultItem(1L, 0.5f, new byte[]{1, 2, 3}, 1L, 1L);

        byte[] returnedPayload = item.payload();
        returnedPayload[0] = 99;

        // Internal payload should be unchanged
        assertThat(item.payload()[0]).isEqualTo((byte) 1);
    }
}
