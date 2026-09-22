package com.eonmux.excalibur.backup.verify;

import java.util.Objects;

/**
 * What one of the engine's own integrity checkers said about one file.
 *
 * <p>Deliberately not a copy of any of their report types. The storage engine reports a
 * per-entry damage list for a segment; the vector and filter modules report a single
 * verdict per file, in two identically shaped but unrelated record types. Flattening them
 * to a verdict and a sentence is what lets one verification report cover an image that
 * contains all three.</p>
 *
 * @param path    The file, relative to the payload root
 * @param format  Which structure it is: segment, hnsw, docmap, codebook, lsh
 * @param healthy Whether the checker found nothing wrong
 * @param detail  What it found, whether or not anything was wrong
 * @author ExcaliburDB
 */
public record ContentCheck(String path, String format, boolean healthy, String detail) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if any argument is null
     */
    public ContentCheck {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(detail, "detail");
    }
}
