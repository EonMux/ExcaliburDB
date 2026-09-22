package com.eonmux.excalibur.graph.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streaming writer for one column file.
 *
 * <p>Streams rather than maps. A column is written once, front to back, and never re-read during
 * the build, so mapping it would reserve address space for a file the builder has no reason to
 * address randomly — and for a store larger than memory, mapping every column for write is how a
 * build starts thrashing. A single reused buffer keeps the whole build at constant memory
 * regardless of edge count.
 *
 * <p>Writes in {@link EdgeStoreDescriptor#COLUMN_BYTE_ORDER}, the one place the format's byte order
 * is applied on the write side.
 */
public final class ColumnFileWriter implements AutoCloseable {

    /**
     * Bytes buffered before a write reaches the channel. Large enough that per-element cost is
     * amortised, small enough to stay out of the way of the page cache doing the real work.
     */
    private static final int BUFFER_BYTES = 1 << 20;

    private final FileChannel channel;
    private final ByteBuffer buffer;
    private final Path path;
    private long elementsWritten;

    private ColumnFileWriter(FileChannel channel, Path path) {
        this.channel = channel;
        this.path = path;
        this.buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            .order(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
    }

    /** Creates or truncates the file for {@code column} in {@code directory}. */
    public static ColumnFileWriter create(Path directory, EdgeColumn column) throws IOException {
        Path path = directory.resolve(column.fileName());
        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        return new ColumnFileWriter(channel, path);
    }

    /** Appends a 4-byte element. */
    public void putInt(int value) throws IOException {
        ensure(Integer.BYTES);
        buffer.putInt(value);
        elementsWritten++;
    }

    /** Appends an 8-byte element. */
    public void putLong(long value) throws IOException {
        ensure(Long.BYTES);
        buffer.putLong(value);
        elementsWritten++;
    }

    /** Appends a 2-byte element. */
    public void putShort(short value) throws IOException {
        ensure(Short.BYTES);
        buffer.putShort(value);
        elementsWritten++;
    }

    /** Appends a 1-byte element. */
    public void putByte(byte value) throws IOException {
        ensure(Byte.BYTES);
        buffer.put(value);
        elementsWritten++;
    }

    /** How many elements have been appended. */
    public long elementsWritten() {
        return elementsWritten;
    }

    private void ensure(int bytes) throws IOException {
        if (buffer.remaining() < bytes) {
            flush();
        }
    }

    private void flush() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    /**
     * Flushes and closes.
     *
     * <p>Does not force to disk, because whether a column is durable is not this writer's
     * decision to make: the ordering that matters is every column before the descriptor, and only
     * the build that writes both knows when that point is. {@code EdgeStoreWriter.build} forces
     * them there, and {@code EdgeStoreCompactor} forces the whole replacement tree before it
     * swaps. Forcing here as well would buy nothing and turn a streaming write into a fsync per
     * buffer's worth of column.
     */
    @Override
    public void close() throws IOException {
        try (FileChannel ch = channel) {
            flush();
        }
    }

    /** The file being written, for diagnostics. */
    public Path path() {
        return path;
    }
}
