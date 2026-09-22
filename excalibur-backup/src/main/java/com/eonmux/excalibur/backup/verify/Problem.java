package com.eonmux.excalibur.backup.verify;

import java.util.Objects;

/**
 * One way a snapshot failed to be what its manifest says it is.
 *
 * @param path   The file, relative to the payload root, or the empty string for the image
 * @param detail What is wrong, in a sentence
 * @author ExcaliburDB
 */
public record Problem(String path, String detail) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if either argument is null
     */
    public Problem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(detail, "detail");
    }
}
