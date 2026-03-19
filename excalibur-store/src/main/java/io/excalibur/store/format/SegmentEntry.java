package io.excalibur.store.format;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Represents a segment entry with its binary format for Robin Hood hashing.
 *
 * <h2>Entry Format (Memory Layout)</h2>
 * <pre>
 * Offset 0:   key_hash (8 bytes, little-endian)
 * Offset 8:   key_len (4 bytes, signed int)
 * Offset 12:  value_len (4 bytes, signed int)
 * Offset 16:  distance (1 byte, unsigned - distance from ideal slot)
 * Offset 17:  key_bytes (variable)
 * Offset 17+key_len: value_bytes (variable)
 * Offset 17+key_len+value_len: tombstone (1 byte, 0=live, 1=deleted)
 * </pre>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>{@code key_hash}: Range [0, Long.MAX_VALUE] (positive 64-bit)</li>
 *   <li>{@code key_len}: Range [0, Integer.MAX_VALUE - 17]</li>
 *   <li>{@code value_len}: Range [0, Integer.MAX_VALUE - 17]</li>
 *   <li>{@code distance}: Range [0, 127] (fits in unsigned byte)</li>
 *   <li>{@code tombstone}: Exactly 0 (live) or 1 (deleted)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Read operations are thread-safe. Write operations require external synchronization.
 * Instances of this class are immutable once created.</p>
 *
 * @author ExcaliburDB
 */
public final class SegmentEntry {

    // ==================== Format Constants ====================

    /**
     * Offset of key_hash field (8 bytes, little-endian).
     */
    public static final int KEY_HASH_OFFSET = 0;

    /**
     * Size of key_hash field in bytes.
     */
    public static final int KEY_HASH_SIZE = 8;

    /**
     * Offset of key_len field (4 bytes, signed int).
     */
    public static final int KEY_LEN_OFFSET = 8;

    /**
     * Size of key_len field in bytes.
     */
    public static final int KEY_LEN_SIZE = 4;

    /**
     * Offset of value_len field (4 bytes, signed int).
     */
    public static final int VALUE_LEN_OFFSET = 12;

    /**
     * Size of value_len field in bytes.
     */
    public static final int VALUE_LEN_SIZE = 4;

    /**
     * Offset of distance field (1 byte, unsigned).
     */
    public static final int DISTANCE_OFFSET = 16;

    /**
     * Size of distance field in bytes.
     */
    public static final int DISTANCE_SIZE = 1;

    /**
     * Offset where variable-length key bytes start.
     */
    public static final int KEY_BYTES_OFFSET = 17;

    /**
     * Maximum allowed distance value (fits in unsigned byte).
     * Domain constraint: distance must be in range [0, 127].
     */
    public static final int MAX_DISTANCE = 127;

    /**
     * Tombstone value indicating a live entry.
     */
    public static final byte TOMBSTONE_LIVE = 0;

    /**
     * Tombstone value indicating a deleted entry.
     */
    public static final byte TOMBSTONE_DELETED = 1;

    /**
     * Fixed header size (all fields before variable key/value data).
     * key_hash(8) + key_len(4) + value_len(4) + distance(1) = 17 bytes
     */
    public static final int HEADER_SIZE = KEY_HASH_SIZE + KEY_LEN_SIZE + VALUE_LEN_SIZE + DISTANCE_SIZE;

    /**
     * Fixed overhead per entry (header + tombstone).
     * 17 + 1 = 18 bytes
     */
    public static final int ENTRY_OVERHEAD = HEADER_SIZE + 1;

    /**
     * Maximum allowed key length in bytes.
     *
     * <p>Domain constraint: key_len must be in range [0, Integer.MAX_VALUE - 17].
     * This ensures that the header (17 bytes) plus key length can be represented
     * as a positive int without overflow.</p>
     *
     * <p>Rationale: When computing offsets like {@code 17 + key_len}, we must ensure
     * the result doesn't overflow. With key_len at most Integer.MAX_VALUE - 17,
     * the sum 17 + key_len ≤ Integer.MAX_VALUE.</p>
     */
    public static final int MAX_KEY_LEN = Integer.MAX_VALUE - HEADER_SIZE;

    /**
     * Maximum allowed value length in bytes.
     *
     * <p>Domain constraint: value_len must be in range [0, Integer.MAX_VALUE - 17].
     * This ensures consistent limits between key and value lengths.</p>
     */
    public static final int MAX_VALUE_LEN = Integer.MAX_VALUE - HEADER_SIZE;

    // ==================== Instance Fields ====================

    private final long keyHash;
    private final byte[] key;
    private final byte[] value;
    private final int distance;
    private final boolean tombstoned;

    // ==================== Constructors ====================

    /**
     * Creates a new segment entry.
     *
     * @param keyHash   64-bit hash of the key (must be non-negative)
     * @param key       The key bytes (must not be null)
     * @param value     The value bytes (must not be null)
     * @param distance  Distance from ideal slot (must be in range [0, 127])
     * @param tombstoned Whether this entry is deleted
     * @throws IllegalArgumentException if any constraint is violated
     * @throws NullPointerException if key or value is null
     */
    public SegmentEntry(long keyHash, byte[] key, byte[] value, int distance, boolean tombstoned) {
        validateKeyHash(keyHash);
        validateKey(key);
        validateValue(value);
        validateDistance(distance);

        this.keyHash = keyHash;
        this.key = key.clone(); // Defensive copy for immutability
        this.value = value.clone(); // Defensive copy for immutability
        this.distance = distance;
        this.tombstoned = tombstoned;
    }

    /**
     * Creates a new live (non-tombstoned) segment entry.
     *
     * @param keyHash  64-bit hash of the key
     * @param key      The key bytes
     * @param value    The value bytes
     * @param distance Distance from ideal slot
     */
    public SegmentEntry(long keyHash, byte[] key, byte[] value, int distance) {
        this(keyHash, key, value, distance, false);
    }

    // ==================== Factory Methods ====================

    /**
     * Reads an entry from a MemorySegment at the specified offset.
     *
     * <p>This method uses ByteBuffer for reading header fields to support unaligned memory access.
     * The ByteBuffer is obtained from a slice of the MemorySegment, which allows reading at
     * arbitrary byte offsets without alignment constraints.</p>
     *
     * <p>The returned entry contains copies of the key and value data, but the original segment
     * remains the source of truth.</p>
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The entry read from the segment
     * @throws IndexOutOfBoundsException if offset is invalid or entry extends beyond segment
     * @throws NullPointerException if segment is null
     */
    public static SegmentEntry readFrom(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Create a ByteBuffer view for reading header fields (handles unaligned access)
        // We need at least HEADER_SIZE bytes to read the header
        ByteBuffer headerBuffer = segment.asSlice(offset, HEADER_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);

        // Read fixed header fields
        long keyHash = headerBuffer.getLong(KEY_HASH_OFFSET);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);
        int valueLen = headerBuffer.getInt(VALUE_LEN_OFFSET);
        int distance = headerBuffer.get(DISTANCE_OFFSET) & 0xFF;

        // Read variable-length key bytes
        byte[] key = new byte[keyLen];
        MemorySegment keySlice = segment.asSlice(offset + KEY_BYTES_OFFSET, keyLen);
        keySlice.asByteBuffer().get(key);

        // Read variable-length value bytes
        byte[] value = new byte[valueLen];
        MemorySegment valueSlice = segment.asSlice(offset + KEY_BYTES_OFFSET + keyLen, valueLen);
        valueSlice.asByteBuffer().get(value);

        // Read and validate tombstone
        byte tombstoneByte = segment.get(ValueLayout.JAVA_BYTE, offset + KEY_BYTES_OFFSET + keyLen + valueLen);
        validateTombstone(tombstoneByte);
        boolean tombstoned = (tombstoneByte == TOMBSTONE_DELETED);

        return new SegmentEntry(keyHash, key, value, distance, tombstoned);
    }

    /**
     * Reads an entry from a MemorySegment at the specified offset using ByteBuffer.
     *
     * <p>Alternative read method for compatibility with ByteBuffer-based code.</p>
     *
     * @param buffer The byte buffer to read from (must be in little-endian order)
     * @param offset The byte offset within the buffer
     * @return The entry read from the buffer
     * @throws IndexOutOfBoundsException if offset is invalid
     * @throws NullPointerException if buffer is null
     */
    public static SegmentEntry readFrom(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");

        // Ensure little-endian byte order
        ByteBuffer view = buffer.order().equals(ByteOrder.LITTLE_ENDIAN)
                ? buffer
                : buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        // Read fixed header fields
        long keyHash = view.getLong(offset + KEY_HASH_OFFSET);
        int keyLen = view.getInt(offset + KEY_LEN_OFFSET);
        int valueLen = view.getInt(offset + VALUE_LEN_OFFSET);
        int distance = view.get(offset + DISTANCE_OFFSET) & 0xFF;

        // Read variable-length key bytes
        byte[] key = new byte[keyLen];
        view.position(offset + KEY_BYTES_OFFSET);
        view.get(key);

        // Read variable-length value bytes
        byte[] value = new byte[valueLen];
        view.get(value);

        // Read and validate tombstone
        byte tombstoneByte = view.get();
        validateTombstone(tombstoneByte);
        boolean tombstoned = (tombstoneByte == TOMBSTONE_DELETED);

        return new SegmentEntry(keyHash, key, value, distance, tombstoned);
    }

    /**
     * Reads just the key from a MemorySegment at the specified offset.
     * This is more efficient than reading the full entry when only the key is needed.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The key bytes
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static byte[] readKey(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Use ByteBuffer for reading key_len (handles unaligned access)
        ByteBuffer headerBuffer = segment.asSlice(offset, KEY_BYTES_OFFSET).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);

        byte[] key = new byte[keyLen];
        MemorySegment keySlice = segment.asSlice(offset + KEY_BYTES_OFFSET, keyLen);
        keySlice.asByteBuffer().get(key);
        return key;
    }

    /**
     * Reads just the value from a MemorySegment at the specified offset.
     * Returns a MemorySegment slice for zero-copy access.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return A MemorySegment slice pointing to the value, or null if tombstoned
     * @throws IndexOutOfBoundsException if offset is invalid
     * @throws IllegalArgumentException if tombstone byte is not 0 or 1
     */
    public static MemorySegment readValueSlice(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Use ByteBuffer for reading header fields (handles unaligned access)
        ByteBuffer headerBuffer = segment.asSlice(offset, KEY_BYTES_OFFSET).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);
        int valueLen = headerBuffer.getInt(VALUE_LEN_OFFSET);
        byte tombstoneByte = segment.get(ValueLayout.JAVA_BYTE, offset + KEY_BYTES_OFFSET + keyLen + valueLen);

        // Validate tombstone is exactly 0 or 1
        validateTombstone(tombstoneByte);

        if (tombstoneByte == TOMBSTONE_DELETED) {
            return null;
        }

        return segment.asSlice(offset + KEY_BYTES_OFFSET + keyLen, valueLen);
    }

    /**
     * Reads just the value bytes from a MemorySegment at the specified offset.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The value bytes, or null if tombstoned
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static byte[] readValue(MemorySegment segment, long offset) {
        MemorySegment slice = readValueSlice(segment, offset);
        if (slice == null) {
            return null;
        }
        return slice.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Reads the key hash from a MemorySegment at the specified offset.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The 64-bit key hash
     */
    public static long readKeyHash(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        // Use ByteBuffer for reading (handles unaligned access)
        ByteBuffer buffer = segment.asSlice(offset, KEY_HASH_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getLong(KEY_HASH_OFFSET);
    }

    /**
     * Reads the distance from a MemorySegment at the specified offset.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The distance value (0-127)
     */
    public static int readDistance(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        return segment.get(ValueLayout.JAVA_BYTE, offset + DISTANCE_OFFSET) & 0xFF;
    }

    /**
     * Reads the tombstone status from a MemorySegment at the specified offset.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return true if the entry is tombstoned (deleted), false otherwise
     * @throws IllegalArgumentException if tombstone byte is not 0 or 1
     */
    public static boolean isTombstoned(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Use ByteBuffer for reading header fields (handles unaligned access)
        ByteBuffer headerBuffer = segment.asSlice(offset, HEADER_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);
        int valueLen = headerBuffer.getInt(VALUE_LEN_OFFSET);
        byte tombstoneByte = segment.get(ValueLayout.JAVA_BYTE, offset + KEY_BYTES_OFFSET + keyLen + valueLen);

        // Validate tombstone is exactly 0 or 1
        validateTombstone(tombstoneByte);

        return tombstoneByte == TOMBSTONE_DELETED;
    }

    // ==================== Write Methods ====================

    /**
     * Writes this entry to a MemorySegment at the specified offset.
     *
     * <p>This method uses ByteBuffer for writing header fields to support unaligned memory access.
     * The ByteBuffer is obtained from a slice of the MemorySegment, which allows writing at
     * arbitrary byte offsets without alignment constraints.</p>
     *
     * @param segment The memory segment to write to
     * @param offset  The byte offset within the segment
     * @throws IndexOutOfBoundsException if offset is invalid or entry extends beyond segment
     * @throws NullPointerException if segment is null
     */
    public void writeTo(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Create a ByteBuffer view for writing header fields (handles unaligned access)
        int entrySize = size();
        ByteBuffer buffer = segment.asSlice(offset, entrySize).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);

        // Write fixed header fields
        buffer.putLong(KEY_HASH_OFFSET, keyHash);
        buffer.putInt(KEY_LEN_OFFSET, key.length);
        buffer.putInt(VALUE_LEN_OFFSET, value.length);
        buffer.put(DISTANCE_OFFSET, (byte) distance);

        // Write variable-length key bytes
        buffer.position(KEY_BYTES_OFFSET);
        buffer.put(key);

        // Write variable-length value bytes
        buffer.put(value);

        // Write tombstone
        byte tombstoneByte = tombstoned ? TOMBSTONE_DELETED : TOMBSTONE_LIVE;
        buffer.put(tombstoneByte);
    }

    /**
     * Writes this entry to a ByteBuffer at the specified offset.
     *
     * @param buffer The byte buffer to write to (must be in little-endian order)
     * @param offset The byte offset within the buffer
     * @throws IndexOutOfBoundsException if offset is invalid
     * @throws NullPointerException if buffer is null
     */
    public void writeTo(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");

        // Ensure little-endian byte order for writing
        ByteBuffer view = buffer.order().equals(ByteOrder.LITTLE_ENDIAN)
                ? buffer
                : buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        // Write fixed header fields
        view.putLong(offset + KEY_HASH_OFFSET, keyHash);
        view.putInt(offset + KEY_LEN_OFFSET, key.length);
        view.putInt(offset + VALUE_LEN_OFFSET, value.length);
        view.put(offset + DISTANCE_OFFSET, (byte) distance);

        // Write variable-length key bytes
        view.position(offset + KEY_BYTES_OFFSET);
        view.put(key);

        // Write variable-length value bytes
        view.put(value);

        // Write tombstone
        byte tombstoneByte = tombstoned ? TOMBSTONE_DELETED : TOMBSTONE_LIVE;
        view.put(tombstoneByte);
    }

    /**
     * Writes only the distance field to a MemorySegment at the specified offset.
     * Useful for updating distance during Robin Hood hashing without rewriting the entire entry.
     *
     * @param segment  The memory segment to write to
     * @param offset   The byte offset of the entry within the segment
     * @param distance The new distance value
     */
    public static void writeDistance(MemorySegment segment, long offset, int distance) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        validateDistance(distance);
        segment.set(ValueLayout.JAVA_BYTE, offset + DISTANCE_OFFSET, (byte) distance);
    }

    /**
     * Writes the tombstone field to a MemorySegment at the specified offset.
     * Marks the entry as deleted.
     *
     * @param segment The memory segment to write to
     * @param offset  The byte offset of the entry within the segment
     */
    public static void writeTombstone(MemorySegment segment, long offset, int keyLen, int valueLen) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        segment.set(ValueLayout.JAVA_BYTE, offset + KEY_BYTES_OFFSET + keyLen + valueLen, TOMBSTONE_DELETED);
    }

    /**
     * Clears the tombstone field to a MemorySegment at the specified offset.
     * Marks the entry as live.
     *
     * @param segment The memory segment to write to
     * @param offset  The byte offset of the entry within the segment
     */
    public static void clearTombstone(MemorySegment segment, long offset, int keyLen, int valueLen) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        segment.set(ValueLayout.JAVA_BYTE, offset + KEY_BYTES_OFFSET + keyLen + valueLen, TOMBSTONE_LIVE);
    }

    // ==================== Size Calculation ====================

    /**
     * Calculates the total size of an entry with the given key and value lengths.
     *
     * @param keyLen   Length of the key in bytes
     * @param valueLen Length of the value in bytes
     * @return Total entry size in bytes
     */
    public static int calculateSize(int keyLen, int valueLen) {
        return ENTRY_OVERHEAD + keyLen + valueLen;
    }

    /**
     * Returns the total size of this entry in bytes.
     *
     * @return Entry size in bytes
     */
    public int size() {
        return calculateSize(key.length, value.length);
    }

    /**
     * Calculates the size of an entry at the given offset in a MemorySegment.
     *
     * @param segment The memory segment
     * @param offset  The byte offset of the entry
     * @return Total entry size in bytes
     */
    public static int sizeAt(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        // Use ByteBuffer for reading (handles unaligned access)
        ByteBuffer buffer = segment.asSlice(offset, HEADER_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = buffer.getInt(KEY_LEN_OFFSET);
        int valueLen = buffer.getInt(VALUE_LEN_OFFSET);
        return calculateSize(keyLen, valueLen);
    }

    // ==================== Validation Methods ====================

    /**
     * Validates that a key hash is in the valid range [0, Long.MAX_VALUE].
     *
     * @param keyHash The key hash to validate
     * @throws IllegalArgumentException if key hash is negative
     */
    public static void validateKeyHash(long keyHash) {
        if (keyHash < 0) {
            throw new IllegalArgumentException(
                "Key hash must be non-negative, got: " + keyHash);
        }
    }

    /**
     * Validates that a key is non-null and non-empty.
     *
     * <p>Domain constraint: key_len must be in range [0, Integer.MAX_VALUE - 17].</p>
     *
     * @param key The key to validate
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key is empty or too long
     */
    public static void validateKey(byte[] key) {
        Objects.requireNonNull(key, "Key cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (key.length > MAX_KEY_LEN) {
            throw new IllegalArgumentException(
                "Key length " + key.length + " exceeds maximum " + MAX_KEY_LEN);
        }
    }

    /**
     * Validates that a value is non-null.
     *
     * <p>Domain constraint: value_len must be in range [0, Integer.MAX_VALUE - 17].</p>
     *
     * @param value The value to validate
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if value is too long
     */
    public static void validateValue(byte[] value) {
        Objects.requireNonNull(value, "Value cannot be null");
        // Empty values are allowed
        if (value.length > MAX_VALUE_LEN) {
            throw new IllegalArgumentException(
                "Value length " + value.length + " exceeds maximum " + MAX_VALUE_LEN);
        }
    }

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

    /**
     * Validates that a distance is in the valid range [0, 127].
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
     * Checks if an entry at the given offset is empty (slot never used).
     * An empty slot has key_len == 0.
     *
     * @param segment The memory segment
     * @param offset  The byte offset
     * @return true if the slot is empty, false otherwise
     */
    public static boolean isEmptySlot(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        // Use ByteBuffer for reading (handles unaligned access)
        ByteBuffer buffer = segment.asSlice(offset, KEY_BYTES_OFFSET).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt(KEY_LEN_OFFSET) == 0;
    }

    /**
     * Marks a slot as empty by zeroing the key_len field.
     *
     * @param segment The memory segment
     * @param offset  The byte offset
     */
    public static void markEmptySlot(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        // Use ByteBuffer for writing (handles unaligned access)
        ByteBuffer buffer = segment.asSlice(offset, KEY_BYTES_OFFSET).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(KEY_LEN_OFFSET, 0);
    }

    // ==================== Getters ====================

    /**
     * Returns the 64-bit key hash.
     *
     * @return The key hash (always non-negative)
     */
    public long keyHash() {
        return keyHash;
    }

    /**
     * Returns a copy of the key bytes.
     *
     * @return The key bytes
     */
    public byte[] key() {
        return key.clone();
    }

    /**
     * Returns the key length in bytes.
     *
     * @return Key length
     */
    public int keyLength() {
        return key.length;
    }

    /**
     * Returns a copy of the value bytes.
     *
     * @return The value bytes
     */
    public byte[] value() {
        return value.clone();
    }

    /**
     * Returns the value length in bytes.
     *
     * @return Value length
     */
    public int valueLength() {
        return value.length;
    }

    /**
     * Returns the distance from the ideal slot.
     *
     * @return Distance (0-127)
     */
    public int distance() {
        return distance;
    }

    /**
     * Returns whether this entry is tombstoned (deleted).
     *
     * @return true if deleted, false if live
     */
    public boolean isTombstoned() {
        return tombstoned;
    }

    // ==================== Transformation Methods ====================

    /**
     * Creates a new entry with the updated distance.
     *
     * @param newDistance The new distance value
     * @return A new entry with the updated distance
     */
    public SegmentEntry withDistance(int newDistance) {
        validateDistance(newDistance);
        return new SegmentEntry(keyHash, key, value, newDistance, tombstoned);
    }

    /**
     * Creates a new entry with the updated value.
     *
     * @param newValue The new value bytes
     * @return A new entry with the updated value
     */
    public SegmentEntry withValue(byte[] newValue) {
        validateValue(newValue);
        return new SegmentEntry(keyHash, key, newValue, distance, tombstoned);
    }

    /**
     * Creates a new entry marked as tombstoned.
     *
     * @return A new entry marked as deleted
     */
    public SegmentEntry asTombstoned() {
        return new SegmentEntry(keyHash, key, value, distance, true);
    }

    /**
     * Creates a new entry marked as live (not tombstoned).
     *
     * @return A new entry marked as live
     */
    public SegmentEntry asLive() {
        return new SegmentEntry(keyHash, key, value, distance, false);
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SegmentEntry that = (SegmentEntry) o;
        return keyHash == that.keyHash &&
               distance == that.distance &&
               tombstoned == that.tombstoned &&
               java.util.Arrays.equals(key, that.key) &&
               java.util.Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(keyHash);
        result = 31 * result + java.util.Arrays.hashCode(key);
        result = 31 * result + java.util.Arrays.hashCode(value);
        result = 31 * result + distance;
        result = 31 * result + Boolean.hashCode(tombstoned);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "SegmentEntry{keyHash=0x%016X, keyLen=%d, valueLen=%d, distance=%d, tombstoned=%s}",
            keyHash, key.length, value.length, distance, tombstoned);
    }

    /**
     * Returns a detailed string representation including key bytes (for debugging).
     *
     * @param maxKeyBytes Maximum number of key bytes to include in output
     * @return Detailed string representation
     */
    public String toString(int maxKeyBytes) {
        StringBuilder sb = new StringBuilder("SegmentEntry{");
        sb.append(String.format("keyHash=0x%016X, ", keyHash));

        // Show truncated key for debugging
        int showBytes = Math.min(maxKeyBytes, key.length);
        sb.append("key=[");
        for (int i = 0; i < showBytes; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("0x%02X", key[i]));
        }
        if (key.length > showBytes) {
            sb.append(", ...");
        }
        sb.append("], ");

        sb.append(String.format("keyLen=%d, valueLen=%d, distance=%d, tombstoned=%s}",
            key.length, value.length, distance, tombstoned));
        return sb.toString();
    }
}
