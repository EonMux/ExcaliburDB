package io.excalibur.retrieval;

import java.util.List;

import io.excalibur.core.trace.QueryTrace;
import io.excalibur.core.trace.Stage;
import io.excalibur.retrieval.fusion.FusedDocument;
import io.excalibur.retrieval.fusion.ReciprocalRankFusion;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRetrieverTest {

    /** A ranker that returns a fixed list, for testing the wiring rather than any real signal. */
    private record FixedRanker(String name, long... ids) implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            List<ScoredDocument> documents = new java.util.ArrayList<>();
            double score = ids.length;
            for (long id : ids) {
                documents.add(new ScoredDocument(id, score--));
            }
            return RankedList.of(name, documents, limit);
        }
    }

    private record FailingRanker(String name) implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            throw new IllegalStateException("signal " + name + " is down");
        }
    }

    private static final RetrievalQuery QUERY = RetrievalQuery.ofText("anything");

    @Test
    @DisplayName("results from every signal are fused into one ordering")
    void fusesEverySignal() {
        HybridRetriever retriever = new HybridRetriever(List.of(
            new FixedRanker("dense", 1, 2, 3),
            new FixedRanker("bm25", 3, 2, 1)));

        List<FusedDocument> results = retriever.retrieve(QUERY, 10);

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(d -> d.signalCount() == 2);

        // 1/rank is convex, so a document ranked (1, 3) scores above one ranked (2, 2):
        // 1/61 + 1/63 = 0.032266 against 2/62 = 0.032258. RRF therefore prefers a document that
        // one signal is confident about over one that both are merely lukewarm on, by a hair.
        // Documents 1 and 3 tie exactly and are separated only by the deterministic tie break.
        assertThat(results)
            .extracting(FusedDocument::documentId)
            .containsExactly(1L, 3L, 2L);
        assertThat(results.get(0).score()).isGreaterThan(results.get(2).score());
    }

    @Test
    @DisplayName("a failing signal degrades the answer instead of failing the query")
    void oneFailingSignalIsSurvivable() {
        HybridRetriever retriever = new HybridRetriever(List.of(
            new FixedRanker("dense", 1, 2),
            new FailingRanker("bm25")));

        List<FusedDocument> results = retriever.retrieve(QUERY, 10);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(d -> d.signalCount() == 1);
    }

    @Test
    @DisplayName("every signal failing fails the query rather than reporting an empty match")
    void allSignalsFailingIsAnError() {
        HybridRetriever retriever = new HybridRetriever(List.of(
            new FailingRanker("dense"), new FailingRanker("bm25")));

        assertThatThrownBy(() -> retriever.retrieve(QUERY, 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("every retrieval signal failed");
    }

    @Test
    @DisplayName("signals are asked for more than the final limit so agreement can be seen")
    void signalsAreOverFetched() {
        // A document deep in both lists can still win on agreement, but only if both lists ran
        // deep enough to contain it. Asking each signal for exactly the limit would discard that.
        int[] askedFor = new int[1];
        Ranker recording = new Ranker() {
            @Override
            public String name() {
                return "recording";
            }

            @Override
            public RankedList rank(RetrievalQuery query, int limit) {
                askedFor[0] = limit;
                return RankedList.empty(name());
            }
        };

        new HybridRetriever(List.of(recording, new FixedRanker("other", 1)))
            .retrieve(QUERY, 5);

        assertThat(askedFor[0])
            .isEqualTo(5 * HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH);
    }

    @Test
    @DisplayName("a configured over-fetch is what each signal is asked for")
    void configuredOverFetchIsUsed() {
        // How deep each signal runs is a cost-against-recall decision that belongs to whoever
        // sized the deployment, not to a constant in this file.
        int[] askedFor = new int[1];
        Ranker recording = new Ranker() {
            @Override
            public String name() {
                return "recording";
            }

            @Override
            public RankedList rank(RetrievalQuery query, int limit) {
                askedFor[0] = limit;
                return RankedList.empty(name());
            }
        };

        new HybridRetriever(List.of(recording, new FixedRanker("other", 1)),
            new ReciprocalRankFusion(), 7).retrieve(QUERY, 5);

        assertThat(askedFor[0]).isEqualTo(35);
    }

    @Test
    @DisplayName("an over-fetch below one is refused")
    void overFetchMustBeAtLeastOne() {
        assertThatThrownBy(() -> new HybridRetriever(
                List.of(new FixedRanker("dense", 1)), new ReciprocalRankFusion(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("a configured fusion is the one that runs, k and all")
    void configuredFusionIsUsed() {
        // The convenience constructor builds fusion at its default k. A deployment that set k
        // has to be able to hand one in instead, or the setting would stop at this boundary.
        HybridRetriever retriever = new HybridRetriever(List.of(new FixedRanker("dense", 1)),
            new ReciprocalRankFusion(17), HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH);

        assertThat(retriever.fusion().name()).isEqualTo("rrf(k=17)");
        assertThat(retriever.retrieve(QUERY, 1).get(0).score())
            .isCloseTo(1.0 / 18, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("the rank stage is recorded in the trace")
    void tracesTheRankStage() {
        QueryTrace trace = new QueryTrace();
        new HybridRetriever(List.of(new FixedRanker("dense", 1))).retrieve(QUERY, 10, trace);

        assertThat(trace.nanosIn(Stage.RANK)).isPositive();
        assertThat(trace.entriesIn(Stage.RANK)).isEqualTo(1);
        assertThat(trace.byStage()).containsKey(Stage.RANK);
    }

    @Test
    @DisplayName("two signals sharing a name are refused")
    void duplicateSignalNamesRefused() {
        assertThatThrownBy(() -> new HybridRetriever(List.of(
                new FixedRanker("bm25", 1), new FixedRanker("bm25", 2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("distinct");
    }

    @Test
    @DisplayName("a retriever needs at least one signal")
    void needsASignal() {
        assertThatThrownBy(() -> new HybridRetriever(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one ranker");
    }

    @Test
    @DisplayName("a non-positive limit returns nothing without running any signal")
    void nonPositiveLimit() {
        HybridRetriever retriever = new HybridRetriever(List.of(new FailingRanker("would-throw")));
        assertThat(retriever.retrieve(QUERY, 0)).isEmpty();
    }

    @Test
    @DisplayName("the limit caps the fused result")
    void limitCapsResult() {
        HybridRetriever retriever = new HybridRetriever(List.of(
            new FixedRanker("dense", 1, 2, 3, 4, 5)));

        assertThat(retriever.retrieve(QUERY, 2)).hasSize(2);
    }
}
