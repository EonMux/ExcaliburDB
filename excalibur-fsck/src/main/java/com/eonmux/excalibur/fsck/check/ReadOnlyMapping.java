package com.eonmux.excalibur.fsck.check;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A read-only mapping of a file, and the promise that checking cannot write through it.
 *
 * <p>Every one of the store's own open paths maps {@code READ_WRITE}, and several of them
 * write during the open: a segment with an empty slot table has its data-region header
 * stamped in, a catalog migrates a version 1 file in place, a write-ahead log is mapped at
 * its maximum size whether or not the file is that long. All of that is correct for a node
 * starting up and wrong for a tool whose entire claim is that it looked without touching.
 * Mapping {@code READ_ONLY} makes the promise enforceable by the kernel rather than by
 * review: a stray write through this mapping is an exception, not a silent modification.</p>
 */
public final class ReadOnlyMapping implements AutoCloseable {

    private final Arena arena;
    private final FileChannel channel;
    private final MemorySegment mapped;
    private final long size;

    /**
     * Maps a whole file for reading.
     *
     * @param file The file to map
     * @throws IOException if the file cannot be opened or mapped
     */
    private ReadOnlyMapping(Path file) throws IOException {
        this.arena = Arena.ofConfined();
        try {
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
        try {
            this.size = channel.size();
            this.mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
        } catch (IOException | RuntimeException e) {
            closeQuietly(e);
            throw e;
        }
    }

    /**
     * Maps a whole file for reading.
     *
     * @param file The file to map
     * @return The mapping, which the caller closes
     * @throws IOException if the file cannot be opened or mapped
     */
    public static ReadOnlyMapping of(Path file) throws IOException {
        return new ReadOnlyMapping(file);
    }

    /**
     * Returns the mapped bytes.
     *
     * @return A read-only memory segment covering the whole file
     */
    public MemorySegment segment() {
        return mapped;
    }

    /**
     * Returns the file's size in bytes.
     *
     * @return The size that was mapped
     */
    public long size() {
        return size;
    }

    /**
     * Releases the mapping and the descriptor behind it.
     */
    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new IllegalStateException("Could not release a read-only mapping", e);
        } finally {
            arena.close();
        }
    }

    /**
     * Releases what was acquired when construction is about to fail.
     *
     * @param failure The failure that will be reported, which collects any second one
     */
    private void closeQuietly(Exception failure) {
        try {
            channel.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        } finally {
            arena.close();
        }
    }
}
