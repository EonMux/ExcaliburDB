package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import com.eonmux.excalibur.bench.benchmark.EndToEndQueryBenchmark;

/**
 * JMH Benchmark Runner for end-to-end query latency (p50/p99).
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
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -cp excalibur-bench.jar com.eonmux.excalibur.bench.EndToEndQueryBenchmarkRunner
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>p50 latency: &lt;= 10ms (for small datasets with 1-4 segments)</li>
 *   <li>p99 latency: &lt;= 50ms (for small datasets with 1-4 segments)</li>
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
 *   <li>p50 &lt; p99 always (percentile ordering)</li>
 *   <li>Results sorted by distance (closest first)</li>
 *   <li>No duplicate entries in results</li>
 * </ul>
 *
 * <h2>Interpreting Results</h2>
 * <p>JMH SampleTime mode produces detailed latency percentiles:</p>
 * <pre>
 * Result "fullQueryPipeline":
 *   N = 5000
 *   mean = 5.234 ±(99.9%) 0.123 ms/op
 *   Histogram, ms/op:
 *     [1.000, 2.000) = 120
 *     [2.000, 5.000) = 2340
 *     [5.000, 10.000) = 2100
 *     [10.000, 20.000) = 400
 *     [20.000, 50.000) = 35
 *     [50.000, 100.000) = 5
 *   Percentiles, ms/op:
 *      p(0.0000) = 1.024 ms/op
 *      p(50.0000) = 4.876 ms/op    &lt;-- p50 latency
 *      p(90.0000) = 8.234 ms/op
 *      p(95.0000) = 12.456 ms/op
 *      p(99.0000) = 23.789 ms/op   &lt;-- p99 latency
 *      p(99.9000) = 45.123 ms/op
 *      p(99.9900) = 67.890 ms/op
 *      p(100.0000) = 98.765 ms/op
 * </pre>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
public class EndToEndQueryBenchmarkRunner {

    /**
     * Target p50 latency in milliseconds.
     * Target: p50 &lt;= 10ms for small datasets.
     */
    public static final double TARGET_P50_MS = 10.0;

    /**
     * Target p99 latency in milliseconds.
     * Target: p99 &lt;= 50ms for small datasets.
     */
    public static final double TARGET_P99_MS = 50.0;

    /**
     * Warmup iterations for JIT compilation stabilization.
     */
    public static final int WARMUP_ITERATIONS = 3;

    /**
     * Measurement iterations for statistical significance.
     */
    public static final int MEASUREMENT_ITERATIONS = 5;

    /**
     * Number of JVM forks for isolation.
     */
    public static final int FORKS = 1;

    /**
     * Main entry point for the benchmark runner.
     *
     * @param args Command line arguments (ignored)
     * @throws RunnerException if benchmark execution fails
     */
    public static void main(String[] args) throws RunnerException {
        printBanner();
        printConfiguration();
        printPerformanceTargets();
        printNotes();

        Options options = new OptionsBuilder()
                // Include the EndToEndQueryBenchmark class
                .include(".*" + EndToEndQueryBenchmark.class.getSimpleName() + ".*")
                // Warmup configuration: 3 iterations x 1 second
                .warmupIterations(WARMUP_ITERATIONS)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration: 5 iterations x 1 second
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .measurementTime(TimeValue.seconds(1))
                // Fork configuration: 1 fork with specified JVM args
                .forks(FORKS)
                // The flags every fork needs, then this benchmark's own preview features
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms2g",
                    "-Xmx2g"
                ))
                // Enable GC between iterations for consistent results
                .shouldDoGC(true)
                // Build the options
                .build();

        try {
            new Runner(options).run();
            printResultsInterpretation();
            printSuccess();
        } catch (RunnerException e) {
            System.err.println("Benchmark execution failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Prints the banner for the benchmark.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     ExcaliburDB End-to-End Query Latency Benchmark (p50/p99) ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Prints the benchmark configuration.
     */
    private static void printConfiguration() {
        System.out.println("┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ Configuration                                                │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Warmup:           %d iterations x 1 second%n", WARMUP_ITERATIONS);
        System.out.printf("│ Measurement:      %d iterations x 1 second%n", MEASUREMENT_ITERATIONS);
        System.out.printf("│ Forks:            %d%n", FORKS);
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│ Test Parameters:                                            │");
        System.out.println("│   Segment counts:    1, 4                                   │");
        System.out.println("│   Vectors/segment:   1000, 10000                            │");
        System.out.println("│   Vector dimensions: 64, 128                                │");
        System.out.println("│   Top-k:             10                                     │");
        System.out.println("│   Timeout:           30000ms                                │");
        System.out.println("│   LLM Scoring:       Disabled (for consistent benchmarking) │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * Prints the performance targets.
     */
    private static void printPerformanceTargets() {
        System.out.println("┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ Performance Targets                                          │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.printf("│ p50 latency:       <= %.1f ms (median latency)%n", TARGET_P50_MS);
        System.out.printf("│ p99 latency:       <= %.1f ms (tail latency)%n", TARGET_P99_MS);
        System.out.println("│                                                             │");
        System.out.println("│ Expected ranges by dataset size:                            │");
        System.out.println("│   Small (1 segment, 1K vectors):    p50 < 5ms, p99 < 20ms   │");
        System.out.println("│   Medium (4 segments, 10K vectors): p50 < 10ms, p99 < 50ms  │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * Prints notes about the benchmark.
     */
    private static void printNotes() {
        System.out.println("┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ Notes                                                       │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│ Latency includes all 7 query pipeline stages:               │");
        System.out.println("│   1. Epoch snapshot                                         │");
        System.out.println("│   2. Segment prioritisation                                 │");
        System.out.println("│   3. Cuckoo filter cascade (orders segments)                │");
        System.out.println("│   4. LSH candidates                                         │");
        System.out.println("│   5. HNSW vector search + tail scan                         │");
        System.out.println("│   6. Full vector rerank                                     │");
        System.out.println("│   7. LLM relevancy scoring (disabled)                       │");
        System.out.println("│                                                             │");
        System.out.println("│ LLM scoring is disabled to ensure consistent, repeatable    │");
        System.out.println("│ measurements without external API latency variance.         │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("Starting benchmark execution...");
        System.out.println("═════════════════════════════════════════════════════════════════");
        System.out.println();
    }

    /**
     * Prints interpretation guidance for results.
     */
    private static void printResultsInterpretation() {
        System.out.println();
        System.out.println("═════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ Results Interpretation                                       │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│ Sample time shows latency per query operation.              │");
        System.out.println("│                                                             │");
        System.out.println("│ Key metrics to check:                                       │");
        System.out.println("│   p(50.0000) = p50 latency (50% of queries faster)          │");
        System.out.println("│   p(99.0000) = p99 latency (99% of queries faster)          │");
        System.out.println("│                                                             │");
        System.out.println("│ Domain Correctness Constraints:                             │");
        System.out.println("│   1. p50 < p99 (strict ordering for non-constant samples)   │");
        System.out.println("│   2. Both values must be non-negative (time >= 0)           │");
        System.out.println("│   3. Both values must be in milliseconds (not ns/us/s)      │");
        System.out.println("│                                                             │");
        System.out.println("│ Lower latency = better user experience.                     │");
        System.out.println("│ For interactive applications, p99 < 100ms is ideal.         │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * Prints the success message with validation criteria.
     */
    private static void printSuccess() {
        System.out.println("┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ Benchmark Complete                                           │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│ Validation criteria:                                        │");
        System.out.printf("│   [ ] p50 latency <= %.1f ms? (check p(50.0000) above)%n", TARGET_P50_MS);
        System.out.printf("│   [ ] p99 latency <= %.1f ms? (check p(99.0000) above)%n", TARGET_P99_MS);
        System.out.println("│   [ ] p50 < p99? (percentiles must be strictly ordered)     │");
        System.out.println("│   [ ] Both values in milliseconds? (not ns/us/s)            │");
        System.out.println("│   [ ] Both values non-negative?                             │");
        System.out.println("│   [ ] No errors or exceptions in output?                    │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              End-to-End Query Benchmark Finished             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
