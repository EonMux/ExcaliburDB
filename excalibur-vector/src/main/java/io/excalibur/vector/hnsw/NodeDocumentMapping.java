package io.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Maps HNSW node IDs to document IDs for document atomicity filtering.
 *
 * <p>This class provides the critical mapping between internal HNSW graph node IDs
 * (which are long integers assigned sequentially) and actual document IDs (which are
 * strings like "doc1", "source1:file1"). This mapping is essential for the
 * {@code DocumentAtomicityFilter} to correctly identify which document each query
 * result belongs to.</p>
 *
 * <h2>Purpose</h2>
 * <p>During ingestion, vectors are inserted into the HNSW graph with:</p>
 * <ul>
 *   <li>An auto-generated numeric node ID (e.g., 0, 1, 2, ...)</li>
 *   <li>The actual document ID from the source (e.g., "doc-atomic-1")</li>
 * </ul>
 *
 * <p>During queries, the HNSW search returns node IDs. This mapping allows
 * converting node IDs back to document IDs for visibility filtering.</p>
 *
 * <h2>Mapping Format</h2>
 * <p>The mapping is persisted to disk using a simple format:</p>
 * <pre>
 * +---------------------------+
 * | Header (16 bytes)         |
 * | - magic (8B): 0x4E4F4445 |
 * | - entryCount (8B)         |
 * +---------------------------+
 * | Entry 1:                  |
 * | - nodeId (8B)             |
 * | - docIdLen (4B)           |
 * | - docId (UTF-8 bytes)     |
 * +---------------------------+
 * | Entry 2...                |
 * +---------------------------+
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Read operations are lock-free (ConcurrentHashMap)</li>
 *   <li>Write operations use read-write locks</li>
 *   <li>Persistence operations are synchronized</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Node IDs are non-negative long integers</li>
 *   <li>Document IDs are non-null, non-empty strings</li>
 *   <li>Each node ID maps to exactly one document ID</li>
 *   <li>Multiple nodes may map to the same document ID (chunks)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see DocumentAtomicityFilter
 * @see HnswGraph
 */
public class NodeDocumentMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeDocumentMapping.class);

    /**
     * Magic number for file format validation ("NODEMAPS").
     */
    private static final long MAGIC = 0x4E4F44454D415053L;

    /**
     * Current file format version.
     */
    private static final int VERSION = 1;

    /**
     * Header size in bytes.
     */
    private static final int HEADER_SIZE = 24;

    /**
     * Byte offset of the entry-count field within the header.
     */
    private static final int OFFSET_ENTRY_COUNT = 16;

    /**
     * Size of the fixed part of an entry: nodeId(8) + docIdLen(4).
     */
    private static final int ENTRY_HEADER_SIZE = 12;

    /**
     * Upper bound on a document ID's UTF-8 length. A truncated or corrupt file can
     * otherwise present an arbitrary length and drive an unbounded allocation.
     */
    private static final int MAX_DOCUMENT_ID_BYTES = 64 * 1024;

    /**
     * The path to the mapping file (may be null for in-memory only).
     */
    private final Path mappingPath;

    /**
     * Thread-safe map from node ID to document ID.
     */
    private final ConcurrentHashMap<Long, String> nodeToDocument;

    /**
     * Lock for coordinating persistence operations.
     */
    private final ReentrantReadWriteLock persistenceLock;

    /**
     * Flag indicating if persistence is enabled.
     */
    private final boolean persistent;

    /**
     * Creates a new in-memory NodeDocumentMapping without persistence.
     */
    public NodeDocumentMapping() {
        this.mappingPath = null;
        this.nodeToDocument = new ConcurrentHashMap<>();
        this.persistenceLock = new ReentrantReadWriteLock();
        this.persistent = false;

        LOGGER.debug("NodeDocumentMapping created (in-memory only)");
    }

    /**
     * Creates a new NodeDocumentMapping with file persistence.
     *
     * @param mappingPath The path to the mapping file
     * @throws NullPointerException if mappingPath is null
     */
    public NodeDocumentMapping(Path mappingPath) {
        this.mappingPath = Objects.requireNonNull(mappingPath, "mappingPath cannot be null");
        this.nodeToDocument = new ConcurrentHashMap<>();
        this.persistenceLock = new ReentrantReadWriteLock();
        this.persistent = true;

        LOGGER.debug("NodeDocumentMapping created with path: {}", mappingPath);
    }

    /**
     * Creates a new NodeDocumentMapping and loads from file if it exists.
     *
     * @param mappingPath The path to the mapping file
     * @param loadExisting If true, load existing mappings from file
     * @return A new NodeDocumentMapping
     * @throws IOException if loading fails
     */
    public static NodeDocumentMapping createOrLoad(Path mappingPath, boolean loadExisting) throws IOException {
        Objects.requireNonNull(mappingPath, "mappingPath cannot be null");

        NodeDocumentMapping mapping = new NodeDocumentMapping(mappingPath);

        if (loadExisting && java.nio.file.Files.exists(mappingPath)) {
            mapping.load();
            LOGGER.info("Loaded {} mappings from {}", mapping.size(), mappingPath);
        }

        return mapping;
    }

    // ==================== Mapping Operations ====================

    /**
     * Registers a mapping from node ID to document ID.
     *
     * <p>If the node ID already has a mapping, it will be updated.</p>
     *
     * @param nodeId The HNSW node ID
     * @param documentId The document ID
     * @throws IllegalArgumentException if nodeId is negative or documentId is null/empty
     */
    public void register(long nodeId, String documentId) {
        if (nodeId < 0) {
            throw new IllegalArgumentException("nodeId must be non-negative: " + nodeId);
        }
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must be non-null and non-empty");
        }

        nodeToDocument.put(nodeId, documentId);
        LOGGER.trace("Registered mapping: nodeId={} -> documentId={}", nodeId, documentId);
    }

    /**
     * Gets the document ID for a node ID.
     *
     * @param nodeId The HNSW node ID
     * @return The document ID, or null if not found
     */
    public String getDocumentId(long nodeId) {
        return nodeToDocument.get(nodeId);
    }

    /**
     * Checks if a node ID has a registered mapping.
     *
     * @param nodeId The HNSW node ID
     * @return true if mapping exists
     */
    public boolean hasMapping(long nodeId) {
        return nodeToDocument.containsKey(nodeId);
    }

    /**
     * Removes the mapping for a node ID.
     *
     * <p>This is typically called when a node is tombstoned.</p>
     *
     * @param nodeId The HNSW node ID
     * @return The previous document ID, or null if not found
     */
    public String remove(long nodeId) {
        String removed = nodeToDocument.remove(nodeId);
        if (removed != null) {
            LOGGER.trace("Removed mapping for nodeId={}", nodeId);
        }
        return removed;
    }

    /**
     * Returns all node IDs that belong to a specific document.
     *
     * <p>This is useful for finding all chunks of a document when
     * the document needs to be tombstoned.</p>
     *
     * @param documentId The document ID
     * @return Set of node IDs belonging to the document
     */
    public Set<Long> getNodesForDocument(String documentId) {
        if (documentId == null) {
            return Collections.emptySet();
        }

        return nodeToDocument.entrySet().stream()
            .filter(e -> documentId.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Returns the number of registered mappings.
     *
     * @return Mapping count
     */
    /**
     * Whether this mapping is backed by a file.
     *
     * @return true when {@link #persist()} and {@link #load()} are available
     */
    public boolean isPersistent() {
        return persistent;
    }

    public int size() {
        return nodeToDocument.size();
    }

    /**
     * Checks if the mapping is empty.
     *
     * @return true if no mappings registered
     */
    public boolean isEmpty() {
        return nodeToDocument.isEmpty();
    }

    /**
     * Clears all mappings.
     */
    public void clear() {
        nodeToDocument.clear();
        LOGGER.debug("Cleared all mappings");
    }

    /**
     * Returns an unmodifiable view of all mappings.
     *
     * @return Map of nodeId -> documentId
     */
    public Map<Long, String> getAllMappings() {
        return Collections.unmodifiableMap(new HashMap<>(nodeToDocument));
    }

    // ==================== Persistence Operations ====================

    /**
     * Persists the current mappings to disk.
     *
     * <p>This method atomically writes all mappings to the mapping file.
     * The file is written to a temporary location first, then renamed
     * to ensure atomicity.</p>
     *
     * @throws IOException if persistence fails
     * @throws IllegalStateException if persistence is not enabled
     */
    public void persist() throws IOException {
        if (!persistent) {
            throw new IllegalStateException("Persistence not enabled for this mapping");
        }

        persistenceLock.writeLock().lock();
        try {
            Path tempPath = mappingPath.resolveSibling(mappingPath.getFileName() + ".tmp");

            // Calculate total size needed
            int entryCount = nodeToDocument.size();

            try (FileChannel channel = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {

                long totalSize = HEADER_SIZE;

                // Estimate entry sizes
                for (Map.Entry<Long, String> entry : nodeToDocument.entrySet()) {
                    totalSize += 8 + 4 + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
                }

                // Allocate buffer
                ByteBuffer buffer = ByteBuffer.allocate((int) totalSize + 1024)
                    .order(ByteOrder.LITTLE_ENDIAN);

                // Write header
                buffer.putLong(MAGIC);
                buffer.putInt(VERSION);
                buffer.putInt(0); // Reserved
                buffer.putLong(entryCount);

                // Write entries
                for (Map.Entry<Long, String> entry : nodeToDocument.entrySet()) {
                    buffer.putLong(entry.getKey());
                    byte[] docIdBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    buffer.putInt(docIdBytes.length);
                    buffer.put(docIdBytes);
                }

                buffer.flip();
                channel.write(buffer);
                channel.force(true);
            }

            // Atomic rename
            java.nio.file.Files.move(tempPath, mappingPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            LOGGER.debug("Persisted {} mappings to {}", entryCount, mappingPath);

        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * Loads mappings from disk.
     *
     * <p>This method clears existing mappings and loads from the mapping file.</p>
     *
     * @throws IOException if loading fails
     * @throws IllegalStateException if persistence is not enabled
     */
    public void load() throws IOException {
        if (!persistent) {
            throw new IllegalStateException("Persistence not enabled for this mapping");
        }

        if (!java.nio.file.Files.exists(mappingPath)) {
            LOGGER.debug("Mapping file does not exist: {}", mappingPath);
            return;
        }

        persistenceLock.writeLock().lock();
        try {
            nodeToDocument.clear();

            try (FileChannel channel = FileChannel.open(mappingPath, StandardOpenOption.READ)) {
                long fileSize = channel.size();
                if (fileSize < HEADER_SIZE) {
                    throw new IOException("Truncated mapping file (no header): " + mappingPath);
                }

                ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, headerBuffer, "mapping header");
                headerBuffer.flip();

                long magic = headerBuffer.getLong();
                if (magic != MAGIC) {
                    throw new IOException("Invalid mapping file: bad magic number");
                }

                int version = headerBuffer.getInt();
                if (version != VERSION) {
                    throw new IOException("Unsupported mapping file version: " + version);
                }

                headerBuffer.getInt(); // Skip reserved
                long entryCount = headerBuffer.getLong();

                // An entry is at least ENTRY_HEADER_SIZE bytes, so a count larger
                // than the file can hold means the header is corrupt. Reject it
                // before allocating anything sized from the file's own claims.
                long maxPossibleEntries = (fileSize - HEADER_SIZE) / ENTRY_HEADER_SIZE;
                if (entryCount < 0 || entryCount > maxPossibleEntries) {
                    throw new IOException("Corrupt mapping file " + mappingPath + ": entryCount=" +
                        entryCount + " exceeds the " + maxPossibleEntries + " entries the file can hold");
                }

                // Read entries
                for (long i = 0; i < entryCount; i++) {
                    ByteBuffer entryHeader = ByteBuffer.allocate(ENTRY_HEADER_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN);
                    readFully(channel, entryHeader, "mapping entry " + i);
                    entryHeader.flip();

                    long nodeId = entryHeader.getLong();
                    int docIdLen = entryHeader.getInt();

                    long remaining = fileSize - channel.position();
                    if (docIdLen < 0 || docIdLen > MAX_DOCUMENT_ID_BYTES || docIdLen > remaining) {
                        throw new IOException("Corrupt mapping file " + mappingPath + ": entry " + i +
                            " declares a document ID of " + docIdLen + " bytes (" + remaining +
                            " bytes left, maximum " + MAX_DOCUMENT_ID_BYTES + ")");
                    }

                    ByteBuffer docIdBuffer = ByteBuffer.allocate(docIdLen);
                    readFully(channel, docIdBuffer, "document ID of entry " + i);
                    String documentId = new String(docIdBuffer.array(), StandardCharsets.UTF_8);

                    nodeToDocument.put(nodeId, documentId);
                }
            }

            LOGGER.debug("Loaded {} mappings from {}", nodeToDocument.size(), mappingPath);

        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * Appends a single mapping to the file without rewriting the entire file.
     *
     * <p>This is more efficient for incremental updates but may leave the file
     * in a state where some entries are duplicated. Use {@link #compact()} to
     * clean up periodically.</p>
     *
     * @param nodeId The node ID
     * @param documentId The document ID
     * @throws IOException if append fails
     * @throws IllegalStateException if persistence is not enabled
     */
    public void appendMapping(long nodeId, String documentId) throws IOException {
        if (!persistent) {
            return; // Silently ignore for in-memory mode
        }

        java.util.Objects.requireNonNull(documentId, "documentId cannot be null");

        // The write lock is required: the append also rewrites the header's entry
        // count, which load() uses to decide how many entries to read.
        persistenceLock.writeLock().lock();
        try {
            try (FileChannel channel = FileChannel.open(mappingPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {

                long entryCount = readOrInitializeHeader(channel);

                byte[] docIdBytes = documentId.getBytes(StandardCharsets.UTF_8);
                if (docIdBytes.length > MAX_DOCUMENT_ID_BYTES) {
                    throw new IOException("Document ID exceeds " + MAX_DOCUMENT_ID_BYTES +
                        " bytes: " + docIdBytes.length);
                }

                ByteBuffer buffer = ByteBuffer.allocate(ENTRY_HEADER_SIZE + docIdBytes.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
                buffer.putLong(nodeId);
                buffer.putInt(docIdBytes.length);
                buffer.put(docIdBytes);
                buffer.flip();
                writeFully(channel, buffer, channel.size());

                // Publish the new entry by bumping the count last: an interrupted
                // append then leaves trailing bytes that load() simply ignores.
                ByteBuffer countBuffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                countBuffer.putLong(entryCount + 1);
                countBuffer.flip();
                writeFully(channel, countBuffer, OFFSET_ENTRY_COUNT);

                LOGGER.trace("Appended mapping: nodeId={} -> documentId={}", nodeId, documentId);
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * Reads the mapping file header, writing a fresh one if the file is new.
     *
     * @param channel The open mapping file channel
     * @return The entry count currently recorded in the header
     * @throws IOException if the existing header is not a valid mapping header
     */
    private static long readOrInitializeHeader(FileChannel channel) throws IOException {
        if (channel.size() < HEADER_SIZE) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(MAGIC);
            header.putInt(VERSION);
            header.putInt(0); // Reserved
            header.putLong(0L); // Entry count
            header.flip();
            writeFully(channel, header, 0);
            return 0L;
        }

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, header, 0, "mapping header");
        header.flip();
        if (header.getLong() != MAGIC) {
            throw new IOException("Invalid mapping file: bad magic number");
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported mapping file version: " + version);
        }
        header.getInt(); // Reserved
        return header.getLong();
    }

    /**
     * Reads exactly {@code buffer.remaining()} bytes from the channel's current
     * position, failing on a short read instead of silently decoding zeros.
     */
    private static void readFully(FileChannel channel, ByteBuffer buffer, String what) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Truncated mapping file while reading " + what);
            }
        }
    }

    /**
     * Reads exactly {@code buffer.remaining()} bytes from an absolute position.
     */
    private static void readFully(FileChannel channel, ByteBuffer buffer, long position, String what)
            throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw new IOException("Truncated mapping file while reading " + what);
            }
            at += read;
        }
    }

    /**
     * Writes the whole buffer at an absolute position.
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
    }

    /**
     * Compacts the mapping file by rewriting it without duplicates.
     *
     * @throws IOException if compaction fails
     */
    public void compact() throws IOException {
        if (!persistent) {
            return;
        }

        persist(); // Simply rewrite the entire file
        LOGGER.debug("Compacted mapping file");
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("NodeDocumentMapping{entries=%d, persistent=%s, path=%s}",
            nodeToDocument.size(), persistent, mappingPath);
    }
}
