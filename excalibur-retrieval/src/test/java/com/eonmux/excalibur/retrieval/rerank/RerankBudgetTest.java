package com.eonmux.excalibur.retrieval.rerank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.CircuitBreakerState;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TokenCounter;
import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the reranking stages spend - and stop spending - a query's time. */
class RerankBudgetTest {

    private static final List<Long> IDS = List.of(1L, 2L, 3L);
    private static final RetrievalQuery TEXT = RetrievalQuery.ofText("who wrote Note G");

    /** Ranks {@link #IDS} in order, recording the budget it was handed. */
    private static final class FusedRanker implements Ranker {
        private QueryBudget seen;

        @Override
        public String name() {
            return "hybrid";
        }

        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            List<ScoredDocument> documents = new ArrayList<>();
            double score = IDS.size();
            for (long id : IDS) {
                documents.add(new ScoredDocument(id, score--));
            }
            return RankedList.of(name(), documents, limit);
        }

        @Override
        public RankedList rank(RetrievalQuery query, int limit, QueryBudget budget) {
            seen = budget;
            return rank(query, limit);
        }
    }

    private static CandidateText texts() {
        return id -> "candidate text for document " + id;
    }

    private static BatchedLlmReranker reranker(BatchRelevanceModel model, CircuitBreaker breaker) {
        return new BatchedLlmReranker(new FusedRanker(), model, texts(), breaker,
            new SerializationBudget(10_000, TokenCounter.conservativeEstimate()));
    }

    private static CircuitBreaker breaker() {
        return new CircuitBreaker(2, Duration.ofSeconds(30), "test-rerank");
    }

    /** A model that answers only once it is interrupted, noting that it was. */
    private static BatchRelevanceModel hanging(CountDownLatch started, AtomicBoolean interrupted) {
        return (query, candidates) -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return candidates.stream().map(text -> 0.5).toList();
        };
    }

    @Test
    @DisplayName("a model slower than the deadline is abandoned, and the fused order stands")
    void slowModelIsAbandonedAtTheDeadline() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        QueryBudget budget = QueryBudget.ofMillis(150);

        long begun = System.nanoTime();
        RankedList result = reranker(hanging(started, interrupted), breaker())
            .rank(TEXT, 3, budget);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

        assertThat(tookMillis).as("bounded by the budget, not the model").isLessThan(5_000);
        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
        assertThat(result.rankerName()).isEqualTo("hybrid+llm");
        assertThat(budget.timedOut()).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitTrue(interrupted);
    }

    @Test
    @DisplayName("a caller's short deadline is not held against the model's breaker")
    void deadlinesDoNotTripTheBreaker() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 3; i++) {
            reranker(hanging(new CountDownLatch(1), new AtomicBoolean()), breaker)
                .rank(TEXT, 3, QueryBudget.ofMillis(20));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("once the deadline has passed the model is not called at all")
    void expiredBudgetSkipsTheModel() {
        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            return List.of(0.9, 0.5, 0.1);
        };
        QueryBudget budget = QueryBudget.until(System.nanoTime() - 1);

        RankedList result = reranker(model, breaker()).rank(TEXT, 3, budget);

        assertThat(calls.get()).isZero();
        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
        assertThat(budget.timedOut()).isTrue();
    }

    @Test
    @DisplayName("a model inside the deadline reranks as it would with none")
    void modelInsideTheDeadlineReranks() {
        BatchRelevanceModel model = (query, candidates) -> List.of(0.1, 0.2, 0.9);
        QueryBudget budget = QueryBudget.ofMillis(10_000);

        RankedList result = reranker(model, breaker()).rank(TEXT, 3, budget);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(3L, 2L, 1L);
        assertThat(budget.timedOut()).isFalse();
    }

    @Test
    @DisplayName("cancelling the query interrupts the model call in flight and ends the query")
    void cancellationInterruptsTheCallInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        QueryBudget budget = QueryBudget.ofMillis(60_000);
        BatchedLlmReranker reranker = reranker(hanging(started, interrupted), breaker());

        CompletableFuture<RankedList> ranking =
            CompletableFuture.supplyAsync(() -> reranker.rank(TEXT, 3, budget));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        budget.cancel("withdrawn");

        assertThatThrownBy(() -> ranking.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(CancellationException.class)
            .hasMessageContaining("withdrawn");
        awaitTrue(interrupted);
    }

    @Test
    @DisplayName("a query cancelled before the call never reaches the model")
    void cancelledBeforeTheCall() {
        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            return List.of(0.9, 0.5, 0.1);
        };
        QueryBudget budget = QueryBudget.ofMillis(60_000);
        budget.cancel("withdrawn");

        assertThatThrownBy(() -> reranker(model, breaker()).rank(TEXT, 3, budget))
            .isInstanceOf(CancellationException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("the exact rerank hands its budget down, and is skipped once it has run out")
    void exactRerankHonoursTheDeadline() {
        FusedRanker coarse = new FusedRanker();
        // In full precision document 3 is nearest, so a rerank would put it first.
        FullPrecisionReranker reranker = new FullPrecisionReranker(coarse,
            Map.of(1L, new float[] {10f}, 2L, new float[] {5f}, 3L, new float[] {1f})::get,
            L2Distance.getInstance());
        RetrievalQuery embedded = RetrievalQuery.ofEmbedding(new float[] {0f});

        QueryBudget live = QueryBudget.ofMillis(10_000);
        assertThat(reranker.rank(embedded, 3, live).documents())
            .extracting(ScoredDocument::documentId).containsExactly(3L, 2L, 1L);
        assertThat(coarse.seen).isSameAs(live);

        QueryBudget expired = QueryBudget.until(System.nanoTime() - 1);
        RankedList skipped = reranker.rank(embedded, 3, expired);
        assertThat(skipped.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
        assertThat(skipped.rankerName()).isEqualTo("hybrid+exact");
        assertThat(expired.timedOut()).isTrue();

        QueryBudget cancelled = QueryBudget.ofMillis(10_000);
        cancelled.cancel("withdrawn");
        assertThatThrownBy(() -> reranker.rank(embedded, 3, cancelled))
            .isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("the embedding gate hands the budget to whichever chain it picks")
    void gatePassesTheBudgetOn() {
        FusedRanker embedded = new FusedRanker();
        FusedRanker textOnly = new FusedRanker();
        EmbeddingGatedRanker gate = new EmbeddingGatedRanker(embedded, textOnly);
        QueryBudget budget = QueryBudget.ofMillis(10_000);

        gate.rank(RetrievalQuery.ofEmbedding(new float[] {0f}), 3, budget);
        gate.rank(TEXT, 3, budget);

        assertThat(embedded.seen).isSameAs(budget);
        assertThat(textOnly.seen).isSameAs(budget);
    }

    private static void awaitTrue(AtomicBoolean flag) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flag.get() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(flag.get()).as("the model call was interrupted").isTrue();
    }
}
