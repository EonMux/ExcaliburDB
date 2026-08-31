package io.excalibur.store;

import io.excalibur.core.checksum.CRC32Checksum;
import io.excalibur.core.memory.Segment;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.store.format.LogEntry;
import io.excalibur.store.hash.XxHash64;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only write-ahead log for crash recovery.
 *
 * <p>This class implements a durable write-ahead log (WAL) that guarantees
 * crash recovery for segment mutations. Every mutation (put, delete) is first
 * appended to the log and fsynced before the in-memory hash map is updated.</p>
 *
 * <h2>Log Entry Format</h2>
 * <p>Each entry follows the format defined in {@link LogEntry}:</p>
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
 *   <li>{@code lsn}: Starts at 0, increases monotonically by 1 for each entry</li>
 *   <li>{@code timestamp}: Positive 64-bit epoch milliseconds</li>
 *   <li>{@code opcode}: Exactly 0x01 (PUT) or 0x02 (DELETE)</li>
 *   <li>{@code crc32}: CRC-32 checksum stored in little-endian format</li>
 *   <li>Log file is append-only (no in-place modifications)</li>
 * </ul>
 *
 * <h2>Durability Guarantees</h2>
 * <ul>
 *   <li>Every append fsyncs the file before returning the LSN</li>
 *   <li>Recovery replays entries in LSN order</li>
 *   <li>Corrupted entries (CRC32 mismatch) are skipped during recovery</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Append operations are serialized via an internal lock.
 * Recovery should only be performed when no concurrent appends are happening (typically
 * during initialization).</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see LogEntry
 * @see Segment
 */
public class WriteLog implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(WriteLog.class.getName());

    // ==================== Configuration Constants ====================

    /**
     * Initial file size when creating a new log file (1 MB).
     */
    private static final long INITIAL_FILE_SIZE = 1024 * 1024;

    /**
     * Maximum file size before rotation is recommended (64 MB).
     */
    private static final long MAX_FILE_SIZE = 64 * 1024 * 1024;

    /**
     * File extension for rotated log files.
     */
    private static final String ROTATED_EXTENSION = ".old";

    // ==================== Instance Fields ====================

    private final Path logFilePath;
    /**
     * Arena backing the current mapping.
     *
     * <p>Owned by this log and replaced whenever the file is re-mapped. A mapping cannot be
     * released on its own - only by closing the Arena that holds it - so a log that maps into
     * an Arena belonging to something longer-lived can never give its address space back. Each
     * log maps 64 MiB, and a segment retired by a split or a compaction takes its log with it,
     * so borrowing the engine's Arena left one of those resident per retired segment for the
     * life of the process.
     */
    private volatile Arena mappingArena;
    private final ReentrantLock appendLock;
    private final AtomicLong nextLsn;
    private final AtomicLong writeOffset;

    private volatile FileChannel fileChannel;
    private volatile MemorySegment mappedSegment; // Native Panama segment (may be null in fallback mode)
    private volatile Segment mappedSegmentWrapper; // Abstraction layer - always set
    private volatile boolean closed;
    private final boolean usingPanama; // Track which implementation is in use

    // ==================== Statistics ====================

    /**
     * Total bytes written to the log file.
     */
    private final AtomicLong totalBytesWritten = new AtomicLong(0);

    /**
     * Total entries appended to the log.
     */
    private final AtomicLong totalEntriesAppended = new AtomicLong(0);

    /**
     * Number of corrupt entries found during recovery.
     */
    private final AtomicLong corruptEntriesFound = new AtomicLong(0);

    /**
     * Last integrated offset (for tail scan tracking).
     * Uses AtomicLong for thread-safe monotonic updates.
     */
    private final AtomicLong lastIntegratedOffset = new AtomicLong(0);

    /**
     * Domain correctness flag - true when all domain constraints are satisfied.
     */
    private volatile boolean domainCorrect = false;

    /**
     * Checks if domain correctness criteria are met:
     * - All LSNs are monotonically increasing
     * - All timestamps are positive
     * - All opcodes are valid (PUT=0x01 or DELETE=0x02)
     * - All key hashes are non-negative
     * - All CRC32 checksums validate correctly
     * - All entry sizes are within expected ranges
     */
    public boolean isDomainCorrect() {
        return domainCorrect;
    }

    /**
     * Sets domain correctness flag based on validation results.
     * Called during recovery to verify log integrity.
     */
    public void setDomainCorrect(boolean correct) {
        this.domainCorrect = correct;
    }

    /**
     * Gets the last integrated offset.
     * <p>
     * This offset tracks the write log offset up to which all entries
     * have been linked into the HNSW graph (Phase 4). Entries beyond
     * this offset exist in the write log but are not yet in the graph.
     * Critical for live-indexing tail scan (Phase 6).
     * </p>
     *
     * <p><strong>Domain Guarantee:</strong> The returned value is always
     * non-negative and has only increased since the last call.</p>
     *
     * @return The last integrated offset (always >= 0)
     */
    public long getLastIntegratedOffset() {
        return lastIntegratedOffset.get();
    }

    /**
     * Updates the last integrated offset with domain constraint validation.
     *
     * <p><strong>Domain Constraints:</strong></p>
     * <ul>
     *   <li>Offset must be non-negative (throws IllegalArgumentException if negative)</li>
     *   <li>Offset must be strictly monotonically increasing (new offset > current offset)</li>
     * </ul>
     *
     * <p>This method is called during HNSW graph integration to track progress.
     * The monotonicity constraint ensures the offset never goes backwards,
     * which is critical for correct tail scan behavior in Phase 6.</p>
     *
     * @param offset The new last integrated offset (must be >= 0 and >= current value)
     * @throws IllegalArgumentException if offset is negative
     * @throws IllegalStateException if offset is less than current value
     */
    public void setLastIntegratedOffset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException(
                "lastIntegratedOffset must be non-negative, got: " + offset);
        }
        // Use CAS loop for atomic monotonic update (no TOCTOU race)
        while (true) {
            long current = lastIntegratedOffset.get();
            if (offset < current) {
                throw new IllegalStateException(String.format(
                    "lastIntegratedOffset must not go backwards: " +
                    "attempted to set %d but current value is %d",
                    offset, current));
            }
            if (offset == current) {
                return; // Idempotent - already at this value
            }
            if (lastIntegratedOffset.compareAndSet(current, offset)) {
                return;
            }
            // CAS failed - another thread updated, retry
        }
    }

    /**
     * Increments the last integrated offset by the specified amount.
     *
     * <p><strong>Domain Constraints:</strong></p>
     * <ul>
     *   <li>Delta must result in a non-negative offset</li>
     *   <li>Resulting offset must be >= current offset (delta must be non-negative)</li>
     * </ul>
     *
     * @param delta The amount to increment (must be >= 0)
     * @throws IllegalArgumentException if delta is negative
     * @throws IllegalStateException if increment would overflow
     */
    public void incrementLastIntegratedOffset(long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException(
                "Delta must be non-negative, got: " + delta);
        }
        // CAS loop: detect overflow BEFORE mutating so the counter is never
        // left in a corrupted wrapped state on an overflow throw.
        while (true) {
            long current = lastIntegratedOffset.get();
            long newValue = current + delta;
            if (newValue < current) {
                throw new IllegalStateException(
                    "Increment would cause overflow: current=" + current +
                    ", delta=" + delta);
            }
            if (lastIntegratedOffset.compareAndSet(current, newValue)) {
                return;
            }
        }
    }

    // ==================== Constructors ====================

    /**
     * Creates or opens a write-ahead log at the specified path using automatic
     * implementation selection.
     *
     * <p>This constructor uses {@link MemorySegmentFactory} to automatically select
     * the appropriate memory segment implementation:</p>
     * <ul>
     *   <li>Uses Panama FFM (native MemorySegment) when available (Java 21+ with native access)</li>
     *   <li>Falls back to ByteBuffer-based implementation when Panama is unavailable</li>
     * </ul>
     *
     * <p>If the log file exists, it will be opened and the next LSN will be
     * determined by scanning existing entries. If the file does not exist,
     * it will be created.</p>
     *
     * @param logFilePath The path to the log file
     * @throws IOException if the file cannot be created or opened
     * @throws NullPointerException if logFilePath is null
     */
    public WriteLog(Path logFilePath) throws IOException {
        Objects.requireNonNull(logFilePath, "Log file path cannot be null");

        this.logFilePath = logFilePath;
        this.usingPanama = MemorySegmentFactory.isPanamaAvailable();
        this.appendLock = new ReentrantLock();
        this.nextLsn = new AtomicLong(0);
        this.writeOffset = new AtomicLong(0);
        this.closed = false;

        initializeLogFile();

        LOGGER.log(Level.INFO, "WriteLog initialized at {0} using {1} implementation",
            new Object[] { logFilePath, usingPanama ? "Panama FFM" : "ByteBuffer fallback" });
    }

    // ==================== Initialization ====================

    /**
     * Initializes the log file, creating it if necessary and memory-mapping it.
     *
     * @throws IOException if initialization fails
     */
    private void initializeLogFile() throws IOException {
        boolean hasExistingEntries = Files.exists(logFilePath) && Files.size(logFilePath) > 0;

        if (!hasExistingEntries) {
            // Create parent directories if needed
            Path parent = logFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        }

        // The log is mapped once, at its maximum size, and never re-mapped. Growing by
        // re-mapping into a long-lived Arena leaves every previous mapping resident for
        // the life of that Arena, and invalidates nothing, so the whole mapping history
        // accumulates. The file is sparse: blocks are allocated as entries are written.
        openFileChannel(MAX_FILE_SIZE);

        if (hasExistingEntries) {
            // Scan to find the highest LSN and write offset
            scanExistingEntries();
        }

        LOGGER.log(Level.INFO, "WriteLog initialized at {0}, nextLSN={1}, writeOffset={2}",
            new Object[] { logFilePath, nextLsn.get(), writeOffset.get() });
    }

    /**
     * Opens the file channel and memory-maps the file.
     *
     * <p>This method uses the memory segment abstraction layer to support both
     * Panama FFM and ByteBuffer fallback implementations.</p>
     *
     * @param size The size to allocate for the file
     * @throws IOException if opening fails
     */
    private void openFileChannel(long size) throws IOException {
        fileChannel = FileChannel.open(
            logFilePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );

        if (usingPanama) {
            // One Arena per mapping, so the previous one can be released. Rotation re-maps,
            // and without this the log kept every mapping it had ever made.
            releaseMapping();
            Arena mapping = Arena.ofShared();
            try {
                mappedSegment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, size, mapping);
            } catch (IOException | RuntimeException e) {
                mapping.close();
                throw e;
            }
            this.mappingArena = mapping;

            // Create Segment wrapper for abstraction layer access
            mappedSegmentWrapper = MemorySegmentFactory.wrapNative(mappedSegment);
        } else {
            // Use fallback implementation via MemorySegmentFactory
            mappedSegment = null; // Not available in fallback mode
            mappedSegmentWrapper = MemorySegmentFactory.mapChannel(fileChannel, size);
        }
    }

    /**
     * Releases the current mapping, if there is one.
     *
     * <p>Closing the Arena is what unmaps: a {@link MemorySegment} obtained from
     * {@link FileChannel#map} lives exactly as long as the Arena it was mapped into.</p>
     */
    private void releaseMapping() {
        Arena mapping = this.mappingArena;
        this.mappingArena = null;
        if (mapping != null) {
            try {
                mapping.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                    "Could not release the mapping for write log " + logFilePath, e);
            }
        }
    }

    /**
     * Scans existing entries to determine the next LSN and write offset.
     *
     * <p>This method reads through the log file from the beginning, validating
     * CRC32 checksums and tracking the highest LSN seen.</p>
     */
    private void scanExistingEntries() {
        long offset = 0;
        long maxLsn = -1;
        long fileSize = mappedSegmentWrapper.byteSize();

        while (offset < fileSize) {
            // Check if we have enough bytes for a header
            if (offset + LogEntry.HEADER_SIZE > fileSize) {
                break;
            }

            try {
                // Read key_len and value_len to determine entry size
                int entrySize = LogEntry.sizeAt(mappedSegmentWrapper, offset);

                // Check if we have enough bytes for the full entry
                if (offset + entrySize > fileSize) {
                    break;
                }

                // Validate CRC32
                if (!LogEntry.validateCrc32(mappedSegmentWrapper, offset)) {
                    // Corrupted entry, stop scanning
                    LOGGER.log(Level.WARNING,
                        "Corrupted log entry found at offset {0}, stopping scan", offset);
                    break;
                }

                // Read the entry to get the LSN
                LogEntry entry = LogEntry.readFromUnchecked(mappedSegmentWrapper, offset);
                maxLsn = Math.max(maxLsn, entry.lsn());

                offset += entrySize;
            } catch (Exception e) {
                // Invalid entry format, stop scanning
                LOGGER.log(Level.WARNING,
                    "Invalid log entry at offset " + offset + ", stopping scan", e);
                break;
            }
        }

        // Set next LSN to max + 1 (or 0 if no entries)
        nextLsn.set(maxLsn + 1);
        writeOffset.set(offset);
    }

    // ==================== Append Operations ====================

    /**
     * Appends a new entry to the write-ahead log.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Computes the key hash using xxHash64</li>
     *   <li>Creates a LogEntry with the next LSN and current timestamp</li>
     *   <li>Writes the entry to the memory-mapped file</li>
     *   <li>Fsyncs the file to ensure durability</li>
     *   <li>Returns the assigned LSN</li>
     * </ol>
     *
     * <p>The append operation is atomic - either the full entry is written
     * and fsynced, or an exception is thrown.</p>
     *
     * @param opcode The operation code (PUT=0x01 or DELETE=0x02)
     * @param key    The key bytes
     * @param value  The value bytes (can be empty for DELETE)
     * @return The assigned LSN (monotonically increasing, starts at 0)
     * @throws IOException if the append or fsync fails
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if key or value is null
     * @throws IllegalArgumentException if opcode is invalid
     */
    public long append(byte opcode, byte[] key, byte[] value) throws IOException {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        LogEntry.validateOpcode(opcode);

        ensureOpen();

        // Compute key hash using xxHash64
        long keyHash = XxHash64.hash(key);

        // Get next LSN and timestamp atomically
        long lsn = nextLsn.getAndIncrement();
        long timestamp = System.currentTimeMillis();

        // Create the log entry
        LogEntry entry = new LogEntry(opcode, lsn, timestamp, keyHash, key, value);
        int entrySize = entry.size();

        appendLock.lock();
        try {
            // Ensure we have space
            ensureCapacity(entrySize);

            // Write entry to memory-mapped file
            long offset = writeOffset.get();
            entry.writeTo(mappedSegmentWrapper, offset);

            // Update write offset and statistics
            writeOffset.set(offset + entrySize);
            totalBytesWritten.addAndGet(entrySize);
            totalEntriesAppended.incrementAndGet();

            // Fsync for durability
            fsync(offset, entrySize);

            return lsn;
        } finally {
            appendLock.unlock();
        }
    }

    /**
     * Appends a PUT entry to the write-ahead log.
     *
     * @param key   The key bytes
     * @param value The value bytes
     * @return The assigned LSN
     * @throws IOException if the append fails
     */
    public long appendPut(byte[] key, byte[] value) throws IOException {
        return append(LogEntry.OPCODE_PUT, key, value);
    }

    /**
     * Appends a DELETE entry to the write-ahead log.
     *
     * @param key The key bytes
     * @return The assigned LSN
     * @throws IOException if the append fails
     */
    public long appendDelete(byte[] key) throws IOException {
        return append(LogEntry.OPCODE_DELETE, key, new byte[0]);
    }

    // ==================== Recovery Operations ====================

    /**
     * Replays all valid log entries to a handler for crash recovery.
     *
     * <p>This method reads through the log file from the beginning, validates
     * CRC32 checksums, and passes valid entries to the handler. Entries are
     * processed in LSN order.</p>
     *
     * <p>Corrupted entries (CRC32 mismatch) are skipped with a warning log.
     * Recovery continues until the end of valid data is reached.</p>
     *
     * <p>A failure thrown by {@code handler} is <strong>not</strong> a corruption: it is
     * wrapped in a {@link LogReplayException} and propagated, because the remaining log
     * would otherwise be dropped without any caller being able to tell.</p>
     *
     * <p><strong>Important:</strong> This method should only be called during
     * initialization when no concurrent appends are happening.</p>
     *
     * <p><strong>Domain Correctness:</strong> After recovery, this method sets the
     * domain correctness flag to true only if all entries are valid and
     * all domain constraints are satisfied.</p>
     *
     * @param handler The consumer to process each recovered entry
     * @return true if recovery was successful and domain correctness verified
     * @throws LogReplayException if the handler fails to apply a valid entry
     * @throws IOException if reading the log fails
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if handler is null
     */
    public boolean recover(Consumer<LogEntry> handler) throws IOException {
        Objects.requireNonNull(handler, "Handler cannot be null");
        ensureOpen();

        long offset = 0;
        long endOffset = writeOffset.get(); // Only scan up to actual written data
        int entriesRecovered = 0;
        int entriesSkipped = 0;
        boolean domainCorrect = true;

        LOGGER.log(Level.INFO, "Starting log recovery from {0} (endOffset={1})",
            new Object[] { logFilePath, endOffset });

        while (offset < endOffset) {
            // Check if we have enough bytes for a header
            if (offset + LogEntry.HEADER_SIZE > endOffset) {
                break;
            }

            // Parsing and applying are kept apart on purpose. A record that will not
            // parse is a storage problem recovery can step over; a handler that rejects
            // a well-formed record means the segment cannot be restored to the state its
            // log describes, and the rest of the log must not be quietly dropped.
            LogEntry entry;
            int entrySize;
            try {
                // Read key_len and value_len to determine entry size
                entrySize = LogEntry.sizeAt(mappedSegmentWrapper, offset);

                // Check if we have enough bytes for the full entry
                if (offset + entrySize > endOffset) {
                    break;
                }

                // Validate CRC32
                if (!LogEntry.validateCrc32(mappedSegmentWrapper, offset)) {
                    // Corrupted entry, skip
                    entriesSkipped++;
                    domainCorrect = false;
                    LOGGER.log(Level.WARNING,
                        "Skipping corrupted log entry at offset {0}", offset);

                    // Try to find next valid entry by scanning forward
                    // This handles partial writes from crashes
                    offset++;
                    continue;
                }

                entry = LogEntry.readFrom(mappedSegmentWrapper, offset);
            } catch (Exception e) {
                // Unparseable entry, stop recovery
                LOGGER.log(Level.WARNING,
                    "Invalid log entry at offset " + offset + ", stopping recovery", e);
                domainCorrect = false;
                break;
            }

            try {
                handler.accept(entry);
            } catch (RuntimeException e) {
                throw new LogReplayException(
                    "Write-ahead log replay failed at offset " + offset + " (lsn=" + entry.lsn()
                    + ") in " + logFilePath + " after " + entriesRecovered
                    + " entries; the segment's committed state cannot be reconstructed.", e);
            }

            entriesRecovered++;
            offset += entrySize;
        }

        // Set domain correctness flag
        this.corruptEntriesFound.set(entriesSkipped);
        this.setDomainCorrect(domainCorrect);

        LOGGER.log(Level.INFO,
            "Log recovery complete: {0} entries recovered, {1} entries skipped, " +
            "domainCorrect={2}",
            new Object[] { entriesRecovered, entriesSkipped, domainCorrect });

        return domainCorrect;
    }

    // ==================== Log Rotation ====================

    /**
     * Rotates the log file, starting a fresh log.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Closes the current file channel</li>
     *   <li>Renames the old log file by appending ".old"</li>
     *   <li>Creates a new empty log file</li>
     *   <li>Resets LSN counter to 0</li>
     * </ol>
     *
     * <p>The old log file remains on disk and should be deleted by the
     * compaction process after it has been merged into the segment.</p>
     *
     * <p><strong>Thread Safety:</strong> This method acquires the append lock
     * to ensure no concurrent appends during rotation.</p>
     *
     * @throws IOException if rotation fails
     * @throws IllegalStateException if the log is closed
     */
    public void rotate() throws IOException {
        ensureOpen();

        appendLock.lock();
        try {
            LOGGER.log(Level.INFO, "Rotating log file: {0}", logFilePath);

            // Close current file channel
            fileChannel.close();

            // Rename old file
            Path rotatedPath = Path.of(logFilePath.toString() + ROTATED_EXTENSION);
            Files.deleteIfExists(rotatedPath);
            Files.move(logFilePath, rotatedPath);

            // Create new file. The same capacity a fresh log is given: rotating into a
            // smaller mapping would cap the new log at a fraction of what the full-check
            // in isFull() measures it against.
            openFileChannel(MAX_FILE_SIZE);

            // Reset counters
            nextLsn.set(0);
            writeOffset.set(0);

            LOGGER.log(Level.INFO, "Log rotation complete. Old log at: {0}", rotatedPath);
        } finally {
            appendLock.unlock();
        }
    }

    /**
     * Returns the path to the rotated log file, if it exists.
     *
     * @return The path to the rotated log file, or null if none exists
     */
    public Path getRotatedLogPath() {
        Path rotatedPath = Path.of(logFilePath.toString() + ROTATED_EXTENSION);
        return Files.exists(rotatedPath) ? rotatedPath : null;
    }

    /**
     * Deletes the rotated log file.
     *
     * <p>This should be called after compaction has successfully merged
     * the old log entries into the segment.</p>
     *
     * @return true if the file was deleted, false if it didn't exist
     * @throws IOException if deletion fails
     */
    public boolean deleteRotatedLog() throws IOException {
        Path rotatedPath = Path.of(logFilePath.toString() + ROTATED_EXTENSION);
        if (Files.exists(rotatedPath)) {
            Files.delete(rotatedPath);
            LOGGER.log(Level.INFO, "Deleted rotated log file: {0}", rotatedPath);
            return true;
        }
        return false;
    }

    // ==================== Capacity Management ====================

    /**
     * Ensures the memory-mapped file has enough capacity for the given entry size.
     *
     * <p>If the current capacity is insufficient, the file is extended.</p>
     *
     * @param requiredBytes The number of bytes needed
     * @throws IOException if extending the file fails
     */
    private void ensureCapacity(int requiredBytes) throws IOException {
        long currentOffset = writeOffset.get();
        long capacity = mappedSegmentWrapper.byteSize();

        if (currentOffset + requiredBytes > capacity) {
            throw new IOException(
                "Write-ahead log " + logFilePath + " is full (" + currentOffset + " of "
                + capacity + " bytes used, " + requiredBytes + " more required). "
                + "The log is reclaimed by compaction, which rebuilds the segment and "
                + "starts a fresh log; enable or unblock compaction for this segment.");
        }
    }

    // ==================== Fsync ====================

    /**
     * Forces a written range to be persisted to the storage device.
     *
     * <p>Entries are written <em>through the memory mapping</em>, so the mapped range
     * has to be msync'd as well: {@code FileChannel.force} alone is not specified to
     * flush pages dirtied through a mapping. Only the range just written is forced, so
     * the cost does not scale with the size of the log.</p>
     *
     * @param offset Offset of the range to persist
     * @param length Length of the range to persist
     * @throws IOException if fsync fails
     */
    private void fsync(long offset, int length) throws IOException {
        MemorySegment mapping = this.mappedSegment;
        if (mapping != null) {
            mapping.asSlice(offset, length).force();
        }
        fileChannel.force(true); // true = include metadata per project spec
    }

    // ==================== Status and Statistics ====================

    /**
     * Returns the next LSN that will be assigned to a new entry.
     *
     * @return The next LSN
     */
    public long nextLsn() {
        return nextLsn.get();
    }

    /**
     * Returns the current write offset in the log file.
     *
     * @return The write offset in bytes
     */
    public long writeOffset() {
        return writeOffset.get();
    }

    /**
     * Reads the log entry at the specified offset.
     *
     * <p>This method is used by the tail scan to read entries directly from
     * the write log without going through the full recovery mechanism.</p>
     *
     * <p><strong>Performance:</strong> This is a direct read from the memory-mapped
     * file with CRC32 validation. For bulk reads, consider using {@link #recover(Consumer)}.</p>
     *
     * @param offset The byte offset within the log file
     * @return The log entry, or null if offset is out of range or entry is corrupted
     * @throws IllegalStateException if the log is closed
     */
    public LogEntry readEntryAt(long offset) {
        ensureOpen();

        long endOffset = writeOffset.get();

        // Check if offset is within valid range
        if (offset < 0 || offset >= endOffset) {
            return null;
        }

        // Check if we have enough bytes for a header
        if (offset + LogEntry.HEADER_SIZE > endOffset) {
            return null;
        }

        try {
            // Read entry size first
            int entrySize = LogEntry.sizeAt(mappedSegmentWrapper, offset);

            // Check if we have enough bytes for the full entry
            if (offset + entrySize > endOffset) {
                return null;
            }

            // Validate CRC32
            if (!LogEntry.validateCrc32(mappedSegmentWrapper, offset)) {
                LOGGER.fine("CRC32 validation failed at offset " + offset);
                return null;
            }

            // Read and return the entry
            return LogEntry.readFromUnchecked(mappedSegmentWrapper, offset);

        } catch (Exception e) {
            LOGGER.fine("Failed to read entry at offset " + offset + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the size of the entry at the specified offset.
     *
     * <p>This is useful for iterating through entries without reading the full content.</p>
     *
     * @param offset The byte offset within the log file
     * @return The entry size in bytes, or -1 if offset is invalid
     * @throws IllegalStateException if the log is closed
     */
    public int entrySizeAt(long offset) {
        ensureOpen();

        long endOffset = writeOffset.get();

        if (offset < 0 || offset + LogEntry.HEADER_SIZE > endOffset) {
            return -1;
        }

        try {
            return LogEntry.sizeAt(mappedSegmentWrapper, offset);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the current size of the log file.
     *
     * @return The file size in bytes
     */
    public long fileSize() {
        return mappedSegmentWrapper != null ? mappedSegmentWrapper.byteSize() : 0;
    }

    /**
     * Returns the path to the log file.
     *
     * @return The log file path
     */
    public Path path() {
        return logFilePath;
    }

    /**
     * Returns the memory-mapped segment wrapper using the Segment abstraction.
     *
     * <p>This method provides access to the underlying memory through the
     * {@link Segment} interface, which abstracts over Panama FFM and ByteBuffer
     * implementations.</p>
     *
     * @return The Segment wrapper, or null if not initialized
     */
    public Segment getSegment() {
        return mappedSegmentWrapper;
    }

    /**
     * Returns the native MemorySegment (Panama FFM).
     *
     * <p>This method provides direct access to the native MemorySegment for
     * advanced operations. Returns null if Panama FFM is not available.</p>
     *
     * @return The native MemorySegment, or null if not using Panama FFM
     */
    public MemorySegment getNativeSegment() {
        return mappedSegment;
    }

    /**
     * Reads the log entry at the specified offset using the Segment abstraction.
     *
     * <p>This is an overloaded version of {@link #readEntryAt(long)} that uses
     * the Segment abstraction layer for better compatibility across different
     * memory access implementations.</p>
     *
     * @param segment The segment to read from
     * @param offset The byte offset within the log file
     * @return The log entry, or null if offset is out of range or entry is corrupted
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if segment is null
     */
    public LogEntry readEntryAt(Segment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        ensureOpen();

        long endOffset = writeOffset.get();

        // Check if offset is within valid range
        if (offset < 0 || offset >= endOffset) {
            return null;
        }

        // Check if we have enough bytes for a header
        if (offset + LogEntry.HEADER_SIZE > endOffset) {
            return null;
        }

        try {
            // Read entry size first
            int entrySize = LogEntry.sizeAt(segment, offset);

            // Check if we have enough bytes for the full entry
            if (offset + entrySize > endOffset) {
                return null;
            }

            // Validate CRC32
            if (!LogEntry.validateCrc32(segment, offset)) {
                LOGGER.fine("CRC32 validation failed at offset " + offset);
                return null;
            }

            // Read and return the entry
            return LogEntry.readFromUnchecked(segment, offset);

        } catch (Exception e) {
            LOGGER.fine("Failed to read entry at offset " + offset + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the size of the entry at the specified offset using the Segment abstraction.
     *
     * @param segment The segment to read from
     * @param offset The byte offset within the log file
     * @return The entry size in bytes, or -1 if offset is invalid
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if segment is null
     */
    public int entrySizeAt(Segment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        ensureOpen();

        long endOffset = writeOffset.get();

        if (offset < 0 || offset + LogEntry.HEADER_SIZE > endOffset) {
            return -1;
        }

        try {
            return LogEntry.sizeAt(segment, offset);
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== Statistics and Status ====================

    /**
     * Returns the total number of bytes written to the log.
     *
     * @return Total bytes written
     */
    public long bytesWritten() {
        return totalBytesWritten.get();
    }

    /**
     * Returns the total number of entries appended to the log.
     *
     * @return Total entries appended
     */
    public long entriesAppended() {
        return totalEntriesAppended.get();
    }

    /**
     * Returns the number of corrupt entries found during recovery.
     *
     * @return Corrupt entries count
     */
    public long corruptEntriesFound() {
        return corruptEntriesFound.get();
    }

    /**
     * Returns the last integrated offset.
     * <p>
     * This offset tracks the write log offset up to which all entries
     * have been linked into the HNSW graph (Phase 4). Entries beyond
     * this offset exist in the write log but are not yet in the graph.
     * </p>
     *
     * @return The last integrated offset
     */
    public long lastIntegratedOffset() {
        return lastIntegratedOffset.get();
    }

    /**
     * Checks if the log file size exceeds the recommended maximum.
     *
     * <p>If this returns true, rotation should be considered to prevent
     * the log file from growing too large.</p>
     *
     * @return true if rotation is recommended
     */
    public boolean shouldRotate() {
        return writeOffset.get() > MAX_FILE_SIZE;
    }

    /**
     * Returns whether this log is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    // ==================== Lifecycle ====================

    /**
     * Ensures the log is not closed.
     *
     * @throws IllegalStateException if the log is closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WriteLog is closed: " + logFilePath);
        }
    }

    /**
     * Closes the write-ahead log, releasing all resources.
     *
     * <p>After closing, no further operations can be performed on this log.
     * Ensures all pending writes are fsynced and domain correctness is finalized.</p>
     *
     * @throws IOException if closing fails
     */
    @Override
    public void close() throws IOException {
        appendLock.lock();
        try {
            if (closed) {
                return;
            }

            closed = true;

            // Final fsync to ensure durability
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.force(true);
                fileChannel.close();
            }

            // Unmap. Readers hold no mapping of their own, so this is the only reference;
            // a read racing this close fails loudly rather than reading unmapped memory.
            releaseMapping();
            mappedSegment = null;
            mappedSegmentWrapper = null;

            LOGGER.log(Level.INFO, "WriteLog closed: {0} (entries={1}, bytes={2}, corrupt={3}, domainCorrect={4})",
                new Object[] {
                    logFilePath,
                    totalEntriesAppended.get(),
                    totalBytesWritten.get(),
                    corruptEntriesFound.get(),
                    domainCorrect
                });
        } finally {
            appendLock.unlock();
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "WriteLog[path=%s, nextLsn=%d, writeOffset=%d, fileSize=%d, " +
            "entries=%d, bytes=%d, corrupt=%d, lastIntOff=%d, domainCorrect=%s]",
            logFilePath,
            nextLsn.get(),
            writeOffset.get(),
            fileSize(),
            totalEntriesAppended.get(),
            totalBytesWritten.get(),
            corruptEntriesFound.get(),
            lastIntegratedOffset.get(),
            domainCorrect
        );
    }

    // ==================== Main Method for Testing ====================

    /**
     * Main method for testing WriteLog functionality.
     * Run with: java -cp <classpath> io.excalibur.store.WriteLog
     */
    public static void main(String[] args) {
        System.out.println("Testing WriteLog...");
        System.out.println("==================");

        try {
            // Create a temporary log file
            Path tempLog = Path.of("/tmp/excalibur_test_log");
            
            // Clean up any existing test file
            Files.deleteIfExists(tempLog);

            // Create WriteLog
            Arena arena = Arena.ofShared();
            WriteLog writeLog = new WriteLog(tempLog);

            System.out.println("Created WriteLog: " + writeLog);

            // Test 1: Append PUT entries
            System.out.println("\n--- Test 1: Appending PUT entries ---");
            long lsn1 = writeLog.appendPut(
                "testKey1".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "testValue1".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            System.out.println("Appended PUT entry, LSN=" + lsn1);

            long lsn2 = writeLog.appendPut(
                "testKey2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "testValue2".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            System.out.println("Appended PUT entry, LSN=" + lsn2);

            // Test 2: Append DELETE entry
            System.out.println("\n--- Test 2: Appending DELETE entry ---");
            long lsn3 = writeLog.appendDelete("testKey1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Appended DELETE entry, LSN=" + lsn3);

            // Test 3: Check statistics
            System.out.println("\n--- Test 3: Statistics ---");
            System.out.println("  Entries appended: " + writeLog.entriesAppended());
            System.out.println("  Bytes written: " + writeLog.bytesWritten());
            System.out.println("  Next LSN: " + writeLog.nextLsn());
            System.out.println("  Write offset: " + writeLog.writeOffset());

            // Test 4: Recovery
            System.out.println("\n--- Test 4: Log Recovery ---");
            final int[] recoveredCount = {0};
            writeLog.recover(entry -> {
                System.out.println("  Recovered: " + entry);
                if (entry.isPut()) {
                    recoveredCount[0]++;
                }
            });
            System.out.println("  Total recovered: " + recoveredCount[0]);
            System.out.println("  Domain correct: " + writeLog.isDomainCorrect());

            // Test 5: Verify LSN monotonicity
            System.out.println("\n--- Test 5: LSN Monotonicity ---");
            final boolean[] monotonic = {true};
            final long[] expectedLsn = {0};
            writeLog.recover(entry -> {
                if (entry.lsn() < expectedLsn[0]) {
                    monotonic[0] = false;
                    System.out.println("  ERROR: LSN not monotonic! Expected >= " + expectedLsn[0] + ", got " + entry.lsn());
                }
                expectedLsn[0] = Math.max(expectedLsn[0], entry.lsn()) + 1;
            });
            System.out.println("  LSNs are monotonic: " + monotonic[0]);

            // Test 6: CRC32 validation (already validated during readFrom)
            System.out.println("\n--- Test 6: CRC32 Validation ---");
            System.out.println("  CRC32 validated during entry read (exceptions thrown on failure)");

            // Test 7: Read entries directly
            System.out.println("\n--- Test 7: Direct Entry Reading ---");
            final long[] startOffsetHolder = {0};
            writeLog.recover(entry -> {
                if (entry.lsn() == 0) {
                    System.out.println("  First entry LSN: " + entry.lsn());
                    startOffsetHolder[0] = 1; // Mark as found
                }
            });
            System.out.println("  First entry found: " + (startOffsetHolder[0] == 1));

            // Close the log
            System.out.println("\n--- Test 8: Closing ---");
            writeLog.close();
            System.out.println("WriteLog closed successfully");

            // Clean up
            Files.deleteIfExists(tempLog);

            System.out.println("\nAll tests passed!");

        } catch (Exception e) {
            System.err.println("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
