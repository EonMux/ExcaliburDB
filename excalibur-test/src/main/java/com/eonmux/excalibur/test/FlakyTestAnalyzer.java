/*
 * excalibur-test - Flaky Test Analyzer
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - FlakyTestAnalyzer: Not thread-safe, designed for single-threaded sequential test execution
 *   - TestRunResult: Immutable, thread-safe
 *   - FlakyTestReport: Immutable, thread-safe
 *
 * Domain Correctness:
 *   - Flakiness scores are in range [0.0, 1.0]
 *   - Pass rates are in range [0.0, 1.0]
 *   - Run counts are non-negative integers
 *   - Duration values are non-negative milliseconds
 */

package com.eonmux.excalibur.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Analyzes test suite for flaky tests by running tests multiple times and
 * identifying patterns in failures.
 *
 * <h2>Purpose</h2>
 * <p>Integration tests in concurrent systems may exhibit non-deterministic failures
 * due to race conditions, timing issues, or resource contention. This analyzer
 * runs the test suite multiple times and tracks which tests pass/fail inconsistently.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * // Run integration tests 5 times and analyze
 * FlakyTestAnalyzer analyzer = new FlakyTestAnalyzer()
 *     .withModuleName("excalibur-integration")
 *     .withRunCount(5)
 *     .withGradlePath("./gradlew");
 *
 * FlakyTestReport report = analyzer.analyze();
 *
 * System.out.println(report.getSummary());
 *
 * if (report.hasFlakyTests()) {
 *     report.getFlakyTests().forEach(test -> {
 *         System.out.println(test.testName() + " - flakiness: " + test.flakinessScore());
 *     });
 * }
 * </pre>
 *
 * <h2>Flakiness Score</h2>
 * <p>The flakiness score is {@code 2 * min(passRate, 1 - passRate)} over the runs that
 * actually executed the test, so it ranges from 0.0 to 1.0:</p>
 * <ul>
 *   <li>0.0 - Test passes or fails consistently (not flaky)</li>
 *   <li>0.5 - Test passes 25% (or 75%) of the time</li>
 *   <li>1.0 - Test passes 50% of the time (maximally flaky)</li>
 * </ul>
 * <p>Tests scoring above 0.1 that both passed and failed are reported as flaky.</p>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class FlakyTestAnalyzer {

    /** Default number of test runs */
    private static final int DEFAULT_RUN_COUNT = 5;

    /** Minimum runs required for meaningful flakiness analysis */
    private static final int MIN_RUN_COUNT = 3;

    /** Default Gradle wrapper path */
    private static final String DEFAULT_GRADLE_PATH = "./gradlew";

    /** Default module name */
    private static final String DEFAULT_MODULE = "excalibur-integration";

    /** Flakiness threshold - tests with scores above this are considered flaky */
    private static final double FLAKINESS_THRESHOLD = 0.1;

    /** Location of the JUnit XML reports Gradle writes, relative to the module directory */
    private static final String TEST_RESULTS_PATH = "build/test-results/test";

    /** Number of trailing Gradle output lines quoted when a run produces no reports */
    private static final int OUTPUT_TAIL_LINES = 20;

    private String moduleName = DEFAULT_MODULE;
    private int runCount = DEFAULT_RUN_COUNT;
    private String gradlePath = DEFAULT_GRADLE_PATH;
    private Path workingDirectory = Paths.get(".").toAbsolutePath();
    private boolean verbose = false;

    /**
     * Create a new FlakyTestAnalyzer with default settings.
     */
    public FlakyTestAnalyzer() {
        // Default constructor
    }

    /**
     * Set the module name to test.
     *
     * @param moduleName The Gradle module name (e.g., "excalibur-integration")
     * @return this
     */
    public FlakyTestAnalyzer withModuleName(String moduleName) {
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName is required");
        return this;
    }

    /**
     * Set the number of times to run the tests.
     *
     * @param count Number of runs (minimum 3)
     * @return this
     * @throws IllegalArgumentException if count is less than 3
     */
    public FlakyTestAnalyzer withRunCount(int count) {
        if (count < MIN_RUN_COUNT) {
            throw new IllegalArgumentException(
                "Run count must be at least " + MIN_RUN_COUNT + " for meaningful analysis, got: " + count);
        }
        this.runCount = count;
        return this;
    }

    /**
     * Set the path to the Gradle wrapper.
     *
     * @param gradlePath Path to gradlew or gradle
     * @return this
     */
    public FlakyTestAnalyzer withGradlePath(String gradlePath) {
        this.gradlePath = Objects.requireNonNull(gradlePath, "gradlePath is required");
        return this;
    }

    /**
     * Set the working directory for running Gradle.
     *
     * @param directory Working directory path
     * @return this
     */
    public FlakyTestAnalyzer withWorkingDirectory(Path directory) {
        this.workingDirectory = Objects.requireNonNull(directory, "directory is required");
        return this;
    }

    /**
     * Enable or disable verbose output.
     *
     * @param verbose true for verbose output
     * @return this
     */
    public FlakyTestAnalyzer withVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /**
     * Run the analysis and return a report.
     *
     * @return FlakyTestReport containing analysis results
     * @throws IOException if test execution fails
     * @throws InterruptedException if test execution is interrupted
     */
    public FlakyTestReport analyze() throws IOException, InterruptedException {
        System.out.println("=== Flaky Test Analysis ===");
        System.out.println("Module: " + moduleName);
        System.out.println("Runs: " + runCount);
        System.out.println("Gradle: " + gradlePath);
        System.out.println();

        List<RunSummary> runSummaries = new ArrayList<>();
        for (int run = 1; run <= runCount; run++) {
            System.out.println("--- Run " + run + "/" + runCount + " ---");

            RunSummary summary = runTests(run);
            runSummaries.add(summary);

            System.out.println("  Passed: " + summary.passed() + ", Failed: " + summary.failed()
                + ", Skipped: " + summary.skipped()
                + ", Duration: " + summary.duration().toMillis() + "ms");
            System.out.println();
        }

        return classify(moduleName, runSummaries, verbose);
    }

    /**
     * Draws the verdict from runs that have already happened.
     *
     * <p>Separate from {@link #analyze()}, which launches Gradle {@code runCount} times to
     * produce them. The verdict is the analyzer's whole output and the part that can be
     * wrong in a way nobody notices - a flaky test filed as sound, or a broken one filed as
     * flaky - and a judgement reachable only by launching a build five times is a judgement
     * nothing tests.</p>
     *
     * @param moduleName   The module the runs came from, carried into the report
     * @param runSummaries What each run found, in order
     * @param verbose      Whether to narrate each test's tally
     * @return What the runs say about each test
     * @throws NullPointerException if moduleName or runSummaries is null
     */
    static FlakyTestReport classify(String moduleName, List<RunSummary> runSummaries,
                                    boolean verbose) {
        Objects.requireNonNull(moduleName, "moduleName is required");
        Objects.requireNonNull(runSummaries, "runSummaries is required");

        Map<String, List<TestRunResult>> testResults = new LinkedHashMap<>();
        for (RunSummary summary : runSummaries) {
            for (TestRunResult result : summary.results()) {
                testResults.computeIfAbsent(result.testName(), k -> new ArrayList<>())
                    .add(result);
            }
        }

        List<FlakyTestResult> flakyTests = new ArrayList<>();
        List<ConsistentFailure> consistentFailures = new ArrayList<>();
        List<String> alwaysPassing = new ArrayList<>();

        for (Map.Entry<String, List<TestRunResult>> entry : testResults.entrySet()) {
            verdict(entry.getKey(), entry.getValue(), verbose,
                flakyTests, consistentFailures, alwaysPassing);
        }

        // Triage starts at the top of the list, so the worst offender belongs there.
        flakyTests.sort(Comparator.comparingDouble(FlakyTestResult::flakinessScore).reversed());

        Map<String, FailureCategory> categorizedFailures = new LinkedHashMap<>();
        for (ConsistentFailure failure : consistentFailures) {
            categorizedFailures.put(failure.testName(),
                FailureCategorizer.categorize(failure.testName(), failure.errorMessage()));
        }

        return new FlakyTestReport(
            moduleName,
            runSummaries.size(),
            runSummaries,
            flakyTests,
            consistentFailures,
            alwaysPassing,
            categorizedFailures,
            testResults
        );
    }

    /**
     * Files one test's results under flaky, consistently failing, or sound.
     *
     * @param testName          The test
     * @param results           Its result from every run, including the runs that skipped it
     * @param verbose           Whether to narrate the tally
     * @param flakyTests        Collects tests that both passed and failed
     * @param consistentFailures Collects tests that never passed
     * @param alwaysPassing     Collects tests that never failed
     */
    private static void verdict(String testName, List<TestRunResult> results, boolean verbose,
                                List<FlakyTestResult> flakyTests,
                                List<ConsistentFailure> consistentFailures,
                                List<String> alwaysPassing) {
        int passCount = (int) results.stream().filter(TestRunResult::passed).count();
        int failCount = (int) results.stream().filter(TestRunResult::failed).count();
        // Only runs that actually executed the test carry a verdict. Counting SKIPPED
        // runs here would both distort the pass rate and break the FlakyTestResult
        // invariant passCount + failCount == totalRuns.
        int executedRuns = passCount + failCount;

        if (executedRuns == 0) {
            // Skipped in every run - there is no evidence either way
            if (verbose) {
                System.out.println("Test: " + testName + " | skipped in all "
                    + results.size() + " runs");
            }
            return;
        }

        double passRate = (double) passCount / executedRuns;
        double flakinessScore = calculateFlakinessScore(passRate);

        if (verbose) {
            System.out.println("Test: " + testName
                + " | Passes: " + passCount + "/" + executedRuns
                + " | Flakiness: " + String.format("%.2f", flakinessScore));
        }

        if (flakinessScore > FLAKINESS_THRESHOLD && failCount > 0 && passCount > 0) {
            flakyTests.add(new FlakyTestResult(testName, passCount, failCount, executedRuns,
                flakinessScore, passRate, extractFailurePatterns(results)));
        } else if (failCount == executedRuns) {
            String errorMessage = results.stream()
                .filter(TestRunResult::failed)
                .map(TestRunResult::errorMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("Unknown error");
            consistentFailures.add(new ConsistentFailure(testName, executedRuns, errorMessage));
        } else if (passCount == executedRuns) {
            alwaysPassing.add(testName);
        }
    }

    /**
     * Calculate flakiness score from pass rate.
     *
     * <p>A score of 0.0 means consistent (0% or 100% pass rate).
     * A score of 1.0 means maximally flaky (50% pass rate).</p>
     *
     * @param passRate The pass rate (0.0 to 1.0)
     * @return Flakiness score (0.0 to 1.0)
     */
    private static double calculateFlakinessScore(double passRate) {
        // Domain correctness: clamp to valid range
        double clampedRate = Math.max(0.0, Math.min(1.0, passRate));
        // Distance from 0 or 1, scaled to [0,1]
        // 0% pass rate -> 0.0 flakiness
        // 50% pass rate -> 1.0 flakiness
        // 100% pass rate -> 0.0 flakiness
        return 2.0 * Math.min(clampedRate, 1.0 - clampedRate);
    }

    /**
     * Extract common failure patterns from a list of test results.
     */
    private static List<String> extractFailurePatterns(List<TestRunResult> results) {
        List<String> patterns = new ArrayList<>();
        Map<String, Integer> errorCounts = new HashMap<>();

        for (TestRunResult result : results) {
            if (result.failed() && result.errorMessage() != null) {
                // Extract key phrases from error message
                String error = result.errorMessage();
                errorCounts.merge(error, 1, Integer::sum);
            }
        }

        // Return unique errors sorted by frequency
        errorCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> patterns.add(e.getKey() + " (" + e.getValue() + "x)"));

        return patterns;
    }

    /**
     * Run tests once and collect results.
     *
     * <p>Results are read from the JUnit XML reports Gradle writes for the module rather
     * than scraped from console output: the reports are structured, carry the real failure
     * message for every failed test, and cannot be confused with a test's own stdout.</p>
     *
     * @param runNumber The 1-based run number
     * @return The summary of this run
     * @throws IOException if Gradle produced no test reports for this run
     */
    private RunSummary runTests(int runNumber) throws IOException, InterruptedException {
        Instant start = Instant.now();
        Path resultsDirectory = testResultsDirectory();

        ProcessBuilder pb = new ProcessBuilder(
            gradlePath,
            ":" + moduleName + ":test",
            "--console=plain",
            "--no-daemon",
            // Without this the test task is UP-TO-DATE from run 2 onwards and no test
            // would actually be executed again, which is the whole point of the analysis.
            "--rerun"
        );
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<String> output = drainOutput(process);
        int exitCode = process.waitFor();
        Instant end = Instant.now();

        List<TestRunResult> results = JUnitReportReader.readReports(resultsDirectory, start, runNumber);
        if (results.isEmpty()) {
            throw new IOException(
                "Run " + runNumber + " produced no test reports in " + resultsDirectory
                    + " (gradle exit code " + exitCode + "). Last output:\n"
                    + String.join("\n", tail(output, OUTPUT_TAIL_LINES)));
        }

        int passed = (int) results.stream().filter(TestRunResult::passed).count();
        int failed = (int) results.stream().filter(TestRunResult::failed).count();
        int skipped = (int) results.stream().filter(TestRunResult::skipped).count();

        return new RunSummary(runNumber, results, passed, failed, skipped, Duration.between(start, end), exitCode);
    }

    /**
     * Resolve the directory Gradle writes JUnit XML reports to for the analyzed module.
     */
    private Path testResultsDirectory() {
        String relativePath = moduleName.replace(':', '/');
        return workingDirectory.resolve(relativePath).resolve(TEST_RESULTS_PATH).normalize();
    }

    /**
     * Consume the process output so the child never blocks on a full pipe.
     */
    private List<String> drainOutput(Process process) throws IOException {
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (verbose) {
                    System.out.println("  " + line);
                }
                output.add(line);
            }
        }
        return output;
    }

    private static List<String> tail(List<String> lines, int count) {
        return lines.subList(Math.max(0, lines.size() - count), lines.size());
    }

    // ==================== Supporting Types ====================

    /**
     * Status of a single test run.
     */
    public enum TestStatus {
        PASSED,
        FAILED,
        SKIPPED
    }

    /**
     * Result of a single test in a single run.
     *
     * @param testName Full test name (class.method)
     * @param status Test status
     * @param errorMessage Error message if failed, null otherwise
     * @param runNumber The run number (1-based)
     */
    public record TestRunResult(
        String testName,
        TestStatus status,
        String errorMessage,
        int runNumber
    ) {
        public TestRunResult {
            Objects.requireNonNull(testName, "testName is required");
            Objects.requireNonNull(status, "status is required");
            if (runNumber < 1) {
                throw new IllegalArgumentException("runNumber must be positive, got: " + runNumber);
            }
        }

        public boolean passed() {
            return status == TestStatus.PASSED;
        }

        public boolean failed() {
            return status == TestStatus.FAILED;
        }

        public boolean skipped() {
            return status == TestStatus.SKIPPED;
        }
    }

    /**
     * Summary of a single test run.
     *
     * @param runNumber The run number (1-based)
     * @param results Individual test results
     * @param passed Number of passed tests
     * @param failed Number of failed tests
     * @param skipped Number of skipped tests
     * @param duration Duration of the run
     * @param exitCode Gradle exit code
     */
    public record RunSummary(
        int runNumber,
        List<TestRunResult> results,
        int passed,
        int failed,
        int skipped,
        Duration duration,
        int exitCode
    ) {
        public RunSummary {
            Objects.requireNonNull(results, "results is required");
            Objects.requireNonNull(duration, "duration is required");
            results = Collections.unmodifiableList(new ArrayList<>(results));
        }

        public int total() {
            return passed + failed + skipped;
        }

        public boolean success() {
            return exitCode == 0 && failed == 0;
        }
    }

    /**
     * Result for a test identified as flaky.
     *
     * @param testName Full test name
     * @param passCount Number of times the test passed
     * @param failCount Number of times the test failed
     * @param totalRuns Number of runs that executed the test (passCount + failCount)
     * @param flakinessScore Flakiness score (0.0 to 1.0, higher = more flaky)
     * @param passRate Pass rate (0.0 to 1.0)
     * @param failurePatterns Common failure patterns observed
     */
    public record FlakyTestResult(
        String testName,
        int passCount,
        int failCount,
        int totalRuns,
        double flakinessScore,
        double passRate,
        List<String> failurePatterns
    ) {
        public FlakyTestResult {
            Objects.requireNonNull(testName, "testName is required");
            Objects.requireNonNull(failurePatterns, "failurePatterns is required");

            // Domain correctness validation
            if (passCount < 0 || failCount < 0 || totalRuns < 0) {
                throw new IllegalArgumentException("Counts must be non-negative");
            }
            if (passCount + failCount != totalRuns) {
                throw new IllegalArgumentException("passCount + failCount must equal totalRuns");
            }
            if (flakinessScore < 0.0 || flakinessScore > 1.0) {
                throw new IllegalArgumentException("flakinessScore must be in range [0.0, 1.0], got: " + flakinessScore);
            }
            if (passRate < 0.0 || passRate > 1.0) {
                throw new IllegalArgumentException("passRate must be in range [0.0, 1.0], got: " + passRate);
            }

            failurePatterns = Collections.unmodifiableList(new ArrayList<>(failurePatterns));
        }

        /**
         * Get a human-readable description of the flakiness.
         */
        public String getDescription() {
            return String.format("%s: passed %d/%d runs (%.0f%%) - flakiness score: %.2f",
                testName, passCount, totalRuns, passRate * 100, flakinessScore);
        }
    }

    /**
     * A test that consistently fails across all runs.
     *
     * @param testName Full test name
     * @param failCount Number of times failed (equals total runs)
     * @param errorMessage The error message
     */
    public record ConsistentFailure(
        String testName,
        int failCount,
        String errorMessage
    ) {
        public ConsistentFailure {
            Objects.requireNonNull(testName, "testName is required");
            if (failCount < 0) {
                throw new IllegalArgumentException("failCount must be non-negative");
            }
        }
    }

    /**
     * Complete report from flaky test analysis.
     */
    public static final class FlakyTestReport {
        private final String moduleName;
        private final int runCount;
        private final List<RunSummary> runSummaries;
        private final List<FlakyTestResult> flakyTests;
        private final List<ConsistentFailure> consistentFailures;
        private final List<String> alwaysPassing;
        private final Map<String, FailureCategory> categorizedFailures;
        private final Map<String, List<TestRunResult>> allResults;

        FlakyTestReport(
            String moduleName,
            int runCount,
            List<RunSummary> runSummaries,
            List<FlakyTestResult> flakyTests,
            List<ConsistentFailure> consistentFailures,
            List<String> alwaysPassing,
            Map<String, FailureCategory> categorizedFailures,
            Map<String, List<TestRunResult>> allResults
        ) {
            this.moduleName = moduleName;
            this.runCount = runCount;
            this.runSummaries = Collections.unmodifiableList(new ArrayList<>(runSummaries));
            this.flakyTests = Collections.unmodifiableList(new ArrayList<>(flakyTests));
            this.consistentFailures = Collections.unmodifiableList(new ArrayList<>(consistentFailures));
            this.alwaysPassing = Collections.unmodifiableList(new ArrayList<>(alwaysPassing));
            this.categorizedFailures = Collections.unmodifiableMap(new LinkedHashMap<>(categorizedFailures));
            this.allResults = Collections.unmodifiableMap(allResults.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> Collections.unmodifiableList(new ArrayList<>(e.getValue()))
                )));
        }

        /**
         * Get the module name that was analyzed.
         */
        public String getModuleName() {
            return moduleName;
        }

        /**
         * Get the number of test runs performed.
         */
        public int getRunCount() {
            return runCount;
        }

        /**
         * Get summaries for each run.
         */
        public List<RunSummary> getRunSummaries() {
            return runSummaries;
        }

        /**
         * Get tests identified as flaky.
         */
        public List<FlakyTestResult> getFlakyTests() {
            return flakyTests;
        }

        /**
         * Get tests that consistently failed.
         */
        public List<ConsistentFailure> getConsistentFailures() {
            return consistentFailures;
        }

        /**
         * Get tests that consistently passed.
         */
        public List<String> getAlwaysPassing() {
            return alwaysPassing;
        }

        /**
         * Get categorized failures using FailureCategorizer.
         */
        public Map<String, FailureCategory> getCategorizedFailures() {
            return categorizedFailures;
        }

        /**
         * Get all test results for all runs.
         */
        public Map<String, List<TestRunResult>> getAllResults() {
            return allResults;
        }

        /**
         * Check if any flaky tests were detected.
         */
        public boolean hasFlakyTests() {
            return !flakyTests.isEmpty();
        }

        /**
         * Check if there are any consistent failures.
         */
        public boolean hasConsistentFailures() {
            return !consistentFailures.isEmpty();
        }

        /**
         * Check if all tests passed consistently.
         */
        public boolean allTestsPassed() {
            return flakyTests.isEmpty() && consistentFailures.isEmpty();
        }

        /**
         * Get the total number of unique tests analyzed.
         */
        public int getTotalTestCount() {
            return allResults.size();
        }

        /**
         * Get overall pass rate across all runs, over the tests that actually executed.
         */
        public double getOverallPassRate() {
            int totalPasses = 0;
            int totalExecuted = 0;

            for (RunSummary summary : runSummaries) {
                totalPasses += summary.passed();
                // Skipped tests were never executed and carry no verdict
                totalExecuted += summary.passed() + summary.failed();
            }

            // Domain correctness: handle division by zero
            if (totalExecuted == 0) {
                return 0.0;
            }

            return (double) totalPasses / totalExecuted;
        }

        /**
         * Get a summary string suitable for console output.
         */
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Flaky Test Analysis Report ===\n\n");

            sb.append("Module: ").append(moduleName).append("\n");
            sb.append("Runs: ").append(runCount).append("\n");
            sb.append("Total Tests: ").append(getTotalTestCount()).append("\n");
            sb.append("Overall Pass Rate: ").append(String.format("%.1f%%", getOverallPassRate() * 100)).append("\n\n");

            // Flaky tests
            if (hasFlakyTests()) {
                sb.append("FLAKY TESTS (").append(flakyTests.size()).append("):\n");
                sb.append("-".repeat(60)).append("\n");
                for (FlakyTestResult test : flakyTests) {
                    sb.append(String.format("  %s\n", test.getDescription()));
                    if (!test.failurePatterns().isEmpty()) {
                        sb.append("    Failure patterns:\n");
                        for (String pattern : test.failurePatterns().subList(0, Math.min(3, test.failurePatterns().size()))) {
                            sb.append("      - ").append(pattern).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }

            // Consistent failures
            if (hasConsistentFailures()) {
                sb.append("CONSISTENT FAILURES (").append(consistentFailures.size()).append("):\n");
                sb.append("-".repeat(60)).append("\n");
                for (ConsistentFailure failure : consistentFailures) {
                    sb.append("  ").append(failure.testName()).append("\n");
                    FailureCategory category = categorizedFailures.get(failure.testName());
                    if (category != null) {
                        sb.append("    Severity: ").append(category.getSeverity()).append("\n");
                        sb.append("    Category: ").append(category.getCategory()).append("\n");
                    }
                }
                sb.append("\n");
            }

            // Run summaries
            sb.append("RUN SUMMARIES:\n");
            sb.append("-".repeat(60)).append("\n");
            for (RunSummary summary : runSummaries) {
                sb.append(String.format("  Run %d: %d passed, %d failed, %d skipped (%dms)\n",
                    summary.runNumber(),
                    summary.passed(),
                    summary.failed(),
                    summary.skipped(),
                    summary.duration().toMillis()));
            }
            sb.append("\n");

            // Conclusion
            sb.append("=== Conclusion ===\n");
            if (allTestsPassed()) {
                sb.append("All tests passed consistently across all runs.\n");
            } else {
                if (hasFlakyTests()) {
                    sb.append(String.format("Found %d flaky test(s) that need investigation.\n", flakyTests.size()));
                }
                if (hasConsistentFailures()) {
                    sb.append(String.format("Found %d consistently failing test(s) that need fixing.\n", consistentFailures.size()));
                }
            }

            return sb.toString();
        }

        /**
         * Get a detailed report with all failure categorizations.
         */
        public String getDetailedReport() {
            StringBuilder sb = new StringBuilder();
            sb.append(getSummary());

            if (hasConsistentFailures()) {
                sb.append("\n=== Detailed Failure Analysis ===\n\n");

                // Group by severity
                Map<FailureSeverity, List<Map.Entry<String, FailureCategory>>> bySeverity =
                    categorizedFailures.entrySet().stream()
                        .collect(Collectors.groupingBy(e -> e.getValue().getSeverity()));

                for (FailureSeverity severity : List.of(FailureSeverity.CRITICAL, FailureSeverity.MAJOR, FailureSeverity.MINOR)) {
                    List<Map.Entry<String, FailureCategory>> entries = bySeverity.get(severity);
                    if (entries != null && !entries.isEmpty()) {
                        sb.append(severity).append(" (").append(entries.size()).append("):\n");
                        for (Map.Entry<String, FailureCategory> entry : entries) {
                            FailureCategory cat = entry.getValue();
                            sb.append("  ").append(entry.getKey()).append("\n");
                            sb.append("    Category: ").append(cat.getCategory()).append("\n");
                            sb.append("    Remediation:\n");
                            for (String step : cat.getRemediationSteps()) {
                                sb.append("      - ").append(step).append("\n");
                            }
                        }
                        sb.append("\n");
                    }
                }
            }

            return sb.toString();
        }

        /**
         * Get a JSON representation of the report (for CI integration).
         */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"module\": \"").append(escapeJson(moduleName)).append("\",\n");
            sb.append("  \"runCount\": ").append(runCount).append(",\n");
            sb.append("  \"totalTests\": ").append(getTotalTestCount()).append(",\n");
            sb.append("  \"overallPassRate\": ").append(String.format("%.4f", getOverallPassRate())).append(",\n");
            sb.append("  \"flakyTestCount\": ").append(flakyTests.size()).append(",\n");
            sb.append("  \"consistentFailureCount\": ").append(consistentFailures.size()).append(",\n");
            sb.append("  \"allTestsPassed\": ").append(allTestsPassed()).append(",\n");

            sb.append("  \"flakyTests\": [\n");
            for (int i = 0; i < flakyTests.size(); i++) {
                FlakyTestResult test = flakyTests.get(i);
                sb.append("    {\n");
                sb.append("      \"testName\": \"").append(escapeJson(test.testName())).append("\",\n");
                sb.append("      \"passCount\": ").append(test.passCount()).append(",\n");
                sb.append("      \"failCount\": ").append(test.failCount()).append(",\n");
                sb.append("      \"flakinessScore\": ").append(String.format("%.4f", test.flakinessScore())).append(",\n");
                sb.append("      \"passRate\": ").append(String.format("%.4f", test.passRate())).append("\n");
                sb.append("    }").append(i < flakyTests.size() - 1 ? "," : "").append("\n");
            }
            sb.append("  ],\n");

            sb.append("  \"runSummaries\": [\n");
            for (int i = 0; i < runSummaries.size(); i++) {
                RunSummary summary = runSummaries.get(i);
                sb.append("    {\n");
                sb.append("      \"runNumber\": ").append(summary.runNumber()).append(",\n");
                sb.append("      \"passed\": ").append(summary.passed()).append(",\n");
                sb.append("      \"failed\": ").append(summary.failed()).append(",\n");
                sb.append("      \"skipped\": ").append(summary.skipped()).append(",\n");
                sb.append("      \"durationMs\": ").append(summary.duration().toMillis()).append("\n");
                sb.append("    }").append(i < runSummaries.size() - 1 ? "," : "").append("\n");
            }
            sb.append("  ]\n");

            sb.append("}\n");
            return sb.toString();
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
        }
    }

    /**
     * Main entry point for command-line usage.
     */
    public static void main(String[] args) throws Exception {
        String module = args.length > 0 ? args[0] : DEFAULT_MODULE;
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_RUN_COUNT;
        boolean verbose = args.length > 2 && "-v".equals(args[2]);

        FlakyTestAnalyzer analyzer = new FlakyTestAnalyzer()
            .withModuleName(module)
            .withRunCount(runs)
            .withVerbose(verbose);

        FlakyTestReport report = analyzer.analyze();

        System.out.println(report.getSummary());

        // Exit with error code if flaky tests or consistent failures found
        if (!report.allTestsPassed()) {
            System.exit(1);
        }
    }
}
