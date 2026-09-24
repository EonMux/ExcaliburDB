package com.eonmux.excalibur.store.format;

/**
 * On-disk format of a {@code segment_N.wal} write-ahead log.
 *
 * <p>The records themselves are the same in every version - {@link LogEntry} describes them,
 * and each carries its own CRC. What the versions differ in is whether the file says what it
 * is. A log's format is decided when the file is created and never changes while it holds a
 * record, because the header would have to be written where the first record already is.</p>
 *
 * <h2>V1 - headerless</h2>
 * <p>The first record starts at offset 0. Nothing in the file names its format, so a build
 * that changed the record layout could not tell an old log from a damaged new one - which is
 * what the header exists to prevent. A V1 log is still read and appended to, and is replaced
 * by a V2 one when a compaction or a split gives its segment a fresh log; one that holds no
 * record is given a header as soon as it is opened.</p>
 *
 * <h2>V2 - versioned</h2>
 * <p>A {@link WriteLogHeader} occupies the first {@value WriteLogHeader#SIZE} bytes and the
 * first record starts after it. The offsets a log hands out and takes back - its head, the
 * integrated offset recorded against it, the positions a tail scan reads - are positions in
 * the record stream and do not include the header, so a V1 log and a V2 log holding the same
 * records report the same offsets.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public enum WriteLogFormat {

    /** Headerless: records from offset 0. Read and appended to, but never created. */
    V1(1, false),

    /** The current format: a versioned, checksummed header, then the records. */
    V2(2, true);

    /** The format every newly created log is written in. */
    public static final WriteLogFormat CURRENT = V2;

    private final int version;
    private final boolean hasHeader;

    WriteLogFormat(int version, boolean hasHeader) {
        this.version = version;
        this.hasHeader = hasHeader;
    }

    /**
     * Returns the version number a header names this format by.
     *
     * @return The version number
     */
    public int version() {
        return version;
    }

    /**
     * Reports whether a log in this format begins with a {@link WriteLogHeader}.
     *
     * @return true if the first record follows a header
     */
    public boolean hasHeader() {
        return hasHeader;
    }

    /**
     * Returns the offset in the file at which a log in this format holds its first record.
     *
     * @return {@value WriteLogHeader#SIZE} for a headed log, 0 for a headerless one
     */
    public int recordsStart() {
        return hasHeader ? WriteLogHeader.SIZE : 0;
    }

    /**
     * Finds the format a header version names.
     *
     * <p>Only a format that has a header can be named by one: V1 is identified by the header's
     * absence, so a header claiming version 1 was not written by any build and is treated like
     * any other version this build does not read.</p>
     *
     * @param version The version a header names
     * @return The format, or null if no headed format this build reads has that version
     */
    public static WriteLogFormat forHeaderVersion(int version) {
        for (WriteLogFormat format : values()) {
            if (format.hasHeader && format.version == version) {
                return format;
            }
        }
        return null;
    }

    /**
     * Returns the oldest version this build reads.
     *
     * @return The oldest readable version
     */
    public static int oldest() {
        return values()[0].version;
    }

    /**
     * Returns the newest version this build reads.
     *
     * @return The newest readable version
     */
    public static int newest() {
        WriteLogFormat[] formats = values();
        return formats[formats.length - 1].version;
    }
}
