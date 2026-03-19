package io.excalibur.vector.hnsw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies HNSW graph invariants for correctness and consistency.
 *
 * <p>This class provides methods to validate that an HNSW graph maintains
 * the fundamental invariants required for correct operation:</p>
 *
 * <h2>Core Invariants</h2>
 * <ul>
 *   <li><strong>Hierarchical Layers:</strong> Each node exists at all levels from 0 to its assigned level</li>
 *   <li><strong>Bidirectional Edges:</strong> If node A connects to B at level L, then B connects to A at level L</li>
 *   <li><strong>Entry Point at Highest Layer:</strong> The entry point node has the highest level in the graph</li>
 *   <li><strong>Connection Limits:</strong> Nodes respect maxConnections and maxConnectionsLayer0 limits</li>
 *   <li><strong>No Self-Loops:</strong> A node cannot have itself as a neighbor</li>
 *   <li><strong>Level Consistency:</strong> A node's neighbors at level L must have level >= L</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for verification operations. It acquires read locks
 * when accessing the graph. However, verification should typically be done in
 * single-threaded test scenarios.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class HnswInvariantVerifier {

    /**
     * Result of an invariant verification check.
     */
    public static final class VerificationResult {
        private final boolean valid;
        private final List<String> violations;
        private final int nodesChecked;
        private final int edgesChecked;

        private VerificationResult(boolean valid, List<String> violations,
                                   int nodesChecked, int edgesChecked) {
            this.valid = valid;
            this.violations = violations;
            this.nodesChecked = nodesChecked;
            this.edgesChecked = edgesChecked;
        }

        /**
         * Returns true if all invariants are satisfied.
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the list of violation descriptions.
         */
        public List<String> violations() {
            return violations;
        }

        /**
         * Returns the number of nodes checked.
         */
        public int nodesChecked() {
            return nodesChecked;
        }

        /**
         * Returns the number of edges checked.
         */
        public int edgesChecked() {
            return edgesChecked;
        }

        @Override
        public String toString() {
            if (valid) {
                return String.format("VerificationResult{valid=true, nodes=%d, edges=%d}",
                    nodesChecked, edgesChecked);
            }
            return String.format("VerificationResult{valid=false, violations=%d, nodes=%d, edges=%d}",
                violations.size(), nodesChecked, edgesChecked);
        }
    }

    /**
     * Verifies all HNSW invariants for the given graph.
     *
     * <p>This method checks:</p>
     * <ul>
     *   <li>Entry point is at the highest level</li>
     *   <li>All edges are bidirectional</li>
     *   <li>Connection limits are respected</li>
     *   <li>No self-loops exist</li>
     *   <li>Level consistency (neighbors have sufficient levels)</li>
     * </ul>
     *
     * @param graph The graph to verify
     * @return VerificationResult with details
     * @throws IllegalStateException if graph is closed
     */
    public static VerificationResult verifyAllInvariants(HnswGraph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        List<String> violations = new ArrayList<>();
        int nodesChecked = 0;
        int edgesChecked = 0;

        // Check 1: Entry point at highest layer
        verifyEntryPointAtHighestLayer(graph, violations);

        // Check 2: For each node, verify invariants
        long nodeCount = graph.nodeCount();
        for (long nodeId = 0; nodeId < nodeCount; nodeId++) {
            if (graph.isTombstoned(nodeId)) {
                continue; // Skip tombstoned nodes
            }

            nodesChecked++;
            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node == null) {
                violations.add(String.format("Node %d: exists in count but cannot be read", nodeId));
                continue;
            }

            // Check connection limits and bidirectionality at each level
            for (int level = 0; level <= node.level(); level++) {
                List<Long> neighbors = node.getNeighbors(level);
                edgesChecked += neighbors.size();

                // Check 2a: No self-loops
                if (neighbors.contains(nodeId)) {
                    violations.add(String.format(
                        "Node %d at level %d: self-loop detected", nodeId, level));
                }

                // Check 2b: Connection limits
                int maxConn = (level == 0) ?
                    graph.config().maxConnectionsLayer0() :
                    graph.config().maxConnections();
                if (neighbors.size() > maxConn) {
                    violations.add(String.format(
                        "Node %d at level %d: exceeds max connections (%d > %d)",
                        nodeId, level, neighbors.size(), maxConn));
                }

                // Check 2c: Bidirectional edges and level consistency
                for (Long neighborId : neighbors) {
                    if (graph.isTombstoned(neighborId)) {
                        continue; // Skip tombstoned neighbors
                    }

                    HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                    if (neighbor == null) {
                        violations.add(String.format(
                            "Node %d at level %d: references non-existent neighbor %d",
                            nodeId, level, neighborId));
                        continue;
                    }

                    // Level consistency: neighbor must exist at this level
                    if (neighbor.level() < level) {
                        violations.add(String.format(
                            "Node %d at level %d: neighbor %d has insufficient level %d",
                            nodeId, level, neighborId, neighbor.level()));
                    }

                    // Bidirectional edge: neighbor must have nodeId in its list
                    List<Long> neighborNeighbors = neighbor.getNeighbors(level);
                    if (!neighborNeighbors.contains(nodeId)) {
                        violations.add(String.format(
                            "Node %d at level %d: unidirectional edge to %d (missing back-edge)",
                            nodeId, level, neighborId));
                    }
                }
            }
        }

        // Check 3: Verify maxLevel matches actual maximum
        verifyMaxLevelConsistency(graph, violations);

        boolean valid = violations.isEmpty();
        return new VerificationResult(valid, violations, nodesChecked, edgesChecked);
    }

    /**
     * Verifies that the entry point is at the highest level in the graph.
     *
     * @param graph The graph to verify
     * @param violations List to add violation descriptions to
     */
    private static void verifyEntryPointAtHighestLayer(HnswGraph graph, List<String> violations) {
        long entryPointId = graph.entryPoint();
        int graphMaxLevel = graph.maxLevel();

        // Empty graph: entry point should be -1 and maxLevel should be -1
        if (graph.nodeCount() == 0 || entryPointId < 0) {
            if (entryPointId >= 0) {
                violations.add(String.format(
                    "Empty graph has entry point %d (should be -1)", entryPointId));
            }
            if (graphMaxLevel != -1 && graph.nodeCount() == 0) {
                violations.add(String.format(
                    "Empty graph has maxLevel %d (should be -1)", graphMaxLevel));
            }
            return;
        }

        // Entry point must exist and not be tombstoned
        if (graph.isTombstoned(entryPointId)) {
            violations.add(String.format(
                "Entry point %d is tombstoned (should find active entry point)",
                entryPointId));
            return;
        }

        HnswGraph.NodeView entryNode = graph.readNode(entryPointId);
        if (entryNode == null) {
            violations.add(String.format(
                "Entry point %d cannot be read", entryPointId));
            return;
        }

        // Entry point level must equal graph's maxLevel
        if (entryNode.level() != graphMaxLevel) {
            violations.add(String.format(
                "Entry point %d has level %d but graph maxLevel is %d",
                entryPointId, entryNode.level(), graphMaxLevel));
        }

        // Entry point must have the highest level among all nodes
        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            if (graph.isTombstoned(nodeId)) {
                continue;
            }

            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node != null && node.level() > graphMaxLevel) {
                violations.add(String.format(
                    "Node %d has level %d which exceeds graph maxLevel %d",
                    nodeId, node.level(), graphMaxLevel));
            }
        }
    }

    /**
     * Verifies that the graph's maxLevel matches the actual maximum level among nodes.
     *
     * @param graph The graph to verify
     * @param violations List to add violation descriptions to
     */
    private static void verifyMaxLevelConsistency(HnswGraph graph, List<String> violations) {
        int graphMaxLevel = graph.maxLevel();
        int actualMaxLevel = -1;

        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            if (graph.isTombstoned(nodeId)) {
                continue;
            }

            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node != null && node.level() > actualMaxLevel) {
                actualMaxLevel = node.level();
            }
        }

        if (graphMaxLevel != actualMaxLevel) {
            // Only report if there are active nodes
            if (actualMaxLevel >= 0 || graph.nodeCount() > 0) {
                violations.add(String.format(
                    "Graph maxLevel %d doesn't match actual maximum level %d among active nodes",
                    graphMaxLevel, actualMaxLevel));
            }
        }
    }

    /**
     * Verifies that the graph is connected (all active nodes reachable from entry point).
     *
     * <p>This is a weaker check than full connectivity - it verifies that the
     * base layer (level 0) is connected via the entry point's connected component.</p>
     *
     * @param graph The graph to verify
     * @return VerificationResult with connectivity details
     */
    public static VerificationResult verifyConnectivity(HnswGraph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        List<String> violations = new ArrayList<>();

        long entryPointId = graph.entryPoint();
        if (entryPointId < 0) {
            if (graph.nodeCount() > 0 && graph.activeNodeCount() > 0) {
                violations.add("Graph has active nodes but no valid entry point");
            }
            return new VerificationResult(violations.isEmpty(), violations, 0, 0);
        }

        // BFS from entry point at level 0
        Set<Long> visited = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        queue.add(entryPointId);
        visited.add(entryPointId);

        while (!queue.isEmpty()) {
            Long nodeId = queue.remove(0);
            if (graph.isTombstoned(nodeId)) {
                continue;
            }

            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node == null) {
                continue;
            }

            List<Long> neighbors = node.getActiveNeighbors(0);
            for (Long neighborId : neighbors) {
                if (!visited.contains(neighborId)) {
                    visited.add(neighborId);
                    queue.add(neighborId);
                }
            }
        }

        // Count active nodes
        int activeNodes = 0;
        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            if (!graph.isTombstoned(nodeId)) {
                activeNodes++;
            }
        }

        // Check if all active nodes were visited
        int reachable = visited.size();
        if (reachable < activeNodes) {
            violations.add(String.format(
                "Graph is not fully connected: %d active nodes, but only %d reachable from entry point",
                activeNodes, reachable));
        }

        return new VerificationResult(violations.isEmpty(), violations, activeNodes, reachable);
    }

    /**
     * Verifies that bidirectional edges are maintained for a specific edge.
     *
     * @param graph The graph to verify
     * @param nodeIdA First node ID
     * @param nodeIdB Second node ID
     * @param level The level to check
     * @return true if edge is bidirectional, false otherwise
     */
    public static boolean isEdgeBidirectional(HnswGraph graph, long nodeIdA, long nodeIdB, int level) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        HnswGraph.NodeView nodeA = graph.readNode(nodeIdA);
        HnswGraph.NodeView nodeB = graph.readNode(nodeIdB);

        if (nodeA == null || nodeB == null) {
            return false;
        }

        if (nodeA.level() < level || nodeB.level() < level) {
            return false; // One or both nodes don't exist at this level
        }

        List<Long> neighborsA = nodeA.getNeighbors(level);
        List<Long> neighborsB = nodeB.getNeighbors(level);

        return neighborsA.contains(nodeIdB) && neighborsB.contains(nodeIdA);
    }

    /**
     * Counts the number of bidirectional edges at a specific level.
     *
     * @param graph The graph to analyze
     * @param level The level to count edges at
     * @return The number of bidirectional edges
     */
    public static long countBidirectionalEdges(HnswGraph graph, int level) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        Set<String> countedEdges = new HashSet<>();
        long edgeCount = 0;

        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            if (graph.isTombstoned(nodeId)) {
                continue;
            }

            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node == null || node.level() < level) {
                continue;
            }

            List<Long> neighbors = node.getNeighbors(level);
            for (Long neighborId : neighbors) {
                // Create canonical edge key (smaller ID first)
                String edgeKey = nodeId < neighborId ?
                    nodeId + "-" + neighborId : neighborId + "-" + nodeId;

                if (!countedEdges.contains(edgeKey)) {
                    if (isEdgeBidirectional(graph, nodeId, neighborId, level)) {
                        edgeCount++;
                    }
                    countedEdges.add(edgeKey);
                }
            }
        }

        return edgeCount;
    }

    /**
     * Returns a summary of the graph structure for debugging.
     *
     * @param graph The graph to summarize
     * @return A human-readable summary string
     */
    public static String summarizeGraph(HnswGraph graph) {
        if (graph == null) {
            return "Graph is null";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("HNSW Graph Summary:\n");
        sb.append(String.format("  Total nodes: %d\n", graph.nodeCount()));
        sb.append(String.format("  Active nodes: %d\n", graph.activeNodeCount()));
        sb.append(String.format("  Tombstoned nodes: %d\n", graph.tombstoneCount()));
        sb.append(String.format("  Entry point: %d\n", graph.entryPoint()));
        sb.append(String.format("  Max level: %d\n", graph.maxLevel()));

        // Count nodes per level
        int[] levelCounts = new int[graph.maxLevel() + 1];
        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            if (graph.isTombstoned(nodeId)) {
                continue;
            }
            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node != null && node.level() >= 0 && node.level() < levelCounts.length) {
                levelCounts[node.level()]++;
            }
        }

        sb.append("  Nodes per level:\n");
        for (int level = levelCounts.length - 1; level >= 0; level--) {
            sb.append(String.format("    Level %d: %d nodes\n", level, levelCounts[level]));
        }

        // Count edges at level 0
        long edgesAtLevel0 = countBidirectionalEdges(graph, 0);
        sb.append(String.format("  Bidirectional edges at level 0: %d\n", edgesAtLevel0));

        return sb.toString();
    }
}
