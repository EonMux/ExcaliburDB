package io.excalibur.core.memory;

import io.excalibur.core.codec.Codec;
import io.excalibur.core.util.CRC32;
import io.excalibur.core.util.SegmentVarHandle;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Memory-mapped file implementation.
 * 
 * MappedFile provides a high-performance interface for reading and writing
 * data directly from disk using memory-mapped I/O. It supports:
 * - Opening and creating files
 * - Extending the mapped region (append mode)
 * - Zero-copy read/write operations
 * - Crash-safe operations via write log support
 * 
 * <h2>Thread Safety</h2>
 * <p>Read operations are thread-safe. Write operations are NOT thread-safe
 * for concurrent access - use a write lock when writing to the same region.</p>
 * 
 * <h2>Panama FFI Support</h2>
 * <p>This implementation uses Panama FFI's MemorySegment when available.
 * If the library is not present, it falls back to FallbackMemorySegment.</p>
 * 
 * @author ExcaliburDB
 */
public class MappedFile implements Closeable {
    
    /**
     * The underlying memory segment.
     */
    private final MemorySegment segment;
    
    /**
     * The path to the mapped file.
     */
    private final Path path;
    
    /**
     * The file channel.
     */
    private final FileChannel channel;
    
    /**
     * The total size of the mapped file.
     */
    private final long size;
    
    /**
     * The current offset within the mapped region.
     */
    private final AtomicLong offset = new AtomicLong(0);
    
    /**
     * Flag indicating if the file is closed.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Read-write lock for synchronized access to the mapped region.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    
    /**
     * Flag indicating if a write lock is currently held.
     */
    private final AtomicBoolean writing = new AtomicBoolean(false);
    
    /**
     * The codec factory for encoding/decoding data.
     */
    private final CodecFactory codecFactory;
    
    /**
     * Creates a new MappedFile.
     * 
     * @param path The path to the file
     * @param size The size of the mapped region
     * @param codecFactory The codec factory to use
     */
    public MappedFile(Path path, long size, CodecFactory codecFactory) {
        this.path = path;
        this.size = size;
        this.codecFactory = codecFactory;
        
        this.segment = getMemorySegment(path, size);
        this.channel = segment.toChannel();
    }
    
    /**
     * Opens an existing mapped file.
     * 
     * @param path The path to the file
     * @return The mapped file
     * @throws IOException if the file cannot be opened
     */
    public static MappedFile open(Path path) throws IOException {
        long size = path.toFile().length();
        return new MappedFile(path, size, CodecFactory.INSTANCE);
    }
    
    /**
     * Creates a new mapped file.
     * 
     * @param path The path to the file
     * @param size The initial size of the mapped region
     * @return The mapped file
     * @throws IOException if the file cannot be created
     */
    public static MappedFile create(Path path, long size) throws IOException {
        return new MappedFile(path, size, CodecFactory.INSTANCE);
    }
    
    /**
     * Creates a new mapped file with the specified codec factory.
     * 
     * @param path The path to the file
     * @param size The initial size of the mapped region
     * @param codecFactory The codec factory to use
     * @return The mapped file
     * @throws IOException if the file cannot be created
     */
    public static MappedFile create(Path path, long size, CodecFactory codecFactory) throws IOException {
        return new MappedFile(path, size, codecFactory);
    }
    
    /**
     * Gets the MemorySegment implementation for the given path and size.
     * 
     * @param path The path to the file
     * @param size The size of the mapped region
     * @return The MemorySegment implementation
     * @throws IOException if the mapping fails
     */
    private static MemorySegment getMemorySegment(Path path, long size) throws IOException {
        try {
            // Try Panama FFI first
            return MemorySegment.mapFile(path, size);
        } catch (NoSuchMethodError | java.lang.UnsupportedOperationException e) {
            // Fall back to FallbackMemorySegment
            return FallbackMemorySegment.mapFile(path, size);
        } catch (Exception e) {
            throw new IOException("Failed to create memory segment", e);
        }
    }
    
    /**
     * Extends the mapped region by appending new data.
     * This operation must be atomic and crash-safe.
     * 
     * @param length The number of bytes to append
     * @throws IOException if the extension fails
     */
    public void extend(long length) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (length <= 0) {
            return;
        }
        
        // Extend the file on disk
        try (FileChannel ch = FileChannel.open(path, java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.size(size + length);
        }
        
        // Extend the memory mapping
        ByteBuffer extendBuffer = ByteBuffer.allocate((int) length);
        segment.put(extendBuffer);
        offset.addAndGet(length);
    }
    
    /**
     * Reads a byte from the current offset.
     * 
     * @return The byte at the current offset
     * @throws IllegalStateException if closed or offset is invalid
     */
    public byte readByte() {
        return read(1);
    }
    
    /**
     * Reads bytes from the current offset.
     * 
     * @param length The number of bytes to read
     * @return The bytes read
     * @throws IllegalStateException if closed or offset is invalid
     */
    public byte[] read(int length) {
        return read(length, offset.get());
    }
    
    /**
     * Reads bytes from a specific offset.
     * 
     * @param length The number of bytes to read
     * @param offset The starting offset
     * @return The bytes read
     * @throws IllegalStateException if closed or offset is invalid
     */
    public byte[] read(int length, int offset) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        return segment.read(offset, length);
    }
    
    /**
     * Reads bytes from a specific offset using the codec factory.
     * 
     * @param length The number of bytes to read
     * @param offset The starting offset
     * @return The bytes read
     * @throws IllegalStateException if closed or offset is invalid
     */
    public <T> T readCodec(int length, int offset) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        byte[] bytes = read(length, offset);
        return codecFactory.create().decode(new java.io.ByteArrayInputStream(bytes));
    }
    
    /**
     * Reads a single int from the current offset.
     * 
     * @return The int value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public int readInt() {
        return readInt(4);
    }
    
    /**
     * Reads a single int from the current offset.
     * 
     * @param length The byte length of the int
     * @return The int value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public int readInt(int length) {
        return readInt(length, offset.get());
    }
    
    /**
     * Reads a single int from a specific offset.
     * 
     * @param offset The offset to read from
     * @param length The byte length of the int
     * @return The int value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public int readInt(int offset, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        return ByteBuffer.wrap(read(length, offset))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }
    
    /**
     * Reads a single long from the current offset.
     * 
     * @return The long value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public long readLong() {
        return readLong(8);
    }
    
    /**
     * Reads a single long from the current offset.
     * 
     * @param length The byte length of the long
     * @return The long value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public long readLong(int length) {
        return readLong(length, offset.get());
    }
    
    /**
     * Reads a single long from a specific offset.
     * 
     * @param offset The offset to read from
     * @param length The byte length of the long
     * @return The long value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public long readLong(int offset, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        return ByteBuffer.wrap(read(length, offset))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
    }
    
    /**
     * Reads a single float from the current offset.
     * 
     * @return The float value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public float readFloat() {
        return readFloat(4);
    }
    
    /**
     * Reads a single float from the current offset.
     * 
     * @param length The byte length of the float
     * @return The float value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public float readFloat(int length) {
        return readFloat(length, offset.get());
    }
    
    /**
     * Reads a single float from a specific offset.
     * 
     * @param offset The offset to read from
     * @param length The byte length of the float
     * @return The float value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public float readFloat(int offset, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        return ByteBuffer.wrap(read(length, offset))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getFloat();
    }
    
    /**
     * Reads a single double from the current offset.
     * 
     * @return The double value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public double readDouble() {
        return readDouble(8);
    }
    
    /**
     * Reads a single double from the current offset.
     * 
     * @param length The byte length of the double
     * @return The double value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public double readDouble(int length) {
        return readDouble(length, offset.get());
    }
    
    /**
     * Reads a single double from a specific offset.
     * 
     * @param offset The offset to read from
     * @param length The byte length of the double
     * @return The double value
     * @throws IllegalStateException if closed or offset is invalid
     */
    public double readDouble(int offset, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        return ByteBuffer.wrap(read(length, offset))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getDouble();
    }
    
    /**
     * Writes bytes to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param bytes The bytes to write
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void write(byte[] bytes) {
        write(bytes, offset.get());
    }
    
    /**
     * Writes bytes to a specific offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param bytes The bytes to write
     * @param pos The starting offset
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void write(byte[] bytes, int pos) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (pos < 0 || pos + bytes.length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + pos + " with length " + bytes.length + " exceeds size " + size);
        }
        
        segment.put(bytes, pos, bytes.length);
        offset.addAndGet(bytes.length);
    }
    
    /**
     * Writes a byte to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The byte to write
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeByte(byte value) {
        write(new byte[]{value});
    }
    
    /**
     * Writes an int to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The int to write
     * @param length The byte length of the int
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeInt(int value, int length) {
        writeInt(value, offset.get(), length);
    }
    
    /**
     * Writes an int to a specific offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The int to write
     * @param pos The starting offset
     * @param length The byte length of the int
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeInt(int value, int pos, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (pos < 0 || pos + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + pos + " with length " + length + " exceeds size " + size);
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        segment.put(buffer.array(), pos, length);
        offset.addAndGet(length);
    }
    
    /**
     * Writes a long to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The long to write
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeLong(long value) {
        writeLong(value, 8);
    }
    
    /**
     * Writes a long to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The long to write
     * @param length The byte length of the long
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeLong(long value, int length) {
        writeLong(value, offset.get(), length);
    }
    
    /**
     * Writes a long to a specific offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The long to write
     * @param pos The starting offset
     * @param length The byte length of the long
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeLong(long value, int pos, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (pos < 0 || pos + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + pos + " with length " + length + " exceeds size " + size);
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        segment.put(buffer.array(), pos, length);
        offset.addAndGet(length);
    }
    
    /**
     * Writes a float to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The float to write
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeFloat(float value) {
        writeFloat(value, 4);
    }
    
    /**
     * Writes a float to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The float to write
     * @param length The byte length of the float
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeFloat(float value, int length) {
        writeFloat(value, offset.get(), length);
    }
    
    /**
     * Writes a float to a specific offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The float to write
     * @param pos The starting offset
     * @param length The byte length of the float
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeFloat(float value, int pos, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (pos < 0 || pos + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + pos + " with length " + length + " exceeds size " + size);
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(value);
        segment.put(buffer.array(), pos, length);
        offset.addAndGet(length);
    }
    
    /**
     * Writes a double to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The double to write
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeDouble(double value) {
        writeDouble(value, 8);
    }
    
    /**
     * Writes a double to the current offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The double to write
     * @param length The byte length of the double
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeDouble(double value, int length) {
        writeDouble(value, offset.get(), length);
    }
    
    /**
     * Writes a double to a specific offset.
     * This operation is NOT thread-safe - acquire a write lock first.
     * 
     * @param value The double to write
     * @param pos The starting offset
     * @param length The byte length of the double
     * @throws IllegalStateException if closed or offset is invalid
     */
    public void writeDouble(double value, int pos, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (pos < 0 || pos + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + pos + " with length " + length + " exceeds size " + size);
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(value);
        segment.put(buffer.array(), pos, length);
        offset.addAndGet(length);
    }
    
    /**
     * Flushes any buffered data to disk.
     * 
     * @throws IOException if flushing fails
     */
    public void flush() throws IOException {
        channel.force(true);
    }
    
    /**
     * Syncs all data to disk, including dirty mappings.
     * 
     * @throws IOException if syncing fails
     */
    public void sync() throws IOException {
        channel.force(true, true);
    }
    
    /**
     * Returns the current offset within the mapped region.
     * 
     * @return The current offset
     */
    public long offset() {
        return offset.get();
    }
    
    /**
     * Returns the size of the mapped file.
     * 
     * @return The size in bytes
     */
    public long size() {
        return size;
    }
    
    /**
     * Returns the path to the mapped file.
     * 
     * @return The path
     */
    public Path path() {
        return path;
    }
    
    /**
     * Returns the underlying memory segment.
     * 
     * @return The memory segment
     */
    public MemorySegment segment() {
        return segment;
    }
    
    /**
     * Returns the underlying file channel.
     * 
     * @return The file channel
     */
    public FileChannel channel() {
        return channel;
    }
    
    /**
     * Checks if this file is closed.
     * 
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed.get();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channel.close();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }
    
    /**
     * Returns a copy of the entire mapped file as a byte array.
     * 
     * @return The complete file contents
     * @throws IllegalStateException if closed
     */
    public byte[] toByteArray() {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        byte[] result = new byte[(int) size];
        segment.put(result);
        return result;
    }
    
    /**
     * Returns a copy of a portion of the mapped file as a byte array.
     * 
     * @param offset The starting offset
     * @param length The number of bytes to copy
     * @return The copied bytes
     * @throws IllegalStateException if closed or offset is invalid
     */
    public byte[] copyRange(int offset, int length) {
        if (closed.get()) {
            throw new IllegalStateException("MappedFile is closed");
        }
        if (offset < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "Offset " + offset + " with length " + length + " exceeds size " + size);
        }
        
        byte[] result = new byte[length];
        segment.put(result, offset, length);
        return result;
    }
}
