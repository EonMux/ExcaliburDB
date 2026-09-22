/*
 * excalibur-test - GoldenTest Framework Verification Test
 *
 * Verifies the core comparison functionality of the GoldenTest framework.
 */

package com.eonmux.excalibur.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GoldenTestVerificationTest {

    @Test
    @DisplayName("GoldenTest compares matching results correctly")
    void goldenTest_matchesResults() {
        // Create query
        Query query = new Query("q1", "test query", Map.of("term", 1.0));

        // Create expected result
        List<QueryResultItem> expectedItems = new ArrayList<>();
        expectedItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        expectedItems.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        ExpectedResult expected = new ExpectedResult(
            "q1",
            expectedItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Create golden test
        GoldenTest goldenTest = new GoldenTest("test1", query, expected);

        // Create matching actual result
        List<QueryResultItem> actualItems = new ArrayList<>();
        actualItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        actualItems.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        QueryResult actual = new QueryResult(
            "q1",
            actualItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Assert comparison passes
        assertTrue(goldenTest.assertResults(actual));
    }

    @Test
    @DisplayName("GoldenTest detects mismatched document IDs")
    void goldenTest_detectsMismatch_fails() {
        // Create query
        Query query = new Query("q1", "test query");

        // Create expected result
        List<QueryResultItem> expectedItems = new ArrayList<>();
        expectedItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        expectedItems.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        ExpectedResult expected = new ExpectedResult(
            "q1",
            expectedItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Create golden test
        GoldenTest goldenTest = new GoldenTest("test2", query, expected);

        // Create non-matching actual result (different doc)
        List<QueryResultItem> actualItems = new ArrayList<>();
        actualItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        actualItems.add(new QueryResultItem("doc3", 0.85f, Collections.emptyMap(), null, null, -1)); // doc3 instead of doc2

        QueryResult actual = new QueryResult(
            "q1",
            actualItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Assert comparison fails
        assertFalse(goldenTest.assertResults(actual));
    }

    @Test
    @DisplayName("GoldenTest detects coverage mismatch")
    void goldenTest_coverageMismatch_fails() {
        // Create query
        Query query = new Query("q1", "test query");

        // Create expected result with full coverage
        List<QueryResultItem> expectedItems = new ArrayList<>();
        expectedItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));

        ExpectedResult expected = new ExpectedResult(
            "q1",
            expectedItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Create golden test
        GoldenTest goldenTest = new GoldenTest("test3", query, expected);

        // Create actual result with degraded coverage
        List<QueryResultItem> actualItems = new ArrayList<>();
        actualItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        QueryResult actual = new QueryResult(
            "q1",
            actualItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            new IndexCoverage(10, 5, 0, 0, 0.25, true)  // degraded=true
        );

        // Assert comparison fails due to coverage mismatch
        assertFalse(goldenTest.assertResults(actual));
    }

    @Test
    @DisplayName("GoldenTest handles tolerance correctly")
    void goldenTest_toleranceWorks() {
        // Create query
        Query query = new Query("q1", "test query");

        // Create expected result with exact score
        List<QueryResultItem> expectedItems = new ArrayList<>();
        expectedItems.add(new QueryResultItem("doc1", 0.95000001f, Collections.emptyMap(), null, null, -1));
        expectedItems.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        ExpectedResult expected = new ExpectedResult(
            "q1",
            expectedItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Create golden test with tolerance large enough to accept small differences
        // (1e-6 tolerance should pass for ~1e-8 difference)
        GoldenTest goldenTest = new GoldenTest("test4", query, expected, 1e-6);

        // Create matching actual result (slightly different score within tolerance)
        List<QueryResultItem> actualItems = new ArrayList<>();
        actualItems.add(new QueryResultItem("doc1", 0.95000002f, Collections.emptyMap(), null, null, -1));
        actualItems.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));
        QueryResult actual = new QueryResult(
            "q1",
            actualItems,
            PartialResult.empty(),
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Assert comparison passes with tolerance (1e-6 > 1e-8 difference)
        assertTrue(goldenTest.assertResults(actual));
    }

    @Test
    @DisplayName("ResultComparator compares results correctly")
    void resultComparator_matchesResults() {
        ResultComparator comparator = new ResultComparator(1e-6);

        List<QueryResultItem> actual = new ArrayList<>();
        actual.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        actual.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        List<QueryResultItem> expected = new ArrayList<>();
        expected.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));  // Different order
        expected.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));

        // Should pass because comparator sorts by document ID
        assertTrue(comparator.compareResults(actual, expected));
    }

    @Test
    @DisplayName("ResultComparator detects differences")
    void resultComparator_detectsDifferences() {
        ResultComparator comparator = new ResultComparator(1e-6);

        List<QueryResultItem> actual = new ArrayList<>();
        actual.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        actual.add(new QueryResultItem("doc3", 0.75f, Collections.emptyMap(), null, null, -1)); // doc3 instead of doc2

        List<QueryResultItem> expected = new ArrayList<>();
        expected.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));
        expected.add(new QueryResultItem("doc2", 0.85f, Collections.emptyMap(), null, null, -1));

        List<ResultComparator.Difference> diffs = comparator.findDifferences(actual, expected);

        // Should find differences
        assertFalse(diffs.isEmpty());
        boolean foundMissing = diffs.stream().anyMatch(d ->
            d.getMessage().contains("doc3") || d.getMessage().contains("doc2")
        );
        assertTrue(foundMissing);
    }

    @Test
    @DisplayName("GoldenTest handles partial results correctly")
    void goldenTest_partialResults() {
        // Create query
        Query query = new Query("q1", "test query");

        // Create expected result with partial results
        List<QueryResultItem> expectedItems = new ArrayList<>();
        expectedItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));

        Set<String> partialIds = new HashSet<>();
        partialIds.add("doc2");
        partialIds.add("doc3");
        PartialResult expectedPartial = PartialResult.withPartialIds(100, partialIds);

        ExpectedResult expected = new ExpectedResult(
            "q1",
            expectedItems,
            expectedPartial,
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Create golden test
        GoldenTest goldenTest = new GoldenTest("test5", query, expected);

        // Create matching actual result
        List<QueryResultItem> actualItems = new ArrayList<>();
        actualItems.add(new QueryResultItem("doc1", 0.95f, Collections.emptyMap(), null, null, -1));

        Set<String> actualPartialIds = new HashSet<>();
        actualPartialIds.add("doc2");
        actualPartialIds.add("doc3");
        PartialResult actualPartial = PartialResult.withPartialIds(100, actualPartialIds);

        QueryResult actual = new QueryResult(
            "q1",
            actualItems,
            actualPartial,
            Collections.emptyMap(),
            IndexCoverage.fullyActive(10)
        );

        // Assert comparison passes
        assertTrue(goldenTest.assertResults(actual));
    }
}
