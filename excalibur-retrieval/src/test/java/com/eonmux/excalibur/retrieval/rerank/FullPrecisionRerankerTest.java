package com.eonmux.excalibur.retrieval.rerank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FullPrecisionRerankerTest {

    /** A stand-in for a quantised index: returns a fixed, deliberately imperfect ordering. */
    private record ApproximateRanker(String name, List<Long> ids, int[] observedLimit)
            implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            if (observedLimit != null) {
                observedLimit[0] = limit;
            }
            List<ScoredDocument> documents = new ArrayList<>();
            double score = ids.size();
            for (long id : ids) {
                documents.add(new ScoredDocument(id, score--));
            }
            return RankedList.of(name, documents, limit);
        }
    }

    private static VectorSource vectors(Map<Long, float[]> map) {
        return map::get;
    }

    @Test
    @DisplayName("reranking corrects an ordering the approximation got wrong")
    void correctsApproximateOrder() {
        // The approximation puts document 1 first, but in full precision document 3 is nearest.
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 10f, 0f });
        stored.put(2L, new float[] { 5f, 0f });
        stored.put(3L, new float[] { 1f, 0f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L, 2L, 3L), null),
            vectors(stored), L2Distance.getInstance());

        RankedList results = reranker.rank(
            RetrievalQuery.ofEmbedding(new float[] { 0f, 0f }), 3);

        assertThat(results.documents())
            .extracting(ScoredDocument::documentId)
            .containsExactly(3L, 2L, 1L);
        assertThat(results.rankerName()).isEqualTo("pq+exact");
    }

    @Test
    @DisplayName("it over-fetches, or it could not reach the document the approximation misplaced")
    void overFetchesFromTheDelegate() {
        // Asking the approximate index for exactly k and reranking those cannot recover a document
        // it ranked at k+1 -- the same error reranking exists to fix is what excluded it.
        int[] observed = new int[1];
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L), observed),
            vectors(Map.of(1L, new float[] { 1f })), L2Distance.getInstance());

        reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 10);

        assertThat(observed[0])
            .isEqualTo(10 * FullPrecisionReranker.DEFAULT_OVER_FETCH_FACTOR);
    }

    @Test
    @DisplayName("the over-fetched surplus is trimmed back to the requested limit")
    void trimsToLimit() {
        Map<Long, float[]> stored = new HashMap<>();
        for (long id = 1; id <= 8; id++) {
            stored.put(id, new float[] { id });
        }
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), null),
            vectors(stored), L2Distance.getInstance());

        RankedList results = reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 2);

        assertThat(results.documents())
            .extracting(ScoredDocument::documentId)
            .as("the two genuinely nearest, found only because eight were considered")
            .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a candidate with no stored vector is kept instead of vanishing")
    void candidateWithoutVectorSurvives() {
        // A document reached by a lexical or graph signal has no vector. Dropping it would quietly
        // turn a hybrid reranker into a vector-only one.
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 100f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("hybrid", List.of(1L, 2L), null),
            vectors(stored), L2Distance.getInstance());

        RankedList results = reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 10);

        assertThat(results.documents())
            .extracting(ScoredDocument::documentId)
            .containsExactlyInAnyOrder(1L, 2L);
    }

    /** A stand-in for a lexical signal: fixed ids at fixed scores, on its own scale. */
    private record ScoredRanker(String name, List<ScoredDocument> documents) implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            return RankedList.of(name, documents, limit);
        }
    }

    @Test
    @DisplayName("an L2 distance is scored as 1 / (1 + d): one for an exact match, falling with distance")
    void distanceBecomesSimilarity() {
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 0f, 0f });
        stored.put(2L, new float[] { 3f, 0f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(2L, 1L), null),
            vectors(stored), L2Distance.getInstance());

        List<ScoredDocument> results = reranker.rank(
            RetrievalQuery.ofEmbedding(new float[] { 0f, 0f }), 2).documents();

        assertThat(results).extracting(ScoredDocument::documentId).containsExactly(1L, 2L);
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).score()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    @DisplayName("a similarity metric's own value is the score, not its negation")
    void similarityIsItsOwnScore() {
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 1f, 0f });
        stored.put(2L, new float[] { 0f, 1f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(2L, 1L), null),
            vectors(stored), CosineSimilarity.getInstance());

        List<ScoredDocument> results = reranker.rank(
            RetrievalQuery.ofEmbedding(new float[] { 1f, 0f }), 2).documents();

        assertThat(results).extracting(ScoredDocument::documentId).containsExactly(1L, 2L);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(1e-6));
        assertThat(results.get(1).score()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("a vector-less candidate ranks beneath every measured one, however large its lexical score")
    void unmeasuredRanksBeneathMeasured() {
        // Document 9's BM25 score of 40 is not a similarity; compared as one it would outrank every
        // cosine there is. Documents 7 and 9 keep the order the lexical signal gave them.
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 1f, 0f });
        stored.put(2L, new float[] { 1f, 1f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ScoredRanker("bm25", List.of(
                new ScoredDocument(9L, 40.0), new ScoredDocument(1L, 3.0),
                new ScoredDocument(7L, 2.5), new ScoredDocument(2L, 1.0))),
            vectors(stored), CosineSimilarity.getInstance());

        List<ScoredDocument> results = reranker.rank(
            RetrievalQuery.ofEmbedding(new float[] { 1f, 0f }), 4).documents();

        assertThat(results).extracting(ScoredDocument::documentId).containsExactly(1L, 2L, 9L, 7L);
        double lowestMeasured = results.get(1).score();
        assertThat(results.get(2).score()).isLessThan(lowestMeasured);
        assertThat(results.get(3).score()).isLessThan(results.get(2).score());
        assertThat(results.get(3).score())
            .as("evidence the lexical signal found is weak, not none - a threshold must see it")
            .isPositive();
    }

    @Test
    @DisplayName("a vector-less candidate keeps a score below the lowest measured one on the unit scale")
    void unmeasuredKeepsItsOwnScoreBeneath() {
        Map<Long, float[]> stored = new HashMap<>();
        stored.put(1L, new float[] { 1f, 0f });

        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ScoredRanker("bm25", List.of(
                new ScoredDocument(1L, 0.9), new ScoredDocument(5L, 0.4),
                new ScoredDocument(6L, 0.0))),
            vectors(stored), CosineSimilarity.getInstance());

        List<ScoredDocument> results = reranker.rank(
            RetrievalQuery.ofEmbedding(new float[] { 1f, 0f }), 3).documents();

        assertThat(results).extracting(ScoredDocument::documentId).containsExactly(1L, 5L, 6L);
        assertThat(results.get(1).score()).isCloseTo(0.4, within(1e-5));
        assertThat(results.get(2).score()).as("nothing found stays nothing").isZero();
    }

    @Test
    @DisplayName("with no candidate measured, the delegate's list stands as it was, on its own scale")
    void nothingMeasuredKeepsTheDelegate() {
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ScoredRanker("bm25", List.of(
                new ScoredDocument(4L, 0.8), new ScoredDocument(5L, 0.3))),
            vectors(Map.of()), CosineSimilarity.getInstance());

        RankedList results = reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 1f }), 5);

        assertThat(results.rankerName()).isEqualTo("bm25+exact");
        assertThat(results.documents()).containsExactly(
            new ScoredDocument(4L, 0.8), new ScoredDocument(5L, 0.3));
    }

    @Test
    @DisplayName("a dimension mismatch is reported rather than scored as if it fit")
    void dimensionMismatchIsAnError() {
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L), null),
            vectors(Map.of(1L, new float[] { 1f, 2f, 3f })), L2Distance.getInstance());

        assertThatThrownBy(() -> reranker.rank(
                RetrievalQuery.ofEmbedding(new float[] { 1f, 2f }), 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("an empty candidate set reranks to nothing")
    void emptyCandidates() {
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(), null),
            vectors(Map.of()), L2Distance.getInstance());

        assertThat(reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 10).documents())
            .isEmpty();
    }

    @Test
    @DisplayName("a query with no embedding names the reranker that needed one")
    void requiresEmbedding() {
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L), null),
            vectors(Map.of()), L2Distance.getInstance());

        assertThatThrownBy(() -> reranker.rank(RetrievalQuery.ofText("no vector here"), 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pq+exact");
    }

    @Test
    @DisplayName("a configured over-fetch factor is what the delegate is asked for")
    void configuredOverFetchFactorIsUsed() {
        // Every extra candidate costs a full-precision vector read, so how many to consider is a
        // deployment's decision rather than a constant here.
        int[] observed = new int[1];
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L), observed),
            vectors(Map.of(1L, new float[] { 1f })), L2Distance.getInstance(), 9);

        reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 4);

        assertThat(reranker.overFetchFactor()).isEqualTo(9);
        assertThat(observed[0]).isEqualTo(36);
    }

    @Test
    @DisplayName("a factor of one asks for exactly the limit, over-fetching nothing")
    void factorOfOneDisablesOverFetch() {
        int[] observed = new int[1];
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(1L), observed),
            vectors(Map.of(1L, new float[] { 1f })), L2Distance.getInstance(), 1);

        reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), 6);

        assertThat(observed[0]).isEqualTo(6);
    }

    @Test
    @DisplayName("an over-fetch factor below one is refused")
    void overFetchFactorMustBeAtLeastOne() {
        assertThatThrownBy(() -> new FullPrecisionReranker(
                new ApproximateRanker("pq", List.of(), null),
                vectors(Map.of()), L2Distance.getInstance(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("a very large limit saturates rather than overflowing into a negative fetch")
    void overFetchSaturates() {
        int[] observed = new int[1];
        FullPrecisionReranker reranker = new FullPrecisionReranker(
            new ApproximateRanker("pq", List.of(), observed),
            vectors(Map.of()), L2Distance.getInstance());

        reranker.rank(RetrievalQuery.ofEmbedding(new float[] { 0f }), Integer.MAX_VALUE);

        assertThat(observed[0]).isEqualTo(Integer.MAX_VALUE);
    }
}
