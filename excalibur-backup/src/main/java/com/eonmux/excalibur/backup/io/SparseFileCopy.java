package com.eonmux.excalibur.backup.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Copies a file without filling in the holes it was not storing.
 *
 * <h2>Why this is not {@code Files.copy}</h2>
 * <p>This engine pre-allocates address space and fills it lazily. A write-ahead log is
 * mapped at 64 MiB and never re-mapped; an HNSW graph is mapped at three quarters of a
 * gigabyte and holds a handful of nodes. On a real node the difference is not marginal: a
 * data directory measured for this work reported fifty gigabytes apparent and fifty
 * megabytes allocated. {@code Files.copy} reads the zeros and writes them, so the naive
 * backup of a fifty-megabyte node is a fifty-gigabyte file - large enough that nobody takes
 * it often enough for it to be worth having.</p>
 *
 * <h2>How the hole is preserved</h2>
 * <p>The destination is given its full length before anything is written to it, which on
 * every filesystem this runs on produces a file that is entirely hole. Only blocks that
 * contain a non-zero byte are then written, at their own offsets. The result is
 * byte-identical to the source as a sequence of bytes - which is what a checksum, a reader
 * and a memory mapping all see - while occupying only what the source occupied.</p>
 *
 * <p>A block of real zeros in the source becomes a hole in the copy. That is not a
 * difference: a hole and a written zero are the same byte to everything above the
 * filesystem.</p>
 *
 * @author ExcaliburDB
 */
public final class SparseFileCopy {

    /** Granularity at which holes are detected. A multiple of every page size in use. */
    private static final int BLOCK_BYTES = 1 << 20;

    private SparseFileCopy() {
    }

    /**
     * Copies {@code source} to {@code destination}, creating parent directories as needed.
     *
     * <p>The destination's contents are forced to the device before this returns, so the
     * caller may rename it into place immediately. The name itself is not forced; that
     * belongs to whoever creates it, and is {@link AtomicPublish}'s job.</p>
     *
     * @param source      The file to read
     * @param destination Where to write it; replaced if it exists
     * @return The number of logical bytes the destination now holds
     * @throws IOException          if either file cannot be opened, read or written
     * @throws NullPointerException if either path is null
     */
    public static long copy(Path source, Path destination) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long size = Files.size(source);
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             RandomAccessFile out = new RandomAccessFile(destination.toFile(), "rw")) {
            out.setLength(0L);
            out.setLength(size);
            copyNonEmptyBlocks(in, out.getChannel(), size);
            out.getChannel().force(true);
        }
        return size;
    }

    /**
     * Writes only those blocks of the source that hold a non-zero byte.
     *
     * @param in    The source, positioned at zero
     * @param out   The destination, already at its full length
     * @param size  How many bytes to copy
     * @throws IOException if a read or a write fails
     */
    private static void copyNonEmptyBlocks(FileChannel in, FileChannel out, long size)
        throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
        long offset = 0L;
        while (offset < size) {
            buffer.clear();
            int read = readFully(in, buffer, offset);
            if (read <= 0) {
                throw new IOException("The source ended at " + offset + " of " + size
                    + " bytes; it is shorter than it said it was");
            }
            buffer.flip();
            if (!isAllZero(buffer)) {
                writeFully(out, buffer, offset);
            }
            offset += read;
        }
    }

    /**
     * Fills the buffer from the channel at an absolute offset, short reads included.
     *
     * @param channel The channel
     * @param buffer  The buffer to fill
     * @param offset  Absolute offset to read from
     * @return How many bytes were read, or -1 at end of file before any byte
     * @throws IOException if the read fails
     */
    private static int readFully(FileChannel channel, ByteBuffer buffer, long offset)
        throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == 0 ? -1 : total;
    }

    /**
     * Drains the buffer to the channel at an absolute offset, short writes included.
     *
     * @param channel The channel
     * @param buffer  The buffer to drain
     * @param offset  Absolute offset to write at
     * @throws IOException if the write fails
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long offset)
        throws IOException {
        int written = 0;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer, offset + written);
        }
    }

    /**
     * Reports whether every byte between the buffer's position and limit is zero.
     *
     * <p>Read eight bytes at a time where alignment allows, because this runs over every
     * byte of every file in the backup and the byte-at-a-time spelling is the whole cost.</p>
     *
     * @param buffer The buffer, flipped
     * @return true if the block holds nothing but zeros
     */
    private static boolean isAllZero(ByteBuffer buffer) {
        int limit = buffer.limit();
        int index = buffer.position();
        int longEnd = index + ((limit - index) & ~7);
        for (; index < longEnd; index += Long.BYTES) {
            if (buffer.getLong(index) != 0L) {
                return false;
            }
        }
        for (; index < limit; index++) {
            if (buffer.get(index) != 0) {
                return false;
            }
        }
        return true;
    }
}
