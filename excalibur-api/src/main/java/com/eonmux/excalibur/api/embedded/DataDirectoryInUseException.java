package com.eonmux.excalibur.api.embedded;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The data directory {@link ExcaliburEmbedded.Builder#open()} was pointed at is already held.
 *
 * <p>One node owns a data directory at a time: a server, another embedded node - in this process
 * or another - or an {@code excalibur-fsck --repair} or {@code excalibur-backup restore} working
 * on it. This is what opening another over the top is refused with, before any of the directory's
 * contents are opened.</p>
 *
 * <p>An {@link IOException}, so a caller that already handles a directory it cannot open handles
 * this one too, and a type of the facade's own, so it can be caught by name with only
 * {@code excalibur-api} on the compile classpath. The engine's own refusal is kept as the
 * cause.</p>
 *
 * <p>The holder's details are what it recorded about itself when it took the directory. They
 * describe; they decide nothing. A holder that was killed leaves its record behind, but not its
 * lock, which the operating system releases the moment the process exits.</p>
 */
public final class DataDirectoryInUseException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The directory that could not be claimed. */
    private final transient Path directory;

    /** The holder's process id, or a negative number when it did not record a readable one. */
    private final long holderPid;

    /** What the holder called itself, or null when its record could not be read. */
    private final String holderDescription;

    /**
     * Creates the exception.
     *
     * @param directory         The directory that could not be claimed
     * @param holderPid         The holder's process id, or a negative number when unknown
     * @param holderDescription What the holder called itself, or null when unknown
     * @param cause             The engine's refusal
     * @throws NullPointerException if directory or cause is null
     */
    DataDirectoryInUseException(Path directory, long holderPid, String holderDescription,
                                IOException cause) {
        super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        this.directory = Objects.requireNonNull(directory, "directory");
        this.holderPid = holderPid;
        this.holderDescription = holderDescription;
    }

    /**
     * Returns the directory that could not be claimed.
     *
     * @return The directory, made absolute
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns the process id the holder recorded.
     *
     * @return The pid, or empty when the holder's record carries no readable one
     */
    public OptionalLong holderPid() {
        return holderPid < 0 ? OptionalLong.empty() : OptionalLong.of(holderPid);
    }

    /**
     * Returns what the holder recorded about itself - {@code excalibur node} for a server,
     * {@code excalibur embedded node}, or the tool, such as {@code excalibur-fsck --repair}.
     *
     * @return The description, or empty when the holder's record could not be read
     */
    public Optional<String> holderDescription() {
        return Optional.ofNullable(holderDescription);
    }
}
