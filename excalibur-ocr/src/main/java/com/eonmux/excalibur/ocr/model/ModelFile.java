package com.eonmux.excalibur.ocr.model;

import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One file of a pinned model set: its name, and the length and SHA-256 digest it must have.
 *
 * @param name   The file's name in the model directory and the repository
 * @param size   Its length in bytes
 * @param sha256 Its SHA-256 digest, 64 lowercase hex digits
 */
public record ModelFile(String name, long size, String sha256) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /**
     * @throws IllegalArgumentException if the name is not a plain file name, the size is not
     *                                  positive or the digest is not 64 lowercase hex digits
     */
    public ModelFile {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (!NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new IllegalArgumentException("not a plain file name: " + name);
        }
        if (size < 1) {
            throw new IllegalArgumentException(name + ": size must be positive, got " + size);
        }
        if (!DIGEST.matcher(sha256).matches()) {
            throw new IllegalArgumentException(name + ": not a SHA-256 digest: " + sha256);
        }
    }

    /**
     * The digest as bytes.
     *
     * @return The 32 bytes of the digest
     */
    byte[] digest() {
        return HexFormat.of().parseHex(sha256);
    }
}
