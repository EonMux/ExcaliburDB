package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The quantised pass has to hand back scores that can be compared with everything else.
 *
 * <p>A segment's answer is the graph's results merged with the write-log tail scan's, and the
 * tail scan measures exact distances. A PQ lookup is a sum of query-to-centroid terms: close to
 * the exact distance, but not it, and the gap is not a constant. Returned as-is, the two halves
 * of the merge would be on different scales and the ranking between them would be decided by
 * which half a result came from. So the quantised beam is only allowed to choose <em>which</em>
 * nodes are worth measuring; what comes back is measured against the full-precision vector.</p>
 */
@DisplayName("Quantised search with exact rescoring")
class HnswGraphPQRerankTest {

    private static final int DIMENSION = 16;
    private static final int CORPUS_SIZE = 200;

    @TempDir
    Path tempDir;

    private HnswGraph graph;
    private DistanceFunction distanceFunction;
    private final List<float[]> corpus = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        distanceFunction = CosineSimilarity.getInstance();
        graph = new HnswGraph(tempDir.resolve("rerank.hnsw"),
            HnswConfig.create(DIMENSION), distanceFunction, 1000);
    }

    @AfterEach
    void tearDown() {
        if (graph != null && !graph.isClosed()) {
            graph.close();
        }
    }

    @Test
    @DisplayName("every returned score is the exact distance, not the quantised estimate")
    void scoresAreExact() {
        fillAndQuantise();
        float[] query = vector(4242);

        List<HnswSearchResult> reranked = graph.searchWithPQThenRerank(query, 10, 4);

        assertThat(reranked).isNotEmpty();
        for (HnswSearchResult result : reranked) {
            float exact = distanceFunction.distance(query, graph.readNode(result.nodeId()).vector());
            assertThat(result.distance())
                .as("node %d must be scored on the full-precision vector", result.nodeId())
                .isEqualTo(exact);
        }
    }

    @Test
    @DisplayName("a node scored by both paths gets the same score from each")
    void scoresAgreeWithExactSearch() {
        fillAndQuantise();
        float[] query = vector(77);

        List<HnswSearchResult> exact = graph.search(query, 10);
        List<HnswSearchResult> reranked = graph.searchWithPQThenRerank(query, 10, 4);

        for (HnswSearchResult result : reranked) {
            exact.stream()
                .filter(candidate -> candidate.nodeId() == result.nodeId())
                .findFirst()
                .ifPresent(match -> assertThat(result.distance())
                    .as("node %d must score the same however it was reached", result.nodeId())
                    .isEqualTo(match.distance()));
        }
    }

    @Test
    @DisplayName("a high oversample ranks exactly as an exhaustive scan does")
    void rankingAgreesWithExhaustiveSearch() {
        fillAndQuantise();
        float[] query = vector(9001);

        List<HnswSearchResult> reranked = graph.searchWithPQThenRerank(query, 5, 20);

        assertThat(reranked).extracting(HnswSearchResult::nodeId)
            .containsExactlyElementsOf(exhaustiveTopK(query, 5));
    }

    @Test
    @DisplayName("results come back closest first")
    void resultsAreOrdered() {
        fillAndQuantise();

        List<HnswSearchResult> reranked = graph.searchWithPQThenRerank(vector(31), 10, 4);

        assertThat(reranked).extracting(HnswSearchResult::distance).isSorted();
        assertThat(reranked).hasSize(10);
    }

    @Test
    @DisplayName("without a quantiser the exact search answers instead")
    void fallsBackToExactSearchWithoutQuantiser() {
        fill();
        float[] query = vector(5);

        assertThat(graph.isPQEnabled()).isFalse();
        assertThat(graph.searchWithPQThenRerank(query, 10, 4))
            .containsExactlyElementsOf(graph.search(query, 10));
    }

    @Test
    @DisplayName("an oversample below one is refused")
    void oversampleBelowOneIsRefused() {
        fillAndQuantise();

        assertThatThrownBy(() -> graph.searchWithPQThenRerank(vector(1), 10, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oversample");
    }

    @Test
    @DisplayName("a non-positive k is refused")
    void nonPositiveKIsRefused() {
        fillAndQuantise();

        assertThatThrownBy(() -> graph.searchWithPQThenRerank(vector(1), 0, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k must be positive");
    }

    private void fill() {
        Random random = new Random(20240914L);
        for (int i = 0; i < CORPUS_SIZE; i++) {
            float[] vector = new float[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = random.nextFloat() * 2 - 1;
            }
            corpus.add(vector);
            graph.insert(vector);
        }
    }

    private void fillAndQuantise() {
        fill();
        graph.trainProductQuantizer(CORPUS_SIZE, 4, 16);
        graph.encodeAllVectors();
    }

    private List<Long> exhaustiveTopK(float[] query, int k) {
        List<Long> ids = new ArrayList<>();
        for (long nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            ids.add(nodeId);
        }
        ids.sort(Comparator.comparingDouble(
            nodeId -> distanceFunction.distance(query, graph.readNode(nodeId).vector())));
        return ids.subList(0, k);
    }

    private static float[] vector(int seed) {
        Random random = new Random(seed);
        float[] values = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            values[i] = random.nextFloat() * 2 - 1;
        }
        return values;
    }
}
