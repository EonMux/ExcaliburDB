package io.excalibur.store;

/**
 * Configuration constants for disk-backed segment storage.
 *
 * <p>This class defines the core configuration parameters that control segment
 * behavior, including capacity limits, load factor thresholds, and memory layout
 * constants for Robin Hood hashing.</p>
 *
 * <h2>Capacity Constraints</h2>
 * <ul>
 *   <li>{@link #MIN_SEGMENT_CAPACITY}: Minimum number of slots (1,024)</li>
 *   <li>{@link #MAX_SEGMENT_CAPACITY}: Maximum number of slots (1,048,576 = 1M)</li>
 *   <li>Capacity must always be a power of 2 for efficient modulo operations</li>
 * </ul>
 *
 * <h2>Load Factor Management</h2>
 * <ul>
 *   <li>{@link #LOAD_FACTOR_THRESHOLD}: 0.7 (70%) - insertions rejected at or above this</li>
 *   <li>Load factor is computed as: entry_count / slot_count</li>
 *   <li>At 0.7 load factor with Robin Hood hashing, average probe length is ~2</li>
 * </ul>
 *
 * <h2>Probe Length</h2>
 * <ul>
 *   <li>{@link #MAX_PROBE_LENGTH}: 128 - maximum distance from ideal slot</li>
 *   <li>With proper load factor, probes rarely exceed 10</li>
 *   <li>128 provides safety margin while fitting in a signed byte</li>
 * </ul>
 *
 * <h2>Entry Memory Layout</h2>
 * <pre>
 * Offset 0:   key_hash (8 bytes, little-endian)
 * Offset 8:   key_len (4 bytes, signed int, little-endian)
 * Offset 12:  value_len (4 bytes, signed int, little-endian)
 * Offset 16:  distance (1 byte, unsigned - distance from ideal slot)
 * Offset 17:  key_bytes (variable length)
 * Offset 17+key_len: value_bytes (variable length)
 * Offset 17+key_len+value_len: tombstone (1 byte, 0=live, 1=deleted)
 * </pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>{@code key_hash}: Range [0, Long.MAX_VALUE] (positive 64-bit values)</li>
 *   <li>{@code key_len}: Range [0, Integer.MAX_VALUE - 17]</li>
 *   <li>{@code value_len}: Range [0, Integer.MAX_VALUE - 17]</li>
 *   <li>{@code distance}: Range [0, 127] (fits in unsigned byte)</li>
 *   <li>{@code tombstone}: Exactly 0 (live) or 1 (deleted)</li>
 *   <li>Slot positions computed as: {@code hash & (capacity - 1)} for power-of-2 capacity</li>
 *   <li>Robin Hood swap occurs when: {@code new_entry.distance > existing_entry.distance}</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see Segment
 * @see io.excalibur.store.format.SegmentEntry
 */
public final class SegmentConfig {

    // ==================== Capacity Constants ====================

    /**
     * Minimum segment capacity (number of slots).
     *
     * <p>Must be a power of 2. Smaller segments would have poor cache behavior
     * and excessive overhead relative to data.</p>
     *
     * <p>Value: 1,024 slots</p>
     * <p>Minimum slot table size: 24 KB (at 24 bytes/slot)</p>
     */
    public static final int MIN_SEGMENT_CAPACITY = 1024;

    /**
     * Maximum segment capacity (number of slots).
     *
     * <p>Must be a power of 2. Limits memory usage per segment and ensures
     * integer arithmetic doesn't overflow. With 1M slots at 70% load factor,
     * a segment holds ~700K entries.</p>
     *
     * <p>Value: 1,048,576 slots (2^20 = 1M)</p>
     * <p>Maximum slot table size: ~24 MB (at 24 bytes/slot)</p>
     */
    public static final int MAX_SEGMENT_CAPACITY = 1 << 20;

    // ==================== Load Factor Constants ====================

    /**
     * Load factor threshold for triggering split or rejecting insertions.
     *
     * <p>When load factor reaches or exceeds this value:</p>
     * <ul>
     *   <li>New insertions are rejected with IllegalStateException</li>
     *   <li>Segment should be split (handled by orchestration layer)</li>
     * </ul>
     *
     * <p>Value: 0.7 (70%)</p>
     *
     * <h3>Rationale</h3>
     * <p>At 0.7 load factor with Robin Hood hashing:</p>
     * <ul>
     *   <li>Average probe length: ~2</li>
     *   <li>99th percentile probe length: ~6</li>
     *   <li>Memory utilization: efficient (30% overhead)</li>
     * </ul>
     *
     * <p>Higher load factors would increase probe lengths significantly,
     * degrading lookup performance. Lower load factors waste memory.</p>
     */
    public static final double LOAD_FACTOR_THRESHOLD = 0.7;

    // ==================== Probe Length Constants ====================

    /**
     * Maximum probe length before giving up on insertion or lookup.
     *
     * <p>This limits the worst-case scenario where a slot sequence is full
     * of entries with large distances. With Robin Hood hashing and proper
     * load factor management, actual probe lengths rarely exceed 10.</p>
     *
     * <p>Value: 128</p>
     *
     * <h3>Constraints</h3>
     * <ul>
     *   <li>Must fit in a signed byte (max 127) when stored</li>
     *   <li>128 is used as the loop bound; stored distances are 0-127</li>
     *   <li>If probe length reaches 128, the hash table is corrupted or
     *       load factor invariant was violated</li>
     * </ul>
     */
    public static final int MAX_PROBE_LENGTH = 128;

    // ==================== Entry Layout Constants ====================

    /**
     * Fixed overhead per entry (header + tombstone).
     *
     * <p>Every entry has this fixed overhead regardless of key/value sizes:</p>
     * <ul>
     *   <li>key_hash: 8 bytes (64-bit hash)</li>
     *   <li>key_len: 4 bytes (32-bit signed int)</li>
     *   <li>value_len: 4 bytes (32-bit signed int)</li>
     *   <li>distance: 1 byte (unsigned, 0-127)</li>
     *   <li>tombstone: 1 byte (0=live, 1=deleted)</li>
     * </ul>
     *
     * <p>Total: 8 + 4 + 4 + 1 + 1 = 18 bytes</p>
     *
     * <p>Full entry size = ENTRY_FIXED_OVERHEAD + key.length + value.length</p>
     *
     * <h3>Note on Terminology</h3>
     * <p>This constant represents the entry overhead in the data region.
     * The slot table uses a separate 24-byte slot format for fast probing
     * (see {@code Segment.SLOT_SIZE} for slot table entries).</p>
     */
    public static final int ENTRY_FIXED_OVERHEAD = 8 + 4 + 4 + 1 + 1;

    /**
     * Size of the entry header (fields before variable-length data).
     *
     * <p>The header contains:</p>
     * <ul>
     *   <li>key_hash: 8 bytes</li>
     *   <li>key_len: 4 bytes</li>
     *   <li>value_len: 4 bytes</li>
     *   <li>distance: 1 byte</li>
     * </ul>
     *
     * <p>Total: 17 bytes</p>
     *
     * <p>Variable-length key bytes start at offset 17 (ENTRY_HEADER_SIZE).</p>
     */
    public static final int ENTRY_HEADER_SIZE = 8 + 4 + 4 + 1;

    /**
     * Size of the entry trailer (tombstone byte).
     *
     * <p>The tombstone byte is at the end of the entry:</p>
     * <ul>
     *   <li>0 = live entry</li>
     *   <li>1 = deleted entry (tombstoned)</li>
     * </ul>
     */
    public static final int ENTRY_TRAILER_SIZE = 1;

    // ==================== Entry Field Offsets ====================

    /**
     * Offset of key_hash field within an entry.
     *
     * <p>8 bytes, little-endian, representing a 64-bit hash value.</p>
     */
    public static final int KEY_HASH_OFFSET = 0;

    /**
     * Offset of key_len field within an entry.
     *
     * <p>4 bytes, little-endian, signed int representing key byte count.</p>
     */
    public static final int KEY_LEN_OFFSET = 8;

    /**
     * Offset of value_len field within an entry.
     *
     * <p>4 bytes, little-endian, signed int representing value byte count.</p>
     */
    public static final int VALUE_LEN_OFFSET = 12;

    /**
     * Offset of distance field within an entry.
     *
     * <p>1 byte, unsigned, representing distance from ideal slot (0-127).</p>
     */
    public static final int DISTANCE_OFFSET = 16;

    /**
     * Offset where variable-length key bytes start within an entry.
     *
     * <p>This is the start of the variable-length portion of the entry.</p>
     */
    public static final int KEY_BYTES_OFFSET = 17;

    // ==================== Tombstone Values ====================

    /**
     * Tombstone value indicating a live (non-deleted) entry.
     */
    public static final byte TOMBSTONE_LIVE = 0;

    /**
     * Tombstone value indicating a deleted entry.
     *
     * <p>When an entry is deleted, it is marked with this tombstone value
     * rather than being immediately removed. This allows concurrent readers
     * to continue accessing the segment safely. Tombstones are cleaned up
     * during compaction.</p>
     */
    public static final byte TOMBSTONE_DELETED = 1;

    // ==================== Slot Table Constants ====================

    /**
     * Size of each slot in the hash table's slot table region.
     *
     * <p>The slot table is separate from the data region and contains
     * fixed-size slot entries for efficient probing:</p>
     *
     * <pre>
     * Offset 0:  key_hash (8 bytes, little-endian)
     * Offset 8:  distance (1 byte, unsigned)
     * Offset 9:  flags (1 byte, bit 0: occupied, bit 1: tombstone)
     * Offset 10: data_offset (6 bytes, unsigned - offset into data region)
     * Offset 16: reserved (8 bytes, for alignment to 24 bytes total)
     * </pre>
     *
     * <p>Total: 24 bytes per slot</p>
     *
     * <h3>Note</h3>
     * <p>Slots point to entries in the data region. The slot contains
     * frequently-accessed fields (hash, distance, flags) for fast probing,
     * while the full entry (including key/value bytes) is stored separately.</p>
     */
    public static final int SLOT_SIZE = 24;

    /**
     * Maximum distance value that can be stored (fits in unsigned byte).
     *
     * <p>Distance values 0-127 are valid. A distance of 128 would overflow
     * the unsigned byte storage.</p>
     */
    public static final int MAX_DISTANCE = 127;

    // ==================== Utility Methods ====================

    /**
     * Validates that a capacity value is valid.
     *
     * <p>A valid capacity must be:</p>
     * <ul>
     *   <li>A power of 2</li>
     *   <li>In the range [{@link #MIN_SEGMENT_CAPACITY}, {@link #MAX_SEGMENT_CAPACITY}]</li>
     * </ul>
     *
     * @param capacity The capacity to validate
     * @return The validated capacity
     * @throws IllegalArgumentException if capacity is invalid
     */
    public static int validateCapacity(int capacity) {
        if (capacity < MIN_SEGMENT_CAPACITY) {
            throw new IllegalArgumentException(
                "Capacity " + capacity + " is below minimum " + MIN_SEGMENT_CAPACITY);
        }
        if (capacity > MAX_SEGMENT_CAPACITY) {
            throw new IllegalArgumentException(
                "Capacity " + capacity + " exceeds maximum " + MAX_SEGMENT_CAPACITY);
        }
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                "Capacity " + capacity + " is not a power of 2");
        }
        return capacity;
    }

    /**
     * Validates that a load factor is within acceptable bounds.
     *
     * @param entryCount Current number of entries
     * @param slotCount Total number of slots
     * @throws IllegalStateException if load factor would exceed threshold
     */
    public static void validateLoadFactor(int entryCount, int slotCount) {
        double loadFactor = (double) entryCount / slotCount;
        if (loadFactor >= LOAD_FACTOR_THRESHOLD) {
            throw new IllegalStateException(
                "Load factor " + loadFactor + " would exceed threshold " + LOAD_FACTOR_THRESHOLD);
        }
    }

    /**
     * Validates that a distance value is in the valid range.
     *
     * @param distance The distance to validate
     * @throws IllegalArgumentException if distance is out of range
     */
    public static void validateDistance(int distance) {
        if (distance < 0 || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException(
                "Distance must be in range [0, " + MAX_DISTANCE + "], got: " + distance);
        }
    }

    /**
     * Validates that a tombstone value is exactly 0 (live) or 1 (deleted).
     *
     * <p>This is a critical domain constraint for the storage engine. The tombstone
     * byte in the entry format must be exactly 0 or 1 to ensure data integrity.
     * Any other value indicates data corruption.</p>
     *
     * @param tombstone The tombstone byte value to validate
     * @throws IllegalArgumentException if tombstone is not 0 or 1
     */
    public static void validateTombstone(byte tombstone) {
        if (tombstone != TOMBSTONE_LIVE && tombstone != TOMBSTONE_DELETED) {
            throw new IllegalArgumentException(
                "Tombstone must be exactly 0 (live) or 1 (deleted), got: " + tombstone);
        }
    }

    /**
     * Checks if a tombstone value is valid (exactly 0 or 1).
     *
     * @param tombstone The tombstone byte value to check
     * @return true if tombstone is 0 or 1, false otherwise
     */
    public static boolean isValidTombstone(byte tombstone) {
        return tombstone == TOMBSTONE_LIVE || tombstone == TOMBSTONE_DELETED;
    }

    /**
     * Rounds a capacity value up to the nearest valid power of 2.
     *
     * <p>The result is clamped to [{@link #MIN_SEGMENT_CAPACITY}, {@link #MAX_SEGMENT_CAPACITY}].</p>
     *
     * @param requestedCapacity The requested capacity
     * @return The nearest valid power-of-2 capacity
     */
    public static int roundToValidCapacity(int requestedCapacity) {
        if (requestedCapacity <= MIN_SEGMENT_CAPACITY) {
            return MIN_SEGMENT_CAPACITY;
        }
        if (requestedCapacity >= MAX_SEGMENT_CAPACITY) {
            return MAX_SEGMENT_CAPACITY;
        }

        // Find next power of 2
        int power = Integer.highestOneBit(requestedCapacity);
        if (power < requestedCapacity) {
            power <<= 1;
        }

        return Math.min(power, MAX_SEGMENT_CAPACITY);
    }

    /**
     * Calculates the total entry size for given key and value lengths.
     *
     * @param keyLen Key length in bytes
     * @param valueLen Value length in bytes
     * @return Total entry size in bytes
     */
    public static int calculateEntrySize(int keyLen, int valueLen) {
        return ENTRY_FIXED_OVERHEAD + keyLen + valueLen;
    }

    /**
     * Calculates the load factor for a segment.
     *
     * @param entryCount Number of entries
     * @param slotCount Number of slots
     * @return Load factor (0.0 to 1.0+)
     */
    public static double calculateLoadFactor(int entryCount, int slotCount) {
        return (double) entryCount / slotCount;
    }

    /**
     * Checks if a capacity value is a power of 2.
     *
     * @param capacity The capacity to check
     * @return true if capacity is a power of 2
     */
    public static boolean isPowerOf2(int capacity) {
        return capacity > 0 && (capacity & (capacity - 1)) == 0;
    }

    /**
     * Computes the slot index for a hash value.
     *
     * <p>Uses bitwise AND for efficient modulo with power-of-2 capacity.</p>
     *
     * @param hash The 64-bit hash value
     * @param slotCount The number of slots (must be power of 2)
     * @return The slot index
     */
    public static int computeSlotIndex(long hash, int slotCount) {
        return (int) (hash & (slotCount - 1));
    }

    // ==================== Key/Value Length Constants ====================

    /**
     * Maximum allowed key length in bytes.
     *
     * <p>This limit ensures that the total entry size calculation does not overflow.
     * The value is derived from the constraint that key_len must be in the range
     * [0, Integer.MAX_VALUE - 17].</p>
     *
     * <p>Rationale: The entry header is 17 bytes, and the tombstone is 1 byte.
     * By limiting key_len to Integer.MAX_VALUE - 17, we ensure that the header
     * plus key can always be represented as a positive int.</p>
     */
    public static final int MAX_KEY_LEN = Integer.MAX_VALUE - 17;

    /**
     * Maximum allowed value length in bytes.
     *
     * <p>This limit ensures that the total entry size calculation does not overflow.
     * The value is derived from the constraint that value_len must be in the range
     * [0, Integer.MAX_VALUE - 17].</p>
     *
     * <p>Rationale: Similar to key_len, this ensures that the value portion of
     * the entry can always be represented as a positive int.</p>
     */
    public static final int MAX_VALUE_LEN = Integer.MAX_VALUE - 17;

    // ==================== Key/Value Length Validation ====================

    /**
     * Validates that a key length is in the valid range [0, MAX_KEY_LEN].
     *
     * <p>Domain constraint: key_len must be in range [0, Integer.MAX_VALUE - 17].</p>
     *
     * @param keyLen The key length to validate
     * @throws IllegalArgumentException if key length is negative or exceeds maximum
     */
    public static void validateKeyLen(int keyLen) {
        if (keyLen < 0) {
            throw new IllegalArgumentException(
                "Key length must be non-negative, got: " + keyLen);
        }
        if (keyLen > MAX_KEY_LEN) {
            throw new IllegalArgumentException(
                "Key length " + keyLen + " exceeds maximum " + MAX_KEY_LEN);
        }
    }

    /**
     * Validates that a value length is in the valid range [0, MAX_VALUE_LEN].
     *
     * <p>Domain constraint: value_len must be in range [0, Integer.MAX_VALUE - 17].</p>
     *
     * @param valueLen The value length to validate
     * @throws IllegalArgumentException if value length is negative or exceeds maximum
     */
    public static void validateValueLen(int valueLen) {
        if (valueLen < 0) {
            throw new IllegalArgumentException(
                "Value length must be non-negative, got: " + valueLen);
        }
        if (valueLen > MAX_VALUE_LEN) {
            throw new IllegalArgumentException(
                "Value length " + valueLen + " exceeds maximum " + MAX_VALUE_LEN);
        }
    }

    /**
     * Checks if a key length is in the valid range [0, MAX_KEY_LEN].
     *
     * @param keyLen The key length to check
     * @return true if the key length is valid, false otherwise
     */
    public static boolean isValidKeyLen(int keyLen) {
        return keyLen >= 0 && keyLen <= MAX_KEY_LEN;
    }

    /**
     * Checks if a value length is in the valid range [0, MAX_VALUE_LEN].
     *
     * @param valueLen The value length to check
     * @return true if the value length is valid, false otherwise
     */
    public static boolean isValidValueLen(int valueLen) {
        return valueLen >= 0 && valueLen <= MAX_VALUE_LEN;
    }

    // ==================== Key Hash Validation ====================

    /**
     * Validates that a key_hash value is in the valid range [0, Long.MAX_VALUE].
     *
     * <p>Key hashes must be positive 64-bit values to ensure consistent
     * slot computation and to avoid issues with signed arithmetic.</p>
     *
     * @param keyHash The key hash to validate
     * @return The validated key hash
     * @throws IllegalArgumentException if key hash is negative
     */
    public static long validateKeyHash(long keyHash) {
        if (keyHash < 0) {
            throw new IllegalArgumentException(
                "key_hash must be in range [0, Long.MAX_VALUE], got: " + keyHash);
        }
        return keyHash;
    }

    /**
     * Checks if a key_hash value is in the valid range [0, Long.MAX_VALUE].
     *
     * @param keyHash The key hash to check
     * @return true if the hash is valid (non-negative), false otherwise
     */
    public static boolean isValidKeyHash(long keyHash) {
        return keyHash >= 0;
    }

    /**
     * Normalizes a raw 64-bit hash to the valid key_hash range [0, Long.MAX_VALUE].
     *
     * <p>This method masks off the sign bit (most significant bit) to ensure
     * the result is always non-negative. This is used by the Segment class
     * to convert xxHash64 output to valid key_hash values.</p>
     *
     * <h3>Mathematical Correctness</h3>
     * <ul>
     *   <li>Long.MAX_VALUE = 0x7FFFFFFFFFFFFFFF (2^63 - 1)</li>
     *   <li>Masking with Long.MAX_VALUE clears the sign bit</li>
     *   <li>Result is always in range [0, 2^63 - 1] = [0, Long.MAX_VALUE]</li>
     * </ul>
     *
     * @param rawHash The raw 64-bit hash value (may be negative)
     * @return The normalized hash in range [0, Long.MAX_VALUE]
     */
    public static long normalizeKeyHash(long rawHash) {
        return rawHash & Long.MAX_VALUE;
    }

    // Private constructor to prevent instantiation

    private SegmentConfig() {
        throw new AssertionError("SegmentConfig is a constants class and cannot be instantiated");
    }
}
