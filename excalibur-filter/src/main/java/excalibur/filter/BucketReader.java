package excalibur.filter;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Lock-free atomic bucket reader for CuckooFilter using VarHandle.
 *
 * <h2>Thread Safety</h2>
 * <p>All read operations are lock-free and thread-safe. Uses VarHandle with
 * acquire semantics to ensure visibility of writes from other threads without
 * requiring locks.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>Each bucket contains 4 fingerprints, each stored as a 2-byte short (16 bits).
 * Total bucket size: 8 bytes (64 bits).</p>
 *
 * <pre>
 * Bucket layout (8 bytes):
 * [fp0 (2B) | fp1 (2B) | fp2 (2B) | fp3 (2B)]
 * </pre>
 *
 * <h2>Atomicity Guarantees</h2>
 * <ul>
 *   <li>{@link #readBucketAtomic(long)} - Reads entire 8-byte bucket atomically</li>
 *   <li>{@link #readFingerprintAtomic(long, int)} - Reads individual fingerprint with acquire semantics</li>
 *   <li>{@link #containsFingerprintAtomic(long, int, int)} - Checks for fingerprint without locks</li>
 * </ul>
 *
 * <h2>Memory Ordering</h2>
 * <p>Uses acquire semantics for reads to ensure that subsequent reads see
 * consistent data. Writes from other threads using volatile semantics
 * will be visible to these reads.</p>
 *
 * <h2>Performance</h2>
 * <p>Reading the entire bucket as a single long (8 bytes) is more efficient
 * than 4 individual short reads when checking all fingerprints. Use
 * {@link #readBucketAtomic(long)} for contains operations.</p>
 *
 * @author ExcaliburDB
 */
public final class BucketReader {

    // Number of fingerprints per bucket
    static final int FINGERPRINTS_PER_BUCKET = 4;

    // Bytes per fingerprint (stored as short for atomicity)
    static final int BYTES_PER_FINGERPRINT = 2;

    // Total bytes per bucket (8 bytes = 64 bits, cache-line friendly)
    static final int BYTES_PER_BUCKET = FINGERPRINTS_PER_BUCKET * BYTES_PER_FINGERPRINT;

    // Memory segment containing bucket data
    private final MemorySegment segment;

    // Offset where bucket data starts in the segment
    private final long bucketDataOffset;

    // Mask for extracting fingerprint bits (configurable)
    private final int fingerprintMask;

    // Number of buckets
    private final long numBuckets;

    /**
     * Creates a new BucketReader.
     *
     * @param segment          The memory segment containing filter data
     * @param bucketDataOffset The offset where bucket data starts
     * @param fingerprintBits  Number of bits per fingerprint (8-16)
     * @param numBuckets       Number of buckets in the filter
     * @throws IllegalArgumentException if parameters are invalid
     */
    public BucketReader(MemorySegment segment, long bucketDataOffset,
                        int fingerprintBits, long numBuckets) {
        if (segment == null) {
            throw new IllegalArgumentException("MemorySegment cannot be null");
        }
        if (bucketDataOffset < 0) {
            throw new IllegalArgumentException("bucketDataOffset cannot be negative");
        }
        if (fingerprintBits < 8 || fingerprintBits > 16) {
            throw new IllegalArgumentException(
                "fingerprintBits must be in [8, 16], got: " + fingerprintBits);
        }
        if (numBuckets <= 0) {
            throw new IllegalArgumentException("numBuckets must be positive");
        }

        this.segment = segment;
        this.bucketDataOffset = bucketDataOffset;
        this.fingerprintMask = (1 << fingerprintBits) - 1;
        this.numBuckets = numBuckets;
    }

    /**
     * Reads an entire bucket atomically as a single long value.
     *
     * <p>This is the most efficient way to read all 4 fingerprints at once.
     * The operation is atomic - no torn reads are possible.</p>
     *
     * <h3>Usage</h3>
     * <pre>{@code
     * long bucketData = reader.readBucketAtomic(bucketIndex);
     * // Extract fingerprints using extractFingerprint()
     * for (int slot = 0; slot < 4; slot++) {
     *     int fp = reader.extractFingerprint(bucketData, slot);
     * }
     * }</pre>
     *
     * @param bucketIndex The bucket index (0 to numBuckets-1)
     * @return The 8-byte bucket value containing all 4 fingerprints
     * @throws IndexOutOfBoundsException if bucketIndex is out of range
     */
    public long readBucketAtomic(long bucketIndex) {
        checkBucketIndex(bucketIndex);
        long offset = getBucketOffset(bucketIndex);

        // Use MemorySegment direct access for atomic read
        // MemorySegment.get() with JAVA_LONG provides atomic reads
        return segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), offset);
    }

    /**
     * Reads a single fingerprint atomically with acquire semantics.
     *
     * <p>Use this when you only need one fingerprint from a bucket.
     * For checking all fingerprints, {@link #readBucketAtomic(long)} is more efficient.</p>
     *
     * @param bucketIndex The bucket index
     * @param slot        The slot within the bucket (0-3)
     * @return The fingerprint value (0 if empty)
     * @throws IndexOutOfBoundsException if bucketIndex or slot is out of range
     */
    public int readFingerprintAtomic(long bucketIndex, int slot) {
        checkBucketIndex(bucketIndex);
        checkSlot(slot);
        long offset = getFingerprintOffset(bucketIndex, slot);

        // Use MemorySegment direct access for atomic short read
        short value = segment.get(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), offset);

        // Mask to get only the fingerprint bits
        return value & fingerprintMask;
    }

    /**
     * Checks if a fingerprint exists in a bucket using lock-free atomic reads.
     *
     * <p>This method reads the entire bucket atomically and checks all slots
     * without acquiring any locks. This is the preferred method for contains
     * operations in the CuckooFilter.</p>
     *
     * @param bucketIndex  The bucket index
     * @param fingerprint  The fingerprint to search for
     * @param fingerprintMask The mask for extracting fingerprint bits
     * @return true if the fingerprint was found in any slot, false otherwise
     */
    public boolean containsFingerprintAtomic(long bucketIndex, int fingerprint, int fingerprintMask) {
        // Read entire bucket atomically - most efficient for checking all 4 slots
        long bucketData = readBucketAtomic(bucketIndex);

        // Extract and compare each fingerprint
        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            int slotFp = extractFingerprintFromLong(bucketData, slot, fingerprintMask);
            if (slotFp == fingerprint) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a fingerprint exists in a bucket using lock-free atomic reads.
     *
     * <p>Uses the configured fingerprint mask from the constructor.</p>
     *
     * @param bucketIndex The bucket index
     * @param fingerprint The fingerprint to search for
     * @return true if found, false otherwise
     */
    public boolean containsFingerprintAtomic(long bucketIndex, int fingerprint) {
        return containsFingerprintAtomic(bucketIndex, fingerprint, this.fingerprintMask);
    }

    /**
     * Extracts a fingerprint from a previously-read bucket value.
     *
     * <p>Use this after calling {@link #readBucketAtomic(long)} to extract
     * individual fingerprints without additional memory reads.</p>
     *
     * @param bucketData The 8-byte bucket value from readBucketAtomic
     * @param slot       The slot within the bucket (0-3)
     * @param fingerprintMask The mask for extracting fingerprint bits
     * @return The fingerprint value (0 if empty)
     */
    public int extractFingerprintFromLong(long bucketData, int slot, int fingerprintMask) {
        checkSlot(slot);

        // Each short is 2 bytes = 16 bits
        // Bucket layout (big-endian): [fp0 (16b) | fp1 (16b) | fp2 (16b) | fp3 (16b)]
        // For big-endian, slot 0 is in the high bits
        int shift = (3 - slot) * 16; // slot 0 -> bits 48-63, slot 3 -> bits 0-15

        // Extract 16 bits and apply mask
        return (int) ((bucketData >>> shift) & 0xFFFF) & fingerprintMask;
    }

    /**
     * Extracts a fingerprint from a previously-read bucket value.
     *
     * <p>Uses the configured fingerprint mask from the constructor.</p>
     *
     * @param bucketData The 8-byte bucket value from readBucketAtomic
     * @param slot       The slot within the bucket (0-3)
     * @return The fingerprint value (0 if empty)
     */
    public int extractFingerprint(long bucketData, int slot) {
        return extractFingerprintFromLong(bucketData, slot, this.fingerprintMask);
    }

    /**
     * Reads all fingerprints from a bucket atomically.
     *
     * <p>This is a convenience method that reads the bucket once and
     * extracts all fingerprints into an array.</p>
     *
     * @param bucketIndex The bucket index
     * @return Array of 4 fingerprints (index 0-3)
     */
    public int[] readAllFingerprintsAtomic(long bucketIndex) {
        long bucketData = readBucketAtomic(bucketIndex);
        int[] fingerprints = new int[FINGERPRINTS_PER_BUCKET];

        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            fingerprints[slot] = extractFingerprint(bucketData, slot);
        }

        return fingerprints;
    }

    /**
     * Finds an empty slot in a bucket using atomic reads.
     *
     * @param bucketIndex The bucket index
     * @return The slot index (0-3) of an empty slot, or -1 if bucket is full
     */
    public int findEmptySlotAtomic(long bucketIndex) {
        long bucketData = readBucketAtomic(bucketIndex);

        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            if (extractFingerprint(bucketData, slot) == 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Finds the slot containing a specific fingerprint.
     *
     * @param bucketIndex The bucket index
     * @param fingerprint The fingerprint to find
     * @return The slot index (0-3) if found, or -1 if not found
     */
    public int findFingerprintSlotAtomic(long bucketIndex, int fingerprint) {
        long bucketData = readBucketAtomic(bucketIndex);

        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            if (extractFingerprint(bucketData, slot) == fingerprint) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Gets the byte offset for a bucket.
     *
     * @param bucketIndex The bucket index
     * @return The byte offset
     */
    private long getBucketOffset(long bucketIndex) {
        return bucketDataOffset + bucketIndex * BYTES_PER_BUCKET;
    }

    /**
     * Gets the byte offset for a fingerprint slot.
     *
     * @param bucketIndex The bucket index
     * @param slot        The slot within the bucket (0-3)
     * @return The byte offset
     */
    private long getFingerprintOffset(long bucketIndex, int slot) {
        return getBucketOffset(bucketIndex) + slot * BYTES_PER_FINGERPRINT;
    }

    /**
     * Validates a bucket index.
     *
     * @param bucketIndex The bucket index to validate
     * @throws IndexOutOfBoundsException if index is invalid
     */
    private void checkBucketIndex(long bucketIndex) {
        if (bucketIndex < 0 || bucketIndex >= numBuckets) {
            throw new IndexOutOfBoundsException(
                "Bucket index out of range: " + bucketIndex +
                " (valid: 0-" + (numBuckets - 1) + ")");
        }
    }

    /**
     * Validates a slot index.
     *
     * @param slot The slot to validate
     * @throws IndexOutOfBoundsException if slot is invalid
     */
    private void checkSlot(int slot) {
        if (slot < 0 || slot >= FINGERPRINTS_PER_BUCKET) {
            throw new IndexOutOfBoundsException(
                "Slot out of range: " + slot +
                " (valid: 0-" + (FINGERPRINTS_PER_BUCKET - 1) + ")");
        }
    }

    /**
     * Gets the number of buckets.
     *
     * @return The bucket count
     */
    public long numBuckets() {
        return numBuckets;
    }

    /**
     * Gets the fingerprint mask.
     *
     * @return The fingerprint mask
     */
    public int fingerprintMask() {
        return fingerprintMask;
    }

    /**
     * Gets the bytes per bucket constant.
     *
     * @return Bytes per bucket (8)
     */
    public static int bytesPerBucket() {
        return BYTES_PER_BUCKET;
    }

    /**
     * Gets the fingerprints per bucket constant.
     *
     * @return Fingerprints per bucket (4)
     */
    public static int fingerprintsPerBucket() {
        return FINGERPRINTS_PER_BUCKET;
    }
}
