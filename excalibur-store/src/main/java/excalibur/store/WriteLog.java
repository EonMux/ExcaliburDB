package excalibur.store;

import excalibur.store.format.LogEntry;
import excalibur.store.hash.XxHash64;

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
    private final Arena arena;
    private final ReentrantLock appendLock;
    private final AtomicLong nextLsn;
    private final AtomicLong writeOffset;

    private volatile FileChannel fileChannel;
    private volatile MemorySegment mappedSegment;
    private volatile boolean closed;

    // ==================== Constructors ====================

    /**
     * Creates or opens a write-ahead log at the specified path.
     *
     * <p>If the log file exists, it will be opened and the next LSN will be
     * determined by scanning existing entries. If the file does not exist,
     * it will be created.</p>
     *
     * @param logFilePath The path to the log file
     * @param arena       The Arena for memory allocation (must outlive this WriteLog)
     * @throws IOException if the file cannot be created or opened
     * @throws NullPointerException if logFilePath or arena is null
     */
    public WriteLog(Path logFilePath, Arena arena) throws IOException {
        Objects.requireNonNull(logFilePath, "Log file path cannot be null");
        Objects.requireNonNull(arena, "Arena cannot be null");

        this.logFilePath = logFilePath;
        this.arena = arena;
        this.appendLock = new ReentrantLock();
        this.nextLsn = new AtomicLong(0);
        this.writeOffset = new AtomicLong(0);
        this.closed = false;

        initializeLogFile();
    }

    // ==================== Initialization ====================

    /**
     * Initializes the log file, creating it if necessary and memory-mapping it.
     *
     * @throws IOException if initialization fails
     */
    private void initializeLogFile() throws IOException {
        boolean fileExists = Files.exists(logFilePath);

        if (fileExists) {
            // Open existing file and determine next LSN from existing entries
            long fileSize = Files.size(logFilePath);
            if (fileSize == 0) {
                // Empty file, start fresh
                openFileChannel(INITIAL_FILE_SIZE);
            } else {
                // Scan to find the highest LSN and write offset
                openFileChannel(Math.max(fileSize, INITIAL_FILE_SIZE));
                scanExistingEntries();
            }
        } else {
            // Create parent directories if needed
            Path parent = logFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Create new file with initial size
            openFileChannel(INITIAL_FILE_SIZE);
        }

        LOGGER.log(Level.INFO, "WriteLog initialized at {0}, nextLSN={1}, writeOffset={2}",
            new Object[] { logFilePath, nextLsn.get(), writeOffset.get() });
    }

    /**
     * Opens the file channel and memory-maps the file.
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

        // Allocate file space
        fileChannel.truncate(size);

        // Memory-map the file
        mappedSegment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
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
        long fileSize = mappedSegment.byteSize();

        while (offset < fileSize) {
            // Check if we have enough bytes for a header
            if (offset + LogEntry.HEADER_SIZE > fileSize) {
                break;
            }

            try {
                // Read key_len and value_len to determine entry size
                int entrySize = LogEntry.sizeAt(mappedSegment, offset);

                // Check if we have enough bytes for the full entry
                if (offset + entrySize > fileSize) {
                    break;
                }

                // Validate CRC32
                if (!LogEntry.validateCrc32(mappedSegment, offset)) {
                    // Corrupted entry, stop scanning
                    LOGGER.log(Level.WARNING,
                        "Corrupted log entry found at offset {0}, stopping scan", offset);
                    break;
                }

                // Read the entry to get the LSN
                LogEntry entry = LogEntry.readFromUnchecked(mappedSegment, offset);
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

        // Compute key hash
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
            entry.writeTo(mappedSegment, offset);

            // Update write offset
            writeOffset.set(offset + entrySize);

            // Fsync for durability
            fsync();

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
     * <p><strong>Important:</strong> This method should only be called during
     * initialization when no concurrent appends are happening.</p>
     *
     * @param handler The consumer to process each recovered entry
     * @throws IOException if reading the log fails
     * @throws IllegalStateException if the log is closed
     * @throws NullPointerException if handler is null
     */
    public void recover(Consumer<LogEntry> handler) throws IOException {
        Objects.requireNonNull(handler, "Handler cannot be null");
        ensureOpen();

        long offset = 0;
        long endOffset = writeOffset.get(); // Only scan up to actual written data
        int entriesRecovered = 0;
        int entriesSkipped = 0;

        LOGGER.log(Level.INFO, "Starting log recovery from {0} (endOffset={1})",
            new Object[] { logFilePath, endOffset });

        while (offset < endOffset) {
            // Check if we have enough bytes for a header
            if (offset + LogEntry.HEADER_SIZE > endOffset) {
                break;
            }

            try {
                // Read key_len and value_len to determine entry size
                int entrySize = LogEntry.sizeAt(mappedSegment, offset);

                // Check if we have enough bytes for the full entry
                if (offset + entrySize > endOffset) {
                    break;
                }

                // Validate CRC32
                if (!LogEntry.validateCrc32(mappedSegment, offset)) {
                    // Corrupted entry, skip
                    entriesSkipped++;
                    LOGGER.log(Level.WARNING,
                        "Skipping corrupted log entry at offset {0}", offset);

                    // Try to find next valid entry by scanning forward
                    // This handles partial writes from crashes
                    offset++;
                    continue;
                }

                // Read and process the entry
                LogEntry entry = LogEntry.readFrom(mappedSegment, offset);
                handler.accept(entry);
                entriesRecovered++;

                offset += entrySize;
            } catch (Exception e) {
                // Invalid entry, stop recovery
                LOGGER.log(Level.WARNING,
                    "Invalid log entry at offset " + offset + ", stopping recovery", e);
                break;
            }
        }

        LOGGER.log(Level.INFO,
            "Log recovery complete: {0} entries recovered, {1} entries skipped",
            new Object[] { entriesRecovered, entriesSkipped });
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

            // Create new file
            openFileChannel(INITIAL_FILE_SIZE);

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
        long currentSize = mappedSegment.byteSize();

        if (currentOffset + requiredBytes > currentSize) {
            // Calculate new size (double or minimum required)
            long newSize = Math.max(currentSize * 2, currentOffset + requiredBytes + INITIAL_FILE_SIZE);

            // Extend the file
            fileChannel.truncate(newSize);

            // Re-map the file
            mappedSegment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, newSize, arena);

            LOGGER.log(Level.FINE, "Extended log file to {0} bytes", newSize);
        }
    }

    // ==================== Fsync ====================

    /**
     * Forces all written data to be persisted to the storage device.
     *
     * <p>This is called after every append to ensure durability guarantees.</p>
     *
     * @throws IOException if fsync fails
     */
    private void fsync() throws IOException {
        fileChannel.force(true); // true = include metadata
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
     * Returns the current size of the log file.
     *
     * @return The file size in bytes
     */
    public long fileSize() {
        return mappedSegment != null ? mappedSegment.byteSize() : 0;
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
     * <p>After closing, no further operations can be performed on this log.</p>
     *
     * @throws IOException if closing fails
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        appendLock.lock();
        try {
            if (closed) {
                return;
            }

            closed = true;

            if (fileChannel != null && fileChannel.isOpen()) {
                // Final fsync before closing
                fileChannel.force(true);
                fileChannel.close();
            }

            LOGGER.log(Level.INFO, "WriteLog closed: {0}", logFilePath);
        } finally {
            appendLock.unlock();
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("WriteLog[path=%s, nextLsn=%d, writeOffset=%d, fileSize=%d, closed=%s]",
            logFilePath, nextLsn.get(), writeOffset.get(), fileSize(), closed);
    }
}
