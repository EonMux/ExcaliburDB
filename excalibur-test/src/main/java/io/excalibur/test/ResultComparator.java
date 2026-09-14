/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - ResultComparator: Thread-safe, stateless comparison operations
 *     Can be safely used concurrently without synchronization
 * 
 * This comparator ensures deterministic ordering by sorting results
 * by document ID before comparison, guaranteeing consistent results
 * regardless of input order.
 */

package io.excalibur.test;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Deterministic comparator for query result verification.
 *
 * The comparator ensures that comparison results are always the same
 * for the same inputs, regardless of processing order. This is achieved
 * by:
 *
 * 1. Sorting results by document ID before comparison
 * 2. Using stable comparison operations
 * 3. Applying consistent tolerance for numeric comparisons
 *
 * This makes the verification process reproducible and debuggable.
 */
public class ResultComparator {

    private static final Logger LOG = Logger.getLogger(ResultComparator.class.getName());
    
    private final double tolerance;
    private final double relativeTolerance;
    private final boolean strictMode;
    
    /**
     * Create a new ResultComparator that applies one tolerance to both absolute and relative
     * numeric comparisons and runs in strict mode.
     *
     * @param tolerance  Tolerance for numeric comparisons, used as both the absolute and the
     *                   relative bound
     */
    public ResultComparator(double tolerance) {
        this(tolerance, tolerance, true);
    }
    
    /**
     * Create a new ResultComparator with custom tolerances.
     * 
     * @param tolerance  Absolute tolerance for numeric comparisons
     * @param relativeTolerance  Relative tolerance for large values
     * @param strictMode  Whether to fail on exact type match
     */
    public ResultComparator(double tolerance, double relativeTolerance, boolean strictMode) {
        this.tolerance = tolerance;
        this.relativeTolerance = relativeTolerance;
        this.strictMode = strictMode;
    }
    
    /**
     * Get the absolute tolerance.
     * 
     * @return The absolute tolerance
     */
    public double getTolerance() {
        return tolerance;
    }
    
    /**
     * Get the relative tolerance.
     * 
     * @return The relative tolerance
     */
    public double getRelativeTolerance() {
        return relativeTolerance;
    }
    
    /**
     * Get strict mode setting.
     * 
     * @return true if strict mode is enabled
     */
    public boolean isStrictMode() {
        return strictMode;
    }
    
    /**
     * Compare two QueryResult lists for equality.
     * 
     * Results are sorted by document ID before comparison to ensure
     * deterministic ordering.
     * 
     * @param actual  The actual results
     * @param expected The expected results
     * @return true if results match
     */
    public boolean compareResults(List<QueryResultItem> actual, List<QueryResultItem> expected) {
        // Handle null/empty cases
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        if (actual.size() != expected.size()) return false;
        
        // Sort both lists by document ID for deterministic comparison
        List<QueryResultItem> sortedActual = new ArrayList<>(actual);
        List<QueryResultItem> sortedExpected = new ArrayList<>(expected);
        
        sortedActual.sort(Comparator.comparing(QueryResultItem::getDocumentId));
        sortedExpected.sort(Comparator.comparing(QueryResultItem::getDocumentId));
        
        // Compare item by item
        for (int i = 0; i < actual.size(); i++) {
            if (!compareItem(sortedActual.get(i), sortedExpected.get(i))) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Compare two QueryResultItem instances.
     * 
     * @param actual  The actual item
     * @param expected The expected item
     * @return true if items match
     */
    public boolean compareItem(QueryResultItem actual, QueryResultItem expected) {
        // Document ID must match exactly
        if (!Objects.equals(actual.getDocumentId(), expected.getDocumentId())) {
            return false;
        }
        
        // Score comparison with tolerance
        if (!compareScore(actual.getScore(), expected.getScore())) {
            return false;
        }
        
        // Content must match exactly
        if (!Objects.equals(actual.getContent(), expected.getContent())) {
            return false;
        }
        
        // Source must match exactly
        if (!Objects.equals(actual.getSource(), expected.getSource())) {
            return false;
        }
        
        // Chunk index must match exactly
        if (actual.getChunkIndex() != expected.getChunkIndex()) {
            return false;
        }
        
        // Metadata comparison
        if (!compareMetadata(actual.getMetadata(), expected.getMetadata())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compare floating-point scores.
     * 
     * Uses both absolute and relative tolerance for robust comparison.
     * 
     * @param actual  The actual score
     * @param expected The expected score
     * @return true if scores match within tolerance
     */
    private boolean compareScore(float actual, float expected) {
        double actualD = (double) actual;
        double expectedD = (double) expected;
        double diff = Math.abs(actualD - expectedD);
        
        // Absolute tolerance check
        if (diff <= tolerance) return true;
        
        // Relative tolerance check for large values
        double max = Math.max(Math.abs(actualD), Math.abs(expectedD));
        if (max > 0 && diff <= relativeTolerance * max) return true;
        
        return false;
    }
    
    /**
     * Compare metadata maps.
     * 
     * @param actual  The actual metadata
     * @param expected The expected metadata
     * @return true if metadata matches
     */
    private boolean compareMetadata(Map<String, Object> actual, Map<String, Object> expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        if (actual.size() != expected.size()) return false;
        
        return actual.entrySet().stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                Object actualVal = entry.getValue();
                Object expectedVal = expected.get(key);
                
                if (actualVal == null && expectedVal == null) return true;
                if (actualVal == null || expectedVal == null) {
                    if (!strictMode) return true;
                    return false;
                }
                
                return compareValues(actualVal, expectedVal);
            });
    }
    
    /**
     * Compare individual values.
     * 
     * @param actual  The actual value
     * @param expected The expected value
     * @return true if values match
     */
    private boolean compareValues(Object actual, Object expected) {
        // Integral metadata (segment IDs, node IDs, counts) is exact by nature and can exceed
        // the 53-bit mantissa of a double, so it is compared without any tolerance.
        if (isIntegral(actual) && isIntegral(expected)) {
            return ((Number) actual).longValue() == ((Number) expected).longValue();
        }

        if (actual instanceof Number a && expected instanceof Number e) {
            // Number.doubleValue() is required here: casting a Number reference to double
            // compiles to a checkcast against java.lang.Double and fails for every other box.
            double actualD = a.doubleValue();
            double expectedD = e.doubleValue();
            double diff = Math.abs(actualD - expectedD);

            if (diff <= tolerance) return true;
            double max = Math.max(Math.abs(actualD), Math.abs(expectedD));
            return max > 0 && diff <= relativeTolerance * max;
        }

        return Objects.equals(actual, expected);
    }

    /**
     * Check whether a value is a boxed integral number that fits a long.
     *
     * @param value The value to test
     * @return true for Byte, Short, Integer and Long
     */
    private static boolean isIntegral(Object value) {
        return value instanceof Long
            || value instanceof Integer
            || value instanceof Short
            || value instanceof Byte;
    }
    
    /**
     * Compare two QueryResult objects for equality.
     * 
     * @param actual  The actual result
     * @param expected The expected result
     * @return true if results match
     */
    public boolean compareResults(QueryResult actual, QueryResult expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        // Compare query ID
        if (!Objects.equals(actual.getQueryId(), expected.getQueryId())) {
            return false;
        }
        
        // Compare top K results
        if (!compareResults(actual.getTopK(), expected.getTopK())) {
            return false;
        }
        
        // Compare partial results
        if (!comparePartialResults(actual.getPartial(), expected.getPartial())) {
            return false;
        }
        
        // Compare metadata
        if (!compareMetadata(actual.getMetadata(), expected.getMetadata())) {
            return false;
        }
        
        // Compare index coverage
        if (!compareIndexCoverage(actual.getIndexCoverage(), expected.getIndexCoverage())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compare partial results sets.
     * 
     * @param actual  The actual partial results
     * @param expected The expected partial results
     * @return true if partial results match
     */
    private boolean comparePartialResults(PartialResult actual, PartialResult expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        // Compare total count
        if (actual.getTotalCount() != expected.getTotalCount()) {
            return false;
        }
        
        // Compare partial IDs
        if (!Objects.equals(actual.getPartialIds(), expected.getPartialIds())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compare index coverage information.
     * 
     * @param actual  The actual coverage
     * @param expected The expected coverage
     * @return true if coverage matches
     */
    private boolean compareIndexCoverage(IndexCoverage actual, IndexCoverage expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        
        return Objects.equals(actual.getTotalSegments(), expected.getTotalSegments()) &&
               Objects.equals(actual.getActiveSegments(), expected.getActiveSegments()) &&
               Objects.equals(actual.getIndexingSegments(), expected.getIndexingSegments()) &&
               Objects.equals(actual.getWarmingSegments(), expected.getWarmingSegments()) &&
               Math.abs(actual.getEstimatedCoverage() - expected.getEstimatedCoverage()) <= tolerance &&
               Objects.equals(actual.isDegraded(), expected.isDegraded());
    }
    
    /**
     * Find differences between two result lists.
     * 
     * @param actual  The actual results
     * @param expected The expected results
     * @return List of differences found
     */
    public List<Difference> findDifferences(List<QueryResultItem> actual, List<QueryResultItem> expected) {
        List<Difference> differences = new ArrayList<>();
        
        if (actual == null && expected == null) return differences;
        if (actual == null || expected == null) {
            differences.add(new Difference("Results not both present", null, null));
            return differences;
        }
        
        int sizeDiff = actual.size() - expected.size();
        if (sizeDiff != 0) {
            differences.add(new Difference(
                String.format("Size mismatch: expected %d, got %d", expected.size(), actual.size()),
                null, null
            ));
        }
        
        // Sort both lists by document ID for comparison
        List<QueryResultItem> sortedActual = new ArrayList<>(actual);
        List<QueryResultItem> sortedExpected = new ArrayList<>(expected);
        sortedActual.sort(Comparator.comparing(QueryResultItem::getDocumentId));
        sortedExpected.sort(Comparator.comparing(QueryResultItem::getDocumentId));
        
        Set<String> expectedIds = sortedExpected.stream()
            .map(QueryResultItem::getDocumentId)
            .collect(Collectors.toSet());
        
        // Find missing items in actual
        for (QueryResultItem expItem : sortedExpected) {
            boolean found = sortedActual.stream()
                .anyMatch(aItem -> matchesItem(aItem, expItem));
            if (!found) {
                differences.add(new Difference(
                    String.format("Missing item: %s", expItem.getDocumentId()),
                    null, expItem
                ));
            }
        }

        // Find extra items in actual
        for (QueryResultItem actItem : sortedActual) {
            boolean found = sortedExpected.stream()
                .anyMatch(expItem -> matchesItem(actItem, expItem));
            if (!found) {
                differences.add(new Difference(
                    String.format("Extra item: %s", actItem.getDocumentId()),
                    actItem, null
                ));
            }
        }

        // Find differing items
        for (int i = 0; i < Math.min(sortedActual.size(), sortedExpected.size()); i++) {
            QueryResultItem actItem = sortedActual.get(i);
            QueryResultItem expItem = sortedExpected.get(i);

            if (!matchesItem(actItem, expItem)) {
                differences.add(new Difference(
                    String.format("Item %s differs", actItem.getDocumentId()),
                    actItem, expItem
                ));
            }
        }
        
        return differences;
    }
    
    /**
     * Check if two items match.
     * 
     * @param actual  The actual item
     * @param expected The expected item
     * @return true if items match
     */
    private boolean matchesItem(QueryResultItem actual, QueryResultItem expected) {
        return Objects.equals(actual.getDocumentId(), expected.getDocumentId()) &&
               compareScore(actual.getScore(), expected.getScore()) &&
               Objects.equals(actual.getContent(), expected.getContent()) &&
               Objects.equals(actual.getSource(), expected.getSource()) &&
               actual.getChunkIndex() == expected.getChunkIndex();
    }
    
    /**
     * A simple difference representation.
     */
    public static class Difference {
        private final String message;
        private final QueryResultItem actual;
        private final QueryResultItem expected;

        public Difference(String message, QueryResultItem actual, QueryResultItem expected) {
            this.message = message;
            this.actual = actual;
            this.expected = expected;
        }

        public String getMessage() {
            return message;
        }

        public QueryResultItem getActual() {
            return actual;
        }

        public QueryResultItem getExpected() {
            return expected;
        }

        @Override
        public String toString() {
            return message;
        }
    }
}
