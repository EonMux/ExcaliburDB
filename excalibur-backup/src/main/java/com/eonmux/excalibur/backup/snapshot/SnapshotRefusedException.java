package com.eonmux.excalibur.backup.snapshot;

import java.io.IOException;
import java.util.Objects;

/**
 * A snapshot this tool declined to produce.
 *
 * <p>Separate from an I/O failure because the two mean different things to whoever is
 * reading the exit code: an I/O failure is a thing to retry, and a refusal is a thing to
 * change your mind about.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why a snapshot was refused. */
    public enum Reason {

        /** The destination already holds something, and a snapshot never merges. */
        DESTINATION_NOT_EMPTY,

        /**
         * The data directory changed while it was being copied.
         *
         * <p>Which means a node was running, whatever anyone believed, and the image is
         * torn: files copied early describe a moment the files copied late have moved past.
         * Stop the node and take it again, or pass the flag that says an image of unknown
         * internal agreement is better than none.</p>
         */
        SOURCE_CHANGED_UNDERNEATH,

        /** A file was copied and the copy did not read back as the original. */
        COPY_DID_NOT_MATCH
    }

    /** Why this one was refused. */
    private final transient Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason Why the snapshot was refused
     * @param detail What the operator should know
     */
    public SnapshotRefusedException(Reason reason, String detail) {
        super(detail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why the snapshot was refused.
     *
     * @return The reason
     */
    public Reason reason() {
        return reason;
    }
}
