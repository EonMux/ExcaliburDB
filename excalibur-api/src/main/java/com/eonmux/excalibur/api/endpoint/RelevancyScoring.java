package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.ChunkTextResolver;
import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.ResultAggregator;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * LLM relevancy scoring over a ranking the query pipeline did not produce.
 *
 * <h2>Why it runs here</h2>
 * <p>Relevancy scoring is the last stage of retrieval: whatever ranking the signals agreed on,
 * a chat model reads each candidate's text against the question and reorders it. Inside the
 * vector pipeline it is Stage 5, and a node answering from vector search alone keeps it there.
 * Two answers are not the pipeline's to score. A BM25 answer never reaches the pipeline at all,
 * so a node with a chat model and no embeddings - a deployment this route is built for - would
 * never score. And a fused answer is only final after fusion: scoring the vector half first
 * judges half the candidates and then lets reciprocal rank undo the judgement, since fusion
 * reads position and not the relevancy the model gave. So those two are scored here, after
 * their ranking is final.</p>
 *
 * <h2>The same stage, not a second one</h2>
 * <p>The scorer is the pipeline's own instance - the same chat model, the same query timeout
 * ({@code excalibur.query.llm-timeout-ms}) and the same circuit breaker, so a breaker that has
 * opened on one route is open on all of them. The contract is Stage 5's: the candidate set is
 * {@code oversampling_factor} times the depth the page needs, so {@code llm_threshold} chooses
 * from more than a page; a candidate the model judged carries its relevancy as its score and a
 * candidate it could not judge keeps its retrieval score; the list is ordered by score; and a
 * scorer that fails leaves the retrieval ranking in place, reported as degraded rather than as
 * a failed query.</p>
 *
 * <h2>Deadline</h2>
 * <p>The call is bounded by the request's own deadline, not the scorer's client timeout, which
 * is sized for a model rather than for a caller holding a socket open. Running out of time is
 * what it is on the pipeline: the retrieval ranking is returned, cut to the depth the page
 * needs, and the answer says it timed out.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: it holds only its collaborators, and each request brings its own plan.</p>
 *
 * @author ExcaliburDB
 */
final class RelevancyScoring {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelevancyScoring.class);

    /**
     * The share of what is left of a request's budget that retrieval leaves for scoring, when
     * both signals retrieve before the model is asked.
     *
     * <p>Stage 5's own margin - the pipeline holds back the same share of its search for the
     * model when it scores. A fused answer is scored after both signals have returned, so the
     * endpoint holds it back instead: the vector pipeline and the wait on BM25 are given the
     * rest, and the model gets a realistic chance to answer inside the request's deadline.</p>
     */
    static final double SCORING_SHARE_HELD_BACK = 0.20;

    private final ExecutorService executor;

    /**
     * Longest candidate text handed to the model, past which it is clipped.
     *
     * <p>The node's per-result text bound, {@code excalibur.api.max-result-text-chars}: a
     * candidate is judged on the same passage a caller would be shown for it, and a scoring
     * prompt is not allowed to grow with the size of whatever chunk happens to rank.</p>
     */
    private final int maxCandidateChars;

    /**
     * Creates the stage.
     *
     * @param executor          Runs the scoring call, so the request's deadline can bound it
     * @param maxCandidateChars Longest candidate text handed to the model
     * @throws NullPointerException     if executor is null
     * @throws IllegalArgumentException if maxCandidateChars is not positive
     */
    RelevancyScoring(ExecutorService executor, int maxCandidateChars) {
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        if (maxCandidateChars <= 0) {
            throw new IllegalArgumentException(
                "maxCandidateChars must be positive: " + maxCandidateChars);
        }
        this.maxCandidateChars = maxCandidateChars;
    }

    /**
     * Decides whether one request's answer will be scored.
     *
     * <p>Three things must all hold: the request asked for it, which it does unless it sent
     * {@code enable_llm_scoring: false}; the node has a chat model to ask; and the node keeps the
     * chunk text the model is asked to read. Decided before the search, because it decides how
     * deep the search ranks.</p>
     *
     * @param parsed    The validated request
     * @param scorer    The pipeline's relevancy scorer, or null on a node with no chat model
     * @param chunkText Reads a chunk's text, or null on a node that keeps no chunk storage
     * @return The plan, which {@link Plan#runs()} says whether to carry out
     */
    Plan plan(QueryRequestParser.ParsedQuery parsed, QueryPipeline.LlmScorer scorer,
              Function<String, String> chunkText) {
        if (!parsed.enableLlmScoring() || scorer == null || chunkText == null) {
            return Plan.NONE;
        }
        return new Plan(scorer, ChunkTextResolver.over(chunkText, maxCandidateChars),
            parsed.query(), parsed.llmThreshold(), parsed.oversamplingFactor());
    }

    /**
     * How one request's answer is to be scored.
     *
     * @param scorer             The relevancy scorer, or null when nothing is scored
     * @param text               Reads each candidate's text, or null when nothing is scored
     * @param query              The query the candidates are judged against
     * @param threshold          The relevancy a judged candidate must reach to be kept
     * @param oversamplingFactor How many times the page's depth is put in front of the model
     */
    record Plan(QueryPipeline.LlmScorer scorer, ChunkTextResolver text, String query,
                float threshold, int oversamplingFactor) {

        /** The plan of a request whose answer is not scored. */
        static final Plan NONE = new Plan(null, null, null, 0.0f, 1);

        /**
         * Whether the answer will be scored.
         *
         * @return true when a scorer will be asked
         */
        boolean runs() {
            return scorer != null;
        }

        /**
         * How deep retrieval must rank for this request's page.
         *
         * <p>The depth itself when nothing is scored. When the answer is scored, that depth
         * times {@code oversampling_factor} - Stage 5's candidate set - so the threshold filter
         * chooses from more than a page and a page is still full after it. The widening is
         * held to {@link QueryRequest#MAX_TOP_K}, the most the pipeline accepts; it never
         * narrows below the depth itself.</p>
         *
         * @param depth The depth the page needs, being offset plus page size
         * @return How many candidates to retrieve
         */
        int candidates(int depth) {
            if (!runs()) {
                return depth;
            }
            long widened = Math.min((long) depth * oversamplingFactor, QueryRequest.MAX_TOP_K);
            return (int) Math.max(depth, widened);
        }

        /**
         * How much of what is left of the budget retrieval may spend, when scoring follows it.
         *
         * <p>All of it when nothing is scored; otherwise all but
         * {@link #SCORING_SHARE_HELD_BACK}, which is kept for the model.</p>
         *
         * @param remainingMs What is left of the request's budget
         * @return What retrieval may spend of it
         */
        long retrievalBudgetMs(long remainingMs) {
            if (!runs()) {
                return remainingMs;
            }
            return (long) (remainingMs * (1.0 - SCORING_SHARE_HELD_BACK));
        }
    }

    /**
     * How one ranking's items are named, scored and re-scored.
     *
     * @param <T> The item type
     */
    interface Candidates<T> {

        /**
         * The chunk identifier the item's text is read under.
         *
         * @param item The item
         * @return Its identifier
         */
        String idOf(T item);

        /**
         * The score retrieval gave the item, which it keeps if the model does not judge it.
         *
         * @param item The item
         * @return Its retrieval score
         */
        float retrievalScore(T item);

        /**
         * The item carrying the relevancy the model gave it.
         *
         * @param item  The item
         * @param score The relevancy, in {@code [0.0, 1.0]}
         * @return The re-scored item
         */
        T rescored(T item, float score);
    }

    /** A BM25 ranking, named by chunk and re-scored in place. */
    static final Candidates<LexicalQueryService.Hit> HITS = new Candidates<>() {
        @Override
        public String idOf(LexicalQueryService.Hit hit) {
            return hit.chunkId();
        }

        @Override
        public float retrievalScore(LexicalQueryService.Hit hit) {
            return (float) hit.score();
        }

        @Override
        public LexicalQueryService.Hit rescored(LexicalQueryService.Hit hit, float score) {
            return new LexicalQueryService.Hit(hit.chunkId(), score);
        }
    };

    /** A fused ranking, keeping each result's provenance and replacing only its score. */
    static final Candidates<HybridFusion.Result> FUSED = new Candidates<>() {
        @Override
        public String idOf(HybridFusion.Result result) {
            return result.documentId();
        }

        @Override
        public float retrievalScore(HybridFusion.Result result) {
            return (float) result.score();
        }

        @Override
        public HybridFusion.Result rescored(HybridFusion.Result result, float score) {
            return new HybridFusion.Result(result.documentId(), score, result.segmentId(),
                result.signals());
        }
    };

    /**
     * A vector ranking, for the one case the pipeline did not score it itself: a fused request
     * whose BM25 half then produced nothing to fuse.
     */
    static final Candidates<QueryResultItem> VECTOR = new Candidates<>() {
        @Override
        public String idOf(QueryResultItem item) {
            return item.externalId();
        }

        @Override
        public float retrievalScore(QueryResultItem item) {
            return item.score();
        }

        @Override
        public QueryResultItem rescored(QueryResultItem item, float score) {
            return item.withScore(score);
        }
    };

    /**
     * What scoring one ranking produced.
     *
     * @param ranking   The ranking to answer with, cut to the page's depth; empty when the
     *                  request was withdrawn
     * @param scored    Whether the model's judgement ordered it
     * @param fellBack  Whether the scorer failed and the retrieval ranking was kept
     * @param timedOut  Whether the deadline passed before the scorer answered
     * @param cancelled Whether the caller withdrew the request while it was being scored
     * @param <T>       The item type
     */
    record Outcome<T>(List<T> ranking, boolean scored, boolean fellBack, boolean timedOut,
                      boolean cancelled) {

        static <T> Outcome<T> unscored(List<T> ranking) {
            return new Outcome<>(ranking, false, false, false, false);
        }

        /**
         * Whether the answer is degraded by what happened here.
         *
         * @return true when scoring was meant to run and did not finish
         */
        boolean degraded() {
            return fellBack || timedOut;
        }
    }

    /**
     * Scores a ranking, or leaves it as it is.
     *
     * @param plan          How this request is scored
     * @param ranking       The retrieval ranking, best first, {@link Plan#candidates} deep
     * @param depth         How deep the answer is, being offset plus page size
     * @param candidates    How the ranking's items are named and re-scored
     * @param deadlineNanos {@link System#nanoTime()} value the request must finish by
     * @param token         Withdraws the scoring call when the caller cancels the request
     * @param <T>           The item type
     * @return What scoring produced
     * @throws InterruptedException if the request is interrupted while the model is judging
     */
    <T> Outcome<T> score(Plan plan, List<T> ranking, int depth, Candidates<T> candidates,
                         long deadlineNanos, CancellationToken token)
            throws InterruptedException {
        if (!plan.runs()) {
            return Outcome.unscored(truncate(ranking, depth));
        }
        // The deadline first: a request whose budget is spent has timed out whether or not
        // the token was also cancelled on the way, and saying it was withdrawn would blame the
        // caller for the server's deadline.
        if (deadlineNanos - System.nanoTime() <= 0) {
            LOGGER.warn("No budget left for relevancy scoring; answering with the retrieval "
                + "ranking");
            return new Outcome<>(truncate(ranking, depth), false, false, true, false);
        }
        if (token.isCancelled()) {
            return new Outcome<>(List.of(), false, false, false, true);
        }
        if (ranking.isEmpty()) {
            // The stage ran over nothing, which is what Stage 5 reports for an empty candidate
            // set too; there is no call to make.
            return new Outcome<>(ranking, true, false, false, false);
        }

        Future<List<T>> pending;
        try {
            pending = executor.submit(() -> judge(plan, ranking, candidates));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Could not start relevancy scoring; answering with the retrieval "
                + "ranking: {}", e.getMessage());
            return new Outcome<>(truncate(ranking, depth), false, true, false, false);
        }
        // Runs at once if the request was withdrawn in between. The call is an outbound request
        // to a model, and one nobody is waiting for is a bill for nothing.
        token.addListener(() -> pending.cancel(true));
        List<T> judged;
        try {
            judged = pending.get(Deadline.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            LOGGER.warn("Relevancy scoring did not answer inside the query's budget; "
                + "answering with the retrieval ranking");
            return new Outcome<>(truncate(ranking, depth), false, false, true, false);
        } catch (CancellationException e) {
            return new Outcome<>(List.of(), false, false, false, true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warn("Relevancy scoring failed; answering with the retrieval ranking: {}",
                cause.getMessage());
            return new Outcome<>(truncate(ranking, depth), false, true, false, false);
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
        return new Outcome<>(truncate(judged, depth), true, false, false, false);
    }

    /**
     * Puts the candidates in front of the model and applies its verdict.
     *
     * <p>Each candidate is handed over as a result carrying its retrieval score, with its
     * position in the ranking as its node id - which is how the verdict is mapped back, since
     * the scorer keeps every result's identity and changes only its score. What comes back is
     * Stage 5's answer: a judged candidate carries its relevancy, one the model could not judge
     * is the very result it was handed in as and keeps its retrieval score, one the threshold
     * removed is gone, and the list is ordered by score. A result naming a position outside the
     * ranking is dropped, since there is nothing for it to be about.</p>
     *
     * <p>Text is read here, on the scoring thread, so the storage reads it costs are inside the
     * request's deadline too.</p>
     */
    private static <T> List<T> judge(Plan plan, List<T> ranking, Candidates<T> candidates) {
        List<QueryResultItem> items = new ArrayList<>(ranking.size());
        for (int i = 0; i < ranking.size(); i++) {
            T item = ranking.get(i);
            String id = candidates.idOf(item);
            String text = plan.text().resolve(id).text();
            byte[] payload = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
            items.add(new QueryResultItem(id, candidates.retrievalScore(item), payload, 0L, i));
        }
        List<QueryResultItem> judged = ResultAggregator.sortByRelevanceDescending(
            plan.scorer().scoreResults(plan.query(), items, plan.threshold()));
        List<T> ordered = new ArrayList<>(judged.size());
        for (QueryResultItem result : judged) {
            long index = result.nodeId();
            if (index < 0 || index >= ranking.size()) {
                continue;
            }
            T original = ranking.get((int) index);
            ordered.add(result == items.get((int) index)
                ? original
                : candidates.rescored(original, result.score()));
        }
        return ordered;
    }

    private static <T> List<T> truncate(List<T> ranking, int depth) {
        return ranking.size() <= depth ? ranking : List.copyOf(ranking.subList(0, depth));
    }
}
