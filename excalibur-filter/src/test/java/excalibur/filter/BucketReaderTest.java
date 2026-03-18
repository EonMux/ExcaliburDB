package excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BucketReader lock-free atomic reads.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Atomic bucket reads via VarHandle</li>
 *   <li>Fingerprint extraction from atomic values</li>
 *   <li>Concurrent read safety</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class BucketReaderTest {

    // BucketReader uses 2 bytes per fingerprint (16 bits), even if only 8-13 bits are used
    private static final int BYTES_PER_BUCKET = 8;
    private static final int HEADER_SIZE = 40;

    @Test
    @DisplayName("BucketReader can be created with valid parameters")
    void testCreation() {
        // Create a small memory segment for testing
        try (Arena arena = Arena.ofConfined()) {
            // 40 bytes header + 8 buckets * 8 bytes = 104 bytes
            MemorySegment segment = arena.allocate(104);

            // Create BucketReader
            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            assertEquals(8, reader.numBuckets());
            assertEquals(0x1FFF, reader.fingerprintMask()); // 13 bits = 0x1FFF
            assertEquals(8, BucketReader.bytesPerBucket());
            assertEquals(4, BucketReader.fingerprintsPerBucket());
        }
    }

    @Test
    @DisplayName("Atomic bucket read returns consistent value")
    void testAtomicBucketRead() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48); // 40 header + 8 bucket

            // Write a known pattern to bucket 0
            // BucketReader uses 2 bytes per fingerprint (16 bits each)
            // FP0=0x1000, FP1=0x0800, FP2=0x0400, FP3=0x0200
            long bucketValue = 0x1000_0800_0400_0200L;
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            // Read atomically
            long readValue = reader.readBucketAtomic(0);

            assertEquals(bucketValue, readValue, "Atomic read should return the exact value written");
        }
    }

    @Test
    @DisplayName("Fingerprint extraction from atomic bucket value works correctly")
    void testFingerprintExtraction() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Write fingerprints: FP0=1, FP1=2, FP2=3, FP3=4
            // Each fingerprint is 16 bits (2 bytes) in big-endian
            // FP0 at bits 48-63, FP1 at bits 32-47, FP2 at bits 16-31, FP3 at bits 0-15
            long fp0 = 1, fp1 = 2, fp2 = 3, fp3 = 4;
            long bucketValue = (fp0 << 48) | (fp1 << 32) | (fp2 << 16) | fp3;
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);
            long readValue = reader.readBucketAtomic(0);

            // Extract fingerprints using the configured mask (13 bits = 0x1FFF)
            assertEquals(1, reader.extractFingerprint(readValue, 0));
            assertEquals(2, reader.extractFingerprint(readValue, 1));
            assertEquals(3, reader.extractFingerprint(readValue, 2));
            assertEquals(4, reader.extractFingerprint(readValue, 3));
        }
    }

    @Test
    @DisplayName("containsFingerprintAtomic finds fingerprint in bucket")
    void testContainsFingerprintAtomic() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Write fingerprint 0x1234 in slot 0
            int fingerprint = 0x1234;
            long bucketValue = ((long) fingerprint) << 48; // Slot 0 at bits 48-63
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            assertTrue(reader.containsFingerprintAtomic(0, fingerprint));
            assertFalse(reader.containsFingerprintAtomic(0, 0x5678));
        }
    }

    @Test
    @DisplayName("readAllFingerprintsAtomic returns all fingerprints")
    void testReadAllFingerprintsAtomic() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Write 4 different fingerprints
            int[] fps = {0x100, 0x200, 0x300, 0x400};
            long bucketValue = ((long) fps[0] << 48) | ((long) fps[1] << 32) |
                               ((long) fps[2] << 16) | fps[3];
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);
            int[] readFps = reader.readAllFingerprintsAtomic(0);

            assertArrayEquals(fps, readFps);
        }
    }

    @Test
    @DisplayName("findEmptySlotAtomic returns -1 for full bucket")
    void testFindEmptySlotAtomicFullBucket() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Fill all slots with non-zero fingerprints
            long bucketValue = 0x1000_1000_1000_1000L;
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            assertEquals(-1, reader.findEmptySlotAtomic(0));
        }
    }

    @Test
    @DisplayName("findEmptySlotAtomic finds empty slot")
    void testFindEmptySlotAtomicFindsEmpty() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Slot 2 is empty (0)
            long bucketValue = (0x1000L << 48) | (0x1000L << 32) | (0L << 16) | (0x1000L);
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            assertEquals(2, reader.findEmptySlotAtomic(0));
        }
    }

    @Test
    @DisplayName("Invalid parameters throw exceptions")
    void testInvalidParameters() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Null segment
            assertThrows(IllegalArgumentException.class, () ->
                new BucketReader(null, HEADER_SIZE, 13, 8));

            // Negative offset
            assertThrows(IllegalArgumentException.class, () ->
                new BucketReader(segment, -1, 13, 8));

            // Invalid fingerprint bits
            assertThrows(IllegalArgumentException.class, () ->
                new BucketReader(segment, HEADER_SIZE, 7, 8));
            assertThrows(IllegalArgumentException.class, () ->
                new BucketReader(segment, HEADER_SIZE, 17, 8));

            // Invalid numBuckets
            assertThrows(IllegalArgumentException.class, () ->
                new BucketReader(segment, HEADER_SIZE, 13, 0));
        }
    }

    @Test
    @DisplayName("Bucket index out of bounds throws exception")
    void testBucketIndexOutOfBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 1);

            // Negative index
            assertThrows(IndexOutOfBoundsException.class, () ->
                reader.readBucketAtomic(-1));

            // Index >= numBuckets
            assertThrows(IndexOutOfBoundsException.class, () ->
                reader.readBucketAtomic(1));
        }
    }

    @Test
    @DisplayName("Slot out of bounds throws exception")
    void testSlotOutOfBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 1);
            long bucketValue = reader.readBucketAtomic(0);

            // Negative slot
            assertThrows(IndexOutOfBoundsException.class, () ->
                reader.extractFingerprint(bucketValue, -1));

            // Slot >= 4
            assertThrows(IndexOutOfBoundsException.class, () ->
                reader.extractFingerprint(bucketValue, 4));
        }
    }

    @Test
    @DisplayName("Concurrent reads are thread-safe")
    void testConcurrentReads() throws InterruptedException {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment segment = arena.allocate(48);

            // Write a known pattern
            long bucketValue = 0x1234_5678_9ABC_DEF0L;
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];
            boolean[] results = new boolean[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadIndex = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    long value = reader.readBucketAtomic(0);
                    // Each read should get the exact same value
                    results[threadIndex] = (value == bucketValue);
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // All reads should have succeeded
            for (int i = 0; i < numThreads; i++) {
                assertTrue(results[i], "Concurrent read " + i + " should return correct value");
            }
        }
    }

    @Test
    @DisplayName("Find fingerprint slot works correctly")
    void testFindFingerprintSlotAtomic() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(48);

            // Write fingerprint 0x1678 in slot 2
            // Note: Using 13-bit fingerprint, so max value is 0x1FFF
            int fingerprint = 0x1678; // Fits in 13 bits
            long bucketValue = (0x1000L << 48) | (0x2000L << 32) |
                               ((long) fingerprint << 16) | (0x0400L);
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), HEADER_SIZE, bucketValue);

            BucketReader reader = new BucketReader(segment, HEADER_SIZE, 13, 8);

            assertEquals(2, reader.findFingerprintSlotAtomic(0, fingerprint));
            assertEquals(-1, reader.findFingerprintSlotAtomic(0, 0x1999));
        }
    }
}
