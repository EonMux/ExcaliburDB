package io.excalibur.core.allocator;

import io.excalibur.core.exception.AllocationException;
import io.excalibur.core.memory.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bump allocator for file-backed memory regions using the Segment abstraction.
 * Provides efficient allocation and deallocation within a memory segment.
 *
 * <p>This implementation uses Segment slice and copy operations for zero-copy
 * access to allocated regions, enabling high-performance disk-backed storage.</p>
 *
 * <h2>Implementation Details</h2>
 * <p>The allocator accepts the {@link Segment} abstraction interface and uses
 * {@link Segment#nativeSegment()} for Panama FFM-specific operations when
 * available. For fallback segments, ByteBuffer-based operations are used via
 * the Segment interface methods.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Allocation operations are thread-safe via atomic offset updates.
 * Read/write operations use the underlying Segment's thread-safety guarantees.
 * Concurrent reads are allowed, allocations use CAS for thread-safety.</p>
 *
 * @author ExcaliburDB
 */
public class SegmentAllocator {

    private static final Logger logger = LoggerFactory.getLogger(SegmentAllocator.class);

    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    private final Segment segment;
    private final MemorySegment nativeSegment; // May be null for fallback segments
    private final long startOffset;
    private final long endOffset;
    private final long segmentSize;

    // Mutable - tracks current allocation position
    private final AtomicLong currentOffset;
    private final List<AllocatedRegion> allocations = new CopyOnWriteArrayList<>();
    private final AtomicLong allocCount = new AtomicLong(0);
    private final AtomicLong freeCount = new AtomicLong(0);

    /**
     * Creates a new SegmentAllocator wrapping a Segment.
     *
     * @param segment The memory segment to allocate within
     * @throws IllegalArgumentException if segment is null or empty
     */
    public SegmentAllocator(Segment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("Segment must be non-null");
        }
        if (segment.byteSize() == 0) {
            throw new IllegalArgumentException("Segment must have non-zero size");
        }

        this.segment = segment;
        this.nativeSegment = segment.nativeSegment(); // May be null for fallback
        this.startOffset = 0;
        this.endOffset = segment.byteSize();
        this.currentOffset = new AtomicLong(startOffset);
        this.segmentSize = segment.byteSize();
    }

    /**
     * Allocates a region by bumping the current offset using CAS.
     *
     * @param size Number of bytes to allocate
     * @return New allocation region
     * @throws AllocationException if not enough space available
     * @throws IllegalArgumentException if size is not positive
     */
    public AllocatedRegion allocate(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }

        long oldOffset;
        long newOffset;
        do {
            oldOffset = currentOffset.get();
            newOffset = oldOffset + size;
            if (newOffset > endOffset) {
                throw new AllocationException(
                    "Cannot allocate " + size + " bytes at offset " + oldOffset +
                    ", only " + (endOffset - oldOffset) + " bytes remaining");
            }
        } while (!currentOffset.compareAndSet(oldOffset, newOffset));

        AllocatedRegion region = new AllocatedRegion(oldOffset, size, segment);
        allocations.add(region);
        allocCount.incrementAndGet();

        logger.debug("Allocated {} bytes at offset {} (total allocations: {})",
                     size, oldOffset, allocCount.get());
        return region;
    }

    /**
     * Allocates a region of exact size. Same as allocate().
     *
     * @param size Number of bytes to allocate (must match exactly)
     * @return New allocation region
     * @throws AllocationException if not enough space available
     */
    public AllocatedRegion allocateExact(long size) {
        return allocate(size);
    }

    /**
     * Allocates at a specific offset.
     * Note: This does not advance the bump pointer, it's for direct placement.
     *
     * @param offset Offset within the segment
     * @param size Number of bytes to allocate
     * @return New allocation region
     * @throws AllocationException if offset is invalid or not enough space available
     */
    public AllocatedRegion allocateAt(long offset, long size) {
        if (offset < startOffset || offset + size > endOffset) {
            throw new IllegalArgumentException(
                "Invalid allocation range: offset=" + offset +
                ", size=" + size + ", bounds=[" + startOffset + ", " + endOffset + "]");
        }

        AllocatedRegion region = new AllocatedRegion(offset, size, segment);
        allocations.add(region);
        allocCount.incrementAndGet();

        logger.debug("Allocated {} bytes at fixed offset {} (total allocations: {})",
                     size, offset, allocCount.get());
        return region;
    }

    /**
     * Frees an allocated region.
     * Note: This is a logical free - memory is not reclaimed until clear().
     *
     * @param region The region to free
     * @return true if freed, false if not found
     */
    public boolean free(AllocatedRegion region) {
        if (region == null) {
            throw new IllegalArgumentException("Region cannot be null");
        }

        boolean removed = allocations.removeIf(r ->
            r.offset() == region.offset() && r.size() == region.size());

        if (removed) {
            freeCount.incrementAndGet();
            logger.debug("Freed {} bytes at offset {} (total frees: {})",
                         region.size(), region.offset(), freeCount.get());
        }
        return removed;
    }

    /**
     * Resets the allocation pointer to the specified offset.
     * WARNING: This allows overwriting previously allocated data.
     *
     * @param offset New offset
     * @return true if successful, false if offset is invalid
     */
    public boolean seek(long offset) {
        if (offset < startOffset || offset > endOffset) {
            return false;
        }
        currentOffset.set(offset);
        logger.debug("Seeked to offset {}", offset);
        return true;
    }

    /**
     * Gets the remaining free space.
     *
     * @return Number of bytes remaining
     */
    public long remaining() {
        return endOffset - currentOffset.get();
    }

    /**
     * Gets the total allocated bytes.
     *
     * @return Total bytes allocated
     */
    public long allocated() {
        return currentOffset.get() - startOffset;
    }

    /**
     * Gets the total size of the segment.
     *
     * @return Total size in bytes
     */
    public long totalSize() {
        return segmentSize;
    }

    /**
     * Gets the start offset of the segment.
     *
     * @return Start offset
     */
    public long startOffset() {
        return startOffset;
    }

    /**
     * Gets the end offset of the segment.
     *
     * @return End offset
     */
    public long endOffset() {
        return endOffset;
    }

    /**
     * Gets the current allocation offset.
     *
     * @return Current offset
     */
    public long currentOffset() {
        return currentOffset.get();
    }

    /**
     * Gets the number of allocated regions.
     *
     * @return Number of allocations
     */
    public int allocationCount() {
        return allocations.size();
    }

    /**
     * Gets the number of freed regions.
     *
     * @return Number of frees
     */
    public long freeCount() {
        return freeCount.get();
    }

    /**
     * Gets all allocated regions.
     *
     * @return List of allocations
     */
    public List<AllocatedRegion> allocations() {
        return new ArrayList<>(allocations);
    }

    /**
     * Gets the underlying Segment abstraction.
     *
     * @return The Segment
     */
    public Segment segment() {
        return segment;
    }

    /**
     * Gets the underlying native MemorySegment if Panama-backed.
     *
     * @return The native MemorySegment, or null if using fallback
     */
    public MemorySegment nativeSegment() {
        return nativeSegment;
    }

    /**
     * Checks if this allocator is backed by Panama FFM.
     *
     * @return true if using Panama FFM implementation
     */
    public boolean isPanama() {
        return nativeSegment != null;
    }

    /**
     * Clears all allocations and resets to start.
     */
    public void clear() {
        allocations.clear();
        currentOffset.set(startOffset);
        logger.info("SegmentAllocator cleared");
    }

    /**
     * Gets a region at the given offset.
     *
     * @param offset Offset of the region
     * @return AllocatedRegion at offset
     * @throws IllegalArgumentException if offset is invalid
     */
    public AllocatedRegion regionAt(long offset) {
        for (AllocatedRegion r : allocations) {
            if (r.offset() == offset) {
                return r;
            }
        }
        throw new IllegalArgumentException(
            "No allocation found at offset " + offset +
            ", current offset: " + currentOffset.get());
    }

    /**
     * Reads bytes from an allocation into a buffer.
     *
     * @param region The allocation to read from
     * @param buffer Destination buffer
     * @param bufferOffset Offset within the buffer
     * @param length Number of bytes to read
     * @return Number of bytes read
     * @throws IllegalArgumentException if region or parameters are invalid
     */
    public int read(AllocatedRegion region, byte[] buffer, int bufferOffset, int length) {
        if (region == null) {
            throw new IllegalArgumentException("Region cannot be null");
        }
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (bufferOffset < 0 || length < 0 || bufferOffset + length > buffer.length) {
            throw new IllegalArgumentException("Invalid buffer parameters");
        }
        if (length > region.size()) {
            throw new IllegalArgumentException(
                "Read length exceeds region size: " + length + " > " + region.size());
        }

        // Use Segment abstraction for reading
        segment.copyTo(region.offset(), buffer, bufferOffset, length);

        return length;
    }

    /**
     * Writes bytes from a buffer to an allocation.
     *
     * @param region The allocation to write to
     * @param buffer Source buffer
     * @param bufferOffset Offset within the buffer
     * @param length Number of bytes to write
     * @return Number of bytes written
     * @throws IllegalArgumentException if region or parameters are invalid
     */
    public int write(AllocatedRegion region, byte[] buffer, int bufferOffset, int length) {
        if (region == null) {
            throw new IllegalArgumentException("Region cannot be null");
        }
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (bufferOffset < 0 || length < 0 || bufferOffset + length > buffer.length) {
            throw new IllegalArgumentException("Invalid buffer parameters");
        }
        if (length > region.size()) {
            throw new IllegalArgumentException(
                "Write length exceeds region size: " + length + " > " + region.size());
        }

        // Use Segment abstraction for writing
        segment.copyFrom(buffer, bufferOffset, region.offset(), length);

        return length;
    }

    /**
     * A region allocated within the segment.
     */
    public static final class AllocatedRegion {
        private final long offset;
        private final long size;
        private final Segment segment;

        AllocatedRegion(long offset, long size, Segment segment) {
            this.offset = offset;
            this.size = size;
            this.segment = segment;
        }

        /**
         * Gets the offset of this region within the segment.
         * @return The offset
         */
        public long offset() {
            return offset;
        }

        /**
         * Gets the size of this region.
         * @return The size in bytes
         */
        public long size() {
            return size;
        }

        /**
         * Gets the size of this region (alias for size()).
         * @return The size in bytes
         */
        public long length() {
            return size;
        }

        /**
         * Gets a slice of this region as a Segment.
         *
         * @param sliceOffset Offset within this region
         * @param sliceLength Length of the slice
         * @return A Segment slice
         */
        public Segment slice(long sliceOffset, long sliceLength) {
            if (sliceOffset < 0 || sliceLength < 0 || sliceOffset + sliceLength > size) {
                throw new IllegalArgumentException("Invalid slice parameters");
            }
            return segment.asSlice(offset + sliceOffset, sliceLength);
        }

        /**
         * Gets a slice of the entire region.
         * @return A Segment slice covering the entire region
         */
        public Segment slice() {
            return segment.asSlice(offset, size);
        }

        /**
         * Gets a slice of this region as a native MemorySegment.
         *
         * <p><strong>Warning:</strong> This method returns null if the segment
         * is not Panama-backed. Use {@link #slice()} for the abstraction.</p>
         *
         * @param sliceOffset Offset within this region
         * @param sliceLength Length of the slice
         * @return A native MemorySegment slice, or null if not Panama-backed
         */
        public MemorySegment nativeSlice(long sliceOffset, long sliceLength) {
            if (sliceOffset < 0 || sliceLength < 0 || sliceOffset + sliceLength > size) {
                throw new IllegalArgumentException("Invalid slice parameters");
            }
            MemorySegment nativeSeg = segment.nativeSegment();
            if (nativeSeg == null) {
                return null;
            }
            return nativeSeg.asSlice(offset + sliceOffset, sliceLength);
        }

        /**
         * Gets a slice of the entire region as a native MemorySegment.
         *
         * <p><strong>Warning:</strong> This method returns null if the segment
         * is not Panama-backed. Use {@link #slice()} for the abstraction.</p>
         *
         * @return A native MemorySegment slice, or null if not Panama-backed
         */
        public MemorySegment nativeSlice() {
            MemorySegment nativeSeg = segment.nativeSegment();
            if (nativeSeg == null) {
                return null;
            }
            return nativeSeg.asSlice(offset, size);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AllocatedRegion that = (AllocatedRegion) o;
            return offset == that.offset && size == that.size;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(offset) + Long.hashCode(size);
        }

        @Override
        public String toString() {
            return "AllocatedRegion[offset=" + offset + ", size=" + size + "]";
        }
    }
}
