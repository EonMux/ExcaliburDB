package excalibur.bench.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The measuring instrument itself.
 *
 * <p>A retrieval comparison is only worth acting on if the metrics are right, and these are the
 * kind of formulas that are easy to write plausibly and wrong. Each test below fixes a case whose
 * answer can be worked out by hand, so a change to the arithmetic is caught here rather than
 * showing up as an architecture decision made on a bad number.
 */
@DisplayName("Retrieval evaluation")
class RetrievalEvaluationTest {

    @Nested
    @DisplayName("metrics")
    class Metrics {

        private final List<String> hits = List.of("a", "b", "c", "d");
        private final Set<String> relevant = Set.of("b", "d", "z");

        @Test
        @DisplayName("recall counts relevant documents found, over relevant documents that exist")
        void recall() {
            // b and d are inside the top 4; z exists and was never retrieved.
            assertThat(RetrievalMetrics.recallAt(hits, relevant, 4)).isEqualTo(2.0 / 3.0);
            assertThat(RetrievalMetrics.recallAt(hits, relevant, 2))
                .as("only b is inside the top 2")
                .isEqualTo(1.0 / 3.0);
            assertThat(RetrievalMetrics.recallAt(List.of(), relevant, 4)).isZero();
        }

        @Test
        @DisplayName("precision is measured against what came back, not against k")
        void precision() {
            // Two of four are relevant.
            assertThat(RetrievalMetrics.precisionAt(hits, relevant, 4)).isEqualTo(0.5);
            // Asking for ten when four came back must not report 0.2: a strategy is not
            // imprecise for returning fewer results than the cutoff allowed.
            assertThat(RetrievalMetrics.precisionAt(hits, relevant, 10)).isEqualTo(0.5);
            assertThat(RetrievalMetrics.precisionAt(List.of(), relevant, 5)).isZero();
        }

        @Test
        @DisplayName("reciprocal rank is the position of the first correct answer")
        void reciprocalRank() {
            assertThat(RetrievalMetrics.reciprocalRank(hits, relevant)).isEqualTo(0.5);
            assertThat(RetrievalMetrics.reciprocalRank(List.of("b", "a"), relevant)).isEqualTo(1.0);
            assertThat(RetrievalMetrics.reciprocalRank(List.of("x", "y"), relevant)).isZero();
        }

        @Test
        @DisplayName("nDCG rewards putting the right answers higher")
        void ndcgRewardsPosition() {
            Set<String> want = Set.of("a", "b");
            double best = RetrievalMetrics.ndcgAt(List.of("a", "b", "c"), want, 3);
            double worse = RetrievalMetrics.ndcgAt(List.of("c", "a", "b"), want, 3);

            assertThat(best).isEqualTo(1.0);
            assertThat(worse).isLessThan(best).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("a perfect ranking scores 1 and an empty one scores 0")
        void bounds() {
            Set<String> want = Set.of("a", "b");
            assertThat(RetrievalMetrics.ndcgAt(List.of("a", "b"), want, 2)).isEqualTo(1.0);
            assertThat(RetrievalMetrics.ndcgAt(List.of("x", "y"), want, 2)).isZero();
            assertThat(RetrievalMetrics.ndcgAt(hits, Set.of(), 4))
                .as("nothing judged relevant means nothing to score")
                .isZero();
        }

        @Test
        @DisplayName("duplicate hits cannot inflate a score")
        void duplicatesDoNotCount() {
            // A strategy returning the same document twice has not found two answers.
            assertThat(RetrievalMetrics.recallAt(List.of("b", "b", "b"), relevant, 3))
                .isEqualTo(1.0 / 3.0);
            assertThat(RetrievalMetrics.precisionAt(List.of("b", "b"), relevant, 2)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a non-positive cutoff is refused")
        void invalidK() {
            assertThatThrownBy(() -> RetrievalMetrics.recallAt(hits, relevant, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("comparing strategies")
    class Comparing {

        private final List<StrategyEvaluator.Judged> judged = List.of(
            new StrategyEvaluator.Judged("out of memory", Set.of("oom")),
            new StrategyEvaluator.Judged("tls handshake", Set.of("tls")));

        @Test
        @DisplayName("a better strategy scores higher on the same queries")
        void betterStrategyScoresHigher() {
            StrategyEvaluator evaluator = new StrategyEvaluator(judged, 5);

            StrategyResult good = evaluator.evaluate(RetrievalStrategy.BM25_EXPANSION,
                (q, k) -> q.startsWith("out") ? List.of("oom") : List.of("tls"));
            StrategyResult bad = evaluator.evaluate(RetrievalStrategy.BM25,
                (q, k) -> List.of("unrelated"));

            assertThat(good.recallAtK()).isEqualTo(1.0);
            assertThat(good.mrr()).isEqualTo(1.0);
            assertThat(bad.recallAtK()).isZero();
            assertThat(good.ran()).isTrue();
        }

        @Test
        @DisplayName("a strategy this deployment cannot run is skipped, never scored zero")
        void unavailableStrategiesAreSkippedNotZeroed() {
            // A zero would average into the comparison and make the available strategies look
            // better than they are, which is the opposite of what the harness is for.
            Map<RetrievalStrategy, StrategyEvaluator.Retrieval> available =
                new EnumMap<>(RetrievalStrategy.class);
            available.put(RetrievalStrategy.BM25, (q, k) -> List.of("oom"));

            List<StrategyResult> results = new StrategyEvaluator(judged, 5).evaluateAll(available);

            StrategyResult vector = results.stream()
                .filter(r -> r.strategy() == RetrievalStrategy.BM25_VECTOR).findFirst().orElseThrow();
            assertThat(vector.ran()).isFalse();
            assertThat(vector.skippedReason()).contains("embedded corpus");
            assertThat(results).hasSize(RetrievalStrategy.values().length);
        }

        @Test
        @DisplayName("a strategy that throws is recorded, not allowed to abort the comparison")
        void failuresAreRecorded() {
            StrategyResult result = new StrategyEvaluator(judged, 5)
                .evaluate(RetrievalStrategy.BM25, (q, k) -> {
                    throw new IllegalStateException("index closed");
                });

            assertThat(result.ran()).isTrue();
            assertThat(result.recallAtK()).isZero();
        }

        @Test
        @DisplayName("which strategies need the corpus embedded is explicit")
        void vectorStrategiesAreIdentifiable() {
            assertThat(RetrievalStrategy.BM25.usesVector()).isFalse();
            assertThat(RetrievalStrategy.BM25_EXPANSION.usesVector()).isFalse();
            assertThat(RetrievalStrategy.BM25_EXPANSION_GRAPH.usesVector()).isFalse();
            assertThat(RetrievalStrategy.FULL_HYBRID.usesVector()).isTrue();
            assertThat(RetrievalStrategy.BM25_VECTOR.usesVector()).isTrue();
        }

        @Test
        @DisplayName("a vocabulary gate is named for what it is")
        void vocabularyGatesAreNamedHonestly() {
            // These two rows were once reported under BM25_GRAPH and BM25_EXPANSION_GRAPH, which
            // measure traversal. A row labelled with a strategy it did not run is worse than a
            // missing row: nothing about the number looks wrong.
            assertThat(RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB.usesExpansion()).isTrue();
            assertThat(RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB.usesGraph()).isFalse();
            assertThat(RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB.usesVector()).isFalse();
            assertThat(RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB.usesExpansion()).isTrue();
            assertThat(RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB.usesGraph()).isFalse();
            assertThat(RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB.usesVector()).isFalse();
        }

        @Test
        @DisplayName("the report names the engine that produced it")
        void reportNamesTheEngine() {
            // These tables get pasted somewhere on their own. A quality number with no engine
            // beside it is a number about an unnamed system.
            List<StrategyResult> results = new StrategyEvaluator(judged, 5)
                .evaluateAll(Map.of(RetrievalStrategy.BM25, (q, k) -> List.of("oom")));

            assertThat(StrategyEvaluator.report(results, 5, "native")).contains("engine=native");
            assertThat(StrategyEvaluator.report(results, 5))
                .as("a comparison that is not engine-bound claims no engine")
                .doesNotContain("engine=");
        }

        @Test
        @DisplayName("the report names every strategy and its cost")
        void reportIsReadable() {
            List<StrategyResult> results = new StrategyEvaluator(judged, 5)
                .evaluateAll(Map.of(RetrievalStrategy.BM25, (q, k) -> List.of("oom")));

            String report = StrategyEvaluator.report(results, 5);

            assertThat(report).contains("recall").contains("nDCG").contains("p95");
            for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
                assertThat(report).contains(strategy.name());
            }
            assertThat(report).contains("no corpus-wide embedding pass");
        }
    }
}
