package com.eonmux.excalibur.core.mapped;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MappedFile using Panama FFM.
 */
class MappedFileTest {

    @TempDir
    Path tempDir;

    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test_" + System.nanoTime() + ".dat");
    }

    @AfterEach
    void tearDown() {
        // Files in @TempDir are automatically cleaned up
    }

    // ==================== Creation Tests ====================

    @Test
    void testCreateNewFile() throws IOException {
        assertFalse(Files.exists(testFile));

        MappedFile mf = MappedFile.create(testFile, 4096);

        assertNotNull(mf);
        assertTrue(Files.exists(testFile));
        assertEquals(4096, mf.size());
        assertFalse(mf.isClosed());
        assertFalse(mf.isReadOnly());
        mf.close();
    }

    @Test
    void testCreateWithPageAlignedSize() throws IOException {
        // Size should be rounded up to page size
        MappedFile mf = MappedFile.create(testFile, 1);
        assertEquals(4096, mf.size()); // Page size alignment
        mf.close();
    }

    @Test
    void testCreateWithNullPath() {
        assertThrows(IllegalArgumentException.class, () ->
            new MappedFile(null, 4096));
    }

    @Test
    void testCreateWithNegativeSize() {
        assertThrows(IllegalArgumentException.class, () ->
            new MappedFile(testFile, -1));
    }

    @Test
    void testCreateReadOnly() throws IOException {
        // First create a file with some data
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4});
        }

        // Now open read-only
        MappedFile readOnly = MappedFile.open(testFile);
        assertTrue(readOnly.isReadOnly());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, readOnly.readBytes(0, 4));

        // Write should fail
        assertThrows(IllegalStateException.class, () ->
            readOnly.writeBytes(0, new byte[]{5, 6, 7, 8}));

        readOnly.close();
    }

    // ==================== Byte Read/Write Tests ====================

    @Test
    void testWriteReadBytes() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            mf.writeBytes(0, data);

            byte[] read = mf.readBytes(0, 8);
            assertArrayEquals(data, read);
        }
    }

    @Test
    void testWriteReadBytesWithOffset() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
            mf.write(100, data, 2, 5); // Write bytes 2,3,4,5,6

            byte[] read = new byte[5];
            mf.read(100, read, 0, 5);
            assertArrayEquals(new byte[]{2, 3, 4, 5, 6}, read);
        }
    }

    @Test
    void testWriteReadSingleByte() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeByte(0, (byte) 0xAB);
            mf.writeByte(1, (byte) 0xCD);

            assertEquals((byte) 0xAB, mf.readByte(0));
            assertEquals((byte) 0xCD, mf.readByte(1));
        }
    }

    // ==================== Primitive Read/Write Tests ====================

    @Test
    void testWriteReadInt() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeInt(0, 0x12345678);
            mf.writeInt(4, Integer.MAX_VALUE);
            mf.writeInt(8, Integer.MIN_VALUE);

            assertEquals(0x12345678, mf.readInt(0));
            assertEquals(Integer.MAX_VALUE, mf.readInt(4));
            assertEquals(Integer.MIN_VALUE, mf.readInt(8));
        }
    }

    @Test
    void testWriteReadLong() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeLong(0, 0x123456789ABCDEF0L);
            mf.writeLong(8, Long.MAX_VALUE);
            mf.writeLong(16, Long.MIN_VALUE);

            assertEquals(0x123456789ABCDEF0L, mf.readLong(0));
            assertEquals(Long.MAX_VALUE, mf.readLong(8));
            assertEquals(Long.MIN_VALUE, mf.readLong(16));
        }
    }

    @Test
    void testWriteReadFloat() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeFloat(0, 3.14159f);
            mf.writeFloat(4, Float.MAX_VALUE);
            mf.writeFloat(8, Float.MIN_VALUE);

            assertEquals(3.14159f, mf.readFloat(0), 0.00001f);
            assertEquals(Float.MAX_VALUE, mf.readFloat(4));
            assertEquals(Float.MIN_VALUE, mf.readFloat(8));
        }
    }

    @Test
    void testWriteReadDouble() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeDouble(0, 3.141592653589793);
            mf.writeDouble(8, Double.MAX_VALUE);
            mf.writeDouble(16, Double.MIN_VALUE);

            assertEquals(3.141592653589793, mf.readDouble(0), 0.0000000001);
            assertEquals(Double.MAX_VALUE, mf.readDouble(8));
            assertEquals(Double.MIN_VALUE, mf.readDouble(16));
        }
    }

    @Test
    void testWriteReadFloatArray() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            float[] data = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            mf.writeFloatArray(0, data);

            float[] read = mf.readFloatArray(0, 5);
            assertArrayEquals(data, read, 0.0001f);
        }
    }

    @Test
    void testReadFloatArrayDecodesBigEndianAtAnUnalignedOffset() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            float[] data = new float[37];
            for (int i = 0; i < data.length; i++) {
                data[i] = (i - 18) * 1.25f + 1.0e-3f * i;
            }
            java.nio.ByteBuffer bigEndian = java.nio.ByteBuffer.allocate(data.length * 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
            for (float value : data) {
                bigEndian.putFloat(value);
            }
            mf.write(13, bigEndian.array());
            long before = mf.bytesRead();

            float[] read = mf.readFloatArray(13, data.length);

            assertArrayEquals(data, read, 0.0f);
            for (int i = 0; i < data.length; i++) {
                assertEquals(mf.readFloat(13 + 4L * i), read[i], 0.0f);
            }
            assertEquals(before + 4L * data.length, mf.bytesRead());
        }
    }

    // ==================== String Read/Write Tests ====================

    @Test
    void testWriteReadStringWithLengthPrefix() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            String text = "Hello, World!";
            int bytesWritten = mf.writeString(0, text);

            assertEquals(4 + text.getBytes().length, bytesWritten);
            assertEquals(text, mf.readString(0));
        }
    }

    @Test
    void testWriteReadStringRaw() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            String text = "Hello, World!";
            int bytesWritten = mf.writeStringRaw(0, text);

            assertEquals(text.getBytes().length, bytesWritten);
            assertEquals(text, mf.readString(0, text.getBytes().length));
        }
    }

    @Test
    void testWriteReadUtf8String() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            String text = "Hello 世界! Привет мир! مرحبا بالعالم!";
            mf.writeString(0, text);

            assertEquals(text, mf.readString(0));
        }
    }

    @Test
    void testWriteNullString() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertThrows(IllegalArgumentException.class, () ->
                mf.writeString(0, null));
        }
    }

    // ==================== Slice Tests ====================

    @Test
    void testSlice() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            var slice = mf.slice(2, 4);
            assertEquals(4, slice.byteSize());

            // Slice should be a view, not a copy
            assertEquals(3, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
            assertEquals(4, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 1));
            assertEquals(5, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 2));
            assertEquals(6, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3));
        }
    }

    @Test
    void testByteSlice() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            byte[] slice = mf.byteSlice(2, 4);
            assertArrayEquals(new byte[]{3, 4, 5, 6}, slice);
        }
    }

    // ==================== Extend/Truncate Tests ====================

    @Test
    void testExtend() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write some data
            mf.writeBytes(0, new byte[]{1, 2, 3, 4});

            // Extend adds to current size: 4096 + 8192 = 12288 (page-aligned)
            mf.extend(8192);
            assertEquals(12288, mf.size()); // 4096 + 8192 = 12288

            // Original data should be preserved
            assertArrayEquals(new byte[]{1, 2, 3, 4}, mf.readBytes(0, 4));
        }
    }

    @Test
    void testTruncate() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 8192)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4});

            mf.truncate(4096);
            assertEquals(4096, mf.size());
        }
    }

    // ==================== Comprehensive File Growth Tests ====================

    /**
     * Test: Multiple sequential extensions work correctly.
     * Verifies that extending a file multiple times properly accumulates size
     * and preserves all previously written data.
     */
    @Test
    void testMultipleExtensions() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Initial state
            assertEquals(4096, mf.size());

            // Write data at offset 0
            mf.writeBytes(0, new byte[]{1, 2, 3, 4});

            // First extension: 4096 + 4096 = 8192
            mf.extend(4096);
            assertEquals(8192, mf.size());

            // Write data at new region (offset 5000)
            mf.writeBytes(5000, new byte[]{10, 20, 30, 40});

            // Second extension: 8192 + 8192 = 16384
            mf.extend(8192);
            assertEquals(16384, mf.size());

            // Write data at newest region (offset 10000)
            mf.writeBytes(10000, new byte[]{(byte)100, (byte)110, (byte)120, (byte)130});

            // Verify ALL data is preserved
            assertArrayEquals(new byte[]{1, 2, 3, 4}, mf.readBytes(0, 4),
                "Data at offset 0 should be preserved");
            assertArrayEquals(new byte[]{10, 20, 30, 40}, mf.readBytes(5000, 4),
                "Data at offset 5000 should be preserved");
            assertArrayEquals(new byte[]{(byte)100, (byte)110, (byte)120, (byte)130}, mf.readBytes(10000, 4),
                "Data at offset 10000 should be preserved");
        }
    }

    /**
     * Test: Writing to newly extended regions works correctly.
     * Verifies that after extending, the new region is writable and readable.
     */
    @Test
    void testWriteToExtendedRegion() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write at start
            mf.writeInt(0, 0x11111111);

            // Extend file
            mf.extend(8192);
            long newSize = mf.size();
            assertTrue(newSize > 4096, "Size should be larger after extend");

            // Write at the END of the original region (boundary test)
            mf.writeInt(4092, 0x22222222);

            // Write in the middle of the new region
            mf.writeInt(6000, 0x33333333);

            // Write at near the end of the new region
            mf.writeInt(newSize - 8, 0x44444444);

            // Verify all writes
            assertEquals(0x11111111, mf.readInt(0));
            assertEquals(0x22222222, mf.readInt(4092));
            assertEquals(0x33333333, mf.readInt(6000));
            assertEquals(0x44444444, mf.readInt(newSize - 8));
        }
    }

    /**
     * Test: Data persists after extend and file reopen.
     * Verifies that data written before and after extend survives close/reopen.
     */
    @Test
    void testDataPersistsAfterExtendAndReopen() throws IOException {
        // Phase 1: Create, write, extend, write more, close
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeInt(0, 0xAAAAAAAA);
            mf.writeInt(4, 0xBBBBBBBB);
            mf.extend(8192);
            mf.writeInt(5000, 0xCCCCCCCC);
            mf.writeInt(5004, 0xDDDDDDDD);
            mf.flush();
        }

        // Phase 2: Reopen and verify all data
        try (MappedFile mf = MappedFile.open(testFile, 16384)) {
            assertEquals(0xAAAAAAAA, mf.readInt(0), "Data at offset 0 should persist");
            assertEquals(0xBBBBBBBB, mf.readInt(4), "Data at offset 4 should persist");
            assertEquals(0xCCCCCCCC, mf.readInt(5000), "Data at offset 5000 should persist");
            assertEquals(0xDDDDDDDD, mf.readInt(5004), "Data at offset 5004 should persist");
        }
    }

    /**
     * Test: Large extension works correctly.
     * Verifies that extending by a large amount (multiple MB) works.
     */
    @Test
    void testLargeExtension() throws IOException {
        // Start with 4KB
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write marker at start
            mf.writeLong(0, 0xDEADBEEFCAFEBABEL);

            // Extend by 1MB
            long extensionSize = 1024 * 1024;
            mf.extend(extensionSize);

            long newSize = mf.size();
            assertTrue(newSize >= 4096 + extensionSize,
                "Size should be at least original + extension");

            // Write at the end of the extended region
            long endOffset = newSize - 8;
            mf.writeLong(endOffset, 0x123456789ABCDEF0L);

            // Verify both writes
            assertEquals(0xDEADBEEFCAFEBABEL, mf.readLong(0),
                "Original data should persist");
            assertEquals(0x123456789ABCDEF0L, mf.readLong(endOffset),
                "Data at extended region should be readable");
        }
    }

    /**
     * Test: Extending read-only file fails.
     * Verifies that extend() throws on read-only MappedFile.
     */
    @Test
    void testExtendReadOnlyFails() throws IOException {
        // Create and write some data
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeInt(0, 0x12345678);
        }

        // Open read-only
        try (MappedFile readOnly = MappedFile.open(testFile)) {
            assertTrue(readOnly.isReadOnly());
            assertThrows(IllegalStateException.class, () ->
                readOnly.extend(4096));
        }
    }

    /**
     * Test: Extend with negative size fails.
     */
    @Test
    void testExtendWithNegativeSizeFails() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertThrows(IllegalArgumentException.class, () ->
                mf.extend(-1));
        }
    }

    /**
     * Test: Extend with zero size is a no-op (sensible behavior).
     * The implementation returns early when extensionSize is 0.
     */
    @Test
    void testExtendWithZeroSizeIsNoOp() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            long originalSize = mf.size();
            assertEquals(4096, originalSize);
            mf.writeInt(0, 0x12345678);

            // Extend by 0 - should be a no-op
            mf.extend(0);
            // Size should remain unchanged
            assertEquals(originalSize, mf.size());

            // Data should still be there
            assertEquals(0x12345678, mf.readInt(0));
        }
    }

    /**
     * Test: Small extension is page-aligned.
     * Verifies that extending by less than a page rounds up appropriately.
     */
    @Test
    void testSmallExtensionIsPageAligned() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            long originalSize = mf.size();
            assertEquals(4096, originalSize);

            // Extend by just 1 byte - should round up to page size
            mf.extend(1);

            // Size should be original + page-aligned(1) = 4096 + 4096 = 8192
            assertEquals(8192, mf.size());
        }
    }

    /**
     * Test: Concurrent reads during extend don't corrupt data.
     * This tests thread safety during file growth.
     *
     * <p>The reader thread must never assert directly: an AssertionFailedError raised off
     * the JUnit thread only kills that thread and the test would pass regardless. Failures
     * are recorded and re-raised from the test thread after the join.</p>
     */
    @Test
    void testConcurrentReadDuringExtend() throws Exception {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write initial data
            byte[] originalData = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            mf.writeBytes(0, originalData);

            java.util.concurrent.atomic.AtomicReference<String> readerFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

            // Start a thread that continuously reads the data
            Thread readerThread = new Thread(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        byte[] read = mf.readBytes(0, 8);
                        // Either we read the original data, or we get an exception
                        // during remap - both are valid
                        if (!java.util.Arrays.equals(originalData, read)) {
                            readerFailure.compareAndSet(null,
                                "Concurrent read returned corrupted data: "
                                    + java.util.Arrays.toString(read));
                            return;
                        }
                    }
                } catch (Throwable t) {
                    // Expected during remap - IllegalStateException is acceptable
                    if (!(t instanceof IllegalStateException)) {
                        readerFailure.compareAndSet(null, "Unexpected exception: " + t);
                    }
                }
            });

            readerThread.start();

            // Perform multiple extends while reader is running
            for (int i = 0; i < 10; i++) {
                mf.extend(4096);
                Thread.sleep(1); // Small delay to allow interleaving
            }

            // Join before the try-with-resources closes the file, so the reader can never
            // race against close() and have that failure silently discarded.
            readerThread.join(30_000);
            assertFalse(readerThread.isAlive(), "Reader thread did not finish in time");
            assertNull(readerFailure.get(), readerFailure.get());

            // Final verification - original data should still be intact
            assertArrayEquals(originalData, mf.readBytes(0, 8),
                "Original data should be intact after concurrent operations");
        }
    }

    /**
     * Test: Extend preserves file content across all original offsets.
     * Tests that extending doesn't corrupt any part of the original mapping.
     */
    @Test
    void testExtendPreservesAllOriginalContent() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write a pattern across the entire original region
            for (int offset = 0; offset < 4096; offset += 8) {
                mf.writeLong(offset, 0xA0000000L + offset);
            }

            // Extend
            mf.extend(8192);

            // Verify ALL original data
            for (int offset = 0; offset < 4096; offset += 8) {
                assertEquals(0xA0000000L + offset, mf.readLong(offset),
                    "Data at offset " + offset + " should be preserved");
            }
        }
    }

    // ==================== Flush Tests ====================

    @Test
    void testFlush() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);
            mf.flush();
            // No exception means success
        }
    }

    @Test
    void testForce() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);
            mf.force();
            // No exception means success
        }
    }

    // ==================== Close Tests ====================

    @Test
    void testClose() throws IOException {
        MappedFile mf = MappedFile.create(testFile, 4096);
        assertFalse(mf.isClosed());

        mf.close();
        assertTrue(mf.isClosed());
    }

    @Test
    void testDoubleClose() throws IOException {
        MappedFile mf = MappedFile.create(testFile, 4096);
        mf.close();
        mf.close(); // Should not throw
        assertTrue(mf.isClosed());
    }

    @Test
    void testReadAfterClose() throws IOException {
        MappedFile mf = MappedFile.create(testFile, 4096);
        mf.close();

        assertThrows(IllegalStateException.class, () ->
            mf.readBytes(0, 10));
    }

    @Test
    void testWriteAfterClose() throws IOException {
        MappedFile mf = MappedFile.create(testFile, 4096);
        mf.close();

        assertThrows(IllegalStateException.class, () ->
            mf.writeBytes(0, new byte[10]));
    }

    @Test
    void testTryWithResources() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4});
        }
        // Should be closed automatically
    }

    // ==================== Bounds Checking Tests ====================

    @Test
    void testReadBeyondSize() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertThrows(IndexOutOfBoundsException.class, () ->
                mf.readBytes(4096, 10));
        }
    }

    @Test
    void testWriteBeyondSize() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertThrows(IndexOutOfBoundsException.class, () ->
                mf.writeBytes(4096, new byte[10]));
        }
    }

    @Test
    void testNegativeOffset() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertThrows(IndexOutOfBoundsException.class, () ->
                mf.readBytes(-1, 10));
        }
    }

    @Test
    void testNegativeLength() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Negative length causes NegativeArraySizeException before bounds checking
            assertThrows(NegativeArraySizeException.class, () ->
                mf.readBytes(0, -1));
        }
    }

    // ==================== Statistics Tests ====================

    @Test
    void testBytesRead() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);

            mf.readBytes(0, 50);
            mf.readBytes(0, 50);

            assertEquals(100, mf.bytesRead());
        }
    }

    @Test
    void testBytesWritten() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);

            assertEquals(100, mf.bytesWritten());
        }
    }

    @Test
    void testReadCount() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);

            mf.readBytes(0, 50);
            mf.readBytes(0, 50);
            mf.readBytes(0, 50);

            assertEquals(3, mf.readCount());
        }
    }

    @Test
    void testWriteCount() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[100]);
            mf.writeBytes(100, new byte[100]);

            assertEquals(2, mf.writeCount());
        }
    }

    // ==================== File Operations Tests ====================

    @Test
    void testFileExists() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertTrue(mf.fileExists());
        }
    }

    @Test
    void testFileLength() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertEquals(4096, mf.fileLength());
        }
    }

    @Test
    void testPath() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertEquals(testFile, mf.path());
        }
    }

    @Test
    void testPageSize() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            assertEquals(4096, mf.pageSize());
        }
    }

    // ==================== Memory Segment Access Tests ====================

    @Test
    void testSegmentAccess() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            var segment = mf.segment();
            assertNotNull(segment);
            assertEquals(4096, segment.byteSize());
        }
    }

    @Test
    void testArenaAccess() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            var arena = mf.arena();
            assertNotNull(arena);
        }
    }

    // ==================== Data Persistence Tests ====================

    @Test
    void testDataPersistsAfterReopen() throws IOException {
        // Write data
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, new byte[]{1, 2, 3, 4, 5});
            mf.writeInt(10, 0x12345678);
            mf.writeLong(20, 0xABCDEF0123456789L);
            mf.flush();
        }

        // Reopen and verify
        try (MappedFile mf = MappedFile.open(testFile, 4096)) {
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, mf.readBytes(0, 5));
            assertEquals(0x12345678, mf.readInt(10));
            assertEquals(0xABCDEF0123456789L, mf.readLong(20));
        }
    }

    // ==================== toString Test ====================

    @Test
    void testToString() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            String str = mf.toString();
            assertTrue(str.contains("MappedFile"));
            assertTrue(str.contains("size=4096"));
            assertTrue(str.contains("closed=false"));
        }
    }

    // ==================== Known Test Data Verification Tests ====================

    /**
     * Test: Reads must return correct bytes with known test data.
     * This is a comprehensive verification that the MappedFile read operations
     * return the exact bytes that were written, using deterministic test patterns.
     *
     * Domain correctness verification:
     * - Each byte value is verified individually
     * - Patterns are designed to catch common errors (off-by-one, endianness, etc.)
     * - Tests multiple sizes to catch boundary issues
     */
    @Test
    void testReadsReturnCorrectBytesWithKnownTestData() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 8192)) {
            // Test Pattern 1: Sequential bytes (0x00 to 0xFF)
            byte[] sequentialPattern = new byte[256];
            for (int i = 0; i < 256; i++) {
                sequentialPattern[i] = (byte) i;
            }

            // Write pattern at offset 0
            mf.writeBytes(0, sequentialPattern);

            // Read back and verify each byte
            byte[] readSequential = mf.readBytes(0, 256);
            for (int i = 0; i < 256; i++) {
                assertEquals((byte) i, readSequential[i],
                    "Sequential pattern byte at index " + i + " should be " + i);
            }

            // Test Pattern 2: Alternating 0x55 and 0xAA (catches bit-shift errors)
            byte[] alternatingPattern = new byte[128];
            for (int i = 0; i < 128; i++) {
                alternatingPattern[i] = (byte) (i % 2 == 0 ? 0x55 : 0xAA);
            }

            mf.writeBytes(256, alternatingPattern);
            byte[] readAlternating = mf.readBytes(256, 128);
            for (int i = 0; i < 128; i++) {
                byte expected = (byte) (i % 2 == 0 ? 0x55 : 0xAA);
                assertEquals(expected, readAlternating[i],
                    "Alternating pattern byte at index " + i);
            }

            // Test Pattern 3: All zeros (catches uninitialized reads)
            byte[] zeroPattern = new byte[64];
            mf.writeBytes(512, zeroPattern);
            byte[] readZeros = mf.readBytes(512, 64);
            for (int i = 0; i < 64; i++) {
                assertEquals((byte) 0, readZeros[i],
                    "Zero pattern byte at index " + i + " should be 0");
            }

            // Test Pattern 4: All ones (0xFF) (catches sign extension issues)
            byte[] onesPattern = new byte[64];
            java.util.Arrays.fill(onesPattern, (byte) 0xFF);
            mf.writeBytes(576, onesPattern);
            byte[] readOnes = mf.readBytes(576, 64);
            for (int i = 0; i < 64; i++) {
                assertEquals((byte) 0xFF, readOnes[i],
                    "Ones pattern byte at index " + i + " should be 0xFF");
            }

            // Test Pattern 5: Known magic numbers at specific offsets
            // This tests that offsets are handled correctly
            mf.writeInt(1024, 0xDEADBEEF);
            mf.writeInt(1028, 0xCAFEBABE);
            mf.writeLong(1032, 0x0123456789ABCDEFL);
            mf.writeLong(1040, 0xFEDCBA9876543210L);

            assertEquals(0xDEADBEEF, mf.readInt(1024),
                "Magic number 1 at offset 1024");
            assertEquals(0xCAFEBABE, mf.readInt(1028),
                "Magic number 2 at offset 1028");
            assertEquals(0x0123456789ABCDEFL, mf.readLong(1032),
                "Magic number 3 at offset 1032");
            assertEquals(0xFEDCBA9876543210L, mf.readLong(1040),
                "Magic number 4 at offset 1040");
        }
    }

    /**
     * Test: Reads with partial buffer offsets return correct bytes.
     * Verifies that read(offset, buffer, bufferOffset, length) works correctly.
     */
    @Test
    void testReadsWithPartialBufferOffsets() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Known test data: ASCII "Hello, World!"
            byte[] knownData = "Hello, World!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            mf.writeBytes(0, knownData);

            // Read into buffer at offset 5
            byte[] buffer = new byte[20];
            java.util.Arrays.fill(buffer, (byte) 0xFF); // Fill with 0xFF first

            int bytesRead = mf.read(0, buffer, 5, knownData.length);
            assertEquals(knownData.length, bytesRead);

            // First 5 bytes should still be 0xFF
            for (int i = 0; i < 5; i++) {
                assertEquals((byte) 0xFF, buffer[i],
                    "Buffer prefix at index " + i + " should be unchanged");
            }

            // Bytes 5 to 5+knownData.length should match known data
            for (int i = 0; i < knownData.length; i++) {
                assertEquals(knownData[i], buffer[5 + i],
                    "Buffer data at index " + (5 + i) + " should match known data");
            }

            // Remaining bytes should still be 0xFF
            for (int i = 5 + knownData.length; i < 20; i++) {
                assertEquals((byte) 0xFF, buffer[i],
                    "Buffer suffix at index " + i + " should be unchanged");
            }
        }
    }

    /**
     * Test: Reads return correct bytes after file growth (extend).
     * Verifies that data written before extend is preserved and readable.
     */
    @Test
    void testReadsReturnCorrectBytesAfterExtend() throws IOException {
        // Known test data pattern
        byte[] pattern1 = new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        byte[] pattern2 = new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD};

        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write first pattern
            mf.writeBytes(0, pattern1);

            // Verify before extend
            assertArrayEquals(pattern1, mf.readBytes(0, 8),
                "Pattern 1 should be readable before extend");

            // Extend the file
            mf.extend(4096);
            assertEquals(8192, mf.size());

            // Verify pattern 1 is still there after extend
            assertArrayEquals(pattern1, mf.readBytes(0, 8),
                "Pattern 1 should be preserved after extend");

            // Write second pattern in extended region
            mf.writeBytes(4096, pattern2);

            // Verify both patterns
            assertArrayEquals(pattern1, mf.readBytes(0, 8),
                "Pattern 1 should still be readable");
            assertArrayEquals(pattern2, mf.readBytes(4096, 4),
                "Pattern 2 should be readable in extended region");
        }
    }

    /**
     * Test: Reads return correct bytes after close and reopen.
     * Verifies persistence of data to disk.
     */
    @Test
    void testReadsReturnCorrectBytesAfterReopen() throws IOException {
        // Known test data - use a pattern that would catch endianness errors
        byte[] knownPattern = new byte[32];
        for (int i = 0; i < 32; i++) {
            knownPattern[i] = (byte) (i * 17); // 0, 17, 34, 51, 68, ...
        }

        // Write and close
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            mf.writeBytes(0, knownPattern);
            mf.writeInt(100, 0x12345678);
            mf.writeLong(104, 0xABCDEF0123456789L);
            mf.flush();
        }

        // Reopen and verify
        try (MappedFile mf = MappedFile.open(testFile, 4096)) {
            byte[] readPattern = mf.readBytes(0, 32);
            for (int i = 0; i < 32; i++) {
                byte expected = (byte) (i * 17);
                assertEquals(expected, readPattern[i],
                    "Byte at index " + i + " should be " + (expected & 0xFF) + " after reopen");
            }

            assertEquals(0x12345678, mf.readInt(100),
                "Int at offset 100 should persist after reopen");
            assertEquals(0xABCDEF0123456789L, mf.readLong(104),
                "Long at offset 104 should persist after reopen");
        }
    }

    /**
     * Test: Slice reads return correct bytes.
     * Verifies that MemorySegment slices provide correct data.
     */
    @Test
    void testSliceReadsReturnCorrectBytes() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Write known pattern
            byte[] knownData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            mf.writeBytes(0, knownData);

            // Get slice at offset 2, length 5
            var slice = mf.slice(2, 5);
            assertEquals(5, slice.byteSize());

            // Verify slice contents: should be bytes 3,4,5,6,7 (indices 2-6 of original)
            assertEquals((byte) 3, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 4, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 1));
            assertEquals((byte) 5, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 2));
            assertEquals((byte) 6, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3));
            assertEquals((byte) 7, slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 4));
        }
    }

    /**
     * Test: Float and double reads return correct values with known test data.
     * Verifies IEEE 754 floating point representation is handled correctly.
     */
    @Test
    void testFloatDoubleReadsReturnCorrectValues() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Known float values with exact IEEE 754 representations
            float[] knownFloats = {
                0.0f,
                1.0f,
                -1.0f,
                Float.MAX_VALUE,
                Float.MIN_VALUE,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NaN
            };

            // Write all floats
            long offset = 0;
            for (float f : knownFloats) {
                mf.writeFloat(offset, f);
                offset += 4;
            }

            // Read back and verify
            offset = 0;
            for (float expected : knownFloats) {
                float actual = mf.readFloat(offset);
                if (Float.isNaN(expected)) {
                    assertTrue(Float.isNaN(actual),
                        "Float at offset " + offset + " should be NaN");
                } else {
                    assertEquals(expected, actual,
                        "Float at offset " + offset + " should be " + expected);
                }
                offset += 4;
            }

            // Known double values
            double[] knownDoubles = {
                0.0,
                1.0,
                -1.0,
                Double.MAX_VALUE,
                Double.MIN_VALUE,
                Math.PI,
                Math.E
            };

            // Write all doubles
            offset = 1000;
            for (double d : knownDoubles) {
                mf.writeDouble(offset, d);
                offset += 8;
            }

            // Read back and verify
            offset = 1000;
            for (double expected : knownDoubles) {
                double actual = mf.readDouble(offset);
                assertEquals(expected, actual,
                    "Double at offset " + offset + " should be " + expected);
                offset += 8;
            }
        }
    }

    /**
     * Test: UTF-8 string reads return correct characters with known test data.
     * Verifies UTF-8 encoding/decoding works correctly.
     */
    @Test
    void testUtf8StringReadsReturnCorrectCharacters() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 4096)) {
            // Known strings with various UTF-8 characteristics
            String[] knownStrings = {
                "ASCII only",           // 1-byte UTF-8
                "café",                 // 2-byte UTF-8 (é)
                "日本語",               // 3-byte UTF-8 (Japanese)
                "🎉",                   // 4-byte UTF-8 (emoji)
                "Mixed: hello 世界! 🌍" // Mixed
            };

            long offset = 0;
            for (String original : knownStrings) {
                int bytesWritten = mf.writeString(offset, original);
                String read = mf.readString(offset);
                assertEquals(original, read,
                    "String at offset " + offset + " should match original");
                offset += bytesWritten;
            }
        }
    }

    /**
     * Test: Large block reads return correct bytes.
     * Verifies that large reads (multi-page) work correctly.
     */
    @Test
    void testLargeBlockReadsReturnCorrectBytes() throws IOException {
        // Create a file larger than one page
        int largeSize = 16 * 1024; // 16 KB
        byte[] largePattern = new byte[largeSize];

        // Fill with deterministic pattern
        java.util.Random rng = new java.util.Random(12345); // Fixed seed for reproducibility
        rng.nextBytes(largePattern);

        try (MappedFile mf = MappedFile.create(testFile, largeSize)) {
            // Write entire pattern
            mf.writeBytes(0, largePattern);

            // Read back entire pattern
            byte[] readPattern = mf.readBytes(0, largeSize);

            // Verify every byte
            for (int i = 0; i < largeSize; i++) {
                assertEquals(largePattern[i], readPattern[i],
                    "Large pattern byte at index " + i);
            }

            // Read in chunks and verify
            int chunkSize = 1024;
            byte[] chunk = new byte[chunkSize];
            for (int chunkStart = 0; chunkStart < largeSize; chunkStart += chunkSize) {
                int toRead = Math.min(chunkSize, largeSize - chunkStart);
                mf.read(chunkStart, chunk, 0, toRead);

                for (int i = 0; i < toRead; i++) {
                    assertEquals(largePattern[chunkStart + i], chunk[i],
                        "Chunk byte at index " + (chunkStart + i));
                }
            }
        }
    }
    // ==================== Regression Tests ====================

    /**
     * Test: extend() never shrinks the backing file.
     *
     * <p>A file reopened with a smaller nominal size than it has on disk used to be
     * truncated to the new mapping size by the very next extend(), destroying every byte
     * beyond it. Growing must only ever grow.</p>
     */
    @Test
    void testExtendNeverTruncatesLargerFile() throws IOException {
        byte[] marker = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        long markerOffset = 120_000L;

        // Phase 1: grow well past the initial size and persist a marker near the end.
        try (MappedFile mf = MappedFile.create(testFile, 65536)) {
            mf.extend(65536);
            mf.writeBytes(markerOffset, marker);
            mf.flush();
        }

        long grownLength = Files.size(testFile);
        assertTrue(grownLength > markerOffset + marker.length,
            "Setup failed: file should have grown past the marker");

        // Phase 2: reopen with the smaller nominal size and grow by one page.
        try (MappedFile mf = MappedFile.create(testFile, 65536)) {
            mf.extend(4096);
            assertTrue(Files.size(testFile) >= grownLength,
                "extend() must not shrink the backing file");
        }

        // Phase 3: the marker must survive.
        try (MappedFile mf = MappedFile.create(testFile, grownLength)) {
            assertArrayEquals(marker, mf.readBytes(markerOffset, marker.length),
                "Data beyond the reopened mapping must survive an extend()");
        }
    }

    /**
     * Test: truncate() is still allowed to shrink the backing file.
     */
    @Test
    void testTruncateStillShrinksFile() throws IOException {
        try (MappedFile mf = MappedFile.create(testFile, 16384)) {
            assertEquals(16384, Files.size(testFile));
            mf.truncate(4096);
            assertEquals(4096, mf.size());
            assertEquals(4096, Files.size(testFile));
        }
    }

    /**
     * Test: open(Path) works for a file whose length is not a multiple of the page size.
     */
    @Test
    void testOpenReadOnlyWithNonPageAlignedLength() throws IOException {
        byte[] content = new byte[100];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(testFile, content);

        try (MappedFile mf = MappedFile.open(testFile)) {
            assertTrue(mf.isReadOnly());
            assertEquals(100, mf.size());
            assertArrayEquals(content, mf.readBytes(0, content.length));
        }
    }

    /**
     * Test: open(Path) works for an empty file.
     */
    @Test
    void testOpenReadOnlyEmptyFile() throws IOException {
        Files.write(testFile, new byte[0]);

        try (MappedFile mf = MappedFile.open(testFile)) {
            assertEquals(0, mf.size());
            assertThrows(IndexOutOfBoundsException.class, () -> mf.readByte(0));
        }
    }

    /**
     * Test: a successful close reports no flush failure.
     */
    @Test
    void testCloseReportsNoFlushFailure() throws IOException {
        MappedFile mf = MappedFile.create(testFile, 4096);
        mf.writeInt(0, 42);
        mf.close();

        assertNull(mf.closeFlushFailure(), "A successful close must not record a flush failure");
    }
}
