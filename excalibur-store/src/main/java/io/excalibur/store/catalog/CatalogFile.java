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
 * if both slots fail is the record area scanned to rebuild the count, and that scan runs
 * to the end of the file rather than stopping at the first damaged row: each record carries
 * its own CRC, so one bad row costs itself and nothing behind it.</p>
 *
 * <p>The catalog is never reset to empty. A file with no usable header and no record that
 * checksums is refused, because starting fresh over it would keep the segment files and
 * silently discard every segment's lifecycle state, integrated offset, source and epochs -
 * arriving as a node that looks like a first start. The one exception is a file of zeroes,
 * which is a create that never reached its first header and has lost nothing.</p>
 *
 * <h2>Version 1 Files</h2>
 * <p>Version 1 files (single 32-byte header, 306-byte records without source ID or
 * epochs) are read and migrated in place on open, defaulting the missing fields.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Every entry point that touches the mapping, the record count or the closed flag holds
 * this object's monitor, readers included. The count and the mapping handle are plain fields
 * that {@code writeSegment} and {@code close} rewrite, so a read taken outside the monitor can
 * see a count already raised for a record not yet written, or a mapping {@code close} has
 * released. Reads therefore serialise with each other as well as with writes; this file is
 * written once per catalog change and read on recovery, so the contention that buys is not
 * worth a torn view of the state a node is rebuilt from.</p>
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
     * Maximum number of rows the catalog file may hold, and so of segments a store may own.
     *
     * <p>A bound on live segments, not on the segments a store has had over its lifetime: a
     * purged row is reused by the next registration rather than left occupying a slot. It
     * limits the file to a reasonable size.</p>
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

    /** How much of the file {@link #isEntirelyBlank()} reads at a time. */
    private static final int BLANK_SCAN_CHUNK = 64 * 1024;

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
     * <p>If a record with the same segment ID already exists, it is updated in place.
     * Otherwise, a new record is appended.</p>
     *
     * <p>The two cases differ in durability, and deliberately so. See
     * {@link #appendSegment(SegmentCatalog.SegmentMetadata)}.</p>
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

        long position = findRecordPosition(metadata.getSegmentId());
        if (position < 0) {
            appendSegment(metadata);
            return;
        }

        // An update to a row that already exists. Entry-count deltas come through here on
        // every put, so this path stays asynchronous: the mapping is msynced by an explicit
        // flush(), by close(), or by the next append.
        writeRecordAt(position, metadata);
        updateHeader();
    }

    /**
     * Appends a record for a segment the catalog has never seen, and makes it durable.
     *
     * <p>An append is the moment a segment file becomes something this store knows it owns.
     * The file itself is already on the device by the time it is registered; the row naming it
     * is not, because the row lives in a memory mapping that the kernel writes back whenever it
     * pleases. A host that loses power in that window comes back with a segment file and no row
     * for it, which is the one inconsistency startup cannot repair - it either adopts the file
     * as a brand new empty segment, losing everything in it, or refuses to start. Appends are
     * rare, so paying an msync for each one is affordable in a way that paying one per put
     * would not be.</p>
     *
     * <p>The record is forced before the count that claims it, so a crash between the two
     * leaves a record no header counts rather than a header counting a record the device never
     * received. And the capacity is grown before the count moves at all: a growth that fails
     * on a full disk must leave the catalog exactly as it was, not counting an entry no write
     * ever made room for.</p>
     *
     * <p>A row the file already holds for a purged segment is taken over instead of a new one
     * being added, so the file's ceiling bounds live segments rather than every segment the
     * store has ever had. See {@link #findPurgedRecordPosition()}.</p>
     *
     * @param metadata The segment metadata to append
     * @throws IOException if the file cannot be grown or written
     */
    private void appendSegment(SegmentCatalog.SegmentMetadata metadata) throws IOException {
        long reusable = findPurgedRecordPosition();
        if (reusable >= 0) {
            reuseRecordAt(reusable, metadata);
            return;
        }

        int appended = segmentCount + 1;
        ensureCapacity(appended);

        long position = HEADER_SIZE + (long) segmentCount * RECORD_SIZE;
        writeRecordAt(position, metadata);
        mappedFile.flush();

        segmentCount = appended;
        updateHeader();
        mappedFile.flush();
    }

    /**
     * Finds a row whose segment no longer exists, so a new segment can take its place.
     *
     * <p>A purged row is the one row in the file that names nothing: the segment's disk has
     * been freed and recovery skips it. Without reuse the file only ever grows, and
     * {@link #MAX_SEGMENTS} stops counting how many segments a store has and starts counting
     * how many it has ever had - so a store that splits and purges, which is what a long-lived
     * store does, eventually cannot register another segment and the wall arrives as a split
     * that fails for no visible reason.</p>
     *
     * <p>Reuse is safe because nothing addresses a row by its position. Lookups scan for a
     * segment id ({@link #findRecordPosition(long)}), recovery reads the rows as a set, and
     * {@link #countValidRecords()} only counts. The scan is linear, which is the same cost
     * {@link #findRecordPosition(long)} already pays on every write, and it runs only on the
     * rare write that brings a new segment into existence.</p>
     *
     * <p>Only rows that pass their own checksum are considered. A corrupt row might have a
     * lifecycle byte that happens to read as purged while actually describing a live segment,
     * and overwriting it on that basis would turn a row recovery might still report into one
     * it certainly cannot.</p>
     *
     * @return Position of a reusable row, or -1 if every row is still in use
     */
    private long findPurgedRecordPosition() {
        for (int i = 0; i < segmentCount; i++) {
            long position = HEADER_SIZE + (long) i * RECORD_SIZE;
            SegmentCatalog.SegmentMetadata existing = readRecordAt(position);
            if (existing != null && existing.getLifecycleState() == LifecycleState.PURGED) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Writes a new segment's record over a purged one, and makes it durable.
     *
     * <p>Forced for the same reason an append is: the segment file is already on the device,
     * and a host that loses power before the row reaches it comes back holding a segment
     * nothing in the catalog claims. The count does not move, so there is no header write to
     * order this against - the row either lands whole or fails its checksum and is skipped,
     * which is the same exposure an append has in the window between its two forces.</p>
     *
     * @param position Position of the purged row being taken over
     * @param metadata The segment metadata to write
     * @throws IOException if the write fails
     */
    private void reuseRecordAt(long position, SegmentCatalog.SegmentMetadata metadata)
            throws IOException {
        writeRecordAt(position, metadata);
        mappedFile.flush();
    }

    /**
     * Reads a segment metadata record by segment ID.
     *
     * @param segmentId The segment ID to look up
     * @return The segment metadata, or null if not found
     * @throws IOException if the read fails
     * @throws IllegalStateException if the catalog file is closed
     */
    public synchronized SegmentCatalog.SegmentMetadata readSegment(long segmentId) throws IOException {
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
     * <p>The row is marked PURGED rather than cut out of the file, because cutting it out
     * would mean moving every row behind it while readers are scanning them. A purged row
     * names nothing - recovery skips it - and the next segment to be registered writes itself
     * over it, so the row is reclaimed by the next append rather than by a sweep that has to
     * be scheduled. See {@link #findPurgedRecordPosition()}.</p>
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
    public synchronized boolean isClosed() {
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
    public synchronized int getSegmentCount() {
        return segmentCount;
    }

    // ==================== Private Methods ====================

    /**
     * Opens an existing catalog file.
     */
    private void openExisting() throws IOException {
        long fileSize = Files.size(catalogPath);

        // Too small to hold even one header slot. A file of zeroes at this size is a create
        // that never got as far as its first header and has lost nothing; anything else is a
        // catalog that was truncated, and starting fresh over it would discard the segment
        // state it used to hold without saying so.
        if (fileSize < HEADER_SLOT_SIZE) {
            if (!isBlankOnDisk(fileSize)) {
                throw new IOException("Catalog " + catalogPath + " is " + fileSize + " bytes, "
                    + "too short to hold a header, and is not blank: it has been truncated. "
                    + "Refusing to start on an empty catalog, which would silently discard "
                    + "every segment's lifecycle state. Restore it from a backup, or move it "
                    + "aside deliberately.");
            }
            createNew();
            return;
        }

        // Open the file
        mappedFile = io.excalibur.core.mapped.MappedFile.open(catalogPath,
            Math.max(fileSize, calculateFileSize(INITIAL_CAPACITY)));

        int version = peekVersion();
        if (version == VERSION_V1 && !hasChecksummedCurrentHeader()) {
            migrateFromV1();
            return;
        }

        refuseUnreadableVersion();

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

        refuseUnreadableCatalog();
        mappedFile.close();
        createNew();
    }

    /**
     * Refuses a catalog whose headers and records are all unreadable, unless it is blank.
     *
     * <p>What this replaces is the worst outcome the recovery path had: writing a fresh empty
     * header over the file and carrying on. The corpus itself survives that - startup
     * rediscovers segment files from the filesystem - but every segment's lifecycle state,
     * integrated offset, source and epochs are gone, and the node comes up looking exactly like
     * a first start. Nothing in its logs or its health says otherwise, so the loss is found
     * later, by something behaving oddly, with no way back to the cause. The same argument is
     * already made for an unreadable format version a few lines below; this is the other way
     * into the same erasure.</p>
     *
     * <p>A blank file is exempt, and has to be: a crash between creating the catalog and
     * writing its first header leaves zeroes, and nothing has been lost there. Zero bytes are
     * the evidence - a file that was ever written holds a magic number, and any non-zero byte
     * in a file whose records will not checksum is damage rather than absence.</p>
     *
     * @throws IOException if the file holds something that cannot be read back
     */
    private void refuseUnreadableCatalog() throws IOException {
        if (isEntirelyBlank()) {
            return;
        }
        releaseMappingBeforeRefusing();
        throw new IOException("Catalog " + catalogPath + " has no usable header and no record "
            + "that passes its checksum, and it is not blank, so it was written and then "
            + "damaged. Refusing to start on an empty catalog: that would keep the segment "
            + "files and silently discard every segment's lifecycle state, integrated offset, "
            + "source and epochs, and would come up looking like a first start. Restore the "
            + "catalog from a backup, or move it aside deliberately if losing that state is "
            + "acceptable - the segment files themselves are rediscovered either way.");
    }

    /**
     * Reports whether a file too small to map is all zeroes.
     *
     * @param fileSize Its size in bytes
     * @return true if nothing has ever been written to it
     * @throws IOException if it cannot be read
     */
    private boolean isBlankOnDisk(long fileSize) throws IOException {
        if (fileSize == 0) {
            return true;
        }
        for (byte b : Files.readAllBytes(catalogPath)) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every byte of the file is zero.
     *
     * <p>Read through the mapping rather than from the path, so it describes the same bytes the
     * recovery just failed on.</p>
     *
     * @return true if nothing has ever been written
     */
    private boolean isEntirelyBlank() {
        byte[] buffer = new byte[BLANK_SCAN_CHUNK];
        long size = mappedFile.size();
        for (long position = 0; position < size; position += BLANK_SCAN_CHUNK) {
            int length = (int) Math.min(BLANK_SCAN_CHUNK, size - position);
            mappedFile.read(position, buffer, 0, length);
            for (int i = 0; i < length; i++) {
                if (buffer[i] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Refuses a catalog written in a format version this build cannot read.
     *
     * <p>Without this the file is not rejected, it is erased. An unknown version reaches
     * {@link #readAndValidateHeader()}, which skips every slot whose version is not the
     * current one and reports no usable header; the record recovery behind it then parses
     * records of an unknown width at this build's record size, fails their checksums and
     * finds none; and what is left is {@link #createNew()}, writing an empty header over the
     * only record of which segment files this store owns. A downgrade or a rollback therefore
     * discards every segment's lifecycle state, integrated offset, source and epochs, and
     * comes up looking exactly like a first start.</p>
     *
     * <p>Keyed off a header slot that passes its own checksum, not off the version field
     * alone. A version garbled by a torn write is a corruption the record-level recovery can
     * still get past, and turning it into a refusal would stop a node that had nothing wrong
     * with its catalog. A slot whose checksum covers the version is the only evidence that the
     * version is what the writer meant to write.</p>
     *
     * @throws IOException if a checksummed header slot names a version this build cannot read
     */
    private void refuseUnreadableVersion() throws IOException {
        for (int slot = 0; slot < 2; slot++) {
            int version = checksummedVersionAt(slot);
            if (version < 0 || version == VERSION || version == VERSION_V1) {
                continue;
            }
            releaseMappingBeforeRefusing();
            throw new IOException("Catalog " + catalogPath + " is format version " + version
                + ", which this build cannot read; it supports versions " + VERSION_V1
                + " and " + VERSION + ". The file was written by a newer build and has been "
                + "left untouched: run that build, or move the catalog aside only if losing "
                + "every segment's lifecycle state is acceptable.");
        }
    }

    /**
     * Reports whether some header slot is a checksummed header in the current format.
     *
     * <p>Guards the version 1 migration, which has nothing else to go on. A version 1 header
     * carries no checksum, so the only evidence that a file is version 1 is the version field
     * itself - and a torn write that leaves a current-format file reading as version 1 sends
     * it through a migration that parses 450-byte records at 306 bytes, recovers none of them
     * and rewrites the catalog empty. A slot that passes the current format's checksum is
     * proof the file is not version 1, whatever the other slot's version field says; a genuine
     * version 1 file cannot produce one.</p>
     *
     * @return true if either slot is a valid header in the current format
     */
    private boolean hasChecksummedCurrentHeader() {
        return checksummedVersionAt(0) == VERSION || checksummedVersionAt(1) == VERSION;
    }

    /**
     * Reads the version out of a header slot, but only if the slot's own checksum agrees.
     *
     * <p>The checksum covers the version field, so a slot that passes it is a slot whose
     * version was written deliberately. The header slot layout - magic, version, count,
     * timestamp, generation, CRC - is what the version field exists to let a reader dispatch
     * on, so it is read the same way whatever version it turns out to name.</p>
     *
     * @param slot Index of the header slot, 0 or 1
     * @return The version, or -1 if the slot has no magic number or fails its checksum
     */
    private int checksummedVersionAt(int slot) {
        long base = (long) slot * HEADER_SLOT_SIZE;
        try {
            if (mappedFile.readInt(base) != MAGIC_NUMBER) {
                return -1;
            }
            int version = mappedFile.readInt(base + 4);
            int count = mappedFile.readInt(base + 8);
            long timestamp = mappedFile.readLong(base + 12);
            long generation = mappedFile.readLong(base + 20);
            int storedCrc = mappedFile.readInt(base + 28);

            crc32.reset();
            crc32.update(intToBytes(MAGIC_NUMBER));
            crc32.update(intToBytes(version));
            crc32.update(intToBytes(count));
            crc32.update(longToBytes(timestamp));
            crc32.update(longToBytes(generation));

            return storedCrc == (int) crc32.getValue() ? version : -1;
        } catch (RuntimeException e) {
            // Distinct from the -1 above: that is a slot that says it holds no valid header,
            // this is a slot nothing could read. The caller cannot tell them apart, so the
            // difference is only recoverable from the log.
            LOGGER.log(java.util.logging.Level.FINE, e,
                () -> "Header slot " + slot + " of the catalog at " + catalogPath
                    + " could not be read");
            return -1;
        }
    }

    /**
     * Releases the mapping when the constructor is about to fail.
     *
     * <p>A constructor that throws never returns an object for anyone to close, so the mapping
     * and the descriptor behind it are released here or not at all.</p>
     */
    private void releaseMappingBeforeRefusing() {
        try {
            mappedFile.close();
        } catch (Exception e) {
            LOGGER.warning("Could not release the mapping for the catalog this build refused "
                + "to open at " + catalogPath + ": " + e);
        } finally {
            mappedFile = null;
        }
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
            } catch (RuntimeException e) {
                int unreadableSlot = slot;
                LOGGER.log(java.util.logging.Level.FINE, e,
                    () -> "Header slot " + unreadableSlot + " of the catalog at " + catalogPath
                        + " could not be read while looking for the format version");
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
        } catch (RuntimeException e) {
            // Every record is about to be dropped and the catalog rewritten empty. That is
            // the loudest thing this class can do to a catalog, so it does not do it quietly.
            LOGGER.log(java.util.logging.Level.SEVERE, e,
                () -> "Could not read the record count of the version 1 catalog at "
                    + catalogPath + "; it will be migrated with no segments");
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
     * Finds how many record slots the file must be read back over to recover everything intact.
     *
     * <p>Every record carries its own CRC, so damage to one says nothing about the others, and
     * {@link #readAll()} already skips the rows that fail. Stopping at the first failure - which
     * this did - therefore discarded every intact row behind a single bad one: one torn record
     * at index 0 was enough to lose the whole catalog. Scanning to the end and returning the
     * position just past the last row that still checksums keeps each row's fate its own.</p>
     *
     * <p>Rows past the count are zeroes or a previous generation's bytes, and neither passes a
     * CRC, so the scan cannot resurrect a row the catalog had moved beyond.</p>
     *
     * @return One past the index of the last record that passes its CRC, or 0 if none does
     */
    private int countValidRecords() {
        int end = 0;
        long capacity = mappedFile.size();
        for (int index = 0; index < MAX_SEGMENTS; index++) {
            long position = HEADER_SIZE + (long) index * RECORD_SIZE;
            if (position + RECORD_SIZE > capacity) {
                break;
            }
            if (readRecordAt(position) != null) {
                end = index + 1;
            }
        }
        return end;
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
            throw new IOException("Catalog " + catalogPath + " already holds " + segmentCount
                + " segments and cannot hold more than " + MAX_SEGMENTS
                + ". Every row belongs to a segment that has not been purged, so this is a "
                + "live-segment count: the store needs fewer, larger segments.");
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
            } catch (RuntimeException e) {
                int unreadableSlot = slot;
                LOGGER.log(java.util.logging.Level.FINE, e,
                    () -> "Header slot " + unreadableSlot + " of the catalog at " + catalogPath
                        + " could not be read");
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
        } catch (RuntimeException e) {
            // Dropping a segment record during recovery loses whatever that segment held, so
            // the position it was dropped from is recorded even though recovery continues.
            LOGGER.log(java.util.logging.Level.WARNING, e,
                () -> "Skipping the unreadable catalog record at offset " + position + " of "
                    + catalogPath);
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
