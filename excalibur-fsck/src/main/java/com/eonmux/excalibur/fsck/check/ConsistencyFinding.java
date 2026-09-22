package com.eonmux.excalibur.fsck.check;

import java.util.Objects;

/**
 * Something wrong between files rather than inside one.
 *
 * <p>A file can pass its own checksum and still be wrong for the store it is in: a catalog
 * record naming a segment that is not on disk, a segment file no record names, a vector
 * index belonging to a segment that no longer exists. None of that is visible to a
 * per-file check, and all of it decides whether a node can serve.</p>
 *
 * @param kind     Machine-readable kind, stable enough to alert on
 * @param subject  What the finding is about, usually a file name
 * @param severity Whether this is damage or a warning
 * @param detail   What is wrong, phrased for an operator
 */
public record ConsistencyFinding(
    String kind,
    String subject,
    FindingSeverity severity,
    String detail) {

    /**
     * Validates the finding's components.
     *
     * @throws NullPointerException if any component is null
     */
    public ConsistencyFinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(detail, "detail");
    }
}
