package com.eonmux.excalibur.graph.format;

/**
 * One column's length and the CRC32C of its contents, as a build wrote them.
 *
 * <p>Recorded in {@link EdgeStoreDescriptor} rather than beside the column, because a column is a
 * bare array whose every byte is an element: a checksum in the file would move every element off
 * the alignment the traversal depends on. The descriptor is written last and moved into place
 * atomically, so a digest can never become visible ahead of the bytes it describes.</p>
 *
 * @param length   How many bytes the column held when it was written
 * @param checksum The CRC32C of those bytes, as an unsigned value in a long
 */
public record ColumnDigest(long length, long checksum) {

    /**
     * Validates the digest.
     *
     * @throws IllegalArgumentException if the length is negative or the checksum is not a CRC32C
     */
    public ColumnDigest {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
        if (checksum < 0 || checksum > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("checksum is not a 32-bit CRC: " + checksum);
        }
    }
}
