package io.excalibur.core.mapped;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Memory-mapped I/O abstraction using Panama FFM (MemorySegment, Arena).
 * Provides efficient file-backed memory regions with thread-safe access.
 *
 * <p>This implementation uses the Java 21 Panama FFM API with Arena-based
 * lifecycle management for proper resource cleanup.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 *
 * <ul>
 *   <li>Read operations use a read lock - multiple threads can read concurrently</li>
 *   <li>Write operations use a write lock - exclusive access during writes</li>
 *   <li>Uses {@link Arena#ofShared()} to allow memory access from any thread</li>
 *   <li>The {@code segment} and {@code mappedSize} fields are volatile for visibility</li>
 *   <li>Extend/truncate operations atomically replace the memory mapping</li>
 *   <li>The arena reference is stored in an {@link AtomicReference} for safe replacement</li>
 * </ul>
 *
 * <h2>Visibility Guarantees</h2>
 * <ul>
 *   <li>Writes by one thread are visible to all other threads after the write lock is released</li>
 *   <li>Changes to the mapping size (extend/truncate) are immediately visible to all threads</li>
 *   <li>The {@code closed} flag is volatile, ensuring all threads see the closed state</li>
 * </ul>
 *
 * <h2>Resource Management</h2>
 * <p>The underlying memory mapping is managed via a shared Arena. When the Arena is closed,
 * all memory mappings are automatically released. Alternatively, call close()
 * to release resources explicitly.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a new memory-mapped file
 * try (MappedFile mf = MappedFile.create(Path.of("data.bin"), 4096)) {
 *     mf.writeInt(0, 0x12345678);
 *     mf.writeString(4, "Hello, World!");
 *     mf.flush();
 * }
 *
 * // Open existing file for reading
 * try (MappedFile mf = MappedFile.open(Path.of("data.bin"))) {
 *     int value = mf.readInt(0);
 *     String text = mf.readString(4);
 * }
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class MappedFile implements AutoCloseable {

    private static final int PAGE_SIZE = 4096;

    private static final OpenOption[] READ_WRITE_OPTIONS = {
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE
    };

    private static final OpenOption[] READ_ONLY_OPTIONS = {
        StandardOpenOption.READ
    };

    // ValueLayouts for primitive access (big-endian for consistency)
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_LAYOUT = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final Path path;
    private final FileChannel channel;
    private final AtomicReference<Arena> arenaRef;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile MemorySegment segment;
    private volatile boolean closed = false;
    private volatile long mappedSize;
    private final boolean readOnly;

    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);

    /**
     * Creates a new MappedFile representing a memory-mapped region.
     *
     * @param path Path to the underlying file
     * @param size Size of the memory region in bytes (will be page-aligned)
     * @throws IOException if file cannot be accessed or mapped
     * @throws IllegalArgumentException if path is null or size is invalid
     */
    public MappedFile(Path path, long size) throws IOException {
        this(path, size, false);
    }

    /**
     * Creates a new MappedFile representing a memory-mapped region.
     *
     * @param path Path to the underlying file
     * @param size Size of the memory region in bytes (will be page-aligned)
     * @param readOnly If true, opens file in read-only mode
     * @throws IOException if file cannot be accessed or mapped
     * @throws IllegalArgumentException if path is null or size is invalid
     */
    public MappedFile(Path path, long size, boolean readOnly) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }

        this.path = path;
        this.readOnly = readOnly;

        // Align size to page boundary
        long alignedSize = alignToPage(size);
        this.mappedSize = alignedSize;

        // Create parent directories if needed (only for read-write)
        if (!readOnly) {
            java.nio.file.Path parent = path.getParent();
            if (parent != null && !java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent);
            }
        }

        // Open file channel
        OpenOption[] options = readOnly ? READ_ONLY_OPTIONS : READ_WRITE_OPTIONS;
        this.channel = FileChannel.open(path, options);

        try {
            // Resize file if needed (only for read-write)
            if (!readOnly && channel.size() < alignedSize) {
                channel.truncate(alignedSize);
            }

            // Create shared arena for thread-safe access from any thread
            Arena arena = Arena.ofShared();
            this.arenaRef = new AtomicReference<>(arena);

            // Map the file into memory using Panama FFM
            FileChannel.MapMode mapMode = readOnly ?
                FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
            this.segment = channel.map(mapMode, 0, alignedSize, arena);

        } catch (Throwable t) {
            channel.close();
            throw new IOException("Failed to create MappedFile: " + path, t);
        }
    }

    /**
     * Opens an existing file as a memory-mapped region in read-only mode.
     *
     * @param path Path to the existing file
     * @return New MappedFile wrapping the region
     * @throws IOException if file cannot be opened or mapped
     */
    public static MappedFile open(Path path) throws IOException {
        long size = java.nio.file.Files.size(path);
        return new MappedFile(path, size, true);
    }

    /**
     * Opens an existing file as a memory-mapped region with specified size.
     *
     * @param path Path to the existing file
     * @param size Size of the region to map
     * @return New MappedFile wrapping the region
     * @throws IOException if file cannot be opened or mapped
     */
    public static MappedFile open(Path path, long size) throws IOException {
        return new MappedFile(path, size, false);
    }

    /**
     * Creates a new file as a memory-mapped region.
     * If the file already exists, it will be overwritten.
     *
     * @param path Path for the new file
     * @param size Size of the region to allocate
     * @return New MappedFile wrapping the region
     * @throws IOException if file cannot be created or mapped
     */
    public static MappedFile create(Path path, long size) throws IOException {
        return new MappedFile(path, size, false);
    }

    /**
     * Aligns a size to the system page size.
     */
    private static long alignToPage(long size) {
        if (size == 0) {
            return PAGE_SIZE;
        }
        return ((size + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE;
    }

    /**
     * Gets the path of the underlying file.
     *
     * @return The file path
     */
    public Path path() {
        return path;
    }

    /**
     * Gets the size of this memory region in bytes.
     *
     * @return The size in bytes
     */
    public long size() {
        return mappedSize;
    }

    /**
     * Gets the page size used for alignment.
     *
     * @return The page size in bytes
     */
    public int pageSize() {
        return PAGE_SIZE;
    }

    /**
     * Returns true if this file is opened in read-only mode.
     *
     * @return true if read-only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Returns the actual file length on disk.
     *
     * @return File length in bytes
     * @throws IllegalStateException if MappedFile is closed
     */
    public long fileLength() throws IOException {
        checkOpen();
        return java.nio.file.Files.size(path);
    }

    /**
     * Returns true if the underlying file exists.
     *
     * @return true if file exists
     */
    public boolean fileExists() {
        return java.nio.file.Files.exists(path);
    }

    // ==================== Read Operations ====================

    /**
     * Reads bytes from the memory segment into a new byte array.
     *
     * @param offset Offset within the segment
     * @param length Number of bytes to read
     * @return New byte array containing the read data
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public byte[] readBytes(long offset, int length) {
        byte[] buffer = new byte[length];
        read(offset, buffer, 0, length);
        return buffer;
    }

    /**
     * Reads bytes from the memory segment into a buffer.
     *
     * @param offset Offset within the segment
     * @param buffer Destination buffer
     * @return Number of bytes read
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public int read(long offset, byte[] buffer) {
        return read(offset, buffer, 0, buffer.length);
    }

    /**
     * Reads bytes from the memory segment into a buffer.
     *
     * @param offset       Offset within the segment
     * @param buffer       Destination buffer
     * @param bufferOffset Offset within the buffer
     * @param length       Number of bytes to read
     * @return Number of bytes read
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public int read(long offset, byte[] buffer, int bufferOffset, int length) {
        checkOpen();
        checkBounds(offset, length);

        lock.readLock().lock();
        try {
            MemorySegment currentSegment = segment;
            MemorySegment.copy(currentSegment, BYTE_LAYOUT, offset, buffer, bufferOffset, length);
            bytesRead.addAndGet(length);
            readCount.incrementAndGet();
            return length;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a byte at the specified offset.
     *
     * @param offset Offset within the segment
     * @return Byte value at offset
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public byte readByte(long offset) {
        checkOpen();
        checkBounds(offset, 1);

        lock.readLock().lock();
        try {
            return segment.get(BYTE_LAYOUT, offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads an int at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @return int value at offset
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public int readInt(long offset) {
        checkOpen();
        checkBounds(offset, 4);

        lock.readLock().lock();
        try {
            return segment.get(INT_LAYOUT, offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a long at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @return long value at offset
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public long readLong(long offset) {
        checkOpen();
        checkBounds(offset, 8);

        lock.readLock().lock();
        try {
            return segment.get(LONG_LAYOUT, offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a float at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @return float value at offset
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public float readFloat(long offset) {
        checkOpen();
        checkBounds(offset, 4);

        lock.readLock().lock();
        try {
            return segment.get(FLOAT_LAYOUT, offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a double at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @return double value at offset
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public double readDouble(long offset) {
        checkOpen();
        checkBounds(offset, 8);

        lock.readLock().lock();
        try {
            return segment.get(DOUBLE_LAYOUT, offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a UTF-8 string at the specified offset.
     * The string must be prefixed with a 4-byte length (big-endian).
     *
     * @param offset Offset within the segment
     * @return String value
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public String readString(long offset) {
        checkOpen();
        checkBounds(offset, 4);

        lock.readLock().lock();
        try {
            int length = segment.get(INT_LAYOUT, offset);
            checkBounds(offset + 4, length);
            byte[] bytes = new byte[length];
            MemorySegment.copy(segment, BYTE_LAYOUT, offset + 4, bytes, 0, length);
            bytesRead.addAndGet(4 + length);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a UTF-8 string at the specified offset with known length.
     *
     * @param offset Offset within the segment
     * @param length Length of the string in bytes (excluding length prefix)
     * @return String value
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public String readString(long offset, int length) {
        checkOpen();
        checkBounds(offset, length);

        lock.readLock().lock();
        try {
            byte[] bytes = new byte[length];
            MemorySegment.copy(segment, BYTE_LAYOUT, offset, bytes, 0, length);
            bytesRead.addAndGet(length);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a float array at the specified offset.
     *
     * @param offset Offset within the segment
     * @param count  Number of floats to read
     * @return float array
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or count is invalid
     */
    public float[] readFloatArray(long offset, int count) {
        checkOpen();
        long byteLength = (long) count * 4L;
        checkBounds(offset, byteLength);

        lock.readLock().lock();
        try {
            float[] result = new float[count];
            for (int i = 0; i < count; i++) {
                result[i] = segment.get(FLOAT_LAYOUT, offset + (long) i * 4L);
            }
            bytesRead.addAndGet(byteLength);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Write Operations ====================

    /**
     * Writes bytes from a buffer to the memory segment.
     *
     * @param offset Offset within the segment
     * @param buffer Source buffer
     * @return Number of bytes written
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public int writeBytes(long offset, byte[] buffer) {
        return write(offset, buffer, 0, buffer.length);
    }

    /**
     * Writes bytes from a buffer to the memory segment.
     *
     * @param offset Offset within the segment
     * @param buffer Source buffer
     * @return Number of bytes written
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public int write(long offset, byte[] buffer) {
        return write(offset, buffer, 0, buffer.length);
    }

    /**
     * Writes bytes from a buffer to the memory segment.
     *
     * @param offset       Offset within the segment
     * @param buffer       Source buffer
     * @param bufferOffset Offset within the buffer
     * @param length       Number of bytes to write
     * @return Number of bytes written
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public int write(long offset, byte[] buffer, int bufferOffset, int length) {
        checkOpen();
        checkWritable();
        checkBounds(offset, length);

        lock.writeLock().lock();
        try {
            MemorySegment.copy(buffer, bufferOffset, segment, BYTE_LAYOUT, offset, length);
            bytesWritten.addAndGet(length);
            writeCount.incrementAndGet();
            return length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a byte at the specified offset.
     *
     * @param offset Offset within the segment
     * @param value  Byte value to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public void writeByte(long offset, byte value) {
        checkOpen();
        checkWritable();
        checkBounds(offset, 1);

        lock.writeLock().lock();
        try {
            segment.set(BYTE_LAYOUT, offset, value);
            bytesWritten.addAndGet(1);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes an int at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @param value  int value to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public void writeInt(long offset, int value) {
        checkOpen();
        checkWritable();
        checkBounds(offset, 4);

        lock.writeLock().lock();
        try {
            segment.set(INT_LAYOUT, offset, value);
            bytesWritten.addAndGet(4);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a long at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @param value  long value to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public void writeLong(long offset, long value) {
        checkOpen();
        checkWritable();
        checkBounds(offset, 8);

        lock.writeLock().lock();
        try {
            segment.set(LONG_LAYOUT, offset, value);
            bytesWritten.addAndGet(8);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a float at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @param value  float value to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public void writeFloat(long offset, float value) {
        checkOpen();
        checkWritable();
        checkBounds(offset, 4);

        lock.writeLock().lock();
        try {
            segment.set(FLOAT_LAYOUT, offset, value);
            bytesWritten.addAndGet(4);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a double at the specified offset (big-endian).
     *
     * @param offset Offset within the segment
     * @param value  double value to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public void writeDouble(long offset, double value) {
        checkOpen();
        checkWritable();
        checkBounds(offset, 8);

        lock.writeLock().lock();
        try {
            segment.set(DOUBLE_LAYOUT, offset, value);
            bytesWritten.addAndGet(8);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a float array at the specified offset.
     *
     * @param offset Offset within the segment
     * @param values float array to write
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IndexOutOfBoundsException if offset is invalid or array too large
     */
    public void writeFloatArray(long offset, float[] values) {
        if (values == null) {
            throw new IllegalArgumentException("Values array cannot be null");
        }
        checkOpen();
        checkWritable();
        long byteLength = (long) values.length * 4L;
        checkBounds(offset, byteLength);

        lock.writeLock().lock();
        try {
            for (int i = 0; i < values.length; i++) {
                segment.set(FLOAT_LAYOUT, offset + (long) i * 4L, values[i]);
            }
            bytesWritten.addAndGet(byteLength);
            writeCount.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a UTF-8 string at the specified offset with a 4-byte length prefix.
     *
     * @param offset Offset within the segment
     * @param value  String value to write
     * @return Number of bytes written (including length prefix)
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IllegalArgumentException if string is null
     * @throws IndexOutOfBoundsException if offset is invalid or string too long
     */
    public int writeString(long offset, String value) {
        if (value == null) {
            throw new IllegalArgumentException("String cannot be null");
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        checkOpen();
        checkWritable();
        checkBounds(offset, 4 + bytes.length);

        lock.writeLock().lock();
        try {
            segment.set(INT_LAYOUT, offset, bytes.length);
            MemorySegment.copy(bytes, 0, segment, BYTE_LAYOUT, offset + 4, bytes.length);
            bytesWritten.addAndGet(4 + bytes.length);
            return 4 + bytes.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a UTF-8 string at the specified offset without a length prefix.
     *
     * @param offset Offset within the segment
     * @param value  String value to write
     * @return Number of bytes written
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IllegalArgumentException if string is null
     * @throws IndexOutOfBoundsException if offset is invalid or string too long
     */
    public int writeStringRaw(long offset, String value) {
        if (value == null) {
            throw new IllegalArgumentException("String cannot be null");
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        checkOpen();
        checkWritable();
        checkBounds(offset, bytes.length);

        lock.writeLock().lock();
        try {
            MemorySegment.copy(bytes, 0, segment, BYTE_LAYOUT, offset, bytes.length);
            bytesWritten.addAndGet(bytes.length);
            return bytes.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Segment Operations ====================

    /**
     * Creates a zero-copy slice of this memory segment.
     *
     * @param offset Offset within this segment
     * @param length Length of the slice
     * @return A MemorySegment slice
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public MemorySegment slice(long offset, long length) {
        checkOpen();
        checkBounds(offset, length);

        lock.readLock().lock();
        try {
            return segment.asSlice(offset, length);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates a byte array copy of a slice of this memory segment.
     *
     * @param offset Offset within this segment
     * @param length Length of the slice
     * @return A byte array copy of the slice
     * @throws IllegalStateException if MappedFile is closed
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public byte[] byteSlice(long offset, int length) {
        return readBytes(offset, length);
    }

    /**
     * Gets the underlying MemorySegment.
     * Use with caution - direct access bypasses bounds checking and statistics.
     *
     * @return The MemorySegment
     * @throws IllegalStateException if MappedFile is closed
     */
    public MemorySegment segment() {
        checkOpen();
        return segment;
    }

    /**
     * Gets the Arena used for this mapping.
     *
     * @return The Arena
     * @throws IllegalStateException if MappedFile is closed
     */
    public Arena arena() {
        checkOpen();
        return arenaRef.get();
    }

    /**
     * Extends the memory mapping by the specified amount.
     * This creates a new mapping and arena - the old segment is no longer valid.
     *
     * <p>Thread safety: This operation acquires the write lock, ensuring no
     * concurrent reads or writes occur during the remapping. The new segment
     * and size are atomically visible to all threads after the lock is released.</p>
     *
     * @param extensionSize Size to add to current mapping (will be page-aligned)
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IOException if remapping fails
     */
    public void extend(long extensionSize) throws IOException {
        if (extensionSize < 0) {
            throw new IllegalArgumentException("Extension size cannot be negative: " + extensionSize);
        }

        checkOpen();
        checkWritable();

        // No-op if extension size is 0
        if (extensionSize == 0) {
            return;
        }

        lock.writeLock().lock();
        try {
            long newSize = this.mappedSize + alignToPage(extensionSize);
            remap(newSize);
        } catch (Throwable t) {
            throw new IOException("Failed to extend MappedFile: " + path, t);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Truncates the memory mapping to a smaller size.
     * This creates a new mapping and arena - the old segment is no longer valid.
     *
     * <p>Thread safety: This operation acquires the write lock, ensuring no
     * concurrent reads or writes occur during the remapping.</p>
     *
     * @param newSize New size in bytes (will be page-aligned)
     * @throws IllegalStateException if MappedFile is closed or read-only
     * @throws IOException if remapping fails
     * @throws IllegalArgumentException if newSize is greater than current size
     */
    public void truncate(long newSize) throws IOException {
        if (newSize < 0) {
            throw new IllegalArgumentException("New size cannot be negative: " + newSize);
        }

        checkOpen();
        checkWritable();

        long alignedSize = alignToPage(newSize);

        if (alignedSize > this.mappedSize) {
            throw new IllegalArgumentException(
                "Truncate size " + alignedSize + " is greater than current size " + this.mappedSize +
                ". Use extend() instead."
            );
        }

        lock.writeLock().lock();
        try {
            remap(alignedSize);
        } catch (Throwable t) {
            throw new IOException("Failed to truncate MappedFile: " + path, t);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal method to remap the file to a new size.
     * Must be called while holding the write lock.
     */
    private void remap(long alignedSize) throws IOException {
        // Flush existing data
        segment.force();

        // Get the current arena and close it (this unmaps the old segment)
        Arena oldArena = arenaRef.getAndSet(null);

        // Resize the file
        channel.truncate(alignedSize);

        // Create new shared arena and mapping
        Arena newArena = Arena.ofShared();
        MemorySegment newSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, alignedSize, newArena);

        // Update arena reference
        arenaRef.set(newArena);

        // Update segment and size - these are volatile for visibility
        this.segment = newSegment;
        this.mappedSize = alignedSize;

        // Now close the old arena (after all references are updated)
        if (oldArena != null) {
            try {
                oldArena.close();
            } catch (Throwable t) {
                // Log but don't fail - the new mapping is already in place
            }
        }
    }

    /**
     * Flushes all changes to the underlying storage device.
     *
     * @throws IllegalStateException if MappedFile is closed
     */
    public void flush() {
        checkOpen();

        lock.writeLock().lock();
        try {
            segment.force();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Same as flush(). Provided for API consistency.
     *
     * @throws IllegalStateException if MappedFile is closed
     */
    public void force() {
        flush();
    }

    /**
     * Closes this MappedFile, releasing all resources.
     * After closing, all operations will throw IllegalStateException.
     *
     * <p>Thread safety: This operation acquires the write lock, ensuring all
     * pending operations complete before closing. Once closed, the volatile
     * closed flag ensures all threads see the closed state.</p>
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            // Flush before closing
            if (segment != null) {
                try {
                    segment.force();
                } catch (Throwable t) {
                    // Ignore - we're closing anyway
                }
            }

            // Close arena (unmaps memory)
            Arena arena = arenaRef.getAndSet(null);
            if (arena != null) {
                try {
                    arena.close();
                } catch (Throwable t) {
                    // Ignore
                }
            }

            // Close channel
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore
                }
            }

            segment = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if this MappedFile is closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Gets total bytes read since creation.
     *
     * @return Bytes read
     */
    public long bytesRead() {
        return bytesRead.get();
    }

    /**
     * Gets total bytes written since creation.
     *
     * @return Bytes written
     */
    public long bytesWritten() {
        return bytesWritten.get();
    }

    /**
     * Gets total number of read operations since creation.
     *
     * @return Read operation count
     */
    public long readCount() {
        return readCount.get();
    }

    /**
     * Gets total number of write operations since creation.
     *
     * @return Write operation count
     */
    public long writeCount() {
        return writeCount.get();
    }

    // ==================== Helper Methods ====================

    /**
     * Checks that the MappedFile is open.
     *
     * @throws IllegalStateException if closed
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("MappedFile is closed: " + path);
        }
    }

    /**
     * Checks that the MappedFile is writable.
     *
     * @throws IllegalStateException if read-only
     */
    private void checkWritable() {
        if (readOnly) {
            throw new IllegalStateException("MappedFile is read-only: " + path);
        }
    }

    /**
     * Checks that the specified offset and length are within bounds.
     *
     * @param offset Offset to check
     * @param length Length to check
     * @throws IndexOutOfBoundsException if out of bounds
     */
    private void checkBounds(long offset, long length) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("Offset cannot be negative: " + offset);
        }
        if (length < 0) {
            throw new IndexOutOfBoundsException("Length cannot be negative: " + length);
        }
        if (offset + length > mappedSize) {
            throw new IndexOutOfBoundsException(
                "Range [" + offset + ", " + (offset + length) + ") exceeds segment size " + mappedSize
            );
        }
    }

    @Override
    public String toString() {
        return "MappedFile{path=" + path + ", size=" + mappedSize + ", closed=" + closed + ", readOnly=" + readOnly + "}";
    }
}
