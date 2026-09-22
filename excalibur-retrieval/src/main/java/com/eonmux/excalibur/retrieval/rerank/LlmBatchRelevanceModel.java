package com.eonmux.excalibur.retrieval.rerank;

import java.util.List;
import java.util.UUID;

import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import com.eonmux.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BatchRelevanceModel} a {@link BatchedLlmReranker} actually runs on: an LLM endpoint,
 * reached through {@link LLMHttpClient}.
 *
 * <p>Everything here exists to turn one shape into another. The client answers with a sparse,
 * self-described list - each {@link RelevancyScore} carries the candidate index it refers to, and
 * a model is free to omit candidates or return them in any order. The reranker needs a dense list,
 * one score per candidate, in the order it sent them. Getting that translation wrong is not a
 * visible failure: positional mapping of a short or reordered reply attributes the model's
 * judgement of one document to another, and the result still looks like a ranking.
 *
 * <h2>Candidates the model did not score</h2>
 * They keep {@link #DEFAULT_NEUTRAL_SCORE}, not zero, and are never dropped. Dropping a candidate
 * would change the size of the reply and cost the reranker its whole batch - it rejects a reply
 * whose size does not match. Zero is worse than that: it is the bottom of the scale the client
 * clamps to, so it asserts the document is irrelevant, which is a claim the model never made about
 * a candidate it did not answer for. The neutral midpoint says only that there is no opinion here,
 * leaving the document above everything the model actually disliked and below everything it liked.
 *
 * <p>A partial reply is ordinary rather than exceptional, which is why this is worth the care: the
 * client splits a batch that exceeds its token limit and then truncates at
 * {@code maxPromptsPerQuery}, so the tail of a large batch comes back unscored by design.
 *
 * <h2>Failure</h2>
 * Every failure of the call arrives as {@link ModelUnavailableException}, which is a plain
 * {@link RuntimeException}. That is what {@link BatchedLlmReranker} expects: it records such a
 * failure against its circuit breaker and returns the fused ranking unchanged. An
 * {@link IllegalStateException} would take the opposite path - the reranker treats one as a defect
 * in itself and rethrows it past both the breaker and the fallback - so an LLM outage would become
 * an outage of search. Nothing thrown from inside the call is allowed to reach the reranker with
 * that type.
 *
 * <h2>One call per batch</h2>
 * One {@link #scoreBatch} is one call to the client. The client may still split internally when its
 * own token estimate exceeds {@code maxBatchTokens}, but it splits by token cost, never per
 * candidate, and {@link BatchedLlmReranker} has already bounded the batch by its own context
 * budget, so in practice the split does not happen.
 *
 * <h2>Thread Safety</h2>
 * Immutable and thread-safe. {@link LLMHttpClient} is thread-safe, and the alignment of a reply
 * happens entirely in locals, so concurrent queries share nothing but the client.
 */
public final class LlmBatchRelevanceModel implements BatchRelevanceModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmBatchRelevanceModel.class);

    /**
     * The score a candidate keeps when the model did not answer for it.
     *
     * <p>The midpoint of the [0, 1] range the client clamps every score to, so it expresses "no
     * judgement" on the model's own scale. Any other value is an opinion the model did not give:
     * lower sinks unjudged candidates beneath ones the model disliked, higher floats them above
     * ones it liked.
     */
    public static final double DEFAULT_NEUTRAL_SCORE = 0.5;

    private final LLMHttpClient client;
    private final double neutralScore;

    /**
     * A model over {@code client} using the neutral midpoint for unscored candidates.
     *
     * @param client the LLM client to score through
     * @throws IllegalArgumentException if {@code client} is null
     */
    public LlmBatchRelevanceModel(LLMHttpClient client) {
        this(client, DEFAULT_NEUTRAL_SCORE);
    }

    /**
     * A model with an explicit neutral score.
     *
     * @param client       the LLM client to score through
     * @param neutralScore what an unscored candidate keeps; see {@link #DEFAULT_NEUTRAL_SCORE}
     * @throws IllegalArgumentException if {@code client} is null, or {@code neutralScore} is not a
     *                                  number in [0, 1] - outside the scale the client clamps
     *                                  scores to, it would sort every unscored candidate above or
     *                                  below the whole batch regardless of what the model said
     */
    public LlmBatchRelevanceModel(LLMHttpClient client, double neutralScore) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (Double.isNaN(neutralScore) || neutralScore < 0.0 || neutralScore > 1.0) {
            throw new IllegalArgumentException(
                "neutralScore must be in [0, 1], got " + neutralScore);
        }
        this.client = client;
        this.neutralScore = neutralScore;
    }

    /** What an unscored candidate keeps. */
    public double neutralScore() {
        return neutralScore;
    }

    /**
     * Scores the whole batch in one call and returns the reply as a dense list.
     *
     * @param query      the query text
     * @param candidates the candidate texts, in ranked order
     * @return one score per candidate, in the same order and of the same size as
     *         {@code candidates}; empty when {@code candidates} is empty
     * @throws IllegalArgumentException if {@code query} is blank, {@code candidates} is null, or
     *                                  any candidate is null - a null candidate reaches the prompt
     *                                  as the text "null" and the model scores a phantom document
     * @throws ModelUnavailableException if the call failed or produced nothing usable; the caller
     *                                   records this against its circuit breaker and keeps the
     *                                   ranking it already had
     */
    @Override
    public List<Double> scoreBatch(String query, List<String> candidates) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null; use an empty list");
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == null) {
                throw new IllegalArgumentException("candidate " + i + " is null");
            }
        }
        if (candidates.isEmpty()) {
            // Nothing to judge. The client rejects an empty batch outright, and a rejected call
            // would be recorded as a model failure by the caller.
            return List.of();
        }

        // A fresh id per call, so a rerank that came back partial or failed here can be found in
        // the client's own request logs. The interface carries no per-call context to reuse.
        String correlationId = UUID.randomUUID().toString();
        List<RelevancyScore> reply;
        try {
            reply = client.scoreRelevancyWithCircuitBreaker(query, candidates, correlationId);
        } catch (LLMException e) {
            throw new ModelUnavailableException("relevance call " + correlationId + " failed: "
                + e.getErrorType() + " - " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Anything else the client stack throws is still the model being unusable. Left
            // untranslated, an IllegalStateException from inside it would be read by the reranker
            // as a defect of its own and would fail the query instead of degrading it.
            throw new ModelUnavailableException(
                "relevance call " + correlationId + " failed: " + e, e);
        }
        return align(reply, candidates.size(), correlationId);
    }

    /**
     * Maps a sparse reply onto one score per candidate.
     *
     * <p>By index, never by position: the reply may be short, reordered, or carry an index the
     * batch never had.
     *
     * @param reply         what the client returned
     * @param size          how many candidates were sent
     * @param correlationId the call's id, for the log line that explains a partial reply
     * @return one score per candidate, in the order they were sent
     * @throws ModelUnavailableException if nothing in the reply could be attributed to a candidate
     */
    private List<Double> align(List<RelevancyScore> reply, int size, String correlationId) {
        Double[] scores = new Double[size];
        int mapped = 0;
        int unattributable = 0;
        if (reply != null) {
            for (RelevancyScore score : reply) {
                if (score == null || score.getIndex() < 0 || score.getIndex() >= size) {
                    // A position the batch never had. Applied anyway it would score whichever
                    // candidate happened to sit there.
                    unattributable++;
                    continue;
                }
                if (scores[score.getIndex()] != null) {
                    // Two scores for one candidate. The first wins, so the result does not depend
                    // on the order the model happened to emit them in.
                    unattributable++;
                    continue;
                }
                scores[score.getIndex()] = score.getScore();
                mapped++;
            }
        }

        if (mapped == 0) {
            // Every candidate would take the neutral score, leaving the batch flat and its order
            // decided by the tie break rather than by relevance - strictly worse than the fused
            // ranking the caller falls back to. A model that finds nothing relevant says so with
            // low scores; a reply with no usable score is a failed call wearing a valid shape.
            throw new ModelUnavailableException("relevance call " + correlationId
                + " returned no score that could be attributed to any of " + size
                + " candidates");
        }

        int unscored = 0;
        for (int i = 0; i < size; i++) {
            if (scores[i] == null) {
                scores[i] = neutralScore;
                unscored++;
            }
        }
        if (unscored > 0 || unattributable > 0) {
            LOGGER.warn("relevance call {}: {} of {} candidates came back unscored and keep the "
                + "neutral score {}; {} scores could not be attributed",
                correlationId, unscored, size, neutralScore, unattributable);
        }
        return List.of(scores);
    }

    /**
     * A rerank call that produced no usable judgement.
     *
     * <p>Deliberately not an {@link IllegalStateException}. {@link BatchedLlmReranker} reserves
     * that type for its own broken invariants and rethrows it past the circuit breaker and past
     * the fallback, so using it here would turn every model outage into a failed query.
     */
    public static final class ModelUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * A call that came back with nothing usable in it.
         *
         * @param message what was asked for and what came back, naming the call
         */
        ModelUnavailableException(String message) {
            super(message);
        }

        /**
         * A call that failed outright.
         *
         * @param message what was asked for and how it failed, naming the call
         * @param cause   the client failure underneath
         */
        ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
