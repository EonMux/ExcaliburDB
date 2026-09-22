package com.eonmux.excalibur.backup.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Makes a staged file or directory authoritative, in the order that makes it survive.
 *
 * <p>The same sequence the rest of this codebase publishes with: write beside the
 * destination, force the contents, move the name over atomically, force the directory the
 * name now appears in. The order is the whole point. A rename that reaches the device ahead
 * of the bytes it points at names a file of zeros, and a directory entry that is never
 * forced can be lost while the file it names survives - both failures leave something that
 * parses, which is worse than something that does not.</p>
 *
 * @author ExcaliburDB
 */
public final class AtomicPublish {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtomicPublish.class);

    private AtomicPublish() {
    }

    /**
     * Forces one file's contents to the device.
     *
     * @param file The file, which must exist
     * @throws IOException if it cannot be opened or forced
     */
    public static void syncFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /**
     * Forces one directory's own entries to the device.
     *
     * <p>A path that is not a directory is an error. A directory the platform refuses to
     * open as a channel - Windows does - is reported and accepted, because the alternative
     * is a tool that cannot write a backup there at all.</p>
     *
     * @param directory The directory
     * @throws IOException if the path is not a directory
     */
    public static void syncDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new IOException("Not a directory, so it cannot be forced: " + directory);
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not force the directory entries of {}: {}. Names created in it "
                + "may not survive a crash of the host.", directory, e.toString());
        }
    }

    /**
     * Forces the directory that holds a file, making the file's name durable.
     *
     * @param file A file whose name must survive
     * @throws IOException if the parent is not a directory
     */
    public static void syncContainingDirectory(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            syncDirectory(parent);
        }
    }

    /**
     * Forces every file beneath a root, then every directory in it.
     *
     * <p>Files before directories, so that by the time a directory entry is durable the file
     * it names is durable too.</p>
     *
     * @param root The tree
     * @throws IOException if the tree cannot be walked, or a file cannot be forced
     */
    public static void syncTree(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        List<Path> entries;
        try (var walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.comparing(Files::isDirectory)).toList();
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                syncDirectory(entry);
            } else {
                syncFile(entry);
            }
        }
    }

    /**
     * Replaces {@code target} with the staged file: contents, then the name.
     *
     * @param staged The fully written replacement, which is consumed
     * @param target Where it belongs; replaced if it exists
     * @throws IOException if the force or the rename fails
     */
    public static void publish(Path staged, Path target) throws IOException {
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(target, "target");
        syncFile(staged);
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        syncContainingDirectory(target);
    }

    /**
     * Moves a directory onto a name atomically, forcing its contents first.
     *
     * <p>The target must not exist; whoever calls this is responsible for having moved
     * anything that was there out of the way, so that the swap itself is one rename and the
     * target is never a blend of the two trees.</p>
     *
     * @param staged The fully written tree, in the same parent as the target
     * @param target The name it should take
     * @throws IOException if the force or the rename fails
     */
    public static void publishTree(Path staged, Path target) throws IOException {
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(target, "target");
        syncTree(staged);
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        syncContainingDirectory(target);
    }

    /**
     * Deletes a tree, deepest entry first.
     *
     * @param root The tree; nothing happens if it is absent
     * @throws IOException if anything in it cannot be deleted
     */
    public static void deleteTree(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        if (!Files.exists(root)) {
            return;
        }
        List<Path> entries;
        try (var walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }
}
