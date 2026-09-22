package com.eonmux.excalibur.core.integrity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The verdict of verifying one persisted index file.
 *
 * <p>Verification reports rather than throws, because the tooling that will consume it has to
 * survey every file a node owns and say which ones are damaged. {@link #requireIntact()} is the
 * loading path's use of the same verdict: it turns a damaged file into a refusal naming the
 * file, instead of letting the index load and answer queries from rotted bytes.</p>
 *
 * @param file    The file that was inspected, or null when the image was not read from one
 * @param format  Human-readable name of the format, used in the refusal message
 * @param status  What the pass concluded
 * @param detail  What was observed, phrased for an operator reading a log line
 */
public record IntegrityReport(Path file, String format, IntegrityStatus status, String detail) {

    /**
     * Validates the report's components.
     *
     * @throws NullPointerException if format, status or detail is null
     */
    public IntegrityReport {
        Objects.requireNonNull(format, "format cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(detail, "detail cannot be null");
    }

    /**
     * Reports a file whose checksum matched.
     *
     * @param file   The file inspected
     * @param format The format's name
     * @param detail What was checked
     * @return An intact report
     */
    public static IntegrityReport intact(Path file, String format, String detail) {
        return new IntegrityReport(file, format, IntegrityStatus.INTACT, detail);
    }

    /**
     * Reports a file that carries checksum fields but was not sealed by its writer.
     *
     * @param file   The file inspected
     * @param format The format's name
     * @param detail Why the payload could not be verified
     * @return An unsealed report
     */
    public static IntegrityReport unsealed(Path file, String format, String detail) {
        return new IntegrityReport(file, format, IntegrityStatus.UNSEALED, detail);
    }

    /**
     * Reports a file written before this format carried checksums.
     *
     * @param file    The file inspected
     * @param format  The format's name
     * @param version The format version found in the file
     * @return A legacy report
     */
    public static IntegrityReport legacy(Path file, String format, int version) {
        return new IntegrityReport(file, format, IntegrityStatus.LEGACY_UNPROTECTED,
            "written at format version " + version + ", which carries no checksum; "
                + "rebuild or compact the index to obtain one");
    }

    /**
     * Reports a file whose checksum did not match the bytes it covers.
     *
     * @param file   The file inspected
     * @param format The format's name
     * @param detail What mismatched
     * @return A corrupt report
     */
    public static IntegrityReport corrupt(Path file, String format, String detail) {
        return new IntegrityReport(file, format, IntegrityStatus.CORRUPT, detail);
    }

    /**
     * Reports a file that does not exist.
     *
     * @param file   The file looked for
     * @param format The format's name
     * @return A missing report
     */
    public static IntegrityReport missing(Path file, String format) {
        return new IntegrityReport(
            file, format, IntegrityStatus.MISSING, "the file does not exist");
    }

    /**
     * Reports a file that could not be read far enough to reach a verdict.
     *
     * @param file   The file inspected
     * @param format The format's name
     * @param detail What stopped the read
     * @return An unreadable report
     */
    public static IntegrityReport unreadable(Path file, String format, String detail) {
        return new IntegrityReport(file, format, IntegrityStatus.UNREADABLE, detail);
    }

    /**
     * Reports whether the file may be loaded.
     *
     * <p>True for everything except damage: an unsealed or legacy file is unverified, not
     * known-bad, and refusing it would take a node down for the absence of a checksum it was
     * never written with.</p>
     *
     * @return true unless the file is corrupt or unreadable
     */
    public boolean loadable() {
        return status != IntegrityStatus.CORRUPT && status != IntegrityStatus.UNREADABLE;
    }

    /**
     * Reports whether the file's bytes were actually checked against a stored checksum.
     *
     * @return true only when a checksum was present and matched
     */
    public boolean verified() {
        return status == IntegrityStatus.INTACT;
    }

    /**
     * Refuses a damaged file, naming it and saying what can be done about it.
     *
     * @throws IOException if the file is corrupt or unreadable
     */
    public void requireIntact() throws IOException {
        if (loadable()) {
            return;
        }
        String named = file != null ? " file " + file : " image";
        throw new IOException(format + named + " failed its integrity check: " + detail
            + ". It will not be loaded, because serving a damaged index answers queries with "
            + "wrong results instead of failing. Restore the file from a backup or reindex "
            + "this shard.");
    }
}
