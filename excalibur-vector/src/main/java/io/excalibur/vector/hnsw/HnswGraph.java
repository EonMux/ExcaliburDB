package io.excalibur.vector.hnsw;

import io.excalibur.vector.durability.DurableFiles;
import io.excalibur.core.mapped.MappedFile;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.core.memory.Segment;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.quantization.PQCodebookFile;
import io.excalibur.vector.quantization.PQDistanceTable;
import io.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Disk-backed HNSW (Hierarchical Navigable Small World) graph for approximate nearest neighbor search.
 *
 * <p>This implementation stores nodes and adjacency lists in memory-mapped files,
 * making the graph navigable directly from disk without requiring full loading into memory.
 * This is critical for handling large-scale vector indexes that exceed available RAM.</p>
 *
 * <h2>Disk Layout</h2>
 * <p>The graph is stored in multiple memory-mapped regions:</p>
 * <pre>
 * +------------------------+
 * | Metadata Header (4KB)  |
 * | - magic (8B)           |
 * | - version (4B)         |
 * | - dimension (4B)       |
 * | - nodeCount (8B)       |
 * | - entryPoint (8B)      |
 * | - maxLevel (4B)        |
 * | - lastIntegratedOffset (8B) |
 * | - config fields...     |
 * | - distance metric (4B) |
 * +------------------------+
 * | Node Index Table       |
 * | - offset to node data  |
 * | - node level           |
 * | - tombstone flag       |
 * +------------------------+
 * | Node Data Region       |
 * | - vector data          |
 * | - adjacency lists      |
 * +------------------------+
 * </pre>
 *
 * <h2>Node Format (variable length)</h2>
 * <pre>
 * +------------------+
 * | nodeId (8B)      |
 * | level (4B)       |
 * | vectorLen (4B)   |
 * | vector (dim*4B)  |
 * | connCount0 (4B)  |
 * | neighbors0 (...) |
 * | connCount1 (4B)  |
 * | neighbors1 (...) |
 * | ... per level    |
 * +------------------+
 * </pre>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Direct disk navigation:</strong> Graph traversal reads directly from memory-mapped file</li>
 *   <li><strong>Incremental insertion:</strong> Vectors can be added without full rebuild</li>
 *   <li><strong>Soft-delete:</strong> Tombstoned nodes are skipped during traversal</li>
 *   <li><strong>Tail offset tracking:</strong> Tracks integration progress for live indexing</li>
 *   <li><strong>Thread-safe:</strong> Read-write locks for concurrent access</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Read operations (search) acquire read lock - concurrent reads allowed</li>
 *   <li>Write operations (insert, delete) acquire write lock - exclusive access</li>
 *   <li>lastIntegratedOffset updates are atomic</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class HnswGraph implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraph.class);

    // Magic number for file format validation ("HNSWGRPH" truncated)
    private static final long MAGIC = 0x484E535747525048L;

    // Current file format version
    private static final int VERSION = 2;

    // The version written before the header recorded a distance metric. Cosine was the
    // only metric a graph could be built under at the time, so a file at this version
    // is read as cosine and is left at this version rather than being rewritten.
    private static final int VERSION_WITHOUT_METRIC = 1;

    // Header size (4KB aligned)
    private static final int HEADER_SIZE = 4096;

    // Node index entry size: nodeId(8) + dataOffset(8) + level(4) + flags(4)
    private static final int NODE_INDEX_ENTRY_SIZE = 24;

    // Flag bits for node state
    private static final int FLAG_TOMBSTONE = 0x01;

    // Suffix for the compacted graph built alongside the live file before the swap
    private static final String COMPACT_SUFFIX = ".compact";

    // Note: all persistent I/O goes through MappedFile, which uses BIG_ENDIAN
    // (see MappedFile.LONG_LAYOUT / INT_LAYOUT / FLOAT_LAYOUT). Do not introduce
    // direct ValueLayout reads/writes here -- they would silently diverge from
    // the on-disk byte order.

    // Header field offsets
    private static final long OFFSET_MAGIC = 0;
    private static final long OFFSET_VERSION = 8;
    private static final long OFFSET_DIMENSION = 12;
    private static final long OFFSET_NODE_COUNT = 16;
    private static final long OFFSET_ENTRY_POINT = 24;
    private static final long OFFSET_MAX_LEVEL = 32;
    private static final long OFFSET_LAST_INTEGRATED = 36;
    private static final long OFFSET_CONFIG_MAX_CONN = 44;
    private static final long OFFSET_CONFIG_MAX_CONN_0 = 48;
    private static final long OFFSET_CONFIG_EF_CONSTRUCTION = 52;
    private static final long OFFSET_CONFIG_ML = 56;
    private static final long OFFSET_CONFIG_EF_SEARCH = 64;
    private static final long OFFSET_MAX_NODES = 68;
    // OFFSET_MAX_NODES holds a long, so the metric code starts at 76.
    private static final long OFFSET_METRIC = 76;

    // Configuration.
    // These are reassigned by reopenAfterCompaction() while lock-free readers
    // (readNode/NodeView) may be dereferencing them, so they must be volatile:
    // a plain field gives no safe-publication guarantee for the replacement.
    private volatile HnswConfig config;
    private volatile DistanceFunction distanceFunction;
    private volatile Path path;
    private volatile Arena arena;
    private volatile MappedFile mappedFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Mutable state (volatile for visibility)
    private volatile long nodeCount = 0;
    private volatile long entryPointId = -1;
    private volatile int maxLevel = -1;
    private volatile boolean closed = false;

    // Atomic counter for last integrated offset (for live indexing)
    private final AtomicLong lastIntegratedOffset = new AtomicLong(0);

    // Node ID generator
    private final AtomicLong nodeIdGenerator = new AtomicLong(0);

    // Data region start offset (after header and node index)
    private volatile long dataRegionOffset;

    // Current write offset in data region
    private volatile long dataWriteOffset;

    // Maximum number of nodes this graph can hold
    private volatile long maxNodes;

    // Generation counter, incremented every time compaction replaces the backing
    // file. Compaction renumbers node IDs, so a node ID is only meaningful within
    // the generation it was obtained from. Callers that hold a node ID across a
    // possible compaction must pass the generation they captured to
    // readNode(long, long) so a renumbered ID is rejected instead of silently
    // resolving to a different vector.
    private volatile long generation = 0;

    // Optional Product Quantizer for compressed vectors
    // Volatile for safe publication after training
    private volatile ProductQuantizer pq;

    // PQ codes storage: maps nodeId -> PQ codes (only when PQ is trained)
    // This is a simple in-memory map; for production, consider disk-backed storage
    private volatile java.util.concurrent.ConcurrentHashMap<Long, byte[]> pqCodes;

    // Node to document ID mapping for document atomicity filtering
    // Maps internal HNSW node IDs to actual document IDs (e.g., "doc1", "source1:file1")
    // This is critical for DocumentAtomicityFilter to correctly identify documents
    /** Suffix of the file holding a graph's node-to-document mapping. */
    public static final String DOCUMENT_MAPPING_SUFFIX = ".docmap";

    /** Suffix of the file holding a graph's trained product quantizer codebook. */
    public static final String PRODUCT_QUANTIZER_SUFFIX = PQCodebookFile.SUFFIX;

    private volatile NodeDocumentMapping nodeDocumentMapping;

    /**
     * Creates a new HNSW graph backed by a memory-mapped file.
     *
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @throws IOException if file cannot be created or mapped
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswGraph(Path path, HnswConfig config, DistanceFunction distanceFunction, long maxNodes)
            throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(config, "Config cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive: " + maxNodes);
        }
        // Neighbor IDs are stored as 4-byte int in the adjacency lists.
        // Graphs with more than Integer.MAX_VALUE nodes would silently
        // truncate neighbor references, causing data corruption.
        if (maxNodes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxNodes cannot exceed Integer.MAX_VALUE (" + Integer.MAX_VALUE +
                ") because neighbor IDs are stored as int: " + maxNodes);
        }

        this.path = path;
        this.config = config;
        this.distanceFunction = distanceFunction;
        this.maxNodes = maxNodes;

        // Beside the graph file, and loaded if it is already there. In memory only, this
        // mapping was rebuilt empty on every restart: the integration worker records it as it
        // inserts vectors, so a graph that was fully integrated before a restart came back with
        // no document identity on any of its nodes. Search still returned them; they simply
        // stopped being attributable to a document, which is what document-level filtering and
        // payload resolution work from.
        this.nodeDocumentMapping = openDocumentMapping(path);

        // Create shared arena for thread-safe access
        this.arena = Arena.ofShared();

        // A graph file may already exist for this path (a segment that is being
        // re-attached after a restart). MappedFile.create() does NOT truncate, so
        // unconditionally rewriting the header here would orphan every persisted
        // node: nodeCount/entryPoint/maxLevel/lastIntegratedOffset would be reset
        // and the next insert would overwrite node 0's record. Adopt the persisted
        // layout instead and only initialize a genuinely new file.
        attachOrInitialize(config, maxNodes);
    }

    /**
     * Brings the backing file into use, adopting an existing one or creating a new one.
     *
     * @param requestedConfig The configuration the caller asked for
     * @param requestedMaxNodes The capacity the caller asked for
     * @throws IOException if the file exists and cannot be used as requested
     */
    private void attachOrInitialize(HnswConfig requestedConfig, long requestedMaxNodes)
            throws IOException {
        boolean attached = false;
        try {
            PersistedLayout existing = readPersistedLayout(path);
            if (existing != null) {
                adoptPersistedLayout(existing, requestedConfig, requestedMaxNodes);
            } else {
                initializeNewFile();
            }
            attached = true;
        } finally {
            if (!attached) {
                // A refused header (wrong dimension, wrong metric, unreadable) must not
                // strand the shared arena and whatever mapping was already made: the
                // constructor never returns, so nobody is left to close them.
                closeQuietly(mappedFile, arena);
            }
        }
    }

    /**
     * Creates and initializes the backing file for a brand new graph.
     */
    private void initializeNewFile() throws IOException {
        // Calculate initial file size
        // Header + node index + estimated data region
        long nodeIndexSize = maxNodes * NODE_INDEX_ENTRY_SIZE;
        long estimatedVectorSize = config.vectorByteSize();
        long estimatedAdjacencySize = config.adjacencyByteSizeLayer0() +
            (long) config.maxConnections() * config.adjacencyByteSize();
        long estimatedNodeSize = 8 + 4 + 4 + estimatedVectorSize + estimatedAdjacencySize;
        long initialDataSize = Math.max(1024 * 1024, maxNodes / 10 * estimatedNodeSize);

        this.dataRegionOffset = HEADER_SIZE + nodeIndexSize;
        long totalSize = dataRegionOffset + initialDataSize;

        this.mappedFile = MappedFile.create(path, totalSize);

        initializeHeader();

        LOGGER.info("Created HNSW graph at {} with dimension={}, maxNodes={}",
            path, config.dimension(), maxNodes);
    }

    /**
     * Re-attaches to an existing graph file, restoring its persisted state.
     *
     * <p>The stored records are laid out by {@code dimension}, {@code maxConnections},
     * {@code maxConnectionsLayer0} and {@code maxNodes}, so those must come from the
     * file - reading them with the requested values would misparse every node. The
     * remaining parameters ({@code efSearch}, {@code efConstruction}, {@code mL}) only
     * affect future searches and insertions, so the caller's values win and are
     * written back to the header.</p>
     *
     * <p>A different dimension is rejected outright: it means the file holds vectors
     * from a different embedding space, which no amount of re-reading can fix. So is a
     * different distance metric, for the reason given on {@link #requireMatchingMetric}.</p>
     *
     * @param existing The layout read from the file header
     * @param requestedConfig The configuration the caller asked for
     * @param requestedMaxNodes The capacity the caller asked for
     * @throws IOException if the persisted layout is incompatible with the request
     */
    private void adoptPersistedLayout(PersistedLayout existing, HnswConfig requestedConfig,
                                      long requestedMaxNodes) throws IOException {
        HnswConfig persisted = existing.config();
        if (persisted.dimension() != requestedConfig.dimension()) {
            throw new IOException("Dimension mismatch for existing HNSW graph " + path +
                ": file holds " + persisted.dimension() +
                ", requested " + requestedConfig.dimension());
        }

        requireMatchingMetric(existing.distanceMetric(), distanceFunction, path);

        warnOnIgnoredLayoutChange("maxConnections", persisted.maxConnections(),
            requestedConfig.maxConnections());
        warnOnIgnoredLayoutChange("maxConnectionsLayer0", persisted.maxConnectionsLayer0(),
            requestedConfig.maxConnectionsLayer0());
        warnOnIgnoredLayoutChange("maxNodes", existing.maxNodes(), requestedMaxNodes);

        this.config = new HnswConfig(
            persisted.maxConnections(),
            persisted.maxConnectionsLayer0(),
            requestedConfig.efConstruction(),
            requestedConfig.efSearch(),
            requestedConfig.mL(),
            persisted.dimension());
        this.maxNodes = existing.maxNodes();
        this.dataRegionOffset = HEADER_SIZE + existing.maxNodes() * NODE_INDEX_ENTRY_SIZE;
        this.mappedFile = MappedFile.open(path, Files.size(path));

        restoreStateFromHeader();

        // Persist the tunables the caller supplied so a later open() without a config
        // sees the values this process is actually using.
        mappedFile.writeInt(OFFSET_CONFIG_EF_CONSTRUCTION, config.efConstruction());
        mappedFile.writeInt(OFFSET_CONFIG_EF_SEARCH, config.efSearch());
        mappedFile.writeLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));

        restorePersistedQuantizer();

        LOGGER.info("Re-attached to existing HNSW graph at {} with {} nodes, dimension={}, maxNodes={}",
            path, nodeCount, config.dimension(), maxNodes);
    }

    /**
     * Warns that a layout parameter cannot be changed on an existing graph file.
     */
    private void warnOnIgnoredLayoutChange(String name, long persistedValue, long requestedValue) {
        if (persistedValue != requestedValue) {
            LOGGER.warn("Existing HNSW graph at {} was created with {}={}; ignoring requested {} " +
                "because the on-disk layout is already fixed (rebuild the index to change it)",
                path, name, persistedValue, requestedValue);
        }
    }

    /**
     * Layout and configuration recovered from an existing graph file header.
     */
    private record PersistedLayout(HnswConfig config, long maxNodes, String distanceMetric) {}

    /**
     * Reports whether this build can interpret a header at the given version.
     */
    private static boolean isSupportedVersion(int version) {
        return version == VERSION || version == VERSION_WITHOUT_METRIC;
    }

    /**
     * Returns the metric a persisted header describes.
     *
     * @param version The header's format version, already known to be supported
     * @param metricCode The value at {@code OFFSET_METRIC}, ignored for a header
     *                   written before the field existed
     * @param path The graph file, for the error message
     * @return The canonical metric name the graph was built with
     * @throws IOException if the header names a metric this build cannot provide
     */
    private static String persistedMetric(int version, int metricCode, Path path) throws IOException {
        if (version == VERSION_WITHOUT_METRIC) {
            return PersistedMetric.LEGACY_METRIC;
        }
        try {
            return PersistedMetric.nameFor(metricCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("HNSW graph " + path + " records a distance metric this build "
                + "does not know (header code " + metricCode + "): " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a graph whose edges were built under a metric other than the requested one.
     *
     * <p>Every edge in the graph was chosen because it was the closest candidate under
     * the metric in force when its node was inserted. Under a different metric those
     * edges are no longer monotone, so greedy descent walks away from the true
     * neighbours and the search returns a plausible but wrong answer, with the scores
     * clamped into the new metric's range on the way out. Nothing about the result
     * shows that it happened, which is why this is an error and not a warning.</p>
     *
     * @param persistedMetric The metric recorded in the header
     * @param distanceFunction The metric the caller is configured for
     * @param path The graph file, for the error message
     * @throws IOException if the two metrics differ
     */
    private static void requireMatchingMetric(String persistedMetric,
                                              DistanceFunction distanceFunction,
                                              Path path) throws IOException {
        String requested = distanceFunction.name();
        if (!persistedMetric.equals(requested)) {
            throw new IOException("HNSW graph " + path + " was built with the distance metric '"
                + persistedMetric + "' but this node is configured for '" + requested
                + "'. A graph's edges only rank correctly under the metric that chose them, "
                + "so the corpus has to be reindexed to change it.");
        }
    }

    /**
     * Reads the layout of an existing graph file without mapping it read-write.
     *
     * @param path The graph file path
     * @return The persisted layout, or null if the file does not exist or has
     *         never been initialized (all-zero header)
     * @throws IOException if the file exists and holds a header that cannot be used
     */
    private static PersistedLayout readPersistedLayout(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) < HEADER_SIZE) {
            return null;
        }

        try (MappedFile probe = MappedFile.open(path)) {
            long magic = probe.readLong(OFFSET_MAGIC);
            if (magic == 0L) {
                // File exists but was never initialized (freshly allocated).
                return null;
            }
            if (magic != MAGIC) {
                throw new IOException("Invalid HNSW file (bad magic number): " + path);
            }

            int version = probe.readInt(OFFSET_VERSION);
            if (!isSupportedVersion(version)) {
                throw new IOException("Unsupported HNSW version " + version + " in " + path);
            }

            String metric = persistedMetric(version, probe.readInt(OFFSET_METRIC), path);

            long persistedMaxNodes = probe.readLong(OFFSET_MAX_NODES);
            if (persistedMaxNodes <= 0) {
                // Written before maxNodes was part of the header. dataRegionOffset
                // cannot be reconstructed from the remaining fields, and guessing it
                // would read node data from the wrong offsets.
                throw new IOException("HNSW file predates the maxNodes header field and " +
                    "cannot be opened safely (rebuild the index): " + path);
            }

            HnswConfig persistedConfig = new HnswConfig(
                probe.readInt(OFFSET_CONFIG_MAX_CONN),
                probe.readInt(OFFSET_CONFIG_MAX_CONN_0),
                probe.readInt(OFFSET_CONFIG_EF_CONSTRUCTION),
                probe.readInt(OFFSET_CONFIG_EF_SEARCH),
                Double.longBitsToDouble(probe.readLong(OFFSET_CONFIG_ML)),
                probe.readInt(OFFSET_DIMENSION));

            return new PersistedLayout(persistedConfig, persistedMaxNodes, metric);
        }
    }

    /**
     * Restores in-memory state from the persisted header.
     *
     * <p>Requires {@code mappedFile}, {@code config}, {@code maxNodes} and
     * {@code dataRegionOffset} to already reflect the persisted layout.</p>
     */
    private void restoreStateFromHeader() {
        long persistedNodeCount = mappedFile.readLong(OFFSET_NODE_COUNT);
        this.entryPointId = mappedFile.readLong(OFFSET_ENTRY_POINT);
        this.maxLevel = mappedFile.readInt(OFFSET_MAX_LEVEL);
        this.lastIntegratedOffset.set(mappedFile.readLong(OFFSET_LAST_INTEGRATED));

        // The header's count and the index slot it names are separate plain stores into the
        // mapping, neither forced nor ordered against the other, so a crash can leave the count
        // ahead of the slot. Trust only as far as the slots go.
        this.nodeCount = highestWrittenNodeCount(persistedNodeCount);
        if (this.nodeCount != persistedNodeCount) {
            LOGGER.warn("HNSW header for {} claims {} nodes but only {} index entries were "
                + "written; recovering to the lower count.",
                path, persistedNodeCount, this.nodeCount);
        }
        this.nodeIdGenerator.set(nodeCount);
        this.dataWriteOffset = recoverDataWriteOffset(nodeCount);
    }

    /**
     * The number of nodes whose index entries actually reached the file.
     *
     * <p>Each entry starts with the id of the node it describes, so a slot that has not been
     * written reads as zeros and does not match its own index. Walking back from the header's
     * count to the highest slot that does match is what keeps a torn tail from being believed:
     * deriving the append position from an unwritten slot yields a data offset of zero, and the
     * next insert then writes over node 0 - which is usually the entry point, so the graph
     * navigates from a corrupted vector and answers with the wrong neighbours.</p>
     *
     * @param persistedNodeCount The count the header claims
     * @return The largest count whose every index entry is present
     */
    private long highestWrittenNodeCount(long persistedNodeCount) {
        long count = Math.min(persistedNodeCount, maxNodes);
        while (count > 0) {
            long indexOffset = HEADER_SIZE + (count - 1) * NODE_INDEX_ENTRY_SIZE;
            if (indexOffset + NODE_INDEX_ENTRY_SIZE <= dataRegionOffset
                && mappedFile.readLong(indexOffset) == count - 1) {
                return count;
            }
            count--;
        }
        return 0;
    }

    /**
     * Recovers the append position in the data region from the last node index entry.
     *
     * <p>Nodes are appended in ID order, so the highest ID's record is the last one
     * written and its size is fully determined by its level. The entry is known to be present:
     * {@link #highestWrittenNodeCount} has already established that.</p>
     *
     * @param persistedNodeCount The node count read from the header
     * @return The offset (relative to the data region) at which the next node goes
     */
    private long recoverDataWriteOffset(long persistedNodeCount) {
        if (persistedNodeCount <= 0) {
            return 0;
        }
        long lastIndexOffset = HEADER_SIZE + (persistedNodeCount - 1) * NODE_INDEX_ENTRY_SIZE;
        long lastDataOffset = mappedFile.readLong(lastIndexOffset + 8);
        int lastLevel = mappedFile.readInt(lastIndexOffset + 16);
        return lastDataOffset + estimateNodeSize(config, lastLevel);
    }

    /**
     * Private constructor for opening an existing graph without reinitializing the header.
     */
    private HnswGraph(Path path, HnswConfig config, DistanceFunction distanceFunction,
                      long maxNodes, MappedFile mappedFile, Arena arena,
                      long dataRegionOffset) {
        this.path = path;
        this.config = config;
        this.distanceFunction = distanceFunction;
        this.maxNodes = maxNodes;
        this.mappedFile = mappedFile;
        this.arena = arena;
        this.dataRegionOffset = dataRegionOffset;
        this.dataWriteOffset = 0; // Will be set by caller if needed
        this.nodeDocumentMapping = openDocumentMapping(path);
    }

    /**
     * Opens the node-to-document mapping that belongs beside a graph file.
     *
     * <p>Falls back to an in-memory mapping if the file cannot be read. Losing the mapping
     * costs document attribution on this graph's results; refusing to open the graph would cost
     * the vectors themselves, which is the worse trade.
     *
     * @param graphPath The graph file
     * @return A mapping, never null
     */
    private static NodeDocumentMapping openDocumentMapping(Path graphPath) {
        return openDocumentMapping(graphPath, true);
    }

    /**
     * Opens the node-to-document mapping that belongs beside a graph file.
     *
     * @param graphPath The graph file
     * @param loadExisting Whether to adopt the mappings already on disk. False when the
     *                     caller is about to replace them wholesale, as compaction does.
     * @return A mapping, never null
     */
    private static NodeDocumentMapping openDocumentMapping(Path graphPath, boolean loadExisting) {
        Path mappingPath = documentMappingPath(graphPath);
        try {
            return NodeDocumentMapping.createOrLoad(mappingPath, loadExisting);
        } catch (IOException e) {
            LOGGER.warn("Could not open the node-to-document mapping at {}: {}. Results from "
                + "this graph will carry no document identity until it is rebuilt.",
                mappingPath, e.toString());
            return new NodeDocumentMapping();
        }
    }

    /**
     * Returns the path of the node-to-document mapping that belongs beside a graph file.
     *
     * @param graphPath The graph file
     * @return The sidecar path, which is the graph path plus {@link #DOCUMENT_MAPPING_SUFFIX}
     */
    private static Path documentMappingPath(Path graphPath) {
        return Path.of(graphPath.toString() + DOCUMENT_MAPPING_SUFFIX);
    }

    /**
     * Returns the path a compaction builds the replacement graph at.
     *
     * <p>Beside the live file, so the swap is a rename within one filesystem, and under a name
     * derived from it, so a crash leaves an artefact the next open can attribute to this graph
     * rather than an orphan in a scratch directory.</p>
     *
     * @param graphPath The graph being compacted
     * @return The staging path for the compacted graph
     */
    private static Path compactionStagingPath(Path graphPath) {
        return graphPath.resolveSibling(graphPath.getFileName() + COMPACT_SUFFIX);
    }

    /**
     * Opens an existing HNSW graph from a file.
     *
     * @param path Path to the existing graph file
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if file cannot be opened or is invalid
     */
    public static HnswGraph open(Path path, DistanceFunction distanceFunction) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        finishInterruptedCompaction(path);

        // Open the file to read size
        long fileSize = java.nio.file.Files.size(path);

        // Create arena and mapped file (read-write for potential updates)
        Arena arena = Arena.ofShared();
        MappedFile mappedFile = MappedFile.open(path, fileSize);

        boolean opened = false;
        try {
            // Read and validate header
            long magic = mappedFile.readLong(OFFSET_MAGIC);
            if (magic != MAGIC) {
                throw new IOException("Invalid HNSW file: bad magic number");
            }

            int version = mappedFile.readInt(OFFSET_VERSION);
            if (!isSupportedVersion(version)) {
                throw new IOException("Unsupported HNSW version: " + version);
            }

            requireMatchingMetric(
                persistedMetric(version, mappedFile.readInt(OFFSET_METRIC), path),
                distanceFunction, path);

            int dimension = mappedFile.readInt(OFFSET_DIMENSION);

            int maxConn = mappedFile.readInt(OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = mappedFile.readInt(OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = mappedFile.readInt(OFFSET_CONFIG_EF_CONSTRUCTION);
            int efSearch = mappedFile.readInt(OFFSET_CONFIG_EF_SEARCH);
            double mL = Double.longBitsToDouble(mappedFile.readLong(OFFSET_CONFIG_ML));

            HnswConfig config = new HnswConfig(maxConn, maxConn0, efConstruction, efSearch, mL, dimension);

            // maxNodes fixes the size of the node index and therefore the start of
            // the data region. Files written before it was persisted cannot have it
            // reconstructed, and guessing would read every node from a wrong offset.
            long maxNodes = mappedFile.readLong(OFFSET_MAX_NODES);
            if (maxNodes <= 0) {
                throw new IOException("HNSW file predates the maxNodes header field and " +
                    "cannot be opened safely (rebuild the index): " + path);
            }

            // Calculate data region offset
            long nodeIndexSize = maxNodes * NODE_INDEX_ENTRY_SIZE;
            long dataRegionOffset = HEADER_SIZE + nodeIndexSize;

            // Create graph using private constructor (doesn't reinitialize header)
            HnswGraph graph = new HnswGraph(path, config, distanceFunction, maxNodes,
                mappedFile, arena, dataRegionOffset);

            // Restore state from persisted data
            graph.restoreStateFromHeader();
            graph.restorePersistedQuantizer();

            LOGGER.info("Opened HNSW graph at {} with {} nodes, dimension={}",
                path, graph.nodeCount, dimension);

            opened = true;
            return graph;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to open HNSW graph: " + path, e);
        } finally {
            if (!opened) {
                // Single cleanup path: closing twice would surface
                // "Already closed" instead of the real failure.
                closeQuietly(mappedFile, arena);
            }
        }
    }

    /**
     * Closes a mapped file and arena without masking an in-flight failure.
     */
    private static void closeQuietly(MappedFile mappedFile, Arena arena) {
        if (mappedFile != null) {
            try {
                mappedFile.close();
            } catch (Exception closeFailure) {
                LOGGER.warn("Error closing mapped file: {}", closeFailure.getMessage());
            }
        }
        if (arena != null) {
            try {
                arena.close();
            } catch (Exception closeFailure) {
                LOGGER.warn("Error closing arena: {}", closeFailure.getMessage());
            }
        }
    }

    /**
     * Opens an existing HNSW graph from a Segment abstraction.
     *
     * <p>This method provides an alternative way to open a graph using the
     * {@link Segment} memory abstraction layer. The Segment abstraction allows
     * the graph to work with either Panama FFM or fallback ByteBuffer implementations
     * depending on runtime availability.</p>
     *
     * <p><strong>Note:</strong> This method creates a MappedFile internally from
     * the Segment's path. If the Segment is not backed by a file, use
     * {@link #open(Path, DistanceFunction)} instead.</p>
     *
     * @param segment The Segment containing the graph data (must be file-backed)
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if the segment cannot be opened or is invalid
     * @throws UnsupportedOperationException if the segment is not file-backed
     * @throws NullPointerException if segment or distanceFunction is null
     * @since 1.1
     */
    public static HnswGraph openFromSegment(Segment segment, DistanceFunction distanceFunction) throws IOException {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        Path path = segment.path();
        if (path == null) {
            throw new UnsupportedOperationException(
                "Segment must be file-backed to open as HnswGraph. " +
                "Use open(Path, DistanceFunction) for in-memory segments.");
        }

        // Delegate to the Path-based open method
        return open(path, distanceFunction);
    }

    /**
     * Creates a new HNSW graph using the Segment memory abstraction.
     *
     * <p>This method creates a graph using the {@link Segment} abstraction layer,
     * which automatically selects the appropriate implementation (Panama FFM or
     * ByteBuffer fallback) based on runtime capabilities.</p>
     *
     * <p><strong>Implementation Note:</strong> This method creates a file at the
     * specified path and maps it using the Segment abstraction. The internal
     * MappedFile is created from this path for compatibility with existing code.</p>
     *
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @return A new HNSW graph
     * @throws IOException if the graph cannot be created
     * @throws NullPointerException if any parameter is null
     * @since 1.1
     */
    public static HnswGraph createWithSegment(Path path, HnswConfig config,
                                               DistanceFunction distanceFunction, long maxNodes) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(config, "Config cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        // Calculate initial file size for the Segment
        long nodeIndexSize = maxNodes * NODE_INDEX_ENTRY_SIZE;
        long estimatedVectorSize = config.vectorByteSize();
        long estimatedAdjacencySize = config.adjacencyByteSizeLayer0() +
            (long) config.maxConnections() * config.adjacencyByteSize();
        long estimatedNodeSize = 8 + 4 + 4 + estimatedVectorSize + estimatedAdjacencySize;
        long initialDataSize = Math.max(1024 * 1024, maxNodes / 10 * estimatedNodeSize);
        long totalSize = HEADER_SIZE + nodeIndexSize + initialDataSize;

        // Create the file using Segment abstraction (validates Panama availability)
        try (Segment segment = MemorySegmentFactory.mapFile(path, totalSize)) {
            // Segment is created and immediately closed; actual mapping done by MappedFile
            LOGGER.debug("Pre-validated Segment creation at {} with size {}", path, totalSize);
        }

        // Create the actual graph using the standard constructor
        return new HnswGraph(path, config, distanceFunction, maxNodes);
    }

    /**
     * Reads header information from a Segment without fully opening the graph.
     *
     * <p>This utility method allows inspecting a graph file's metadata without
     * the overhead of creating a full HnswGraph instance. Useful for validation
     * and metadata extraction.</p>
     *
     * @param segment The Segment to read header from
     * @return HeaderInfo containing metadata, or null if invalid
     * @throws NullPointerException if segment is null
     * @since 1.1
     */
    public static HeaderInfo readHeaderInfo(Segment segment) {
        Objects.requireNonNull(segment, "Segment cannot be null");

        if (segment.byteSize() < HEADER_SIZE) {
            return null;
        }

        try {
            long magic = segment.getLong(OFFSET_MAGIC);
            if (magic != MAGIC) {
                return null;
            }

            int version = segment.getInt(OFFSET_VERSION);
            if (!isSupportedVersion(version)) {
                // The remaining fields may sit at other offsets in a version this build
                // does not know, so reporting them would be reporting noise.
                LOGGER.debug("Segment holds an HNSW header at unsupported version {}", version);
                return null;
            }

            String distanceMetric = version == VERSION_WITHOUT_METRIC
                ? PersistedMetric.LEGACY_METRIC
                : PersistedMetric.nameFor(segment.getInt(OFFSET_METRIC));

            int dimension = segment.getInt(OFFSET_DIMENSION);
            long nodeCount = segment.getLong(OFFSET_NODE_COUNT);
            long entryPoint = segment.getLong(OFFSET_ENTRY_POINT);
            int maxLevel = segment.getInt(OFFSET_MAX_LEVEL);
            long lastIntegrated = segment.getLong(OFFSET_LAST_INTEGRATED);
            int maxConn = segment.getInt(OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = segment.getInt(OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = segment.getInt(OFFSET_CONFIG_EF_CONSTRUCTION);
            double mL = Double.longBitsToDouble(segment.getLong(OFFSET_CONFIG_ML));
            int efSearch = segment.getInt(OFFSET_CONFIG_EF_SEARCH);

            return new HeaderInfo(version, dimension, nodeCount, entryPoint, maxLevel,
                lastIntegrated, maxConn, maxConn0, efConstruction, efSearch, mL, distanceMetric);
        } catch (Exception e) {
            LOGGER.debug("Failed to read header info from segment: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Immutable record containing HNSW graph header information.
     *
     * @since 1.1
     */
   public record HeaderInfo(
        int version,
        int dimension,
        long nodeCount,
        long entryPoint,
        int maxLevel,
        long lastIntegratedOffset,
        int maxConnections,
        int maxConnectionsLayer0,
        int efConstruction,
        int efSearch,
        double mL,
        String distanceMetric
    ) {
        /**
         * Creates an HnswConfig from this header info.
         *
         * @return A new HnswConfig with the header values
         */
        public HnswConfig toConfig() {
            return new HnswConfig(maxConnections, maxConnectionsLayer0,
                efConstruction, efSearch, mL, dimension);
        }
    }

    /**
     * Estimates the size of a node based on its level.
     */
    private static int estimateNodeSize(HnswConfig config, int level) {
        int vectorBytes = config.vectorByteSize();
        int adjacencyBytes = 0;
        for (int l = 0; l <= level; l++) {
            int maxConnAtLevel = (l == 0) ? config.maxConnectionsLayer0() : config.maxConnections();
            adjacencyBytes += 4 + (maxConnAtLevel * Integer.BYTES);
        }
        return 8 + 4 + 4 + vectorBytes + adjacencyBytes;
    }

    /**
     * Initializes the file header.
     */
    private void initializeHeader() throws IOException {
        lock.writeLock().lock();
        try {
            mappedFile.writeLong(OFFSET_MAGIC, MAGIC);
            mappedFile.writeInt(OFFSET_VERSION, VERSION);
            mappedFile.writeInt(OFFSET_DIMENSION, config.dimension());
            mappedFile.writeLong(OFFSET_NODE_COUNT, 0);
            mappedFile.writeLong(OFFSET_ENTRY_POINT, -1);
            mappedFile.writeInt(OFFSET_MAX_LEVEL, -1);
            mappedFile.writeLong(OFFSET_LAST_INTEGRATED, 0);
            mappedFile.writeInt(OFFSET_CONFIG_MAX_CONN, config.maxConnections());
            mappedFile.writeInt(OFFSET_CONFIG_MAX_CONN_0, config.maxConnectionsLayer0());
            mappedFile.writeInt(OFFSET_CONFIG_EF_CONSTRUCTION, config.efConstruction());
            mappedFile.writeInt(OFFSET_CONFIG_EF_SEARCH, config.efSearch());
            mappedFile.writeLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));
            mappedFile.writeLong(OFFSET_MAX_NODES, maxNodes);
            mappedFile.writeInt(OFFSET_METRIC, PersistedMetric.codeFor(distanceFunction.name()));

            this.dataWriteOffset = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Public API - Search ====================

    /**
     * Searches for the k nearest neighbors to the query vector.
     *
     * <p>This method performs hierarchical search starting from the entry point,
     * navigating through the graph layers to find approximate nearest neighbors.
     * The search is performed by reading directly from the memory-mapped file,
     * without loading the entire graph into memory.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Start at entry point at the highest layer</li>
     *   <li>Greedy search at each layer to find closest node</li>
     *   <li>At layer 0, perform beam search with efSearch candidates</li>
     *   <li>Return top-k results</li>
     * </ol>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @return List of search results sorted by distance (closest first)
     * @throws IllegalStateException if graph is closed or empty
     * @throws IllegalArgumentException if query is null or wrong dimension
     */
    public List<HnswSearchResult> search(float[] query, int k) {
        validateNotClosed();
        validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        lock.readLock().lock();
        try {
            if (nodeCount == 0 || entryPointId < 0) {
                return new ArrayList<>();
            }

            // Start from entry point
            long currentNodeId = entryPointId;
            NodeView current = readNode(currentNodeId);

            if (current == null || current.isTombstoned()) {
                // Entry point is tombstoned, need to find a new starting point
                currentNodeId = findActiveNode();
                if (currentNodeId < 0) {
                    return new ArrayList<>();
                }
                current = readNode(currentNodeId);
            }

            final float[] queryVec = query;
            // The layer-0 beam is trimmed to ef entries, so a beam narrower than k
            // can never yield k results.
            final int efSearch = Math.max(config.efSearch(), k);

            // Search through layers from top to layer 1
            for (int level = maxLevel; level > 0; level--) {
                // Greedy search: find the closest node at this level
                NodeView closest = searchLayerSimple(queryVec, current, level);
                if (closest != null) {
                    current = closest;
                }
            }

            // At layer 0, perform beam search
            PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
                Comparator.comparingDouble(HnswSearchResult::distance).reversed()
            );

            PriorityQueue<HnswSearchResult> results = searchLayerBeam(
                queryVec, current, 0, efSearch);

            // Extract ALL results from the heap and find top-k closest
            // Note: results is a max-heap, so polling gives furthest first
            // We need to extract all, then sort to get the k closest
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                // Verify node is not tombstoned
                NodeView node = readNode(result.nodeId());
                if (node != null && !node.isTombstoned()) {
                    allResults.add(result);
                }
            }

            // Sort by distance (closest first)
            allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));

            // Take top-k closest results
            List<HnswSearchResult> topResults = new ArrayList<>();
            for (int i = 0; i < Math.min(k, allResults.size()); i++) {
                topResults.add(allResults.get(i));
            }

            return topResults;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Searches for the k nearest neighbors to the query vector within a candidate set.
     *
     * <p>A pre-filter names the nodes worth measuring, and this method answers with the nearest of
     * them. The names are a scattering of ids drawn from wherever the filter's buckets happened to
     * fall, not a connected region of the graph, so the graph's edges are used to <em>reach</em>
     * named nodes and never to decide which of them may be returned. A walk permitted to step only
     * from one named node to another is walking an edgeless subgraph, and comes back with whatever
     * it started on.</p>
     *
     * <h3>Algorithm</h3>
     * <p>Two ways to answer, chosen by which does less work:</p>
     * <ul>
     *   <li><strong>Measure every named node</strong>, when the set is small. Exact, at one node
     *       read per name.</li>
     *   <li><strong>Walk the graph</strong> otherwise: the ordinary descent and layer-0 beam over
     *       the whole graph, admitting only named nodes to the results.</li>
     * </ul>
     *
     * <p>The line between them follows from what each costs. Measuring reads {@code |C|} nodes. A
     * walk keeps exploring until it holds {@code ef} admitted results and admits a
     * {@code |C| / nodeCount} fraction of what it visits, so it reads on the order of
     * {@code ef * nodeCount / |C|}. Measuring is the cheaper of the two while
     * {@code |C|² <= ef * nodeCount}, and it is also the exact one, so that is where the line
     * sits.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>A null candidate set means "no restriction" and searches the whole graph; an empty one
     *       means "nothing is admissible" and returns nothing</li>
     *   <li>Only nodes in the candidate set are returned</li>
     *   <li>Results are sorted by distance (closest first)</li>
     *   <li>Tombstoned nodes, and ids naming no node, are skipped</li>
     * </ul>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @param candidateIds Set of node IDs to search within (null = full search)
     * @return List of search results sorted by distance (closest first)
     * @throws IllegalStateException if graph is closed
     * @throws IllegalArgumentException if query is null or wrong dimension, or {@code k <= 0}
     * @since 1.2
     */
    public List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
        validateNotClosed();
        validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        if (candidateIds == null) {
            return search(query, k);
        }
        if (candidateIds.isEmpty()) {
            return new ArrayList<>();
        }

        lock.readLock().lock();
        try {
            if (nodeCount == 0 || entryPointId < 0) {
                return new ArrayList<>();
            }

            // As in search(): the beam is trimmed to ef entries, so one narrower than k can never
            // yield k results.
            final int ef = Math.max(config.efSearch(), k * 2);

            long named = candidateIds.size();
            if (named * named <= (long) ef * nodeCount) {
                return measureCandidates(query, k, candidateIds);
            }
            return walkAdmitting(query, k, ef, candidateIds);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Answers a candidate-restricted search by measuring every named node.
     *
     * <p>Exact, and independent of how the graph happens to link the names. Must be called with
     * the read lock held.</p>
     */
    private List<HnswSearchResult> measureCandidates(float[] query, int k, Set<Long> candidateIds) {
        List<HnswSearchResult> measured = new ArrayList<>(candidateIds.size());
        for (long nodeId : candidateIds) {
            NodeView node = readNode(nodeId);
            if (node == null || node.isTombstoned()) {
                continue;
            }
            measured.add(createSearchResult(nodeId, computeDistance(query, node.vector())));
        }

        measured.sort(Comparator.comparingDouble(HnswSearchResult::distance));
        return new ArrayList<>(measured.subList(0, Math.min(k, measured.size())));
    }

    /**
     * Answers a candidate-restricted search with the ordinary graph walk, admitting only named
     * nodes to the results.
     *
     * <p>Traversal is unrestricted: a named node is reached through whatever unnamed nodes lie
     * between it and the entry point, which is the only way a scattered set can be searched at
     * all. Must be called with the read lock held.</p>
     */
    private List<HnswSearchResult> walkAdmitting(
            float[] query, int k, int ef, Set<Long> candidateIds) {

        NodeView current = readNode(entryPointId);
        if (current == null || current.isTombstoned()) {
            long active = findActiveNode();
            if (active < 0) {
                return new ArrayList<>();
            }
            current = readNode(active);
            if (current == null) {
                return new ArrayList<>();
            }
        }

        for (int level = maxLevel; level > 0; level--) {
            NodeView closest = searchLayerSimple(query, current, level);
            if (closest != null) {
                current = closest;
            }
        }

        PriorityQueue<HnswSearchResult> results =
            searchLayerBeam(query, current, 0, ef, candidateIds);

        List<HnswSearchResult> admitted = new ArrayList<>();
        while (!results.isEmpty()) {
            HnswSearchResult result = results.poll();
            // The beam admitted only named nodes; this re-read is for one tombstoned since.
            NodeView node = readNode(result.nodeId());
            if (node != null && !node.isTombstoned()) {
                admitted.add(result);
            }
        }

        admitted.sort(Comparator.comparingDouble(HnswSearchResult::distance));
        return new ArrayList<>(admitted.subList(0, Math.min(k, admitted.size())));
    }

    /**
     * Simple greedy search at a single layer.
     *
     * <p>Tombstoned nodes are automatically skipped during traversal.</p>
     */
    private NodeView searchLayerSimple(float[] query, NodeView entry, int level) {
        NodeView current = entry;
        float currentDist = computeDistance(query, current.vector());
        boolean improved = true;

        while (improved) {
            improved = false;
            // Use getActiveNeighbors to automatically filter tombstoned nodes
            List<Long> neighbors = current.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                NodeView neighbor = readNode(neighborId);
                // Double-check in case neighbor became tombstoned after getActiveNeighbors
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = computeDistance(query, neighbor.vector());
                if (dist < currentDist) {
                    currentDist = dist;
                    current = neighbor;
                    improved = true;
                }
            }
        }

        return current;
    }

    /**
     * Beam search at a layer, admitting every node it reaches.
     *
     * <p>Tombstoned nodes are automatically skipped during traversal:
     * <ul>
     *   <li>The entry node is checked for tombstone status before starting</li>
     *   <li>All neighbor lists are filtered to exclude tombstoned nodes</li>
     *   <li>Results are verified to exclude any nodes that became tombstoned during search</li>
     * </ul>
     * </p>
     */
    private PriorityQueue<HnswSearchResult> searchLayerBeam(
            float[] query, NodeView entry, int level, int ef) {
        return searchLayerBeam(query, entry, level, ef, null);
    }

    /**
     * Beam search at a layer, admitting only the named nodes to the results.
     *
     * <p>The walk itself is never restricted. Every neighbour is stepped through and measured, and
     * the named set decides only which of them may be kept, so a node the caller asked for is
     * reached through whatever unnamed nodes lie between it and the entry point. Restricting the
     * walk instead would confine it to the subgraph induced on the named set, which for a set drawn
     * from a pre-filter's buckets has almost no edges at all.</p>
     *
     * <p>Because a traversed node is no longer necessarily a kept one, the walk stops on the beam
     * being both full and no longer improvable - not on the frontier passing the worst result,
     * which for an unfilled beam would stop it before it had an answer.</p>
     *
     * @param admissible ids that may enter the results, or null to admit every node reached
     */
    private PriorityQueue<HnswSearchResult> searchLayerBeam(
            float[] query, NodeView entry, int level, int ef, Set<Long> admissible) {

        // Max-heap for results (furthest first so we can remove worst and peek at furthest)
        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        // Min-heap for candidates (closest first so we process nearest first)
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance)
        );

        // Visited set
        java.util.Set<Long> visited = new java.util.HashSet<>();

        if (entry == null || entry.isTombstoned()) {
            return results;
        }

        float entryDist = computeDistance(query, entry.vector());
        HnswSearchResult entryResult = createSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        if (admissible == null || admissible.contains(entry.nodeId())) {
            results.add(entryResult);
        }
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            NodeView currentNode = readNode(current.nodeId());

            if (currentNode == null || currentNode.isTombstoned()) {
                continue;
            }

            // Nothing ahead of the frontier can improve a beam that is already full.
            // results.peek() returns furthest since it's a max-heap
            if (results.size() >= ef && current.distance() > results.peek().distance()) {
                break;
            }

            // Use getActiveNeighbors to automatically filter tombstoned nodes
            List<Long> neighbors = currentNode.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                if (visited.contains(neighborId)) {
                    continue;
                }
                visited.add(neighborId);

                NodeView neighbor = readNode(neighborId);
                // Double-check in case neighbor became tombstoned after getActiveNeighbors
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = computeDistance(query, neighbor.vector());
                HnswSearchResult result = createSearchResult(neighborId, dist);

                // Step through it while the beam is unfilled or it beats the worst kept result
                if (results.size() < ef || dist < results.peek().distance()) {
                    candidates.add(result);

                    if (admissible == null || admissible.contains(neighborId)) {
                        results.add(result);

                        // Keep results bounded
                        while (results.size() > ef) {
                            results.poll();
                        }
                    }
                }
            }
        }

        return results;
    }

    // ==================== Public API - Insertion ====================

    /**
     * Inserts a vector into the HNSW graph.
     *
     * <p>This method performs incremental insertion without requiring a full rebuild.
     * The algorithm:</p>
     * <ol>
     *   <li>Generates a random level for the new node based on mL parameter</li>
     *   <li>Finds entry points at each layer using greedy search</li>
     *   <li>Connects the new node to its nearest neighbors at each layer</li>
     *   <li>Updates neighbor connections to maintain max connection limits</li>
     * </ol>
     *
     * @param vector The vector to insert
     * @return The ID of the inserted node
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public long insert(float[] vector) {
        validateNotClosed();
        validateVector(vector);

        lock.writeLock().lock();
        try {
            // Generate node ID
            long nodeId = nodeIdGenerator.getAndIncrement();

            // Check capacity
            if (nodeCount >= maxNodes) {
                throw new IllegalStateException("Graph is full: " + nodeCount + "/" + maxNodes);
            }

            // Generate random level for this node
            int level = generateRandomLevel();

            // Write node to disk
            long dataOffset = writeNode(nodeId, level, vector);
            updateNodeIndex(nodeId, dataOffset, level, false);

            // Track if this node should become the new entry point
            boolean becomesEntryPoint = (entryPointId < 0 || level > maxLevel);
            long previousEntryPoint = entryPointId;
            int previousMaxLevel = maxLevel;

            // Update entry point if this is the first node or highest level
            if (becomesEntryPoint) {
                entryPointId = nodeId;
                maxLevel = level;
                updateHeader();
            }

            nodeCount++;
            mappedFile.writeLong(OFFSET_NODE_COUNT, nodeCount);

            // If this is the first node, no connections to make
            if (nodeCount == 1) {
                return nodeId;
            }

            // Connect node to existing graph
            // Even if this node becomes the new entry point, we need to connect it
            // to the previous graph structure
            connectNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);

            LOGGER.debug("Inserted node {} at level {} (total nodes: {})",
                nodeId, level, nodeCount);

            return nodeId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Public API - Incremental Insertion ====================

    /**
     * Inserts a vector with full result metadata for write log integration.
     *
     * <p>This method provides detailed information about the insertion for
     * integration with the write log and crash recovery system. It returns
     * an {@link IncrementalInsertResult} containing:</p>
     * <ul>
     *   <li>The node ID assigned</li>
     *   <li>The level in the hierarchy</li>
     *   <li>The data offset for potential recovery</li>
     *   <li>Number of connections made</li>
     *   <li>List of connected nodes</li>
     * </ul>
     *
     * @param vector The vector to insert
     * @return IncrementalInsertResult with insertion metadata
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public IncrementalInsertResult insertWithResult(float[] vector) {
        return insertWithResult(vector, -1);
    }

    /**
     * Inserts a vector with write log offset for crash recovery.
     *
     * <p>This overload allows associating the insertion with a specific
     * write log offset, which is used for recovery after crashes.</p>
     *
     * @param vector The vector to insert
     * @param writeLogOffset The write log offset this insertion corresponds to (-1 if not from write log)
     * @return IncrementalInsertResult with insertion metadata
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public IncrementalInsertResult insertWithResult(float[] vector, long writeLogOffset) {
        validateNotClosed();
        validateVector(vector);

        lock.writeLock().lock();
        try {
            // Generate node ID
            long nodeId = nodeIdGenerator.getAndIncrement();

            // Check capacity
            if (nodeCount >= maxNodes) {
                return IncrementalInsertResult.FAILED;
            }

            // Generate random level for this node
            int level = generateRandomLevel();

            // Write node to disk
            long dataOffset = writeNode(nodeId, level, vector);
            updateNodeIndex(nodeId, dataOffset, level, false);

            // Track if this node should become the new entry point
            boolean becomesEntryPoint = (entryPointId < 0 || level > maxLevel);
            long previousEntryPoint = entryPointId;
            int previousMaxLevel = maxLevel;

            // Update entry point if this is the first node or highest level
            if (becomesEntryPoint) {
                entryPointId = nodeId;
                maxLevel = level;
                updateHeader();
            }

            nodeCount++;
            mappedFile.writeLong(OFFSET_NODE_COUNT, nodeCount);

            // Track connections made
            List<Long> connectedNodes = new ArrayList<>();
            int connectionsMade = 0;

            // If this is the first node, no connections to make
            if (nodeCount > 1) {
                // Connect node to existing graph
                connectedNodes = connectNodeAndTrack(nodeId, level, vector,
                    previousEntryPoint, previousMaxLevel);
                connectionsMade = connectedNodes.size();
            }

            LOGGER.debug("Incrementally inserted node {} at level {} (connections: {}, total nodes: {})",
                nodeId, level, connectionsMade, nodeCount);

            return new IncrementalInsertResult(nodeId, level, dataOffset,
                connectionsMade, writeLogOffset, connectedNodes);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Connects a node to the graph and returns the list of connected nodes.
     *
     * <p>This is similar to {@link #connectNode} but tracks which nodes were connected
     * for the incremental insertion result.</p>
     *
     * @return List of node IDs that were connected to
     */
    private List<Long> connectNodeAndTrack(long nodeId, int nodeLevel, float[] vector,
                                           long startEntryPoint, int startMaxLevel) {
        List<Long> allConnectedNodes = new ArrayList<>();

        NodeView newNode = readNode(nodeId);
        if (newNode == null) {
            return allConnectedNodes;
        }

        if (startEntryPoint < 0) {
            return allConnectedNodes;
        }

        NodeView current = readNode(startEntryPoint);
        if (current == null || current.isTombstoned()) {
            current = readNode(findActiveNode());
            if (current == null) {
                return allConnectedNodes;
            }
        }

        // Search for entry points at each level
        for (int level = startMaxLevel; level > nodeLevel; level--) {
            NodeView closest = searchLayerSimple(vector, current, level);
            if (closest != null) {
                current = closest;
            }
        }

        // Connect at each level from nodeLevel down to 0
        for (int level = Math.min(nodeLevel, startMaxLevel); level >= 0; level--) {
            PriorityQueue<HnswSearchResult> neighbors = searchLayerBeam(
                vector, current, level, config.efConstruction());

            List<Long> selectedNeighbors = selectNeighbors(neighbors,
                level == 0 ? config.maxConnectionsLayer0() : config.maxConnections());

            selectedNeighbors.removeIf(nid -> {
                NodeView n = readNode(nid);
                return n == null || n.isTombstoned();
            });

            // Connect neighbors back to new node first (to check which accept us)
            // Only keep neighbors that successfully add us to their list (bidirectional)
            List<Long> successfulNeighbors = new ArrayList<>();
            for (long neighborId : selectedNeighbors) {
                boolean added = addConnection(neighborId, level, nodeId);
                if (added) {
                    successfulNeighbors.add(neighborId);
                    if (!allConnectedNodes.contains(neighborId)) {
                        allConnectedNodes.add(neighborId);
                    }
                }
            }

            // Now connect new node only to neighbors that accepted us
            addConnections(nodeId, level, successfulNeighbors);

            // Update current for next level
            if (!neighbors.isEmpty()) {
                HnswSearchResult closest = null;
                for (HnswSearchResult r : neighbors) {
                    NodeView n = readNode(r.nodeId());
                    if (n != null && !n.isTombstoned()) {
                        if (closest == null || r.distance() < closest.distance()) {
                            closest = r;
                        }
                    }
                }
                if (closest != null) {
                    current = readNode(closest.nodeId());
                }
            }
        }

        return allConnectedNodes;
    }

    /**
     * Inserts multiple vectors in batch for efficient bulk loading.
     *
     * <p>Batch insertion is more efficient than individual insertions because:
     * <ul>
     *   <li>Write lock is acquired once for the entire batch</li>
     *   <li>Vector data is written sequentially for better I/O patterns</li>
     *   <li>Graph structure is built incrementally within the batch</li>
     * </ul>
     * </p>
     *
     * <p><strong>Note:</strong> This does NOT perform batch optimization like
     * bulk loading algorithms. Each vector is inserted incrementally, but
     * with reduced locking overhead.</p>
     *
     * @param vectors List of vectors to insert
     * @return List of node IDs for inserted vectors (in order)
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vectors is null or contains wrong dimensions
     */
    public List<Long> insertBatch(List<float[]> vectors) {
        if (vectors == null) {
            throw new IllegalArgumentException("Vectors list cannot be null");
        }

        validateNotClosed();

        List<Long> nodeIds = new ArrayList<>(vectors.size());

        lock.writeLock().lock();
        try {
            for (float[] vector : vectors) {
                validateVector(vector);

                // Check capacity
                if (nodeCount >= maxNodes) {
                    throw new IllegalStateException("Graph is full: " + nodeCount + "/" + maxNodes);
                }

                long nodeId = nodeIdGenerator.getAndIncrement();
                int level = generateRandomLevel();
                long dataOffset = writeNode(nodeId, level, vector);
                updateNodeIndex(nodeId, dataOffset, level, false);

                boolean becomesEntryPoint = (entryPointId < 0 || level > maxLevel);
                long previousEntryPoint = entryPointId;
                int previousMaxLevel = maxLevel;

                if (becomesEntryPoint) {
                    entryPointId = nodeId;
                    maxLevel = level;
                    updateHeader();
                }

                nodeCount++;
                mappedFile.writeLong(OFFSET_NODE_COUNT, nodeCount);

                if (nodeCount > 1) {
                    connectNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);
                }

                nodeIds.add(nodeId);
            }

            LOGGER.debug("Batch inserted {} vectors (total nodes: {})",
                vectors.size(), nodeCount);

            return nodeIds;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts multiple vectors with full result metadata for write log integration.
     *
     * <p>Combines batch insertion efficiency with detailed result tracking
     * for each inserted vector.</p>
     *
     * @param vectors List of vectors to insert
     * @param writeLogOffsets Corresponding write log offsets (must match vectors size, or null)
     * @return List of IncrementalInsertResult for each insertion
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vectors is null or sizes don't match
     */
    public List<IncrementalInsertResult> insertBatchWithResults(
            List<float[]> vectors, List<Long> writeLogOffsets) {
        if (vectors == null) {
            throw new IllegalArgumentException("Vectors list cannot be null");
        }
        if (writeLogOffsets != null && writeLogOffsets.size() != vectors.size()) {
            throw new IllegalArgumentException(
                "Write log offsets size must match vectors size");
        }

        validateNotClosed();

        List<IncrementalInsertResult> results = new ArrayList<>(vectors.size());

        lock.writeLock().lock();
        try {
            for (int i = 0; i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                validateVector(vector);

                if (nodeCount >= maxNodes) {
                    // Return partial results with FAILED for remaining
                    results.add(IncrementalInsertResult.FAILED);
                    continue;
                }

                long writeLogOffset = writeLogOffsets != null ? writeLogOffsets.get(i) : -1;
                results.add(insertWithResultInternal(vector, writeLogOffset));
            }

            LOGGER.debug("Batch inserted {} vectors with results (total nodes: {})",
                vectors.size(), nodeCount);

            return results;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal method for incremental insertion within a write lock.
     */
    private IncrementalInsertResult insertWithResultInternal(float[] vector, long writeLogOffset) {
        long nodeId = nodeIdGenerator.getAndIncrement();

        if (nodeCount >= maxNodes) {
            return IncrementalInsertResult.FAILED;
        }

        int level = generateRandomLevel();
        long dataOffset = writeNode(nodeId, level, vector);
        updateNodeIndex(nodeId, dataOffset, level, false);

        boolean becomesEntryPoint = (entryPointId < 0 || level > maxLevel);
        long previousEntryPoint = entryPointId;
        int previousMaxLevel = maxLevel;

        if (becomesEntryPoint) {
            entryPointId = nodeId;
            maxLevel = level;
            updateHeader();
        }

        nodeCount++;
        mappedFile.writeLong(OFFSET_NODE_COUNT, nodeCount);

        List<Long> connectedNodes = new ArrayList<>();
        int connectionsMade = 0;

        if (nodeCount > 1) {
            connectedNodes = connectNodeAndTrack(nodeId, level, vector,
                previousEntryPoint, previousMaxLevel);
            connectionsMade = connectedNodes.size();
        }

        return new IncrementalInsertResult(nodeId, level, dataOffset,
            connectionsMade, writeLogOffset, connectedNodes);
    }

    /**
     * Connects a newly inserted node to the graph.
     *
     * <p>This method skips tombstoned nodes when searching for neighbors to connect to.
     * Tombstoned nodes are never selected as connection targets.</p>
     *
     * @param nodeId The node to connect
     * @param nodeLevel The level of the node
     * @param vector The node's vector
     * @param startEntryPoint The entry point to start searching from (may be different from current entry point)
     * @param startMaxLevel The max level to start searching from
     */
    private void connectNode(long nodeId, int nodeLevel, float[] vector,
                             long startEntryPoint, int startMaxLevel) {
        NodeView newNode = readNode(nodeId);
        if (newNode == null) {
            return;
        }

        // Start from the provided entry point (could be the previous entry point
        // if this node is becoming the new entry point)
        if (startEntryPoint < 0) {
            return; // No nodes to connect to
        }

        NodeView current = readNode(startEntryPoint);
        // Skip tombstoned entry points
        if (current == null || current.isTombstoned()) {
            current = readNode(findActiveNode());
            if (current == null) {
                return;
            }
        }

        // Search for entry points at each level
        for (int level = startMaxLevel; level > nodeLevel; level--) {
            NodeView closest = searchLayerSimple(vector, current, level);
            if (closest != null) {
                current = closest;
            }
        }

        // Connect at each level from nodeLevel down to 0
        for (int level = Math.min(nodeLevel, startMaxLevel); level >= 0; level--) {
            // Find efConstruction nearest neighbors at this level
            // searchLayerBeam automatically filters tombstoned nodes
            PriorityQueue<HnswSearchResult> neighbors = searchLayerBeam(
                vector, current, level, config.efConstruction());

            // Select neighbors (simple heuristic: take closest M)
            List<Long> selectedNeighbors = selectNeighbors(neighbors,
                level == 0 ? config.maxConnectionsLayer0() : config.maxConnections());

            // Filter out any tombstoned neighbors (defensive check)
            selectedNeighbors.removeIf(nid -> {
                NodeView n = readNode(nid);
                return n == null || n.isTombstoned();
            });

            // Connect neighbors back to new node first (to check which accept us)
            // Only keep neighbors that successfully add us to their list
            List<Long> successfulNeighbors = new ArrayList<>();
            for (long neighborId : selectedNeighbors) {
                boolean added = addConnection(neighborId, level, nodeId);
                if (added) {
                    successfulNeighbors.add(neighborId);
                }
            }

            // Now connect new node only to neighbors that accepted us (bidirectional)
            addConnections(nodeId, level, successfulNeighbors);

            // Update current for next level - use closest neighbor
            if (!neighbors.isEmpty()) {
                // Find the closest neighbor (min distance in max-heap)
                HnswSearchResult closest = null;
                for (HnswSearchResult r : neighbors) {
                    NodeView n = readNode(r.nodeId());
                    if (n != null && !n.isTombstoned()) {
                        if (closest == null || r.distance() < closest.distance()) {
                            closest = r;
                        }
                    }
                }
                if (closest != null) {
                    current = readNode(closest.nodeId());
                }
            }
        }
    }

    /**
     * Selects neighbors from candidate set.
     */
    private List<Long> selectNeighbors(PriorityQueue<HnswSearchResult> candidates, int maxConn) {
        List<Long> selected = new ArrayList<>();
        List<HnswSearchResult> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        for (HnswSearchResult candidate : sorted) {
            if (selected.size() >= maxConn) {
                break;
            }
            selected.add(candidate.nodeId());
        }

        return selected;
    }

    /**
     * Generates a random level for a new node.
     *
     * <p>Uses the exponential distribution: P(level = l) = (1 - mL^l) * mL</p>
     */
    private int generateRandomLevel() {
        Random random = ThreadLocalRandom.current();
        double r = -Math.log(1 - random.nextDouble()) * config.mL();
        return (int) Math.min(r, HnswConfig.MAX_LEVELS - 1);
    }

    // ==================== Public API - Deletion ====================

    /**
     * Marks a node as deleted (soft-delete with tombstone).
     *
     * <p>The node is marked with a tombstone flag and will be skipped during
     * search operations. The actual data is not removed from disk until
     * compaction is performed.</p>
     *
     * <h3>Soft-Delete Behavior</h3>
     * <ul>
     *   <li>The node remains in the graph structure but is marked as deleted</li>
     *   <li>All traversal operations skip tombstoned nodes</li>
     *   <li>Search results never include tombstoned nodes</li>
     *   <li>The node's connections are preserved (for potential future compaction)</li>
     *   <li>If the entry point is deleted, a new active entry point is found</li>
     * </ul>
     *
     * @param nodeId The ID of the node to delete
     * @return true if the node was found and deleted, false otherwise
     * @throws IllegalStateException if graph is closed
     */
    public boolean delete(long nodeId) {
        validateNotClosed();

        lock.writeLock().lock();
        try {
            // A negative id would compute an offset inside the header and the
            // tombstone write below would corrupt header fields.
            if (nodeId < 0 || nodeId >= nodeIdGenerator.get()) {
                return false;
            }

            long indexOffset = HEADER_SIZE + nodeId * NODE_INDEX_ENTRY_SIZE;
            if (indexOffset + NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
                return false;
            }

            // Read current flags
            long dataOffset = mappedFile.readLong(indexOffset + 8);
            int level = mappedFile.readInt(indexOffset + 16);
            int flags = mappedFile.readInt(indexOffset + 20);

            if ((flags & FLAG_TOMBSTONE) != 0) {
                return false; // Already tombstoned
            }

            // Set tombstone flag
            flags |= FLAG_TOMBSTONE;
            mappedFile.writeInt(indexOffset + 20, flags);

            // If we deleted the entry point, find a new one at the highest level
            // This maintains the HNSW invariant: entry point must be at the highest layer
            if (nodeId == entryPointId) {
                long newEntryPoint = findHighestLevelActiveNode();
                entryPointId = newEntryPoint;
                if (newEntryPoint >= 0) {
                    NodeView newEntry = readNode(newEntryPoint);
                    if (newEntry != null) {
                        maxLevel = newEntry.level();
                    }
                } else {
                    maxLevel = -1;
                }
                updateHeader();
            }

            LOGGER.debug("Tombstoned node {}", nodeId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Retires every node a named document contributed.
     *
     * <p>A node carries no text, so an erased document's vectors look like harmless residue. They
     * are not: a node is attributed to a document, and a search that ranks it says that document
     * matched - with a distance, and with the document's name - whether or not there is any text
     * left to show for it. This is what makes an erasure reach the vector index.</p>
     *
     * <p>Each node is retired by {@link #delete(long)}, so the tombstones are the same ones every
     * traversal skips, a restart reads back, and {@link #compact(java.nio.file.Path)} reclaims.
     * Nodes are attributed by {@link #registerDocumentId(long, String)}, which the integration
     * worker calls as it inserts; a graph with no mapping has nothing to name and retires
     * nothing.</p>
     *
     * @param documentId The document to retire, in the form node attribution uses
     * @return The number of nodes newly tombstoned; zero when the graph holds none of them, or
     *         when they were already retired
     * @throws IllegalStateException if graph is closed
     */
    public int deleteDocument(String documentId) {
        validateNotClosed();

        NodeDocumentMapping mapping = this.nodeDocumentMapping;
        if (documentId == null || mapping == null) {
            return 0;
        }

        int retired = 0;
        for (long nodeId : mapping.getNodesForDocument(documentId)) {
            if (delete(nodeId)) {
                retired++;
            }
        }

        if (retired > 0) {
            LOGGER.info("Retired {} node(s) of document {}", retired, documentId);
        }
        return retired;
    }

    /**
     * Checks if a node is tombstoned (soft-deleted).
     *
     * <p>This method provides a way to check the deletion status of a node
     * without needing to obtain a NodeView.</p>
     *
     * @param nodeId The ID of the node to check
     * @return true if the node is tombstoned, false if active or not found
     * @throws IllegalStateException if graph is closed
     */
    public boolean isTombstoned(long nodeId) {
        validateNotClosed();

        lock.readLock().lock();
        try {
            if (nodeId < 0 || nodeId >= nodeIdGenerator.get()) {
                return false;
            }

            long indexOffset = HEADER_SIZE + nodeId * NODE_INDEX_ENTRY_SIZE;
            if (indexOffset + NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
                return false;
            }

            int flags = mappedFile.readInt(indexOffset + 20);
            return (flags & FLAG_TOMBSTONE) != 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of tombstoned (soft-deleted) nodes in the graph.
     *
     * <p>This method iterates through all nodes to count tombstoned entries.
     * The result is not cached, so this is an O(n) operation.</p>
     *
     * @return The number of tombstoned nodes
     * @throws IllegalStateException if graph is closed
     */
    public long tombstoneCount() {
        validateNotClosed();

        lock.readLock().lock();
        try {
            long count = 0;
            for (long id = 0; id < nodeCount; id++) {
                long indexOffset = HEADER_SIZE + id * NODE_INDEX_ENTRY_SIZE;
                if (indexOffset + NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
                    break;
                }
                int flags = mappedFile.readInt(indexOffset + 20);
                if ((flags & FLAG_TOMBSTONE) != 0) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of active (non-tombstoned) nodes in the graph.
     *
     * @return The number of active nodes
     * @throws IllegalStateException if graph is closed
     */
    public long activeNodeCount() {
        validateNotClosed();
        return nodeCount - tombstoneCount();
    }

    // ==================== Public API - Compaction ====================

    /**
     * Compacts the graph by removing tombstoned nodes and re-optimizing edges.
     *
     * <p>This method performs the following operations:</p>
     * <ol>
     *   <li>Creates a new graph file with only active (non-tombstoned) nodes</li>
     *   <li>Rebuilds adjacency lists to exclude tombstoned neighbors</li>
     *   <li>Atomically swaps the old graph file for the new one</li>
     *   <li>Reclaims disk space from the old file</li>
     * </ol>
     *
     * <p><strong>Thread Safety:</strong> This method acquires a write lock and
     * blocks both reads and writes during compaction. For non-blocking compaction,
     * use {@link #compactTo(Path)} to create a compacted copy.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Compaction is only performed when tombstones exist</li>
     *   <li>Document ID mappings are preserved across compaction</li>
     *   <li>PQ codes are re-encoded for active nodes if PQ is enabled</li>
     *   <li>Entry point is updated to the highest-level active node</li>
     * </ul>
     *
     * @param tempDir Retained for API compatibility; the compacted graph is written
     *                next to the live file so the final swap is an atomic rename
     *                within a single filesystem
     * @return The number of tombstoned nodes removed
     * @throws IOException if compaction fails
     * @throws IllegalStateException if graph is closed
     */
    public long compact(Path tempDir) throws IOException {
        validateNotClosed();
        Objects.requireNonNull(tempDir, "tempDir cannot be null");

        lock.writeLock().lock();
        try {
            long removed = tombstoneCount();
            if (removed == 0) {
                LOGGER.debug("No tombstoned nodes to compact");
                return 0; // Nothing to compact
            }

            // Build the compacted graph next to the original. A caller-supplied
            // temp directory can live on a different filesystem, which makes the
            // final ATOMIC_MOVE fail after the live mapping has been torn down.
            Path originalPath = this.path;
            Path compactPath = compactionStagingPath(originalPath);
            Files.deleteIfExists(compactPath);
            Files.deleteIfExists(documentMappingPath(compactPath));

            LOGGER.info("Starting HNSW compaction: {} tombstoned nodes to remove", removed);

            // Both replacement files are made durable before either becomes visible, and the
            // graph is renamed first. A crash then leaves a state the next open can read: the
            // staged mapping still beside the staged graph means the swap never started, and
            // beside no staged graph means the graph swap completed and only the mapping
            // rename is outstanding. Writing the mapping after the graph instead leaves the
            // two indistinguishable, and a compacted graph reading a pre-compaction mapping
            // answers every query with the wrong documents.
            CompactedState compacted = buildCompactedGraph(compactPath);
            swapInCompactedGraph(compactPath, originalPath, compacted.pqCodes());
            promoteCompactedMapping(compactPath, originalPath, compacted.documentMapping());

            LOGGER.info("Compacted HNSW graph: removed {} tombstoned nodes", removed);
            return removed;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes a tombstone-free copy of this graph to {@code compactPath}.
     *
     * <p>Must be called with the write lock held. The copy carries over the
     * node-to-document mapping, the product quantizer codes, and the write-log integration
     * checkpoint, so nothing has to be re-derived after the swap.</p>
     *
     * <p>The mapping is rebuilt rather than shared. Compaction renumbers nodes, so
     * registering the new IDs into the live mapping would leave it holding the old
     * numbering as well; every ID above the compacted node count would then resolve
     * to whichever document held it before, and the next insert to take that ID would
     * inherit that document.</p>
     *
     * <p>It is rebuilt onto disk, at the sidecar path belonging to {@code compactPath},
     * rather than only in memory. The compacted numbering is meaningless without it, so it
     * has to be durable before the graph that uses that numbering is renamed into place.</p>
     *
     * @param compactPath Destination for the compacted graph
     * @return The state of the compacted numbering, to be installed after the swap
     * @throws IOException if the compacted graph cannot be written
     */
    private CompactedState buildCompactedGraph(Path compactPath) throws IOException {
        java.util.concurrent.ConcurrentHashMap<Long, byte[]> oldPqCodes = this.pqCodes;
        CompactedState state;

        try (HnswGraph compacted = new HnswGraph(compactPath, config, distanceFunction, maxNodes)) {
            // The replacement graph opens its own sidecar beside itself; document identity is
            // re-registered into it below, against the new node IDs.
            NodeDocumentMapping compactedMapping = compacted.getNodeDocumentMapping();

            // Re-insert all active nodes with their original vectors
            Map<Long, Long> oldToNewId = new java.util.HashMap<>();
            for (long oldNodeId = 0; oldNodeId < nodeCount; oldNodeId++) {
                if (isTombstoned(oldNodeId)) {
                    continue;
                }

                NodeView node = readNode(oldNodeId);
                if (node == null) continue;

                float[] vector = node.vector();
                long newNodeId = compacted.insert(vector);
                oldToNewId.put(oldNodeId, newNodeId);

                // Transfer document ID mapping
                String docId = getDocumentId(oldNodeId);
                if (docId != null) {
                    compacted.registerDocumentId(newNodeId, docId);
                }
            }

            // Carry the write-log integration checkpoint across. Losing it would
            // make the integration worker rescan the write log from byte 0 and
            // re-insert every entry as a duplicate node.
            compacted.setLastIntegratedOffset(this.lastIntegratedOffset.get());

            // Forces the graph's pages and writes the sidecar through its own
            // stage-force-rename.
            compacted.flush();
            state = new CompactedState(compactedMapping.getAllMappings(),
                rekeyPqCodes(oldPqCodes, oldToNewId));
        }

        // Both staged names are forced once the files behind them are closed and on storage.
        // Without this the graph swap could be durable while the mapping staged beside it is
        // not, and the next open would see a completed swap with nothing left to finish.
        DurableFiles.syncContainingDirectory(compactPath);
        return state;
    }

    /**
     * Re-keys the PQ codes from the old numbering to the compacted one.
     *
     * <p>A compacted node holds the same vector it held before, so its codes are still the
     * right quantisation of it and only the id they are filed under has moved. Re-keying is
     * therefore exact, and it is what keeps a compaction from quietly switching quantisation
     * off for the segment: the codebook is unaffected, but codes dropped here would only ever
     * come back from another full encoding pass, and nothing triggers one on an active
     * segment.</p>
     *
     * @param oldCodes The codes in the pre-compaction numbering, possibly null
     * @param oldToNewId Which new node each surviving old node became
     * @return The codes in the compacted numbering, never null
     */
    private static Map<Long, byte[]> rekeyPqCodes(
            Map<Long, byte[]> oldCodes, Map<Long, Long> oldToNewId) {

        if (oldCodes == null || oldCodes.isEmpty()) {
            return Map.of();
        }
        Map<Long, byte[]> rekeyed = new java.util.HashMap<>(oldToNewId.size());
        for (Map.Entry<Long, Long> entry : oldToNewId.entrySet()) {
            byte[] codes = oldCodes.get(entry.getKey());
            if (codes != null) {
                rekeyed.put(entry.getValue(), codes);
            }
        }
        return rekeyed;
    }

    /**
     * What a compaction produced, in the compacted numbering, for installation after the swap.
     *
     * @param documentMapping The node-to-document mapping, already staged on disk beside the
     *                        compacted graph; carried in memory too so the live graph can
     *                        adopt it without reading the file back
     * @param pqCodes The product quantizer codes, empty when the segment has none
     */
    private record CompactedState(Map<Long, String> documentMapping, Map<Long, byte[]> pqCodes) {}

    /**
     * Renames the already-staged mapping over the live one and adopts it.
     *
     * <p>The contents were forced to storage before the graph was swapped, so this is a
     * rename and nothing more. If it fails, the staged file is deliberately left where it is:
     * it is the only record that the graph on disk is the compacted one, and
     * {@link #finishInterruptedCompaction(Path)} completes the swap from it at the next open.
     * The live graph adopts the compacted mapping regardless, so this process keeps answering
     * correctly while the on-disk pair is still half swapped.</p>
     *
     * @param compactPath      The staging path the compacted graph was built at
     * @param graphPath        The canonical path the graph is served from
     * @param compactedMapping The mapping in the compacted numbering
     */
    private void promoteCompactedMapping(Path compactPath, Path graphPath,
                                         Map<Long, String> compactedMapping) {
        Path stagedMapping = documentMappingPath(compactPath);
        Path livePath = documentMappingPath(graphPath);
        try {
            Files.move(stagedMapping, livePath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.syncContainingDirectory(livePath);
        } catch (IOException e) {
            LOGGER.error("Could not install the compacted node-to-document mapping at {}: {}. "
                + "The staged copy at {} is left in place and the next open of this graph "
                + "will finish the swap from it.", livePath, e.toString(), stagedMapping, e);
        }

        NodeDocumentMapping installed = openDocumentMapping(graphPath, false);
        installed.clear();
        for (Map.Entry<Long, String> entry : compactedMapping.entrySet()) {
            installed.register(entry.getKey(), entry.getValue());
        }
        this.nodeDocumentMapping = installed;
    }

    /**
     * Resolves whatever a compaction that did not run to completion left on disk.
     *
     * <p>A compaction replaces two files and cannot rename both at once, so a crash can land
     * between them. The staged mapping is what distinguishes the two outcomes, because it is
     * written and forced before either rename happens:</p>
     * <ul>
     *   <li>The staged graph is still there, so its rename never happened. The live graph and
     *       the mapping beside it are the pre-compaction pair and agree with each other;
     *       both staged files are stale and are removed.</li>
     *   <li>The staged graph is gone, so its rename completed and the live graph carries the
     *       compacted numbering. The mapping beside it still describes the old numbering, and
     *       the staged mapping is the one that matches - finishing its rename is what keeps
     *       node IDs resolving to the documents they actually hold.</li>
     * </ul>
     *
     * <p>A failure to finish that second case is raised rather than logged. Opening anyway
     * would serve a compacted graph through a pre-compaction mapping, which returns confident
     * answers naming the wrong documents; refusing to open says so instead.</p>
     *
     * @param graphPath The graph about to be opened
     * @throws IOException if a completed graph swap's mapping cannot be installed
     */
    private static void finishInterruptedCompaction(Path graphPath) throws IOException {
        Path compactPath = compactionStagingPath(graphPath);
        Path stagedMapping = documentMappingPath(compactPath);
        if (!Files.exists(stagedMapping)) {
            return;
        }

        if (Files.exists(compactPath)) {
            LOGGER.warn("Discarding the remains of an HNSW compaction of {} that never "
                + "swapped its graph into place.", graphPath);
            deleteQuietly(compactPath);
            deleteQuietly(stagedMapping);
            return;
        }

        Path livePath = documentMappingPath(graphPath);
        LOGGER.warn("An HNSW compaction of {} swapped its graph in and did not install the "
            + "matching node-to-document mapping. Completing the swap from {}.",
            graphPath, stagedMapping);
        try {
            Files.move(stagedMapping, livePath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("HNSW graph " + graphPath + " holds a compacted numbering "
                + "whose node-to-document mapping is still staged at " + stagedMapping
                + " and could not be installed. Opening it would attribute every result to "
                + "the wrong document.", e);
        }
        DurableFiles.syncContainingDirectory(livePath);
    }

    /**
     * Removes a leftover compaction artefact, reporting rather than raising a failure.
     *
     * <p>The pair that remains on disk is already consistent at this point, so a stray file
     * costs space and a confusing directory listing, not correctness.</p>
     *
     * @param path The artefact to remove
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not remove the abandoned compaction artefact {}: {}",
                path, e.toString());
        }
    }

    /**
     * Replaces the live graph file with the compacted one and re-maps it.
     *
     * <p>Must be called with the write lock held. If the rename fails the original
     * file is untouched, so the graph is re-mapped from it and the compacted temp
     * file is deleted - the graph is never left permanently closed by a failed
     * swap.</p>
     *
     * @param compactPath The compacted graph built by {@link #buildCompactedGraph}
     * @param originalPath The canonical path the graph is served from
     * @param rekeyedPqCodes The PQ codes in the compacted numbering
     * @throws IOException if the swap fails
     */
    private void swapInCompactedGraph(Path compactPath, Path originalPath,
                                      Map<Long, byte[]> rekeyedPqCodes) throws IOException {
        // Release the current mapping before renaming over it.
        this.closed = true;
        if (mappedFile != null) {
            try {
                mappedFile.flush();
                mappedFile.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing mapped file during compaction: {}", e.getMessage());
            }
        }
        if (arena != null) {
            try {
                arena.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing arena during compaction: {}", e.getMessage());
            }
        }

        boolean moved = false;
        try {
            Files.move(compactPath, originalPath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;

            // Compaction renumbers node IDs. Invalidate every ID handed out by the
            // previous generation so a stale ID cannot rebind to a different vector.
            this.generation++;

            // The PQ code map is keyed by node ID and describes the old numbering: new node k
            // is the (k+1)-th survivor, whose old id is larger than k as soon as anything below
            // it was tombstoned. Left as it was, every PQ distance would score a node against
            // another vector's codes; dropped, the segment would keep a quantizer that no
            // longer has codes for anything and would silently fall back to full precision
            // forever. The codes re-keyed to the compacted numbering are installed instead.
            this.pqCodes = new java.util.concurrent.ConcurrentHashMap<>(rekeyedPqCodes);

            reopenAfterCompaction(originalPath);
        } catch (IOException | RuntimeException e) {
            if (!moved) {
                rollbackFailedSwap(compactPath, originalPath);
            }
            throw e;
        }
    }

    /**
     * Restores the graph after a failed compaction swap.
     *
     * <p>The rename did not happen, so the original file still holds the live data:
     * re-map it and drop both staged files.</p>
     *
     * @param compactPath  The staging path the compacted graph was built at
     * @param originalPath The canonical path the graph is served from
     */
    private void rollbackFailedSwap(Path compactPath, Path originalPath) {
        try {
            reopenAfterCompaction(originalPath);
        } catch (Exception reopenFailure) {
            LOGGER.error("Failed to restore HNSW graph {} after an aborted compaction swap: {}",
                originalPath, reopenFailure.getMessage());
        }
        // The staged mapping goes with the staged graph. Left alone it would tell the next
        // open that a graph swap had completed, and it would install a compacted numbering
        // over the graph that was never replaced.
        deleteQuietly(compactPath);
        deleteQuietly(documentMappingPath(compactPath));
    }

    /**
     * Creates a compacted copy of the graph at the specified path.
     *
     * <p>This method does not block reads or writes to the current graph,
     * making it suitable for creating backup copies or performing online
     * compaction with minimal disruption.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Destination path must not exist or will be overwritten</li>
     *   <li>Only active (non-tombstoned) nodes are copied</li>
     *   <li>Document ID mappings are carried across, re-keyed to the copy's node IDs</li>
     * </ul>
     *
     * @param destPath Destination path for the compacted graph
     * @return The number of tombstoned nodes that were skipped
     * @throws IOException if compaction fails
     * @throws IllegalStateException if graph is closed
     */
    public long compactTo(Path destPath) throws IOException {
        validateNotClosed();
        Objects.requireNonNull(destPath, "destPath cannot be null");

        lock.readLock().lock();
        try {
            long skipped = tombstoneCount();

            LOGGER.info("Creating compacted HNSW copy at {}: {} tombstoned nodes to skip", destPath, skipped);

            // Delete an existing copy, mapping included. The copy renumbers nodes, so a
            // mapping left over from an earlier copy would attribute this one's nodes to
            // whichever documents held those IDs before.
            Files.deleteIfExists(destPath);
            Files.deleteIfExists(documentMappingPath(destPath));

            try (HnswGraph compacted = new HnswGraph(destPath, config, distanceFunction, maxNodes)) {
                // Copy all active nodes
                for (long nodeId = 0; nodeId < nodeCount; nodeId++) {
                    if (isTombstoned(nodeId)) {
                        continue;
                    }

                    NodeView node = readNode(nodeId);
                    if (node == null) continue;

                    long newNodeId = compacted.insert(node.vector());

                    String docId = getDocumentId(nodeId);
                    if (docId != null) {
                        compacted.registerDocumentId(newNodeId, docId);
                    }
                }
                compacted.flush();
            }

            LOGGER.info("Created compacted HNSW copy at {}: skipped {} tombstoned nodes", destPath, skipped);
            return skipped;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reopens the graph after compaction.
     *
     * <p>This method reinitializes all internal state after the graph file
     * has been replaced by a compacted version.</p>
     *
     * @param graphPath The path to the graph file
     * @throws IOException if reopening fails
     */
    private void reopenAfterCompaction(Path graphPath) throws IOException {
        // Create new arena
        this.arena = Arena.ofShared();
        this.path = graphPath;

        // Reinitialize the mapped file. MappedFile.open(Path) is the READ-ONLY
        // factory; the two-argument overload maps READ_WRITE. Using the read-only
        // one here leaves the graph permanently unable to accept inserts, tombstones
        // or offset updates after a compaction.
        this.mappedFile = MappedFile.open(graphPath, Files.size(graphPath));
        if (mappedFile.isReadOnly()) {
            throw new IOException("HNSW graph re-mapped read-only after compaction: " + graphPath);
        }

        // Read header to restore state
        long magic = mappedFile.readLong(OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IOException("Invalid HNSW graph file after compaction: bad magic number");
        }

        int version = mappedFile.readInt(OFFSET_VERSION);
        if (!isSupportedVersion(version)) {
            throw new IOException("Unsupported HNSW graph version after compaction: " + version);
        }

        requireMatchingMetric(
            persistedMetric(version, mappedFile.readInt(OFFSET_METRIC), graphPath),
            distanceFunction, graphPath);

        int dimension = mappedFile.readInt(OFFSET_DIMENSION);
        this.nodeCount = mappedFile.readLong(OFFSET_NODE_COUNT);
        this.entryPointId = mappedFile.readLong(OFFSET_ENTRY_POINT);
        this.maxLevel = mappedFile.readInt(OFFSET_MAX_LEVEL);
        this.lastIntegratedOffset.set(mappedFile.readLong(OFFSET_LAST_INTEGRATED));

        // Restore config (dimension might have been validated)
        if (dimension != config.dimension()) {
            throw new IOException("Dimension mismatch after compaction: expected " + config.dimension() + ", got " + dimension);
        }

        // Calculate data region offset
        this.dataRegionOffset = HEADER_SIZE + (maxNodes * NODE_INDEX_ENTRY_SIZE);

        // Recalculate dataWriteOffset from the last node's index entry.
        // Setting to 0 would cause the next insert to overwrite node 0's data.
        this.dataWriteOffset = recoverDataWriteOffset(nodeCount);

        // Update nodeIdGenerator to reflect existing nodes
        this.nodeIdGenerator.set(nodeCount);

        // Mark as open
        this.closed = false;

        LOGGER.debug("Reopened compacted HNSW graph: {} nodes, entryPoint={}, maxLevel={}",
            nodeCount, entryPointId, maxLevel);
    }

    // ==================== Public API - Tail Offset Tracking ====================

    /**
     * Returns the last integrated offset.
     *
     * <p>This offset tracks the write log offset up to which all entries
     * have been linked into the HNSW graph. Entries beyond this offset
     * exist in the write log but are not yet in the graph.</p>
     *
     * <p><strong>Atomicity guarantee:</strong> The returned value is always
     * greater than or equal to the persisted value. This ensures crash recovery
     * correctness - any entries between the persisted value and the returned value
     * will be replayed from the write log.</p>
     *
     * @return The last integrated offset
     */
    public long getLastIntegratedOffset() {
        return lastIntegratedOffset.get();
    }

    /**
     * Atomically sets the last integrated offset with persistence guarantee.
     *
     * <p><strong>Atomicity and persistence guarantee:</strong> This method ensures
     * that the offset is both atomically updated in memory AND persisted to disk
     * before returning. The implementation uses the following ordering:</p>
     * <ol>
     *   <li>Acquire write lock to prevent concurrent updates</li>
     *   <li>Check monotonicity (new offset must be greater than current)</li>
     *   <li>Write new offset to memory-mapped file (persists to page cache)</li>
     *   <li>Update in-memory atomic value</li>
     *   <li>Release write lock</li>
     * </ol>
     *
     * <p>This ordering ensures that the persisted value is never greater than the
     * value visible to readers. It does <strong>not</strong> make the offset durable:
     * the write goes to the page cache and is not forced, nor ordered against the
     * node index and data pages. Use
     * {@link #compareAndSetLastIntegratedOffsetSync(long)} when durability matters.</p>
     *
     * <p>This method is called by the integration worker after successfully
     * integrating entries from the write log into the graph.</p>
     *
     * @param offset The new last integrated offset (must be >= 0)
     * @throws IllegalArgumentException if offset is negative
     */
    public void setLastIntegratedOffset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }

        lock.writeLock().lock();
        try {
            // Check monotonicity - offset must only increase
            long current = lastIntegratedOffset.get();
            if (offset <= current) {
                // Not an increase, no-op
                return;
            }

            // PERSIST FIRST: write to the mapped file before updating memory, so a
            // reader can never observe an offset that was not written. This is
            // ordering only - the store is not forced to disk here.
            mappedFile.writeLong(OFFSET_LAST_INTEGRATED, offset);

            // THEN update memory atomically
            lastIntegratedOffset.set(offset);

            LOGGER.trace("Updated lastIntegratedOffset from {} to {} (persisted)", current, offset);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Atomically updates the last integrated offset if the new value is greater,
     * with persistence guarantee.
     *
     * <p>The CAS and the mapped-file write are performed under the read lock so the
     * pair cannot straddle a compaction, which closes and replaces the mapping. If
     * the write fails the in-memory value is rolled back, so the two never diverge.</p>
     *
     * <p><strong>Durability:</strong> the write lands in the page cache only. It is
     * neither forced nor ordered against the node index and data pages, which the
     * kernel may write back in any order. After a crash the persisted offset may
     * therefore be ahead of, behind, or interleaved with the node records it refers
     * to. Use {@link #compareAndSetLastIntegratedOffsetSync(long)} at checkpoints
     * where the offset must actually be durable.</p>
     *
     * <p>Note: multiple concurrent writers retry the CAS; the read lock does not
     * serialise them against each other, only against compaction.</p>
     *
     * @param newOffset The new offset value (must be >= 0)
     * @return true if the offset was updated
     */
    public boolean compareAndSetLastIntegratedOffset(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        lock.readLock().lock();
        try {
            while (true) {
                long current = lastIntegratedOffset.get();
                if (newOffset <= current) {
                    // Not an increase
                    return false;
                }

                // Try to atomically update memory
                if (lastIntegratedOffset.compareAndSet(current, newOffset)) {
                    try {
                        mappedFile.writeLong(OFFSET_LAST_INTEGRATED, newOffset);
                    } catch (RuntimeException e) {
                        // Undo the CAS so the in-memory value never claims a
                        // checkpoint that was not written.
                        lastIntegratedOffset.compareAndSet(newOffset, current);
                        throw e;
                    }
                    LOGGER.trace("CAS updated lastIntegratedOffset from {} to {} (persisted)",
                        current, newOffset);
                    return true;
                }
                // CAS failed due to concurrent update, retry
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Rebases the integration checkpoint onto a different write log, ignoring monotonicity.
     *
     * <p>Every other setter here refuses to go backwards, and that is right while the log is the
     * same one: a checkpoint that moves down would re-integrate entries already in the graph.
     * But compaction <em>replaces</em> the segment's write log with an empty file, and a byte
     * offset into the discarded log means nothing in the new one. The carried checkpoint then sits
     * above the new log's head, so every entry appended after the compaction falls below it and is
     * never integrated — the segment looks idle while silently accepting vectors that no query can
     * ever find.
     *
     * <p>So this is deliberately not monotone, and deliberately narrow: it exists for the
     * replace-the-log case and nothing else. Calling it while the log is unchanged discards
     * integration work and re-does it.
     *
     * <p>Forced to disk rather than left to the page cache. The offset now disagrees with what a
     * crash would otherwise restore, and restoring the old value would point at a log that no
     * longer exists.
     *
     * @param offset the new checkpoint, normally the origin of the replacement log
     * @throws IllegalArgumentException if offset is negative
     */
    public void resetLastIntegratedOffset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }

        lock.writeLock().lock();
        try {
            long current = lastIntegratedOffset.get();
            mappedFile.writeLong(OFFSET_LAST_INTEGRATED, offset);
            mappedFile.force();
            lastIntegratedOffset.set(offset);
            LOGGER.info("Reset lastIntegratedOffset from {} to {} for a replacement write log",
                current, offset);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Atomically updates the last integrated offset with persistence and explicit sync.
     *
     * <p>This method provides stronger durability guarantees by explicitly flushing
     * the offset to durable storage. Use this for critical checkpoints where
     * data loss is unacceptable.</p>
     *
     * <p><strong>Performance note:</strong> This method calls {@link #flush()},
     * which may block on I/O. Use sparingly.</p>
     *
     * @param newOffset The new offset value
     * @return true if the offset was updated
     */
    public boolean compareAndSetLastIntegratedOffsetSync(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        lock.writeLock().lock();
        try {
            long current = lastIntegratedOffset.get();
            if (newOffset <= current) {
                return false;
            }

            // Persist first with explicit flush
            mappedFile.writeLong(OFFSET_LAST_INTEGRATED, newOffset);
            mappedFile.flush();

            // Then update memory
            lastIntegratedOffset.set(newOffset);

            LOGGER.trace("Sync-updated lastIntegratedOffset from {} to {} (synced)", current, newOffset);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Node I/O Operations ====================

    /**
     * Writes a node to the data region.
     *
     * <p>Node format (stores adjacency lists for ALL levels from 0 to node's level):</p>
     * <pre>
     * [nodeId:8][level:4][vectorLen:4][vector:dim*4]
     * For each level from 0 to level:
     *   [connCount:4][reserved space for maxConn_at_level neighbors]
     * </pre>
     *
     * @return The offset within the data region where the node was written
     */
    private long writeNode(long nodeId, int level, float[] vector) {
        long offset = dataWriteOffset;
        long absoluteOffset = dataRegionOffset + offset;

        // Calculate node size including ALL levels' adjacency lists
        int vectorBytes = vector.length * Float.BYTES;

        // Sum adjacency space for all levels from 0 to level (inclusive)
        int adjacencyBytes = 0;
        for (int l = 0; l <= level; l++) {
            int maxConnAtLevel = (l == 0) ? config.maxConnectionsLayer0() : config.maxConnections();
            adjacencyBytes += 4 + (maxConnAtLevel * Integer.BYTES); // connCount + neighbors
        }

        // Node format:
        // [nodeId:8][level:4][vectorLen:4][vector:dim*4]
        // For each level 0..level: [connCount:4][neighbors:maxConn*4]
        int nodeSize = 8 + 4 + 4 + vectorBytes + adjacencyBytes;

        // Ensure we have space
        ensureCapacity(nodeSize);

        // Write node data
        mappedFile.writeLong(absoluteOffset, nodeId);
        mappedFile.writeInt(absoluteOffset + 8, level);
        mappedFile.writeInt(absoluteOffset + 12, vector.length);

        // Write vector
        long vecOffset = absoluteOffset + 16;
        for (int i = 0; i < vector.length; i++) {
            mappedFile.writeFloat(vecOffset + (long) i * 4, vector[i]);
        }

        // Initialize all level adjacency lists with count=0
        long adjOffset = absoluteOffset + 16 + vectorBytes;
        for (int l = 0; l <= level; l++) {
            mappedFile.writeInt(adjOffset, 0); // connCount = 0
            int maxConnAtLevel = (l == 0) ? config.maxConnectionsLayer0() : config.maxConnections();
            adjOffset += 4 + ((long) maxConnAtLevel * Integer.BYTES);
        }

        // Advance write offset
        dataWriteOffset += nodeSize;

        return offset;
    }

    /**
     * Reads a node from disk.
     *
     * <p>This method provides zero-copy access to node data by reading directly
     * from the memory-mapped file. The returned NodeView is a view into the
     * underlying memory, so it should not be used after the graph is closed.</p>
     *
     * <p><strong>Node IDs are generation-scoped.</strong> Compaction renumbers them,
     * so an ID captured before a compaction may name a different vector afterwards.
     * Callers that keep an ID across a possible compaction must use
     * {@link #readNode(long, long)} with the {@link #generation()} the ID came
     * from.</p>
     *
     * @param nodeId The node ID to read
     * @return NodeView for the node, or null if not found
     */
    public NodeView readNode(long nodeId) {
        // The read lock is what makes compaction's write lock exclude this method:
        // compact() closes the current mapping and installs a new one, and reading
        // through the old MappedFile would throw "MappedFile is closed".
        lock.readLock().lock();
        try {
            return readNodeUnderLock(nodeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a node, rejecting IDs that belong to a superseded graph generation.
     *
     * @param nodeId The node ID to read
     * @param expectedGeneration The generation the node ID was obtained from, or
     *                           {@link HnswSearchResult#GENERATION_UNKNOWN} to skip
     *                           the check
     * @return NodeView for the node, or null if not found or the generation no
     *         longer matches
     * @since 1.3
     */
    public NodeView readNode(long nodeId, long expectedGeneration) {
        lock.readLock().lock();
        try {
            if (expectedGeneration >= 0 && expectedGeneration != generation) {
                LOGGER.debug("Rejecting node {} from superseded graph generation {} (current {})",
                    nodeId, expectedGeneration, generation);
                return null;
            }
            return readNodeUnderLock(nodeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a node. Must be called with at least the read lock held.
     */
    private NodeView readNodeUnderLock(long nodeId) {
        if (nodeId < 0 || nodeId >= nodeIdGenerator.get()) {
            return null;
        }

        long indexOffset = HEADER_SIZE + nodeId * NODE_INDEX_ENTRY_SIZE;
        if (indexOffset + NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
            return null;
        }

        long storedId = mappedFile.readLong(indexOffset);
        if (storedId != nodeId) {
            return null;
        }

        long dataOffset = mappedFile.readLong(indexOffset + 8);
        int level = mappedFile.readInt(indexOffset + 16);
        int flags = mappedFile.readInt(indexOffset + 20);

        long absoluteOffset = dataRegionOffset + dataOffset;

        return new NodeView(nodeId, level, absoluteOffset, flags, generation);
    }

    /**
     * Returns the current graph generation.
     *
     * <p>The generation is incremented every time compaction swaps in a rebuilt
     * file. Because compaction renumbers node IDs, an ID is only meaningful within
     * the generation it was obtained from.</p>
     *
     * @return The current generation (starts at 0)
     * @since 1.3
     */
    public long generation() {
        return generation;
    }

    /**
     * Updates the node index.
     */
    private void updateNodeIndex(long nodeId, long dataOffset, int level, boolean tombstoned) {
        long indexOffset = HEADER_SIZE + nodeId * NODE_INDEX_ENTRY_SIZE;

        mappedFile.writeLong(indexOffset, nodeId);
        mappedFile.writeLong(indexOffset + 8, dataOffset);
        mappedFile.writeInt(indexOffset + 16, level);
        mappedFile.writeInt(indexOffset + 20, tombstoned ? FLAG_TOMBSTONE : 0);
    }

    /**
     * Updates the header with current state.
     */
    private void updateHeader() {
        mappedFile.writeLong(OFFSET_ENTRY_POINT, entryPointId);
        mappedFile.writeInt(OFFSET_MAX_LEVEL, maxLevel);
    }

    /**
     * Ensures the file has enough capacity.
     */
    private void ensureCapacity(int additionalBytes) throws IllegalStateException {
        long required = dataRegionOffset + dataWriteOffset + additionalBytes;
        if (required > mappedFile.size()) {
            try {
                // Extend by at least 1MB or 50%, whichever is larger
                long extension = Math.max(1024 * 1024, mappedFile.size() / 2);
                mappedFile.extend(extension);
                LOGGER.debug("Extended HNSW file to {} bytes", mappedFile.size());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to extend HNSW file", e);
            }
        }
    }

    /**
     * Finds an active (non-tombstoned) node.
     */
    private long findActiveNode() {
        for (long id = 0; id < nodeCount; id++) {
            long indexOffset = HEADER_SIZE + id * NODE_INDEX_ENTRY_SIZE;
            int flags = mappedFile.readInt(indexOffset + 20);
            if ((flags & FLAG_TOMBSTONE) == 0) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Finds the active node with the highest level in the graph.
     *
     * <p>This method is used when the entry point is deleted to ensure the
     * new entry point maintains the invariant: entry point must be at the
     * highest layer in the graph.</p>
     *
     * @return The node ID with the highest level among active nodes, or -1 if no active nodes exist
     */
    private long findHighestLevelActiveNode() {
        long highestLevelNode = -1;
        int highestLevel = -1;

        for (long id = 0; id < nodeCount; id++) {
            long indexOffset = HEADER_SIZE + id * NODE_INDEX_ENTRY_SIZE;
            int flags = mappedFile.readInt(indexOffset + 20);
            if ((flags & FLAG_TOMBSTONE) != 0) {
                continue; // Skip tombstoned nodes
            }

            int level = mappedFile.readInt(indexOffset + 16);
            if (level > highestLevel) {
                highestLevel = level;
                highestLevelNode = id;
            }
        }

        return highestLevelNode;
    }

    /**
     * Adds connections to a node at a specific level.
     *
     * <p>Navigation to the correct level's adjacency section accounts for variable-length
     * per-level sections: layer 0 uses maxConnectionsLayer0, higher layers use maxConnections.</p>
     */
    private void addConnections(long nodeId, int level, List<Long> neighborIds) {
        NodeView node = readNode(nodeId);
        if (node == null) {
            return;
        }

        long offset = node.dataOffset;
        int vectorBytes = config.dimension() * Float.BYTES;

        // Navigate to the connection section for the specified level
        // Format: after vector, we have [connCount:4][neighbors:maxConn*4] for each level 0..nodeLevel
        long connOffset = offset + 16 + vectorBytes;
        for (int l = 0; l < level; l++) {
            int maxConn = (l == 0) ? config.maxConnectionsLayer0() : config.maxConnections();
            connOffset += 4 + ((long) maxConn * Integer.BYTES);
        }

        // Write connection count (bounded by max connections for this level)
        int maxConnAtLevel = (level == 0) ? config.maxConnectionsLayer0() : config.maxConnections();
        int count = Math.min(neighborIds.size(), maxConnAtLevel);
        mappedFile.writeInt(connOffset, count);

        // Write neighbor IDs
        for (int i = 0; i < count; i++) {
            mappedFile.writeInt(connOffset + 4 + (long) i * 4, neighborIds.get(i).intValue());
        }
    }

    /**
     * Adds a single connection to a node with pruning if necessary.
     *
     * <p>When max connections is exceeded, uses the simple heuristic:
     * keep the closest neighbors based on distance to the node's vector.</p>
     *
     * <p><strong>Bidirectional Edge Invariant:</strong> When pruning removes a neighbor,
     * the corresponding back-edge is also removed to maintain bidirectionality.</p>
     *
     * @param nodeId The node to add connection to
     * @param level The level for the connection
     * @param neighborId The neighbor to connect
     * @return true if the connection was successfully added, false if it was pruned
     */
    private boolean addConnection(long nodeId, int level, long neighborId) {
        NodeView node = readNode(nodeId);
        if (node == null || node.isTombstoned()) {
            return false;
        }

        // Read current connections
        List<Long> currentNeighbors = node.getNeighbors(level);

        // Check if already connected
        if (currentNeighbors.contains(neighborId)) {
            return true; // Already connected counts as success
        }

        // Check capacity
        int maxConn = level == 0 ? config.maxConnectionsLayer0() : config.maxConnections();

        if (currentNeighbors.size() >= maxConn) {
            // Prune connections using simple heuristic: keep closest neighbors
            // This also removes back-edges from pruned nodes to maintain bidirectionality
            currentNeighbors = pruneConnectionsAndRemoveBackEdges(nodeId, level, currentNeighbors, neighborId, maxConn);
            // Check if the new neighbor was included after pruning
            boolean added = currentNeighbors.contains(neighborId);
            addConnections(nodeId, level, currentNeighbors);
            return added;
        } else {
            currentNeighbors.add(neighborId);
            addConnections(nodeId, level, currentNeighbors);
            return true;
        }
    }

    /**
     * Prunes connections to maintain max connection limit.
     *
     * <p>Uses the simple heuristic: keep the neighbors closest to the node's vector.
     * This maintains graph quality while respecting connection limits.</p>
     *
     * @param node The node whose connections are being pruned
     * @param level The level for connections
     * @param currentNeighbors Current neighbor list
     * @param newNeighborId The new neighbor to potentially add
     * @param maxConn Maximum connections allowed
     * @return Pruned list of neighbors (size <= maxConn)
     */
    private List<Long> pruneConnections(NodeView node, int level,
                                        List<Long> currentNeighbors,
                                        long newNeighborId, int maxConn) {
        float[] nodeVector = node.vector();

        // Build list of all candidates (current + new) with distances
        List<NeighborCandidate> candidates = new ArrayList<>(currentNeighbors.size() + 1);

        for (Long neighborId : currentNeighbors) {
            NodeView neighbor = readNode(neighborId);
            if (neighbor != null && !neighbor.isTombstoned()) {
                float dist = computeDistance(nodeVector, neighbor.vector());
                candidates.add(new NeighborCandidate(neighborId, dist));
            }
        }

        // Add new neighbor
        NodeView newNeighbor = readNode(newNeighborId);
        if (newNeighbor != null && !newNeighbor.isTombstoned()) {
            float dist = computeDistance(nodeVector, newNeighbor.vector());
            candidates.add(new NeighborCandidate(newNeighborId, dist));
        }

        // Sort by distance (ascending - closest first)
        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        // Take top maxConn neighbors
        List<Long> pruned = new ArrayList<>(maxConn);
        for (int i = 0; i < Math.min(maxConn, candidates.size()); i++) {
            pruned.add(candidates.get(i).nodeId);
        }

        return pruned;
    }

    /**
     * Prunes connections and removes back-edges from pruned neighbors.
     *
     * <p>This method ensures the bidirectional edge invariant is maintained:
     * when a neighbor is pruned from this node's adjacency list, the corresponding
     * back-edge from that neighbor to this node is also removed.</p>
     *
     * <p><strong>Why this is necessary:</strong> In HNSW, edges must be bidirectional.
     * If node A has node B in its neighbor list, then node B must also have node A
     * in its neighbor list. When pruning removes B from A's list, we must also
     * remove A from B's list to maintain this invariant.</p>
     *
     * @param nodeId The node whose connections are being pruned
     * @param level The level for connections
     * @param currentNeighbors Current neighbor list
     * @param newNeighborId The new neighbor to potentially add
     * @param maxConn Maximum connections allowed
     * @return Pruned list of neighbors (size <= maxConn)
     */
    private List<Long> pruneConnectionsAndRemoveBackEdges(long nodeId, int level,
                                                          List<Long> currentNeighbors,
                                                          long newNeighborId, int maxConn) {
        NodeView node = readNode(nodeId);
        if (node == null) {
            return currentNeighbors;
        }

        float[] nodeVector = node.vector();

        // Build list of all candidates (current + new) with distances
        List<NeighborCandidate> candidates = new ArrayList<>(currentNeighbors.size() + 1);

        for (Long neighborId : currentNeighbors) {
            NodeView neighbor = readNode(neighborId);
            if (neighbor != null && !neighbor.isTombstoned()) {
                float dist = computeDistance(nodeVector, neighbor.vector());
                candidates.add(new NeighborCandidate(neighborId, dist));
            }
        }

        // Add new neighbor
        NodeView newNeighbor = readNode(newNeighborId);
        if (newNeighbor != null && !newNeighbor.isTombstoned()) {
            float dist = computeDistance(nodeVector, newNeighbor.vector());
            candidates.add(new NeighborCandidate(newNeighborId, dist));
        }

        // Sort by distance (ascending - closest first)
        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        // Determine which neighbors to keep
        java.util.Set<Long> keptNeighbors = new java.util.HashSet<>();
        List<Long> pruned = new ArrayList<>(maxConn);
        for (int i = 0; i < Math.min(maxConn, candidates.size()); i++) {
            Long id = candidates.get(i).nodeId;
            pruned.add(id);
            keptNeighbors.add(id);
        }

        // Remove back-edges from pruned neighbors (those not in the kept set)
        for (Long oldNeighborId : currentNeighbors) {
            if (!keptNeighbors.contains(oldNeighborId)) {
                // This neighbor was pruned - remove the back-edge
                removeBackEdge(oldNeighborId, level, nodeId);
            }
        }

        return pruned;
    }

    /**
     * Removes a back-edge from a node's neighbor list.
     *
     * <p>This method removes nodeId from neighborId's adjacency list at the specified level,
     * maintaining the bidirectional edge invariant when pruning.</p>
     *
     * @param neighborId The node from whose list to remove the back-edge
     * @param level The level of the edge
     * @param nodeId The node to remove from the neighbor list
     */
    private void removeBackEdge(long neighborId, int level, long nodeId) {
        NodeView neighbor = readNode(neighborId);
        if (neighbor == null || neighbor.isTombstoned()) {
            return;
        }

        // Check if the neighbor exists at this level
        if (neighbor.level() < level) {
            return;
        }

        List<Long> neighborList = neighbor.getNeighbors(level);
        if (!neighborList.contains(nodeId)) {
            return; // Already not connected
        }

        // Remove nodeId from neighbor's list
        List<Long> updatedList = new ArrayList<>(neighborList);
        updatedList.remove(Long.valueOf(nodeId));

        // Write updated list back
        addConnections(neighborId, level, updatedList);
    }

    /**
     * Helper record for connection pruning.
     */
    private record NeighborCandidate(long nodeId, float distance) {}

    // ==================== Utility Methods ====================

    /**
     * Computes the ordering distance between a query and a vector.
     *
     * <p>Every comparison in this class - greedy descent, beam admission, neighbour
     * pruning and final sorting - treats the returned value as a distance where
     * <strong>smaller is closer</strong>. {@link DistanceFunction#compute} does not
     * guarantee that direction (cosine and inner product are similarities), so this
     * method must go through {@link DistanceFunction#distance(float[], float[])},
     * which normalizes the direction using the function's declared
     * {@link DistanceFunction.Ordering}.</p>
     */
    private float computeDistance(float[] query, float[] vector) {
        return distanceFunction.distance(query, vector);
    }

    private void validateNotClosed() {
        if (closed) {
            throw new IllegalStateException("HNSW graph is closed: " + path);
        }
    }

    private void validateQuery(float[] query) {
        if (query == null) {
            throw new IllegalArgumentException("Query vector cannot be null");
        }
        if (query.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Query dimension mismatch: expected " + config.dimension() +
                ", got " + query.length);
        }
    }

    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Vector cannot be null");
        }
        if (vector.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + config.dimension() +
                ", got " + vector.length);
        }
    }

    // ==================== Accessors ====================

    public long nodeCount() {
        return nodeCount;
    }

    public long entryPoint() {
        return entryPointId;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public HnswConfig config() {
        return config;
    }

    public Path path() {
        return path;
    }

    public boolean isClosed() {
        return closed;
    }

    // ==================== Product Quantization Support ====================

    /**
     * Returns the ProductQuantizer associated with this graph, if any.
     *
     * <p>Product Quantization enables compressed vector storage and asymmetric
     * distance computation for memory-efficient approximate nearest neighbor search.</p>
     *
     * @return The ProductQuantizer, or null if not configured
     */
    public ProductQuantizer getProductQuantizer() {
        return pq;
    }

    /**
     * Sets a ProductQuantizer for this graph.
     *
     * <p>After setting a trained quantizer, subsequent searches can optionally
     * use asymmetric distance computation via the PQ codes.</p>
     *
     * <p><strong>Graceful Fallback:</strong> If the quantizer is not yet trained,
     * searches will use full-precision distance computation until training completes.
     * This allows queries on cold segments during the indexing phase.</p>
     *
     * <p><strong>Note:</strong> The quantizer's dimension must match this graph's dimension.</p>
     *
     * @param quantizer A ProductQuantizer (must not be null, may be untrained)
     * @throws IllegalArgumentException if quantizer dimension doesn't match
     */
    public void setProductQuantizer(ProductQuantizer quantizer) {
        Objects.requireNonNull(quantizer, "ProductQuantizer cannot be null");
        if (quantizer.originalDim() != config.dimension()) {
            throw new IllegalArgumentException(
                "ProductQuantizer dimension (" + quantizer.originalDim() +
                ") must match graph dimension (" + config.dimension() + ")");
        }

        lock.writeLock().lock();
        try {
            this.pq = quantizer;
            this.pqCodes = new java.util.concurrent.ConcurrentHashMap<>();
            if (quantizer.isTrained()) {
                LOGGER.info("ProductQuantizer set for graph at {} (compression ratio: {}x)",
                    path, quantizer.compressionRatio());
            } else {
                LOGGER.info("ProductQuantizer set for graph at {} (not yet trained, will use full-precision fallback)",
                    path);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Trains a ProductQuantizer on a sample of vectors from this graph.
     *
     * <p>This method extracts sample vectors from the graph and trains
     * a new ProductQuantizer. After training, vectors are encoded and
     * stored as PQ codes for efficient distance computation.</p>
     *
     * <p><strong>Note:</strong> Training requires reading vectors from disk,
     * which may be slow for large graphs. Use a representative sample size.</p>
     *
     * @param sampleSize Number of vectors to sample for training (0 = use all)
     * @param numSubspaces Number of subspaces for PQ
     * @param numCentroids Number of centroids per subspace (typically 256)
     * @return The trained ProductQuantizer
     * @throws IllegalStateException if graph is empty or closed
     */
    public ProductQuantizer trainProductQuantizer(int sampleSize, int numSubspaces, int numCentroids) {
        validateNotClosed();

        List<float[]> samples = collectTrainingSamples(sampleSize);

        LOGGER.info("Training ProductQuantizer on {} sample vectors", samples.size());

        // Create and train quantizer
        ProductQuantizer quantizer = new ProductQuantizer(config.dimension(), numSubspaces, numCentroids);
        quantizer.train(samples.toArray(new float[0][]));

        // Publishing the quantizer and its code map is a mutation of shared state,
        // so it needs the write lock, not the read lock the sampling ran under.
        lock.writeLock().lock();
        try {
            // Publish the (empty) code map before the quantizer so no reader can see
            // a trained quantizer paired with the previous quantizer's codes.
            this.pqCodes = new java.util.concurrent.ConcurrentHashMap<>();
            this.pq = quantizer;
        } finally {
            lock.writeLock().unlock();
        }

        LOGGER.info("ProductQuantizer trained successfully (compression ratio: {}x)",
            quantizer.compressionRatio());

        return quantizer;
    }

    /**
     * Samples vectors from the graph for product quantizer training.
     *
     * @param sampleSize Number of vectors to sample (0 = use all)
     * @return The sampled vectors
     * @throws IllegalStateException if the graph is empty or holds no readable vectors
     */
    private List<float[]> collectTrainingSamples(int sampleSize) {
        lock.readLock().lock();
        try {
            if (nodeCount == 0) {
                throw new IllegalStateException("Cannot train ProductQuantizer on empty graph");
            }

            int actualSampleSize = sampleSize > 0 ? Math.min(sampleSize, (int) nodeCount) : (int) nodeCount;
            List<float[]> samples = new ArrayList<>(actualSampleSize);

            // Sample uniformly from the graph
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            java.util.Set<Integer> sampledIndices = new java.util.HashSet<>();

            while (sampledIndices.size() < actualSampleSize && sampledIndices.size() < nodeCount) {
                int idx = random.nextInt((int) nodeCount);
                if (sampledIndices.add(idx)) {
                    NodeView node = readNodeUnderLock(idx);
                    if (node != null && !node.isTombstoned()) {
                        samples.add(node.vector());
                    }
                }
            }

            if (samples.isEmpty()) {
                throw new IllegalStateException("No valid vectors found for training");
            }

            return samples;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Encodes all vectors in the graph using the configured ProductQuantizer.
     *
     * <p>After encoding, searches can use asymmetric distance computation
     * via {@link #searchWithPQ(float[], int, PQDistanceTable)}.</p>
     *
     * <p><strong>Note:</strong> This is a one-time operation that reads all
     * vectors from disk. Subsequent insertions should call
     * {@link #encodeAndStorePQCode(long, float[])} to maintain consistency.</p>
     *
     * @throws IllegalStateException if ProductQuantizer is not set
     */
    public void encodeAllVectors() {
        if (pq == null || !pq.isTrained()) {
            throw new IllegalStateException("ProductQuantizer must be set and trained");
        }

        int encoded = encodeEveryVector();
        LOGGER.info("Encoded {} vectors with ProductQuantizer", encoded);

        // The codebook is written once the codes exist, because that is the point at which the
        // quantiser is actually in use. A codebook on disk is what lets the next process skip
        // the training this one just paid for.
        persistProductQuantizer();
    }

    /**
     * Encodes every live node against the current quantizer.
     *
     * <p>The read lock, not the write lock. The loop reads one node per iteration off the
     * mapped file - a page-in per node, and the whole vector region for a large segment - and
     * its only mutation is into a concurrent map. Holding the exclusive lock across that
     * excluded every query and every insert on the segment for the length of a full scan, and
     * this runs on a WARMING segment, which is queryable.</p>
     *
     * @return The number of vectors encoded
     */
    private int encodeEveryVector() {
        lock.readLock().lock();
        try {
            int encoded = 0;
            for (long nodeId = 0; nodeId < nodeCount; nodeId++) {
                NodeView node = readNode(nodeId);
                if (node != null && !node.isTombstoned()) {
                    byte[] codes = pq.encode(node.vector());
                    pqCodes.put(nodeId, codes);
                    encoded++;
                }
            }
            return encoded;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Writes the trained codebook beside the graph file.
     *
     * <p>Failure is logged rather than thrown. The codes this process holds are unaffected, so
     * the cost of a failed write is that the next process has to warm the segment again -
     * against a graph that is already durable. Raising here would instead fail the encoding
     * that did succeed.</p>
     */
    private void persistProductQuantizer() {
        ProductQuantizer quantizer = this.pq;
        if (quantizer == null || !quantizer.isTrained()) {
            return;
        }
        Path codebookPath = productQuantizerPath(path);
        try {
            PQCodebookFile.write(codebookPath, quantizer);
            LOGGER.debug("Wrote the product quantizer codebook for {} to {}", path, codebookPath);
        } catch (IOException e) {
            LOGGER.warn("Could not write the product quantizer codebook for {} to {}: {}. "
                + "This segment will have to be re-trained after a restart.",
                path, codebookPath, e.toString());
        }
    }

    /**
     * Adopts the codebook sitting beside an existing graph file, if there is one.
     *
     * <p>Called when re-attaching to a graph that already holds data. Training only happens
     * while a segment is warming, and a segment that is reopened is past that, so a codebook
     * that is not picked up here is one the segment will never have again.</p>
     *
     * <p>Only the codebook is read; the codes are not scanned back in. Opening a graph is on
     * the startup path and quantised search is off unless a deployment asked for it, so a full
     * encoding pass here would be a per-segment scan at every boot that most nodes would never
     * consult. The codes rebuild themselves instead: a node the quantised beam reaches without
     * a stored code is encoded on the spot and cached, which costs the same in total and only
     * for the nodes a query actually visits. A caller that would rather pay it up front - one
     * that knows quantised search is enabled - calls {@link #encodeAllVectors()} after
     * opening.</p>
     *
     * <p>Persisting the codes themselves would not help: they are keyed by node id, which a
     * compaction renumbers, so a file of them would have to be rewritten by every compaction or
     * be read back describing the wrong vectors.</p>
     *
     * <p>A codebook that does not match this graph is refused and reported, and the graph opens
     * without one. The vectors are the data; a sidecar that cannot be trusted costs
     * quantisation, and refusing to open the graph over it would cost the corpus.</p>
     */
    private void restorePersistedQuantizer() {
        Path codebookPath = productQuantizerPath(path);
        if (!Files.exists(codebookPath)) {
            return;
        }

        ProductQuantizer restored;
        try {
            restored = PQCodebookFile.read(codebookPath, config.dimension());
        } catch (IOException e) {
            LOGGER.warn("Refusing the product quantizer codebook at {}: {}. The graph is open "
                + "without quantisation and searches it at full precision.",
                codebookPath, e.toString());
            return;
        }

        setProductQuantizer(restored);
        LOGGER.info("Restored the product quantizer for {} from {}", path, codebookPath);
    }

    /**
     * Returns the path of the codebook belonging to a graph file.
     */
    private static Path productQuantizerPath(Path graphPath) {
        return graphPath.resolveSibling(graphPath.getFileName() + PRODUCT_QUANTIZER_SUFFIX);
    }

    /**
     * Encodes and stores PQ codes for a single vector.
     *
     * <p>Call this after inserting a vector to maintain PQ consistency.</p>
     *
     * @param nodeId The node ID
     * @param vector The vector to encode
     * @throws IllegalStateException if ProductQuantizer is not set
     */
    public void encodeAndStorePQCode(long nodeId, float[] vector) {
        if (pq == null || !pq.isTrained()) {
            return; // No PQ configured, skip silently
        }
        byte[] codes = pq.encode(vector);
        pqCodes.put(nodeId, codes);
    }

    /**
     * Searches for k nearest neighbors using Product Quantization for distance computation.
     *
     * <p>This method uses asymmetric distance computation: the query is kept at full
     * precision while database vectors are represented by their PQ codes. This provides
     * good accuracy with significantly reduced memory usage.</p>
     *
     * <h3>Fallback Behavior</h3>
     * <ul>
     *   <li>If ProductQuantizer is not set, falls back to full-precision search</li>
     *   <li>If a node has no PQ codes, uses full-precision distance</li>
     * </ul>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @return List of search results sorted by distance (closest first)
     */
    public List<HnswSearchResult> searchWithPQ(float[] query, int k) {
        if (pq == null || !pq.isTrained()) {
            // Fall back to regular search
            return search(query, k);
        }

        // Built for this graph's metric. A table on squared L2 consulted by a cosine graph
        // ranks by a different geometry and returns values on a range the caller's
        // normalisation was never calibrated against.
        PQDistanceTable distanceTable = pq.createDistanceTable(query, distanceFunction);
        return searchWithPQ(query, k, distanceTable);
    }

    /**
     * Searches for k nearest neighbors using a precomputed PQ distance table.
     *
     * <p>Use this when performing multiple searches with the same query,
     * as it reuses the precomputed distance table.</p>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @param distanceTable Precomputed distance table for the query
     * @return List of search results sorted by distance (closest first)
     */
    public List<HnswSearchResult> searchWithPQ(float[] query, int k, PQDistanceTable distanceTable) {
        validateNotClosed();
        validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        if (pq == null || !pq.isTrained()) {
            return search(query, k);
        }

        lock.readLock().lock();
        try {
            if (nodeCount == 0 || entryPointId < 0) {
                return new ArrayList<>();
            }

            // Start from entry point
            long currentNodeId = entryPointId;
            NodeView current = readNode(currentNodeId);

            if (current == null || current.isTombstoned()) {
                currentNodeId = findActiveNode();
                if (currentNodeId < 0) {
                    return new ArrayList<>();
                }
                current = readNode(currentNodeId);
            }

            // As in search(): the layer-0 beam is trimmed to ef entries, so a beam narrower
            // than k can never yield k results, and the caller gets a short answer with
            // nothing to say it was short.
            final int efSearch = Math.max(config.efSearch(), k);

            // Search through layers from top to layer 1
            for (int level = maxLevel; level > 0; level--) {
                NodeView closest = searchLayerSimpleWithPQ(query, current, level, distanceTable);
                if (closest != null) {
                    current = closest;
                }
            }

            // At layer 0, perform beam search with PQ
            PriorityQueue<HnswSearchResult> results = searchLayerBeamWithPQ(
                query, current, 0, efSearch, distanceTable);

            // Extract and sort results, re-checking tombstones the way search() does: a node
            // admitted to the beam can be tombstoned before it is extracted, and returning it
            // hands back a deleted document.
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                NodeView node = readNode(result.nodeId());
                if (node != null && !node.isTombstoned()) {
                    allResults.add(result);
                }
            }

            allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));

            List<HnswSearchResult> topResults = new ArrayList<>();
            for (int i = 0; i < Math.min(k, allResults.size()); i++) {
                topResults.add(allResults.get(i));
            }

            return topResults;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Searches with the quantised beam and returns the top k scored on full-precision vectors.
     *
     * <p>The quantised pass is a cheap way to decide which nodes are worth measuring, and
     * nothing more. Its lookups are sums of query-to-centroid terms - near the true distance,
     * but off by an amount that varies per node - so a caller handed them would be ranking on a
     * scale that is not the metric's. A segment's answer is this list merged with the write-log
     * tail scan's, and the tail scan measures exactly; mixing the two scales would let the
     * ranking turn on which half a result came from. Every candidate is therefore re-measured
     * against its stored vector before it is returned, which makes these results
     * indistinguishable in kind from {@link #search(float[], int)}'s.</p>
     *
     * <p>Recall is bought with {@code oversample}: the beam collects {@code oversample * k}
     * candidates and the exact pass picks the k best of them, so a node the quantisation
     * mis-ranks is still recovered as long as it lands inside the wider set.</p>
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @param oversample How many candidates to measure per result, at least 1
     * @return The k nearest, closest first, scored exactly
     * @throws IllegalStateException if the graph is closed
     * @throws IllegalArgumentException if the query is null or wrong dimension, k is not
     *         positive, or oversample is below 1
     */
    public List<HnswSearchResult> searchWithPQThenRerank(float[] query, int k, int oversample) {
        validateNotClosed();
        validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (oversample < 1) {
            throw new IllegalArgumentException("oversample must be at least 1: " + oversample);
        }

        if (pq == null || !pq.isTrained()) {
            return search(query, k);
        }

        // One read lock over both passes, which the nested acquisitions below re-enter. The
        // candidates are node ids, and a compaction between the two passes renumbers them -
        // the rescore would then measure the query against whichever vectors had inherited
        // those ids, and return them as this segment's nearest.
        lock.readLock().lock();
        try {
            List<HnswSearchResult> candidates =
                searchWithPQ(query, candidateWidth(k, oversample));
            return rescoreExactly(query, candidates, k);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * The number of candidates a rescored search collects, saturating instead of overflowing.
     *
     * <p>{@code k} reaches here from the caller and the oversample from configuration, so the
     * product can leave the int range; wrapping would turn the beam width negative.</p>
     */
    private static int candidateWidth(int k, int oversample) {
        long widened = (long) k * oversample;
        return widened > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) widened;
    }

    /**
     * Re-measures candidates against their stored vectors and keeps the k nearest.
     *
     * <p>A candidate tombstoned since the beam admitted it is dropped rather than carried
     * through with a distance nothing backs, which is the same re-check
     * {@link #search(float[], int)} makes on its way out.</p>
     *
     * @param query The query vector
     * @param candidates What the quantised beam proposed
     * @param k How many to keep
     * @return The k nearest candidates, closest first
     */
    private List<HnswSearchResult> rescoreExactly(
            float[] query, List<HnswSearchResult> candidates, int k) {

        lock.readLock().lock();
        try {
            List<HnswSearchResult> measured = new ArrayList<>(candidates.size());
            for (HnswSearchResult candidate : candidates) {
                NodeView node = readNode(candidate.nodeId());
                if (node == null || node.isTombstoned()) {
                    continue;
                }
                measured.add(createSearchResult(
                    candidate.nodeId(), computeDistance(query, node.vector())));
            }

            measured.sort(Comparator.comparingDouble(HnswSearchResult::distance));
            return new ArrayList<>(measured.subList(0, Math.min(k, measured.size())));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Simple greedy search at a single layer using PQ distances.
     */
    private NodeView searchLayerSimpleWithPQ(float[] query, NodeView entry, int level, PQDistanceTable distanceTable) {
        NodeView current = entry;
        float currentDist = computeDistanceWithPQ(query, current, distanceTable);
        boolean improved = true;

        while (improved) {
            improved = false;
            List<Long> neighbors = current.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                NodeView neighbor = readNode(neighborId);
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = computeDistanceWithPQ(query, neighbor, distanceTable);
                if (dist < currentDist) {
                    currentDist = dist;
                    current = neighbor;
                    improved = true;
                }
            }
        }

        return current;
    }

    /**
     * Beam search at a layer using PQ distances.
     */
    private PriorityQueue<HnswSearchResult> searchLayerBeamWithPQ(
            float[] query, NodeView entry, int level, int ef, PQDistanceTable distanceTable) {

        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance).reversed());
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance));
        java.util.Set<Long> visited = new java.util.HashSet<>();

        if (entry == null || entry.isTombstoned()) {
            return results;
        }

        float entryDist = computeDistanceWithPQ(query, entry, distanceTable);
        HnswSearchResult entryResult = createSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        results.add(entryResult);
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            NodeView currentNode = readNode(current.nodeId());

            if (currentNode == null || currentNode.isTombstoned()) {
                continue;
            }

            if (!results.isEmpty() && current.distance() > results.peek().distance()) {
                break;
            }

            List<Long> neighbors = currentNode.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                if (visited.contains(neighborId)) {
                    continue;
                }
                visited.add(neighborId);

                NodeView neighbor = readNode(neighborId);
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = computeDistanceWithPQ(query, neighbor, distanceTable);
                HnswSearchResult result = createSearchResult(neighborId, dist);

                if (results.size() < ef || dist < results.peek().distance()) {
                    candidates.add(result);
                    results.add(result);

                    while (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }

        return results;
    }

    /**
     * Computes distance using PQ when available, falling back to full precision.
     */
    private float computeDistanceWithPQ(float[] query, NodeView node, PQDistanceTable distanceTable) {
        // Try PQ-based distance first
        java.util.concurrent.ConcurrentHashMap<Long, byte[]> codeMap = this.pqCodes;
        if (codeMap != null) {
            byte[] codes = codeMap.get(node.nodeId());
            if (codes != null) {
                return distanceTable.lookup(codes);
            }
        }

        // A node with no stored code must still be scored on the distance table's
        // scale. Mixing raw distance-function values with PQ lookups in the same
        // priority queue compares two different metrics and ranks nodes by which
        // encoding they happen to have, so encode on the fly instead.
        ProductQuantizer quantizer = this.pq;
        if (quantizer != null && quantizer.isTrained()) {
            byte[] codes = quantizer.encode(node.vector());
            if (codeMap != null) {
                codeMap.put(node.nodeId(), codes);
            }
            return distanceTable.lookup(codes);
        }

        // No quantizer at all: fall back to the ordering distance (smaller is closer).
        return computeDistance(query, node.vector());
    }

    /**
     * Returns whether Product Quantization is enabled for this graph.
     */
    public boolean isPQEnabled() {
        return pq != null && pq.isTrained();
    }

    /**
     * Returns the number of vectors that have been encoded with PQ.
     */
    public int encodedVectorCount() {
        return pqCodes != null ? pqCodes.size() : 0;
    }

    // ==================== Lifecycle ====================

    /**
     * Flushes all changes to disk.
     */
    public void flush() {
        validateNotClosed();
        mappedFile.flush();
        persistDocumentMapping();
    }

    /**
     * Writes the node-to-document mapping beside the graph.
     *
     * <p>On flush and on close rather than on every registration: the integration worker records
     * one entry per vector it inserts, and a file write per vector would put the mapping on the
     * ingest hot path. A crash therefore loses whatever was registered since the last flush,
     * which is the same exposure the graph's own checkpoint has.
     *
     * <p>Failure is logged rather than thrown. A mapping that could not be written costs
     * document attribution after the next restart; letting it fail a close would cost the
     * graph's own durability.
     */
    private void persistDocumentMapping() {
        NodeDocumentMapping mapping = this.nodeDocumentMapping;
        if (mapping == null || !mapping.isPersistent()) {
            return;
        }
        try {
            mapping.persist();
        } catch (IOException e) {
            LOGGER.warn("Failed to persist the node-to-document mapping for {}: {}",
                path, e.toString());
        }
    }

    // ==================== Segment-based Serialization ====================

    /**
     * Serializes a node's data to a Segment.
     *
     * <p>This method writes the complete node data (ID, level, vector, and all
     * adjacency lists) to the provided Segment at the specified offset. This is
     * useful for exporting individual nodes or creating backups.</p>
     *
     * <p><strong>Format:</strong></p>
     * <pre>
     * [nodeId:8][level:4][vectorLen:4][vector:dim*4]
     * For each level 0..level: [connCount:4][neighbors:maxConn*4]
     * </pre>
     *
     * @param nodeId The node ID to serialize
     * @param segment The target Segment to write to
     * @param offset The offset within the Segment to start writing
     * @return The number of bytes written
     * @throws IllegalStateException if graph is closed
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if nodeId is invalid or segment is too small
     * @since 1.1
     */
    public long serializeNodeToSegment(long nodeId, Segment segment, long offset) {
        validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        NodeView node = readNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }

        lock.readLock().lock();
        try {
            long writeOffset = offset;

            // Write nodeId
            segment.setLong(writeOffset, node.nodeId());
            writeOffset += 8;

            // Write level
            segment.setInt(writeOffset, node.level());
            writeOffset += 4;

            // Write vector
            float[] vector = node.vector();
            segment.setInt(writeOffset, vector.length);
            writeOffset += 4;

            for (float v : vector) {
                segment.setFloat(writeOffset, v);
                writeOffset += 4;
            }

            // Write adjacency lists for each level
            for (int level = 0; level <= node.level(); level++) {
                List<Long> neighbors = node.getNeighbors(level);
                int maxConnAtLevel = (level == 0) ?
                    config.maxConnectionsLayer0() : config.maxConnections();

                // Write connection count
                segment.setInt(writeOffset, neighbors.size());
                writeOffset += 4;

                // Write neighbor IDs
                for (Long neighborId : neighbors) {
                    segment.setInt(writeOffset, neighborId.intValue());
                    writeOffset += 4;
                }

                // Skip reserved space for remaining connections
                int reservedSpace = (maxConnAtLevel - neighbors.size()) * 4;
                writeOffset += reservedSpace;
            }

            return writeOffset - offset;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deserializes a node from a Segment and inserts it into the graph.
     *
     * <p>This method reads node data from a Segment (in the format written by
     * {@link #serializeNodeToSegment}) and inserts it as a new node. The node
     * is assigned a new ID and connected to the graph structure.</p>
     *
     * <p><strong>Note:</strong> The neighbor IDs in the serialized data are
     * informational only. The new node will be connected using the standard
     * HNSW insertion algorithm.</p>
     *
     * @param segment The source Segment to read from
     * @param offset The offset within the Segment to start reading
     * @return The new node ID assigned to the deserialized node
     * @throws IllegalStateException if graph is closed or full
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if segment data is invalid
     * @since 1.1
     */
    public long deserializeNodeFromSegment(Segment segment, long offset) {
        validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        lock.writeLock().lock();
        try {
            long readOffset = offset;

            // Read and validate nodeId (informational)
            long serializedNodeId = segment.getLong(readOffset);
            readOffset += 8;

            // Read level
            int level = segment.getInt(readOffset);
            readOffset += 4;

            // Read vector
            int vectorLen = segment.getInt(readOffset);
            readOffset += 4;

            if (vectorLen != config.dimension()) {
                throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + config.dimension() +
                    ", got " + vectorLen);
            }

            float[] vector = new float[vectorLen];
            for (int i = 0; i < vectorLen; i++) {
                vector[i] = segment.getFloat(readOffset);
                readOffset += 4;
            }

            // Skip adjacency lists (we'll reconnect using standard algorithm)
            for (int l = 0; l <= level; l++) {
                int connCount = segment.getInt(readOffset);
                readOffset += 4;

                int maxConnAtLevel = (l == 0) ?
                    config.maxConnectionsLayer0() : config.maxConnections();
                readOffset += (long) maxConnAtLevel * 4; // Skip neighbors and reserved space
            }

            // Release lock and use standard insertion
            lock.writeLock().unlock();
            return insert(vector);
        } finally {
            // Ensure lock is released if not already
            if (lock.writeLock().isHeldByCurrentThread()) {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * Exports the graph header to a Segment.
     *
     * <p>This method writes the complete graph header (first 4KB) to the provided
     * Segment. Useful for creating snapshots or validating graph files.</p>
     *
     * @param segment The target Segment (must have at least HEADER_SIZE bytes)
     * @return The number of bytes written
     * @throws IllegalStateException if graph is closed
     * @throws NullPointerException if segment is null
     * @since 1.1
     */
    public long exportHeaderToSegment(Segment segment) {
        validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small for header: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        lock.readLock().lock();
        try {
            // Write header fields
            segment.setLong(OFFSET_MAGIC, MAGIC);
            segment.setInt(OFFSET_VERSION, VERSION);
            segment.setInt(OFFSET_DIMENSION, config.dimension());
            segment.setLong(OFFSET_NODE_COUNT, nodeCount);
            segment.setLong(OFFSET_ENTRY_POINT, entryPointId);
            segment.setInt(OFFSET_MAX_LEVEL, maxLevel);
            segment.setLong(OFFSET_LAST_INTEGRATED, lastIntegratedOffset.get());
            segment.setInt(OFFSET_CONFIG_MAX_CONN, config.maxConnections());
            segment.setInt(OFFSET_CONFIG_MAX_CONN_0, config.maxConnectionsLayer0());
            segment.setInt(OFFSET_CONFIG_EF_CONSTRUCTION, config.efConstruction());
            segment.setInt(OFFSET_CONFIG_EF_SEARCH, config.efSearch());
            segment.setLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));
            segment.setLong(OFFSET_MAX_NODES, maxNodes);
            segment.setInt(OFFSET_METRIC, PersistedMetric.codeFor(distanceFunction.name()));

            return HEADER_SIZE;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the native MemorySegment if Panama FFM is available.
     *
     * <p>This method provides escape-hatch access to the underlying native
     * MemorySegment for advanced operations. Returns null if Panama FFM is
     * not available or if the graph is not backed by a native segment.</p>
     *
     * <p><strong>Warning:</strong> Direct access to the native MemorySegment
     * bypasses the Segment abstraction. Use with caution.</p>
     *
     * @return The underlying java.lang.foreign.MemorySegment, or null
     * @throws IllegalStateException if graph is closed
     * @since 1.1
     */
    public java.lang.foreign.MemorySegment getNativeMemorySegment() {
        validateNotClosed();
        // MappedFile uses Panama FFM internally, but doesn't expose the segment directly
        // This method is provided for API consistency
        return null;
    }

    // ==================== Node-to-Document Mapping ====================

    /**
     * Registers a document ID for a node.
     *
     * <p>This method associates an HNSW node ID with the actual document ID
     * from the source document. This mapping is essential for the
     * {@code DocumentAtomicityFilter} to correctly identify which document
     * each query result belongs to.</p>
     *
     * <p><strong>Call this method after inserting a vector with a document ID.</strong></p>
     *
     * @param nodeId The HNSW node ID
     * @param documentId The document ID (e.g., "doc1", "source1:file1")
     * @throws IllegalArgumentException if nodeId is negative or documentId is null/empty
     */
    public void registerDocumentId(long nodeId, String documentId) {
        if (nodeDocumentMapping != null) {
            nodeDocumentMapping.register(nodeId, documentId);
            LOGGER.trace("Registered documentId={} for nodeId={}", documentId, nodeId);
        }
    }

    /**
     * Gets the document ID for a node.
     *
     * @param nodeId The HNSW node ID
     * @return The document ID, or null if not found
     */
    public String getDocumentId(long nodeId) {
        return nodeDocumentMapping != null ? nodeDocumentMapping.getDocumentId(nodeId) : null;
    }

    /**
     * Sets the node-to-document mapping for this graph.
     *
     * <p>This allows using a persistent mapping that can survive restarts.</p>
     *
     * @param mapping The mapping to use
     */
    public void setNodeDocumentMapping(NodeDocumentMapping mapping) {
        this.nodeDocumentMapping = Objects.requireNonNull(mapping, "mapping cannot be null");
    }

    /**
     * Gets the current node-to-document mapping.
     *
     * @return The current mapping, or null if not set
     */
    public NodeDocumentMapping getNodeDocumentMapping() {
        return nodeDocumentMapping;
    }

    /**
     * Creates a search result with the document ID populated from the mapping.
     *
     * @param nodeId The node ID
     * @param distance The distance value
     * @return An HnswSearchResult with the document ID included
     */
    private HnswSearchResult createSearchResult(long nodeId, float distance) {
        String documentId = getDocumentId(nodeId);
        // Stamp the generation so a caller that re-reads this node later can have a
        // renumbered ID rejected rather than silently resolved to another vector.
        return new HnswSearchResult(nodeId, distance, documentId, generation);
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            persistDocumentMapping();

            // Flush before closing
            if (mappedFile != null) {
                try {
                    mappedFile.flush();
                    mappedFile.close();
                } catch (Exception e) {
                    LOGGER.warn("Error closing mapped file: {}", e.getMessage());
                }
            }

            // Close arena
            if (arena != null) {
                try {
                    arena.close();
                } catch (Exception e) {
                    LOGGER.warn("Error closing arena: {}", e.getMessage());
                }
            }

            LOGGER.info("Closed HNSW graph at {} ({} nodes)", path, nodeCount);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("HnswGraph{path=%s, nodes=%d, dimension=%d, maxLevel=%d}",
            path, nodeCount, config.dimension(), maxLevel);
    }

    // ==================== Inner Classes ====================

    /**
     * View of a node stored in the memory-mapped file.
     *
     * <p>This class provides zero-copy access to node data by reading
     * directly from the memory-mapped file. It does not load the entire
     * node into memory, making it suitable for large graphs.</p>
     */
    public final class NodeView {
        private final long nodeId;
        private final int level;
        private final long dataOffset;
        private final int flags;
        private final long viewGeneration;

        NodeView(long nodeId, int level, long dataOffset, int flags, long viewGeneration) {
            this.nodeId = nodeId;
            this.level = level;
            this.dataOffset = dataOffset;
            this.flags = flags;
            this.viewGeneration = viewGeneration;
        }

        /**
         * Returns the graph generation this view was created in.
         *
         * @return The generation
         */
        public long generation() {
            return viewGeneration;
        }

        /**
         * Fails fast if compaction replaced the backing file since this view was
         * created: the recorded offset then points into a file that no longer
         * describes this node.
         */
        private void validateGeneration() {
            if (viewGeneration != generation) {
                throw new IllegalStateException(
                    "NodeView for node " + nodeId + " belongs to graph generation " +
                        viewGeneration + " but the graph is now at generation " + generation);
            }
        }

        public long nodeId() {
            return nodeId;
        }

        public int level() {
            return level;
        }

        public boolean isTombstoned() {
            return (flags & FLAG_TOMBSTONE) != 0;
        }

        /**
         * Reads the vector data for this node.
         *
         * @return The vector (a new array is allocated each time)
         */
        public float[] vector() {
            lock.readLock().lock();
            try {
                validateGeneration();
                int dim = config.dimension();
                float[] vec = new float[dim];

                // Vector starts after nodeId(8) + level(4) + vectorLen(4)
                long vecOffset = dataOffset + 16;
                for (int i = 0; i < dim; i++) {
                    vec[i] = mappedFile.readFloat(vecOffset + (long) i * 4);
                }

                return vec;
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Reads the neighbor list for a specific level.
         *
         * <p><strong>Note:</strong> This returns ALL neighbors, including potentially
         * tombstoned ones. Use {@link #getActiveNeighbors(int)} to get only active neighbors.</p>
         *
         * @param level The level to read (0 to this.level)
         * @return List of neighbor node IDs (may include tombstoned nodes)
         */
        public List<Long> getNeighbors(int level) {
            if (level > this.level || level < 0) {
                return new ArrayList<>();
            }

            lock.readLock().lock();
            try {
                validateGeneration();
                int vectorBytes = config.dimension() * Float.BYTES;

                // Navigate to the connection count for this level
                long connOffset = dataOffset + 16 + vectorBytes;
                for (int l = 0; l < level; l++) {
                    int maxConn = l == 0 ? config.maxConnectionsLayer0() : config.maxConnections();
                    connOffset += 4 + (long) maxConn * 4;
                }

                // Read connection count
                int connCount = mappedFile.readInt(connOffset);

                // Read neighbor IDs
                List<Long> neighbors = new ArrayList<>(connCount);
                for (int i = 0; i < connCount; i++) {
                    neighbors.add((long) mappedFile.readInt(connOffset + 4 + (long) i * 4));
                }

                return neighbors;
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Reads the neighbor list for a specific level, filtering out tombstoned nodes.
         *
         * <p>This method is used during graph traversal to skip deleted nodes.
         * Tombstoned nodes are never returned in the result list.</p>
         *
         * @param level The level to read (0 to this.level)
         * @return List of active (non-tombstoned) neighbor node IDs
         */
        public List<Long> getActiveNeighbors(int level) {
            lock.readLock().lock();
            try {
                List<Long> allNeighbors = getNeighbors(level);
                List<Long> activeNeighbors = new ArrayList<>(allNeighbors.size());

                for (Long neighborId : allNeighbors) {
                    NodeView neighbor = readNodeUnderLock(neighborId);
                    if (neighbor != null && !neighbor.isTombstoned()) {
                        activeNeighbors.add(neighborId);
                    }
                }

                return activeNeighbors;
            } finally {
                lock.readLock().unlock();
            }
        }
    }
}
