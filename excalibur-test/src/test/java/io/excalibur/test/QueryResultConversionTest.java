/*
 * excalibur-test - Query Result Conversion Test
 *
 * Package: io.excalibur.test
 *
 * Tests the conversion from excalibur.query.QueryResponse to io.excalibur.test.QueryResult.
 */

package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the QueryResult.fromQueryResponse factory method.
 *
 * <p>Verifies that the conversion between query pipeline types and
 * test framework types works correctly.</p>
 */
class QueryResultConversionTest {

    @Test
    @DisplayName("fromQueryResponse converts QueryResponse with string document ID")
    void fromQueryResponse_withStringDocumentId_convertsCorrectly() {
        // Arrange: Create a QueryResponse with string document ID
        excalibur.query.QueryResultItem queryItem = new excalibur.query.QueryResultItem(
            "doc-123",  // string document ID
            0.95f,      // score
            null,       // payload
            1L,         // segmentId
            42L         // nodeId
        );

        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(8)
            .indexingSegments(1)
            .warmingSegments(1)
            .estimatedCorpusCoverage(0.85)
            .degraded(false)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            List.of(queryItem),
            queryCoverage,
            150L,  // queryTimeMs
            false, // fromCache
            false  // timedOut
        );

        // Act: Convert to test framework type
        QueryResult testResult = QueryResult.fromQueryResponse(response, "query-001");

        // Assert: Verify conversion
        assertThat(testResult.getQueryId()).isEqualTo("query-001");
        assertThat(testResult.getTopK()).hasSize(1);

        QueryResultItem testItem = testResult.getTopK().get(0);
        assertThat(testItem.getDocumentId()).isEqualTo("doc-123");
        assertThat(testItem.getScore()).isEqualTo(0.95f);
        assertThat(testItem.getMetadata())
            .containsEntry("segmentId", 1L)
            .containsEntry("nodeId", 42L);
        assertThat(testItem.getContent()).isNull();
        assertThat(testItem.getSource()).isNull();
        assertThat(testItem.getChunkIndex()).isEqualTo(-1);

        // Verify coverage conversion
        IndexCoverage testCoverage = testResult.getIndexCoverage();
        assertThat(testCoverage.getTotalSegments()).isEqualTo(10);
        assertThat(testCoverage.getActiveSegments()).isEqualTo(8);
        assertThat(testCoverage.getIndexingSegments()).isEqualTo(1);
        assertThat(testCoverage.getWarmingSegments()).isEqualTo(1);
        assertThat(testCoverage.getEstimatedCoverage()).isEqualTo(0.85);
        assertThat(testCoverage.isDegraded()).isFalse();

        // Verify response metadata
        assertThat(testResult.getMetadata())
            .containsEntry("queryTimeMs", 150L)
            .containsEntry("fromCache", false)
            .containsEntry("timedOut", false)
            .containsEntry("isDegraded", false)
            .containsEntry("resultCount", 1);
    }

    @Test
    @DisplayName("fromQueryResponse converts QueryResponse with numeric document ID")
    void fromQueryResponse_withNumericDocumentId_convertsToString() {
        // Arrange: Create a QueryResponse with only numeric document ID
        excalibur.query.QueryResultItem queryItem = new excalibur.query.QueryResultItem(
            999L,   // numeric document ID only
            0.75f,  // score
            null,   // payload
            2L,     // segmentId
            10L     // nodeId
        );

        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(5)
            .activeSegments(5)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            List.of(queryItem),
            queryCoverage,
            50L
        );

        // Act
        QueryResult testResult = QueryResult.fromQueryResponse(response, "q2");

        // Assert: Numeric ID should be converted to string
        assertThat(testResult.getTopK().get(0).getDocumentId()).isEqualTo("999");
    }

    @Test
    @DisplayName("fromQueryResponse handles empty results")
    void fromQueryResponse_withEmptyResults_returnsEmptyTopK() {
        // Arrange
        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(1)
            .activeSegments(1)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();

        excalibur.query.QueryResponse response = excalibur.query.QueryResponse.empty(queryCoverage, 10L);

        // Act
        QueryResult testResult = QueryResult.fromQueryResponse(response, "empty-query");

        // Assert
        assertThat(testResult.getTopK()).isEmpty();
        assertThat(testResult.getIndexCoverage().getTotalSegments()).isEqualTo(1);
    }

    @Test
    @DisplayName("fromQueryResponse handles degraded coverage")
    void fromQueryResponse_withDegradedCoverage_preservesDegradedFlag() {
        // Arrange
        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(100)
            .activeSegments(20)
            .estimatedCorpusCoverage(0.2)
            .degraded(true)
            .segmentsWithErrors(5)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            Collections.emptyList(),
            queryCoverage,
            500L,
            false,
            false
        );

        // Act
        QueryResult testResult = QueryResult.fromQueryResponse(response, "degraded-query");

        // Assert
        assertThat(testResult.getIndexCoverage().isDegraded()).isTrue();
        assertThat(testResult.getIndexCoverage().getEstimatedCoverage()).isEqualTo(0.2);
        // Additional coverage info should be in metadata
        assertThat(testResult.getMetadata())
            .containsEntry("segmentsWithErrors", 5);
    }

    @Test
    @DisplayName("fromQueryResponse handles timed out query")
    void fromQueryResponse_withTimedOutQuery_preservesTimeoutInfo() {
        // Arrange
        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(8)
            .estimatedCorpusCoverage(0.8)
            .degraded(true)
            .segmentsTimedOut(2)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            Collections.emptyList(),
            queryCoverage,
            5000L,
            false,
            true  // timedOut
        );

        // Act
        QueryResult testResult = QueryResult.fromQueryResponse(response, "timeout-query");

        // Assert
        assertThat(testResult.getMetadata())
            .containsEntry("timedOut", true)
            .containsEntry("segmentsTimedOut", 2);
    }

    @Test
    @DisplayName("fromQueryResponse throws on null response")
    void fromQueryResponse_withNullResponse_throwsNullPointerException() {
        assertThatThrownBy(() -> QueryResult.fromQueryResponse(null, "q1"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("QueryResponse cannot be null");
    }

    @Test
    @DisplayName("fromQueryResponse throws on null queryId")
    void fromQueryResponse_withNullQueryId_throwsNullPointerException() {
        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(1)
            .estimatedCorpusCoverage(1.0)
            .build();

        excalibur.query.QueryResponse response = excalibur.query.QueryResponse.empty(queryCoverage, 1L);

        assertThatThrownBy(() -> QueryResult.fromQueryResponse(response, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("QueryId cannot be null");
    }

    @Test
    @DisplayName("fromQueryResponse includes all additional coverage fields in metadata")
    void fromQueryResponse_includesAllCoverageFieldsInMetadata() {
        // Arrange
        excalibur.query.IndexCoverage queryCoverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(100)
            .activeSegments(70)
            .indexingSegments(10)
            .warmingSegments(5)
            .compactingSegments(3)
            .tombstonedSegments(12)
            .tailScanEntries(1500L)
            .segmentsSearched(85)
            .segmentsWithErrors(2)
            .segmentsTimedOut(1)
            .estimatedCorpusCoverage(0.88)
            .degraded(true)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            Collections.emptyList(),
            queryCoverage,
            250L,
            true,
            false
        );

        // Act
        QueryResult testResult = QueryResult.fromQueryResponse(response, "full-coverage");

        // Assert: All extra fields should be in metadata
        Map<String, Object> metadata = testResult.getMetadata();
        assertThat(metadata)
            .containsEntry("compactingSegments", 3L)
            .containsEntry("tombstonedSegments", 12L)
            .containsEntry("tailScanEntries", 1500L)
            .containsEntry("segmentsSearched", 85)
            .containsEntry("segmentsWithErrors", 2)
            .containsEntry("segmentsTimedOut", 1);
    }
}
