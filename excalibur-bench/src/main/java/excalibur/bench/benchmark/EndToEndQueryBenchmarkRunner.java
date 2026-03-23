package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import excalibur.bench.benchmark.EndToEndQueryBenchmark;

/**
 * JMH Benchmark Runner for end-to-end query latency.
 *
 * <p>This runner executes the end-to-end query benchmark to measure
 * full pipeline latency from request to response.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all end-to-end query benchmarks
 * ./gradlew :excalibur-bench:runEndToEndQueryBenchmark
 *
 * # Or run directly
 * java -jar excalibur-bench.jar e2e-query
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>p50 latency: <= 10ms (for small datasets)</li>
 *   <li>p99 latency: <= 50ms (for small datasets)</li>
 * </ul>
 *
 * <h2>Configuration Parameters</h2>
 * <ul>
 *   <li>Segment counts: 1, 4</li>
 *   <li>Vectors per segment: 1000, 10000</li>
 *   <li>Vector dimensions: 64, 128</li>
 *   <li>Top-k: 10</li>
 *   <li>Timeout: 1000ms</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Latency values are non-negative milliseconds</li>
 *   <li>Results sorted by distance (closest first)</li>
 *   <li>No duplicate entries in results</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class EndToEndQueryBenchmarkRunner {

    /**
     * Target p50 latency in milliseconds.
     */
    public static final double TARGET_P50_MS = 10.0;

    /**
     * Target p99 latency in milliseconds.
     */
    public static final double TARGET_P99_MS = 50.0;

    public static void main(String[] args) throws RunnerException {
        System.out.println("========================================");
        System.out.println("ExcaliburDB End-to-End Query Benchmark Runner");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running end-to-end query benchmarks with configuration:");
        System.out.println("  Warmup: 3 iterations x 1 second");
        System.out.println("  Measurement: 5 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();
        System.out.println("Test Parameters:");
        System.out.println("  Segment counts: 1, 4");
        System.out.println("  Vectors per segment: 1000, 10000");
        System.out.println("  Vector dimensions: 64, 128");
        System.out.println("  Top-k: 10");
        System.out.println("  Timeout: 1000ms");
        System.out.println();
        System.out.println("Performance targets:");
        System.out.println("  p50 latency: <= " + TARGET_P50_MS + "ms");
        System.out.println("  p99 latency: <= " + TARGET_P99_MS + "ms");
        System.out.println();
        System.out.println("Note: Latency includes all query pipeline stages:");
        System.out.println("      Epoch snapshot, Cuckoo filter cascade, LSH pre-filter,");
        System.out.println("      HNSW search, tail scan, reranking, and optional LLM scoring.");
        System.out.println();
        System.out.println("========================================");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include EndToEndQueryBenchmark
                .include(".*EndToEndQueryBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(1))
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
            System.out.println("End-to-End Query Benchmark Complete");
            System.out.println("========================================");
            System.out.println();
            System.out.println("INTERPRETATION:");
            System.out.println("  - Sample time shows latency per query");
            System.out.println("  - Lower latency = better user experience");
            System.out.println("  - p50 = median latency (50% of queries faster)");
            System.out.println("  - p99 = tail latency (99% of queries faster)");
            System.out.println();
            System.out.println("Expected results:");
            System.out.println("  - Small datasets (1 segment, 1K vectors): p50 < 5ms, p99 < 20ms");
            System.out.println("  - Medium datasets (4 segments, 10K vectors): p50 < 10ms, p99 < 50ms");
            System.out.println();
            System.out.println("Validation:");
            System.out.println("  - Check that p50 <= " + TARGET_P50_MS + "ms");
            System.out.println("  - Check that p99 <= " + TARGET_P99_MS + "ms");
            System.out.println();
        } catch (RunnerException e) {
            System.err.println("Benchmark execution failed: " + e.getMessage());
            throw e;
        }
    }
}
