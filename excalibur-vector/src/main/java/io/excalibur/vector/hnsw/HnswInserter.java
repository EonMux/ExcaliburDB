package io.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Appends nodes to the graph and links them into it.
 *
 * <h2>Why it is its own class</h2>
 * <p>An insert is two separate things wearing one name: an append to the node index and the
 * data region, which is pure bookkeeping over the file's layout, and a linking pass, which is a
 * search plus a round of edge maintenance. Both are repeated, with small deliberate differences,
 * across five public entry points - a plain insert, one that reports what it did, two batch
 * forms and the batch's internal step. Holding them side by side is the only way those
 * differences stay visible instead of drifting.</p>
 *
 * <h2>The differences that are deliberate</h2>
 * <p>{@link #insertBatch} checks capacity <em>before</em> taking a node id, while the
 * single-vector paths take the id first and then check; a rejected insert therefore burns an id
 * on one path and not the other. {@link #insert} throws when the graph is full, while the
 * result-returning paths answer {@link IncrementalInsertResult#FAILED}. These are the observable
 * contracts of five published methods and are preserved exactly as they were.</p>
 *
 * <h2>Locking</h2>
 * <p>Each public entry point takes the graph's write lock for the whole of its work, exactly
 * where the method it was extracted from took it, and the batch forms hold it across the entire
 * batch. The state these methods advance - the id generator, the node count, the entry point,
 * the highest layer and the data-region append position - is only ever advanced under that
 * lock.</p>
 */
final class HnswInserter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswInserter.class);

    private final HnswGraph graph;
    private final HnswLayerSearch layerSearch;
    private final HnswNeighborLinker linker;

    /**
     * Creates the insertion collaborator for one graph.
     *
     * @param graph The graph being inserted into
     * @param layerSearch The traversal used to find a new node's neighbours
     * @param linker The adjacency maintenance the linking pass writes through
     */
    HnswInserter(HnswGraph graph, HnswLayerSearch layerSearch, HnswNeighborLinker linker) {
        this.graph = graph;
        this.layerSearch = layerSearch;
        this.linker = linker;
    }

    /**
     * Inserts a vector, throwing if the graph is full.
     *
     * @param vector The vector to insert
     * @return The ID of the inserted node
     */
    long insert(float[] vector) {
        graph.validateNotClosed();
        graph.validateVector(vector);

        graph.lock.writeLock().lock();
        try {
            // Generate node ID
            long nodeId = graph.nodeIdGenerator.getAndIncrement();

            // Check capacity
            if (graph.nodeCount >= graph.maxNodes) {
                throw new IllegalStateException("Graph is full: " + graph.nodeCount + "/" + graph.maxNodes);
            }

            // Generate random level for this node
            int level = generateRandomLevel();

            // Write node to disk
            long dataOffset = writeNode(nodeId, level, vector);
            HnswNodeRecords.writeIndexEntry(graph.mappedFile, nodeId, dataOffset, level, false);

            // Track if this node should become the new entry point
            boolean becomesEntryPoint = (graph.entryPointId < 0 || level > graph.maxLevel);
            long previousEntryPoint = graph.entryPointId;
            int previousMaxLevel = graph.maxLevel;

            // Update entry point if this is the first node or highest level
            if (becomesEntryPoint) {
                graph.entryPointId = nodeId;
                graph.maxLevel = level;
                HnswGraphHeader.writeEntryPoint(
                    graph.mappedFile, graph.entryPointId, graph.maxLevel);
            }

            graph.nodeCount++;
            graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_NODE_COUNT, graph.nodeCount);

            // If this is the first node, no connections to make
            if (graph.nodeCount == 1) {
                return nodeId;
            }

            // Connect node to existing graph
            // Even if this node becomes the new entry point, we need to connect it
            // to the previous graph structure
            connectNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);

            LOGGER.debug("Inserted node {} at level {} (total nodes: {})",
                nodeId, level, graph.nodeCount);

            return nodeId;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Inserts a vector and reports what the insertion did.
     *
     * @param vector The vector to insert
     * @param writeLogOffset The write log offset this insertion corresponds to, or -1
     * @return The insertion's metadata, or {@link IncrementalInsertResult#FAILED} if full
     */
    IncrementalInsertResult insertWithResult(float[] vector, long writeLogOffset) {
        graph.validateNotClosed();
        graph.validateVector(vector);

        graph.lock.writeLock().lock();
        try {
            // Generate node ID
            long nodeId = graph.nodeIdGenerator.getAndIncrement();

            // Check capacity
            if (graph.nodeCount >= graph.maxNodes) {
                return IncrementalInsertResult.FAILED;
            }

            // Generate random level for this node
            int level = generateRandomLevel();

            // Write node to disk
            long dataOffset = writeNode(nodeId, level, vector);
            HnswNodeRecords.writeIndexEntry(graph.mappedFile, nodeId, dataOffset, level, false);

            // Track if this node should become the new entry point
            boolean becomesEntryPoint = (graph.entryPointId < 0 || level > graph.maxLevel);
            long previousEntryPoint = graph.entryPointId;
            int previousMaxLevel = graph.maxLevel;

            // Update entry point if this is the first node or highest level
            if (becomesEntryPoint) {
                graph.entryPointId = nodeId;
                graph.maxLevel = level;
                HnswGraphHeader.writeEntryPoint(
                    graph.mappedFile, graph.entryPointId, graph.maxLevel);
            }

            graph.nodeCount++;
            graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_NODE_COUNT, graph.nodeCount);

            // Track connections made
            List<Long> connectedNodes = new ArrayList<>();
            int connectionsMade = 0;

            // If this is the first node, no connections to make
            if (graph.nodeCount > 1) {
                // Connect node to existing graph
                connectedNodes = connectNodeAndTrack(nodeId, level, vector,
                    previousEntryPoint, previousMaxLevel);
                connectionsMade = connectedNodes.size();
            }

            LOGGER.debug("Incrementally inserted node {} at level {} (connections: {}, total nodes: {})",
                nodeId, level, connectionsMade, graph.nodeCount);

            return new IncrementalInsertResult(nodeId, level, dataOffset,
                connectionsMade, writeLogOffset, connectedNodes);
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Inserts a list of vectors under a single write lock.
     *
     * @param vectors The vectors to insert
     * @return The node IDs assigned, in order
     */
    List<Long> insertBatch(List<float[]> vectors) {
        if (vectors == null) {
            throw new IllegalArgumentException("Vectors list cannot be null");
        }

        graph.validateNotClosed();

        List<Long> nodeIds = new ArrayList<>(vectors.size());

        graph.lock.writeLock().lock();
        try {
            for (float[] vector : vectors) {
                graph.validateVector(vector);

                // Check capacity
                if (graph.nodeCount >= graph.maxNodes) {
                    throw new IllegalStateException("Graph is full: " + graph.nodeCount + "/" + graph.maxNodes);
                }

                long nodeId = graph.nodeIdGenerator.getAndIncrement();
                int level = generateRandomLevel();
                long dataOffset = writeNode(nodeId, level, vector);
                HnswNodeRecords.writeIndexEntry(graph.mappedFile, nodeId, dataOffset, level, false);

                boolean becomesEntryPoint = (graph.entryPointId < 0 || level > graph.maxLevel);
                long previousEntryPoint = graph.entryPointId;
                int previousMaxLevel = graph.maxLevel;

                if (becomesEntryPoint) {
                    graph.entryPointId = nodeId;
                    graph.maxLevel = level;
                    HnswGraphHeader.writeEntryPoint(
                        graph.mappedFile, graph.entryPointId, graph.maxLevel);
                }

                graph.nodeCount++;
                graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_NODE_COUNT, graph.nodeCount);

                if (graph.nodeCount > 1) {
                    connectNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);
                }

                nodeIds.add(nodeId);
            }

            LOGGER.debug("Batch inserted {} vectors (total nodes: {})",
                vectors.size(), graph.nodeCount);

            return nodeIds;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Inserts a list of vectors and reports what each insertion did.
     *
     * @param vectors The vectors to insert
     * @param writeLogOffsets Corresponding write log offsets, or null
     * @return One result per vector, in order
     */
    List<IncrementalInsertResult> insertBatchWithResults(
            List<float[]> vectors, List<Long> writeLogOffsets) {
        if (vectors == null) {
            throw new IllegalArgumentException("Vectors list cannot be null");
        }
        if (writeLogOffsets != null && writeLogOffsets.size() != vectors.size()) {
            throw new IllegalArgumentException(
                "Write log offsets size must match vectors size");
        }

        graph.validateNotClosed();

        List<IncrementalInsertResult> results = new ArrayList<>(vectors.size());

        graph.lock.writeLock().lock();
        try {
            for (int i = 0; i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                graph.validateVector(vector);

                if (graph.nodeCount >= graph.maxNodes) {
                    // Return partial results with FAILED for remaining
                    results.add(IncrementalInsertResult.FAILED);
                    continue;
                }

                long writeLogOffset = writeLogOffsets != null ? writeLogOffsets.get(i) : -1;
                results.add(insertWithResultInternal(vector, writeLogOffset));
            }

            LOGGER.debug("Batch inserted {} vectors with results (total nodes: {})",
                vectors.size(), graph.nodeCount);

            return results;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Internal method for incremental insertion within a write lock.
     */
    private IncrementalInsertResult insertWithResultInternal(float[] vector, long writeLogOffset) {
        long nodeId = graph.nodeIdGenerator.getAndIncrement();

        if (graph.nodeCount >= graph.maxNodes) {
            return IncrementalInsertResult.FAILED;
        }

        int level = generateRandomLevel();
        long dataOffset = writeNode(nodeId, level, vector);
        HnswNodeRecords.writeIndexEntry(graph.mappedFile, nodeId, dataOffset, level, false);

        boolean becomesEntryPoint = (graph.entryPointId < 0 || level > graph.maxLevel);
        long previousEntryPoint = graph.entryPointId;
        int previousMaxLevel = graph.maxLevel;

        if (becomesEntryPoint) {
            graph.entryPointId = nodeId;
            graph.maxLevel = level;
            HnswGraphHeader.writeEntryPoint(
                graph.mappedFile, graph.entryPointId, graph.maxLevel);
        }

        graph.nodeCount++;
        graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_NODE_COUNT, graph.nodeCount);

        List<Long> connectedNodes = new ArrayList<>();
        int connectionsMade = 0;

        if (graph.nodeCount > 1) {
            connectedNodes = connectNodeAndTrack(nodeId, level, vector,
                previousEntryPoint, previousMaxLevel);
            connectionsMade = connectedNodes.size();
        }

        return new IncrementalInsertResult(nodeId, level, dataOffset,
            connectionsMade, writeLogOffset, connectedNodes);
    }

    /**
     * Appends a node record to the data region and advances the append position.
     *
     * @param nodeId The node's id
     * @param level The node's highest layer
     * @param vector The node's vector
     * @return The offset within the data region where the node was written
     */
    private long writeNode(long nodeId, int level, float[] vector) {
        long offset = graph.dataWriteOffset;
        long absoluteOffset = graph.dataRegionOffset + offset;

        HnswConfig config = graph.config;
        int nodeSize = HnswNodeRecords.recordSize(config, level, vector.length * Float.BYTES);

        // Ensure we have space
        HnswNodeRecords.ensureCapacity(graph.mappedFile, absoluteOffset + nodeSize);

        HnswNodeRecords.writeRecord(graph.mappedFile, config, absoluteOffset, nodeId, level, vector);

        // Advance write offset
        graph.dataWriteOffset += nodeSize;

        return offset;
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
        HnswGraph.NodeView newNode = graph.readNode(nodeId);
        if (newNode == null) {
            return;
        }

        // Start from the provided entry point (could be the previous entry point
        // if this node is becoming the new entry point)
        if (startEntryPoint < 0) {
            return; // No nodes to connect to
        }

        HnswGraph.NodeView current = graph.readNode(startEntryPoint);
        // Skip tombstoned entry points
        if (current == null || current.isTombstoned()) {
            current = graph.readNode(HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount));
            if (current == null) {
                return;
            }
        }

        // Search for entry points at each level
        for (int level = startMaxLevel; level > nodeLevel; level--) {
            HnswGraph.NodeView closest = layerSearch.searchLayerSimple(vector, current, level);
            if (closest != null) {
                current = closest;
            }
        }

        // Connect at each level from nodeLevel down to 0
        for (int level = Math.min(nodeLevel, startMaxLevel); level >= 0; level--) {
            // Find efConstruction nearest neighbors at this level
            // searchLayerBeam automatically filters tombstoned nodes
            PriorityQueue<HnswSearchResult> neighbors = layerSearch.searchLayerBeam(
                vector, current, level, graph.config.efConstruction());

            // Select neighbors (simple heuristic: take closest M)
            List<Long> selectedNeighbors = selectNeighbors(neighbors,
                HnswNodeRecords.maxConnectionsAt(graph.config, level));

            // Filter out any tombstoned neighbors (defensive check)
            selectedNeighbors.removeIf(nid -> {
                HnswGraph.NodeView n = graph.readNode(nid);
                return n == null || n.isTombstoned();
            });

            // Connect neighbors back to new node first (to check which accept us)
            // Only keep neighbors that successfully add us to their list
            List<Long> successfulNeighbors = new ArrayList<>();
            for (long neighborId : selectedNeighbors) {
                boolean added = linker.addConnection(neighborId, level, nodeId);
                if (added) {
                    successfulNeighbors.add(neighborId);
                }
            }

            // Now connect new node only to neighbors that accepted us (bidirectional)
            linker.addConnections(nodeId, level, successfulNeighbors);

            // Update current for next level - use closest neighbor
            current = closestActive(neighbors, current);
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

        HnswGraph.NodeView newNode = graph.readNode(nodeId);
        if (newNode == null) {
            return allConnectedNodes;
        }

        if (startEntryPoint < 0) {
            return allConnectedNodes;
        }

        HnswGraph.NodeView current = graph.readNode(startEntryPoint);
        if (current == null || current.isTombstoned()) {
            current = graph.readNode(HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount));
            if (current == null) {
                return allConnectedNodes;
            }
        }

        // Search for entry points at each level
        for (int level = startMaxLevel; level > nodeLevel; level--) {
            HnswGraph.NodeView closest = layerSearch.searchLayerSimple(vector, current, level);
            if (closest != null) {
                current = closest;
            }
        }

        // Connect at each level from nodeLevel down to 0
        for (int level = Math.min(nodeLevel, startMaxLevel); level >= 0; level--) {
            PriorityQueue<HnswSearchResult> neighbors = layerSearch.searchLayerBeam(
                vector, current, level, graph.config.efConstruction());

            List<Long> selectedNeighbors = selectNeighbors(neighbors,
                HnswNodeRecords.maxConnectionsAt(graph.config, level));

            selectedNeighbors.removeIf(nid -> {
                HnswGraph.NodeView n = graph.readNode(nid);
                return n == null || n.isTombstoned();
            });

            // Connect neighbors back to new node first (to check which accept us)
            // Only keep neighbors that successfully add us to their list (bidirectional)
            List<Long> successfulNeighbors = new ArrayList<>();
            for (long neighborId : selectedNeighbors) {
                boolean added = linker.addConnection(neighborId, level, nodeId);
                if (added) {
                    successfulNeighbors.add(neighborId);
                    if (!allConnectedNodes.contains(neighborId)) {
                        allConnectedNodes.add(neighborId);
                    }
                }
            }

            // Now connect new node only to neighbors that accepted us
            linker.addConnections(nodeId, level, successfulNeighbors);

            // Update current for next level
            current = closestActive(neighbors, current);
        }

        return allConnectedNodes;
    }

    /**
     * Picks the descent point for the next layer down: the nearest of this layer's beam that is
     * still active.
     *
     * <p>The beam is a heap ordered furthest-first, so the nearest entry is found by scanning it
     * rather than by polling. A beam that is empty, or whose every member has been tombstoned
     * since it was gathered, leaves the descent where it was - the alternative is to carry a
     * retired node down into the next layer and search from a vector no query may return.</p>
     *
     * @param neighbors This layer's beam
     * @param current Where the descent currently stands
     * @return The node to continue from
     */
    private HnswGraph.NodeView closestActive(PriorityQueue<HnswSearchResult> neighbors,
                                             HnswGraph.NodeView current) {
        if (neighbors.isEmpty()) {
            return current;
        }
        HnswSearchResult closest = null;
        for (HnswSearchResult r : neighbors) {
            HnswGraph.NodeView n = graph.readNode(r.nodeId());
            if (n != null && !n.isTombstoned()) {
                if (closest == null || r.distance() < closest.distance()) {
                    closest = r;
                }
            }
        }
        if (closest != null) {
            return graph.readNode(closest.nodeId());
        }
        return current;
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
        double r = -Math.log(1 - random.nextDouble()) * graph.config.mL();
        return (int) Math.min(r, HnswConfig.MAX_LEVELS - 1);
    }
}
