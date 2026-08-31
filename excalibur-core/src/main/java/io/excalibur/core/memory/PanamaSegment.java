package io.excalibur.core.memory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panama FFM-based implementation of the Segment interface.
 *
 * <p>This implementation wraps a native {@link java.lang.foreign.MemorySegment}
 * and provides all segment operations using the Panama Foreign Function and
 * Memory API available in Java 21+.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All read operations are thread-safe. Write operations require external
 * synchronization. The underlying Panama MemorySegment provides atomic
 * access guarantees for individual primitive operations.</p>
 *
 * <h2>Performance</h2>
 * <p>This implementation provides optimal performance through direct native
 * memory access without JNI overhead. VarHandle operations provide atomic
 * access where supported.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class PanamaSegment implements Segment {

    private static final Logger LOGGER = LoggerFactory.getLogger(PanamaSegment.class);

    // ValueLayouts for primitive access (big-endian for consistency)
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_LAYOUT = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final MemorySegment nativeSegment;
    private final Arena arena;
    private final FileChannel channel;
    private final Path path;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Creates a PanamaSegment wrapping an existing native MemorySegment.
     *
     * @param nativeSegment The native MemorySegment to wrap
     * @param arena The Arena managing the segment lifecycle (may be null for slices)
     * @param channel The FileChannel (may be null for non-file segments)
     * @param path The file path (may be null for non-file segments)
     */
    private PanamaSegment(MemorySegment nativeSegment, Arena arena, FileChannel channel, Path path) {
        this.nativeSegment = Objects.requireNonNull(nativeSegment, "Native segment cannot be null");
        this.arena = arena;
        this.channel = channel;
        this.path = path;
    }

    /**
     * Maps a file into memory using Panama FFM.
     *
     * @param path The path to the file
     * @param size The size of the mapping
     * @return A new PanamaSegment backed by the file
     * @throws IOException if mapping fails
     */
    static PanamaSegment mapFile(Path path, long size) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");

        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE);
        Arena arena = null;
        try {
            arena = Arena.ofShared();
            MemorySegment nativeSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
            return new PanamaSegment(nativeSegment, arena, channel, path);
        } catch (Throwable t) {
            // Cleanup must never replace the real cause of the mapping failure.
            IOException failure = new IOException("Failed to map file: " + path, t);
            closeSuppressing(arena, failure);
            closeSuppressing(channel, failure);
            throw failure;
        }
    }

    /**
     * Wraps an existing native MemorySegment without Arena or channel.
     *
     * @param nativeSegment The native MemorySegment to wrap
     * @return A new PanamaSegment wrapping the native segment
     */
    static PanamaSegment wrap(MemorySegment nativeSegment) {
        Objects.requireNonNull(nativeSegment, "Native segment cannot be null");
        return new PanamaSegment(nativeSegment, null, null, null);
    }

    /**
     * Wraps an existing native MemorySegment with an Arena for lifecycle management.
     *
     * @param nativeSegment The native MemorySegment to wrap
     * @param arena The Arena managing the segment lifecycle
     * @return A new PanamaSegment wrapping the native segment
     */
    static PanamaSegment wrapWithArena(MemorySegment nativeSegment, Arena arena) {
        Objects.requireNonNull(nativeSegment, "Native segment cannot be null");
        return new PanamaSegment(nativeSegment, arena, null, null);
    }

    @Override
    public long byteSize() {
        checkOpen();
        return nativeSegment.byteSize();
    }

    @Override
    public Segment asSlice(long offset, long length) {
        checkOpen();
        checkBounds(offset, length);
        return wrap(nativeSegment.asSlice(offset, length));
    }

    // ==================== Byte Operations ====================

    @Override
    public byte getByte(long offset) {
        checkOpen();
        checkBounds(offset, 1);
        return nativeSegment.get(BYTE_LAYOUT, offset);
    }

    @Override
    public void setByte(long offset, byte value) {
        checkOpen();
        checkBounds(offset, 1);
        nativeSegment.set(BYTE_LAYOUT, offset, value);
    }

    @Override
    public byte[] getBytes(long offset, int length) {
        checkOpen();
        checkBounds(offset, length);
        byte[] result = new byte[length];
        MemorySegment.copy(nativeSegment, BYTE_LAYOUT, offset, result, 0, length);
        return result;
    }

    @Override
    public void setBytes(long offset, byte[] src, int srcOffset, int length) {
        checkOpen();
        checkBounds(offset, length);
        MemorySegment.copy(src, srcOffset, nativeSegment, BYTE_LAYOUT, offset, length);
    }

    // ==================== Int Operations ====================

    @Override
    public int getInt(long offset) {
        checkOpen();
        checkBounds(offset, 4);
        return nativeSegment.get(INT_LAYOUT, offset);
    }

    @Override
    public void setInt(long offset, int value) {
        checkOpen();
        checkBounds(offset, 4);
        nativeSegment.set(INT_LAYOUT, offset, value);
    }

    // ==================== Long Operations ====================

    @Override
    public long getLong(long offset) {
        checkOpen();
        checkBounds(offset, 8);
        return nativeSegment.get(LONG_LAYOUT, offset);
    }

    @Override
    public void setLong(long offset, long value) {
        checkOpen();
        checkBounds(offset, 8);
        nativeSegment.set(LONG_LAYOUT, offset, value);
    }

    // ==================== Float Operations ====================

    @Override
    public float getFloat(long offset) {
        checkOpen();
        checkBounds(offset, 4);
        return nativeSegment.get(FLOAT_LAYOUT, offset);
    }

    @Override
    public void setFloat(long offset, float value) {
        checkOpen();
        checkBounds(offset, 4);
        nativeSegment.set(FLOAT_LAYOUT, offset, value);
    }

    // ==================== Double Operations ====================

    @Override
    public double getDouble(long offset) {
        checkOpen();
        checkBounds(offset, 8);
        return nativeSegment.get(DOUBLE_LAYOUT, offset);
    }

    @Override
    public void setDouble(long offset, double value) {
        checkOpen();
        checkBounds(offset, 8);
        nativeSegment.set(DOUBLE_LAYOUT, offset, value);
    }

    // ==================== Copy Operations ====================

    @Override
    public void copyTo(long srcOffset, byte[] dst, int dstOffset, int length) {
        checkOpen();
        checkBounds(srcOffset, length);
        MemorySegment.copy(nativeSegment, BYTE_LAYOUT, srcOffset, dst, dstOffset, length);
    }

    @Override
    public void copyFrom(byte[] src, int srcOffset, long dstOffset, int length) {
        checkOpen();
        checkBounds(dstOffset, length);
        MemorySegment.copy(src, srcOffset, nativeSegment, BYTE_LAYOUT, dstOffset, length);
    }

    @Override
    public void copyTo(long srcOffset, Segment dst, long dstOffset, long length) {
        checkOpen();
        checkBounds(srcOffset, length);

        if (dst instanceof PanamaSegment) {
            MemorySegment dstNative = ((PanamaSegment) dst).nativeSegment;
            MemorySegment.copy(nativeSegment, BYTE_LAYOUT, srcOffset, dstNative, BYTE_LAYOUT, dstOffset, length);
        } else {
            // Fallback for mixed implementations
            byte[] buffer = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
            MemorySegment.copy(nativeSegment, BYTE_LAYOUT, srcOffset, buffer, 0, buffer.length);
            dst.setBytes(dstOffset, buffer, 0, buffer.length);
        }
    }

    @Override
    public void copyFrom(Segment src, long srcOffset, long dstOffset, long length) {
        checkOpen();
        checkBounds(dstOffset, length);

        if (src instanceof PanamaSegment) {
            MemorySegment srcNative = ((PanamaSegment) src).nativeSegment;
            MemorySegment.copy(srcNative, BYTE_LAYOUT, srcOffset, nativeSegment, BYTE_LAYOUT, dstOffset, length);
        } else {
            // Fallback for mixed implementations
            byte[] buffer = src.getBytes(srcOffset, (int) Math.min(length, Integer.MAX_VALUE));
            MemorySegment.copy(buffer, 0, nativeSegment, BYTE_LAYOUT, dstOffset, buffer.length);
        }
    }

    // ==================== ByteBuffer Operations ====================

    @Override
    public void read(long offset, ByteBuffer buffer, int length) {
        checkOpen();
        checkBounds(offset, length);
        byte[] data = new byte[length];
        MemorySegment.copy(nativeSegment, BYTE_LAYOUT, offset, data, 0, length);
        buffer.put(data, 0, length);
    }

    @Override
    public void write(long offset, ByteBuffer buffer) {
        checkOpen();
        int length = buffer.remaining();
        checkBounds(offset, length);
        byte[] data = new byte[length];
        buffer.get(data);
        MemorySegment.copy(data, 0, nativeSegment, BYTE_LAYOUT, offset, length);
    }

    // ==================== Flush and Persistence ====================

    @Override
    public void force() {
        checkOpen();
        // Only a mapped segment has anything to msync; MemorySegment.force() throws
        // UnsupportedOperationException for anything else. The ByteBuffer implementation makes
        // exactly this check and no-ops, so without it the two backends disagree on a method
        // the interface defines for both.
        if (nativeSegment.isMapped()) {
            nativeSegment.force();
        }
    }

    // ==================== File Channel Access ====================

    @Override
    public FileChannel toChannel() {
        return channel;
    }

    @Override
    public Path path() {
        return path;
    }

    // ==================== Lifecycle ====================

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already closed by another thread
        }

        // A segment with no file behind it has nothing to flush, and calling force() on one
        // threw into the catch below - so every correct close of an anonymous native segment
        // logged an error claiming data might not be durable, naming a null path.
        if (nativeSegment != null && nativeSegment.isMapped()) {
            try {
                nativeSegment.force();
            } catch (Throwable t) {
                // Last durability point for this mapping - never fail silently.
                LOGGER.error("Final flush (msync) failed while closing segment {}"
                    + " - data written through this mapping may not be durable", path, t);
            }
        }

        if (arena != null) {
            try {
                arena.close();
            } catch (Throwable t) {
                LOGGER.warn("Failed to close Arena for segment {}", path, t);
            }
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (Throwable t) {
                LOGGER.warn("Failed to close FileChannel for segment {}", path, t);
            }
        }
    }

    /**
     * Closes a resource during failure cleanup, attaching any failure as a suppressed
     * exception on the primary failure rather than replacing it.
     */
    private static void closeSuppressing(AutoCloseable resource, IOException primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable t) {
            primary.addSuppressed(t);
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== VarHandle Support ====================

    @Override
    public MemorySegment nativeSegment() {
        checkOpen();
        return nativeSegment;
    }

    @Override
    public boolean isPanama() {
        return true;
    }

    /**
     * Returns the ValueLayout for byte access.
     *
     * @return The byte ValueLayout
     */
    public static ValueLayout.OfByte byteLayout() {
        return BYTE_LAYOUT;
    }

    /**
     * Returns the ValueLayout for int access (big-endian).
     *
     * @return The int ValueLayout
     */
    public static ValueLayout.OfInt intLayout() {
        return INT_LAYOUT;
    }

    /**
     * Returns the ValueLayout for long access (big-endian).
     *
     * @return The long ValueLayout
     */
    public static ValueLayout.OfLong longLayout() {
        return LONG_LAYOUT;
    }

    /**
     * Returns the ValueLayout for float access (big-endian).
     *
     * @return The float ValueLayout
     */
    public static ValueLayout.OfFloat floatLayout() {
        return FLOAT_LAYOUT;
    }

    /**
     * Returns the ValueLayout for double access (big-endian).
     *
     * @return The double ValueLayout
     */
    public static ValueLayout.OfDouble doubleLayout() {
        return DOUBLE_LAYOUT;
    }

    // ==================== Helper Methods ====================

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Segment is closed");
        }
    }

    private void checkBounds(long offset, long length) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("Offset cannot be negative: " + offset);
        }
        if (length < 0) {
            throw new IndexOutOfBoundsException("Length cannot be negative: " + length);
        }
        if (offset + length > nativeSegment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Range [" + offset + ", " + (offset + length) + ") exceeds segment size " + nativeSegment.byteSize());
        }
    }

    @Override
    public String toString() {
        return "PanamaSegment{size=" + (closed.get() ? "closed" : nativeSegment.byteSize()) +
               ", path=" + path + "}";
    }
}
