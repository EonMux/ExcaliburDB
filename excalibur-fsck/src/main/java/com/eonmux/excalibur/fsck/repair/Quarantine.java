package com.eonmux.excalibur.fsck.repair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Moves a file out of the way without ever destroying it.
 *
 * <p>The whole point of this class is that it has no delete. A checker that decides a file
 * is damaged is deciding it from a checksum, and a checksum can only prove that the bytes
 * are not what was written - never that there is nothing left in them worth recovering. A
 * damaged segment still holds every record whose checksum matched, and a human with the
 * file has options that a human with a log line saying it was removed does not.</p>
 *
 * <p>A name already taken in the quarantine directory is never overwritten either: the
 * second copy is suffixed, because the first is evidence too.</p>
 */
public final class Quarantine {

    private final Path directory;

    /**
     * Prepares a quarantine directory.
     *
     * @param directory Where files are moved to, created when first used
     */
    public Quarantine(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
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
     * @param file     The file to move
     * @param relative Its path relative to the data directory, which becomes its name
     * @return Where it now is
     * @throws IOException if it cannot be moved
     */
    public Path take(Path file, String relative) throws IOException {
        Files.createDirectories(directory);
        Path destination = free(directory.resolve(relative.replace('/', '_')));
        Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE);
        return destination;
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
}
