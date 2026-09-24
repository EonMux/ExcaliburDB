package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Cuckoo Filter False Positive Rate measurement.
 *
 * <p>Runs the CuckooFilterFPRateBenchmark to measure actual false positive rate
 * at various load factors and validates against the ≤0.1% specification.</p>
 *
 * <h2>Benchmark Target</h2>
 * <p>Measures actual false positive rate with:
 * <ul>
 *   <li>Large sample size (100K+ queries) for statistical significance</li>
 *   <li>Multiple load factors (50%, 75%, 90%, 95%)</li>
 *   <li>13-bit fingerprints (specification requirement)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>FP rate values must be in range [0.0, 1.0]</li>
 *   <li>FP rate should be ≤0.001 (0.1%) at 95% load factor</li>
 *   <li>No false negatives ever (validated during setup)</li>
 *   <li>Results are reported as percentage (e.g., 0.09%)</li>
 * </ul>
 *
 * <h2>Expected Output</h2>
 * <p>The benchmark reports:</p>
 * <ul>
 *   <li>Measured FP rate at each load factor</li>
 *   <li>Comparison to theoretical expected rate</li>
 *   <li>PASS/FAIL status for spec compliance (≤0.1%)</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class CuckooFilterFPRateBenchmarkRunner {

    /**
     * Maximum acceptable false positive rate for the Cuckoo filter (0.1%).
     */
    public static final double MAX_ACCEPTABLE_FP_RATE = 0.001; // 0.1%

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Cuckoo Filter FP Rate Benchmark");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Measuring actual false positive rate at various load factors:");
        System.out.println("  - 50% load factor: Lightly loaded");
        System.out.println("  - 75% load factor: Moderate load");
        System.out.println("  - 90% load factor: Heavy load");
        System.out.println("  - 95% load factor: Near-full (spec validation point)");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  - Fingerprint bits: 13 (per spec)");
        System.out.println("  - Capacity: 2,000,000 entries");
        System.out.println("  - Queries per iteration: 100,000");
        System.out.println();
        System.out.println("Target: FP rate <= 0.1% (0.001)");
        System.out.println();
        System.out.println("Benchmark configuration:");
        System.out.println("  Warmup: 2 iterations x 1 second");
        System.out.println("  Measurement: 5 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include FP Rate benchmark
                .include(".*CuckooFilterFPRateBenchmark.*")
                // Warmup configuration
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(1))
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
        System.out.println("FP Rate Benchmark Complete");
        System.out.println("========================================");
        System.out.println();
        System.out.println("RESULT INTERPRETATION:");
        System.out.println("  - The 'measureFPRateBatch' result shows false positive count per 100K queries");
        System.out.println("  - FP rate = false_positives / 100000");
        System.out.println("  - Target: <= 100 false positives per 100K queries (0.1%)");
        System.out.println("  - Check 'SPEC COMPLIANCE' in teardown output for pass/fail status");
        System.out.println();
        System.out.println("EXPECTED RANGES:");
        System.out.println("  - 50% load: ~20-50 FP per 100K (0.02-0.05%)");
        System.out.println("  - 75% load: ~40-80 FP per 100K (0.04-0.08%)");
        System.out.println("  - 90% load: ~60-100 FP per 100K (0.06-0.10%)");
        System.out.println("  - 95% load: ~80-120 FP per 100K (0.08-0.12%)");
        System.out.println("========================================");
    }
}
