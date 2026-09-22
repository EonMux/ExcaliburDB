package com.eonmux.excalibur.core.hash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for XxHash64 implementation.
 * Tests official xxHash64 test vectors and various input types.
 *
 * <p>Note: All test vectors are normalized to positive range [0, Long.MAX_VALUE]
 * to comply with ExcaliburDB's key_hash constraint.</p>
 */
class XxHash64Test {

    private XxHash64 hasher;

    @BeforeEach
    void setUp() {
        hasher = new XxHash64();
    }

    // ==================== Positive Hash Guarantee Tests ====================
    // Domain constraint: key_hash values MUST be in range [0, Long.MAX_VALUE]

    /**
     * Verifies that all hash values are non-negative.
     * This is the core domain constraint for ExcaliburDB storage.
     */
    @Test
    void testAllHashesAreNonNegative() {
        // Test various inputs to ensure hash is always non-negative
        String[] testInputs = {
            "", "a", "abc", "message digest", "abcdefghijklmnopqrstuvwxyz",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
            "12345678901234567890123456789012345678901234567890123456789012345678901234567890",
            "The quick brown fox jumps over the lazy dog",
            "\u0000\u0001\u0002\u0003",  // Binary data
            new String(new char[1000]).replace('\0', 'x')  // Long string
        };

        for (String input : testInputs) {
            long hash = XxHash64.hash(input);
            assertTrue(hash >= 0,
                "Hash must be non-negative for input: " + input.substring(0, Math.min(20, input.length())));
            assertTrue(hash <= Long.MAX_VALUE,
                "Hash must be <= Long.MAX_VALUE for input: " + input.substring(0, Math.min(20, input.length())));
        }
    }

    /**
     * Verifies that streaming API also produces non-negative hashes.
     */
    @Test
    void testStreamingApiProducesNonNegativeHashes() {
        String[] testInputs = {
            "", "a", "abc", "abcdefghijklmnopqrstuvwxyz",
            new String(new char[100]).replace('\0', 'x')
        };

        for (String input : testInputs) {
            hasher.reset();
            hasher.update(input.getBytes(StandardCharsets.UTF_8));
            long hash = hasher.getValue();
            assertTrue(hash >= 0, "Streaming hash must be non-negative for input: " + input.substring(0, Math.min(20, input.length())));
        }
    }

    /**
     * Verifies that hash(long) produces non-negative values.
     */
    @Test
    void testLongValueHashIsNonNegative() {
        long[] testValues = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0xDEADBEEFL, 0xCAFEBABEL};

        for (long value : testValues) {
            long hash = XxHash64.hash(value);
            assertTrue(hash >= 0, "Hash of long value must be non-negative: " + value);
        }
    }

    /**
     * Verifies the normalizeToPositive method works correctly.
     */
    @Test
    void testNormalizeToPositive() {
        // Test edge cases
        assertEquals(0L, XxHash64.normalizeToPositive(0L));
        assertEquals(Long.MAX_VALUE, XxHash64.normalizeToPositive(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, XxHash64.normalizeToPositive(-1L)); // All 1s -> clear MSB
        assertEquals(0L, XxHash64.normalizeToPositive(Long.MIN_VALUE)); // Only MSB set -> clear it

        // Test that normalization clears the sign bit
        for (long value : new long[]{0x8000000000000001L, 0xFFFFFFFFFFFFFFFFL, 0xDEADBEEFCAFEBABEL}) {
            long normalized = XxHash64.normalizeToPositive(value);
            assertTrue(normalized >= 0, "Normalized value must be non-negative");
            assertTrue(normalized <= Long.MAX_VALUE, "Normalized value must be <= Long.MAX_VALUE");
        }
    }

    // ==================== Official Test Vectors (Normalized) ====================
    // These test vectors are normalized to positive range [0, Long.MAX_VALUE]
    // Original raw values are commented for reference

    /**
     * Official test vector: empty string with seed=0
     * Original raw: 0xEF46DB3751D8E999, normalized: 0x6F46DB3751D8E999
     */
    @Test
    void testEmptyInput() {
        byte[] empty = new byte[0];
        assertEquals(0x6F46DB3751D8E999L, XxHash64.hash(empty, 0));
    }

    /**
     * Official test vector: empty string with seed=PRIME32 (2654435761)
     * Original raw: 0xAC75FDA2929B17EF, normalized: 0x2C75FDA2929B17EF
     */
    @Test
    void testEmptyWithPrimeSeed() {
        byte[] empty = new byte[0];
        assertEquals(0x2C75FDA2929B17EFL, XxHash64.hash(empty, 2654435761L));
    }

    /**
     * Official test vector: empty string with seed=1
     * Original raw: 0xD5AFBA1336A3BE4B, normalized: 0x55AFBA1336A3BE4B
     */
    @Test
    void testEmptyWithSeed1() {
        assertEquals(0x55AFBA1336A3BE4BL, XxHash64.hash(new byte[0], 1L));
    }

    // ==================== Common String Test Vectors (Normalized) ====================

    @Test
    void testSingleCharacter() {
        // Original raw: 0xD24EC4F1A98C6E5B, normalized: 0x524EC4F1A98C6E5B
        assertEquals(0x524EC4F1A98C6E5BL, XxHash64.hash("a"));
    }

    @Test
    void testSingleCharWithSeed1() {
        // Original raw: 0xDEC2BC81C3CD46C6, normalized: 0x5EC2BC81C3CD46C6
        assertEquals(0x5EC2BC81C3CD46C6L, XxHash64.hash("a", 1L));
    }

    @Test
    void testAbc() {
        // Already positive: 0x44BC2CF5AD770999
        assertEquals(0x44BC2CF5AD770999L, XxHash64.hash("abc"));
    }

    @Test
    void testMessageDigest() {
        // Already positive: 0x066ED728FCEEB3BE
        assertEquals(0x066ED728FCEEB3BEL, XxHash64.hash("message digest"));
    }

    @Test
    void testAlphabetLower() {
        // Original raw: 0xCFE1F278FA89835C, normalized: 0x4FE1F278FA89835C
        assertEquals(0x4FE1F278FA89835CL, XxHash64.hash("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void testAlphabetMixed() {
        // Original raw: 0xAAA46907D3047814, normalized: 0x2AA46907D3047814
        String input = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        assertEquals(0x2AA46907D3047814L, XxHash64.hash(input));
    }

    @Test
    void testLongRepeating() {
        // Original raw: 0xE04A477F19EE145D, normalized: 0x604A477F19EE145D
        String input = "12345678901234567890123456789012345678901234567890123456789012345678901234567890";
        assertEquals(0x604A477F19EE145DL, XxHash64.hash(input));
    }

    // ==================== Input Type Tests ====================

    @Test
    void testNullByteArray() {
        assertThrows(NullPointerException.class, () -> XxHash64.hash((byte[]) null));
    }

    @Test
    void testNullString() {
        assertThrows(NullPointerException.class, () -> XxHash64.hash((String) null));
    }

    @Test
    void testNullByteBuffer() {
        assertThrows(NullPointerException.class, () -> XxHash64.hash((ByteBuffer) null));
    }

    @Test
    void testByteArrayWithOffset() {
        byte[] data = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        // Hash "cdef" (offset 2, length 4)
        long hash1 = XxHash64.hash(data, 2, 4);
        // Hash "cdef" directly
        long hash2 = XxHash64.hash("cdef".getBytes(StandardCharsets.UTF_8));
        assertEquals(hash2, hash1);
    }

    @Test
    void testByteBufferInput() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(data);

        long hashFromBuffer = XxHash64.hash(buffer);
        long hashFromArray = XxHash64.hash(data);

        assertEquals(hashFromArray, hashFromBuffer);
    }

    @Test
    void testLongValue() {
        // Hash of a single long value
        long hash = XxHash64.hash(0x123456789ABCDEF0L);
        assertNotEquals(0L, hash);
    }

    @Test
    void testLongValueWithSeed() {
        long value = 12345L;
        long hash1 = XxHash64.hash(value, 0L);
        long hash2 = XxHash64.hash(value, 100L);
        assertNotEquals(hash1, hash2);
    }

    // ==================== Determinism Tests ====================

    @Test
    void testDeterministic() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);

        long hash1 = XxHash64.hash(data);
        long hash2 = XxHash64.hash(data);
        long hash3 = XxHash64.hash(data);

        assertEquals(hash1, hash2);
        assertEquals(hash2, hash3);
    }

    @Test
    void testDifferentInputsDifferentHashes() {
        long hash1 = XxHash64.hash("hello");
        long hash2 = XxHash64.hash("world");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void testSeedAffectsHash() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        long hash0 = XxHash64.hash(data, 0L);
        long hash1 = XxHash64.hash(data, 1L);
        long hash2 = XxHash64.hash(data, 100L);

        assertNotEquals(hash0, hash1);
        assertNotEquals(hash1, hash2);
    }

    // ==================== Streaming API Tests ====================

    @Test
    void testStreamingSingleUpdate() {
        byte[] data = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

        hasher.update(data);
        long streamingHash = hasher.getValue();
        long staticHash = XxHash64.hash(data);

        assertEquals(staticHash, streamingHash);
    }

    @Test
    void testStreamingMultipleUpdates() {
        byte[] data = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

        // Update in chunks
        hasher.update(data, 0, 10);  // "abcdefghij"
        hasher.update(data, 10, 10); // "klmnopqrst"
        hasher.update(data, 20, 6);  // "uvwxyz"

        long streamingHash = hasher.getValue();
        long staticHash = XxHash64.hash(data);

        assertEquals(staticHash, streamingHash);
    }

    @Test
    void testStreamingWithReset() {
        byte[] data1 = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "world".getBytes(StandardCharsets.UTF_8);

        hasher.update(data1);
        hasher.reset();
        hasher.update(data2);

        long hash = hasher.getValue();
        long expected = XxHash64.hash(data2);

        assertEquals(expected, hash);
    }

    @Test
    void testStreamingEmptyInput() {
        hasher.update(new byte[0]);
        long hash = hasher.getValue();
        // Original raw: 0xEF46DB3751D8E999, normalized: 0x6F46DB3751D8E999
        assertEquals(0x6F46DB3751D8E999L, hash);
    }

    @Test
    void testUpdateAndHash() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        long hash = hasher.updateAndHash(data);
        long expected = XxHash64.hash(data);
        assertEquals(expected, hash);
    }

    // ==================== Edge Cases ====================

    @Test
    void testInvalidOffset() {
        byte[] data = new byte[10];
        assertThrows(IndexOutOfBoundsException.class, () ->
            XxHash64.hash(data, -1, 5));
        assertThrows(IndexOutOfBoundsException.class, () ->
            XxHash64.hash(data, 8, 5)); // offset + length > data.length
    }

    @Test
    void testLargeInput() {
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }

        long hash = XxHash64.hash(data);
        assertNotEquals(0L, hash);

        // Verify determinism
        assertEquals(hash, XxHash64.hash(data));
    }

    @Test
    void testVariousLengths() {
        // Test that hash works for various input lengths and all are non-negative
        for (int len = 0; len <= 100; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) i;
            }

            long hash = XxHash64.hash(data);
            assertTrue(hash >= 0, "Hash must be non-negative for length " + len);
            assertTrue(hash <= Long.MAX_VALUE, "Hash must be <= Long.MAX_VALUE for length " + len);
        }
    }

    // ==================== Constructor Tests ====================

    @Test
    void testDefaultConstructor() {
        XxHash64 h = new XxHash64();
        assertEquals(0L, h.seed());
    }

    @Test
    void testSeedConstructor() {
        XxHash64 h = new XxHash64(42L);
        assertEquals(42L, h.seed());
    }

    // ==================== Verify All Test Vectors ====================

    @Test
    void testVerifyAllTestVectors() {
        assertTrue(XxHash64.verifyTestVectors(),
            "All built-in test vectors should pass");
    }

    // ==================== toString Test ====================

    @Test
    void testToString() {
        XxHash64 h = new XxHash64(0x12345678L);
        String str = h.toString();
        assertTrue(str.contains("XxHash64"));
        assertTrue(str.contains("12345678"));
    }
}
