package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.mapped.MappedFile;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Brings a graph file into use: creating a new one, re-attaching to one that already holds
 * data, and recovering the state a crash left behind.
 *
 * <h2>Why it is its own class</h2>
 * <p>Attaching is where almost every way of getting the graph wrong is decided, and none of it
 * runs again afterwards. A header can be foreign, damaged, or written by a build that knew a
 * different layout; a file can exist with a header that was never written; a count in the
 * header can be ahead of the index entries behind it. Each of those has a different right
 * answer - refuse, adopt, start fresh, recover to the lower count - and the reasoning for each
 * is worth reading together rather than scattered through a constructor.</p>
 *
 * <h2>Adopt, do not impose</h2>
 * <p>{@code dimension}, {@code maxConnections}, {@code maxConnectionsLayer0} and
 * {@code maxNodes} fix where every node record begins, so on an existing file they come from
 * the file and a caller asking for different ones is warned rather than obeyed. The remaining
 * tunables - {@code efSearch}, {@code efConstruction}, {@code mL} - only affect future work, so
 * the caller's values win and are written back.</p>
 *
 * <h2>Cleanup on refusal</h2>
 * <p>A constructor that refuses a header never returns, so nobody is left holding the shared
 * arena and whatever mapping was already made. Every path here that can fail after mapping
 * closes both on the way out.</p>
 */
final class HnswGraphOpener {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphOpener.class);

    private HnswGraphOpener() {
    }

    /**
     * Returns the bytes a brand new graph file needs after its header.
     *
     * <p>The node index is sized for the full capacity up front, because its width is what
     * fixes where the data region starts and it can never move. The data region starts at a
     * tenth of the capacity's worth of average-sized records, and grows on demand.</p>
     *
     * @param config The graph's configuration
     * @param maxNodes The capacity being created for
     * @return The size of the node index plus the initial data region
     */
    static long initialFileSize(HnswConfig config, long maxNodes) {
        long nodeIndexSize = maxNodes * HnswNodeRecords.NODE_INDEX_ENTRY_SIZE;
        long estimatedVectorSize = config.vectorByteSize();
        long estimatedAdjacencySize = config.adjacencyByteSizeLayer0() +
            (long) config.maxConnections() * config.adjacencyByteSize();
        long estimatedNodeSize = 8 + 4 + 4 + estimatedVectorSize + estimatedAdjacencySize;
        long initialDataSize = Math.max(1024 * 1024, maxNodes / 10 * estimatedNodeSize);
        return nodeIndexSize + initialDataSize;
    }

    /**
     * Validates a caller's request and brings a brand new {@link HnswGraph} into service.
     *
     * <p>This is the body of the public constructor. The order is load-bearing: the
     * compaction recovery runs before anything beside the graph is read, the sidecar mapping
     * is opened before the graph file is, and the arena exists before any mapping is made
     * over it.</p>
     *
     * @param graph The graph being constructed
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @throws IOException if the file cannot be created, mapped, or adopted
     * @throws IllegalArgumentException if parameters are invalid
     */
    static void attach(HnswGraph graph, Path path, HnswConfig config,
                       DistanceFunction distanceFunction, long maxNodes) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(config, "Config cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive: " + maxNodes);
        }
        // Neighbor IDs are stored as 4-byte int in the adjacency lists. Graphs with more than
        // Integer.MAX_VALUE nodes would silently truncate neighbor references, causing data
        // corruption.
        if (maxNodes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxNodes cannot exceed Integer.MAX_VALUE (" + Integer.MAX_VALUE +
                ") because neighbor IDs are stored as int: " + maxNodes);
        }

        graph.path = path;
        graph.config = config;
        graph.distanceFunction = distanceFunction;
        graph.maxNodes = maxNodes;

        // Before anything beside the graph is read. A compaction replaces the graph and its
        // mapping with two renames it cannot perform as one, so a crash between them leaves a
        // renumbered graph next to a mapping that describes the numbering before it. Resolving
        // that has to happen on every path that attaches a graph, not only on open(Path,
        // DistanceFunction): production reaches a graph through the constructor, so a recovery
        // that ran only in open() left every deployment serving the mismatched pair.
        HnswCompactor.finishInterruptedCompaction(path);

        // Beside the graph file, and loaded if it is already there. In memory only, this
        // mapping was rebuilt empty on every restart: the integration worker records it as it
        // inserts vectors, so a graph that was fully integrated before a restart came back with
        // no document identity on any of its nodes. Search still returned them; they simply
        // stopped being attributable to a document, which is what document-level filtering and
        // payload resolution work from.
        graph.nodeDocumentMapping = openDocumentMapping(path);

        // Create shared arena for thread-safe access
        graph.arena = Arena.ofShared();

        // A graph file may already exist for this path (a segment that is being re-attached
        // after a restart). MappedFile.create() does NOT truncate, so unconditionally
        // rewriting the header here would orphan every persisted node: the node count, entry
        // point, highest layer and integration checkpoint would be reset and the next insert
        // would overwrite node 0's record. Adopt the persisted layout instead and only
        // initialize a genuinely new file.
        attachOrInitialize(graph, config, maxNodes);
    }

    /**
     * Brings the backing file into use, adopting an existing one or creating a new one.
     *
     * @param graph The graph being constructed
     * @param requestedConfig The configuration the caller asked for
     * @param requestedMaxNodes The capacity the caller asked for
     * @throws IOException if the file exists and cannot be used as requested
     */
    static void attachOrInitialize(HnswGraph graph, HnswConfig requestedConfig,
                                   long requestedMaxNodes) throws IOException {
        boolean attached = false;
        try {
            HnswGraphHeader.PersistedLayout existing =
                HnswGraphHeader.readPersistedLayout(graph.path);
            if (existing != null) {
                adoptPersistedLayout(graph, existing, requestedConfig, requestedMaxNodes);
            } else {
                initializeNewFile(graph);
            }
            attached = true;
        } finally {
            if (!attached) {
                // A refused header (wrong dimension, wrong metric, unreadable) must not
                // strand the shared arena and whatever mapping was already made: the
                // constructor never returns, so nobody is left to close them.
                closeQuietly(graph.mappedFile, graph.arena);
            }
        }
    }

    /**
     * Creates and initializes the backing file for a brand new graph.
     *
     * @param graph The graph being constructed
     * @throws IOException if the file cannot be created or mapped
     */
    private static void initializeNewFile(HnswGraph graph) throws IOException {
        long nodeIndexSize = graph.maxNodes * HnswNodeRecords.NODE_INDEX_ENTRY_SIZE;
        graph.dataRegionOffset = HnswGraphHeader.HEADER_SIZE + nodeIndexSize;
        long totalSize = HnswGraphHeader.HEADER_SIZE + initialFileSize(graph.config, graph.maxNodes);

        graph.mappedFile = MappedFile.create(graph.path, totalSize);

        initializeHeader(graph);

        LOGGER.info("Created HNSW graph at {} with dimension={}, maxNodes={}",
            graph.path, graph.config.dimension(), graph.maxNodes);
    }

    /**
     * Initializes the file header of a brand new graph.
     *
     * @param graph The graph being constructed
     */
    private static void initializeHeader(HnswGraph graph) {
        graph.lock.writeLock().lock();
        try {
            HnswGraphHeader.writeInitial(
                graph.mappedFile, graph.config, graph.maxNodes, graph.distanceFunction);
            graph.formatVersion = HnswGraphHeader.VERSION;
            graph.dataWriteOffset = 0;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Re-attaches to an existing graph file, restoring its persisted state.
     *
     * <p>A different dimension is rejected outright: it means the file holds vectors
     * from a different embedding space, which no amount of re-reading can fix. So is a
     * different distance metric, for the reason given on
     * {@link HnswGraphHeader#requireMatchingMetric}.</p>
     *
     * @param graph The graph being constructed
     * @param existing The layout read from the file header
     * @param requestedConfig The configuration the caller asked for
     * @param requestedMaxNodes The capacity the caller asked for
     * @throws IOException if the persisted layout is incompatible with the request
     */
    private static void adoptPersistedLayout(HnswGraph graph,
                                             HnswGraphHeader.PersistedLayout existing,
                                             HnswConfig requestedConfig,
                                             long requestedMaxNodes) throws IOException {
        HnswConfig persisted = existing.config();
        if (persisted.dimension() != requestedConfig.dimension()) {
            throw new IOException("Dimension mismatch for existing HNSW graph " + graph.path +
                ": file holds " + persisted.dimension() +
                ", requested " + requestedConfig.dimension());
        }

        HnswGraphHeader.requireMatchingMetric(
            existing.distanceMetric(), graph.distanceFunction, graph.path);

        warnOnIgnoredLayoutChange(graph.path, "maxConnections", persisted.maxConnections(),
            requestedConfig.maxConnections());
        warnOnIgnoredLayoutChange(graph.path, "maxConnectionsLayer0",
            persisted.maxConnectionsLayer0(), requestedConfig.maxConnectionsLayer0());
        warnOnIgnoredLayoutChange(graph.path, "maxNodes", existing.maxNodes(), requestedMaxNodes);

        graph.config = new HnswConfig(
            persisted.maxConnections(),
            persisted.maxConnectionsLayer0(),
            requestedConfig.efConstruction(),
            requestedConfig.efSearch(),
            requestedConfig.mL(),
            persisted.dimension());
        graph.maxNodes = existing.maxNodes();
        graph.formatVersion = existing.version();
        graph.dataRegionOffset = HnswGraphHeader.HEADER_SIZE
            + existing.maxNodes() * HnswNodeRecords.NODE_INDEX_ENTRY_SIZE;
        graph.mappedFile = MappedFile.open(graph.path, Files.size(graph.path));

        restoreStateFromHeader(graph);

        // Persist the tunables the caller supplied so a later open() without a config
        // sees the values this process is actually using.
        graph.mappedFile.writeInt(
            HnswGraphHeader.OFFSET_CONFIG_EF_CONSTRUCTION, graph.config.efConstruction());
        graph.mappedFile.writeInt(
            HnswGraphHeader.OFFSET_CONFIG_EF_SEARCH, graph.config.efSearch());
        graph.mappedFile.writeLong(
            HnswGraphHeader.OFFSET_CONFIG_ML, Double.doubleToLongBits(graph.config.mL()));

        // After the tunables above, which live inside the region the header checksum covers.
        graph.lifecycle.breakSeal();

        graph.restorePersistedQuantizer();

        LOGGER.info("Re-attached to existing HNSW graph at {} with {} nodes, dimension={}, maxNodes={}",
            graph.path, graph.nodeCount, graph.config.dimension(), graph.maxNodes);
    }

    /**
     * Warns that a layout parameter cannot be changed on an existing graph file.
     *
     * @param path The graph file, for the message
     * @param name The parameter's name
     * @param persistedValue What the file was created with
     * @param requestedValue What the caller asked for
     */
    private static void warnOnIgnoredLayoutChange(Path path, String name,
                                                  long persistedValue, long requestedValue) {
        if (persistedValue != requestedValue) {
            LOGGER.warn("Existing HNSW graph at {} was created with {}={}; ignoring requested {} " +
                "because the on-disk layout is already fixed (rebuild the index to change it)",
                path, name, persistedValue, requestedValue);
        }
    }

    /**
     * Restores in-memory state from the persisted header.
     *
     * <p>Requires the graph's mapping, configuration, capacity and data region offset to
     * already reflect the persisted layout.</p>
     *
     * @param graph The graph whose state is being restored
     */
    static void restoreStateFromHeader(HnswGraph graph) {
        MappedFile file = graph.mappedFile;
        long persistedNodeCount = file.readLong(HnswGraphHeader.OFFSET_NODE_COUNT);
        graph.entryPointId = file.readLong(HnswGraphHeader.OFFSET_ENTRY_POINT);
        graph.maxLevel = file.readInt(HnswGraphHeader.OFFSET_MAX_LEVEL);
        graph.checkpoint.adopt(file.readLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED));

        // The header's count and the index slot it names are separate plain stores into the
        // mapping, neither forced nor ordered against the other, so a crash can leave the count
        // ahead of the slot. Trust only as far as the slots go.
        graph.nodeCount = HnswNodeRecords.highestWrittenNodeCount(
            file, persistedNodeCount, graph.maxNodes, graph.dataRegionOffset);
        if (graph.nodeCount != persistedNodeCount) {
            LOGGER.warn("HNSW header for {} claims {} nodes but only {} index entries were "
                + "written; recovering to the lower count.",
                graph.path, persistedNodeCount, graph.nodeCount);
        }
        graph.nodeIdGenerator.set(graph.nodeCount);
        graph.dataWriteOffset =
            HnswNodeRecords.recoverDataWriteOffset(file, graph.config, graph.nodeCount);
    }

    /**
     * Opens an existing HNSW graph from a file.
     *
     * @param path Path to the existing graph file
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if file cannot be opened or is invalid
     */
    static HnswGraph open(Path path, DistanceFunction distanceFunction) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        HnswCompactor.finishInterruptedCompaction(path);

        // Open the file to read size
        long fileSize = Files.size(path);

        // Create arena and mapped file (read-write for potential updates)
        Arena arena = Arena.ofShared();
        MappedFile mappedFile = MappedFile.open(path, fileSize);

        boolean opened = false;
        try {
            // Read and validate header
            long magic = mappedFile.readLong(HnswGraphHeader.OFFSET_MAGIC);
            if (magic != HnswGraphHeader.MAGIC) {
                throw new IOException("Invalid HNSW file (bad magic number): " + path);
            }

            int version = mappedFile.readInt(HnswGraphHeader.OFFSET_VERSION);
            if (!HnswGraphHeader.isSupportedVersion(version)) {
                throw new IOException("Unsupported HNSW version " + version + " in " + path);
            }

            // Before any layout field is believed: see HnswGraphHeader.readPersistedLayout.
            HnswGraphIntegrity.requireIntact(mappedFile, path, version);

            HnswGraphHeader.requireMatchingMetric(
                HnswGraphHeader.persistedMetric(
                    version, mappedFile.readInt(HnswGraphHeader.OFFSET_METRIC), path),
                distanceFunction, path);

            int dimension = mappedFile.readInt(HnswGraphHeader.OFFSET_DIMENSION);

            int maxConn = mappedFile.readInt(HnswGraphHeader.OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = mappedFile.readInt(HnswGraphHeader.OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = mappedFile.readInt(HnswGraphHeader.OFFSET_CONFIG_EF_CONSTRUCTION);
            int efSearch = mappedFile.readInt(HnswGraphHeader.OFFSET_CONFIG_EF_SEARCH);
            double mL = Double.longBitsToDouble(
                mappedFile.readLong(HnswGraphHeader.OFFSET_CONFIG_ML));

            HnswConfig config =
                new HnswConfig(maxConn, maxConn0, efConstruction, efSearch, mL, dimension);

            // maxNodes fixes the size of the node index and therefore the start of
            // the data region. Files written before it was persisted cannot have it
            // reconstructed, and guessing would read every node from a wrong offset.
            long maxNodes = mappedFile.readLong(HnswGraphHeader.OFFSET_MAX_NODES);
            if (maxNodes <= 0) {
                throw new IOException("HNSW file predates the maxNodes header field and " +
                    "cannot be opened safely (rebuild the index): " + path);
            }

            // Calculate data region offset
            long nodeIndexSize = maxNodes * HnswNodeRecords.NODE_INDEX_ENTRY_SIZE;
            long dataRegionOffset = HnswGraphHeader.HEADER_SIZE + nodeIndexSize;

            // Create graph using private constructor (doesn't reinitialize header)
            HnswGraph graph = new HnswGraph(path, config, distanceFunction, maxNodes,
                mappedFile, arena, dataRegionOffset);

            // Restore state from persisted data
            graph.formatVersion = version;
            restoreStateFromHeader(graph);
            graph.lifecycle.breakSeal();
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
     *
     * @param mappedFile The mapping to close, or null
     * @param arena The arena to close, or null
     */
    static void closeQuietly(MappedFile mappedFile, Arena arena) {
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
     * Opens the node-to-document mapping that belongs beside a graph file.
     *
     * <p>Falls back to an in-memory mapping if the file cannot be read. Losing the mapping
     * costs document attribution on this graph's results; refusing to open the graph would cost
     * the vectors themselves, which is the worse trade.</p>
     *
     * @param graphPath The graph file
     * @return A mapping, never null
     */
    static NodeDocumentMapping openDocumentMapping(Path graphPath) {
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
    static NodeDocumentMapping openDocumentMapping(Path graphPath, boolean loadExisting) {
        Path mappingPath = HnswSidecarPaths.documentMapping(graphPath);
        try {
            return NodeDocumentMapping.createOrLoad(mappingPath, loadExisting);
        } catch (IOException e) {
            LOGGER.warn("Could not open the node-to-document mapping at {}: {}. Results from "
                + "this graph will carry no document identity until it is rebuilt.",
                mappingPath, e.toString());
            return new NodeDocumentMapping();
        }
    }
}
