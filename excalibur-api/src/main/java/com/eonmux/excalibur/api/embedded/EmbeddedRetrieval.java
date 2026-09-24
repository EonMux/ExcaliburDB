package com.eonmux.excalibur.api.embedded;

import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.retrieval.LexicalSignal;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.InProcessServices;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.QueryOptions;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Answers a search in-process, over the signals {@code POST /v1/query} would use.
 *
 * <h2>The same decision as the server</h2>
 * <p>Which signals run is decided by what the node holds, by the rule the query endpoint uses: no
 * embedding client means BM25 alone; an embedding client and a chunk index with
 * {@code excalibur.query.hybrid-retrieval} on means both, fused by reciprocal rank; otherwise
 * vector search alone. The BM25 half starts before the embedding call, since the two are
 * independent. A vector half that fails or runs out of time leaves the BM25 half to answer, and
 * a BM25 half that fails leaves the vector one - degradation, not failure, exactly as on the
 * wire. The request bounds are the node's own: {@code excalibur.api.max-top-k},
 * {@code max-query-chars} and {@code max-query-timeout-ms}.</p>
 *
 * <h2>What it leaves out</h2>
 * <p>The HTTP surface around the search: the result cache, pagination, explanations,
 * cancellation by query id and relevancy scoring by the chat model. An embedder that wants any of
 * those runs the server.</p>
 */
final class EmbeddedRetrieval {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedRetrieval.class);

    /** How long past its own budget the pipeline is waited for, as the query endpoint waits. */
    private static final long PIPELINE_RESPONSE_GRACE_MILLIS = 250;

    /** Said when the node has no signal at all, which the query endpoint also refuses. */
    static final String NO_SIGNAL = "This node has neither an embedding client nor a lexical "
        + "index, so there is no index to search. Configure an embedding endpoint "
        + "(excalibur.llm.embedding-base-url or excalibur.llm.base-url, with "
        + "excalibur.llm.api-key for the hosted default), or see the log for why the chunk "
        + "lexical index was not opened.";

    private final QueryPipeline pipeline;
    private final EmbeddingClient embeddings;
    private final LexicalQueryService lexical;
    private final boolean hybrid;
    private final float threshold;
    private final boolean includePartial;
    private final ApiSecurityConfig limits;
    private final Function<String, String> chunkText;
    private final ExecutorService executor;

    /**
     * @param services  The node's services
     * @param limits    The node's request bounds
     * @param chunkText Reads a chunk's text back out of storage
     * @param executor  Where the embedding call and the BM25 search run
     */
    EmbeddedRetrieval(InProcessServices services, ApiSecurityConfig limits,
                      Function<String, String> chunkText, ExecutorService executor) {
        Config config = services.config();
        this.pipeline = services.queryPipeline();
        this.embeddings = services.embeddingClient();
        this.lexical = services.lexicalQueryService();
        this.hybrid = config.query().isHybridRetrieval();
        this.threshold = (float) config.query().getThreshold();
        this.includePartial = config.query().isIncludePartialSegments();
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        this.chunkText = Objects.requireNonNull(chunkText, "chunkText cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    /**
     * The route a search on this node takes when both of its signals are healthy.
     *
     * @return The route, or empty when the node has no signal to search
     */
    Optional<SearchRoute> route() {
        if (embeddings == null) {
            return lexical == null ? Optional.empty() : Optional.of(SearchRoute.LEXICAL);
        }
        return Optional.of(hybrid && lexical != null ? SearchRoute.HYBRID : SearchRoute.VECTOR);
    }

    /**
     * Searches the corpus.
     *
     * @param text      The query text
     * @param topK      Most hits to return
     * @param timeoutMs The search's budget
     * @param scope     The sources the answer is drawn from
     * @return The answer, which says which signals produced it
     * @throws IllegalArgumentException if a bound is violated
     * @throws IllegalStateException    if the node has no signal, every signal it has failed, or
     *                                  the search is scoped and the node cannot attribute chunks
     *                                  to sources
     * @throws InterruptedException     if the calling thread is interrupted while it waits
     */
    SearchAnswer search(String text, int topK, long timeoutMs, SourceScope scope)
            throws InterruptedException {
        validate(text, topK, timeoutMs);
        Objects.requireNonNull(scope, "scope cannot be null");
        if (!scope.isUnrestricted() && pipeline.getChunkSources() == null) {
            throw new IllegalStateException("This node cannot confine a search to sources: it "
                + "keeps no document registry to say which source a chunk is from");
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        SearchRoute planned = route().orElseThrow(() -> new IllegalStateException(NO_SIGNAL));
        Search search = new Search(text, topK, scope, deadlineNanos);
        if (planned == SearchRoute.LEXICAL) {
            return lexicalOnly(search);
        }
        LexicalSignal signal = planned == SearchRoute.HYBRID
            ? LexicalSignal.start(lexical, executor, text, scope, topK, budget(deadlineNanos))
            : null;
        try {
            return withVectors(search, signal);
        } finally {
            if (signal != null) {
                signal.cancel();
            }
        }
    }

    private void validate(String text, int topK, long timeoutMs) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The query text must not be blank");
        }
        if (text.length() > limits.maxQueryChars()) {
            throw new IllegalArgumentException("The query text must be at most "
                + limits.maxQueryChars() + " characters: " + text.length());
        }
        if (topK <= 0 || topK > limits.maxTopK()) {
            throw new IllegalArgumentException("topK must lie in [1, " + limits.maxTopK() + "]: "
                + topK);
        }
        if (timeoutMs <= 0 || timeoutMs > limits.maxQueryTimeoutMs()) {
            throw new IllegalArgumentException("The timeout must lie in [1, "
                + limits.maxQueryTimeoutMs() + "] ms: " + timeoutMs);
        }
    }

    /**
     * What one search asked for.
     *
     * @param text          The query text
     * @param topK          Most hits to return
     * @param scope         The sources the answer is drawn from
     * @param deadlineNanos {@link System#nanoTime()} value the search must finish by
     */
    private record Search(String text, int topK, SourceScope scope, long deadlineNanos) {
    }

    /** A node with no vectors: BM25 is the whole answer. */
    private SearchAnswer lexicalOnly(Search search) throws InterruptedException {
        long deadlineNanos = search.deadlineNanos();
        LexicalSignal signal =
            LexicalSignal.start(lexical, executor, search.text(), search.scope(), search.topK(),
                budget(deadlineNanos));
        if (signal == null) {
            throw new IllegalStateException("Every BM25 search permit is in use; try again");
        }
        try {
            LexicalQueryService.Ranking ranking = signal.await(remainingMillis(deadlineNanos));
            if (ranking != null) {
                return new SearchAnswer(SearchRoute.LEXICAL, lexicalHits(ranking.hits()), false);
            }
            if (remainingMillis(deadlineNanos) == 0) {
                return new SearchAnswer(SearchRoute.LEXICAL, List.of(), true);
            }
            throw new IllegalStateException("The BM25 search failed; see the log for why");
        } finally {
            signal.cancel();
        }
    }

    /** A node with vectors, and a BM25 search already running beside them or not. */
    private SearchAnswer withVectors(Search search, LexicalSignal signal)
            throws InterruptedException {
        String text = search.text();
        int topK = search.topK();
        long deadlineNanos = search.deadlineNanos();
        CancellationToken token = new CancellationToken();
        try {
            float[] vector = embed(text, deadlineNanos);
            if (vector == null) {
                LOGGER.warn("Query embedding ran out of the search's budget");
                return fallBack(search, signal, true, null);
            }
            long budgetMs = Math.max(1, remainingMillis(deadlineNanos));
            QueryRequest request = QueryRequest.builder()
                .queryText(text)
                .queryVector(vector)
                .topK(topK)
                .threshold(threshold)
                .timeoutMs(budgetMs)
                .includePartialSegments(includePartial)
                .enableLlmScoring(false)
                .oversamplingFactor(QueryOptions.DEFAULT_OVERSAMPLING_FACTOR)
                // A hit an embedder cannot name is one it cannot fetch or erase.
                .requireNamedResults(true)
                .sourceScope(search.scope())
                .maxSearchDepth(Math.min(limits.maxTopK(), QueryRequest.MAX_TOP_K))
                .build();
            QueryResponse response = pipeline.execute(request, token)
                .get(budgetMs + PIPELINE_RESPONSE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            LexicalQueryService.Ranking ranking =
                signal == null ? null : signal.await(remainingMillis(deadlineNanos));
            if (ranking != null) {
                List<HybridFusion.Result> fused =
                    HybridFusion.fuse(response.results(), ranking.hits(), topK, lexical.rrfK());
                return new SearchAnswer(SearchRoute.HYBRID, fusedHits(fused),
                    response.timedOut());
            }
            return new SearchAnswer(SearchRoute.VECTOR, vectorHits(response.results(), topK),
                response.timedOut());
        } catch (TimeoutException e) {
            token.cancel("Query timeout");
            LOGGER.warn("Vector search ran out of the search's budget");
            return fallBack(search, signal, true, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Vector search failed: {}", cause.getMessage(), cause);
            return fallBack(search, signal, false, cause);
        } catch (IllegalStateException e) {
            LOGGER.error("Query embedding is unusable: {}", e.getMessage(), e);
            return fallBack(search, signal, false, e);
        } catch (InterruptedException e) {
            token.cancel("Query interrupted");
            throw e;
        }
    }

    /**
     * Embeds the query inside the search's budget.
     *
     * @return The vector, or null when the budget ran out first
     * @throws ExecutionException    if the embedding call failed
     * @throws IllegalStateException if it returned an empty or non-finite vector
     */
    private float[] embed(String text, long deadlineNanos)
            throws InterruptedException, ExecutionException {
        Future<float[]> pending = executor.submit(() -> embeddings.embed(text));
        float[] vector;
        try {
            vector = pending.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return null;
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("The embedding endpoint returned an empty vector");
        }
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalStateException("The embedding carries a non-finite value at "
                    + "index " + i + ": " + vector[i]);
            }
        }
        return vector;
    }

    /**
     * Answers from BM25 after the vector half failed, as the query endpoint does.
     *
     * <p>The search started beside the vector half is used when there is one. When there is
     * none and the node keeps a lexical index, one is started now - unless the budget is
     * already spent, since there would be nothing left to wait for it with.</p>
     *
     * @param timedOut Whether a deadline, rather than a failure, ended the vector half
     * @param failure  What ended it, when it was a failure
     */
    private SearchAnswer fallBack(Search search, LexicalSignal running, boolean timedOut,
                                  Throwable failure) throws InterruptedException {
        long deadlineNanos = search.deadlineNanos();
        LexicalSignal signal = running;
        boolean startedHere = false;
        if (signal == null && !timedOut && lexical != null && remainingMillis(deadlineNanos) > 0) {
            signal = LexicalSignal.start(lexical, executor, search.text(), search.scope(),
                search.topK(), budget(deadlineNanos));
            startedHere = signal != null;
        }
        try {
            LexicalQueryService.Ranking ranking = signal == null ? null
                : timedOut ? signal.ready() : signal.await(remainingMillis(deadlineNanos));
            if (ranking != null) {
                return new SearchAnswer(SearchRoute.LEXICAL, lexicalHits(ranking.hits()),
                    timedOut);
            }
        } finally {
            if (startedHere) {
                signal.cancel();
            }
        }
        if (timedOut) {
            return new SearchAnswer(SearchRoute.VECTOR, List.of(), true);
        }
        throw new IllegalStateException("The search failed and no other signal could answer: "
            + (failure == null ? "unknown cause" : failure.getMessage()), failure);
    }

    private List<SearchHit> vectorHits(List<QueryResultItem> results, int topK) {
        List<SearchHit> hits = new ArrayList<>(Math.min(results.size(), topK));
        for (QueryResultItem item : results) {
            String chunkId = item.documentIdStr();
            if (chunkId == null || chunkId.isEmpty()) {
                continue;
            }
            hits.add(hit(chunkId, item.score(), List.of(HybridFusion.DENSE_SIGNAL)));
            if (hits.size() == topK) {
                break;
            }
        }
        return hits;
    }

    private List<SearchHit> lexicalHits(List<LexicalQueryService.Hit> results) {
        List<SearchHit> hits = new ArrayList<>(results.size());
        for (LexicalQueryService.Hit result : results) {
            hits.add(hit(result.chunkId(), result.score(), List.of(HybridFusion.LEXICAL_SIGNAL)));
        }
        return hits;
    }

    private List<SearchHit> fusedHits(List<HybridFusion.Result> results) {
        List<SearchHit> hits = new ArrayList<>(results.size());
        for (HybridFusion.Result result : results) {
            hits.add(hit(result.documentId(), result.score(), result.signals()));
        }
        return hits;
    }

    private SearchHit hit(String chunkId, double score, List<String> signals) {
        return new SearchHit(chunkId, score, signals, chunkText.apply(chunkId));
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /** What is left of the search's budget, for a BM25 search and its query expansion. */
    private static Duration budget(long deadlineNanos) {
        return Duration.ofMillis(remainingMillis(deadlineNanos));
    }
}
