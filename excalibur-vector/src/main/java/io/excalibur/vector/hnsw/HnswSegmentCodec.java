package io.excalibur.vector.hnsw;

import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.core.memory.Segment;
import io.excalibur.vector.distance.DistanceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Moves nodes and headers between a graph and a {@link Segment}, for export, snapshotting and
 * validation.
 *
 * <h2>Why it is its own class</h2>
 * <p>This is a second writer of the same record format, aimed at a buffer the graph does not
 * own. It exists so that a node or a header can be inspected, copied or checked without a graph
 * being put into service over it. Keeping it beside the graph rather than inside it makes the
 * dependency one-way - this class reads the layout from {@link HnswNodeRecords} and
 * {@link HnswGraphHeader}, and the graph's own hot paths never reach for it.</p>
 *
 * <h2>What a deserialized node keeps, and what it does not</h2>
 * <p>Only the vector survives a round trip. The serialized adjacency lists are read past and
 * discarded, and the node is linked in by the ordinary insertion algorithm under a new id: the
 * ids in the source buffer belong to whatever graph produced it, and adopting them would attach
 * edges to whichever nodes happen to hold those ids here.</p>
 */
final class HnswSegmentCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswSegmentCodec.class);

    private final HnswGraph graph;

    /**
     * Creates the segment codec for one graph.
     *
     * @param graph The graph being read out of or written into
     */
    HnswSegmentCodec(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Creates a new graph after pre-validating that the path can be mapped as a segment.
     *
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @return A new HNSW graph
     * @throws IOException if the graph cannot be created
     */
    static HnswGraph create(Path path, HnswConfig config, DistanceFunction distanceFunction,
                            long maxNodes) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(config, "Config cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        long totalSize = HnswGraphHeader.HEADER_SIZE + HnswGraphOpener.initialFileSize(config, maxNodes);

        // Create the file using Segment abstraction (validates Panama availability)
        try (Segment segment = MemorySegmentFactory.mapFile(path, totalSize)) {
            // Segment is created and immediately closed; actual mapping done by MappedFile
            LOGGER.debug("Pre-validated Segment creation at {} with size {}", path, totalSize);
        }

        // Create the actual graph using the standard constructor
        return new HnswGraph(path, config, distanceFunction, maxNodes);
    }

    /**
     * Opens an existing graph named by a file-backed segment.
     *
     * @param segment The Segment containing the graph data (must be file-backed)
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if the segment cannot be opened or is invalid
     * @throws UnsupportedOperationException if the segment is not file-backed
     */
    static HnswGraph open(Segment segment, DistanceFunction distanceFunction) throws IOException {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        Path path = segment.path();
        if (path == null) {
            throw new UnsupportedOperationException(
                "Segment must be file-backed to open as HnswGraph. " +
                "Use open(Path, DistanceFunction) for in-memory segments.");
        }

        // Delegate to the Path-based open method
        return HnswGraph.open(path, distanceFunction);
    }

    /**
     * Serializes a node's data to a Segment.
     *
     * @param nodeId The node ID to serialize
     * @param segment The target Segment to write to
     * @param offset The offset within the Segment to start writing
     * @return The number of bytes written
     */
    long serializeNode(long nodeId, Segment segment, long offset) {
        graph.validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        HnswGraph.NodeView node = graph.readNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }

        graph.lock.readLock().lock();
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
                int maxConnAtLevel = HnswNodeRecords.maxConnectionsAt(graph.config, level);

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
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Deserializes a node from a Segment and inserts it into the graph.
     *
     * <p>The write lock is taken to read the buffer past its adjacency lists and is released
     * before the insert, because {@link HnswGraph#insert(float[])} takes it again for itself
     * and is the method whose contract - including a subclass's override of it - decides how
     * the node is linked in.</p>
     *
     * @param segment The source Segment to read from
     * @param offset The offset within the Segment to start reading
     * @return The new node ID assigned to the deserialized node
     */
    long deserializeNode(Segment segment, long offset) {
        graph.validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        graph.lock.writeLock().lock();
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

            if (vectorLen != graph.config.dimension()) {
                throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + graph.config.dimension() +
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

                readOffset += (long) HnswNodeRecords.maxConnectionsAt(graph.config, l) * 4;
            }

            // Release lock and use standard insertion
            graph.lock.writeLock().unlock();
            return graph.insert(vector);
        } finally {
            // Ensure lock is released if not already
            if (graph.lock.writeLock().isHeldByCurrentThread()) {
                graph.lock.writeLock().unlock();
            }
        }
    }

    /**
     * Exports the graph header to a Segment.
     *
     * @param segment The target Segment (must have at least the header's size)
     * @return The number of bytes written
     */
    long exportHeader(Segment segment) {
        graph.validateNotClosed();
        Objects.requireNonNull(segment, "Segment cannot be null");

        if (segment.byteSize() < HnswGraphHeader.HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small for header: " + segment.byteSize() + " < "
                    + HnswGraphHeader.HEADER_SIZE);
        }

        graph.lock.readLock().lock();
        try {
            HnswConfig config = graph.config;

            // Write header fields
            segment.setLong(HnswGraphHeader.OFFSET_MAGIC, HnswGraphHeader.MAGIC);
            segment.setInt(HnswGraphHeader.OFFSET_VERSION, HnswGraphHeader.VERSION);
            segment.setInt(HnswGraphHeader.OFFSET_DIMENSION, config.dimension());
            segment.setLong(HnswGraphHeader.OFFSET_NODE_COUNT, graph.nodeCount);
            segment.setLong(HnswGraphHeader.OFFSET_ENTRY_POINT, graph.entryPointId);
            segment.setInt(HnswGraphHeader.OFFSET_MAX_LEVEL, graph.maxLevel);
            segment.setLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED,
                graph.checkpoint.get());
            segment.setInt(HnswGraphHeader.OFFSET_CONFIG_MAX_CONN, config.maxConnections());
            segment.setInt(HnswGraphHeader.OFFSET_CONFIG_MAX_CONN_0, config.maxConnectionsLayer0());
            segment.setInt(HnswGraphHeader.OFFSET_CONFIG_EF_CONSTRUCTION, config.efConstruction());
            segment.setInt(HnswGraphHeader.OFFSET_CONFIG_EF_SEARCH, config.efSearch());
            segment.setLong(HnswGraphHeader.OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));
            segment.setLong(HnswGraphHeader.OFFSET_MAX_NODES, graph.maxNodes);
            segment.setInt(HnswGraphHeader.OFFSET_METRIC,
                PersistedMetric.codeFor(graph.distanceFunction.name()));

            // An exported header describes a graph whose payload this call does not write, so
            // it is published unsealed rather than carrying a checksum that covers nothing.
            segment.setInt(HnswGraphIntegrity.OFFSET_STATE, HnswGraphIntegrity.STATE_OPEN);

            return HnswGraphHeader.HEADER_SIZE;
        } finally {
            graph.lock.readLock().unlock();
        }
    }
}
