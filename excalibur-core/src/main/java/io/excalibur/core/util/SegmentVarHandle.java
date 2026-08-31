package io.excalibur.core.util;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * VarHandle-based utility class for atomic operations on MemorySegment.
 *
 * <p>This class provides thread-safe read/write operations using VarHandle
 * with volatile semantics for MemorySegment access.</p>
 *
 * <h2>Memory Ordering</h2>
 * <p>Read operations use volatile (acquire) semantics via {@code getVolatile}.
 * Write operations use volatile (release) semantics via {@code setVolatile}.
 * Compare-and-set operations provide full atomic CAS guarantees.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All read/write methods are thread-safe with volatile memory ordering.
 * Compare-and-set and get-and-add operations are fully atomic.</p>
 *
 * <p>The one exception is {@link #readLongUnaligned}, which guarantees only per-byte
 * atomicity for arbitrary offsets; see its javadoc before relying on the assembled
 * 64-bit value.</p>
 *
 * <h2>Implementation Note</h2>
 * <p>In Java 21's FFM API, VarHandles from ValueLayout have 1 coordinate (MemorySegment).
 * This implementation uses MemorySegment slicing to provide offset-based access.
 * The slice operation is zero-cost as it creates a view, not a copy.</p>
 *
 * @author ExcaliburDB
 * @see MemorySegment
 * @see VarHandle
 */
public final class SegmentVarHandle {

    // VarHandles for atomic access with volatile semantics
    // Using BIG_ENDIAN for consistent cross-platform behavior
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT
        .withOrder(ByteOrder.BIG_ENDIAN)
        .varHandle();
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG
        .withOrder(ByteOrder.BIG_ENDIAN)
        .varHandle();
    private static final VarHandle FLOAT_HANDLE = ValueLayout.JAVA_FLOAT
        .withOrder(ByteOrder.BIG_ENDIAN)
        .varHandle();
    private static final VarHandle DOUBLE_HANDLE = ValueLayout.JAVA_DOUBLE
        .withOrder(ByteOrder.BIG_ENDIAN)
        .varHandle();
    private static final VarHandle BYTE_HANDLE = ValueLayout.JAVA_BYTE.varHandle();
    private static final VarHandle BOOLEAN_HANDLE = ValueLayout.JAVA_BOOLEAN.varHandle();

    // Layouts for direct MemorySegment access (for non-volatile operations)
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_LAYOUT = ValueLayout.JAVA_DOUBLE
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfBoolean BOOLEAN_LAYOUT = ValueLayout.JAVA_BOOLEAN;

    private SegmentVarHandle() {
        // Utility class - prevent instantiation
    }

    // ==================== Int Operations ====================

    /**
     * Atomically reads an int from a MemorySegment at the specified offset
     * with volatile (acquire) semantics.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The int value (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int readInt(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return (int) INT_HANDLE.getVolatile(slice);
    }

    /**
     * Atomically writes an int to a MemorySegment at the specified offset
     * with volatile (release) semantics.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The int value to write (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeInt(MemorySegment segment, long offset, int value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        INT_HANDLE.setVolatile(slice, value);
    }

    /**
     * Atomically compares and sets an int value.
     *
     * <p>This operation is atomic and provides the same memory ordering
     * guarantees as {@link java.util.concurrent.atomic.AtomicInteger#compareAndSet}.</p>
     *
     * @param segment  The memory segment
     * @param offset   Offset in bytes
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static boolean compareAndSetInt(MemorySegment segment, long offset,
                                           int expected, int newValue) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return INT_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Atomically adds a delta to an int value and returns the previous value.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int getAndAddInt(MemorySegment segment, long offset, int delta) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return (int) INT_HANDLE.getAndAdd(slice, delta);
    }

    /**
     * Atomically adds a delta to an int value and returns the new value.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param delta   The value to add
     * @return The new value after addition
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int addGetInt(MemorySegment segment, long offset, int delta) {
        return getAndAddInt(segment, offset, delta) + delta;
    }

    // ==================== Long Operations ====================

    /**
     * Atomically reads a long from a MemorySegment at the specified offset
     * with volatile (acquire) semantics.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The long value (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static long readLong(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return (long) LONG_HANDLE.getVolatile(slice);
    }

    /**
     * Reads up to 8 bytes from a MemorySegment at the specified offset, assembling them
     * into a long. Each individual byte is read with volatile (acquire) semantics.
     *
     * <h3>Atomicity - read this before relying on the result</h3>
     * <p><strong>The assembled 64-bit value is NOT read atomically unless the access
     * happens to be a naturally aligned 8-byte read.</strong> Only single-byte atomicity
     * is guaranteed in the general case: a concurrent writer can land between two
     * iterations, so the returned long can mix pre-write and post-write bytes. For a
     * value that spans bytes - a packed multi-bit field, for instance - the result may
     * then equal neither the old nor the new value.</p>
     *
     * <p>Callers that require a genuinely atomic 64-bit load must align the datum to
     * 8 bytes and use {@link #readLong(MemorySegment, long)}, or serialize with the
     * writer using the same lock the write path takes. As an optimisation this method
     * performs a single atomic volatile load whenever the access is an aligned 8-byte
     * read of a native segment, but that is a best-effort strengthening, not a contract
     * the caller can depend on for arbitrary offsets.</p>
     *
     * <h3>Use Case</h3>
     * <p>This is designed for packed data structures where offsets may not be
     * 8-byte aligned, such as CuckooFilter's packed fingerprint storage.</p>
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @param bytesToRead Number of bytes to read (1-8)
     * @return The value assembled from bytes (big-endian, left-aligned in result)
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if bytesToRead is not in range [1, 8]
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static long readLongUnaligned(MemorySegment segment, long offset, int bytesToRead) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (bytesToRead < 1 || bytesToRead > 8) {
            throw new IllegalArgumentException("bytesToRead must be in range [1, 8], got: " + bytesToRead);
        }
        checkBounds(segment, offset, bytesToRead);

        // Fast path: a naturally aligned 8-byte read of a native segment can be done as a
        // single atomic volatile load, which is both faster and free of tearing.
        if (bytesToRead == 8 && isEightByteAligned(segment, offset)) {
            return (long) LONG_HANDLE.getVolatile(segment.asSlice(offset, 8));
        }

        // General path: read byte-by-byte with volatile semantics and assemble into long.
        // Only per-byte atomicity holds here - see the atomicity note above.
        long value = 0;
        for (int i = 0; i < bytesToRead; i++) {
            MemorySegment byteSlice = segment.asSlice(offset + i, 1);
            byte b = (byte) BYTE_HANDLE.getVolatile(byteSlice);
            value = (value << 8) | (b & 0xFF);
        }
        // Shift to left-align in 64-bit result (high bits)
        value <<= (8 - bytesToRead) * 8;
        return value;
    }

    /**
     * Returns true if an access at {@code offset} within {@code segment} is naturally
     * aligned for an 8-byte volatile access.
     *
     * <p>Only native segments are considered: a heap segment backed by a byte array has a
     * maximum alignment of one byte regardless of its reported address, so an aligned
     * volatile load is rejected by the FFM API there.</p>
     */
    private static boolean isEightByteAligned(MemorySegment segment, long offset) {
        return segment.isNative() && ((segment.address() + offset) & 7L) == 0L;
    }

    /**
     * Atomically writes a long to a MemorySegment at the specified offset
     * with volatile (release) semantics.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The long value to write (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeLong(MemorySegment segment, long offset, long value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        LONG_HANDLE.setVolatile(slice, value);
    }

    /**
     * Atomically compares and sets a long value.
     *
     * <p>This operation is atomic and provides the same memory ordering
     * guarantees as {@link java.util.concurrent.atomic.AtomicLong#compareAndSet}.</p>
     *
     * @param segment  The memory segment
     * @param offset   Offset in bytes
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static boolean compareAndSetLong(MemorySegment segment, long offset,
                                            long expected, long newValue) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return LONG_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Atomically adds a delta to a long value and returns the previous value.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static long getAndAddLong(MemorySegment segment, long offset, long delta) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return (long) LONG_HANDLE.getAndAdd(slice, delta);
    }

    /**
     * Atomically adds a delta to a long value and returns the new value.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param delta   The value to add
     * @return The new value after addition
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static long addGetLong(MemorySegment segment, long offset, long delta) {
        return getAndAddLong(segment, offset, delta) + delta;
    }

    // ==================== Float Operations ====================

    /**
     * Atomically reads a float from a MemorySegment at the specified offset
     * with volatile (acquire) semantics.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The float value (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static float readFloat(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return (float) FLOAT_HANDLE.getVolatile(slice);
    }

    /**
     * Atomically writes a float to a MemorySegment at the specified offset
     * with volatile (release) semantics.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The float value to write (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeFloat(MemorySegment segment, long offset, float value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        FLOAT_HANDLE.setVolatile(slice, value);
    }

    /**
     * Atomically compares and sets a float value.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Float.floatToRawIntBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The memory segment
     * @param offset   Offset in bytes
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static boolean compareAndSetFloat(MemorySegment segment, long offset,
                                             float expected, float newValue) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return FLOAT_HANDLE.compareAndSet(slice, expected, newValue);
    }

    // ==================== Double Operations ====================

    /**
     * Atomically reads a double from a MemorySegment at the specified offset
     * with volatile (acquire) semantics.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The double value (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static double readDouble(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return (double) DOUBLE_HANDLE.getVolatile(slice);
    }

    /**
     * Atomically writes a double to a MemorySegment at the specified offset
     * with volatile (release) semantics.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The double value to write (big-endian)
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeDouble(MemorySegment segment, long offset, double value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        DOUBLE_HANDLE.setVolatile(slice, value);
    }

    /**
     * Atomically compares and sets a double value.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Double.doubleToRawLongBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The memory segment
     * @param offset   Offset in bytes
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static boolean compareAndSetDouble(MemorySegment segment, long offset,
                                              double expected, double newValue) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        checkBounds(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return DOUBLE_HANDLE.compareAndSet(slice, expected, newValue);
    }

    // ==================== Byte Operations ====================

    /**
     * Reads a byte from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The byte value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static byte readByte(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(BYTE_LAYOUT, offset);
    }

    /**
     * Writes a byte to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The byte value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeByte(MemorySegment segment, long offset, byte value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(BYTE_LAYOUT, offset, value);
    }

    // ==================== Boolean Operations ====================

    /**
     * Reads a boolean from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The boolean value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static boolean readBoolean(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(BOOLEAN_LAYOUT, offset);
    }

    /**
     * Writes a boolean to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The boolean value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeBoolean(MemorySegment segment, long offset, boolean value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(BOOLEAN_LAYOUT, offset, value);
    }

    // ==================== Legacy Compatibility Methods ====================

    /**
     * Gets the current value from a MemorySegment at the specified offset.
     * Equivalent to {@link #readInt(MemorySegment, long)}.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @return The int value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int get(MemorySegment segment, long offset) {
        return readInt(segment, offset);
    }

    /**
     * Sets a value in a MemorySegment at the specified offset.
     * Equivalent to {@link #writeInt(MemorySegment, long, int)}.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param value   The value to set
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void set(MemorySegment segment, long offset, int value) {
        writeInt(segment, offset, value);
    }

    // ==================== Bulk Operations ====================

    /**
     * Reads a value from a MemorySegment as a MemorySegment slice.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @param length  Length of data to read
     * @return A slice of the segment
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public static MemorySegment read(MemorySegment segment, long offset, long length) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        if (offset + length > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds segment byteSize " + segment.byteSize());
        }
        return segment.asSlice(offset, length);
    }

    /**
     * Writes a byte array to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param data    The byte array to write
     * @throws NullPointerException if segment or data is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void write(MemorySegment segment, long offset, byte[] data) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        if (offset + data.length > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with data length " + data.length + " exceeds segment byteSize " + segment.byteSize());
        }
        MemorySegment.copy(data, 0, segment, BYTE_LAYOUT, offset, data.length);
    }

    /**
     * Writes a MemorySegment to another at the specified offset.
     *
     * @param target   The target memory segment
     * @param source   The source memory segment
     * @param offset   Offset in bytes
     * @throws NullPointerException if target or source is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void write(MemorySegment target, MemorySegment source, long offset) {
        if (target == null) {
            throw new NullPointerException("Target cannot be null");
        }
        if (source == null) {
            throw new NullPointerException("Source cannot be null");
        }
        if (offset + source.byteSize() > target.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with source byteSize " + source.byteSize() + " exceeds target byteSize " + target.byteSize());
        }
        MemorySegment.copy(source, 0, target, offset, source.byteSize());
    }

    // ==================== Spin-Lock Utilities ====================

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment    The memory segment
     * @param offset     Offset in bytes
     * @param expected   The expected current value
     * @param newValue   The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasInt(MemorySegment segment, long offset,
                                     int expected, int newValue, int maxRetries) {
        int currentExpected = expected;
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetInt(segment, offset, currentExpected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            currentExpected = readInt(segment, offset);
        }
        return false;
    }

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment    The memory segment
     * @param offset     Offset in bytes
     * @param expected   The expected current value
     * @param newValue   The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasLong(MemorySegment segment, long offset,
                                      long expected, long newValue, int maxRetries) {
        long currentExpected = expected;
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetLong(segment, offset, currentExpected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            currentExpected = readLong(segment, offset);
        }
        return false;
    }

    // ==================== VarHandle Accessors ====================

    /**
     * Gets the VarHandle for int access with volatile semantics.
     * @return The int VarHandle
     */
    public static VarHandle intHandle() {
        return INT_HANDLE;
    }

    /**
     * Gets the VarHandle for long access with volatile semantics.
     * @return The long VarHandle
     */
    public static VarHandle longHandle() {
        return LONG_HANDLE;
    }

    /**
     * Gets the VarHandle for float access with volatile semantics.
     * @return The float VarHandle
     */
    public static VarHandle floatHandle() {
        return FLOAT_HANDLE;
    }

    /**
     * Gets the VarHandle for double access with volatile semantics.
     * @return The double VarHandle
     */
    public static VarHandle doubleHandle() {
        return DOUBLE_HANDLE;
    }

    /**
     * Gets the VarHandle for byte access.
     * @return The byte VarHandle
     */
    public static VarHandle byteHandle() {
        return BYTE_HANDLE;
    }

    // ==================== Helper Methods ====================

    /**
     * Checks bounds for memory access.
     *
     * @param segment The memory segment
     * @param offset  The offset to validate
     * @param size    The size of the access
     * @throws IndexOutOfBoundsException if access is out of bounds
     */
    private static void checkBounds(MemorySegment segment, long offset, int size) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("Offset cannot be negative: " + offset);
        }
        if (offset + size > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Access out of bounds: offset=" + offset + ", size=" + size +
                ", segmentSize=" + segment.byteSize());
        }
    }
}
