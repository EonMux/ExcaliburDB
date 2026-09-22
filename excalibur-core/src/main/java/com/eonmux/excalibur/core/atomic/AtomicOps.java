package com.eonmux.excalibur.core.atomic;

import com.eonmux.excalibur.core.exception.AtomicException;
import com.eonmux.excalibur.core.memory.Segment;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * VarHandle-based atomic operations for memory segment access.
 *
 * <p>This class provides atomic read/write and compare-and-set operations
 * for memory segments using Java 21's Panama FFM VarHandle API.</p>
 *
 * <h2>API Design</h2>
 * <p>The primary public API accepts the {@link Segment} abstraction interface,
 * while internal operations use {@link MemorySegment} for VarHandle access.
 * The {@link Segment#nativeSegment()} method provides the escape hatch for
 * Panama FFM operations.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are atomic and thread-safe for concurrent access to
 * the underlying memory segment. Compare-and-set operations provide
 * lock-free atomic updates.</p>
 *
 * <h2>Memory Ordering</h2>
 * <p>Every {@code get*}/{@code put*} pair is a volatile access: reads have acquire
 * semantics and writes have release semantics. Compare-and-set and get-and-add are
 * fully atomic.</p>
 *
 * <h2>Alignment</h2>
 * <p>Volatile and atomic accesses are only defined at naturally aligned offsets, so every
 * operation wider than a byte requires the accessed address to be a multiple of its
 * width; a misaligned offset is rejected by the FFM API with
 * {@link IllegalArgumentException}. For deliberately unaligned packed data use
 * {@link com.eonmux.excalibur.core.util.SegmentVarHandle#readLongUnaligned}, which documents the
 * weaker guarantee it provides.</p>
 *
 * <h2>Implementation Note</h2>
 * <p>In Java 21's FFM API, VarHandles from ValueLayout have only 1 coordinate (MemorySegment).
 * This implementation uses MemorySegment slicing to provide offset-based access.
 * The slice operation is zero-cost as it creates a view, not a copy.</p>
 *
 * @author ExcaliburDB
 * @see Segment
 * @see MemorySegment
 * @see VarHandle
 */
public class AtomicOps {

    // VarHandles for atomic access to MemorySegment with volatile semantics
    // These VarHandles have 1 coordinate (MemorySegment) - we use slicing for offset access
    private static final VarHandle BYTE_HANDLE = ValueLayout.JAVA_BYTE.varHandle();
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT
        .withOrder(ByteOrder.BIG_ENDIAN).varHandle();
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG
        .withOrder(ByteOrder.BIG_ENDIAN).varHandle();
    private static final VarHandle FLOAT_HANDLE = ValueLayout.JAVA_FLOAT
        .withOrder(ByteOrder.BIG_ENDIAN).varHandle();
    private static final VarHandle DOUBLE_HANDLE = ValueLayout.JAVA_DOUBLE
        .withOrder(ByteOrder.BIG_ENDIAN).varHandle();

    // Static initialization
    static {
        // Ensure VarHandles are properly initialized
        MethodHandles.lookup(); // Trigger initialization
    }

    private AtomicOps() {
        // Utility class - prevent instantiation
    }

    // ==================== Byte Operations (Segment API) ====================

    /**
     * Atomically reads a byte from the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @return The byte value
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static byte getByte(Segment segment, long offset) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getByte(nativeSegment, offset);
    }

    /**
     * Atomically writes a byte to the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param value   The value to write
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static void putByte(Segment segment, long offset, byte value) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        putByte(nativeSegment, offset, value);
    }

    // ==================== Int Operations (Segment API) ====================

    /**
     * Atomically reads an int from the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @return The int value (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static int getInt(Segment segment, long offset) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getInt(nativeSegment, offset);
    }

    /**
     * Atomically writes an int to the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static void putInt(Segment segment, long offset, int value) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        putInt(nativeSegment, offset, value);
    }

    /**
     * Atomically compares and sets an int value.
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static boolean compareAndSetInt(Segment segment, long offset,
                                           int expected, int newValue) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return compareAndSetInt(nativeSegment, offset, expected, newValue);
    }

    /**
     * Atomically adds a delta to an int value and returns the previous value.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static int getAndAddInt(Segment segment, long offset, int delta) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getAndAddInt(nativeSegment, offset, delta);
    }

    /**
     * Atomically adds a delta to an int value and returns the new value.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static int addGetInt(Segment segment, long offset, int delta) {
        return getAndAddInt(segment, offset, delta) + delta;
    }

    // ==================== Long Operations (Segment API) ====================

    /**
     * Atomically reads a long from the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @return The long value (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static long getLong(Segment segment, long offset) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getLong(nativeSegment, offset);
    }

    /**
     * Atomically writes a long to the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static void putLong(Segment segment, long offset, long value) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        putLong(nativeSegment, offset, value);
    }

    /**
     * Atomically compares and sets a long value.
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static boolean compareAndSetLong(Segment segment, long offset,
                                            long expected, long newValue) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return compareAndSetLong(nativeSegment, offset, expected, newValue);
    }

    /**
     * Atomically adds a delta to a long value and returns the previous value.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static long getAndAddLong(Segment segment, long offset, long delta) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getAndAddLong(nativeSegment, offset, delta);
    }

    /**
     * Atomically adds a delta to a long value and returns the new value.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static long addGetLong(Segment segment, long offset, long delta) {
        return getAndAddLong(segment, offset, delta) + delta;
    }

    // ==================== Float Operations (Segment API) ====================

    /**
     * Atomically reads a float from the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @return The float value (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static float getFloat(Segment segment, long offset) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getFloat(nativeSegment, offset);
    }

    /**
     * Atomically writes a float to the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static void putFloat(Segment segment, long offset, float value) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        putFloat(nativeSegment, offset, value);
    }

    /**
     * Atomically compares and sets a float value.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Float.floatToRawIntBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static boolean compareAndSetFloat(Segment segment, long offset,
                                             float expected, float newValue) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return compareAndSetFloat(nativeSegment, offset, expected, newValue);
    }

    // ==================== Double Operations (Segment API) ====================

    /**
     * Atomically reads a double from the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @return The double value (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static double getDouble(Segment segment, long offset) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return getDouble(nativeSegment, offset);
    }

    /**
     * Atomically writes a double to the segment at the given offset.
     *
     * @param segment The memory segment abstraction
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static void putDouble(Segment segment, long offset, double value) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        putDouble(nativeSegment, offset, value);
    }

    /**
     * Atomically compares and sets a double value.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Double.doubleToRawLongBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null, not Panama-backed, or offset is invalid
     */
    public static boolean compareAndSetDouble(Segment segment, long offset,
                                              double expected, double newValue) {
        MemorySegment nativeSegment = getNativeSegment(segment);
        return compareAndSetDouble(nativeSegment, offset, expected, newValue);
    }

    // ==================== Utility Methods (Segment API) ====================

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasLong(Segment segment, long offset,
                                      long expected, long newValue, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetLong(segment, offset, expected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            expected = getLong(segment, offset);
        }
        return false;
    }

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment  The memory segment abstraction
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasInt(Segment segment, long offset,
                                     int expected, int newValue, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetInt(segment, offset, expected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            expected = getInt(segment, offset);
        }
        return false;
    }

    // ==================== Native MemorySegment Operations (Internal) ====================

    /**
     * Atomically reads a byte from the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @return The byte value
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static byte getByte(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 1);
        return (byte) BYTE_HANDLE.getVolatile(segment.asSlice(offset, 1));
    }

    /**
     * Atomically writes a byte to the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putByte(MemorySegment segment, long offset, byte value) {
        checkSegment(segment, offset, 1);
        BYTE_HANDLE.setVolatile(segment.asSlice(offset, 1), value);
    }

    /**
     * Atomically reads an int from the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @return The int value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static int getInt(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 4);
        return (int) INT_HANDLE.getVolatile(segment.asSlice(offset, 4));
    }

    /**
     * Atomically writes an int to the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putInt(MemorySegment segment, long offset, int value) {
        checkSegment(segment, offset, 4);
        INT_HANDLE.setVolatile(segment.asSlice(offset, 4), value);
    }

    /**
     * Atomically compares and sets an int value in the native segment.
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static boolean compareAndSetInt(MemorySegment segment, long offset,
                                           int expected, int newValue) {
        checkSegment(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return INT_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Atomically adds a delta to an int value and returns the previous value.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static int getAndAddInt(MemorySegment segment, long offset, int delta) {
        checkSegment(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return (int) INT_HANDLE.getAndAdd(slice, delta);
    }

    /**
     * Atomically adds a delta to an int value and returns the new value.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static int addGetInt(MemorySegment segment, long offset, int delta) {
        return getAndAddInt(segment, offset, delta) + delta;
    }

    /**
     * Atomically reads a long from the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @return The long value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static long getLong(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 8);
        return (long) LONG_HANDLE.getVolatile(segment.asSlice(offset, 8));
    }

    /**
     * Atomically writes a long to the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putLong(MemorySegment segment, long offset, long value) {
        checkSegment(segment, offset, 8);
        LONG_HANDLE.setVolatile(segment.asSlice(offset, 8), value);
    }

    /**
     * Atomically compares and sets a long value in the native segment.
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static boolean compareAndSetLong(MemorySegment segment, long offset,
                                            long expected, long newValue) {
        checkSegment(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return LONG_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Atomically adds a delta to a long value and returns the previous value.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The previous value before addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static long getAndAddLong(MemorySegment segment, long offset, long delta) {
        checkSegment(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return (long) LONG_HANDLE.getAndAdd(slice, delta);
    }

    /**
     * Atomically adds a delta to a long value and returns the new value.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static long addGetLong(MemorySegment segment, long offset, long delta) {
        return getAndAddLong(segment, offset, delta) + delta;
    }

    /**
     * Atomically reads a float from the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @return The float value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static float getFloat(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 4);
        return (float) FLOAT_HANDLE.getVolatile(segment.asSlice(offset, 4));
    }

    /**
     * Atomically writes a float to the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putFloat(MemorySegment segment, long offset, float value) {
        checkSegment(segment, offset, 4);
        FLOAT_HANDLE.setVolatile(segment.asSlice(offset, 4), value);
    }

    /**
     * Atomically compares and sets a float value in the native segment.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Float.floatToRawIntBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static boolean compareAndSetFloat(MemorySegment segment, long offset,
                                             float expected, float newValue) {
        checkSegment(segment, offset, 4);
        MemorySegment slice = segment.asSlice(offset, 4);
        return FLOAT_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Atomically reads a double from the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @return The double value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static double getDouble(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 8);
        return (double) DOUBLE_HANDLE.getVolatile(segment.asSlice(offset, 8));
    }

    /**
     * Atomically writes a double to the native segment at the given offset.
     *
     * @param segment The native memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putDouble(MemorySegment segment, long offset, double value) {
        checkSegment(segment, offset, 8);
        DOUBLE_HANDLE.setVolatile(segment.asSlice(offset, 8), value);
    }

    /**
     * Atomically compares and sets a double value in the native segment.
     *
     * <p>Uses bitwise comparison for floating-point values, following the semantics
     * of Double.doubleToRawLongBits(). NaN values are compared bitwise, not numerically.</p>
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static boolean compareAndSetDouble(MemorySegment segment, long offset,
                                              double expected, double newValue) {
        checkSegment(segment, offset, 8);
        MemorySegment slice = segment.asSlice(offset, 8);
        return DOUBLE_HANDLE.compareAndSet(slice, expected, newValue);
    }

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasLong(MemorySegment segment, long offset,
                                      long expected, long newValue, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetLong(segment, offset, expected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            expected = getLong(segment, offset);
        }
        return false;
    }

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment  The native memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set
     * @param maxRetries Maximum number of CAS attempts
     * @return true if successful, false if max retries exceeded
     */
    public static boolean spinCasInt(MemorySegment segment, long offset,
                                     int expected, int newValue, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (compareAndSetInt(segment, offset, expected, newValue)) {
                return true;
            }
            // Re-read current value for next attempt
            expected = getInt(segment, offset);
        }
        return false;
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts the native MemorySegment from the Segment abstraction.
     *
     * @param segment The Segment abstraction
     * @return The underlying native MemorySegment
     * @throws AtomicException if segment is null or not Panama-backed
     */
    private static MemorySegment getNativeSegment(Segment segment) {
        if (segment == null) {
            throw new AtomicException("Segment cannot be null");
        }
        MemorySegment nativeSegment = segment.nativeSegment();
        if (nativeSegment == null) {
            throw new AtomicException(
                "Segment must be Panama-backed for atomic operations. " +
                "Fallback segments do not support VarHandle-based atomic operations.");
        }
        return nativeSegment;
    }

    /**
     * Checks native segment validity and bounds.
     *
     * @param segment The native memory segment to check
     * @param offset  The offset to validate
     * @param size    The size of the access
     * @throws AtomicException if validation fails
     */
    private static void checkSegment(MemorySegment segment, long offset, int size) {
        if (segment == null) {
            throw new AtomicException("MemorySegment cannot be null");
        }
        if (offset < 0) {
            throw new AtomicException("Offset cannot be negative: " + offset);
        }
        if (offset + size > segment.byteSize()) {
            throw new AtomicException(
                "Access out of bounds: offset=" + offset + ", size=" + size +
                ", segmentSize=" + segment.byteSize());
        }
    }

    /**
     * Gets the VarHandle for byte access.
     * @return The byte VarHandle
     */
    public static VarHandle byteHandle() {
        return BYTE_HANDLE;
    }

    /**
     * Gets the VarHandle for int access.
     * @return The int VarHandle
     */
    public static VarHandle intHandle() {
        return INT_HANDLE;
    }

    /**
     * Gets the VarHandle for long access.
     * @return The long VarHandle
     */
    public static VarHandle longHandle() {
        return LONG_HANDLE;
    }

    /**
     * Gets the VarHandle for float access.
     * @return The float VarHandle
     */
    public static VarHandle floatHandle() {
        return FLOAT_HANDLE;
    }

    /**
     * Gets the VarHandle for double access.
     * @return The double VarHandle
     */
    public static VarHandle doubleHandle() {
        return DOUBLE_HANDLE;
    }
}
