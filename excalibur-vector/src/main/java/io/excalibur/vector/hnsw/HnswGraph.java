package io.excalibur.vector.hnsw;

import io.excalibur.core.mapped.MappedFile;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.core.memory.Segment;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.quantization.PQDistanceTable;
import io.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final int VERSION = 1;

    // Header size (4KB aligned)
    private static final int HEADER_SIZE = 4096;

    // Node index entry size: nodeId(8) + dataOffset(8) + level(4) + flags(4)
    private static final int NODE_INDEX_ENTRY_SIZE = 24;

    // Flag bits for node state
    private static final int FLAG_TOMBSTONE = 0x01;

    // Layouts for memory access (little-endian for consistency)
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

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

    // Configuration
    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private Path path;
    private Arena arena;
    private MappedFile mappedFile;
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
    private long maxNodes;

    // Optional Product Quantizer for compressed vectors
    // Volatile for safe publication after training
    private volatile ProductQuantizer pq;

    // PQ codes storage: maps nodeId -> PQ codes (only when PQ is trained)
    // This is a simple in-memory map; for production, consider disk-backed storage
    private volatile java.util.concurrent.ConcurrentHashMap<Long, byte[]> pqCodes;

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

        this.path = path;
        this.config = config;
        this.distanceFunction = distanceFunction;
        this.maxNodes = maxNodes;

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

        // Create shared arena for thread-safe access
        this.arena = Arena.ofShared();

        // Create or open the mapped file
        this.mappedFile = MappedFile.create(path, totalSize);

        // Initialize header
        initializeHeader();

        LOGGER.info("Created HNSW graph at {} with dimension={}, maxNodes={}",
            path, config.dimension(), maxNodes);
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

        // Open the file to read size
        long fileSize = java.nio.file.Files.size(path);

        // Create arena and mapped file (read-write for potential updates)
        Arena arena = Arena.ofShared();
        MappedFile mappedFile = MappedFile.open(path, fileSize);

        try {
            // Read and validate header
            long magic = mappedFile.readLong(OFFSET_MAGIC);
            if (magic != MAGIC) {
                mappedFile.close();
                arena.close();
                throw new IOException("Invalid HNSW file: bad magic number");
            }

            int version = mappedFile.readInt(OFFSET_VERSION);
            if (version != VERSION) {
                mappedFile.close();
                arena.close();
                throw new IOException("Unsupported HNSW version: " + version);
            }

            int dimension = mappedFile.readInt(OFFSET_DIMENSION);
            long nodeCount = mappedFile.readLong(OFFSET_NODE_COUNT);
            long entryPoint = mappedFile.readLong(OFFSET_ENTRY_POINT);
            int maxLevel = mappedFile.readInt(OFFSET_MAX_LEVEL);
            long lastIntegrated = mappedFile.readLong(OFFSET_LAST_INTEGRATED);

            int maxConn = mappedFile.readInt(OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = mappedFile.readInt(OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = mappedFile.readInt(OFFSET_CONFIG_EF_CONSTRUCTION);
            double mL = Double.longBitsToDouble(mappedFile.readLong(OFFSET_CONFIG_ML));

            HnswConfig config = new HnswConfig(maxConn, maxConn0, efConstruction, 100, mL, dimension);

            // Calculate capacity based on existing node count
            long maxNodes = Math.max(nodeCount * 2, 1000);

            // Calculate data region offset
            long nodeIndexSize = maxNodes * NODE_INDEX_ENTRY_SIZE;
            long dataRegionOffset = HEADER_SIZE + nodeIndexSize;

            // Find the data write offset by scanning the last node
            long dataWriteOffset = 0;
            if (nodeCount > 0) {
                // Read the last node's offset and estimate size
                long lastIndexOffset = HEADER_SIZE + (nodeCount - 1) * NODE_INDEX_ENTRY_SIZE;
                long lastDataOffset = mappedFile.readLong(lastIndexOffset + 8);
                int lastLevel = mappedFile.readInt(lastIndexOffset + 16);
                // Estimate node size based on level
                int estimatedSize = estimateNodeSize(config, lastLevel);
                dataWriteOffset = lastDataOffset + estimatedSize;
            }

            // Create graph using private constructor (doesn't reinitialize header)
            HnswGraph graph = new HnswGraph(path, config, distanceFunction, maxNodes,
                mappedFile, arena, dataRegionOffset);

            // Restore state from persisted data
            graph.nodeCount = nodeCount;
            graph.entryPointId = entryPoint;
            graph.maxLevel = maxLevel;
            graph.lastIntegratedOffset.set(lastIntegrated);
            graph.nodeIdGenerator.set(nodeCount);
            graph.dataWriteOffset = dataWriteOffset;

            LOGGER.info("Opened HNSW graph at {} with {} nodes, dimension={}",
                path, nodeCount, dimension);

            return graph;
        } catch (Exception e) {
            mappedFile.close();
            arena.close();
            throw new IOException("Failed to open HNSW graph: " + path, e);
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
            int dimension = segment.getInt(OFFSET_DIMENSION);
            long nodeCount = segment.getLong(OFFSET_NODE_COUNT);
            long entryPoint = segment.getLong(OFFSET_ENTRY_POINT);
            int maxLevel = segment.getInt(OFFSET_MAX_LEVEL);
            long lastIntegrated = segment.getLong(OFFSET_LAST_INTEGRATED);
            int maxConn = segment.getInt(OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = segment.getInt(OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = segment.getInt(OFFSET_CONFIG_EF_CONSTRUCTION);
            double mL = Double.longBitsToDouble(segment.getLong(OFFSET_CONFIG_ML));

            return new HeaderInfo(version, dimension, nodeCount, entryPoint, maxLevel,
                lastIntegrated, maxConn, maxConn0, efConstruction, mL);
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
        double mL
    ) {
        /**
         * Creates an HnswConfig from this header info.
         *
         * @return A new HnswConfig with the header values
         */
        public HnswConfig toConfig() {
            return new HnswConfig(maxConnections, maxConnectionsLayer0,
                efConstruction, 100, mL, dimension);
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
            mappedFile.writeLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));

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
            final int efSearch = config.efSearch();

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
     * <p>This method performs HNSW search but only considers nodes whose IDs are in the
     * provided candidate set. This is used for LSH pre-filtering where LSH identifies
     * a subset of potentially relevant vectors, and this search narrows down to the
     * actual nearest neighbors within that subset.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Find entry points from the candidate set at the highest layer</li>
     *   <li>Greedy search at each layer, only considering candidate nodes</li>
     *   <li>At layer 0, perform beam search restricted to candidate set</li>
     *   <li>Return top-k results from the candidate set</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>If candidateIds is null or empty, falls back to full graph search</li>
     *   <li>Results are sorted by distance (closest first)</li>
     *   <li>Only nodes in the candidate set are returned</li>
     *   <li>Tombstoned nodes are automatically skipped</li>
     * </ul>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @param candidateIds Set of node IDs to search within (null or empty = full search)
     * @return List of search results sorted by distance (closest first)
     * @throws IllegalStateException if graph is closed
     * @throws IllegalArgumentException if query is null or wrong dimension, or k <= 0
     * @since 1.2
     */
    public List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
        validateNotClosed();
        validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        // If candidate filter is null, fall back to full search
        // If candidate filter is empty (explicitly no candidates), return empty
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

            // Find an entry point from the candidate set
            long entryFromCandidates = findEntryPointFromCandidates(candidateIds);
            if (entryFromCandidates < 0) {
                // No valid entry point in candidates, return empty
                return new ArrayList<>();
            }

            final float[] queryVec = query;
            final int efSearch = Math.max(config.efSearch(), k * 2);

            // Start from the candidate entry point
            NodeView current = readNode(entryFromCandidates);
            if (current == null || current.isTombstoned()) {
                return new ArrayList<>();
            }

            // Get the level of our entry point
            int startLevel = current.level();

            // Search through layers from start level down to layer 1
            // Only consider nodes that are in the candidate set
            for (int level = startLevel; level > 0; level--) {
                NodeView closest = searchLayerSimpleWithFilter(queryVec, current, level, candidateIds);
                if (closest != null) {
                    current = closest;
                }
            }

            // At layer 0, perform beam search restricted to candidates
            PriorityQueue<HnswSearchResult> results = searchLayerBeamWithFilter(
                queryVec, current, 0, efSearch, candidateIds);

            // Extract results from the heap
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                // Verify node is still valid and in candidate set
                if (candidateIds.contains(result.nodeId())) {
                    NodeView node = readNode(result.nodeId());
                    if (node != null && !node.isTombstoned()) {
                        allResults.add(result);
                    }
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
     * Finds an entry point from the candidate set.
     *
     * <p>Prefers higher-level nodes as they provide better starting points for
     * hierarchical search. Falls back to any valid candidate if no high-level
     * nodes are found.</p>
     *
     * @param candidateIds Set of candidate node IDs
     * @return A valid entry point ID, or -1 if none found
     */
    private long findEntryPointFromCandidates(Set<Long> candidateIds) {
        long bestCandidate = -1;
        int bestLevel = -1;

        for (Long nodeId : candidateIds) {
            NodeView node = readNode(nodeId);
            if (node != null && !node.isTombstoned()) {
                if (node.level() > bestLevel) {
                    bestLevel = node.level();
                    bestCandidate = nodeId;
                }
                // If we haven't found any high-level node, take the first valid one
                if (bestCandidate < 0) {
                    bestCandidate = nodeId;
                    bestLevel = node.level();
                }
            }
        }

        return bestCandidate;
    }

    /**
     * Greedy search at a single layer, restricted to candidate set.
     *
     * <p>Only considers neighbors that are in the candidateIds set.</p>
     */
    private NodeView searchLayerSimpleWithFilter(float[] query, NodeView entry, int level, Set<Long> candidateIds) {
        NodeView current = entry;
        float currentDist = computeDistance(query, current.vector());
        boolean improved = true;

        while (improved) {
            improved = false;
            List<Long> neighbors = current.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                // Skip if not in candidate set
                if (!candidateIds.contains(neighborId)) {
                    continue;
                }

                NodeView neighbor = readNode(neighborId);
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
     * Beam search at a layer with candidate set filtering.
     *
     * <p>Only considers nodes that are in the candidateIds set during traversal.</p>
     */
    private PriorityQueue<HnswSearchResult> searchLayerBeamWithFilter(
            float[] query, NodeView entry, int level, int ef, Set<Long> candidateIds) {

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
        HnswSearchResult entryResult = new HnswSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        results.add(entryResult);
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            NodeView currentNode = readNode(current.nodeId());

            if (currentNode == null || currentNode.isTombstoned()) {
                continue;
            }

            // If current is further than furthest in results, stop
            if (!results.isEmpty() && current.distance() > results.peek().distance()) {
                break;
            }

            List<Long> neighbors = currentNode.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                // Skip if not in candidate set
                if (!candidateIds.contains(neighborId)) {
                    continue;
                }

                if (visited.contains(neighborId)) {
                    continue;
                }
                visited.add(neighborId);

                NodeView neighbor = readNode(neighborId);
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = computeDistance(query, neighbor.vector());
                HnswSearchResult result = new HnswSearchResult(neighborId, dist);

                // Add to candidates if closer than furthest result, or if results not full
                if (results.size() < ef || dist < results.peek().distance()) {
                    candidates.add(result);
                    results.add(result);

                    // Keep results bounded
                    while (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }

        return results;
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
     * Beam search at a layer with candidate set.
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
        HnswSearchResult entryResult = new HnswSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        results.add(entryResult);
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            NodeView currentNode = readNode(current.nodeId());

            if (currentNode == null || currentNode.isTombstoned()) {
                continue;
            }

            // If current is further than furthest in results, stop
            // results.peek() returns furthest since it's a max-heap
            if (!results.isEmpty() && current.distance() > results.peek().distance()) {
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
                HnswSearchResult result = new HnswSearchResult(neighborId, dist);

                // Add to candidates if closer than furthest result, or if results not full
                if (results.size() < ef || dist < results.peek().distance()) {
                    candidates.add(result);
                    results.add(result);

                    // Keep results bounded
                    while (results.size() > ef) {
                        results.poll();
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
     * <p>This ordering ensures that upon crash recovery, the persisted value is
     * always less than or equal to any value that was visible to readers.</p>
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

            // PERSIST FIRST: Write to memory-mapped file before updating memory
            // This ensures crash recovery sees persisted <= memory
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
     * <p><strong>Atomicity and persistence guarantee:</strong> This method uses
     * a CAS loop with persistence. The ordering ensures:</p>
     * <ol>
     *   <li>CAS on in-memory atomic value</li>
     *   <li>If CAS succeeds, immediately persist to memory-mapped file</li>
     *   <li>If persistence fails, in-memory value is still valid (will be replayed)</li>
     * </ol>
     *
     * <p>Note: This is lock-free for the common case where the offset is increasing
     * monotonically from a single writer. Multiple concurrent writers will retry.</p>
     *
     * @param newOffset The new offset value (must be >= 0)
     * @return true if the offset was updated
     */
    public boolean compareAndSetLastIntegratedOffset(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        while (true) {
            long current = lastIntegratedOffset.get();
            if (newOffset <= current) {
                // Not an increase
                return false;
            }

            // Try to atomically update memory
            if (lastIntegratedOffset.compareAndSet(current, newOffset)) {
                // CAS succeeded - now persist
                // Note: Even if this write fails, the in-memory value is correct
                // Recovery will replay from write log
                mappedFile.writeLong(OFFSET_LAST_INTEGRATED, newOffset);
                LOGGER.trace("CAS updated lastIntegratedOffset from {} to {} (persisted)", current, newOffset);
                return true;
            }
            // CAS failed due to concurrent update, retry
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
     * @param nodeId The node ID to read
     * @return NodeView for the node, or null if not found or tombstoned
     */
    public NodeView readNode(long nodeId) {
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

        return new NodeView(nodeId, level, absoluteOffset, flags);
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
     * Computes distance between query and a vector.
     */
    private float computeDistance(float[] query, float[] vector) {
        return distanceFunction.compute(query, vector);
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

        lock.readLock().lock();
        try {
            if (nodeCount == 0) {
                throw new IllegalStateException("Cannot train ProductQuantizer on empty graph");
            }

            // Collect sample vectors
            int actualSampleSize = sampleSize > 0 ? Math.min(sampleSize, (int) nodeCount) : (int) nodeCount;
            List<float[]> samples = new ArrayList<>(actualSampleSize);

            // Sample uniformly from the graph
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            java.util.Set<Integer> sampledIndices = new java.util.HashSet<>();

            while (sampledIndices.size() < actualSampleSize && sampledIndices.size() < nodeCount) {
                int idx = random.nextInt((int) nodeCount);
                if (sampledIndices.add(idx)) {
                    NodeView node = readNode(idx);
                    if (node != null && !node.isTombstoned()) {
                        samples.add(node.vector());
                    }
                }
            }

            if (samples.isEmpty()) {
                throw new IllegalStateException("No valid vectors found for training");
            }

            LOGGER.info("Training ProductQuantizer on {} sample vectors", samples.size());

            // Create and train quantizer
            ProductQuantizer quantizer = new ProductQuantizer(config.dimension(), numSubspaces, numCentroids);
            quantizer.train(samples.toArray(new float[0][]));

            // Store reference
            this.pq = quantizer;
            this.pqCodes = new java.util.concurrent.ConcurrentHashMap<>();

            LOGGER.info("ProductQuantizer trained successfully (compression ratio: {}x)",
                quantizer.compressionRatio());

            return quantizer;
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

        lock.writeLock().lock();
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
            LOGGER.info("Encoded {} vectors with ProductQuantizer", encoded);
        } finally {
            lock.writeLock().unlock();
        }
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

        // Create distance table for efficient lookup
        PQDistanceTable distanceTable = pq.createDistanceTable(query);
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

            final int efSearch = config.efSearch();

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

            // Extract and sort results
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                allResults.add(result);
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
        HnswSearchResult entryResult = new HnswSearchResult(entry.nodeId(), entryDist);
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
                HnswSearchResult result = new HnswSearchResult(neighborId, dist);

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
        if (pqCodes != null) {
            byte[] codes = pqCodes.get(node.nodeId());
            if (codes != null) {
                return distanceTable.lookup(codes);
            }
        }

        // Fall back to full precision
        return distanceFunction.compute(query, node.vector());
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
            segment.setLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));

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

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

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

        NodeView(long nodeId, int level, long dataOffset, int flags) {
            this.nodeId = nodeId;
            this.level = level;
            this.dataOffset = dataOffset;
            this.flags = flags;
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
            int dim = config.dimension();
            float[] vec = new float[dim];

            // Vector starts after nodeId(8) + level(4) + vectorLen(4)
            long vecOffset = dataOffset + 16;
            for (int i = 0; i < dim; i++) {
                vec[i] = mappedFile.readFloat(vecOffset + (long) i * 4);
            }

            return vec;
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
            List<Long> allNeighbors = getNeighbors(level);
            List<Long> activeNeighbors = new ArrayList<>(allNeighbors.size());

            for (Long neighborId : allNeighbors) {
                NodeView neighbor = readNode(neighborId);
                if (neighbor != null && !neighbor.isTombstoned()) {
                    activeNeighbors.add(neighborId);
                }
            }

            return activeNeighbors;
        }
    }
}
