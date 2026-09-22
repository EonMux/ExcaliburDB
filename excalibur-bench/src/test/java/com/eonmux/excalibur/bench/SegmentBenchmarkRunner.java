package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Test Runner for Segment Benchmark.
 *
 * <p>Runs the SegmentBenchmark to verify the ≥1B lookups/sec target.</p>
 *
 * <h2>Target</h2>
 * <p>≥1 billion lookups/sec (page cache hot)</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values should be in range [1M, 10B] ops/sec</li>
 *   <li>Latency values should be in nanoseconds (1-1000ns)</li>
 *   <li>Results should show stable variance (&lt; 10% deviation)</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class SegmentBenchmarkRunner {

    /**
     * Target throughput in ops/sec (1 billion = 1_000_000_000)
     */
    public static final double TARGET_OPS_PER_SEC = 1_000_000_000.0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Segment Benchmark Runner");
        System.out.println("Target: >=1B lookups/sec (page cache hot)");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running benchmark with configuration:");
        System.out.println("  Warmup: 2 iterations x 1 second");
        System.out.println("  Measurement: 3 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include only SegmentBenchmark
                .include(".*SegmentBenchmark.*")
                // Warmup configuration
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                // Fork configuration
                .forks(1)
                // The flags every fork needs, then this benchmark's own
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms1g",
                    "-Xmx1g"
                ))
                // Output format
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Benchmark Complete");
        System.out.println("Target: >=1,000,000,000 ops/sec");
        System.out.println("========================================");
    }
}
