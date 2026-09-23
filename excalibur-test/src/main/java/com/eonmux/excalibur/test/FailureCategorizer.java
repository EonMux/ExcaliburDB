/*
 * excalibur-test - Test Failure Categorization
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - FailureCategorizer: Thread-safe, stateless categorization operations
 *
 * Domain Correctness:
 *   This categorizer understands the specific failure patterns of ExcaliburDB
 *   integration tests and maps them to appropriate severity levels based on
 *   their impact on system correctness, reliability, and cleanup requirements.
 */

package com.eonmux.excalibur.test;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Categorizes test failures by severity based on error patterns and test context.
 *
 * <h2>Failure Categories</h2>
 *
 * <h3>CRITICAL (Correctness)</h3>
 * <p>Failures that violate core system correctness guarantees:</p>
 * <ul>
 *   <li>Data loss: Ingested documents not appearing in query results</li>
 *   <li>Epoch isolation: Torn reads, partial documents visible</li>
 *   <li>Document atomicity: Partial documents in results</li>
 *   <li>Deletion correctness: Deleted keys still found in filters</li>
 *   <li>Query correctness: Wrong documents returned</li>
 *   <li>LLM fallback: No results when LLM unavailable (must fallback to vector-only)</li>
 *   <li>Empty results on populated index</li>
 *   <li>Results not sorted by score/distance</li>
 *   <li>Duplicate entries in results</li>
 *   <li>Coverage values outside valid range [0,1]</li>
 * </ul>
 *
 * <h3>MAJOR (Reliability)</h3>
 * <p>Failures that affect stability but not correctness:</p>
 * <ul>
 *   <li>Circuit breaker not transitioning correctly</li>
 *   <li>Timeout violations</li>
 *   <li>Concurrent modification exceptions</li>
 *   <li>Memory/resource leaks</li>
 *   <li>Performance issues</li>
 *   <li>Coverage metadata inaccurate</li>
 *   <li>False positive rate significantly above target</li>
 * </ul>
 *
 * <h3>MINOR (Cleanup)</h3>
 * <p>Non-blocking failures:</p>
 * <ul>
 *   <li>False positive rate slightly above target</li>
 *   <li>Test infrastructure issues</li>
 *   <li>Non-critical assertion failures</li>
 *   <li>Cosmetic output issues</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class FailureCategorizer {

    // ==================== CRITICAL Patterns ====================
    // These patterns indicate correctness violations

    private static final Pattern[] CRITICAL_PATTERNS = {
        // Data loss / missing results
        Pattern.compile("ingested.*not.*found", Pattern.CASE_INSENSITIVE),
        Pattern.compile("results.*empty.*but.*expected", Pattern.CASE_INSENSITIVE),
        Pattern.compile("should.*have.*results.*but.*got.*0", Pattern.CASE_INSENSITIVE),
        Pattern.compile("no.*results.*after.*ingest", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expected.*results.*but.*none.*returned", Pattern.CASE_INSENSITIVE),

        // Epoch isolation violations
        Pattern.compile("torn.*read", Pattern.CASE_INSENSITIVE),
        Pattern.compile("epoch.*isolation", Pattern.CASE_INSENSITIVE),
        Pattern.compile("partial.*document.*visible", Pattern.CASE_INSENSITIVE),
        Pattern.compile("document.*atomicity", Pattern.CASE_INSENSITIVE),

        // Deletion correctness
        Pattern.compile("deleted.*still.*found", Pattern.CASE_INSENSITIVE),
        Pattern.compile("false.*positive.*deleted", Pattern.CASE_INSENSITIVE),
        Pattern.compile("contains.*should.*be.*false.*deleted", Pattern.CASE_INSENSITIVE),

        // Query correctness
        Pattern.compile("wrong.*document.*returned", Pattern.CASE_INSENSITIVE),
        Pattern.compile("incorrect.*result", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expected.*doc.*but.*got", Pattern.CASE_INSENSITIVE),
        Pattern.compile("document.*id.*mismatch", Pattern.CASE_INSENSITIVE),

        // Coverage correctness
        Pattern.compile("coverage.*must.*be.*0\\.0", Pattern.CASE_INSENSITIVE),
        Pattern.compile("coverage.*outside.*range", Pattern.CASE_INSENSITIVE),
        Pattern.compile("degraded.*must.*be.*true", Pattern.CASE_INSENSITIVE),

        // Sorting/ordering
        Pattern.compile("results.*not.*sorted", Pattern.CASE_INSENSITIVE),
        Pattern.compile("score.*ascending.*order", Pattern.CASE_INSENSITIVE),

        // Duplicates
        Pattern.compile("duplicate.*entries", Pattern.CASE_INSENSITIVE),
        Pattern.compile("seenIds.*doesNotContain", Pattern.CASE_INSENSITIVE),

        // LLM fallback
        Pattern.compile("llm.*fallback.*failed", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vector-only.*fallback.*no.*results", Pattern.CASE_INSENSITIVE)
    };

    // Test names that are always CRITICAL if they fail
    private static final Pattern[] CRITICAL_TEST_NAMES = {
        Pattern.compile("ingested.*appear.*query", Pattern.CASE_INSENSITIVE),
        Pattern.compile("no.*torn.*read", Pattern.CASE_INSENSITIVE),
        Pattern.compile("deleted.*not.*found", Pattern.CASE_INSENSITIVE),
        Pattern.compile("document.*atomicity", Pattern.CASE_INSENSITIVE),
        Pattern.compile("correctness", Pattern.CASE_INSENSITIVE),
        Pattern.compile("epoch.*isolation", Pattern.CASE_INSENSITIVE),
        Pattern.compile("content.*retrieved", Pattern.CASE_INSENSITIVE),
        Pattern.compile("empty.*index.*degraded", Pattern.CASE_INSENSITIVE)
    };

    // ==================== MAJOR Patterns ====================
    // These patterns indicate reliability issues

    private static final Pattern[] MAJOR_PATTERNS = {
        // Circuit breaker
        Pattern.compile("circuit.*breaker.*not.*open", Pattern.CASE_INSENSITIVE),
        Pattern.compile("circuit.*should.*be.*OPEN", Pattern.CASE_INSENSITIVE),
        Pattern.compile("circuit.*breaker.*state", Pattern.CASE_INSENSITIVE),

        // Timeouts
        Pattern.compile("timeout.*exceeded", Pattern.CASE_INSENSITIVE),
        Pattern.compile("query.*took.*too.*long", Pattern.CASE_INSENSITIVE),
        Pattern.compile("should.*complete.*within", Pattern.CASE_INSENSITIVE),

        // Concurrency
        Pattern.compile("ConcurrentModification", Pattern.CASE_INSENSITIVE),
        Pattern.compile("race.*condition", Pattern.CASE_INSENSITIVE),

        // Resource issues
        Pattern.compile("OutOfMemory", Pattern.CASE_INSENSITIVE),
        Pattern.compile("resource.*leak", Pattern.CASE_INSENSITIVE),
        Pattern.compile("too.*many.*open.*files", Pattern.CASE_INSENSITIVE),

        // Performance
        Pattern.compile("throughput.*below.*target", Pattern.CASE_INSENSITIVE),
        Pattern.compile("latency.*exceeded", Pattern.CASE_INSENSITIVE),
        Pattern.compile("performance.*degradation", Pattern.CASE_INSENSITIVE),

        // Coverage metadata
        Pattern.compile("coverage.*inaccurate", Pattern.CASE_INSENSITIVE),
        Pattern.compile("coverage.*should.*reflect", Pattern.CASE_INSENSITIVE),

        // False positive rate significantly above target
        Pattern.compile("false.*positive.*rate.*0\\.0[5-9]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("fp.*rate.*above.*5%", Pattern.CASE_INSENSITIVE),

        // Exceptions during concurrent operations
        Pattern.compile("exception.*during.*concurrent", Pattern.CASE_INSENSITIVE),
        Pattern.compile("exceptions.*should.*be.*0", Pattern.CASE_INSENSITIVE)
    };

    // Test names that indicate MAJOR issues if they fail
    private static final Pattern[] MAJOR_TEST_NAMES = {
        Pattern.compile("circuit.*breaker", Pattern.CASE_INSENSITIVE),
        Pattern.compile("concurrent.*ingestion.*query", Pattern.CASE_INSENSITIVE),
        Pattern.compile("timeout", Pattern.CASE_INSENSITIVE),
        Pattern.compile("performance", Pattern.CASE_INSENSITIVE),
        Pattern.compile("coverage.*accurate", Pattern.CASE_INSENSITIVE),
        Pattern.compile("progressive.*results", Pattern.CASE_INSENSITIVE)
    };

    // ==================== MINOR Patterns ====================
    // These patterns indicate cleanup/non-blocking issues

    private static final Pattern[] MINOR_PATTERNS = {
        // Slightly above target
        Pattern.compile("false.*positive.*slightly.*above", Pattern.CASE_INSENSITIVE),
        Pattern.compile("fp.*rate.*0\\.0[0-2]", Pattern.CASE_INSENSITIVE),

        // Infrastructure
        Pattern.compile("temp.*file.*cleanup", Pattern.CASE_INSENSITIVE),
        Pattern.compile("test.*infrastructure", Pattern.CASE_INSENSITIVE),

        // Non-critical
        Pattern.compile("logging.*format", Pattern.CASE_INSENSITIVE),
        Pattern.compile("metric.*name", Pattern.CASE_INSENSITIVE),
        Pattern.compile("cosmetic", Pattern.CASE_INSENSITIVE),

        // Order doesn't matter
        Pattern.compile("order.*may.*vary", Pattern.CASE_INSENSITIVE),
        Pattern.compile("different.*order", Pattern.CASE_INSENSITIVE)
    };

    // Private constructor - use static methods
    private FailureCategorizer() {}

    /**
     * Categorize a test failure based on test name and error message.
     *
     * @param testName The name of the failed test
     * @param errorMessage The error message from the test failure
     * @return A FailureCategory with severity and remediation guidance
     */
    public static FailureCategory categorize(String testName, String errorMessage) {
        Objects.requireNonNull(testName, "testName is required");
        Objects.requireNonNull(errorMessage, "errorMessage is required (can be empty string)");

        // First check test name patterns (higher priority)
        for (Pattern pattern : CRITICAL_TEST_NAMES) {
            if (pattern.matcher(testName).find()) {
                return buildCriticalCategory(testName, errorMessage, "Critical test failure");
            }
        }

        for (Pattern pattern : MAJOR_TEST_NAMES) {
            if (pattern.matcher(testName).find()) {
                return buildMajorCategory(testName, errorMessage, "Reliability test failure");
            }
        }

        // Then check error message patterns
        for (Pattern pattern : CRITICAL_PATTERNS) {
            if (pattern.matcher(errorMessage).find()) {
                return buildCriticalCategory(testName, errorMessage, "Pattern match: " + pattern.pattern());
            }
        }

        for (Pattern pattern : MAJOR_PATTERNS) {
            if (pattern.matcher(errorMessage).find()) {
                return buildMajorCategory(testName, errorMessage, "Pattern match: " + pattern.pattern());
            }
        }

        for (Pattern pattern : MINOR_PATTERNS) {
            if (pattern.matcher(errorMessage).find()) {
                return buildMinorCategory(testName, errorMessage, "Pattern match: " + pattern.pattern());
            }
        }

        // Default to MAJOR for unrecognized failures (safer default)
        return buildUnknownCategory(testName, errorMessage);
    }

    /**
     * Categorize a test failure based on test name, error message, and assertion context.
     *
     * @param testName The name of the failed test
     * @param errorMessage The error message from the test failure
     * @param assertionContext Additional context from the assertion (e.g., expected vs actual)
     * @return A FailureCategory with severity and remediation guidance
     */
    public static FailureCategory categorize(String testName, String errorMessage, String assertionContext) {
        String combinedMessage = errorMessage + " " + (assertionContext != null ? assertionContext : "");
        return categorize(testName, combinedMessage);
    }

    /**
     * Categorize multiple test failures and return a summary.
     *
     * @param failures List of test failures as [testName, errorMessage] pairs
     * @return A FailureSummary with categorized failures
     */
    public static FailureSummary categorizeAll(List<TestFailure> failures) {
        List<FailureCategory> critical = new ArrayList<>();
        List<FailureCategory> major = new ArrayList<>();
        List<FailureCategory> minor = new ArrayList<>();

        for (TestFailure failure : failures) {
            FailureCategory category = categorize(failure.testName(), failure.errorMessage());

            switch (category.getSeverity()) {
                case CRITICAL -> critical.add(category);
                case MAJOR -> major.add(category);
                case MINOR -> minor.add(category);
            }
        }

        return new FailureSummary(critical, major, minor);
    }

    // ==================== Category Builders ====================

    private static FailureCategory buildCriticalCategory(String testName, String errorMessage, String reason) {
        String category = determineCriticalCategory(errorMessage);
        String domainViolation = determineDomainViolation(errorMessage);

        return FailureCategory.builder()
            .testName(testName)
            .severity(FailureSeverity.CRITICAL)
            .category(category)
            .errorMessage(errorMessage)
            .domainCorrectnessViolation(domainViolation)
            .addRemediationStep("This is a CRITICAL correctness issue - must be fixed immediately")
            .addRemediationStep(getCriticalRemediation(category))
            .addRemediationStep("Run the failing test in isolation to reproduce the issue")
            .addRemediationStep("Add debug logging to trace the data flow")
            .build();
    }

    private static FailureCategory buildMajorCategory(String testName, String errorMessage, String reason) {
        String category = determineMajorCategory(errorMessage);

        return FailureCategory.builder()
            .testName(testName)
            .severity(FailureSeverity.MAJOR)
            .category(category)
            .errorMessage(errorMessage)
            .addRemediationStep("This is a MAJOR reliability issue - should be fixed before release")
            .addRemediationStep(getMajorRemediation(category))
            .addRemediationStep("Review the test for race conditions or timing issues")
            .build();
    }

    private static FailureCategory buildMinorCategory(String testName, String errorMessage, String reason) {
        String category = determineMinorCategory(errorMessage);

        return FailureCategory.builder()
            .testName(testName)
            .severity(FailureSeverity.MINOR)
            .category(category)
            .errorMessage(errorMessage)
            .addRemediationStep("This is a MINOR cleanup issue - fix when convenient")
            .addRemediationStep(getMinorRemediation(category))
            .build();
    }

    private static FailureCategory buildUnknownCategory(String testName, String errorMessage) {
        return FailureCategory.builder()
            .testName(testName)
            .severity(FailureSeverity.MAJOR)  // Default to MAJOR for unknown failures
            .category("Unknown")
            .errorMessage(errorMessage)
            .addRemediationStep("Unknown failure pattern - review test and error manually")
            .addRemediationStep("Consider adding this pattern to FailureCategorizer")
            .build();
    }

    // ==================== Category Determination ====================

    private static String determineCriticalCategory(String errorMessage) {
        String lower = errorMessage.toLowerCase(Locale.ROOT);

        // Check most specific patterns first to avoid false matches
        if (lower.contains("duplicate")) {
            return "Duplicate Results";
        }
        if (lower.contains("deleted") && (lower.contains("still found") || lower.contains("contains"))) {
            return "Deletion Correctness";
        }
        if (lower.contains("false positive") && lower.contains("deleted")) {
            return "Deletion Correctness";
        }
        if (lower.contains("torn") || lower.contains("epoch isolation")) {
            return "Epoch Isolation";
        }
        if (lower.contains("atomicity") || lower.contains("partial document") || lower.contains("partial") && lower.contains("visible")) {
            return "Document Atomicity";
        }
        if (lower.contains("coverage") && (lower.contains("range") || lower.contains("0.0") || lower.contains("degraded"))) {
            return "Coverage Correctness";
        }
        if (lower.contains("sorted") || lower.contains("ascending order")) {
            return "Result Ordering";
        }
        if (lower.contains("ingested") && lower.contains("not found")) {
            return "Data Loss";
        }
        if (lower.contains("empty") && lower.contains("expected")) {
            return "Data Loss";
        }
        if (lower.contains("results") && lower.contains("0") && lower.contains("expected")) {
            return "Data Loss";
        }
        return "Query Correctness";
    }

    private static String determineMajorCategory(String errorMessage) {
        // The MAJOR_PATTERNS that route here are all CASE_INSENSITIVE, so the keyword
        // lookup must be too or a capitalized message falls through to the default.
        String lower = errorMessage.toLowerCase(Locale.ROOT);

        if (lower.contains("circuit") || lower.contains("breaker")) {
            return "Circuit Breaker";
        }
        if (lower.contains("timeout") || lower.contains("latency") || lower.contains("exceeded")) {
            return "Timeout";
        }
        if (lower.contains("concurrent") || lower.contains("race")) {
            return "Concurrency";
        }
        if (lower.contains("memory") || lower.contains("leak")) {
            return "Resource Leak";
        }
        if (lower.contains("throughput") || lower.contains("performance") || lower.contains("below target")) {
            return "Performance";
        }
        return "Reliability";
    }

    private static String determineMinorCategory(String errorMessage) {
        // The MINOR_PATTERNS that route here are all CASE_INSENSITIVE, so the keyword
        // lookup must be too or a capitalized message falls through to the default.
        String lower = errorMessage.toLowerCase(Locale.ROOT);

        if (lower.contains("false positive") || lower.contains("fp rate") || lower.contains("slightly above")) {
            return "False Positive Rate";
        }
        if (lower.contains("cleanup") || lower.contains("temp") || lower.contains("infrastructure")) {
            return "Test Infrastructure";
        }
        return "Cleanup";
    }

    private static String determineDomainViolation(String errorMessage) {
        String lower = errorMessage.toLowerCase(Locale.ROOT);

        // Coverage violations
        if (lower.contains("coverage") && (lower.contains("range") || lower.contains("0.0") || lower.contains("1.0"))) {
            return "Coverage values must be in range [0.0, 1.0]";
        }
        if (lower.contains("degraded") && (lower.contains("true") || lower.contains("empty"))) {
            return "Empty index must have degraded=true and coverage=0.0";
        }
        // Result ordering
        if (lower.contains("sorted") || lower.contains("ascending")) {
            return "Query results must be sorted by distance/score (closest first)";
        }
        // Duplicates
        if (lower.contains("duplicate")) {
            return "No duplicate entries allowed in query results";
        }
        // Deletion correctness
        if (lower.contains("deleted") && (lower.contains("found") || lower.contains("contains"))) {
            return "After delete(key), contains(key) MUST return false";
        }
        // Epoch isolation
        if (lower.contains("torn") || lower.contains("epoch isolation")) {
            return "A query must only see data visible at its captured epoch";
        }
        // Document atomicity
        if (lower.contains("atomicity") || lower.contains("partial document") || (lower.contains("partial") && lower.contains("visible"))) {
            return "All chunks of a document must become visible atomically";
        }
        return null;
    }

    // ==================== Remediation Guidance ====================

    private static String getCriticalRemediation(String category) {
        return switch (category) {
            case "Data Loss" -> "Check segment registration in VisibilityTracker and StorageEngine";
            case "Epoch Isolation" -> "Verify epoch capture happens before any data access";
            case "Document Atomicity" -> "Ensure DocumentBatchPublisher publishes all chunks in one epoch";
            case "Deletion Correctness" -> "Verify Cuckoo filter delete() removes the key's fingerprint from its bucket";
            case "Coverage Correctness" -> "Check IndexCoverage calculation reflects actual segment states";
            case "Result Ordering" -> "Verify results are sorted by distance in ascending order";
            case "Duplicate Results" -> "Check for duplicate node IDs in HNSW graph or segment storage";
            default -> "Review test assertion and trace data flow";
        };
    }

    private static String getMajorRemediation(String category) {
        return switch (category) {
            case "Circuit Breaker" -> "Check failure count increments and cooldown timer";
            case "Timeout" -> "Profile query execution to find bottlenecks";
            case "Concurrency" -> "Add proper synchronization or use thread-safe collections";
            case "Resource Leak" -> "Ensure Arena.close() and Segment.close() are called in finally blocks";
            case "Performance" -> "Review HNSW efSearch settings and distance computation optimization";
            default -> "Review test for timing or synchronization issues";
        };
    }

    private static String getMinorRemediation(String category) {
        return switch (category) {
            case "False Positive Rate" -> "Consider a wider fingerprint for the Cuckoo filter";
            case "Test Infrastructure" -> "Add proper cleanup in @AfterEach methods";
            default -> "Minor issue - fix when convenient";
        };
    }

    // ==================== Supporting Types ====================

    /**
     * Record representing a test failure for categorization.
     *
     * @param testName The name of the failed test
     * @param errorMessage The error message from the failure
     */
    public record TestFailure(String testName, String errorMessage) {}

    /**
     * Summary of categorized failures.
     */
    public static final class FailureSummary {
        private final List<FailureCategory> critical;
        private final List<FailureCategory> major;
        private final List<FailureCategory> minor;

        FailureSummary(List<FailureCategory> critical, List<FailureCategory> major, List<FailureCategory> minor) {
            this.critical = Collections.unmodifiableList(critical);
            this.major = Collections.unmodifiableList(major);
            this.minor = Collections.unmodifiableList(minor);
        }

        /**
         * Get all CRITICAL failures.
         */
        public List<FailureCategory> getCritical() {
            return critical;
        }

        /**
         * Get all MAJOR failures.
         */
        public List<FailureCategory> getMajor() {
            return major;
        }

        /**
         * Get all MINOR failures.
         */
        public List<FailureCategory> getMinor() {
            return minor;
        }

        /**
         * Get total number of failures.
         */
        public int getTotalCount() {
            return critical.size() + major.size() + minor.size();
        }

        /**
         * Check if there are any CRITICAL failures.
         */
        public boolean hasCriticalFailures() {
            return !critical.isEmpty();
        }

        /**
         * Check if there are any failures at all.
         */
        public boolean hasFailures() {
            return getTotalCount() > 0;
        }

        /**
         * Get a formatted report of all failures.
         */
        public String getReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Failure Categorization Report ===\n\n");

            if (!critical.isEmpty()) {
                sb.append("CRITICAL (").append(critical.size()).append(") - Must fix immediately:\n");
                for (FailureCategory cat : critical) {
                    sb.append("  - ").append(cat.getTestName()).append(": ").append(cat.getCategory()).append("\n");
                }
                sb.append("\n");
            }

            if (!major.isEmpty()) {
                sb.append("MAJOR (").append(major.size()).append(") - Should fix before release:\n");
                for (FailureCategory cat : major) {
                    sb.append("  - ").append(cat.getTestName()).append(": ").append(cat.getCategory()).append("\n");
                }
                sb.append("\n");
            }

            if (!minor.isEmpty()) {
                sb.append("MINOR (").append(minor.size()).append(") - Fix when convenient:\n");
                for (FailureCategory cat : minor) {
                    sb.append("  - ").append(cat.getTestName()).append(": ").append(cat.getCategory()).append("\n");
                }
                sb.append("\n");
            }

            if (!hasFailures()) {
                sb.append("No failures to report.\n");
            }

            sb.append("=== Summary ===\n");
            sb.append("Total: ").append(getTotalCount()).append(" failures\n");
            sb.append("  CRITICAL: ").append(critical.size()).append("\n");
            sb.append("  MAJOR: ").append(major.size()).append("\n");
            sb.append("  MINOR: ").append(minor.size()).append("\n");

            return sb.toString();
        }
    }
}
