package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;

import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.RequestBody;
import excalibur.api.protocol.ResponseBody;
import excalibur.api.retrieval.LexicalCoverage;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.api.retrieval.LexicalSearchPermits;
import excalibur.api.retrieval.LexicalSignal;
import excalibur.query.IndexCoverage;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    /**
     * Creates the answer path.
     *
     * @param parser          Validates a request body
     * @param executorService Runs the search off the handler thread, so its budget is enforced
     * @param queryRegistry   Makes a running query cancellable, or null when none is kept
     * @param contexts        Resolves what a request asked for beyond the search - the page,
     *                        the text, the explanation - the same way the fused path does
     * @throws NullPointerException if parser, executorService or contexts is null
     */
    LexicalAnswer(QueryRequestParser parser, ExecutorService executorService,
                  QueryRegistry queryRegistry, QueryContexts contexts) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.executorService =
            Objects.requireNonNull(executorService, "executorService cannot be null");
        this.queryRegistry = queryRegistry;
        this.contexts = Objects.requireNonNull(contexts, "contexts cannot be null");
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
     * @param exchange      The request to answer
     * @param lexicalSignal The BM25 search, or null when none was started
     * @param queryId       The identifier this query was registered under
     * @param requestBody   The raw request, for the fields this path cannot honour
     * @param parsed        The validated request
     * @param durationMs    How long the request has taken
     * @param context       What this request asked for beyond the search
     * @param timedOut      Whether the vector half ran out of time rather than failing
     * @return true when an answer was sent, false when this signal had none to give
     */
    boolean fallback(HttpExchange exchange,
                     LexicalSignal lexicalSignal,
                     String queryId, String requestBody,
                     QueryRequestParser.ParsedQuery parsed, long durationMs,
                     QueryContext context, boolean timedOut)
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
        // The page is what bounds the hits handed back. top_k is a validated bound and what
        // this signal was asked for is a detail of how the two are fused, so no detail of
        // that is allowed to hand the caller more, or less, than the page it asked for.
        List<LexicalQueryService.Hit> page = context.page(hits.size()).slice(hits);
        ResponseBody.sendJson(exchange, 200, QueryResponseJson.lexical(queryId, page, coverage,
            ignoredFor(requestBody, lexicalSignal.service(), parsed.query()), timedOut, false,
            durationMs, answerFor(context, hits.size(), page, coverage, timedOut, false)));
        return true;
    }

    /**
     * Everything a BM25 answer says beyond its ranking.
     *
     * @param context   What this request asked for beyond the search
     * @param produced  How many hits the search ranked, before the page was cut
     * @param page      The hits this answer returns
     * @param coverage  What the index says it searched
     * @param timedOut  Whether the request's budget ran out
     * @param cancelled Whether the caller withdrew the request
     * @return The answer
     */
    private static QueryAnswer answerFor(QueryContext context, int produced,
                                         List<LexicalQueryService.Hit> page,
                                         IndexCoverage coverage, boolean timedOut,
                                         boolean cancelled) {
        QueryExplanation explanation = context.explaining()
            ? QueryExplainer.lexical(page, context.offset(), context.passages(),
                context.expansion(true))
            : null;
        return context.answer(produced,
            ResultTotal.searchWasComplete(coverage, timedOut, cancelled), explanation);
    }

    /**
     * Request fields the lexical path parses and validates but cannot act on.
     *
     * <p>Each one steers a stage this path does not have. {@code threshold} is the exception
     * worth spelling out: BM25 scores are not cosine similarities and are not bounded by 1.0,
     * so a floor tuned against vector scores would cut an arbitrary part of a correct ranking
     * rather than the weak tail it was meant for. They are named in the response instead of
     * being applied or silently dropped - see {@link #answer}.</p>
     */
    private static final List<String> UNHONOURED_FIELDS = List.of(
        "threshold", "include_partial_segments", "enable_llm_scoring", "llm_threshold",
        "oversampling_factor");

    /**
     * Named when a query was searched as typed, without the expansion this node applies to
     * shorter ones.
     *
     * <p>Not a request field, and the only entry here that is not: it is a stage of retrieval
     * the caller's own input turned off. It belongs in the same list because the failure it
     * prevents is the same one - a request that was accepted in full and answered by less than
     * it asked for, with nothing in the answer saying which part was left out. A query past the
     * bound is answered by plain BM25, and a caller comparing it against a shorter query is
     * comparing two retrieval strategies without being told.</p>
     */
    private static final String QUERY_EXPANSION = "query_expansion";

    /**
     * What one answer accepted and did not act on.
     *
     * <p>Decided here rather than in the renderer: whether a field was honoured depends on what
     * the caller sent and on what this node's retrieval stages could do with it, and neither is
     * a question about how to write JSON.</p>
     *
     * @param requestBody The raw request, read for which fields the caller actually sent
     * @param lexical     The index that answered, asked what it does to a query this long
     * @param query       The query text, as validated
     * @return The entries for {@code ignored_fields}, in a stable order
     */
    private static List<String> ignoredFor(String requestBody, LexicalQueryService lexical,
                                           String query) {
        List<String> ignored = new ArrayList<>(UNHONOURED_FIELDS.size() + 1);
        for (String field : UNHONOURED_FIELDS) {
            if (JsonText.has(requestBody, field)) {
                ignored.add(field);
            }
        }
        // Trimmed, because that is the length the expander measures: reporting a stage as
        // skipped when it ran is the same kind of untruth as not reporting one that did not.
        if (query.trim().length() > lexical.maxExpandableQueryChars()) {
            ignored.add(QUERY_EXPANSION);
        }
        return ignored;
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
     * <p>The request's budget is spent on the search, so {@code timed_out} reports what
     * happened rather than a literal false. The query is registered while it runs, so
     * cancelling it finds it and stops it. And the fields this path cannot honour are named
     * back to the caller in {@code ignored_fields}: accepting {@code threshold}, validating it,
     * and answering as though it had been applied is the part that misleads.</p>
     */
    void answer(HttpExchange exchange,
                   LexicalQueryService lexical,
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

        Outcome outcome = search(exchange, lexical, parsed, context, queryId, correlationId,
            startTime);
        if (outcome == null) {
            return;
        }
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        IndexCoverage coverage = coverageFor(lexical, outcome.timedOut(), outcome.cancelled());
        List<LexicalQueryService.Hit> page = context.page(outcome.hits().size())
            .slice(outcome.hits());

        ResponseBody.sendJson(exchange, 200, QueryResponseJson.lexical(queryId, page, coverage,
            ignoredFor(requestBody, lexical, parsed.query()), outcome.timedOut(),
            outcome.cancelled(), durationMs, answerFor(context, outcome.hits().size(), page,
                coverage, outcome.timedOut(), outcome.cancelled())));
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
     * Registers the query and starts its search.
     *
     * <p>Registered before the search starts, so {@code DELETE /v1/query/{id}} can find it for
     * the whole time it is running rather than answering "not found or already completed" for a
     * query that is neither.</p>
     *
     * @param exchange      The request, answered here when the search cannot be started
     * @param lexical       The index to search
     * @param parsed        The validated request
     * @param context       What this request asked for beyond the search, for its depth
     * @param queryId       The identifier to register the running query under
     * @param correlationId Correlation ID for the registration
     * @param token         Cancels the search when the caller withdraws the request
     * @return The running search, or null when this method has already answered
     * @throws IOException if a refusal cannot be written
     */
    private Future<List<LexicalQueryService.Hit>> start(HttpExchange exchange,
                                                        LexicalQueryService lexical,
                                                        QueryRequestParser.ParsedQuery parsed,
                                                        QueryContext context,
                                                        String queryId, String correlationId,
                                                        CancellationToken token)
            throws IOException {
        if (queryRegistry != null) {
            queryRegistry.register(queryId, token, correlationId);
        }
        // The same node-wide bound the hybrid route takes. This route is the whole answer on a
        // node that does not embed its corpus, so a bound held only by the other route leaves
        // that deployment with none: a BM25 search outlives the request's deadline, and at the
        // rate the guard allows, abandoned searches accumulate faster than they drain.
        if (!LexicalSearchPermits.tryAcquire()) {
            refuse(exchange, queryId, "every BM25 permit is in use");
            return null;
        }
        try {
            return executorService.submit(() -> {
                try {
                    return lexical.search(parsed.query(), context.depth());
                } finally {
                    // Released when the search stops, not when the request stops waiting for
                    // it. Releasing at the call site would let abandoned searches pile up
                    // behind a permit count that says they are not there.
                    LexicalSearchPermits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            LexicalSearchPermits.release();
            refuse(exchange, queryId, e.getMessage());
            return null;
        }
    }

    /**
     * Answers 503 and takes the query back out of the registry.
     *
     * <p>The registration is unwound by the caller's {@code finally}, which a refusal here
     * never reaches. Left behind, the entry outlives the process's interest in it and DELETE
     * reports a query that never started as live.</p>
     *
     * @param exchange The request to refuse
     * @param queryId  The identifier the query was registered under
     * @param reason   Why the search could not start, for the node's log only
     * @throws IOException if the refusal cannot be written
     */
    private void refuse(HttpExchange exchange, String queryId, String reason) throws IOException {
        if (queryRegistry != null) {
            queryRegistry.unregister(queryId);
        }
        LOGGER.warn("Could not start the lexical search: {}", reason);
        ResponseBody.sendError(exchange, 503, "Query service is saturated");
    }

    /**
     * Runs the search inside the request's budget, cancellable for as long as it runs.
     *
     * @param exchange      The request, answered here when the search could not produce one
     * @param lexical       The index to search
     * @param parsed        The validated request
     * @param context       What this request asked for beyond the search, for its depth
     * @param queryId       The identifier to register the running query under
     * @param correlationId Correlation ID for the registration
     * @param startTime     {@link System#nanoTime()} value the request's budget runs from
     * @return What the search produced, or null when this method has already answered
     * @throws IOException if a refusal cannot be written
     */
    private Outcome search(HttpExchange exchange, LexicalQueryService lexical,
                           QueryRequestParser.ParsedQuery parsed, QueryContext context,
                           String queryId, String correlationId, long startTime)
            throws IOException {
        long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMs());
        List<LexicalQueryService.Hit> hits;
        boolean timedOut = false;
        boolean cancelled = false;
        CancellationToken token = new CancellationToken();
        Future<List<LexicalQueryService.Hit>> pending =
            start(exchange, lexical, parsed, context, queryId, correlationId, token);
        if (pending == null) {
            return null;
        }
        // Attached after the submit because it needs the future, and addListener runs
        // immediately if a cancellation arrived in between. This is what makes the
        // cancellation real rather than recorded: the search runs inside Lucene, which stops
        // on the interrupt and nothing else.
        token.addListener(() -> pending.cancel(true));
        try {
            hits = pending.get(Deadline.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            hits = List.of();
            timedOut = true;
            LOGGER.warn("Lexical query exceeded its {}ms budget", parsed.timeoutMs());
        } catch (CancellationException e) {
            hits = List.of();
            cancelled = true;
            LOGGER.info("Lexical query cancelled");
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the carrier thread unable to observe the
            // shutdown that interrupted it.
            Thread.currentThread().interrupt();
            pending.cancel(true);
            ResponseBody.sendError(exchange, 503, "Query interrupted");
            return null;
        } catch (ExecutionException e) {
            // A failed search is a failure. Reporting it as 200 with an empty result list is
            // indistinguishable from "nothing matched".
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Lexical query failed: {}", cause.getMessage(), cause);
            ResponseBody.sendError(exchange, 500, "Query execution failed");
            return null;
        } finally {
            if (queryRegistry != null) {
                queryRegistry.unregister(queryId);
            }
        }
        return new Outcome(hits, timedOut, cancelled);
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
