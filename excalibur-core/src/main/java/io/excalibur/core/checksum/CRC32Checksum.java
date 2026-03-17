package io.excalibur.core.checksum;

import io.excalibur.core.exception.ChecksumException;

import java.nio.charset.StandardCharsets;

/**
 * CRC32 checksum computation for write-log entry validation.
 * Implements the standard IEEE 802.3 CRC-32 polynomial (0xEDB88320).
 *
 * <p>This checksum is used for crash recovery to verify write-log integrity.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All static methods are thread-safe for concurrent access.</p>
 *
 * @author ExcaliburDB
 */
public class CRC32Checksum {

    private static final int POLYNOMIAL = 0xEDB88320;
    private static final int TABLE_SIZE = 256;

    // Lookup table - MUST be int[], not byte[]
    private static final int[] TABLE = new int[TABLE_SIZE];

    static {
        // Precompute CRC32 lookup table using reflected polynomial
        for (int i = 0; i < TABLE_SIZE; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ POLYNOMIAL;
                } else {
                    crc >>>= 1;
                }
            }
            TABLE[i] = crc;
        }
    }

    /**
     * Computes CRC32 of a byte array.
     *
     * @param data Byte array to checksum
     * @return CRC32 checksum value (unsigned 32-bit stored in long)
     * @throws ChecksumException if data is null
     */
    public static long compute(byte[] data) {
        if (data == null) {
            throw new ChecksumException("Data cannot be null");
        }
        return compute(data, 0, data.length);
    }

    /**
     * Computes CRC32 of a byte array starting at offset.
     *
     * @param data   Byte array to checksum
     * @param offset Starting offset
     * @param length Number of bytes to checksum
     * @return CRC32 checksum value (unsigned 32-bit stored in long)
     * @throws ChecksumException if offset/length is invalid
     */
    public static long compute(byte[] data, int offset, int length) {
        if (data == null) {
            throw new ChecksumException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new ChecksumException("Invalid offset or length");
        }

        int crc = 0xFFFFFFFF;

        for (int i = offset; i < offset + length; i++) {
            int index = (crc ^ (data[i] & 0xFF)) & 0xFF;
            crc = (crc >>> 8) ^ TABLE[index];
        }

        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    /**
     * Computes CRC32 of a String (UTF-8 encoded).
     *
     * @param str String to checksum
     * @return CRC32 checksum value (unsigned 32-bit)
     * @throws ChecksumException if string is null
     */
    public static long compute(String str) {
        if (str == null) {
            throw new ChecksumException("String cannot be null");
        }
        if (str.isEmpty()) {
            return compute(new byte[0]);
        }
        return compute(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies CRC32 of a byte array against expected value.
     *
     * @param data     Byte array to verify
     * @param expected Expected CRC32 value
     * @return true if checksum matches, false otherwise
     * @throws ChecksumException if data is null
     */
    public static boolean verify(byte[] data, long expected) {
        if (data == null) {
            throw new ChecksumException("Data cannot be null");
        }
        return compute(data) == expected;
    }

    /**
     * Verifies CRC32 of a byte array starting at offset.
     *
     * @param data     Byte array to verify
     * @param offset   Starting offset
     * @param length   Number of bytes to verify
     * @param expected Expected CRC32 value
     * @return true if checksum matches, false otherwise
     * @throws ChecksumException if offset/length is invalid
     */
    public static boolean verify(byte[] data, int offset, int length, long expected) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new ChecksumException("Invalid offset or length");
        }
        return compute(data, offset, length) == expected;
    }

    /**
     * Updates CRC32 state with new data.
     * Use this for incremental CRC computation.
     *
     * @param previousCrc Previous CRC32 value (as returned by compute())
     * @param data        Data to update with
     * @return Updated CRC32 value
     * @throws ChecksumException if data is null
     */
    public static long update(long previousCrc, byte[] data) {
        if (data == null) {
            throw new ChecksumException("Data cannot be null");
        }
        return update(previousCrc, data, 0, data.length);
    }

    /**
     * Updates CRC32 state with data at offset.
     *
     * @param previousCrc Previous CRC32 value (as returned by compute())
     * @param data        Data source
     * @param offset      Starting offset
     * @param length      Number of bytes to update
     * @return Updated CRC32 value
     * @throws ChecksumException if offset/length is invalid
     */
    public static long update(long previousCrc, byte[] data, int offset, int length) {
        if (data == null) {
            throw new ChecksumException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new ChecksumException("Invalid offset or length");
        }

        // Undo the finalization from previous CRC
        int crc = (int) (previousCrc ^ 0xFFFFFFFFL);

        for (int i = offset; i < offset + length; i++) {
            int index = (crc ^ (data[i] & 0xFF)) & 0xFF;
            crc = (crc >>> 8) ^ TABLE[index];
        }

        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    /**
     * Gets the internal CRC32 table for testing purposes.
     *
     * @return Copy of the lookup table
     */
    static int[] getTable() {
        return TABLE.clone();
    }

    /**
     * Verifies the implementation against known test vectors.
     * Test vectors verified against Python's binascii.crc32().
     *
     * @return true if all test vectors pass
     */
    public static boolean verifyTestVectors() {
        boolean allPassed = true;

        // Test vector: empty input
        // CRC32 of empty string is 0x00000000
        allPassed &= check("empty", compute(new byte[0]), 0x00000000L);

        // Test vector: "Hello World"
        // Verified: Python binascii.crc32(b"Hello World") = 0x4a17b156
        byte[] helloWorld = "Hello World".getBytes(StandardCharsets.UTF_8);
        allPassed &= check("Hello World", compute(helloWorld), 0x4A17B156L);

        // Test vector: "hello"
        // Verified: Python binascii.crc32(b"hello") = 0x3610a686
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        allPassed &= check("hello", compute(hello), 0x3610A686L);

        // Test vector: "The quick brown fox jumps over the lazy dog"
        // Verified: Python binascii.crc32(b"The quick brown fox jumps over the lazy dog") = 0x414fa339
        byte[] pangram = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        allPassed &= check("pangram", compute(pangram), 0x414FA339L);

        // Test vector: single byte 0x00
        // Verified: Python binascii.crc32(b"\\x00") = 0xd202ef8d
        allPassed &= check("byte 0x00", compute(new byte[]{0x00}), 0xD202EF8DL);

        // Test vector: single byte 0xFF
        // Verified: Python binascii.crc32(b"\\xff") = 0xff000000
        allPassed &= check("byte 0xFF", compute(new byte[]{(byte) 0xFF}), 0xFF000000L);

        // Test incremental update
        long crc1 = compute(hello);
        long crc2 = update(0L, hello);
        allPassed &= check("incremental", crc2, crc1);

        // Test partial update
        long partialCrc = compute(hello, 0, 2); // "he"
        partialCrc = update(partialCrc, hello, 2, 3); // "llo"
        allPassed &= check("partial update", partialCrc, crc1);

        if (allPassed) {
            System.out.println("All CRC32 test vectors PASSED");
        } else {
            System.err.println("Some CRC32 test vectors FAILED");
        }

        return allPassed;
    }

    private static boolean check(String description, long actual, long expected) {
        if (actual != expected) {
            System.err.printf("FAILED: CRC32(%s) = 0x%08X, expected 0x%08X%n",
                description, actual, expected);
            return false;
        }
        return true;
    }

    /**
     * Main method for testing.
     */
    public static void main(String[] args) {
        System.out.println("CRC32 Test Vector Verification");
        System.out.println("==============================");
        boolean passed = verifyTestVectors();
        System.exit(passed ? 0 : 1);
    }
}
