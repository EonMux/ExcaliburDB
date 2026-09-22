package com.eonmux.excalibur.core.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ByteBuffer-based fallback implementation of Segment for environments without Panama FFM.
 *
 * <p>This implementation provides the same API as PanamaSegment but uses standard Java NIO
 * ByteBuffer operations. It serves as a fallback when Panama FFM is unavailable due to:</p>
 * <ul>
 *   <li>Running on Java versions prior to 21</li>
 *   <li>Security manager restrictions on native memory access</li>
 *   <li>Explicit disable via system property</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are synchronized for thread-safety. For optimal performance with
 * concurrent access, external synchronization should be used instead of relying on
 * the built-in synchronization.</p>
 *
 * <h2>Performance</h2>
 * <p>This implementation is slower than Panama FFM due to ByteBuffer overhead and
 * synchronization. Use PanamaSegment when available for production workloads.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class FallbackSegment implements Segment {

    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackSegment.class);

    private final Path path;
    private final ByteBuffer buffer;
    private final FileChannel channel;
    private final long size;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Creates a FallbackSegment from a file mapping.
     *
     * @param path The path to the file
     * @param size The size of the mapped region
     * @return A new FallbackSegment
     * @throws IOException if mapping fails
     */
    public static FallbackSegment mapFile(Path path, long size) throws IOException {
        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE);
        try {
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
            buffer.order(ByteOrder.BIG_ENDIAN);
            return new FallbackSegment(path, buffer, channel, size);
        } catch (Throwable t) {
            // Cleanup must never replace the real cause of the mapping failure.
            IOException failure = new IOException("Failed to map file: " + path, t);
            try {
                channel.close();
            } catch (Throwable ct) {
                failure.addSuppressed(ct);
            }
            throw failure;
        }
    }

    private FallbackSegment(Path path, ByteBuffer buffer, FileChannel channel, long size) {
        this.path = path;
        this.buffer = buffer;
        this.channel = channel;
        this.size = size;
    }

    /**
     * Creates a FallbackSegment from a ByteBuffer (for slices).
     */
    FallbackSegment(ByteBuffer buffer, long size) {
        this.path = null;
        this.buffer = buffer;
        this.channel = null;
        this.size = size;
    }

    @Override
    public long byteSize() {
        return size;
    }

    @Override
    public Segment asSlice(long offset, long length) {
        checkOpen();
        checkBounds(offset, length);
        ByteBuffer slice = buffer.slice((int) offset, (int) length);
        slice.order(ByteOrder.BIG_ENDIAN);
        return new FallbackSegment(slice, length);
    }

    // ==================== Byte Operations ====================

    @Override
    public byte getByte(long offset) {
        checkOpen();
        checkBounds(offset, 1);
        synchronized (this) {
            return buffer.get((int) offset);
        }
    }

    @Override
    public void setByte(long offset, byte value) {
        checkOpen();
        checkBounds(offset, 1);
        synchronized (this) {
            buffer.put((int) offset, value);
        }
    }

    @Override
    public byte[] getBytes(long offset, int length) {
        checkOpen();
        checkBounds(offset, length);
        byte[] result = new byte[length];
        synchronized (this) {
            int oldPos = buffer.position();
            buffer.position((int) offset);
            buffer.get(result, 0, length);
            buffer.position(oldPos);
        }
        return result;
    }

    @Override
    public void setBytes(long offset, byte[] src, int srcOffset, int length) {
        checkOpen();
        checkBounds(offset, length);
        synchronized (this) {
            int oldPos = buffer.position();
            buffer.position((int) offset);
            buffer.put(src, srcOffset, length);
            buffer.position(oldPos);
        }
    }

    // ==================== Int Operations ====================

    @Override
    public int getInt(long offset) {
        checkOpen();
        checkBounds(offset, 4);
        synchronized (this) {
            return buffer.getInt((int) offset);
        }
    }

    @Override
    public void setInt(long offset, int value) {
        checkOpen();
        checkBounds(offset, 4);
        synchronized (this) {
            buffer.putInt((int) offset, value);
        }
    }

    // ==================== Long Operations ====================

    @Override
    public long getLong(long offset) {
        checkOpen();
        checkBounds(offset, 8);
        synchronized (this) {
            return buffer.getLong((int) offset);
        }
    }

    @Override
    public void setLong(long offset, long value) {
        checkOpen();
        checkBounds(offset, 8);
        synchronized (this) {
            buffer.putLong((int) offset, value);
        }
    }

    // ==================== Float Operations ====================

    @Override
    public float getFloat(long offset) {
        checkOpen();
        checkBounds(offset, 4);
        synchronized (this) {
            return buffer.getFloat((int) offset);
        }
    }

    @Override
    public void setFloat(long offset, float value) {
        checkOpen();
        checkBounds(offset, 4);
        synchronized (this) {
            buffer.putFloat((int) offset, value);
        }
    }

    // ==================== Double Operations ====================

    @Override
    public double getDouble(long offset) {
        checkOpen();
        checkBounds(offset, 8);
        synchronized (this) {
            return buffer.getDouble((int) offset);
        }
    }

    @Override
    public void setDouble(long offset, double value) {
        checkOpen();
        checkBounds(offset, 8);
        synchronized (this) {
            buffer.putDouble((int) offset, value);
        }
    }

    // ==================== Copy Operations ====================

    @Override
    public void copyTo(long srcOffset, byte[] dst, int dstOffset, int length) {
        checkOpen();
        checkBounds(srcOffset, length);
        synchronized (this) {
            int oldPos = buffer.position();
            buffer.position((int) srcOffset);
            buffer.get(dst, dstOffset, length);
            buffer.position(oldPos);
        }
    }

    @Override
    public void copyFrom(byte[] src, int srcOffset, long dstOffset, int length) {
        checkOpen();
        checkBounds(dstOffset, length);
        synchronized (this) {
            int oldPos = buffer.position();
            buffer.position((int) dstOffset);
            buffer.put(src, srcOffset, length);
            buffer.position(oldPos);
        }
    }

    @Override
    public void copyTo(long srcOffset, Segment dst, long dstOffset, long length) {
        checkOpen();
        checkBounds(srcOffset, length);
        byte[] tempBuffer = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
        copyTo(srcOffset, tempBuffer, 0, tempBuffer.length);
        dst.setBytes(dstOffset, tempBuffer, 0, tempBuffer.length);
    }

    @Override
    public void copyFrom(Segment src, long srcOffset, long dstOffset, long length) {
        checkOpen();
        checkBounds(dstOffset, length);
        byte[] tempBuffer = src.getBytes(srcOffset, (int) Math.min(length, Integer.MAX_VALUE));
        setBytes(dstOffset, tempBuffer, 0, tempBuffer.length);
    }

    // ==================== ByteBuffer Operations ====================

    @Override
    public void read(long offset, ByteBuffer dstBuffer, int length) {
        checkOpen();
        checkBounds(offset, length);
        synchronized (this) {
            buffer.position((int) offset);
            byte[] data = new byte[length];
            buffer.get(data, 0, length);
            dstBuffer.put(data, 0, length);
        }
    }

    @Override
    public void write(long offset, ByteBuffer srcBuffer) {
        checkOpen();
        int length = srcBuffer.remaining();
        checkBounds(offset, length);
        synchronized (this) {
            buffer.position((int) offset);
            byte[] data = new byte[length];
            srcBuffer.get(data);
            buffer.put(data);
        }
    }

    // ==================== Flush and Persistence ====================

    @Override
    public void force() {
        checkOpen();
        synchronized (this) {
            if (buffer instanceof MappedByteBuffer) {
                ((MappedByteBuffer) buffer).force();
            }
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

        if (buffer instanceof MappedByteBuffer mapped) {
            try {
                mapped.force();
            } catch (Throwable t) {
                // Last durability point for this mapping - never fail silently.
                LOGGER.error("Final flush (msync) failed while closing segment {}"
                    + " - data written through this mapping may not be durable", path, t);
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

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== Legacy Compatibility ====================

    /**
     * Returns the underlying ByteBuffer.
     *
     * @return The ByteBuffer
     */
    public ByteBuffer buffer() {
        return buffer;
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
        if (offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Range [" + offset + ", " + (offset + length) + ") exceeds segment size " + size);
        }
    }

    @Override
    public String toString() {
        return "FallbackSegment{size=" + (closed.get() ? "closed" : size) +
               ", path=" + path + "}";
    }
}
