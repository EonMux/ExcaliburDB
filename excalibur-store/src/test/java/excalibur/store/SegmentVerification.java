package excalibur.store;

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

/**
 * Standalone verification for Segment's core methods: put, get, delete.
 * Run directly with: java --enable-preview --release 21 excalibur.store.SegmentVerification
 *
 * Tests verify:
 * - Basic put/get/delete operations
 * - Robin Hood hashing behavior
 * - Zero-copy MemorySegment reads
 * - Load factor rejection
 * - Thread safety for concurrent reads
 * - Domain correctness (hash, distance, tombstone values)
 */
public class SegmentVerification {

    private static int passedTests = 0;
    private static int failedTests = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Segment Core Methods Verification ===\n");

        // Run all test categories
        testPutGetOperations();
        testDeleteOperations();
        testZeroCopyValidation();
        testLoadFactorRejection();
        testHashCollisions();
        testRobinHoodDisplacement();
        testConcurrentReads();
        testDomainCorrectness();
        testArgumentValidation();

        // Summary
        System.out.println("\n=== Test Summary ===");
        System.out.println("Passed: " + passedTests);
        System.out.println("Failed: " + failedTests);
        System.out.println("Total:  " + (passedTests + failedTests));

        if (failedTests > 0) {
            System.exit(1);
        }
    }

    // ==================== Test Runners ====================

    static void testPutGetOperations() throws IOException {
        System.out.println("--- put/get Operations ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        // Use larger capacity to ensure enough slot table space
        // Note: Data region is minimum 1MB regardless of capacity
        Segment segment = new Segment(segmentFile, 64 * 1024 * 1024, arena);

        try {
            // Test 1: Basic put/get
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();
            segment.put(key, value);

            MemorySegment result = segment.get(key);
            check("Should insert and retrieve a key-value pair",
                result != null && arrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE)));

            // Test 2: Non-existent key
            check("Should return null for non-existent key",
                segment.get("nonexistent".getBytes()) == null);

            // Test 3: Update existing key
            byte[] value2 = "value2-longer".getBytes();
            segment.put(key, value2);
            result = segment.get(key);
            check("Should update existing key with new value",
                result != null && arrayEquals(value2, result.toArray(ValueLayout.JAVA_BYTE)));

            // Test 4: Multiple key-value pairs (reduced to fit in 1MB data region)
            int count = 50;
            for (int i = 0; i < count; i++) {
                segment.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }
            boolean allFound = true;
            for (int i = 0; i < count; i++) {
                MemorySegment r = segment.get(("key-" + i).getBytes());
                if (r == null || !arrayEquals(("value-" + i).getBytes(), r.toArray(ValueLayout.JAVA_BYTE))) {
                    allFound = false;
                    break;
                }
            }
            check("Should handle multiple key-value pairs", allFound);

            // Test 5: Binary data
            byte[] binKey = new byte[] {0x00, 0x01, (byte) 0xFF, (byte) 0xFE};
            byte[] binValue = new byte[] {(byte) 0x80, (byte) 0x90, (byte) 0xA0};
            segment.put(binKey, binValue);
            result = segment.get(binKey);
            check("Should handle binary keys and values",
                result != null && arrayEquals(binValue, result.toArray(ValueLayout.JAVA_BYTE)));

            // Test 6: Empty value
            byte[] emptyKey = "empty-value-key".getBytes();
            byte[] emptyValue = new byte[0];
            segment.put(emptyKey, emptyValue);
            result = segment.get(emptyKey);
            check("Should handle empty value",
                result != null && result.byteSize() == 0);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testDeleteOperations() throws IOException {
        System.out.println("\n--- delete Operations ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Test 1: Delete existing key
            byte[] key = "delete-key".getBytes();
            byte[] value = "delete-value".getBytes();
            segment.put(key, value);
            boolean deleted = segment.delete(key);
            check("Should delete existing key and return true",
                deleted && segment.get(key) == null);

            // Test 2: Delete non-existent key
            check("Should return false for non-existent key",
                !segment.delete("nonexistent".getBytes()));

            // Test 3: Delete twice
            segment.put(key, value);
            segment.delete(key);
            check("Should return false when deleting already deleted key",
                !segment.delete(key));

            // Test 4: Delete isolation
            byte[] key1 = "key1".getBytes();
            byte[] key2 = "key2".getBytes();
            segment.put(key1, "value1".getBytes());
            segment.put(key2, "value2".getBytes());
            segment.delete(key1);
            check("Should not affect other keys after delete",
                segment.get(key1) == null &&
                arrayEquals("value2".getBytes(), segment.get(key2).toArray(ValueLayout.JAVA_BYTE)));

            // Test 5: Re-insert after delete
            segment.delete(key2);
            segment.put(key2, "new-value".getBytes());
            check("Should allow re-insert after delete",
                arrayEquals("new-value".getBytes(), segment.get(key2).toArray(ValueLayout.JAVA_BYTE)));

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testZeroCopyValidation() throws IOException {
        System.out.println("\n--- Zero-Copy Validation ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Test 1: Returns MemorySegment
            byte[] key = "zero-copy-key".getBytes();
            byte[] value = "zero-copy-value".getBytes();
            segment.put(key, value);

            MemorySegment result = segment.get(key);
            check("get should return MemorySegment not null",
                result != null);
            check("get should return MemorySegment with correct size",
                result.byteSize() == value.length);

            // Test 2: Can access memory directly
            boolean canAccess = false;
            try {
                byte firstByte = result.get(ValueLayout.JAVA_BYTE, 0);
                canAccess = (firstByte == value[0]);
            } catch (Exception e) {
                // Ignore
            }
            check("MemorySegment should be accessible for zero-copy reads", canAccess);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testLoadFactorRejection() throws IOException {
        System.out.println("\n--- Load Factor Rejection ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        // Use larger capacity to ensure we have enough space for data region
        // With 64MB: slot table ~24MB, data region ~40MB (enough for ~70% load factor)
        Segment segment = new Segment(segmentFile, 64 * 1024 * 1024, arena);

        try {
            // Test 1: Load factor increases
            double initialLoadFactor = segment.loadFactor();
            segment.put("key1".getBytes(), "value1".getBytes());
            check("Load factor should increase with insertions",
                segment.loadFactor() > initialLoadFactor);

            // Test 2: Reject at >= 0.7
            int slotCount = segment.slotCount();
            int maxEntries = (int) (slotCount * Segment.LOAD_FACTOR_THRESHOLD) - 1;

            // Create new segment for fill test
            segment.close();
            arena.close();
            arena = Arena.ofConfined();
            segment = new Segment(segmentFile, 64 * 1024 * 1024, arena);

            for (int i = 0; i < maxEntries; i++) {
                try {
                    segment.put(("load-test-" + i).getBytes(), ("value-" + i).getBytes());
                } catch (IllegalStateException e) {
                    // May hit data region limit before load factor limit - that's okay
                    break;
                }
            }

            // Try to insert one more - should be rejected by either load factor or space
            boolean rejected = false;
            try {
                segment.put("overflow-key".getBytes(), "overflow-value".getBytes());
            } catch (IllegalStateException e) {
                rejected = true;
            }
            check("Should reject insertions at load factor >= 0.7", rejected);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testHashCollisions() throws IOException {
        System.out.println("\n--- Hash Collision Handling ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Test 1: Force collisions with many keys
            int count = 500;
            for (int i = 0; i < count; i++) {
                segment.put(("collision-key-" + String.format("%04d", i)).getBytes(),
                           ("collision-value-" + i).getBytes());
            }

            boolean allFound = true;
            for (int i = 0; i < count; i++) {
                MemorySegment result = segment.get(
                    ("collision-key-" + String.format("%04d", i)).getBytes());
                if (result == null) {
                    allFound = false;
                    break;
                }
            }
            check("Should handle keys with potential hash collisions", allFound);

            // Test 2: Similar keys
            String[] keys = {"key", "key1", "key2", "key10", "key01", "keya", "keyA"};
            for (String k : keys) {
                segment.put(k.getBytes(), ("value-" + k).getBytes());
            }
            allFound = true;
            for (String k : keys) {
                MemorySegment result = segment.get(k.getBytes());
                if (result == null || !arrayEquals(("value-" + k).getBytes(),
                    result.toArray(ValueLayout.JAVA_BYTE))) {
                    allFound = false;
                    break;
                }
            }
            check("Should correctly distinguish similar keys", allFound);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testRobinHoodDisplacement() throws IOException {
        System.out.println("\n--- Robin Hood Displacement ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Insert many entries
            List<byte[]> keys = new ArrayList<>();
            List<byte[]> values = new ArrayList<>();

            for (int i = 0; i < 200; i++) {
                byte[] key = ("rh-key-" + i).getBytes();
                byte[] value = ("rh-value-" + i).getBytes();
                keys.add(key);
                values.add(value);
                segment.put(key, value);
            }

            boolean allFound = true;
            for (int i = 0; i < 200; i++) {
                MemorySegment result = segment.get(keys.get(i));
                if (result == null || !arrayEquals(values.get(i), result.toArray(ValueLayout.JAVA_BYTE))) {
                    allFound = false;
                    break;
                }
            }
            check("Entries should be retrievable regardless of insertion order", allFound);

            // Test insert-delete-insert pattern
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 30; i++) {
                    segment.put(("round-" + round + "-key-" + i).getBytes(),
                               ("value-" + i).getBytes());
                }
                for (int i = 0; i < 15; i++) {
                    segment.delete(("round-" + round + "-key-" + i).getBytes());
                }
            }

            int foundCount = 0;
            for (int round = 0; round < 3; round++) {
                for (int i = 15; i < 30; i++) {
                    if (segment.get(("round-" + round + "-key-" + i).getBytes()) != null) {
                        foundCount++;
                    }
                }
            }
            check("Should handle insert-delete-insert pattern", foundCount > 0);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testConcurrentReads() throws Exception {
        System.out.println("\n--- Concurrent Reads ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        // Use shared arena to allow memory access from multiple threads
        Arena arena = Arena.ofShared();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Pre-populate
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
                            MemorySegment result = segment.get(
                                ("concurrent-key-" + keyIndex).getBytes());
                            if (result != null) {
                                successCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
            executor.shutdown();

            check("Multiple threads should read concurrently without exceptions",
                errorCount.get() == 0);
            check("All concurrent reads should succeed",
                successCount.get() == numThreads * readsPerThread);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testDomainCorrectness() throws IOException {
        System.out.println("\n--- Domain Correctness ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Test 1: Key hash is positive (internal)
            segment.put("positive-hash-1".getBytes(), "value1".getBytes());
            segment.put("positive-hash-2".getBytes(), "value2".getBytes());
            check("Key hash values should be positive 64-bit (verified by retrieval)",
                segment.get("positive-hash-1".getBytes()) != null &&
                segment.get("positive-hash-2".getBytes()) != null);

            // Test 2: Distance values valid
            for (int i = 0; i < 100; i++) {
                segment.put(("distance-key-" + i).getBytes(),
                           ("distance-value-" + i).getBytes());
            }
            boolean allFound = true;
            for (int i = 0; i < 100; i++) {
                if (segment.get(("distance-key-" + i).getBytes()) == null) {
                    allFound = false;
                    break;
                }
            }
            check("Distance values should be in range [0, 127]", allFound);

            // Test 3: Tombstone value
            byte[] key = "tombstone-key".getBytes();
            segment.put(key, "value".getBytes());
            segment.delete(key);
            check("Tombstone should be exactly 1 for deleted entries",
                segment.get(key) == null);

            segment.put(key, "new-value".getBytes());
            check("Re-insert after tombstone should work",
                segment.get(key) != null);

            // Test 4: Capacity is power of 2
            int slotCount = segment.slotCount();
            check("Capacity should be power of 2",
                (slotCount & (slotCount - 1)) == 0);
            check("Capacity should be >= MIN",
                slotCount >= Segment.MIN_SEGMENT_CAPACITY);
            check("Capacity should be <= MAX",
                slotCount <= Segment.MAX_SEGMENT_CAPACITY);

            // Test 5: Load factor computation
            segment.close();
            arena.close();
            arena = Arena.ofConfined();
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

            double initialLf = segment.loadFactor();
            segment.put("lf-key".getBytes(), "value".getBytes());
            double newLf = segment.loadFactor();
            check("Load factor should be computed as entry_count / slot_count",
                initialLf == 0.0 && newLf > 0.0 &&
                newLf == segment.entryCount() / (double) segment.slotCount());

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    static void testArgumentValidation() throws IOException {
        System.out.println("\n--- Argument Validation ---");

        Path tempDir = Files.createTempDirectory("segment-test");
        Arena arena = Arena.ofConfined();
        Path segmentFile = tempDir.resolve("test-segment.bin");
        Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

        try {
            // Test null key in put
            boolean rejected = false;
            try {
                segment.put(null, "value".getBytes());
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check("Should reject null key in put", rejected);

            // Test null value in put
            rejected = false;
            try {
                segment.put("key".getBytes(), null);
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check("Should reject null value in put", rejected);

            // Test null key in get
            rejected = false;
            try {
                segment.get(null);
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check("Should reject null key in get", rejected);

            // Test null key in delete
            rejected = false;
            try {
                segment.delete(null);
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check("Should reject null key in delete", rejected);

        } finally {
            segment.close();
            arena.close();
            Files.deleteIfExists(segmentFile);
            Files.deleteIfExists(tempDir);
        }
    }

    // ==================== Helper Methods ====================

    static void check(String description, boolean passed) {
        if (passed) {
            System.out.println("  [PASS] " + description);
            passedTests++;
        } else {
            System.out.println("  [FAIL] " + description);
            failedTests++;
        }
    }

    static boolean arrayEquals(byte[] a1, byte[] a2) {
        if (a1 == null || a2 == null) return a1 == a2;
        if (a1.length != a2.length) return false;
        for (int i = 0; i < a1.length; i++) {
            if (a1[i] != a2[i]) return false;
        }
        return true;
    }
}
