package com.eonmux.excalibur.fsck.repair;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Moves a file out of the way without ever destroying it.
 *
 * <p>The whole point of this class is that no file it is handed stops existing: the only
 * removal in it is of an original whose copy has already been proven equal. A checker that
 * decides a file
 * is damaged is deciding it from a checksum, and a checksum can only prove that the bytes
 * are not what was written - never that there is nothing left in them worth recovering. A
 * damaged segment still holds every record whose checksum matched, and a human with the
 * file has options that a human with a log line saying it was removed does not.</p>
 *
 * <p>A name already taken in the quarantine directory is never overwritten either: the
 * second copy is suffixed, because the first is evidence too.</p>
 *
 * <h2>Across filesystems</h2>
 * <p>A file is renamed into quarantine when it can be, which is atomic and costs nothing.
 * When the quarantine directory is on another filesystem - in a container, where the data
 * directory is one mount and the quarantine another, that is the ordinary case, because a
 * rename between two mounts is refused even when one disk lies beneath both - the file is
 * copied instead. The copy is staged under a name of its own, forced to the device, compared
 * with the original byte for byte, and renamed onto its final name; only then is the
 * original removed. A crash anywhere in that sequence leaves the original in place, and at
 * worst a second copy beside it: never neither.</p>
 *
 * <p>A directory - a lexical segment is one - crosses the same way, as a whole: every file in it
 * is copied into a staged tree and forced, the tree is compared with the original - the same
 * entries, and each file equal byte for byte - and only then is it renamed onto its final name
 * and the original tree removed. A directory holding anything other than files and directories
 * is refused before anything is copied, because a link or a device node has no bytes a
 * comparison could prove equal.</p>
 *
 * <h2>Durability</h2>
 * <p>A move is finished when both of its directory entries are on the device, not when the
 * calls that made them return. A new name lives in its directory's entries, and so does the
 * absence of an old one, and neither survives a crash until that directory is forced. So every
 * directory this class creates is forced, together with the parent of the first of them, before
 * anything is put inside; the quarantine directory is forced once the file's new name is in it;
 * and the directory the file came from is forced once its old name is gone. Skipping the first
 * would let a crash take away a quarantine directory, and the only copy with it, after the
 * original had been removed. Skipping the last would let a crash bring back a name the caller
 * has since acted on as gone, and two moves made in a deliberate order could then be undone
 * in the other.</p>
 *
 * <p>When a move gets part of the way, the failure says which part: a
 * {@link PartialQuarantineException} names the copy that now exists in quarantine, so nobody
 * is told the file is exactly as it was when a copy of it has already landed elsewhere.</p>
 */
public final class Quarantine {

    /** Suffix of a copy that has not yet been proven equal to its original. */
    private static final String STAGING_SUFFIX = ".quarantine-partial";

    private final Path directory;

    private final Renamer renamer;

    private final DirectorySync sync;

    /**
     * Prepares a quarantine directory.
     *
     * @param directory Where files are moved to, created when first used
     */
    public Quarantine(Path directory) {
        this(directory, (source, target) ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Prepares a quarantine directory whose first attempt at each move goes through the given
     * rename.
     *
     * <p>The seam exists for the one condition a test cannot produce on demand: a quarantine
     * directory on another filesystem, whose rename is refused.</p>
     *
     * @param directory Where files are moved to, created when first used
     * @param renamer   Performs the atomic rename that is tried first
     */
    Quarantine(Path directory, Renamer renamer) {
        this(directory, renamer, Quarantine::forceDirectory);
    }

    /**
     * Prepares a quarantine directory whose renames and directory syncs go through the given
     * seams.
     *
     * <p>A directory sync leaves nothing a test can observe afterwards, so the order in which
     * directories are forced, relative to the moves around them, can only be asserted by
     * watching the calls.</p>
     *
     * @param directory Where files are moved to, created when first used
     * @param renamer   Performs the atomic rename that is tried first
     * @param sync      Forces a directory's entries to the device
     */
    Quarantine(Path directory, Renamer renamer, DirectorySync sync) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.renamer = Objects.requireNonNull(renamer, "renamer");
        this.sync = Objects.requireNonNull(sync, "sync");
    }

    /**
     * Returns where files are moved to.
     *
     * @return The quarantine directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * Moves one file into quarantine, preserving its bytes and its name.
     *
     * <p>When this returns, the move is durable: the file's name in quarantine and the absence
     * of its old name are both on the device.</p>
     *
     * @param file     The file to move
     * @param relative Its path relative to the data directory, which becomes its name
     * @return Where it now is
     * @throws PartialQuarantineException if the file reached quarantine and a later step did
     *                                    not complete; the exception says which, and where
     *                                    the copy is
     * @throws IOException                if it cannot be moved; the original is then still
     *                                    where it was, and nothing of it is in quarantine
     */
    public Path take(Path file, String relative) throws IOException {
        createDurably(directory);
        Path destination = free(directory.resolve(relative.replace('/', '_')));
        try {
            renamer.rename(file, destination);
        } catch (AtomicMoveNotSupportedException e) {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                copyTreeThenRemove(file, destination);
            } else {
                copyThenRemove(file, destination);
            }
            return destination;
        }
        try {
            sync.force(destination.getParent());
            sync.force(containingDirectory(file));
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "moved to " + destination
                + ", but the rename could not be forced to the device, so a crash before "
                + "either directory is next written may bring the old name back: " + e, e);
        }
        return destination;
    }

    /**
     * Forces the directory a file was in, so that a name removed from it stays removed.
     *
     * <p>For a caller whose next step depends on a file being gone, whoever removed it: a
     * move that failed half-way, or one made before this run, is made durable here too.</p>
     *
     * @param file A file that was, or may have been, in that directory
     * @throws IOException if the directory cannot be opened or forced
     */
    void forceContainingDirectory(Path file) throws IOException {
        sync.force(containingDirectory(file));
    }

    /**
     * Creates the quarantine directory and every missing ancestor, and makes each of them
     * durable before anything is put inside.
     *
     * <p>Each directory created is forced for the entries of the one created inside it, and the
     * parent of the first is forced for that first one's entry. A directory that already
     * existed is not forced again: its entry was published by whoever created it.</p>
     *
     * @param target The quarantine directory
     * @throws IOException if a directory cannot be created or forced
     */
    private void createDurably(Path target) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path each = target.toAbsolutePath(); each != null && !Files.isDirectory(each);
                each = each.getParent()) {
            missing.push(each);
        }
        Files.createDirectories(target);
        if (missing.isEmpty()) {
            return;
        }
        Path topmostParent = missing.peek().getParent();
        if (topmostParent != null) {
            sync.force(topmostParent);
        }
        for (Path created : missing) {
            sync.force(created);
        }
    }

    /**
     * Moves a file to another filesystem without a moment at which no copy of it exists.
     *
     * <p>The copy is forced through a read-only channel. {@code COPY_ATTRIBUTES} has already
     * given it the original's permissions by then, and a read-only original - a sealed file
     * the engine left {@code 0444} - makes a copy no process without override privileges may
     * open for writing. Forcing needs no write access: {@code fsync} on a descriptor opened for
     * reading flushes the file's data and metadata all the same.</p>
     *
     * @param file        The original
     * @param destination Its free name in quarantine
     * @throws PartialQuarantineException if the copy was published in quarantine and a later
     *                                    step did not complete
     * @throws IOException                if the copy cannot be written, forced or shown to be
     *                                    equal, in which case the original is untouched and
     *                                    the staged copy removed
     */
    private void copyThenRemove(Path file, Path destination) throws IOException {
        Path staged = destination.resolveSibling(destination.getFileName() + STAGING_SUFFIX);
        try {
            Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.READ)) {
                channel.force(true);
            }
            long firstDifference = Files.mismatch(file, staged);
            if (firstDifference != -1) {
                throw new IOException("The copy of " + file + " made for quarantine differs "
                    + "from it at byte " + firstDifference + ", so the original was kept");
            }
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        try {
            sync.force(destination.getParent());
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "a verified copy is at "
                + destination + " but could not be forced to the device, so the original was "
                + "kept where it was: " + e, e);
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "a verified copy is at "
                + destination + ", but the original could not be removed and is still in "
                + "place: " + e, e);
        }
        try {
            sync.force(containingDirectory(file));
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "moved to " + destination
                + ", but the removal of the original could not be forced to the device, so a "
                + "crash before its directory is next written may bring it back: " + e, e);
        }
    }

    /**
     * Moves a directory to another filesystem without a moment at which no whole copy of it
     * exists.
     *
     * <p>The sequence {@link #copyThenRemove} follows for one file, over a tree: the copy is
     * staged under a name of its own, every file in it forced as that method forces one and
     * every directory in it forced for the entries it holds, and the whole of it compared with
     * the original before one rename publishes it. Only then is the original removed, deepest
     * entries first, so a removal cut short leaves the original's own name in place, never a
     * name that has gone while part of what was under it has not.</p>
     *
     * @param tree        The original directory
     * @param destination Its free name in quarantine
     * @throws PartialQuarantineException if the copy was published in quarantine and a later
     *                                    step did not complete
     * @throws IOException                if the tree holds something other than files and
     *                                    directories, or the copy cannot be written, forced or
     *                                    shown to be equal; the original is then untouched and
     *                                    the staged copy removed
     */
    private void copyTreeThenRemove(Path tree, Path destination) throws IOException {
        List<Path> entries = entriesOf(tree);
        Path staged = destination.resolveSibling(destination.getFileName() + STAGING_SUFFIX);
        try {
            // A staged tree left by a run that crashed mid-copy was never published, so it is
            // nobody's evidence; the copy starts again from the original.
            deleteTree(staged);
            copyTree(tree, entries, staged);
            verifyTree(tree, entries, staged);
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(staged);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        try {
            sync.force(destination.getParent());
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "a verified copy is at "
                + destination + " but could not be forced to the device, so the original was "
                + "kept where it was: " + e, e);
        }
        try {
            deleteTree(tree);
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "a verified copy is at "
                + destination + ", but the original could not be removed entirely and what is "
                + "left of it is still in place: " + e, e);
        }
        try {
            sync.force(containingDirectory(tree));
        } catch (IOException e) {
            throw new PartialQuarantineException(destination, "moved to " + destination
                + ", but the removal of the original could not be forced to the device, so a "
                + "crash before its directory is next written may bring it back: " + e, e);
        }
    }

    /**
     * Lists a tree's entries, parents before children, refusing anything that is neither a file
     * nor a directory.
     *
     * @param tree The directory
     * @return Every entry beneath it, the directory itself excluded
     * @throws IOException if it cannot be listed, or holds a link or a special file
     */
    private static List<Path> entriesOf(Path tree) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                if (entry.equals(tree)) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(entry + " is neither a file nor a directory, so no "
                        + "copy of it could be proven equal; " + tree + " was kept where it was");
                }
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Copies a tree into a staged directory and forces all of it to the device.
     *
     * @param tree    The original directory
     * @param entries Its entries, parents first
     * @param staged  Where the copy is built
     * @throws IOException if any part of it cannot be written or forced
     */
    private void copyTree(Path tree, List<Path> entries, Path staged) throws IOException {
        Files.createDirectory(staged);
        List<Path> directories = new ArrayList<>();
        directories.add(staged);
        for (Path entry : entries) {
            Path copy = staged.resolve(tree.relativize(entry).toString());
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(copy);
                directories.add(copy);
                continue;
            }
            Files.copy(entry, copy, StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS);
            // Through a read-only channel, for the reason copyThenRemove gives.
            try (FileChannel channel = FileChannel.open(copy, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
        // Deepest first, so a directory's entries are on the device before the entry naming it
        // in its parent is.
        for (int at = directories.size() - 1; at >= 0; at--) {
            sync.force(directories.get(at));
        }
    }

    /**
     * Proves a staged copy holds exactly what the original tree holds.
     *
     * @param tree    The original directory
     * @param entries Its entries
     * @param staged  The copy
     * @throws IOException if an entry is missing, of the wrong kind or unequal, or the copy
     *                     holds one the original does not
     */
    private static void verifyTree(Path tree, List<Path> entries, Path staged)
            throws IOException {
        for (Path entry : entries) {
            Path copy = staged.resolve(tree.relativize(entry).toString());
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The copy of " + tree + " made for quarantine has no "
                        + "directory " + copy + ", so the original was kept");
                }
                continue;
            }
            long firstDifference = Files.mismatch(entry, copy);
            if (firstDifference != -1) {
                throw new IOException("The copy of " + entry + " made for quarantine differs "
                    + "from it at byte " + firstDifference + ", so the original was kept");
            }
        }
        long copied;
        try (Stream<Path> walk = Files.walk(staged)) {
            copied = walk.count() - 1;
        }
        if (copied != entries.size()) {
            throw new IOException("The copy of " + tree + " made for quarantine holds " + copied
                + " entries where the original holds " + entries.size()
                + ", so the original was kept");
        }
    }

    /**
     * Removes a tree, deepest entries first, doing nothing when it does not exist.
     *
     * @param tree The directory
     * @throws IOException if any entry cannot be removed
     */
    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(tree)) {
            all = walk.toList();
        }
        for (int at = all.size() - 1; at >= 0; at--) {
            Files.delete(all.get(at));
        }
    }

    /**
     * Returns the directory a file's name lives in.
     *
     * @param file The file
     * @return Its parent, resolved against the working directory when the path is relative
     */
    private static Path containingDirectory(Path file) {
        return file.toAbsolutePath().getParent();
    }

    /**
     * Forces a directory's entries to the device, so a published name survives a crash.
     *
     * <p>Package-private so that a test watching the order of syncs can still perform them.</p>
     *
     * @param directory The directory
     * @throws IOException if it cannot be opened or forced
     */
    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /**
     * Finds a name in the quarantine directory that is not already taken.
     *
     * @param preferred The name to use if it is free
     * @return A free path, suffixed if it had to be
     */
    private static Path free(Path preferred) {
        if (!Files.exists(preferred)) {
            return preferred;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = preferred.resolveSibling(preferred.getFileName() + "." + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free name in quarantine for " + preferred);
    }

    /**
     * Forces a directory's entries to the device.
     */
    @FunctionalInterface
    interface DirectorySync {

        /**
         * Forces one directory.
         *
         * @param directory The directory
         * @throws IOException if it cannot be opened or forced
         */
        void force(Path directory) throws IOException;
    }

    /**
     * Performs the atomic rename a move tries first.
     */
    @FunctionalInterface
    interface Renamer {

        /**
         * Renames source to target atomically.
         *
         * @param source What to rename
         * @param target Its new name
         * @throws AtomicMoveNotSupportedException if the two are on different filesystems
         * @throws IOException                     if the rename fails for any other reason
         */
        void rename(Path source, Path target) throws IOException;
    }
}
