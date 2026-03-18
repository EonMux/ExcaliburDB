package io.excalibur.core.util;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * CRC32 checksum computation utility.
 *
 * <p>Implements the CRC-32 polynomial algorithm (IEEE 802.3 standard).
 * Uses the polynomial: x^32 + x^26 + x^23 + x^22 + x^16 + x^12 + x^11 + x^10 + x^8 + x^7 + x^5 + x^4 + x^2 + x + 1
 * (0xEDB88320 in reversed byte order)</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. Static methods use immutable algorithms.</p>
 *
 * @author ExcaliburDB
 */
public class CRC32 {

    /**
     * The CRC32 polynomial in reversed byte order (IEEE 802.3 standard).
     * This allows efficient byte-by-byte computation.
     */
    private static final int POLYNOMIAL = 0xEDB88320;

    /**
     * Precomputed CRC32 table for efficient computation.
     * Initialized in static block.
     */
    private static final int[] table;

    static {
        table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) == 1 ? (c >>> 1) ^ POLYNOMIAL : c >>> 1;
            }
            table[i] = c;
        }
    }

    /**
     * Computes the CRC32 checksum of a byte array.
     *
     * @param data The input bytes
     * @return The CRC32 checksum as a 32-bit unsigned integer
     */
    public static long crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    /**
     * Computes the CRC32 checksum of a byte array starting at a specific offset.
     *
     * @param data The input bytes
     * @param offset Starting offset
     * @return The CRC32 checksum as a 32-bit unsigned integer
     */
    public static long crc32(byte[] data, int offset, int length) {
        return crc32(data, offset, length, 0);
    }

    /**
     * Internal implementation of CRC32.
     */
    private static long crc32(byte[] data, int offset, int length, int seed) {
        int crc = seed ^ 0xFFFFFFFF;

        // Process in 8-byte chunks for efficiency
        int i = offset;
        while (i + 7 < offset + length) {
            int v = ((crc >> 24) & 0xFF) * 0x01010101;
            v = table[(v ^ data[i]) & 0xFF] ^ table[(v >>> 8) & 0xFF] ^
                  table[(v >>> 16) & 0xFF] ^ table[(v >>> 24) & 0xFF];
            crc = (crc << 8) ^ v;
            i += 8;
        }

        // Process remaining bytes
        while (i < offset + length) {
            crc = ((crc >> 24) & 0xFF) * 0x01010101 ^ table[(crc ^ data[i]) & 0xFF];
            crc = (crc << 8) ^ ((crc >> 24) & 0xFF);
            crc = (crc << 8) ^ ((crc >> 24) & 0xFF);
            crc = (crc << 8) ^ ((crc >> 24) & 0xFF);
            i++;
        }

        return crc ^ 0xFFFFFFFFL;
    }

    /**
     * Convenience method to compute CRC32 of a MemorySegment.
     *
     * @param segment The memory segment
     * @param offset  Starting offset
     * @param length  Number of bytes to hash
     * @param seed    Initial seed
     * @return The CRC32 checksum
     */
    public static long crc32(MemorySegment segment, long offset, int length, int seed) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                "Invalid range: offset=" + offset + ", length=" + length + ", segment.byteSize()=" + segment.byteSize());
        }

        // Extract bytes from MemorySegment
        byte[] bytes = segment.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
        return crc32(bytes, 0, bytes.length, seed);
    }

    /**
     * Convenience method to compute CRC32 of a MemorySegment without seed.
     *
     * @param segment The memory segment
     * @return The CRC32 checksum
     */
    public static long crc32(MemorySegment segment) {
        return crc32(segment, 0, (int) segment.byteSize(), 0);
    }

    /**
     * Convenience method to compute CRC32 of a MemorySegment with offset and length.
     *
     * @param segment The memory segment
     * @param offset  Starting offset
     * @param length  Number of bytes to hash
     * @return The CRC32 checksum
     */
    public static long crc32(MemorySegment segment, long offset, int length) {
        return crc32(segment, offset, length, 0);
    }

    /**
     * Convenience method to compute CRC32 of a MemorySegment with seed.
     *
     * @param segment The memory segment
     * @param seed    Initial seed
     * @return The CRC32 checksum
     */
    public static long crc32(MemorySegment segment, int seed) {
        return crc32(segment, 0, (int) segment.byteSize(), seed);
    }
}
