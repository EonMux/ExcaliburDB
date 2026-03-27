package excalibur.bench;

import excalibur.bench.metric.BenchmarkComparison;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs benchmarks and compares results against a baseline.
 *
 * <p>Supports multiple modes:</p>
 * <ul>
 *   <li>Run benchmarks and capture results</li>
 *   <li>Compare current results against a saved baseline</li>
 *   <li>Compare two saved result files</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run benchmarks, capture results, and save as new baseline
 * java -jar excalibur-bench.jar --run --output ./baseline/
 *
 * # Run benchmarks and compare against saved baseline
 * java -jar excalibur-bench.jar --compare --baseline ./baseline/benchmark_results.json
 *
 * # Compare two saved result files
 * java -jar excalibur-bench.jar --diff ./old_results.json ./new_results.json
 * </pre>
 *
 * <h2>Output Files</h2>
 * <ul>
 *   <li>benchmark_results.json - Full results in JSON format</li>
 *   <li>benchmark_comparison.json - Comparison analysis</li>
 *   <li>benchmark_comparison.md - Human-readable comparison report</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class BenchmarkComparisonRunner {

    private static final String DEFAULT_OUTPUT_DIR = "build/benchmark-results";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];

        switch (command) {
            case "--run" -> runAndCapture(args);
            case "--compare" -> runAndCompare(args);
            case "--diff" -> compareFiles(args);
            case "--help" -> printUsage();
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
            }
        }
    }

    /**
     * Runs benchmarks and captures results to files.
     */
    private static void runAndCapture(String[] args) throws Exception {
        String pattern = ".*Benchmark.*";
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--pattern" -> pattern = args[++i];
                case "--output" -> outputDir = args[++i];
            }
        }

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              ExcaliburDB Benchmark Capture Mode                           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Pattern: " + pattern);
        System.out.println("Output:  " + outputDir);
        System.out.println();

        BenchmarkResultSet results = runBenchmarks(pattern);
        writeResults(results, Paths.get(outputDir));

        System.out.println("\n✅ Benchmark results captured successfully!");
        System.out.println("   Results saved to: " + outputDir);
    }

    /**
     * Runs benchmarks and compares against a baseline file.
     */
    private static void runAndCompare(String[] args) throws Exception {
        String pattern = ".*Benchmark.*";
        String baselineFile = null;
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--pattern" -> pattern = args[++i];
                case "--baseline" -> baselineFile = args[++i];
                case "--output" -> outputDir = args[++i];
            }
        }

        if (baselineFile == null) {
            System.err.println("Error: --baseline <file> is required for comparison mode");
            return;
        }

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              ExcaliburDB Benchmark Comparison Mode                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Pattern:  " + pattern);
        System.out.println("Baseline: " + baselineFile);
        System.out.println("Output:   " + outputDir);
        System.out.println();

        // Load baseline
        Path baselinePath = Paths.get(baselineFile);
        if (!Files.exists(baselinePath)) {
            System.err.println("Error: Baseline file not found: " + baselineFile);
            return;
        }

        BenchmarkResultSet baseline = loadResults(baselinePath);
        System.out.println("Loaded baseline: " + baseline.name() + " (" + baseline.resultCount() + " benchmarks)");

        // Run current benchmarks
        System.out.println("\nRunning current benchmarks...");
        BenchmarkResultSet current = runBenchmarks(pattern);

        // Perform comparison
        BenchmarkComparison comparison = BenchmarkComparison.compare(current, baseline);

        // Write outputs
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        writeResults(current, outputPath);
        writeComparison(comparison, outputPath);

        // Print summary
        System.out.println("\n" + comparison.formatSummary());

        // Exit with appropriate code
        if (comparison.hasRegressions()) {
            System.out.println("\n⚠️  Regressions detected! See comparison report for details.");
            System.exit(1);
        } else {
            System.out.println("\n✅ No regressions detected. All benchmarks maintained or improved.");
        }
    }

    /**
     * Compares two saved result files.
     */
    private static void compareFiles(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: --diff requires two file paths");
            System.err.println("Usage: --diff <baseline.json> <current.json> [--output <dir>]");
            return;
        }

        String baselineFile = args[1];
        String currentFile = args[2];
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 3; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            }
        }

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              ExcaliburDB Benchmark Diff Mode                              ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Baseline: " + baselineFile);
        System.out.println("Current:  " + currentFile);
        System.out.println("Output:   " + outputDir);
        System.out.println();

        Path baselinePath = Paths.get(baselineFile);
        Path currentPath = Paths.get(currentFile);

        if (!Files.exists(baselinePath)) {
            System.err.println("Error: Baseline file not found: " + baselineFile);
            return;
        }
        if (!Files.exists(currentPath)) {
            System.err.println("Error: Current file not found: " + currentFile);
            return;
        }

        BenchmarkResultSet baseline = loadResults(baselinePath);
        BenchmarkResultSet current = loadResults(currentPath);

        System.out.println("Baseline: " + baseline.name() + " (" + baseline.resultCount() + " benchmarks)");
        System.out.println("Current:  " + current.name() + " (" + current.resultCount() + " benchmarks)");
        System.out.println();

        // Perform comparison
        BenchmarkComparison comparison = BenchmarkComparison.compare(current, baseline);

        // Write outputs
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);
        writeComparison(comparison, outputPath);

        // Print summary
        System.out.println(comparison.formatSummary());

        // Exit with appropriate code
        if (comparison.hasRegressions()) {
            System.out.println("\n⚠️  Regressions detected!");
            System.exit(1);
        } else {
            System.out.println("\n✅ No regressions detected.");
        }
    }

    /**
     * Runs benchmarks with the given pattern and returns structured results.
     */
    private static BenchmarkResultSet runBenchmarks(String pattern) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos);
        PrintStream originalOut = System.out;

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
            new Runner(options).run();
        } finally {
            Instant endTime = Instant.now();

            String capturedOutput = baos.toString();
            List<String> outputLines = parseLines(capturedOutput);

            List<BenchmarkResult> results = BenchmarkResultCapture.parseConsoleOutput(outputLines);

            return BenchmarkResultSet.builder()
                .name("ExcaliburDB Benchmark Run " + TIMESTAMP_FORMAT.format(startTime))
                .startTime(startTime)
                .endTime(endTime)
                .results(results)
                .build();
        }
    }

    /**
     * Loads benchmark results from a JSON file.
     */
    private static BenchmarkResultSet loadResults(Path jsonPath) throws IOException {
        String content = Files.readString(jsonPath);
        List<BenchmarkResult> results = BenchmarkResultCapture.parseJsonContent(content);

        // Extract metadata from JSON
        String name = extractJsonString(content, "name");
        if (name == null) name = "Loaded Results";

        return BenchmarkResultSet.builder()
            .name(name)
            .results(results)
            .build();
    }

    /**
     * Writes benchmark results to files.
     */
    private static void writeResults(BenchmarkResultSet results, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        Path jsonPath = outputDir.resolve("benchmark_results.json");
        Path mdPath = outputDir.resolve("benchmark_results.md");
        Path csvPath = outputDir.resolve("benchmark_results.csv");
        Path txtPath = outputDir.resolve("benchmark_results.txt");

        BenchmarkReportWriter.writeJson(results, jsonPath);
        BenchmarkReportWriter.writeMarkdown(results, mdPath);
        BenchmarkReportWriter.writeCsv(results, csvPath);
        BenchmarkReportWriter.writeTxt(results, txtPath);

        System.out.println("\n───────────────────────────────────────────────────────────────────────────");
        System.out.println("OUTPUT FILES:");
        System.out.println("───────────────────────────────────────────────────────────────────────────");
        System.out.println("  JSON:     " + jsonPath.toAbsolutePath());
        System.out.println("  Markdown: " + mdPath.toAbsolutePath());
        System.out.println("  CSV:      " + csvPath.toAbsolutePath());
        System.out.println("  Text:     " + txtPath.toAbsolutePath());
    }

    /**
     * Writes comparison results to files.
     */
    private static void writeComparison(BenchmarkComparison comparison, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        // Write comparison JSON
        Path jsonPath = outputDir.resolve("benchmark_comparison.json");
        Files.writeString(jsonPath, comparison.toJson());

        // Write comparison text summary
        Path txtPath = outputDir.resolve("benchmark_comparison.txt");
        Files.writeString(txtPath, comparison.formatSummary());

        System.out.println("\n───────────────────────────────────────────────────────────────────────────");
        System.out.println("COMPARISON FILES:");
        System.out.println("───────────────────────────────────────────────────────────────────────────");
        System.out.println("  JSON: " + jsonPath.toAbsolutePath());
        System.out.println("  Text: " + txtPath.toAbsolutePath());
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

    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos < 0) return null;

        int colonPos = json.indexOf(":", keyPos + searchKey.length());
        if (colonPos < 0) return null;

        int valueStart = json.indexOf("\"", colonPos);
        if (valueStart < 0) return null;

        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd < 0) return null;

        return json.substring(valueStart + 1, valueEnd);
    }

    private static void printUsage() {
        System.out.println("ExcaliburDB Benchmark Runner with Comparison Support");
        System.out.println();
        System.out.println("Usage:");
        System.out.println();
        System.out.println("  Run and capture benchmarks:");
        System.out.println("    java -jar excalibur-bench.jar --run [--pattern PAT] [--output DIR]");
        System.out.println();
        System.out.println("  Run and compare against baseline:");
        System.out.println("    java -jar excalibur-bench.jar --compare --baseline FILE [--pattern PAT] [--output DIR]");
        System.out.println();
        System.out.println("  Compare two saved result files:");
        System.out.println("    java -jar excalibur-bench.jar --diff OLD.json NEW.json [--output DIR]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --pattern PAT    Benchmark pattern to run (default: .*Benchmark.*)");
        System.out.println("  --output DIR     Output directory (default: build/benchmark-results)");
        System.out.println("  --baseline FILE  Baseline JSON file for comparison");
        System.out.println();
        System.out.println("Examples:");
        System.out.println();
        System.out.println("  # Run all benchmarks and save as baseline");
        System.out.println("  java -jar excalibur-bench.jar --run --output ./baseline/");
        System.out.println();
        System.out.println("  # Run and compare against baseline");
        System.out.println("  java -jar excalibur-bench.jar --compare --baseline ./baseline/benchmark_results.json");
        System.out.println();
        System.out.println("  # Compare two saved result files");
        System.out.println("  java -jar excalibur-bench.jar --diff ./old.json ./new.json");
        System.out.println();
        System.out.println("Exit Codes:");
        System.out.println("  0 - Success, no regressions detected");
        System.out.println("  1 - Regressions detected (comparison mode only)");
    }
}
