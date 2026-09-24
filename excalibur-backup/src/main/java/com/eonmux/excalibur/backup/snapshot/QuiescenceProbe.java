package com.eonmux.excalibur.backup.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Notices that a data directory moved while it was being copied.
 *
 * <h2>Why this exists beside the lock</h2>
 * <p>A node holds its data directory's lock for as long as it runs, and a snapshot takes the
 * same lock before it copies anything, so a node of this build that is running is refused at
 * the door and one cannot start during the copy. That covers the writers that take the lock.
 * It does not cover one that does not: a build older than the lock, a node started with the
 * lock file deleted underneath it, a filesystem whose locks are not honoured across hosts, or a
 * snapshot taken with {@code --allow-live}. This is the check that still sees those.</p>
 *
 * <h2>What it does</h2>
 * <p>It notices a writer. The directory is fingerprinted before the copy and again
 * after it, and a file that grew, shrank, appeared, vanished or was rewritten in place
 * between the two is proof that something was writing. That is a one-sided test - a node
 * that happened to write nothing during the copy looks exactly like a node that was not
 * running - but the case it catches is the one that produces a torn image, which is the
 * only case that matters.</p>
 *
 * <p>Mapped writes are the hard case, because a page dirtied through a mapping need not
 * update the file's timestamp until it is written back. This is therefore a detector and
 * never a proof; a snapshot taken with the node stopped is the only image this tool will
 * describe as consistent.</p>
 *
 * @author ExcaliburDB
 */
public final class QuiescenceProbe {

    private QuiescenceProbe() {
    }

    /**
     * Observes every regular file under a directory.
     *
     * <p>A file that vanishes between being listed and being examined is recorded as
     * absent rather than raised, because a directory that is changing underneath the walk
     * is exactly the condition the caller is trying to detect.</p>
     *
     * @param dataDir The directory
     * @return What was observed
     * @throws IOException          if the directory cannot be walked
     * @throws NullPointerException if dataDir is null
     */
    public static DirectoryFingerprint fingerprint(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        List<Path> files;
        try (var walk = Files.walk(dataDir)) {
            // Not the lock file, whose record changes whenever a holder takes it, and not a
            // restore's scratch: neither is copied, so neither moving tears the image.
            files = walk.filter(Files::isRegularFile)
                .filter(file -> !com.eonmux.excalibur.core.datadir.ReservedNames.isReserved(
                    dataDir.relativize(file).toString().replace('\\', '/')))
                .toList();
        }
        var observed = new TreeMap<String, FileStat>();
        for (Path file : files) {
            String path = dataDir.relativize(file).toString().replace('\\', '/');
            try {
                observed.put(path, new FileStat(Files.size(file),
                    Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS)));
            } catch (NoSuchFileException e) {
                observed.put(path, new FileStat(-1L, -1L));
            }
        }
        return new DirectoryFingerprint(observed);
    }

    /**
     * Describes every way two observations of the same directory disagree.
     *
     * @param before The earlier observation
     * @param after  The later one
     * @return One sentence per difference, in path order; empty if nothing moved
     * @throws NullPointerException if either argument is null
     */
    public static List<String> differences(DirectoryFingerprint before,
                                           DirectoryFingerprint after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        var paths = new TreeSet<String>();
        paths.addAll(before.files().keySet());
        paths.addAll(after.files().keySet());
        List<String> differences = new ArrayList<>();
        for (String path : paths) {
            describe(path, before.files().get(path), after.files().get(path))
                .ifPresent(differences::add);
        }
        return List.copyOf(differences);
    }

    /**
     * Describes how one file differs between two observations.
     *
     * @param path   The file
     * @param before What it was, or null if it was not there
     * @param after  What it is, or null if it is no longer there
     * @return The difference, or empty if there is none
     */
    private static java.util.Optional<String> describe(String path, FileStat before,
                                                       FileStat after) {
        if (before == null) {
            return java.util.Optional.of(path + " appeared while the snapshot was running");
        }
        if (after == null) {
            return java.util.Optional.of(path + " was deleted while the snapshot was running");
        }
        if (before.size() != after.size()) {
            return java.util.Optional.of(path + " changed length from " + before.size()
                + " to " + after.size() + " bytes while the snapshot was running");
        }
        if (before.lastModifiedNanos() != after.lastModifiedNanos()) {
            return java.util.Optional.of(path + " was rewritten in place while the snapshot "
                + "was running");
        }
        return java.util.Optional.empty();
    }
}
