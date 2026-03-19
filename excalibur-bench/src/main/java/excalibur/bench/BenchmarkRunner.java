package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for ExcaliburDB.
 *
 * <p>Runs benchmarks for Segment, Cuckoo Filter, and other components.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all benchmarks
 * java -jar excalibur-bench.jar
 *
 * # Run specific benchmark
 * java -jar excalibur-bench.jar CuckooFilterInsert
 * </pre>
 *
 * <h2>Targets</h2>
 * <ul>
 *   <li>Segment lookups: >=1B ops/sec (page cache hot)</li>
 *   <li>Cuckoo Filter lookups: High throughput, O(1) average case</li>
 *   <li>Cuckoo Filter inserts: Graceful degradation at high load factors</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values should be in range [1M, 10B] ops/sec</li>
 *   <li>Latency values should be in nanoseconds (1-1000ns)</li>
 *   <li>Results should show stable variance (&lt; 10% deviation)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is designed for single-threaded use as a command-line application.
 * The main method is not intended to be called concurrently. JMH handles
 * thread safety internally for benchmark execution.</p>
 *
 * @author ExcaliburDB
 */
public class BenchmarkRunner {

    /**
     * Target throughput in ops/sec (1 billion = 1_000_000_000)
     */
    public static final double TARGET_OPS_PER_SEC = 1_000_000_000.0;

    public static void main(String[] args) throws Exception {
        // Determine which benchmark to run based on args
        String benchmarkPattern = ".*Benchmark.*"; // Default: run all benchmarks

        if (args.length > 0) {
            String benchmarkName = args[0].toLowerCase();
            if (benchmarkName.contains("segment")) {
                benchmarkPattern = ".*SegmentBenchmark.*";
            } else if (benchmarkName.contains("cuckoo") && benchmarkName.contains("insert")) {
                benchmarkPattern = ".*CuckooFilterInsertBenchmark.*";
            } else if (benchmarkName.contains("cuckoo")) {
                benchmarkPattern = ".*CuckooFilter.*Benchmark.*";
            }
        }

        System.out.println("========================================");
        System.out.println("ExcaliburDB Benchmark Runner");
        System.out.println("Pattern: " + benchmarkPattern);
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running benchmark with configuration:");
        System.out.println("  Warmup: 2 iterations x 1 second");
        System.out.println("  Measurement: 3 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include benchmarks matching the pattern
                .include(benchmarkPattern)
                // Warmup configuration
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                // Fork configuration
                .forks(1)
                // JVM arguments for Panama FFM
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Xms1g",
                    "-Xmx1g"
                )
                // Output format
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Benchmark Complete");
        System.out.println("========================================");
    }
}
