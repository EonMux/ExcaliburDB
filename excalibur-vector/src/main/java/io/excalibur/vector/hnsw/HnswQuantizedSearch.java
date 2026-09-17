package io.excalibur.vector.hnsw;

import io.excalibur.vector.quantization.PQDistanceTable;
import io.excalibur.vector.quantization.ProductQuantizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The quantised half of the query path: the same descent and beam, scored from a
 * query-to-centroid table instead of from the stored vectors.
 *
 * <h2>Why it is not folded into {@link HnswLayerSearch}</h2>
 * <p>The two walks look alike and are deliberately kept apart, because the numbers they produce
 * are not on the same scale. A table lookup is a sum of per-subspace approximations: close to
 * the true distance, but off by an amount that varies per node. A queue holding both kinds
 * would order nodes partly by which encoding each happened to have. Keeping the quantised walk
 * in its own class is what makes "never mix the two scales in one heap" a structural fact
 * rather than a rule someone has to remember.</p>
 *
 * <h2>Why the beam's stopping rule differs</h2>
 * <p>The quantised beam stops as soon as the frontier is worse than the current worst result,
 * without waiting for the beam to fill. This is the behaviour the graph has always had on this
 * path and is preserved exactly; the full-precision beam's rule, which also requires the beam
 * to be full, is documented on {@link HnswLayerSearch#searchLayerBeam}.</p>
 *
 * <h2>Reranking</h2>
 * <p>{@link #searchWithPQThenRerank} exists because a segment's answer is merged with the write
 * log tail scan's, and the tail scan measures exactly. Handing back table values would let the
 * merged ranking turn on which half a result came from, so every candidate is re-measured
 * against its stored vector before it is returned.</p>
 */
final class HnswQuantizedSearch {

    private final HnswGraph graph;
    private final HnswQuantization quantization;

    /**
     * Creates the quantised search collaborator for one graph.
     *
     * @param graph The graph being searched
     * @param quantization The codebook and codes the scoring reads through
     */
    HnswQuantizedSearch(HnswGraph graph, HnswQuantization quantization) {
        this.graph = graph;
        this.quantization = quantization;
    }

    /**
     * Searches with a distance table built for this graph's metric.
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @return The k nearest, closest first, scored from the table
     */
    List<HnswSearchResult> search(float[] query, int k) {
        ProductQuantizer pq = quantization.quantizer();
        if (pq == null || !pq.isTrained()) {
            // Fall back to regular search
            return graph.search(query, k);
        }

        // Built for this graph's metric. A table on squared L2 consulted by a cosine graph
        // ranks by a different geometry and returns values on a range the caller's
        // normalisation was never calibrated against.
        PQDistanceTable distanceTable = pq.createDistanceTable(query, graph.distanceFunction);
        return graph.searchWithPQ(query, k, distanceTable);
    }

    /**
     * Searches with a caller-supplied distance table.
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @param distanceTable Precomputed distance table for the query
     * @return The k nearest, closest first, scored from the table
     */
    List<HnswSearchResult> search(float[] query, int k, PQDistanceTable distanceTable) {
        graph.validateNotClosed();
        graph.validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        if (!quantization.isEnabled()) {
            return graph.search(query, k);
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
                currentNodeId = HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
                if (currentNodeId < 0) {
                    return new ArrayList<>();
                }
                current = graph.readNode(currentNodeId);
            }

            // As in search(): the layer-0 beam is trimmed to ef entries, so a beam narrower
            // than k can never yield k results, and the caller gets a short answer with
            // nothing to say it was short.
            final int efSearch = Math.max(graph.config.efSearch(), k);

            // Search through layers from top to layer 1
            for (int level = graph.maxLevel; level > 0; level--) {
                HnswGraph.NodeView closest = searchLayerSimple(query, current, level, distanceTable);
                if (closest != null) {
                    current = closest;
                }
            }

            // At layer 0, perform beam search with PQ
            PriorityQueue<HnswSearchResult> results = searchLayerBeam(
                query, current, 0, efSearch, distanceTable);

            // Extract and sort results, re-checking tombstones the way search() does: a node
            // admitted to the beam can be tombstoned before it is extracted, and returning it
            // hands back a deleted document.
            List<HnswSearchResult> allResults = new ArrayList<>();
            while (!results.isEmpty()) {
                HnswSearchResult result = results.poll();
                HnswGraph.NodeView node = graph.readNode(result.nodeId());
                if (node != null && !node.isTombstoned()) {
                    allResults.add(result);
                }
            }

            allResults.sort(Comparator.comparingDouble(HnswSearchResult::distance));

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
     * Searches with the quantised beam and returns the top k scored on full-precision vectors.
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @param oversample How many candidates to measure per result, at least 1
     * @return The k nearest, closest first, scored exactly
     */
    List<HnswSearchResult> searchThenRerank(float[] query, int k, int oversample) {
        graph.validateNotClosed();
        graph.validateQuery(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (oversample < 1) {
            throw new IllegalArgumentException("oversample must be at least 1: " + oversample);
        }

        if (!quantization.isEnabled()) {
            return graph.search(query, k);
        }

        // One read lock over both passes, which the nested acquisitions below re-enter. The
        // candidates are node ids, and a compaction between the two passes renumbers them -
        // the rescore would then measure the query against whichever vectors had inherited
        // those ids, and return them as this segment's nearest.
        graph.lock.readLock().lock();
        try {
            List<HnswSearchResult> candidates =
                graph.searchWithPQ(query, candidateWidth(k, oversample));
            return rescoreExactly(query, candidates, k);
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * The number of candidates a rescored search collects, saturating instead of overflowing.
     *
     * <p>{@code k} reaches here from the caller and the oversample from configuration, so the
     * product can leave the int range; wrapping would turn the beam width negative.</p>
     */
    private static int candidateWidth(int k, int oversample) {
        long widened = (long) k * oversample;
        return widened > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) widened;
    }

    /**
     * Re-measures candidates against their stored vectors and keeps the k nearest.
     *
     * <p>A candidate tombstoned since the beam admitted it is dropped rather than carried
     * through with a distance nothing backs, which is the same re-check
     * {@link HnswGraph#search(float[], int)} makes on its way out.</p>
     *
     * @param query The query vector
     * @param candidates What the quantised beam proposed
     * @param k How many to keep
     * @return The k nearest candidates, closest first
     */
    private List<HnswSearchResult> rescoreExactly(
            float[] query, List<HnswSearchResult> candidates, int k) {

        graph.lock.readLock().lock();
        try {
            List<HnswSearchResult> measured = new ArrayList<>(candidates.size());
            for (HnswSearchResult candidate : candidates) {
                HnswGraph.NodeView node = graph.readNode(candidate.nodeId());
                if (node == null || node.isTombstoned()) {
                    continue;
                }
                measured.add(graph.createSearchResult(
                    candidate.nodeId(), graph.computeDistance(query, node.vector())));
            }

            measured.sort(Comparator.comparingDouble(HnswSearchResult::distance));
            return new ArrayList<>(measured.subList(0, Math.min(k, measured.size())));
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Simple greedy search at a single layer using PQ distances.
     */
    private HnswGraph.NodeView searchLayerSimple(float[] query, HnswGraph.NodeView entry,
                                                 int level, PQDistanceTable distanceTable) {
        HnswGraph.NodeView current = entry;
        float currentDist = quantization.distance(query, current, distanceTable);
        boolean improved = true;

        while (improved) {
            improved = false;
            List<Long> neighbors = current.getActiveNeighbors(level);

            for (long neighborId : neighbors) {
                HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = quantization.distance(query, neighbor, distanceTable);
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
    private PriorityQueue<HnswSearchResult> searchLayerBeam(
            float[] query, HnswGraph.NodeView entry, int level, int ef,
            PQDistanceTable distanceTable) {

        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance).reversed());
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance));
        Set<Long> visited = new HashSet<>();

        if (entry == null || entry.isTombstoned()) {
            return results;
        }

        float entryDist = quantization.distance(query, entry, distanceTable);
        HnswSearchResult entryResult = graph.createSearchResult(entry.nodeId(), entryDist);
        candidates.add(entryResult);
        results.add(entryResult);
        visited.add(entry.nodeId());

        while (!candidates.isEmpty()) {
            HnswSearchResult current = candidates.poll();
            HnswGraph.NodeView currentNode = graph.readNode(current.nodeId());

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

                HnswGraph.NodeView neighbor = graph.readNode(neighborId);
                if (neighbor == null || neighbor.isTombstoned()) {
                    continue;
                }

                float dist = quantization.distance(query, neighbor, distanceTable);
                HnswSearchResult result = graph.createSearchResult(neighborId, dist);

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
}
