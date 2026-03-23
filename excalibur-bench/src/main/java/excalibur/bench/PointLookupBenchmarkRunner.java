package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Point Lookup Throughput.
 *
 * <p>Runs the PointLookupBenchmark to validate the Phase 2 storage engine
 * performance target of ≥1 billion lookups/sec.</p>
 *
 * <h2>Target</h2>
 * <p>≥1,000,000,000 ops/sec (1 billion lookups per second)</p>
 *
 * <h2>How to Run</h2>
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -cp build/classes/java/main:... \
 *      excalibur.bench.PointLookupBenchmarkRunner
 * </pre>
 *
 * @author ExcaliburDB
 */
public class PointLookupBenchmarkRunner {

    /**
     * Target throughput in ops/sec (1 billion)
     */
    public static final double TARGET_OPS_PER_SEC = 1_000_000_000.0;

    public static void main(String[] args) throws RunnerException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     ExcaliburDB Point Lookup Throughput Benchmark            ║");
        System.out.println("║     Target: ≥1,000,000,000 ops/sec                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 3 seconds");
        System.out.println("  Forks: 1");
        System.out.println("  JVM: 2GB heap, transparent huge pages enabled");
        System.out.println();
        System.out.println("Benchmarks:");
        System.out.println("  sequentialLookupFast    - Lock-free, pre-computed hash (FASTEST)");
        System.out.println("  randomLookupFast        - Lock-free random access");
        System.out.println("  sequentialLookupWithLock- Full path with lock");
        System.out.println("  batchLookup10           - Batch of 10 with single lock");
        System.out.println("  batchLookup100          - Batch of 100 with single lock");
        System.out.println("  batchLookupApi          - Using batch API");
        System.out.println("  lookupMiss              - Miss path (key not found)");
        System.out.println();

        Options options = new OptionsBuilder()
                .include(".*PointLookupBenchmark.*")
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(3))
                .forks(1)
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Xms2g",
                    "-Xmx2g",
                    "-XX:+UseTransparentHugePages"
                )
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     Benchmark Complete                                       ║");
        System.out.println("║     Check results against target: ≥1,000,000,000 ops/sec     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
