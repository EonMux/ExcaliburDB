package io.excalibur.core.checksum;

import io.excalibur.core.exception.ChecksumException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CRC32Checksum class.
 */
class CRC32ChecksumTest {

    // Test vectors verified against Python binascii.crc32()
    private static final long HELLO_WORLD_CRC = 0x4A17B156L;
    private static final long HELLO_CRC = 0x3610A686L;
    private static final long PANGRAM_CRC = 0x414FA339L;
    private static final long EMPTY_CRC = 0x00000000L;

    @Test
    public void testComputeByteArray() {
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        long crc = CRC32Checksum.compute(data);
        assertEquals(HELLO_WORLD_CRC, crc, "CRC32 of \"Hello World\" should be 0x4A17B156");
    }

    @Test
    public void testComputeEmptyByteArray() {
        long crc = CRC32Checksum.compute(new byte[0]);
        assertEquals(EMPTY_CRC, crc, "CRC32 of empty array should be 0");
    }

    @Test
    public void testComputeNullByteArray() {
        assertThrows(ChecksumException.class, () -> CRC32Checksum.compute((byte[]) null));
    }

    @Test
    public void testComputeWithOffset() {
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        long crc = CRC32Checksum.compute(data, 6, 5); // "World"

        // Verify by computing "World" directly
        byte[] world = "World".getBytes(StandardCharsets.UTF_8);
        long expectedCrc = CRC32Checksum.compute(world);
        assertEquals(expectedCrc, crc, "CRC32 of \"World\" should match");
    }

    @Test
    public void testComputeWithInvalidOffset() {
        byte[] data = new byte[10];
        assertThrows(ChecksumException.class, () -> CRC32Checksum.compute(data, 100, 10));
    }

    @Test
    public void testComputeString() {
        long crc = CRC32Checksum.compute("Hello World");
        assertEquals(HELLO_WORLD_CRC, crc, "CRC32 of string \"Hello World\" should be 0x4A17B156");
    }

    @Test
    public void testComputeNullString() {
        assertThrows(ChecksumException.class, () -> CRC32Checksum.compute((String) null));
    }

    @Test
    public void testVerify() {
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        long expected = CRC32Checksum.compute(data);

        assertTrue(CRC32Checksum.verify(data, expected), "Verify should return true for correct CRC32");
        assertFalse(CRC32Checksum.verify(data, 0xFFFFFFFFL), "Verify should return false for wrong CRC32");
    }

    @Test
    public void testUpdate() {
        byte[] hello = "Hello ".getBytes(StandardCharsets.UTF_8);
        byte[] world = "World".getBytes(StandardCharsets.UTF_8);

        long crc = CRC32Checksum.update(EMPTY_CRC, hello);
        long crc2 = CRC32Checksum.update(crc, world);

        assertEquals(HELLO_WORLD_CRC, crc2, "Update should produce correct final CRC32");
    }

    @Test
    public void testTestVectors() {
        // Verify official test vectors
        assertEquals(HELLO_WORLD_CRC, CRC32Checksum.compute("Hello World".getBytes(StandardCharsets.UTF_8)));
        assertEquals(HELLO_CRC, CRC32Checksum.compute("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(PANGRAM_CRC, CRC32Checksum.compute(
            "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
        assertEquals(EMPTY_CRC, CRC32Checksum.compute(new byte[0]));
    }

    @Test
    public void testVerifyTestVectors() {
        assertTrue(CRC32Checksum.verifyTestVectors(), "All test vectors should pass");
    }
}
