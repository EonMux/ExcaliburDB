package io.excalibur.store.hash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * xxHash64 implementation for fast 64-bit non-cryptographic hashing.
 *
 * <p>This is a correct implementation of the xxHash64 algorithm based on the
 * official specification from https://github.com/Cyan4973/xxHash.</p>
 *
 * <h2>Positive Hash Guarantee</h2>
 * <p>All hash values returned by this class are normalized to the range [0, Long.MAX_VALUE]
 * (positive 63-bit values). This is achieved by clearing the sign bit (MSB) of the
 * raw 64-bit hash. This guarantee is required for ExcaliburDB storage where key_hash
 * values must be non-negative for use in hash table slot calculations.</p>
 *
 * <h2>Test Vectors (seed=0, normalized to positive)</h2>
 * <ul>
 *   <li>"" (empty) = 0x6F46DB3751D8E999</li>
 *   <li>"a" = 0x524EC4F1A98C6E5B</li>
 *   <li>"abc" = 0x44BC2CF5AD770999</li>
 *   <li>"message digest" = 0x066ED728FCEEB3BE</li>
 *   <li>"abcdefghijklmnopqrstuvwxyz" = 0x4FE1F278FA89835C</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Static methods are thread-safe. Instance methods for streaming updates
 * are NOT thread-safe and should be used from a single thread.</p>
 *
 * @author ExcaliburDB
 * @see <a href="https://github.com/Cyan4973/xxHash">xxHash GitHub Repository</a>
 */
public class XxHash64 {

    // xxHash64 prime constants (from official specification)
    // https://github.com/Cyan4973/xxHash/blob/dev/xxhash.h
    private static final long PRIME1 = 0x9E3779B185EBCA87L;
    private static final long PRIME2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME3 = 0x165667B19E3779F9L;
    private static final long PRIME4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME5 = 0x27D4EB2F165667C5L;

    /**
     * Mask for normalizing hash values to positive range [0, Long.MAX_VALUE].
     * This clears the sign bit, ensuring the result is always non-negative.
     *
     * <p>Domain constraint: key_hash values MUST be in range [0, Long.MAX_VALUE] (positive 64-bit).
     * This is required because:
     * <ul>
     *   <li>SegmentEntry stores key_hash and validates it must be non-negative</li>
     *   <li>LogEntry stores key_hash and validates it must be non-negative</li>
     *   <li>Slot positions are computed as hash % capacity, which requires non-negative hash</li>
     * </ul>
     */
    public static final long POSITIVE_HASH_MASK = 0x7FFFFFFFFFFFFFFFL;

    // Instance state for streaming API
    private final long seed;
    private long acc1;
    private long acc2;
    private long acc3;
    private long acc4;
    private long totalLen;
    private final byte[] buffer = new byte[32];
    private int bufferPos;

    /**
     * Creates a new XxHash64 instance with seed 0.
     */
    public XxHash64() {
        this(0L);
    }

    /**
     * Creates a new XxHash64 instance with the specified seed.
     *
     * @param seed The seed value
     */
    public XxHash64(long seed) {
        this.seed = seed;
        reset();
    }

    /**
     * Resets this hasher to its initial state.
     *
     * @return This hasher for chaining
     */
    public XxHash64 reset() {
        acc1 = seed + PRIME1 + PRIME2;
        acc2 = seed + PRIME2;
        acc3 = seed;
        acc4 = seed - PRIME1;
        totalLen = 0;
        bufferPos = 0;
        return this;
    }

    // ==================== Static Hash Methods ====================

    /**
     * Hashes a byte array with seed 0.
     *
     * @param data Input data
     * @return 64-bit hash value
     * @throws NullPointerException if data is null
     */
    public static long hash(byte[] data) {
        return hash(data, 0, data != null ? data.length : 0, 0L);
    }

    /**
     * Hashes a byte array with the specified seed.
     *
     * @param data Input data
     * @param seed Seed value
     * @return 64-bit hash value
     * @throws NullPointerException if data is null
     */
    public static long hash(byte[] data, long seed) {
        return hash(data, 0, data != null ? data.length : 0, seed);
    }

    /**
     * Hashes a portion of a byte array with seed 0.
     *
     * @param data   Input data
     * @param offset Start offset in the array
     * @param length Number of bytes to hash
     * @return 64-bit hash value
     * @throws NullPointerException      if data is null
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public static long hash(byte[] data, int offset, int length) {
        return hash(data, offset, length, 0L);
    }

    /**
     * Hashes a portion of a byte array with the specified seed.
     * This is the core hash implementation following the official xxHash64 spec.
     *
     * @param data   Input data
     * @param offset Start offset in the array
     * @param length Number of bytes to hash
     * @param seed   Seed value
     * @return 64-bit hash value
     * @throws NullPointerException      if data is null
     * @throws IndexOutOfBoundsException if offset or length is invalid
     */
    public static long hash(byte[] data, int offset, int length, long seed) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                "Invalid range: offset=" + offset + ", length=" + length + ", data.length=" + data.length);
        }

        long h64;
        int index = offset;
        int end = offset + length;

        if (length >= 32) {
            // Initialize accumulators (lanes)
            long v1 = seed + PRIME1 + PRIME2;
            long v2 = seed + PRIME2;
            long v3 = seed;
            long v4 = seed - PRIME1;

            // Process 32-byte blocks (4 lanes of 8 bytes each)
            int limit = end - 32;
            do {
                v1 = round(v1, readLongLE(data, index));
                index += 8;
                v2 = round(v2, readLongLE(data, index));
                index += 8;
                v3 = round(v3, readLongLE(data, index));
                index += 8;
                v4 = round(v4, readLongLE(data, index));
                index += 8;
            } while (index <= limit);

            // Merge accumulators
            h64 = rotateLeft(v1, 1) + rotateLeft(v2, 7) +
                  rotateLeft(v3, 12) + rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME5;
        }

        h64 += length;

        // Process remaining 8-byte chunks
        while (index + 8 <= end) {
            h64 ^= round(0, readLongLE(data, index));
            h64 = rotateLeft(h64, 27) * PRIME1;
            h64 += PRIME4;
            index += 8;
        }

        // Process remaining 4-byte chunk
        if (index + 4 <= end) {
            h64 ^= (readIntLE(data, index) & 0xFFFFFFFFL) * PRIME1;
            h64 = rotateLeft(h64, 23) * PRIME2;
            h64 += PRIME3;
            index += 4;
        }

        // Process remaining bytes
        while (index < end) {
            h64 ^= (data[index] & 0xFFL) * PRIME5;
            h64 = rotateLeft(h64, 11) * PRIME1;
            index++;
        }

        // Final avalanche
        h64 ^= h64 >>> 33;
        h64 *= PRIME2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME3;
        h64 ^= h64 >>> 32;

        // Normalize to positive range [0, Long.MAX_VALUE]
        // Domain constraint: key_hash values MUST be non-negative for ExcaliburDB storage
        return normalizeToPositive(h64);
    }

    /**
     * Normalizes a 64-bit hash value to the positive range [0, Long.MAX_VALUE].
     *
     * <p>xxHash64 produces a full 64-bit unsigned value, but Java's long is signed.
     * This method clears the sign bit to ensure the result is always non-negative,
     * which is required for use as key_hash in ExcaliburDB storage.</p>
     *
     * @param hash The raw hash value (may be negative in Java's signed interpretation)
     * @return The normalized hash value in range [0, Long.MAX_VALUE]
     */
    public static long normalizeToPositive(long hash) {
        return hash & POSITIVE_HASH_MASK;
    }

    /**
     * Hashes a string (UTF-8 encoded) with seed 0.
     *
     * @param str Input string
     * @return 64-bit hash value
     * @throws NullPointerException if str is null
     */
    public static long hash(String str) {
        return hash(str, 0L);
    }

    /**
     * Hashes a string (UTF-8 encoded) with the specified seed.
     *
     * @param str  Input string
     * @param seed Seed value
     * @return 64-bit hash value
     * @throws NullPointerException if str is null
     */
    public static long hash(String str, long seed) {
        if (str == null) {
            throw new NullPointerException("String cannot be null");
        }
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        return hash(data, seed);
    }

    /**
     * Hashes a ByteBuffer with seed 0.
     *
     * @param buffer Input buffer (reads from current position to limit)
     * @return 64-bit hash value
     * @throws NullPointerException if buffer is null
     */
    public static long hash(ByteBuffer buffer) {
        return hash(buffer, 0L);
    }

    /**
     * Hashes a ByteBuffer with the specified seed.
     *
     * @param buffer Input buffer (reads from current position to limit)
     * @param seed   Seed value
     * @return 64-bit hash value
     * @throws NullPointerException if buffer is null
     */
    public static long hash(ByteBuffer buffer, long seed) {
        if (buffer == null) {
            throw new NullPointerException("Buffer cannot be null");
        }

        if (buffer.hasArray()) {
            int offset = buffer.arrayOffset() + buffer.position();
            int length = buffer.remaining();
            return hash(buffer.array(), offset, length, seed);
        } else {
            byte[] data = new byte[buffer.remaining()];
            buffer.duplicate().get(data);
            return hash(data, seed);
        }
    }

    /**
     * Hashes a long value with seed 0.
     *
     * @param value Long value to hash
     * @return 64-bit hash value
     */
    public static long hash(long value) {
        return hashLong(value, 0L);
    }

    /**
     * Hashes a long value with the specified seed.
     *
     * @param value Long value to hash
     * @param seed  Seed value
     * @return 64-bit hash value
     */
    public static long hash(long value, long seed) {
        return hashLong(value, seed);
    }

    private static long hashLong(long value, long seed) {
        // Hash single 8-byte value
        long h64 = seed + PRIME5 + 8L;

        // Process the 8 bytes (little-endian)
        h64 ^= round(0, Long.reverseBytes(value));
        h64 = rotateLeft(h64, 27) * PRIME1;
        h64 += PRIME4;

        // Final avalanche
        h64 ^= h64 >>> 33;
        h64 *= PRIME2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME3;
        h64 ^= h64 >>> 32;

        // Normalize to positive range [0, Long.MAX_VALUE]
        return normalizeToPositive(h64);
    }

    // ==================== Instance Methods for Streaming ====================

    /**
     * Updates the hash with the specified byte array.
     *
     * @param data Input data
     * @return This hasher for chaining
     */
    public XxHash64 update(byte[] data) {
        return update(data, 0, data.length);
    }

    /**
     * Updates the hash with a portion of a byte array.
     *
     * @param data   Input data
     * @param offset Start offset
     * @param length Number of bytes
     * @return This hasher for chaining
     */
    public XxHash64 update(byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid range");
        }

        totalLen += length;
        int index = offset;
        int end = offset + length;

        // If we have buffered data, try to fill the buffer first
        if (bufferPos > 0) {
            int needed = 32 - bufferPos;
            int toCopy = Math.min(needed, length);

            System.arraycopy(data, offset, buffer, bufferPos, toCopy);
            bufferPos += toCopy;
            index += toCopy;

            if (bufferPos == 32) {
                // Process the full buffer
                acc1 = round(acc1, readLongLE(buffer, 0));
                acc2 = round(acc2, readLongLE(buffer, 8));
                acc3 = round(acc3, readLongLE(buffer, 16));
                acc4 = round(acc4, readLongLE(buffer, 24));
                bufferPos = 0;
            }
        }

        // Process complete 32-byte blocks directly from input
        while (index + 32 <= end) {
            acc1 = round(acc1, readLongLE(data, index));
            acc2 = round(acc2, readLongLE(data, index + 8));
            acc3 = round(acc3, readLongLE(data, index + 16));
            acc4 = round(acc4, readLongLE(data, index + 24));
            index += 32;
        }

        // Buffer remaining bytes
        int remaining = end - index;
        if (remaining > 0) {
            System.arraycopy(data, index, buffer, 0, remaining);
            bufferPos = remaining;
        }

        return this;
    }

    /**
     * Computes the final hash value.
     * Does not reset the hasher - call reset() to start a new hash.
     *
     * @return 64-bit hash value
     */
    public long getValue() {
        long h64;

        if (totalLen >= 32) {
            // Merge accumulators
            h64 = rotateLeft(acc1, 1) + rotateLeft(acc2, 7) +
                  rotateLeft(acc3, 12) + rotateLeft(acc4, 18);
            h64 = mergeRound(h64, acc1);
            h64 = mergeRound(h64, acc2);
            h64 = mergeRound(h64, acc3);
            h64 = mergeRound(h64, acc4);
        } else {
            h64 = seed + PRIME5;
        }

        h64 += totalLen;

        // Process buffered remaining bytes
        int index = 0;
        int remaining = bufferPos;

        // Process 8-byte chunks
        while (remaining >= 8) {
            h64 ^= round(0, readLongLE(buffer, index));
            h64 = rotateLeft(h64, 27) * PRIME1;
            h64 += PRIME4;
            index += 8;
            remaining -= 8;
        }

        // Process 4-byte chunk
        if (remaining >= 4) {
            h64 ^= (readIntLE(buffer, index) & 0xFFFFFFFFL) * PRIME1;
            h64 = rotateLeft(h64, 23) * PRIME2;
            h64 += PRIME3;
            index += 4;
            remaining -= 4;
        }

        // Process remaining bytes
        while (remaining > 0) {
            h64 ^= (buffer[index] & 0xFFL) * PRIME5;
            h64 = rotateLeft(h64, 11) * PRIME1;
            index++;
            remaining--;
        }

        // Final avalanche
        h64 ^= h64 >>> 33;
        h64 *= PRIME2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME3;
        h64 ^= h64 >>> 32;

        // Normalize to positive range [0, Long.MAX_VALUE]
        return normalizeToPositive(h64);
    }

    /**
     * Convenience method: reset, update and get value.
     *
     * @param data Input data
     * @return 64-bit hash value
     */
    public long updateAndHash(byte[] data) {
        reset();
        update(data);
        return getValue();
    }

    /**
     * Gets the seed value.
     *
     * @return The seed
     */
    public long seed() {
        return seed;
    }

    // ==================== Internal Implementation ====================

    /**
     * Performs a single round of the xxHash64 algorithm.
     * acc = acc + (input * PRIME2); acc = rotl(acc, 31); acc = acc * PRIME1;
     */
    private static long round(long acc, long input) {
        acc += input * PRIME2;
        acc = rotateLeft(acc, 31);
        acc *= PRIME1;
        return acc;
    }

    /**
     * Performs a merge round.
     * h64 ^= round(0, acc); h64 = h64 * PRIME1 + PRIME4;
     */
    private static long mergeRound(long h64, long acc) {
        h64 ^= round(0, acc);
        h64 *= PRIME1;
        h64 += PRIME4;
        return h64;
    }

    /**
     * Rotates a long left by the specified number of bits.
     */
    private static long rotateLeft(long value, int shift) {
        return (value << shift) | (value >>> (64 - shift));
    }

    /**
     * Reads a little-endian long from a byte array at the specified offset.
     */
    private static long readLongLE(byte[] data, int offset) {
        return (data[offset] & 0xFFL) |
               ((data[offset + 1] & 0xFFL) << 8) |
               ((data[offset + 2] & 0xFFL) << 16) |
               ((data[offset + 3] & 0xFFL) << 24) |
               ((data[offset + 4] & 0xFFL) << 32) |
               ((data[offset + 5] & 0xFFL) << 40) |
               ((data[offset + 6] & 0xFFL) << 48) |
               ((data[offset + 7] & 0xFFL) << 56);
    }

    /**
     * Reads a little-endian int from a byte array at the specified offset.
     */
    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
               ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 3] & 0xFF) << 24);
    }

    @Override
    public String toString() {
        return String.format("XxHash64[seed=0x%016X]", seed);
    }

    // ==================== Test Vector Verification ====================

    /**
     * Verifies this implementation against known test vectors from
     * the official xxHash repository.
     *
     * <p>Note: All test vectors are normalized to positive range [0, Long.MAX_VALUE]
     * to comply with ExcaliburDB's key_hash constraint.</p>
     *
     * @return true if all test vectors pass
     */
    public static boolean verifyTestVectors() {
        boolean allPassed = true;

        // Official xxHash64 test vectors (seed=0), normalized to positive range
        // Original raw values from https://github.com/Cyan4973/xxHash:
        //   "" = 0xEF46DB3751D8E999, normalized = 0x6F46DB3751D8E999
        //   "a" = 0xD24EC4F1A98C6E5B, normalized = 0x524EC4F1A98C6E5B
        //   "abc" = 0x44BC2CF5AD770999 (already positive)
        //   "message digest" = 0x066ED728FCEEB3BE (already positive)
        //   "abcdefghijklmnopqrstuvwxyz" = 0xCFE1F278FA89835C, normalized = 0x4FE1F278FA89835C
        allPassed &= verifyVector("", 0x6F46DB3751D8E999L, 0L);
        allPassed &= verifyVector("a", 0x524EC4F1A98C6E5BL, 0L);
        allPassed &= verifyVector("abc", 0x44BC2CF5AD770999L, 0L);
        allPassed &= verifyVector("message digest", 0x066ED728FCEEB3BEL, 0L);
        allPassed &= verifyVector("abcdefghijklmnopqrstuvwxyz", 0x4FE1F278FA89835CL, 0L);
        allPassed &= verifyVector("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", 0x2AA46907D3047814L, 0L);
        allPassed &= verifyVector("12345678901234567890123456789012345678901234567890123456789012345678901234567890", 0x604A477F19EE145DL, 0L);

        // Test with non-zero seed (seed=1)
        // Original raw values:
        //   "" = 0xD5AFBA1336A3BE4B, normalized = 0x55AFBA1336A3BE4B
        //   "a" = 0xDEC2BC81C3CD46C6, normalized = 0x5EC2BC81C3CD46C6
        allPassed &= verifyVector("", 0x55AFBA1336A3BE4BL, 1L);
        allPassed &= verifyVector("a", 0x5EC2BC81C3CD46C6L, 1L);

        // Test byte array directly
        byte[] emptyBytes = new byte[0];
        allPassed &= check("empty byte array", hash(emptyBytes, 0), 0x6F46DB3751D8E999L);

        byte[] singleByte = new byte[] {0x61}; // 'a'
        allPassed &= check("single byte array", hash(singleByte, 0), 0x524EC4F1A98C6E5BL);

        // Test streaming API
        XxHash64 streaming = new XxHash64(0);
        streaming.update("abc".getBytes(StandardCharsets.UTF_8));
        allPassed &= check("streaming API", streaming.getValue(), 0x44BC2CF5AD770999L);

        // Test reset
        streaming.reset();
        streaming.update("message digest".getBytes(StandardCharsets.UTF_8));
        allPassed &= check("reset + streaming", streaming.getValue(), 0x066ED728FCEEB3BEL);

        // Test streaming in chunks
        streaming.reset();
        byte[] abc = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        streaming.update(abc, 0, 10);
        streaming.update(abc, 10, 10);
        streaming.update(abc, 20, abc.length - 20);
        allPassed &= check("chunked streaming", streaming.getValue(), 0x4FE1F278FA89835CL);

        return allPassed;
    }

    private static boolean verifyVector(String input, long expected, long seed) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        long actual = hash(data, 0, data.length, seed);

        return check("\"" + (input.isEmpty() ? "(empty)" : input) + "\" (seed=" + seed + ")", actual, expected);
    }

    private static boolean check(String description, long actual, long expected) {
        if (actual != expected) {
            System.err.printf("FAILED: XxHash64(%s) = 0x%016X, expected 0x%016X%n",
                description, actual, expected);
            return false;
        }
        return true;
    }

    /**
     * Main method to run test vector verification.
     *
     * @param args Command line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("Verifying XxHash64 implementation against test vectors...");
        System.out.println();

        boolean passed = verifyTestVectors();

        System.out.println();
        if (passed) {
            System.out.println("All test vectors PASSED!");
        } else {
            System.out.println("Some test vectors FAILED!");
            System.exit(1);
        }
    }
}
