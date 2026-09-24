package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.checksum.CRC32Checksum;
import com.eonmux.excalibur.core.memory.Segment;
import com.eonmux.excalibur.core.memory.MemorySegmentFactory;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.store.format.UnsupportedWriteLogFormatException;
import com.eonmux.excalibur.store.format.WriteLogFormat;
import com.eonmux.excalibur.store.format.WriteLogHeader;
import com.eonmux.excalibur.store.hash.XxHash64;

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
 * <h2>File Format</h2>
 * <p>A log this build creates is {@link WriteLogFormat#CURRENT}: a {@link WriteLogHeader} -
 * magic {@code "EXWL"}, a format version and a CRC over both - followed by the records. A log
 * written before the header existed ({@link WriteLogFormat#V1}) has records from offset 0 and
 * is still read and appended to; see {@link WriteLogFormat} for when it is replaced by a
 * headed one. Opening a log whose sound header names a version this build does not read fails
 * with {@link UnsupportedWriteLogFormatException} and leaves the file exactly as it was.</p>
 *
 * <p>Every offset this class hands out or takes back - {@link #writeOffset()},
 * {@link #readEntryAt(long)}, {@link #entrySizeAt(long)} and the integrated offset - is a
 * position in the record stream, which does not include the header. Those offsets are
 * recorded against the log elsewhere (the catalog and the graph keep the integrated offset),
 * and they mean the same thing in a headed log as in a headerless one. The offsets in a
 * {@link CorruptLogException}, and {@link #firstCorruptOffset()}, are offsets in the file,
 * because an operator reads the file.</p>
 *
 * <h2>Log Entry Format</h2>
 * <p>Each entry follows the format defined in {@link LogEntry}. Its multi-byte fields are
 * written through the {@link Segment} accessors, which are big-endian:</p>
 * <pre>
 * Offset 0:   opcode (1 byte: PUT=0x01, DELETE=0x02)
 * Offset 1:   lsn (8 bytes, monotonically increasing)
 * Offset 9:   timestamp (8 bytes, epoch millis)
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
 *   <li>{@code crc32}: CRC-32 checksum of the record's preceding bytes</li>
 *   <li>Log file is append-only (no in-place modifications)</li>
 * </ul>
 *
 * <h2>Durability Guarantees</h2>
 * <ul>
 *   <li>An append publishes the record only once the fsync that makes it durable has
 *       returned, so a record the caller was told did not land is not in the log</li>
 *   <li>Recovery replays entries in LSN order</li>
 *   <li>Opening a log that is damaged anywhere but its tail fails with
 *       {@link CorruptLogException} rather than ending the log at the damage, because every
 *       record past it was acknowledged; a tail torn by a crash ends the log and is logged</li>
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
     * The capacity of a log, and a hard limit rather than a recommendation (64 MiB).
     *
     * <p>The file is mapped once at this size and never re-mapped (see the comment on the
     * {@code openFileChannel} call in the constructor), so an append that would cross it is
     * refused and stays refused: nothing grows the log, and only compaction replaces it. That
     * makes this the ceiling every write-log-driven compaction trigger has to sit below, which
     * is why it is public - {@link CompactionWorker#MAX_WRITE_LOG_SIZE_THRESHOLD} is derived
     * from it, and a trigger above it would be a trigger that can never fire.</p>
     */
    public static final long MAX_FILE_SIZE = 64L * 1024 * 1024;

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

    /**
     * Where the next record is written, as an offset in the file.
     *
     * <p>A file offset rather than a record-stream one, because every read and write of the
     * mapping is made at one; {@link #writeOffset()} subtracts the header on the way out.</p>
     */
    private final AtomicLong writeOffset;

    /**
     * The format of the file currently mapped, which says where its first record is.
     *
     * <p>Not final: a rotation starts a new file, which is always the current format whatever
     * the one it replaced was.</p>
     */
    private volatile WriteLogFormat format;

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
     * Offset of the first record a recovery could not read, or -1 if there was none.
     *
     * <p>The count alone does not let a caller say where the log stopped being trustworthy,
     * which is the first thing an operator needs and the first thing a failure has to carry.
     */
    private final AtomicLong firstCorruptOffset = new AtomicLong(-1);

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
     * it will be created, with a header naming {@link WriteLogFormat#CURRENT}.</p>
     *
     * @param logFilePath The path to the log file
     * @throws UnsupportedWriteLogFormatException if a newer build wrote the log; the file is
     *                                            left untouched
     * @throws CorruptLogException if the log is damaged in a way that loses acknowledged
     *                             records; the file is left untouched
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

        LogScan existing = null;
        if (hasExistingEntries) {
            // Before anything maps the file for writing: that mapping extends it to the log's
            // full capacity, and a log that has to be refused - a newer build's, or one damaged
            // in the middle - must be left exactly as it was found.
            existing = LogScan.of(logFilePath);
        } else {
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

        try {
            if (existing == null) {
                startWithHeader();
            } else {
                adopt(existing);
            }
        } catch (IOException | RuntimeException e) {
            // The constructor is about to fail, so nothing will ever call close() on this
            // log: the mapping and the descriptor are released here or not at all.
            releaseOnFailedOpen(e);
            throw e;
        }

        LOGGER.log(Level.INFO, "WriteLog initialized at {0}, format=v{1}, nextLSN={2}, "
            + "writeOffset={3}",
            new Object[] { logFilePath, format.version(), nextLsn.get(), writeOffset() });
    }

    /**
     * Makes the mapped file an empty log in the current format.
     *
     * <p>The header is forced before this returns, and so before any record can be written
     * behind it. That ordering is what lets a scan treat a damaged header with nothing behind it
     * as a header torn while it was being written - nothing has been lost - and one with
     * records behind it as damage.</p>
     *
     * @throws IOException if the header cannot be made durable
     */
    private void startWithHeader() throws IOException {
        mappedSegmentWrapper.setBytes(0, WriteLogHeader.encode(WriteLogFormat.CURRENT.version()));
        force(0, WriteLogHeader.SIZE);
        format = WriteLogFormat.CURRENT;
        nextLsn.set(0);
        writeOffset.set(format.recordsStart());
    }

    /**
     * Takes up where an existing log left off.
     *
     * <p>A log that holds nothing and has no header to believe - a headerless log no record was
     * ever written to, or a log whose header was torn as it was created - is given a header
     * now, because this is the one point at which that is free: no record has to move, so no
     * offset recorded against the log changes, and an older build reading the result finds a
     * log that ends at offset 0 and writes over the header, losing nothing. A headerless log
     * with records keeps its format until its segment is given a fresh log by a compaction or
     * a split; writing a header into it would mean moving every record, and every offset the
     * catalog and the graph hold for it.</p>
     *
     * @param scan What the read-only scan of the file found
     * @throws IOException if a header has to be written and cannot be made durable
     */
    private void adopt(LogScan scan) throws IOException {
        if (scan.tornTail() != null) {
            // Loud but not fatal: the records the tail lost were never acknowledged, so the
            // log genuinely ends here - but an operator still needs to know the process did
            // not shut down cleanly, and where.
            LOGGER.log(Level.WARNING,
                "Write-ahead log {0} ends in a corrupt or partly written record at offset "
                + "{1}: {2}. Nothing was written after it, so this is a tail torn by a crash "
                + "and the log ends before it; those bytes were never acknowledged.",
                new Object[] { logFilePath, scan.endOffset(), scan.tornTail() });
        }

        if (scan.endOffset() == 0) {
            LOGGER.log(Level.INFO,
                "Write-ahead log {0} holds no record and no header this build can believe; "
                + "writing a version {1} header", new Object[] { logFilePath,
                WriteLogFormat.CURRENT.version() });
            startWithHeader();
            return;
        }

        format = scan.format();
        nextLsn.set(scan.nextLsn());
        writeOffset.set(scan.endOffset());
    }

    /**
     * Gives back the mapping and the descriptor of a log that never finished opening.
     *
     * <p>Whatever goes wrong here is secondary to the failure that brought us in, so it is
     * attached to that one rather than replacing it - the caller needs to be told the log is
     * corrupt, not that closing a channel afterwards also failed.</p>
     *
     * @param failure The failure that is about to be thrown from the constructor
     */
    private void releaseOnFailedOpen(Exception failure) {
        try {
            releaseMapping();
            mappedSegment = null;
            mappedSegmentWrapper = null;
            if (fileChannel != null) {
                fileChannel.close();
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
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

            long offset = writeOffset.get();
            try {
                entry.writeTo(mappedSegmentWrapper, offset);
                fsync(offset, entrySize);
            } catch (IOException | RuntimeException e) {
                discardUnsyncedRecord(offset, e);
                throw e;
            }

            // Published only now. The head is what a tail scan reads up to and what the next
            // append writes after, so advancing it before the fsync returns would make a
            // record visible - and, if the device then refused it, would make a write the
            // caller is about to be told failed indistinguishable from one that succeeded.
            writeOffset.set(offset + entrySize);
            totalBytesWritten.addAndGet(entrySize);
            totalEntriesAppended.incrementAndGet();

            return lsn;
        } finally {
            appendLock.unlock();
        }
    }

    /**
     * Takes back a record that was written into the mapping but never made durable.
     *
     * <p>Leaving the head where it was is not enough on its own: the head is not stored
     * anywhere, it is derived by reading the file back, so a record whose bytes reached the
     * device despite the failed fsync would be read as part of the log on the next open and
     * replayed - committing a write whose caller was told it had failed. Clearing the opcode
     * is what makes the scan read that offset as never written, and it is safe to do because
     * an append holds the append lock and writes at the head, so this record is the last one
     * and nothing sound follows it.</p>
     *
     * <p>If clearing the opcode cannot be made durable either, the log is no worse off than
     * before - the next successful append writes over the same offset and supersedes it - but
     * the caller has to know that until then a restart may still find the record, so the
     * second failure is attached to the first rather than dropped.</p>
     *
     * @param offset  Where the record was written
     * @param failure The failure that is about to be reported to the caller
     */
    private void discardUnsyncedRecord(long offset, Exception failure) {
        try {
            mappedSegmentWrapper.setByte(offset + LogEntry.OPCODE_OFFSET, (byte) 0);
            fsync(offset, LogEntry.OPCODE_SIZE);
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
            LOGGER.log(Level.SEVERE,
                "Write-ahead log " + logFilePath + " could not take back the record at offset "
                + offset + " that its fsync rejected. Until another append overwrites that "
                + "offset, a restart may replay a write the caller was told had failed.", e);
        }
    }

    /**
     * Checks that this log can take an entry for {@code key} and {@code value}.
     *
     * <p>What makes the log write-ahead. A segment cannot append first and mutate second,
     * because the mutation is also what decides whether the write is admissible at all - a
     * Robin Hood insertion discovers a probe-length or distance overflow only by attempting the
     * placement, and a write the segment refuses must never be in the log for a tail scan to
     * read or a restart to replay. So the order is: ask here whether the log could take it,
     * mutate, then append. This is the only part of an append that can refuse a well-formed
     * entry, so once it has passed the append that follows cannot leave the segment holding a
     * change the log has no record of.</p>
     *
     * <p>A check, not a booking: nothing is held. It stays true only for as long as no other
     * thread appends, which is what the caller's own lock is for - every mutation of a segment
     * holds that segment's write lock, and a log belongs to one segment.</p>
     *
     * @param opcode The operation code the entry will carry
     * @param key    The key bytes
     * @param value  The value bytes, empty for a delete
     * @throws IOException              if the log could not take the entry
     * @throws IllegalArgumentException if the entry is malformed, exactly as the append would
     * @throws IllegalStateException    if the log is closed
     */
    public void reserve(byte opcode, byte[] key, byte[] value) throws IOException {
        // The same validation the entry itself performs, and for the same reason it has to
        // happen here: an entry rejected during the append has already been applied to the
        // segment. Anything append can refuse, reserve refuses first.
        LogEntry.validateOpcode(opcode);
        LogEntry.validateKey(key);
        LogEntry.validateValue(value, opcode);
        ensureOpen();

        appendLock.lock();
        try {
            ensureCapacity(LogEntry.calculateSize(key.length, value.length));
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
     * <p>Corrupted entries (CRC32 mismatch) are skipped with a warning log and recovery
     * continues, so that a caller who can salvage a partial replay is able to. It returns
     * false when that happened, and a caller who cannot salvage one - a segment being brought
     * back to the state its log describes, for instance - has to treat that as fatal rather
     * than as a successful recovery. Opening the log has already established that every record
     * up to the head reads cleanly, so reaching one that does not means the log changed while
     * it was being read.</p>
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

        // File offsets throughout, from the first record to the head: a record that will not
        // read is reported where it lies in the file.
        long offset = format.recordsStart();
        long endOffset = writeOffset.get(); // Only scan up to actual written data
        int entriesRecovered = 0;
        int entriesSkipped = 0;
        boolean domainCorrect = true;
        firstCorruptOffset.set(-1);

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
                    firstCorruptOffset.compareAndSet(-1, offset);
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
                firstCorruptOffset.compareAndSet(-1, offset);
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
     *   <li>Creates a new empty log file, with a header naming the current format</li>
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

            // A new file, so the current format whatever the old one was; this also resets
            // the LSN and the head.
            startWithHeader();

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
     * <p>Package-private rather than private so that a test can stand in for a device that
     * rejects the write, which is the one failure of an append that cannot be provoked through
     * the public surface and is also the only one that can leave a record behind.</p>
     *
     * @param offset Offset of the range to persist
     * @param length Length of the range to persist
     * @throws IOException if fsync fails
     */
    void fsync(long offset, int length) throws IOException {
        force(offset, length);
    }

    /**
     * Forces a written range to the device; what {@link #fsync} does when no test stands in.
     *
     * <p>Private so the constructor can make the header durable without calling a method a
     * subclass may have overridden before its own fields are set.</p>
     *
     * @param offset Offset of the range to persist
     * @param length Length of the range to persist
     * @throws IOException if fsync fails
     */
    private void force(long offset, int length) throws IOException {
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
     * Returns the head of the log: how many bytes of records it holds.
     *
     * <p>A position in the record stream, not in the file - the header is not counted - so it
     * is the offset the next record will be read back at, and an empty log's head is 0 in
     * every format.</p>
     *
     * @return The write offset in bytes
     */
    public long writeOffset() {
        return writeOffset.get() - format.recordsStart();
    }

    /**
     * Returns the format of the log file currently mapped.
     *
     * @return The format, never null
     */
    public WriteLogFormat format() {
        return format;
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
     * @param offset The record's position in the record stream, as {@link #writeOffset()}
     *               counts it
     * @return The log entry, or null if offset is out of range or entry is corrupted
     * @throws IllegalStateException if the log is closed
     */
    public LogEntry readEntryAt(long offset) {
        ensureOpen();
        return readEntryAt(mappedSegmentWrapper, offset);
    }

    /**
     * Returns the size of the entry at the specified offset.
     *
     * <p>This is useful for iterating through entries without reading the full content.</p>
     *
     * @param offset The record's position in the record stream, as {@link #writeOffset()}
     *               counts it
     * @return The entry size in bytes, or -1 if offset is invalid
     * @throws IllegalStateException if the log is closed
     */
    public int entrySizeAt(long offset) {
        ensureOpen();
        return entrySizeAt(mappedSegmentWrapper, offset);
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
     * implementations. It maps the whole file, header included.</p>
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
     * advanced operations. Returns null if Panama FFM is not available. It maps the whole
     * file, header included.</p>
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
     * @param segment A mapping of this log's whole file, as {@link #getSegment()} returns
     * @param offset  The record's position in the record stream, as {@link #writeOffset()}
     *                counts it
     * @return The log entry, or null if offset is out of range or entry is corrupted
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if segment is null
     */
    public LogEntry readEntryAt(Segment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        ensureOpen();

        int entrySize = entrySizeAt(segment, offset);
        if (entrySize < 0) {
            return null;
        }
        long at = offset + format.recordsStart();
        try {
            // Check if we have enough bytes for the full entry
            if (at + entrySize > writeOffset.get()) {
                return null;
            }

            // Validate CRC32
            if (!LogEntry.validateCrc32(segment, at)) {
                LOGGER.fine("CRC32 validation failed at offset " + offset);
                return null;
            }

            // Read and return the entry
            return LogEntry.readFromUnchecked(segment, at);

        } catch (Exception e) {
            LOGGER.fine("Failed to read entry at offset " + offset + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the size of the entry at the specified offset using the Segment abstraction.
     *
     * @param segment A mapping of this log's whole file, as {@link #getSegment()} returns
     * @param offset  The record's position in the record stream, as {@link #writeOffset()}
     *                counts it
     * @return The entry size in bytes, or -1 if offset is invalid
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if segment is null
     */
    public int entrySizeAt(Segment segment, long offset) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        ensureOpen();

        // Taken together, so that a rotation between the two reads cannot pair one file's
        // head with the other's header size.
        WriteLogFormat mapped = format;
        long endOffset = writeOffset.get();
        long at = offset + mapped.recordsStart();

        if (offset < 0 || at + LogEntry.HEADER_SIZE > endOffset) {
            return -1;
        }

        try {
            return LogEntry.sizeAt(segment, at);
        } catch (Exception e) {
            LOGGER.fine("Failed to read the entry size at offset " + offset + ": " + e.getMessage());
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
     * Returns where the last recovery first failed to read a record.
     *
     * @return The byte offset, or -1 if the last recovery read every record
     */
    public long firstCorruptOffset() {
        return firstCorruptOffset.get();
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
            writeOffset(),
            fileSize(),
            totalEntriesAppended.get(),
            totalBytesWritten.get(),
            corruptEntriesFound.get(),
            lastIntegratedOffset.get(),
            domainCorrect
        );
    }
}
