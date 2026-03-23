package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import excalibur.bench.benchmark.TailScanBenchmark;

/**
 * JMH Benchmark Runner for tail scan throughput.
 *
 * <p>This runner executes the tail scan benchmark to measure brute-force
 * scan throughput over write log entries.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all tail scan benchmarks
 * ./gradlew :excalibur-bench:runTailScanBenchmark
 *
 * # Or run directly
 * java -jar excalibur-bench.jar tail-scan
 * </pre>
 *
 * <h2>Performance Target</h2>
 * <p>Tail scan should process entries at high throughput (millions/sec)</p>
 *
 * <h2>Configuration Parameters</h2>
 * <ul>
 *   <li>Tail sizes: 100, 1000, 10000, 50000 entries</li>
 *   <li>Vector dimensions: 64, 128</li>
 *   <li>Top-k: 10</li>
 *   <li>Backpressure limit: 100,000 entries</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Distance values in valid ranges (cosine ∈ [-1,1], L2 ∈ [0,∞))</li>
 *   <li>Results sorted by distance (closest first)</li>
 *   <li>Backpressure enforced at 100K entries</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class TailScanBenchmarkRunner {

    /**
     * Target throughput: millions of entries per second.
     */
    public static final double TARGET_ENTRIES_PER_SEC = 1_000_000.0;

    public static void main(String[] args) throws RunnerException {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Tail Scan Benchmark Runner");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running tail scan benchmarks with configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println();
        System.out.println("Test Parameters:");
        System.out.println("  Tail sizes: 100, 1000, 10000, 50000 entries");
        System.out.println("  Vector dimensions: 64, 128");
        System.out.println("  Top-k: 10");
        System.out.println();
        System.out.println("Performance target:");
        System.out.println("  Throughput: >= " + (long) TARGET_ENTRIES_PER_SEC + " entries/sec");
        System.out.println();
        System.out.println("Note: Tail scan is a brute-force scan over un-integrated");
        System.out.println("      write log entries. Higher throughput indicates better");
        System.out.println("      query completeness during live indexing.");
        System.out.println();
        System.out.println("========================================");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include TailScanBenchmark
                .include(".*TailScanBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(2))
                // Fork configuration
                .forks(1)
                // JVM arguments for Panama FFM
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Xms2g",
                    "-Xmx2g"
                )
                // Output format
                .shouldDoGC(true)
                .build();

        try {
            new Runner(options).run();
            System.out.println();
            System.out.println("========================================");
            System.out.println("Tail Scan Benchmark Complete");
            System.out.println("========================================");
            System.out.println();
            System.out.println("INTERPRETATION:");
            System.out.println("  - Throughput is measured in operations per second");
            System.out.println("  - Each operation scans the entire tail region");
            System.out.println("  - Higher throughput = faster tail scan = better query latency");
            System.out.println();
            System.out.println("Expected results:");
            System.out.println("  - Small tails (100-1000 entries): Very high throughput");
            System.out.println("  - Medium tails (10000 entries): Good throughput");
            System.out.println("  - Large tails (50000 entries): Moderate throughput");
            System.out.println();
        } catch (RunnerException e) {
            System.err.println("Benchmark execution failed: " + e.getMessage());
            throw e;
        }
    }
}
