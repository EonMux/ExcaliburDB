package excalibur.bench;

import io.excalibur.store.Segment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick throughput test for point lookups.
 *
 * <p>This test validates the optimized lookup path and measures
 * approximate throughput to verify the ≥1B lookups/sec target is achievable.</p>
 *
 * <h2>Target</h2>
 * <p>≥1,000,000,000 ops/sec (1 billion lookups per second)</p>
 *
 * @author ExcaliburDB
 */
@Tag("benchmark")
class PointLookupThroughputTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path tempFile;
    private Segment segment;

    private byte[][] keys;
    private long[] keyHashes;

    // Note: Using 5000 entries to fit within Segment's default 1MB data region
    // Each entry is ~98 bytes (17 header + 16 key + 64 value + 1 tombstone)
    // 5000 * 98 = ~490KB, leaving room for overhead
    private static final int ENTRY_COUNT = 5_000;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 64;
    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 50_000_000; // 50M for accurate measurement

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        tempFile = Files.createTempFile(tempDir, "throughput_test_", ".bin");

        // Calculate segment size with generous margins
        int minSlots = (int) Math.ceil(ENTRY_COUNT / Segment.LOAD_FACTOR_THRESHOLD);
        int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
        long slotTableSize = (long) slotCount * 24;
        long entrySize = 17 + KEY_SIZE + VALUE_SIZE + 1;
        // Allocate 50x the estimated size to ensure we have enough space
        long dataRegionSize = ENTRY_COUNT * entrySize * 50;
        long segmentSize = slotTableSize + dataRegionSize + 100 * 1024 * 1024; // +100MB extra

        segment = new Segment(tempFile, segmentSize, arena);

        // Populate segment
        Random random = new Random(42);
        keys = new byte[ENTRY_COUNT][];
        keyHashes = new long[ENTRY_COUNT];

        for (int i = 0; i < ENTRY_COUNT; i++) {
            byte[] key = new byte[KEY_SIZE];
            byte[] value = new byte[VALUE_SIZE];

            String keyStr = "key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, KEY_SIZE));

            random.nextBytes(value);

            keys[i] = key;
            segment.put(key, value);
        }

        // Pre-compute hashes
        for (int i = 0; i < ENTRY_COUNT; i++) {
            keyHashes[i] = segment.hashKey(keys[i]);
        }

        System.out.println("Setup complete:");
        System.out.println("  Entry count: " + segment.entryCount());
        System.out.println("  Slot count: " + segment.slotCount());
        System.out.println("  Load factor: " + String.format("%.3f", segment.loadFactor()));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    void testThroughputSequentialFastPath() {
        System.out.println("\n=== Sequential Lookup Fast Path ===");

        // Warmup
        segment.readLock().lock();
        try {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                int idx = i % ENTRY_COUNT;
                segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
            }
        } finally {
            segment.readLock().unlock();
        }

        // Measurement - lock-free path with pre-acquired lock
        segment.readLock().lock();
        try {
            long startNanos = System.nanoTime();
            int found = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                int idx = i % ENTRY_COUNT;
                if (segment.getByHashWithoutLock(keys[idx], keyHashes[idx]) != null) {
                    found++;
                }
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double opsPerSec = (MEASUREMENT_ITERATIONS * 1_000_000_000.0) / elapsedNanos;
            double latencyNs = (double) elapsedNanos / MEASUREMENT_ITERATIONS;

            System.out.println("  Iterations: " + MEASUREMENT_ITERATIONS);
            System.out.println("  Found: " + found);
            System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
            System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
            System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");
            System.out.println("  Target: " + formatOpsPerSec(1_000_000_000.0));

            if (opsPerSec >= 1_000_000_000.0) {
                System.out.println("  ✓ TARGET MET!");
            } else {
                double percent = (opsPerSec / 1_000_000_000.0) * 100;
                System.out.println("  ⚠ At " + String.format("%.1f", percent) + "% of target");
            }

            assertEquals(MEASUREMENT_ITERATIONS, found, "All lookups should succeed");
        } finally {
            segment.readLock().unlock();
        }
    }

    @Test
    void testThroughputWithLockAcquisition() {
        System.out.println("\n=== Lookup With Lock Acquisition ===");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            int idx = i % ENTRY_COUNT;
            segment.getByHash(keys[idx], keyHashes[idx]);
        }

        // Measurement - with lock acquisition
        long startNanos = System.nanoTime();
        int found = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS / 10; i++) { // Fewer iterations for lock test
            int idx = i % ENTRY_COUNT;
            if (segment.getByHash(keys[idx], keyHashes[idx]) != null) {
                found++;
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        int iterations = MEASUREMENT_ITERATIONS / 10;

        double opsPerSec = (iterations * 1_000_000_000.0) / elapsedNanos;
        double latencyNs = (double) elapsedNanos / iterations;

        System.out.println("  Iterations: " + iterations);
        System.out.println("  Found: " + found);
        System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");

        assertEquals(iterations, found, "All lookups should succeed");
    }

    @Test
    void testBatchThroughput() {
        System.out.println("\n=== Batch Lookup Throughput ===");
        int batchSize = 100;
        int numBatches = MEASUREMENT_ITERATIONS / batchSize;

        // Warmup
        for (int b = 0; b < 100; b++) {
            segment.readLock().lock();
            try {
                for (int i = 0; i < batchSize; i++) {
                    int idx = (b * batchSize + i) % ENTRY_COUNT;
                    segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
                }
            } finally {
                segment.readLock().unlock();
            }
        }

        // Measurement
        long startNanos = System.nanoTime();
        int found = 0;
        for (int b = 0; b < numBatches; b++) {
            segment.readLock().lock();
            try {
                for (int i = 0; i < batchSize; i++) {
                    int idx = (b * batchSize + i) % ENTRY_COUNT;
                    if (segment.getByHashWithoutLock(keys[idx], keyHashes[idx]) != null) {
                        found++;
                    }
                }
            } finally {
                segment.readLock().unlock();
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        int totalIterations = numBatches * batchSize;

        double opsPerSec = (totalIterations * 1_000_000_000.0) / elapsedNanos;
        double latencyNs = (double) elapsedNanos / totalIterations;

        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Total iterations: " + totalIterations);
        System.out.println("  Found: " + found);
        System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");
        System.out.println("  Target: " + formatOpsPerSec(1_000_000_000.0));

        if (opsPerSec >= 1_000_000_000.0) {
            System.out.println("  ✓ TARGET MET!");
        } else {
            double percent = (opsPerSec / 1_000_000_000.0) * 100;
            System.out.println("  ⚠ At " + String.format("%.1f", percent) + "% of target");
        }

        assertEquals(totalIterations, found, "All lookups should succeed");
    }

    @Test
    void testBatchApiThroughput() {
        System.out.println("\n=== Batch API Throughput ===");
        int batchSize = 100;
        int numBatches = 100_000;

        // Prepare batches
        byte[][][] batchKeys = new byte[numBatches][batchSize][];
        long[][] batchHashes = new long[numBatches][batchSize];

        Random random = new Random(123);
        for (int b = 0; b < numBatches; b++) {
            for (int i = 0; i < batchSize; i++) {
                int idx = random.nextInt(ENTRY_COUNT);
                batchKeys[b][i] = keys[idx];
                batchHashes[b][i] = keyHashes[idx];
            }
        }

        // Warmup
        for (int b = 0; b < 100; b++) {
            segment.batchGetByHash(batchKeys[b], batchHashes[b]);
        }

        // Measurement
        long startNanos = System.nanoTime();
        int found = 0;
        for (int b = 0; b < numBatches; b++) {
            var results = segment.batchGetByHash(batchKeys[b], batchHashes[b]);
            for (var r : results) {
                if (r != null) found++;
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        int totalIterations = numBatches * batchSize;

        double opsPerSec = (totalIterations * 1_000_000_000.0) / elapsedNanos;
        double latencyNs = (double) elapsedNanos / totalIterations;

        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Num batches: " + numBatches);
        System.out.println("  Total iterations: " + totalIterations);
        System.out.println("  Found: " + found);
        System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");

        assertEquals(totalIterations, found, "All lookups should succeed");
    }

    private static String formatOpsPerSec(double ops) {
        if (ops >= 1_000_000_000) {
            return String.format("%.2f B ops/sec", ops / 1_000_000_000);
        } else if (ops >= 1_000_000) {
            return String.format("%.2f M ops/sec", ops / 1_000_000);
        } else if (ops >= 1_000) {
            return String.format("%.2f K ops/sec", ops / 1_000);
        }
        return String.format("%.0f ops/sec", ops);
    }

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
