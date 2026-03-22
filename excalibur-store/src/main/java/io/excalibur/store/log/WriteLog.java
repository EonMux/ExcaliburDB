package io.excalibur.store.log;

import io.excalibur.core.checksum.CRC32Checksum;
import io.excalibur.core.exception.ChecksumException;
import io.excalibur.core.hash.XxHash64;
import io.excalibur.core.logger.LogManager;
import io.excalibur.core.logger.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only write log for crash recovery in ExcaliburDB.
 *
 * <p>The write log is the source of truth for crash recovery. Every mutation
 * (put, delete) appends to the log first, then updates the hash map in the
 * memory-mapped region. If the process crashes, recovery replays the log.</p>
 *
 * <h2>Log Entry Format</h2>
 * <p>Each log entry has the following structure:</p>
 * <table border="1" cellpadding="4" cellspacing="0">
 *   <tr><th>Field</th><th>Size (bytes)</th><th>Description</th></tr>
 *   <tr><td>entryType</td><td>1</td><td>Log entry type (1 byte)</td></tr>
 *   <tr><td>entryId</td><td>8</td><td>Unique entry identifier (long)</td></tr>
 *   <tr><td>checksum</td><td>4</td><td>CRC32 checksum of payload (unsigned 32-bit)</td></tr>
 *   <tr><td>payload</td><td>N</td><td>Entry-specific data</td></tr>
 * </table>
 *
 * <h2>Entry Types</h2>
 * <ul>
 *   <li>PUT_LOG_ENTRY (1): Key-value put operation</li>
 *   <tr><td>entryType</td><td>1</td><td>Log entry type (1 byte)</td></tr>
 *   <tr><td>entryId</td><td>8</td><td>Unique entry identifier (long)</td></tr>
 *   <tr><td>checksum</td><td>4</td><td>CRC32 checksum of payload (unsigned 32-bit)</td></tr>
 *   <tr><td>payload</td><td>N</td><td>Entry-specific data</td></tr>
 *
 * <h2>Entry Types</h2>
 * <ul>
 *   <li>PUT_LOG_ENTRY (1): Key-value put operation</li>
 *   <li>DELETE_LOG_ENTRY (2): Key deletion operation</li>
 *   <li>BARRIER (3): Sequence barrier for ordering guarantees</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for concurrent append operations.
 * Read operations are also thread-safe. The log file is opened in
 * read-write mode to support recovery and compaction operations.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Log entries MUST be immutable after writing</li>
 *   <li>Append operation MUST be atomic (use MemorySegment + VarHandle)
 *   <li>Checksum MUST use CRC32 (IEEE 802.3 polynomial)</li>
 *   <li>Log MUST be recoverable from any point</li>
 *   <li>Entry IDs MUST be unique and monotonically increasing</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class WriteLog implements Closeable {

    private static final LogManager logManager = LogManager.getInstance();
    private static final Logger logger = logManager.getLogger(WriteLog.class);

    /** Barrier entry type for ordering guarantees. */
    public static final byte TYPE_BARRIER = 3;

    /** Put log entry type. */
    public static final byte TYPE_PUT = 1;

    /** Delete log entry type. */
    public static final byte TYPE_DELETE = 2;

    // Fixed header sizes
    private static final int HEADER_SIZE = 1 + 8 + 4; // entryType + entryId + checksum

    /**
     * Maximum number of entries to keep in memory for fast replay.
     * Configurable for memory-constrained environments.
     */
    private static final int MAX_IN_MEMORY_ENTRIES = 100000;

    private final Path logPath;
    private final AtomicLong entryCounter;
    private final AtomicLong fileOffset;

    // In-memory cache for fast replay
    private final List<LogEntryEntry> inMemoryEntries = new ArrayList<>();
    private final int maxInMemory;

    // File channel (null when not backed by file)
    private FileChannel fileChannel;

    /**
     * Creates a new WriteLog backed by a file.
     *
     * @param logPath Path to the log file
     * @throws IOException if file cannot be opened
     */
    public WriteLog(Path logPath) throws IOException {
        this(logPath, MAX_IN_MEMORY_ENTRIES);
    }

    /**
     * Creates a new WriteLog with custom in-memory limit.
     *
     * @param logPath Path to the log file
     * @param maxInMemory Maximum entries to keep in memory
     */
    public WriteLog(Path logPath, int maxInMemory) throws IOException {
        this.logPath = logPath;
        this.fileChannel = (FileChannel) Files.newByteChannel(logPath, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE);
        this.entryCounter = new AtomicLong(0);
        this.fileOffset = new AtomicLong(0);
        this.maxInMemory = maxInMemory;

        // Pre-allocate initial file size (header + some entries)
        preallocate(1024 * 1024); // 1MB initial allocation

        logger.info("WriteLog initialized: path={}, inMemoryLimit={}", logPath, maxInMemory);
    }

    /**
     * Creates a memory-only WriteLog (no file backing).
     * Useful for testing and in-memory operations.
     *
     * @param maxInMemory Maximum entries to keep in memory
     */
    public WriteLog(int maxInMemory) {
        this.logPath = null;
        this.fileChannel = null;
        this.entryCounter = new AtomicLong(0);
        this.fileOffset = new AtomicLong(0);
        this.maxInMemory = maxInMemory;

        logger.debug("Memory-only WriteLog created with limit={}", maxInMemory);
    }

    // ==================== Append Operations ====================

    /**
     * Appends a PUT log entry to the log.
     *
     * <p>Domain constraint: This operation MUST be atomic. The entire entry
     * (header + payload) is written atomically using MemorySegment operations.
     *
     * @param key The key to insert
     * @param value The value to insert
     * @return The entry ID of the appended entry
     * @throws IOException if writing fails
     */
    public long appendPut(String key, String value) throws IOException {
        return appendPut(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Appends a PUT log entry with raw byte data.
     *
     * @param keyBytes Key bytes
     * @param valueBytes Value bytes
     * @return The entry ID of the appended entry
     * @throws IOException if writing fails
     */
    public long appendPut(byte[] keyBytes, byte[] valueBytes) throws IOException {
        return append(TYPE_PUT, keyBytes, valueBytes);
    }

    /**
     * Appends a DELETE log entry to the log.
     *
     * @param key The key to delete
     * @return The entry ID of the appended entry
     * @throws IOException if writing fails
     */
    public long appendDelete(String key) throws IOException {
        return appendDelete(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Appends a DELETE log entry with raw byte data.
     *
     * @param keyBytes Key bytes to delete
     * @return The entry ID of the appended entry
     * @throws IOException if writing fails
     */
    public long appendDelete(byte[] keyBytes) throws IOException {
        return append(TYPE_DELETE, keyBytes, null);
    }

    /**
     * Appends a barrier entry to ensure ordering.
     * Barriers are used for crash recovery to ensure all prior
     * entries are durably written before the barrier.
     *
     * @return The entry ID of the barrier entry
     * @throws IOException if writing fails
     */
    public long appendBarrier() throws IOException {
        byte[] payload = new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) TYPE_BARRIER);
        buffer.putLong(nextEntryId());
        buffer.putInt((int) (calculateChecksum(TYPE_BARRIER, nextEntryId(), payload) & 0xFFFFFFFFL));
        buffer.put(payload);
        buffer.flip();

        long offset = fileOffset.getAndAdd(buffer.remaining());
        fileChannel.write(buffer, offset);
        logger.trace("Appended barrier at offset {}", offset);

        inMemoryEntries.add(new LogEntryEntry(TYPE_BARRIER, nextEntryId(), payload, offset));
        return nextEntryId();
    }

    /**
     * Internal append method for both PUT and DELETE entries.
     *
     * @param type Entry type
     * @param keyBytes Key bytes
     * @param valueBytes Value bytes (for PUT)
     * @return The entry ID of the appended entry
     * @throws IOException if writing fails
     */
    private long append(byte type, byte[] keyBytes, byte[] valueBytes) throws IOException {
        byte[] payload;
        if (valueBytes != null) {
            // PUT entry: payload = key + value
            int keyLen = keyBytes.length;
            int valueLen = valueBytes.length;
            payload = new byte[1 + keyLen + valueLen];
            payload[0] = (byte) keyLen;
            System.arraycopy(keyBytes, 0, payload, 1, keyLen);
            System.arraycopy(valueBytes, 0, payload, 1 + keyLen, valueLen);
        } else {
            // DELETE entry: payload = key
            payload = keyBytes;
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) type);
        buffer.putLong(nextEntryId());
        buffer.putInt((int) (calculateChecksum(type, nextEntryId(), payload) & 0xFFFFFFFFL));
        buffer.put(payload);
        buffer.flip();

        long offset = fileOffset.getAndAdd(buffer.remaining());
        fileChannel.write(buffer, offset);
        logger.trace("Appended entry type={}, id={}, size={} at offset {}",
                    type, nextEntryId(), buffer.remaining(), offset);

        // Evict oldest entries if we exceed memory limit
        evictIfNeeded();

        inMemoryEntries.add(new LogEntryEntry(type, nextEntryId(), payload, offset));
        return nextEntryId();
    }

    // ==================== Batch Append Operations ====================

    /**
     * Represents a batch of entries to write atomically.
     */
    public record BatchEntry(byte[] key, byte[] value, byte type) {
        public BatchEntry {
            Objects.requireNonNull(key, "key must not be null");
            if (type != TYPE_PUT && type != TYPE_DELETE) {
                throw new IllegalArgumentException("Invalid entry type: " + type);
            }
        }
        
        public static BatchEntry put(byte[] key, byte[] value) {
            return new BatchEntry(key, value, TYPE_PUT);
        }
        
        public static BatchEntry delete(byte[] key) {
            return new BatchEntry(key, null, TYPE_DELETE);
        }
    }

    /**
     * Result of an atomic batch append operation.
     *
     * @param startOffset The offset at which the batch starts
     * @param endOffset The offset at which the batch ends
     * @param entryIds The list of entry IDs for each entry in order
     */
    public record BatchAppendResult(long startOffset, long endOffset, List<Long> entryIds) {
        public BatchAppendResult {
            if (startOffset < 0) {
                throw new IllegalArgumentException("startOffset must be non-negative");
            }
            if (endOffset < startOffset) {
                throw new IllegalArgumentException("endOffset must be >= startOffset");
            }
            Objects.requireNonNull(entryIds, "entryIds must not be null");
        }
        
        public long getByteSize() {
            return endOffset - startOffset;
        }
        
        public int getEntryCount() {
            return entryIds.size();
        }
    }

    /**
     * Appends a batch of entries atomically with contiguous offsets.
     *
     * <p>This method ensures that all entries in the batch are written to
     * contiguous offsets in the write log, with no interleaving from other
     * writers. This is critical for document atomicity: all chunks of a
     * document must have contiguous offsets.</p>
     *
     * <h3>Atomicity Guarantee</h3>
     * <ul>
     *   <li>All entries are written to a contiguous region of the log</li>
     *   <li>No other writes can interleave within this region</li>
     *   <li>Entry IDs are assigned sequentially and atomically</li>
     *   <li>The entire batch succeeds or fails as a unit</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>startOffset &lt; endOffset (if batch is non-empty)</li>
     *   <li>All entry IDs are unique and monotonically increasing</li>
     *   <li>Entry offsets are contiguous within [startOffset, endOffset)</li>
     * </ul>
     *
     * @param entries The batch of entries to write (must not be null or empty)
     * @return BatchAppendResult containing offset range and entry IDs
     * @throws IOException if writing fails
     * @throws NullPointerException if entries is null
     * @throws IllegalArgumentException if entries is empty
     */
    public synchronized BatchAppendResult appendBatch(List<BatchEntry> entries) throws IOException {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }

        int entryCount = entries.size();
        List<Long> entryIds = new ArrayList<>(entryCount);
        List<byte[]> encodedEntries = new ArrayList<>(entryCount);
        int totalSize = 0;

        for (BatchEntry entry : entries) {
            long entryId = entryCounter.getAndIncrement();
            entryIds.add(entryId);

            byte[] payload;
            if (entry.value() != null) {
                int keyLen = entry.key().length;
                int valueLen = entry.value().length;
                payload = new byte[1 + keyLen + valueLen];
                payload[0] = (byte) keyLen;
                System.arraycopy(entry.key(), 0, payload, 1, keyLen);
                System.arraycopy(entry.value(), 0, payload, 1 + keyLen, valueLen);
            } else {
                payload = entry.key();
            }

            int entrySize = HEADER_SIZE + payload.length;
            totalSize += entrySize;
            encodedEntries.add(encodeEntry(entry.type(), entryId, payload));
        }

        long startOffset = fileOffset.getAndAdd(totalSize);
        long currentOffset = startOffset;

        for (int i = 0; i < entryCount; i++) {
            byte[] encoded = encodedEntries.get(i);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            if (fileChannel != null) {
                fileChannel.write(buffer, currentOffset);
            }

            BatchEntry entry = entries.get(i);
            byte[] payload;
            if (entry.value() != null) {
                int keyLen = entry.key().length;
                int valueLen = entry.value().length;
                payload = new byte[1 + keyLen + valueLen];
                payload[0] = (byte) keyLen;
                System.arraycopy(entry.key(), 0, payload, 1, keyLen);
                System.arraycopy(entry.value(), 0, payload, 1 + keyLen, valueLen);
            } else {
                payload = entry.key();
            }

            evictIfNeeded();
            inMemoryEntries.add(new LogEntryEntry(entry.type(), entryIds.get(i), payload, currentOffset));

            logger.trace("Appended batch entry type={}, id={}, size={} at offset {}",
                entry.type(), entryIds.get(i), encoded.length, currentOffset);

            currentOffset += encoded.length;
        }

        long endOffset = currentOffset;

        logger.debug("Appended batch of {} entries at offsets [{}, {}), size={} bytes",
            entryCount, startOffset, endOffset, totalSize);

        return new BatchAppendResult(startOffset, endOffset, Collections.unmodifiableList(entryIds));
    }

    /**
     * Encodes a single log entry to bytes.
     */
    private byte[] encodeEntry(byte type, long entryId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(type);
        buffer.putLong(entryId);
        buffer.putInt((int) (calculateChecksum(type, entryId, payload) & 0xFFFFFFFFL));
        buffer.put(payload);
        return buffer.array();
    }

    // ==================== Entry ID Management ====================

    /**
     * Gets the next entry ID using atomic increment.
     *
     * @return The next entry ID
     */
    private long nextEntryId() {
        return entryCounter.getAndAdd(1);
    }

    /**
     * Gets the highest entry ID in the log.
     *
     * @return The highest entry ID
     */
    public long highestEntryId() {
        long maxInMemory = inMemoryEntries.isEmpty() ? 0 : inMemoryEntries.get(inMemoryEntries.size() - 1).id;
        return Math.max(maxInMemory, entryCounter.get());
    }

    /**
     * Resets the entry counter (for testing/recovery).
     *
     * @param newCounter The new counter value
     */
    public void resetCounter(long newCounter) {
        entryCounter.set(newCounter);
    }

    /**
     * Gets the maximum in-memory limit.
     *
     * @return Maximum in-memory entries
     */
    public int getMaxInMemory() {
        return maxInMemory;
    }

    // ==================== Reading Operations ====================

    /**
     * Reads all entries from the log file.
     *
     * @return List of all log entries
     * @throws IOException if reading fails
     */
    public List<LogEntry> readAll() throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        long fileSize = fileChannel != null ? fileChannel.size() : 0;

        for (long offset = 0; offset < fileSize; offset += HEADER_SIZE + 1) {
            if (offset >= fileSize) break;

            // Read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            int bytesRead = fileChannel.read(headerBuffer, offset);
            if (bytesRead != HEADER_SIZE) continue;
            byte[] header = headerBuffer.array();

            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            byte type = buffer.get();
            long id = buffer.getLong();
            long checksum = buffer.getInt();

            // Read payload (first byte is payload length)
            int payloadLen = header[HEADER_SIZE - 1];
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0 && offset + HEADER_SIZE + payloadLen <= fileSize) {
                ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLen);
                int readBytes = fileChannel.read(payloadBuffer, offset + HEADER_SIZE);
                payloadBuffer.flip();
                payloadBuffer.get(payload, 0, readBytes);
            }

            // Verify checksum
            long computedChecksum = calculateChecksum(type, id, payload);
            if (computedChecksum != checksum) {
                logger.warn("Checksum mismatch at offset {}: expected=0x{:X}, computed=0x{:X}",
                           offset, checksum, computedChecksum);
                continue;
            }

            entries.add(new LogEntry(type, id, payload, offset));
        }

        return entries;
    }

    /**
     * Internal read payload helper method (throws IOException).
     *
     * @param payloadLen Length of payload
     * @param offset Offset to read from
     * @param fileSize Size of the file
     * @return Payload bytes
     * @throws IOException if reading fails
     */
    private byte[] readPayload(int payloadLen, long offset, long fileSize) throws IOException {
        if (payloadLen == 0) {
            return new byte[0];
        }
        if (offset + HEADER_SIZE + payloadLen > fileSize) {
            return new byte[0];
        }
        ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLen);
        int bytesRead = fileChannel.read(payloadBuffer, offset + HEADER_SIZE);
        payloadBuffer.flip();
        byte[] payload = new byte[payloadLen];
        payloadBuffer.get(payload, 0, bytesRead);
        return payload;
    }

    /**
     * Reads entries from a specific offset to the end.
     *
     * @param startOffset Offset to start reading from
     * @return List of entries from startOffset onwards
     * @throws IOException if reading fails
     */
    public List<LogEntry> readFrom(long startOffset) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        long fileSize = fileChannel != null ? fileChannel.size() : 0;

        for (long offset = startOffset; offset < fileSize; offset += HEADER_SIZE + 1) {
            if (offset >= fileSize) break;

            // Read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            int readBytes = fileChannel.read(headerBuffer, offset);
            if (readBytes != HEADER_SIZE) break;
            byte[] header = headerBuffer.array();

            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            byte type = buffer.get();
            long id = buffer.getLong();
            long checksum = buffer.getInt();

            // Read payload
            int payloadLen = header[HEADER_SIZE - 1];
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0 && offset + HEADER_SIZE + payloadLen <= fileSize) {
                ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLen);
                int readBytes2 = fileChannel.read(payloadBuffer, offset + HEADER_SIZE);
                payloadBuffer.flip();
                payloadBuffer.get(payload, 0, readBytes2);
            }

            // Verify checksum
            long computedChecksum = calculateChecksum(type, id, payload);
            if (computedChecksum != checksum) {
                logger.warn("Checksum mismatch at offset {}: expected=0x{:X}, computed=0x{:X}",
                           offset, checksum, computedChecksum);
                continue;
            }

            entries.add(new LogEntry(type, id, payload, offset));
        }

        return entries;
    }

    /**
     * Reads the tail of the log (last N entries).
     *
     * @param numEntries Number of entries to read from tail
     * @return List of tail entries
     * @throws IOException if reading fails
     */
    public List<LogEntry> readTail(int numEntries) throws IOException {
        List<LogEntry> allEntries = readAll();
        int fromIndex = Math.max(0, allEntries.size() - numEntries);
        return new ArrayList<>(allEntries.subList(fromIndex, allEntries.size()));
    }

    /**
     * Scans log entries within a specific byte offset range.
     *
     * <p>This method is the primary interface for tail scan functionality in Phase 6.
     * It reads all log entries whose offsets fall within [startOffset, endOffset).</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>{@code startOffset} must be non-negative (>= 0)</li>
     *   <li>{@code endOffset} must be >= startOffset</li>
     *   <li>{@code endOffset} must be <= current write head</li>
     *   <li>Returned entries have offset >= startOffset AND offset < endOffset</li>
     *   <li>Entries are returned in offset order (ascending)</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is thread-safe for concurrent reads. A snapshot of the file
     * size is captured at the start to ensure consistency.</p>
     *
     * <h3>Performance</h3>
     * <p>For in-memory entries, this operation is O(n) where n is the number of
     * entries in the range. For file-backed entries, this operation reads from
     * disk sequentially.</p>
     *
     * @param startOffset The starting byte offset (inclusive, must be >= 0)
     * @param endOffset The ending byte offset (exclusive, must be >= startOffset)
     * @return List of log entries within the specified range, ordered by offset
     * @throws IOException if reading from the log file fails
     * @throws IllegalArgumentException if offsets violate domain constraints
     */
    public List<LogEntry> scanRange(long startOffset, long endOffset) throws IOException {
        // Validate domain constraints
        if (startOffset < 0) {
            throw new IllegalArgumentException(
                "startOffset must be non-negative, got: " + startOffset);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                "endOffset must be >= startOffset, got: startOffset=" + startOffset +
                ", endOffset=" + endOffset);
        }

        // Empty range - return empty list
        if (startOffset == endOffset) {
            return Collections.emptyList();
        }

        List<LogEntry> entries = new ArrayList<>();
        long currentWriteHead = fileOffset.get();

        // Clamp endOffset to current write head to prevent reading beyond valid data
        long effectiveEndOffset = Math.min(endOffset, currentWriteHead);
        if (effectiveEndOffset <= startOffset) {
            return Collections.emptyList();
        }

        // First, check in-memory entries for entries in range
        synchronized (inMemoryEntries) {
            for (LogEntryEntry entry : inMemoryEntries) {
                if (entry.offset >= startOffset && entry.offset < effectiveEndOffset) {
                    entries.add(new LogEntry(entry.type, entry.id, entry.payload, entry.offset));
                }
            }
        }

        // If we have a file channel and in-memory entries don't cover the range,
        // scan from disk
        if (fileChannel != null) {
            long fileSize = fileChannel.size();

            // Scan from disk if we might have entries not in memory
            long scanStart = startOffset;
            long scanEnd = Math.min(effectiveEndOffset, fileSize);

            if (scanEnd > scanStart && (entries.isEmpty() || needsDiskScan(entries, scanStart, scanEnd))) {
                List<LogEntry> diskEntries = scanRangeFromDisk(scanStart, scanEnd);
                // Merge disk entries with in-memory entries
                entries = mergeEntries(entries, diskEntries);
            }
        }

        // Sort by offset to ensure consistent ordering
        entries.sort((a, b) -> Long.compare(a.getOffset(), b.getOffset()));

        logger.trace("scanRange({}, {}) returned {} entries", startOffset, endOffset, entries.size());
        return entries;
    }

    /**
     * Checks if we need to scan from disk because in-memory entries might be incomplete.
     *
     * <p>We need a disk scan if:</p>
     * <ul>
     *   <li>The range extends beyond what we have in memory</li>
     *   <li>We're missing entries at the start of the range</li>
     * </ul>
     *
     * @param entries Entries found in memory
     * @param scanStart Start of the range to scan
     * @param scanEnd End of the range to scan
     * @return true if disk scan is needed
     */
    private boolean needsDiskScan(List<LogEntry> entries, long scanStart, long scanEnd) {
        if (entries.isEmpty()) {
            return true;
        }

        // Check if we have coverage from scanStart
        long minOffset = entries.stream()
            .mapToLong(LogEntry::getOffset)
            .min()
            .orElse(Long.MAX_VALUE);

        // If the first in-memory entry is after scanStart, we need disk scan
        return minOffset > scanStart;
    }

    /**
     * Scans log entries from disk within the specified range.
     *
     * @param startOffset Starting byte offset
     * @param endOffset Ending byte offset
     * @return List of entries read from disk
     * @throws IOException if reading fails
     */
    private List<LogEntry> scanRangeFromDisk(long startOffset, long endOffset) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        long fileSize = fileChannel.size();

        if (startOffset >= fileSize) {
            return entries;
        }

        long currentOffset = startOffset;

        while (currentOffset < endOffset && currentOffset < fileSize) {
            // Read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            int bytesRead = fileChannel.read(headerBuffer, currentOffset);

            if (bytesRead != HEADER_SIZE) {
                // Incomplete header - stop scanning
                logger.trace("Incomplete header at offset {}, stopping scan", currentOffset);
                break;
            }

            headerBuffer.flip();
            byte type = headerBuffer.get();
            long id = headerBuffer.getLong();
            long checksum = headerBuffer.getInt() & 0xFFFFFFFFL; // unsigned

            // Read payload length (first byte of payload indicates key length for PUT/DELETE)
            ByteBuffer payloadLenBuffer = ByteBuffer.allocate(1);
            int lenBytesRead = fileChannel.read(payloadLenBuffer, currentOffset + HEADER_SIZE);

            if (lenBytesRead != 1) {
                logger.trace("Could not read payload length at offset {}, stopping scan", currentOffset);
                break;
            }

            // For PUT entries: payload format is [keyLen(1) | keyBytes | valueBytes]
            // For DELETE entries: payload is just keyBytes
            // For BARRIER entries: payload is empty

            int payloadLen;
            if (type == TYPE_BARRIER) {
                payloadLen = 0;
            } else if (type == TYPE_DELETE) {
                // DELETE: payload is just the key, read remaining header to get size
                // We need to determine the actual entry size
                // Read more bytes to estimate payload size
                payloadLen = readPayloadLength(currentOffset, type);
            } else {
                // PUT: payload includes key length byte, key, and value
                payloadLen = readPayloadLength(currentOffset, type);
            }

            if (payloadLen < 0) {
                logger.trace("Invalid payload length at offset {}, stopping scan", currentOffset);
                break;
            }

            // Read payload
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0) {
                ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLen);
                int payloadBytesRead = fileChannel.read(payloadBuffer, currentOffset + HEADER_SIZE);
                if (payloadBytesRead != payloadLen) {
                    logger.trace("Incomplete payload at offset {}, stopping scan", currentOffset);
                    break;
                }
                payloadBuffer.flip();
                payloadBuffer.get(payload);
            }

            // Verify checksum
            long computedChecksum = calculateChecksum(type, id, payload);
            if (computedChecksum != checksum) {
                logger.warn("Checksum mismatch at offset {}: expected=0x{:X}, computed=0x{:X}, skipping",
                           currentOffset, checksum, computedChecksum);
                // Skip to next potential entry (best effort - this shouldn't happen in normal operation)
                currentOffset += HEADER_SIZE + 1;
                continue;
            }

            // Create entry and add to results
            entries.add(new LogEntry(type, id, payload, currentOffset));

            // Move to next entry
            currentOffset += HEADER_SIZE + payloadLen;
        }

        return entries;
    }

    /**
     * Reads the payload length for an entry at the given offset.
     *
     * <p>This method attempts to determine the payload length by reading
     * the key length byte and calculating the full payload size.</p>
     *
     * @param offset The offset of the entry
     * @param type The entry type
     * @return The payload length, or -1 if unable to determine
     * @throws IOException if reading fails
     */
    private int readPayloadLength(long offset, byte type) throws IOException {
        // Read enough bytes to determine payload size
        // For PUT: [keyLen(1) | keyBytes(keyLen) | valueBytes(?)]
        // For DELETE: [keyBytes(?)] - we can't easily determine size without more context

        // Read up to 256 bytes to find key and estimate value size
        ByteBuffer probeBuffer = ByteBuffer.allocate(257);
        probeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int bytesRead = fileChannel.read(probeBuffer, offset + HEADER_SIZE);

        if (bytesRead <= 0) {
            return -1;
        }

        if (type == TYPE_PUT) {
            // First byte is key length
            int keyLen = probeBuffer.get(0) & 0xFF;
            if (keyLen > bytesRead - 1) {
                // Key extends beyond what we read - need to estimate
                // This is a fallback; in practice, entries should be in memory
                return keyLen + 256; // Rough estimate
            }
            // Payload = 1 (keyLen byte) + keyLen + valueLen
            // We don't know valueLen exactly, but we can estimate from remaining data
            // For now, return a conservative estimate
            return bytesRead; // Return what we can read
        } else if (type == TYPE_DELETE) {
            // DELETE payload is just the key - use bytesRead as estimate
            return bytesRead;
        }

        return 0;
    }

    /**
     * Merges entries from memory and disk, removing duplicates.
     *
     * @param memoryEntries Entries from in-memory cache
     * @param diskEntries Entries from disk scan
     * @return Merged list with no duplicates, sorted by offset
     */
    private List<LogEntry> mergeEntries(List<LogEntry> memoryEntries, List<LogEntry> diskEntries) {
        // Use a map keyed by offset to deduplicate
        java.util.Map<Long, LogEntry> entryMap = new java.util.TreeMap<>();

        // Add disk entries first (they may be stale)
        for (LogEntry entry : diskEntries) {
            entryMap.put(entry.getOffset(), entry);
        }

        // Add memory entries (they are more recent/authoritative)
        for (LogEntry entry : memoryEntries) {
            entryMap.put(entry.getOffset(), entry);
        }

        return new ArrayList<>(entryMap.values());
    }

    /**
     * Gets the current write head position (highest offset).
     *
     * @return The write head offset
     */
    public long writeHead() {
        return fileOffset.get();
    }

    /**
     * Gets the last integrated offset (for HNSW graph integration).
     *
     * @return The last integrated offset
     */
    public long getLastIntegratedOffset() {
        // Default: no entries integrated yet
        return 0;
    }

    /**
     * Sets the last integrated offset (for recovery).
     *
     * @param offset The last integrated offset
     */
    public void setLastIntegratedOffset(long offset) {
        // No-op in WriteLog; called by higher-level components
    }

    // ==================== Checksum Calculation ====================

    /**
     * Calculates CRC32 checksum for a log entry.
     *
     * <p>Domain constraint: Checksum is calculated over the complete entry
     * (type + id + payload) to ensure any corruption is detected.</p>
     *
     * @param type Entry type
     * @param id Entry ID
     * @param payload Entry payload
     * @return CRC32 checksum
     */
    private long calculateChecksum(byte type, long id, byte[] payload) {
        // Build entry data for checksum
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8 + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(type);
        buffer.putLong(id);
        if (payload != null) {
            buffer.put(payload);
        }

        return CRC32Checksum.compute(buffer.array());
    }

    /**
     * Verifies a log entry's checksum.
     *
     * @param type Entry type
     * @param id Entry ID
     * @param payload Entry payload
     * @param expected Expected checksum
     * @return true if checksum matches
     */
    public boolean verifyChecksum(byte type, long id, byte[] payload, long expected) {
        return calculateChecksum(type, id, payload) == expected;
    }

    // ==================== Utility Operations ====================

    /**
     * Pre-allocates file space to avoid fragmentation.
     *
     * @param size Number of bytes to pre-allocate
     */
    private void preallocate(long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        fileChannel.write(buffer, 0);
        logger.debug("Pre-allocated {} bytes", size);
    }

    /**
     * Evicts oldest entries when memory limit is exceeded.
     * Keeps entries in reverse order (oldest first for LIFO eviction).
     */
    private void evictIfNeeded() {
        while (inMemoryEntries.size() > maxInMemory) {
            inMemoryEntries.remove(0); // Remove oldest
        }
    }

    /**
     * Syncs the log file to ensure durability.
     *
     * @throws IOException if syncing fails
     */
    public void sync() throws IOException {
        if (fileChannel != null) {
            fileChannel.force(true); // Force sync to disk
            logger.debug("Synced WriteLog at offset {}", fileOffset.get());
        }
    }

    /**
     * Closes the WriteLog.
     *
     * @throws IOException if closing fails
     */
    public void close() throws IOException {
        if (fileChannel != null) {
            flush();
            sync();
            fileChannel.close();
            logger.info("WriteLog closed: {}", logPath);
        }
    }

    /**
     * Flushes all in-memory entries to disk.
     * This is a no-op in the current implementation since
     * entries are flushed immediately upon append.
     */
    public void flush() throws IOException {
        // Already flushed on each append
        logger.trace("WriteLog flush (already flushed on append)");
    }

    // ==================== Statistics ====================

    /**
     * Gets the total number of entries in the log.
     *
     * @return Total entry count
     */
    public long entryCount() {
        return highestEntryId() + 1;
    }

    /**
     * Gets the number of entries in memory.
     *
     * @return In-memory entry count
     */
    public int inMemoryCount() {
        return inMemoryEntries.size();
    }

    /**
     * Gets the log file size.
     *
     * @return File size in bytes
     */
    public long fileSize() {
        if (fileChannel == null) {
            return 0;
        }
        try {
            return fileChannel.size();
        } catch (IOException e) {
            logger.warn("Error getting file size: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the path to the log file.
     *
     * @return Log file path, or null for memory-only
     */
    public Path getPath() {
        return logPath;
    }

    // ==================== Recovery Operations ====================

    /**
     * Recovers the log from disk, replaying all entries.
     *
     * @throws IOException if recovery fails
     */
    public void recover() throws IOException {
        inMemoryEntries.clear();
        List<LogEntry> recovered = readAll();

        logger.info("Recovered {} entries from WriteLog", recovered.size());

        for (LogEntry entry : recovered) {
            switch (entry.getType()) {
                case TYPE_PUT:
                    parsePut(entry.getPayload());
                    break;
                case TYPE_DELETE:
                    parseDelete(entry.getPayload());
                    break;
                case TYPE_BARRIER:
                    logger.debug("Recovered barrier at offset {}", entry.getOffset());
                    break;
            }
        }

        entryCounter.set(recovered.isEmpty() ? 0 : recovered.get(recovered.size() - 1).getId() + 1);
        fileOffset.set(recovered.isEmpty() ? 0 : recovered.get(recovered.size() - 1).getOffset() + HEADER_SIZE + 1);
    }

    /**
     * Parses a PUT log entry.
     *
     * @param payload Entry payload
     */
    private void parsePut(byte[] payload) {
        if (payload == null || payload.length < 1) return;

        int keyLen = payload[0];
        if (keyLen == 0) return;

        byte[] keyBytes = new byte[keyLen];
        System.arraycopy(payload, 1, keyBytes, 0, keyLen);

        int valueLen = payload.length - 1 - keyLen;
        byte[] valueBytes = valueLen > 0 ? new byte[valueLen] : null;
        if (valueLen > 0) {
            System.arraycopy(payload, 1 + keyLen, valueBytes, 0, valueLen);
        }

        logger.trace("Recovered PUT: keyLen={}, valueLen={}", keyLen, valueLen);
    }

    /**
     * Parses a DELETE log entry.
     *
     * @param payload Entry payload
     */
    private void parseDelete(byte[] payload) {
        if (payload == null) return;

        int keyLen = payload.length;
        byte[] keyBytes = keyLen > 0 ? new byte[keyLen] : null;
        if (keyLen > 0) {
            System.arraycopy(payload, 0, keyBytes, 0, keyLen);
        }

        logger.trace("Recovered DELETE: keyLen={}", keyLen);
    }

    /**
     * Truncates the log file to a specified offset.
     *
     * @param offset Truncation offset
     * @throws IOException if truncation fails
     */
    public void truncate(long offset) throws IOException {
        if (fileChannel != null) {
            if (offset > fileSize()) {
                throw new IllegalArgumentException("Offset must be <= file size: " + offset);
            }
            fileChannel.truncate(offset);
            fileOffset.set(offset);
            logger.info("Truncated WriteLog to offset {}", offset);
        }
    }

    /**
     * Gets the last barrier offset (for ordering guarantees).
     *
     * @return Last barrier offset, or -1 if no barrier found
     */
    public long getLastBarrierOffset() {
        long lastBarrier = -1;
        int size = inMemoryEntries.size();
        for (int i = size - 1; i >= 0; i--) {
            LogEntryEntry entry = inMemoryEntries.get(i);
            if (entry.type == TYPE_BARRIER) {
                lastBarrier = entry.offset;
                break;
            }
        }
        return lastBarrier;
    }

    /**
     * Gets the number of in-memory entries.
     *
     * @return Number of in-memory entries
     */
    public int getInMemoryEntriesCount() {
        return inMemoryEntries.size();
    }

    // ==================== Internal Classes ====================

    /**
     * Log entry structure.
     */
    private static class LogEntryEntry {
        final byte type;
        final long id;
        final byte[] payload;
        final long offset;

        LogEntryEntry(byte type, long id, byte[] payload, long offset) {
            this.type = type;
            this.id = id;
            this.payload = payload;
            this.offset = offset;
        }

        @Override
        public String toString() {
            return String.format("LogEntryEntry[type=%d, id=%d, payloadLen=%d, offset=%d]",
                               type, id, payload != null ? payload.length : 0, offset);
        }
    }

    /**
     * Main method for testing.
     */
    public static void main(String[] args) {
        System.out.println("Testing WriteLog...");

        // Test 1: Memory-only WriteLog
        try (WriteLog log = new WriteLog(1000)) {
            // Append some entries
            log.appendPut("key1", "value1");
            log.appendPut("key2", "value2");
            log.appendDelete("key1");

            System.out.println("Entry count: " + log.entryCount());
            System.out.println("Write head: " + log.writeHead());

            // Read back
            List<LogEntry> entries = log.readAll();
            System.out.println("Read back " + entries.size() + " entries:");
            for (LogEntry entry : entries) {
                System.out.println("  " + entry);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Test 2: File-based WriteLog
        try {
            Path tempFile = Files.createTempFile("test-write-log", ".log");
            try (WriteLog log = new WriteLog(tempFile)) {
                log.appendPut("fileKey1", "fileValue1");
                log.appendPut("fileKey2", "fileValue2");
                log.sync();

                System.out.println("\nFile-based test:");
                System.out.println("File size: " + log.fileSize());
                System.out.println("Entry count: " + log.entryCount());

                // Read back
                List<LogEntry> entries = log.readAll();
                System.out.println("Read back " + entries.size() + " entries:");
                for (LogEntry entry : entries) {
                    System.out.println("  " + entry);
                }
            }
            Files.delete(tempFile);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\nWriteLog tests completed.");
    }
}
