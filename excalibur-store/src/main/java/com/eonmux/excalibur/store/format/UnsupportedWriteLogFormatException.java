package com.eonmux.excalibur.store.format;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A write-ahead log whose header is sound and names a format this build does not read.
 *
 * <p>Nothing about such a log is shown to be damaged: its magic, reserved field and checksum
 * all agree. What it shows is that a newer build wrote it. Its records may be laid out in a way
 * this build does not know, and they are the only copy of every write the segment accepted
 * since its last compaction - so it is neither replayed nor truncated nor given a header of
 * this build's, and the segment it belongs to is not opened. The file is left exactly as it
 * was found.</p>
 *
 * <p>Deliberately not a {@link com.eonmux.excalibur.store.CorruptLogException}: a damaged log
 * calls for a backup, a newer one for the build that wrote it.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class UnsupportedWriteLogFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final int version;

    /**
     * Refuses a log.
     *
     * @param file    The log
     * @param version The version its header names
     */
    public UnsupportedWriteLogFormatException(Path file, int version) {
        super(describe(file, version));
        this.file = file;
        this.version = version;
    }

    /**
     * Returns the log that was refused.
     *
     * @return The log file
     */
    public Path file() {
        return file;
    }

    /**
     * Returns the version the header names.
     *
     * @return The refused version
     */
    public int version() {
        return version;
    }

    /**
     * Returns the oldest version this build reads.
     *
     * @return The oldest readable version
     */
    public int oldest() {
        return WriteLogFormat.oldest();
    }

    /**
     * Returns the newest version this build reads.
     *
     * @return The newest readable version
     */
    public int newest() {
        return WriteLogFormat.newest();
    }

    private static String describe(Path file, int version) {
        return "Write-ahead log " + file + " is written at format version " + version
            + ", which this build does not read - it reads versions " + WriteLogFormat.oldest()
            + " to " + WriteLogFormat.newest() + ". Its header's checksum matches, so it is not "
            + "damaged: a newer build wrote it. It is refused and left untouched - not replayed, "
            + "not truncated - so run a build that reads it";
    }
}
