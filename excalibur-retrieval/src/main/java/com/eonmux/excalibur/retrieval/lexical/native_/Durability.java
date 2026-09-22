package com.eonmux.excalibur.retrieval.lexical.native_;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Making a write outlive the machine, expressed as an ordering rather than as one call.
 *
 * <h2>Why a rename is not enough on its own</h2>
 * <p>Writing beside and renaming over is what stops a reader ever seeing half a file. It says
 * nothing about whether either half reached the device. A replacement whose contents are still in
 * the page cache when the rename lands publishes a correctly named file that a power cut turns
 * into an empty or partial one - which is the very failure the rename was there to prevent,
 * arrived at from the other end. So the contents are forced first, then renamed, and the
 * directory forced afterwards, because the rename is itself a change to the directory and is no
 * more durable on its own than the contents were.
 *
 * <p>Forcing a file is not best effort: a caller that has been told its segment is published
 * decides what to delete on the strength of it, so a failure has to reach that caller. Forcing a
 * directory is best effort, because a platform that will not open a directory channel is not a
 * reason to fail a write whose bytes are already on the device.
 *
 * <h2>Why this is an interface</h2>
 * <p>No test can cut the power between two of these steps. What can be pinned is the order they
 * are taken in, which is what the ordering above actually consists of - so the steps are named
 * separately and a test can record them instead of taking them.
 */
interface Durability {

    /** The steps as the filesystem performs them. */
    Durability FILE_SYSTEM = new FileSystemDurability();

    /** Suffix of the sibling a replacement is assembled in before it is published. */
    String REPLACEMENT_SUFFIX = ".tmp";

    /**
     * Forces a written file's contents to the underlying device.
     *
     * @param file The file to force
     * @throws IOException if the contents cannot be made durable
     */
    void force(Path file) throws IOException;

    /**
     * Renames a finished replacement over the file it replaces.
     *
     * @param replacement The file holding the new contents
     * @param target      What it becomes
     * @throws IOException if the replacement cannot be published
     */
    void publish(Path replacement, Path target) throws IOException;

    /**
     * Forces a directory entry, so the renames and creations within it survive a crash.
     *
     * <p>Best effort, and deliberately not throwing: see the class comment.</p>
     *
     * @param directory The directory to force
     */
    void forceDirectory(Path directory);

    /**
     * Replaces a whole file durably, in the order the class comment sets out.
     *
     * <p>A failure anywhere leaves the previous file exactly as it was, so the only thing to undo
     * is the half-written sibling. Its removal is suppressed rather than thrown, because losing
     * the real cause to a tidy-up failure is the worse trade.</p>
     *
     * @param target   The file to replace
     * @param contents What it should hold
     * @throws IOException if the new contents cannot be made durable under that name
     */
    default void replace(Path target, byte[] contents) throws IOException {
        Path replacement = target.resolveSibling(target.getFileName() + REPLACEMENT_SUFFIX);
        try {
            Files.write(replacement, contents);
            force(replacement);
            publish(replacement, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(replacement);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        Path parent = target.getParent();
        if (parent != null) {
            forceDirectory(parent);
        }
    }
}
