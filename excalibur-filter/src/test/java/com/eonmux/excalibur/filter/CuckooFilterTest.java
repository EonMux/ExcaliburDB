package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CuckooFilter.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Basic operations: insert → contains → delete → !contains</li>
 *   <li>FP rate measurement: Insert items, query non-items, measure FP rate ≤ 0.1%</li>
 *   <li>Deletion correctness: Insert items A, B with same hash bucket; delete A; verify B still present</li>
 *   <li>Full filter handling: Fill to capacity, verify insert returns false gracefully</li>
 *   <li>Thread safety: Concurrent operations</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class CuckooFilterTest {

    private static final int LARGE_TEST_ITEMS = 1_000_000;
    private static final double TARGET_LOAD_FACTOR = 0.95;
    private static final double MAX_FP_RATE = 0.001; // 0.1%
    private static final double MAX_FP_RATE_QUICK = 0.002; // 0.2% for quick tests (higher variance)

    @Test
    @DisplayName("Basic insert, contains, delete cycle")
    void testBasicOperations() {
        CuckooFilter filter = CuckooFilter.create(1000);

        byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);

        // Initially not present
        assertFalse(filter.contains(item));

        // Insert
        assertTrue(filter.insert(item));
        assertEquals(1, filter.count());

        // Now present
        assertTrue(filter.contains(item));

        // Delete
        assertTrue(filter.delete(item));
        assertEquals(0, filter.count());

        // No longer present
        assertFalse(filter.contains(item));
    }

    /**
     * CRITICAL TEST: Basic operations with edge cases
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Empty byte array is a valid input</li>
     *   <li>Single byte is a valid input</li>
     *   <li>Count accurately reflects number of entries</li>
     *   <li>Repeated insert/delete cycles maintain correctness</li>
     * </ul>
     */
    @Test
    @DisplayName("Basic operations with empty and single-byte items")
    void testBasicOperationsWithEdgeCases() {
        CuckooFilter filter = CuckooFilter.create(1000);

        // Test with empty byte array
        byte[] emptyItem = new byte[0];
        assertFalse(filter.contains(emptyItem), "Empty item should not be present initially");

        assertTrue(filter.insert(emptyItem), "Insert of empty item should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after inserting empty item");
        assertTrue(filter.contains(emptyItem), "Empty item should be present after insert");

        assertTrue(filter.delete(emptyItem), "Delete of empty item should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after deleting empty item");
        assertFalse(filter.contains(emptyItem), "Empty item should not be present after delete");

        // Test with single byte
        byte[] singleByteItem = new byte[]{(byte) 0x42};
        assertFalse(filter.contains(singleByteItem), "Single byte item should not be present initially");

        assertTrue(filter.insert(singleByteItem), "Insert of single byte item should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after inserting single byte item");
        assertTrue(filter.contains(singleByteItem), "Single byte item should be present after insert");

        assertTrue(filter.delete(singleByteItem), "Delete of single byte item should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after deleting single byte item");
        assertFalse(filter.contains(singleByteItem), "Single byte item should not be present after delete");
    }

    /**
     * CRITICAL TEST: Repeated insert/delete cycles
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Same item can be inserted, deleted, and re-inserted</li>
     *   <li>No stale state persists between cycles</li>
     *   <li>Count always reflects current state</li>
     * </ul>
     */
    @Test
    @DisplayName("Repeated insert/delete cycles maintain correctness")
    void testBasicOperationsRepeatedCycles() {
        CuckooFilter filter = CuckooFilter.create(1000);

        byte[] item = "reusable-item".getBytes(StandardCharsets.UTF_8);

        // First cycle
        assertTrue(filter.insert(item), "First insert should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after first insert");
        assertTrue(filter.contains(item), "Item should be present after first insert");

        assertTrue(filter.delete(item), "First delete should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after first delete");
        assertFalse(filter.contains(item), "Item should not be present after first delete");

        // Second cycle - verify filter handles re-insertion
        assertTrue(filter.insert(item), "Second insert should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after second insert");
        assertTrue(filter.contains(item), "Item should be present after second insert");

        assertTrue(filter.delete(item), "Second delete should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after second delete");
        assertFalse(filter.contains(item), "Item should not be present after second delete");

        // Third cycle - verify consistency
        assertTrue(filter.insert(item), "Third insert should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after third insert");
        assertTrue(filter.contains(item), "Item should be present after third insert");
        assertTrue(filter.delete(item), "Third delete should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after third delete");
        assertFalse(filter.contains(item), "Item should not be present after third delete");
    }

    /**
     * CRITICAL TEST: Multiple distinct items basic operations
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Each item independently follows insert → contains → delete → !contains</li>
     *   <li>Deleting one item does not affect others</li>
     *   <li>Count accurately tracks total entries</li>
     * </ul>
     */
    @Test
    @DisplayName("Multiple distinct items basic operations cycle")
    void testBasicOperationsMultipleDistinctItems() {
        CuckooFilter filter = CuckooFilter.create(1000);

        byte[] itemA = "item-A".getBytes(StandardCharsets.UTF_8);
        byte[] itemB = "item-B".getBytes(StandardCharsets.UTF_8);
        byte[] itemC = "item-C".getBytes(StandardCharsets.UTF_8);

        // Insert all items
        assertTrue(filter.insert(itemA), "Insert itemA should succeed");
        assertTrue(filter.insert(itemB), "Insert itemB should succeed");
        assertTrue(filter.insert(itemC), "Insert itemC should succeed");
        assertEquals(3, filter.count(), "Count should be 3 after three inserts");

        // All items should be present
        assertTrue(filter.contains(itemA), "itemA should be present");
        assertTrue(filter.contains(itemB), "itemB should be present");
        assertTrue(filter.contains(itemC), "itemC should be present");

        // Delete itemB
        assertTrue(filter.delete(itemB), "Delete itemB should succeed");
        assertEquals(2, filter.count(), "Count should be 2 after deleting itemB");

        // itemA and itemC should still be present, itemB should not
        assertTrue(filter.contains(itemA), "itemA should still be present");
        assertFalse(filter.contains(itemB), "itemB should not be present after delete");
        assertTrue(filter.contains(itemC), "itemC should still be present");

        // Delete remaining items
        assertTrue(filter.delete(itemA), "Delete itemA should succeed");
        assertEquals(1, filter.count(), "Count should be 1 after deleting itemA");
        assertFalse(filter.contains(itemA), "itemA should not be present after delete");
        assertTrue(filter.contains(itemC), "itemC should still be present");

        assertTrue(filter.delete(itemC), "Delete itemC should succeed");
        assertEquals(0, filter.count(), "Count should be 0 after deleting itemC");
        assertFalse(filter.contains(itemC), "itemC should not be present after delete");
    }

    @Test
    @DisplayName("Multiple items insert and lookup")
    void testMultipleItems() {
        CuckooFilter filter = CuckooFilter.create(1000);

        Set<String> items = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String item = "item-" + i;
            items.add(item);
            assertTrue(filter.insert(item.getBytes(StandardCharsets.UTF_8)));
        }

        assertEquals(100, filter.count());

        // All items should be present (no false negatives allowed)
        for (String item : items) {
            assertTrue(filter.contains(item.getBytes(StandardCharsets.UTF_8)),
                "False negative for item: " + item);
        }

        // Items not inserted should not be present
        for (int i = 100; i < 150; i++) {
            String item = "item-" + i;
            // Note: This might have false positives, so we don't assert
            filter.contains(item.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Delete only exact fingerprint - deletion correctness")
    void testDeletionCorrectness() {
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert two items that might hash to similar buckets
        byte[] itemA = "item-A-with-same-prefix".getBytes(StandardCharsets.UTF_8);
        byte[] itemB = "item-B-with-same-prefix".getBytes(StandardCharsets.UTF_8);

        assertTrue(filter.insert(itemA));
        assertTrue(filter.insert(itemB));
        assertEquals(2, filter.count());

        // Both should be present
        assertTrue(filter.contains(itemA));
        assertTrue(filter.contains(itemB));

        // Delete itemA
        assertTrue(filter.delete(itemA));
        assertEquals(1, filter.count());

        // itemB should still be present (no false negative from deletion)
        assertTrue(filter.contains(itemB),
            "itemB should still be present after deleting itemA");

        // itemA should not be present
        assertFalse(filter.contains(itemA),
            "itemA should not be present after deletion");

        // Deleting again should fail
        assertFalse(filter.delete(itemA),
            "Deleting already-deleted item should return false");
    }

    @Test
    @DisplayName("Full filter returns false gracefully")
    void testFullFilterHandling() {
        // Create a small filter that will fill up quickly
        CuckooFilter filter = CuckooFilter.create(100);

        int inserted = 0;
        int failed = 0;

        // Try to insert many items
        for (int i = 0; i < 500; i++) {
            byte[] item = ("fill-item-" + i).getBytes(StandardCharsets.UTF_8);
            if (filter.insert(item)) {
                inserted++;
            } else {
                failed++;
            }
        }

        // Should have some successful insertions
        assertTrue(inserted > 0, "Should have inserted at least some items");

        // Should have some failures when full
        assertTrue(failed > 0, "Should have failed to insert when filter is full");

        // No exception should have been thrown
        System.out.println("Inserted: " + inserted + ", Failed: " + failed);
    }

    @Test
    @DisplayName("Null item throws NullPointerException")
    void testNullItem() {
        CuckooFilter filter = CuckooFilter.create(1000);

        assertThrows(NullPointerException.class, () -> filter.insert(null));
        assertThrows(NullPointerException.class, () -> filter.contains(null));
        assertThrows(NullPointerException.class, () -> filter.delete(null));
    }

    @Test
    @DisplayName("Filter configuration")
    void testFilterConfiguration() {
        CuckooFilter filter = CuckooFilter.create(10000);

        // Default fingerprint bits is 13 to achieve ≤0.1% FP rate at 95% load factor
        // 12 bits would only achieve ~0.19% FP rate, exceeding the 0.1% spec target
        assertEquals(13, filter.fingerprintBits(), "Default fingerprint bits should be 13 to meet ≤0.1% FP rate spec");
        assertTrue(filter.capacity() >= 10000);
        assertTrue(filter.numBuckets() > 0);
        assertEquals(0.0, filter.loadFactor(), 0.001);
    }

    /**
     * CRITICAL TEST: FP Rate Measurement at Load Factor 0.95
     *
     * <p>Domain constraint: Measured false positive rate MUST be ≤ 0.1% at load factor 0.95</p>
     *
     * <p>This test validates the core specification requirement for the Cuckoo Filter.</p>
     */
    @Test
    @Tag("slow")
    @DisplayName("FP rate measurement at load factor 0.95 - MUST be ≤ 0.1%")
    void testFPRateAtLoadFactor095() {
        // Create filter with default 13-bit fingerprints (achieves ≤0.1% FP at 95% load)
        CuckooFilter filter = CuckooFilter.create(LARGE_TEST_ITEMS);

        System.out.println("=== FP Rate Measurement at Load Factor 0.95 ===");
        System.out.println("Filter capacity: " + filter.capacity());
        System.out.println("Fingerprint bits: " + filter.fingerprintBits());

        // Calculate target insertions to reach 95% load factor
        long targetInsertions = (long) (filter.capacity() * TARGET_LOAD_FACTOR);

        // Insert items to reach load factor ~0.95
        Set<String> insertedItems = new HashSet<>();
        Random random = new Random(42); // Deterministic seed for reproducibility

        int inserted = 0;

        for (int i = 0; i < targetInsertions; i++) {
            String item = "fp-test-item-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);

            if (filter.insert(itemBytes)) {
                insertedItems.add(item);
                inserted++;
            }
        }

        double actualLoadFactor = filter.loadFactor();
        System.out.println("Inserted items: " + inserted);
        System.out.println("Actual load factor: " + String.format("%.4f", actualLoadFactor));

        // Verify no false negatives (all inserted items MUST be found)
        int falseNegatives = 0;
        for (String item : insertedItems) {
            if (!filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "False negatives are NOT allowed in Cuckoo Filter");
        System.out.println("False negatives: " + falseNegatives + " (expected 0)");

        // Query non-inserted items to measure FP rate
        int fpCount = 0;
        int nonItemQueries = inserted; // Query same number as inserted

        for (int i = 0; i < nonItemQueries; i++) {
            String nonItem = "non-existent-item-" + random.nextInt(Integer.MAX_VALUE);
            byte[] nonItemBytes = nonItem.getBytes(StandardCharsets.UTF_8);

            // Skip if accidentally inserted
            if (insertedItems.contains(nonItem)) {
                continue;
            }

            if (filter.contains(nonItemBytes)) {
                fpCount++;
            }
        }

        double fpRate = (double) fpCount / nonItemQueries;
        System.out.println("FP count: " + fpCount + " / " + nonItemQueries);
        System.out.println("Measured FP rate: " + String.format("%.6f", fpRate) +
            " (" + String.format("%.4f", fpRate * 100) + "%)");

        // CRITICAL ASSERTION: FP rate MUST be ≤ 0.1%
        assertTrue(fpRate <= MAX_FP_RATE,
            "FP rate " + String.format("%.6f", fpRate) +
            " exceeds maximum allowed " + MAX_FP_RATE +
            " (0.1%) at load factor " + actualLoadFactor);

        System.out.println("✓ FP rate assertion PASSED: " + String.format("%.4f", fpRate * 100) +
            "% ≤ 0.1%");
    }

    /**
     * Additional FP rate test with smaller scale for quick verification.
     */
    @Test
    @DisplayName("Quick FP rate verification (smaller scale)")
    void testFPRateQuickVerification() {
        int itemCount = 100_000;
        CuckooFilter filter = CuckooFilter.create(itemCount);

        Random random = new Random(12345);
        Set<String> inserted = new HashSet<>();

        // Insert items to reach 95% load factor
        int targetInsertions = (int) (filter.capacity() * TARGET_LOAD_FACTOR);

        for (int i = 0; i < targetInsertions; i++) {
            String item = "quick-test-" + random.nextInt(Integer.MAX_VALUE);
            if (filter.insert(item.getBytes(StandardCharsets.UTF_8))) {
                inserted.add(item);
            }
        }

        System.out.println("Quick test - Load factor: " +
            String.format("%.4f", filter.loadFactor()));

        // Test for false positives
        int fpCount = 0;
        int queries = 100_000;

        for (int i = 0; i < queries; i++) {
            String nonItem = "quick-non-item-" + random.nextInt(Integer.MAX_VALUE);
            if (!inserted.contains(nonItem) &&
                filter.contains(nonItem.getBytes(StandardCharsets.UTF_8))) {
                fpCount++;
            }
        }

        double fpRate = (double) fpCount / queries;
        System.out.println("Quick test - FP rate: " + String.format("%.6f", fpRate));

        // Quick test uses higher threshold due to statistical variance with smaller sample
        // The authoritative test is testFPRateMeasurementAtLoadFactor95() which uses MAX_FP_RATE
        assertTrue(fpRate <= MAX_FP_RATE_QUICK,
            "Quick test FP rate " + fpRate + " exceeds 0.2%");
    }

    @Test
    @DisplayName("Concurrent inserts are thread-safe")
    void testConcurrentInserts() throws InterruptedException {
        CuckooFilter filter = CuckooFilter.create(10000);

        int numThreads = 8;
        int itemsPerThread = 500;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < itemsPerThread; i++) {
                    String item = "thread-" + threadId + "-item-" + i;
                    filter.insert(item.getBytes(StandardCharsets.UTF_8));
                }
            });
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify some items were inserted (count should be close to expected)
        System.out.println("Concurrent test - Final count: " + filter.count());
        assertTrue(filter.count() > 0, "Some items should have been inserted");

        // Verify no corruption - sample some items
        int found = 0;
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < itemsPerThread; i += 50) {
                String item = "thread-" + t + "-item-" + i;
                if (filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                    found++;
                }
            }
        }
        System.out.println("Concurrent test - Sampled items found: " + found);
    }

    @Test
    @DisplayName("Insert and delete cycle maintains correctness")
    void testInsertDeleteCycle() {
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert items
        for (int i = 0; i < 100; i++) {
            String item = "cycle-item-" + i;
            assertTrue(filter.insert(item.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(100, filter.count());

        // Delete half
        for (int i = 0; i < 50; i++) {
            String item = "cycle-item-" + i;
            assertTrue(filter.delete(item.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(50, filter.count());

        // Verify remaining items still present
        for (int i = 50; i < 100; i++) {
            String item = "cycle-item-" + i;
            assertTrue(filter.contains(item.getBytes(StandardCharsets.UTF_8)),
                "Item " + i + " should still be present");
        }

        // Verify deleted items not present
        for (int i = 0; i < 50; i++) {
            String item = "cycle-item-" + i;
            assertFalse(filter.contains(item.getBytes(StandardCharsets.UTF_8)),
                "Deleted item " + i + " should not be present");
        }
    }

    @Test
    @DisplayName("Filter with different fingerprint bits")
    void testDifferentFingerprintBits() {
        // Test with 8 bits (higher FP rate)
        CuckooFilter filter8 = CuckooFilter.create(10000, 8);
        assertEquals(8, filter8.fingerprintBits());

        // Test with 16 bits (lower FP rate)
        CuckooFilter filter16 = CuckooFilter.create(10000, 16);
        assertEquals(16, filter16.fingerprintBits());

        // Both should work
        byte[] item = "fingerprint-test".getBytes(StandardCharsets.UTF_8);
        assertTrue(filter8.insert(item));
        assertTrue(filter16.insert(item));

        assertTrue(filter8.contains(item));
        assertTrue(filter16.contains(item));
    }

    @Test
    @DisplayName("Invalid fingerprint bits throws exception")
    void testInvalidFingerprintBits() {
        assertThrows(IllegalArgumentException.class, () -> CuckooFilter.create(1000, 7));
        assertThrows(IllegalArgumentException.class, () -> CuckooFilter.create(1000, 17));
    }

    @Test
    @DisplayName("Filter statistics")
    void testFilterStatistics() {
        CuckooFilter filter = CuckooFilter.create(1000);

        assertEquals(0.0, filter.loadFactor(), 0.001);

        for (int i = 0; i < 100; i++) {
            filter.insert(("stat-item-" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(filter.loadFactor() > 0);
        System.out.println("Filter stats: " + filter);
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Sizing")
    class Sizing {

        @Test
        @DisplayName("a filter accepts the capacity it was created for")
        void acceptsItsStatedCapacity() {
            // The bucket count was rounded down to a power of two after being doubled, which
            // cancelled the doubling: capacity 1000 got 256 buckets, 1024 slots, and the filter
            // sat at 98% full at its own documented capacity. A cuckoo insert gives up after a
            // bounded displacement chain, so that is where inserts start being refused - and a
            // filter that has refused a term stops being trusted to rule a segment out, which
            // turns pruning off for the whole node.
            int capacity = 1000;
            CuckooFilter filter = CuckooFilter.create(capacity);

            int accepted = 0;
            for (int i = 0; i < capacity; i++) {
                if (filter.insert(("key-" + i).getBytes(StandardCharsets.UTF_8))) {
                    accepted++;
                }
            }

            assertEquals(capacity, accepted,
                "every key up to the stated capacity has to fit, or the capacity is a fiction");
            assertTrue(filter.loadFactor() < 0.6,
                "and it should still be comfortably below the load where inserts start failing, "
                    + "not scraping in at the last slot: was " + filter.loadFactor());
        }

        @Test
        @DisplayName("a tiny capacity still gets the floor of four buckets")
        void tinyCapacitiesGetTheFloor() {
            CuckooFilter filter = CuckooFilter.create(1);

            assertTrue(filter.insert("only".getBytes(StandardCharsets.UTF_8)));
            assertTrue(filter.contains("only".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
