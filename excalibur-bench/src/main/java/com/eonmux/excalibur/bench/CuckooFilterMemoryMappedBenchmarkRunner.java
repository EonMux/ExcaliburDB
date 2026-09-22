package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Cuckoo Filter memory-mapped read/write throughput.
 *
 * <p>Runs the CuckooFilterMemoryMappedBenchmark to measure serialization and
 * deserialization performance for memory-mapped I/O operations.</p>
 *
 * <h2>Benchmark Targets</h2>
 * <ul>
 *   <li>MemorySegment write (serialize): Target >=2 GB/s</li>
 *   <li>MemorySegment read (deserialize): Target >=1 GB/s</li>
 *   <li>File-backed write: Target >=500 MB/s (SSD dependent)</li>
 *   <li>File-backed read: Target >=500 MB/s (page cache dependent)</li>
 * </ul>
 *
 * <h2>Filter Sizes Tested</h2>
 * <ul>
 *   <li>10K entries (~350 KB) - small filter, fits in L2 cache</li>
 *   <li>100K entries (~3.5 MB) - medium filter, fits in L3 cache</li>
 *   <li>1M entries (~35 MB) - large filter, exceeds CPU cache</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values expected in range [100 MB/s, 50 GB/s] for memory ops</li>
 *   <li>Round-trip must preserve all data (no false negatives after deserialize)</li>
 *   <li>Filter byte sizes must be consistent with spec:
 *       HEADER(32B) + numBuckets * ((4 * fingerprintBits + 7) / 8)</li>
 * </ul>
 *
 * <h2>Memory Layout (from spec)</h2>
 * <pre>
 * [magic(4B)|version(4B)|fingerprintBits(4B)|numBuckets(8B)|seed(8B)|buckets(numBuckets*4*fingerprintBits/8)]
 * </pre>
 *
 * @author ExcaliburDB
 */
public class CuckooFilterMemoryMappedBenchmarkRunner {

    /**
     * Expected minimum throughput for memory operations in bytes/sec.
     * Modern systems should achieve >=2 GB/s for memory-to-memory copies.
     */
    public static final long EXPECTED_MIN_MEMORY_THROUGHPUT = 2L * 1024 * 1024 * 1024; // 2 GB/s

    /**
     * Expected minimum throughput for file operations in bytes/sec.
     * SSDs should achieve >=500 MB/s for sequential writes.
     */
    public static final long EXPECTED_MIN_FILE_THROUGHPUT = 500L * 1024 * 1024; // 500 MB/s

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Cuckoo Filter Memory-Mapped Benchmark");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Measuring memory-mapped I/O throughput:");
        System.out.println("  - MemorySegment write (serialize): Filter -> Off-heap memory");
        System.out.println("  - MemorySegment read (deserialize): Off-heap memory -> Filter");
        System.out.println("  - File-backed write: Filter -> Memory-mapped file");
        System.out.println("  - File-backed read: Memory-mapped file -> Filter");
        System.out.println("  - Round-trip: Write + Read combined");
        System.out.println();
        System.out.println("Filter sizes tested:");
        System.out.println("  - 10,000 entries (~350 KB) - L2 cache");
        System.out.println("  - 100,000 entries (~3.5 MB) - L3 cache");
        System.out.println("  - 1,000,000 entries (~35 MB) - RAM");
        System.out.println();
        System.out.println("Expected throughput:");
        System.out.println("  - MemorySegment: >=2 GB/s");
        System.out.println("  - File-backed: >=500 MB/s (device dependent)");
        System.out.println();
        System.out.println("Benchmark configuration:");
        System.out.println("  Warmup: 2 iterations x 2 seconds");
        System.out.println("  Measurement: 3 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println("  Heap: 4 GB");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include only memory-mapped benchmarks
                .include(".*CuckooFilterMemoryMappedBenchmark.*")
                // Warmup configuration
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(2))
                // Fork configuration
                .forks(1)
                // JVM arguments for Panama FFM and adequate heap
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Xms4g",
                    "-Xmx4g"
                )
                // Output format
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Benchmark Complete");
        System.out.println();
        System.out.println("Expected throughput targets:");
        System.out.println("  MemorySegment write/read: >=2 GB/s");
        System.out.println("  File-backed: >=500 MB/s");
        System.out.println();
        System.out.println("To calculate throughput in MB/s from ops/sec:");
        System.out.println("  MB/s = (ops/sec * filter_size_bytes) / (1024 * 1024)");
        System.out.println("========================================");
    }
}
