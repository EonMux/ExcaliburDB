package excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for zero-copy slice validity tracking.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>ZeroCopySlice creation and validity</li>
 *   <li>Epoch-based validity tracking</li>
 *   <li>Slice invalidation on put/delete operations</li>
 *   <li>Slice invalidation on segment close</li>
 *   <li>Domain correctness of epoch values</li>
 * </ul>
 */
@DisplayName("Zero-Copy Slice Validity Tests")
class ZeroCopySliceValidityTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path segmentFile;
    private Segment segment;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segmentFile = tempDir.resolve("validity-test-segment.bin");
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

    // ==================== ZeroCopySlice Creation Tests ====================

    @Nested
    @DisplayName("ZeroCopySlice Creation")
    class SliceCreationTests {

        @Test
        @DisplayName("getWithEpoch should return found slice for existing key")
        void testGetWithEpochFound() {
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertNotNull(slice);
            assertTrue(slice.isFound());
            assertNotNull(slice.data());
            assertEquals(value.length, slice.size());
        }

        @Test
        @DisplayName("getWithEpoch should return not-found slice for missing key")
        void testGetWithEpochNotFound() {
            ZeroCopySlice slice = segment.getWithEpoch("nonexistent".getBytes());

            assertNotNull(slice);
            assertFalse(slice.isFound());
            assertNull(slice.data());
            assertEquals(0, slice.size());
        }

        @Test
        @DisplayName("ZeroCopySlice should have correct epoch")
        void testSliceEpoch() {
            byte[] key = "epoch-key".getBytes();
            byte[] value = "epoch-value".getBytes();

            segment.put(key, value);
            long epochBefore = segment.currentEpoch();
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertEquals(epochBefore, slice.epoch());
        }

        @Test
        @DisplayName("ZeroCopySlice should have correct segment ID")
        void testSliceSegmentId() {
            byte[] key = "segment-id-key".getBytes();
            byte[] value = "value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertEquals(segment.segmentId(), slice.segmentId());
        }

        @Test
        @DisplayName("ZeroCopySlice data should contain correct bytes")
        void testSliceDataContent() {
            byte[] key = "content-key".getBytes();
            byte[] value = "test-content-value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isFound());
            assertArrayEquals(value, slice.toByteArray());
            assertArrayEquals(value, slice.data().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ==================== Slice Validity Tests ====================

    @Nested
    @DisplayName("Slice Validity")
    class SliceValidityTests {

        @Test
        @DisplayName("Newly created slice should be valid")
        void testNewSliceIsValid() {
            byte[] key = "valid-key".getBytes();
            byte[] value = "valid-value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isValid());
            assertTrue(segment.isSliceValid(slice));
        }

        @Test
        @DisplayName("Slice should become invalid after put operation")
        void testSliceInvalidAfterPut() {
            byte[] key1 = "key1".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] key2 = "key2".getBytes();

            segment.put(key1, value1);
            ZeroCopySlice slice = segment.getWithEpoch(key1);

            assertTrue(slice.isValid());

            // Modify segment with another put
            segment.put(key2, "value2".getBytes());

            // Original slice should now be invalid
            assertFalse(slice.isValid());
            assertFalse(segment.isSliceValid(slice));
        }

        @Test
        @DisplayName("Slice should become invalid after update operation")
        void testSliceInvalidAfterUpdate() {
            byte[] key = "update-key".getBytes();
            byte[] value1 = "value1".getBytes();

            segment.put(key, value1);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isValid());

            // Update the same key
            segment.put(key, "value2".getBytes());

            // Slice should now be invalid
            assertFalse(slice.isValid());
        }

        @Test
        @DisplayName("Slice should become invalid after delete operation")
        void testSliceInvalidAfterDelete() {
            byte[] key = "delete-key".getBytes();
            byte[] value = "delete-value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isValid());

            // Delete the key
            segment.delete(key);

            // Slice should now be invalid
            assertFalse(slice.isValid());
        }

        @Test
        @DisplayName("Slice should become invalid after segment close")
        void testSliceInvalidAfterClose() {
            byte[] key = "close-key".getBytes();
            byte[] value = "close-value".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isValid());

            // Close segment
            segment.close();

            // Slice should now be invalid
            assertFalse(slice.isValid());
        }

        @Test
        @DisplayName("Multiple slices should all become invalid after modification")
        void testMultipleSlicesInvalidAfterModification() {
            byte[] key1 = "multi-key1".getBytes();
            byte[] key2 = "multi-key2".getBytes();
            byte[] key3 = "multi-key3".getBytes();

            segment.put(key1, "value1".getBytes());
            segment.put(key2, "value2".getBytes());
            segment.put(key3, "value3".getBytes());

            ZeroCopySlice slice1 = segment.getWithEpoch(key1);
            ZeroCopySlice slice2 = segment.getWithEpoch(key2);
            ZeroCopySlice slice3 = segment.getWithEpoch(key3);

            assertTrue(slice1.isValid());
            assertTrue(slice2.isValid());
            assertTrue(slice3.isValid());

            // Modify segment
            segment.delete(key1);

            // All slices should now be invalid
            assertFalse(slice1.isValid());
            assertFalse(slice2.isValid());
            assertFalse(slice3.isValid());
        }
    }

    // ==================== Epoch Tracking Tests ====================

    @Nested
    @DisplayName("Epoch Tracking")
    class EpochTrackingTests {

        @Test
        @DisplayName("Initial epoch should be 0")
        void testInitialEpoch() {
            assertEquals(0, segment.currentEpoch());
        }

        @Test
        @DisplayName("Epoch should increment on put")
        void testEpochIncrementsOnPut() {
            long epoch0 = segment.currentEpoch();

            segment.put("key1".getBytes(), "value1".getBytes());
            assertEquals(epoch0 + 1, segment.currentEpoch());

            segment.put("key2".getBytes(), "value2".getBytes());
            assertEquals(epoch0 + 2, segment.currentEpoch());
        }

        @Test
        @DisplayName("Epoch should increment on update")
        void testEpochIncrementsOnUpdate() {
            byte[] key = "update-key".getBytes();

            segment.put(key, "value1".getBytes());
            long epoch1 = segment.currentEpoch();

            segment.put(key, "value2".getBytes());
            assertEquals(epoch1 + 1, segment.currentEpoch());
        }

        @Test
        @DisplayName("Epoch should increment on delete")
        void testEpochIncrementsOnDelete() {
            byte[] key = "delete-key".getBytes();

            segment.put(key, "value".getBytes());
            long epochBeforeDelete = segment.currentEpoch();

            segment.delete(key);
            assertEquals(epochBeforeDelete + 1, segment.currentEpoch());
        }

        @Test
        @DisplayName("Epoch should NOT increment on failed delete")
        void testEpochNoIncrementOnFailedDelete() {
            long epoch = segment.currentEpoch();

            // Delete non-existent key
            boolean deleted = segment.delete("nonexistent".getBytes());

            assertFalse(deleted);
            assertEquals(epoch, segment.currentEpoch());
        }

        @Test
        @DisplayName("Epoch should NOT increment on read")
        void testEpochNoIncrementOnRead() {
            byte[] key = "read-key".getBytes();
            segment.put(key, "value".getBytes());

            long epoch = segment.currentEpoch();

            segment.get(key);
            assertEquals(epoch, segment.currentEpoch());

            segment.getWithEpoch(key);
            assertEquals(epoch, segment.currentEpoch());
        }
    }

    // ==================== Segment ID Tests ====================

    @Nested
    @DisplayName("Segment ID")
    class SegmentIdTests {

        @Test
        @DisplayName("Each segment should have unique ID")
        void testUniqueSegmentIds() throws IOException {
            Segment segment2 = new Segment(tempDir.resolve("segment2.bin"), 1024 * 1024, arena);
            Segment segment3 = new Segment(tempDir.resolve("segment3.bin"), 1024 * 1024, arena);

            try {
                long id1 = segment.segmentId();
                long id2 = segment2.segmentId();
                long id3 = segment3.segmentId();

                assertNotEquals(id1, id2);
                assertNotEquals(id2, id3);
                assertNotEquals(id1, id3);

                assertTrue(id1 >= 0);
                assertTrue(id2 >= 0);
                assertTrue(id3 >= 0);
            } finally {
                segment2.close();
                segment3.close();
            }
        }

        @Test
        @DisplayName("Slice from different segment should be invalid")
        void testSliceFromDifferentSegment() throws IOException {
            Segment segment2 = new Segment(tempDir.resolve("other-segment.bin"), 1024 * 1024, arena);

            try {
                byte[] key = "cross-key".getBytes();

                segment.put(key, "value1".getBytes());
                segment2.put(key, "value2".getBytes());

                ZeroCopySlice slice1 = segment.getWithEpoch(key);
                ZeroCopySlice slice2 = segment2.getWithEpoch(key);

                // Each slice should be valid for its own segment
                assertTrue(segment.isSliceValid(slice1));
                assertTrue(segment2.isSliceValid(slice2));

                // Cross-validation should fail
                assertFalse(segment.isSliceValid(slice2));
                assertFalse(segment2.isSliceValid(slice1));
            } finally {
                segment2.close();
            }
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Epoch should always be non-negative")
        void testEpochNonNegative() {
            // Perform many operations
            for (int i = 0; i < 100; i++) {
                segment.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }

            assertTrue(segment.currentEpoch() >= 0);
        }

        @Test
        @DisplayName("ZeroCopySlice epoch should be non-negative")
        void testSliceEpochNonNegative() {
            segment.put("key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = segment.getWithEpoch("key".getBytes());

            assertTrue(slice.epoch() >= 0);
        }

        @Test
        @DisplayName("ZeroCopySlice segmentId should be non-negative")
        void testSliceSegmentIdNonNegative() {
            segment.put("key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = segment.getWithEpoch("key".getBytes());

            assertTrue(slice.segmentId() >= 0);
        }

        @Test
        @DisplayName("ZeroCopySlice size should match value length")
        void testSliceSizeCorrect() {
            byte[] value = new byte[256];
            for (int i = 0; i < value.length; i++) {
                value[i] = (byte) i;
            }

            segment.put("size-key".getBytes(), value);
            ZeroCopySlice slice = segment.getWithEpoch("size-key".getBytes());

            assertEquals(value.length, slice.size());
            assertEquals(value.length, slice.data().byteSize());
        }

        @Test
        @DisplayName("ZeroCopySlice toByteArray should return copy not reference")
        void testToByteArrayReturnsCopy() {
            byte[] key = "copy-key".getBytes();
            byte[] value = "original".getBytes();

            segment.put(key, value);
            ZeroCopySlice slice = segment.getWithEpoch(key);

            byte[] copy1 = slice.toByteArray();
            byte[] copy2 = slice.toByteArray();

            // Should be different array instances
            assertNotSame(copy1, copy2);
            // But same content
            assertArrayEquals(copy1, copy2);
        }
    }

    // ==================== ZeroCopySlice Helper Methods Tests ====================

    @Nested
    @DisplayName("ZeroCopySlice Helper Methods")
    class SliceHelperMethodsTests {

        @Test
        @DisplayName("firstByte should return first byte or -1 for empty/null")
        void testFirstByte() {
            segment.put("key1".getBytes(), "value".getBytes());
            segment.put("key2".getBytes(), new byte[0]);

            ZeroCopySlice slice1 = segment.getWithEpoch("key1".getBytes());
            ZeroCopySlice slice2 = segment.getWithEpoch("key2".getBytes());
            ZeroCopySlice slice3 = segment.getWithEpoch("nonexistent".getBytes());

            assertEquals('v', slice1.firstByte());
            assertEquals(-1, slice2.firstByte());
            assertEquals(-1, slice3.firstByte());
        }

        @Test
        @DisplayName("getByte should read byte at offset")
        void testGetByte() {
            byte[] value = {0x01, 0x02, 0x03, 0x04, 0x05};
            segment.put("offset-key".getBytes(), value);

            ZeroCopySlice slice = segment.getWithEpoch("offset-key".getBytes());

            assertEquals(0x01, slice.getByte(0));
            assertEquals(0x02, slice.getByte(1));
            assertEquals(0x05, slice.getByte(4));
        }

        @Test
        @DisplayName("getByte should throw on out of bounds")
        void testGetByteOutOfBounds() {
            segment.put("oob-key".getBytes(), new byte[]{0x01, 0x02});
            ZeroCopySlice slice = segment.getWithEpoch("oob-key".getBytes());

            assertThrows(IndexOutOfBoundsException.class, () -> slice.getByte(2));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.getByte(-1));
        }

        @Test
        @DisplayName("dataOrThrow should return data for valid slice")
        void testDataOrThrowValid() {
            segment.put("throw-key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = segment.getWithEpoch("throw-key".getBytes());

            assertNotNull(slice.dataOrThrow());
        }

        @Test
        @DisplayName("dataOrThrow should throw for invalid slice")
        void testDataOrThrowInvalid() {
            segment.put("throw-key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = segment.getWithEpoch("throw-key".getBytes());

            // Invalidate the slice
            segment.put("other-key".getBytes(), "other".getBytes());

            assertThrows(IllegalStateException.class, slice::dataOrThrow);
        }

        @Test
        @DisplayName("dataOrThrow should throw for not found")
        void testDataOrThrowNotFound() {
            ZeroCopySlice slice = segment.getWithEpoch("nonexistent".getBytes());

            assertThrows(IllegalStateException.class, slice::dataOrThrow);
        }

        @Test
        @DisplayName("toString should contain useful information")
        void testToString() {
            segment.put("tostring-key".getBytes(), "value".getBytes());
            ZeroCopySlice slice = segment.getWithEpoch("tostring-key".getBytes());

            String str = slice.toString();

            assertTrue(str.contains("epoch="));
            assertTrue(str.contains("segmentId="));
            assertTrue(str.contains("5 bytes")); // "value".length()
            assertTrue(str.contains("valid"));
        }
    }

    // ==================== Concurrent Access Tests ====================

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("Multiple threads should see consistent epoch changes")
        void testConcurrentEpochChanges() throws Exception {
            int numThreads = 4;
            int opsPerThread = 100;
            Thread[] threads = new Thread[numThreads];

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] key = ("thread-" + threadId + "-key-" + i).getBytes();
                        segment.put(key, "value".getBytes());
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Epoch should equal total number of puts
            assertEquals(numThreads * opsPerThread, segment.currentEpoch());
        }

        @Test
        @DisplayName("Slice validity check should be thread-safe")
        void testConcurrentValidityCheck() throws Exception {
            byte[] key = "concurrent-key".getBytes();
            segment.put(key, "value".getBytes());

            ZeroCopySlice slice = segment.getWithEpoch(key);
            assertTrue(slice.isValid());

            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(10);
                    segment.put("other".getBytes(), "value".getBytes());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            writer.start();

            // Check validity while writer might be modifying
            boolean wasValidAtSomePoint = false;
            for (int i = 0; i < 100; i++) {
                if (slice.isValid()) {
                    wasValidAtSomePoint = true;
                }
                Thread.sleep(1);
            }

            writer.join();

            // After writer completes, slice should be invalid
            assertFalse(slice.isValid());
        }
    }

    // ==================== Null and Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("isSliceValid should throw on null")
        void testIsSliceValidNull() {
            assertThrows(NullPointerException.class, () -> segment.isSliceValid(null));
        }

        @Test
        @DisplayName("getWithEpoch should throw on null key")
        void testGetWithEpochNullKey() {
            assertThrows(IllegalArgumentException.class, () -> segment.getWithEpoch(null));
        }

        @Test
        @DisplayName("ZeroCopySlice should handle empty value")
        void testEmptyValue() {
            byte[] key = "empty-value-key".getBytes();
            segment.put(key, new byte[0]);

            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertTrue(slice.isFound());
            assertEquals(0, slice.size());
            assertNotNull(slice.data());
            assertEquals(0, slice.data().byteSize());
        }

        @Test
        @DisplayName("ZeroCopySlice should handle tombstoned entry")
        void testTombstonedEntry() {
            byte[] key = "tombstone-key".getBytes();
            segment.put(key, "value".getBytes());
            segment.delete(key);

            ZeroCopySlice slice = segment.getWithEpoch(key);

            assertFalse(slice.isFound());
            assertNull(slice.data());
        }
    }
}
