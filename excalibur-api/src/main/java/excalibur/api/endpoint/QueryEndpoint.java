package excalibur.api.endpoint;

import excalibur.api.protocol.RequestBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryRegistry;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.api.protocol.ResponseBody;
import excalibur.api.security.ApiSecurityConfig;
import io.excalibur.config.Config;
import io.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP endpoint for the /v1/query API.
 *
 * <p>QueryEndpoint handles POST requests to /v1/query, with timeout and cancellation support.
 * Which signals answer depends on what the node holds: vector search, BM25 over the chunk
 * index, or - the default when it has both - the two fused by rank. {@code retrieval} on the
 * response says which of the three it was, and is always present.</p>
 *
 * <h2>Request Format</h2>
 *
 * <p>Clients submit raw query text. The server embeds it using the same model
 * configured for ingestion (see {@code excalibur.llm.embedding-model}). This is
 * the only supported input: a client-supplied vector cannot be validated against
 * the server's ingestion embedding space, so accepting one would silently produce
 * wrong results if the client used a different model.</p>
 *
 * <pre>{@code
 * {
 *   "query": "search query text",
 *   "top_k": 10,
 *   "offset": 0,
 *   "threshold": 0.5,
 *   "timeout_ms": 5000,
 *   "include_partial_segments": true,
 *   "enable_llm_scoring": true,
 *   "include_text": false,
 *   "snippet": false,
 *   "snippet_chars": 320,
 *   "explain": false
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d",
 *   "results": [
 *     {"document_id": "my-docs:guide.md:chunk:2", "score": 0.950000, "segment_id": 3}
 *   ],
 *   "retrieval": "vector",
 *   "coverage": {
 *     "total_segments": 1000,
 *     "active_segments": 950,
 *     "indexing_segments": 40,
 *     "warming_segments": 10,
 *     "compacting_segments": 0,
 *     "tombstoned_segments": 0,
 *     "estimated_corpus_coverage": 0.9600,
 *     "segments_searched": 960,
 *     "segments_excluded": 40,
 *     "segments_with_errors": 0,
 *     "segments_timed_out": 0,
 *     "tail_scan_entries": 12500,
 *     "degraded": false
 *   },
 *   "page": {"offset": 0, "limit": 10, "returned": 10, "next_offset": 10},
 *   "result_total": {"value": 10, "relation": "at_least"},
 *   "timed_out": false,
 *   "cancelled": false,
 *   "query_time_ms": 42
 * }
 * }</pre>
 *
 * <h2>Paging, and what the total is worth</h2>
 * <p>{@code page} and {@code result_total} are on every answer, the empty one included.
 * {@code offset} deepens the search rather than resuming one - a ranked index has no cursor -
 * so it is bounded together with {@code top_k} by {@code excalibur.api.max-top-k}. The total
 * is a floor unless the engine came back shallower than it was asked for and reached the whole
 * corpus, which is the only case where it can stand behind having run out of matches; see
 * {@link ResultTotal}.</p>
 *
 * <h2>Text, snippets and explanations</h2>
 * <p>All three are opt-in, because all three cost a read of chunk storage for every result.
 * {@code include_text} returns the chunk from its start, as it always has. Adding
 * {@code snippet} returns the window the query matched instead, with {@code text_offset} and
 * {@code highlights} beside it so the caller can locate and mark what it was shown;
 * {@code snippet_chars} sizes that window and is held to
 * {@code excalibur.api.max-result-text-chars}. {@code explain} adds, on each result, every
 * contributing signal's own rank and score and the query terms the passage contains, and on
 * the answer, the terms the query was widened with - see {@link QueryExpansion}.</p>
 *
 * <p>A node with no embedding client answers from the lexical index instead, reporting
 * {@code "retrieval": "lexical"}, no {@code segment_id} on a result, and {@code degraded}
 * always true.</p>
 *
 * <h2>Hybrid retrieval</h2>
 * <p>A node with both an embedding model and a chunk index runs both signals - concurrently,
 * since BM25 needs no embedding - and fuses their rankings by reciprocal rank. Two things
 * change on such an answer, and {@code "retrieval": "hybrid"} is what declares them. Each
 * result carries the {@code signals} that returned it, and {@code segment_id} is null for one
 * only BM25 found. And {@code score} is the fused rank sum rather than a similarity, so a
 * threshold tuned against cosine scores does not transfer to it: {@code threshold} is still
 * applied by the pipeline, to the vector signal, before fusion. Set
 * {@code excalibur.query.hybrid-retrieval=false} for vector-only ranking.</p>
 *
 * <p>Neither signal is allowed to fail the query, and that runs both ways. A BM25 search that
 * breaks, misses the budget, or is shed because too many are already running leaves the vector
 * answer intact and the response says {@code "retrieval": "vector"}. An embedding endpoint or a
 * vector pipeline that misses the deadline, refuses the call, or returns something unusable is
 * answered from the BM25 half, which needed no embedding and has usually finished, with
 * {@code "retrieval": "lexical"}; {@code "timed_out"} then says whether a deadline is what ended
 * the vector half, and degraded coverage says the answer came from fewer signals either way. A
 * cancelled request is the one exception: it is answered by neither, because a withdrawn question
 * is not answered. What the field never reports is a fusion that did not happen.</p>
 *
 * <h2>Timeout Handling</h2>
 * <p>Queries that exceed their timeout return partial results with
 * {@code timed_out: true}. The cancellation token is used to propagate
 * cancellation to all query stages.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each request creates its own CancellationToken
 * and executes independently.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class QueryEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryEndpoint.class);

    /**
     * How long past its own deadline the pipeline is given to hand back its answer.
     *
     * <p>The pipeline answers a timeout with the results it already has, which is the whole
     * point of giving it a deadline rather than simply abandoning it. Waiting exactly as long as
     * the deadline races that answer and usually loses, turning a partial result into an empty
     * one; this margin is for building and returning the response, not for more query work, so
     * it is a small constant rather than a share of the budget.</p>
     */
    private static final long PIPELINE_RESPONSE_GRACE_MILLIS = 250;

    /**
     * Lexical retrieval over ingested chunks, or null when this node keeps no such index.
     *
     * <p>Set separately from construction because it is optional and orthogonal: a node may have
     * embeddings, a lexical index, both, or in the degenerate case neither.</p>
     */
    private volatile excalibur.api.retrieval.LexicalQueryService lexicalQueryService;

    /**
     * Decides whether a request may have the text behind a result identifier, and reads it.
     *
     * <p>Never null: whether this node keeps chunk storage is a property of what is inside it,
     * given by {@link #setChunkText}, not of whether the holder exists. Both retrieval paths
     * read this one, so a request is refused or answered the same way whichever answers it.</p>
     */
    private final excalibur.api.retrieval.ResultText resultText;

    /**
     * Resolves what each request asked for beyond the search, and refuses what this node
     * cannot serve.
     *
     * <p>Shared with {@link LexicalAnswer} rather than built per path: paging, totals and
     * explanations have to mean the same thing whichever of the three signals answered, and
     * the only way that stays true is for there to be one place that decides them.</p>
     */
    private final QueryContexts contexts;

    /**
     * Whether a node holding both signals answers from both.
     *
     * <p>Fixed at construction rather than read per request: it decides how {@code score} is
     * scaled, and a value that could change under a running client would change what the field
     * means without the response saying anything different.</p>
     */
    private final boolean hybridRetrieval;

    /**
     * Why a node answers 503 to every query, and what to change.
     *
     * <p>Returned to the client, not only logged. It names a configuration key and no internal
     * detail: there is no path, no address and no state of this process in it, which is what makes
     * it safe to hand out. {@code /v1/ingest/status} already reports the same gap the same way in
     * {@code last_error}, and a node that explains it on one endpoint and not the other sends
     * whoever hits the other one looking through the logs for it.</p>
     */
    static final String UNCONFIGURED_QUERY_REASON =
        "Server is not configured for text queries: this node has neither an embedding client "
        + "nor a lexical index, so there is no index to search. Set excalibur.llm.api-key (or "
        + "EXCALIBUR_LLM_API_KEY) so the server can embed queries with the same model that "
        + "ingestion uses, or see the startup log for why the chunk lexical index was not opened.";

    /**
     * Answers a repeated question without searching again, or null where none is configured.
     *
     * <p>Off unless a deployment asks for it, and set after construction for the same reason
     * the lexical index is: whether there is a cache at all is a property of configuration
     * rather than of this endpoint. Null is not a degenerate cache but no cache - no corpus
     * fingerprint is taken, and a request costs exactly what it did before.</p>
     *
     * <p>What it holds is the vector pipeline's answer, and the corpus fingerprint that
     * answer was computed against; see {@link QueryCacheLookup} for why the BM25 half and
     * every read of result text stay on the live path.</p>
     */
    private volatile excalibur.query.cache.QueryResultCache resultCache;

    /**
     * Default timeout for queries in milliseconds.
     * Initialized from config if available, otherwise falls back to 30 seconds.
     */
    private final long defaultTimeoutMs;

    /**
     * Turns a request body into validated fields, for whichever path answers it.
     *
     * <p>One instance rather than one per path: a bound applied on the vector path and not the
     * lexical one is the failure this endpoint has already had once.</p>
     */
    private final QueryRequestParser parser;

    /** Answers from BM25 alone, both for a node with no vector half and when that half fails. */
    private final LexicalAnswer lexicalAnswer;

    private final QueryPipeline queryPipeline;
    private final ExecutorService executorService;
    private final Config config;
    private final QueryRegistry queryRegistry;

    /**
     * Bounds on client-supplied request values. {@code top_k} sizes priority queues
     * and result arrays inside the pipeline, {@code timeout_ms} decides how long
     * one request may hold server resources, and {@code query} is the body of the
     * outbound embedding and expansion calls, so all three are bounded at the
     * boundary rather than wherever they happen to be consumed.
     */
    private final ApiSecurityConfig limits;

    /**
     * Optional embedding client for server-side embedding generation.
     * When configured, clients can submit raw query text and the server
     * generates the embedding using the same model that was used for ingestion.
     * When null, clients must submit a pre-computed vector.
     */
    private final excalibur.llm.embedding.EmbeddingClient embeddingClient;

    /**
     * Creates a new QueryEndpoint.
     *
     * @param queryPipeline The query pipeline for executing queries
     */
    public QueryEndpoint(QueryPipeline queryPipeline) {
        this(queryPipeline, null, null, null);
    }

    /**
     * Creates a new QueryEndpoint with configuration.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config) {
        this(queryPipeline, config, null, null);
    }

    /**
     * Creates a new QueryEndpoint with a custom executor service.
     *
     * @param queryPipeline The query pipeline
     * @param executorService The executor service for async operations
     */
    public QueryEndpoint(QueryPipeline queryPipeline, ExecutorService executorService) {
        this(queryPipeline, null, executorService, null);
    }

    /**
     * Creates a new QueryEndpoint with all options.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     * @param executorService The executor service for async operations (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config, ExecutorService executorService) {
        this(queryPipeline, config, executorService, null);
    }

    /**
     * Creates a new QueryEndpoint with query registry for cancellation support.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     * @param executorService The executor service for async operations (may be null)
     * @param queryRegistry The query registry for cancellation support (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config, ExecutorService executorService, QueryRegistry queryRegistry) {
        this(queryPipeline, config, executorService, queryRegistry, null);
    }

    /**
     * Creates a new QueryEndpoint with full configuration including server-side
     * embedding generation.
     *
     * <p>When {@code embeddingClient} is non-null, clients may submit a raw
     * {@code query} text field and the server will generate the embedding vector
     * using the configured embedding model. This ensures the query embedding is
     * consistent with the ingestion embedding.</p>
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     * @param executorService The executor service for async operations (may be null)
     * @param queryRegistry The query registry for cancellation support (may be null)
     * @param embeddingClient Optional embedding client for server-side embedding generation (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config, ExecutorService executorService,
                         QueryRegistry queryRegistry, excalibur.llm.embedding.EmbeddingClient embeddingClient) {
        this(queryPipeline, config, executorService, queryRegistry, embeddingClient,
            ApiSecurityConfig.defaults());
    }

    /**
     * Creates a new QueryEndpoint with explicit request bounds.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     * @param executorService The executor service for async operations (may be null)
     * @param queryRegistry The query registry for cancellation support (may be null)
     * @param embeddingClient Optional embedding client for server-side embedding generation
     * @param limits Bounds applied to client-supplied request values
     * @throws NullPointerException if queryPipeline or limits is null
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config, ExecutorService executorService,
                         QueryRegistry queryRegistry, excalibur.llm.embedding.EmbeddingClient embeddingClient,
                         ApiSecurityConfig limits) {
        this.queryPipeline = Objects.requireNonNull(queryPipeline, "QueryPipeline cannot be null");
        this.config = config;
        this.executorService = executorService != null ? executorService : Executors.newVirtualThreadPerTaskExecutor();
        this.queryRegistry = queryRegistry;
        this.embeddingClient = embeddingClient;
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        // Initialize default timeout from config visibility settings, fallback to 30 seconds
        this.defaultTimeoutMs = config != null
            ? config.visibility().getQueryTimeoutMs()
            : 30_000L;
        this.hybridRetrieval = config == null || config.query().isHybridRetrieval();
        this.parser = new QueryRequestParser(config, this.limits, this.defaultTimeoutMs);
        this.resultText = new excalibur.api.retrieval.ResultText(limits.maxResultTextChars());
        this.contexts = new QueryContexts(this.limits, this.resultText);
        this.lexicalAnswer = new LexicalAnswer(this.parser, this.executorService,
            this.queryRegistry, this.contexts);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        String queryId = UUID.randomUUID().toString();
        long startTime = System.nanoTime();

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withCorrelationId(correlationId)
                .withQueryId(queryId)
                .build()) {

            try {
                // Checked before the body is even read, and answered with the reason. Which
                // retrieval paths a node has is a property of the deployment rather than of the
                // request, so a node that has none meets this on every query until someone
                // changes the configuration. Saying only "not available" leaves an operator to
                // guess which of the several things behind a 503 it was, while the startup log
                // two hundred lines up already named it.
                if (embeddingClient == null) {
                    // No vectors, but possibly a lexical index. Answering from BM25 and query
                    // expansion is the whole point of not requiring the corpus to be embedded:
                    // this node has an index, it simply has a different signal in it.
                    excalibur.api.retrieval.LexicalQueryService lexical = this.lexicalQueryService;
                    if (lexical != null) {
                        lexicalAnswer.answer(exchange, lexical, queryId, correlationId, startTime);
                        return;
                    }
                    LOGGER.error("Query endpoint is not ready: {}", UNCONFIGURED_QUERY_REASON);
                    sendError(exchange, 503, UNCONFIGURED_QUERY_REASON);
                    return;
                }

                // Parse and validate the request body. No network call happens here:
                // embedding is a blocking call and must be paid for out of the
                // request's own timeout budget, not before the clock starts.
                String requestBody = readRequestBody(exchange);
                QueryRequestParser.ParsedQuery parsed = parser.parse(requestBody);
                // Resolved here rather than at render time so a request asking for text, a
                // page or an explanation this node cannot serve is refused before any search
                // runs, and so every exit below - fused, vector, or the BM25 fallback -
                // answers through the same page, total and explanation.
                QueryContext context = contexts.of(requestBody, parsed);

                LOGGER.debug("Processing query: topK={}, offset={}, timeout={}ms",
                    parsed.topK(), context.offset(), parsed.timeoutMs());

                // The BM25 signal needs no embedding, so it is started before the embedding call
                // rather than after the pipeline returns. Run in series the two signals would
                // cost the sum of their latencies, and their independence is the whole premise.
                excalibur.api.retrieval.LexicalSignal lexicalSignal =
                    startLexicalSignal(parsed, context.depth());
                // Non-null only once both signals have actually produced a ranking. Every path
                // that leaves it null - no lexical index, fusion turned off, the BM25 search
                // failing or missing the budget - answers from vector search and says so, rather
                // than reporting a fusion that did not happen.
                List<excalibur.api.retrieval.HybridFusion.Result> fused = null;
                // Kept beside the fused list because an explanation needs the ranking each
                // signal produced on its own, and fusion is where those stop existing.
                List<excalibur.api.retrieval.LexicalQueryService.Hit> hits = null;

                // Create cancellation token
                CancellationToken token = new CancellationToken();

                // Register query with registry for cancellation support
                if (queryRegistry != null) {
                    queryRegistry.register(queryId, token, correlationId);
                }
                // Cancelling a query has to reach both signals. Without this the vector half
                // stops and the BM25 half keeps a Lucene search running for a request nobody
                // is waiting for.
                if (lexicalSignal != null) {
                    token.addListener(lexicalSignal::cancel);
                }

                QueryResponse response;
                long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMs());
                // Fingerprinted before the search and stored against afterwards. Taking the
                // fingerprint again at store time would let a document ingested while this
                // query ran make a pre-ingest answer look like a post-ingest one.
                QueryCacheLookup lookup = QueryCacheLookup.of(resultCache, queryPipeline,
                    parsed, context, lexicalSignal != null, exchange);
                try {
                    if (lookup.hit() != null) {
                        // The embedding call and the whole segment fan-out are both skipped;
                        // the BM25 half below still runs, because its own freshness is not
                        // something this fingerprint can speak for.
                        response = lookup.hit().withQueryId(queryId)
                            .withQueryTimeMs((System.nanoTime() - startTime) / 1_000_000);
                        hits = lexicalSignal == null ? null
                            : lexicalSignal.await(Deadline.remainingMillis(deadlineNanos));
                        fused = fuse(lexicalSignal, response.results(), hits, context.depth());
                        sendAnswer(exchange, response, fused, hits, context);
                        return;
                    }
                    float[] vector = embedWithinBudget(parsed.query(), correlationId, deadlineNanos);
                    if (vector == null) {
                        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                        LOGGER.warn("Query embedding timed out after {}ms", durationMs);
                        // The BM25 half needed no embedding and has usually finished by now.
                        // Answering empty would throw away a complete answer the node is
                        // holding and report the corpus as having nothing to say.
                        if (!lexicalAnswer.fallback(exchange, lexicalSignal, queryId, requestBody,
                                parsed, durationMs, context, true)) {
                            sendResponse(exchange, 200, buildTimeoutResponse(queryId, durationMs),
                                parsed.topK());
                        }
                        return;
                    }

                    // What is left after embedding, not what the client asked for: the pipeline
                    // measures its own deadline from here, so the request's timeout would put it
                    // after this handler's and the partial answer would never be waited for.
                    long budgetMs = Deadline.remainingMillis(deadlineNanos);
                    QueryRequest request = context.deepened(parsed)
                        .toRequest(vector, correlationId, budgetMs);
                    CompletableFuture<QueryResponse> future = queryPipeline.execute(request, token);

                    response = future.get(
                        budgetMs + PIPELINE_RESPONSE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                    response = response.withQueryId(queryId);
                    // Refused by the cache unless the search reached the whole corpus it
                    // meant to, inside its deadline, against the corpus it was fingerprinted
                    // at. A degraded answer replayed to later callers turns one moment's
                    // trouble into a persistent fault that outlives its cause.
                    lookup.keep(response);
                    hits = lexicalSignal == null ? null
                        : lexicalSignal.await(Deadline.remainingMillis(deadlineNanos));
                    fused = fuse(lexicalSignal, response.results(), hits, context.depth());
                } catch (TimeoutException e) {
                    // Cancel the token to propagate cancellation
                    token.cancel("Query timeout");
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.warn("Query timed out after {}ms", durationMs);
                    // Which half missed the deadline does not change what the other half is
                    // holding. Answering empty here would claim vector search produced the
                    // answer when vector search is exactly what failed.
                    if (lexicalAnswer.fallback(exchange, lexicalSignal, queryId, requestBody,
                            parsed, durationMs, context, true)) {
                        return;
                    }
                    response = buildTimeoutResponse(queryId, durationMs);
                } catch (CancellationException e) {
                    // No fallback here, unlike the timeout above: the caller withdrew the
                    // request, and handing back an answer from the signal that happened to
                    // finish is answering a question that was taken back.
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.info("Query cancelled after {}ms", durationMs);
                    response = buildCancelledResponse(queryId, durationMs);
                } catch (InterruptedException e) {
                    // Restore the flag: swallowing it leaves the carrier thread unable
                    // to observe the shutdown that interrupted it.
                    Thread.currentThread().interrupt();
                    token.cancel("Query interrupted");
                    LOGGER.warn("Query interrupted");
                    sendError(exchange, 503, "Query interrupted");
                    return;
                } catch (ExecutionException e) {
                    // A rejected key, an open circuit breaker, a 429, a 5xx, or a pipeline that
                    // threw. None of those is a fact about the corpus, and the BM25 half needed
                    // no embedding: the documented effect of a missing embedding endpoint is no
                    // dense signal, not a failed query. So this degrades exactly as a missed
                    // deadline does, and answers 500 only when there is nothing to degrade to -
                    // where 200 with an empty list would be indistinguishable from "nothing
                    // matched".
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOGGER.error("Query failed: {}", cause.getMessage(), cause);
                    if (degradeToLexical(exchange, lexicalSignal, queryId, requestBody, parsed,
                            startTime, context, deadlineNanos)) {
                        return;
                    }
                    sendError(exchange, 500, "Query execution failed");
                    return;
                } catch (IllegalStateException e) {
                    // An empty or non-finite embedding: the vector half cannot produce a
                    // meaningful distance from it, which makes this the same outage as one the
                    // provider reports outright. It reached the handler's own 503 before, which
                    // threw away the BM25 answer this node was already holding.
                    LOGGER.error("Query embedding is unusable: {}", e.getMessage(), e);
                    if (degradeToLexical(exchange, lexicalSignal, queryId, requestBody, parsed,
                            startTime, context, deadlineNanos)) {
                        return;
                    }
                    sendError(exchange, 503, "Query service is not available");
                    return;
                } finally {
                    // A no-op once it has been consumed; on every path that did not consume it
                    // this is what stops an abandoned search outliving the request that asked
                    // for it.
                    if (lexicalSignal != null) {
                        lexicalSignal.cancel();
                    }
                    // Unregister query when complete
                    if (queryRegistry != null) {
                        queryRegistry.unregister(queryId);
                    }
                }

                sendAnswer(exchange, response, fused, hits, context);

            } catch (RequestBody.TooLargeException e) {
                // Well-formed and too big is not the same failure as malformed: 400 tells the
                // caller not to send it again, 413 tells it to split the payload.
                LOGGER.warn("Refused an oversized query body: {}", e.getMessage());
                sendError(exchange, 413, e.getMessage());

            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid request: {}", e.getMessage());
                sendError(exchange, 400, "Bad Request: " + e.getMessage());

            } catch (IllegalStateException e) {
                // The one state this endpoint knows how to explain is handled by the guard above,
                // which answers with its reason. Anything else reaching here is unexpected, and
                // its message is not written to be read by a client, so it is logged rather than
                // returned.
                LOGGER.error("Query endpoint is not ready: {}", e.getMessage(), e);
                sendError(exchange, 503, "Query service is not available");

            } catch (Exception e) {
                LOGGER.error("Internal error: {}", e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
        // MDC context is automatically cleared when ctx.close() is called
    }

    /**
     * Points this endpoint at a lexical index over the ingested corpus.
     *
     * @param service The service, or null to serve vector search only
     */
    public void setLexicalQueryService(excalibur.api.retrieval.LexicalQueryService service) {
        this.lexicalQueryService = service;
    }

    /**
     * Points this endpoint at a cache of previously computed answers.
     *
     * <p>Every entry is valid only while the corpus fingerprint it was computed against is
     * unchanged, which is checked against the live corpus on each lookup - see
     * {@code CorpusVersion}. Passing null turns caching off and returns the endpoint to
     * searching on every request.</p>
     *
     * @param cache The cache, or null to answer every query from the index
     */
    public void setQueryResultCache(excalibur.query.cache.QueryResultCache cache) {
        this.resultCache = cache;
    }

    /**
     * Points this endpoint at the storage a result's text is read back from.
     *
     * <p>Without it {@code include_text} is refused rather than quietly dropped: a caller that
     * asked for passages and was handed identifiers will quote text it never received.</p>
     *
     * @param chunkText Reads a chunk's text by identifier, or null to serve identifiers only
     */
    public void setChunkText(java.util.function.Function<String, String> chunkText) {
        this.contexts.chunkText(chunkText);
    }

    /**
     * Points this endpoint at the expander its searching half widens queries with.
     *
     * <p>Must be the very instance the lexical index was given, and that instance must
     * remember what it produced - see {@code CachingQueryExpander}. Expansion is a generation
     * from a chat model: a second, separate expander here would bill the operator twice and
     * report terms the search never used.</p>
     *
     * <p>Without it an explained answer reports the expansion as {@code unreported} rather
     * than as absent, because "this node widened nothing" and "this node cannot say what it
     * widened" are different facts about a ranking.</p>
     *
     * @param expander The expander, or null on a node that reports nothing about expansion
     */
    public void setQueryExpander(io.excalibur.retrieval.expand.QueryExpander expander) {
        this.contexts.expander(expander);
    }

    /**
     * Fuses the two rankings, degrading to the vector one rather than failing the query.
     *
     * @param lexicalSignal The BM25 search, or null when none was started
     * @param dense         What the vector pipeline produced, best first
     * @param hits          What BM25 produced, or null when that signal gave nothing
     * @param depth         How deep the fused ranking is built, being offset plus page size
     * @return The fused ranking, or null when only the vector signal answered
     */
    private static List<excalibur.api.retrieval.HybridFusion.Result> fuse(
            excalibur.api.retrieval.LexicalSignal lexicalSignal,
            List<QueryResultItem> dense,
            List<excalibur.api.retrieval.LexicalQueryService.Hit> hits, int depth) {
        if (lexicalSignal == null || hits == null) {
            return null;
        }
        try {
            return excalibur.api.retrieval.HybridFusion.fuse(dense, hits, depth,
                lexicalSignal.service().rrfK());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not fuse the two signals; answering from vector search alone: {}",
                e.getMessage(), e);
            return null;
        }
    }

    /**
     * Writes the answer, from whichever signals produced it.
     *
     * <p>One exit for both, because the page, the total and the explanation are the same
     * questions whichever ranking is being returned, and a second exit is where the two
     * answers start differing about them.</p>
     *
     * @param exchange The request to answer
     * @param response What the query pipeline produced, for its coverage and timings
     * @param fused    The fused ranking, or null when only vector search answered
     * @param hits     What BM25 produced, for the explanation's per-signal ranks
     * @param context  What this request asked for beyond the search
     * @throws IOException if the response cannot be written
     */
    private void sendAnswer(HttpExchange exchange, QueryResponse response,
                            List<excalibur.api.retrieval.HybridFusion.Result> fused,
                            List<excalibur.api.retrieval.LexicalQueryService.Hit> hits,
                            QueryContext context) throws IOException {
        // A search that was cut short cannot establish that there was nothing more to find,
        // so its total is a floor however many results it came back with.
        boolean complete = ResultTotal.searchWasComplete(response.coverage(),
            response.timedOut(), response.cancelled());
        if (fused != null) {
            List<excalibur.api.retrieval.HybridFusion.Result> page =
                context.page(fused.size()).slice(fused);
            QueryExplanation explanation = context.explaining()
                ? QueryExplainer.hybrid(page, response.results(), hits, context.passages(),
                    context.expansion(true))
                : null;
            sendJson(exchange, 200, QueryResponseJson.hybrid(response, page,
                context.answer(fused.size(), complete, explanation)));
            return;
        }
        List<QueryResultItem> produced = response.results();
        List<QueryResultItem> page = context.page(produced.size()).slice(produced);
        QueryExplanation explanation = context.explaining()
            ? QueryExplainer.vector(page, context.offset(), context.passages(),
                context.expansion(false))
            : null;
        sendJson(exchange, 200, QueryResponseJson.vector(response, page,
            context.answer(produced.size(), complete, explanation)));
    }

    /**
     * Answers from the BM25 half after the vector half failed outright.
     *
     * <p>Distinct from the timeout paths in one way that matters: a failure can leave budget on
     * the clock. A circuit breaker refuses in microseconds, so the BM25 search that started
     * alongside the embedding call may well still be running, and reading only what it has
     * finished would report "no answer" for a search that was about to produce one. So what is
     * left of the request's own deadline is waited out first, and no longer.</p>
     *
     * @param exchange      The request to answer
     * @param lexicalSignal The BM25 search, or null when none was started
     * @param queryId       The identifier this query was registered under
     * @param requestBody   The raw request, for the fields this path cannot honour
     * @param parsed        The validated request
     * @param startTime     {@link System#nanoTime()} value the request's budget runs from
     * @param context       What this request asked for beyond the search
     * @param deadlineNanos {@link System#nanoTime()} value the request must finish by
     * @return true when an answer was sent, false when this signal had none to give
     * @throws IOException if the response cannot be written
     */
    private boolean degradeToLexical(HttpExchange exchange,
                                     excalibur.api.retrieval.LexicalSignal lexicalSignal,
                                     String queryId, String requestBody,
                                     QueryRequestParser.ParsedQuery parsed, long startTime,
                                     QueryContext context,
                                     long deadlineNanos) throws IOException {
        if (lexicalSignal == null) {
            return false;
        }
        try {
            lexicalSignal.await(Deadline.remainingMillis(deadlineNanos));
        } catch (InterruptedException e) {
            // Restore the flag and let the caller answer: building a 200 on a thread carrying
            // an unanswered interrupt closes the channel mid-write.
            Thread.currentThread().interrupt();
            return false;
        }
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        return lexicalAnswer.fallback(exchange, lexicalSignal, queryId, requestBody, parsed,
            durationMs, context, false);
    }

    /**
     * Starts the BM25 half of a hybrid answer, when this node is to produce one.
     *
     * <p>Null means the answer will come from vector search alone, whether because the node
     * keeps no lexical index, because an operator turned fusion off, or because
     * {@link excalibur.api.retrieval.LexicalSignal} declined to start another search. The rest
     * of the request reads that null rather than inferring it from an empty hit list, which is
     * a different thing.</p>
     *
     * @param parsed The validated request
     * @param depth  How deep this signal is asked to rank, being offset plus page size
     * @return The running search, or null when only vector search will answer
     */
    private excalibur.api.retrieval.LexicalSignal startLexicalSignal(
            QueryRequestParser.ParsedQuery parsed, int depth) {
        if (!hybridRetrieval) {
            return null;
        }
        return excalibur.api.retrieval.LexicalSignal.start(this.lexicalQueryService,
            executorService, parsed.query(), depth);
    }

    /**
     * Reads the request body from the HTTP exchange.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        return RequestBody.read(exchange);
    }


    /**
     * Generates the query embedding without exceeding the request's deadline.
     *
     * <p>Embedding is an outbound network call. Running it on the handler thread
     * before the timeout starts lets a slow or hanging embedding provider pin the
     * request for as long as its own client timeout allows, no matter what the
     * caller asked for.</p>
     *
     * @param query         The query text
     * @param correlationId Correlation ID for the embedding call
     * @param deadlineNanos {@link System#nanoTime()} value the request must finish by
     * @return The embedding vector, or null when the deadline passed first
     * @throws InterruptedException if the calling thread is interrupted
     * @throws ExecutionException if embedding failed
     */
    private float[] embedWithinBudget(String query, String correlationId, long deadlineNanos)
            throws InterruptedException, ExecutionException {
        Future<float[]> pending = executorService.submit(() -> embeddingClient.embed(query, correlationId));
        float[] vector;
        try {
            vector = pending.get(Deadline.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return null;
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Embedding generation returned an empty vector for query text");
        }
        validateVectorValues(vector);
        return vector;
    }

    /**
     * Validates that all values in a vector are finite.
     *
     * <p><strong>Domain Correctness:</strong> Vector values must be finite for
     * meaningful distance computation. NaN and Infinity values produce undefined
     * results in similarity calculations.</p>
     *
     * <p>The vector always comes from the server's own embedding client, so a
     * non-finite component is a server-side fault and is reported as one.</p>
     *
     * @param vector The vector to validate
     * @throws IllegalStateException if any value is NaN or Infinity
     */
    private void validateVectorValues(float[] vector) {
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalStateException(
                    "Embedding contains non-finite value at index " + i + ": " + vector[i]);
            }
        }
    }

    /**
     * Sends an answer that has no results and no page behind it.
     *
     * <p>For the exits where the search produced nothing and could not establish that there
     * was nothing to produce. The envelope is the same one every other answer uses, because a
     * client that has to test for the page block on some answers cannot use it on any.</p>
     *
     * @param exchange   The request to answer
     * @param statusCode The status to answer with
     * @param response   The empty answer
     * @param limit      The page size the request asked for
     * @throws IOException if the response cannot be written
     */
    private void sendResponse(HttpExchange exchange, int statusCode, QueryResponse response,
                              int limit) throws IOException {
        sendJson(exchange, statusCode, QueryResponseJson.vector(response, response.results(),
            QueryAnswer.nothing(limit)));
    }

    /**
     * Sends an already-rendered JSON body.
     */
    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        ResponseBody.sendJson(exchange, statusCode, json);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        ResponseBody.sendError(exchange, statusCode, message);
    }

    /**
     * A coverage builder carrying what the index holds, or an empty one if it cannot be read.
     *
     * <p>A response that reports no segments at all - because the query was abandoned before it
     * could look - reads exactly like one served by an empty index. The census is the only part
     * of a coverage block that is true of a query that never ran.</p>
     *
     * @return A builder to finish
     */
    private IndexCoverage.Builder indexCensus() {
        try {
            IndexCoverage.Builder census = queryPipeline.segmentCensus();
            if (census != null) {
                return census;
            }
        } catch (RuntimeException e) {
            // Whatever stopped the query may be the same thing that stops the catalog being
            // read. The caller is still owed the response.
            LOGGER.debug("Index census unavailable: {}", e.toString());
        }
        return IndexCoverage.builder();
    }

    /**
     * Builds a timeout response.
     */
    private QueryResponse buildTimeoutResponse(String queryId, long durationMs) {
        // Not computeCoverage(): the query read nothing, whatever the index holds.
        IndexCoverage coverage = indexCensus()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .build();

        return new QueryResponse(
            queryId,
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            true  // timedOut
        );
    }

    /**
     * Builds a cancelled response.
     */
    private QueryResponse buildCancelledResponse(String queryId, long durationMs) {
        IndexCoverage coverage = indexCensus()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .build();

        return new QueryResponse(
            queryId,
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            false, // timedOut: the deadline is not what ended this
            true   // cancelled
        );
    }

    /**
     * Shuts down the endpoint and releases resources.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

