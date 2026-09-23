package com.eonmux.excalibur.vector.hnsw;

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
            linkNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);

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
                connectedNodes = linkNode(nodeId, level, vector,
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
                    linkNode(nodeId, level, vector, previousEntryPoint, previousMaxLevel);
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
            connectedNodes = linkNode(nodeId, level, vector,
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
     * Connects a newly inserted node to the graph (Malkov &amp; Yashunin, Algorithm 1).
     *
     * <p>Descends greedily through the layers above the node's own, then at each layer it
     * occupies gathers an {@code efConstruction}-wide beam, chooses the node's neighbours from
     * it with {@link HnswNeighborLinker#selectNeighbors}, writes them as the node's adjacency
     * list and offers each chosen neighbour a link back. The node keeps every neighbour it
     * chose whether or not the back-link survives that neighbour's own shrink: edges are
     * directed, and a node's out-links are what make it a way through the graph.</p>
     *
     * <p>The new node selects up to its layer's full limit - {@code maxConnectionsLayer0} at
     * layer 0, as FAISS does, rather than the paper's {@code M} - because on high-dimensional
     * data the heuristic already keeps far fewer than the limit, and a wider first list is
     * measurably better connected there at no cost elsewhere. Tombstoned nodes never reach the
     * beam, so they are never chosen.</p>
     *
     * @param nodeId The node to connect
     * @param nodeLevel The level of the node
     * @param vector The node's vector
     * @param startEntryPoint The entry point to start searching from (may be different from current entry point)
     * @param startMaxLevel The max level to start searching from
     * @return The distinct nodes the new node now links to, across every layer
     */
    private List<Long> linkNode(long nodeId, int nodeLevel, float[] vector,
                                long startEntryPoint, int startMaxLevel) {
        List<Long> linked = new ArrayList<>();
        // Opened after writeNode has grown the file, so the mapping it reads is final for this insert
        HnswTraversal traversal = HnswTraversal.open(graph);
        long current = descentStart(traversal, startEntryPoint);
        if (current < 0 || traversal.recordOffset(nodeId) < 0) {
            return linked;
        }

        for (int level = startMaxLevel; level > nodeLevel; level--) {
            current = layerSearch.searchLayerSimple(traversal, vector, current, level);
        }

        for (int level = Math.min(nodeLevel, startMaxLevel); level >= 0; level--) {
            List<HnswSearchResult> beam = nearestFirst(layerSearch.searchLayerBeam(
                traversal, vector, current, level, graph.config.efConstruction(), null));
            // Only reachable when every older node is tombstoned and the walk began at this one
            beam.removeIf(result -> result.nodeId() == nodeId);

            List<Long> chosen = linker.selectNeighbors(traversal, beam,
                HnswNodeRecords.maxConnectionsAt(graph.config, level));
            linker.writeConnections(traversal, nodeId, level, chosen);
            for (long neighborId : chosen) {
                linker.addConnection(traversal, neighborId, level, nodeId);
                if (!linked.contains(neighborId)) {
                    linked.add(neighborId);
                }
            }

            // The beam's nearest member is where the descent continues on the layer below
            if (!beam.isEmpty()) {
                current = beam.get(0).nodeId();
            }
        }
        return linked;
    }

    /**
     * Resolves where an insertion's descent starts: the given entry point, or any active node
     * when that one has been tombstoned.
     *
     * @param traversal Read access opened under the write lock
     * @param entryPoint The entry point recorded before this insertion, or a negative id for none
     * @return The node to descend from, or -1 when the graph holds no active node
     */
    private long descentStart(HnswTraversal traversal, long entryPoint) {
        if (entryPoint < 0) {
            return -1;
        }
        return traversal.isActive(entryPoint)
            ? entryPoint
            : HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
    }

    /**
     * Drains a beam into a list ordered nearest first.
     *
     * @param beam A layer's beam, as the search returns it
     * @return The beam's members, closest first
     */
    private static List<HnswSearchResult> nearestFirst(PriorityQueue<HnswSearchResult> beam) {
        List<HnswSearchResult> sorted = new ArrayList<>(beam);
        sorted.sort(Comparator.comparingDouble(HnswSearchResult::distance));
        return sorted;
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
