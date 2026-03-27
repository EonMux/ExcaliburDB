package excalibur.integration.util;

import io.excalibur.test.FailureCategory;
import io.excalibur.test.FailureCategorizer;
import io.excalibur.test.FailureSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for generating categorized failure reports from integration test results.
 *
 * <p>This class provides a simple way to generate failure reports from
 * test results without using the JUnit extension.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * // In @AfterAll or after test suite completes:
 * List&lt;TestResult&gt; results = new ArrayList&lt;&gt;();
 * results.add(new TestResult("testName", "errorMessage"));
 *
 * FailureReportGenerator.generateReport(results);
 * </pre>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class FailureReportGenerator {

    /**
     * Represents a single test result for categorization.
     */
    public record TestResult(String testName, String errorMessage) {}

    private FailureReportGenerator() {
        // Utility class - no instantiation
    }

    /**
     * Categorize test results and generate a failure report.
     *
     * @param results List of test results to categorize
     * @return A formatted failure report string
     */
    public static String generateReport(List<TestResult> results) {
        List<FailureCategorizer.TestFailure> failures = new ArrayList<>();

        for (TestResult result : results) {
            failures.add(new FailureCategorizer.TestFailure(
                result.testName(),
                result.errorMessage()
            ));
        }

        FailureCategorizer.FailureSummary summary = FailureCategorizer.categorizeAll(failures);

        return summary.getReport();
    }

    /**
     * Categorize test results and return the summary object.
     *
     * @param results List of test results to categorize
     * @return A FailureSummary with categorized failures
     */
    public static FailureCategorizer.FailureSummary categorizeResults(List<TestResult> results) {
        List<FailureCategorizer.TestFailure> failures = new ArrayList<>();

        for (TestResult result : results) {
            failures.add(new FailureCategorizer.TestFailure(
                result.testName(),
                result.errorMessage()
            ));
        }

        return FailureCategorizer.categorizeAll(failures);
    }
    /**
     * Check if any failures are CRITICAL severity.
     *
     * @param results List of test results to check
     * @return true if any CRITICAL failures exist
     */
    public static boolean hasCriticalFailures(List<TestResult> results) {
        return categorizeResults(results).hasCriticalFailures();
    }
    /**
     * Check if any failures exist at any severity.
     *
     * @param results List of test results to check
     * @return true if any failures exist
     */
    public static boolean hasFailures(List<TestResult> results) {
        return categorizeResults(results).hasFailures();
    }
    /**
     * Get the count of failures at each severity level.
     *
     * @param results List of test results
     * @return An array with [criticalCount, majorCount, minorCount]
     */
    public static int[] getFailureCounts(List<TestResult> results) {
        FailureCategorizer.FailureSummary summary = categorizeResults(results);
        return new int[] {
            summary.getCritical().size(),
            summary.getMajor().size(),
            summary.getMinor().size()
        };
    }
    /**
     * Get detailed failure information for a specific test.
     *
     * @param results List of test results to search
     * @param testName The test name to find
     * @return The FailureCategory, or null if not found
     */
    public static FailureCategory getFailureFor(List<TestResult> results, String testName) {
        for (TestResult result : results) {
            if (result.testName().equals(testName)) {
                return FailureCategorizer.categorize(result.testName(), result.errorMessage());
            }
        }
        return null;
    }
}
