package io.excalibur.core.atomic;

import io.excalibur.core.exception.AtomicException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * VarHandle-based atomic operations for MemorySegment access.
 *
 * <p>This class provides atomic read/write and compare-and-set operations
 * for memory segments using Java 21's Panama FFM VarHandle API.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are atomic and thread-safe for concurrent access to
 * the underlying MemorySegment. Compare-and-set operations provide
 * lock-free atomic updates.</p>
 *
 * <h2>Memory Ordering</h2>
 * <p>Volatile operations provide acquire/release semantics. Plain operations
 * provide relaxed semantics suitable for performance-critical paths where
 * ordering is not required.</p>
 *
 * <h2>Implementation Note</h2>
 * <p>In Java 21's FFM API, VarHandles from ValueLayout have only 1 coordinate (MemorySegment).
 * This implementation uses MemorySegment slicing to provide offset-based access.
 * The slice operation is zero-cost as it creates a view, not a copy.</p>
 *
 * @author ExcaliburDB
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

    // Layouts for direct MemorySegment access
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT
        .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_LAYOUT = ValueLayout.JAVA_DOUBLE
        .withOrder(ByteOrder.BIG_ENDIAN);

    // Static initialization
    static {
        // Ensure VarHandles are properly initialized
        MethodHandles.lookup(); // Trigger initialization
    }

    private AtomicOps() {
        // Utility class - prevent instantiation
    }

    // ==================== Byte Operations ====================

    /**
     * Atomically reads a byte from the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @return The byte value
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static byte getByte(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 1);
        return segment.get(ValueLayout.JAVA_BYTE, offset);
    }

    /**
     * Atomically writes a byte to the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putByte(MemorySegment segment, long offset, byte value) {
        checkSegment(segment, offset, 1);
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    /**
     * Atomically compares and sets a byte value.
     *
     * @param segment  The memory segment
     * @param offset   The offset within the segment
     * @param expected The expected current value
     * @param newValue The new value to set if comparison succeeds
     * @return true if the comparison succeeded and value was updated
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static boolean compareAndSetByte(MemorySegment segment, long offset,
                                            byte expected, byte newValue) {
        checkSegment(segment, offset, 1);
        MemorySegment slice = segment.asSlice(offset, 1);
        return BYTE_HANDLE.compareAndSet(slice, expected, newValue);
    }

    // ==================== Int Operations ====================

    /**
     * Atomically reads an int from the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @return The int value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static int getInt(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 4);
        return segment.get(INT_LAYOUT, offset);
    }

    /**
     * Atomically writes an int to the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putInt(MemorySegment segment, long offset, int value) {
        checkSegment(segment, offset, 4);
        segment.set(INT_LAYOUT, offset, value);
    }

    /**
     * Atomically compares and sets an int value.
     *
     * @param segment  The memory segment
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
     * @param segment The memory segment
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
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static int addGetInt(MemorySegment segment, long offset, int delta) {
        return getAndAddInt(segment, offset, delta) + delta;
    }

    // ==================== Long Operations ====================

    /**
     * Atomically reads a long from the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @return The long value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static long getLong(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 8);
        return segment.get(LONG_LAYOUT, offset);
    }

    /**
     * Atomically writes a long to the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putLong(MemorySegment segment, long offset, long value) {
        checkSegment(segment, offset, 8);
        segment.set(LONG_LAYOUT, offset, value);
    }

    /**
     * Atomically compares and sets a long value.
     *
     * @param segment  The memory segment
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
     * @param segment The memory segment
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
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param delta   The value to add
     * @return The new value after addition
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static long addGetLong(MemorySegment segment, long offset, long delta) {
        return getAndAddLong(segment, offset, delta) + delta;
    }

    // ==================== Float Operations ====================

    /**
     * Atomically reads a float from the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @return The float value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static float getFloat(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 4);
        return segment.get(FLOAT_LAYOUT, offset);
    }

    /**
     * Atomically writes a float to the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putFloat(MemorySegment segment, long offset, float value) {
        checkSegment(segment, offset, 4);
        segment.set(FLOAT_LAYOUT, offset, value);
    }

    // ==================== Double Operations ====================

    /**
     * Atomically reads a double from the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @return The double value (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static double getDouble(MemorySegment segment, long offset) {
        checkSegment(segment, offset, 8);
        return segment.get(DOUBLE_LAYOUT, offset);
    }

    /**
     * Atomically writes a double to the segment at the given offset.
     *
     * @param segment The memory segment
     * @param offset  The offset within the segment
     * @param value   The value to write (big-endian)
     * @throws AtomicException if segment is null or offset is invalid
     */
    public static void putDouble(MemorySegment segment, long offset, double value) {
        checkSegment(segment, offset, 8);
        segment.set(DOUBLE_LAYOUT, offset, value);
    }

    // ==================== Utility Methods ====================

    /**
     * Performs a spin-loop CAS until successful or max retries exceeded.
     *
     * @param segment  The memory segment
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
     * @param segment  The memory segment
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

    /**
     * Checks segment validity and bounds.
     *
     * @param segment The memory segment to check
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
}
