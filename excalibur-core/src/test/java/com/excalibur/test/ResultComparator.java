package com.excalibur.test;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic result comparator for golden test verification.
 * 
 * This comparator ensures that identical results always produce
 * identical comparison outcomes, regardless of internal ordering.
 * 
 * Features:
 * - Stable ordering: Results sorted by document ID before comparison
 * - Type-safe comparison: String equality, numeric tolerance, collection ordering
 * - No data leakage: Compare only required fields, not internal state
 */
public class ResultComparator {
    
    static final double DEFAULT_EPSILON = 1e-6;
    static double epsilon = DEFAULT_EPSILON;
    private static final Comparator<Result> DEFAULT_RESULT_COMPARATOR = 
        Result::compareToDocumentId;
    
    public ResultComparator() {
        ResultComparator.epsilon = DEFAULT_EPSILON;
    }
    
    public ResultComparator(double epsilon) {
        ResultComparator.epsilon = epsilon;
    }
    
    /**
     * Compare two result lists deterministically.
     * 
     * @param expected Expected results (sorted by document ID)
     * @param actual Actual results (sorted by document ID)
     * @param context Test context for formatting
     * @return true if results are equal, false otherwise
     */
    public boolean compareResults(List<Result> expected, List<Result> actual, TestContext context) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.size() != actual.size()) {
            return false;
        }
        
        for (int i = 0; i < expected.size(); i++) {
            Result e = expected.get(i);
            Result a = actual.get(i);
            
            if (!compareResult(e, a, context)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Compare a single result deterministically.
     */
    private boolean compareResult(Result expected, Result actual, TestContext context) {
        // Compare all fields
        if (!compareField(expected.getDocumentId(), actual.getDocumentId(), context)) {
            return false;
        }
        if (!compareField(expected.getScore(), actual.getScore(), context)) {
            return false;
        }
        if (!compareField(expected.getRelevance(), actual.getRelevance(), context)) {
            return false;
        }
        if (!compareField(expected.getTags(), actual.getTags(), context)) {
            return false;
        }
        if (!compareField(expected.getSimilarity(), actual.getSimilarity(), context)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compare a field deterministically.
     */
    private boolean compareField(Object expected, Object actual, TestContext context) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        
        if (expected.getClass() != actual.getClass()) {
            return false;
        }
        
        if (expected instanceof String) {
            return compareString((String) expected, (String) actual);
        } else if (expected instanceof Number) {
            return compareNumber((Number) expected, (Number) actual);
        } else if (expected instanceof List) {
            return compareCollection((List<?>) expected, (List<?>) actual);
        } else if (expected instanceof float[]) {
            return compareArray((float[]) expected, (float[]) actual);
        } else {
            return expected.equals(actual);
        }
    }
    
    /**
     * Compare string equality.
     */
    private boolean compareString(String expected, String actual) {
        return expected.equals(actual);
    }
    
    /**
     * Compare numeric values with tolerance.
     */
    private boolean compareNumber(Number expected, Number actual) {
        double e = expected.doubleValue();
        double a = actual.doubleValue();
        return Math.abs(e - a) <= epsilon;
    }
    
    /**
     * Compare collections deterministically (must already be sorted).
     */
    private boolean compareCollection(List<?> expected, List<?> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Compare float arrays element-wise.
     */
    static boolean compareArray(float[] expected, float[] actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!equalsDouble(expected[i], actual[i])) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Compare two floating-point numbers with epsilon tolerance.
     */
    static boolean equalsDouble(double a, double b) {
        if (Double.isInfinite(a) && Double.isInfinite(b)) {
            return Double.compare(a, b) == 0;
        }
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            return false;
        }
        return Math.abs(a - b) <= ResultComparator.epsilon;
    }
    
    /**
     * Compare two result lists and generate a detailed diff report.
     * 
     * @param expected Expected results
     * @param actual Actual results
     * @param context Test context
     * @return Diff report as string
     */
    public String generateDiff(List<Result> expected, List<Result> actual, TestContext context) {
        StringBuilder sb = new StringBuilder();
        
        // Check count difference
        int expectedCount = expected != null ? expected.size() : 0;
        int actualCount = actual != null ? actual.size() : 0;
        if (expectedCount != actualCount) {
            sb.append("Result count mismatch: ");
            sb.append("expected=").append(expectedCount)
              .append(" | actual=").append(actualCount);
            return sb.toString();
        }
        
        // Find mismatches
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            Result e = expected.get(i);
            Result a = actual.get(i);
            
            // Check document ID
            if (!e.getDocumentId().equals(a.getDocumentId())) {
                mismatches.add(String.format("Position %d: documentId '%s' != '%s'",
                    i, escapeJson(e.getDocumentId()), escapeJson(a.getDocumentId())));
            }
            
            // Check relevance
            if (!equalsDouble(parseDouble(e.getRelevance()), parseDouble(a.getRelevance()))) {
                mismatches.add(String.format("Position %d: relevance %.6f != %.6f",
                    i, e.getRelevance(), a.getRelevance()));
            }
            
            // Check tags
            if (!tagsEqual(e.getTags(), a.getTags())) {
                mismatches.add(String.format("Position %d: tags differ",
                    i));
            }
            
            // Check similarity
            if (!compareArray(e.getSimilarity(), a.getSimilarity())) {
                mismatches.add(String.format("Position %d: similarity differs",
                    i));
            }
        }
        
        if (mismatches.isEmpty()) {
            return "Results match completely.";
        }
        
        sb.append("\n=== Mismatches ===\n");
        for (String m : mismatches) {
            sb.append("- ").append(m).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Check if two result lists are equal.
     */
    public static boolean resultsEqual(List<Result> expected, List<Result> actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!resultsEqual(expected.get(i), actual.get(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if two results are equal.
     */
    public static boolean resultsEqual(Result expected, Result actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        return expected.getDocumentId().equals(actual.getDocumentId())
            && equalsDouble(expected.getRelevance(), actual.getRelevance())
            && tagsEqual(expected.getTags(), actual.getTags())
            && compareArray(expected.getSimilarity(), actual.getSimilarity());
    }
    
    /**
     * Escape string for display.
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n").replace("\r", "\r").replace("\t", "\t");
    }
    
    /**
     * Parse a double string value.
     */
    public static double parseDouble(String value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
    
    /**
     * Check if two tag sets have the same intersection count.
     */
    public static boolean tagsIntersectionEqual(List<String> tags1, List<String> tags2) {
        if (tags1 == null && tags2 == null) {
            return true;
        }
        if (tags1 == null || tags2 == null) {
            return false;
        }
        
        Set<String> set1 = new HashSet<>(tags1);
        Set<String> set2 = new HashSet<>(tags2);
        
        return set1.size() == set2.size();
    }
    
    /**
     * Check if two tag sets are equal (same tags in any order).
     */
    public static boolean tagsEqual(List<String> tags1, List<String> tags2) {
        if (tags1 == null && tags2 == null) {
            return true;
        }
        if (tags1 == null || tags2 == null) {
            return false;
        }
        
        return tags1.equals(tags2);
    }
    
    /**
     * Get epsilon value.
     */
    public double getEpsilon() {
        return epsilon;
    }
    
    /**
     * Get default epsilon.
     */
    public static double getDefaultEpsilon() {
        return DEFAULT_EPSILON;
    }
    
    /**
     * Sort results by document ID for deterministic comparison.
     */
    public static <R extends Result> List<R> sortResults(List<R> results, double epsilon) {
        return results.stream()
            .sorted(DEFAULT_RESULT_COMPARATOR)
            .collect(Collectors.toList());
    }
    
    /**
     * Sort results by document ID for deterministic comparison.
     */
    public static <R extends Result> List<R> sortResults(List<R> results) {
        return sortResults(results, DEFAULT_EPSILON);
    }
    
    /**
     * Compare two document IDs.
     */
    public static int compareDocumentIds(String id1, String id2) {
        try {
            long v1 = Long.parseLong(id1);
            long v2 = Long.parseLong(id2);
            return Long.compare(v1, v2);
        } catch (NumberFormatException e) {
            return id1.compareTo(id2);
        }
    }
    
    /**
     * Verify that a computed relevance score matches expected score.
     */
    public static boolean verifyRelevance(
            List<String> queryTags,
            List<String> docTags,
            double expectedScore,
            double epsilon) {
        double actualScore = computeRelevance(queryTags, docTags);
        return equalsDouble(actualScore, expectedScore, epsilon);
    }
    
    /**
     * Compute relevance: intersection(tag_count) / max(tag_count).
     */
    public static double computeRelevance(List<String> queryTags, List<String> docTags) {
        if (queryTags == null || queryTags.isEmpty()) {
            return 0.0;
        }
        if (docTags == null || docTags.isEmpty()) {
            return 0.0;
        }
        
        Set<String> querySet = new HashSet<>(queryTags);
        int intersection = 0;
        for (String tag : docTags) {
            if (querySet.contains(tag)) {
                intersection++;
            }
        }
        
        return (double) intersection / (double) queryTags.size();
    }
}
