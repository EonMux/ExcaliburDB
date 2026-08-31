/*
 * excalibur-test - Query Result Verification Service
 *
 * Verifies that both comparison engines (ResultComparator and GoldenTest) can compare
 * the boxed metadata QueryResult.fromQueryResponse actually produces: Long and Integer.
 */

package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Metadata value comparison")
class MetadataComparisonTest {

    private static QueryResultItem itemWithMetadata(Map<String, Object> metadata) {
        return new QueryResultItem("doc-1", 0.9f, metadata, null, null, -1);
    }

    @Nested
    @DisplayName("ResultComparator")
    class ResultComparatorMetadata {

        private final ResultComparator comparator = new ResultComparator(1e-6);

        @Test
        @DisplayName("compares Long metadata without a ClassCastException")
        void comparesLongMetadata() {
            Map<String, Object> metadata = Map.of("segmentId", 5L, "nodeId", 42L);

            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(metadata)),
                List.of(itemWithMetadata(metadata)))).isTrue();
        }

        @Test
        @DisplayName("detects differing Long metadata")
        void detectsDifferingLongMetadata() {
            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(Map.of("segmentId", 5L))),
                List.of(itemWithMetadata(Map.of("segmentId", 6L))))).isFalse();
        }

        @Test
        @DisplayName("compares Integer metadata exactly")
        void comparesIntegerMetadata() {
            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(Map.of("resultCount", 3))),
                List.of(itemWithMetadata(Map.of("resultCount", 3))))).isTrue();
            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(Map.of("resultCount", 3))),
                List.of(itemWithMetadata(Map.of("resultCount", 4))))).isFalse();
        }

        @Test
        @DisplayName("distinguishes large longs that collapse onto the same double")
        void distinguishesLargeLongs() {
            long first = (1L << 53) + 1L;
            long second = (1L << 53) + 2L;

            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(Map.of("nodeId", first))),
                List.of(itemWithMetadata(Map.of("nodeId", second))))).isFalse();
        }

        @Test
        @DisplayName("compares Float metadata within tolerance")
        void comparesFloatMetadataWithinTolerance() {
            assertThat(comparator.compareResults(
                List.of(itemWithMetadata(Map.of("ratio", 0.5f))),
                List.of(itemWithMetadata(Map.of("ratio", 0.5f))))).isTrue();
        }

        @Test
        @DisplayName("compares two converted QueryResults end to end")
        void comparesConvertedQueryResults() {
            QueryResult first = convertedResult();
            QueryResult second = convertedResult();

            assertThat(comparator.compareResults(first, second)).isTrue();
        }
    }

    @Nested
    @DisplayName("GoldenTest")
    class GoldenTestMetadata {

        @Test
        @DisplayName("compares Long metadata without a ClassCastException")
        void comparesLongMetadata() {
            Map<String, Object> metadata = Map.of("segmentId", 5L, "nodeId", 42L);
            List<QueryResultItem> items = List.of(itemWithMetadata(metadata));

            ExpectedResult expected = new ExpectedResult(
                "q1", items, PartialResult.empty(), Map.of("queryTimeMs", 12L), IndexCoverage.fullyActive(1));
            QueryResult actual = new QueryResult(
                "q1", items, PartialResult.empty(), Map.of("queryTimeMs", 12L), IndexCoverage.fullyActive(1));

            assertThat(new GoldenTest("golden", new Query("q1", "text"), expected)
                .assertResults(actual)).isTrue();
        }

        @Test
        @DisplayName("detects differing Long metadata")
        void detectsDifferingLongMetadata() {
            ExpectedResult expected = new ExpectedResult(
                "q1", List.of(itemWithMetadata(Map.of("segmentId", 5L))),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));
            QueryResult actual = new QueryResult(
                "q1", List.of(itemWithMetadata(Map.of("segmentId", 6L))),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));

            assertThat(new GoldenTest("golden", new Query("q1", "text"), expected)
                .assertResults(actual)).isFalse();
        }

        @Test
        @DisplayName("detects an item whose metadata came back empty")
        void detectsMissingItemMetadata() {
            // Comparing only the keys the query returned makes an empty map match anything, so a
            // regression that dropped the provenance fields entirely was reported as a pass.
            ExpectedResult expected = new ExpectedResult(
                "q1", List.of(itemWithMetadata(Map.of("segmentId", 5L, "nodeId", 42L))),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));
            QueryResult actual = new QueryResult(
                "q1", List.of(itemWithMetadata(Collections.emptyMap())),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));

            assertThat(new GoldenTest("golden", new Query("q1", "text"), expected)
                .assertResults(actual)).isFalse();
        }

        @Test
        @DisplayName("detects an item missing one expected metadata key")
        void detectsOneMissingMetadataKey() {
            ExpectedResult expected = new ExpectedResult(
                "q1", List.of(itemWithMetadata(Map.of("segmentId", 5L, "nodeId", 42L))),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));
            QueryResult actual = new QueryResult(
                "q1", List.of(itemWithMetadata(Map.of("segmentId", 5L))),
                PartialResult.empty(), Collections.emptyMap(), IndexCoverage.fullyActive(1));

            assertThat(new GoldenTest("golden", new Query("q1", "text"), expected)
                .assertResults(actual)).isFalse();
        }

        @Test
        @DisplayName("verifies a converted QueryResponse against a golden expectation")
        void verifiesConvertedQueryResponse() {
            QueryResult actual = convertedResult();

            ExpectedResult expected = new ExpectedResult(
                "q1",
                actual.getTopK(),
                PartialResult.empty(),
                actual.getMetadata(),
                actual.getIndexCoverage());

            assertThat(new GoldenTest("golden", new Query("q1", "text"), expected)
                .assertResults(actual)).isTrue();
        }
    }

    private static QueryResult convertedResult() {
        excalibur.query.QueryResultItem queryItem = new excalibur.query.QueryResultItem(
            "doc-1", 0.9f, null, 5L, 42L);

        excalibur.query.IndexCoverage coverage = excalibur.query.IndexCoverage.builder()
            .totalSegments(4)
            .activeSegments(3)
            .indexingSegments(1)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();

        excalibur.query.QueryResponse response = new excalibur.query.QueryResponse(
            List.of(queryItem), coverage, 12L, false, false);

        return QueryResult.fromQueryResponse(response, "q1");
    }
}
