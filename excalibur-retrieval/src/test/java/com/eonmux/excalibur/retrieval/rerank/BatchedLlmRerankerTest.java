package com.eonmux.excalibur.retrieval.rerank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TokenCounter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchedLlmRerankerTest {

    private static final RetrievalQuery QUERY = RetrievalQuery.ofText("who wrote Note G");

    /** The fused hybrid ranker this decorates, and the fallback whenever the model is unusable. */
    private record FusedRanker(String name, List<Long> ids) implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            List<ScoredDocument> documents = new ArrayList<>();
            double score = ids.size();
            for (long id : ids) {
                documents.add(new ScoredDocument(id, score--));
            }
            return RankedList.of(name, documents, limit);
        }
    }

    private static CandidateText textFor(List<Long> ids) {
        Map<Long, String> texts = new HashMap<>();
        for (long id : ids) {
            texts.put(id, "candidate text for document " + id);
        }
        return texts::get;
    }

    private static SerializationBudget budget(int maxTokens) {
        return new SerializationBudget(maxTokens, TokenCounter.conservativeEstimate());
    }

    private static CircuitBreaker breaker() {
        return new CircuitBreaker(3, Duration.ofSeconds(30), "test-rerank");
    }

    @Test
    @DisplayName("the model reorders the fused candidates")
    void modelReorders() {
        List<Long> ids = List.of(1L, 2L, 3L);
        // The model likes the last candidate best.
        BatchRelevanceModel model = (query, candidates) -> List.of(0.1, 0.2, 0.9);

        BatchedLlmReranker reranker = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, textFor(ids), breaker(), budget(10_000));

        RankedList result = reranker.rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(3L, 2L, 1L);
        assertThat(result.rankerName()).isEqualTo("hybrid+llm");
    }

    @Test
    @DisplayName("the whole batch is scored in exactly one model call")
    void oneCallPerQuery() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            assertThat(candidates)
                .as("every candidate must arrive in the same call")
                .hasSize(ids.size());
            return candidates.stream().map(text -> 0.5).toList();
        };

        new BatchedLlmReranker(new FusedRanker("hybrid", ids), model, textFor(ids),
            breaker(), budget(10_000)).rank(QUERY, 5);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second model call for one query is refused outright")
    void batchInvariantIsEnforced() {
        // The invariant has to be enforced between the reranker and the model. Checking a counter
        // afterwards cannot catch a per-candidate loop -- by then every call has been made. So the
        // guard itself is what gets tested: it must refuse the second call.
        AtomicInteger delegateCalls = new AtomicInteger();
        BatchRelevanceModel underlying = (query, candidates) -> {
            delegateCalls.incrementAndGet();
            return List.of(0.5);
        };
        BatchedLlmReranker.SingleCallGuard guard =
            new BatchedLlmReranker.SingleCallGuard(underlying, "hybrid+llm");

        assertThat(guard.scoreBatch("q", List.of("a"))).containsExactly(0.5);

        assertThatThrownBy(() -> guard.scoreBatch("q", List.of("b")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("one call per query")
            .hasMessageContaining("hybrid+llm");

        assertThat(delegateCalls.get())
            .as("the refused call must never reach the model")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("an open circuit keeps the fused ranking rather than dumping unranked results")
    void openCircuitFallsBackToFusedRanking() {
        List<Long> ids = List.of(1L, 2L, 3L);
        CircuitBreaker open = new CircuitBreaker(1, Duration.ofSeconds(30), "already-open");
        open.recordFailure();

        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            return List.of(0.9, 0.5, 0.1);
        };

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, textFor(ids), open, budget(10_000))
            .rank(QUERY, 3);

        assertThat(calls.get()).as("the model must not be called at all").isZero();
        assertThat(result.documents())
            .as("still ordered by the fused ranking, not an arbitrary dump")
            .extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a failing model degrades to the fused ranking and trips the breaker")
    void failureFallsBackAndRecordsFailure() {
        List<Long> ids = List.of(1L, 2L, 3L);
        CircuitBreaker breaker = breaker();
        BatchRelevanceModel failing = (query, candidates) -> {
            throw new RuntimeException("model unavailable");
        };

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), failing, textFor(ids), breaker, budget(10_000))
            .rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
        assertThat(breaker.getFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong-sized response is rejected instead of being mapped onto the wrong documents")
    void mismatchedResponseSizeFallsBack() {
        List<Long> ids = List.of(1L, 2L, 3L);
        // Two scores for three candidates: applying them positionally would attribute the model's
        // judgement of one document to another.
        BatchRelevanceModel model = (query, candidates) -> List.of(0.9, 0.1);

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, textFor(ids), breaker(), budget(10_000))
            .rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("the candidate cap bounds the batch")
    void candidateCapIsEnforced() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            ids.add(id);
        }
        AtomicInteger batchSize = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            batchSize.set(candidates.size());
            return candidates.stream().map(text -> 0.5).toList();
        };

        new BatchedLlmReranker(new FusedRanker("hybrid", ids), model, textFor(ids),
            breaker(), budget(1_000_000), 50).rank(QUERY, 100);

        assertThat(batchSize.get()).isEqualTo(50);
    }

    @Test
    @DisplayName("the context token cap bounds the batch even under the candidate cap")
    void contextBudgetBoundsTheBatch() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            ids.add(id);
        }
        AtomicInteger batchSize = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            batchSize.set(candidates.size());
            return candidates.stream().map(text -> 0.5).toList();
        };

        // Far below what fifty candidates cost, so the token cap binds first.
        new BatchedLlmReranker(new FusedRanker("hybrid", ids), model, textFor(ids),
            breaker(), budget(60), 50).rank(QUERY, 50);

        assertThat(batchSize.get()).isPositive().isLessThan(50);
    }

    @Test
    @DisplayName("a candidate with no text keeps its place instead of being dropped")
    void untextedCandidateSurvives() {
        List<Long> ids = List.of(1L, 2L, 3L);
        // Document 2 has no text: it cannot be judged, which is not the same as being irrelevant.
        CandidateText partial = id -> id == 2L ? null : "text for " + id;
        BatchRelevanceModel model = (query, candidates) -> List.of(0.1, 0.9);

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, partial, breaker(), budget(10_000))
            .rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .as("judged candidates first, the unjudged one kept beneath them")
            .containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("no candidate carrying text keeps the fused ranking without calling the model")
    void noTextAtAllFallsBack() {
        List<Long> ids = List.of(1L, 2L);
        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            return List.of();
        };

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, id -> null, breaker(), budget(10_000))
            .rank(QUERY, 2);

        assertThat(calls.get()).isZero();
        assertThat(result.documents()).hasSize(2);
    }

    @Test
    @DisplayName("a query with no text keeps the fused ranking")
    void noQueryTextFallsBack() {
        List<Long> ids = List.of(1L, 2L);
        AtomicInteger calls = new AtomicInteger();
        BatchRelevanceModel model = (query, candidates) -> {
            calls.incrementAndGet();
            return List.of(0.1, 0.9);
        };

        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", ids), model, textFor(ids), breaker(), budget(10_000))
            .rank(RetrievalQuery.ofEmbedding(new float[] { 1f, 2f }), 2);

        assertThat(calls.get()).isZero();
        assertThat(result.documents()).hasSize(2);
    }

    @Test
    @DisplayName("an empty delegate result reranks to nothing")
    void emptyDelegate() {
        RankedList result = new BatchedLlmReranker(
            new FusedRanker("hybrid", List.of()), (query, candidates) -> List.of(),
            id -> "text", breaker(), budget(10_000)).rank(QUERY, 10);

        assertThat(result.documents()).isEmpty();
    }

    @Test
    @DisplayName("constructor arguments are all required")
    void argumentsValidated() {
        FusedRanker delegate = new FusedRanker("hybrid", List.of(1L));
        BatchRelevanceModel model = (query, candidates) -> List.of(0.5);

        assertThatThrownBy(() -> new BatchedLlmReranker(
                null, model, id -> "t", breaker(), budget(100)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchedLlmReranker(
                delegate, model, id -> "t", breaker(), budget(100), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1");
    }
}
