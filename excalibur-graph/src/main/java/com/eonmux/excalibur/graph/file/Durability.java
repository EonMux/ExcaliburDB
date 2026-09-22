package com.eonmux.excalibur.graph.file;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/**
 * Forces written bytes and directory entries out of the page cache.
 *
 * <h2>When this is worth its cost</h2>
 * <p>Most of what this system writes does not need it. A build assembles a new directory and moves
 * it into place, and a build whose bytes never reached the platter is simply a build that has to be
 * run again from the staging log that outlived it. Erasure is the exception: the operations that
 * discharge an erasure obligation destroy the only other copy of what they replaced, so a
 * replacement still sitting in the page cache when the power fails leaves nothing at all - a
 * directory of short or empty files that will not open, with no original to fall back to.
 *
 * <h2>Directories as well as files</h2>
 * <p>Syncing a file does not make the name it was created under durable; that lives in the parent
 * directory, and on a crash a fully written file can come back with no directory entry pointing at
 * it. Anything that renames a file into place therefore syncs the directory the name appears in.
 *
 * @author ExcaliburDB
 */
public interface Durability {

    /** Forces bytes and metadata to the device. */
    Durability DEVICE = new Durability() {
    };

    /**
     * Forces one file's contents to the device.
     *
     * @param file The file, which must exist
     * @throws IOException if it cannot be opened or synced
     */
    default void fsyncFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /**
     * Forces one directory's entries to the device.
     *
     * <p>Opening a directory for reading and forcing it is the portable-enough spelling on the
     * platforms this runs on. A filesystem that refuses to open a directory as a channel reports
     * that rather than being worked around, because silently skipping the sync would leave the
     * caller believing a rename is durable when it is not.
     *
     * @param directory The directory
     * @throws IOException if it cannot be opened or synced
     */
    default void fsyncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /**
     * Forces every regular file beneath {@code root}, then {@code root} and its subdirectories.
     *
     * <p>Files before directories, so that by the time a directory entry is durable the file it
     * names is durable too.
     *
     * @param root The tree
     * @throws IOException if any file or directory cannot be synced
     */
    default void fsyncTree(Path root) throws IOException {
        List<Path> entries;
        try (var walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.comparing(Files::isDirectory)).toList();
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                fsyncDirectory(entry);
            } else {
                fsyncFile(entry);
            }
        }
    }

    /**
     * Replaces {@code target} with {@code temp} durably: the new bytes, then the rename.
     *
     * <p>The rename is atomic, so a reader sees one file or the other and never a blend. Syncing
     * the contents first is what makes the choice a real one - a rename that reached the disk ahead
     * of the bytes it points at names a file of zeros.
     *
     * @param temp   The fully written replacement
     * @param target Where it belongs
     * @throws IOException if the sync or the rename fails
     */
    default void replaceDurably(Path temp, Path target) throws IOException {
        fsyncFile(temp);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Path parent = target.getParent();
        if (parent != null) {
            fsyncDirectory(parent);
        }
    }
}
