/*
 * excalibur-test - Test Failure Reporting Extension
 *
 * Package: io.excalibur.test
 *
 * Thread Safety:
 *   - FailureReportingExtension: Thread-safe via ConcurrentHashMap and atomic operations
 */

package io.excalibur.test;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JUnit 5 extension that automatically categorizes test failures and generates reports.
 *
 * <h2>Usage</h2>
 * <p>Add to your test class:</p>
 * <pre>
 * {@code @RegisterExtension
 * static FailureReportingExtension failureReporter = FailureReportingExtension.create();}
 * </pre>
 *
 * <p>Or use as a global extension in {@code junit-platform.properties}:</p>
 * <pre>
 * junit.jupiter.extensions.autodetection.enabled=true
 * </pre>
 *
 * <h2>Report Output</h2>
 * <p>After all tests complete, generates:</p>
 * <ul>
 *   <li>Console report with categorized failures</li>
 *   <li>JSON report file (optional) with detailed failure information</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This extension is thread-safe and can be used with parallel test execution.</p>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class FailureReportingExtension
    implements BeforeAllCallback, AfterAllCallback, TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(FailureReportingExtension.class);

    private static final String FAILURES_KEY = "FAILURES";
    private static final String TEST_CLASS_KEY = "TEST_CLASS";

    private final Path reportDirectory;
    private final boolean writeJsonReport;
    private final boolean consoleReport;

    // Track all failures across test classes (for global reporting)
    private static final Map<String, List<FailureCategory>> globalFailures = new ConcurrentHashMap<>();
    private static final AtomicInteger totalTests = new AtomicInteger(0);
    private static final AtomicInteger totalFailures = new AtomicInteger(0);

    private FailureReportingExtension(Builder builder) {
        this.reportDirectory = builder.reportDirectory;
        this.writeJsonReport = builder.writeJsonReport;
        this.consoleReport = builder.consoleReport;
    }

    /**
     * Create a new FailureReportingExtension with default settings.
     *
     * @return A new extension instance
     */
    public static FailureReportingExtension create() {
        return builder().build();
    }

    /**
     * Create a new builder for configuring the extension.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // Store the test class name for reporting
        context.getStore(NAMESPACE).put(TEST_CLASS_KEY, context.getRequiredTestClass().getSimpleName());
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        // Increment failure counter
        totalFailures.incrementAndGet();

        // Extract test name
        String testName = context.getDisplayName();
        String className = context.getRequiredTestClass().getSimpleName();
        String fullName = className + "." + testName;

        // Extract error message
        String errorMessage = extractErrorMessage(throwable);

        // Categorize the failure
        FailureCategory category = FailureCategorizer.categorize(fullName, errorMessage);

        // Store in context for this test class
        @SuppressWarnings("unchecked")
        List<FailureCategory> failures = context.getStore(NAMESPACE)
            .getOrComputeIfAbsent(FAILURES_KEY, k -> new ArrayList<FailureCategory>(), List.class);

        synchronized (failures) {
            ((List<FailureCategory>) failures).add(category);
        }

        // Also store in global failures map
        globalFailures.computeIfAbsent(className, k -> new ArrayList<>()).add(category);

        // Re-throw the exception so JUnit handles it properly
        throw throwable;
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // Get failures for this test class
        @SuppressWarnings("unchecked")
        List<FailureCategory> failures = context.getStore(NAMESPACE)
            .get(FAILURES_KEY, List.class);

        if (failures == null || failures.isEmpty()) {
            return;
        }

        // Generate summary for this test class
        FailureCategorizer.FailureSummary summary = buildSummary(failures);

        // Console report
        if (consoleReport) {
            System.out.println("\n" + generateConsoleReport(context.getRequiredTestClass().getSimpleName(), summary));
        }

        // JSON report
        if (writeJsonReport && reportDirectory != null) {
            writeJsonReport(context.getRequiredTestClass().getSimpleName(), failures, summary);
        }
    }

    /**
     * Get a global summary of all failures across all test classes.
     *
     * @return A combined failure summary
     */
    public static FailureCategorizer.FailureSummary getGlobalSummary() {
        List<FailureCategorizer.TestFailure> allFailures = new ArrayList<>();
        for (Map.Entry<String, List<FailureCategory>> entry : globalFailures.entrySet()) {
            for (FailureCategory cat : entry.getValue()) {
                allFailures.add(new FailureCategorizer.TestFailure(cat.getTestName(), cat.getErrorMessage()));
            }
        }
        return FailureCategorizer.categorizeAll(allFailures);
    }

    /**
     * Print a global failure report to the console.
     */
    public static void printGlobalReport() {
        FailureCategorizer.FailureSummary summary = getGlobalSummary();
        if (summary.hasFailures()) {
            System.out.println("\n" + "=" .repeat(80));
            System.out.println("GLOBAL FAILURE REPORT - ALL TEST CLASSES");
            System.out.println("=".repeat(80));
            System.out.println(summary.getReport());
            System.out.println("Total tests run: " + totalTests.get());
            System.out.println("Total failures: " + totalFailures.get());
        }
    }

    // ==================== Private Helpers ====================

    private String extractErrorMessage(Throwable throwable) {
        StringBuilder sb = new StringBuilder();

        // Main message
        if (throwable.getMessage() != null) {
            sb.append(throwable.getMessage());
        }

        // Include assertion error details if present
        if (throwable instanceof AssertionError) {
            sb.append(" [AssertionError]");
        }

        // Include cause if different
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            sb.append(" | Caused by: ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }

        return sb.toString();
    }

    private FailureCategorizer.FailureSummary buildSummary(List<FailureCategory> failures) {
        List<FailureCategorizer.TestFailure> testFailures = new ArrayList<>();
        for (FailureCategory cat : failures) {
            testFailures.add(new FailureCategorizer.TestFailure(cat.getTestName(), cat.getErrorMessage()));
        }
        return FailureCategorizer.categorizeAll(testFailures);
    }

    private String generateConsoleReport(String className, FailureCategorizer.FailureSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(80)).append("\n");
        sb.append("FAILURE REPORT: ").append(className).append("\n");
        sb.append("=".repeat(80)).append("\n\n");

        if (summary.hasCriticalFailures()) {
            sb.append("CRITICAL FAILURES (Must fix immediately):\n");
            sb.append("-".repeat(40)).append("\n");
            for (FailureCategory cat : summary.getCritical()) {
                sb.append(cat.getSummary()).append("\n\n");
            }
        }

        if (!summary.getMajor().isEmpty()) {
            sb.append("MAJOR FAILURES (Should fix before release):\n");
            sb.append("-".repeat(40)).append("\n");
            for (FailureCategory cat : summary.getMajor()) {
                sb.append(cat.getSummary()).append("\n\n");
            }
        }

        if (!summary.getMinor().isEmpty()) {
            sb.append("MINOR FAILURES (Fix when convenient):\n");
            sb.append("-".repeat(40)).append("\n");
            for (FailureCategory cat : summary.getMinor()) {
                sb.append(cat.getSummary()).append("\n\n");
            }
        }

        sb.append("=".repeat(80)).append("\n");
        sb.append("Summary: ").append(summary.getTotalCount()).append(" total failures\n");
        sb.append("  CRITICAL: ").append(summary.getCritical().size()).append("\n");
        sb.append("  MAJOR: ").append(summary.getMajor().size()).append("\n");
        sb.append("  MINOR: ").append(summary.getMinor().size()).append("\n");
        sb.append("=".repeat(80)).append("\n");

        return sb.toString();
    }

    private void writeJsonReport(String className, List<FailureCategory> failures,
                                 FailureCategorizer.FailureSummary summary) throws IOException {
        Files.createDirectories(reportDirectory);

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String filename = "failure-report-" + className + "-" + timestamp.replace(":", "-") + ".json";
        Path reportPath = reportDirectory.resolve(filename);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"testClass\": \"").append(escapeJson(className)).append("\",\n");
        json.append("  \"timestamp\": \"").append(escapeJson(timestamp)).append("\",\n");
        json.append("  \"summary\": {\n");
        json.append("    \"total\": ").append(summary.getTotalCount()).append(",\n");
        json.append("    \"critical\": ").append(summary.getCritical().size()).append(",\n");
        json.append("    \"major\": ").append(summary.getMajor().size()).append(",\n");
        json.append("    \"minor\": ").append(summary.getMinor().size()).append("\n");
        json.append("  },\n");
        json.append("  \"failures\": [\n");

        boolean first = true;
        for (FailureCategory cat : failures) {
            if (!first) json.append(",\n");
            first = false;

            json.append("    {\n");
            json.append("      \"testName\": \"").append(escapeJson(cat.getTestName())).append("\",\n");
            json.append("      \"severity\": \"").append(escapeJson(cat.getSeverity().getDisplayName())).append("\",\n");
            json.append("      \"category\": \"").append(escapeJson(cat.getCategory())).append("\",\n");
            json.append("      \"errorMessage\": \"").append(escapeJson(cat.getErrorMessage())).append("\",\n");

            if (cat.getDomainCorrectnessViolation() != null) {
                json.append("      \"domainViolation\": \"").append(escapeJson(cat.getDomainCorrectnessViolation())).append("\",\n");
            }

            json.append("      \"remediation\": [\n");
            List<String> steps = cat.getRemediationSteps();
            for (int i = 0; i < steps.size(); i++) {
                json.append("        \"").append(escapeJson(steps.get(i))).append("\"");
                if (i < steps.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("      ]\n");
            json.append("    }");
        }

        json.append("\n  ]\n");
        json.append("}\n");

        Files.writeString(reportPath, json.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("Failure report written to: " + reportPath);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // ==================== Builder ====================

    /**
     * Builder for configuring FailureReportingExtension.
     */
    public static final class Builder {
        private Path reportDirectory;
        private boolean writeJsonReport = false;
        private boolean consoleReport = true;

        /**
         * Set the directory for JSON report files.
         *
         * @param directory The directory path
         * @return This builder
         */
        public Builder reportDirectory(Path directory) {
            this.reportDirectory = directory;
            return this;
        }

        /**
         * Enable or disable JSON report generation.
         *
         * @param enabled true to enable JSON reports
         * @return This builder
         */
        public Builder writeJsonReport(boolean enabled) {
            this.writeJsonReport = enabled;
            return this;
        }

        /**
         * Enable or disable console report output.
         *
         * @param enabled true to enable console reports
         * @return This builder
         */
        public Builder consoleReport(boolean enabled) {
            this.consoleReport = enabled;
            return this;
        }

        /**
         * Build the FailureReportingExtension instance.
         *
         * @return A new FailureReportingExtension
         */
        public FailureReportingExtension build() {
            return new FailureReportingExtension(this);
        }
    }
}
