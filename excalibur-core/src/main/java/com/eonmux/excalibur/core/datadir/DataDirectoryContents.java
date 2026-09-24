package com.eonmux.excalibur.core.datadir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What in a data directory is data.
 *
 * <p>Everything but the entries that coordinate the processes using it ({@link ReservedNames})
 * and the ones that belong to the filesystem it is on ({@link FilesystemEntries}). The node, the
 * checker, the snapshot and the restore all list a data directory through here, so they agree on
 * what it holds.</p>
 *
 * <p>The entries that are not data are left out of the walk rather than filtered from its
 * results. A mounted volume's {@code lost+found} cannot be opened by the node's user, and a walk
 * that descended into it would fail before it had looked at anything else.</p>
 *
 * @author ExcaliburDB
 */
public final class DataDirectoryContents {

    private DataDirectoryContents() {
        throw new AssertionError("Utility class");
    }

    /**
     * Lists the entries directly inside a data directory that hold data.
     *
     * @param dataDirectory The data directory
     * @return Its entries less the reserved and the filesystem's own, in name order
     * @throws IOException          if the directory cannot be listed
     * @throws NullPointerException if dataDirectory is null
     */
    public static List<Path> entries(Path dataDirectory) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        try (Stream<Path> listing = Files.list(dataDirectory)) {
            return listing.filter(DataDirectoryContents::isData).sorted().toList();
        }
    }

    /**
     * Reports whether a data directory holds anything.
     *
     * <p>A directory holding only the lock a stopped node left, or only the {@code lost+found} of
     * the filesystem mounted there, holds nothing.</p>
     *
     * @param dataDirectory The data directory
     * @return true if it has at least one entry that is data
     * @throws IOException          if the directory cannot be listed
     * @throws NullPointerException if dataDirectory is null
     */
    public static boolean holdsData(Path dataDirectory) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        try (Stream<Path> listing = Files.list(dataDirectory)) {
            return listing.anyMatch(DataDirectoryContents::isData);
        }
    }

    /**
     * Lists every regular file under a data directory that holds data.
     *
     * <p>Symbolic links are not followed into directories, and a link to a regular file is
     * listed as the file it names, as {@link Files#walk(Path, java.nio.file.FileVisitOption...)}
     * and {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)} have it.</p>
     *
     * @param dataDirectory The data directory
     * @return The files, entry by entry in name order and in walk order within each
     * @throws IOException          if the directory cannot be listed
     * @throws java.io.UncheckedIOException if a directory holding data cannot be walked
     * @throws NullPointerException if dataDirectory is null
     */
    public static List<Path> regularFiles(Path dataDirectory) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path entry : entries(dataDirectory)) {
            try (Stream<Path> walk = Files.walk(entry)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        return files;
    }

    /**
     * Reports whether an entry directly inside a data directory holds data.
     *
     * @param entry The entry
     * @return false for the reserved entries and the filesystem's own
     */
    private static boolean isData(Path entry) {
        return !ReservedNames.isReserved(entry.getFileName().toString())
            && !FilesystemEntries.isFilesystemEntry(entry);
    }
}
