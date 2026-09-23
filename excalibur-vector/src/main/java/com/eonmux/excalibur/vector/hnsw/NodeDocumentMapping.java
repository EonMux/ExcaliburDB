package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.vector.durability.DurableFiles;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Maps HNSW node IDs to the chunk each node is the vector of.
 *
 * <p>A graph node is a number assigned in insertion order; the chunk it embeds is a string,
 * {@code sourceId:documentId:chunk:N} as {@link ChunkIdentifiers} forms it. This mapping is the
 * only link between the two, and everything that names a vector hit goes through it: the query
 * result's identifier, the text read back for it, the atomicity filter that asks whether its
 * document is published, and the retirement of a chunk or of a whole document.</p>
 *
 * <h2>Why a chunk and not its document</h2>
 * <p>A node is one chunk's embedding, so the chunk is the only identity that is true of it. Naming
 * the document instead loses three things at once: which passage matched (every chunk of a
 * document collapses into one result whose text cannot be read back), agreement with the lexical
 * index (which names chunks, so hybrid fusion could never recognise the same passage returned by
 * both signals), and the ability to retire one version of a chunk without the rest - a document
 * re-posted with new text would leave the old embedding ranked under the document's name, because
 * nothing narrower than the whole document could be named to retire it. The document is still one
 * derivation away: {@link ChunkIdentifiers#documentKeyOfChunk(String)} is the single definition
 * of that convention, and it is what {@link #getNodesForDocument(String)} and the atomicity
 * filter use.</p>
 *
 * <h2>Supersession</h2>
 * <p>Several nodes may name the same chunk: a chunk id is reused when a document is re-posted, and
 * the integration worker inserts the new value's vector as a new node. Only the newest is the
 * chunk's vector; {@code HnswGraph.attributeChunk} retires the others as it attributes the new
 * one, and {@link #getNodesForChunk(String)} is what makes that retirement cheap.</p>
 *
 * <h2>File format</h2>
 * <pre>
 * +---------------------------+
 * | Header (48 bytes)         |  laid out in {@link NodeMappingIntegrity}
 * +---------------------------+
 * | Entry 1:                  |
 * | - nodeId (8B)             |
 * | - keyLen (4B)             |
 * | - key (UTF-8 bytes)       |
 * +---------------------------+
 * | Entry 2...                |
 * +---------------------------+
 * </pre>
 *
 * <h2>Versions</h2>
 * <ul>
 *   <li>Version {@value #VERSION_V1}: a 24-byte header, no checksum, entries naming
 *       documents.</li>
 *   <li>Version {@value #VERSION_DOCUMENT_KEYED}: the 48-byte checksummed header, entries naming
 *       documents.</li>
 *   <li>Version {@value #VERSION}: the same layout, entries naming chunks.</li>
 * </ul>
 * <p>The entry layout never changed; what changed is what the string in it means, and the version
 * is the only thing that says so. A mapping read from a version 1 or 2 file is
 * {@link Keying#DOCUMENT document-keyed} and is persisted at version 2 for as long as it stays
 * that way - writing it at version 3 would present document keys as chunk keys. It becomes
 * chunk-keyed only through {@link #rekeyToChunks(Map)}, which the node drives from the segment's
 * own entries before it integrates anything more into the graph; see
 * {@link ChunkAttributionUpgrade}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong> Lookups by node are lock-free; the reverse
 * indexes by chunk and by document are changed together with the forward map under one monitor,
 * so a lookup by chunk or document never sees a node the forward map has not registered.
 * Persistence takes its own read-write lock.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Node IDs are non-negative long integers</li>
 *   <li>Keys are non-null, non-empty strings</li>
 *   <li>Each node ID maps to exactly one key</li>
 *   <li>Several nodes may map to one chunk (values not yet retired) and to one document (its
 *       chunks)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see HnswGraph
 */
public class NodeDocumentMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeDocumentMapping.class);

    /**
     * Magic number for file format validation ("NODEMAPS").
     */
    static final long MAGIC = 0x4E4F44454D415053L;

    /**
     * Current file format version: entries name chunks. The layout is version 2's; the class
     * documentation says why the meaning of the key needed a version of its own.
     */
    public static final int VERSION = 3;

    /**
     * The checksummed version whose entries name documents. Still written for a mapping loaded
     * from a document-keyed file until it has been re-keyed, so the version on disk never claims
     * a keying its entries do not have.
     */
    public static final int VERSION_DOCUMENT_KEYED = 2;

    /**
     * The version written before the header carried checksums. Its entries name documents.
     */
    static final int VERSION_V1 = 1;

    /**
     * The first version whose header carries the integrity block.
     */
    static final int FIRST_SEALED_VERSION = VERSION_DOCUMENT_KEYED;

    /**
     * What the keys of a mapping name.
     */
    public enum Keying {
        /** Each node names the chunk it is the vector of. What this build writes. */
        CHUNK,
        /**
         * Each node names only the document its chunk belongs to. Read from files written
         * before nodes were attributed to chunks, and served until the node re-keys them.
         */
        DOCUMENT
    }

    /**
     * Header size in bytes.
     */
    static final int HEADER_SIZE = 48;

    /**
     * Header size in bytes of a version 1 file.
     */
    static final int HEADER_SIZE_V1 = 24;

    /**
     * Byte offset of the entry-count field within the header, in both versions.
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
     * Thread-safe map from node ID to key.
     */
    private final ConcurrentHashMap<Long, String> nodeToDocument;

    /**
     * Nodes by the exact key they were registered under: by chunk, in a chunk-keyed mapping.
     * Guarded by {@link #indexLock}.
     */
    private final Map<String, Set<Long>> nodesByKey = new HashMap<>();

    /**
     * Nodes by the document their key belongs to, through
     * {@link ChunkIdentifiers#documentKeyOfChunk(String)}. That returns a document key unchanged,
     * so the same index answers for either keying. Guarded by {@link #indexLock}.
     */
    private final Map<String, Set<Long>> nodesByDocument = new HashMap<>();

    /** Guards the reverse indexes and their agreement with the forward map. */
    private final Object indexLock = new Object();

    /**
     * What the keys name: chunks for a new mapping, documents for one loaded from an older file
     * until {@link #rekeyToChunks(Map)} replaces its entries.
     */
    private volatile Keying keying = Keying.CHUNK;

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
     * Registers a mapping from node ID to key.
     *
     * <p>If the node ID already has a mapping, it will be updated. The key is the chunk id the
     * node is the vector of, in the chunk-keyed mapping every graph this build creates holds.</p>
     *
     * @param nodeId The HNSW node ID
     * @param documentId The key: the chunk id, {@code sourceId:documentId:chunk:N}
     * @throws IllegalArgumentException if nodeId is negative or documentId is null/empty
     */
    public void register(long nodeId, String documentId) {
        if (nodeId < 0) {
            throw new IllegalArgumentException("nodeId must be non-negative: " + nodeId);
        }
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must be non-null and non-empty");
        }

        synchronized (indexLock) {
            unindex(nodeId, nodeToDocument.put(nodeId, documentId));
            index(nodeId, documentId);
        }
        LOGGER.trace("Registered mapping: nodeId={} -> key={}", nodeId, documentId);
    }

    /**
     * Files a node under its key and under the document the key belongs to.
     *
     * @param nodeId The node
     * @param key    Its key
     */
    private void index(long nodeId, String key) {
        nodesByKey.computeIfAbsent(key, k -> new HashSet<>()).add(nodeId);
        nodesByDocument.computeIfAbsent(ChunkIdentifiers.documentKeyOfChunk(key),
            k -> new HashSet<>()).add(nodeId);
    }

    /**
     * Removes a node from the reverse indexes it was filed under.
     *
     * @param nodeId The node
     * @param key    The key it was filed under, or null when it was not filed
     */
    private void unindex(long nodeId, String key) {
        if (key == null) {
            return;
        }
        removeFrom(nodesByKey, key, nodeId);
        removeFrom(nodesByDocument, ChunkIdentifiers.documentKeyOfChunk(key), nodeId);
    }

    /**
     * Removes one node from one reverse index entry, dropping the entry once it is empty.
     */
    private static void removeFrom(Map<String, Set<Long>> index, String key, long nodeId) {
        Set<Long> nodes = index.get(key);
        if (nodes != null && nodes.remove(nodeId) && nodes.isEmpty()) {
            index.remove(key);
        }
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
        String removed;
        synchronized (indexLock) {
            removed = nodeToDocument.remove(nodeId);
            unindex(nodeId, removed);
        }
        if (removed != null) {
            LOGGER.trace("Removed mapping for nodeId={}", nodeId);
        }
        return removed;
    }

    /**
     * Returns all node IDs that belong to a specific document.
     *
     * <p>Every node whose key is one of the document's chunk ids - or, in a document-keyed
     * mapping, the document key itself - which is what erasure and a rolled-back ingestion have
     * to retire. Answered from an index rather than a scan: erasing a thousand documents would
     * otherwise read every entry of every graph a thousand times.</p>
     *
     * @param documentId The document key, {@code sourceId:documentId}
     * @return A snapshot of the nodes belonging to the document
     */
    public Set<Long> getNodesForDocument(String documentId) {
        if (documentId == null) {
            return Collections.emptySet();
        }
        synchronized (indexLock) {
            Set<Long> nodes = nodesByDocument.get(documentId);
            return nodes == null ? Collections.emptySet() : Set.copyOf(nodes);
        }
    }

    /**
     * Returns all node IDs registered under one chunk id.
     *
     * <p>More than one only while a superseded value's node has not been retired yet. Always
     * empty in a {@link Keying#DOCUMENT document-keyed} mapping, whose keys name no chunk.</p>
     *
     * @param chunkId The chunk id
     * @return A snapshot of the nodes registered under it
     */
    public Set<Long> getNodesForChunk(String chunkId) {
        if (chunkId == null) {
            return Collections.emptySet();
        }
        synchronized (indexLock) {
            Set<Long> nodes = nodesByKey.get(chunkId);
            return nodes == null ? Collections.emptySet() : Set.copyOf(nodes);
        }
    }

    /**
     * What this mapping's keys name.
     *
     * @return {@link Keying#CHUNK} unless the mapping was loaded from a file written before nodes
     *         were attributed to chunks and has not been re-keyed since
     */
    public Keying keying() {
        return keying;
    }

    /**
     * Replaces every entry with chunk-keyed ones, and from then on persists as chunk-keyed.
     *
     * <p>The one way a document-keyed mapping stops being one. All at once rather than entry by
     * entry: a mapping holding keys of both kinds would be persisted under one version or the
     * other, and either claim would be false for part of it.</p>
     *
     * @param chunkKeyed The complete new contents, node to chunk id
     * @throws NullPointerException if chunkKeyed is null
     * @throws IllegalArgumentException if any entry is not a valid mapping
     */
    public void rekeyToChunks(Map<Long, String> chunkKeyed) {
        Objects.requireNonNull(chunkKeyed, "chunkKeyed cannot be null");
        synchronized (indexLock) {
            nodeToDocument.clear();
            nodesByKey.clear();
            nodesByDocument.clear();
            keying = Keying.CHUNK;
            for (Map.Entry<Long, String> entry : chunkKeyed.entrySet()) {
                register(entry.getKey(), entry.getValue());
            }
        }
        LOGGER.debug("Re-keyed {} mappings to chunks", chunkKeyed.size());
    }

    /**
     * Sets what the entries about to be registered name, for a mapping rebuilt from another.
     *
     * <p>Compaction copies a mapping into a fresh one under new node numbers, and the copy has to
     * be persisted under the same version as the original: a document-keyed mapping must not come
     * out of a compaction claiming to name chunks.</p>
     *
     * @param keying What the entries name
     * @throws NullPointerException if keying is null
     */
    void useKeying(Keying keying) {
        this.keying = Objects.requireNonNull(keying, "keying cannot be null");
    }

    /**
     * Whether this mapping is backed by a file.
     *
     * @return true when {@link #persist()} and {@link #load()} are available
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Returns the number of registered mappings.
     *
     * @return Mapping count
     */
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
        synchronized (indexLock) {
            nodeToDocument.clear();
            nodesByKey.clear();
            nodesByDocument.clear();
        }
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
     * <p>The mappings are written to a temporary file in the same directory, forced to
     * storage, and renamed over the mapping file, so a reader sees either the previous
     * mapping or this one and never a partial one. The directory is forced after the rename
     * as well: a caller that swaps this file together with the graph it describes has to be
     * able to tell, after a crash, which of the two renames had happened.</p>
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

            int entryCount = nodeToDocument.size();
            ByteBuffer payload = encodeEntries();
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(0, MAGIC);
            header.putInt(Long.BYTES, versionFor(keying));
            header.putLong(OFFSET_ENTRY_COUNT, entryCount);

            // The payload is digested before either part is written, so the checksum in the
            // header describes exactly the bytes that follow it in this same file.
            NodeMappingIntegrity.seal(header, payload.array(), payload.limit());

            try (FileChannel channel = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(new ByteBuffer[] {header, payload});
                channel.force(true);
            }

            // Atomic rename
            java.nio.file.Files.move(tempPath, mappingPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.syncContainingDirectory(mappingPath);

            LOGGER.debug("Persisted {} mappings to {}", entryCount, mappingPath);

        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * The version a mapping of this keying is written at.
     *
     * @param keying What the entries name
     * @return {@link #VERSION} for chunks, {@link #VERSION_DOCUMENT_KEYED} for documents
     */
    static int versionFor(Keying keying) {
        return keying == Keying.CHUNK ? VERSION : VERSION_DOCUMENT_KEYED;
    }

    /**
     * Reports whether this build reads a mapping file written at a given version.
     *
     * <p>The one statement of the range, shared by the loader and the verifier, so that a
     * version the loader refuses is never one the verifier vouches for.</p>
     *
     * @param version The version a file's header names
     * @return true for {@link #VERSION_V1} through {@link #VERSION}
     */
    static boolean isSupportedVersion(int version) {
        return version == VERSION || version == VERSION_DOCUMENT_KEYED || version == VERSION_V1;
    }

    /**
     * What the entries of a file at a given version name.
     *
     * @param version A version {@link #readVersion} accepted
     * @return The keying its entries were written under
     */
    static Keying keyingOf(int version) {
        return version == VERSION ? Keying.CHUNK : Keying.DOCUMENT;
    }

    /**
     * Encodes every mapping into the entry region that follows the header.
     *
     * <p>Returned as a buffer over a whole array, so the checksum can be taken over the same
     * bytes the write will emit rather than over a re-encoding of them.</p>
     *
     * @return A flipped buffer whose backing array holds exactly the entry region
     */
    private ByteBuffer encodeEntries() {
        int size = 0;
        Map<Long, byte[]> encoded = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : nodeToDocument.entrySet()) {
            byte[] documentId = entry.getValue().getBytes(StandardCharsets.UTF_8);
            encoded.put(entry.getKey(), documentId);
            size += ENTRY_HEADER_SIZE + documentId.length;
        }

        ByteBuffer payload = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<Long, byte[]> entry : encoded.entrySet()) {
            payload.putLong(entry.getKey());
            payload.putInt(entry.getValue().length);
            payload.put(entry.getValue());
        }
        payload.flip();
        return payload;
    }

    /**
     * Verifies a mapping file on disk without loading it.
     *
     * <p>The checking entry point for this format: it reads the header and streams the
     * entries past a digest, so a file can be judged without the mapping it describes being
     * attached to a graph.</p>
     *
     * @param mappingPath The mapping file to verify
     * @return What the check concluded, including whether the file carries a checksum at all
     * @throws NullPointerException if mappingPath is null
     */
    public static IntegrityReport verifyIntegrity(Path mappingPath) {
        return NodeMappingIntegrity.verify(mappingPath);
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
            clear();

            try (FileChannel channel = FileChannel.open(mappingPath, StandardOpenOption.READ)) {
                long fileSize = channel.size();
                if (fileSize < HEADER_SIZE_V1) {
                    throw new IOException("Truncated mapping file (no header): " + mappingPath);
                }

                int version = readVersion(channel, mappingPath);
                int headerSize = version == VERSION_V1 ? HEADER_SIZE_V1 : HEADER_SIZE;
                if (fileSize < headerSize) {
                    throw new IOException("Truncated mapping file (header of a version "
                        + version + " file is " + headerSize + " bytes): " + mappingPath);
                }

                ByteBuffer headerBuffer = ByteBuffer.allocate(headerSize)
                    .order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, headerBuffer, 0, "mapping header");

                // Before a single entry is decoded. A mapping that has rotted re-attributes
                // vectors to other documents, which erasure and document filtering both act
                // on without anything in their answers showing it.
                NodeMappingIntegrity.inspect(mappingPath, channel, headerBuffer, version)
                    .requireIntact();

                long entryCount = headerBuffer.getLong(OFFSET_ENTRY_COUNT);
                channel.position(headerSize);

                // An entry is at least ENTRY_HEADER_SIZE bytes, so a count larger
                // than the file can hold means the header is corrupt. Reject it
                // before allocating anything sized from the file's own claims.
                long maxPossibleEntries = (fileSize - headerSize) / ENTRY_HEADER_SIZE;
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
                    if (nodeId < 0 || documentId.isEmpty()) {
                        throw new IOException("Corrupt mapping file " + mappingPath + ": entry " + i
                            + " maps node " + nodeId + " to an empty or invalid key");
                    }

                    register(nodeId, documentId);
                }
                keying = keyingOf(version);
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

                ByteBuffer header = readOrInitializeHeader(channel, mappingPath);
                long entryCount = header.getLong(OFFSET_ENTRY_COUNT);

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
                // append then leaves trailing bytes that load() simply ignores. The seal goes
                // with it - this call never reads the entries it is adding to, so it has no
                // checksum to offer over them and must not leave the previous one standing.
                header.putLong(OFFSET_ENTRY_COUNT, entryCount + 1);
                NodeMappingIntegrity.breakSeal(channel, header);

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
    private ByteBuffer readOrInitializeHeader(FileChannel channel, Path mappingPath)
            throws IOException {
        int expected = versionFor(keying);
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        if (channel.size() < HEADER_SIZE) {
            header.putLong(0, MAGIC);
            header.putInt(Long.BYTES, expected);
            header.putLong(OFFSET_ENTRY_COUNT, 0L);
            return header;
        }

        // An append adds a key of this mapping's keying to a file that declares one, so the two
        // have to agree: a chunk id appended to a document-keyed file, or the reverse, would be
        // read back under the wrong meaning.
        int version = readVersion(channel, mappingPath);
        if (version != expected) {
            throw new IOException("Mapping file " + mappingPath + " is at format version "
                + version + ", which cannot be appended to by this mapping; compact the mapping "
                + "to rewrite it at version " + expected);
        }
        readFully(channel, header, 0, "mapping header");
        return header;
    }

    /**
     * Reads and validates the magic number, returning the format version behind it.
     *
     * @param channel     The open mapping file
     * @param mappingPath The file, for the error message
     * @return The format version recorded in the header
     * @throws IOException if the file does not start with a mapping header this build knows
     */
    private static int readVersion(FileChannel channel, Path mappingPath) throws IOException {
        ByteBuffer probe = ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, probe, 0, "mapping header");
        if (probe.getLong(0) != MAGIC) {
            throw new IOException("Invalid mapping file (bad magic number): " + mappingPath);
        }
        int version = probe.getInt(Long.BYTES);
        if (!isSupportedVersion(version)) {
            throw new IOException("Unsupported mapping file version " + version + " in "
                + mappingPath);
        }
        return version;
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
    static void readFully(FileChannel channel, ByteBuffer buffer, long position, String what)
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
        return String.format("NodeDocumentMapping{entries=%d, keying=%s, persistent=%s, path=%s}",
            nodeToDocument.size(), keying, persistent, mappingPath);
    }
}
