package com.eonmux.excalibur.backup.io;

import java.util.Objects;

/**
 * What one pass over a file found: how long it is and what it hashes to.
 *
 * <p>Two checksums rather than one, computed together so the file is read once. CRC32C is
 * what every routine verification uses - it is hardware-accelerated, it runs at memory
 * bandwidth, and it detects the damage that actually happens to a stored image: a flipped
 * bit, a truncated write, a block returned from the wrong offset. SHA-256 is what a deep
 * verification uses, and it is the one that means something when the question is not
 * "did this rot" but "is this the image I took", because a CRC can be forged and a
 * cryptographic digest cannot.</p>
 *
 * @param size    The file's length in bytes
 * @param crc32c  CRC32C of every byte, lowercase hex, eight characters
 * @param sha256  SHA-256 of every byte, lowercase hex, sixty-four characters
 * @author ExcaliburDB
 */
public record FileDigest(long size, String crc32c, String sha256) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if either checksum is null
     * @throws IllegalArgumentException if the size is negative or a checksum is the wrong
     *                                  length for its algorithm
     */
    public FileDigest {
        Objects.requireNonNull(crc32c, "crc32c");
        Objects.requireNonNull(sha256, "sha256");
        if (size < 0) {
            throw new IllegalArgumentException("A file cannot be " + size + " bytes long");
        }
        if (crc32c.length() != 8) {
            throw new IllegalArgumentException("A CRC32C is eight hex characters, not "
                + crc32c.length());
        }
        if (sha256.length() != 64) {
            throw new IllegalArgumentException("A SHA-256 is sixty-four hex characters, not "
                + sha256.length());
        }
    }
}
