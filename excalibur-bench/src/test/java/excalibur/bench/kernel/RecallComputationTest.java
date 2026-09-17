package excalibur.bench.kernel;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the brute-force ground truth and recall arithmetic that an approximate
 * index is scored against.
 *
 * <p>This is the measuring stick for the HNSW recall target. A ground truth computed
 * backwards, or a recall that divides by the wrong denominator, reports an index as
 * meeting 99% recall when it does not - which is the failure mode this suite exists
 * to rule out.</p>
 */
class RecallComputationTest {

    private static final DistanceFunction L2 = L2Distance.getInstance();

    /** Four points on a line, so the nearest-neighbour order is unambiguous. */
    private static final float[][] LINE = {
        {0.0f}, {1.0f}, {2.0f}, {3.0f}, {4.0f}
    };

    @Test
    @DisplayName("ground truth is ordered nearest first")
    void trueTopKIsOrderedNearestFirst() {
        List<Long> topK = RecallComputation.trueTopK(new float[]{2.1f}, LINE, 3, L2);

        assertThat(topK).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("ground truth returns the whole dataset when k exceeds its size")
    void trueTopKIsCappedByDatasetSize() {
        List<Long> topK = RecallComputation.trueTopK(new float[]{0.0f}, LINE, 99, L2);

        assertThat(topK).containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("ground truth ranks a larger-is-closer metric the right way round")
    void trueTopKRespectsMetricOrdering() {
        // Cosine reports similarity: the closest neighbour has the LARGEST compute()
        // value. Ranking by compute() ascending would return the most dissimilar
        // vectors and call them ground truth.
        float[][] directions = {
            {1.0f, 0.0f},   // identical to the query
            {0.0f, 1.0f},   // orthogonal
            {-1.0f, 0.0f}   // opposed
        };

        List<Long> topK = RecallComputation.trueTopK(
            new float[]{1.0f, 0.0f}, directions, 2, CosineSimilarity.getInstance());

        assertThat(topK).containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("recall is the share of ground truth that was retrieved")
    void recallCountsGroundTruthHits() {
        assertThat(RecallComputation.recall(Set.of(1L, 2L, 9L), List.of(1L, 2L, 3L, 4L)))
            .isEqualTo(0.5);
    }

    @Test
    @DisplayName("perfect retrieval is recall 1 and empty retrieval is recall 0")
    void recallSpansTheFullRange() {
        assertThat(RecallComputation.recall(Set.of(1L, 2L), List.of(1L, 2L))).isEqualTo(1.0);
        assertThat(RecallComputation.recall(Set.of(), List.of(1L, 2L))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("extra retrieved ids beyond the ground truth cannot push recall above 1")
    void recallIsNotInflatedByExtraResults() {
        assertThat(RecallComputation.recall(Set.of(1L, 2L, 3L, 4L, 5L), List.of(1L, 2L)))
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("recall against empty ground truth is 0, not a division by zero")
    void recallWithNoGroundTruthIsZero() {
        assertThat(RecallComputation.recall(Set.of(1L), List.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("aggregate recall divides total hits by total expected, not by query count")
    void aggregateRecallUsesTheTotalExpected() {
        // Two queries, k=4 each: 2 hits then 4 hits is 6 of 8.
        RecallComputation.Tally tally = new RecallComputation.Tally();
        tally.record(Set.of(1L, 2L, 7L, 8L), List.of(1L, 2L, 3L, 4L));
        tally.record(Set.of(5L, 6L, 7L, 8L), List.of(5L, 6L, 7L, 8L));

        assertThat(tally.recall()).isEqualTo(0.75);
        assertThat(tally.found()).isEqualTo(6);
        assertThat(tally.expected()).isEqualTo(8);
    }

    @Test
    @DisplayName("an empty tally reports 0 recall rather than NaN")
    void emptyTallyIsZero() {
        assertThat(new RecallComputation.Tally().recall()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("k must be positive")
    void nonPositiveKIsRejected() {
        assertThatThrownBy(() -> RecallComputation.trueTopK(new float[]{0.0f}, LINE, 0, L2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k");
    }
}
