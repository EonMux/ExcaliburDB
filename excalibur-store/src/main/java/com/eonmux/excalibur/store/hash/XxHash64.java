package com.eonmux.excalibur.store.hash;

import java.nio.ByteBuffer;

/**
 * xxHash64 for the storage engine - a thin facade over
 * {@link com.eonmux.excalibur.core.hash.XxHash64}.
 *
 * <p>This class used to be a second, byte-identical implementation of the algorithm.
 * Two independent copies of a hash function whose output is <em>persisted</em> as
 * {@code key_hash} and consumed across module boundaries is a divergence waiting to
 * happen: any edit to one copy silently invalidates every segment and write log written
 * by the other, with no checksum able to detect it. There is now exactly one
 * implementation, in {@code excalibur-core}, and this type forwards to it so existing
 * call sites keep working.</p>
 *
 * <h2>Positive Hash Guarantee</h2>
 * <p>All hash values are normalized to the range [0, Long.MAX_VALUE] (positive 63-bit
 * values) by clearing the sign bit. ExcaliburDB requires non-negative {@code key_hash}
 * values for hash table slot calculations.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Static methods are thread-safe. Instance methods for streaming updates are NOT
 * thread-safe and should be used from a single thread.</p>
 *
 * @author ExcaliburDB
 * @see com.eonmux.excalibur.core.hash.XxHash64
 * @see <a href="https://github.com/Cyan4973/xxHash">xxHash GitHub Repository</a>
 */
public class XxHash64 {

    /**
     * Mask that clears the sign bit, normalizing a hash to [0, Long.MAX_VALUE].
     */
    public static final long POSITIVE_HASH_MASK = com.eonmux.excalibur.core.hash.XxHash64.POSITIVE_HASH_MASK;

    /**
     * Streaming delegate. Holds all state for the instance API.
     */
    private final com.eonmux.excalibur.core.hash.XxHash64 delegate;

    /**
     * Creates a streaming hasher with the default seed (0).
     */
    public XxHash64() {
        this.delegate = new com.eonmux.excalibur.core.hash.XxHash64();
    }

    /**
     * Creates a streaming hasher with an explicit seed.
     *
     * @param seed The seed value
     */
    public XxHash64(long seed) {
        this.delegate = new com.eonmux.excalibur.core.hash.XxHash64(seed);
    }

    // ==================== One-shot Hashing ====================

    /**
     * Hashes a byte array with the default seed.
     *
     * @param data Input data
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(byte[] data) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(data);
    }

    /**
     * Hashes a byte array with an explicit seed.
     *
     * @param data Input data
     * @param seed The seed value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(byte[] data, long seed) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(data, seed);
    }

    /**
     * Hashes a region of a byte array with the default seed.
     *
     * @param data   Input data
     * @param offset Start offset
     * @param length Number of bytes to hash
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(byte[] data, int offset, int length) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(data, offset, length);
    }

    /**
     * Hashes a region of a byte array with an explicit seed.
     *
     * @param data   Input data
     * @param offset Start offset
     * @param length Number of bytes to hash
     * @param seed   The seed value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(byte[] data, int offset, int length, long seed) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(data, offset, length, seed);
    }

    /**
     * Hashes a string's UTF-8 bytes with the default seed.
     *
     * @param str Input string
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(String str) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(str);
    }

    /**
     * Hashes a string's UTF-8 bytes with an explicit seed.
     *
     * @param str  Input string
     * @param seed The seed value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(String str, long seed) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(str, seed);
    }

    /**
     * Hashes the remaining bytes of a buffer with the default seed.
     *
     * @param buffer Input buffer
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(ByteBuffer buffer) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(buffer);
    }

    /**
     * Hashes the remaining bytes of a buffer with an explicit seed.
     *
     * @param buffer Input buffer
     * @param seed   The seed value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(ByteBuffer buffer, long seed) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(buffer, seed);
    }

    /**
     * Hashes a long value with the default seed.
     *
     * @param value Input value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(long value) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(value);
    }

    /**
     * Hashes a long value with an explicit seed.
     *
     * @param value Input value
     * @param seed  The seed value
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public static long hash(long value, long seed) {
        return com.eonmux.excalibur.core.hash.XxHash64.hash(value, seed);
    }

    /**
     * Clears the sign bit of a raw hash.
     *
     * @param hash Raw 64-bit hash
     * @return Hash in [0, Long.MAX_VALUE]
     */
    public static long normalizeToPositive(long hash) {
        return com.eonmux.excalibur.core.hash.XxHash64.normalizeToPositive(hash);
    }

    // ==================== Streaming API ====================

    /**
     * Resets the streaming state, keeping the seed.
     *
     * @return This instance
     */
    public XxHash64 reset() {
        delegate.reset();
        return this;
    }

    /**
     * Feeds data into the streaming hash.
     *
     * @param data Input data
     * @return This instance
     */
    public XxHash64 update(byte[] data) {
        delegate.update(data);
        return this;
    }

    /**
     * Feeds a region of data into the streaming hash.
     *
     * @param data   Input data
     * @param offset Start offset
     * @param length Number of bytes to consume
     * @return This instance
     */
    public XxHash64 update(byte[] data, int offset, int length) {
        delegate.update(data, offset, length);
        return this;
    }

    /**
     * Returns the hash of everything fed in so far.
     *
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public long getValue() {
        return delegate.getValue();
    }

    /**
     * Resets, feeds the data and returns the hash.
     *
     * @param data Input data
     * @return 64-bit hash in [0, Long.MAX_VALUE]
     */
    public long updateAndHash(byte[] data) {
        return delegate.updateAndHash(data);
    }

    /**
     * Returns the seed this instance was created with.
     *
     * @return The seed
     */
    public long seed() {
        return delegate.seed();
    }

    // ==================== Verification ====================

    /**
     * Verifies the implementation against the official xxHash test vectors.
     *
     * @return true if all test vectors pass
     */
    public static boolean verifyTestVectors() {
        return com.eonmux.excalibur.core.hash.XxHash64.verifyTestVectors();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
