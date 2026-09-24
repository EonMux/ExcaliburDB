package com.eonmux.excalibur.core.datadir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A data directory some other process - or another owner in this one - already holds.
 *
 * <p>An {@link IOException}, so every caller that already reports a failure to open its
 * directory reports this one too, and a distinct type, so the operator tools can map it to an
 * exit code of its own: "a node is running on this" calls for stopping the node, not for
 * retrying or for suspecting the disk.</p>
 *
 * @author ExcaliburDB
 */
public final class DataDirectoryLockedException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The directory that could not be claimed. */
    private final transient Path directory;

    /** What the holder recorded about itself, if it could be read. */
    private final transient LockHolder holder;

    /**
     * Creates the exception.
     *
     * @param directory   The directory that could not be claimed
     * @param holder      What the holder recorded, or null if nothing could be read
     * @param thisProcess Whether the holder is this very process
     */
    DataDirectoryLockedException(Path directory, LockHolder holder, boolean thisProcess) {
        super(message(directory, holder, thisProcess));
        this.directory = Objects.requireNonNull(directory, "directory");
        this.holder = holder;
    }

    /**
     * Returns the directory that could not be claimed.
     *
     * @return The directory, as the caller named it
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns what the holder recorded about itself.
     *
     * @return The record, or empty when it could not be read
     */
    public Optional<LockHolder> holder() {
        return Optional.ofNullable(holder);
    }

    /**
     * Words the refusal.
     *
     * @param directory   The directory
     * @param holder      The holder's record, or null
     * @param thisProcess Whether the holder is this process
     * @return The message
     */
    private static String message(Path directory, LockHolder holder, boolean thisProcess) {
        String who = holder == null ? "a process whose record could not be read" : holder.describe();
        if (thisProcess) {
            return "The data directory " + directory + " is already held by this process ("
                + who + "). A data directory has one owner at a time.";
        }
        return "The data directory " + directory + " is in use by another process (" + who
            + "). Only one process may use a data directory at a time: stop that one first, or "
            + "point this one at a different directory. The process id is what the holder "
            + "recorded in " + DataDirectoryLock.FILE_NAME + "; the operating system's lock on "
            + "that file is what is enforced, and it is released the moment the holder exits.";
    }
}
