package com.eonmux.excalibur.graph.file;

import java.io.IOException;

/**
 * An append-only log refused for damage its reader cannot survive, as opposed to one it could not
 * read at all.
 *
 * <h2>Why the distinction needs a type</h2>
 * <p>A log's reader throws {@link IOException} for several unrelated reasons: the operating system
 * refused the read, the header names a version this build does not know, or a record failed its
 * checksum with sound records written after it. Only the last is a statement about the bytes. The
 * first says something about the device, and the second about which build wrote the file - a newer
 * one, most likely, whose log is not damaged at all. Anything that has to act on the difference,
 * above all an offline checker deciding between "corrupt" and "could not be judged", would
 * otherwise be left parsing messages, and a reworded message would silently move a file from one
 * verdict to the other.</p>
 *
 * <p>Raised by {@link TailIntegrity#requireTornTail} for damage with an intact record behind it,
 * and by the triple log for a whole record whose checksum fails. Both are raised on the same terms
 * whether the log is being opened, replayed, rewritten or merely inspected, which is what lets an
 * inspection's verdict stand for what the node would do.</p>
 *
 * @author ExcaliburDB
 */
public final class LogCorruptionException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What is damaged and where, phrased for the operator who has to act on it
     */
    public LogCorruptionException(String message) {
        super(message);
    }
}
