package com.eonmux.excalibur.fsck;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Where a quarantine directory really is, and whether that is inside the data directory.
 *
 * <p>A quarantine inside the data directory defeats the point of one. The files moved there are
 * still in the directory being checked, so the next check reports them again - or, because the
 * scan skips the quarantine, stops reporting whatever else lives under it - and the next backup,
 * disk-usage alarm and recursive copy all carry the damaged files along. So the question has to
 * be answered about the directory the files would actually land in, not about how its path is
 * spelled: a path that reads as a sibling can reach the data directory through a symbolic link,
 * and a {@code ..} after a link climbs from where the link points, not from where it sits.</p>
 *
 * <p>The quarantine usually does not exist yet, so {@link Path#toRealPath} cannot answer on its
 * own. The path is resolved one name at a time instead, the way the kernel resolves it when the
 * directory is created: every link met is replaced by its target, including one whose target is
 * missing, and whatever lies past the last existing name is taken as spelled, since a name that
 * does not exist cannot be a link.</p>
 */
final class QuarantineLocation {

    /**
     * How many links one resolution may follow before it is taken to be a loop - the limit
     * Linux's own path walk uses.
     */
    private static final int MAX_LINKS = 40;

    private QuarantineLocation() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether the quarantine lies inside the data directory, or is it.
     *
     * @param dataDirectory The directory being checked
     * @param quarantine    Where damaged files would be moved
     * @return true if files moved to the quarantine would still be inside the data directory
     * @throws IOException if either path cannot be resolved - a link loop, or a directory on
     *                     the way that cannot be read
     */
    static boolean isInside(Path dataDirectory, Path quarantine) throws IOException {
        return canonical(quarantine).startsWith(canonical(dataDirectory));
    }

    /**
     * Resolves a path to the one the filesystem would reach, whether or not it exists.
     *
     * @param path The path, relative to the working directory or absolute
     * @return The absolute path with every link and {@code .} or {@code ..} resolved
     * @throws IOException if a link loop is met, or a directory on the way cannot be read
     */
    static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path resolved = absolute.getRoot();
        Deque<String> remaining = new ArrayDeque<>();
        for (Path name : absolute) {
            remaining.addLast(name.toString());
        }
        int linksFollowed = 0;
        while (!remaining.isEmpty()) {
            String name = remaining.removeFirst();
            if (name.isEmpty() || name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                // Safe to take lexically: everything resolved so far is a real directory, so
                // its parent is the one the filesystem climbs to.
                Path parent = resolved.getParent();
                resolved = parent != null ? parent : resolved;
                continue;
            }
            Path candidate = resolved.resolve(name);
            if (!Files.isSymbolicLink(candidate)) {
                resolved = candidate;
                continue;
            }
            if (++linksFollowed > MAX_LINKS) {
                throw new FileSystemException(absolute.toString(), null,
                    "more than " + MAX_LINKS + " symbolic links on the way; a link loop?");
            }
            Path target = Files.readSymbolicLink(candidate);
            if (target.isAbsolute()) {
                resolved = target.getRoot();
            }
            // The target's names are walked in place of the link's, before whatever followed it.
            Deque<String> targetNames = new ArrayDeque<>();
            for (Path targetName : target) {
                targetNames.addLast(targetName.toString());
            }
            while (!targetNames.isEmpty()) {
                remaining.addFirst(targetNames.removeLast());
            }
        }
        return resolved;
    }
}
