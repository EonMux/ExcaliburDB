package io.excalibur.vector.hnsw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The full-precision half of the graph's query path: greedy descent, the layer-0 beam, and the
 * two ways a candidate-restricted query can be answered.
 *
 * <h2>Why it is its own class</h2>
 * <p>Descent and the beam are also what insertion uses to find the neighbours a new node should
 * link to, so they are not a detail of {@code search} - they are the traversal the rest of the
 * graph is built on. Naming them separately keeps the single implementation that queries and
 * insertions share visible as one thing, rather than as a pair of private helpers that happen
 * to be called from both.</p>
 *
 * <h2>Distances</h2>
 * <p>Everything here orders by {@link HnswGraph#computeDistance}, where smaller is closer. The
 * quantised counterpart lives on {@link HnswQuantizedSearch} and deliberately does not share
 * these methods: its values come from a lookup table on a different scale, and a queue holding
 * both would rank nodes by which encoding they happen to have.</p>
 *
 * <h2>Locking</h2>
 * <p>This class holds no state of its own and takes no lock that the graph did not already
 * define. The public entry points acquire the graph's read lock exactly where the methods they
 * were extracted from acquired it; the layer walks below assume it is already held, and re-enter
 * it through {@link HnswGraph#readNode(long)}. Tombstone checks are repeated on the way out
 * because a node admitted to the beam can be retired before the results are extracted, and the
 * read lock does not exclude that - {@link HnswGraph#delete(long)} takes the write lock, but a
 * result is only as fresh as the moment it was measured.</p>
 */
final class HnswLayerSearch {

    private final HnswGraph graph;

    /**
     * Creates the search collaborator for one graph.
     *
     * @param graph The graph whose state and lock the traversal runs against
     */
    HnswLayerSearch(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Answers an unrestricted k-nearest-neighbour query.
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @return The k nearest active nodes, closest first
     */
    List<HnswSearchResult> search(float[] query, int k) {
        graph.validateNotClosed();
        graph.validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        graph.lock.readLock().lock();
        try {
            if (graph.nodeCount == 0 || graph.entryPointId < 0) {
                return new ArrayList<>();
            }

            // Start from entry point
            long currentNodeId = graph.entryPointId;
            HnswGraph.NodeView current = graph.readNode(currentNodeId);

            if (current == null || current.isTombstoned()) {
                // Entry point is tombstoned, need to find a new starting point
                currentNodeId = HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
                if (currentNodeId < 0) {
                    return new ArrayList<>();
                }
                current = graph.readNode(currentNodeId);
            }

            final float[] queryVec = query;
            // The layer-0 beam is trimmed to ef entries, so a beam narrower than k
            // can never yield k results.
            final int efSearch = Math.max(graph.config.efSearch(), k);

            // Search through layers from top to layer 1
            for (int level = graph.maxLevel; level > 0; level--) {
                // Greedy search: find the closest node at this level
                HnswGraph.NodeView closest = searchLayerSimple(queryVec, current, level);
                if (closest != null) {
                    current = closest;
                }
            }

            // At layer 0, perform beam search
            PriorityQueue<HnswSearchResult> results = searchLayerBeam(
                queryVec, current, 0, efSearch);

            // Extract ALL results from the heap and find top-k closest
            // Note: results is a max-heap, so polling gives furthest first
            // We need to extract all, then sort to get the k closest
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                // Verify node is not tombstoned
                HnswGraph.NodeView node = graph.readNode(result.nodeId());
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
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Answers a k-nearest-neighbour query restricted to a named set of nodes.
     *
     * <p>Two ways to answer, chosen by which does less work. Measuring reads {@code |C|}
     * nodes. A walk keeps exploring until it holds {@code ef} admitted results and admits a
     * {@code |C| / nodeCount} fraction of what it visits, so it reads on the order of
     * {@code ef * nodeCount / |C|}. Measuring is the cheaper of the two while
     * {@code |C|<sup>2</sup> <= ef * nodeCount}, and it is also the exact one, so that is
     * where the line sits.</p>
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @param candidateIds The nodes that may be returned, or null for no restriction
     * @return The k nearest admissible nodes, closest first
     */
    List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
        graph.validateNotClosed();
        graph.validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        if (candidateIds == null) {
            return graph.search(query, k);
        }
        if (candidateIds.isEmpty()) {
            return new ArrayList<>();
        }

        graph.lock.readLock().lock();
        try {
            if (graph.nodeCount == 0 || graph.entryPointId < 0) {
                return new ArrayList<>();
            }

            // As in search(): the beam is trimmed to ef entries, so one narrower than k can never
            // yield k results.
            final int ef = Math.max(graph.config.efSearch(), k * 2);

            long named = candidateIds.size();
            if (named * named <= (long) ef * graph.nodeCount) {
                return measureCandidates(query, k, candidateIds);
            }
            return walkAdmitting(query, k, ef, candidateIds);
        } finally {
            graph.lock.readLock().unlock();
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
            HnswGraph.NodeView node = graph.readNode(nodeId);
            if (node == null || node.isTombstoned()) {
                continue;
            }
            measured.add(graph.createSearchResult(nodeId, graph.computeDistance(query, node.vector())));
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

        HnswGraph.NodeView current = graph.readNode(graph.entryPointId);
        if (current == null || current.isTombstoned()) {
            long active = HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
            if (active < 0) {
                return new ArrayList<>();
            }
            current = graph.readNode(active);
            if (current == null) {
                return new ArrayList<>();
            }
        }

        for (int level = graph.maxLevel; level > 0; level--) {
            HnswGraph.NodeView closest = searchLayerSimple(query, current, level);
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
            HnswGraph.NodeView node = graph.readNode(result.nodeId());
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
     *
     * @param query The vector being navigated towards
     * @param entry Where the descent starts at this layer
     * @param level The layer to walk
     * @return The closest node reachable by strictly improving steps
     */
    HnswGraph.NodeView searchLayerSimple(float[] query, HnswGraph.NodeView entry, int level) {
        HnswGraph.NodeView current = entry;
        float currentDist = graph.computeDistance(query, current.vector());
        boolean improved = true;

        while (improved) {
            improved = false;
            // Use getActiveNeighbors to automatically filter tombstoned nodes
            List<Long> neighbors = current.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                // Double-check in case neighbor became tombstoned after getActiveNeighbors
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = graph.computeDistance(query, neighbor.vector());
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
     *
     * @param query The vector being searched for
     * @param entry Where the beam starts
     * @param level The layer to walk
     * @param ef The beam width
     * @return A max-heap of the beam's contents, furthest first
     */
    PriorityQueue<HnswSearchResult> searchLayerBeam(
            float[] query, HnswGraph.NodeView entry, int level, int ef) {
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
     * @param query The vector being searched for
     * @param entry Where the beam starts
     * @param level The layer to walk
     * @param ef The beam width
     * @param admissible ids that may enter the results, or null to admit every node reached
     * @return A max-heap of the beam's contents, furthest first
     */
    PriorityQueue<HnswSearchResult> searchLayerBeam(
            float[] query, HnswGraph.NodeView entry, int level, int ef, Set<Long> admissible) {

        // Max-heap for results (furthest first so we can remove worst and peek at furthest)
        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        // Min-heap for candidates (closest first so we process nearest first)
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance)
        );

        // Visited set
        Set<Long> visited = new HashSet<>();

        if (entry == null || entry.isTombstoned()) {
            return results;
        }

        float entryDist = graph.computeDistance(query, entry.vector());
        HnswSearchResult entryResult = graph.createSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        if (admissible == null || admissible.contains(entry.nodeId())) {
            results.add(entryResult);
        }
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            HnswGraph.NodeView currentNode = graph.readNode(current.nodeId());

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

                HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                // Double-check in case neighbor became tombstoned after getActiveNeighbors
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = graph.computeDistance(query, neighbor.vector());
                HnswSearchResult result = graph.createSearchResult(neighborId, dist);

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
}
