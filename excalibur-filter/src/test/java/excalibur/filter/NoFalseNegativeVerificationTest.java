package excalibur.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRITICAL TEST SUITE: No False Negatives Allowed
 *
 * <h2>Purpose</h2>
 * <p>This test suite validates the fundamental invariant of Cuckoo Filters:
 * <b>All inserted items MUST be found - no false negatives are allowed.</b></p>
 *
 * <h2>Domain Correctness</h2>
 * <p>A false negative would mean that contains(item) returns false for an item
 * that was successfully inserted. This is a critical correctness violation
 * because Cuckoo Filters guarantee 100% recall for inserted items.</p>
 *
 * <h2>Test Categories</h2>
 * <ol>
 *   <li>Basic single-item operations</li>
 *   <li>Multiple items at various scales</li>
 *   <li>Edge cases: empty bytes, single byte, collisions</li>
 *   <li>After deletion operations</li>
 *   <li>After displacement operations (high load factor)</li>
 *   <li>Concurrent operations</li>
 *   <li>Serialization round-trip</li>
 *   <li>Stress testing at high load factors</li>
 * </ol>
 *
 * @author ExcaliburDB
 */
@DisplayName("CRITICAL: No False Negatives Verification")
class NoFalseNegativeVerificationTest {

    // ==================== Constants ====================

    private static final double TARGET_LOAD_FACTOR = 0.95;
    private static final double HIGH_LOAD_FACTOR = 0.85;
    private static final String ASSERTION_MESSAGE = "FALSE NEGATIVE DETECTED: Inserted item not found - this is a critical correctness violation";

    // ==================== Helper Methods ====================

    /**
     * Generates a unique byte array for testing.
     *
     * @param seed The seed for deterministic generation
     * @return A unique byte array
     */
    private byte[] generateItem(int seed) {
        return ("item-" + seed + "-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generates a deterministic byte array for testing.
     *
     * @param seed The seed for deterministic generation
     * @return A deterministic byte array
     */
    private byte[] generateDeterministicItem(int seed) {
        return ("deterministic-item-" + seed).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Asserts that all inserted items are found (no false negatives).
     *
     * @param filter        The CuckooFilter to test
     * @param insertedItems The set of inserted items
     * @param context       Context string for error messages
     */
    private void assertNoFalseNegatives(CuckooFilter filter, Set<byte[]> insertedItems, String context) {
        int falseNegatives = 0;
        List<String> failedItems = new ArrayList<>();

        for (byte[] item : insertedItems) {
            if (!filter.contains(item)) {
                falseNegatives++;
                if (failedItems.size() < 10) { // Limit output
                    failedItems.add(new String(item, StandardCharsets.UTF_8));
                }
            }
        }

        if (falseNegatives > 0) {
            fail(String.format(
                "%s: %d FALSE NEGATIVES DETECTED out of %d items. " +
                "Sample failed items: %s. This is a CRITICAL correctness violation.",
                context, falseNegatives, insertedItems.size(),
                failedItems.subList(0, Math.min(5, failedItems.size()))
            ));
        }
    }

    /**
     * Tracks inserted items with their byte array content for verification.
     */
    private static class InsertionTracker {
        final Set<String> itemKeys = new HashSet<>();
        final List<byte[]> itemBytes = new ArrayList<>();

        void add(byte[] item) {
            String key = bytesToHex(item);
            if (!itemKeys.contains(key)) {
                itemKeys.add(key);
                itemBytes.add(item.clone());
            }
        }

        int count() {
            return itemBytes.size();
        }

        List<byte[]> items() {
            return Collections.unmodifiableList(itemBytes);
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    // ==================== Basic Single Item Tests ====================

    @Nested
    @DisplayName("Single Item Operations")
    class SingleItemTests {

        @Test
        @DisplayName("Single item: insert then contains returns true")
        void testSingleItemBasic() {
            CuckooFilter filter = CuckooFilter.create(1000);
            byte[] item = "single-test-item".getBytes(StandardCharsets.UTF_8);

            assertTrue(filter.insert(item), "Insert should succeed");
            assertTrue(filter.contains(item), ASSERTION_MESSAGE);
        }

        @Test
        @DisplayName("Single item: empty byte array")
        void testEmptyByteArray() {
            CuckooFilter filter = CuckooFilter.create(1000);
            byte[] emptyItem = new byte[0];

            assertTrue(filter.insert(emptyItem), "Insert of empty array should succeed");
            assertTrue(filter.contains(emptyItem), ASSERTION_MESSAGE + " (empty byte array)");
        }

        @Test
        @DisplayName("Single item: single byte")
        void testSingleByte() {
            CuckooFilter filter = CuckooFilter.create(1000);

            for (int b = Byte.MIN_VALUE; b <= Byte.MAX_VALUE; b++) {
                byte[] item = new byte[]{(byte) b};
                assertTrue(filter.insert(item), "Insert should succeed for byte " + b);
            }

            // Verify all single bytes are found
            for (int b = Byte.MIN_VALUE; b <= Byte.MAX_VALUE; b++) {
                byte[] item = new byte[]{(byte) b};
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " (single byte: " + b + ")");
            }
        }

        @Test
        @DisplayName("Single item: various lengths")
        void testVariousLengths() {
            CuckooFilter filter = CuckooFilter.create(10000);

            // Test items of various lengths
            for (int len = 1; len <= 1000; len++) {
                byte[] item = new byte[len];
                for (int i = 0; i < len; i++) {
                    item[i] = (byte) (i % 256);
                }
                assertTrue(filter.insert(item), "Insert should succeed for length " + len);
            }

            // Verify all items of various lengths are found
            for (int len = 1; len <= 1000; len++) {
                byte[] item = new byte[len];
                for (int i = 0; i < len; i++) {
                    item[i] = (byte) (i % 256);
                }
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " (length: " + len + ")");
            }
        }

        @RepeatedTest(10)
        @DisplayName("Single item: random bytes")
        void testRandomSingleItem() {
            CuckooFilter filter = CuckooFilter.create(1000);
            Random random = new Random();

            byte[] item = new byte[32];
            random.nextBytes(item);

            assertTrue(filter.insert(item), "Insert should succeed");
            assertTrue(filter.contains(item), ASSERTION_MESSAGE + " (random bytes)");
        }
    }

    // ==================== Multiple Items Tests ====================

    @Nested
    @DisplayName("Multiple Items Operations")
    class MultipleItemsTests {

        @Test
        @DisplayName("10 items: all must be found")
        void test10Items() {
            testNItems(10);
        }

        @Test
        @DisplayName("100 items: all must be found")
        void test100Items() {
            testNItems(100);
        }

        @Test
        @DisplayName("1000 items: all must be found")
        void test1000Items() {
            testNItems(1000);
        }

        @Test
        @DisplayName("10000 items: all must be found")
        void test10000Items() {
            testNItems(10000);
        }

        @Test
        @DisplayName("100000 items: all must be found")
        void test100000Items() {
            testNItems(100000);
        }

        private void testNItems(int n) {
            CuckooFilter filter = CuckooFilter.create(n);
            InsertionTracker tracker = new InsertionTracker();

            // Insert all items
            int inserted = 0;
            for (int i = 0; i < n; i++) {
                byte[] item = generateDeterministicItem(i);
                if (filter.insert(item)) {
                    tracker.add(item);
                    inserted++;
                }
            }

            System.out.println("Test " + n + " items: inserted " + inserted + " items");

            // CRITICAL: Verify all inserted items are found
            for (byte[] item : tracker.items()) {
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " at scale " + n);
            }
        }

        @Test
        @DisplayName("Sequential integers as items")
        void testSequentialIntegers() {
            CuckooFilter filter = CuckooFilter.create(10000);
            InsertionTracker tracker = new InsertionTracker();

            // Insert items representing integers 0 to 9999
            for (int i = 0; i < 10000; i++) {
                byte[] item = intToBytes(i);
                if (filter.insert(item)) {
                    tracker.add(item);
                }
            }

            // Verify all are found
            for (byte[] item : tracker.items()) {
                assertTrue(filter.contains(item), ASSERTION_MESSAGE);
            }
        }

        private byte[] intToBytes(int value) {
            return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
            };
        }
    }

    // ==================== High Load Factor Tests ====================

    @Nested
    @DisplayName("High Load Factor Operations (Displacement Stress)")
    class HighLoadFactorTests {

        @Test
        @DisplayName("Load factor 0.85: all items must be found")
        void testLoadFactor085() {
            testLoadFactor(10000, 0.85);
        }

        @Test
        @DisplayName("Load factor 0.90: all items must be found")
        void testLoadFactor090() {
            testLoadFactor(10000, 0.90);
        }

        @Test
        @DisplayName("Load factor 0.95: all items must be found")
        void testLoadFactor095() {
            testLoadFactor(10000, 0.95);
        }

        @Test
        @DisplayName("Large scale at 95% load factor: 100K items")
        void testLargeScaleHighLoadFactor() {
            testLoadFactor(100000, 0.95);
        }

        private void testLoadFactor(int capacity, double targetLoadFactor) {
            CuckooFilter filter = CuckooFilter.create(capacity);
            InsertionTracker tracker = new InsertionTracker();
            Random random = new Random(42); // Deterministic

            long targetInsertions = (long) (filter.capacity() * targetLoadFactor);
            int inserted = 0;
            int failed = 0;

            // Insert items until we reach target load factor
            for (int i = 0; i < targetInsertions * 2 && inserted < targetInsertions; i++) {
                byte[] item = ("load-test-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    tracker.add(item);
                    inserted++;
                } else {
                    failed++;
                }
            }

            double actualLoadFactor = filter.loadFactor();
            System.out.println("Load factor test: target=" + targetLoadFactor +
                ", actual=" + String.format("%.4f", actualLoadFactor) +
                ", inserted=" + inserted + ", failed=" + failed);

            // CRITICAL: Verify ALL inserted items are found
            int falseNegatives = 0;
            for (byte[] item : tracker.items()) {
                if (!filter.contains(item)) {
                    falseNegatives++;
                }
            }

            assertEquals(0, falseNegatives,
                "FALSE NEGATIVES at load factor " + actualLoadFactor + ": " +
                falseNegatives + " items not found out of " + inserted);
        }
    }

    // ==================== After Deletion Tests ====================

    @Nested
    @DisplayName("After Deletion Operations")
    class AfterDeletionTests {

        @Test
        @DisplayName("Delete some items: remaining items must all be found")
        void testAfterPartialDeletion() {
            CuckooFilter filter = CuckooFilter.create(1000);
            InsertionTracker allItems = new InsertionTracker();
            List<byte[]> itemsToDelete = new ArrayList<>();

            // Insert 500 items
            for (int i = 0; i < 500; i++) {
                byte[] item = generateDeterministicItem(i);
                if (filter.insert(item)) {
                    allItems.add(item);
                    if (i % 2 == 0) {
                        itemsToDelete.add(item);
                    }
                }
            }

            // Delete half the items
            for (byte[] item : itemsToDelete) {
                filter.delete(item);
            }

            // Track remaining items (odd items were not deleted)
            InsertionTracker remainingItems = new InsertionTracker();
            for (int i = 0; i < 500; i++) {
                byte[] item = generateDeterministicItem(i);
                if (i % 2 == 1) { // Odd items were not deleted
                    remainingItems.add(item);
                }
            }

            // CRITICAL: All remaining items must be found (NO FALSE NEGATIVES)
            // This is the core guarantee: items that were inserted and NOT deleted
            // must always be found by contains()
            for (byte[] item : remainingItems.items()) {
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " after deleting other items");
            }

            // NOTE: We do NOT assert that deleted items are NOT found.
            // Cuckoo filters allow false positives - a deleted item might still
            // "match" if another item with the same fingerprint exists.
            // This test focuses on NO FALSE NEGATIVES for remaining items.
        }

        @Test
        @DisplayName("Delete and re-insert cycle: all items must be found")
        void testDeleteAndReinsertCycle() {
            CuckooFilter filter = CuckooFilter.create(1000);

            // Phase 1: Insert items
            for (int i = 0; i < 100; i++) {
                byte[] item = generateDeterministicItem(i);
                filter.insert(item);
            }

            // Verify all found
            for (int i = 0; i < 100; i++) {
                byte[] item = generateDeterministicItem(i);
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " phase 1");
            }

            // Phase 2: Delete all
            for (int i = 0; i < 100; i++) {
                byte[] item = generateDeterministicItem(i);
                filter.delete(item);
            }

            // Phase 3: Re-insert all
            for (int i = 0; i < 100; i++) {
                byte[] item = generateDeterministicItem(i);
                filter.insert(item);
            }

            // CRITICAL: All items must still be found after delete/re-insert cycle
            for (int i = 0; i < 100; i++) {
                byte[] item = generateDeterministicItem(i);
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " after delete/re-insert cycle");
            }
        }

        @Test
        @DisplayName("Selective deletion with collision items")
        void testSelectiveDeletionWithCollisions() {
            CuckooFilter filter = CuckooFilter.create(100);

            // Insert items that may hash to similar buckets
            List<byte[]> items = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                // Use similar prefixes to increase collision likelihood
                byte[] item = ("prefix-" + i).getBytes(StandardCharsets.UTF_8);
                filter.insert(item);
                items.add(item);
            }

            // Verify all found initially
            for (byte[] item : items) {
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " before deletion");
            }

            // Delete every other item
            List<byte[]> remainingItems = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (i % 2 == 0) {
                    filter.delete(items.get(i));
                } else {
                    remainingItems.add(items.get(i));
                }
            }

            // CRITICAL: Remaining items must all be found (NO FALSE NEGATIVES)
            // This is the core guarantee: items that were inserted and NOT deleted
            // must always be found by contains()
            for (byte[] item : remainingItems) {
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " after selective deletion - remaining item not found");
            }

            // NOTE: We do NOT assert that deleted items are NOT found.
            // Cuckoo filters allow false positives - a deleted item might still
            // "match" if another item with the same fingerprint exists.
            // This test focuses on NO FALSE NEGATIVES for remaining items.
        }
    }

    // ==================== Concurrent Operations Tests ====================

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentTests {

        @Test
        @DisplayName("Concurrent inserts: all inserted items must be found")
        void testConcurrentInserts() throws InterruptedException {
            CuckooFilter filter = CuckooFilter.create(50000);
            int numThreads = 8;
            int itemsPerThread = 1000;

            ConcurrentHashMap<String, byte[]> allInsertedItems = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger totalInserted = new AtomicInteger(0);

            Thread[] threads = new Thread[numThreads];
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();

                        for (int i = 0; i < itemsPerThread; i++) {
                            byte[] item = ("concurrent-" + threadId + "-" + i)
                                .getBytes(StandardCharsets.UTF_8);
                            if (filter.insert(item)) {
                                allInsertedItems.put(
                                    new String(item, StandardCharsets.UTF_8),
                                    item.clone()
                                );
                                totalInserted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for completion
            doneLatch.await();

            System.out.println("Concurrent test: " + totalInserted.get() + " items inserted");

            // CRITICAL: ALL inserted items must be found
            int falseNegatives = 0;
            for (byte[] item : allInsertedItems.values()) {
                if (!filter.contains(item)) {
                    falseNegatives++;
                }
            }

            assertEquals(0, falseNegatives,
                "FALSE NEGATIVES in concurrent test: " + falseNegatives +
                " out of " + totalInserted.get() + " items");
        }

        @Test
        @DisplayName("Concurrent read while inserting: all visible items must be found")
        void testConcurrentReadWhileInsert() throws InterruptedException {
            CuckooFilter filter = CuckooFilter.create(10000);

            // Pre-populate some items
            InsertionTracker prePopulated = new InsertionTracker();
            for (int i = 0; i < 1000; i++) {
                byte[] item = ("pre-" + i).getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    prePopulated.add(item);
                }
            }

            AtomicInteger readErrors = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            // Writer thread
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5000; i++) {
                        byte[] item = ("writer-" + i).getBytes(StandardCharsets.UTF_8);
                        filter.insert(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });

            // Reader thread - constantly verifies pre-populated items
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int iteration = 0; iteration < 1000; iteration++) {
                        for (byte[] item : prePopulated.items()) {
                            if (!filter.contains(item)) {
                                readErrors.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            doneLatch.await();

            assertEquals(0, readErrors.get(),
                "FALSE NEGATIVES during concurrent read: pre-populated items not found");
        }
    }

    // ==================== Serialization Round-Trip Tests ====================

    @Nested
    @DisplayName("Serialization Round-Trip")
    class SerializationTests {

        @Test
        @DisplayName("After serialization round-trip: all items must be found")
        void testSerializationRoundTrip() {
            CuckooFilter original = CuckooFilter.create(10000);
            InsertionTracker tracker = new InsertionTracker();

            // Insert items
            for (int i = 0; i < 5000; i++) {
                byte[] item = generateDeterministicItem(i);
                if (original.insert(item)) {
                    tracker.add(item);
                }
            }

            System.out.println("Serialization test: inserted " + tracker.count() + " items");

            // Serialize to memory segment
            long size = original.byteSize();
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared();
            java.lang.foreign.MemorySegment buffer = arena.allocate(size);
            original.writeTo(buffer, 0);

            // Deserialize
            CuckooFilter restored = CuckooFilter.readFrom(buffer, 0);

            // CRITICAL: All items must be found in restored filter
            int falseNegatives = 0;
            for (byte[] item : tracker.items()) {
                if (!restored.contains(item)) {
                    falseNegatives++;
                }
            }

            assertEquals(0, falseNegatives,
                "FALSE NEGATIVES after serialization: " + falseNegatives +
                " items not found in restored filter");

            arena.close();
        }

        @Test
        @DisplayName("Different fingerprint bits after serialization")
        void testDifferentFingerprintBitsSerialization() {
            for (int bits = 8; bits <= 16; bits++) {
                testSerializationWithBits(bits);
            }
        }

        private void testSerializationWithBits(int fingerprintBits) {
            CuckooFilter original = CuckooFilter.create(1000, fingerprintBits);
            InsertionTracker tracker = new InsertionTracker();

            // Insert items
            for (int i = 0; i < 500; i++) {
                byte[] item = ("bits-" + fingerprintBits + "-" + i).getBytes(StandardCharsets.UTF_8);
                if (original.insert(item)) {
                    tracker.add(item);
                }
            }

            // Serialize and restore
            long size = original.byteSize();
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared();
            java.lang.foreign.MemorySegment buffer = arena.allocate(size);
            original.writeTo(buffer, 0);
            CuckooFilter restored = CuckooFilter.readFrom(buffer, 0);

            // Verify all items found
            for (byte[] item : tracker.items()) {
                assertTrue(restored.contains(item),
                    ASSERTION_MESSAGE + " after serialization with " + fingerprintBits + " bits");
            }

            arena.close();
        }
    }

    // ==================== Stress Tests ====================

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("Insert-delete-insert cycles")
        void testInsertDeleteCycles() {
            CuckooFilter filter = CuckooFilter.create(1000);

            for (int cycle = 0; cycle < 10; cycle++) {
                // Insert items
                for (int i = 0; i < 100; i++) {
                    byte[] item = ("cycle-" + cycle + "-item-" + i).getBytes(StandardCharsets.UTF_8);
                    filter.insert(item);
                }

                // Verify all found
                for (int i = 0; i < 100; i++) {
                    byte[] item = ("cycle-" + cycle + "-item-" + i).getBytes(StandardCharsets.UTF_8);
                    assertTrue(filter.contains(item),
                        ASSERTION_MESSAGE + " in cycle " + cycle);
                }

                // Delete all
                for (int i = 0; i < 100; i++) {
                    byte[] item = ("cycle-" + cycle + "-item-" + i).getBytes(StandardCharsets.UTF_8);
                    filter.delete(item);
                }
            }
        }

        @Test
        @DisplayName("Fill to near capacity, verify all found")
        void testFillToNearCapacity() {
            CuckooFilter filter = CuckooFilter.create(10000);
            InsertionTracker tracker = new InsertionTracker();
            Random random = new Random(12345);

            // Keep inserting until we can't insert anymore
            int attempts = 0;
            int maxAttempts = 100000;

            while (attempts < maxAttempts) {
                byte[] item = ("capacity-test-" + random.nextInt(Integer.MAX_VALUE))
                    .getBytes(StandardCharsets.UTF_8);

                if (filter.insert(item)) {
                    tracker.add(item);
                }

                attempts++;

                // Stop when we've reached high load factor
                if (filter.loadFactor() > 0.90) {
                    break;
                }
            }

            double loadFactor = filter.loadFactor();
            System.out.println("Capacity test: load factor " + String.format("%.4f", loadFactor) +
                ", items " + tracker.count());

            // CRITICAL: ALL items must be found
            int falseNegatives = 0;
            for (byte[] item : tracker.items()) {
                if (!filter.contains(item)) {
                    falseNegatives++;
                }
            }

            assertEquals(0, falseNegatives,
                "FALSE NEGATIVES at load factor " + loadFactor + ": " + falseNegatives);
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 500, 1000, 5000, 10000})
        @DisplayName("Parameterized scale test: all items must be found")
        void testParameterizedScale(int count) {
            // Create filter with 2x capacity to ensure room for all items
            // (avoid displacement failures due to tight capacity)
            CuckooFilter filter = CuckooFilter.create(count * 2);
            InsertionTracker tracker = new InsertionTracker();

            for (int i = 0; i < count; i++) {
                byte[] item = ("scale-" + count + "-" + i).getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    tracker.add(item);
                }
            }

            // Verify all found
            for (byte[] item : tracker.items()) {
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " at scale " + count);
            }
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Duplicate inserts: item must still be found")
        void testDuplicateInserts() {
            CuckooFilter filter = CuckooFilter.create(100);
            byte[] item = "duplicate-item".getBytes(StandardCharsets.UTF_8);

            // Insert the same item multiple times
            for (int i = 0; i < 10; i++) {
                filter.insert(item);
            }

            // Must still be found
            assertTrue(filter.contains(item), ASSERTION_MESSAGE + " after duplicate inserts");
        }

        @Test
        @DisplayName("Binary data: all bytes from 0x00 to 0xFF")
        void testAllByteValues() {
            CuckooFilter filter = CuckooFilter.create(1000);

            // Create items with each possible byte value
            for (int b = 0; b < 256; b++) {
                byte[] item = new byte[]{(byte) b, (byte) b, (byte) b};
                filter.insert(item);
            }

            // Verify all found
            for (int b = 0; b < 256; b++) {
                byte[] item = new byte[]{(byte) b, (byte) b, (byte) b};
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " for byte value " + b);
            }
        }

        @Test
        @DisplayName("Unicode strings: all must be found")
        void testUnicodeStrings() {
            CuckooFilter filter = CuckooFilter.create(1000);
            String[] unicodeStrings = {
                "日本語",           // Japanese
                "한국어",           // Korean
                "العربية",         // Arabic
                "עברית",           // Hebrew
                "中文",             // Chinese
                "Ελληνικά",        // Greek
                "Русский",         // Russian
                "ไทย",             // Thai
                "🎉🔥💯",          // Emojis
                "mixed123abc"      // Mixed ASCII
            };

            for (String s : unicodeStrings) {
                byte[] item = s.getBytes(StandardCharsets.UTF_8);
                filter.insert(item);
            }

            for (String s : unicodeStrings) {
                byte[] item = s.getBytes(StandardCharsets.UTF_8);
                assertTrue(filter.contains(item),
                    ASSERTION_MESSAGE + " for unicode string: " + s);
            }
        }

        @Test
        @DisplayName("Large items: 10KB items must be found")
        void testLargeItems() {
            CuckooFilter filter = CuckooFilter.create(100);

            // Insert a few large items
            List<byte[]> largeItems = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                byte[] item = new byte[10 * 1024]; // 10KB
                for (int j = 0; j < item.length; j++) {
                    item[j] = (byte) ((i + j) % 256);
                }
                filter.insert(item);
                largeItems.add(item);
            }

            // All large items must be found
            for (byte[] item : largeItems) {
                assertTrue(filter.contains(item), ASSERTION_MESSAGE + " for large item");
            }
        }
    }
}
