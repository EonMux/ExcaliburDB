package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for MappedFile read/write throughput.
 *
 * <p>Runs benchmarks for memory-mapped I/O operations.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all MappedFile benchmarks
 * ./gradlew :excalibur-bench:runMappedFileBenchmark
 *
 * # Or directly:
 * java -jar excalibur-bench.jar MappedFile
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Sequential reads: GB/sec throughput (memory bandwidth limited)</li>
 *   <li>Sequential writes: GB/sec throughput</li>
 *   <li>Random access: Millions of ops/sec</li>
 *   <li>Primitive operations: Tens of millions of ops/sec</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values should be in GB/sec range for sequential operations</li>
 *   <li>Ops/sec should be in millions range for primitive operations</li>
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
public class MappedFileBenchmarkRunner {

    /**
     * Target sequential throughput in GB/sec (conservative estimate for modern hardware).
     */
    public static final double TARGET_GB_PER_SEC = 2.0;

    /**
     * Target random access ops/sec (millions).
     */
    public static final double TARGET_RANDOM_OPS_PER_SEC = 10_000_000.0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB MappedFile Benchmark Runner");
        System.out.println("Core Primitives Validation");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running benchmark with configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println("  File sizes: 64KB, 1MB, 16MB");
        System.out.println("  Buffer sizes: 64B, 1KB, 4KB, 64KB");
        System.out.println();
        System.out.println("Performance Targets:");
        System.out.println("  Sequential throughput: >= " + TARGET_GB_PER_SEC + " GB/sec");
        System.out.println("  Random access: >= " + (TARGET_RANDOM_OPS_PER_SEC / 1_000_000) + " M ops/sec");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include MappedFile benchmarks
                .include(".*MappedFileBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(2))
                // Fork configuration
                .forks(1)
                // The flags every fork needs, then this benchmark's own
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms2g",
                    "-Xmx2g"
                ))
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
        System.out.println("  - Throughput in ops/sec: Higher is better");
        System.out.println("  - For byte operations: ops/sec * buffer_size = bytes/sec");
        System.out.println("  - Example: 1M ops/sec * 4KB = 4 GB/sec throughput");
        System.out.println();
        System.out.println("Requirements:");
        System.out.println("  [OK] MappedFile read/write throughput measurable");
        System.out.println();
    }
}
