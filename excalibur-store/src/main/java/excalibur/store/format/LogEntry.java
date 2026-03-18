package excalibur.store.format;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Represents a write-ahead log entry for crash recovery.
 *
 * <h2>Log Entry Format (Memory Layout)</h2>
 * <pre>
 * Offset 0:   opcode (1 byte: PUT=0x01, DELETE=0x02)
 * Offset 1:   lsn (8 bytes, monotonically increasing, little-endian)
 * Offset 9:   timestamp (8 bytes, epoch millis, little-endian)
 * Offset 17:  key_hash (8 bytes)
 * Offset 25:  key_len (4 bytes)
 * Offset 29:  value_len (4 bytes, 0 for DELETE)
 * Offset 33:  key_bytes (variable)
 * Offset 33+key_len: value_bytes (variable, empty for DELETE)
 * Offset 33+key_len+value_len: crc32 (4 bytes, CRC-32 of all preceding bytes)
 * </pre>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>{@code opcode}: Exactly 0x01 (PUT) or 0x02 (DELETE)</li>
 *   <li>{@code lsn}: Range [0, Long.MAX_VALUE], monotonically increasing</li>
 *   <li>{@code timestamp}: Positive 64-bit epoch milliseconds</li>
 *   <li>{@code key_hash}: Range [0, Long.MAX_VALUE] (positive 64-bit)</li>
 *   <li>{@code key_len}: Range [0, Integer.MAX_VALUE - 37]</li>
 *   <li>{@code value_len}: Range [0, Integer.MAX_VALUE - 37] (0 for DELETE)</li>
 *   <li>{@code crc32}: CRC-32 checksum of all bytes preceding it, little-endian</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Instances of this class are immutable once created.
 * All read operations are thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class LogEntry {

    // ==================== Format Constants ====================

    /**
     * Opcode for PUT operation (insert or update).
     */
    public static final byte OPCODE_PUT = 0x01;

    /**
     * Opcode for DELETE operation.
     */
    public static final byte OPCODE_DELETE = 0x02;

    /**
     * Offset of opcode field (1 byte).
     */
    public static final int OPCODE_OFFSET = 0;

    /**
     * Size of opcode field in bytes.
     */
    public static final int OPCODE_SIZE = 1;

    /**
     * Offset of LSN field (8 bytes, little-endian).
     */
    public static final int LSN_OFFSET = 1;

    /**
     * Size of LSN field in bytes.
     */
    public static final int LSN_SIZE = 8;

    /**
     * Offset of timestamp field (8 bytes, epoch millis, little-endian).
     */
    public static final int TIMESTAMP_OFFSET = 9;

    /**
     * Size of timestamp field in bytes.
     */
    public static final int TIMESTAMP_SIZE = 8;

    /**
     * Offset of key_hash field (8 bytes, little-endian).
     */
    public static final int KEY_HASH_OFFSET = 17;

    /**
     * Size of key_hash field in bytes.
     */
    public static final int KEY_HASH_SIZE = 8;

    /**
     * Offset of key_len field (4 bytes, little-endian).
     */
    public static final int KEY_LEN_OFFSET = 25;

    /**
     * Size of key_len field in bytes.
     */
    public static final int KEY_LEN_SIZE = 4;

    /**
     * Offset of value_len field (4 bytes, little-endian).
     */
    public static final int VALUE_LEN_OFFSET = 29;

    /**
     * Size of value_len field in bytes.
     */
    public static final int VALUE_LEN_SIZE = 4;

    /**
     * Offset where variable-length key bytes start.
     */
    public static final int KEY_BYTES_OFFSET = 33;

    /**
     * Fixed header size (all fields before variable key/value data).
     * opcode(1) + lsn(8) + timestamp(8) + key_hash(8) + key_len(4) + value_len(4) = 33 bytes
     */
    public static final int HEADER_SIZE = OPCODE_SIZE + LSN_SIZE + TIMESTAMP_SIZE +
                                          KEY_HASH_SIZE + KEY_LEN_SIZE + VALUE_LEN_SIZE;

    /**
     * Size of CRC32 field at end of entry (4 bytes, little-endian).
     */
    public static final int CRC32_SIZE = 4;

    /**
     * Fixed overhead per entry (header + CRC32).
     * 33 + 4 = 37 bytes
     */
    public static final int ENTRY_OVERHEAD = HEADER_SIZE + CRC32_SIZE;

    // ==================== Instance Fields ====================

    private final byte opcode;
    private final long lsn;
    private final long timestamp;
    private final long keyHash;
    private final byte[] key;
    private final byte[] value;

    // ==================== Constructors ====================

    /**
     * Creates a new log entry.
     *
     * @param opcode   Operation type (PUT or DELETE)
     * @param lsn      Log sequence number (must be non-negative)
     * @param timestamp Epoch timestamp in milliseconds (must be positive)
     * @param keyHash  64-bit hash of the key (must be non-negative)
     * @param key      The key bytes (must not be null)
     * @param value    The value bytes (must not be null; can be empty for DELETE)
     * @throws IllegalArgumentException if any constraint is violated
     * @throws NullPointerException if key or value is null
     */
    public LogEntry(byte opcode, long lsn, long timestamp, long keyHash, byte[] key, byte[] value) {
        validateOpcode(opcode);
        validateLsn(lsn);
        validateTimestamp(timestamp);
        validateKeyHash(keyHash);
        validateKey(key);
        validateValue(value, opcode);

        this.opcode = opcode;
        this.lsn = lsn;
        this.timestamp = timestamp;
        this.keyHash = keyHash;
        this.key = key.clone(); // Defensive copy for immutability
        this.value = value.clone(); // Defensive copy for immutability
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a PUT log entry.
     *
     * @param lsn      Log sequence number
     * @param timestamp Epoch timestamp in milliseconds
     * @param keyHash  64-bit hash of the key
     * @param key      The key bytes
     * @param value    The value bytes
     * @return A new PUT log entry
     */
    public static LogEntry put(long lsn, long timestamp, long keyHash, byte[] key, byte[] value) {
        return new LogEntry(OPCODE_PUT, lsn, timestamp, keyHash, key, value);
    }

    /**
     * Creates a DELETE log entry.
     *
     * @param lsn      Log sequence number
     * @param timestamp Epoch timestamp in milliseconds
     * @param keyHash  64-bit hash of the key
     * @param key      The key bytes
     * @return A new DELETE log entry
     */
    public static LogEntry delete(long lsn, long timestamp, long keyHash, byte[] key) {
        return new LogEntry(OPCODE_DELETE, lsn, timestamp, keyHash, key, new byte[0]);
    }

    /**
     * Reads a log entry from a MemorySegment at the specified offset.
     *
     * <p>This method validates the CRC32 checksum before returning the entry.
     * If the checksum is invalid, the entry is considered corrupted.</p>
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The entry read from the segment
     * @throws IndexOutOfBoundsException if offset is invalid or entry extends beyond segment
     * @throws NullPointerException if segment is null
     * @throws IllegalStateException if CRC32 validation fails
     */
    public static LogEntry readFrom(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Read header fields using ByteBuffer for unaligned access
        ByteBuffer headerBuffer = segment.asSlice(offset, HEADER_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);

        byte opcode = headerBuffer.get(OPCODE_OFFSET);
        long lsn = headerBuffer.getLong(LSN_OFFSET);
        long timestamp = headerBuffer.getLong(TIMESTAMP_OFFSET);
        long keyHash = headerBuffer.getLong(KEY_HASH_OFFSET);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);
        int valueLen = headerBuffer.getInt(VALUE_LEN_OFFSET);

        // Read variable-length key bytes
        byte[] key = new byte[keyLen];
        MemorySegment keySlice = segment.asSlice(offset + KEY_BYTES_OFFSET, keyLen);
        keySlice.asByteBuffer().get(key);

        // Read variable-length value bytes
        byte[] value = new byte[valueLen];
        if (valueLen > 0) {
            MemorySegment valueSlice = segment.asSlice(offset + KEY_BYTES_OFFSET + keyLen, valueLen);
            valueSlice.asByteBuffer().get(value);
        }

        // Read CRC32 (little-endian)
        long crcOffset = offset + KEY_BYTES_OFFSET + keyLen + valueLen;
        ByteBuffer crcBuffer = segment.asSlice(crcOffset, CRC32_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int storedCrc = crcBuffer.getInt();

        // Compute and validate CRC32
        int computedCrc = computeCrc32(segment, offset, HEADER_SIZE + keyLen + valueLen);
        if (storedCrc != computedCrc) {
            throw new IllegalStateException(
                "CRC32 validation failed at offset " + offset +
                ". Stored: 0x" + Integer.toHexString(storedCrc) +
                ", Computed: 0x" + Integer.toHexString(computedCrc));
        }

        return new LogEntry(opcode, lsn, timestamp, keyHash, key, value);
    }

    /**
     * Reads a log entry from a MemorySegment without CRC32 validation.
     * Use this for performance-critical paths where data integrity is already guaranteed.
     *
     * @param segment The memory segment to read from
     * @param offset  The byte offset within the segment
     * @return The entry read from the segment
     */
    public static LogEntry readFromUnchecked(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        ByteBuffer headerBuffer = segment.asSlice(offset, HEADER_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);

        byte opcode = headerBuffer.get(OPCODE_OFFSET);
        long lsn = headerBuffer.getLong(LSN_OFFSET);
        long timestamp = headerBuffer.getLong(TIMESTAMP_OFFSET);
        long keyHash = headerBuffer.getLong(KEY_HASH_OFFSET);
        int keyLen = headerBuffer.getInt(KEY_LEN_OFFSET);
        int valueLen = headerBuffer.getInt(VALUE_LEN_OFFSET);

        byte[] key = new byte[keyLen];
        MemorySegment keySlice = segment.asSlice(offset + KEY_BYTES_OFFSET, keyLen);
        keySlice.asByteBuffer().get(key);

        byte[] value = new byte[valueLen];
        if (valueLen > 0) {
            MemorySegment valueSlice = segment.asSlice(offset + KEY_BYTES_OFFSET + keyLen, valueLen);
            valueSlice.asByteBuffer().get(value);
        }

        return new LogEntry(opcode, lsn, timestamp, keyHash, key, value);
    }

    /**
     * Reads a log entry from a ByteBuffer at the specified offset.
     *
     * @param buffer The byte buffer to read from (must be in little-endian order)
     * @param offset The byte offset within the buffer
     * @return The entry read from the buffer
     * @throws IndexOutOfBoundsException if offset is invalid
     * @throws NullPointerException if buffer is null
     */
    public static LogEntry readFrom(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");

        ByteBuffer view = buffer.order().equals(ByteOrder.LITTLE_ENDIAN)
                ? buffer
                : buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        byte opcode = view.get(offset + OPCODE_OFFSET);
        long lsn = view.getLong(offset + LSN_OFFSET);
        long timestamp = view.getLong(offset + TIMESTAMP_OFFSET);
        long keyHash = view.getLong(offset + KEY_HASH_OFFSET);
        int keyLen = view.getInt(offset + KEY_LEN_OFFSET);
        int valueLen = view.getInt(offset + VALUE_LEN_OFFSET);

        byte[] key = new byte[keyLen];
        view.position(offset + KEY_BYTES_OFFSET);
        view.get(key);

        byte[] value = new byte[valueLen];
        view.get(value);

        // Read and validate CRC32
        int storedCrc = view.getInt();
        int computedCrc = computeCrc32(buffer, offset, HEADER_SIZE + keyLen + valueLen);
        if (storedCrc != computedCrc) {
            throw new IllegalStateException("CRC32 validation failed");
        }

        return new LogEntry(opcode, lsn, timestamp, keyHash, key, value);
    }

    /**
     * Validates CRC32 checksum of an entry in a MemorySegment.
     *
     * @param segment The memory segment
     * @param offset  The byte offset of the entry
     * @return true if CRC32 is valid, false otherwise
     */
    public static boolean validateCrc32(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        // Read key_len and value_len to calculate entry size
        ByteBuffer headerBuffer = segment.asSlice(offset + KEY_LEN_OFFSET, 8).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = headerBuffer.getInt();
        int valueLen = headerBuffer.getInt();

        // Read stored CRC32
        long crcOffset = offset + KEY_BYTES_OFFSET + keyLen + valueLen;
        ByteBuffer crcBuffer = segment.asSlice(crcOffset, CRC32_SIZE).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int storedCrc = crcBuffer.getInt();

        // Compute CRC32
        int computedCrc = computeCrc32(segment, offset, HEADER_SIZE + keyLen + valueLen);

        return storedCrc == computedCrc;
    }

    // ==================== Write Methods ====================

    /**
     * Writes this entry to a MemorySegment at the specified offset.
     *
     * <p>This method computes and writes the CRC32 checksum at the end of the entry.</p>
     *
     * @param segment The memory segment to write to
     * @param offset  The byte offset within the segment
     * @throws IndexOutOfBoundsException if offset is invalid or entry extends beyond segment
     * @throws NullPointerException if segment is null
     */
    public void writeTo(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        int entrySize = size();
        ByteBuffer buffer = segment.asSlice(offset, entrySize).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);

        // Write fixed header fields
        buffer.put(OPCODE_OFFSET, opcode);
        buffer.putLong(LSN_OFFSET, lsn);
        buffer.putLong(TIMESTAMP_OFFSET, timestamp);
        buffer.putLong(KEY_HASH_OFFSET, keyHash);
        buffer.putInt(KEY_LEN_OFFSET, key.length);
        buffer.putInt(VALUE_LEN_OFFSET, value.length);

        // Write variable-length key bytes
        buffer.position(KEY_BYTES_OFFSET);
        buffer.put(key);

        // Write variable-length value bytes
        buffer.put(value);

        // Compute and write CRC32
        int crc32 = computeCrc32(segment, offset, HEADER_SIZE + key.length + value.length);
        buffer.putInt(crc32);
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

        ByteBuffer view = buffer.order().equals(ByteOrder.LITTLE_ENDIAN)
                ? buffer
                : buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        // Write fixed header fields
        view.put(offset + OPCODE_OFFSET, opcode);
        view.putLong(offset + LSN_OFFSET, lsn);
        view.putLong(offset + TIMESTAMP_OFFSET, timestamp);
        view.putLong(offset + KEY_HASH_OFFSET, keyHash);
        view.putInt(offset + KEY_LEN_OFFSET, key.length);
        view.putInt(offset + VALUE_LEN_OFFSET, value.length);

        // Write variable-length key bytes
        view.position(offset + KEY_BYTES_OFFSET);
        view.put(key);

        // Write variable-length value bytes
        view.put(value);

        // Compute and write CRC32
        int dataLen = HEADER_SIZE + key.length + value.length;
        CRC32 crc = new CRC32();

        // Update CRC with header
        byte[] headerBytes = new byte[HEADER_SIZE];
        ByteBuffer headerView = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        headerView.put(OPCODE_OFFSET, opcode);
        headerView.putLong(LSN_OFFSET, lsn);
        headerView.putLong(TIMESTAMP_OFFSET, timestamp);
        headerView.putLong(KEY_HASH_OFFSET, keyHash);
        headerView.putInt(KEY_LEN_OFFSET, key.length);
        headerView.putInt(VALUE_LEN_OFFSET, value.length);
        crc.update(headerBytes);

        // Update CRC with key and value
        crc.update(key);
        crc.update(value);

        int crcValue = (int) crc.getValue();
        view.putInt(crcValue);
    }

    // ==================== Size Calculation ====================

    /**
     * Calculates the total size of a log entry with the given key and value lengths.
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
     * Calculates the size of a log entry at the given offset in a MemorySegment.
     *
     * @param segment The memory segment
     * @param offset  The byte offset of the entry
     * @return Total entry size in bytes
     */
    public static int sizeAt(MemorySegment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        ByteBuffer buffer = segment.asSlice(offset + KEY_LEN_OFFSET, 8).asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN);
        int keyLen = buffer.getInt();
        int valueLen = buffer.getInt();
        return calculateSize(keyLen, valueLen);
    }

    // ==================== CRC32 Computation ====================

    /**
     * Computes CRC32 checksum of the entry data (excluding the CRC32 field itself).
     *
     * @param segment The memory segment
     * @param offset  The byte offset of the entry
     * @param dataLen The length of data to checksum (header + key + value)
     * @return The CRC32 checksum value
     */
    private static int computeCrc32(MemorySegment segment, long offset, int dataLen) {
        CRC32 crc = new CRC32();
        byte[] data = new byte[dataLen];
        MemorySegment dataSlice = segment.asSlice(offset, dataLen);
        dataSlice.asByteBuffer().get(data);
        crc.update(data);
        return (int) crc.getValue();
    }

    /**
     * Computes CRC32 checksum from a ByteBuffer.
     *
     * @param buffer  The byte buffer
     * @param offset  The byte offset of the entry
     * @param dataLen The length of data to checksum
     * @return The CRC32 checksum value
     */
    private static int computeCrc32(ByteBuffer buffer, int offset, int dataLen) {
        CRC32 crc = new CRC32();
        byte[] data = new byte[dataLen];

        ByteBuffer view = buffer.duplicate();
        view.position(offset);
        view.get(data);

        crc.update(data);
        return (int) crc.getValue();
    }

    // ==================== Validation Methods ====================

    /**
     * Validates that an opcode is valid (PUT or DELETE).
     *
     * @param opcode The opcode to validate
     * @throws IllegalArgumentException if opcode is invalid
     */
    public static void validateOpcode(byte opcode) {
        if (opcode != OPCODE_PUT && opcode != OPCODE_DELETE) {
            throw new IllegalArgumentException(
                "Invalid opcode: 0x" + String.format("%02X", opcode) +
                ". Must be PUT (0x01) or DELETE (0x02)");
        }
    }

    /**
     * Validates that an LSN is in the valid range [0, Long.MAX_VALUE].
     *
     * @param lsn The LSN to validate
     * @throws IllegalArgumentException if LSN is negative
     */
    public static void validateLsn(long lsn) {
        if (lsn < 0) {
            throw new IllegalArgumentException(
                "LSN must be non-negative, got: " + lsn);
        }
    }

    /**
     * Validates that a timestamp is positive.
     *
     * @param timestamp The timestamp to validate
     * @throws IllegalArgumentException if timestamp is not positive
     */
    public static void validateTimestamp(long timestamp) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException(
                "Timestamp must be positive epoch milliseconds, got: " + timestamp);
        }
    }

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
     * @param key The key to validate
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key is empty or too long
     */
    public static void validateKey(byte[] key) {
        Objects.requireNonNull(key, "Key cannot be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (key.length > Integer.MAX_VALUE - ENTRY_OVERHEAD) {
            throw new IllegalArgumentException(
                "Key length too large: " + key.length);
        }
    }

    /**
     * Validates that a value is non-null and appropriate for the opcode.
     *
     * @param value  The value to validate
     * @param opcode The operation type
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if value is too long or invalid for opcode
     */
    public static void validateValue(byte[] value, byte opcode) {
        Objects.requireNonNull(value, "Value cannot be null");
        if (opcode == OPCODE_DELETE && value.length > 0) {
            throw new IllegalArgumentException(
                "DELETE operation must have empty value, got length: " + value.length);
        }
        if (value.length > Integer.MAX_VALUE - ENTRY_OVERHEAD) {
            throw new IllegalArgumentException(
                "Value length too large: " + value.length);
        }
    }

    /**
     * Checks if an opcode indicates a PUT operation.
     *
     * @param opcode The opcode to check
     * @return true if PUT, false otherwise
     */
    public static boolean isPut(byte opcode) {
        return opcode == OPCODE_PUT;
    }

    /**
     * Checks if an opcode indicates a DELETE operation.
     *
     * @param opcode The opcode to check
     * @return true if DELETE, false otherwise
     */
    public static boolean isDelete(byte opcode) {
        return opcode == OPCODE_DELETE;
    }

    // ==================== Getters ====================

    /**
     * Returns the operation opcode.
     *
     * @return The opcode (PUT or DELETE)
     */
    public byte opcode() {
        return opcode;
    }

    /**
     * Returns the log sequence number.
     *
     * @return The LSN (always non-negative)
     */
    public long lsn() {
        return lsn;
    }

    /**
     * Returns the timestamp in epoch milliseconds.
     *
     * @return The timestamp (always positive)
     */
    public long timestamp() {
        return timestamp;
    }

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
     * @return The value bytes (empty for DELETE operations)
     */
    public byte[] value() {
        return value.clone();
    }

    /**
     * Returns the value length in bytes.
     *
     * @return Value length (0 for DELETE operations)
     */
    public int valueLength() {
        return value.length;
    }

    /**
     * Returns whether this is a PUT operation.
     *
     * @return true if PUT, false if DELETE
     */
    public boolean isPut() {
        return opcode == OPCODE_PUT;
    }

    /**
     * Returns whether this is a DELETE operation.
     *
     * @return true if DELETE, false if PUT
     */
    public boolean isDelete() {
        return opcode == OPCODE_DELETE;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return opcode == logEntry.opcode &&
               lsn == logEntry.lsn &&
               timestamp == logEntry.timestamp &&
               keyHash == logEntry.keyHash &&
               java.util.Arrays.equals(key, logEntry.key) &&
               java.util.Arrays.equals(value, logEntry.value);
    }

    @Override
    public int hashCode() {
        int result = Byte.hashCode(opcode);
        result = 31 * result + Long.hashCode(lsn);
        result = 31 * result + Long.hashCode(timestamp);
        result = 31 * result + Long.hashCode(keyHash);
        result = 31 * result + java.util.Arrays.hashCode(key);
        result = 31 * result + java.util.Arrays.hashCode(value);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "LogEntry{opcode=0x%02X, lsn=%d, timestamp=%d, keyHash=0x%016X, keyLen=%d, valueLen=%d}",
            opcode, lsn, timestamp, keyHash, key.length, value.length);
    }

    /**
     * Returns a detailed string representation including key bytes (for debugging).
     *
     * @param maxKeyBytes Maximum number of key bytes to include in output
     * @return Detailed string representation
     */
    public String toString(int maxKeyBytes) {
        StringBuilder sb = new StringBuilder("LogEntry{");
        sb.append(String.format("opcode=0x%02X (%s), ", opcode, isPut() ? "PUT" : "DELETE"));
        sb.append(String.format("lsn=%d, timestamp=%d, ", lsn, timestamp));
        sb.append(String.format("keyHash=0x%016X, ", keyHash));

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

        sb.append(String.format("valueLen=%d}", value.length));
        return sb.toString();
    }
}
