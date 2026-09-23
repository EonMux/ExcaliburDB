package com.eonmux.excalibur.backup.verify;

import java.util.Objects;

/**
 * What the checker for one file's format said about it.
 *
 * <p>Deliberately not a copy of any of their report types. The storage engine reports a
 * per-entry damage list for a segment; the vector and filter modules report a single
 * verdict per file; {@code excalibur-fsck}'s checkers report one of its own statuses. Flattening
 * them to two verdicts and a sentence is what lets one verification report cover an image
 * that contains all of them.</p>
 *
 * <h2>Two verdicts, not one</h2>
 * <p>{@code healthy} is whether the checker found anything wrong, and {@code proven} is
 * whether it could have. They differ for every format that carries no checksum - a routing
 * directory, a registry marked only with a record count, the in-flight ingest journal, a
 * lexical store sidecar - and for a file sealed at a format version that predates its seal.
 * Such a file is healthy, because nothing was found, and unproven, because nothing could
 * have been. Reporting one verdict would make those files read as verified, which they were
 * not.</p>
 *
 * @param path    The file, relative to the payload root
 * @param format  Which structure it is
 * @param healthy Whether the checker found nothing wrong
 * @param proven  Whether the checker positively established the file's contents, against a
 *                checksum or seal the format carries; never true when {@code healthy} is
 *                false
 * @param detail  What it found, whether or not anything was wrong
 * @author ExcaliburDB
 */
public record ContentCheck(String path, String format, boolean healthy, boolean proven,
                           String detail) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if the file is called proven and not healthy
     */
    public ContentCheck {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(detail, "detail");
        if (proven && !healthy) {
            throw new IllegalArgumentException("a damaged file cannot be proven: " + path);
        }
    }
}
