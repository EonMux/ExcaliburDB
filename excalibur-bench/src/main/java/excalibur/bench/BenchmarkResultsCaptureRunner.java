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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
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
     */
    private static void runWithCapture(String pattern, String outputDir) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║          ExcaliburDB Benchmark Runner with Result Capture                 ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Pattern: " + pattern);
        System.out.println("Output:  " + outputDir);
        System.out.println();

        // Create output directory
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        // Capture JMH output
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos);

        // Save original System.out
        PrintStream originalOut = System.out;

        // Build JMH options
        Options options = new OptionsBuilder()
            .include(pattern)
            .warmupIterations(2)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .jvmArgsAppend(
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "-Xms1g",
                "-Xmx1g"
            )
            .shouldDoGC(true)
            .build();

        Instant startTime = Instant.now();

        try {
            // Run benchmarks (output goes to both console and capture stream)
            new Runner(options).run();
        } finally {
            Instant endTime = Instant.now();

            // Parse captured output
            String capturedOutput = baos.toString();
            List<String> outputLines = parseLines(capturedOutput);

            List<BenchmarkResult> results = BenchmarkResultCapture.parseConsoleOutput(outputLines);

            if (results.isEmpty()) {
                System.out.println("\n⚠ No benchmark results captured from output.");
                System.out.println("Attempting to parse raw output...\n");

                // Print first 50 lines for debugging
                int lineCount = 0;
                for (String line : outputLines) {
                    if (lineCount++ >= 50) break;
                    System.out.println("  " + line);
                }
            }

            // Create result set
            BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
                .name("ExcaliburDB Benchmark Run")
                .startTime(startTime)
                .endTime(endTime)
                .results(results)
                .build();

            // Write outputs
            Path jsonPath = outputPath.resolve("benchmark_results.json");
            Path mdPath = outputPath.resolve("benchmark_results.md");
            Path csvPath = outputPath.resolve("benchmark_results.csv");
            Path txtPath = outputPath.resolve("benchmark_results.txt");

            BenchmarkReportWriter.writeJson(resultSet, jsonPath);
            BenchmarkReportWriter.writeMarkdown(resultSet, mdPath);
            BenchmarkReportWriter.writeCsv(resultSet, csvPath);
            BenchmarkReportWriter.writeTxt(resultSet, txtPath);

            // Print summary
            System.out.println("\n" + resultSet.formatSummary());

            System.out.println("\n───────────────────────────────────────────────────────────────────────────");
            System.out.println("OUTPUT FILES:");
            System.out.println("───────────────────────────────────────────────────────────────────────────");
            System.out.println("  JSON:     " + jsonPath.toAbsolutePath());
            System.out.println("  Markdown: " + mdPath.toAbsolutePath());
            System.out.println("  CSV:      " + csvPath.toAbsolutePath());
            System.out.println("  Text:     " + txtPath.toAbsolutePath());
            System.out.println();

            // Print metrics summary
            System.out.println(BenchmarkReportWriter.generateMetricsSummary(resultSet));

            // Exit code based on pass/fail
            if (!resultSet.allPassed()) {
                System.out.println("❌ Some benchmarks did not meet their targets. See report for details.");
            } else {
                System.out.println("✅ All benchmarks with targets passed!");
            }
        }
    }

    /**
     * Runs benchmarks in standard mode (no structured capture).
     */
    private static void runStandard(String pattern) throws RunnerException {
        Options options = new OptionsBuilder()
            .include(pattern)
            .warmupIterations(2)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .jvmArgsAppend(
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "-Xms1g",
                "-Xmx1g"
            )
            .shouldDoGC(true)
            .build();

        new Runner(options).run();
    }

    private static List<String> parseLines(String output) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // Should not happen with StringReader
        }
        return lines;
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
