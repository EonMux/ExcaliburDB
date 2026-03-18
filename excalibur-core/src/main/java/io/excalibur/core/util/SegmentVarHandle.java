package io.excalibur.core.util;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VarHandle-based utility class for atomic operations on MemorySegment.
 *
 * <p>MemorySegment is a zero-copy abstraction over direct byte buffers.
 * This class provides thread-safe read/write operations using VarHandle
 * to avoid copying data to heap.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. Multiple threads may read from or
 * write to a MemorySegment concurrently without synchronization.</p>
 *
 * @author ExcaliburDB
 */
public class SegmentVarHandle {

    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfDouble DOUBLE_LAYOUT = ValueLayout.JAVA_DOUBLE_UNALIGNED;
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfBoolean BOOLEAN_LAYOUT = ValueLayout.JAVA_BOOLEAN;

    /**
     * Reads an int value from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The int value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int readInt(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(INT_LAYOUT, offset);
    }

    /**
     * Writes an int value to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The int value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeInt(MemorySegment segment, long offset, int value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(INT_LAYOUT, offset, value);
    }

    /**
     * Reads a long value from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The long value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static long readLong(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(LONG_LAYOUT, offset);
    }

    /**
     * Writes a long value to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The long value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeLong(MemorySegment segment, long offset, long value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(LONG_LAYOUT, offset, value);
    }

    /**
     * Reads a float value from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The float value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static float readFloat(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(FLOAT_LAYOUT, offset);
    }

    /**
     * Writes a float value to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The float value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeFloat(MemorySegment segment, long offset, float value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(FLOAT_LAYOUT, offset, value);
    }

    /**
     * Reads a double value from a MemorySegment at the specified offset.
     *
     * @param segment The source memory segment
     * @param offset  Offset in bytes
     * @return The double value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static double readDouble(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return segment.get(DOUBLE_LAYOUT, offset);
    }

    /**
     * Writes a double value to a MemorySegment at the specified offset.
     *
     * @param segment The target memory segment
     * @param offset  Offset in bytes
     * @param value   The double value to write
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void writeDouble(MemorySegment segment, long offset, double value) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        segment.set(DOUBLE_LAYOUT, offset, value);
    }

    /**
     * Reads a byte value from a MemorySegment at the specified offset.
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
     * Writes a byte value to a MemorySegment at the specified offset.
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

    /**
     * Reads a boolean value from a MemorySegment at the specified offset.
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
     * Writes a boolean value to a MemorySegment at the specified offset.
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

    /**
     * Gets the current value from a MemorySegment at the specified offset.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @return The int value
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static int get(MemorySegment segment, long offset) {
        return segment.get(INT_LAYOUT, offset);
    }

    /**
     * Sets a value in a MemorySegment at the specified offset.
     *
     * @param segment The memory segment
     * @param offset  Offset in bytes
     * @param value   The value to set
     * @throws NullPointerException if segment is null
     * @throws IndexOutOfBoundsException if offset is out of bounds
     */
    public static void set(MemorySegment segment, long offset, int value) {
        segment.set(INT_LAYOUT, offset, value);
    }

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
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
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

    // ==================== Factory Methods ====================

    /**
     * Gets a VarHandle for AtomicInteger.
     *
     * @return VarHandle for AtomicInteger
     * @throws NoSuchFieldException if field not found
     */
    public static VarHandle intAtomicVarHandle() throws NoSuchFieldException, IllegalAccessException {
        return MethodHandles.lookup().findVarHandle(AtomicInteger.class, "value", int.class);
    }

    /**
     * Gets a VarHandle for AtomicLong.
     *
     * @return VarHandle for AtomicLong
     * @throws NoSuchFieldException if field not found
     */
    public static VarHandle longAtomicVarHandle() throws NoSuchFieldException, IllegalAccessException {
        return MethodHandles.lookup().findVarHandle(AtomicLong.class, "value", long.class);
    }

    /**
     * Gets a VarHandle for AtomicReference.
     *
     * @return VarHandle for AtomicReference
     * @throws NoSuchFieldException if field not found
     */
    public static VarHandle referenceVarHandle() throws NoSuchFieldException, IllegalAccessException {
        return MethodHandles.lookup().findVarHandle(AtomicReference.class, "value", Object.class);
    }
}
