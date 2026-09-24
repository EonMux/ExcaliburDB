package com.eonmux.excalibur.ingest.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A lazy, depth-first walk of a directory tree that does not enter the directories it is told to
 * leave alone.
 *
 * <h2>Why not {@code Files.walk}</h2>
 * <p>{@code Files.walk} cannot be told to skip a subtree: anything it lists can only be filtered
 * out after it has been reached, so an excluded {@code node_modules} is still read entry by
 * entry, and with links followed a cycle inside it aborts the whole walk. {@code
 * Files.walkFileTree} can skip one, but it drives a visitor to the end before returning, so the
 * listing would have to be held in memory whole where a stream hands it out one path at a time.
 * This keeps {@code Files.walk}'s contract - the same order, the same depth rule, a symlink
 * cycle reported as a {@link FileSystemLoopException}, any I/O failure raised as an
 * {@link UncheckedIOException} from the element that met it - and adds the one thing it
 * lacks.</p>
 *
 * <h2>What is listed</h2>
 * <p>Every entry below the root that is not a directory the walk descends into: files, links
 * that are not followed or lead nowhere, special files, and directories at the depth limit or
 * refused by the pruning predicate. The root itself is not listed and is never pruned. A caller
 * decides which of those are documents.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe; a walk is consumed by one thread, like any stream.</p>
 */
final class PrunedWalk implements Iterator<Path> {

    /** One open directory: its listing, how deep it is, and what identifies it for cycles. */
    private record Frame(Path directory, DirectoryStream<Path> stream, Iterator<Path> entries,
                         int depth, Object fileKey) {
    }

    private final int maxDepth;
    private final boolean followLinks;
    private final Predicate<Path> descend;
    private final LinkOption[] linkOptions;
    private final Deque<Frame> open = new ArrayDeque<>();
    private Path next;

    private PrunedWalk(int maxDepth, boolean followLinks, Predicate<Path> descend) {
        this.maxDepth = maxDepth;
        this.followLinks = followLinks;
        this.descend = descend;
        this.linkOptions = followLinks ? new LinkOption[0]
            : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    }

    /**
     * Starts a walk of the tree under a directory.
     *
     * @param root        The directory to walk; not itself listed
     * @param maxDepth    How many levels below the root to list; entries of the root are level 1,
     *                    and a directory at the last level is listed rather than entered
     * @param followLinks Whether a link to a directory is walked as the directory it leads to
     * @param descend     Whether to enter a directory below the root; one it refuses is neither
     *                    entered nor listed
     * @return The entries, in the order the directories list them, depth first; closing the
     *         stream closes every directory still open
     * @throws IOException if the root cannot be read
     */
    static Stream<Path> of(Path root, int maxDepth, boolean followLinks, Predicate<Path> descend)
            throws IOException {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(descend, "descend must not be null");
        PrunedWalk walk = new PrunedWalk(maxDepth, followLinks, descend);
        BasicFileAttributes attributes = walk.attributesOf(root);
        if (maxDepth > 0) {
            walk.enter(root, 0, attributes);
        }
        Spliterator<Path> spliterator = Spliterators.spliteratorUnknownSize(walk,
            Spliterator.DISTINCT | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false).onClose(walk::close);
    }

    @Override
    public boolean hasNext() {
        if (next == null) {
            next = advance();
        }
        return next != null;
    }

    @Override
    public Path next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Path path = next;
        next = null;
        return path;
    }

    /**
     * Moves to the next entry to list, entering and leaving directories on the way.
     *
     * @return The entry, or null once every directory has been read
     */
    private Path advance() {
        while (!open.isEmpty()) {
            Frame frame = open.peek();
            Path entry;
            try {
                if (!frame.entries().hasNext()) {
                    open.pop();
                    closeQuietly(frame.stream());
                    continue;
                }
                entry = frame.entries().next();
            } catch (DirectoryIteratorException e) {
                throw new UncheckedIOException(e.getCause());
            }
            try {
                BasicFileAttributes attributes = attributesOf(entry);
                int depth = frame.depth() + 1;
                if (!attributes.isDirectory() || depth >= maxDepth) {
                    return entry;
                }
                if (descend.test(entry)) {
                    enter(entry, depth, attributes);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }

    /**
     * Opens a directory to be read next, refusing one that is its own ancestor.
     *
     * @param directory  The directory
     * @param depth      Its level below the root
     * @param attributes What was read about it, by the walk's link rule
     * @throws IOException if it cannot be listed, or is a directory the walk is already inside
     */
    private void enter(Path directory, int depth, BasicFileAttributes attributes)
            throws IOException {
        Object fileKey = attributes.fileKey();
        if (followLinks) {
            for (Frame ancestor : open) {
                boolean same = fileKey != null && ancestor.fileKey() != null
                    ? fileKey.equals(ancestor.fileKey())
                    : Files.isSameFile(directory, ancestor.directory());
                if (same) {
                    throw new FileSystemLoopException(directory.toString());
                }
            }
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        open.push(new Frame(directory, stream, stream.iterator(), depth, fileKey));
    }

    /**
     * Reads an entry's attributes by the walk's link rule.
     *
     * <p>Following links, a link that leads nowhere is described as the link itself, the way
     * {@code Files.walk} describes it, so a dangling link is listed rather than aborting the
     * walk.</p>
     */
    private BasicFileAttributes attributesOf(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, linkOptions);
        } catch (IOException e) {
            if (!followLinks) {
                throw e;
            }
            return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        }
    }

    /** Closes every directory still open; the walk lists nothing after it. */
    private void close() {
        while (!open.isEmpty()) {
            closeQuietly(open.pop().stream());
        }
        next = null;
    }

    private static void closeQuietly(DirectoryStream<Path> stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // A directory handle that fails to close has nothing left to report: the entries
            // read from it are already listed, and the handle is released either way.
        }
    }
}
