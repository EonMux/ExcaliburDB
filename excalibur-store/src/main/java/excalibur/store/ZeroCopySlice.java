package excalibur.store;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * A zero-copy slice of a value stored in a Segment, with epoch-based validity tracking.
 *
 * <p>This record wraps a {@link MemorySegment} that points directly to the off-heap memory
 * storing a value in a Segment. The slice remains valid until the segment is modified
 * (put/delete) or closed.</p>
 *
 * <h2>Validity Contract</h2>
 * <p>A zero-copy slice is valid if and only if:</p>
 * <ul>
 *   <li>The segment has not been closed</li>
 *   <li>No modifications (put/delete) have occurred since the slice was created</li>
 *   <li>The arena backing the segment is still open</li>
 * </ul>
 *
 * <h2>Epoch-Based Validity Checking</h2>
 * <p>Each slice captures the modification epoch of the segment at creation time.
 * Validity is determined by comparing this epoch with the segment's current epoch:</p>
 * <pre>{@code
 * ZeroCopySlice slice = segment.getWithEpoch(key);
 * // ... use slice.data() ...
 * if (slice.isValid()) {
 *     // Slice is still valid, safe to access
 *     byte[] value = slice.toByteArray();
 * } else {
 *     // Slice invalidated by modification, need to re-fetch
 *     slice = segment.getWithEpoch(key);
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Instances of this class are immutable and thread-safe. However, the underlying
 * MemorySegment may become invalid if the segment is modified by another thread.
 * Always call {@link #isValid()} before accessing the data.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>{@code epoch}: Non-negative long value representing the modification count at creation</li>
 *   <li>{@code segmentId}: Unique identifier of the segment that created this slice</li>
 *   <li>{@code data}: MemorySegment pointing to off-heap memory, or null if not found</li>
 * </ul>
 *
 * @param epoch     The modification epoch at which this slice was created
 * @param segmentId The unique identifier of the segment that created this slice
 * @param data      The MemorySegment slice pointing to the value, or null if not found
 * @param segment   Reference to the segment for validity checking
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see Segment#getWithEpoch(byte[])
 * @see Segment#isSliceValid(ZeroCopySlice)
 */
public record ZeroCopySlice(
    long epoch,
    long segmentId,
    MemorySegment data,
    Segment segment
) {

    /**
     * Compact constructor that validates the parameters.
     *
     * @throws IllegalArgumentException if epoch is negative
     */
    public ZeroCopySlice {
        if (epoch < 0) {
            throw new IllegalArgumentException("Epoch must be non-negative, got: " + epoch);
        }
        // segment can be null if the key was not found
        // segmentId must be non-negative
        if (segmentId < 0) {
            throw new IllegalArgumentException("Segment ID must be non-negative, got: " + segmentId);
        }
    }

    /**
     * Creates a slice representing a not-found result.
     *
     * @param epoch     The current modification epoch
     * @param segmentId The segment ID
     * @param segment   The segment reference
     * @return A ZeroCopySlice with null data representing not found
     */
    public static ZeroCopySlice notFound(long epoch, long segmentId, Segment segment) {
        return new ZeroCopySlice(epoch, segmentId, null, segment);
    }

    /**
     * Checks if this slice contains found data.
     *
     * @return true if the key was found and data is non-null
     */
    public boolean isFound() {
        return data != null;
    }

    /**
     * Checks if this slice is still valid.
     *
     * <p>A slice is valid if:</p>
     * <ul>
     *   <li>The segment is not closed</li>
     *   <li>No modifications have occurred since creation</li>
     * </ul>
     *
     * <p>Note: This method does NOT check if the Arena is still open. The Arena's
     * lifecycle is managed separately and closing it will cause access to the
     * MemorySegment to fail.</p>
     *
     * @return true if the slice is still valid and safe to access
     */
    public boolean isValid() {
        if (segment == null) {
            return false;
        }
        return segment.isSliceValid(this);
    }

    /**
     * Returns the size of the value data in bytes.
     *
     * @return The byte size, or 0 if data is null
     */
    public long size() {
        return data != null ? data.byteSize() : 0;
    }

    /**
     * Copies the value data to a new byte array.
     *
     * <p><strong>Warning:</strong> This creates a heap copy and defeats the zero-copy
     * optimization. Use this only when you need to modify the data or keep it beyond
     * the slice's validity period.</p>
     *
     * @return A new byte array containing the value data, or null if data is null
     */
    public byte[] toByteArray() {
        if (data == null) {
            return null;
        }
        return data.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Returns the first byte of the value, or -1 if not found or empty.
     *
     * <p>This is a convenience method for quick checks without creating a byte array.</p>
     *
     * @return The first byte (0-255), or -1 if not found or empty
     * @throws IllegalStateException if the slice is invalid
     */
    public int firstByte() {
        if (data == null || data.byteSize() == 0) {
            return -1;
        }
        if (!isValid()) {
            throw new IllegalStateException("Slice is no longer valid - segment was modified or closed");
        }
        return data.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
    }

    /**
     * Reads a byte at the specified offset within the value data.
     *
     * @param offset The byte offset (0 to size()-1)
     * @return The byte value (0-255)
     * @throws IndexOutOfBoundsException if offset is out of range
     * @throws IllegalStateException if the slice is invalid
     */
    public int getByte(long offset) {
        if (data == null) {
            throw new IllegalStateException("No data - key was not found");
        }
        if (!isValid()) {
            throw new IllegalStateException("Slice is no longer valid - segment was modified or closed");
        }
        if (offset < 0 || offset >= data.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " out of range [0, " + data.byteSize() + ")");
        }
        return data.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
    }

    /**
     * Verifies the slice is still valid and returns the data.
     *
     * @return The MemorySegment data
     * @throws IllegalStateException if the slice is invalid
     */
    public MemorySegment dataOrThrow() {
        if (data == null) {
            throw new IllegalStateException("No data - key was not found");
        }
        if (!isValid()) {
            throw new IllegalStateException("Slice is no longer valid - segment was modified or closed");
        }
        return data;
    }

    @Override
    public String toString() {
        String dataSize = data != null ? String.valueOf(data.byteSize()) + " bytes" : "null";
        String validStatus = (segment != null && segment.isSliceValid(this)) ? "valid" : "invalid";
        return String.format("ZeroCopySlice{epoch=%d, segmentId=%d, data=%s, status=%s}",
            epoch, segmentId, dataSize, validStatus);
    }
}
