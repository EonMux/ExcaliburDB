package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.store.Segment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.eonmux.excalibur.bench.metric.FPRateValidator;
import com.eonmux.excalibur.bench.metric.ThroughputValidator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick validation tests for benchmark targets.
 *
 * <p>Runs fast micro-benchmarks and checks the measured values against the targets in
 * {@link com.eonmux.excalibur.bench.metric.BenchmarkTargetRegistry}. Rates measured in wall-clock time are
 * reported against their target and asserted against a regression floor - see
 * {@link com.eonmux.excalibur.bench.metric.ThroughputValidator#POINT_LOOKUP_REGRESSION_FLOOR} - because the
 * host moves such a reading further than a code change does. Rates that are not wall-clock, such
 * as the filter's false-positive rate, are asserted against the target itself.</p>
 *
 * <p>Tagged {@code benchmark} and therefore excluded from ordinary builds; run with
 * {@code ./gradlew :excalibur-bench:benchmarkTest}.</p>
 */
@Tag("benchmark")
public class QuickBenchmarkValidation {

    // ==================== CUCKOO FILTER TARGETS ====================
    // Target: FP rate ≤ 0.1% at 95% load factor with 13-bit fingerprints

    @Test
    void validateCuckooFilterFPRate() {
        System.out.println("\n========================================");
        System.out.println("Cuckoo Filter FP Rate Validation");
        System.out.println("Target: ≤0.1% FP rate at 95% load factor");
        System.out.println("========================================\n");

        int requestedCapacity = 100000;
        int fingerprintBits = 13;
        double targetLoadFactor = 0.95;

        CuckooFilter filter = CuckooFilter.create(requestedCapacity, fingerprintBits);

        // The insert count must come from the REALIZED capacity: create() rounds the
        // bucket count up to a power of two, so 100,000 * 0.95 would have filled only
        // 72.5% of the filter while the test claimed to measure 95% load.
        long realizedCapacity = filter.capacity();
        int entriesToInsert = (int) (realizedCapacity * targetLoadFactor);

        for (int i = 0; i < entriesToInsert; i++) {
            byte[] key = ("key-" + i).getBytes();
            assertTrue(filter.insert(key), "Insert should succeed at 95% load factor");
        }

        double actualLoadFactor = filter.loadFactor();
        System.out.println("Realized capacity: " + realizedCapacity);
        System.out.println("Actual load factor: " + String.format("%.4f", actualLoadFactor));
        assertEquals(targetLoadFactor, actualLoadFactor, 0.005,
            "measurement must be taken at the load factor it claims");

        // Measure false positive rate with keys NOT inserted
        int falsePositives = 0;
        int fpSampleSize = 200000;
        for (int i = 0; i < fpSampleSize; i++) {
            byte[] nonExistentKey = ("fp-test-" + (i + realizedCapacity)).getBytes();
            if (filter.contains(nonExistentKey)) {
                falsePositives++;
            }
        }

        double fpRate = (double) falsePositives / fpSampleSize;

        System.out.println("False positives: " + falsePositives + " / " + fpSampleSize);
        System.out.println("FP rate: " + String.format("%.4f%%", fpRate * 100));
        System.out.println("Spec: " + String.format("%.4f%%", FPRateValidator.MAX_FP_RATE * 100));

        // Verify no false negatives (all inserted items must be found)
        int falseNegatives = 0;
        for (int i = 0; i < Math.min(10000, entriesToInsert); i++) {
            byte[] key = ("key-" + i).getBytes();
            if (!filter.contains(key)) {
                falseNegatives++;
            }
        }

        System.out.println("False negatives: " + falseNegatives + " (must be 0)");
        assertEquals(0, falseNegatives, "Cuckoo filter must have zero false negatives");

        // One-sided binomial bound rather than a flat 50% tolerance.
        double standardError = Math.sqrt(
            FPRateValidator.MAX_FP_RATE * (1 - FPRateValidator.MAX_FP_RATE) / fpSampleSize);
        double threshold = FPRateValidator.MAX_FP_RATE + 3.0 * standardError;
        assertTrue(fpRate <= threshold, String.format(
            "FP rate at %.4f load must be at most %.4f%% (0.1%% spec + 3 standard errors "
                + "at n=%d), got %.4f%%",
            actualLoadFactor, threshold * 100, fpSampleSize, fpRate * 100));
    }

    // ==================== STORAGE LOOKUP TARGETS ====================
    // Target: ≥1B ops/sec point lookups (page cache hot)
    // Note: 1B ops/sec requires ~1ns per op, which is achievable with:
    // - Pre-computed hashes
    // - Lock-free reads
    // - Memory-mapped I/O with hot page cache

    @Test
    void validatePointLookupThroughput(@TempDir Path tempDir) throws IOException {
        System.out.println("\n========================================");
        System.out.println("Point Lookup Throughput Validation");
        System.out.println("Target: ≥1,000,000,000 ops/sec");
        System.out.println("========================================\n");

        // The shape the point-lookup target is defined over. It used to be a local ten thousand,
        // a different operating point - more of the table misses cache - measured against a
        // constant that was not set on it, which gates the table size rather than the lookup.
        int entryCount = ThroughputValidator.TARGET_POINT_LOOKUP_ENTRIES;
        int keySize = 16;
        int valueSize = 64;

        // Create segment
        Path segmentFile = tempDir.resolve("benchmark_segment.bin");
        Arena arena = Arena.ofShared();

        int minSlots = (int) Math.ceil(entryCount / Segment.LOAD_FACTOR_THRESHOLD);
        int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
        long slotTableSize = (long) slotCount * 24;
        long entrySize = 17 + keySize + valueSize + 1;
        // Calculate proper data region size with ample safety margin
        long dataRegionSize = (long) entryCount * entrySize * 20; // 20x safety margin
        long segmentSize = slotTableSize + dataRegionSize + 4 * 1024 * 1024; // +4MB extra

        Segment segment = new Segment(segmentFile, segmentSize, arena);

        // Pre-populate with entries
        Random random = new Random(42);
        byte[][] keys = new byte[entryCount][];
        long[] keyHashes = new long[entryCount];

        for (int i = 0; i < entryCount; i++) {
            byte[] key = ("key-" + String.format("%08d", i)).getBytes();
            byte[] value = new byte[valueSize];
            random.nextBytes(value);

            keys[i] = key;
            segment.put(key, value);
        }

        // Pre-compute hashes for fast lookup path
        for (int i = 0; i < entryCount; i++) {
            keyHashes[i] = segment.hashKey(keys[i]);
        }

        System.out.println("Segment stats:");
        System.out.println("  Entry count: " + segment.entryCount());
        System.out.println("  Slot count: " + segment.slotCount());
        System.out.println("  Load factor: " + String.format("%.3f", segment.loadFactor()));

        // Warmup
        for (int i = 0; i < 10000; i++) {
            int idx = i % entryCount;
            segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
        }

        // Measure throughput (lock-free path), at steady state rather than partway up the JIT's
        // ramp. Timing one pass read several million ops/sec below what the same lookup reaches
        // once warm - a difference in how long the clock ran, not in what was measured.
        final int iterations = 10_000_000;
        double opsPerSec = SteadyState.bestOpsPerSec(iterations, () -> {
            long performed = 0;
            for (int i = 0; i < iterations; i++) {
                int idx = i % entryCount;
                segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
                performed++;
            }
            return performed;
        });

        System.out.println("\nLock-free lookup results:");
        System.out.println("  Iterations per round: " + iterations);
        System.out.println("  Rounds: " + SteadyState.ROUNDS + " (best taken)");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Target: " + formatOpsPerSec(ThroughputValidator.TARGET_POINT_LOOKUP)
            + (opsPerSec >= ThroughputValidator.TARGET_POINT_LOOKUP ? " (met)" : " (not met)"));
        System.out.println("  Enforced floor: "
            + formatOpsPerSec(ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR));
        System.out.println("  PROJECT GOAL (aspirational): " + formatOpsPerSec(1_000_000_000.0));

        // Assert the regression floor rather than the target: the target is a wall-clock figure
        // and the host moves a reading further than a code change does. Printing
        // "BELOW TARGET (expected in JVM)" and asserting nothing let any throughput at all pass.
        assertTrue(opsPerSec >= ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR, String.format(
            "lock-free point lookup throughput must be at least %s, got %s",
            formatOpsPerSec(ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR),
            formatOpsPerSec(opsPerSec)));

        // Measure batch throughput (10 lookups with single lock)
        final int batchSize = 10;
        final int batchIterations = 1_000_000;

        final long totalBatchOps = (long) batchIterations * batchSize;
        double batchOpsPerSec = SteadyState.bestOpsPerSec(totalBatchOps, () -> {
            long performed = 0;
            for (int i = 0; i < batchIterations; i++) {
                segment.readLock().lock();
                try {
                    for (int j = 0; j < batchSize; j++) {
                        int idx = (i * batchSize + j) % entryCount;
                        segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
                        performed++;
                    }
                } finally {
                    segment.readLock().unlock();
                }
            }
            return performed;
        });

        System.out.println("\nBatch lookup results (10 per batch with lock):");
        System.out.println("  Operations per round: " + totalBatchOps);
        System.out.println("  Rounds: " + SteadyState.ROUNDS + " (best taken)");
        System.out.println("  Throughput: " + formatOpsPerSec(batchOpsPerSec));
        System.out.println("  Per-op latency: " + String.format("%.2f", 1_000_000_000.0 / batchOpsPerSec) + " ns");

        assertTrue(batchOpsPerSec >= ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR, String.format(
            "batched point lookup throughput must be at least %s, got %s",
            formatOpsPerSec(ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR),
            formatOpsPerSec(batchOpsPerSec)));

        // Cleanup
        segment.close();
        arena.close();

        // Note: 1B ops/sec is extremely aggressive target
        // Real-world performance of 100M+ is excellent
        System.out.println("\nNote: 1B ops/sec target requires:");
        System.out.println("  - Pre-computed hashes");
        System.out.println("  - Lock-free reads");
        System.out.println("  - Hot L1/L2/L3 cache");
        System.out.println("  - JMH with proper warmup (JIT compilation)");
        System.out.println("  Actual throughput: " + formatOpsPerSec(opsPerSec));
    }

    // ==================== DISTANCE COMPUTATION ====================
    // Validate vector distance functions for correctness

    @Test
    void validateDistanceComputation() {
        System.out.println("\n========================================");
        System.out.println("Distance Computation Validation");
        System.out.println("========================================\n");

        // Test vectors
        float[] v1 = {1.0f, 0.0f, 0.0f};
        float[] v2 = {0.0f, 1.0f, 0.0f};
        float[] v3 = {1.0f, 1.0f, 0.0f};
        float[] v4 = {1.0f, 0.0f, 0.0f}; // Same as v1

        // L2 distance
        double l2_v1_v2 = l2Distance(v1, v2);
        double l2_v1_v4 = l2Distance(v1, v4);
        System.out.println("L2 distance(v1, v2): " + l2_v1_v2 + " (expected: √2 ≈ 1.414)");
        System.out.println("L2 distance(v1, v4): " + l2_v1_v4 + " (expected: 0.0)");
        assertEquals(Math.sqrt(2), l2_v1_v2, 0.001, "L2 distance should be √2");
        assertEquals(0.0, l2_v1_v4, 0.001, "L2 distance of identical vectors should be 0");

        // Cosine similarity
        double cos_v1_v2 = cosineSimilarity(v1, v2);
        double cos_v1_v3 = cosineSimilarity(v1, v3);
        double cos_v1_v4 = cosineSimilarity(v1, v4);
        System.out.println("\nCosine similarity(v1, v2): " + cos_v1_v2 + " (expected: 0.0)");
        System.out.println("Cosine similarity(v1, v3): " + cos_v1_v3 + " (expected: 0.707)");
        System.out.println("Cosine similarity(v1, v4): " + cos_v1_v4 + " (expected: 1.0)");
        assertEquals(0.0, cos_v1_v2, 0.001, "Cosine of orthogonal vectors should be 0");
        assertEquals(1.0 / Math.sqrt(2), cos_v1_v3, 0.001, "Cosine similarity check");
        assertEquals(1.0, cos_v1_v4, 0.001, "Cosine of identical vectors should be 1");

        // Inner product
        double ip_v1_v2 = innerProduct(v1, v2);
        double ip_v1_v3 = innerProduct(v1, v3);
        System.out.println("\nInner product(v1, v2): " + ip_v1_v2 + " (expected: 0.0)");
        System.out.println("Inner product(v1, v3): " + ip_v1_v3 + " (expected: 1.0)");
        assertEquals(0.0, ip_v1_v2, 0.001, "Inner product of orthogonal vectors should be 0");
        assertEquals(1.0, ip_v1_v3, 0.001, "Inner product check");

        System.out.println("\nDistance functions: PASS ✓");
    }

    // ==================== HELPER METHODS ====================

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

    private static String formatOpsPerSec(double opsPerSec) {
        if (opsPerSec >= 1_000_000_000) {
            return String.format("%.2f B ops/sec", opsPerSec / 1_000_000_000);
        } else if (opsPerSec >= 1_000_000) {
            return String.format("%.2f M ops/sec", opsPerSec / 1_000_000);
        } else if (opsPerSec >= 1_000) {
            return String.format("%.2f K ops/sec", opsPerSec / 1_000);
        } else {
            return String.format("%.2f ops/sec", opsPerSec);
        }
    }

    // Simple distance functions for validation
    private static double l2Distance(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double innerProduct(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
