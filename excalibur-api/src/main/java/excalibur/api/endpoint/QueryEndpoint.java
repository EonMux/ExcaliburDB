package excalibur.api.endpoint;

import excalibur.api.protocol.RequestBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryRegistry;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.api.protocol.JsonText;
import excalibur.api.retrieval.LexicalCoverage;
import excalibur.api.protocol.ResponseBody;
import excalibur.api.security.ApiSecurityConfig;
import io.excalibur.config.Config;
import io.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *   "threshold": 0.5,
 *   "timeout_ms": 5000,
 *   "include_partial_segments": true,
 *   "enable_llm_scoring": true
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
 *   "timed_out": false,
 *   "cancelled": false,
 *   "query_time_ms": 42
 * }
 * }</pre>
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
 * vector pipeline that misses the deadline is answered from the BM25 half, which needed no
 * embedding and has usually finished, with {@code "retrieval": "lexical"} and
 * {@code "timed_out": true}. A cancelled request is the one exception: it is answered by
 * neither, because a withdrawn question is not answered. What the field never reports is a
 * fusion that did not happen.</p>
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
                        answerLexically(exchange, lexical, queryId, correlationId, startTime);
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

                LOGGER.debug("Processing query: topK={}, timeout={}ms",
                    parsed.topK(), parsed.timeoutMs());

                // The BM25 signal needs no embedding, so it is started before the embedding call
                // rather than after the pipeline returns. Run in series the two signals would
                // cost the sum of their latencies, and their independence is the whole premise.
                excalibur.api.retrieval.LexicalSignal lexicalSignal =
                    startLexicalSignal(parsed);
                // Non-null only once both signals have actually produced a ranking. Every path
                // that leaves it null - no lexical index, fusion turned off, the BM25 search
                // failing or missing the budget - answers from vector search and says so, rather
                // than reporting a fusion that did not happen.
                List<excalibur.api.retrieval.HybridFusion.Result> fused = null;

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
                try {
                    float[] vector = embedWithinBudget(parsed.query(), correlationId, deadlineNanos);
                    if (vector == null) {
                        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                        LOGGER.warn("Query embedding timed out after {}ms", durationMs);
                        // The BM25 half needed no embedding and has usually finished by now.
                        // Answering empty would throw away a complete answer the node is
                        // holding and report the corpus as having nothing to say.
                        if (!sendLexicalFallback(exchange, lexicalSignal, queryId, requestBody,
                                parsed.topK(), durationMs)) {
                            sendResponse(exchange, 200, buildTimeoutResponse(queryId, durationMs));
                        }
                        return;
                    }

                    // What is left after embedding, not what the client asked for: the pipeline
                    // measures its own deadline from here, so the request's timeout would put it
                    // after this handler's and the partial answer would never be waited for.
                    long budgetMs = remainingMillis(deadlineNanos);
                    QueryRequest request = parsed.toRequest(vector, correlationId, budgetMs);
                    CompletableFuture<QueryResponse> future = queryPipeline.execute(request, token);

                    response = future.get(
                        budgetMs + PIPELINE_RESPONSE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                    response = response.withQueryId(queryId);
                    fused = lexicalSignal == null ? null : lexicalSignal.fuseWith(
                        response.results(), parsed.topK(), remainingMillis(deadlineNanos));
                } catch (TimeoutException e) {
                    // Cancel the token to propagate cancellation
                    token.cancel("Query timeout");
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.warn("Query timed out after {}ms", durationMs);
                    // Which half missed the deadline does not change what the other half is
                    // holding. Answering empty here would claim vector search produced the
                    // answer when vector search is exactly what failed.
                    if (sendLexicalFallback(exchange, lexicalSignal, queryId, requestBody,
                            parsed.topK(), durationMs)) {
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
                    // A failed query is a failure. Reporting it as 200 with an empty
                    // result list is indistinguishable from "nothing matched".
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOGGER.error("Query failed: {}", cause.getMessage(), cause);
                    sendError(exchange, 500, "Query execution failed");
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

                // Send response
                if (fused != null) {
                    sendJson(exchange, 200, QueryResponseJson.hybrid(response, fused));
                } else {
                    sendResponse(exchange, 200, response);
                }

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
     * Starts the BM25 half of a hybrid answer, when this node is to produce one.
     *
     * <p>Null means the answer will come from vector search alone, whether because the node
     * keeps no lexical index, because an operator turned fusion off, or because
     * {@link excalibur.api.retrieval.LexicalSignal} declined to start another search. The rest
     * of the request reads that null rather than inferring it from an empty hit list, which is
     * a different thing.</p>
     *
     * @param parsed The validated request
     * @return The running search, or null when only vector search will answer
     */
    private excalibur.api.retrieval.LexicalSignal startLexicalSignal(
            QueryRequestParser.ParsedQuery parsed) {
        if (!hybridRetrieval) {
            return null;
        }
        return excalibur.api.retrieval.LexicalSignal.start(this.lexicalQueryService,
            executorService, parsed.query(), parsed.topK());
    }

    /**
     * Answers from the BM25 half alone when the vector half could not run.
     *
     * <p>The two signals are independent, and the point of starting them together is that one
     * missing its deadline does not decide the other's fate. An embedding endpoint that hangs
     * is the common case: BM25 has already finished, the node is holding a complete answer, and
     * returning nothing reports the corpus as empty of what it actually contains.</p>
     *
     * <p>{@code timed_out} stays true. The request did miss its deadline, and the answer did
     * come from fewer signals than this node has, so both are said rather than the weaker one
     * being dressed up as a whole answer.</p>
     *
     * @param exchange      The request to answer
     * @param lexicalSignal The BM25 search, or null when none was started
     * @param queryId       The identifier this query was registered under
     * @param requestBody   The raw request, for the fields this path cannot honour
     * @param topK          Most results the caller asked for
     * @param durationMs    How long the request has taken
     * @return true when an answer was sent, false when this signal had none to give
     */
    private boolean sendLexicalFallback(HttpExchange exchange,
                                        excalibur.api.retrieval.LexicalSignal lexicalSignal,
                                        String queryId, String requestBody, int topK,
                                        long durationMs) throws IOException {
        List<excalibur.api.retrieval.LexicalQueryService.Hit> hits =
            lexicalSignal == null ? null : lexicalSignal.ready();
        // Completed, not non-empty: BM25 running and matching nothing is a fact about the
        // corpus, and reporting it as a vector timeout instead would make the response shape
        // depend on whether the query happened to match. `retrieval` means which signals ran
        // everywhere else on this route, and it has to mean that here too.
        if (hits == null) {
            return false;
        }
        // top_k is a validated bound and is enforced here rather than assumed of the search:
        // what this signal was asked for is a detail of how the two are fused, and no detail
        // of that is allowed to hand the caller more than it asked for.
        if (hits.size() > topK) {
            hits = hits.subList(0, topK);
        }
        excalibur.query.IndexCoverage coverage;
        try {
            coverage = LexicalCoverage.of(lexicalSignal.service().stats());
        } catch (RuntimeException e) {
            // An index that answered a search and then could not describe itself is still not
            // a reason to lose the answer - but coverage that cannot be read cannot be
            // guessed at either, so this falls back to the timeout response.
            LOGGER.warn("The lexical index could not report its coverage: {}", e.getMessage());
            return false;
        }
        sendJson(exchange, 200, QueryResponseJson.lexical(queryId, hits, coverage, requestBody,
            LEXICALLY_UNHONOURED_FIELDS, true, false, durationMs));
        return true;
    }

    /**
     * Request fields the lexical path parses and validates but cannot act on.
     *
     * <p>Each one steers a stage this path does not have. {@code threshold} is the exception
     * worth spelling out: BM25 scores are not cosine similarities and are not bounded by 1.0,
     * so a floor tuned against vector scores would cut an arbitrary part of a correct ranking
     * rather than the weak tail it was meant for. They are named in the response instead of
     * being applied or silently dropped - see {@link #answerLexically}.</p>
     */
    private static final List<String> LEXICALLY_UNHONOURED_FIELDS = List.of(
        "threshold", "include_partial_segments", "enable_llm_scoring", "llm_threshold",
        "oversampling_factor");

    /**
     * Answers a query from the lexical index alone.
     *
     * <p>Reports the same response shape as vector search, through the same coverage renderer
     * and over numbers the index is asked for rather than assumed - see {@link LexicalCoverage}.
     * Coverage is always marked degraded: the answer is real, and it came from fewer signals than
     * a fully configured node would use. Saying so is what lets a caller tell "this is all there
     * was" from "this is all we looked at".</p>
     *
     * <p>Three things this path used to assert without checking. The request's budget is now
     * spent on the search rather than ignored, so {@code timed_out} reports what happened
     * instead of a literal false. The query is registered while it runs, so cancelling it
     * finds it and stops it rather than answering that it does not exist. And the fields this
     * path cannot honour are named back to the caller in {@code ignored_fields}: accepting
     * {@code threshold}, validating it, and answering as though it had been applied is the
     * part that misleads.</p>
     */
    private void answerLexically(HttpExchange exchange,
                                 excalibur.api.retrieval.LexicalQueryService lexical,
                                 String queryId, String correlationId, long startTime)
            throws IOException {
        String requestBody = readRequestBody(exchange);
        QueryRequestParser.ParsedQuery parsed;
        try {
            parsed = parser.parse(requestBody);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }

        long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMs());
        List<excalibur.api.retrieval.LexicalQueryService.Hit> hits;
        boolean timedOut = false;
        boolean cancelled = false;
        // Registered before the search starts, so DELETE /v1/query/{id} can find it for the
        // whole time it is running rather than answering "not found or already completed" for
        // a query that is neither. The listener is what makes the cancellation real rather
        // than recorded: the search runs inside Lucene, which stops on the interrupt and
        // nothing else. It is attached after the submit because it needs the future, and
        // addListener runs immediately if a cancellation arrived in between.
        CancellationToken token = new CancellationToken();
        if (queryRegistry != null) {
            queryRegistry.register(queryId, token, correlationId);
        }
        Future<List<excalibur.api.retrieval.LexicalQueryService.Hit>> pending;
        try {
            pending = executorService.submit(() -> lexical.search(parsed.query(), parsed.topK()));
        } catch (RejectedExecutionException e) {
            // The registration above is only unwound by the finally of the try below, which
            // this never reaches. Without undoing it here the entry outlives the process's
            // interest in it and DELETE reports a query that never started as live.
            if (queryRegistry != null) {
                queryRegistry.unregister(queryId);
            }
            LOGGER.warn("Could not start the lexical search: {}", e.getMessage());
            sendError(exchange, 503, "Query service is saturated");
            return;
        }
        token.addListener(() -> pending.cancel(true));
        try {
            hits = pending.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
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
            sendError(exchange, 503, "Query interrupted");
            return;
        } catch (ExecutionException e) {
            // A failed search is a failure. Reporting it as 200 with an empty result list is
            // indistinguishable from "nothing matched".
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Lexical query failed: {}", cause.getMessage(), cause);
            sendError(exchange, 500, "Query execution failed");
            return;
        } finally {
            if (queryRegistry != null) {
                queryRegistry.unregister(queryId);
            }
        }
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        sendJson(exchange, 200, QueryResponseJson.lexical(queryId, hits,
            coverageFor(lexical, timedOut, cancelled), requestBody,
            LEXICALLY_UNHONOURED_FIELDS, timedOut, cancelled, durationMs));
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
    private static excalibur.query.IndexCoverage coverageFor(
            excalibur.api.retrieval.LexicalQueryService lexical,
            boolean timedOut, boolean cancelled) {
        if (timedOut) {
            return LexicalCoverage.timedOut(lexical.stats());
        }
        if (cancelled) {
            return LexicalCoverage.cancelled(lexical.stats());
        }
        return LexicalCoverage.of(lexical.stats());
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
            vector = pending.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
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
     * Returns the milliseconds left before a deadline, never less than one.
     *
     * @param deadlineNanos {@link System#nanoTime()} value of the deadline
     * @return Remaining budget in milliseconds
     */
    private static long remainingMillis(long deadlineNanos) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(1L, remaining);
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
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, QueryResponse response) throws IOException {
        sendJson(exchange, statusCode, QueryResponseJson.vector(response));
    }

    /**
     * Sends an already-rendered JSON body.
     */
    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
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

