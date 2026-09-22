package com.eonmux.excalibur.vector.durability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * The durability step a staged-then-renamed index file depends on.
 *
 * <p>Every persistent file this module writes - the graph, its document-mapping sidecar, the
 * quantiser codebook - is built under a temporary name and renamed into place, because a
 * reader must never see a half-written one. The rename is what makes the file authoritative,
 * so anything not yet on storage when it happens is content the file claims to have and does
 * not. That failure mode is invisible: the name is right, the header parses, and the values
 * read back are simply wrong.</p>
 *
 * <p>Forcing the staged contents is the writer's own job, on the channel it already holds.
 * What is easy to forget is the second half - the directory entry the rename creates is
 * metadata, and on a crash it can be lost while the file it names survives, or survive while
 * a file staged moments earlier does not. A swap that spans two files, as HNSW compaction
 * does, depends on that ordering being real rather than assumed, which is why it is stated
 * here once instead of at each call site.</p>
 */
public final class DurableFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableFiles.class);

    private DurableFiles() {
    }

    /**
     * Forces a directory's own entries to storage.
     *
     * <p>Call it after creating or renaming a file whose visibility the next startup has to be
     * able to rely on. On POSIX this is an fsync of the directory; where the platform refuses
     * to open a directory at all - Windows does - the call is reported and returns, because
     * the write it accompanies did succeed and failing it would make the index unwritable on
     * that platform rather than more durable on it.</p>
     *
     * @param directory The directory to force; ignored when null, which is what a path with
     *                  no parent yields
     */
    public static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not force the directory entry for {}: {}. A file renamed into "
                + "it may not survive a crash of the host.", directory, e.toString());
        }
    }

    /**
     * Forces the directory that holds {@code file}.
     *
     * @param file A file whose newly created or renamed name must be durable
     * @throws NullPointerException if file is null
     */
    public static void syncContainingDirectory(Path file) {
        Objects.requireNonNull(file, "file cannot be null");
        syncDirectory(file.toAbsolutePath().getParent());
    }
}
