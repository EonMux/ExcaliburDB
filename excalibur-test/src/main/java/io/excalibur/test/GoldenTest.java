/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - GoldenTest: Thread-safe for test execution, each test instance is independent
 *   - ResultComparator: Thread-safe, stateless comparison operations
 *   - ExpectedResult: Thread-safe, immutable result builder
 *   - VerificationReport: Thread-safe for creation, NOT thread-safe for concurrent modification
 */

package io.excalibur.test;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Base class for golden test cases that verify query results against expected values.
 *
 * Each test case defines a query and its expected result. The comparison is deterministic:
 * same inputs always produce identical comparison results.
 *
 * Results are sorted by document ID before comparison to ensure stable ordering.
 * Numeric comparisons use configurable epsilon tolerance.
 */
public class GoldenTest {

    private static final Logger LOG = Logger.getLogger(GoldenTest.class.getName());

    private final String name;
    private final Query query;
    private final ExpectedResult expected;

    /**
     * Constructor for a new golden test case.
     *
     * @param name  Unique test name for reporting
     * @param query The query being tested
     * @param expected The expected result structure
     */
    public GoldenTest(String name, Query query, ExpectedResult expected) {
        this.name = name;
        this.query = query;
        this.expected = expected;
    }

    /**
     * Constructor with parameters.
     *
     * @param name      Unique test name
     * @param query     The query being tested
     * @param expected  The expected result structure
     * @param tolerance Numeric tolerance for floating-point comparison
     */
    public GoldenTest(String name, Query query, ExpectedResult expected, double tolerance) {
        this.name = name;
        this.query = query;
        this.expected = expected;
        this.tolerance = tolerance;
    }
    
    private double tolerance = 1e-6;
    
    /**
     * Get the unique test name.
     * 
     * @return The test name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Get the query being tested.
     * 
     * @return The query
     */
    public Query getQuery() {
        return query;
    }
    
    /**
     * Get the expected result.
     * 
     * @return The expected result
     */
    public ExpectedResult getExpected() {
        return expected;
    }
    
    /**
     * Get the numeric tolerance for floating-point comparison.
     * 
     * @return The tolerance value
     */
    public double getTolerance() {
        return tolerance;
    }
    
    /**
     * Compare actual query results against expected results.
     * 
     * This method performs deterministic comparison:
     * 1. Results are sorted by document ID for stable ordering
     * 2. String comparisons use exact equality
     * 3. Numeric comparisons use configured tolerance
     * 4. Collection comparisons use element-wise comparison
     * 
     * @param actual The actual query results to compare
     * @return true if results match expected within tolerance
     */
    public boolean assertResults(QueryResult actual) {
        return assertResults(actual, null);
    }
    
    /**
     * Compare actual query results against expected results with custom tolerance.
     * 
     * @param actual  The actual query results to compare
     * @param customTolerance  Override tolerance for this comparison
     * @return true if results match expected within tolerance
     */
    public boolean assertResults(QueryResult actual, Double customTolerance) {
        double tol = customTolerance != null ? customTolerance : tolerance;
        
        // Validate inputs
        if (actual == null) {
            throw new IllegalArgumentException("Actual query result cannot be null");
        }
        if (expected == null) {
            throw new IllegalArgumentException("Expected result cannot be null");
        }
        if (query == null) {
            throw new IllegalArgumentException("Query cannot be null");
        }
        
        // Perform comparison
        boolean match = compareResults(actual, expected, tol);
        
        if (match) {
            LOG.info("✓ Test PASSED: " + name);
            return true;
        } else {
            LOG.severe("✗ Test FAILED: " + name);
            LOG.severe("  Query: " + query.getId());
            reportMismatch(actual, expected, tol);
            return false;
        }
    }
    
    /**
     * Compare two result structures for equality.
     * 
     * @param actual  The actual result
     * @param expected The expected result
     * @param tolerance Numeric tolerance for floats
     * @return true if results match
     */
    protected boolean compareResults(QueryResult actual, ExpectedResult expected, double tolerance) {
        // Compare query IDs first
        if (!queryIdMatch(actual.getQueryId(), expected.getQueryId())) {
            return false;
        }
        
        // Compare top_k results
        if (!topKMatch(actual.getTopK(), expected.getTopK(), tolerance)) {
            return false;
        }
        
        // Compare partial results
        if (!partialMatch(actual.getPartial(), expected.getPartial(), tolerance)) {
            return false;
        }
        
        // Compare metadata
        if (!metadataMatch(actual.getMetadata(), expected.getMetadata(), tolerance)) {
            return false;
        }
        
        // Compare index coverage
        if (!coverageMatch(actual.getIndexCoverage(), expected.getIndexCoverage(), tolerance)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compare query IDs.
     */
    protected boolean queryIdMatch(String actual, String expected) {
        return Objects.equals(actual, expected);
    }
    
    /**
     * Compare top_k results.
     */
    protected boolean topKMatch(List<QueryResultItem> actual, List<QueryResultItem> expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        if (actual.size() != expected.size()) {
            return false;
        }
        
        // Sort both lists by document ID for deterministic comparison
        return actual.stream()
            .sorted(Comparator.comparing(QueryResultItem::getDocumentId))
            .allMatch(item -> expected.stream()
                .anyMatch(expItem -> compareItem(item, expItem, tolerance)))
            && expected.stream()
                .allMatch(item -> actual.stream()
                    .anyMatch(actItem -> compareItem(actItem, item, tolerance)));
    }
    
    /**
     * Compare partial results.
     */
    protected boolean partialMatch(PartialResult actual, PartialResult expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.getTotalCount() == expected.getTotalCount() &&
               partialSetMatch(actual.getPartialIds(), expected.getPartialIds(), tolerance);
    }
    
    /**
     * Compare metadata.
     */
    protected boolean metadataMatch(Map<String, Object> actual, Map<String, Object> expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.size() == expected.size() &&
               actual.entrySet().stream()
                   .allMatch(entry -> {
                       String key = entry.getKey();
                       Object actualVal = entry.getValue();
                       Object expectedVal = expected.get(key);
                       
                       if (actualVal == null && expectedVal == null) return true;
                       if (actualVal == null || expectedVal == null) return false;
                       
                       return compareValues(actualVal, expectedVal, tolerance);
                   });
    }
    
    /**
     * Compare index coverage.
     */
    protected boolean coverageMatch(IndexCoverage actual, IndexCoverage expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.getTotalSegments() == expected.getTotalSegments() &&
               actual.getActiveSegments() == expected.getActiveSegments() &&
               actual.getIndexingSegments() == expected.getIndexingSegments() &&
               actual.getWarmingSegments() == expected.getWarmingSegments() &&
               Math.abs(actual.getEstimatedCoverage() - expected.getEstimatedCoverage()) <= tolerance &&
               actual.isDegraded() == expected.isDegraded();
    }
    
    /**
     * Compare index coverage values.
     */
    protected boolean coverageValuesMatch(int actual, int expected) {
        return actual == expected;
    }
    
    /**
     * Compare index coverage booleans.
     */
    protected boolean coverageBooleanMatch(boolean actual, boolean expected) {
        return actual == expected;
    }
    
    /**
     * Compare partial result sets.
     */
    protected boolean partialSetMatch(Set<String> actual, Set<String> expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        if (actual.size() != expected.size()) {
            return false;
        }
        
        return actual.stream()
            .allMatch(id -> expected.stream()
                .anyMatch(expId -> compareId(id, expId, tolerance)));
    }
    
    /**
     * Compare individual item results.
     */
    protected boolean compareItem(QueryResultItem actual, QueryResultItem expected, double tolerance) {
        return compareId(actual.getDocumentId(), expected.getDocumentId(), tolerance) &&
               compareScore(actual.getScore(), expected.getScore(), tolerance) &&
               compareMetadata(actual.getMetadata(), expected.getMetadata(), tolerance) &&
               compareContent(actual.getContent(), expected.getContent(), tolerance) &&
               compareSource(actual.getSource(), expected.getSource(), tolerance) &&
               compareChunk(actual.getChunkIndex(), expected.getChunkIndex());
    }
    
    /**
     * Compare document IDs.
     */
    protected boolean compareId(String actual, String expected, double tolerance) {
        return Objects.equals(actual, expected);
    }
    
    /**
     * Compare floating-point scores.
     */
    protected boolean compareScore(float actual, float expected, double tolerance) {
        return Math.abs((double) actual - (double) expected) <= tolerance;
    }
    
    /**
     * Compare integer chunk indices.
     */
    protected boolean compareChunk(int actual, int expected) {
        return actual == expected;
    }
    
    /**
     * Compare metadata maps.
     */
    protected boolean compareMetadata(Map<String, Object> actual, Map<String, Object> expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.entrySet().stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                Object actualVal = entry.getValue();
                Object expectedVal = expected.get(key);
                
                if (actualVal == null && expectedVal == null) return true;
                if (actualVal == null || expectedVal == null) return false;
                
                return compareValues(actualVal, expectedVal, tolerance);
            });
    }
    
    /**
     * Compare content strings.
     */
    protected boolean compareContent(String actual, String expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.equals(expected);
    }
    
    /**
     * Compare source values.
     */
    protected boolean compareSource(String actual, String expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return actual.equals(expected);
    }
    
    /**
     * General value comparison.
     */
    protected boolean compareValues(Object actual, Object expected, double tolerance) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        if (actual instanceof String s && expected instanceof String es) {
            return s.equals(es);
        }
        
        if (actual instanceof Number a && expected instanceof Number e) {
            double diff = Math.abs((double) a - (double) e);
            return diff <= tolerance;
        }
        
        if (actual instanceof Boolean b && expected instanceof Boolean e) {
            return b == e;
        }
        
        return Objects.equals(actual, expected);
    }
    
    /**
     * Compare floating-point values.
     */
    protected boolean compareDouble(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }
    
    /**
     * Compare floating-point values (relative tolerance).
     */
    protected boolean compareDoubleRelative(double actual, double expected, double tolerance) {
        double diff = Math.abs(actual - expected);
        double max = Math.max(Math.abs(actual), Math.abs(expected));
        return diff <= tolerance * max;
    }
    
    /**
     * Report comparison mismatch with details.
     */
    protected void reportMismatch(QueryResult actual, ExpectedResult expected, double tolerance) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Comparison Mismatch ===\n");
        sb.append("Test: ").append(name).append("\n");
        sb.append("Query: ").append(queryIdMatch(actual.getQueryId(), expected.getQueryId()) ? "MATCH" : "MISMATCH\n");
        sb.append("Top K: ");
        
        String actualItems = actual.getTopK().stream()
            .sorted(Comparator.comparing(QueryResultItem::getDocumentId))
            .limit(10)
            .map(i -> i.getDocumentId() + " (score: " + String.format("%.6f", i.getScore()) + ")")
            .collect(Collectors.joining(", "));

        String expectedItems = expected.getTopK().stream()
            .sorted(Comparator.comparing(QueryResultItem::getDocumentId))
            .limit(10)
            .map(i -> i.getDocumentId() + " (score: " + String.format("%.6f", i.getScore()) + ")")
            .collect(Collectors.joining(", "));
        
        sb.append(actualItems.isEmpty() ? "empty" : actualItems);
        sb.append(" vs ").append(expectedItems.isEmpty() ? "empty" : expectedItems);
        sb.append("\n");
        
        sb.append("Partial: ");
        sb.append(actual.getPartial() == null ? "none" :
              partialMatch(actual.getPartial(), expected.getPartial(), tolerance) ? "MATCH" : "MISMATCH");
        sb.append("\n");
        
        sb.append("Coverage: ");
        boolean cov = coverageMatch(actual.getIndexCoverage(), expected.getIndexCoverage(), tolerance);
        sb.append(cov ? "MATCH" : "MISMATCH");
        sb.append("\n");
        
        sb.append("===========================\n");
        
        LOG.severe(sb.toString());
    }
}
