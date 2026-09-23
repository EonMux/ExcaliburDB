package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import com.eonmux.excalibur.bench.metric.ErroredBenchmark;
import com.eonmux.excalibur.bench.report.BenchmarkReportWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * The benchmark capture workflow: run, write the reports, and turn the outcome into
 * an exit code.
 *
 * <p>The measurement is behind an interface so the decision this command makes - what
 * a run's outcome means - can be tested without running one. That decision is the part
 * that can be wrong in a way nobody notices: a report that no target could be compared
 * against still prints a table of numbers.</p>
 *
 * <h2>Thread safety</h2>
 * <p>One instance per invocation; not thread-safe.</p>
 */
public final class BenchmarkCaptureCommand {

    /** Every target was compared and met. */
    public static final int EXIT_OK = 0;

    /**
     * A benchmark combination errored, a target was missed or could not be evaluated,
     * or nothing was measured.
     */
    public static final int EXIT_FAILED = 1;

    /** The arguments could not be acted on. */
    public static final int EXIT_USAGE = 2;

    private static final String DEFAULT_OUTPUT_DIR = "build/benchmark-results";
    private static final String DEFAULT_PATTERN = ".*Benchmark.*";

    /**
     * Produces a set of measurements.
     */
    @FunctionalInterface
    public interface BenchmarkRun {

        /**
         * Runs every benchmark matching a pattern.
         *
         * @param pattern the JMH include pattern
         * @param capture whether the caller intends to write reports from the results
         * @return the measurements, and every combination that errored instead
         * @throws Exception if the run fails
         */
        BenchmarkResultSet measure(String pattern, boolean capture) throws Exception;
    }

    private final BenchmarkRun benchmarkRun;
    private final PrintStream out;
    private final PrintStream err;

    /**
     * Creates a command.
     *
     * @param benchmarkRun how to obtain measurements
     * @param out the stream for the report
     * @param err the stream for failures
     */
    public BenchmarkCaptureCommand(BenchmarkRun benchmarkRun, PrintStream out, PrintStream err) {
        this.benchmarkRun = Objects.requireNonNull(benchmarkRun, "benchmarkRun");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Carries out one invocation.
     *
     * @param args the command-line arguments
     * @return the process exit code
     * @throws Exception if the benchmark run or a report write fails
     */
    public int execute(String[] args) throws Exception {
        boolean capture = false;
        String pattern = DEFAULT_PATTERN;
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--capture" -> capture = true;
                case "--pattern" -> {
                    if (++i >= args.length) {
                        return usageError("--pattern requires a value");
                    }
                    pattern = args[i];
                }
                case "--output" -> {
                    if (++i >= args.length) {
                        return usageError("--output requires a value");
                    }
                    outputDir = args[i];
                }
                case "--help" -> {
                    printUsage();
                    return EXIT_OK;
                }
                // Silently ignoring an unrecognised option means a mistyped --pattern
                // runs the whole suite instead of the one benchmark that was asked for,
                // and the report looks perfectly normal.
                default -> {
                    return usageError("unknown option: " + args[i]);
                }
            }
        }

        return capture ? runWithCapture(pattern, outputDir) : runStandard(pattern);
    }

    private int runWithCapture(String pattern, String outputDir) throws Exception {
        out.println("=".repeat(76));
        out.println("ExcaliburDB Benchmark Runner with Result Capture");
        out.println("=".repeat(76));
        out.println();
        out.println("Pattern: " + pattern);
        out.println("Output:  " + outputDir);
        out.println();

        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        BenchmarkResultSet resultSet = Objects.requireNonNull(
            benchmarkRun.measure(pattern, true), "benchmark run returned no result set");

        writeReports(resultSet, outputPath);
        out.println();
        out.println(resultSet.formatSummary());
        out.println(BenchmarkReportWriter.generateMetricsSummary(resultSet));

        return reportOutcome(resultSet, pattern);
    }

    /**
     * Runs without writing reports. Targets are not judged here - JMH's console output is
     * the report - but a combination that errored still fails the run, since the console
     * scrolls its stack trace away and the summary table simply lacks the row.
     */
    private int runStandard(String pattern) throws Exception {
        BenchmarkResultSet resultSet = Objects.requireNonNull(
            benchmarkRun.measure(pattern, false), "benchmark run returned no result set");
        return reportErrored(resultSet) ? EXIT_FAILED : EXIT_OK;
    }

    /**
     * Turns a run's outcome into an exit code, saying which of the failure modes applies.
     *
     * @param resultSet the captured results
     * @param pattern the include pattern that was run
     * @return {@link #EXIT_OK} only when every target was compared and met
     */
    private int reportOutcome(BenchmarkResultSet resultSet, String pattern) {
        // Checked first: an errored combination is also why a run can have no results,
        // and "nothing was measured" would hide the exception that explains it.
        if (reportErrored(resultSet)) {
            return EXIT_FAILED;
        }
        if (resultSet.resultCount() == 0) {
            err.println("FAILED: the run produced no results for pattern '" + pattern
                + "'. Nothing was measured, so no target can be reported as met.");
            return EXIT_FAILED;
        }
        if (resultSet.unevaluatedCount() > 0) {
            err.println("FAILED: " + resultSet.unevaluatedCount()
                + " benchmark(s) carry a target that could not be evaluated. See the report.");
            return EXIT_FAILED;
        }
        if (!resultSet.allPassed()) {
            err.println("FAILED: " + resultSet.failCount()
                + " benchmark(s) did not meet their targets. See the report for details.");
            return EXIT_FAILED;
        }
        out.println("PASSED: all " + resultSet.passCount()
            + " benchmark target(s) were evaluated and met.");
        return EXIT_OK;
    }

    /**
     * Names every errored combination and its failure on the error stream.
     *
     * @return true if any combination errored
     */
    private boolean reportErrored(BenchmarkResultSet resultSet) {
        if (resultSet.erroredCount() == 0) {
            return false;
        }
        err.println("FAILED: " + resultSet.erroredCount()
            + " benchmark combination(s) errored and produced no measurement:");
        for (ErroredBenchmark entry : resultSet.erroredBenchmarks()) {
            err.println("  [" + entry.category() + "] " + entry.label());
            err.println("    " + entry.message());
        }
        return true;
    }

    private void writeReports(BenchmarkResultSet resultSet, Path outputPath) throws IOException {
        Path json = outputPath.resolve("benchmark_results.json");
        Path markdown = outputPath.resolve("benchmark_results.md");
        Path csv = outputPath.resolve("benchmark_results.csv");
        Path text = outputPath.resolve("benchmark_results.txt");

        BenchmarkReportWriter.writeJson(resultSet, json);
        BenchmarkReportWriter.writeMarkdown(resultSet, markdown);
        BenchmarkReportWriter.writeCsv(resultSet, csv);
        BenchmarkReportWriter.writeTxt(resultSet, text);

        out.println();
        out.println("OUTPUT FILES:");
        out.println("  JSON:     " + json.toAbsolutePath());
        out.println("  Markdown: " + markdown.toAbsolutePath());
        out.println("  CSV:      " + csv.toAbsolutePath());
        out.println("  Text:     " + text.toAbsolutePath());
    }

    private int usageError(String message) {
        err.println("Error: " + message);
        printUsage();
        return EXIT_USAGE;
    }

    private void printUsage() {
        out.println("""
            ExcaliburDB Benchmark Runner with Result Capture

            Usage: java -jar excalibur-bench.jar [options]

            Options:
              --capture       Write JSON, Markdown, CSV and text reports
              --pattern PAT   Benchmark pattern to run (default: .*Benchmark.*)
              --output DIR    Output directory (default: build/benchmark-results)
              --help          Show this message

            Exit codes:
              0 - every combination ran and every target was compared and met
              1 - a combination errored, a target was missed or could not be
                  evaluated, or nothing was measured
              2 - the arguments could not be acted on""");
    }
}
