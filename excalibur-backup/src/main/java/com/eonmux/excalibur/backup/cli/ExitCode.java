package com.eonmux.excalibur.backup.cli;

/**
 * What the process exits with, and what each value means to the script reading it.
 *
 * <p>A wrapper that can only tell success from failure cannot tell "the backup is damaged,
 * page someone" from "you pointed at a directory that already holds a node". Both are
 * non-zero and they call for opposite responses, so each refusal has a code of its own.</p>
 *
 * @author ExcaliburDB
 */
public enum ExitCode {

    /** The command did what it was asked. */
    OK(0),

    /** The command line was wrong: unknown command, missing option, unknown option. */
    USAGE(2),

    /**
     * A snapshot did not verify.
     *
     * <p>The most important code here. It means an image that exists and can be read is
     * not the image it claims to be, which is the failure a backup regime is run to
     * detect. Nothing was restored.</p>
     */
    VERIFICATION_FAILED(3),

    /**
     * The directory the command was to write into already holds something.
     *
     * <p>A restore target without {@code --force}, or a snapshot's {@code --out}, which never
     * merges into an existing image. Either way nothing was written, and retrying the same
     * line will be refused the same way: the fix is a different directory.</p>
     */
    TARGET_NOT_EMPTY(4),

    /**
     * A snapshot was refused because its source moved while it was being copied.
     *
     * <p>A node is running on the data directory. Stop it, or pass {@code --allow-live}.</p>
     */
    NOT_QUIESCED(5),

    /** A file could not be read or written. Retryable, unlike everything above. */
    IO_ERROR(6),

    /** Something this build did not anticipate. A bug here, reported rather than hidden. */
    INTERNAL_ERROR(70);

    /** The value handed to the operating system. */
    private final int code;

    /**
     * Creates a code.
     *
     * @param code The value handed to the operating system
     */
    ExitCode(int code) {
        this.code = code;
    }

    /**
     * Returns the value handed to the operating system.
     *
     * @return The exit code
     */
    public int code() {
        return code;
    }
}
