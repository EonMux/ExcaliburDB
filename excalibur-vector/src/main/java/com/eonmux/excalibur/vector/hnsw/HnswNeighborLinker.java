package com.eonmux.excalibur.vector.hnsw;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses and maintains the adjacency lists: which neighbours a node links to, and how a list
 * at its limit makes room for one more.
 *
 * <h2>Why it is its own class</h2>
 * <p>Both halves of edge maintenance run the same selection rule. A new node picks its
 * neighbours from a search beam, and an existing node whose list overflows when a back-link
 * arrives re-picks from what it had plus the newcomer. Keeping that rule in one place is what
 * keeps the two from drifting apart.</p>
 *
 * <h2>The selection rule</h2>
 * <p>Malkov &amp; Yashunin's heuristic (Algorithm 4, without candidate extension or refilling
 * from discarded candidates, as hnswlib and FAISS run it): candidates are taken nearest first,
 * and one is kept only if it is closer to the base node than to every neighbour already kept.
 * A candidate that fails lies behind a kept neighbour and is reached through it. Keeping only
 * the nearest candidates instead spends every edge of a node in a clustered corpus inside its
 * own cluster, so nothing leads out of it and a search that starts elsewhere never arrives.</p>
 *
 * <h2>Edges are directed</h2>
 * <p>A list's contents are its owner's choice alone. When an overflowing list drops a
 * neighbour, the neighbour's own link back is left alone: removing it would take an out-link
 * from a node that did nothing, and repeated over a build that steadily strips the graph of
 * the edges it navigates by. Most edges end up mutual anyway, since every chosen neighbour is
 * offered the link back.</p>
 *
 * <h2>Locking</h2>
 * <p>Every method here must be called with the graph's write lock held, and is handed the
 * {@link HnswTraversal} the caller opened under it; insertion is the only caller, and it holds
 * that lock for the whole of an insert. Reads go through the traversal, writes through the
 * mapped file, and both address the same mapping, so a list written here is what the next read
 * of it sees.</p>
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
     * @param traversal Read access opened under the write lock
     * @param nodeId The node whose list is being written
     * @param level The layer to write
     * @param neighborIds The neighbours to store, truncated to the layer's limit
     * @throws IllegalArgumentException if the node is not written or does not reach that layer:
     *         a record reserves adjacency only for the layers its node spans, so a list for any
     *         other would be written over the record that follows it
     */
    void writeConnections(HnswTraversal traversal, long nodeId, int level, List<Long> neighborIds) {
        int nodeLevel = traversal.level(nodeId);
        if (level < 0 || level > nodeLevel) {
            throw new IllegalArgumentException("node " + nodeId + " at level " + nodeLevel
                + " has no layer " + level + " to write");
        }
        HnswNodeRecords.writeNeighbors(
            graph.mappedFile, graph.config, traversal.recordOffset(nodeId), level, neighborIds);
    }

    /**
     * Chooses up to {@code limit} neighbours for a base vector by the selection heuristic.
     *
     * <p>Candidates that no longer name an active node are skipped.</p>
     *
     * @param traversal Read access opened under the write lock
     * @param nearestFirst Candidates with their distance to the base, closest first
     * @param limit The most neighbours to keep
     * @return The chosen neighbours, closest first
     */
    List<Long> selectNeighbors(HnswTraversal traversal, List<HnswSearchResult> nearestFirst, int limit) {
        List<Long> kept = new ArrayList<>(limit);
        List<float[]> keptVectors = new ArrayList<>(limit);
        // Most candidates are rejected, so each is read into one buffer and only a kept one is
        // copied out to be compared against later.
        float[] vector = new float[graph.config.dimension()];

        for (HnswSearchResult candidate : nearestFirst) {
            if (kept.size() >= limit) {
                break;
            }
            if (!traversal.isActive(candidate.nodeId())) {
                continue;
            }
            traversal.readVector(candidate.nodeId(), vector);
            if (isDiverse(vector, candidate.distance(), keptVectors)) {
                kept.add(candidate.nodeId());
                keptVectors.add(vector.clone());
            }
        }
        return kept;
    }

    /**
     * Whether a candidate is at least as close to the base as to every neighbour already kept.
     */
    private boolean isDiverse(float[] candidate, float distanceToBase, List<float[]> keptVectors) {
        for (float[] kept : keptVectors) {
            if (graph.computeDistance(candidate, kept) < distanceToBase) {
                return false;
            }
        }
        return true;
    }

    /**
     * Offers a node a link to a neighbour, shrinking its list by the heuristic when it is full.
     *
     * <p>A list with room simply takes the link. A full one re-chooses from its current members
     * and the newcomer, measured from its own vector, which may leave the newcomer out or drop an
     * older member; neither outcome touches any other node's list.</p>
     *
     * <p>A node that is inactive, or that does not reach the layer, declines the link. In a sound
     * graph every node offered a link at a layer reaches it, because it was found by walking
     * that layer; one that does not was named by a damaged list, and it has no list at that
     * layer to hold the link.</p>
     *
     * @param traversal Read access opened under the write lock
     * @param nodeId The node whose list is offered the link
     * @param level The layer of the link
     * @param neighborId The neighbour to link to
     */
    void addConnection(HnswTraversal traversal, long nodeId, int level, long neighborId) {
        if (!traversal.isActive(nodeId) || traversal.level(nodeId) < level) {
            return;
        }

        int count = traversal.readNeighbors(nodeId, level);
        int[] current = traversal.neighbors();
        List<Long> offered = new ArrayList<>(count + 1);
        for (int i = 0; i < count; i++) {
            if (current[i] == neighborId) {
                return;
            }
            offered.add((long) current[i]);
        }
        offered.add(neighborId);

        int limit = HnswNodeRecords.maxConnectionsAt(graph.config, level);
        List<Long> updated = offered.size() <= limit
            ? offered
            : selectNeighbors(traversal, measuredFrom(traversal, traversal.vector(nodeId), offered), limit);
        writeConnections(traversal, nodeId, level, updated);
    }

    /**
     * Measures each active node in {@code ids} from {@code base}, closest first.
     */
    private static List<HnswSearchResult> measuredFrom(HnswTraversal traversal, float[] base, List<Long> ids) {
        List<HnswSearchResult> measured = new ArrayList<>(ids.size());
        for (long id : ids) {
            if (traversal.isActive(id)) {
                measured.add(new HnswSearchResult(id, traversal.distance(base, id)));
            }
        }
        measured.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return measured;
    }
}
