package com.eonmux.excalibur.core.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Memory segment abstraction for direct memory access operations.
 *
 * <p>This interface provides a unified API for memory-mapped file I/O operations
 * that can be implemented using either Panama FFM (Foreign Function and Memory API)
 * or a fallback ByteBuffer-based implementation for environments where Panama FFM
 * is unavailable (older JVMs, restricted security managers).</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Read operations on Segment instances are thread-safe and may be
 * performed concurrently from multiple threads. Write operations require
 * external synchronization to ensure thread safety. Implementations should
 * document any additional thread-safety guarantees.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>All primitive operations use big-endian byte order by default unless
 * otherwise specified. This ensures consistency across different platforms.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a memory-mapped segment
 * Segment segment = MemorySegmentFactory.mapFile(path, 4096);
 *
 * // Write primitives
 * segment.setInt(0, 0x12345678);
 * segment.setLong(4, 123456789L);
 *
 * // Read primitives
 * int value = segment.getInt(0);
 *
 * // Create a slice for zero-copy access
 * Segment slice = segment.asSlice(100, 200);
 *
 * // Flush to disk
 * segment.force();
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public interface Segment extends AutoCloseable {

    // ==================== Factory Methods ====================

    /**
     * Maps a file into memory.
     *
     * @param path The path to the file
     * @param size The size of the mapping
     * @return A memory segment backed by the file
     * @throws IOException if mapping fails
     */
    static Segment mapFile(Path path, long size) throws IOException {
        return MemorySegmentFactory.mapFile(path, size);
    }

    // ==================== Size and Capacity ====================

    /**
     * Returns the size of this memory segment in bytes.
     *
     * @return The size in bytes
     */
    long byteSize();

    /**
     * Checks if this segment is null (zero-sized or unmapped).
     *
     * @return true if the segment is null/empty
     */
    default boolean isNull() {
        return byteSize() == 0;
    }

    // ==================== Slice Operations ====================

    /**
     * Returns a slice of this memory segment.
     *
     * <p>The slice is a view of the same underlying memory, not a copy.
     * Changes to the slice are reflected in the original segment and vice versa.</p>
     *
     * @param offset The starting offset
     * @param length The length of the slice
     * @return A new segment representing the slice
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    Segment asSlice(long offset, long length);

    /**
     * Returns a slice of this memory segment from the offset to the end.
     *
     * @param offset The starting offset
     * @return A new segment representing the slice
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    default Segment asSlice(long offset) {
        return asSlice(offset, byteSize() - offset);
    }

    // ==================== Byte Operations ====================

    /**
     * Reads a byte at the specified offset.
     *
     * @param offset The offset within the segment
     * @return The byte value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    byte getByte(long offset);

    /**
     * Writes a byte at the specified offset.
     *
     * @param offset The offset within the segment
     * @param value The byte value to write
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    void setByte(long offset, byte value);

    /**
     * Reads bytes from the specified offset into a new byte array.
     *
     * @param offset The starting offset
     * @param length The number of bytes to read
     * @return A new byte array containing the read data
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    byte[] getBytes(long offset, int length);

    /**
     * Writes bytes from a byte array to the specified offset.
     *
     * @param offset The offset within the segment
     * @param src The source byte array
     * @throws IndexOutOfBoundsException if offset is invalid or array is too large
     */
    default void setBytes(long offset, byte[] src) {
        setBytes(offset, src, 0, src.length);
    }

    /**
     * Writes bytes from a byte array to the specified offset.
     *
     * @param offset The offset within the segment
     * @param src The source byte array
     * @param srcOffset The offset within the source array
     * @param length The number of bytes to write
     * @throws IndexOutOfBoundsException if parameters are invalid
     */
    void setBytes(long offset, byte[] src, int srcOffset, int length);

    // ==================== Int Operations ====================

    /**
     * Reads an int at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @return The int value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    int getInt(long offset);

    /**
     * Writes an int at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @param value The int value to write
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    void setInt(long offset, int value);

    // ==================== Long Operations ====================

    /**
     * Reads a long at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @return The long value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    long getLong(long offset);

    /**
     * Writes a long at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @param value The long value to write
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    void setLong(long offset, long value);

    // ==================== Float Operations ====================

    /**
     * Reads a float at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @return The float value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    float getFloat(long offset);

    /**
     * Writes a float at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @param value The float value to write
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    void setFloat(long offset, float value);

    // ==================== Double Operations ====================

    /**
     * Reads a double at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @return The double value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    double getDouble(long offset);

    /**
     * Writes a double at the specified offset (big-endian).
     *
     * @param offset The offset within the segment
     * @param value The double value to write
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    void setDouble(long offset, double value);

    // ==================== Copy Operations ====================

    /**
     * Copies bytes from this segment to a byte array.
     *
     * @param srcOffset The offset within this segment
     * @param dst The destination byte array
     * @param dstOffset The offset within the destination array
     * @param length The number of bytes to copy
     */
    void copyTo(long srcOffset, byte[] dst, int dstOffset, int length);

    /**
     * Copies bytes from a byte array to this segment.
     *
     * @param src The source byte array
     * @param srcOffset The offset within the source array
     * @param dstOffset The offset within this segment
     * @param length The number of bytes to copy
     */
    void copyFrom(byte[] src, int srcOffset, long dstOffset, int length);

    /**
     * Copies bytes from this segment to another segment.
     *
     * @param srcOffset The offset within this segment
     * @param dst The destination segment
     * @param dstOffset The offset within the destination segment
     * @param length The number of bytes to copy
     */
    void copyTo(long srcOffset, Segment dst, long dstOffset, long length);

    /**
     * Copies bytes from another segment to this segment.
     *
     * @param src The source segment
     * @param srcOffset The offset within the source segment
     * @param dstOffset The offset within this segment
     * @param length The number of bytes to copy
     */
    void copyFrom(Segment src, long srcOffset, long dstOffset, long length);

    // ==================== ByteBuffer Operations ====================

    /**
     * Reads bytes into a ByteBuffer.
     *
     * @param offset The offset within this segment
     * @param buffer The destination buffer
     * @param length The number of bytes to read
     */
    void read(long offset, ByteBuffer buffer, int length);

    /**
     * Writes bytes from a ByteBuffer.
     *
     * @param offset The offset within this segment
     * @param buffer The source buffer
     */
    void write(long offset, ByteBuffer buffer);

    // ==================== Flush and Persistence ====================

    /**
     * Forces all changes to be written to the underlying storage device.
     *
     * <p>This method ensures that all modifications made to this segment
     * are persisted to the backing file.</p>
     */
    void force();

    // ==================== File Channel Access ====================

    /**
     * Returns a FileChannel for this segment.
     *
     * @return The file channel, or null if not backed by a file
     */
    FileChannel toChannel();

    /**
     * Returns the file path backing this segment.
     *
     * @return The file path, or null if not backed by a file
     */
    Path path();

    // ==================== Lifecycle ====================

    /**
     * Closes this segment and releases any associated resources.
     *
     * <p>After closing, all operations on this segment may throw
     * {@link IllegalStateException}.</p>
     */
    @Override
    void close();

    /**
     * Checks if this segment has been closed.
     *
     * @return true if closed
     */
    boolean isClosed();

    // ==================== VarHandle Support ====================

    /**
     * Returns the underlying native MemorySegment if using Panama FFM.
     * This method provides escape hatch access for advanced operations.
     *
     * <p><strong>Warning:</strong> This method may return null if the
     * implementation does not use Panama FFM. Callers must check the
     * result before using it.</p>
     *
     * @return The underlying java.lang.foreign.MemorySegment, or null
     */
    default java.lang.foreign.MemorySegment nativeSegment() {
        return null;
    }

    /**
     * Checks if this segment is backed by Panama FFM.
     *
     * @return true if using Panama FFM implementation
     */
    default boolean isPanama() {
        return false;
    }

    /**
     * Checks if this segment is backed by a ByteBuffer fallback.
     *
     * @return true if using ByteBuffer fallback implementation
     */
    default boolean isFallback() {
        return !isPanama();
    }
}
