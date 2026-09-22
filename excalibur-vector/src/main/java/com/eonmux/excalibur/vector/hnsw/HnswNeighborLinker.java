package com.eonmux.excalibur.vector.hnsw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains the adjacency lists, including the back-edges that keep them bidirectional.
 *
 * <h2>Why it is its own class</h2>
 * <p>Adding one edge is rarely one write. A node at its connection limit has to give something
 * up, and whatever it drops must also drop its edge back, or the graph acquires a one-way link
 * that the traversal will follow in one direction and never in the other - the failure mode that
 * makes a node unreachable while it still looks connected from its own adjacency list. Keeping
 * the add, the pruning and the back-edge removal together is what makes that invariant a
 * property of a single collaborator rather than a convention spread across the insert path.</p>
 *
 * <h2>Locking</h2>
 * <p>Every method here mutates the mapped file and must be called with the graph's write lock
 * held; insertion is the only caller, and it holds that lock for the whole of an insert. The
 * reads these methods make re-enter the graph's read lock through
 * {@link HnswGraph#readNode(long)}, which a writer already owning the write lock may do.</p>
 */
final class HnswNeighborLinker {

    private final HnswGraph graph;

    /**
     * Creates the adjacency collaborator for one graph.
     *
     * @param graph The graph whose records and lock the edits run against
     */
    HnswNeighborLinker(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Replaces a node's neighbour list at one layer.
     *
     * @param nodeId The node whose list is being written
     * @param level The layer to write
     * @param neighborIds The neighbours to store, truncated to the layer's limit
     */
    void addConnections(long nodeId, int level, List<Long> neighborIds) {
        HnswGraph.NodeView node = graph.readNode(nodeId);
        if (node == null) {
            return;
        }

        HnswNodeRecords.writeNeighbors(
            graph.mappedFile, graph.config, node.dataOffset(), level, neighborIds);
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
    boolean addConnection(long nodeId, int level, long neighborId) {
        HnswGraph.NodeView node = graph.readNode(nodeId);
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
        int maxConn = HnswNodeRecords.maxConnectionsAt(graph.config, level);

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
     * @return Pruned list of neighbors (size &lt;= maxConn)
     */
    private List<Long> pruneConnectionsAndRemoveBackEdges(long nodeId, int level,
                                                          List<Long> currentNeighbors,
                                                          long newNeighborId, int maxConn) {
        HnswGraph.NodeView node = graph.readNode(nodeId);
        if (node == null) {
            return currentNeighbors;
        }

        float[] nodeVector = node.vector();

        // Build list of all candidates (current + new) with distances
        List<NeighborCandidate> candidates = new ArrayList<>(currentNeighbors.size() + 1);

        for (Long neighborId : currentNeighbors) {
            HnswGraph.NodeView neighbor = graph.readNode(neighborId);
            if (neighbor != null && !neighbor.isTombstoned()) {
                float dist = graph.computeDistance(nodeVector, neighbor.vector());
                candidates.add(new NeighborCandidate(neighborId, dist));
            }
        }

        // Add new neighbor
        HnswGraph.NodeView newNeighbor = graph.readNode(newNeighborId);
        if (newNeighbor != null && !newNeighbor.isTombstoned()) {
            float dist = graph.computeDistance(nodeVector, newNeighbor.vector());
            candidates.add(new NeighborCandidate(newNeighborId, dist));
        }

        // Sort by distance (ascending - closest first)
        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        // Determine which neighbors to keep
        Set<Long> keptNeighbors = new HashSet<>();
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
        HnswGraph.NodeView neighbor = graph.readNode(neighborId);
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
}
