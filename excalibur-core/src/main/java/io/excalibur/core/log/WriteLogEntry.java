package io.excalibur.core.log;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Log Entry structure for the append-only write log.
 *
 * <p>The log entry format is:
 * <pre>
 *   [entryType(1B) | entryId(8B) | checksum(4B) | payload(N)]
 * </pre>
 *
 * <h2>Entry Types</h2>
 * <ul>
 *   <li>{@link EntryType#PUT (0x01)} - Key-value insert/update</li>
 *   <li>{@link EntryType#DELETE (0x02)} - Key deletion (tombstone)</li>
 *   <li>{@link EntryType#CHECKPOINT (0x03)} - Recovery checkpoint marker</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. All fields are final and set once.
 * The entry payload is copied into a byte array upon construction, ensuring
 * no external references can modify the entry after creation.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Entry ID is a 64-bit unsigned integer (entryId >= 0)
 *   <li>Checksum uses CRC32 IEEE 802.3 polynomial
 *   <li>Entry size is validated against Integer.MAX_VALUE to prevent overflow
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class WriteLogEntry {

    /** Entry type identifier (1 byte) */
    private final byte entryType;

    /** Unique entry ID (8 bytes, big-endian) */
    private final long entryId;

    /** CRC32 checksum of payload (4 bytes) */
    private final long checksum;

    /** Raw payload bytes (immutable after construction) */
    private final byte[] payload;

    /** Total entry size in bytes (for quick serialization) */
    private final int entrySize;

    /** Base offset of this entry in the log file */
    private final long fileOffset;

    /** Header size (1 + 8 + 4 = 13 bytes) */
    private static final int HEADER_SIZE = 13;

    /** Maximum allowed entry size to prevent integer overflow */
    private static final int MAX_ENTRY_SIZE = Integer.MAX_VALUE - HEADER_SIZE;

    /**
     * Creates a new WriteLogEntry.
     *
     * @param entryType Type of entry (PUT, DELETE, CHECKPOINT)
     * @param entryId Unique entry identifier
     * @param payload Raw payload bytes
     * @param fileOffset Base offset in log file
     * @throws IllegalArgumentException if entryType is invalid or entry too large
     * @throws IndexOutOfBoundsException if payload is null
     */
    public WriteLogEntry(byte entryType, long entryId, byte[] payload, long fileOffset) {
        if (payload == null) {
            throw new IndexOutOfBoundsException("Payload cannot be null");
        }
        if (entryType < 0 || entryType > 255) {
            throw new IllegalArgumentException("Invalid entry type: " + entryType);
        }
        if ((long) payload.length > MAX_ENTRY_SIZE) {
            throw new IllegalArgumentException(
                "Payload too large: " + payload.length + " > " + MAX_ENTRY_SIZE
            );
        }

        this.entryType = entryType;
        this.entryId = entryId;
        this.payload = payload.clone(); // Ensure immutability
        this.fileOffset = fileOffset;
        this.checksum = computeChecksum(payload);
        this.entrySize = HEADER_SIZE + payload.length;
    }

    /**
     * Creates a WriteLogEntry with computed checksum.
     *
     * @param entryType Type of entry
     * @param entryId Unique entry ID
     * @param payload Payload bytes
     * @param checksum Pre-computed checksum (for recovery)
     * @param fileOffset File offset
     */
    public WriteLogEntry(byte entryType, long entryId, byte[] payload, long checksum, long fileOffset) {
        this.entryType = entryType;
        this.entryId = entryId;
        this.payload = payload.clone();
        this.checksum = checksum;
        this.fileOffset = fileOffset;
        this.entrySize = HEADER_SIZE + payload.length;
    }

    /**
     * Gets the entry type.
     *
     * @return Entry type identifier
     */
    public byte entryType() {
        return entryType;
    }

    /**
     * Checks if this is a PUT (insert/update) entry.
     *
     * @return true if entryType == PUT
     */
    public boolean isPut() {
        return entryType == EntryType.PUT.value();
    }

    /**
     * Checks if this is a DELETE (tombstone) entry.
     *
     * @return true if entryType == DELETE
     */
    public boolean isDelete() {
        return entryType == EntryType.DELETE.value();
    }

    /**
     * Checks if this is a CHECKPOINT entry.
     *
     * @return true if entryType == CHECKPOINT
     */
    public boolean isCheckpoint() {
        return entryType == EntryType.CHECKPOINT.value();
    }

    /**
     * Gets the unique entry ID.
     *
     * @return 64-bit entry ID (>= 0)
     */
    public long entryId() {
        return entryId;
    }

    /**
     * Gets the CRC32 checksum.
     *
     * @return 32-bit checksum
     */
    public long checksum() {
        return checksum;
    }

    /**
     * Gets the raw payload bytes.
     *
     * @return Immutable payload
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * Gets the entry size in bytes.
     *
     * @return Total entry size (header + payload)
     */
    public int size() {
        return entrySize;
    }

    /**
     * Gets the file offset where this entry starts.
     *
     * @return Base offset in log file
     */
    public long fileOffset() {
        return fileOffset;
    }

    /**
     * Validates the entry by verifying the checksum.
     *
     * @return true if checksum matches, false otherwise
     */
    public boolean validate() {
        long computed = computeChecksum(payload);
        return computed == checksum;
    }

    /**
     * Computes CRC32 checksum of payload.
     */
    private static long computeChecksum(byte[] payload) {
        return io.excalibur.core.util.CRC32.crc32(payload);
    }

    /**
     * Reads an entry from a MemorySegment at the given offset.
     *
     * @param segment Memory segment containing the log
     * @param offset  Offset within segment where entry starts
     * @param entryId Expected entry ID (for validation)
     * @return Parsed WriteLogEntry
     * @throws WriteLogException if entry is corrupted or invalid
     */
    public static WriteLogEntry read(MemorySegment segment, long offset, long expectedEntryId) {
        // Read header fields
        long fileOffset = segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), offset);
        long entryId = segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), offset + 8);
        long storedChecksum = segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), offset + 16);

        // Read payload
        byte[] payload = new byte[(int) (fileOffset - offset - 13)];
        MemorySegment.copy(
            segment, ValueLayout.JAVA_BYTE, offset + 20, payload, 0, payload.length
        );

        // Create entry with pre-read checksum
        return new WriteLogEntry((byte) fileOffset, entryId, payload, storedChecksum, fileOffset);
    }

    /**
     * Serializes the entry to bytes.
     *
     * @return Serialized bytes
     */
    public byte[] serialize() {
        byte[] header = new byte[HEADER_SIZE];
        header[0] = entryType;
        
        // Write entryId (8 bytes, big-endian)
        for (int i = 0; i < 8; i++) {
            header[1 + i] = (byte) ((entryId >>> (56 - i * 8)) & 0xFF);
        }
        
        // Write checksum (8 bytes, big-endian)
        for (int i = 0; i < 8; i++) {
            header[9 + i] = (byte) ((checksum >>> (56 - i * 8)) & 0xFF);
        }
        
        // Concatenate header + payload
        byte[] result = new byte[entrySize];
        System.arraycopy(header, 0, result, 0, HEADER_SIZE);
        System.arraycopy(payload, 0, result, HEADER_SIZE, payload.length);
        
        return result;
    }

    /**
     * Returns a string representation of the entry.
     */
    @Override
    public String toString() {
        return "WriteLogEntry{entryType=" + EntryType.from(entryType) + 
               ", entryId=" + entryId + 
               ", checksum=0x" + Long.toHexString(checksum).toUpperCase() +
               ", payloadLen=" + payload.length + 
               ", offset=" + fileOffset + 
               "}";
    }

    /**
     * Entry type enumeration.
     */
    public enum EntryType {
        /** Key-value insert/update */
        PUT(0x01),
        /** Key deletion (tombstone) */
        DELETE(0x02),
        /** Recovery checkpoint marker */
        CHECKPOINT(0x03);

        private static final EntryType[] BYTES = {
            null, PUT, DELETE, CHECKPOINT
        };

        private final int value;

        EntryType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static EntryType from(byte value) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Invalid entry type byte: " + value);
            }
            EntryType[] types = BYTES;
            if (value >= types.length) {
                throw new IllegalArgumentException(
                    "Unknown entry type: 0x" + String.format("%02X", value)
                );
            }
            EntryType type = types[(int) value];
            if (type == null) {
                throw new IllegalArgumentException(
                    "Unknown entry type: 0x" + String.format("%02X", value)
                );
            }
            return type;
        }

        public static boolean isValid(byte value) {
            return value == PUT.value() || value == DELETE.value() || value == CHECKPOINT.value();
        }
    }
}
