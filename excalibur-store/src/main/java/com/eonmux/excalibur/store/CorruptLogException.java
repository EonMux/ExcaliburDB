package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Thrown when a write-ahead log is damaged somewhere other than its tail.
 *
 * <p>A tail torn by a crash is ordinary and recoverable: the records after the last complete
 * one were never acknowledged, so a log that ends early there has lost nothing anyone was told
 * had happened. Damage with sound records after it is the opposite - every one of those
 * records was fsynced and acknowledged - and there is no way to replay them, because the
 * damaged record may be the very update whose effect they assume.</p>
 *
 * <p>Opening such a log would mean silently choosing one of two wrong answers: replay up to
 * the damage and lose every acknowledged write after it, or skip the damage and apply the rest
 * out of a state that never existed. Refusing to open it is the only answer that leaves the
 * evidence intact for an operator to act on, and it is fatal by design - the next append would
 * otherwise overwrite the damaged region and destroy the only record of what was lost.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class CorruptLogException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path logFilePath;
    private final long damageOffset;

    /**
     * Creates a new corruption failure.
     *
     * @param logFilePath  The log that cannot be opened
     * @param damageOffset Offset of the first record that could not be read
     * @param message      What was found, and where
     */
    public CorruptLogException(Path logFilePath, long damageOffset, String message) {
        super(message);
        this.logFilePath = logFilePath;
        this.damageOffset = damageOffset;
    }

    /**
     * Returns the log that could not be opened.
     *
     * @return The log file path
     */
    public Path logFilePath() {
        return logFilePath;
    }

    /**
     * Returns the offset of the first unreadable record.
     *
     * @return Byte offset within the log file
     */
    public long damageOffset() {
        return damageOffset;
    }
}
