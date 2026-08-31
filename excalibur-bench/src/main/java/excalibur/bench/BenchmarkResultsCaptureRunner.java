package excalibur.bench;

import excalibur.bench.metric.BenchmarkResult;
import excalibur.bench.metric.BenchmarkResultCapture;
import excalibur.bench.metric.BenchmarkResultSet;
import excalibur.bench.report.BenchmarkReportWriter;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import org.openjdk.jmh.results.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * JMH Benchmark Runner with structured result capture.
 *
 * <p>Runs benchmarks and captures results (throughput, latency percentiles)
 * in a structured format for analysis and reporting.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all benchmarks and capture results
 * java -jar excalibur-bench.jar --capture
 *
 * # Run specific benchmark pattern
 * java -jar excalibur-bench.jar --capture --pattern ".*PointLookup.*"
 *
 * # Output to specific directory
 * java -jar excalibur-bench.jar --capture --output ./benchmark-results/
 * </pre>
 *
 * <h2>Output Files</h2>
 * <ul>
 *   <li>benchmark_results.json - Full results in JSON format</li>
 *   <li>benchmark_results.md - Human-readable Markdown report</li>
 *   <li>benchmark_results.csv - CSV export for spreadsheet analysis</li>
 *   <li>benchmark_results.txt - Console summary</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class BenchmarkResultsCaptureRunner {

    private static final String DEFAULT_OUTPUT_DIR = "build/benchmark-results";
    private static final String BOX_TOP =
        "\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557";
    private static final String BOX_BOTTOM =
        "\u255a\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255d";
    private static final String SEPARATOR =
        "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

    public static void main(String[] args) throws Exception {
        boolean captureMode = false;
        String pattern = ".*Benchmark.*";
        String outputDir = DEFAULT_OUTPUT_DIR;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--capture":
                    captureMode = true;
                    break;
                case "--pattern":
                    if (i + 1 < args.length) {
                        pattern = args[++i];
                    }
                    break;
                case "--output":
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    }
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }

        if (captureMode) {
            runWithCapture(pattern, outputDir);
        } else {
            runStandard(pattern);
        }
    }

    /**
     * Runs benchmarks with structured result capture.
     *
     * <p>Results come from JMH's own {@link org.openjdk.jmh.runner.Runner#run()} return
     * value rather than from scraped console output: the benchmarks execute in a forked
     * JVM whose stdout this process never sees, so no amount of stream redirection here
     * could have captured them.</p>
     *
     * <p>A run that produced no results, missed a target, or carries a target that could
     * not be evaluated exits non-zero. Reporting nothing is never a pass.</p>
     */
    private static void runWithCapture(String pattern, String outputDir) throws Exception {
        System.out.println(BOX_TOP);
        System.out.println("\u2551          ExcaliburDB Benchmark Runner with Result Capture                 \u2551");
        System.out.println(BOX_BOTTOM);
        System.out.println();
        System.out.println("Pattern: " + pattern);
        System.out.println("Output:  " + outputDir);
        System.out.println();

        // Create output directory
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        Options options = buildOptions(pattern);

        Instant startTime = Instant.now();
        Collection<RunResult> runResults = new Runner(options).run();
        Instant endTime = Instant.now();

        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(runResults);

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("ExcaliburDB Benchmark Run")
            .startTime(startTime)
            .endTime(endTime)
            .results(results)
            .build();

        writeReports(resultSet, outputPath);

        System.out.println("\n" + resultSet.formatSummary());
        System.out.println(BenchmarkReportWriter.generateMetricsSummary(resultSet));

        System.exit(reportOutcome(resultSet, pattern));
    }

    /**
     * Prints the outcome of a run and returns the process exit code.
     *
     * @param resultSet the captured results
     * @param pattern the benchmark include pattern that was run
     * @return 0 when the run genuinely passed, 1 otherwise
     */
    private static int reportOutcome(BenchmarkResultSet resultSet, String pattern) {
        if (resultSet.resultCount() == 0) {
            System.err.println("FAILED: JMH returned no results for pattern '" + pattern
                + "'. Nothing was measured, so no target can be reported as met.");
            return 1;
        }
        if (resultSet.unevaluatedCount() > 0) {
            System.err.println("FAILED: " + resultSet.unevaluatedCount()
                + " benchmark(s) carry a target that could not be evaluated. See the report.");
            return 1;
        }
        if (!resultSet.allPassed()) {
            System.err.println("FAILED: " + resultSet.failCount()
                + " benchmark(s) did not meet their targets. See the report for details.");
            return 1;
        }
        System.out.println("PASSED: all " + resultSet.passCount()
            + " benchmark target(s) were evaluated and met.");
        return 0;
    }

    private static void writeReports(BenchmarkResultSet resultSet, Path outputPath) throws IOException {
        Path jsonPath = outputPath.resolve("benchmark_results.json");
        Path mdPath = outputPath.resolve("benchmark_results.md");
        Path csvPath = outputPath.resolve("benchmark_results.csv");
        Path txtPath = outputPath.resolve("benchmark_results.txt");

        BenchmarkReportWriter.writeJson(resultSet, jsonPath);
        BenchmarkReportWriter.writeMarkdown(resultSet, mdPath);
        BenchmarkReportWriter.writeCsv(resultSet, csvPath);
        BenchmarkReportWriter.writeTxt(resultSet, txtPath);

        System.out.println("\n" + SEPARATOR);
        System.out.println("OUTPUT FILES:");
        System.out.println(SEPARATOR);
        System.out.println("  JSON:     " + jsonPath.toAbsolutePath());
        System.out.println("  Markdown: " + mdPath.toAbsolutePath());
        System.out.println("  CSV:      " + csvPath.toAbsolutePath());
        System.out.println("  Text:     " + txtPath.toAbsolutePath());
        System.out.println();
    }

    /**
     * Builds the JMH options shared by both run modes.
     *
     * <p>{@code --add-modules=jdk.incubator.vector} must reach the forked JVM: several
     * benchmarks use the Vector API and fail to start without it, exactly as the Gradle
     * benchmark tasks already declare.</p>
     */
    private static Options buildOptions(String pattern) {
        return new OptionsBuilder()
            .include(pattern)
            .warmupIterations(2)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .jvmArgsAppend(
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-Xms1g",
                "-Xmx1g"
            )
            .shouldDoGC(true)
            .build();
    }

    /**
     * Runs benchmarks in standard mode (no structured capture).
     */
    private static void runStandard(String pattern) throws RunnerException {
        new Runner(buildOptions(pattern)).run();
    }

    private static void printUsage() {
        System.out.println("ExcaliburDB Benchmark Runner with Result Capture");
        System.out.println();
        System.out.println("Usage: java -jar excalibur-bench.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --capture       Enable structured result capture");
        System.out.println("  --pattern PAT   Benchmark pattern to run (default: .*Benchmark.*)");
        System.out.println("  --output DIR    Output directory (default: build/benchmark-results)");
        System.out.println("  --help          Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Run all benchmarks with capture");
        System.out.println("  java -jar excalibur-bench.jar --capture");
        System.out.println();
        System.out.println("  # Run only point lookup benchmarks");
        System.out.println("  java -jar excalibur-bench.jar --capture --pattern \".*PointLookup.*\"");
        System.out.println();
        System.out.println("  # Custom output directory");
        System.out.println("  java -jar excalibur-bench.jar --capture --output ./results/");
    }
}
