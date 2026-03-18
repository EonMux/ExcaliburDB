package excalibur.test;

import excalibur.core.schema.QueryResult;
import excalibur.core.schema.QueryResultEntry;

import java.util.*;

/**
 * Deterministic result comparator for golden tests.
 * 
 * Provides type-safe comparison methods with proper epsilon handling
 * for different data types. Ensures stable ordering by document ID.
 */
public class ResultComparator {

    /** Epsilon for double comparison (scores, counts with decimals) */
    public static final double DOUBLE_EPSILON = 1e-6;

    /** Epsilon for string comparison (relative tolerance) */
    public static final double STRING_EPSILON = 1e-12;

    /**
     * Compares two query results for equality.
     * 
     * Uses stable sorting by document ID before comparison.
     * Each field type uses its own appropriate comparison method.
     * 
     * @param actual The actual result to compare
     * @param expected The expected result to compare against
     * @return true if results match, false otherwise
     */
    public static boolean compareResults(QueryResult actual, QueryResult expected) {
        if (actual == null) return false;
        if (expected == null) return false;
        if (actual.queryId() != null && !actual.queryId().equals(expected.queryId())) return false;
        if (actual.query() != null && expected.query() != null && !actual.query().equals(expected.query())) return false;

        List<QueryResultEntry> actualEntries = actual.entries();
        List<QueryResultEntry> expectedEntries = expected.entries();

        if (actualEntries.size() != expectedEntries.size()) return false;

        // Stable sort by document ID before comparison
        Collections.sort(actualEntries, Comparator.comparing(QueryResultEntry::id));
        Collections.sort(expectedEntries, Comparator.comparing(QueryResultEntry::id));

        for (int i = 0; i < actualEntries.size(); i++) {
            if (!compareResultEntries(actualEntries.get(i), expectedEntries.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Compares individual result entries.
     */
    private static boolean compareResultEntries(QueryResultEntry actual, QueryResultEntry expected) {
        if (!actual.id().equals(expected.id())) {
            return false;
        }
        if (!compareDouble(actual.score(), expected.score(), DOUBLE_EPSILON)) {
            return false;
        }
        if (actual.metadata() != null && expected.metadata() != null
                && !actual.metadata().equals(expected.metadata())) {
            return false;
        }
        if ((actual.metadata() == null) != (expected.metadata() == null)) {
            return false;
        }
        return true;
    }

    /**
     * Compares two long values (e.g., document IDs, counts).
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @return true if values are equal, false otherwise
     */
    public static boolean compareLong(Long actual, Long expected) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        return actual.equals(expected);
    }

    /**
     * Compares two long values with epsilon tolerance.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @param epsilon Maximum allowed difference
     * @return true if values are within epsilon of each other
     */
    public static boolean compareLong(Long actual, Long expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        return Math.abs(actual - expected) <= epsilon;
    }

    /**
     * Compares two double values (e.g., scores).
     * Uses default epsilon of 1e-6.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @return true if values are within epsilon of each other
     */
    public static boolean compareDouble(Double actual, Double expected) {
        return compareDouble(actual, expected, DOUBLE_EPSILON);
    }

    /**
     * Compares two double values with epsilon tolerance.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @param epsilon Maximum allowed absolute difference
     * @return true if values are within epsilon of each other
     */
    public static boolean compareDouble(Double actual, Double expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        return Math.abs(actual - expected) <= epsilon;
    }

    /**
     * Compares two double values (for floating-point tolerance).
     * Uses default epsilon of 1e-6.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @return true if values are within epsilon of each other
     */
    public static boolean compareDoubleWithFloatingPointTolerance(Double actual, Double expected) {
        return compareDouble(actual, expected, DOUBLE_EPSILON);
    }

    /**
     * Compares two double values with floating-point tolerance.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @param epsilon Maximum allowed relative difference (must be < 1e-6)
     * @return true if values are within epsilon of each other
     */
    public static boolean compareDoubleWithFloatingPointTolerance(Double actual, Double expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (Math.abs(actual) < 1e-12) {
            return Math.abs(actual - expected) <= epsilon;
        }
        return Math.abs(actual - expected) <= Math.abs(actual) * epsilon;
    }

    /**
     * Compares two string values.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @return true if values are equal
     */
    public static boolean compareString(String actual, String expected) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        return actual.equals(expected);
    }

    /**
     * Compares two string values with relative tolerance.
     * 
     * @param actual The actual value
     * @param expected The expected value
     * @param epsilon Maximum allowed relative difference (must be < 1e-6)
     * @return true if values are within epsilon of each other
     */
    public static boolean compareStringWithTolerance(String actual, String expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        double actualLen = actual.length();
        double expectedLen = expected.length();
        double maxDiff = Math.max(actualLen, expectedLen) * epsilon;
        return Math.abs(actualLen - expectedLen) <= maxDiff;
    }

    /**
     * Compares two lists of results.
     * Uses default epsilon of 1e-6 for double comparisons.
     * 
     * @param actual The actual result list
     * @param expected The expected result list
     * @return true if lists match
     */
    public static boolean compareList(List<QueryResultEntry> actual, List<QueryResultEntry> expected) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (actual.size() != expected.size()) return false;
        
        Collections.sort(actual, Comparator.comparing(QueryResultEntry::id));
        Collections.sort(expected, Comparator.comparing(QueryResultEntry::id));

        for (int i = 0; i < actual.size(); i++) {
            if (!compareResultEntries(actual.get(i), expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two lists with configurable epsilon for double comparisons.
     * 
     * @param actual The actual result list
     * @param expected The expected result list
     * @param epsilon Epsilon for double comparisons
     * @return true if lists match
     */
    public static boolean compareList(List<QueryResultEntry> actual, List<QueryResultEntry> expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (actual.size() != expected.size()) return false;
        
        Collections.sort(actual, Comparator.comparing(QueryResultEntry::id));
        Collections.sort(expected, Comparator.comparing(QueryResultEntry::id));

        for (int i = 0; i < actual.size(); i++) {
            if (!compareResultEntries(actual.get(i), expected.get(i), epsilon)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two lists of generic objects.
     * 
     * @param actual The actual list
     * @param expected The expected list
     * @return true if lists match
     */
    public static boolean compareList(List<?> actual, List<?> expected) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (actual.size() != expected.size()) return false;
        
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two maps of result IDs to scores.
     * 
     * @param actual The actual map
     * @param expected The expected map
     * @return true if maps match
     */
    public static boolean compareMap(Map<String, Double> actual, Map<String, Double> expected) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (actual.size() != expected.size()) return false;
        
        for (Map.Entry<String, Double> entry : actual.entrySet()) {
            String id = entry.getKey();
            Double expectedScore = expected.get(id);
            if (expectedScore == null) return false;
            if (!compareDouble(entry.getValue(), expectedScore, DOUBLE_EPSILON)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two maps with epsilon tolerance for double values.
     * 
     * @param actual The actual map
     * @param expected The expected map
     * @param epsilon Epsilon for double comparisons
     * @return true if maps match
     */
    public static boolean compareMap(Map<String, Double> actual, Map<String, Double> expected, double epsilon) {
        if (actual == null) return expected == null;
        if (expected == null) return false;
        if (actual.size() != expected.size()) return false;
        
        for (Map.Entry<String, Double> entry : actual.entrySet()) {
            String id = entry.getKey();
            Double expectedScore = expected.get(id);
            if (expectedScore == null) return false;
            if (!compareDouble(entry.getValue(), expectedScore, epsilon)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the difference between two lists for debugging.
     */
    public static List<String> listDiff(List<QueryResultEntry> actual, List<QueryResultEntry> expected) {
        List<String> differences = new ArrayList<>();
        
        Collections.sort(actual, Comparator.comparing(QueryResultEntry::id));
        Collections.sort(expected, Comparator.comparing(QueryResultEntry::id));

        Set<String> expectedIds = expected.stream()
                .map(QueryResultEntry::id)
                .collect(Collectors.toSet());

        for (QueryResultEntry entry : actual) {
            if (!expectedIds.contains(entry.id())) {
                differences.add("  Extra entry: id=" + entry.id() + ", score=" + entry.score());
            }
        }

        Set<String> actualIds = actual.stream()
                .map(QueryResultEntry::id)
                .collect(Collectors.toSet());

        for (QueryResultEntry entry : expected) {
            if (!actualIds.contains(entry.id())) {
                differences.add("  Missing entry: id=" + entry.id() + ", score=" + entry.score());
            }
        }

        return differences;
    }

    /**
     * Computes the difference between two maps for debugging.
     */
    public static Map<String, String> mapDiff(Map<String, Double> actual, Map<String, Double> expected) {
        Map<String, String> differences = new LinkedHashMap<>();
        
        for (Map.Entry<String, Double> entry : actual.entrySet()) {
            String id = entry.getKey();
            Double expectedScore = expected.get(id);
            if (expectedScore == null) {
                differences.put(id, "  Extra entry: score=" + entry.getValue());
            } else if (!compareDouble(entry.getValue(), expectedScore, DOUBLE_EPSILON)) {
                differences.put(id, String.format(
                        "  Score mismatch: actual=%.10f, expected=%.10f (diff=%.10f)",
                        entry.getValue(), expectedScore, Math.abs(entry.getValue() - expectedScore)));
            }
        }

        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            String id = entry.getKey();
            if (!actual.containsKey(id)) {
                differences.put(id, "  Missing entry: score=" + entry.getValue());
            }
        }

        return differences;
    }
}
