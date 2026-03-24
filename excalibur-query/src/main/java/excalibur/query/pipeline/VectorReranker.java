package excalibur.query.pipeline;

import excalibur.query.QueryResultItem;
import excalibur.query.executor.TailScanner;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.DistanceValidator;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswGraph.NodeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reranks query candidates by computing exact distances on full-precision vectors.
 *
 * <p>Stage 4 of the query pipeline uses this class to compute exact distances
 * for top candidates from Stage 3 (HNSW search + tail scan). This is critical
 * because Stage 3 may return approximate distances if Product Quantization (PQ)
 * was used for the search.</p>
 *
 * <h2>Reranking Process</h2>
 * <ol>
 *   <li>Take candidates with nodeId and approximate distance from Stage 3</li>
 *   <li>Fetch the full-precision vector from the HNSW graph for each nodeId</li>
 *   <li>Compute exact distance using the distance function</li>
 *   <li>Re-sort by exact distance and return top-k</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Distances are computed using exact arithmetic (full precision)</li>
 *   <li>Cosine similarity ∈ [-1, 1], L2 distance ∈ [0, ∞)</li>
 *   <li>Results are sorted by distance (closest first)</li>
 *   <li>Missing vectors are handled gracefully (skipped with warning)</li>
 *   <li>Tombstoned nodes are skipped</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each rerank operation uses local variables only.
 * The HnswGraph and DistanceFunction must be thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class VectorReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorReranker.class);

    private final DistanceFunction distanceFunction;

    /**
     * Creates a new VectorReranker.
     *
     * @param distanceFunction The distance function for computing exact distances
     * @throws NullPointerException if distanceFunction is null
     */
    public VectorReranker(DistanceFunction distanceFunction) {
        this.distanceFunction = Objects.requireNonNull(distanceFunction,
            "DistanceFunction cannot be null");
    }

    /**
     * Reranks candidates by computing exact distances on full-precision stored vectors.
     *
     * <p>This method fetches the full-precision vector for each candidate from
     * the HNSW graph and recomputes the exact distance. Candidates with missing
     * or invalid vectors are skipped.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Results are sorted by exact distance (closest first)</li>
     *   <li>Distances are validated and clamped to valid ranges</li>
     *   <li>Exactly topK results are returned (or fewer if insufficient valid candidates)</li>
     * </ul>
     *
     * @param candidates The candidates to rerank (from Stage 3)
     * @param queryVector The query vector
     * @param graph The HNSW graph containing the full-precision vectors
     * @param topK The maximum number of results to return
     * @return Reranked results sorted by exact distance (closest first)
     * @throws NullPointerException if candidates, queryVector, or graph is null
     * @throws IllegalArgumentException if topK is negative
     */
    public List<QueryResultItem> rerank(
            List<QueryResultItem> candidates,
            float[] queryVector,
            HnswGraph graph,
            int topK) {

        Objects.requireNonNull(candidates, "Candidates cannot be null");
        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(graph, "Graph cannot be null");

        if (topK < 0) {
            throw new IllegalArgumentException("topK cannot be negative: " + topK);
        }

        if (candidates.isEmpty() || topK == 0) {
            return new ArrayList<>();
        }

        LOGGER.trace("Reranking {} candidates with topK={}", candidates.size(), topK);

        List<QueryResultItem> reranked = new ArrayList<>();

        for (QueryResultItem candidate : candidates) {
            long nodeId = candidate.nodeId();

            // Skip tail scan results - they already have exact distances
            // (Tail scan computes exact distances on full-precision vectors from write log)
            if (TailScanner.isTailScanResult(nodeId)) {
                // Keep the existing exact distance from tail scan
                reranked.add(candidate);
                continue;
            }

            try {
                // Fetch the full-precision vector from the HNSW graph
                NodeView nodeView = graph.readNode(nodeId);

                if (nodeView == null) {
                    LOGGER.trace("Node {} not found in graph, skipping", nodeId);
                    continue;
                }

                // Skip tombstoned nodes
                if (nodeView.isTombstoned()) {
                    LOGGER.trace("Node {} is tombstoned, skipping", nodeId);
                    continue;
                }

                // Get the full-precision vector
                float[] storedVector = nodeView.vector();

                if (storedVector == null || storedVector.length == 0) {
                    LOGGER.trace("Node {} has no vector, skipping", nodeId);
                    continue;
                }

                // Validate dimension match
                if (storedVector.length != queryVector.length) {
                    LOGGER.trace("Node {} dimension mismatch: expected {}, got {}, skipping",
                        nodeId, queryVector.length, storedVector.length);
                    continue;
                }

                // Compute exact distance
                float exactDistance = distanceFunction.compute(queryVector, storedVector);

                // Validate and clamp distance to ensure domain correctness
                exactDistance = DistanceValidator.validateAndClamp(exactDistance, distanceFunction);

                // Create new result with exact distance
                reranked.add(candidate.withScore(exactDistance));

            } catch (Exception e) {
                LOGGER.debug("Error reranking node {}: {}", nodeId, e.getMessage());
                // Skip this candidate on error
            }
        }

        // Sort by exact distance (closest first)
        reranked.sort(Comparator.comparingDouble(QueryResultItem::score));

        // Limit to topK
        if (reranked.size() > topK) {
            reranked = new ArrayList<>(reranked.subList(0, topK));
        }

        LOGGER.trace("Reranked to {} results", reranked.size());

        return reranked;
    }

    /**
     * Returns the distance function used by this reranker.
     *
     * @return The distance function
     */
    public DistanceFunction distanceFunction() {
        return distanceFunction;
    }
}
