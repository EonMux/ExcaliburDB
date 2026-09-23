package com.eonmux.excalibur.vector.hnsw;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <h2>Locking and reads</h2>
 * <p>This class holds no state of its own and takes no lock that the graph did not already
 * define. The public entry points acquire the graph's read lock exactly where the methods they
 * were extracted from acquired it, and read nodes through a {@link HnswTraversal} opened under
 * it; the layer walks below are handed that traversal by their caller, who holds the lock -
 * the read lock for a query, the write lock for an insert. Nothing the walks read can change
 * while that lock is held, so a node found active when it was reached is still active when the
 * results are extracted.</p>
 *
 * <h2>What a walk allocates</h2>
 * <p>Nodes are handled by id. A node's vector is measured in a scratch buffer, its neighbours
 * read into another, visited nodes are marked in a set borrowed from the graph's
 * {@link HnswVisitedPool} rather than allocated at the graph's size, and the beam holds bare
 * id-and-distance results. Document ids and the generation stamp are attached only to the k
 * results a query returns, not to the thousands of nodes a walk passes through.</p>
 */
final class HnswLayerSearch {

    private static final Comparator<HnswSearchResult> NEAREST_FIRST =
        Comparator.comparingDouble(HnswSearchResult::distance);

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
            HnswTraversal traversal = HnswTraversal.open(graph);
            long entry = descend(traversal, query);
            if (entry < 0) {
                return new ArrayList<>();
            }
            // The layer-0 beam is trimmed to ef entries, so a beam narrower than k
            // can never yield k results.
            int ef = Math.max(graph.config.efSearch(), k);
            return topK(searchLayerBeam(traversal, query, entry, 0, ef, null), k);
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
            final int ef = Math.max(graph.config.efSearch(), (int) Math.min(Integer.MAX_VALUE, 2L * k));
            HnswTraversal traversal = HnswTraversal.open(graph);

            long named = candidateIds.size();
            if (named * named <= (long) ef * graph.nodeCount) {
                return measureCandidates(traversal, query, k, candidateIds);
            }
            long entry = descend(traversal, query);
            if (entry < 0) {
                return new ArrayList<>();
            }
            return topK(searchLayerBeam(traversal, query, entry, 0, ef, candidateIds), k);
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
    private List<HnswSearchResult> measureCandidates(
            HnswTraversal traversal, float[] query, int k, Set<Long> candidateIds) {
        List<HnswSearchResult> measured = new ArrayList<>(candidateIds.size());
        for (long nodeId : candidateIds) {
            if (traversal.isActive(nodeId)) {
                measured.add(new HnswSearchResult(nodeId, traversal.distance(query, nodeId)));
            }
        }
        measured.sort(NEAREST_FIRST);
        return stamped(measured, k);
    }

    /**
     * Greedy descent from the entry point to layer 1, the start of every query.
     *
     * <p>A tombstoned entry point is replaced by any active node, from which the walk proceeds as
     * usual. Must be called with the read lock held.</p>
     *
     * @return The node to start the layer-0 beam from, or -1 when no node is active
     */
    private long descend(HnswTraversal traversal, float[] query) {
        long current = graph.entryPointId;
        if (!traversal.isActive(current)) {
            current = HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
            if (current < 0) {
                return -1;
            }
        }
        for (int level = graph.maxLevel; level > 0; level--) {
            current = searchLayerSimple(traversal, query, current, level);
        }
        return current;
    }

    /**
     * Sorts a beam nearest first and returns its k closest, with document ids attached.
     */
    private List<HnswSearchResult> topK(PriorityQueue<HnswSearchResult> beam, int k) {
        List<HnswSearchResult> sorted = new ArrayList<>(beam);
        sorted.sort(NEAREST_FIRST);
        return stamped(sorted, k);
    }

    /**
     * The first k of a nearest-first list, each carrying its document id and the generation.
     */
    private List<HnswSearchResult> stamped(List<HnswSearchResult> nearestFirst, int k) {
        int count = Math.min(k, nearestFirst.size());
        List<HnswSearchResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HnswSearchResult result = nearestFirst.get(i);
            results.add(graph.createSearchResult(result.nodeId(), result.distance()));
        }
        return results;
    }

    /**
     * Simple greedy search at a single layer.
     *
     * <p>Tombstoned nodes are skipped: they are neither measured nor stepped through.</p>
     *
     * @param traversal Read access opened under the lock the caller holds
     * @param query The vector being navigated towards
     * @param entry An active node where the descent starts at this layer
     * @param level The layer to walk
     * @return The closest node reachable by strictly improving steps
     */
    long searchLayerSimple(HnswTraversal traversal, float[] query, long entry, int level) {
        long current = entry;
        float currentDist = traversal.distance(query, current);
        boolean improved = true;

        while (improved) {
            improved = false;
            int count = traversal.readNeighbors(current, level);
            int[] neighbors = traversal.neighbors();
            for (int i = 0; i < count; i++) {
                long neighborId = neighbors[i];
                if (!traversal.isActive(neighborId)) {
                    continue;
                }
                float dist = traversal.distance(query, neighborId);
                if (dist < currentDist) {
                    currentDist = dist;
                    current = neighborId;
                    improved = true;
                }
            }
        }
        return current;
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
     * which for an unfilled beam would stop it before it had an answer. Tombstoned nodes are
     * never measured, stepped through or kept.</p>
     *
     * @param traversal Read access opened under the lock the caller holds
     * @param query The vector being searched for
     * @param entry An active node where the beam starts
     * @param level The layer to walk
     * @param ef The beam width
     * @param admissible ids that may enter the results, or null to admit every node reached
     * @return A max-heap of the beam's contents, furthest first, without document ids
     */
    PriorityQueue<HnswSearchResult> searchLayerBeam(HnswTraversal traversal, float[] query,
                                                    long entry, int level, int ef,
                                                    Set<Long> admissible) {
        // Sized for what the beam can actually hold: ef is caller-derived and may be as large as
        // Integer.MAX_VALUE, but the beam never outgrows the nodes that exist.
        int capacity = (int) Math.min(Math.min((long) ef, traversal.nodeLimit()) + 1, Integer.MAX_VALUE);
        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(capacity, NEAREST_FIRST.reversed());
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(NEAREST_FIRST);

        if (!traversal.isActive(entry)) {
            return results;
        }

        HnswVisitedPool.Marker visited = graph.visitedPool.borrow(traversal.nodeLimit());
        try {
            HnswSearchResult entryResult = new HnswSearchResult(entry, traversal.distance(query, entry));
            candidates.add(entryResult);
            if (admissible == null || admissible.contains(entry)) {
                results.add(entryResult);
            }
            visited.visit((int) entry);

            while (!candidates.isEmpty()) {
                HnswSearchResult current = candidates.poll();

                // Nothing ahead of the frontier can improve a beam that is already full.
                if (results.size() >= ef && current.distance() > results.peek().distance()) {
                    break;
                }

                int count = traversal.readNeighbors(current.nodeId(), level);
                int[] neighbors = traversal.neighbors();
                for (int i = 0; i < count; i++) {
                    int neighborId = neighbors[i];
                    if (!firstVisit(traversal, visited, neighborId) || !traversal.isActive(neighborId)) {
                        continue;
                    }

                    float dist = traversal.distance(query, neighborId);
                    // Step through it while the beam is unfilled or it beats the worst kept result
                    if (results.size() < ef || dist < results.peek().distance()) {
                        HnswSearchResult result = new HnswSearchResult(neighborId, dist);
                        candidates.add(result);
                        if (admissible == null || admissible.contains((long) neighborId)) {
                            results.add(result);
                            if (results.size() > ef) {
                                results.poll();
                            }
                        }
                    }
                }
            }
            return results;
        } finally {
            graph.visitedPool.release(visited);
        }
    }

    /**
     * Marks a neighbour id visited, reporting whether this walk is reaching it for the first time.
     *
     * <p>An id is only as trustworthy as the record it was read from. One outside the ids handed
     * out names no node - a damaged list, not a neighbour - and is passed over before it can
     * reach the visited set, which is sized by the ids that exist.</p>
     *
     * @param traversal The walk's read access, whose node limit bounds every real id
     * @param visited The walk's visited set
     * @param neighborId The id read from a neighbour list
     * @return true if the id names a node this walk has not reached before
     */
    static boolean firstVisit(HnswTraversal traversal, HnswVisitedPool.Marker visited, int neighborId) {
        return neighborId >= 0 && neighborId < traversal.nodeLimit() && visited.visit(neighborId);
    }
}
