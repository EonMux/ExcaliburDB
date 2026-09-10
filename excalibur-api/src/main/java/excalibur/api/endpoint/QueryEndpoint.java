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
 * <p>QueryEndpoint handles POST requests to /v1/query for vector similarity
 * search with timeout and cancellation support.</p>
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
 *   "coverage": {
 *     "total_segments": 1024,
 *     "active_segments": 980,
 *     "indexing_segments": 40,
 *     "warming_segments": 4,
 *     "compacting_segments": 0,
 *     "tombstoned_segments": 0,
 *     "estimated_corpus_coverage": 0.9600,
 *     "segments_searched": 980,
 *     "segments_excluded": 44,
 *     "segments_with_errors": 0,
 *     "segments_timed_out": 0,
 *     "tail_scan_entries": 12500,
 *     "degraded": false
 *   },
 *   "timed_out": false,
 *   "query_time_ms": 42
 * }
 * }</pre>
 *
 * <p>A node with no embedding client answers from the lexical index instead. The shape is the
 * same, with a {@code "retrieval": "lexical"} field the vector path does not emit, no
 * {@code segment_id} on a result, and {@code degraded} always true.</p>
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
     * Why a node answers 503 to every query, and what to change.
     *
     * <p>Returned to the client, not only logged. It names a configuration key and no internal
     * detail: there is no path, no address and no state of this process in it, which is what makes
     * it safe to hand out. {@code /v1/ingest/status} already reports the same gap the same way in
     * {@code last_error}, and a node that explains it on one endpoint and not the other sends
     * whoever hits the other one looking through the logs for it.</p>
     */
    /**
     * Lexical retrieval over ingested chunks, or null when this node keeps no such index.
     *
     * <p>Set separately from construction because it is optional and orthogonal: a node may have
     * embeddings, a lexical index, both, or in the degenerate case neither.</p>
     */
    private volatile excalibur.api.retrieval.LexicalQueryService lexicalQueryService;

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
                        answerLexically(exchange, lexical, queryId, startTime);
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
                ParsedQuery parsed = parseRequest(requestBody);

                LOGGER.debug("Processing query: topK={}, timeout={}ms",
                    parsed.topK(), parsed.timeoutMs());

                // Create cancellation token
                CancellationToken token = new CancellationToken();

                // Register query with registry for cancellation support
                if (queryRegistry != null) {
                    queryRegistry.register(queryId, token, correlationId);
                }

                QueryResponse response;
                try {
                    long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMs());

                    float[] vector = embedWithinBudget(parsed.query(), correlationId, deadlineNanos);
                    if (vector == null) {
                        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                        LOGGER.warn("Query embedding timed out after {}ms", durationMs);
                        sendResponse(exchange, 200, buildTimeoutResponse(queryId, durationMs));
                        return;
                    }

                    QueryRequest request = parsed.toRequest(vector, correlationId);
                    CompletableFuture<QueryResponse> future = queryPipeline.execute(request, token);

                    response = future.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
                    response = response.withQueryId(queryId);
                } catch (TimeoutException e) {
                    // Cancel the token to propagate cancellation
                    token.cancel("Query timeout");
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.warn("Query timed out after {}ms", durationMs);
                    response = buildTimeoutResponse(queryId, durationMs);
                } catch (CancellationException e) {
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
                    // Unregister query when complete
                    if (queryRegistry != null) {
                        queryRegistry.unregister(queryId);
                    }
                }

                // Send response
                sendResponse(exchange, 200, response);

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
     * Answers a query from the lexical index alone.
     *
     * <p>Reports the same response shape as vector search, through the same coverage renderer
     * and over numbers the index is asked for rather than assumed - see {@link LexicalCoverage}.
     * Coverage is always marked degraded: the answer is real, and it came from fewer signals than
     * a fully configured node would use. Saying so is what lets a caller tell "this is all there
     * was" from "this is all we looked at".</p>
     */
    private void answerLexically(HttpExchange exchange,
                                 excalibur.api.retrieval.LexicalQueryService lexical,
                                 String queryId, long startTime) throws IOException {
        String requestBody = readRequestBody(exchange);
        ParsedQuery parsed;
        try {
            parsed = parseRequest(requestBody);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }

        List<excalibur.api.retrieval.LexicalQueryService.Hit> hits =
            lexical.search(parsed.query(), parsed.topK());
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"query_id\": \"").append(JsonText.escape(queryId)).append("\", ");
        sb.append("\"results\": [");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"document_id\": \"")
              .append(JsonText.escape(hits.get(i).chunkId()))
              .append("\", \"score\": ").append(JsonText.number(hits.get(i).score(), 6))
              .append("}");
        }
        sb.append("], ");
        sb.append("\"retrieval\": \"lexical\", ");
        appendCoverage(sb, LexicalCoverage.of(lexical.stats()));
        sb.append("\"timed_out\": false, ");
        sb.append("\"query_time_ms\": ").append(durationMs);
        sb.append("}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, 200, bytes);
    }

    /**
     * Reads the request body from the HTTP exchange.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        return RequestBody.read(exchange);
    }

    /**
     * Parses and validates the JSON request body.
     *
     * <p><strong>Domain Correctness:</strong> validates that:</p>
     * <ul>
     *   <li>{@code query} is present, non-empty, and no longer than
     *       {@code excalibur.api.max-query-chars} - the server embeds it with the
     *       same model ingestion used, so a client-supplied vector is not accepted</li>
     *   <li>{@code top_k} is positive and within the configured maximum</li>
     *   <li>{@code threshold} and {@code llm_threshold} are in [0.0, 1.0]</li>
     *   <li>{@code timeout_ms} is positive and within the configured maximum</li>
     *   <li>{@code oversampling_factor} is at least 1</li>
     *   <li>{@code recall_target} is in [0.0, 1.0]</li>
     * </ul>
     *
     * @param json The request body
     * @return The validated request fields
     * @throws IllegalArgumentException if any field is missing or out of range
     * @throws IllegalStateException if the server cannot embed query text
     */
    private ParsedQuery parseRequest(String json) {
        String query = JsonText.string(json, "query");

        // Use config default for top_k if config is provided, otherwise fallback to 10
        int defaultTopK = config != null ? config.query().getDefaultTopK() : 10;
        int topK = JsonText.integer(json, "top_k", defaultTopK);

        // Use config default for threshold if config is provided
        float defaultThreshold = config != null
            ? (float) config.query().getThreshold()
            : 0.0f;
        float threshold = JsonText.floatValue(json, "threshold", defaultThreshold);
        long timeoutMs = JsonText.longValue(json, "timeout_ms", defaultTimeoutMs);

        // Use config default for include_partial_segments if config is provided
        boolean defaultIncludePartial = config != null
            ? config.query().isIncludePartialSegments()
            : true;
        boolean includePartial = JsonText.booleanValue(json, "include_partial_segments", defaultIncludePartial);

        boolean enableLlmScoring = JsonText.booleanValue(json, "enable_llm_scoring", true);

        // Use config default for llm_threshold if config is provided
        float defaultLlmThreshold = config != null
            ? (float) config.llm().getRelevancyThreshold()
            : 0.3f;
        float llmThreshold = JsonText.floatValue(json, "llm_threshold", defaultLlmThreshold);

        // Parse oversampling_factor with default from QueryOptions
        int oversamplingFactor = JsonText.integer(json, "oversampling_factor",
            QueryOptions.DEFAULT_OVERSAMPLING_FACTOR);

        // Use config default for recall_target if config is provided
        double defaultRecallTarget = config != null ? config.query().getRecallTarget() : 0.95;
        double recallTarget = JsonText.doubleValue(json, "recall_target", defaultRecallTarget);

        // 'query' text is required. The server embeds it using the SAME model
        // that ingestion uses, guaranteeing the query vector lives in the same
        // embedding space as the indexed vectors. Accepting a pre-computed vector
        // from clients is NOT supported: the client cannot guarantee their vector
        // matches the server's ingestion embedding model, and a mismatch produces
        // silently wrong results. Embedding is a server invariant.
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Field 'query' is required and must be non-empty");
        }
        // Every other bound here exists because the value sizes something inside this process.
        // This one exists because the value leaves it: the text is the body of an embedding call,
        // and on the lexical path of an expansion call as well, so an unbounded query is an
        // outbound request whose size the caller picks and the operator is billed for. The
        // BM25 pass is bounded by it too - term count is not what top_k limits.
        if (query.length() > limits.maxQueryChars()) {
            throw new IllegalArgumentException("Field 'query' must be at most "
                + limits.maxQueryChars() + " characters: " + query.length());
        }
        // Deliberately no embedding-client check here. Parsing a request is not the same
        // question as whether this node can run a vector search over it: handle() answers that
        // before it reaches the vector path, and the lexical path parses the very same body and
        // needs no embedding at all. Refusing here made a node that could answer from BM25
        // report itself unconfigured.

        // Validate topK. This value sizes priority queues and result buffers inside
        // the pipeline, so an unbounded value is an allocation the caller controls.
        if (topK <= 0) {
            throw new IllegalArgumentException("Field 'top_k' must be positive: " + topK);
        }
        if (topK > limits.maxTopK()) {
            throw new IllegalArgumentException(
                "Field 'top_k' must be at most " + limits.maxTopK() + ": " + topK);
        }

        // Validate threshold
        if (Float.isNaN(threshold) || threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("Field 'threshold' must be in range [0.0, 1.0]: " + threshold);
        }

        // Validate timeoutMs
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Field 'timeout_ms' must be positive: " + timeoutMs);
        }
        if (timeoutMs > limits.maxQueryTimeoutMs()) {
            throw new IllegalArgumentException(
                "Field 'timeout_ms' must be at most " + limits.maxQueryTimeoutMs() + ": " + timeoutMs);
        }

        // Validate llmThreshold
        if (Float.isNaN(llmThreshold) || llmThreshold < 0.0f || llmThreshold > 1.0f) {
            throw new IllegalArgumentException("Field 'llm_threshold' must be in range [0.0, 1.0]: " + llmThreshold);
        }

        // Validate oversamplingFactor
        if (oversamplingFactor < 1) {
            throw new IllegalArgumentException("Field 'oversampling_factor' must be at least 1: " + oversamplingFactor);
        }

        // Validate recallTarget
        if (Double.isNaN(recallTarget) || recallTarget < 0.0 || recallTarget > 1.0) {
            throw new IllegalArgumentException("Field 'recall_target' must be in range [0.0, 1.0]: " + recallTarget);
        }

        return new ParsedQuery(query, topK, recallTarget, threshold, timeoutMs,
            includePartial, enableLlmScoring, llmThreshold, oversamplingFactor);
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
        String json = toJson(response);
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
     * Converts QueryResponse to JSON.
     */
    private String toJson(QueryResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Query ID (for cancellation support)
        if (response.queryId() != null) {
            sb.append("\"query_id\": \"").append(JsonText.escape(response.queryId())).append("\", ");
        }

        // Results. document_id carries the document's own identifier - the string the
        // ingestion pipeline assigned - not the HNSW node ID, which is an internal
        // handle that compaction renumbers and that no client can look anything up by.
        sb.append("\"results\": [");
        for (int i = 0; i < response.results().size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            QueryResultItem item = response.results().get(i);
            sb.append("{\"document_id\": \"").append(JsonText.escape(documentIdOf(item)))
                .append("\", \"score\": ").append(JsonText.number(item.score(), 6))
                .append(", \"segment_id\": ").append(item.segmentId())
                .append("}");
        }
        sb.append("], ");

        appendCoverage(sb, response.coverage());

        // Query metadata
        sb.append("\"timed_out\": ").append(response.timedOut()).append(", ");
        sb.append("\"query_time_ms\": ").append(response.queryTimeMs());

        sb.append("}");
        return sb.toString();
    }

    /**
     * Renders the coverage block.
     *
     * <p>One renderer for both retrieval paths. A client that has to branch on which path
     * answered in order to read how much of the corpus was searched cannot use the field for
     * what it is for, so the shape is not allowed to differ between them.</p>
     *
     * @param sb       The response under construction
     * @param coverage What the query covered
     */
    private static void appendCoverage(StringBuilder sb, IndexCoverage coverage) {
        sb.append("\"coverage\": {");
        sb.append("\"total_segments\": ").append(coverage.totalSegments()).append(", ");
        sb.append("\"active_segments\": ").append(coverage.activeSegments()).append(", ");
        sb.append("\"indexing_segments\": ").append(coverage.indexingSegments()).append(", ");
        sb.append("\"warming_segments\": ").append(coverage.warmingSegments()).append(", ");
        sb.append("\"compacting_segments\": ").append(coverage.compactingSegments()).append(", ");
        sb.append("\"tombstoned_segments\": ").append(coverage.tombstonedSegments()).append(", ");
        sb.append("\"estimated_corpus_coverage\": ")
            .append(JsonText.number(coverage.estimatedCorpusCoverage(), 4)).append(", ");
        // What this query did, as opposed to what the catalog holds. Without these an empty
        // answer reads the same whether the query searched the whole corpus and found nothing or
        // was never allowed to look at any of it.
        sb.append("\"segments_searched\": ").append(coverage.segmentsSearched()).append(", ");
        sb.append("\"segments_excluded\": ").append(coverage.segmentsExcluded()).append(", ");
        sb.append("\"segments_with_errors\": ").append(coverage.segmentsWithErrors()).append(", ");
        sb.append("\"segments_timed_out\": ").append(coverage.segmentsTimedOut()).append(", ");
        sb.append("\"tail_scan_entries\": ").append(coverage.tailScanEntries()).append(", ");
        sb.append("\"degraded\": ").append(coverage.degraded());
        sb.append("}, ");
    }

    /**
     * Returns the identifier a client can use to fetch the document again.
     *
     * @param item The result item
     * @return The string document ID when the pipeline knows one, otherwise the
     *         numeric ID rendered as a string
     */
    private static String documentIdOf(QueryResultItem item) {
        String documentIdStr = item.documentIdStr();
        if (documentIdStr != null && !documentIdStr.isEmpty()) {
            return documentIdStr;
        }
        return Long.toString(item.documentId());
    }

    /**
     * Builds a timeout response.
     */
    private QueryResponse buildTimeoutResponse(String queryId, long durationMs) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
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
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            queryId,
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            false
        );
    }

    /**
     * The validated fields of one query request.
     *
     * <p>Holds everything the caller supplied, already range-checked, but not the
     * embedding: that is produced later, inside the request's timeout budget.</p>
     *
     * @param query               Raw query text
     * @param topK                Number of results requested
     * @param recallTarget        Target recall
     * @param threshold           Minimum score
     * @param timeoutMs           Request budget in milliseconds
     * @param includePartial      Whether partially indexed segments may contribute
     * @param enableLlmScoring    Whether Stage 5 LLM scoring runs
     * @param llmThreshold        Minimum LLM relevancy score
     * @param oversamplingFactor  Candidate oversampling factor
     */
    private record ParsedQuery(String query, int topK, double recallTarget, float threshold,
                               long timeoutMs, boolean includePartial, boolean enableLlmScoring,
                               float llmThreshold, int oversamplingFactor) {

        /**
         * Builds the pipeline request once the embedding is available.
         *
         * @param vector        The query embedding
         * @param correlationId Correlation ID for logging
         * @return The pipeline request
         */
        QueryRequest toRequest(float[] vector, String correlationId) {
            return QueryRequest.builder()
                .queryText(query)
                .queryVector(vector)
                .topK(topK)
                .recallTarget(recallTarget)
                .threshold(threshold)
                .timeoutMs(timeoutMs)
                .includePartialSegments(includePartial)
                .enableLlmScoring(enableLlmScoring)
                .llmThreshold(llmThreshold)
                .oversamplingFactor(oversamplingFactor)
                .correlationId(correlationId)
                .build();
        }
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

