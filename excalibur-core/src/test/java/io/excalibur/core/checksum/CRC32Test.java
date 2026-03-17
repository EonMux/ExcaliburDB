package io.excalibur.core.checksum;

/**
 * Simple test for CRC32Checksum functionality.
 * Uses verified test vectors from Python's binascii.crc32().
 */
public class CRC32Test {
    public static void main(String[] args) {
        System.out.println("CRC32 Test Vector Verification");
        System.out.println("==============================");

        // Test CRC32 of "Hello World"
        // Verified: Python binascii.crc32(b"Hello World") = 0x4a17b156
        byte[] data = "Hello World".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long crc = CRC32Checksum.compute(data);
        System.out.println("CRC32 of \"Hello World\": " + Long.toHexString(crc & 0xFFFFFFFFL));
        System.out.println("Expected: 0x4a17b156");
        boolean pass1 = (0x4A17B156L == (crc & 0xFFFFFFFFL));
        System.out.println("Match: " + pass1);

        // Test CRC32 of "hello"
        // Verified: Python binascii.crc32(b"hello") = 0x3610a686
        byte[] hello = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        crc = CRC32Checksum.compute(hello);
        System.out.println("\nCRC32 of \"hello\": " + Long.toHexString(crc & 0xFFFFFFFFL));
        System.out.println("Expected: 0x3610a686");
        boolean pass2 = (0x3610A686L == (crc & 0xFFFFFFFFL));
        System.out.println("Match: " + pass2);

        // Test empty input
        // CRC32 of empty string is 0x00000000
        crc = CRC32Checksum.compute(new byte[0]);
        System.out.println("\nCRC32 of empty: " + Long.toHexString(crc & 0xFFFFFFFFL));
        System.out.println("Expected: 0x00000000");
        boolean pass3 = (0x00000000L == (crc & 0xFFFFFFFFL));
        System.out.println("Match: " + pass3);

        // Test verify
        System.out.println("\nVerification test:");
        boolean pass4 = CRC32Checksum.verify(data, CRC32Checksum.compute(data));
        System.out.println("verify(data, correct_crc): " + pass4);

        if (pass1 && pass2 && pass3 && pass4) {
            System.out.println("\nAll tests passed!");
        } else {
            System.out.println("\nSome tests FAILED!");
            System.exit(1);
        }
    }
}
