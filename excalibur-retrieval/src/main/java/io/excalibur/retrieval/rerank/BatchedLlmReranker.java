package io.excalibur.retrieval.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.excalibur.llm.client.CircuitBreaker;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;
import io.excalibur.retrieval.serialize.SerializationBudget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reranks with a model, in exactly one call per query, under a hard token cap.
 *
 * <p>It is a {@link Ranker} like any other, so its output feeds fusion alongside BM25 and the dense
 * signal rather than overriding them. A model's judgement is one more opinion — a well-informed one
 * — and letting it replace the ranking rather than contribute to it throws away the signals that
 * are right when the model is wrong.
 *
 * <h2>One call per query, enforced</h2>
 * The batch invariant is checked in code, not left to convention. A per-candidate loop is the
 * natural thing to write and it is catastrophic here: the query and instructions are the bulk of
 * each prompt, so scoring fifty candidates individually costs about fifty prefills of the same
 * prefix, plus fifty round trips. That regression is invisible in tests and only shows up on the
 * bill, so {@link #rank} counts its own model invocations and fails loudly if there is ever more
 * than one.
 *
 * <h2>Failure</h2>
 * When the breaker is open or the call fails, this returns <em>the delegate's ranking, unchanged</em>
 * — still fused, still ordered. Not an unranked dump, and not nothing. Degrading from "the model's
 * opinion of a good ranking" to "a good ranking" is a real fallback; degrading to an arbitrary
 * order is an outage that returns HTTP 200.
 */
public final class BatchedLlmReranker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchedLlmReranker.class);

    /**
     * Most candidates to send in one batch.
     *
     * <p>Capped for three reasons that all bite at once: the prompt has to fit, a model's ability
     * to keep a long list ordered degrades well before the context runs out, and the whole batch is
     * one unit of failure — a batch of five hundred that times out loses everything.
     */
    public static final int DEFAULT_MAX_CANDIDATES = 50;

    private final Ranker delegate;
    private final BatchRelevanceModel model;
    private final CandidateText candidateText;
    private final CircuitBreaker breaker;
    private final SerializationBudget contextBudget;
    private final int maxCandidates;
    private final String name;

    public BatchedLlmReranker(Ranker delegate, BatchRelevanceModel model,
                              CandidateText candidateText, CircuitBreaker breaker,
                              SerializationBudget contextBudget) {
        this(delegate, model, candidateText, breaker, contextBudget, DEFAULT_MAX_CANDIDATES);
    }

    public BatchedLlmReranker(Ranker delegate, BatchRelevanceModel model,
                              CandidateText candidateText, CircuitBreaker breaker,
                              SerializationBudget contextBudget, int maxCandidates) {
        if (delegate == null || model == null || candidateText == null
                || breaker == null || contextBudget == null) {
            throw new IllegalArgumentException(
                "delegate, model, candidateText, breaker and contextBudget are all required");
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException(
                "maxCandidates must be at least 1, got " + maxCandidates);
        }
        this.delegate = delegate;
        this.model = model;
        this.candidateText = candidateText;
        this.breaker = breaker;
        this.contextBudget = contextBudget;
        this.maxCandidates = maxCandidates;
        this.name = delegate.name() + "+llm";
    }

    @Override
    public String name() {
        return name;
    }

    /** The candidate cap in force. */
    public int maxCandidates() {
        return maxCandidates;
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (limit <= 0) {
            return RankedList.empty(name);
        }

        int fetch = Math.min(maxCandidates, Math.max(limit, maxCandidates));
        RankedList coarse = delegate.rank(query, fetch);
        if (coarse.isEmpty()) {
            return RankedList.empty(name);
        }

        String queryText = query.textIfPresent().orElse(null);
        if (queryText == null) {
            LOGGER.debug("{}: query carries no text; keeping the fused ranking", name);
            return relabel(coarse, limit);
        }
        if (!breaker.allowRequest()) {
            LOGGER.debug("{}: circuit breaker is {}; keeping the fused ranking",
                name, breaker.getState());
            return relabel(coarse, limit);
        }

        Batch batch = buildBatch(coarse);
        if (batch.candidates.isEmpty()) {
            LOGGER.debug("{}: no candidate carried text; keeping the fused ranking", name);
            return relabel(coarse, limit);
        }

        // The model is reached only through a guard that permits one call. A future edit that
        // loops per candidate fails here immediately instead of silently costing fifty prefills.
        SingleCallGuard guard = new SingleCallGuard(model, name);
        List<Double> scores;
        try {
            scores = guard.scoreBatch(queryText, batch.candidates);
            breaker.recordSuccess();
        } catch (IllegalStateException e) {
            // A broken invariant is a defect in this class, not a model outage. It must not be
            // recorded against the breaker or swallowed into the fallback.
            throw e;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            LOGGER.warn("{}: rerank call failed; keeping the fused ranking", name, e);
            return relabel(coarse, limit);
        }
        if (scores == null || scores.size() != batch.candidates.size()) {
            LOGGER.warn("{}: model returned {} scores for {} candidates; keeping the fused ranking",
                name, scores == null ? "no" : scores.size(), batch.candidates.size());
            return relabel(coarse, limit);
        }

        return applyScores(coarse, batch, scores, limit);
    }

    /**
     * Selects the candidates that fit, in ranked order, under both caps.
     *
     * <p>Measures each candidate's real token cost as it goes. Sending as many as the count cap
     * allows and hoping they fit is how a rerank call fails on the prompt rather than on the model.
     */
    private Batch buildBatch(RankedList coarse) {
        List<String> candidates = new ArrayList<>();
        List<Long> documentIds = new ArrayList<>();
        int tokens = 0;

        for (ScoredDocument document : coarse.documents()) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            String text = candidateText.textOf(document.documentId());
            if (text == null || text.isBlank()) {
                // Judged on nothing is not the same as judged irrelevant. Left out of the batch,
                // it keeps its fused rank below whatever the model did rank.
                continue;
            }
            int cost = contextBudget.counter().count(text);
            if (tokens + cost > contextBudget.maxTokens()) {
                break;
            }
            tokens += cost;
            candidates.add(text);
            documentIds.add(document.documentId());
        }
        return new Batch(candidates, documentIds, tokens);
    }

    /**
     * Rebuilds the ranking from the model's scores.
     *
     * <p>Candidates that were not sent keep their place beneath those that were, rather than being
     * dropped. They were excluded for want of budget or text, which says nothing about relevance.
     */
    private RankedList applyScores(RankedList coarse, Batch batch, List<Double> scores, int limit) {
        List<ScoredDocument> reranked = new ArrayList<>(coarse.size());
        double lowest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < batch.documentIds.size(); i++) {
            double score = scores.get(i);
            reranked.add(new ScoredDocument(batch.documentIds.get(i), score));
            lowest = Math.min(lowest, score);
        }

        if (batch.documentIds.size() < coarse.size()) {
            java.util.Set<Long> judged = new java.util.HashSet<>(batch.documentIds);
            // Placed strictly below every judged candidate, preserving their fused order among
            // themselves via a small decreasing offset.
            double floor = Double.isInfinite(lowest) ? 0.0 : lowest;
            int rank = 0;
            for (ScoredDocument document : coarse.documents()) {
                if (judged.contains(document.documentId())) {
                    continue;
                }
                reranked.add(new ScoredDocument(document.documentId(),
                    floor - 1.0 - (rank++ * 1e-6)));
            }
        }
        return RankedList.of(name, reranked, limit);
    }

    /** The delegate's ranking under this ranker's name, which is the fallback in every failure. */
    private RankedList relabel(RankedList coarse, int limit) {
        return RankedList.of(name, coarse.documents(), limit);
    }

    private record Batch(List<String> candidates, List<Long> documentIds, int tokens) {
    }

    /**
     * Lets the model be called exactly once, and turns a second call into an immediate failure.
     *
     * <p>This is the batch invariant expressed as code. Asserting it after the fact cannot catch
     * the violation it exists to prevent — a per-candidate loop still makes every call before
     * anything gets to check the total — so the guard has to sit between the reranker and the
     * model, where it can refuse the second one.
     */
    static final class SingleCallGuard implements BatchRelevanceModel {

        private final BatchRelevanceModel delegate;
        private final String rankerName;
        private final AtomicInteger calls = new AtomicInteger();

        SingleCallGuard(BatchRelevanceModel delegate, String rankerName) {
            this.delegate = delegate;
            this.rankerName = rankerName;
        }

        @Override
        public List<Double> scoreBatch(String query, List<String> candidates) {
            int call = calls.incrementAndGet();
            if (call > 1) {
                throw new IllegalStateException(rankerName
                    + " attempted model call " + call + " for a single query. The batch invariant"
                    + " is one call per query: the query and instructions dominate each prompt, so"
                    + " scoring candidates individually costs one prefill each.");
            }
            return delegate.scoreBatch(query, candidates);
        }
    }
}
