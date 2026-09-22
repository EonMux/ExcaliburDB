package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Cuckoo Filter lookup throughput.
 *
 * <p>Runs the CuckooFilterBenchmark to measure lookup throughput at various load factors.</p>
 *
 * <h2>Benchmark Target</h2>
 * <p>High throughput lookup operations (targeting 50M+ ops/sec) with:
 * <ul>
 *   <li>Lock-free reads via VarHandle atomic operations</li>
 *   <li>Cache-line aligned bucket storage (64 bytes)</li>
 *   <li>O(1) average case - two bucket checks per lookup</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values expected in range [1M, 500M] ops/sec</li>
 *   <li>Latency values expected in range [2, 500] nanoseconds</li>
 *   <li>False positive rate at 95% load factor should be ≤0.1%</li>
 *   <li>Results should show stable variance (&lt; 10% deviation)</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class CuckooFilterBenchmarkRunner {

    /**
     * Expected minimum throughput in ops/sec.
     * Cuckoo filters with lock-free reads should achieve 50M+ ops/sec.
     */
    public static final double EXPECTED_MIN_OPS_PER_SEC = 50_000_000.0;

    /**
     * Maximum acceptable false positive rate (0.1%).
     * Per spec: "Target FP rate ≤0.1%"
     */
    public static final double MAX_FP_RATE = 0.001;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Cuckoo Filter Benchmark");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Measuring lookup throughput at various load factors:");
        System.out.println("  - 50% load factor: Lightly loaded");
        System.out.println("  - 75% load factor: Moderate load");
        System.out.println("  - 90% load factor: Heavy load");
        System.out.println("  - 95% load factor: Near-full");
        System.out.println();
        System.out.println("Expected throughput: >=50M ops/sec");
        System.out.println("Expected FP rate: <=0.1%");
        System.out.println();
        System.out.println("Benchmark configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include CuckooFilterBenchmark
                .include(".*CuckooFilterBenchmark.*")
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

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Benchmark Complete");
        System.out.println("Expected: >=50,000,000 ops/sec");
        System.out.println("FP rate target: <=0.1%");
        System.out.println("========================================");
    }
}
