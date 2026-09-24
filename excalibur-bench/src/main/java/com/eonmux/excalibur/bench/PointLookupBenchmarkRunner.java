package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Point Lookup Throughput.
 *
 * <p>Runs the PointLookupBenchmark to measure the storage engine's lookup throughput with
 * the page cache hot.</p>
 *
 * <h2>Target</h2>
 * <ul>
 *   <li><strong>Single-threaded</strong>: ≥10M lookups/sec</li>
 *   <li><strong>Units</strong>: every score is per lookup, batches included</li>
 *   <li><strong>Multi-threaded scaling</strong>: With N threads, target N × 10M ops/sec</li>
 * </ul>
 *
 * <h2>Why 10M</h2>
 * <ul>
 *   <li>A nanosecond per lookup is three to five CPU cycles - less than a hash table lookup
 *       that touches memory can cost</li>
 *   <li>Modern CPU memory latency: ~50-100ns for L3 cache, ~100-300ns for RAM</li>
 *   <li>Hash table lookup involves: hash compute + slot probe + key compare + result return</li>
 *   <li>A hit also verifies its slot and entry checksums before the value is returned,
 *       and the entry CRC32 is the largest single cost of a lookup</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values: positive ops/sec, typically 1M-100M range</li>
 *   <li>Load factor: 0.7 threshold maintained</li>
 *   <li>Key hashes: positive 64-bit values</li>
 * </ul>
 *
 * <h2>How to Run</h2>
 * <pre>
 * ./gradlew :excalibur-bench:runPointLookupBenchmark
 * </pre>
 *
 * @author ExcaliburDB
 */
public class PointLookupBenchmarkRunner {

    /**
     * Target throughput in ops/sec, single-threaded.
     *
     * <p>Achievable with memory-mapped I/O and the page cache hot.</p>
     */
    public static final double TARGET_OPS_PER_SEC = 10_000_000.0;

    public static void main(String[] args) throws RunnerException {
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     ExcaliburDB Point Lookup Throughput Benchmark                   ║");
        System.out.println("║     Target: ≥10M ops/sec, single-threaded                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
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
        System.out.println("  batchLookup10           - Batch of 10 with single lock (per lookup)");
        System.out.println("  batchLookup100          - Batch of 100 with single lock (per lookup)");
        System.out.println("  batchLookupApi          - Batch of 10 via batch API (per lookup)");
        System.out.println("  lookupMiss              - Miss path (key not found)");
        System.out.println();

        Options options = new OptionsBuilder()
                .include(".*PointLookupBenchmark.*")
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(3))
                .forks(1)
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms2g",
                    "-Xmx2g",
                    "-XX:+UseTransparentHugePages"
                ))
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     Benchmark Complete                                              ║");
        System.out.println("║     Target: ≥10M lookups/sec, every score per lookup                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }
}
