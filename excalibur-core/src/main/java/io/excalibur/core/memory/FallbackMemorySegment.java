package io.excalibur.core.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Fallback implementation of MemorySegment for environments without Panama FFI.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. This implementation uses standard Java NIO.
 * For optimal performance with concurrent access, consider using a write lock.</p>
 *
 * @author ExcaliburDB
 */
public final class FallbackMemorySegment implements MemorySegment {

    private final Path path;
    private final ByteBuffer buffer;
    private final FileChannel channel;
    private final long size;

    /**
     * Creates a FallbackMemorySegment from a file mapping.
     *
     * @param path The path to the file
     * @param size The size of the mapped region
     * @return A new FallbackMemorySegment
     * @throws IOException if mapping fails
     */
    public static FallbackMemorySegment mapFile(Path path, long size) throws IOException {
        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE);
        ByteBuffer buffer = channel.map(MapMode.READ_WRITE, 0, size);
        return new FallbackMemorySegment(path, buffer, channel, size);
    }

    private FallbackMemorySegment(Path path, ByteBuffer buffer, FileChannel channel, long size) {
        this.path = path;
        this.buffer = buffer;
        this.channel = channel;
        this.size = size;
    }

    @Override
    public long byteSize() {
        return size;
    }

    @Override
    public byte[] read(int offset, int length) {
        byte[] result = new byte[length];
        synchronized (this) {
            buffer.position(offset);
            buffer.get(result, 0, length);
        }
        return result;
    }

    @Override
    public void put(byte[] data, int offset, int length) {
        synchronized (this) {
            buffer.position(offset);
            buffer.put(data, 0, length);
        }
    }

    @Override
    public void put(ByteBuffer src) {
        synchronized (this) {
            buffer.put(src);
        }
    }

    @Override
    public MemorySegment asSlice(long offset, long length) {
        ByteBuffer slice = buffer.slice((int) offset, (int) length);
        return new FallbackMemorySegment(path, slice, channel, length);
    }

    @Override
    public FileChannel toChannel() {
        return channel;
    }

    /**
     * Returns the underlying file path.
     *
     * @return The file path
     */
    public Path path() {
        return path;
    }

    /**
     * Returns the underlying ByteBuffer.
     *
     * @return The ByteBuffer
     */
    public ByteBuffer buffer() {
        return buffer;
    }

    @Override
    public String toString() {
        return "FallbackMemorySegment{path=" + path + ", size=" + size + "}";
    }
}
