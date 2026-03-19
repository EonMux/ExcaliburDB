package excalibur.bench.benchmark;

import excalibur.filter.CuckooFilter;
import io.excalibur.core.mapped.MappedFile;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Cuckoo Filter memory-mapped read/write throughput.
 *
 * <h2>Purpose</h2>
 * <p>Measures the throughput of serialization and deserialization operations
 * for Cuckoo Filters via memory-mapped I/O, validating the Phase 3 requirement
 * for high-performance memory-mappable probabilistic structures.</p>
 *
 * <h2>Benchmark Scenarios</h2>
 * <ul>
 *   <li>MemorySegment write (serialize) - writing filter to off-heap memory</li>
 *   <li>MemorySegment read (deserialize) - reading filter from off-heap memory</li>
 *   <li>File-backed write - writing filter to memory-mapped file</li>
 *   <li>File-backed read - reading filter from memory-mapped file</li>
 *   <li>Round-trip (write then read) - combined serialization/deserialization</li>
 * </ul>
 *
 * <h2>Filter Sizes Tested</h2>
 * <ul>
 *   <li>10K entries (~350 KB) - small filter, fits in L2 cache</li>
 *   <li>100K entries (~3.5 MB) - medium filter, fits in L3 cache</li>
 *   <li>1M entries (~35 MB) - large filter, exceeds CPU cache</li>
 *   <li>10M entries (~350 MB) - very large filter, stresses memory bandwidth</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>Filter sizes in bytes must be positive and reasonable (1KB to 1GB)</li>
 *   <li>Throughput values expected in range [100 MB/s, 50 GB/s] for memory ops</li>
 *   <li>Round-trip must preserve all data (no false negatives after deserialize)</li>
 *   <li>Load factor for test filters: 0.75 (realistic operating point)</li>
 * </ul>
 *
 * <h2>Memory Layout (from spec)</h2>
 * <pre>
 * [magic(4B)|version(4B)|fingerprintBits(4B)|numBuckets(8B)|seed(8B)|buckets(numBuckets*4*fingerprintBits/8)]
 * </pre>
 * <p>For 13-bit fingerprints with 4 fingerprints per bucket: 7 bytes per bucket</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The benchmark state is managed by JMH's @State annotations which
 * provide proper isolation between threads. Resources like Arena and MappedFile
 * are properly managed in setup/teardown lifecycle methods.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xms4g", "-Xmx4g"})
public class CuckooFilterMemoryMappedBenchmark {

    // ==================== Benchmark Parameters ====================

    /**
     * Filter sizes (number of entries) to benchmark.
     * Domain constraint: must be positive integers.
     * Sizes chosen to test different cache hierarchy levels:
     * - 10000: ~350KB, fits in L2 cache
     * - 100000: ~3.5MB, fits in L3 cache
     * - 1000000: ~35MB, exceeds CPU cache
     * - 10000000: ~350MB, stresses memory bandwidth
     */
    @Param({"10000", "100000", "1000000"})
    public int filterCapacity;

    /**
     * Load factor for pre-populated filters.
     * Domain constraint: [0.0, 1.0]
     * 0.75 is a realistic operating point with good performance characteristics.
     */
    @Param({"0.75"})
    public double loadFactor;

    /**
     * Number of bits per fingerprint.
     * 13 bits provides ≤0.1% false positive rate at 95% load factor.
     * Domain constraint: [8, 16]
     */
    @Param({"13"})
    public int fingerprintBits;

    // ==================== Benchmark State ====================

    /**
     * The source filter to be serialized.
     * Pre-populated to the specified load factor.
     */
    private CuckooFilter sourceFilter;

    /**
     * Pre-allocated MemorySegment for serialization benchmarks.
     * Sized to hold the filter exactly.
     */
    private MemorySegment targetSegment;

    /**
     * Shared arena for the target segment.
     * Kept alive for the duration of the benchmark.
     */
    private Arena sharedArena;

    /**
     * Keys that were inserted into the source filter.
     * Used to verify deserialization correctness.
     */
    private byte[][] insertedKeys;

    /**
     * Random number generator with fixed seed for reproducibility.
     */
    private static final Random RANDOM = new Random(42);

    /**
     * Temporary directory for file-backed benchmarks.
     * Created manually in setup() since @TempDir is JUnit-specific.
     */
    private Path tempDir;

    /**
     * Path for the test file used in file-backed benchmarks.
     */
    private Path testFilePath;

    /**
     * Pre-populated filter written to file for read benchmarks.
     */
    private MappedFile preWrittenFile;

    // ==================== Setup and Teardown ====================

    /**
     * Initializes the benchmark state.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Filter capacity is validated as positive</li>
     *   <li>Load factor is clamped to [0.0, 1.0]</li>
     *   <li>Filter is pre-populated to exact target load factor</li>
     *   <li>Memory segment is sized exactly to filter byte size</li>
     * </ul>
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Validate domain constraints
        if (filterCapacity <= 0) {
            throw new IllegalArgumentException(
                "Filter capacity must be positive, got: " + filterCapacity);
        }
        if (loadFactor < 0.0 || loadFactor > 1.0) {
            throw new IllegalArgumentException(
                "Load factor must be in [0.0, 1.0], got: " + loadFactor);
        }
        if (fingerprintBits < 8 || fingerprintBits > 16) {
            throw new IllegalArgumentException(
                "Fingerprint bits must be in [8, 16], got: " + fingerprintBits);
        }

        // Create and populate source filter
        sourceFilter = CuckooFilter.create(filterCapacity, fingerprintBits);

        // Calculate number of entries for target load factor
        long targetEntries = (long) (sourceFilter.capacity() * loadFactor);

        // Pre-generate keys and insert into filter
        insertedKeys = new byte[(int) targetEntries][];
        for (int i = 0; i < targetEntries; i++) {
            insertedKeys[i] = generateKey(i);
            if (!sourceFilter.insert(insertedKeys[i])) {
                throw new IllegalStateException(
                    "Failed to insert key at index " + i + " (load factor would be " +
                    ((double) i / sourceFilter.capacity()) + ")");
            }
        }

        // Verify actual load factor matches target (within 5% tolerance)
        double actualLoadFactor = sourceFilter.loadFactor();
        if (Math.abs(actualLoadFactor - loadFactor) > 0.05) {
            throw new IllegalStateException(
                "Load factor mismatch. Target: " + loadFactor +
                ", Actual: " + actualLoadFactor);
        }

        // Create shared arena and pre-allocate target segment
        sharedArena = Arena.ofShared();
        targetSegment = sharedArena.allocate(sourceFilter.byteSize());

        // Create temp directory for file-backed benchmarks
        tempDir = Files.createTempDirectory("cuckoo-filter-bench-");

        // Setup file-backed test
        testFilePath = tempDir.resolve("cuckoo-filter-bench-" + filterCapacity + ".bin");

        // Pre-write filter to file for read benchmarks
        preWrittenFile = MappedFile.create(testFilePath, sourceFilter.byteSize());
        sourceFilter.writeTo(preWrittenFile.segment(), 0);
        preWrittenFile.flush();

        // Log benchmark setup
        System.out.println("========================================");
        System.out.println("Cuckoo Filter Memory-Mapped Benchmark Setup");
        System.out.println("========================================");
        System.out.println("  Filter capacity: " + filterCapacity);
        System.out.println("  Actual capacity: " + sourceFilter.capacity());
        System.out.println("  Load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Entry count: " + sourceFilter.count());
        System.out.println("  Fingerprint bits: " + fingerprintBits);
        System.out.println("  Filter byte size: " + formatBytes(sourceFilter.byteSize()));
        System.out.println("  Target segment size: " + formatBytes(targetSegment.byteSize()));
        System.out.println("  Test file: " + testFilePath);
        System.out.println("========================================");
    }

    /**
     * Cleans up resources after each trial.
     */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        // Close pre-written file
        if (preWrittenFile != null) {
            preWrittenFile.close();
        }

        // Close shared arena (releases target segment)
        if (sharedArena != null) {
            sharedArena.close();
        }

        // Clean up test file
        if (testFilePath != null && Files.exists(testFilePath)) {
            Files.deleteIfExists(testFilePath);
        }

        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.deleteIfExists(tempDir);
        }

        System.out.println("Benchmark teardown complete");
    }

    // ==================== MemorySegment Benchmarks ====================

    /**
     * Benchmark: Write filter to MemorySegment (serialize).
     *
     * <p>Measures throughput of the writeTo() operation, which copies
     * the entire filter state to an off-heap memory segment.</p>
     *
     * <h3>Expected Performance</h3>
     * <ul>
     *   <li>Small filters (10K): 10+ GB/s (fits in cache)</li>
     *   <li>Medium filters (100K): 5-10 GB/s</li>
     *   <li>Large filters (1M): 2-5 GB/s (memory bandwidth limited)</li>
     * </ul>
     */
    @Benchmark
    public void memorySegmentWrite(Blackhole bh) {
        sourceFilter.writeTo(targetSegment, 0);
        // Blackhole consume to prevent dead code elimination
        bh.consume(targetSegment);
    }

    /**
     * Benchmark: Read filter from MemorySegment (deserialize).
     *
     * <p>Measures throughput of the readFrom() operation, which creates
     * a new CuckooFilter instance from an off-heap memory segment.</p>
     *
     * <h3>Domain Correctness</h3>
     * <p>Before measuring, the filter is written to the segment to ensure
     * valid data is present for deserialization.</p>
     */
    @Benchmark
    public CuckooFilter memorySegmentRead(Blackhole bh) {
        // Ensure segment has valid data
        sourceFilter.writeTo(targetSegment, 0);

        // Measure deserialization
        CuckooFilter loaded = CuckooFilter.readFrom(targetSegment, 0);

        // Verify a sample of keys (domain correctness check)
        bh.consume(loaded.contains(insertedKeys[0]));

        return loaded;
    }

    /**
     * Benchmark: Round-trip MemorySegment (write then read).
     *
     * <p>Measures combined throughput of serialization followed by
     * deserialization. This reflects the typical usage pattern when
     * persisting and restoring a filter.</p>
     */
    @Benchmark
    public CuckooFilter memorySegmentRoundTrip(Blackhole bh) {
        // Write
        sourceFilter.writeTo(targetSegment, 0);

        // Read
        CuckooFilter loaded = CuckooFilter.readFrom(targetSegment, 0);

        // Verify correctness (domain constraint: no false negatives)
        if (!loaded.contains(insertedKeys[0])) {
            throw new IllegalStateException(
                "Domain correctness violation: inserted key not found after round-trip");
        }

        return loaded;
    }

    /**
     * Benchmark: Batch write operations (10 writes).
     *
     * <p>Measures throughput when performing multiple sequential writes.
     * This tests the memory bandwidth without allocation overhead.</p>
     */
    @Benchmark
    public int memorySegmentBatchWrite(Blackhole bh) {
        int bytesWritten = 0;
        for (int i = 0; i < 10; i++) {
            sourceFilter.writeTo(targetSegment, 0);
            bytesWritten += sourceFilter.byteSize();
        }
        return bytesWritten;
    }

    // ==================== File-Backed Benchmarks ====================

    /**
     * State for file-backed benchmarks.
     * Creates a fresh MappedFile for each iteration to avoid caching effects.
     */
    @State(Scope.Thread)
    public static class FileState {
        Path filePath;
        CuckooFilter filter;
        long filterSize;

        @Setup(Level.Iteration)
        public void setup() throws IOException {
            // Create temp file for this iteration
            filePath = Files.createTempFile("cuckoo-bench-", ".bin");

            // Create a small filter for file benchmarks
            filter = CuckooFilter.create(10000, 13);

            // Populate filter
            for (int i = 0; i < 7500; i++) {
                byte[] key = ("file-key-" + i).getBytes();
                filter.insert(key);
            }

            filterSize = filter.byteSize();
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            if (filePath != null && Files.exists(filePath)) {
                Files.deleteIfExists(filePath);
            }
        }
    }

    /**
     * Benchmark: Write filter to memory-mapped file.
     *
     * <p>Measures throughput of writing a filter to a file-backed
     * MemorySegment via MappedFile.</p>
     *
     * <h3>Expected Performance</h3>
     * <ul>
     *   <li>SSD: 500 MB/s - 3 GB/s (device dependent)</li>
     *   <li>Page cache hot: 5-15 GB/s (memory speed)</li>
     *   <li>NVMe: 2-7 GB/s</li>
     * </ul>
     */
    @Benchmark
    public long fileBackedWrite(FileState state) throws IOException {
        try (MappedFile mappedFile = MappedFile.create(state.filePath, state.filterSize)) {
            state.filter.writeTo(mappedFile.segment(), 0);
            mappedFile.flush();
            return state.filterSize;
        }
    }

    /**
     * Benchmark: Read filter from memory-mapped file.
     *
     * <p>Measures throughput of reading a filter from a file-backed
     * MemorySegment via MappedFile.</p>
     */
    @Benchmark
    public CuckooFilter fileBackedReader(FileState state) throws IOException {
        // First write the filter to the file
        try (MappedFile writeFile = MappedFile.create(state.filePath, state.filterSize)) {
            state.filter.writeTo(writeFile.segment(), 0);
            writeFile.flush();
        }

        // Then read it back
        try (MappedFile readFile = MappedFile.open(state.filePath)) {
            return CuckooFilter.readFrom(readFile.segment(), 0);
        }
    }

    /**
     * Benchmark: Read from pre-written file (page cache warm).
     *
     * <p>Measures read throughput when the file is already in the
     * operating system's page cache. This represents best-case
     * performance for frequently accessed filters.</p>
     */
    @Benchmark
    public CuckooFilter fileBackedReadWarm(Blackhole bh) throws IOException {
        // Read from pre-written file (set up in setup())
        CuckooFilter loaded = CuckooFilter.readFrom(preWrittenFile.segment(), 0);

        // Verify correctness
        bh.consume(loaded.contains(insertedKeys[0]));

        return loaded;
    }

    /**
     * Benchmark: Round-trip file-backed (write then read).
     *
     * <p>Measures combined throughput of writing to and reading from
     * a memory-mapped file.</p>
     */
    @Benchmark
    public CuckooFilter fileBackedRoundTrip(FileState state) throws IOException {
        // Write
        try (MappedFile writeFile = MappedFile.create(state.filePath, state.filterSize)) {
            state.filter.writeTo(writeFile.segment(), 0);
            writeFile.flush();
        }

        // Read
        try (MappedFile readFile = MappedFile.open(state.filePath)) {
            CuckooFilter loaded = CuckooFilter.readFrom(readFile.segment(), 0);

            // Verify correctness
            if (!loaded.contains("file-key-0".getBytes())) {
                throw new IllegalStateException("Domain correctness violation after file round-trip");
            }

            return loaded;
        }
    }

    // ==================== Offset-Based Benchmarks ====================

    /**
     * Benchmark: Write filter at non-zero offset.
     *
     * <p>Tests serialization performance when the filter is written
     * at an offset within a larger memory segment. This simulates
     * storing multiple filters in a single segment.</p>
     */
    @Benchmark
    public void memorySegmentWriteWithOffset(Blackhole bh) {
        // Use a 4KB offset (one page)
        long offset = 4096;
        sourceFilter.writeTo(targetSegment, offset);
        bh.consume(targetSegment);
    }

    /**
     * Benchmark: Read filter from non-zero offset.
     *
     * <p>Tests deserialization performance when the filter is read
     * from an offset within a larger memory segment.</p>
     */
    @Benchmark
    public CuckooFilter memorySegmentReadWithOffset(Blackhole bh) {
        long offset = 4096;

        // Write at offset first
        sourceFilter.writeTo(targetSegment, offset);

        // Read from offset
        CuckooFilter loaded = CuckooFilter.readFrom(targetSegment, offset);

        bh.consume(loaded);
        return loaded;
    }

    // ==================== Helper Methods ====================

    /**
     * Generates a deterministic key for the given index.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16 bytes (128 bits)</li>
     *   <li>Keys are unique per index</li>
     *   <li>Key format: [prefix(8B)][index(8B)]</li>
     * </ul>
     *
     * @param index The unique index for this key
     * @return A 16-byte array containing the key
     */
    private byte[] generateKey(int index) {
        byte[] key = new byte[16];

        // First 8 bytes: fixed prefix "CUCKMKEY" (Cuckoo Memory Key)
        key[0] = 'C';
        key[1] = 'U';
        key[2] = 'C';
        key[3] = 'K';
        key[4] = 'M';
        key[5] = 'K';
        key[6] = 'E';
        key[7] = 'Y';

        // Last 8 bytes: big-endian index
        key[8] = (byte) (index >>> 56);
        key[9] = (byte) (index >>> 48);
        key[10] = (byte) (index >>> 40);
        key[11] = (byte) (index >>> 32);
        key[12] = (byte) (index >>> 24);
        key[13] = (byte) (index >>> 16);
        key[14] = (byte) (index >>> 8);
        key[15] = (byte) index;

        return key;
    }

    /**
     * Formats a byte count as a human-readable string.
     *
     * @param bytes The number of bytes
     * @return Formatted string (e.g., "1.5 GB")
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
