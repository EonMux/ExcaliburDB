package com.eonmux.excalibur.core.datadir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The entries at the top of a data directory that belong to the filesystem it is on rather than
 * to anything that uses it.
 *
 * <p>A data directory is often the root of a filesystem of its own - a disk or a volume mounted
 * at the data directory - and {@code mkfs} gives the root of an ext2/3/4 filesystem a
 * {@code lost+found} directory, owned by root and unreadable to anyone else, where
 * {@code e2fsck} reattaches the inodes it recovers. It is not the engine's: nothing here writes
 * it or reads it, the node cannot open it, and it cannot be moved by anyone but root. So it is
 * not data. A snapshot does not copy it, a restore neither replaces nor moves it and does not
 * count it as something a target holds, a check does not report it, and the node does not take
 * it for a namespace or a segment.</p>
 *
 * <h2>The rule</h2>
 * <p>A directory named {@code lost+found} directly under the data directory, not a symbolic link
 * to one. The name alone is not the rule: a regular file of that name is not what a filesystem
 * makes, so it is left to be classified like any other unrecognised file, and the same name
 * deeper down is nobody's but whoever put it there. The directory is recognised whether or not
 * the data directory is at that moment the root of a mount, for three reasons:</p>
 * <ul>
 *   <li>There is nothing to disambiguate. No build writes that name, and it is not a valid
 *       namespace, so a directory of that name at the top is never the engine's state.</li>
 *   <li>A mount root cannot be told reliably from Java. The platform has no mount table; comparing
 *       device numbers with the parent is Unix-only, calls a btrfs subvolume a mount and a bind
 *       mount of a subdirectory a root, and says nothing about a directory copied off a volume
 *       with its {@code lost+found}.</li>
 *   <li>A mount-dependent rule would make the same directory mean different things to the tools
 *       depending on where it is sitting. An image taken on one mount and restored onto another,
 *       or onto a plain directory, would have its classification decided by the mount table of
 *       each host rather than by its contents.</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class FilesystemEntries {

    /** Where an ext2/3/4 filesystem reattaches the inodes a check recovers. */
    public static final String LOST_AND_FOUND = "lost+found";

    /** What begins the path of anything inside it, relative to the data directory. */
    private static final String LOST_AND_FOUND_PREFIX = LOST_AND_FOUND + "/";

    private FilesystemEntries() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether an entry directly inside a data directory is the filesystem's own.
     *
     * <p>Only the name and the entry's own type are examined, never its contents, so an entry
     * the caller cannot read is recognised as readily as one it can.</p>
     *
     * @param entry An entry directly inside a data directory
     * @return true if it is a directory, not a link, named {@code lost+found}
     * @throws NullPointerException if entry is null
     */
    public static boolean isFilesystemEntry(Path entry) {
        Objects.requireNonNull(entry, "entry");
        Path name = entry.getFileName();
        return name != null && name.toString().equals(LOST_AND_FOUND)
            && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Reports whether a path relative to a data directory lies inside the filesystem's own
     * directory.
     *
     * <p>For a list of paths rather than a directory - an image's manifest, which names only
     * files. A file listed under {@code lost+found/} was inside a directory of that name at the
     * top of the directory it was copied from, which is that filesystem's, whatever build made
     * the list.</p>
     *
     * @param relativePath The path relative to the data directory, "/"-separated
     * @return true if it is under {@code lost+found/}
     * @throws NullPointerException if relativePath is null
     */
    public static boolean isWithinFilesystemEntry(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        return relativePath.startsWith(LOST_AND_FOUND_PREFIX);
    }
}
