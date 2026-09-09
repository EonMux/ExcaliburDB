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
 * <p>Stage 4 of the query pipeline uses this class to measure the candidates Stage 3 returned
 * against the query as the graph holds them now. Stage 3's distances are exact already, so the
 * number this recomputes matches for a node that has not moved; what the second look catches is
 * one that has. The node is re-read under the generation it was found at, so a candidate
 * tombstoned since, or whose id a compaction has since given to another node, drops out instead
 * of being ranked on a stale measurement.</p>
 *
 * <h2>Reranking Process</h2>
 * <ol>
 *   <li>Take candidates with nodeId and a normalized score from Stage 3</li>
 *   <li>Fetch the full-precision vector from the HNSW graph for each nodeId</li>
 *   <li>Compute the exact dissimilarity using the distance function</li>
 *   <li>Re-sort by the resulting normalized score and return top-k</li>
 * </ol>
 *
 * <h2>Score Scale</h2>
 * <p>Candidates arrive already normalized to [0.0, 1.0] (higher is better) by
 * {@link excalibur.query.executor.ResultAggregator#mergeAndRankWithNormalization}.
 * Reranking replaces that score with one computed the same way from the exact
 * distance, so the output stays on a single scale. Candidates this reranker cannot
 * re-measure - write-log tail-scan hits, whose vectors are not in the graph - keep
 * the score they arrived with. Normalizing them a second time would map an already
 * normalized [0,1] score into [0.5,1.0] under cosine and float every tail hit above
 * genuine graph matches.</p>
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
     *   <li>Results are sorted by normalized score descending (closest first)</li>
     *   <li>Distances are validated and clamped to valid ranges</li>
     *   <li>Every result carries a [0.0, 1.0] score normalized exactly once</li>
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

            // Tail scan results have no node in the graph, so there is nothing to
            // re-measure. Their score was already normalized by the aggregator on the
            // same scale as every other candidate, so it passes through untouched.
            if (TailScanner.isTailScanResult(nodeId)) {
                reranked.add(candidate);
                continue;
            }

            try {
                // Fetch the full-precision vector from the HNSW graph. The generation
                // check rejects a node ID invalidated by a concurrent compaction rather
                // than letting it resolve to whatever vector now holds that ID.
                NodeView nodeView = graph.readNode(nodeId, candidate.generation());

                if (nodeView == null) {
                    LOGGER.trace("Node {} not found in graph (generation {}), skipping",
                        nodeId, candidate.generation());
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

                // Compute the exact dissimilarity (smaller is closer for every metric)
                float exactDistance = distanceFunction.distance(queryVector, storedVector);

                if (!Float.isFinite(exactDistance)) {
                    LOGGER.trace("Node {} produced non-finite distance {}, skipping",
                        nodeId, exactDistance);
                    continue;
                }

                // Validate, clamp, and normalize to [0.0, 1.0] score range
                // This ensures all scores are in the valid [0.0, 1.0] range for ranking
                float normalizedScore = DistanceValidator.validateClampAndNormalizeOrderedDistance(
                    exactDistance, distanceFunction);

                if (Float.isNaN(normalizedScore)) {
                    LOGGER.trace("Node {} produced NaN score, skipping", nodeId);
                    continue;
                }

                // Create new result with normalized score
                reranked.add(candidate.withScore(normalizedScore));

            } catch (Exception e) {
                LOGGER.debug("Error reranking node {}: {}", nodeId, e.getMessage());
                // Skip this candidate on error
            }
        }

        // Sort by normalized score descending (highest/best first)
        // Normalized scores are in [0.0, 1.0] where higher is better
        reranked.sort(Comparator.comparingDouble(QueryResultItem::score).reversed());

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
