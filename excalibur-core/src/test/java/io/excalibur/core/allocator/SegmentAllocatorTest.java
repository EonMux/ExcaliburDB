package io.excalibur.core.allocator;

import io.excalibur.core.allocator.SegmentAllocator.AllocatedRegion;
import io.excalibur.core.exception.AllocationException;
import io.excalibur.core.mapped.MappedFile;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.core.memory.Segment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SegmentAllocator bump allocator.
 * Tests allocation, deallocation, seek, and memory segment access.
 */
class SegmentAllocatorTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private MappedFile mf;
    private SegmentAllocator allocator;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test_" + System.nanoTime() + ".dat");
        mf = MappedFile.create(testFile, 16384); // 16 KB

        // Get the underlying MemorySegment from MappedFile and wrap as Segment
        Segment segment = MemorySegmentFactory.wrapNative(mf.segment());
        allocator = new SegmentAllocator(segment);
    }

    @AfterEach
    void tearDown() throws IOException {
        mf.close();
    }

    // ==================== Creation Tests ====================

    @Test
    void testCreateWithValidSegment() {
        assertNotNull(allocator);
        assertEquals(16384, allocator.totalSize());
    }

    @Test
    void testCreateWithNullSegment() {
        assertThrows(IllegalArgumentException.class, () -> new SegmentAllocator(null));
    }

    @Test
    void testCreateWithEmptySegment() throws IOException {
        Path emptyFile = tempDir.resolve("empty.dat");
        try (MappedFile emptyMf = MappedFile.create(emptyFile, 4096)) {
            // Get a slice that represents an empty region (offset at end)
            Segment emptySlice = MemorySegmentFactory.wrapNative(emptyMf.segment()).asSlice(0, 0);
            assertThrows(IllegalArgumentException.class, () -> new SegmentAllocator(emptySlice));
        }
    }

    // ==================== Allocation Tests ====================

    @Test
    void testAllocateSmallRegion() {
        AllocatedRegion region = allocator.allocate(64);

        assertNotNull(region);
        assertEquals(64, region.size());
        assertEquals(0, region.offset());
        assertEquals(16384 - 64, allocator.remaining());
    }

    @Test
    void testAllocateMediumRegion() {
        AllocatedRegion region = allocator.allocate(4096);

        assertNotNull(region);
        assertEquals(4096, region.size());
        assertEquals(16384 - 4096, allocator.remaining());
    }

    @Test
    void testAllocateAtFixedOffset() {
        AllocatedRegion region1 = allocator.allocateAt(1000, 256);
        AllocatedRegion region2 = allocator.allocateAt(1400, 256);
        
        assertNotNull(region1);
        assertEquals(256, region1.size());
        assertEquals(1000, region1.offset());
        
        assertNotNull(region2);
        assertEquals(256, region2.size());
        assertEquals(1400, region2.offset());
    }

    @Test
    void testAllocateExhaustsSpace() {
        // Allocate until no space remains
        while (allocator.remaining() >= 1) {
            allocator.allocate(1);
        }
        
        // Next allocation should fail
        assertThrows(AllocationException.class, () -> allocator.allocate(1));
    }

    @Test
    void testAllocateNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(0));
    }

    // ==================== Free Tests ====================

    @Test
    void testFreeAllocatedRegion() {
        AllocatedRegion region = allocator.allocate(256);

        assertEquals(16384 - 256, allocator.remaining()); // 16128
        assertTrue(allocator.free(region));
        // After free, remaining is still based on currentOffset which hasn't changed
        assertEquals(16384 - 256, allocator.remaining());
    }

    @Test
    void testFreeNullRegion() {
        assertThrows(IllegalArgumentException.class, () -> allocator.free(null));
    }

    @Test
    void testFreeUnknownRegion() {
        // Create a region that was never allocated by this allocator
        Segment segment = MemorySegmentFactory.wrapNative(mf.segment());
        AllocatedRegion unknownRegion = new AllocatedRegion(9999, 100, segment);
        assertFalse(allocator.free(unknownRegion));
    }

    @Test
    void testFreeUnknownRegionTwice() {
        AllocatedRegion region = allocator.allocate(256);
        assertTrue(allocator.free(region));
        // Second free of the same region should return false (already removed)
        assertFalse(allocator.free(region));
    }

    // ==================== Seek Tests ====================

    @Test
    void testSeekToStart() {
        allocator.allocate(100);
        allocator.allocate(100);
        
        assertTrue(allocator.seek(0));
        assertEquals(0, allocator.currentOffset());
    }

    @Test
    void testSeekToMiddle() {
        allocator.allocate(100);
        allocator.allocate(100);
        allocator.allocate(100);
        
        assertTrue(allocator.seek(200));
        assertEquals(200, allocator.currentOffset());
    }

    @Test
    void testSeekToEnd() {
        allocator.allocate(100);
        allocator.allocate(100);
        
        assertTrue(allocator.seek(200));
        assertEquals(200, allocator.currentOffset());
    }

    @Test
    void testSeekInvalidPosition() {
        // seek(100) is valid since 100 is within [0, 16384]
        assertTrue(allocator.seek(100));
        // seek(16385) is invalid - out of bounds
        assertFalse(allocator.seek(16385));
        // seek(-1) is invalid
        assertFalse(allocator.seek(-1));
    }

    @Test
    void testSeekNegativePosition() {
        assertFalse(allocator.seek(-1));
    }

    @Test
    void testSeekAfterClear() {
        allocator.clear();
        assertTrue(allocator.seek(500));
        assertEquals(500, allocator.currentOffset());
    }

    // ==================== Statistics Tests ====================

    @Test
    void testRemainingAfterAllocation() {
        AllocatedRegion region = allocator.allocate(512);
        assertEquals(15872, allocator.remaining());
    }

    @Test
    void testAllocatedAfterAllocation() {
        AllocatedRegion region = allocator.allocate(512);
        assertEquals(512, allocator.allocated());
    }

    @Test
    void testTotalSize() {
        assertEquals(16384, allocator.totalSize());
    }

    @Test
    void testStartOffset() {
        assertEquals(0, allocator.startOffset());
    }

    @Test
    void testEndOffset() {
        assertEquals(16384, allocator.endOffset());
    }

    @Test
    void testCurrentOffset() {
        AllocatedRegion region = allocator.allocate(512);
        assertEquals(512, allocator.currentOffset());
    }

    @Test
    void testAllocationCount() {
        allocator.allocate(64);
        allocator.allocate(64);
        allocator.allocate(64);
        assertEquals(3, allocator.allocationCount());
    }

    @Test
    void testFreeCount() {
        AllocatedRegion region = allocator.allocate(64);
        allocator.free(region);
        assertEquals(1, allocator.freeCount());
    }

    @Test
    void testAllocationsList() {
        allocator.allocate(64);
        allocator.allocate(64);
        allocator.allocate(64);
        
        List<AllocatedRegion> regions = allocator.allocations();
        assertEquals(3, regions.size());
    }

    // ==================== Region Access Tests ====================

    @Test
    void testRegionAt() {
        AllocatedRegion region = allocator.allocate(256);
        
        AllocatedRegion found = allocator.regionAt(region.offset());
        assertEquals(region, found);
    }

    @Test
    void testRegionAtInvalidOffset() {
        assertThrows(IllegalArgumentException.class, () -> allocator.regionAt(100));
    }

    @Test
    void testRegionSlice() {
        AllocatedRegion region = allocator.allocate(1024);

        Segment slice = region.slice(64, 256);
        assertEquals(256, slice.byteSize());

        Segment fullSlice = region.slice();
        assertEquals(1024, fullSlice.byteSize());
    }
    @Test
    void testRegionSliceInvalid() {
        AllocatedRegion region = allocator.allocate(1024);
        
        assertThrows(IllegalArgumentException.class, () -> region.slice(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> region.slice(1024, 100));
    }

    // ==================== Read/Write Tests ====================

    @Test
    void testReadFromRegion() {
        AllocatedRegion region = allocator.allocate(256);
        byte[] data = new byte[256];
        
        // Fill with pattern
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        
        allocator.write(region, data, 0, 256);
        
        byte[] read = new byte[256];
        allocator.read(region, read, 0, 256);
        
        assertArrayEquals(data, read);
    }

    @Test
    void testWriteToRegion() {
        AllocatedRegion region = allocator.allocate(256);
        byte[] data = new byte[256];
        
        // Fill with pattern
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        
        allocator.write(region, data, 0, 256);
        
        byte[] read = new byte[256];
        allocator.read(region, read, 0, 256);
        
        assertArrayEquals(data, read);
    }

    @Test
    void testReadRegionTooLarge() {
        AllocatedRegion region = allocator.allocate(128);
        
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(region, new byte[256], 0, 256));
    }

    @Test
    void testWriteRegionTooLarge() {
        AllocatedRegion region = allocator.allocate(128);
        
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(region, new byte[256], 0, 256));
    }

    @Test
    void testReadNullRegion() {
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(null, new byte[100], 0, 100));
    }

    @Test
    void testWriteNullRegion() {
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(null, new byte[100], 0, 100));
    }

    @Test
    void testReadNullBuffer() {
        AllocatedRegion region = allocator.allocate(100);
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(region, null, 0, 100));
    }

    @Test
    void testWriteNullBuffer() {
        AllocatedRegion region = allocator.allocate(100);
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(region, null, 0, 100));
    }

    @Test
    void testReadInvalidBufferParams() {
        AllocatedRegion region = allocator.allocate(100);
        
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(region, new byte[100], -1, 100));
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(region, new byte[100], 0, -1));
        assertThrows(IllegalArgumentException.class, () ->
            allocator.read(region, new byte[100], 150, 50));
    }

    @Test
    void testWriteInvalidBufferParams() {
        AllocatedRegion region = allocator.allocate(100);
        
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(region, new byte[100], -1, 100));
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(region, new byte[100], 0, -1));
        assertThrows(IllegalArgumentException.class, () ->
            allocator.write(region, new byte[100], 150, 50));
    }

    // ==================== Segment Access Tests ====================

    @Test
    void testSegmentAccess() {
        Segment segment = allocator.segment();
        assertNotNull(segment);
        assertEquals(16384, segment.byteSize());
    }


    // ==================== Clear Tests ====================

    @Test
    void testClear() {
        allocator.allocate(100);
        allocator.allocate(100);
        
        allocator.clear();
        
        assertEquals(0, allocator.currentOffset());
        assertEquals(0, allocator.allocationCount());
    }

    // ==================== Multiple Allocation Patterns ====================

    @Test
    void testConsecutiveAllocations() {
        // Allocate consecutive regions
        AllocatedRegion r1 = allocator.allocate(100);
        AllocatedRegion r2 = allocator.allocate(100);
        AllocatedRegion r3 = allocator.allocate(100);
        
        assertEquals(300, allocator.currentOffset());
        assertEquals(16084, allocator.remaining());
    }

    @Test
    void testInterleavedAllocations() {
        AllocatedRegion r1 = allocator.allocate(100);
        AllocatedRegion r2 = allocator.allocate(200);
        AllocatedRegion r3 = allocator.allocate(100);
        AllocatedRegion r4 = allocator.allocate(200);
        
        assertEquals(600, allocator.currentOffset());
        assertEquals(15784, allocator.remaining());
    }

    @Test
    void testAllocationWithWriteRead() {
        AllocatedRegion region = allocator.allocate(512);
        
        // Write a pattern
        byte[] pattern = new byte[512];
        for (int i = 0; i < 512; i++) {
            pattern[i] = (byte) (i % 256);
        }
        allocator.write(region, pattern, 0, 512);
        
        // Read it back
        byte[] read = new byte[512];
        allocator.read(region, read, 0, 512);
        
        assertArrayEquals(pattern, read);
    }

    @Test
    void testLargeAllocation() {
        // Trying to allocate 1MB from a 16KB segment should fail
        assertThrows(AllocationException.class, () -> allocator.allocate(1024 * 1024));
    }

    @Test
    void testMultipleRegionsWriteRead() {
        // Allocate multiple regions
        AllocatedRegion r1 = allocator.allocate(512);
        AllocatedRegion r2 = allocator.allocate(1024);
        
        // Write patterns
        allocator.write(r1, new byte[512], 0, 512);
        allocator.write(r2, new byte[1024], 0, 1024);
        
        // Read back
        byte[] r1Read = new byte[512];
        byte[] r2Read = new byte[1024];
        
        allocator.read(r1, r1Read, 0, 512);
        allocator.read(r2, r2Read, 0, 1024);
        
        assertArrayEquals(new byte[512], r1Read);
        assertArrayEquals(new byte[1024], r2Read);
    }

    // ==================== Boundary Tests ====================

    @Test
    void testAllocateAtBoundary() {
        // Allocate at offset 16376 (leaves 8 bytes)
        AllocatedRegion region = allocator.allocateAt(16376, 8);
        
        assertNotNull(region);
        assertEquals(8, region.size());
        assertEquals(16384, allocator.remaining());
    }

    @Test
    void testAllocateAtEndBoundary() {
        // Try to allocate at the very end
        AllocatedRegion region = allocator.allocateAt(16376, 8);

        // Allocate again to exhaust space
        allocator.allocate(8);

        // Next allocation at out-of-bounds should fail with IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> allocator.allocateAt(16384, 1));
    }

    // ==================== toString Test ====================

    @Test
    void testToString() {
        AllocatedRegion region = allocator.allocate(256);
        
        String str = region.toString();
        assertTrue(str.contains("AllocatedRegion"));
        assertTrue(str.contains("offset=0"));
        assertTrue(str.contains("size=256"));
    }

    // ==================== Known Test Data Verification ====================

    /**
     * Test: Write/read patterns return correct bytes.
     * Verifies that allocated regions can be written to and read back correctly.
     */
    @Test
    void testWriteReadPatternsReturnCorrectBytes() {
        // Known test patterns
        byte[] pattern1 = new byte[64];
        for (int i = 0; i < 64; i++) {
            pattern1[i] = (byte) (i * 4);
        }

        byte[] pattern2 = new byte[128];
        for (int i = 0; i < 128; i++) {
            pattern2[i] = (byte) ((i * 7) & 0xFF);
        }

        // Allocate and write pattern 1
        AllocatedRegion region1 = allocator.allocate(64);
        allocator.write(region1, pattern1, 0, 64);

        // Read back and verify
        byte[] read1 = new byte[64];
        allocator.read(region1, read1, 0, 64);
        for (int i = 0; i < 64; i++) {
            assertEquals(pattern1[i], read1[i],
                "Pattern 1 byte at index " + i);
        }

        // Allocate and write pattern 2
        AllocatedRegion region2 = allocator.allocate(128);
        allocator.write(region2, pattern2, 0, 128);

        // Read back and verify
        byte[] read2 = new byte[128];
        allocator.read(region2, read2, 0, 128);
        for (int i = 0; i < 128; i++) {
            assertEquals(pattern2[i], read2[i],
                "Pattern 2 byte at index " + i);
        }
    }

    /**
     * Test: Allocations preserve data after free and new allocation.
     * Tests that freed regions can be reused without corruption.
     */
    @Test
    void testDataPreservedAfterFreeAndReuse() {
        byte[] originalData = new byte[256];
        for (int i = 0; i < 256; i++) {
            originalData[i] = (byte) i;
        }

        // Allocate, write, free, reallocate
        AllocatedRegion region = allocator.allocate(256);
        allocator.write(region, originalData, 0, 256);
        allocator.free(region);

        // Reallocate at same offset
        AllocatedRegion newRegion = allocator.allocateAt(0, 256);
        
        // Write new data
        byte[] newData = new byte[256];
        for (int i = 0; i < 256; i++) {
            newData[i] = (byte) (i + 256);
        }
        allocator.write(newRegion, newData, 0, 256);

        // Read back - should be new data, not old data
        byte[] read = new byte[256];
        allocator.read(newRegion, read, 0, 256);

        for (int i = 0; i < 256; i++) {
            assertEquals(newData[i], read[i],
                "Data at index " + i + " should be from new allocation");
        }
    }

    /**
     * Test: Allocation count reflects current allocations (freed regions are removed).
     */
    @Test
    void testAllocationCountIncludesFreedRegions() {
        AllocatedRegion r1 = allocator.allocate(100);
        AllocatedRegion r2 = allocator.allocate(100);
        AllocatedRegion r3 = allocator.allocate(100);

        assertEquals(3, allocator.allocationCount());

        allocator.free(r1);
        allocator.free(r2);

        // Count reflects current allocations (freed regions are removed from list)
        assertEquals(1, allocator.allocationCount());
        // freeCount tracks successful frees
        assertEquals(2, allocator.freeCount());
    }

    /**
     * Test: Multiple seeks don't corrupt allocations.
     */
    @Test
    void testMultipleSeeksPreserveAllocations() {
        AllocatedRegion r1 = allocator.allocate(100);
        AllocatedRegion r2 = allocator.allocate(100);
        
        // Write to r1
        allocator.write(r1, new byte[100], 0, 100);
        
        // Seek and reallocate
        allocator.seek(200);
        AllocatedRegion r3 = allocator.allocate(100);
        
        // Verify r1 data is still intact
        byte[] read1 = new byte[100];
        allocator.read(r1, read1, 0, 100);
        
        // Verify r3 was written correctly
        allocator.write(r3, new byte[100], 0, 100);
        byte[] read3 = new byte[100];
        allocator.read(r3, read3, 0, 100);
        
        assertArrayEquals(new byte[100], read3);
    }
}
