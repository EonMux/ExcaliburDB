package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Relevancy scoring over a ranking the pipeline did not produce.
 *
 * <p>The contract is Stage 4's, so these hold it to Stage 4's terms: a judged candidate carries
 * the model's relevancy, an unjudged one keeps its retrieval score, the threshold removes, the
 * result is ordered by score and cut to the page's depth, and a scorer that fails or is too slow
 * leaves the retrieval ranking in place rather than failing the query.</p>
 */
@DisplayName("RelevancyScoring")
class RelevancyScoringTest {

    private static final Function<String, String> CORPUS =
        Map.of("a", "alpha text", "b", "beta text", "c", "gamma text", "d", "delta text")::get;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RelevancyScoring scoring = new RelevancyScoring(executor, 4096);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static QueryRequestParser.ParsedQuery parsed(boolean enable, float llmThreshold,
                                                         int oversampling) {
        return new QueryRequestParser.ParsedQuery("which one", 10, 0.0f, 5_000L, true, enable,
            llmThreshold, oversampling, false,
            com.eonmux.excalibur.core.text.SourceScope.ALL);
    }

    private static List<LexicalQueryService.Hit> hits(String... ids) {
        List<LexicalQueryService.Hit> hits = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            hits.add(new LexicalQueryService.Hit(ids[i], 1.0 / (61 + i)));
        }
        return hits;
    }

    private static long later() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    /** A model that judges the candidates it has a verdict for and leaves the rest alone. */
    private static QueryPipeline.LlmScorer judging(Map<String, Float> verdicts) {
        return (query, results, threshold) -> {
            List<QueryResultItem> out = new ArrayList<>();
            for (QueryResultItem item : results) {
                Float verdict = verdicts.get(item.externalId());
                if (verdict == null) {
                    out.add(item);
                } else if (verdict >= threshold) {
                    out.add(item.withScore(verdict));
                }
            }
            return out;
        };
    }

    @Nested
    @DisplayName("deciding whether to score")
    class Planning {

        @Test
        @DisplayName("plans nothing when the request turned scoring off")
        void offWhenDisabled() {
            assertThat(scoring.plan(parsed(false, 0.5f, 2), judging(Map.of()), CORPUS).runs())
                .isFalse();
        }

        @Test
        @DisplayName("plans nothing on a node with no chat model")
        void offWithoutScorer() {
            assertThat(scoring.plan(parsed(true, 0.5f, 2), null, CORPUS).runs()).isFalse();
        }

        @Test
        @DisplayName("plans nothing on a node with no chunk text for a model to read")
        void offWithoutText() {
            assertThat(scoring.plan(parsed(true, 0.5f, 2), judging(Map.of()), null).runs())
                .isFalse();
        }

        @Test
        @DisplayName("plans scoring when the request, the model and the text are all there")
        void onWhenEverythingIsThere() {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.4f, 3), judging(Map.of()),
                CORPUS);

            assertThat(plan.runs()).isTrue();
            assertThat(plan.threshold()).isEqualTo(0.4f);
            assertThat(plan.oversamplingFactor()).isEqualTo(3);
            assertThat(plan.query()).isEqualTo("which one");
        }

        @Test
        @DisplayName("refuses a candidate bound that is not positive")
        void refusesNonPositiveBound() {
            assertThatThrownBy(() -> new RelevancyScoring(executor, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("sizing retrieval")
    class Sizing {

        @Test
        @DisplayName("ranks only as deep as the page when nothing is scored")
        void depthWhenUnscored() {
            assertThat(RelevancyScoring.Plan.NONE.candidates(15)).isEqualTo(15);
            assertThat(RelevancyScoring.Plan.NONE.retrievalBudgetMs(1_000)).isEqualTo(1_000);
        }

        @Test
        @DisplayName("ranks oversampling_factor times deeper when scoring follows")
        void oversamplesWhenScored() {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 3), judging(Map.of()),
                CORPUS);

            assertThat(plan.candidates(10)).isEqualTo(30);
        }

        @Test
        @DisplayName("never widens past what the pipeline accepts, nor narrows below the page")
        void widenedWithinBounds() {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 10), judging(Map.of()),
                CORPUS);

            assertThat(plan.candidates(5_000)).isEqualTo(QueryRequest.MAX_TOP_K);
            assertThat(plan.candidates(QueryRequest.MAX_TOP_K + 1))
                .isEqualTo(QueryRequest.MAX_TOP_K + 1);
        }

        @Test
        @DisplayName("holds the model's share of the budget back from retrieval")
        void holdsBackTheModelsShare() {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), judging(Map.of()),
                CORPUS);

            assertThat(plan.retrievalBudgetMs(1_000)).isEqualTo(800);
        }
    }

    @Nested
    @DisplayName("scoring a ranking")
    class Scoring {

        @Test
        @DisplayName("cuts an unscored ranking to the page's depth and leaves its order alone")
        void unscoredIsTruncated() throws InterruptedException {
            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(
                RelevancyScoring.Plan.NONE, hits("a", "b", "c"), 2, RelevancyScoring.HITS,
                later(), new CancellationToken());

            assertThat(outcome.ranking()).extracting(LexicalQueryService.Hit::chunkId)
                .containsExactly("a", "b");
            assertThat(outcome.scored()).isFalse();
            assertThat(outcome.degraded()).isFalse();
        }

        @Test
        @DisplayName("orders by the model's judgement, drops what the threshold removes, and "
            + "keeps the retrieval score of what it could not judge")
        void appliesTheVerdict() throws InterruptedException {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2),
                judging(Map.of("a", 0.2f, "b", 0.9f, "c", 0.7f)), CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b", "c", "d"), 3, RelevancyScoring.HITS, later(),
                new CancellationToken());

            assertThat(outcome.scored()).isTrue();
            assertThat(outcome.ranking()).extracting(LexicalQueryService.Hit::chunkId)
                .containsExactly("b", "c", "d");
            assertThat(outcome.ranking().get(0).score()).isEqualTo(0.9f, within());
            assertThat(outcome.ranking().get(2).score())
                .as("the model had no verdict on d, so it keeps what BM25 gave it")
                .isEqualTo(1.0 / 64);
        }

        @Test
        @DisplayName("hands the model each candidate's text, read from chunk storage")
        void readsTheText() throws InterruptedException {
            AtomicReference<List<String>> seen = new AtomicReference<>();
            QueryPipeline.LlmScorer recording = (query, results, threshold) -> {
                List<String> texts = new ArrayList<>();
                for (QueryResultItem item : results) {
                    texts.add(new String(item.payload(), StandardCharsets.UTF_8));
                }
                seen.set(texts);
                return results;
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), recording, CORPUS);

            scoring.score(plan, hits("a", "b"), 2, RelevancyScoring.HITS, later(),
                new CancellationToken());

            assertThat(seen.get()).containsExactly("alpha text", "beta text");
        }

        @Test
        @DisplayName("keeps the retrieval ranking and says it fell back when the model fails")
        void fallsBackWhenTheModelFails() throws InterruptedException {
            QueryPipeline.LlmScorer failing = (query, results, threshold) -> {
                throw new IllegalStateException("breaker open");
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), failing, CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b", "c"), 2, RelevancyScoring.HITS, later(), new CancellationToken());

            assertThat(outcome.fellBack()).isTrue();
            assertThat(outcome.scored()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.ranking()).extracting(LexicalQueryService.Hit::chunkId)
                .containsExactly("a", "b");
        }

        @Test
        @DisplayName("keeps the retrieval ranking and says it timed out when the model is too "
            + "slow for the deadline")
        void timesOutOnASlowModel() throws InterruptedException {
            CountDownLatch never = new CountDownLatch(1);
            QueryPipeline.LlmScorer hanging = (query, results, threshold) -> {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return results;
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), hanging, CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b", "c"), 2, RelevancyScoring.HITS,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100), new CancellationToken());

            assertThat(outcome.timedOut()).isTrue();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.ranking()).hasSize(2);
        }

        @Test
        @DisplayName("does not ask the model at all when the deadline has already passed")
        void noCallPastTheDeadline() throws InterruptedException {
            AtomicReference<Boolean> called = new AtomicReference<>(false);
            QueryPipeline.LlmScorer recording = (query, results, threshold) -> {
                called.set(true);
                return results;
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), recording, CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b"), 1, RelevancyScoring.HITS, System.nanoTime() - 1,
                new CancellationToken());

            assertThat(outcome.timedOut()).isTrue();
            assertThat(outcome.ranking()).hasSize(1);
            assertThat(called.get()).isFalse();
        }

        @Test
        @DisplayName("answers nothing for a request that was withdrawn before scoring")
        void withdrawnBeforehand() throws InterruptedException {
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), judging(Map.of()),
                CORPUS);
            CancellationToken token = new CancellationToken();
            token.cancel("caller withdrew");

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a"), 1, RelevancyScoring.HITS, later(), token);

            assertThat(outcome.cancelled()).isTrue();
            assertThat(outcome.ranking()).isEmpty();
        }

        @Test
        @DisplayName("withdraws the model call when the request is cancelled mid-flight")
        void withdrawnWhileScoring() throws InterruptedException {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch never = new CountDownLatch(1);
            QueryPipeline.LlmScorer hanging = (query, results, threshold) -> {
                started.countDown();
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return results;
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), hanging, CORPUS);
            CancellationToken token = new CancellationToken();
            executor.submit(() -> {
                started.await();
                token.cancel("caller withdrew");
                return null;
            });

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b"), 2, RelevancyScoring.HITS, later(), token);

            assertThat(outcome.cancelled()).isTrue();
            assertThat(outcome.ranking()).isEmpty();
        }

        @Test
        @DisplayName("makes no call over an empty ranking, and reports the stage as having run")
        void emptyRanking() throws InterruptedException {
            AtomicReference<Boolean> called = new AtomicReference<>(false);
            QueryPipeline.LlmScorer recording = (query, results, threshold) -> {
                called.set(true);
                return results;
            };
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), recording, CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                List.of(), 5, RelevancyScoring.HITS, later(), new CancellationToken());

            assertThat(outcome.scored()).isTrue();
            assertThat(outcome.ranking()).isEmpty();
            assertThat(called.get()).isFalse();
        }

        @Test
        @DisplayName("falls back rather than failing when the executor refuses the call")
        void fallsBackWhenRefused() throws InterruptedException {
            ExecutorService closed = Executors.newSingleThreadExecutor();
            closed.shutdown();
            RelevancyScoring refused = new RelevancyScoring(closed, 4096);
            RelevancyScoring.Plan plan = refused.plan(parsed(true, 0.5f, 2), judging(Map.of()),
                CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = refused.score(plan,
                hits("a", "b"), 1, RelevancyScoring.HITS, later(), new CancellationToken());

            assertThat(outcome.fellBack()).isTrue();
            assertThat(outcome.ranking()).hasSize(1);
        }

        @Test
        @DisplayName("drops a verdict about a candidate it was never handed")
        void dropsForeignResults() throws InterruptedException {
            QueryPipeline.LlmScorer inventive = (query, results, threshold) -> List.of(
                new QueryResultItem("stranger", 0.99f, null, 0L, 42L),
                results.get(0).withScore(0.8f));
            RelevancyScoring.Plan plan = scoring.plan(parsed(true, 0.5f, 2), inventive, CORPUS);

            RelevancyScoring.Outcome<LexicalQueryService.Hit> outcome = scoring.score(plan,
                hits("a", "b"), 2, RelevancyScoring.HITS, later(), new CancellationToken());

            assertThat(outcome.ranking()).extracting(LexicalQueryService.Hit::chunkId)
                .containsExactly("a");
        }
    }

    @Nested
    @DisplayName("the three kinds of ranking")
    class Candidates {

        @Test
        @DisplayName("a fused result keeps its provenance and takes the model's score")
        void fused() {
            HybridFusion.Result result =
                new HybridFusion.Result("a", 0.03, 7L, List.of("vector", "bm25"));

            HybridFusion.Result rescored = RelevancyScoring.FUSED.rescored(result, 0.8f);

            assertThat(RelevancyScoring.FUSED.idOf(result)).isEqualTo("a");
            assertThat(RelevancyScoring.FUSED.retrievalScore(result)).isEqualTo(0.03f);
            assertThat(rescored.score()).isEqualTo(0.8f, within());
            assertThat(rescored.segmentId()).isEqualTo(7L);
            assertThat(rescored.signals()).containsExactly("vector", "bm25");
        }

        @Test
        @DisplayName("a vector result keeps its segment and node and takes the model's score")
        void vector() {
            QueryResultItem item = new QueryResultItem("doc:1", 0.91f, null, 3L, 9L);

            QueryResultItem rescored = RelevancyScoring.VECTOR.rescored(item, 0.4f);

            assertThat(RelevancyScoring.VECTOR.idOf(item)).isEqualTo("doc:1");
            assertThat(RelevancyScoring.VECTOR.retrievalScore(item)).isEqualTo(0.91f);
            assertThat(rescored.score()).isEqualTo(0.4f);
            assertThat(rescored.segmentId()).isEqualTo(3L);
            assertThat(rescored.nodeId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("a BM25 hit keeps its chunk and takes the model's score")
        void hit() {
            LexicalQueryService.Hit rescored =
                RelevancyScoring.HITS.rescored(new LexicalQueryService.Hit("c", 0.016), 0.6f);

            assertThat(rescored.chunkId()).isEqualTo("c");
            assertThat(rescored.score()).isEqualTo(0.6f, within());
        }
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-6);
    }
}
