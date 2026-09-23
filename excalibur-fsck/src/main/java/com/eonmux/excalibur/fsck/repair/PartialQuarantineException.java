package com.eonmux.excalibur.fsck.repair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A move into quarantine that got part of the way.
 *
 * <p>Separate from a plain {@link IOException} because the two leave different things behind,
 * and an operator reading the outcome acts on what it says. A plain failure means nothing of
 * the file reached quarantine and the original is where it was. This one means a copy of it
 * is in quarantine - under the name {@link #copy()} gives - and the message says what did not
 * follow: the original could not be removed, or a directory could not be forced.</p>
 */
public final class PartialQuarantineException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Where the copy in quarantine is; a path rather than a file, so not serialized. */
    private final transient Path copy;

    /**
     * Records a move that got part of the way.
     *
     * @param copy    Where the file's copy in quarantine is
     * @param message What happened and what did not, naming the copy
     * @param cause   The failure that stopped it
     */
    PartialQuarantineException(Path copy, String message, IOException cause) {
        super(message, cause);
        this.copy = Objects.requireNonNull(copy, "copy");
    }

    /**
     * Returns where the file's copy in quarantine is.
     *
     * @return The copy's path
     */
    public Path copy() {
        return copy;
    }
}
