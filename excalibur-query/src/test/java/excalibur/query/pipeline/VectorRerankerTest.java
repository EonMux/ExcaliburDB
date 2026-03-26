package excalibur.query.pipeline;

import excalibur.query.QueryResultItem;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswGraph.NodeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for VectorReranker.
 *
 * <p>Tests verify that VectorReranker correctly computes exact distances
 * from full-precision vectors stored in the HNSW graph.</p>
 */
class VectorRerankerTest {

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("Constructor rejects null distance function")
    void testConstructorNullDistanceFunction() {
        assertThatThrownBy(() -> new VectorReranker(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("DistanceFunction cannot be null");
    }

    // ==================== Basic Reranking Tests ====================

    @Test
    @DisplayName("Rerank returns empty list for empty candidates")
    void testRerankEmptyCandidates() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 2.0f, 3.0f};

        List<QueryResultItem> result = reranker.rerank(
            Collections.emptyList(),
            queryVector,
            graph,
            10
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Rerank returns empty list for topK = 0")
    void testRerankZeroTopK() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 2.0f, 3.0f};
        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            0
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Rerank computes exact distance for single candidate")
    void testRerankSingleCandidate() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        NodeView nodeView = mock(NodeView.class);

        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};
        float[] storedVector = new float[]{0.8f, 0.0f, 0.0f};
        float exactDistance = 0.2f;
        // For L2 distance, normalized score = 1 / (1 + distance)
        float expectedNormalizedScore = 1.0f / (1.0f + exactDistance); // = 0.8333333f

        // Setup mocks
        when(graph.readNode(1L)).thenReturn(nodeView);
        when(nodeView.isTombstoned()).thenReturn(false);
        when(nodeView.vector()).thenReturn(storedVector);
        when(distanceFunction.compute(queryVector, storedVector)).thenReturn(exactDistance);

        // Create candidate with approximate distance (different from exact)
        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L)  // Approximate distance 0.5
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(1L);
        // Score should be normalized to [0, 1] range where higher is better
        assertThat(result.get(0).score()).isCloseTo(expectedNormalizedScore, within(0.001f));
        verify(distanceFunction).compute(queryVector, storedVector);
    }

    @Test
    @DisplayName("Rerank sorts results by exact distance")
    void testRerankSortsByExactDistance() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        // Create three nodes with different vectors
        NodeView node1 = mock(NodeView.class);
        NodeView node2 = mock(NodeView.class);
        NodeView node3 = mock(NodeView.class);

        float[] vec1 = new float[]{0.9f, 0.0f, 0.0f};  // Distance 0.1
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};  // Distance 0.5
        float[] vec3 = new float[]{0.7f, 0.0f, 0.0f};  // Distance 0.3

        when(graph.readNode(1L)).thenReturn(node1);
        when(graph.readNode(2L)).thenReturn(node2);
        when(graph.readNode(3L)).thenReturn(node3);

        when(node1.isTombstoned()).thenReturn(false);
        when(node2.isTombstoned()).thenReturn(false);
        when(node3.isTombstoned()).thenReturn(false);

        when(node1.vector()).thenReturn(vec1);
        when(node2.vector()).thenReturn(vec2);
        when(node3.vector()).thenReturn(vec3);

        when(distanceFunction.compute(queryVector, vec1)).thenReturn(0.1f);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);
        when(distanceFunction.compute(queryVector, vec3)).thenReturn(0.3f);

        // Create candidates with approximate distances (in wrong order)
        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L),  // Approx 0.5, exact 0.1
            new QueryResultItem(2L, 0.2f, null, 100L, 2L),  // Approx 0.2, exact 0.5
            new QueryResultItem(3L, 0.3f, null, 100L, 3L)   // Approx 0.3, exact 0.3
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        // Results should be sorted by normalized score descending (higher is better)
        // L2 distance 0.1 → normalized score 1/(1+0.1) ≈ 0.909
        // L2 distance 0.3 → normalized score 1/(1+0.3) ≈ 0.769
        // L2 distance 0.5 → normalized score 1/(1+0.5) ≈ 0.667
        assertThat(result).hasSize(3);
        assertThat(result.get(0).nodeId()).isEqualTo(1L);  // Best score (distance 0.1)
        assertThat(result.get(0).score()).isCloseTo(1.0f / 1.1f, within(0.001f));
        assertThat(result.get(1).nodeId()).isEqualTo(3L);  // Second best (distance 0.3)
        assertThat(result.get(1).score()).isCloseTo(1.0f / 1.3f, within(0.001f));
        assertThat(result.get(2).nodeId()).isEqualTo(2L);  // Worst (distance 0.5)
        assertThat(result.get(2).score()).isCloseTo(1.0f / 1.5f, within(0.001f));
    }

    @Test
    @DisplayName("Rerank limits to topK results")
    void testRerankLimitsToTopK() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        NodeView node1 = mock(NodeView.class);
        NodeView node2 = mock(NodeView.class);
        NodeView node3 = mock(NodeView.class);

        float[] vec1 = new float[]{0.9f, 0.0f, 0.0f};
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};
        float[] vec3 = new float[]{0.7f, 0.0f, 0.0f};

        when(graph.readNode(1L)).thenReturn(node1);
        when(graph.readNode(2L)).thenReturn(node2);
        when(graph.readNode(3L)).thenReturn(node3);

        when(node1.isTombstoned()).thenReturn(false);
        when(node2.isTombstoned()).thenReturn(false);
        when(node3.isTombstoned()).thenReturn(false);

        when(node1.vector()).thenReturn(vec1);
        when(node2.vector()).thenReturn(vec2);
        when(node3.vector()).thenReturn(vec3);

        when(distanceFunction.compute(queryVector, vec1)).thenReturn(0.1f);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);
        when(distanceFunction.compute(queryVector, vec3)).thenReturn(0.3f);

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.1f, null, 100L, 1L),
            new QueryResultItem(2L, 0.5f, null, 100L, 2L),
            new QueryResultItem(3L, 0.3f, null, 100L, 3L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            2  // Only top 2
        );

        assertThat(result).hasSize(2);
        assertThat(result.get(0).nodeId()).isEqualTo(1L);  // Distance 0.1
        assertThat(result.get(1).nodeId()).isEqualTo(3L);  // Distance 0.3
    }

    // ==================== Missing/Tombstoned Node Tests ====================

    @Test
    @DisplayName("Rerank skips missing nodes")
    void testRerankSkipsMissingNodes() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        NodeView node2 = mock(NodeView.class);
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};

        // Node 1 is missing
        when(graph.readNode(1L)).thenReturn(null);
        // Node 2 exists
        when(graph.readNode(2L)).thenReturn(node2);
        when(node2.isTombstoned()).thenReturn(false);
        when(node2.vector()).thenReturn(vec2);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.1f, null, 100L, 1L),
            new QueryResultItem(2L, 0.5f, null, 100L, 2L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Rerank skips tombstoned nodes")
    void testRerankSkipsTombstonedNodes() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        NodeView node1 = mock(NodeView.class);
        NodeView node2 = mock(NodeView.class);
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};

        // Node 1 is tombstoned
        when(graph.readNode(1L)).thenReturn(node1);
        when(node1.isTombstoned()).thenReturn(true);
        // Node 2 is active
        when(graph.readNode(2L)).thenReturn(node2);
        when(node2.isTombstoned()).thenReturn(false);
        when(node2.vector()).thenReturn(vec2);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.1f, null, 100L, 1L),
            new QueryResultItem(2L, 0.5f, null, 100L, 2L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Rerank skips nodes with null vectors")
    void testRerankSkipsNullVectors() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        NodeView node1 = mock(NodeView.class);
        NodeView node2 = mock(NodeView.class);
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};

        when(graph.readNode(1L)).thenReturn(node1);
        when(node1.isTombstoned()).thenReturn(false);
        when(node1.vector()).thenReturn(null);  // Null vector

        when(graph.readNode(2L)).thenReturn(node2);
        when(node2.isTombstoned()).thenReturn(false);
        when(node2.vector()).thenReturn(vec2);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.1f, null, 100L, 1L),
            new QueryResultItem(2L, 0.5f, null, 100L, 2L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Rerank skips nodes with empty vectors")
    void testRerankSkipsEmptyVectors() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        NodeView node1 = mock(NodeView.class);
        NodeView node2 = mock(NodeView.class);
        float[] vec2 = new float[]{0.5f, 0.0f, 0.0f};

        when(graph.readNode(1L)).thenReturn(node1);
        when(node1.isTombstoned()).thenReturn(false);
        when(node1.vector()).thenReturn(new float[0]);  // Empty vector

        when(graph.readNode(2L)).thenReturn(node2);
        when(node2.isTombstoned()).thenReturn(false);
        when(node2.vector()).thenReturn(vec2);
        when(distanceFunction.compute(queryVector, vec2)).thenReturn(0.5f);

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.1f, null, 100L, 1L),
            new QueryResultItem(2L, 0.5f, null, 100L, 2L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeId()).isEqualTo(2L);
    }

    // ==================== Tail Scan Result Tests ====================

    @Test
    @DisplayName("Rerank normalizes tail scan results to [0, 1] score range")
    void testRerankNormalizesTailScanResults() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        // Tail scan results use high bit set in nodeId
        long tailScanNodeId = Long.MIN_VALUE | 42L;

        // HNSW node (not a tail scan result)
        NodeView node1 = mock(NodeView.class);
        float[] vec1 = new float[]{0.9f, 0.0f, 0.0f};

        when(graph.readNode(1L)).thenReturn(node1);
        when(node1.isTombstoned()).thenReturn(false);
        when(node1.vector()).thenReturn(vec1);
        when(distanceFunction.compute(queryVector, vec1)).thenReturn(0.1f);

        // Tail scan result has raw L2 distance 0.3, should be normalized to 1/(1+0.3) ≈ 0.769
        float tailRawDistance = 0.3f;
        float expectedTailNormalizedScore = 1.0f / (1.0f + tailRawDistance);  // ≈ 0.769

        List<QueryResultItem> candidates = Arrays.asList(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L),       // HNSW result
            new QueryResultItem(2L, tailRawDistance, null, 100L, tailScanNodeId)  // Tail scan result
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(2);
        // Tail scan result should now have normalized score in [0, 1] range
        QueryResultItem tailResult = result.stream()
            .filter(r -> r.nodeId() == tailScanNodeId)
            .findFirst()
            .orElse(null);
        assertThat(tailResult).isNotNull();
        // Score should be normalized to [0, 1] range
        assertThat(tailResult.score()).isCloseTo(expectedTailNormalizedScore, within(0.001f));
        assertThat(tailResult.score()).isGreaterThanOrEqualTo(0.0f);
        assertThat(tailResult.score()).isLessThanOrEqualTo(1.0f);
    }

    // ==================== Null Argument Tests ====================

    @Test
    @DisplayName("Rerank rejects null candidates")
    void testRerankNullCandidates() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};

        assertThatThrownBy(() -> reranker.rerank(null, queryVector, graph, 10))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Candidates cannot be null");
    }

    @Test
    @DisplayName("Rerank rejects null query vector")
    void testRerankNullQueryVector() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L)
        );

        assertThatThrownBy(() -> reranker.rerank(candidates, null, graph, 10))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Query vector cannot be null");
    }

    @Test
    @DisplayName("Rerank rejects null graph")
    void testRerankNullGraph() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};
        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L)
        );

        assertThatThrownBy(() -> reranker.rerank(candidates, queryVector, null, 10))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Graph cannot be null");
    }

    // ==================== Distance Function Accessor Tests ====================

    @Test
    @DisplayName("distanceFunction returns configured function")
    void testDistanceFunctionAccessor() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        VectorReranker reranker = new VectorReranker(distanceFunction);

        assertThat(reranker.distanceFunction()).isSameAs(distanceFunction);
    }

    // ==================== Domain Correctness Tests ====================

    @Test
    @DisplayName("Rerank produces normalized scores from L2 distances")
    void testRerankExactL2Distances() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 2.0f, 3.0f};
        float[] storedVector = new float[]{2.0f, 3.0f, 4.0f};

        // L2 distance: sqrt((2-1)^2 + (3-2)^2 + (4-3)^2) = sqrt(3) ≈ 1.732
        float rawL2Distance = 1.7320508f;
        // Normalized score: 1 / (1 + 1.732) ≈ 0.366
        float expectedNormalizedScore = 1.0f / (1.0f + rawL2Distance);

        NodeView node = mock(NodeView.class);
        when(graph.readNode(1L)).thenReturn(node);
        when(node.isTombstoned()).thenReturn(false);
        when(node.vector()).thenReturn(storedVector);
        when(distanceFunction.compute(queryVector, storedVector)).thenReturn(rawL2Distance);

        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(1L, 0.5f, null, 100L, 1L)  // Approximate distance
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        // Score should be normalized to [0, 1] range
        assertThat(result.get(0).score()).isCloseTo(expectedNormalizedScore, within(0.001f));
    }

    @Test
    @DisplayName("Rerank preserves all result metadata except score")
    void testRerankPreservesMetadata() {
        DistanceFunction distanceFunction = mock(DistanceFunction.class);
        when(distanceFunction.name()).thenReturn("l2");  // Required by DistanceValidator
        VectorReranker reranker = new VectorReranker(distanceFunction);
        HnswGraph graph = mock(HnswGraph.class);
        float[] queryVector = new float[]{1.0f, 0.0f, 0.0f};
        float[] storedVector = new float[]{0.8f, 0.0f, 0.0f};

        float rawDistance = 0.2f;
        // For L2, normalized score = 1 / (1 + distance)
        float expectedNormalizedScore = 1.0f / (1.0f + rawDistance); // = 0.8333...

        NodeView node = mock(NodeView.class);
        when(graph.readNode(1L)).thenReturn(node);
        when(node.isTombstoned()).thenReturn(false);
        when(node.vector()).thenReturn(storedVector);
        when(distanceFunction.compute(queryVector, storedVector)).thenReturn(rawDistance);

        byte[] payload = new byte[]{1, 2, 3};
        List<QueryResultItem> candidates = List.of(
            new QueryResultItem(42L, 0.5f, payload, 100L, 1L)
        );

        List<QueryResultItem> result = reranker.rerank(
            candidates,
            queryVector,
            graph,
            10
        );

        assertThat(result).hasSize(1);
        QueryResultItem item = result.get(0);
        assertThat(item.documentId()).isEqualTo(42L);
        assertThat(item.segmentId()).isEqualTo(100L);
        assertThat(item.nodeId()).isEqualTo(1L);
        // Score should be normalized to [0, 1] range
        assertThat(item.score()).isCloseTo(expectedNormalizedScore, within(0.001f));
    }
}
