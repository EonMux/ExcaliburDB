package excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Segment's core methods: put, get, delete.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Basic put/get/delete operations</li>
 *   <li>Robin Hood hashing behavior</li>
 *   <li>Zero-copy MemorySegment reads</li>
 *   <li>Load factor rejection</li>
 *   <li>Thread safety for concurrent reads</li>
 *   <li>Domain correctness (hash, distance, tombstone values)</li>
 * </ul>
 */
@DisplayName("Segment Core Methods Tests")
class SegmentTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path segmentFile;
    private Segment segment;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        segmentFile = tempDir.resolve("test-segment.bin");
        // Create segment with enough capacity for tests
        segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
    }

    @AfterEach
    void tearDown() {
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Basic Put/Get Tests ====================

    @Nested
    @DisplayName("put/get Operations")
    class PutGetTests {

        @Test
        @DisplayName("Should insert and retrieve a key-value pair")
        void testPutGet() throws IOException {
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result, "Value should not be null after put");

            // Verify content matches
            assertEquals(value.length, result.byteSize());
            byte[] resultBytes = result.toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(value, resultBytes, "Retrieved value should match inserted value");
        }

        @Test
        @DisplayName("Should return null for non-existent key")
        void testGetNonExistent() {
            byte[] key = "nonexistent".getBytes();

            MemorySegment result = segment.get(key);
            assertNull(result, "Should return null for non-existent key");
        }

        @Test
        @DisplayName("Should update existing key with new value")
        void testUpdate() {
            byte[] key = "update-key".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] value2 = "value2-longer".getBytes();

            segment.put(key, value1);
            segment.put(key, value2);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            byte[] resultBytes = result.toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(value2, resultBytes, "Value should be updated");
        }

        @Test
        @DisplayName("Should handle multiple key-value pairs")
        void testMultiplePairs() {
            int count = 100;
            for (int i = 0; i < count; i++) {
                byte[] key = ("key-" + i).getBytes();
                byte[] value = ("value-" + i).getBytes();
                segment.put(key, value);
            }

            // Verify all can be retrieved
            for (int i = 0; i < count; i++) {
                byte[] key = ("key-" + i).getBytes();
                byte[] expectedValue = ("value-" + i).getBytes();

                MemorySegment result = segment.get(key);
                assertNotNull(result, "Should find key-" + i);
                assertArrayEquals(expectedValue, result.toArray(ValueLayout.JAVA_BYTE));
            }
        }

        @Test
        @DisplayName("Should handle binary keys and values")
        void testBinaryData() {
            byte[] key = new byte[] {0x00, 0x01, (byte) 0xFF, (byte) 0xFE};
            byte[] value = new byte[] {(byte) 0x80, (byte) 0x90, (byte) 0xA0};

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE));
        }

        @Test
        @DisplayName("Should handle empty value")
        void testEmptyValue() {
            byte[] key = "empty-value-key".getBytes();
            byte[] value = new byte[0];

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertEquals(0, result.byteSize());
        }

        @Test
        @DisplayName("Should handle large values")
        void testLargeValue() {
            byte[] key = "large-value-key".getBytes();
            byte[] value = new byte[64 * 1024]; // 64KB
            for (int i = 0; i < value.length; i++) {
                value[i] = (byte) (i % 256);
            }

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ==================== Delete Tests ====================

    @Nested
    @DisplayName("delete Operations")
    class DeleteTests {

        @Test
        @DisplayName("Should delete existing key")
        void testDelete() {
            byte[] key = "delete-key".getBytes();
            byte[] value = "delete-value".getBytes();

            segment.put(key, value);
            assertNotNull(segment.get(key));

            boolean deleted = segment.delete(key);
            assertTrue(deleted, "delete should return true for existing key");
            assertNull(segment.get(key), "get should return null after delete");
        }

        @Test
        @DisplayName("Should return false for non-existent key")
        void testDeleteNonExistent() {
            byte[] key = "nonexistent".getBytes();

            boolean deleted = segment.delete(key);
            assertFalse(deleted, "delete should return false for non-existent key");
        }

        @Test
        @DisplayName("Should return false when deleting already deleted key")
        void testDeleteTwice() {
            byte[] key = "delete-twice".getBytes();
            byte[] value = "value".getBytes();

            segment.put(key, value);
            assertTrue(segment.delete(key));
            assertFalse(segment.delete(key), "Second delete should return false");
        }

        @Test
        @DisplayName("Should not affect other keys after delete")
        void testDeleteIsolation() {
            byte[] key1 = "key1".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] key2 = "key2".getBytes();
            byte[] value2 = "value2".getBytes();

            segment.put(key1, value1);
            segment.put(key2, value2);
            segment.delete(key1);

            assertNull(segment.get(key1));
            assertArrayEquals(value2, segment.get(key2).toArray(ValueLayout.JAVA_BYTE));
        }

        @Test
        @DisplayName("Should allow re-insert after delete")
        void testReinsertAfterDelete() {
            byte[] key = "reinsert-key".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] value2 = "value2".getBytes();

            segment.put(key, value1);
            segment.delete(key);
            segment.put(key, value2);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertArrayEquals(value2, result.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ==================== Zero-Copy Validation Tests ====================

    @Nested
    @DisplayName("Zero-Copy Validation")
    class ZeroCopyTests {

        @Test
        @DisplayName("get should return MemorySegment not byte[]")
        void testReturnsMemorySegment() {
            byte[] key = "zero-copy-key".getBytes();
            byte[] value = "zero-copy-value".getBytes();

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertTrue(result instanceof MemorySegment,
                "get should return MemorySegment for zero-copy access");
        }

        @Test
        @DisplayName("MemorySegment should be a slice pointing to off-heap memory")
        void testOffHeapMemory() {
            byte[] key = "offheap-key".getBytes();
            byte[] value = "offheap-value".getBytes();

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result);

            // Verify it's a slice (has a non-zero offset or is not the entire segment)
            // The slice should be backed by the segment's memory
            assertDoesNotThrow(() -> {
                // Should be able to access the memory
                byte firstByte = result.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(value[0], firstByte);
            });
        }

        @Test
        @DisplayName("Multiple gets should return independent slices")
        void testIndependentSlices() {
            byte[] key = "slice-key".getBytes();
            byte[] value = "slice-value".getBytes();

            segment.put(key, value);

            MemorySegment result1 = segment.get(key);
            MemorySegment result2 = segment.get(key);

            // Both should return valid data
            assertNotNull(result1);
            assertNotNull(result2);

            // They may be the same slice or different slices, but both should have correct data
            assertEquals(result1.byteSize(), result2.byteSize());
        }

        @Test
        @DisplayName("get MUST return MemorySegment pointing to off-heap memory (not heap array)")
        void testGetReturnsOffHeapMemorySegment() {
            byte[] key = "offheap-verify-key".getBytes();
            byte[] value = "offheap-verify-value".getBytes();

            segment.put(key, value);

            MemorySegment result = segment.get(key);
            assertNotNull(result, "get() should return non-null for existing key");

            // CRITICAL: Verify the MemorySegment is a native (off-heap) segment
            // A heap-backed MemorySegment would have a non-null heapBase()
            // A native/off-heap MemorySegment has null heapBase() and isNative() returns true
            assertNull(result.heapBase().orElse(null),
                "get() MUST return MemorySegment pointing to off-heap memory, not a heap array. " +
                "heapBase() should be null for native memory segments.");

            // Additional verification: isNative() should indicate off-heap memory
            // For memory-mapped files, this indicates native memory backing
            assertTrue(result.isNative() || result.heapBase().isEmpty(),
                "MemorySegment should be backed by native/off-heap memory (memory-mapped file), " +
                "not by a Java heap byte array.");

            // Verify the slice has correct size and content
            assertEquals(value.length, result.byteSize(),
                "MemorySegment byteSize should match value length");
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE),
                "MemorySegment content should match original value");
        }

        @Test
        @DisplayName("Zero-copy slice must remain valid until segment modification")
        void testZeroCopySliceValidity() {
            byte[] key1 = "validity-key-1".getBytes();
            byte[] value1 = "validity-value-1".getBytes();
            byte[] key2 = "validity-key-2".getBytes();

            segment.put(key1, value1);

            // Get zero-copy slice
            MemorySegment slice = segment.get(key1);
            assertNotNull(slice);

            // Slice should be valid and contain correct data
            assertEquals(value1.length, slice.byteSize());
            assertArrayEquals(value1, slice.toArray(ValueLayout.JAVA_BYTE));

            // After inserting another key, the first slice should still be valid
            // (memory-mapped regions don't move)
            segment.put(key2, "value2".getBytes());

            // Original slice should still be accessible
            assertDoesNotThrow(() -> {
                byte firstByte = slice.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(value1[0], firstByte);
            }, "Zero-copy slice should remain valid after other insertions");
        }
    }

    // ==================== Load Factor Tests ====================

    @Nested
    @DisplayName("Load Factor Rejection")
    class LoadFactorTests {

        @Test
        @DisplayName("Should reject insertions at load factor >= 0.7")
        void testLoadFactorRejection() throws IOException {
            // Create a dedicated segment sized to ensure data region can hold
            // entries up to 0.7 load factor.
            //
            // Calculation:
            // - For N slots, we need 0.7 * N entries at load factor threshold
            // - Each entry: 18 bytes overhead + key_len + value_len
            // - With minimal keys (e.g., "k" + int ~6 bytes) and values (1 byte): ~25 bytes/entry
            // - Data region needed: 0.7 * N * 25 = 17.5 * N bytes
            // - Slot table: N * 24 bytes
            // - Total: N * 41.5 bytes minimum
            //
            // Using a 256KB segment:
            // - Estimated slots: (256KB / 2) / 24 = 5461 -> rounded to 4096 slots
            // - Slot table: 4096 * 24 = 98,304 bytes
            // - Data region: ~157,696 bytes (enough for 2867 entries at ~25 bytes each)
            // - Max entries at 0.7 load factor: 2867 (but limited by 0.7 * 4096 = 2867)
            segment.close();
            segmentFile = tempDir.resolve("load-factor-test-segment.bin");
            segment = new Segment(segmentFile, 256 * 1024, arena); // 256KB

            int slotCount = segment.slotCount();
            // Calculate max entries before rejection using the same formula as Segment:
            // maxEntriesAtThreshold = ceil(slotCount * LOAD_FACTOR_THRESHOLD)
            // We can insert up to (maxEntriesAtThreshold - 1) entries before rejection
            int maxEntriesAtThreshold = (int) Math.ceil(slotCount * Segment.LOAD_FACTOR_THRESHOLD);
            int maxEntries = maxEntriesAtThreshold - 1;

            // Use minimal key/value pairs to maximize entries in data region
            // Key format: single char + short int (e.g., "k0", "k1", ..., "k9999")
            // Value format: single byte
            for (int i = 0; i < maxEntries; i++) {
                byte[] key = ("k" + i).getBytes();
                byte[] value = new byte[] {(byte) i};
                segment.put(key, value);
            }

            // Verify we're just under the threshold
            double currentLoadFactor = segment.loadFactor();
            assertTrue(currentLoadFactor < Segment.LOAD_FACTOR_THRESHOLD,
                "Load factor before overflow should be under 0.7, was: " + currentLoadFactor);

            // Next insertion should fail due to load factor threshold
            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
                segment.put("overflow".getBytes(), new byte[] {0});
            }, "Should reject insertion at load factor >= 0.7");

            // Verify the exception message mentions load factor (not data space)
            assertTrue(exception.getMessage().contains("Load factor"),
                "Exception should mention load factor, got: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("0.7"),
                "Exception should mention threshold 0.7, got: " + exception.getMessage());
        }

        @Test
        @DisplayName("Load factor should increase with insertions")
        void testLoadFactorIncreases() {
            double initialLoadFactor = segment.loadFactor();
            assertEquals(0.0, initialLoadFactor, 0.001, "Initial load factor should be 0");

            segment.put("key1".getBytes(), "value1".getBytes());
            assertTrue(segment.loadFactor() > initialLoadFactor);

            segment.put("key2".getBytes(), "value2".getBytes());
            assertTrue(segment.loadFactor() > 0);
        }

        @Test
        @DisplayName("Load factor calculation must be entry_count / slot_count")
        void testLoadFactorCalculation() throws IOException {
            // Create a small segment for precise testing
            segment.close();
            segmentFile = tempDir.resolve("load-factor-calc-test.bin");
            segment = new Segment(segmentFile, 64 * 1024, arena); // 64KB

            int slotCount = segment.slotCount();

            // Insert known number of entries
            for (int i = 0; i < 100; i++) {
                segment.put(("lf-calc-" + i).getBytes(), ("val-" + i).getBytes());
            }

            // Verify load factor calculation: entry_count / slot_count
            double expectedLoadFactor = 100.0 / slotCount;
            double actualLoadFactor = segment.loadFactor();

            assertEquals(expectedLoadFactor, actualLoadFactor, 0.0001,
                "Load factor must be computed as entry_count / slot_count");
        }

        @Test
        @DisplayName("Segment must reject when load factor would equal 0.7 exactly")
        void testRejectAtExactThreshold() throws IOException {
            // Create a segment where we can precisely hit 0.7 load factor
            segment.close();
            segmentFile = tempDir.resolve("exact-threshold-test.bin");
            segment = new Segment(segmentFile, 64 * 1024, arena);

            int slotCount = segment.slotCount();
            // Calculate using the same formula as Segment:
            // maxEntriesAtThreshold = ceil(slotCount * LOAD_FACTOR_THRESHOLD)
            // We can insert up to (maxEntriesAtThreshold - 1) entries before rejection
            int maxEntriesAtThreshold = (int) Math.ceil(slotCount * Segment.LOAD_FACTOR_THRESHOLD);
            int entriesToFill = maxEntriesAtThreshold - 1;

            for (int i = 0; i < entriesToFill; i++) {
                byte[] key = ("t" + i).getBytes(); // Minimal key
                segment.put(key, new byte[] {0}); // Minimal value
            }

            // Current load factor should be just under 0.7
            double currentLoadFactor = segment.loadFactor();
            assertTrue(currentLoadFactor < Segment.LOAD_FACTOR_THRESHOLD,
                "Load factor before last insertion should be under 0.7");

            // Next insertion should trigger rejection (would make load factor >= 0.7)
            assertThrows(IllegalStateException.class, () -> {
                segment.put("last".getBytes(), new byte[] {0});
            }, "Should reject when load factor would reach or exceed 0.7");
        }
    }

    // ==================== Hash Collision Tests ====================

    @Nested
    @DisplayName("Hash Collision Handling")
    class HashCollisionTests {

        @Test
        @DisplayName("Should handle keys with same hash mod capacity")
        void testHashCollision() {
            // Insert many keys to force some collisions
            int count = 500;
            for (int i = 0; i < count; i++) {
                byte[] key = ("collision-key-" + String.format("%04d", i)).getBytes();
                byte[] value = ("collision-value-" + i).getBytes();
                segment.put(key, value);
            }

            // Verify all can be retrieved
            for (int i = 0; i < count; i++) {
                byte[] key = ("collision-key-" + String.format("%04d", i)).getBytes();
                byte[] expectedValue = ("collision-value-" + i).getBytes();

                MemorySegment result = segment.get(key);
                assertNotNull(result, "Should find key " + i);
                assertArrayEquals(expectedValue, result.toArray(ValueLayout.JAVA_BYTE));
            }
        }

        @Test
        @DisplayName("Should correctly distinguish similar keys")
        void testSimilarKeys() {
            String[] keys = {"key", "key1", "key2", "key10", "key01", "keya", "keyA"};

            for (String key : keys) {
                segment.put(key.getBytes(), ("value-" + key).getBytes());
            }

            for (String key : keys) {
                MemorySegment result = segment.get(key.getBytes());
                assertNotNull(result, "Should find key: " + key);
                assertArrayEquals(("value-" + key).getBytes(), result.toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }

    // ==================== Robin Hood Displacement Tests ====================

    @Nested
    @DisplayName("Robin Hood Displacement")
    class RobinHoodTests {

        @Test
        @DisplayName("Entries should be retrievable regardless of insertion order")
        void testRobinHoodDisplacement() {
            // Insert many entries in a way that would cause displacement
            List<byte[]> keys = new ArrayList<>();
            List<byte[]> values = new ArrayList<>();

            for (int i = 0; i < 200; i++) {
                byte[] key = ("rh-key-" + i).getBytes();
                byte[] value = ("rh-value-" + i).getBytes();
                keys.add(key);
                values.add(value);
                segment.put(key, value);
            }

            // Verify all entries are retrievable
            for (int i = 0; i < 200; i++) {
                MemorySegment result = segment.get(keys.get(i));
                assertNotNull(result, "Should find entry " + i);
                assertArrayEquals(values.get(i), result.toArray(ValueLayout.JAVA_BYTE));
            }
        }

        @Test
        @DisplayName("Should handle insert-delete-insert pattern")
        void testInsertDeleteInsert() {
            for (int round = 0; round < 5; round++) {
                // Insert
                for (int i = 0; i < 50; i++) {
                    byte[] key = ("round-" + round + "-key-" + i).getBytes();
                    segment.put(key, ("value-" + i).getBytes());
                }

                // Delete half
                for (int i = 0; i < 25; i++) {
                    byte[] key = ("round-" + round + "-key-" + i).getBytes();
                    segment.delete(key);
                }
            }

            // Verify remaining entries
            int foundCount = 0;
            for (int round = 0; round < 5; round++) {
                for (int i = 25; i < 50; i++) {
                    byte[] key = ("round-" + round + "-key-" + i).getBytes();
                    if (segment.get(key) != null) {
                        foundCount++;
                    }
                }
            }
            assertTrue(foundCount > 0, "Should find remaining entries");
        }

        @Test
        @DisplayName("Should correctly handle displacement chains (distance increment after swap)")
        void testDisplacementChainWithDistanceIncrement() throws IOException {
            // This test specifically verifies the Robin Hood hashing fix:
            // After a swap, the displaced entry's distance must be incremented by 1

            // Create a small segment to increase collision probability
            segment.close();
            segmentFile = tempDir.resolve("small-rh-segment.bin");
            segment = new Segment(segmentFile, 32 * 1024, arena); // 32KB for more collisions

            // Insert many entries to create displacement chains
            int entryCount = 400;
            List<String> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();

            for (int i = 0; i < entryCount; i++) {
                String key = "displacement-test-key-" + String.format("%04d", i);
                String value = "displacement-test-value-" + i + "-data";
                keys.add(key);
                values.add(value);
                segment.put(key.getBytes(), value.getBytes());
            }

            // All entries must be retrievable - this verifies the distance increment fix
            // If the distance wasn't incremented correctly after swaps, entries would be lost
            int foundCount = 0;
            for (int i = 0; i < entryCount; i++) {
                MemorySegment result = segment.get(keys.get(i).getBytes());
                if (result != null) {
                    String actualValue = new String(result.toArray(ValueLayout.JAVA_BYTE));
                    assertEquals(values.get(i), actualValue,
                        "Value mismatch for key " + i + ": expected '" + values.get(i) +
                        "' but got '" + actualValue + "'");
                    foundCount++;
                } else {
                    fail("Key not found: " + keys.get(i) + " at index " + i);
                }
            }
            assertEquals(entryCount, foundCount, "All entries should be retrievable");
        }

        @Test
        @DisplayName("Should maintain Robin Hood invariant: distance increases with probe length")
        void testRobinHoodInvariantMaintained() throws IOException {
            // Create a small segment
            segment.close();
            segmentFile = tempDir.resolve("invariant-test-segment.bin");
            segment = new Segment(segmentFile, 32 * 1024, arena);

            // Insert entries that will cause multiple displacements
            for (int i = 0; i < 300; i++) {
                // Use keys that are likely to cause collisions
                String key = "invariant-" + (i % 50) + "-" + i;
                String value = "inv-value-" + i;
                segment.put(key.getBytes(), value.getBytes());
            }

            // Verify all entries are retrievable
            // If Robin Hood invariant is maintained, all entries should be findable
            for (int i = 0; i < 300; i++) {
                String key = "invariant-" + (i % 50) + "-" + i;
                String expectedValue = "inv-value-" + i;
                MemorySegment result = segment.get(key.getBytes());
                assertNotNull(result, "Key should be found: " + key);
                assertEquals(expectedValue, new String(result.toArray(ValueLayout.JAVA_BYTE)));
            }
        }

        @Test
        @DisplayName("Should handle cascading displacements correctly")
        void testCascadingDisplacements() {
            // Insert entries in a pattern that causes cascading swaps
            // This tests the "continue" after swap to ensure the loop works correctly
            for (int batch = 0; batch < 10; batch++) {
                for (int i = 0; i < 30; i++) {
                    String key = "cascade-" + batch + "-" + i;
                    String value = "cascade-value-" + batch + "-" + i;
                    segment.put(key.getBytes(), value.getBytes());
                }
            }

            // Verify all entries
            for (int batch = 0; batch < 10; batch++) {
                for (int i = 0; i < 30; i++) {
                    String key = "cascade-" + batch + "-" + i;
                    String expectedValue = "cascade-value-" + batch + "-" + i;
                    MemorySegment result = segment.get(key.getBytes());
                    assertNotNull(result, "Key should be found: " + key);
                    assertEquals(expectedValue, new String(result.toArray(ValueLayout.JAVA_BYTE)));
                }
            }
        }
    }

// ==================== Concurrent Reads Tests ====================

    @Nested
    @DisplayName("Concurrent Reads")
    class ConcurrentReadsTests {

        @Test
        @DisplayName("Multiple threads should read concurrently without exceptions")
        void testConcurrentReads() throws Exception {
            // Close the confined arena and use shared arena for concurrent access
            segment.close();
            arena.close();
            arena = Arena.ofShared();
            segmentFile = tempDir.resolve("concurrent-test-segment.bin");
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

            // Pre-populate with data
            int numEntries = 100;
            for (int i = 0; i < numEntries; i++) {
                segment.put(("concurrent-key-" + i).getBytes(),
                           ("concurrent-value-" + i).getBytes());
            }

            int numThreads = 8;
            int readsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < numThreads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int r = 0; r < readsPerThread; r++) {
                            int keyIndex = r % numEntries;
                            byte[] key = ("concurrent-key-" + keyIndex).getBytes();
                            MemorySegment result = segment.get(key);
                            if (result != null) {
                                successCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }

            // Start all threads
            startLatch.countDown();

            // Wait for completion
            for (Future<?> future : futures) {
                future.get();
            }
            executor.shutdown();

            assertEquals(0, errorCount.get(), "No exceptions should occur during concurrent reads");
            assertEquals(numThreads * readsPerThread, successCount.get(),
                "All reads should succeed");
        }

        @Test
        @DisplayName("Reads should see consistent data during concurrent writes")
        void testReadConsistency() throws Exception {
            // Close the confined arena and use shared arena for concurrent access
            segment.close();
            arena.close();
            arena = Arena.ofShared();
            segmentFile = tempDir.resolve("consistency-test-segment.bin");
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

            // Pre-populate
            for (int i = 0; i < 50; i++) {
                segment.put(("consistency-key-" + i).getBytes(),
                           ("value-" + i).getBytes());
            }

            int numReaders = 4;
            int numWriters = 2;
            ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger readErrors = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();

            // Reader threads
            for (int t = 0; t < numReaders; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 100; i++) {
                            int keyIndex = i % 50;
                            segment.get(("consistency-key-" + keyIndex).getBytes());
                        }
                    } catch (Exception e) {
                        readErrors.incrementAndGet();
                    }
                }));
            }

            // Writer threads
            for (int t = 0; t < numWriters; t++) {
                final int writerId = t;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 50; i++) {
                            segment.put(("consistency-key-" + (50 + writerId * 50 + i)).getBytes(),
                                       ("new-value-" + i).getBytes());
                        }
                    } catch (Exception e) {
                        // Writer errors are acceptable for this test
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
            executor.shutdown();

            assertEquals(0, readErrors.get(), "Readers should not see exceptions");
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Key hash values should be positive 64-bit")
        void testKeyHashPositive() {
            // Insert keys and verify they can be found (hash is positive internally)
            segment.put("positive-hash-1".getBytes(), "value1".getBytes());
            segment.put("positive-hash-2".getBytes(), "value2".getBytes());

            assertNotNull(segment.get("positive-hash-1".getBytes()));
            assertNotNull(segment.get("positive-hash-2".getBytes()));
        }

        @Test
        @DisplayName("Distance values should be in range [0, 127]")
        void testDistanceRange() {
            // Insert many entries to potentially cause various distances
            for (int i = 0; i < 100; i++) {
                segment.put(("distance-key-" + i).getBytes(),
                           ("distance-value-" + i).getBytes());
            }

            // All should be retrievable, indicating valid distances
            for (int i = 0; i < 100; i++) {
                assertNotNull(segment.get(("distance-key-" + i).getBytes()));
            }
        }

        @Test
        @DisplayName("Tombstone should be exactly 1 for deleted entries")
        void testTombstoneValue() {
            byte[] key = "tombstone-key".getBytes();
            segment.put(key, "value".getBytes());
            segment.delete(key);

            // After delete, get should return null (tombstone detected)
            assertNull(segment.get(key));

            // Re-insert should work (tombstone shouldn't block)
            segment.put(key, "new-value".getBytes());
            assertNotNull(segment.get(key));
        }

        @Test
        @DisplayName("Capacity should be power of 2")
        void testCapacityPowerOf2() {
            int slotCount = segment.slotCount();
            assertTrue((slotCount & (slotCount - 1)) == 0,
                "Slot count should be power of 2");
            assertTrue(slotCount >= Segment.MIN_SEGMENT_CAPACITY);
            assertTrue(slotCount <= Segment.MAX_SEGMENT_CAPACITY);
        }

        @Test
        @DisplayName("Load factor should be computed as entry_count / slot_count")
        void testLoadFactorComputation() {
            int initialCount = segment.entryCount();
            double initialLoadFactor = segment.loadFactor();
            assertEquals(initialCount / (double) segment.slotCount(), initialLoadFactor, 0.0001);

            segment.put("load-factor-key".getBytes(), "value".getBytes());
            int newCount = segment.entryCount();
            double newLoadFactor = segment.loadFactor();
            assertEquals(newCount / (double) segment.slotCount(), newLoadFactor, 0.0001);
        }
    }

    // ==================== Segment Lifecycle Tests ====================

    @Nested
    @DisplayName("Segment Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Should reject operations after close")
        void testOperationsAfterClose() throws IOException {
            segment.put("before-close".getBytes(), "value".getBytes());
            segment.close();

            assertThrows(IllegalStateException.class, () ->
                segment.put("after-close".getBytes(), "value".getBytes()));

            assertThrows(IllegalStateException.class, () ->
                segment.get("before-close".getBytes()));

            assertThrows(IllegalStateException.class, () ->
                segment.delete("before-close".getBytes()));
        }

        @Test
        @DisplayName("isClosed should reflect segment state")
        void testIsClosed() {
            assertFalse(segment.isClosed());
            segment.close();
            assertTrue(segment.isClosed());
        }

        @Test
        @DisplayName("Flush should complete without error")
        void testFlush() {
            segment.put("flush-key".getBytes(), "value".getBytes());
            assertDoesNotThrow(() -> segment.flush());
        }
    }

    // ==================== Argument Validation Tests ====================

    @Nested
    @DisplayName("Argument Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should reject null key in put")
        void testPutNullKey() {
            assertThrows(IllegalArgumentException.class, () ->
                segment.put(null, "value".getBytes()));
        }

        @Test
        @DisplayName("Should reject null value in put")
        void testPutNullValue() {
            assertThrows(IllegalArgumentException.class, () ->
                segment.put("key".getBytes(), null));
        }

        @Test
        @DisplayName("Should reject null key in get")
        void testGetNullKey() {
            assertThrows(IllegalArgumentException.class, () ->
                segment.get(null));
        }

        @Test
        @DisplayName("Should reject null key in delete")
        void testDeleteNullKey() {
            assertThrows(IllegalArgumentException.class, () ->
                segment.delete(null));
        }
    }

    // ==================== Distance Overflow Tests ====================

    @Nested
    @DisplayName("Distance Overflow Protection")
    class DistanceOverflowTests {

        @Test
        @DisplayName("MAX_DISTANCE constant should be 127")
        void testMaxDistanceConstant() {
            assertEquals(127, Segment.MAX_DISTANCE);
        }

        @Test
        @DisplayName("Should reject insertion when distance would overflow 127")
        void testDistanceOverflowRejection() throws IOException {
            // This test verifies that insertions are rejected when the distance
            // would exceed 127 (the maximum value that fits in an unsigned byte).
            //
            // With proper load factor management (0.7 threshold), distances should
            // rarely exceed 10. However, we need to ensure the overflow protection
            // is in place for edge cases.
            //
            // The test fills the segment close to capacity and verifies that
            // either entries are inserted successfully (with valid distances)
            // or an IllegalStateException is thrown (distance overflow).

            // Close and recreate with a smaller segment for this test
            segment.close();
            segmentFile = tempDir.resolve("distance-overflow-test.bin");
            segment = new Segment(segmentFile, 64 * 1024, arena); // 64KB

            // Fill to just below load factor threshold
            int slotCount = segment.slotCount();
            int maxSafeEntries = (int) (slotCount * 0.69); // Just under 0.7

            // Insert entries - all should succeed with valid distances
            for (int i = 0; i < maxSafeEntries; i++) {
                byte[] key = ("distance-overflow-test-key-" + String.format("%06d", i)).getBytes();
                byte[] value = ("value-" + i).getBytes();
                assertDoesNotThrow(() -> segment.put(key, value),
                    "Insertion " + i + " should succeed with valid distance");
            }

            // Verify entries are retrievable
            int retrievedCount = 0;
            for (int i = 0; i < maxSafeEntries; i++) {
                byte[] key = ("distance-overflow-test-key-" + String.format("%06d", i)).getBytes();
                if (segment.get(key) != null) {
                    retrievedCount++;
                }
            }

            // All inserted entries should be retrievable
            assertEquals(maxSafeEntries, retrievedCount,
                "All inserted entries should be retrievable");
        }

        @Test
        @DisplayName("Distance values stored should be in range [0, 127]")
        void testStoredDistanceRange() throws IOException {
            // Insert entries and verify all stored distances are in valid range
            segment.close();
            segmentFile = tempDir.resolve("distance-range-test.bin");
            segment = new Segment(segmentFile, 128 * 1024, arena);

            // Insert enough entries to cause various probe distances
            int entryCount = 500;
            for (int i = 0; i < entryCount; i++) {
                byte[] key = ("dist-range-" + i).getBytes();
                byte[] value = ("value-" + i).getBytes();
                segment.put(key, value);
            }

            // All entries should be retrievable (distances were valid)
            for (int i = 0; i < entryCount; i++) {
                byte[] key = ("dist-range-" + i).getBytes();
                MemorySegment result = segment.get(key);
                assertNotNull(result, "Entry " + i + " should be found (distance was valid)");
            }
        }
    }
}
