package com.eonmux.excalibur.backup.restore;

import java.io.IOException;
import java.util.Objects;

/**
 * A restore this tool declined to perform.
 *
 * <p>The target is untouched when this is thrown. That is the point of it: the checks that
 * raise it all run before a single byte is written, and the write itself is one rename.</p>
 *
 * @author ExcaliburDB
 */
public final class RestoreRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why a restore was refused. */
    public enum Reason {

        /**
         * The image did not verify.
         *
         * <p>Either its manifest could not be believed, or a file it names is missing,
         * truncated or damaged. Restoring from it would overwrite a data directory with a
         * known-bad one, and discovering the damage afterwards - with the original gone -
         * is the exact failure a verified backup exists to prevent.</p>
         */
        SNAPSHOT_DOES_NOT_VERIFY,

        /**
         * The target holds something.
         *
         * <p>Most of the time this is the wrong target. When it is the right one, saying
         * so explicitly is a second of work against the alternative.</p>
         */
        TARGET_NOT_EMPTY
    }

    /** Why this one was refused. */
    private final transient Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason Why the restore was refused
     * @param detail What the operator should know, including what to do instead
     */
    public RestoreRefusedException(Reason reason, String detail) {
        super(detail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why the restore was refused.
     *
     * @return The reason
     */
    public Reason reason() {
        return reason;
    }
}
