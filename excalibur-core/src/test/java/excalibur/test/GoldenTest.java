package excalibur.test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for golden test framework.
 * Provides thread-safe comparison and structured error reporting.
 */
public class GoldenTest {

    /** Default epsilon for double comparison (matches QueryResult.compare default) */
    public static final double DEFAULT_EPSILON = 1e-6;

    /** Global comparison state - thread-safe */
    private final ThreadLocal<AtomicInteger> currentComparisonCount;
    
    /** Thread-local comparison results for current test */
    private final ThreadLocal<AtomicBoolean> currentTestPassed;
    
    /** Report for current test */
    private final VerificationReport report;
    
    private final double epsilon;
    
    public GoldenTest(double epsilon) {
        this.epsilon = epsilon;
        this.currentComparisonCount = ThreadLocal.withInitial(AtomicInteger::new);
        this.currentTestPassed = ThreadLocal.withInitial(AtomicBoolean::new);
        this.report = new VerificationReport();
    }

    /**
     * Thread-safe main assertion method for comparing query results.
     * 
     * @param actual Results from execution
     * @param expected Expected results (must be sorted by document ID)
     * @param epsilon Tolerance for numeric comparisons (default: 1e-6)
     * @throws AssertionError if results differ
     */
    public void assertResults(QueryResult actual, QueryResult expected, double epsilon) {
        this.epsilon = epsilon > 0 ? epsilon : DEFAULT_EPSILON;
        this.currentTestPassed.set(true);
        this.report.addTest("assertResults", true, null, 0);

        try {
            compareResults(actual, expected);
            currentComparisonCount.get().incrementAndGet();
        } catch (AssertionError e) {
            currentTestPassed.set(false);
            currentComparisonCount.get().incrementAndGet();
            report.addTest("assertResults", false, e.getMessage(), 0);
            throw e;
        }
    }

    /**
     * Thread-safe main assertion method for comparing query results.
     * Uses default epsilon of 1e-6.
     * 
     * @param actual Results from execution
     * @param expected Expected results (must be sorted by document ID)
     * @throws AssertionError if results differ
     */
    public void assertResults(QueryResult actual, QueryResult expected) {
        assertResults(actual, expected, DEFAULT_EPSILON);
    }

    /**
     * Asserts that results match, with stable ordering by document ID.
     * Comparison is deterministic and thread-safe.
     */
    private void compareResults(QueryResult actual, QueryResult expected) {
        if (actual == null) {
            throw new AssertionError("Actual result is null");
        }
        if (expected == null) {
            throw new AssertionError("Expected result is null");
        }

        // Verify sizes match
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format("Size mismatch: actual=%d, expected=%d",
                    actual.size(), expected.size()));
        }

        // Stable sort by document ID for deterministic comparison
        List<QueryResultEntry> actualEntries = actual.entries();
        List<QueryResultEntry> expectedEntries = expected.entries();

        Collections.sort(actualEntries, Comparator.comparing(QueryResultEntry::id));
        Collections.sort(expectedEntries, Comparator.comparing(QueryResultEntry::id));

        // Compare each entry
        for (int i = 0; i < actualEntries.size(); i++) {
            QueryResultEntry actualEntry = actualEntries.get(i);
            QueryResultEntry expectedEntry = expectedEntries.get(i);

            // Compare IDs
            if (!actualEntry.id().equals(expectedEntry.id())) {
                throw new AssertionError(String.format("ID mismatch at index %d: actual='%s', expected='%s'",
                        i, actualEntry.id(), expectedEntry.id()));
            }

            // Compare scores with configurable epsilon
            if (Math.abs(actualEntry.score() - expectedEntry.score()) > epsilon) {
                throw new AssertionError(String.format("Score mismatch at index %d (ID='%s'): actual=%.10f, expected=%.10f (epsilon=%.10f)",
                        i, actualEntry.id(), actualEntry.score(), expectedEntry.score(), epsilon));
            }

            // Compare metadata
            if (actualEntry.metadata() != null && expectedEntry.metadata() != null
                    && !actualEntry.metadata().equals(expectedEntry.metadata())) {
                throw new AssertionError(String.format("Metadata mismatch at index %d (ID='%s'): actual='%s', expected='%s'",
                        i, actualEntry.id(), actualEntry.metadata(), expectedEntry.metadata()));
            }

            // Compare null metadata
            if ((actualEntry.metadata() == null) != (expectedEntry.metadata() == null)) {
                throw new AssertionError(String.format("Metadata null mismatch at index %d (ID='%s')", i, actualEntry.id()));
            }
        }
    }

    /**
     * Returns the current test pass rate.
     */
    public double getPassRate() {
        return report.getPassRate();
    }

    /**
     * Returns the current test false negative rate.
     */
    public double getFalseNegativeRate() {
        return report.getFalseNegativeRate();
    }

    /**
     * Returns the current test false positive rate.
     */
    public double getFalsePositiveRate() {
        return report.getFalsePositiveRate();
    }

    /**
     * Returns a formatted summary of all tests.
     */
    public String getSummary() {
        return report.getSummary();
    }

    /**
     * Returns the total number of comparisons made.
     */
    public int getComparisonCount() {
        return currentComparisonCount.get().get();
    }

    /**
     * Returns the verification report.
     */
    public VerificationReport getReport() {
        return report;
    }

    /**
     * Serializes the current report to JSON.
     */
    public String toJson() {
        return report.toJson();
    }

    /**
     * Asserts that results match, using stable ordering by ID and default epsilon.
     * Returns true if results match, false otherwise.
     */
    public static boolean compareResults(QueryResult actual, QueryResult expected) {
        GoldenTest test = new GoldenTest(DEFAULT_EPSILON);
        try {
            test.compareResults(actual, expected);
            return true;
        } catch (AssertionError e) {
            test.report.addTest("compareResults", false, e.getMessage(), 0);
            return false;
        }
    }

    /**
     * Asserts that results match, using stable ordering by ID.
     * Returns true if results match, false otherwise.
     */
    public static boolean compareResults(QueryResult actual, QueryResult expected, double epsilon) {
        GoldenTest test = new GoldenTest(epsilon);
        try {
            test.compareResults(actual, expected);
            return true;
        } catch (AssertionError e) {
            test.report.addTest("compareResults", false, e.getMessage(), 0);
            return false;
        }
    }
}
