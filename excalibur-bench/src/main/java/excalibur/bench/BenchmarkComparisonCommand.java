package excalibur.bench;

import excalibur.bench.metric.BenchmarkComparison;
import excalibur.bench.metric.BenchmarkResult;
import excalibur.bench.metric.BenchmarkResultCapture;
import excalibur.bench.metric.BenchmarkResultSet;
import excalibur.bench.report.BenchmarkReportWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * The benchmark capture-and-compare workflow, with the measurement pushed behind an
 * interface.
 *
 * <p>Everything a regression gate actually decides - which arguments were given, what
 * the baseline said, whether the current numbers are worse, and what exit code CI
 * therefore sees - lives here rather than in a {@code main} method, so it can be
 * exercised without spending minutes running JMH.</p>
 *
 * <h2>Exit codes</h2>
 * <p>Only {@link #EXIT_OK} means "measured, compared and fine". Every other outcome,
 * including one this command could not evaluate, is non-zero: a gate that cannot tell
 * "nothing ran" from "nothing regressed" is not a gate.</p>
 *
 * <h2>Thread safety</h2>
 * <p>One instance per invocation; not thread-safe.</p>
 */
public final class BenchmarkComparisonCommand {

    /** Measured, compared, and no regression found. */
    public static final int EXIT_OK = 0;

    /** A regression was detected. */
    public static final int EXIT_REGRESSION = 1;

    /** The command could not be carried out: bad arguments, or a missing input. */
    public static final int EXIT_USAGE = 2;

    private static final String DEFAULT_OUTPUT_DIR = "build/benchmark-results";
    private static final String DEFAULT_PATTERN = ".*Benchmark.*";

    /**
     * Produces a fresh set of measurements.
     *
     * <p>In production this runs JMH. Injecting it is what lets the surrounding
     * decision logic be tested at all: a real run takes minutes and its numbers move
     * between runs, so neither the pass nor the fail path could otherwise be asserted.</p>
     */
    @FunctionalInterface
    public interface BenchmarkRun {

        /**
         * Runs every benchmark matching a pattern.
         *
         * @param pattern the JMH include pattern
         * @return the measurements
         * @throws Exception if the run fails
         */
        BenchmarkResultSet measure(String pattern) throws Exception;
    }

    /** Raised for an argument list this command cannot act on. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private final BenchmarkRun benchmarkRun;
    private final PrintStream out;
    private final PrintStream err;

    /**
     * Creates a command.
     *
     * @param benchmarkRun how to obtain fresh measurements
     * @param out the stream for the report
     * @param err the stream for failures
     */
    public BenchmarkComparisonCommand(BenchmarkRun benchmarkRun, PrintStream out, PrintStream err) {
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
        if (args.length == 0) {
            printUsage();
            return EXIT_USAGE;
        }
        try {
            return switch (args[0]) {
                case "--run" -> runAndCapture(args);
                case "--compare" -> runAndCompare(args);
                case "--diff" -> compareFiles(args);
                case "--help" -> {
                    printUsage();
                    yield EXIT_OK;
                }
                default -> {
                    err.println("Unknown command: " + args[0]);
                    printUsage();
                    yield EXIT_USAGE;
                }
            };
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        }
    }

    private int runAndCapture(String[] args) throws Exception {
        Options options = Options.parse(args, 1);
        banner("ExcaliburDB Benchmark Capture Mode");
        out.println("Pattern: " + options.pattern());
        out.println("Output:  " + options.outputDir());
        out.println();

        BenchmarkResultSet results = measure(options.pattern());
        if (results.resultCount() == 0) {
            err.println("FAILED: the run produced no results for pattern '" + options.pattern()
                + "'. Nothing was measured, so nothing can be reported as captured.");
            return EXIT_USAGE;
        }

        writeResults(results, Paths.get(options.outputDir()));
        out.println("\nBenchmark results captured. Saved to: " + options.outputDir());
        return EXIT_OK;
    }

    private int runAndCompare(String[] args) throws Exception {
        Options options = Options.parse(args, 1);
        if (options.baseline() == null) {
            throw new UsageException("--baseline <file> is required for comparison mode");
        }

        banner("ExcaliburDB Benchmark Comparison Mode");
        out.println("Pattern:  " + options.pattern());
        out.println("Baseline: " + options.baseline());
        out.println("Output:   " + options.outputDir());
        out.println();

        BenchmarkResultSet baseline = loadRequired(Paths.get(options.baseline()), "Baseline");
        out.println("Loaded baseline: " + baseline.name()
            + " (" + baseline.resultCount() + " benchmarks)");

        out.println("\nRunning current benchmarks...");
        BenchmarkResultSet current = measure(options.pattern());
        if (current.resultCount() == 0) {
            err.println("FAILED: the run produced no results for pattern '" + options.pattern()
                + "'. There is nothing to compare against the baseline.");
            return EXIT_USAGE;
        }

        Path outputPath = Paths.get(options.outputDir());
        writeResults(current, outputPath);
        return report(BenchmarkComparison.compare(current, baseline), outputPath);
    }

    private int compareFiles(String[] args) throws Exception {
        if (args.length < 3 || args[1].startsWith("--") || args[2].startsWith("--")) {
            throw new UsageException("--diff requires two file paths: "
                + "--diff <baseline.json> <current.json> [--output <dir>]");
        }
        Options options = Options.parse(args, 3);

        banner("ExcaliburDB Benchmark Diff Mode");
        out.println("Baseline: " + args[1]);
        out.println("Current:  " + args[2]);
        out.println("Output:   " + options.outputDir());
        out.println();

        BenchmarkResultSet baseline = loadRequired(Paths.get(args[1]), "Baseline");
        BenchmarkResultSet current = loadRequired(Paths.get(args[2]), "Current");

        out.println("Baseline: " + baseline.name() + " (" + baseline.resultCount() + " benchmarks)");
        out.println("Current:  " + current.name() + " (" + current.resultCount() + " benchmarks)");
        out.println();

        return report(BenchmarkComparison.compare(current, baseline),
            Paths.get(options.outputDir()));
    }

    /**
     * Prints the comparison, writes it out, and turns it into an exit code.
     */
    private int report(BenchmarkComparison comparison, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        writeComparison(comparison, outputDir);
        out.println(comparison.formatSummary());

        if (comparison.hasRegressions()) {
            out.println("\nRegressions detected. See the comparison report for details.");
            return EXIT_REGRESSION;
        }
        out.println("\nNo regressions detected. All benchmarks maintained or improved.");
        return EXIT_OK;
    }

    private BenchmarkResultSet measure(String pattern) throws Exception {
        return Objects.requireNonNull(
            benchmarkRun.measure(pattern), "benchmark run returned no result set");
    }

    private BenchmarkResultSet loadRequired(Path path, String what) throws Exception {
        if (!Files.exists(path)) {
            throw new UsageException(what + " file not found: " + path);
        }
        return loadResults(path);
    }

    /**
     * Reads a result set back out of a report this tool wrote.
     */
    private static BenchmarkResultSet loadResults(Path jsonPath) throws IOException {
        String content = Files.readString(jsonPath);
        List<BenchmarkResult> results = BenchmarkResultCapture.parseJsonContent(content);
        String name = extractJsonString(content, "name");
        return BenchmarkResultSet.builder()
            .name(name == null ? "Loaded Results" : name)
            .results(results)
            .build();
    }

    private void writeResults(BenchmarkResultSet results, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve("benchmark_results.json");
        Path markdown = outputDir.resolve("benchmark_results.md");
        Path csv = outputDir.resolve("benchmark_results.csv");
        Path text = outputDir.resolve("benchmark_results.txt");

        BenchmarkReportWriter.writeJson(results, json);
        BenchmarkReportWriter.writeMarkdown(results, markdown);
        BenchmarkReportWriter.writeCsv(results, csv);
        BenchmarkReportWriter.writeTxt(results, text);

        out.println();
        out.println("OUTPUT FILES:");
        out.println("  JSON:     " + json.toAbsolutePath());
        out.println("  Markdown: " + markdown.toAbsolutePath());
        out.println("  CSV:      " + csv.toAbsolutePath());
        out.println("  Text:     " + text.toAbsolutePath());
    }

    private void writeComparison(BenchmarkComparison comparison, Path outputDir) throws IOException {
        Path json = outputDir.resolve("benchmark_comparison.json");
        Path text = outputDir.resolve("benchmark_comparison.txt");
        Files.writeString(json, comparison.toJson());
        Files.writeString(text, comparison.formatSummary());

        out.println();
        out.println("COMPARISON FILES:");
        out.println("  JSON: " + json.toAbsolutePath());
        out.println("  Text: " + text.toAbsolutePath());
    }

    private void banner(String title) {
        out.println("=".repeat(76));
        out.println(title);
        out.println("=".repeat(76));
        out.println();
    }

    /**
     * Reads the first string value of a named key. Enough for the run name in a
     * report this tool wrote itself; not a general JSON reader.
     */
    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos < 0) {
            return null;
        }
        int colonPos = json.indexOf(':', keyPos + searchKey.length());
        if (colonPos < 0) {
            return null;
        }
        int valueStart = json.indexOf('"', colonPos);
        if (valueStart < 0) {
            return null;
        }
        int valueEnd = json.indexOf('"', valueStart + 1);
        return valueEnd < 0 ? null : json.substring(valueStart + 1, valueEnd);
    }

    private void printUsage() {
        out.println("""
            ExcaliburDB Benchmark Runner with Comparison Support

            Usage:
              --run [--pattern PAT] [--output DIR]
                  Run the benchmarks and write the results as a new baseline.

              --compare --baseline FILE [--pattern PAT] [--output DIR]
                  Run the benchmarks and compare them against a saved baseline.

              --diff OLD.json NEW.json [--output DIR]
                  Compare two saved result files without running anything.

            Options:
              --pattern PAT    Benchmark pattern to run (default: .*Benchmark.*)
              --output DIR     Output directory (default: build/benchmark-results)
              --baseline FILE  Baseline JSON file for comparison

            Exit codes:
              0 - measured, compared, no regressions
              1 - regressions detected
              2 - the command could not be carried out (bad arguments, missing input,
                  or a run that measured nothing); never read this as a pass""");
    }

    /**
     * The optional flags, parsed from the argument list.
     *
     * @param pattern the JMH include pattern
     * @param outputDir where reports are written
     * @param baseline the baseline file, or null when none was given
     */
    private record Options(String pattern, String outputDir, String baseline) {

        /**
         * Parses the flags following the positional arguments.
         *
         * <p>A flag whose value is missing is rejected outright. Reading past the end
         * of the array instead throws {@link ArrayIndexOutOfBoundsException} out of
         * {@code main}, which tells the caller nothing about which flag was wrong.</p>
         */
        static Options parse(String[] args, int from) throws UsageException {
            String pattern = DEFAULT_PATTERN;
            String outputDir = DEFAULT_OUTPUT_DIR;
            String baseline = null;

            for (int i = from; i < args.length; i++) {
                switch (args[i]) {
                    case "--pattern" -> pattern = value(args, ++i, "--pattern");
                    case "--output" -> outputDir = value(args, ++i, "--output");
                    case "--baseline" -> baseline = value(args, ++i, "--baseline");
                    default -> throw new UsageException("unknown option: " + args[i]);
                }
            }
            return new Options(pattern, outputDir, baseline);
        }

        private static String value(String[] args, int index, String flag) throws UsageException {
            if (index >= args.length) {
                throw new UsageException(flag + " requires a value");
            }
            return args[index];
        }
    }
}
