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
 * <p>Measures point-lookup throughput of the Robin Hood hash table.</p>
 *
 * <h2>Performance Target</h2>
 * <p>{@code sequentialLookup} and {@code randomLookup} are held to 10 million lookups per
 * second, single-threaded and page cache hot, by {@code BenchmarkTargetRegistry}. Every score
 * is per lookup: the methods that make several lookups per invocation declare
 * {@link OperationsPerInvocation}, so a batch and a single lookup read on the same scale.</p>
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

    /**
     * Lookups per invocation of {@link #batchLookup}.
     */
    static final int BATCH = 100;

    /**
     * Lookups per invocation of {@link #lookupWithManualLock}.
     */
    static final int LOCKED_BATCH = 10;

    /**
     * Length of the random index stream, a power of two so that a cursor wraps with a mask.
     */
    static final int LOOKUP_INDEX_COUNT = 1 << 14;

    /**
     * Number of distinct absent keys the miss benchmark cycles through, a power of two.
     */
    static final int MISS_KEY_COUNT = 1 << 12;

    private Arena arena;
    private Path tempFile;
    private Segment segment;
    private byte[][] keys;
    private int[] lookupIndices;
    private int sequentialIndex;
    private int lookupCursor;
    private byte[][] missKeys;
    private int missCursor;

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
        lookupIndices = new int[LOOKUP_INDEX_COUNT];
        for (int i = 0; i < LOOKUP_INDEX_COUNT; i++) {
            lookupIndices[i] = random.nextInt(entryCount);
        }
        sequentialIndex = 0;
        lookupCursor = 0;

        // Absent keys of the same length as the stored ones, each confirmed absent
        missKeys = new byte[MISS_KEY_COUNT][];
        for (int i = 0; i < MISS_KEY_COUNT; i++) {
            byte[] missKey = new byte[keySize];
            byte[] missBytes = ("nonexistent-" + i).getBytes();
            System.arraycopy(missBytes, 0, missKey, 0, Math.min(missBytes.length, keySize));
            missKeys[i] = missKey;
            if (segment.get(missKey) != null) {
                throw new IllegalStateException("Miss key " + i + " is present in the segment");
            }
        }
        missCursor = 0;

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
        int idx = sequentialIndex;
        sequentialIndex = idx + 1 == entryCount ? 0 : idx + 1;
        return segment.get(keys[idx]);
    }

    /**
     * Benchmark random point lookups.
     * Tests realistic access pattern where keys are accessed in random order.
     */
    @Benchmark
    public Object randomLookup(Blackhole bh) {
        int idx = lookupIndices[nextLookupPosition(1)];
        return segment.get(keys[idx]);
    }

    /**
     * Benchmark lookups in a tight loop of 100 independent calls.
     *
     * <p>Reported per lookup. Each call takes and releases the read lock itself, so nothing is
     * amortised: this is {@link #randomLookup} without the per-invocation harness cost, and
     * {@link #lookupWithManualLock} is the variant that shares one lock acquisition.</p>
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int batchLookup(Blackhole bh) {
        int start = nextLookupPosition(BATCH);
        int found = 0;
        for (int i = 0; i < BATCH; i++) {
            int idx = lookupIndices[(start + i) & (LOOKUP_INDEX_COUNT - 1)];
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
        int idx = lookupIndices[nextLookupPosition(1)];
        return segment.getWithEpoch(keys[idx]);
    }

    /**
     * Benchmark ten lookups under one manually acquired read lock.
     *
     * <p>Reported per lookup, so that it compares directly with {@link #randomLookup}: the
     * difference is what sharing one lock acquisition between ten lookups saves.</p>
     */
    @Benchmark
    @OperationsPerInvocation(LOCKED_BATCH)
    public int lookupWithManualLock(Blackhole bh) {
        int start = nextLookupPosition(LOCKED_BATCH);
        segment.readLock().lock();
        try {
            int found = 0;
            for (int i = 0; i < LOCKED_BATCH; i++) {
                int idx = lookupIndices[(start + i) & (LOOKUP_INDEX_COUNT - 1)];
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
     *
     * <p>Cycles through keys generated and confirmed absent at setup, so the timed call is
     * the lookup and not the construction of a new key.</p>
     */
    @Benchmark
    public Object lookupMiss(Blackhole bh) {
        int i = missCursor;
        missCursor = (i + 1) & (MISS_KEY_COUNT - 1);
        return segment.get(missKeys[i]);
    }

    /**
     * Claims the next positions of the random index stream.
     *
     * @param count Number of positions claimed
     * @return The first claimed position; the rest follow it, wrapping with the stream mask
     */
    private int nextLookupPosition(int count) {
        int start = lookupCursor;
        lookupCursor = (start + count) & (LOOKUP_INDEX_COUNT - 1);
        return start;
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
