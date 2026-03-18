package excalibur.bench.benchmark;

import excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for CuckooFilterInsertBenchmark logic.
 *
 * <p>Verifies domain correctness of the benchmark setup and key generation.</p>
 *
 * @author ExcaliburDB
 */
class CuckooFilterInsertBenchmarkTest {

    @Test
    @DisplayName("Key generation produces unique 16-byte keys")
    void testKeyGeneration() {
        CuckooFilterInsertBenchmarkTestHelper helper = new CuckooFilterInsertBenchmarkTestHelper();

        byte[] key1 = helper.generateKey(1);
        byte[] key2 = helper.generateKey(2);
        byte[] key3 = helper.generateKey(1); // Same index as key1

        // Keys should be 16 bytes
        assertThat(key1).hasSize(16);
        assertThat(key2).hasSize(16);

        // Different indices should produce different keys
        assertThat(key1).isNotEqualTo(key2);

        // Same index should produce same key (deterministic)
        assertThat(key3).isEqualTo(key1);
    }

    @Test
    @DisplayName("Insert throughput at 25% load factor")
    void testInsertAt25PercentLoadFactor() {
        testInsertAtLoadFactor(0.25, 100000);
    }

    @Test
    @DisplayName("Insert throughput at 50% load factor")
    void testInsertAt50PercentLoadFactor() {
        testInsertAtLoadFactor(0.50, 100000);
    }

    @Test
    @DisplayName("Insert throughput at 75% load factor")
    void testInsertAt75PercentLoadFactor() {
        testInsertAtLoadFactor(0.75, 100000);
    }

    @Test
    @DisplayName("Insert throughput at 95% load factor")
    void testInsertAt95PercentLoadFactor() {
        testInsertAtLoadFactor(0.95, 100000);
    }

    @Test
    @DisplayName("Domain correctness: load factor is accurate")
    void testLoadFactorAccuracy() {
        int requestedCapacity = 100000;
        CuckooFilter filter = CuckooFilter.create(requestedCapacity);

        // Use the filter's actual capacity (which may differ from requested due to power-of-2 sizing)
        long actualCapacity = filter.capacity();

        // Insert 50% of actual capacity
        int targetCount = (int) (actualCapacity / 2);
        CuckooFilterInsertBenchmarkTestHelper helper = new CuckooFilterInsertBenchmarkTestHelper();
        for (int i = 0; i < targetCount; i++) {
            boolean inserted = filter.insert(helper.generateKey(i));
            assertThat(inserted).isTrue();
        }

        // Verify load factor
        double expectedLoadFactor = 0.5;
        double actualLoadFactor = filter.loadFactor();
        assertThat(actualLoadFactor).isCloseTo(expectedLoadFactor, within(0.01));

        // Verify entry count
        assertThat(filter.count()).isEqualTo(targetCount);
    }

    @Test
    @DisplayName("Domain correctness: inserted items are findable")
    void testInsertedItemsAreFindable() {
        int capacity = 10000;
        CuckooFilter filter = CuckooFilter.create(capacity);
        CuckooFilterInsertBenchmarkTestHelper helper = new CuckooFilterInsertBenchmarkTestHelper();

        // Insert items
        int count = 5000;
        for (int i = 0; i < count; i++) {
            filter.insert(helper.generateKey(i));
        }

        // All inserted items must be findable (no false negatives)
        for (int i = 0; i < count; i++) {
            assertThat(filter.contains(helper.generateKey(i)))
                .as("Inserted key at index " + i + " should be found")
                .isTrue();
        }
    }

    @Test
    @DisplayName("Benchmark measures insert throughput correctly")
    void testBenchmarkMeasuresThroughput() {
        int capacity = 100000;
        int batchSize = 1000;
        CuckooFilter filter = CuckooFilter.create(capacity);
        CuckooFilterInsertBenchmarkTestHelper helper = new CuckooFilterInsertBenchmarkTestHelper();

        // Pre-populate to 50% load factor
        long prePopulateCount = (long) (capacity * 0.5);
        for (long i = 0; i < prePopulateCount; i++) {
            filter.insert(helper.generateKey(i));
        }

        // Measure insert throughput
        int successCount = 0;
        int failCount = 0;
        long startTime = System.nanoTime();

        for (int i = 0; i < batchSize; i++) {
            byte[] key = helper.generateKey(prePopulateCount + i);
            if (filter.insert(key)) {
                successCount++;
            } else {
                failCount++;
            }
        }

        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double opsPerSecond = batchSize / durationSeconds;

        // Log results
        System.out.println("Insert Throughput Benchmark Results:");
        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Duration: " + String.format("%.3f", durationSeconds * 1000) + " ms");
        System.out.println("  Throughput: " + String.format("%.0f", opsPerSecond) + " ops/sec");
        System.out.println("  Success: " + successCount);
        System.out.println("  Fail: " + failCount);

        // Domain correctness assertions
        // Throughput should be reasonable (at least 10K ops/sec for a fast operation)
        assertThat(opsPerSecond).isGreaterThan(10_000);

        // Success count should be reasonable at 50% load factor
        assertThat(successCount).isGreaterThan(batchSize / 2);
    }

    /**
     * Helper class to expose generateKey method for testing.
     */
    private static class CuckooFilterInsertBenchmarkTestHelper {
        /**
         * Generates a unique key for the given index.
         * Same logic as CuckooFilterInsertBenchmark.generateKey.
         */
        byte[] generateKey(long index) {
            byte[] key = new byte[16];

            // First 8 bytes: fixed prefix "CUCKBKEY"
            key[0] = 'C';
            key[1] = 'U';
            key[2] = 'C';
            key[3] = 'K';
            key[4] = 'B';
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
    }

    /**
     * Tests insert operations at a specific load factor.
     */
    private void testInsertAtLoadFactor(double targetLoadFactor, int requestedCapacity) {
        CuckooFilter filter = CuckooFilter.create(requestedCapacity);
        CuckooFilterInsertBenchmarkTestHelper helper = new CuckooFilterInsertBenchmarkTestHelper();

        // Use the filter's actual capacity (which may differ from requested due to power-of-2 sizing)
        long actualCapacity = filter.capacity();

        // Pre-populate to target load factor
        long targetEntries = (long) (actualCapacity * targetLoadFactor);
        for (long i = 0; i < targetEntries; i++) {
            boolean inserted = filter.insert(helper.generateKey(i));
            assertThat(inserted)
                .as("Insert should succeed at load factor " + targetLoadFactor)
                .isTrue();
        }

        // Verify load factor is within 1% of target
        double actualLoadFactor = filter.loadFactor();
        assertThat(actualLoadFactor)
            .as("Load factor should be close to " + targetLoadFactor)
            .isCloseTo(targetLoadFactor, within(0.01));

        // Measure insert throughput for new keys
        int batchSize = 1000;
        int successCount = 0;

        long startTime = System.nanoTime();
        for (int i = 0; i < batchSize; i++) {
            if (filter.insert(helper.generateKey(targetEntries + i))) {
                successCount++;
            }
        }
        long endTime = System.nanoTime();

        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        double opsPerSecond = batchSize / durationSeconds;

        System.out.println(String.format(
            "Load factor %.0f%%: %d/%d successful inserts, %.0f ops/sec",
            targetLoadFactor * 100, successCount, batchSize, opsPerSecond));

        // At lower load factors, most inserts should succeed
        if (targetLoadFactor < 0.9) {
            assertThat(successCount)
                .as("Most inserts should succeed at load factor " + targetLoadFactor)
                .isGreaterThan(batchSize / 2);
        }
    }
}
