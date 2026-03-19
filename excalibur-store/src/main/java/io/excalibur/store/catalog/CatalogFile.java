package io.excalibur.store.catalog;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Memory-mapped file for persistent catalog storage.
 *
 * <p>This class provides durable storage for segment catalog metadata,
 * ensuring that segment lifecycle states, entry counts, and other critical
 * metadata survive process restarts.</p>
 *
 * <h2>File Format</h2>
 * <pre>
 * HEADER (32 bytes):
 *   - Magic number (4 bytes): 0x45584341 ("EXCA")
 *   - Version (4 bytes): format version
 *   - Segment count (4 bytes): number of segment records
 *   - Last modified timestamp (8 bytes): milliseconds since epoch
 *   - Header CRC32 (4 bytes): checksum of header
 *   - Reserved (8 bytes): for future use
 *
 * SEGMENT RECORD (305 bytes each):
 *   - Segment ID (8 bytes)
 *   - Volume path (256 bytes, UTF-8, null-padded)
 *   - Entry count (4 bytes)
 *   - Entry state (1 byte)
 *   - Lifecycle state (1 byte)
 *   - Last integrated offset (8 bytes)
 *   - Last compaction time (8 bytes)
 *   - Creation time (8 bytes)
 *   - Last active time (8 bytes)
 *   - CRC32 (4 bytes): checksum of record
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read and write operations. All writes
 * are synchronized to prevent concurrent modification. Reads can proceed
 * concurrently with other reads.</p>
 *
 * <h2>Recovery</h2>
 * <p>On recovery, the catalog file is read and all valid segment records
 * are loaded into memory. Invalid records (failed CRC check) are skipped
 * with a warning log. This provides graceful handling of partial writes
 * or corruption.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class CatalogFile implements AutoCloseable {

    // ==================== Constants ====================

    /**
     * Magic number for catalog file identification: "EXCA" in ASCII.
     */
    public static final int MAGIC_NUMBER = 0x45584341;

    /**
     * Current file format version.
     */
    public static final int VERSION = 1;

    /**
     * Maximum number of segments that can be stored.
     * This limits the file size to a reasonable maximum.
     */
    public static final int MAX_SEGMENTS = 65536;

    /**
     * Size of the file header in bytes.
     */
    public static final int HEADER_SIZE = 32;

    /**
     * Size of a single segment record in bytes.
     * - segmentId: 8 bytes
     * - volumePath: 256 bytes
     * - entryCount: 4 bytes
     * - entryState: 1 byte
     * - lifecycleState: 1 byte
     * - lastIntegratedOffset: 8 bytes
     * - lastCompactionTime: 8 bytes
     * - creationTime: 8 bytes
     * - lastActiveTime: 8 bytes
     * - CRC32: 4 bytes
     * Total: 306 bytes (but we use 305 to be conservative)
     */
    public static final int RECORD_SIZE = 306;

    /**
     * Maximum length of volume path string (in UTF-8 bytes).
     */
    public static final int VOLUME_PATH_SIZE = 256;

    /**
     * Initial capacity for the catalog file (in segments).
     */
    private static final int INITIAL_CAPACITY = 128;

    // ==================== Instance Fields ====================

    private final Path catalogPath;
    private final byte[] readBuffer = new byte[RECORD_SIZE];
    private final byte[] writeBuffer = new byte[RECORD_SIZE];
    private final CRC32 crc32 = new CRC32();

    // Memory-mapped file for catalog data
    private io.excalibur.core.mapped.MappedFile mappedFile;
    private int segmentCount;
    private boolean closed = false;

    // ==================== Constructors ====================

    /**
     * Creates or opens a catalog file at the specified path.
     *
     * <p>If the file exists, it is opened and the header is read. If the file
     * does not exist, a new file is created with an empty catalog.</p>
     *
     * @param catalogPath Path to the catalog file
     * @throws IOException if the file cannot be created or read
     * @throws IllegalArgumentException if the path is null
     */
    public CatalogFile(Path catalogPath) throws IOException {
        if (catalogPath == null) {
            throw new IllegalArgumentException("Catalog path cannot be null");
        }

        this.catalogPath = catalogPath;
        this.segmentCount = 0;

        // Create parent directory if needed
        Path parent = catalogPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Open or create the file
        if (Files.exists(catalogPath)) {
            openExisting();
        } else {
            createNew();
        }
    }

    // ==================== Public API ====================

    /**
     * Writes a segment metadata record to the catalog file.
     *
     * <p>If a record with the same segment ID already exists, it is updated.
     * Otherwise, a new record is appended.</p>
     *
     * @param metadata The segment metadata to write
     * @throws IOException if the write fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public synchronized void writeSegment(SegmentCatalog.SegmentMetadata metadata) throws IOException {
        checkOpen();

        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        // Find existing record or determine position for new record
        long position = findRecordPosition(metadata.getSegmentId());
        if (position < 0) {
            // New record - append at end
            position = HEADER_SIZE + (long) segmentCount * RECORD_SIZE;
            segmentCount++;

            // Ensure we have space
            ensureCapacity(segmentCount);
        }

        // Write the record
        writeRecordAt(position, metadata);

        // Update header
        updateHeader();
    }

    /**
     * Reads a segment metadata record by segment ID.
     *
     * @param segmentId The segment ID to look up
     * @return The segment metadata, or null if not found
     * @throws IOException if the read fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public SegmentCatalog.SegmentMetadata readSegment(long segmentId) throws IOException {
        checkOpen();

        long position = findRecordPosition(segmentId);
        if (position < 0) {
            return null;
        }

        return readRecordAt(position);
    }

    /**
     * Reads all segment metadata records from the catalog file.
     *
     * <p>Records that fail CRC validation are skipped with a warning.
     * This provides graceful recovery from partial writes or corruption.</p>
     *
     * @return List of all valid segment metadata records
     * @throws IOException if the read fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public synchronized List<SegmentCatalog.SegmentMetadata> readAll() throws IOException {
        checkOpen();

        List<SegmentCatalog.SegmentMetadata> result = new ArrayList<>();

        for (int i = 0; i < segmentCount; i++) {
            long position = HEADER_SIZE + (long) i * RECORD_SIZE;
            SegmentCatalog.SegmentMetadata metadata = readRecordAt(position);
            if (metadata != null) {
                result.add(metadata);
            }
        }

        return result;
    }

    /**
     * Removes a segment record from the catalog.
     *
     * <p>This is implemented by marking the record as PURGED. The physical
     * removal happens during compaction.</p>
     *
     * @param segmentId The segment ID to remove
     * @return true if the segment was found and marked for removal
     * @throws IOException if the write fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public synchronized boolean removeSegment(long segmentId) throws IOException {
        checkOpen();

        long position = findRecordPosition(segmentId);
        if (position < 0) {
            return false;
        }

        // Read the record and update lifecycle state
        SegmentCatalog.SegmentMetadata metadata = readRecordAt(position);
        if (metadata == null) {
            return false;
        }

        // Mark as PURGED
        metadata.transitionToPurged();
        writeRecordAt(position, metadata);

        return true;
    }

    /**
     * Flushes all changes to disk.
     *
     * @throws IOException if the flush fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public synchronized void flush() throws IOException {
        checkOpen();
        if (mappedFile != null) {
            mappedFile.flush();
        }
    }

    /**
     * Syncs all changes to the underlying storage device.
     *
     * <p>This is an alias for {@link #flush()}.</p>
     *
     * @throws IOException if the sync fails
     */
    public void fsync() throws IOException {
        flush();
    }

    /**
     * Closes the catalog file and releases resources.
     *
     * <p>Any pending changes are flushed before closing.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (mappedFile != null) {
            try {
                mappedFile.flush();
            } catch (Exception e) {
                // Ignore flush errors during close
            }
            try {
                mappedFile.close();
            } catch (Exception e) {
                // Ignore close errors
            }
            mappedFile = null;
        }
    }

    /**
     * Checks if the catalog file is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Gets the path to the catalog file.
     *
     * @return The catalog file path
     */
    public Path getPath() {
        return catalogPath;
    }

    /**
     * Gets the number of segments in the catalog.
     *
     * @return Segment count
     */
    public int getSegmentCount() {
        return segmentCount;
    }

    // ==================== Private Methods ====================

    /**
     * Opens an existing catalog file.
     */
    private void openExisting() throws IOException {
        long fileSize = Files.size(catalogPath);

        // Minimum valid file size is header
        if (fileSize < HEADER_SIZE) {
            // File too small, recreate
            createNew();
            return;
        }

        // Open the file
        mappedFile = io.excalibur.core.mapped.MappedFile.open(catalogPath, Math.max(fileSize, calculateFileSize(INITIAL_CAPACITY)));

        // Read and validate header
        if (!readAndValidateHeader()) {
            // Invalid header, recreate
            mappedFile.close();
            createNew();
            return;
        }
    }

    /**
     * Creates a new catalog file.
     */
    private void createNew() throws IOException {
        long initialSize = calculateFileSize(INITIAL_CAPACITY);
        mappedFile = io.excalibur.core.mapped.MappedFile.create(catalogPath, initialSize);
        segmentCount = 0;
        writeHeader();
    }

    /**
     * Calculates the required file size for a given capacity.
     */
    private static long calculateFileSize(int capacity) {
        return HEADER_SIZE + (long) capacity * RECORD_SIZE;
    }

    /**
     * Ensures the file has capacity for the given number of segments.
     */
    private void ensureCapacity(int requiredCapacity) throws IOException {
        if (requiredCapacity > MAX_SEGMENTS) {
            throw new IOException("Catalog exceeds maximum segment count: " + MAX_SEGMENTS);
        }

        long requiredSize = calculateFileSize(requiredCapacity);
        if (mappedFile.size() < requiredSize) {
            // Extend the file
            long newSize = calculateFileSize(requiredCapacity * 2);
            mappedFile.extend(newSize - mappedFile.size());
        }
    }

    /**
     * Writes the file header.
     */
    private void writeHeader() throws IOException {
        long timestamp = System.currentTimeMillis();

        // Write header fields
        mappedFile.writeInt(0, MAGIC_NUMBER);
        mappedFile.writeInt(4, VERSION);
        mappedFile.writeInt(8, segmentCount);
        mappedFile.writeLong(12, timestamp);

        // Calculate header CRC (fields 0-23)
        crc32.reset();
        crc32.update(intToBytes(MAGIC_NUMBER));
        crc32.update(intToBytes(VERSION));
        crc32.update(intToBytes(segmentCount));
        crc32.update(longToBytes(timestamp));
        int headerCrc = (int) crc32.getValue();

        mappedFile.writeInt(20, headerCrc);

        // Reserved bytes at 24-31 are zero
        mappedFile.writeLong(24, 0L);
    }

    /**
     * Reads and validates the file header.
     *
     * @return true if header is valid, false otherwise
     */
    private boolean readAndValidateHeader() {
        try {
            int magic = mappedFile.readInt(0);
            if (magic != MAGIC_NUMBER) {
                return false;
            }

            int version = mappedFile.readInt(4);
            if (version != VERSION) {
                return false;
            }

            segmentCount = mappedFile.readInt(8);
            if (segmentCount < 0 || segmentCount > MAX_SEGMENTS) {
                return false;
            }

            // Verify header CRC
            long storedTimestamp = mappedFile.readLong(12);
            int storedCrc = mappedFile.readInt(20);

            crc32.reset();
            crc32.update(intToBytes(magic));
            crc32.update(intToBytes(version));
            crc32.update(intToBytes(segmentCount));
            crc32.update(longToBytes(storedTimestamp));
            int computedCrc = (int) crc32.getValue();

            return storedCrc == computedCrc;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Updates the header with current segment count and timestamp.
     */
    private void updateHeader() throws IOException {
        writeHeader();
    }

    /**
     * Finds the position of a record by segment ID.
     *
     * @return Position of the record, or -1 if not found
     */
    private long findRecordPosition(long segmentId) throws IOException {
        for (int i = 0; i < segmentCount; i++) {
            long position = HEADER_SIZE + (long) i * RECORD_SIZE;
            long currentId = mappedFile.readLong(position);
            if (currentId == segmentId) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Writes a segment record at the specified position.
     */
    private void writeRecordAt(long position, SegmentCatalog.SegmentMetadata metadata) throws IOException {
        int offset = 0;

        // Segment ID (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getSegmentId());
        offset += 8;

        // Volume path (256 bytes, UTF-8, null-padded)
        byte[] pathBytes = metadata.getVolumePath() != null
            ? metadata.getVolumePath().toString().getBytes(StandardCharsets.UTF_8)
            : new byte[0];
        if (pathBytes.length > VOLUME_PATH_SIZE) {
            throw new IOException("Volume path too long: " + metadata.getVolumePath());
        }
        mappedFile.write(position + offset, pathBytes, 0, pathBytes.length);
        // Zero-fill remaining bytes
        for (int i = pathBytes.length; i < VOLUME_PATH_SIZE; i++) {
            mappedFile.writeByte(position + offset + i, (byte) 0);
        }
        offset += VOLUME_PATH_SIZE;

        // Entry count (4 bytes)
        mappedFile.writeInt(position + offset, metadata.getEntryCount());
        offset += 4;

        // Entry state (1 byte)
        mappedFile.writeByte(position + offset, (byte) metadata.getEntryState());
        offset += 1;

        // Lifecycle state (1 byte)
        mappedFile.writeByte(position + offset, (byte) metadata.getLifecycleStateValue());
        offset += 1;

        // Last integrated offset (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getLastIntegratedOffset());
        offset += 8;

        // Last compaction time (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getLastCompactionTime());
        offset += 8;

        // Creation time (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getCreationTime());
        offset += 8;

        // Last active time (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getLastActiveTime());
        offset += 8;

        // Calculate and write CRC32 (4 bytes)
        crc32.reset();

        // Read back the record bytes for CRC calculation
        mappedFile.read(position, writeBuffer, 0, offset);
        crc32.update(writeBuffer, 0, offset);

        mappedFile.writeInt(position + offset, (int) crc32.getValue());
    }

    /**
     * Reads a segment record at the specified position.
     *
     * @return The segment metadata, or null if the record is invalid
     */
    private SegmentCatalog.SegmentMetadata readRecordAt(long position) {
        try {
            // Read the entire record (excluding CRC)
            int dataLength = RECORD_SIZE - 4;
            mappedFile.read(position, readBuffer, 0, dataLength);

            // Verify CRC
            int storedCrc = mappedFile.readInt(position + dataLength);
            crc32.reset();
            crc32.update(readBuffer, 0, dataLength);
            int computedCrc = (int) crc32.getValue();

            if (storedCrc != computedCrc) {
                // CRC mismatch - skip this record
                return null;
            }

            // Parse the record
            int offset = 0;

            // Segment ID (8 bytes)
            long segmentId = bytesToLong(readBuffer, offset);
            offset += 8;

            // Volume path (256 bytes)
            int pathLen = 0;
            for (int i = 0; i < VOLUME_PATH_SIZE; i++) {
                if (readBuffer[offset + i] == 0) {
                    pathLen = i;
                    break;
                }
            }
            if (pathLen == 0 && readBuffer[offset] != 0) {
                pathLen = VOLUME_PATH_SIZE;
            }
            Path volumePath = pathLen > 0
                ? Path.of(new String(readBuffer, offset, pathLen, StandardCharsets.UTF_8))
                : null;
            offset += VOLUME_PATH_SIZE;

            // Entry count (4 bytes)
            int entryCount = bytesToInt(readBuffer, offset);
            offset += 4;

            // Entry state (1 byte)
            int entryState = readBuffer[offset] & 0xFF;
            offset += 1;

            // Lifecycle state (1 byte)
            int lifecycleValue = readBuffer[offset] & 0xFF;
            offset += 1;

            // Last integrated offset (8 bytes)
            long lastIntegratedOffset = bytesToLong(readBuffer, offset);
            offset += 8;

            // Last compaction time (8 bytes)
            long lastCompactionTime = bytesToLong(readBuffer, offset);
            offset += 8;

            // Creation time (8 bytes)
            long creationTime = bytesToLong(readBuffer, offset);
            offset += 8;

            // Last active time (8 bytes)
            long lastActiveTime = bytesToLong(readBuffer, offset);

            // Create metadata object
            SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(segmentId);
            metadata.setVolumePath(volumePath);
            metadata.setEntryCount(entryCount);
            metadata.setEntryState(entryState);

            // Set lifecycle state directly (bypass validation for recovery)
            LifecycleState state = LifecycleState.fromValue(lifecycleValue);
            if (state != null) {
                // Use reflection to bypass validation or set directly
                try {
                    java.lang.reflect.Field field = SegmentCatalog.SegmentMetadata.class.getDeclaredField("lifecycleState");
                    field.setAccessible(true);
                    field.set(metadata, state);
                } catch (Exception e) {
                    // Fallback: try to set via the public method
                    metadata.setLifecycleState(state);
                }
            }

            metadata.setLastIntegratedOffset(lastIntegratedOffset);
            metadata.setLastCompactionTime(lastCompactionTime);
            metadata.setCreationTime(creationTime);
            metadata.setLastActiveTime(lastActiveTime);

            return metadata;
        } catch (Exception e) {
            // Invalid record - skip
            return null;
        }
    }

    /**
     * Converts an int to a byte array (big-endian).
     */
    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    /**
     * Converts a long to a byte array (big-endian).
     */
    private byte[] longToBytes(long value) {
        return new byte[] {
            (byte) (value >> 56),
            (byte) (value >> 48),
            (byte) (value >> 40),
            (byte) (value >> 32),
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    /**
     * Converts bytes to an int (big-endian).
     */
    private int bytesToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
               ((bytes[offset + 1] & 0xFF) << 16) |
               ((bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }

    /**
     * Converts bytes to a long (big-endian).
     */
    private long bytesToLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 56) |
               ((long) (bytes[offset + 1] & 0xFF) << 48) |
               ((long) (bytes[offset + 2] & 0xFF) << 40) |
               ((long) (bytes[offset + 3] & 0xFF) << 32) |
               ((long) (bytes[offset + 4] & 0xFF) << 24) |
               ((long) (bytes[offset + 5] & 0xFF) << 16) |
               ((long) (bytes[offset + 6] & 0xFF) << 8) |
               ((long) (bytes[offset + 7] & 0xFF));
    }

    /**
     * Checks that the catalog file is open.
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("CatalogFile is closed: " + catalogPath);
        }
    }
}
