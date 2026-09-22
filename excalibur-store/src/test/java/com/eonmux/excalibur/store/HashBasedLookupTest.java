package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for hash-based point lookup methods in Segment.
 *
 * <p>Tests the following public API methods:</p>
 * <ul>
 *   <li>{@link Segment#getByHash(byte[], long)} - lookup with lock</li>
 *   <li>{@link Segment#getByHashWithoutLock(byte[], long)} - lookup without lock</li>
 *   <li>{@link Segment#batchGetByHash(byte[][], long[])} - batch lookup</li>
 *   <li>{@link Segment#hashKey(byte[])} - hash computation</li>
 * </ul>
 */
class HashBasedLookupTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path tempFile;
    private Segment segment;

    private static final int ENTRY_COUNT = 100;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 64;

    private byte[][] keys;
    private byte[][] values;
    private long[] hashes;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        tempFile = Files.createTempFile(tempDir, "hash_lookup_test_", ".bin");

        // Create segment with 10MB size
        segment = new Segment(tempFile, 10 * 1024 * 1024, arena);

        // Initialize test data
        Random random = new Random(42);
        keys = new byte[ENTRY_COUNT][];
        values = new byte[ENTRY_COUNT][];
        hashes = new long[ENTRY_COUNT];

        // Insert entries
        for (int i = 0; i < ENTRY_COUNT; i++) {
            String keyStr = "key-" + String.format("%08d", i);
            keys[i] = keyStr.getBytes();

            values[i] = new byte[VALUE_SIZE];
            random.nextBytes(values[i]);
            // Store a marker in the first bytes for verification
            String marker = "val" + i;
            System.arraycopy(marker.getBytes(), 0, values[i], 0, Math.min(marker.length(), VALUE_SIZE));

            segment.put(keys[i], values[i]);
        }

        // Pre-compute hashes
        for (int i = 0; i < ENTRY_COUNT; i++) {
            hashes[i] = segment.hashKey(keys[i]);
        }
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

    private byte[] toByteArray(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }

    @Test
    void testGetByHash_ReturnsCorrectValue() {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            var result = segment.getByHash(keys[i], hashes[i]);
            assertNotNull(result, "Entry " + i + " should be found");

            byte[] resultBytes = toByteArray(result);
            assertArrayEquals(values[i], resultBytes, "Value mismatch for entry " + i);
        }
    }

    @Test
    void testGetByHash_NonExistentKey_ReturnsNull() {
        byte[] missKey = "nonexistent-key-xyz".getBytes();
        long missHash = segment.hashKey(missKey);
        var result = segment.getByHash(missKey, missHash);
        assertNull(result, "Non-existent key should return null");
    }

    @Test
    void testGetByHashWithoutLock_ReturnsCorrectValue() {
        segment.readLock().lock();
        try {
            for (int i = 0; i < ENTRY_COUNT; i++) {
                var result = segment.getByHashWithoutLock(keys[i], hashes[i]);
                assertNotNull(result, "Entry " + i + " should be found");

                byte[] resultBytes = toByteArray(result);
                assertArrayEquals(values[i], resultBytes, "Value mismatch for entry " + i);
            }
        } finally {
            segment.readLock().unlock();
        }
    }

    @Test
    void testGetByHashWithoutLock_NonExistentKey_ReturnsNull() {
        byte[] missKey = "nonexistent-key-xyz".getBytes();
        long missHash = segment.hashKey(missKey);

        segment.readLock().lock();
        try {
            var result = segment.getByHashWithoutLock(missKey, missHash);
            assertNull(result, "Non-existent key should return null");
        } finally {
            segment.readLock().unlock();
        }
    }

    @Test
    void testBatchGetByHash_ReturnsCorrectValues() {
        var results = segment.batchGetByHash(keys, hashes);

        assertEquals(ENTRY_COUNT, results.length, "Result count should match input count");

        for (int i = 0; i < ENTRY_COUNT; i++) {
            assertNotNull(results[i], "Entry " + i + " should be found");
            byte[] resultBytes = toByteArray(results[i]);
            assertArrayEquals(values[i], resultBytes, "Value mismatch for entry " + i);
        }
    }

    @Test
    void testBatchGetByHash_MixedExistingAndNonExisting() {
        // Create mixed batch: 50 existing, 50 non-existing
        byte[][] mixedKeys = new byte[100][];
        long[] mixedHashes = new long[100];

        for (int i = 0; i < 50; i++) {
            mixedKeys[i] = keys[i];
            mixedHashes[i] = hashes[i];
        }
        for (int i = 50; i < 100; i++) {
            mixedKeys[i] = ("nonexistent-" + i).getBytes();
            mixedHashes[i] = segment.hashKey(mixedKeys[i]);
        }

        var results = segment.batchGetByHash(mixedKeys, mixedHashes);

        // First 50 should be found
        for (int i = 0; i < 50; i++) {
            assertNotNull(results[i], "Existing entry " + i + " should be found");
        }
        // Last 50 should be null
        for (int i = 50; i < 100; i++) {
            assertNull(results[i], "Non-existing entry " + i + " should be null");
        }
    }

    @Test
    void testHashKey_ProducesPositiveHashes() {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            long hash = segment.hashKey(keys[i]);
            assertTrue(hash >= 0, "Hash should be non-negative: " + hash);
        }
    }

    @Test
    void testHashKey_ConsistentResults() {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            long hash1 = segment.hashKey(keys[i]);
            long hash2 = segment.hashKey(keys[i]);
            assertEquals(hash1, hash2, "Hash should be consistent for same key");
        }
    }

    @Test
    void testZeroCopy_ReturnsMemorySegmentSlice() {
        var result1 = segment.getByHash(keys[0], hashes[0]);
        var result2 = segment.getByHash(keys[0], hashes[0]);

        // Both results should be MemorySegment slices
        assertNotNull(result1);
        assertNotNull(result2);

        // Values should match
        assertArrayEquals(toByteArray(result1), toByteArray(result2));
    }

    @Test
    void testTombstonedKey_ReturnsNull() {
        // Delete a key
        segment.delete(keys[50]);

        // Should return null
        var result = segment.getByHash(keys[50], hashes[50]);
        assertNull(result, "Tombstoned key should return null");
    }

    @Test
    void testBatchGetByHash_ThrowsOnMismatchedArrayLengths() {
        byte[][] badKeys = new byte[10][];
        long[] badHashes = new long[5]; // Mismatched length

        assertThrows(IllegalArgumentException.class, () -> {
            segment.batchGetByHash(badKeys, badHashes);
        });
    }

    @Test
    void testBatchGetByHash_ThrowsOnNullArrays() {
        assertThrows(IllegalArgumentException.class, () -> {
            segment.batchGetByHash(null, hashes);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            segment.batchGetByHash(keys, null);
        });
    }

    @Test
    void testConcurrentAccess_MultipleThreads() throws Exception {
        // Test concurrent lookups across 8 threads (80,000 total lookups)
        int numThreads = 8;
        int lookupsPerThread = 10_000;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random localRandom = new Random(42 + threadId);

                    for (int i = 0; i < lookupsPerThread; i++) {
                        int keyIdx = localRandom.nextInt(ENTRY_COUNT);
                        var result = segment.getByHash(keys[keyIdx], hashes[keyIdx]);

                        if (result == null) {
                            errorCount.incrementAndGet();
                        } else {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds");
        executor.shutdown();

        int expectedTotal = numThreads * lookupsPerThread;
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent access");
        assertEquals(expectedTotal, successCount.get(), "All lookups should succeed");
    }

    @Test
    void testConcurrentBatchAccess() throws Exception {
        // Test concurrent batch lookups
        int numThreads = 4;
        int batchesPerThread = 100;
        int batchSize = 50;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random localRandom = new Random(123 + threadId);

                    for (int b = 0; b < batchesPerThread; b++) {
                        byte[][] batchKeys = new byte[batchSize][];
                        long[] batchHashes = new long[batchSize];

                        for (int i = 0; i < batchSize; i++) {
                            int keyIdx = localRandom.nextInt(ENTRY_COUNT);
                            batchKeys[i] = keys[keyIdx];
                            batchHashes[i] = hashes[keyIdx];
                        }

                        var results = segment.batchGetByHash(batchKeys, batchHashes);

                        for (var r : results) {
                            if (r == null) {
                                errorCount.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds");
        executor.shutdown();

        assertEquals(0, errorCount.get(), "No errors should occur during concurrent batch access");
    }
}
