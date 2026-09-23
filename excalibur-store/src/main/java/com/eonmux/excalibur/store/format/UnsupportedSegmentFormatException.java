package com.eonmux.excalibur.store.format;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A segment file whose data region header is sound and names a format this build does not read.
 *
 * <p>Nothing about such a file is shown to be damaged: its header's magic, reserved field and
 * checksum all agree. What it shows is that a newer build wrote it - or that its version field
 * was rewritten along with a matching checksum, which no accident produces. Either way its
 * records are laid out in a way this build does not know, so it is refused rather than read,
 * and it is left exactly as it was found.</p>
 */
public final class UnsupportedSegmentFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final int version;

    /**
     * Refuses a header, before the file it belongs to is known.
     *
     * @param version The version the header names
     */
    public UnsupportedSegmentFormatException(int version) {
        this(null, version);
    }

    private UnsupportedSegmentFormatException(Path file, int version) {
        super(describe(file, version));
        this.file = file;
        this.version = version;
    }

    /**
     * Names the file the refused header belongs to.
     *
     * @param segmentFile The segment file
     * @return The same refusal, naming the file
     */
    public UnsupportedSegmentFormatException in(Path segmentFile) {
        UnsupportedSegmentFormatException named =
            new UnsupportedSegmentFormatException(segmentFile, version);
        named.setStackTrace(getStackTrace());
        return named;
    }

    /**
     * Returns the file the refused header belongs to.
     *
     * @return The segment file, or null if it was not named
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
        return SegmentFormat.values()[0].version();
    }

    /**
     * Returns the newest version this build reads.
     *
     * @return The newest readable version
     */
    public int newest() {
        SegmentFormat[] formats = SegmentFormat.values();
        return formats[formats.length - 1].version();
    }

    private static String describe(Path file, int version) {
        SegmentFormat[] formats = SegmentFormat.values();
        String subject = file == null ? "The segment file" : "Segment file " + file;
        return subject + " is written at format version " + version + ", which this build "
            + "does not read - it reads versions " + formats[0].version() + " to "
            + formats[formats.length - 1].version() + ". A newer build wrote it; it is "
            + "refused and left untouched, so run a build that reads it";
    }
}
