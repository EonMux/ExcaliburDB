package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for XxHash64 hash function speed benchmarks.
 *
 * <p>Validates Phase 1 requirement: "Benchmark hash function speed via JMH."</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all hash benchmarks
 * ./gradlew :excalibur-bench:runHashBenchmark
 *
 * # Or directly:
 * java -jar excalibur-bench.jar HashBenchmark
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Small keys (8-32 bytes): > 500 million hashes/sec</li>
 *   <li>Medium keys (64-256 bytes): > 300 million hashes/sec</li>
 *   <li>Large keys (1KB-4KB): > 200 million hashes/sec</li>
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
 * <p>This class is designed for single-threaded use as a command-line application.</p>
 *
 * @author ExcaliburDB
 */
public class HashBenchmarkRunner {

    /**
     * Expected minimum throughput for small keys (8-32 bytes).
     */
    public static final long MIN_THROUGHPUT_SMALL_KEYS = 500_000_000L;

    /**
     * Expected minimum throughput for medium keys (64-256 bytes).
     */
    public static final long MIN_THROUGHPUT_MEDIUM_KEYS = 300_000_000L;

    /**
     * Expected minimum throughput for large keys (1KB+).
     */
    public static final long MIN_THROUGHPUT_LARGE_KEYS = 200_000_000L;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Hash Function Speed Benchmark");
        System.out.println("XxHash64 Implementation");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Performance Targets:");
        System.out.println("  Small keys (8-32 bytes):  >= " + MIN_THROUGHPUT_SMALL_KEYS + " hashes/sec");
        System.out.println("  Medium keys (64-256 bytes): >= " + MIN_THROUGHPUT_MEDIUM_KEYS + " hashes/sec");
        System.out.println("  Large keys (1KB+):        >= " + MIN_THROUGHPUT_LARGE_KEYS + " hashes/sec");
        System.out.println();
        System.out.println("Running benchmark with configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 3 seconds");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include only HashBenchmark
                .include(".*HashBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(3))
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
        System.out.println();
        System.out.println("Interpreting Results:");
        System.out.println("  - 'ops/sec' column shows throughput (hashes per second)");
        System.out.println("  - Higher is better");
        System.out.println("  - Compare against targets above");
        System.out.println();
        System.out.println("Data Rate Calculation:");
        System.out.println("  MB/sec = (ops/sec * key_size_bytes) / 1,000,000");
        System.out.println("  Example: 500M ops/sec * 64 bytes = 32,000 MB/sec = 32 GB/sec");
    }
}
