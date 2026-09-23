package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.quantization.PQDistanceTable;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

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
 * <h2>Reads</h2>
 * <p>Like the full-precision walk, this one reads nodes through a {@link HnswTraversal} opened
 * under the read lock and attaches document ids only to the results it returns.</p>
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

            HnswTraversal traversal = HnswTraversal.open(graph);
            long current = graph.entryPointId;
            if (!traversal.isActive(current)) {
                current = HnswNodeRecords.findActiveNode(graph.mappedFile, graph.nodeCount);
                if (current < 0) {
                    return new ArrayList<>();
                }
            }

            // As in search(): the layer-0 beam is trimmed to ef entries, so a beam narrower
            // than k can never yield k results, and the caller gets a short answer with
            // nothing to say it was short.
            final int efSearch = Math.max(graph.config.efSearch(), k);

            for (int level = graph.maxLevel; level > 0; level--) {
                current = searchLayerSimple(traversal, query, current, level, distanceTable);
            }

            List<HnswSearchResult> nearestFirst = new ArrayList<>(
                searchLayerBeam(traversal, query, current, 0, efSearch, distanceTable));
            nearestFirst.sort(Comparator.comparingDouble(HnswSearchResult::distance));

            int count = Math.min(k, nearestFirst.size());
            List<HnswSearchResult> topResults = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                HnswSearchResult result = nearestFirst.get(i);
                topResults.add(graph.createSearchResult(result.nodeId(), result.distance()));
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
            HnswTraversal traversal = HnswTraversal.open(graph);
            List<HnswSearchResult> measured = new ArrayList<>(candidates.size());
            for (HnswSearchResult candidate : candidates) {
                if (traversal.isActive(candidate.nodeId())) {
                    measured.add(graph.createSearchResult(
                        candidate.nodeId(), traversal.distance(query, candidate.nodeId())));
                }
            }

            measured.sort(Comparator.comparingDouble(HnswSearchResult::distance));
            return new ArrayList<>(measured.subList(0, Math.min(k, measured.size())));
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Simple greedy search at a single layer using PQ distances. Tombstoned nodes are skipped.
     */
    private long searchLayerSimple(HnswTraversal traversal, float[] query, long entry,
                                   int level, PQDistanceTable distanceTable) {
        long current = entry;
        float currentDist = quantization.distance(query, traversal, current, distanceTable);
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
                float dist = quantization.distance(query, traversal, neighborId, distanceTable);
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
     * Beam search at a layer using PQ distances. Tombstoned nodes are never measured or kept.
     *
     * @return A max-heap of the beam's contents, furthest first, without document ids
     */
    private PriorityQueue<HnswSearchResult> searchLayerBeam(
            HnswTraversal traversal, float[] query, long entry, int level, int ef,
            PQDistanceTable distanceTable) {

        PriorityQueue<HnswSearchResult> results = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance).reversed());
        PriorityQueue<HnswSearchResult> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(HnswSearchResult::distance));
        if (!traversal.isActive(entry)) {
            return results;
        }

        HnswVisitedPool.Marker visited = graph.visitedPool.borrow(traversal.nodeLimit());
        try {
            HnswSearchResult entryResult = new HnswSearchResult(
                entry, quantization.distance(query, traversal, entry, distanceTable));
            candidates.add(entryResult);
            results.add(entryResult);
            visited.visit((int) entry);

            while (!candidates.isEmpty()) {
                HnswSearchResult current = candidates.poll();
                if (!results.isEmpty() && current.distance() > results.peek().distance()) {
                    break;
                }

                int count = traversal.readNeighbors(current.nodeId(), level);
                int[] neighbors = traversal.neighbors();
                for (int i = 0; i < count; i++) {
                    int neighborId = neighbors[i];
                    if (!HnswLayerSearch.firstVisit(traversal, visited, neighborId)
                            || !traversal.isActive(neighborId)) {
                        continue;
                    }

                    float dist = quantization.distance(query, traversal, neighborId, distanceTable);
                    if (results.size() < ef || dist < results.peek().distance()) {
                        HnswSearchResult result = new HnswSearchResult(neighborId, dist);
                        candidates.add(result);
                        results.add(result);
                        if (results.size() > ef) {
                            results.poll();
                        }
                    }
                }
            }
            return results;
        } finally {
            graph.visitedPool.release(visited);
        }
    }
}
