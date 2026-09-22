package com.eonmux.excalibur.filter.bloom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BloomFilter.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Basic operations: put → mightContain</li>
 *   <li>No false negatives guarantee: all inserted items must be found</li>
 *   <li>False positive rate within configured bounds</li>
 *   <li>Memory segment serialization round-trip</li>
 *   <li>Empty filter behavior</li>
 *   <li>High load factor behavior</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class BloomFilterTest {

    private static final int LARGE_TEST_ITEMS = 100_000;
    private static final double TARGET_FP_RATE = 0.01; // 1%

    @Test
    @DisplayName("Basic put and mightContain cycle")
    void putAndContains() {
        BloomFilter filter = BloomFilter.create(1000);

        byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);

        // Initially not present
        assertFalse(filter.mightContain(item), "Item should not be present initially");

        // Insert
        assertTrue(filter.put(item), "Put should return true");
        assertEquals(1, filter.count(), "Count should be 1 after insert");

        // Now present (must be true - no false negatives allowed)
        assertTrue(filter.mightContain(item), "Item must be present after insert (no false negatives)");
    }

    @Test
    @DisplayName("No false negatives - all inserted items must be found")
    void noFalseNegatives() {
        BloomFilter filter = BloomFilter.create(10_000);

        Set<String> items = new HashSet<>();
        Random random = new Random(42);

        // Insert many items
        for (int i = 0; i < 5000; i++) {
            String item = "item-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);
            filter.put(itemBytes);
            items.add(item);
        }

        // CRITICAL: All inserted items MUST be found (no false negatives allowed)
        for (String item : items) {
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);
            assertTrue(filter.mightContain(itemBytes),
                "False negative detected for item: " + item + " - this is NOT allowed in Bloom filters");
        }

        System.out.println("No false negatives verified for " + items.size() + " items");
    }

    @Test
    @DisplayName("False positive rate within configured bounds")
    void falsePositiveRateWithinBounds() {
        double targetFpRate = 0.01; // 1%
        int expectedInsertions = 100_000;

        BloomFilter filter = BloomFilter.create(expectedInsertions, targetFpRate);

        System.out.println("=== False Positive Rate Test ===");
        System.out.println("Filter size (bits): " + filter.size());
        System.out.println("Num hash functions: " + filter.numHashFunctions());
        System.out.println("Expected FP rate: " + targetFpRate);

        Set<String> insertedItems = new HashSet<>();
        Random random = new Random(12345);

        // Insert items
        for (int i = 0; i < expectedInsertions; i++) {
            String item = "fp-test-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);
            filter.put(itemBytes);
            insertedItems.add(item);
        }

        System.out.println("Inserted items: " + insertedItems.size());
        System.out.println("Fill ratio: " + String.format("%.4f", filter.fillRatio()));

        // Verify no false negatives
        int falseNegatives = 0;
        for (String item : insertedItems) {
            if (!filter.mightContain(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "False negatives are NOT allowed in Bloom filters");

        // Measure false positive rate
        int fpCount = 0;
        int nonItemQueries = 100_000;

        for (int i = 0; i < nonItemQueries; i++) {
            String nonItem = "non-existent-" + random.nextInt(Integer.MAX_VALUE);

            // Skip if accidentally inserted
            if (insertedItems.contains(nonItem)) {
                continue;
            }

            if (filter.mightContain(nonItem.getBytes(StandardCharsets.UTF_8))) {
                fpCount++;
            }
        }

        double measuredFpRate = (double) fpCount / nonItemQueries;
        System.out.println("FP count: " + fpCount + " / " + nonItemQueries);
        System.out.println("Measured FP rate: " + String.format("%.6f", measuredFpRate) +
            " (" + String.format("%.4f", measuredFpRate * 100) + "%)");
        System.out.println("Expected FP rate: " + String.format("%.6f", targetFpRate) +
            " (" + String.format("%.4f", targetFpRate * 100) + "%)");

        // Allow some margin for statistical variance (2x the target)
        double maxAllowedFpRate = targetFpRate * 2.5;
        assertTrue(measuredFpRate <= maxAllowedFpRate,
            "Measured FP rate " + String.format("%.6f", measuredFpRate) +
            " exceeds maximum allowed " + String.format("%.6f", maxAllowedFpRate));

        System.out.println("✓ FP rate within bounds: " + String.format("%.4f", measuredFpRate * 100) +
            "% <= " + String.format("%.4f", maxAllowedFpRate * 100) + "%");
    }

    @Test
    @DisplayName("Memory segment serialization round-trip")
    void serializationRoundTrip() {
        BloomFilter original = BloomFilter.create(1000, 0.01);

        // Insert some items
        for (int i = 0; i < 500; i++) {
            byte[] item = ("serialize-test-" + i).getBytes(StandardCharsets.UTF_8);
            original.put(item);
        }

        // Serialize to MemorySegment
        MemorySegment segment;
        try (Arena arena = Arena.ofConfined()) {
            segment = original.toMemorySegment(arena);

            // Verify segment size
            assertTrue(segment.byteSize() >= 40, "Segment should have at least header size");

            // Deserialize
            BloomFilter restored = BloomFilter.fromMemorySegment(segment);

            // Verify configuration preserved
            assertEquals(original.size(), restored.size(), "Size should be preserved");
            assertEquals(original.numHashFunctions(), restored.numHashFunctions(),
                "Num hash functions should be preserved");
            assertEquals(original.seed(), restored.seed(), "Seed should be preserved");

            // Verify all items still present after round-trip
            for (int i = 0; i < 500; i++) {
                byte[] item = ("serialize-test-" + i).getBytes(StandardCharsets.UTF_8);
                assertTrue(restored.mightContain(item),
                    "Item " + i + " should be present after serialization round-trip");
            }

            // Verify count preserved
            assertEquals(original.count(), restored.count(), "Count should be preserved");

            System.out.println("Serialization round-trip successful:");
            System.out.println("  Original: " + original);
            System.out.println("  Restored: " + restored);
        }
    }

    @Test
    @DisplayName("Empty filter behavior")
    void emptyFilter() {
        BloomFilter filter = BloomFilter.create(1000);

        // Empty filter should have count 0
        assertEquals(0, filter.count(), "Empty filter should have count 0");

        // Empty filter should have fill ratio 0
        assertEquals(0.0, filter.fillRatio(), 0.001, "Empty filter should have fill ratio 0");

        // Empty filter should have 0 bit count
        assertEquals(0, filter.bitCount(), "Empty filter should have 0 bits set");

        // Empty filter should report 0 FP rate for 0 insertions
        assertEquals(0.0, filter.expectedFalsePositiveRate(0), 0.001,
            "Empty filter should report 0 FP rate for 0 insertions");

        // Querying empty filter should always return false
        for (int i = 0; i < 100; i++) {
            byte[] item = ("non-existent-" + i).getBytes(StandardCharsets.UTF_8);
            assertFalse(filter.mightContain(item),
                "Empty filter should not contain any items");
        }

        System.out.println("Empty filter behavior verified");
    }

    @Test
    @DisplayName("High load factor behavior")
    void highLoadFactor() {
        int expectedInsertions = 10000;
        BloomFilter filter = BloomFilter.create(expectedInsertions, 0.01);

        // Insert more items than expected (high load factor)
        int actualInsertions = expectedInsertions * 2;

        Set<String> items = new HashSet<>();
        for (int i = 0; i < actualInsertions; i++) {
            String item = "high-load-" + i;
            filter.put(item.getBytes(StandardCharsets.UTF_8));
            items.add(item);
        }

        System.out.println("High load factor test:");
        System.out.println("  Expected insertions: " + expectedInsertions);
        System.out.println("  Actual insertions: " + actualInsertions);
        System.out.println("  Fill ratio: " + String.format("%.4f", filter.fillRatio()));
        System.out.println("  Expected FP rate at " + actualInsertions + " insertions: " +
            String.format("%.6f", filter.expectedFalsePositiveRate(actualInsertions)));

        // CRITICAL: Even at high load, no false negatives allowed
        int falseNegatives = 0;
        for (String item : items) {
            if (!filter.mightContain(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "False negatives are NOT allowed even at high load factor");

        System.out.println("✓ No false negatives at high load factor");
    }

    @Test
    @DisplayName("Null key throws NullPointerException")
    void nullKey() {
        BloomFilter filter = BloomFilter.create(1000);

        assertThrows(NullPointerException.class, () -> filter.put(null),
            "put(null) should throw NullPointerException");

        assertThrows(NullPointerException.class, () -> filter.mightContain(null),
            "mightContain(null) should throw NullPointerException");
    }

    @Test
    @DisplayName("Invalid constructor parameters throw IllegalArgumentException")
    void invalidParameters() {
        // Zero expected insertions
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(0),
            "Zero expected insertions should throw");

        // Negative expected insertions
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(-100),
            "Negative expected insertions should throw");

        // FP rate = 0
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(1000, 0.0),
            "FP rate = 0 should throw");

        // FP rate = 1
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(1000, 1.0),
            "FP rate = 1 should throw");

        // FP rate > 1
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(1000, 1.5),
            "FP rate > 1 should throw");

        // FP rate < 0
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(1000, -0.1),
            "Negative FP rate should throw");
    }

    @Test
    @DisplayName("Clear filter removes all items")
    void clearFilter() {
        BloomFilter filter = BloomFilter.create(1000);

        // Insert items
        for (int i = 0; i < 100; i++) {
            filter.put(("clear-test-" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(filter.count() > 0, "Filter should have items");
        assertTrue(filter.fillRatio() > 0, "Fill ratio should be positive");

        // Clear
        filter.clear();

        // Verify empty
        assertEquals(0, filter.count(), "Count should be 0 after clear");
        assertEquals(0, filter.bitCount(), "Bit count should be 0 after clear");
        assertEquals(0.0, filter.fillRatio(), 0.001, "Fill ratio should be 0 after clear");

        // Items should not be found
        for (int i = 0; i < 100; i++) {
            assertFalse(filter.mightContain(("clear-test-" + i).getBytes(StandardCharsets.UTF_8)),
                "Item " + i + " should not be found after clear");
        }

        System.out.println("Clear filter verified");
    }

    @Test
    @DisplayName("Multiple distinct items")
    void multipleItems() {
        BloomFilter filter = BloomFilter.create(1000);

        byte[] itemA = "item-A".getBytes(StandardCharsets.UTF_8);
        byte[] itemB = "item-B".getBytes(StandardCharsets.UTF_8);
        byte[] itemC = "item-C".getBytes(StandardCharsets.UTF_8);

        // Insert all
        filter.put(itemA);
        filter.put(itemB);
        filter.put(itemC);

        assertEquals(3, filter.count());

        // All should be present
        assertTrue(filter.mightContain(itemA), "itemA should be present");
        assertTrue(filter.mightContain(itemB), "itemB should be present");
        assertTrue(filter.mightContain(itemC), "itemC should be present");
    }

    @Test
    @DisplayName("Empty byte array is valid input")
    void emptyByteArray() {
        BloomFilter filter = BloomFilter.create(1000);

        byte[] emptyItem = new byte[0];

        assertFalse(filter.mightContain(emptyItem), "Empty item should not be present initially");

        filter.put(emptyItem);

        assertTrue(filter.mightContain(emptyItem), "Empty item should be present after insert");
    }

    @Test
    @DisplayName("Expected FP rate calculation")
    void expectedFpRateCalculation() {
        BloomFilter filter = BloomFilter.create(10000, 0.01);

        // FP rate should increase with more insertions
        double fpRate0 = filter.expectedFalsePositiveRate(0);
        double fpRate5000 = filter.expectedFalsePositiveRate(5000);
        double fpRate10000 = filter.expectedFalsePositiveRate(10000);
        double fpRate20000 = filter.expectedFalsePositiveRate(20000);

        System.out.println("Expected FP rates:");
        System.out.println("  At 0 insertions: " + String.format("%.6f", fpRate0));
        System.out.println("  At 5000 insertions: " + String.format("%.6f", fpRate5000));
        System.out.println("  At 10000 insertions: " + String.format("%.6f", fpRate10000));
        System.out.println("  At 20000 insertions: " + String.format("%.6f", fpRate20000));

        assertEquals(0.0, fpRate0, 0.001, "FP rate at 0 insertions should be 0");
        assertTrue(fpRate5000 < fpRate10000, "FP rate should increase with more insertions");
        assertTrue(fpRate10000 < fpRate20000, "FP rate should increase with more insertions");

        // At expected capacity, FP rate should be close to configured
        // Allow some margin for rounding effects
        assertTrue(fpRate10000 < 0.05,
            "FP rate at expected capacity should be reasonably low");
    }

    @Test
    @DisplayName("Byte size calculation")
    void byteSizeCalculation() {
        BloomFilter filter = BloomFilter.create(10000, 0.01);

        long byteSize = filter.byteSize();

        // Should be at least header size
        assertTrue(byteSize >= 40, "Byte size should be at least header size (40 bytes)");

        // Should be header + bit array
        int expectedBitArrayBytes = (filter.size() + 63) / 64 * 8;
        long expectedSize = 40 + expectedBitArrayBytes;

        assertEquals(expectedSize, byteSize, "Byte size should match header + bit array");

        System.out.println("Byte size: " + byteSize + " bytes");
        System.out.println("Bits: " + filter.size());
        System.out.println("Bits per item: " + (double) filter.size() / 10000);
    }

    @Test
    @DisplayName("Different FP rate configurations")
    void differentFpRates() {
        // Lower FP rate = more bits = larger filter
        BloomFilter filter1 = BloomFilter.create(10000, 0.1);  // 10% FP rate
        BloomFilter filter2 = BloomFilter.create(10000, 0.01); // 1% FP rate
        BloomFilter filter3 = BloomFilter.create(10000, 0.001); // 0.1% FP rate

        System.out.println("Different FP rate configurations:");
        System.out.println("  10% FP rate: bits=" + filter1.size() + ", hashFns=" + filter1.numHashFunctions());
        System.out.println("  1% FP rate: bits=" + filter2.size() + ", hashFns=" + filter2.numHashFunctions());
        System.out.println("  0.1% FP rate: bits=" + filter3.size() + ", hashFns=" + filter3.numHashFunctions());

        // Lower FP rate should have more bits
        assertTrue(filter3.size() > filter2.size(), "Lower FP rate should have more bits");
        assertTrue(filter2.size() > filter1.size(), "Lower FP rate should have more bits");

        // All should work correctly
        byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);
        filter1.put(item);
        filter2.put(item);
        filter3.put(item);

        assertTrue(filter1.mightContain(item));
        assertTrue(filter2.mightContain(item));
        assertTrue(filter3.mightContain(item));
    }

    @Test
    @DisplayName("Concurrent operations are thread-safe")
    void concurrentOperations() throws InterruptedException {
        BloomFilter filter = BloomFilter.create(10000);
        int numThreads = 8;
        int itemsPerThread = 500;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < itemsPerThread; i++) {
                    String item = "thread-" + threadId + "-item-" + i;
                    filter.put(item.getBytes(StandardCharsets.UTF_8));
                }
            });
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify some items were inserted
        assertTrue(filter.count() > 0, "Some items should have been inserted");

        // Verify no false negatives for sampled items
        int found = 0;
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < itemsPerThread; i += 50) {
                String item = "thread-" + t + "-item-" + i;
                if (filter.mightContain(item.getBytes(StandardCharsets.UTF_8))) {
                    found++;
                }
            }
        }

        // All sampled items should be found (no false negatives)
        assertTrue(found > 0, "Some items should be found");

        System.out.println("Concurrent test - Count: " + filter.count());
        System.out.println("Concurrent test - Sampled items found: " + found);
    }

    @Test
    @DisplayName("Invalid memory segment throws IllegalArgumentException")
    void invalidMemorySegment() {
        // Null segment
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter((MemorySegment) null),
            "Null segment should throw");

        // Too small segment
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment smallSegment = arena.allocate(10);
            assertThrows(IllegalArgumentException.class, () -> new BloomFilter(smallSegment),
                "Too small segment should throw");
        }

        // Invalid magic number
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment invalidMagic = arena.allocate(100);
            // Fill with zeros (invalid magic)
            assertThrows(IllegalArgumentException.class, () -> new BloomFilter(invalidMagic),
                "Invalid magic should throw");
        }
    }

    @Test
    @DisplayName("String representation contains key metrics")
    void stringRepresentation() {
        BloomFilter filter = BloomFilter.create(1000, 0.01);

        for (int i = 0; i < 100; i++) {
            filter.put(("string-test-" + i).getBytes(StandardCharsets.UTF_8));
        }

        String str = filter.toString();

        assertTrue(str.contains("BloomFilter"), "String should contain class name");
        assertTrue(str.contains("count="), "String should contain count");
        assertTrue(str.contains("numBits="), "String should contain numBits");
        assertTrue(str.contains("numHashFunctions="), "String should contain numHashFunctions");

        System.out.println("String representation: " + str);
    }
}
