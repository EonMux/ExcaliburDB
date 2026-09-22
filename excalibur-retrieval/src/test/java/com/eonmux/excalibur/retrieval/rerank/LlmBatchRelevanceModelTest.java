package com.eonmux.excalibur.retrieval.rerank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
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

class LlmBatchRelevanceModelTest {

    private static final RetrievalQuery QUERY = RetrievalQuery.ofText("who wrote Note G");
    private static final List<String> THREE = List.of("first", "second", "third");

    /**
     * An LLM client that answers from a canned reply instead of over the network.
     *
     * <p>A subclass rather than a mock: the point of these tests is the shape of what the real
     * client returns - sparse, indexed, in whatever order the model emitted - so the seam has to be
     * the real method that returns it.
     */
    private static final class StubClient extends LLMHttpClient {

        private final Function<List<String>, List<RelevancyScore>> reply;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile List<String> lastCandidates = List.of();

        StubClient(Function<List<String>, List<RelevancyScore>> reply) {
            super(LLMClientConfig.defaultLocal());
            this.reply = reply;
        }

        @Override
        public List<RelevancyScore> scoreRelevancyWithCircuitBreaker(
                String query, List<String> candidates, String correlationId) {
            calls.incrementAndGet();
            lastCandidates = List.copyOf(candidates);
            return reply.apply(candidates);
        }
    }

    /** The fused ranking the reranker decorates, and its fallback when the model is unusable. */
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

    private static StubClient clientReturning(RelevancyScore... scores) {
        return new StubClient(candidates -> List.of(scores));
    }

    private static SerializationBudget budget() {
        return new SerializationBudget(10_000, TokenCounter.conservativeEstimate());
    }

    @Test
    @DisplayName("a reordered reply is mapped back by index, not by position")
    void scoresFollowCandidateOrder() {
        // The model answers third, first, second. Applied positionally, every document would take
        // another document's judgement and the ranking would still look plausible.
        StubClient client = clientReturning(
            new RelevancyScore(2, 0.9),
            new RelevancyScore(0, 0.1),
            new RelevancyScore(1, 0.4));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores).containsExactly(0.1, 0.4, 0.9);
    }

    @Test
    @DisplayName("the reply is one score per candidate, whatever the model returned")
    void sizeAlwaysMatchesTheBatch() {
        // Short by one. A reply whose size does not match the batch is rejected wholesale by the
        // reranker, so the adapter must never pass one on.
        StubClient client = clientReturning(new RelevancyScore(0, 0.8));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores).hasSameSizeAs(THREE);
    }

    @Test
    @DisplayName("a candidate the model skipped keeps the neutral score rather than zero")
    void partialReplyKeepsUnscoredCandidates() {
        StubClient client = clientReturning(
            new RelevancyScore(0, 0.2),
            new RelevancyScore(2, 0.9));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores.get(1))
            .as("no judgement was given, so none is invented in either direction")
            .isEqualTo(LlmBatchRelevanceModel.DEFAULT_NEUTRAL_SCORE);
        assertThat(scores).containsExactly(0.2, 0.5, 0.9);
    }

    @Test
    @DisplayName("an unscored candidate outranks one the model actually disliked")
    void neutralOutranksAJudgedRejection() {
        // The reason zero is wrong: it would sink an unjudged document below one the model looked
        // at and rejected, which is a claim about the corpus that nothing supports.
        StubClient client = clientReturning(new RelevancyScore(0, 0.0));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores.get(1)).isGreaterThan(scores.get(0));
    }

    @Test
    @DisplayName("a custom neutral score is used for the candidates that were skipped")
    void neutralScoreIsConfigurable() {
        StubClient client = clientReturning(new RelevancyScore(1, 0.7));

        List<Double> scores = new LlmBatchRelevanceModel(client, 0.25).scoreBatch("q", THREE);

        assertThat(scores).containsExactly(0.25, 0.7, 0.25);
    }

    @Test
    @DisplayName("a score for a candidate that was never sent is dropped")
    void unattributableScoreIsIgnored() {
        // Index 7 in a batch of three. Kept, it would have to be applied to some other candidate.
        StubClient client = clientReturning(
            new RelevancyScore(7, 1.0),
            new RelevancyScore(0, 0.3),
            new RelevancyScore(1, 0.6),
            new RelevancyScore(2, 0.4));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores).containsExactly(0.3, 0.6, 0.4);
    }

    @Test
    @DisplayName("a repeated index keeps the first score, so the order of the reply cannot matter")
    void duplicateIndexIsResolvedDeterministically() {
        StubClient client = clientReturning(
            new RelevancyScore(0, 0.3),
            new RelevancyScore(0, 0.9));

        List<Double> scores = new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(scores.get(0)).isEqualTo(0.3);
    }

    @Test
    @DisplayName("the whole batch reaches the model in one call")
    void oneCallPerBatch() {
        StubClient client = new StubClient(candidates -> {
            List<RelevancyScore> scores = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                scores.add(new RelevancyScore(i, 0.5));
            }
            return scores;
        });

        new LlmBatchRelevanceModel(client).scoreBatch("q", THREE);

        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(client.lastCandidates).isEqualTo(THREE);
    }

    @Test
    @DisplayName("an LLM failure arrives as the kind of failure the reranker degrades on")
    void llmFailureIsTranslated() {
        StubClient client = new StubClient(candidates -> {
            throw new LLMException("endpoint down", LLMException.ErrorType.NETWORK);
        });

        assertThatThrownBy(() -> new LlmBatchRelevanceModel(client).scoreBatch("q", THREE))
            .isInstanceOf(LlmBatchRelevanceModel.ModelUnavailableException.class)
            .isNotInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NETWORK")
            .hasCauseInstanceOf(LLMException.class);
    }

    @Test
    @DisplayName("a failure from deeper in the client stack is translated too")
    void nonLlmFailureIsAlsoTranslated() {
        // An IllegalStateException reaching the reranker unchanged would be read as a defect in
        // the reranker itself and rethrown past the breaker and the fallback, failing the query.
        StubClient client = new StubClient(candidates -> {
            throw new IllegalStateException("connection pool closed");
        });

        assertThatThrownBy(() -> new LlmBatchRelevanceModel(client).scoreBatch("q", THREE))
            .isInstanceOf(LlmBatchRelevanceModel.ModelUnavailableException.class)
            .isNotInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a reply with no usable score is a failed call, not a flat batch")
    void unusableReplyFails() {
        // Every candidate would take the neutral score, so the order would come from the tie break
        // rather than from relevance. Failing hands the caller its fused ranking back instead.
        StubClient client = clientReturning();

        assertThatThrownBy(() -> new LlmBatchRelevanceModel(client).scoreBatch("q", THREE))
            .isInstanceOf(LlmBatchRelevanceModel.ModelUnavailableException.class)
            .hasMessageContaining("3 candidates");
    }

    @Test
    @DisplayName("an empty batch is answered without calling the model")
    void emptyBatchMakesNoCall() {
        StubClient client = clientReturning(new RelevancyScore(0, 1.0));

        assertThat(new LlmBatchRelevanceModel(client).scoreBatch("q", List.of())).isEmpty();
        assertThat(client.calls.get()).isZero();
    }

    @Test
    @DisplayName("a null candidate is refused instead of being sent as the text \"null\"")
    void nullCandidateIsRefused() {
        List<String> withNull = new ArrayList<>(THREE);
        withNull.set(1, null);
        StubClient client = clientReturning(new RelevancyScore(0, 1.0));

        assertThatThrownBy(() -> new LlmBatchRelevanceModel(client).scoreBatch("q", withNull))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidate 1");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    @DisplayName("constructor arguments are validated")
    void argumentsValidated() {
        assertThatThrownBy(() -> new LlmBatchRelevanceModel(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("client");
        assertThatThrownBy(
            () -> new LlmBatchRelevanceModel(clientReturning(), 1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0, 1]");
        assertThatThrownBy(
            () -> new LlmBatchRelevanceModel(clientReturning(), Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("driving a reranker, a partial reply reranks what was judged and keeps the rest")
    void rerankerAppliesAPartialReply() {
        List<Long> ids = List.of(1L, 2L, 3L);
        // Document 3 is the model's favourite; document 2 goes unscored and so keeps the neutral
        // score, which is above document 1's rejection.
        StubClient client = new StubClient(candidates -> List.of(
            new RelevancyScore(0, 0.1),
            new RelevancyScore(2, 0.9)));

        RankedList result = new BatchedLlmReranker(new FusedRanker("hybrid", ids),
            new LlmBatchRelevanceModel(client), id -> "text for " + id,
            new CircuitBreaker(3, Duration.ofSeconds(30), "test"), budget()).rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .containsExactly(3L, 2L, 1L);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("driving a reranker, an LLM failure degrades to the fused ranking and is recorded")
    void rerankerDegradesOnLlmFailure() {
        List<Long> ids = List.of(1L, 2L, 3L);
        CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), "test");
        StubClient client = new StubClient(candidates -> {
            throw new LLMException("upstream 503", LLMException.ErrorType.HTTP_ERROR, 503);
        });

        RankedList result = new BatchedLlmReranker(new FusedRanker("hybrid", ids),
            new LlmBatchRelevanceModel(client), id -> "text for " + id, breaker, budget())
            .rank(QUERY, 3);

        assertThat(result.documents()).extracting(ScoredDocument::documentId)
            .as("still the fused order, not an arbitrary dump")
            .containsExactly(1L, 2L, 3L);
        assertThat(breaker.getFailureCount())
            .as("the breaker has to learn about the outage")
            .isEqualTo(1);
    }
}
