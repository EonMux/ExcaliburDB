package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.api.retrieval.LexicalCoverage;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.retrieval.LexicalSearchPermits;
import com.eonmux.excalibur.api.retrieval.LexicalSignal;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Answers a query from the BM25 signal.
 *
 * <p>Two routes reach this. A node with no embedding client has no vector half at all and
 * answers every query here. A node that has one answers here only when the vector half misses
 * its deadline, because the two signals are independent and one failing does not decide the
 * other's fate - the node is holding a complete lexical answer, and returning nothing would
 * report the corpus as empty of what it actually contains.</p>
 *
 * <p>Both say so in the response rather than passing a partial answer off as a whole one:
 * coverage is marked degraded, and the request fields this path cannot act on are named back
 * to the caller.</p>
 *
 * <h2>Relevancy scoring</h2>
 * <p>A BM25 answer is scored like any other: when the request asks for it and the node has a
 * chat model and chunk text, the search ranks {@code oversampling_factor} times deeper and
 * {@link RelevancyScoring} puts that candidate set in front of the model, inside the same
 * deadline and the same cancellation as the search. A node with no embeddings is the deployment
 * that most needs the model's judgement, since expansion is the only semantics it has.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe, holding only the collaborators a request needs.</p>
 *
 * @author ExcaliburDB
 */
final class LexicalAnswer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalAnswer.class);

    private final QueryRequestParser parser;
    private final ExecutorService executorService;
    private final QueryRegistry queryRegistry;
    private final QueryContexts contexts;
    private final QueryPipeline queryPipeline;
    private final RelevancyScoring relevancy;

    /**
     * Creates the answer path.
     *
     * @param parser          Validates a request body
     * @param executorService Runs the search off the handler thread, so its budget is enforced
     * @param queryRegistry   Makes a running query cancellable, or null when none is kept
     * @param contexts        Resolves what a request asked for beyond the search - the page,
     *                        the text, the explanation - the same way the fused path does
     * @param queryPipeline   The pipeline, for its relevancy scorer and its corpus fingerprint
     * @param relevancy       Scores a ranking the pipeline did not produce
     * @throws NullPointerException if any argument but queryRegistry is null
     */
    LexicalAnswer(QueryRequestParser parser, ExecutorService executorService,
                  QueryRegistry queryRegistry, QueryContexts contexts,
                  QueryPipeline queryPipeline, RelevancyScoring relevancy) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.executorService =
            Objects.requireNonNull(executorService, "executorService cannot be null");
        this.queryRegistry = queryRegistry;
        this.contexts = Objects.requireNonNull(contexts, "contexts cannot be null");
        this.queryPipeline = Objects.requireNonNull(queryPipeline, "queryPipeline cannot be null");
        this.relevancy = Objects.requireNonNull(relevancy, "relevancy cannot be null");
    }

    /**
     * Decides whether one request's answer will be scored, on this node, now.
     *
     * <p>Read per request because the scorer is the pipeline's and the chunk text is the
     * node's, and both are wired after this path exists.</p>
     *
     * @param parsed The validated request
     * @return The plan
     */
    RelevancyScoring.Plan plan(QueryRequestParser.ParsedQuery parsed) {
        return relevancy.plan(parsed, queryPipeline.getLlmScorer(), contexts.chunkText());
    }

    /**
     * Answers from the BM25 half alone when the vector half could not run.
     *
     * <p>The two signals are independent, and the point of starting them together is that one
     * missing its deadline does not decide the other's fate. An embedding endpoint that hangs
     * is the common case: BM25 has already finished, the node is holding a complete answer, and
     * returning nothing reports the corpus as empty of what it actually contains.</p>
     *
     * <p>It is not only a deadline that reaches here. An embedding endpoint with a rejected
     * key, an open circuit breaker or a 5xx fails in microseconds, and the documented effect of
     * a missing embedding endpoint is no dense signal - not a failed query. Either way the
     * answer says what it is: coverage is degraded because it came from fewer signals than this
     * node has, and {@code timed_out} reports whether a deadline is what ended the other half
     * rather than being set because the answer is degraded.</p>
     *
     * <p>When the request was to be scored after fusion, the BM25 search ranked the whole
     * candidate set, so it is scored here instead - with whatever is left of the deadline, which
     * after a vector timeout is nothing, and then the BM25 order is returned and the answer says
     * it timed out. Either way the ranking is cut to the page's depth before it is answered.
     * Never cached: it is not the answer the request's cache key names.</p>
     *
     * @param exchange      The request to answer
     * @param lexicalSignal The BM25 search, or null when none was started
     * @param queryId       The identifier this query was registered under
     * @param requestBody   The raw request, for the fields this path cannot honour
     * @param parsed        The validated request
     * @param context       What this request asked for beyond the search
     * @param plan          How this request's answer was to be scored
     * @param token         Withdraws the scoring call when the caller cancels the request
     * @param startTime     {@link System#nanoTime()} value the request's budget runs from
     * @param deadlineNanos {@link System#nanoTime()} value the request must finish by
     * @param timedOut      Whether the vector half ran out of time rather than failing
     * @return true when an answer was sent, false when this signal had none to give, or the
     *         request was interrupted while its answer was being scored
     * @throws IOException if the response cannot be written
     */
    boolean fallback(HttpExchange exchange,
                     LexicalSignal lexicalSignal,
                     String queryId, String requestBody,
                     QueryRequestParser.ParsedQuery parsed, QueryContext context,
                     RelevancyScoring.Plan plan, CancellationToken token,
                     long startTime, long deadlineNanos, boolean timedOut)
            throws IOException {
        List<LexicalQueryService.Hit> hits =
            lexicalSignal == null ? null : lexicalSignal.ready();
        // Completed, not non-empty: BM25 running and matching nothing is a fact about the
        // corpus, and reporting it as a vector timeout instead would make the response shape
        // depend on whether the query happened to match. `retrieval` means which signals ran
        // everywhere else on this route, and it has to mean that here too.
        if (hits == null) {
            return false;
        }
        IndexCoverage coverage;
        try {
            coverage = LexicalCoverage.of(lexicalSignal.service().stats());
        } catch (RuntimeException e) {
            // An index that answered a search and then could not describe itself is still not
            // a reason to lose the answer - but coverage that cannot be read cannot be
            // guessed at either, so this falls back to the timeout response.
            LOGGER.warn("The lexical index could not report its coverage: {}", e.getMessage());
            return false;
        }
        int depth = context.depth();
        boolean exhausted = hits.size() < plan.candidates(depth);
        RelevancyScoring.Outcome<LexicalQueryService.Hit> scored;
        try {
            scored = relevancy.score(plan, hits, depth, RelevancyScoring.HITS, deadlineNanos,
                token);
        } catch (InterruptedException e) {
            // Restore the flag and let the caller answer: building a 200 on a thread carrying
            // an unanswered interrupt closes the channel mid-write.
            Thread.currentThread().interrupt();
            return false;
        }
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        LexicalResponse response = new LexicalResponse(queryId, scored.ranking(), exhausted,
            scored.cancelled() ? LexicalCoverage.cancelled(lexicalSignal.service().stats())
                : coverage,
            timedOut || scored.timedOut(), scored.cancelled(), scored.scored(),
            scored.fellBack(), durationMs, false);
        send(exchange, response, requestBody, parsed, context, plan);
        return true;
    }

    /**
     * Answers a query from the lexical index alone.
     *
     * <p>Reports the same response shape as vector search, through the same coverage renderer
     * and over numbers the index is asked for rather than assumed - see {@link LexicalCoverage}.
     * Coverage is always marked degraded: the answer is real, and it came from fewer signals than
     * a fully configured node would use. Saying so is what lets a caller tell "this is all there
     * was" from "this is all we looked at".</p>
     *
     * <p>The request's budget is spent on the search and its scoring, so {@code timed_out}
     * reports what happened rather than a literal false. The query is registered while either
     * runs, so cancelling it finds it and stops it. And the fields this path cannot honour are
     * named back to the caller in {@code ignored_fields}: accepting {@code threshold},
     * validating it, and answering as though it had been applied is the part that misleads.</p>
     *
     * <p>A repeated question is answered from {@code cache} when the lexical index would still
     * read what it read the first time; see {@link QueryCacheLookup}.</p>
     *
     * @param exchange      The request to answer
     * @param lexical       The index to search
     * @param cache         The query result cache, or null on a node that runs without one
     * @param queryId       The identifier to register the running query under
     * @param correlationId Correlation ID for the registration
     * @param startTime     {@link System#nanoTime()} value the request's budget runs from
     * @throws IOException if the response cannot be written
     */
    void answer(HttpExchange exchange,
                LexicalQueryService lexical, QueryResultCache cache,
                String queryId, String correlationId, long startTime)
            throws IOException {
        String requestBody = RequestBody.read(exchange);
        QueryRequestParser.ParsedQuery parsed;
        try {
            parsed = parser.parse(requestBody);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }

        // Resolved before the search, not at render time, so a request asking for text, a
        // page or an explanation this node cannot serve is refused without a full BM25 pass
        // having run for an answer that is going to be thrown away.
        QueryContext context;
        try {
            context = contexts.of(requestBody, parsed);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }
        RelevancyScoring.Plan plan = plan(parsed);

        // Fingerprinted before the search and stored against afterwards, for the same reason
        // the vector route does: a chunk published while this query ran must not make a
        // pre-publication answer look current.
        QueryCacheLookup lookup = QueryCacheLookup.of(cache, queryPipeline, lexical,
            QueryResponseJson.LEXICAL, parsed, context, exchange);
        LexicalResponse held = lookup.hit(LexicalResponse.class);
        if (held != null) {
            send(exchange, held.withQueryId(queryId)
                    .withQueryTimeMs((System.nanoTime() - startTime) / 1_000_000),
                requestBody, parsed, context, plan);
            return;
        }

        CancellationToken token = new CancellationToken();
        // Registered before the search starts and kept until scoring is over, so
        // DELETE /v1/query/{id} can find it for the whole time it is running rather than
        // answering "not found or already completed" for a query that is neither.
        if (queryRegistry != null) {
            queryRegistry.register(queryId, token, correlationId);
        }
        LexicalResponse response;
        try {
            response = searchAndScore(exchange, lexical, parsed, context, plan, queryId, token,
                startTime);
        } finally {
            if (queryRegistry != null) {
                queryRegistry.unregister(queryId);
            }
        }
        if (response == null) {
            return;
        }
        lookup.keep(QueryResponseJson.LEXICAL, response);
        send(exchange, response, requestBody, parsed, context, plan);
    }

    /**
     * Writes a lexical answer, cutting its page and deciding what it says beyond the ranking.
     *
     * <p>One exit for a searched, a replayed and a fallen-back answer, so the three cannot
     * disagree about the page, the total or the fields left unacted on.</p>
     *
     * @param exchange    The request to answer
     * @param response    What the search produced, or what the cache held
     * @param requestBody The raw request, for the fields this path cannot honour
     * @param parsed      The validated request
     * @param context     What this request asked for beyond the search
     * @param plan        How this request's answer was to be scored
     * @throws IOException if the response cannot be written
     */
    private static void send(HttpExchange exchange, LexicalResponse response, String requestBody,
                             QueryRequestParser.ParsedQuery parsed, QueryContext context,
                             RelevancyScoring.Plan plan) throws IOException {
        List<LexicalQueryService.Hit> hits = response.hits();
        // The page is what bounds the hits handed back. top_k is a validated bound and how deep
        // the search ranked is a detail of how it was scored or fused, so no detail of that is
        // allowed to hand the caller more, or less, than the page it asked for.
        List<LexicalQueryService.Hit> page = context.page(hits.size()).slice(hits);
        QueryExplanation explanation = context.explaining()
            ? QueryExplainer.lexical(page, context.offset(), context.passages(),
                context.expansion(true))
            : null;
        QueryAnswer answer = context.answer(hits.size(), response.total(context.depth()),
                explanation)
            .ignoring(IgnoredFields.of(requestBody, parsed, true, plan.runs()));
        ResponseBody.sendJson(exchange, 200, QueryResponseJson.lexical(response, page, answer));
    }

    /**
     * Searches, then scores what the search found, all inside the request's budget.
     *
     * @param exchange  The request, answered here when the search could not produce one
     * @param lexical   The index to search
     * @param parsed    The validated request
     * @param context   What this request asked for beyond the search, for its depth
     * @param plan      How this request's answer is to be scored
     * @param queryId   The identifier the running query is registered under
     * @param token     Cancels the search and the scoring when the caller withdraws
     * @param startTime {@link System#nanoTime()} value the request's budget runs from
     * @return The answer, or null when this method has already answered
     * @throws IOException if a refusal cannot be written
     */
    private LexicalResponse searchAndScore(HttpExchange exchange, LexicalQueryService lexical,
                                           QueryRequestParser.ParsedQuery parsed,
                                           QueryContext context, RelevancyScoring.Plan plan,
                                           String queryId, CancellationToken token,
                                           long startTime) throws IOException {
        long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMs());
        int depth = context.depth();
        int candidates = plan.candidates(depth);
        Outcome outcome = search(exchange, lexical, parsed, candidates, token, deadlineNanos);
        if (outcome == null) {
            return null;
        }
        if (outcome.timedOut() || outcome.cancelled()) {
            return new LexicalResponse(queryId, List.of(), false,
                coverageFor(lexical, outcome.timedOut(), outcome.cancelled()),
                outcome.timedOut(), outcome.cancelled(), false, false, elapsedMs(startTime),
                false);
        }
        RelevancyScoring.Outcome<LexicalQueryService.Hit> scored;
        try {
            scored = relevancy.score(plan, outcome.hits(), depth, RelevancyScoring.HITS,
                deadlineNanos, token);
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the carrier thread unable to observe the
            // shutdown that interrupted it.
            Thread.currentThread().interrupt();
            ResponseBody.sendError(exchange, 503, "Query interrupted");
            return null;
        }
        return new LexicalResponse(queryId, scored.ranking(),
            outcome.hits().size() < candidates,
            coverageFor(lexical, false, scored.cancelled()),
            scored.timedOut(), scored.cancelled(), scored.scored(), scored.fellBack(),
            elapsedMs(startTime), false);
    }

    private static long elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    /**
     * What one lexical search produced.
     *
     * @param hits      The ranked passages, empty when the search did not finish
     * @param timedOut  Whether the request's budget ran out
     * @param cancelled Whether the caller withdrew the request
     */
    private record Outcome(List<LexicalQueryService.Hit> hits, boolean timedOut,
                           boolean cancelled) {
    }

    /**
     * Starts the search, inside the node-wide bound on concurrent BM25 searches.
     *
     * @param exchange The request, answered here when the search cannot be started
     * @param lexical  The index to search
     * @param query    The query text
     * @param limit    How deep the search ranks
     * @return The running search, or null when this method has already answered
     * @throws IOException if a refusal cannot be written
     */
    private Future<List<LexicalQueryService.Hit>> start(HttpExchange exchange,
                                                        LexicalQueryService lexical,
                                                        String query, int limit)
            throws IOException {
        // The same node-wide bound the hybrid route takes. This route is the whole answer on a
        // node that does not embed its corpus, so a bound held only by the other route leaves
        // that deployment with none: a BM25 search outlives the request's deadline, and at the
        // rate the guard allows, abandoned searches accumulate faster than they drain.
        if (!LexicalSearchPermits.tryAcquire()) {
            refuse(exchange, "every BM25 permit is in use");
            return null;
        }
        try {
            return executorService.submit(() -> {
                try {
                    return lexical.search(query, limit);
                } finally {
                    // Released when the search stops, not when the request stops waiting for
                    // it. Releasing at the call site would let abandoned searches pile up
                    // behind a permit count that says they are not there.
                    LexicalSearchPermits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            LexicalSearchPermits.release();
            refuse(exchange, e.getMessage());
            return null;
        }
    }

    /**
     * Answers 503 for a search that could not be started.
     *
     * <p>The registration is unwound by the caller, which holds it for the search and the
     * scoring together.</p>
     *
     * @param exchange The request to refuse
     * @param reason   Why the search could not start, for the node's log only
     * @throws IOException if the refusal cannot be written
     */
    private static void refuse(HttpExchange exchange, String reason) throws IOException {
        LOGGER.warn("Could not start the lexical search: {}", reason);
        ResponseBody.sendError(exchange, 503, "Query service is saturated");
    }

    /**
     * Runs the search inside the request's budget, cancellable for as long as it runs.
     *
     * @param exchange      The request, answered here when the search could not produce one
     * @param lexical       The index to search
     * @param parsed        The validated request
     * @param limit         How deep the search ranks
     * @param token         Cancels the search when the caller withdraws the request
     * @param deadlineNanos {@link System#nanoTime()} value the request must finish by
     * @return What the search produced, or null when this method has already answered
     * @throws IOException if a refusal cannot be written
     */
    private Outcome search(HttpExchange exchange, LexicalQueryService lexical,
                           QueryRequestParser.ParsedQuery parsed, int limit,
                           CancellationToken token, long deadlineNanos)
            throws IOException {
        Future<List<LexicalQueryService.Hit>> pending =
            start(exchange, lexical, parsed.query(), limit);
        if (pending == null) {
            return null;
        }
        // Attached after the submit because it needs the future, and addListener runs
        // immediately if a cancellation arrived in between. It releases the request, not the
        // search, and never interrupts it: see abandon(Future).
        token.addListener(() -> abandon(pending));
        try {
            return new Outcome(
                pending.get(Deadline.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS),
                false, false);
        } catch (TimeoutException e) {
            abandon(pending);
            LOGGER.warn("Lexical query exceeded its {}ms budget", parsed.timeoutMs());
            return new Outcome(List.of(), true, false);
        } catch (CancellationException e) {
            LOGGER.info("Lexical query cancelled");
            return new Outcome(List.of(), false, true);
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the carrier thread unable to observe the
            // shutdown that interrupted it.
            Thread.currentThread().interrupt();
            abandon(pending);
            ResponseBody.sendError(exchange, 503, "Query interrupted");
            return null;
        } catch (ExecutionException e) {
            // A failed search is a failure. Reporting it as 200 with an empty result list is
            // indistinguishable from "nothing matched".
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Lexical query failed: {}", cause.getMessage(), cause);
            ResponseBody.sendError(exchange, 500, "Query execution failed");
            return null;
        }
    }

    /**
     * Gives up waiting for a search, leaving it to end on its own.
     *
     * <p>Cancelled without interrupting it. A search reads nothing an interrupt could cut
     * short - the native index and Lucene's {@code MMapDirectory} read through mappings, which
     * never look at the flag - and on the one layout that does not map, Lucene's
     * {@code NIOFSDirectory} fallback, an interrupt reaching a read closes the index file for
     * every query that shares it.</p>
     *
     * @param pending The search
     */
    private static void abandon(Future<?> pending) {
        pending.cancel(false);
    }

    /**
     * What a lexical answer may claim to have covered.
     *
     * <p>An interrupted search read an unknown prefix of the manifest and can claim none of
     * it. Which interruption it was matters to the counts: a deadline missed is a segment
     * that timed out, a request withdrawn is not.</p>
     *
     * @param lexical   The index, asked for its own account of itself
     * @param timedOut  Whether the request's budget ran out
     * @param cancelled Whether the caller withdrew the request
     * @return The coverage block for this answer
     */
    private static IndexCoverage coverageFor(
            LexicalQueryService lexical,
            boolean timedOut, boolean cancelled) {
        if (timedOut) {
            return LexicalCoverage.timedOut(lexical.stats());
        }
        if (cancelled) {
            return LexicalCoverage.cancelled(lexical.stats());
        }
        return LexicalCoverage.of(lexical.stats());
    }
}
