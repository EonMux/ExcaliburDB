package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Cuckoo Filter delete throughput.
 *
 * <p>Runs the CuckooFilterDeleteBenchmark to measure delete throughput at various load factors.</p>
 *
 * <h2>Benchmark Target</h2>
 * <p>High throughput delete operations (targeting 50M+ ops/sec) with:
 * <ul>
 *   <li>O(1) deletion - only two bucket checks needed</li>
 *   <li>Striped locks for concurrent deletion performance</li>
 *   <li>Exact fingerprint deletion - never removes wrong fingerprint</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values expected in range [1M, 500M] ops/sec</li>
 *   <li>Delete returns true if item found, false otherwise (never throws)</li>
 *   <li>After delete(x), contains(x) MUST return false - no false negatives</li>
 *   <li>Delete(x) removes EXACT fingerprint for x, never a different item</li>
 *   <li>Results should show stable variance (&lt; 10% deviation)</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class CuckooFilterDeleteBenchmarkRunner {

    /**
     * Expected minimum throughput in ops/sec.
     * Cuckoo filter deletions should achieve 50M+ ops/sec (similar to lookups).
     */
    public static final double EXPECTED_MIN_OPS_PER_SEC = 50_000_000.0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Cuckoo Filter Delete Benchmark");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Measuring delete throughput at various load factors:");
        System.out.println("  - 25% load factor: Lightly loaded");
        System.out.println("  - 50% load factor: Moderate load");
        System.out.println("  - 75% load factor: Heavy load");
        System.out.println("  - 95% load factor: Near-full");
        System.out.println();
        System.out.println("Expected throughput: >=50M ops/sec");
        System.out.println();
        System.out.println("Benchmark configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include CuckooFilterDeleteBenchmark
                .include(".*CuckooFilterDeleteBenchmark.*")
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
        System.out.println("Delete Benchmark Complete");
        System.out.println("Expected: >=50,000,000 ops/sec");
        System.out.println("========================================");
    }
}
