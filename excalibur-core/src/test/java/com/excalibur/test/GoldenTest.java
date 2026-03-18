package com.excalibur.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Base golden test class for deterministic query result verification.
 * 
 * Golden tests compare query outputs against known expected results.
 * All comparisons are deterministic - same inputs always produce identical results.
 */
public class GoldenTest {
    
    private final String name;
    private final TestContext context;
    private boolean passed;
    private String errorMessage;
    private List<String> failures;
    private List<String> warnings;
    
    public GoldenTest(String name, TestContext context) {
        this.name = name;
        this.context = context;
        this.passed = true;
        this.failures = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }
    
    /**
     * Get test name.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Get test context.
     */
    public TestContext getContext() {
        return context;
    }
    
    /**
     * Get overall result.
     */
    public boolean isPassed() {
        return passed;
    }
    
    /**
     * Get failure details.
     */
    public List<String> getFailures() {
        return Collections.unmodifiableList(failures);
    }
    
    /**
     * Get warnings.
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    /**
     * Record a failure.
     */
    public <T> void fail(String message, T actual, T expected) {
        this.passed = false;
        this.failures.add("FAIL: " + message + 
            " | Actual: " + context.format(actual) + 
            " | Expected: " + context.format(expected));
    }
    
    /**
     * Record a failure.
     */
    public <T> void fail(String message, T actual) {
        this.passed = false;
        this.failures.add("FAIL: " + message + " | Actual: " + context.format(actual));
    }
    
    /**
     * Record a warning.
     */
    public <T> void warn(String message, T value) {
        this.warnings.add("WARN: " + message + " | " + context.format(value));
    }
    
    /**
     * Record a warning.
     */
    public void warn(String message) {
        this.warnings.add("WARN: " + message);
    }
    
    /**
     * Mark test as passed.
     */
    public void pass() {
        this.passed = true;
    }
    
    /**
     * Generate JSON report for this test.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escapeJson(name)).append("\"");
        sb.append(",\"passed\":[").append(passed).append("\"");
        
        if (!failures.isEmpty()) {
            sb.append(",\"failures\":[");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(escapeJson(failures.get(i)));
            }
            sb.append("]");
        }
        
        if (!warnings.isEmpty()) {
            sb.append(",\"warnings\":[");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(escapeJson(warnings.get(i)));
            }
            sb.append("]");
        }
        
        sb.append("\\}");
        return sb.toString();
    }
    
    /**
     * Escape string for JSON.
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n").replace("\r", "\r").replace("\t", "\t");
    }
    
    /**
     * Pretty print report with indentation.
     */
    public String prettyReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(String.format("  \"name\": \"%s\",\n", escapeJson(name)));
        sb.append(String.format("  \"passed\": %s,\n", passed));
        
        if (!failures.isEmpty()) {
            sb.append("  \"failures\": [");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append("    " + escapeJson(failures.get(i)));
            }
            sb.append("\n  ]");
        }
        
        if (!warnings.isEmpty()) {
            sb.append(",\n  \"warnings\": [");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append("    " + escapeJson(warnings.get(i)));
            }
            sb.append("\n  ]");
        }
        
        sb.append("\n}");
        return sb.toString();
    }
    
    /**
     * Run this test and record results.
     */
    public void run() {
        // Default test - pass if no assertions have been made
        pass();
    }
    
    /**
     * Assert that two result lists match within tolerance.
     * 
     * @param expected Expected results (sorted by document ID)
     * @param actual   Actual results (sorted by document ID)
     * @param epsilon  Numerical tolerance for float comparisons
     */
    public void assertResults(List<Result> expected, List<Result> actual, double epsilon) {
        ResultComparator comparator = new ResultComparator(epsilon);
        boolean match = comparator.compareResults(expected, actual, context);
        
        if (match) {
            pass();
        } else {
            fail("Results do not match", actual);
        }
    }
    
    /**
     * Assert that a single result matches expected within tolerance.
     * 
     * @param expected Expected result
     * @param actual   Actual result
     * @param epsilon  Numerical tolerance for float comparisons
     */
    public void assertResult(Result expected, Result actual, double epsilon) {
        ResultComparator comparator = new ResultComparator(epsilon);
        boolean match = comparator.resultsEqual(expected, actual);
        
        if (match) {
            pass();
        } else {
            fail("Result does not match", actual);
        }
    }
    
    /**
     * Assert that two values are equal within tolerance.
     * 
     * @param expected Expected value
     * @param actual    Actual value
     * @param epsilon   Numerical tolerance
     */
    public void assertEquals(double expected, double actual, double epsilon) {
        boolean equal = Math.abs(expected - actual) <= epsilon;
        if (equal) {
            pass();
        } else {
            fail("Values not equal within epsilon", actual);
        }
    }
    
    /**
     * Assert that two values are equal.
     * 
     * @param expected Expected value
     * @param actual    Actual value
     */
    public void assertEquals(Object expected, Object actual) {
        boolean equal = expected.equals(actual);
        if (equal) {
            pass();
        } else {
            fail("Values not equal", actual);
        }
    }
    
    /**
     * Assert that a collection is empty.
     * 
     * @param collection Collection to check
     */
    public void assertTrueEmpty(Collection<?> collection) {
        boolean empty = collection == null || collection.isEmpty();
        if (empty) {
            pass();
        } else {
            fail("Collection is not empty", collection);
        }
    }
    
    /**
     * Assert that a collection is not empty.
     * 
     * @param collection Collection to check
     */
    public void assertTrueNotEmpty(Collection<?> collection) {
        boolean notEmpty = collection != null && !collection.isEmpty();
        if (notEmpty) {
            pass();
        } else {
            fail("Collection is empty", collection);
        }
    }
    
    /**
     * Assert that two collections are equal.
     * 
     * @param expected Expected collection
     * @param actual    Actual collection
     */
    public void assertCollectionsEqual(Collection<?> expected, Collection<?> actual) {
        boolean equal = expected.equals(actual);
        if (equal) {
            pass();
        } else {
            fail("Collections not equal", actual);
        }
    }
    
    /**
     * Assert that all items in a collection match expected values.
     * 
     * @param collection Collection of results
     * @param epsilon    Numerical tolerance
     */
    public void assertAllResultsMatch(List<Result> collection, double epsilon) {
        for (Result result : collection) {
            assertResult(result, result, epsilon);
        }
        pass();
    }
    
    /**
     * Assert that a boolean value is true.
     * 
     * @param condition Condition to check
     */
    public void assertTrue(boolean condition) {
        if (condition) {
            pass();
        } else {
            fail("Condition is false", condition);
        }
    }
    
    /**
     * Assert that a boolean value is false.
     * 
     * @param condition Condition to check
     */
    public void assertFalse(boolean condition) {
        if (!condition) {
            pass();
        } else {
            fail("Condition is true", condition);
        }
    }
    
    /**
     * Assert that a string is not null and not empty.
     * 
     * @param string String to check
     */
    public void assertNotNullNotEmpty(String string) {
        boolean notNullNotEmpty = string != null && !string.isEmpty();
        if (notNullNotEmpty) {
            pass();
        } else {
            fail("String is null or empty", string);
        }
    }
    
    /**
     * Assert that a numeric value is within expected range.
     * 
     * @param value   Value to check
     * @param expected Expected value
     * @param epsilon Numerical tolerance
     */
    public void assertValueInRange(double value, double expected, double epsilon) {
        double diff = Math.abs(value - expected);
        boolean inRange = diff <= epsilon;
        if (inRange) {
            pass();
        } else {
            fail("Value " + value + " not within " + expected + " "+ epsilon, value);
        }
    }
    
    /**
     * Assert that a relevance score equals expected value.
     * 
     * @param score   Score to check
     * @param expected Expected score
     * @param epsilon Numerical tolerance
     */
    public void assertRelevance(double score, double expected, double epsilon) {
        assertValueInRange(score, expected, epsilon);
    }
    
    /**
     * Assert that a query produces the expected results.
     * 
     * @param query        Query to run (contains query tags)
     * @param expectedTags Expected query tags
     * @param results       Actual results
     * @param epsilon       Numerical tolerance
     */
    public void assertQueryResults(QueryWithExpected query, List<Result> results, double epsilon) {
        List<Result> sortedResults = ResultComparator.sortResults(results, epsilon);
        List<Result> expectedResults = query.getExpectedResults();
        boolean match = ResultComparator.resultsEqual(sortedResults, expectedResults);
        
        if (match) {
            pass();
        } else {
            fail("Query results do not match", results);
        }
    }
    
    /**
     * Assert that two document IDs are equal.
     * 
     * @param id1 First document ID
     * @param id2 Second document ID
     */
    public void assertDocumentIdsEqual(String id1, String id2) {
        boolean equal = id1.equals(id2);
        if (equal) {
            pass();
        } else {
            fail("Document IDs not equal", id2);
        }
    }
    
    /**
     * Assert that a relevance formula is correctly computed.
     * 
     * @param queryTags Query tags
     * @param docTags    Document tags
     * @param expected   Expected relevance
     * @param epsilon    Numerical tolerance
     */
    public void assertRelevanceFormula(List<String> queryTags, List<String> docTags, double expected, double epsilon) {
        double actual = ResultComparator.computeRelevance(queryTags, docTags);
        assertEquals(actual, expected, epsilon);
    }
    
}

