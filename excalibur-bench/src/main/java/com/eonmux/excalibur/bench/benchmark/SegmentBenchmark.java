package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.store.Segment;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Segment point lookup throughput.
 *
 * <p>Measures throughput of hash table lookups to validate the Phase 2
 * requirement for high-performance point lookups using Robin Hood hashing.</p>
 *
 * <h2>Performance Target</h2>
 * <p>Target: ≥1 billion lookups/sec (page cache hot)</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Load factor maintained at 0.7 threshold</li>
 *   <li>Distance values in range [0, 127]</li>
 *   <li>Key hashes are positive 64-bit values</li>
 *   <li>Capacity is power of 2 in range [1024, 1048576]</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The benchmark state is managed by JMH's @State annotations which
 * provide proper isolation between threads. The Segment being tested has its
 * own thread-safety guarantees documented in its class Javadoc.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class SegmentBenchmark {

    /**
     * Number of entries to pre-populate in the segment.
     * Limited to 10000 due to current Segment data region size constraints.
     */
    @Param({"10000"})
    public int entryCount;

    /**
     * Size of keys in bytes.
     */
    @Param({"16"})
    public int keySize;

    /**
     * Size of values in bytes.
     */
    @Param({"64"})
    public int valueSize;

    private Arena arena;
    private Path tempFile;
    private Segment segment;
    private byte[][] keys;
    private int[] lookupIndices;
    private int lookupIndex;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Create shared arena for multi-threaded access
        arena = Arena.ofShared();

        // Create temp file for segment
        tempFile = Files.createTempFile("segment_bench_", ".bin");

        // Calculate segment size to accommodate entries at 0.7 load factor
        // Each entry: 17 bytes header + key + value + 1 byte tombstone
        // Slot table: 24 bytes per slot
        // We need at least entryCount / 0.7 slots
        int minSlots = (int) Math.ceil(entryCount / Segment.LOAD_FACTOR_THRESHOLD);
        int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
        long slotTableSize = (long) slotCount * 24;
        // Calculate data region size with proper sizing:
        // Each entry = 17 bytes header + key + value + 1 byte tombstone
        // Add 10x safety margin to ensure we don't run out of space
        long entrySize = 17 + keySize + valueSize + 1;
        long dataRegionSize = entryCount * entrySize * 10;
        long segmentSize = slotTableSize + dataRegionSize + 1024 * 1024; // +1MB extra

        // Create segment
        segment = new Segment(tempFile, segmentSize, arena);

        // Pre-populate with entries
        Random random = new Random(42);
        keys = new byte[entryCount][];

        for (int i = 0; i < entryCount; i++) {
            byte[] key = new byte[keySize];
            byte[] value = new byte[valueSize];
            random.nextBytes(key);
            random.nextBytes(value);

            // Make key human-readable for debugging
            String keyStr = "key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, keySize));

            keys[i] = key;
            segment.put(key, value);
        }

        // Generate random lookup indices (for non-sequential access pattern)
        int numLookups = 10000;
        lookupIndices = new int[numLookups];
        for (int i = 0; i < numLookups; i++) {
            lookupIndices[i] = random.nextInt(entryCount);
        }
        lookupIndex = 0;

        // Verify segment is properly populated
        if (segment.entryCount() != entryCount) {
            throw new IllegalStateException(
                "Segment not fully populated. Expected " + entryCount +
                " entries, got " + segment.entryCount());
        }

        // Log segment stats
        System.out.println("Segment Benchmark Setup:");
        System.out.println("  Entry count: " + segment.entryCount());
        System.out.println("  Slot count: " + segment.slotCount());
        System.out.println("  Load factor: " + String.format("%.3f", segment.loadFactor()));
        System.out.println("  Segment size: " + (segment.size() / 1024 / 1024) + " MB");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Benchmark sequential point lookups.
     * Tests cache-friendly access pattern where keys are accessed in order.
     */
    @Benchmark
    public Object sequentialLookup(Blackhole bh) {
        int idx = lookupIndex % entryCount;
        lookupIndex++;
        return segment.get(keys[idx]);
    }

    /**
     * Benchmark random point lookups.
     * Tests realistic access pattern where keys are accessed in random order.
     */
    @Benchmark
    public Object randomLookup(Blackhole bh) {
        int idx = lookupIndices[lookupIndex % lookupIndices.length];
        lookupIndex++;
        return segment.get(keys[idx]);
    }

    /**
     * Benchmark batch lookups.
     * Tests throughput when performing multiple lookups in a tight loop.
     */
    @Benchmark
    public int batchLookup(Blackhole bh) {
        int found = 0;
        for (int i = 0; i < 100; i++) {
            int idx = lookupIndices[i % lookupIndices.length];
            if (segment.get(keys[idx]) != null) {
                found++;
            }
        }
        return found;
    }

    /**
     * Benchmark lookups with epoch tracking (zero-copy slice validity).
     * Tests the overhead of epoch-based slice validity checking.
     */
    @Benchmark
    public Object lookupWithEpoch(Blackhole bh) {
        int idx = lookupIndices[lookupIndex % lookupIndices.length];
        lookupIndex++;
        return segment.getWithEpoch(keys[idx]);
    }

    /**
     * Benchmark lookups after acquiring read lock manually.
     * Tests the overhead of explicit lock acquisition for bulk reads.
     */
    @Benchmark
    public int lookupWithManualLock(Blackhole bh) {
        segment.readLock().lock();
        try {
            int found = 0;
            for (int i = 0; i < 10; i++) {
                int idx = lookupIndices[i % lookupIndices.length];
                if (segment.getWithoutLock(keys[idx]) != null) {
                    found++;
                }
            }
            return found;
        } finally {
            segment.readLock().unlock();
        }
    }

    /**
     * Benchmark lookup of non-existent keys (miss path).
     * Tests the performance when keys are not found.
     */
    @Benchmark
    public Object lookupMiss(Blackhole bh) {
        // Generate a key that doesn't exist
        byte[] nonExistentKey = ("nonexistent-" + (lookupIndex++)).getBytes();
        return segment.get(nonExistentKey);
    }

    /**
     * Rounds a value up to the nearest power of 2.
     */
    private static int roundToPowerOf2(int value) {
        if (value <= Segment.MIN_SEGMENT_CAPACITY) {
            return Segment.MIN_SEGMENT_CAPACITY;
        }
        if (value >= Segment.MAX_SEGMENT_CAPACITY) {
            return Segment.MAX_SEGMENT_CAPACITY;
        }
        int power = Integer.highestOneBit(value);
        if (power < value) {
            power <<= 1;
        }
        return Math.min(power, Segment.MAX_SEGMENT_CAPACITY);
    }
}
