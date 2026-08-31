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
 * <h2>File Format (version 2)</h2>
 * <pre>
 * HEADER REGION (64 bytes) - two alternating 32-byte slots:
 *   Each slot:
 *     - Magic number (4 bytes): 0x45584341 ("EXCA")
 *     - Version (4 bytes): format version
 *     - Segment count (4 bytes): number of segment records
 *     - Last modified timestamp (8 bytes): milliseconds since epoch
 *     - Generation (8 bytes): monotonically increasing write counter
 *     - Header CRC32 (4 bytes): checksum of the preceding 28 bytes
 *
 * SEGMENT RECORD (450 bytes each):
 *   - Segment ID (8 bytes)
 *   - Volume path (256 bytes, UTF-8, null-padded)
 *   - Entry count (4 bytes)
 *   - Entry state (1 byte)
 *   - Lifecycle state (1 byte)
 *   - Last integrated offset (8 bytes)
 *   - Last compaction time (8 bytes)
 *   - Creation time (8 bytes)
 *   - Last active time (8 bytes)
 *   - Source ID (128 bytes, UTF-8, null-padded)
 *   - Creation epoch (8 bytes)
 *   - Tombstone epoch (8 bytes)
 *   - CRC32 (4 bytes): checksum of record
 * </pre>
 *
 * <h2>Crash-Atomic Header</h2>
 * <p>The header is never rewritten in place. Each update goes to whichever of the two
 * slots is older, carrying a higher generation number, so a crash mid-write always
 * leaves the previous slot intact. On open the newest slot with a valid CRC wins. Only
 * if both slots fail is the record area scanned to rebuild the count - the catalog is
 * never reset to empty while records still checksum.</p>
 *
 * <h2>Version 1 Files</h2>
 * <p>Version 1 files (single 32-byte header, 306-byte records without source ID or
 * epochs) are read and migrated in place on open, defaulting the missing fields.</p>
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
    public static final int VERSION = 2;

    /**
     * Legacy format version: single header slot, records without source ID or epochs.
     */
    public static final int VERSION_V1 = 1;

    /**
     * Maximum number of segments that can be stored.
     * This limits the file size to a reasonable maximum.
     */
    public static final int MAX_SEGMENTS = 65536;

    /**
     * Size of one header slot in bytes.
     */
    public static final int HEADER_SLOT_SIZE = 32;

    /**
     * Size of the header region in bytes: two alternating slots.
     */
    public static final int HEADER_SIZE = HEADER_SLOT_SIZE * 2;

    /**
     * Size of the version 1 header region in bytes.
     */
    private static final int HEADER_SIZE_V1 = 32;

    /**
     * Size of a version 1 segment record in bytes.
     */
    private static final int RECORD_SIZE_V1 = 306;

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
     * - sourceId: 128 bytes
     * - creationEpoch: 8 bytes
     * - tombstoneEpoch: 8 bytes
     * - CRC32: 4 bytes
     */
    public static final int RECORD_SIZE = 450;

    /**
     * Maximum length of volume path string (in UTF-8 bytes).
     */
    public static final int VOLUME_PATH_SIZE = 256;

    /**
     * Maximum length of source ID string (in UTF-8 bytes).
     *
     * <p>The source ID is what the reindexer matches on to tombstone the segments a
     * source previously produced, so a catalog that does not persist it stops
     * superseding old segments after any restart.</p>
     */
    public static final int SOURCE_ID_SIZE = 128;

    /**
     * Initial capacity for the catalog file (in segments).
     */
    private static final int INITIAL_CAPACITY = 128;

    // ==================== Instance Fields ====================

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(CatalogFile.class.getName());

    private final Path catalogPath;
    private final byte[] readBuffer = new byte[RECORD_SIZE];
    private final byte[] writeBuffer = new byte[RECORD_SIZE];
    private final CRC32 crc32 = new CRC32();

    // Memory-mapped file for catalog data
    private io.excalibur.core.mapped.MappedFile mappedFile;
    private int segmentCount;
    private boolean closed = false;

    /** First failure encountered while flushing/closing, or null if the close was clean. */
    private volatile Throwable closeFailure;

    /** Generation of the header slot currently in effect. */
    private long headerGeneration;

    /** Index (0 or 1) of the header slot currently in effect. */
    private int headerSlot;

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
                // The catalog is the durability record for every segment's lifecycle state.
                // A failed final flush (ENOSPC, EIO) means the on-disk catalog is stale, which
                // makes HNSW integration re-process already-integrated write-log ranges on the
                // next start. Record it rather than discarding it.
                recordCloseFailure(e);
            }
            try {
                mappedFile.close();
            } catch (Exception e) {
                recordCloseFailure(e);
            }
            // MappedFile performs its own final msync in close(); surface that failure too.
            Throwable mappedFailure = mappedFile.closeFlushFailure();
            if (mappedFailure != null) {
                recordCloseFailure(mappedFailure);
            }
            mappedFile = null;
        }
    }

    /**
     * Returns the failure that prevented this catalog from being flushed durably on close,
     * or {@code null} if the close was clean.
     *
     * <p>A non-null value means the on-disk catalog may not reflect the last writes, and the
     * shutdown must not be reported as clean.</p>
     *
     * @return the first close-time failure, or null
     */
    public Throwable closeFailure() {
        return closeFailure;
    }

    private void recordCloseFailure(Throwable failure) {
        LOGGER.log(java.util.logging.Level.SEVERE,
            "Catalog " + catalogPath + " could not be flushed durably on close; "
                + "on-disk segment metadata may be stale", failure);
        if (closeFailure == null) {
            closeFailure = failure;
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

        // Minimum valid file size is a single header slot
        if (fileSize < HEADER_SLOT_SIZE) {
            // File too small, recreate
            createNew();
            return;
        }

        // Open the file
        mappedFile = io.excalibur.core.mapped.MappedFile.open(catalogPath,
            Math.max(fileSize, calculateFileSize(INITIAL_CAPACITY)));

        int version = peekVersion();
        if (version == VERSION_V1) {
            migrateFromV1();
            return;
        }

        if (readAndValidateHeader()) {
            return;
        }

        // Both header slots are unusable. The records themselves are checksummed, so
        // rebuild the count from them rather than discarding a catalog that is intact.
        int recovered = countValidRecords();
        if (recovered > 0) {
            segmentCount = recovered;
            headerGeneration = 0;
            headerSlot = 1;
            writeHeader();
            return;
        }

        mappedFile.close();
        createNew();
    }

    /**
     * Reads the format version from whichever header slot carries a valid magic number.
     *
     * @return The version, or -1 if no slot has a recognisable header
     */
    private int peekVersion() {
        for (int slot = 0; slot < 2; slot++) {
            long base = (long) slot * HEADER_SLOT_SIZE;
            try {
                if (mappedFile.readInt(base) == MAGIC_NUMBER) {
                    return mappedFile.readInt(base + 4);
                }
            } catch (Exception e) {
                // Try the other slot
            }
        }
        return -1;
    }

    /**
     * Rewrites a version 1 catalog in the current format.
     *
     * <p>Version 1 records carry no source ID and no epochs; those fields default to
     * "unset" rather than causing the file to be discarded.</p>
     *
     * @throws IOException if the migration fails
     */
    private void migrateFromV1() throws IOException {
        List<SegmentCatalog.SegmentMetadata> legacy = readAllV1();

        mappedFile.close();
        long initialSize = calculateFileSize(Math.max(INITIAL_CAPACITY, legacy.size() * 2));
        mappedFile = io.excalibur.core.mapped.MappedFile.create(catalogPath, initialSize);

        segmentCount = 0;
        headerGeneration = 0;
        headerSlot = 1;
        writeHeader();

        for (SegmentCatalog.SegmentMetadata metadata : legacy) {
            writeSegment(metadata);
        }
    }

    /**
     * Reads every valid record from a version 1 catalog.
     *
     * @return The recovered records, in file order
     */
    private List<SegmentCatalog.SegmentMetadata> readAllV1() {
        List<SegmentCatalog.SegmentMetadata> result = new ArrayList<>();
        int count;
        try {
            count = mappedFile.readInt(8);
        } catch (Exception e) {
            return result;
        }
        if (count < 0 || count > MAX_SEGMENTS) {
            return result;
        }

        byte[] buffer = new byte[RECORD_SIZE_V1];
        for (int i = 0; i < count; i++) {
            long position = HEADER_SIZE_V1 + (long) i * RECORD_SIZE_V1;
            SegmentCatalog.SegmentMetadata metadata = parseRecord(position, buffer, RECORD_SIZE_V1, false);
            if (metadata != null) {
                result.add(metadata);
            }
        }
        return result;
    }

    /**
     * Counts the records at the head of the file that still pass their CRC check.
     *
     * @return Number of consecutive valid records
     */
    private int countValidRecords() {
        int count = 0;
        long capacity = mappedFile.size();
        while (true) {
            long position = HEADER_SIZE + (long) count * RECORD_SIZE;
            if (position + RECORD_SIZE > capacity || count >= MAX_SEGMENTS) {
                break;
            }
            if (readRecordAt(position) == null) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * Creates a new catalog file.
     */
    private void createNew() throws IOException {
        long initialSize = calculateFileSize(INITIAL_CAPACITY);
        mappedFile = io.excalibur.core.mapped.MappedFile.create(catalogPath, initialSize);
        segmentCount = 0;
        headerGeneration = 0;
        headerSlot = 1;
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
        // Always write to the slot that is NOT currently in effect, so a crash part-way
        // through this write leaves the previous slot readable.
        int targetSlot = 1 - headerSlot;
        long base = (long) targetSlot * HEADER_SLOT_SIZE;
        long timestamp = System.currentTimeMillis();
        long generation = headerGeneration + 1;

        mappedFile.writeInt(base, MAGIC_NUMBER);
        mappedFile.writeInt(base + 4, VERSION);
        mappedFile.writeInt(base + 8, segmentCount);
        mappedFile.writeLong(base + 12, timestamp);
        mappedFile.writeLong(base + 20, generation);

        crc32.reset();
        crc32.update(intToBytes(MAGIC_NUMBER));
        crc32.update(intToBytes(VERSION));
        crc32.update(intToBytes(segmentCount));
        crc32.update(longToBytes(timestamp));
        crc32.update(longToBytes(generation));

        // The CRC is written last: until it lands, this slot reads as invalid.
        mappedFile.writeInt(base + 28, (int) crc32.getValue());

        headerSlot = targetSlot;
        headerGeneration = generation;
    }

    /**
     * Reads the header, taking the newest slot that passes its CRC check.
     *
     * @return true if a usable header slot was found
     */
    private boolean readAndValidateHeader() {
        int bestSlot = -1;
        long bestGeneration = -1;
        int bestCount = 0;

        for (int slot = 0; slot < 2; slot++) {
            long base = (long) slot * HEADER_SLOT_SIZE;
            try {
                if (mappedFile.readInt(base) != MAGIC_NUMBER) {
                    continue;
                }
                int version = mappedFile.readInt(base + 4);
                if (version != VERSION) {
                    continue;
                }
                int count = mappedFile.readInt(base + 8);
                if (count < 0 || count > MAX_SEGMENTS) {
                    continue;
                }

                long timestamp = mappedFile.readLong(base + 12);
                long generation = mappedFile.readLong(base + 20);
                int storedCrc = mappedFile.readInt(base + 28);

                crc32.reset();
                crc32.update(intToBytes(MAGIC_NUMBER));
                crc32.update(intToBytes(version));
                crc32.update(intToBytes(count));
                crc32.update(longToBytes(timestamp));
                crc32.update(longToBytes(generation));

                if (storedCrc != (int) crc32.getValue()) {
                    continue;
                }

                if (generation > bestGeneration) {
                    bestGeneration = generation;
                    bestSlot = slot;
                    bestCount = count;
                }
            } catch (Exception e) {
                // Slot unreadable - try the other one
            }
        }

        if (bestSlot < 0) {
            return false;
        }

        segmentCount = bestCount;
        headerSlot = bestSlot;
        headerGeneration = bestGeneration;
        return true;
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
        String volumePath = metadata.getVolumePath() != null
            ? metadata.getVolumePath().toString() : null;
        offset += writeFixedString(position + offset, volumePath, VOLUME_PATH_SIZE);

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

        // Source ID (128 bytes, UTF-8, null-padded)
        offset += writeFixedString(position + offset, metadata.getSourceId(), SOURCE_ID_SIZE);

        // Creation epoch (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getCreationEpoch());
        offset += 8;

        // Tombstone epoch (8 bytes)
        mappedFile.writeLong(position + offset, metadata.getTombstoneEpoch());
        offset += 8;

        // Calculate and write CRC32 (4 bytes)
        crc32.reset();

        // Read back the record bytes for CRC calculation
        mappedFile.read(position, writeBuffer, 0, offset);
        crc32.update(writeBuffer, 0, offset);

        mappedFile.writeInt(position + offset, (int) crc32.getValue());
    }

    /**
     * Writes a UTF-8 string into a fixed-width, null-padded field.
     *
     * @param position  Absolute position of the field
     * @param value     The string, or null for an empty field
     * @param fieldSize Width of the field in bytes
     * @return The field width, for offset arithmetic
     * @throws IOException if the encoded string does not fit
     */
    private int writeFixedString(long position, String value, int fieldSize) throws IOException {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (bytes.length > fieldSize) {
            throw new IOException("Value too long for " + fieldSize + " byte field: " + value);
        }
        mappedFile.write(position, bytes, 0, bytes.length);
        for (int i = bytes.length; i < fieldSize; i++) {
            mappedFile.writeByte(position + i, (byte) 0);
        }
        return fieldSize;
    }

    /**
     * Reads a UTF-8 string from a fixed-width, null-padded field in a buffer.
     *
     * @param buffer    The record buffer
     * @param offset    Offset of the field within the buffer
     * @param fieldSize Width of the field in bytes
     * @return The decoded string, or null if the field is empty
     */
    private static String readFixedString(byte[] buffer, int offset, int fieldSize) {
        int length = fieldSize;
        for (int i = 0; i < fieldSize; i++) {
            if (buffer[offset + i] == 0) {
                length = i;
                break;
            }
        }
        return length > 0 ? new String(buffer, offset, length, StandardCharsets.UTF_8) : null;
    }

    /**
     * Reads a segment record at the specified position.
     *
     * @param position Absolute position of the record
     * @return The segment metadata, or null if the record is invalid
     */
    private SegmentCatalog.SegmentMetadata readRecordAt(long position) {
        return parseRecord(position, readBuffer, RECORD_SIZE, true);
    }

    /**
     * Parses a record of either format version.
     *
     * @param position     Absolute position of the record
     * @param buffer       Scratch buffer at least {@code recordSize} bytes long
     * @param recordSize   Total record size including the trailing CRC
     * @param hasExtras    Whether the record carries source ID and epoch fields
     * @return The segment metadata, or null if the record is invalid
     */
    private SegmentCatalog.SegmentMetadata parseRecord(long position, byte[] buffer,
                                                       int recordSize, boolean hasExtras) {
        try {
            // Read the entire record (excluding CRC)
            int dataLength = recordSize - 4;
            mappedFile.read(position, buffer, 0, dataLength);

            // Verify CRC
            int storedCrc = mappedFile.readInt(position + dataLength);
            crc32.reset();
            crc32.update(buffer, 0, dataLength);
            if (storedCrc != (int) crc32.getValue()) {
                // CRC mismatch - skip this record
                return null;
            }

            int offset = 0;

            long segmentId = bytesToLong(buffer, offset);
            offset += 8;

            String pathText = readFixedString(buffer, offset, VOLUME_PATH_SIZE);
            Path volumePath = pathText != null ? Path.of(pathText) : null;
            offset += VOLUME_PATH_SIZE;

            int entryCount = bytesToInt(buffer, offset);
            offset += 4;

            int entryState = buffer[offset] & 0xFF;
            offset += 1;

            int lifecycleValue = buffer[offset] & 0xFF;
            offset += 1;

            long lastIntegratedOffset = bytesToLong(buffer, offset);
            offset += 8;

            long lastCompactionTime = bytesToLong(buffer, offset);
            offset += 8;

            long creationTime = bytesToLong(buffer, offset);
            offset += 8;

            long lastActiveTime = bytesToLong(buffer, offset);
            offset += 8;

            String sourceId = null;
            long creationEpoch = -1;
            long tombstoneEpoch = -1;
            if (hasExtras) {
                sourceId = readFixedString(buffer, offset, SOURCE_ID_SIZE);
                offset += SOURCE_ID_SIZE;
                creationEpoch = bytesToLong(buffer, offset);
                offset += 8;
                tombstoneEpoch = bytesToLong(buffer, offset);
            }

            SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(segmentId);
            metadata.setVolumePath(volumePath);
            metadata.setEntryCount(entryCount);
            metadata.setEntryState(entryState);

            // The persisted state is wherever the segment actually was; replaying it
            // through the transition rules would reject valid states such as ACTIVE.
            metadata.restoreLifecycleState(LifecycleState.fromValue(lifecycleValue));

            metadata.setLastIntegratedOffset(lastIntegratedOffset);
            metadata.setLastCompactionTime(lastCompactionTime);
            metadata.setCreationTime(creationTime);
            metadata.setLastActiveTime(lastActiveTime);
            if (sourceId != null) {
                metadata.setSourceId(sourceId);
            }
            if (creationEpoch >= 0) {
                metadata.setCreationEpoch(creationEpoch);
            }
            if (tombstoneEpoch >= 0) {
                metadata.setTombstoneEpoch(tombstoneEpoch);
            }

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
