package io.excalibur.store.log;

import io.excalibur.core.checksum.CRC32Checksum;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable log entry representing a single entry in the WriteLog.
 *
 * <p>This class wraps the raw entry data and provides convenient accessors
 * for parsing and validation.</p>
 *
 * <h2>Entry Format</h2>
 * <p>Each log entry follows the consistent format: {@code [offset | entry_data]}</p>
 * <table border="1" cellpadding="4" cellspacing="0">
 *   <tr><th>Field</th><th>Size (bytes)</th><th>Description</th></tr>
 *   <tr><td>offset</td><td>implicit (8 bytes in context)</td><td>Byte offset in log file (non-negative)</td></tr>
 *   <tr><td>entryType</td><td>1</td><td>Entry type (PUT=1, DELETE=2, BARRIER=3)</td></tr>
 *   <tr><td>entryId</td><td>8</td><td>Unique entry identifier (monotonically increasing)</td></tr>
 *   <tr><td>checksum</td><td>4</td><td>CRC32 checksum of entry data (unsigned 32-bit)</td></tr>
 *   <tr><td>payload</td><td>N</td><td>Entry-specific data (variable length)</td></tr>
 * </table>
 *
 * <p><strong>Consistent Format Representation:</strong> {@code [offset={offset} | type={type} id={id} crc={crc} payload={payload}]}</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Log entries MUST be immutable after construction</li>
 *   <li>Entry IDs MUST be unique within a log</li>
 *   <li>Offsets MUST be non-negative and monotonically increasing</li>
 *   <li>Entry types MUST be valid (1=PUT, 2=DELETE, 3=BARRIER)</li>
 *   <li>Checksums MUST be valid CRC32 values</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class LogEntry {

    /** Barrier entry type for ordering guarantees. */
    public static final byte TYPE_BARRIER = 3;

    /** Put log entry type. */
    public static final byte TYPE_PUT = 1;

    /** Delete log entry type. */
    public static final byte TYPE_DELETE = 2;

    /**
     * Fixed header size: entryType (1 byte) + entryId (8 bytes) + checksum (4 bytes).
     */
    public static final int HEADER_SIZE = 13;

    // Internal constant for checksum calculation
    private static final int CHECKSUM_PAYLOAD_OFFSET = 9; // After type (1) + id (8)

    private final byte type;
    private final long id;
    private final byte[] payload;
    private final long offset;
    private final long checksum;

    /**
     * Creates a new LogEntry.
     *
     * @param type Entry type
     * @param id Unique entry identifier
     * @param payload Entry payload data
     * @param offset Offset of this entry in the log file
     * @param checksum CRC32 checksum of the entry
     */
    public LogEntry(byte type, long id, byte[] payload, long offset, long checksum) {
        this.type = type;
        this.id = id;
        this.payload = payload;
        this.offset = offset;
        this.checksum = checksum;
    }

    /**
     * Creates a LogEntry with auto-calculated checksum.
     *
     * @param type Entry type
     * @param id Unique entry identifier
     * @param payload Entry payload data
     * @param offset Offset of this entry in the log file
     */
    public LogEntry(byte type, long id, byte[] payload, long offset) {
        this(type, id, payload, offset, CRC32Checksum.compute(payload));
    }

    // ==================== Getters ====================

    /**
     * Gets the entry type.
     *
     * @return Entry type (TYPE_PUT, TYPE_DELETE, or TYPE_BARRIER)
     */
    public byte getType() {
        return type;
    }

    /**
     * Checks if this is a PUT entry.
     *
     * @return true if this is a PUT entry
     */
    public boolean isPut() {
        return type == TYPE_PUT;
    }

    /**
     * Checks if this is a DELETE entry.
     *
     * @return true if this is a DELETE entry
     */
    public boolean isDelete() {
        return type == TYPE_DELETE;
    }

    /**
     * Checks if this is a barrier entry.
     *
     * @return true if this is a barrier entry
     */
    public boolean isBarrier() {
        return type == TYPE_BARRIER;
    }

    /**
     * Gets the unique entry identifier.
     *
     * @return Entry ID
     */
    public long getId() {
        return id;
    }

    /**
     * Gets the entry payload.
     *
     * @return Payload data, or null for barrier entries
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Gets the offset of this entry in the log file.
     *
     * @return File offset
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Gets the CRC32 checksum of this entry.
     *
     * @return Checksum value
     */
    public long getChecksum() {
        return checksum;
    }

    // ==================== Parsing ====================

    /**
     * Parses a PUT log entry.
     *
     * @return PutEntry with key and value, or null if not a PUT entry
     */
    public PutEntry asPutEntry() {
        if (!isPut()) {
            return null;
        }

        byte[] payload = getPayload();
        if (payload == null || payload.length < 1) {
            return null;
        }

        int keyLen = payload[0];
        if (keyLen == 0 || keyLen > payload.length - 1) {
            return null;
        }

        byte[] keyBytes = new byte[keyLen];
        System.arraycopy(payload, 1, keyBytes, 0, keyLen);

        int valueLen = payload.length - 1 - keyLen;
        byte[] valueBytes = valueLen > 0 ? new byte[valueLen] : null;
        if (valueLen > 0) {
            System.arraycopy(payload, 1 + keyLen, valueBytes, 0, valueLen);
        }

        return new PutEntry(keyBytes, valueBytes, id, offset);
    }

    /**
     * Parses a DELETE log entry.
     *
     * @return DeleteEntry with key, or null if not a DELETE entry
     */
    public DeleteEntry asDeleteEntry() {
        if (!isDelete()) {
            return null;
        }

        byte[] payload = getPayload();
        byte[] keyBytes = payload != null ? new byte[payload.length] : null;
        if (payload != null) {
            System.arraycopy(payload, 0, keyBytes, 0, payload.length);
        }

        return new DeleteEntry(keyBytes, id, offset);
    }

    /**
     * Parses a barrier entry.
     *
     * @return BarrierEntry with offset information
     */
    public BarrierEntry asBarrierEntry() {
        if (!isBarrier()) {
            return null;
        }
        return new BarrierEntry(id, offset);
    }

    // ==================== Validation ====================

    /**
     * Verifies the checksum of this entry.
     *
     * @return true if checksum is valid
     */
    public boolean verifyChecksum() {
        return calculateChecksum(type, id, payload) == checksum;
    }

    /**
     * Calculates the CRC32 checksum for this entry.
     *
     * @return CRC32 checksum
     */
    private long calculateChecksum(byte type, long id, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8 + (payload != null ? payload.length : 0));
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(type);
        buffer.putLong(id);
        if (payload != null) {
            buffer.put(payload);
        }
        return CRC32Checksum.compute(buffer.array());
    }

    /**
     * Gets the estimated size of this entry in bytes.
     *
     * @return Entry size
     */
    public long estimateSize() {
        return HEADER_SIZE + (payload != null ? payload.length : 0);
    }

    // ==================== Utility ====================

    /**
     * Creates a copy of this entry.
     *
     * @return A new LogEntry with a copied payload
     */
    public LogEntry copy() {
        if (payload == null) {
            return new LogEntry(type, id, null, offset, checksum);
        }
        return new LogEntry(type, id, payload.clone(), offset, checksum);
    }

    /**
     * Compares this entry with another for equality.
     *
     * @param other The other entry
     * @return true if equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LogEntry)) return false;
        LogEntry that = (LogEntry) other;
        return type == that.type &&
               id == that.id &&
               payload != null ? payload.length == that.payload.length &&
                              java.util.Arrays.equals(payload, that.payload) : that.payload == null &&
               offset == that.offset &&
               checksum == that.checksum;
    }

    /**
     * Generates a hash code for this entry.
     *
     * @return Hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + (int) type;
        result = 31 * result + (int) (id ^ (id >>> 32));
        result = 31 * result + (payload != null ? payload.length : 0);
        result = 31 * result + (int) (offset ^ (offset >>> 32));
        result = 31 * result + (int) (checksum ^ (checksum >>> 32));
        return result;
    }

    /**
     * Returns a string representation of this entry in the consistent format.
     *
     * <p>Format: {@code [offset={offset} | type={type} id={id} len={len} crc=0x{crc}]}</p>
     *
     * @return String representation in consistent format: [offset | entry_data]
     */
    @Override
    public String toString() {
        // Consistent format: [offset | entry_data]
        // entry_data = type id len crc
        return String.format("[offset=%d | type=%s id=%d len=%d crc=0x%08X]",
                offset, typeToString(), id, payload != null ? payload.length : 0, checksum);
    }

    /**
     * Returns a detailed string representation with payload preview.
     *
     * <p>Format: {@code [offset={offset} | type={type} id={id} len={len} crc=0x{crc} payload={preview}]}</p>
     *
     * @param maxPayloadBytes Maximum payload bytes to include in preview
     * @return Detailed string representation
     */
    public String toString(int maxPayloadBytes) {
        StringBuilder sb = new StringBuilder("[offset=");
        sb.append(offset);
        sb.append(" | type=");
        sb.append(typeToString());
        sb.append(" id=");
        sb.append(id);
        sb.append(" len=");
        sb.append(payload != null ? payload.length : 0);
        sb.append(" crc=0x");
        sb.append(String.format("%08X", checksum));

        if (payload != null && payload.length > 0) {
            sb.append(" payload=");
            int showBytes = Math.min(maxPayloadBytes, payload.length);
            sb.append("[");
            for (int i = 0; i < showBytes; i++) {
                if (i > 0) sb.append(" ");
                sb.append(String.format("%02X", payload[i] & 0xFF));
            }
            if (payload.length > showBytes) {
                sb.append(" ...");
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    private String typeToString() {
        switch (type) {
            case TYPE_PUT: return "PUT";
            case TYPE_DELETE: return "DELETE";
            case TYPE_BARRIER: return "BARRIER";
            default: return "UNKNOWN(" + type + ")";
        }
    }

    // ==================== Public Classes ====================

    /**
     * Represents a PUT log entry.
     *
     * <p>Format: {@code [offset | PUT id={id} keyLen={keyLen} valLen={valLen}]}</p>
     */
    public static class PutEntry {
        private final byte[] key;
        private final byte[] value;
        private final long id;
        private final long offset;

        PutEntry(byte[] key, byte[] value, long id) {
            this(key, value, id, -1);
        }

        PutEntry(byte[] key, byte[] value, long id, long offset) {
            this.key = key;
            this.value = value;
            this.id = id;
            this.offset = offset;
        }

        public byte[] getKey() {
            return key;
        }

        public String getKeyAsString() {
            return new String(key, java.nio.charset.StandardCharsets.UTF_8);
        }

        public byte[] getValue() {
            return value;
        }

        public String getValueAsString() {
            return value != null ? new String(value, java.nio.charset.StandardCharsets.UTF_8) : null;
        }

        public long getId() {
            return id;
        }

        public long getOffset() {
            return offset;
        }

        @Override
        public String toString() {
            // Consistent format: [offset | entry_data]
            return String.format("[offset=%d | PUT id=%d keyLen=%d valLen=%d]",
                               offset, id, key != null ? key.length : 0, value != null ? value.length : 0);
        }
    }

    /**
     * Represents a DELETE log entry.
     *
     * <p>Format: {@code [offset | DELETE id={id} keyLen={keyLen}]}</p>
     */
    public static class DeleteEntry {
        private final byte[] key;
        private final long id;
        private final long offset;

        DeleteEntry(byte[] key, long id) {
            this(key, id, -1);
        }

        DeleteEntry(byte[] key, long id, long offset) {
            this.key = key;
            this.id = id;
            this.offset = offset;
        }

        public byte[] getKey() {
            return key;
        }

        public String getKeyAsString() {
            return key != null ? new String(key, java.nio.charset.StandardCharsets.UTF_8) : null;
        }

        public long getId() {
            return id;
        }

        public long getOffset() {
            return offset;
        }

        @Override
        public String toString() {
            // Consistent format: [offset | entry_data]
            return String.format("[offset=%d | DELETE id=%d keyLen=%d]",
                               offset, id, key != null ? key.length : 0);
        }
    }

    /**
     * Represents a barrier entry.
     *
     * <p>Format: {@code [offset | BARRIER id={id}]}</p>
     */
    public static class BarrierEntry {
        private final long id;
        private final long offset;

        BarrierEntry(long id, long offset) {
            this.id = id;
            this.offset = offset;
        }

        public long getId() {
            return id;
        }

        public long getOffset() {
            return offset;
        }

        @Override
        public String toString() {
            // Consistent format: [offset | entry_data]
            return String.format("[offset=%d | BARRIER id=%d]", offset, id);
        }
    }

    // ==================== Main Method for Testing ====================

    public static void main(String[] args) {
        System.out.println("Testing LogEntry...");

        // Create a PUT entry
        byte[] key = "testKey".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = "testValue".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LogEntry putEntry = new LogEntry(TYPE_PUT, 1L, payload(key, value), 100L);

        System.out.println("PUT Entry: " + putEntry);
        System.out.println("  Type: " + putEntry.getType());
        System.out.println("  ID: " + putEntry.getId());
        System.out.println("  Is PUT: " + putEntry.isPut());
        System.out.println("  Checksum valid: " + putEntry.verifyChecksum());

        // Parse the entry
        PutEntry parsedPut = putEntry.asPutEntry();
        if (parsedPut != null) {
            System.out.println("  Parsed key: " + parsedPut.getKeyAsString());
            System.out.println("  Parsed value: " + parsedPut.getValueAsString());
        }

        // Create a DELETE entry
        LogEntry deleteEntry = new LogEntry(TYPE_DELETE, 2L, key, 200L);
        DeleteEntry parsedDelete = deleteEntry.asDeleteEntry();
        System.out.println("\nDELETE Entry: " + deleteEntry);
        System.out.println("  Parsed key: " + parsedDelete.getKeyAsString());

        // Create a barrier entry
        LogEntry barrierEntry = new LogEntry(TYPE_BARRIER, 3L, null, 300L);
        BarrierEntry parsedBarrier = barrierEntry.asBarrierEntry();
        System.out.println("\nBARRIER Entry: " + barrierEntry);
        System.out.println("  Offset: " + parsedBarrier.getOffset());

        // Test equality
        LogEntry anotherPut = new LogEntry(TYPE_PUT, 1L, payload(key, value), 100L);
        System.out.println("\nEquality test: " + putEntry.equals(anotherPut));

        System.out.println("\nLogEntry tests completed.");
    }

    private static byte[] payload(byte[] key, byte[] value) {
        int keyLen = key.length;
        int valueLen = value.length;
        byte[] payload = new byte[1 + keyLen + valueLen];
        payload[0] = (byte) keyLen;
        System.arraycopy(key, 0, payload, 1, keyLen);
        System.arraycopy(value, 0, payload, 1 + keyLen, valueLen);
        return payload;
    }
}
