package io.excalibur.core.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Compatibility interface for memory segment operations.
 * This provides a common API that can be implemented by either
 * Panama FFI MemorySegment or fallback implementations.
 */
public interface MemorySegment {

    /**
     * Maps a file into memory.
     *
     * @param path The path to the file
     * @param size The size of the mapping
     * @return A memory segment backed by the file
     * @throws IOException if mapping fails
     */
    static MemorySegment mapFile(Path path, long size) throws IOException {
        // Use FallbackMemorySegment by default
        return FallbackMemorySegment.mapFile(path, size);
    }

    /**
     * Returns the size of this memory segment in bytes.
     *
     * @return The size in bytes
     */
    long byteSize();

    /**
     * Reads bytes from the specified offset.
     *
     * @param offset The starting offset
     * @param length The number of bytes to read
     * @return The bytes read
     */
    byte[] read(int offset, int length);

    /**
     * Returns a slice of this memory segment.
     *
     * @param offset The starting offset
     * @param length The length of the slice
     * @return A new memory segment representing the slice
     */
    MemorySegment asSlice(long offset, long length);

    /**
     * Writes bytes to the specified offset.
     *
     * @param data The bytes to write
     * @param offset The starting offset
     * @param length The number of bytes to write
     */
    void put(byte[] data, int offset, int length);

    /**
     * Writes a ByteBuffer's contents to this segment.
     *
     * @param buffer The buffer to write
     */
    void put(ByteBuffer buffer);

    /**
     * Returns a FileChannel for this segment.
     *
     * @return The file channel
     */
    FileChannel toChannel();
}
