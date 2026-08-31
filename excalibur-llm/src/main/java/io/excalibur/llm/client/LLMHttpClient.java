package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.LLMException.ErrorType;
import io.excalibur.llm.RelevancyScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.excalibur.llm.json.JsonWriter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for LLM API operations.
 *
 * <p>Provides methods to interact with OpenAI-compatible LLM endpoints for
 * embedding generation and relevancy scoring.</p>
 *
 * <h2>Circuit Breaker Integration</h2>
 * <p>This client integrates with {@link CircuitBreaker} for resilience. When the
 * circuit breaker is open, requests fail fast without calling the LLM endpoint.</p>
 *
 * <h3>Sanitized Response Handling (CRITICAL)</h3>
 * <p>When an LLM response contains markdown fences or commentary but valid JSON
 * inside (e.g., {@code ```json\n[{"score": 0.5}]\n```}), the {@link ResponseSanitizer}
 * extracts the valid JSON. This is counted as a <strong>SUCCESS</strong>, not a failure.</p>
 *
 * <p>The circuit breaker only counts actual failures:</p>
 * <ul>
 *   <li>Network errors (timeouts, connection failures)</li>
 *   <li>HTTP 5xx server errors</li>
 *   <li>HTTP 401/403 authentication errors</li>
 *   <li>Truly unparseable responses (no valid JSON extractable)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The internal {@link HttpClient} is immutable
 * and can be safely shared across threads. The {@link CircuitBreaker} is also
 * thread-safe. All methods can be called concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP status codes: valid range [100, 599] (validated via {@link HttpStatus})</li>
 *   <li>Request timeout: positive value, default 30000ms</li>
 *   <li>Embedding vector: non-null, length in [1, maxDimension], all values finite</li>
 *   <li>Relevancy scores: each score in [0.0, 1.0] after clamping</li>
 *   <li>JSON request body: valid UTF-8, properly escaped strings</li>
 *   <li>Correlation ID: optional non-empty string, included in X-Correlation-ID header</li>
 *   <li>Sanitized-but-valid responses: counted as SUCCESS, not failure</li>
 * </ul>
 *
 * @see HttpStatus
 * @see CircuitBreaker
 * @see ResponseSanitizer
 */
public class LLMHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(LLMHttpClient.class);

    /** HTTP header for authorization */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** HTTP header for content type */
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    /** HTTP header for correlation ID */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** JSON content type value */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /** Embeddings endpoint path */
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    /** Chat completions endpoint path */
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** Default request timeout in milliseconds */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** Maximum embedding dimension supported */
    private static final int MAX_EMBEDDING_DIMENSION = 4096;

    /** Minimum embedding dimension */
    private static final int MIN_EMBEDDING_DIMENSION = 1;

    /**
     * Pattern to extract individual score objects from a JSON array.
     *
     * <p>One object at a time, with its fields read separately, because a model's key order and
     * extra keys are not something to depend on. A single pattern that required
     * {@code {"index": N, "score": X}} in that order and nothing else matched nothing at all for
     * a reply that transposed the two fields or added a "reason" - and a reply that parsed to no
     * scores used to be indistinguishable from one that scored nothing highly.</p>
     */
    private static final Pattern SCORE_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*\\}");

    /** The {@code index} field of a score object. */
    private static final Pattern SCORE_INDEX_FIELD = Pattern.compile(
            "\"index\"\\s*:\\s*(-?\\d+)");

    /** The {@code score} field of a score object. */
    private static final Pattern SCORE_VALUE_FIELD = Pattern.compile(
            "\"score\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)");

    // ==================== Retry with Exponential Backoff Configuration ====================

    // Note: Base delay is now configurable via config.getRetryBaseDelay()
    // Minimum allowed: 10ms (enforced by ConfigValidation)

    /**
     * Maximum delay between retries (30 seconds).
     * Domain correctness: prevents unreasonably long waits while allowing
     * sufficient time for rate limits to reset.
     */
    private static final long RETRY_MAX_DELAY_MS = 30_000L;

    /**
     * Exponential backoff multiplier (2x).
     * Domain correctness: standard exponential backoff that doubles delay each attempt.
     */
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    /**
     * Jitter factor (±25%).
     * Domain correctness: jitter prevents thundering herd when multiple clients
     * retry simultaneously. ±25% provides good randomization without excessive variance.
     */
    private static final double RETRY_JITTER_FACTOR = 0.25;

    /** Random generator for jitter (thread-safe) */
    private static final RandomGenerator RANDOM = RandomGeneratorFactory.getDefault().create();

    private final HttpClient httpClient;
    private final LLMClientConfig config;
    private final CircuitBreaker circuitBreaker;
    private final ResponseSanitizer responseSanitizer;

    /**
     * Lazy-initialized batch handler for splitting large candidate sets.
     * Initialized on first use to avoid circular dependency in constructor
     * (BatchHandler needs LLMHttpClient, LLMHttpClient needs BatchHandler).
     *
     * <p>Thread-safe via double-checked locking pattern.</p>
     */
    private volatile BatchHandler batchHandler;

    /**
     * Returns the lazy-initialized BatchHandler instance.
     *
     * <p>Uses double-checked locking for thread-safe lazy initialization.</p>
     *
     * @return BatchHandler instance (never null after initialization)
     */
    private BatchHandler getBatchHandler() {
        if (batchHandler == null) {
            synchronized (this) {
                if (batchHandler == null) {
                    batchHandler = new BatchHandler(this, config);
                }
            }
        }
        return batchHandler;
    }

    /**
     * Creates a new LLMHttpClient with the given configuration.
     *
     * <p>Initializes an internal circuit breaker with configuration from
     * {@link LLMClientConfig#getFailureThreshold()} and
     * {@link LLMClientConfig#getCooldownPeriod()}.</p>
     *
     * @param config Client configuration (must not be null)
     * @throws IllegalArgumentException if config is null or has invalid values
     */
    public LLMHttpClient(LLMClientConfig config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.httpClient = createHttpClient(config.getTimeout());

        // Initialize circuit breaker from config
        this.circuitBreaker = new CircuitBreaker(
            config.getFailureThreshold(),
            config.getCooldownPeriod(),
            "llm-client"
        );

        this.responseSanitizer = new ResponseSanitizer();

        LOG.debug("LLMHttpClient initialized with baseUrl={}, embeddingModel={}, circuitBreaker={}",
                config.getBaseUrl(), config.getEmbeddingModel(), circuitBreaker);
    }

    /**
     * Creates a new LLMHttpClient with the given configuration and circuit breaker.
     *
     * <p>Use this constructor when you need to share a circuit breaker across
     * multiple client instances or provide a custom circuit breaker configuration.</p>
     *
     * @param config Client configuration (must not be null)
     * @param circuitBreaker Circuit breaker for resilience (must not be null)
     * @throws IllegalArgumentException if config or circuitBreaker is null
     */
    public LLMHttpClient(LLMClientConfig config, CircuitBreaker circuitBreaker) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "CircuitBreaker cannot be null");
        this.httpClient = createHttpClient(config.getTimeout());
        this.responseSanitizer = new ResponseSanitizer();

        LOG.debug("LLMHttpClient initialized with baseUrl={}, embeddingModel={}, providedCircuitBreaker",
                config.getBaseUrl(), config.getEmbeddingModel());
    }

    /**
     * Generates an embedding vector for the given text.
     *
     * <p>Calls the OpenAI-compatible {@code /v1/embeddings} endpoint and returns
     * the embedding vector as a float array. The embedding dimension matches
     * the configured model's output dimension.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Input text: non-null, non-empty string</li>
     *   <li>Output: non-null float array with length in [1, 4096]</li>
     *   <li>All float values are finite (not NaN or Infinite)</li>
     * </ul>
     *
     * @param text The text to embed (must not be null or empty)
     * @return Embedding vector as float array
     * @throws IllegalArgumentException if text is null or empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public float[] generateEmbedding(String text) {
        return generateEmbedding(text, null);
    }

    /**
     * Generates an embedding vector for the given text with correlation ID for tracing.
     *
     * <p>Calls the OpenAI-compatible {@code /v1/embeddings} endpoint and returns
     * the embedding vector as a float array. The embedding dimension matches
     * the configured model's output dimension.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Input text: non-null, non-empty string</li>
     *   <li>Correlation ID: optional, if non-empty will be included in request headers</li>
     *   <li>Output: non-null float array with length in [1, 4096]</li>
     *   <li>All float values are finite (not NaN or Infinite)</li>
     * </ul>
     *
     * @param text The text to embed (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return Embedding vector as float array
     * @throws IllegalArgumentException if text is null or empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public float[] generateEmbedding(String text, String correlationId) {
        // Validate input
        Objects.requireNonNull(text, "Text cannot be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }

        LOG.debug("Generating embedding for text of length {} characters, correlationId={}",
                text.length(), correlationId);

        // Build request body
        String requestBody = buildEmbeddingRequestBody(text, config.getEmbeddingModel());
        String url = config.getBaseUrl() + EMBEDDINGS_PATH;

        // Make HTTP request with optional correlation ID
        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);

        // Validate HTTP status
        validateHttpStatus(response.statusCode());

        // Parse embedding from response
        float[] embedding = parseEmbeddingResponse(response.body());

        // Validate embedding domain constraints
        validateEmbedding(embedding);

        LOG.debug("Generated embedding of dimension {}, correlationId={}", embedding.length, correlationId);
        return embedding;
    }

    /**
     * Scores the relevancy of candidate passages against a query.
     *
     * <p>Calls the OpenAI-compatible {@code /v1/chat/completions} endpoint with a
     * prompt that asks the LLM to score each candidate from 0.0 to 1.0 based on
     * relevance to the query.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Query: non-null, non-empty string</li>
     *   <li>Candidates: non-null, non-empty list of strings</li>
     *   <li>Output: list of RelevancyScore with scores clamped to [0.0, 1.0]</li>
     *   <li>Each score's index corresponds to the original candidate position</li>
     * </ul>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null or empty)
     * @return List of RelevancyScore objects, one per candidate
     * @throws IllegalArgumentException if query or candidates is null/empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public List<RelevancyScore> scoreRelevancy(String query, List<String> candidates) {
        return scoreRelevancy(query, candidates, null);
    }

    /**
     * Scores the relevancy of candidate passages against a query with correlation ID.
     *
     * <p>This method automatically batches candidates when the estimated token count
     * exceeds the configured limit ({@link LLMClientConfig#getMaxBatchTokens()}).
     * Large candidate sets are split into multiple API calls, and results are merged
     * while preserving the original candidate order.</p>
     *
     * <p>For direct API calls without batching, use {@link #scoreRelevancyUnbatched(String, List, String)}.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Query: non-null, non-empty string</li>
     *   <li>Candidates: non-null, non-empty list of strings</li>
     *   <li>Correlation ID: optional, if non-empty will be included in request headers</li>
     *   <li>Output: list of RelevancyScore with scores clamped to [0.0, 1.0]</li>
     *   <li>Each score's index corresponds to the original candidate position</li>
     *   <li>Batching preserves original candidate order across multiple API calls</li>
     * </ul>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return List of RelevancyScore objects, one per candidate
     * @throws IllegalArgumentException if query or candidates is null/empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public List<RelevancyScore> scoreRelevancy(String query, List<String> candidates, String correlationId) {
        // Validate inputs
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidates cannot be empty");
        }

        // Delegate to BatchHandler for automatic batching
        return getBatchHandler().scoreWithBatching(query, candidates, correlationId);
    }

    /**
     * Scores relevancy without automatic batching (single API call).
     *
     * <p>This is the raw implementation that makes a single API call to the LLM.
     * Used internally by {@link BatchHandler} for each batch. External callers
     * should use {@link #scoreRelevancy(String, List, String)} which handles
     * batching automatically.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Query: non-null, non-empty string</li>
     *   <li>Candidates: non-null, non-empty list of strings</li>
     *   <li>Correlation ID: optional, if non-empty will be included in request headers</li>
     *   <li>Output: list of RelevancyScore with scores clamped to [0.0, 1.0]</li>
     *   <li>Each score's index corresponds to the candidate position in the input list</li>
     * </ul>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return List of RelevancyScore objects, one per candidate
     * @throws IllegalArgumentException if query or candidates is null/empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    List<RelevancyScore> scoreRelevancyUnbatched(String query, List<String> candidates, String correlationId) {
        // Validate inputs
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidates cannot be empty");
        }

        LOG.debug("Scoring relevancy for {} candidates (unbatched), correlationId={}", candidates.size(), correlationId);

        // Build the prompt
        String requestBody = buildRelevancyRequestBody(query, candidates, config.getChatModel());
        String url = config.getBaseUrl() + CHAT_COMPLETIONS_PATH;

        // Make HTTP request with optional correlation ID
        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);

        // Validate HTTP status
        validateHttpStatus(response.statusCode());

        // Parse relevancy scores from response
        List<RelevancyScore> scores = parseRelevancyResponse(response.body(), candidates);

        LOG.debug("Scored {} candidates (unbatched), correlationId={}", scores.size(), correlationId);
        return scores;
    }

    /**
     * Asks the chat model a question and returns what it said.
     *
     * <p>The general form of the call {@link #scoreRelevancy} makes for one specific purpose.
     * Relevancy scoring was the only reason to talk to the chat model when this client was
     * written, so the endpoint, the retry loop, the bounded body read and the status handling all
     * existed with no way to reach them for anything else - and a second caller would have had to
     * duplicate every one of them.</p>
     *
     * <p>Temperature is fixed at zero. Every use of this in the system is an extraction or a
     * judgement whose value depends on being reproducible: the same document must yield the same
     * facts on a re-ingest, or the graph changes shape for no reason anyone can point at.</p>
     *
     * @param instruction   What the model is being asked to do, sent as the system message
     * @param input         The text to act on, sent as the user message
     * @param correlationId Correlation id for tracing, or null
     * @return The assistant's reply, never null
     * @throws IllegalArgumentException if instruction or input is null or blank
     * @throws LLMException if the call fails or the reply cannot be read
     */
    public String complete(String instruction, String input, String correlationId) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction must not be null or blank");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be null or blank");
        }

        String requestBody = "{\"model\":\"" + JsonWriter.escapeString(config.getChatModel())
            + "\",\"messages\":["
            + "{\"role\":\"system\",\"content\":\"" + JsonWriter.escapeString(instruction) + "\"},"
            + "{\"role\":\"user\",\"content\":\"" + JsonWriter.escapeString(input) + "\"}"
            + "],\"temperature\":0.0}";

        HttpResponse<String> response = executeRequest(
            config.getBaseUrl() + CHAT_COMPLETIONS_PATH, requestBody, correlationId);
        validateHttpStatus(response.statusCode());
        return extractMessageContent(response.body());
    }

    /**
     * Asks the chat model a question, refusing to call it while the circuit breaker is open.
     *
     * <p>The breaker is the same one relevancy scoring uses, because it is the same endpoint: a
     * model that is failing is failing for both, and letting one caller keep hammering it would
     * hold the breaker open for the other.</p>
     *
     * @param instruction   What the model is being asked to do
     * @param input         The text to act on
     * @param correlationId Correlation id for tracing, or null
     * @return The assistant's reply
     * @throws LLMException if the breaker is open, or the call fails
     */
    public String completeWithCircuitBreaker(String instruction, String input,
                                             String correlationId) {
        if (!circuitBreaker.allowRequest()) {
            throw new LLMException(
                "Circuit breaker is open for " + circuitBreaker.getName()
                    + "; not calling the chat model",
                ErrorType.CIRCUIT_OPEN);
        }
        try {
            String reply = complete(instruction, input, correlationId);
            circuitBreaker.recordSuccess();
            return reply;
        } catch (LLMException e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    /**
     * Returns the configuration used by this client.
     *
     * @return Client configuration (never null)
     */
    public LLMClientConfig getConfig() {
        return config;
    }

    /**
     * Returns the circuit breaker used by this client.
     *
     * <p>Useful for monitoring circuit breaker state via JMX or metrics.</p>
     *
     * @return Circuit breaker instance (never null)
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Scores relevancy with circuit breaker protection.
     *
     * <p>This method automatically batches candidates when the estimated token count
     * exceeds the configured limit. Each batch is protected by the circuit breaker.
     * When the circuit breaker is open, requests fail fast with an {@link LLMException}
     * of type {@link io.excalibur.llm.LLMException.ErrorType#CIRCUIT_OPEN}.</p>
     *
     * <h3>Sanitized Response Handling (CRITICAL)</h3>
     * <p>When the LLM returns a response with markdown fences or commentary
     * but valid JSON inside, the {@link ResponseSanitizer} extracts the JSON.
     * This is counted as a <strong>SUCCESS</strong> for circuit breaker purposes.</p>
     *
     * <p>Only the following are counted as failures:</p>
     * <ul>
     *   <li>Network errors (timeout, connection failure)</li>
     *   <li>HTTP 5xx server errors</li>
     *   <li>HTTP 401/403 authentication errors</li>
     *   <li>Truly unparseable responses (sanitizer returns null)</li>
     * </ul>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return List of RelevancyScore objects, one per candidate
     * @throws IllegalArgumentException if query or candidates is null/empty
     * @throws LLMException if the circuit is open or API call fails
     */
    public List<RelevancyScore> scoreRelevancyWithCircuitBreaker(
            String query, List<String> candidates, String correlationId) {
        // Validate inputs
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidates cannot be empty");
        }

        // Delegate to BatchHandler for automatic batching with circuit breaker protection
        return getBatchHandler().scoreWithBatchingAndCircuitBreaker(query, candidates, correlationId);
    }

    /**
     * Scores relevancy with circuit breaker protection, without automatic batching.
     *
     * <p>This is the raw implementation that makes a single API call with circuit
     * breaker protection. Used internally by {@link BatchHandler} for each batch.
     * External callers should use {@link #scoreRelevancyWithCircuitBreaker(String, List, String)}
     * which handles batching automatically.</p>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return List of RelevancyScore objects, one per candidate
     * @throws IllegalArgumentException if query or candidates is null/empty
     * @throws LLMException if the circuit is open or API call fails
     */
    List<RelevancyScore> scoreRelevancyUnbatchedWithCircuitBreaker(
            String query, List<String> candidates, String correlationId) {
        // Check circuit breaker before making request
        if (!circuitBreaker.allowRequest()) {
            LOG.warn("Circuit breaker is OPEN, failing fast (correlationId={})", correlationId);
            throw new LLMException(
                "Circuit breaker is open - LLM endpoint appears unavailable",
                io.excalibur.llm.LLMException.ErrorType.CIRCUIT_OPEN
            );
        }

        try {
            // Call the unbatched method
            List<RelevancyScore> scores = scoreRelevancyUnbatched(query, candidates, correlationId);

            // Success! Record it (even if response required sanitization)
            circuitBreaker.recordSuccess();

            return scores;

        } catch (LLMException e) {
            // Determine if this should count as a failure for circuit breaker
            if (shouldCountAsFailure(e)) {
                circuitBreaker.recordFailure();
                LOG.debug("Recorded circuit breaker failure due to: {} (correlationId={})",
                        e.getErrorType(), correlationId);
            } else {
                // Parse errors from successfully sanitized responses are NOT failures
                LOG.debug("LLM exception not counted as circuit breaker failure: {} (correlationId={})",
                        e.getErrorType(), correlationId);
            }
            throw e;
        }
    }

    /**
     * Determines if an LLMException should count as a circuit breaker failure.
     *
     * <p>CRITICAL: Sanitized-but-valid responses should NOT count as failures.
     * This means PARSE_ERROR exceptions where sanitization succeeded but subsequent
     * parsing failed are still counted as failures (the sanitizer already did its job).</p>
     *
     * <p>Failures that should trip the circuit:</p>
     * <ul>
     *   <li>NETWORK - connection issues, timeouts</li>
     *   <li>HTTP_ERROR - server errors (5xx)</li>
     *   <li>RATE_LIMITED - too many requests</li>
     *   <li>AUTH_ERROR - authentication failures</li>
     *   <li>PARSE_ERROR - truly unparseable responses</li>
     * </ul>
     *
     * @param e The exception to evaluate
     * @return true if this should count as a failure for circuit breaker
     */
    private boolean shouldCountAsFailure(LLMException e) {
        return switch (e.getErrorType()) {
            case NETWORK, HTTP_ERROR, RATE_LIMITED, AUTH_ERROR, PARSE_ERROR, INVALID_REQUEST -> true;
            case CIRCUIT_OPEN -> false; // Circuit already open, don't double-count
        };
    }

    // ==================== Private Implementation ====================

    /**
     * Creates an HTTP client with the specified timeout.
     *
     * @param timeout Request timeout duration
     * @return Configured HttpClient
     */
    private HttpClient createHttpClient(Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofMillis(DEFAULT_TIMEOUT_MS);

        return HttpClient.newBuilder()
                .connectTimeout(effectiveTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Builds the JSON request body for an embedding request.
     *
     * <p>Uses JsonWriter for proper UTF-8 encoding and string escaping.</p>
     *
     * @param text Text to embed
     * @param model Model name to use
     * @return JSON request body as string
     */
    private String buildEmbeddingRequestBody(String text, String model) {
        // Use JsonWriter for proper JSON escaping (handles UTF-8, control chars, etc.)
        String escapedText = JsonWriter.escapeString(text);
        String escapedModel = JsonWriter.escapeString(model);

        return "{\"model\":\"" + escapedModel + "\",\"input\":\"" + escapedText + "\"}";
    }


    /**
     * Executes an HTTP POST request to the given URL.
     *
     * @param url Target URL
     * @param requestBody JSON request body
     * @return HTTP response
     * @throws LLMException if request fails
     */
    private HttpResponse<String> executeRequest(String url, String requestBody) {
        return executeRequest(url, requestBody, null);
    }

    /**
     * Executes an HTTP POST request to the given URL with optional correlation ID.
     *
     * <p>This method implements retry with exponential backoff for retryable errors.
     * The number of retries is controlled by {@link LLMClientConfig#getMaxRetries()}.</p>
     *
     * <h3>Retry Behavior</h3>
     * <ul>
     *   <li>Base delay: 100ms</li>
     *   <li>Exponential multiplier: 2x each retry</li>
     *   <li>Max delay: 30 seconds</li>
     *   <li>Jitter: ±25% randomization to prevent thundering herd</li>
     *   <li>Retries on: NETWORK errors, RATE_LIMITED (429), HTTP 5xx errors</li>
     *   <li>No retry on: AUTH_ERROR (401/403), INVALID_REQUEST (400), PARSE_ERROR</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Request timeout: uses configured timeout (must be positive)</li>
     *   <li>Request body: valid UTF-8 JSON</li>
     *   <li>Timeout error → NETWORK error type (retryable)</li>
     *   <li>IO error → NETWORK error type (retryable)</li>
     *   <li>HTTP 5xx/429 → retryable</li>
     *   <li>HTTP 401/403/400 → non-retryable, throw immediately</li>
     *   <li>InterruptedException → preserves interrupt status, no retry</li>
     * </ul>
     *
     * <h3>DEBUG Logging for Request/Response Tracing</h3>
     * <p>When DEBUG logging is enabled, the following is logged:</p>
     * <ul>
     *   <li>Request: URL, method, headers (with correlation ID), body preview</li>
     *   <li>Response: status code, headers, body preview</li>
     *   <li>Correlation ID is included in all log messages for trace correlation</li>
     *   <li>Retry attempts with delay calculations</li>
     * </ul>
     *
     * @param url Target URL
     * @param requestBody JSON request body
     * @param correlationId Optional correlation ID for tracing (may be null or empty)
     * @return HTTP response with successful status code (2xx)
     * @throws LLMException if all retries fail or request is non-retryable
     */
    private HttpResponse<String> executeRequest(String url, String requestBody, String correlationId) {
        int maxRetries = config.getMaxRetries();
        LLMException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
                        .timeout(config.getTimeout())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

                // Add authorization header if API key is configured
                String apiKey = config.getApiKey();
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header(AUTHORIZATION_HEADER, "Bearer " + apiKey);
                }

                // Add correlation ID header if provided and non-blank
                if (correlationId != null && !correlationId.isBlank()) {
                    requestBuilder.header(CORRELATION_ID_HEADER, correlationId);
                }

                HttpRequest request = requestBuilder.build();

                // DEBUG: Log request details for tracing (include attempt number)
                if (LOG.isDebugEnabled()) {
                    if (attempt > 0) {
                        LOG.debug("Retry attempt {} of {} (correlationId={})",
                                attempt, maxRetries, correlationId);
                    }
                    logRequest(url, request, requestBody, correlationId);
                }

                // Bounded, because the timeout is not: a peer that keeps sending slowly never
                // trips it, and an unbounded read ends in an OutOfMemoryError rather than in a
                // bad-response error the caller can act on.
                HttpResponse<String> response = httpClient.send(request,
                    excalibur.llm.client.BoundedBody.ofString(
                        config.getMaxResponseBytes(), StandardCharsets.UTF_8));

                // DEBUG: Log response details for tracing
                if (LOG.isDebugEnabled()) {
                    logResponse(response, correlationId);
                }

                // Check HTTP status code - handle retryable vs non-retryable errors
                int statusCode = response.statusCode();
                if (HttpStatus.isError(statusCode)) {
                    if (isRetryableHttpStatus(statusCode)) {
                        // Retryable HTTP error (5xx, 429) - continue retry loop
                        ErrorType errorType = mapHttpStatusToErrorType(statusCode);
                        lastException = new LLMException(
                                "LLM API returned error status " + statusCode +
                                " (" + HttpStatus.getReasonPhrase(statusCode) + ")",
                                errorType, statusCode);
                        LOG.warn("Retryable HTTP error {} on attempt {}/{} (correlationId={})",
                                statusCode, attempt + 1, maxRetries + 1, correlationId);
                        // Fall through to retry logic below
                    } else {
                        // Non-retryable HTTP error (401, 403, 400, etc.) - throw immediately
                        throw new LLMException(
                                "LLM API returned error status " + statusCode +
                                " (" + HttpStatus.getReasonPhrase(statusCode) + ")",
                                mapHttpStatusToErrorType(statusCode), statusCode);
                    }
                } else {
                    // Success (2xx status) - return response
                    return response;
                }

            } catch (HttpTimeoutException e) {
                // Domain correctness: timeout is a network-level failure (retryable)
                lastException = new LLMException(
                        "LLM API request timed out after " + config.getTimeout().toMillis() + "ms to " + url,
                        ErrorType.NETWORK, e);
                LOG.warn("Request timeout on attempt {}/{} (correlationId={})",
                        attempt + 1, maxRetries + 1, correlationId);

            } catch (IOException e) {
                // Domain correctness: IO error is a network-level failure (retryable)
                lastException = new LLMException(
                        "IO error during LLM API call to " + url + ": " + e.getMessage(),
                        ErrorType.NETWORK, e);
                LOG.warn("IO error on attempt {}/{}: {} (correlationId={})",
                        attempt + 1, maxRetries + 1, e.getMessage(), correlationId);

            } catch (InterruptedException e) {
                // Preserve interrupt status per best practice - do NOT retry
                Thread.currentThread().interrupt();
                throw new LLMException("LLM API call interrupted", ErrorType.NETWORK, e);
            }

            // Check if we should retry
            if (attempt < maxRetries) {
                // Calculate delay with exponential backoff and jitter
                long delayMs = calculateRetryDelay(attempt);
                LOG.debug("Waiting {}ms before retry attempt {} (correlationId={})",
                        delayMs, attempt + 2, correlationId);

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LLMException("Interrupted during retry delay", ErrorType.NETWORK, e);
                }
            }
        }

        // All retries exhausted
        LOG.error("All {} retry attempts exhausted for request to {} (correlationId={})",
                maxRetries + 1, url, correlationId);

        // Domain correctness: ensure we always throw a documented exception
        // This should never be null in practice, but defensive programming ensures
        // we throw a documented LLMException rather than NullPointerException
        if (lastException == null) {
            throw new LLMException(
                "Request failed after " + (maxRetries + 1) + " attempts with unknown error",
                ErrorType.NETWORK);
        }
        throw lastException;
    }

    /**
     * Determines if an HTTP status code is retryable.
     *
     * <p>Retryable status codes:</p>
     * <ul>
     *   <li>429 (Too Many Requests) - rate limited, may succeed after backoff</li>
     *   <li>500-599 (Server Errors) - transient server issues</li>
     * </ul>
     *
     * <p>Non-retryable status codes:</p>
     * <ul>
     *   <li>400 (Bad Request) - client error, won't succeed on retry</li>
     *   <li>401 (Unauthorized) - authentication error</li>
     *   <li>403 (Forbidden) - authorization error</li>
     *   <li>404 (Not Found) - resource doesn't exist</li>
     *   <li>Other 4xx - client errors</li>
     * </ul>
     *
     * @param statusCode HTTP status code
     * @return true if the status code indicates a retryable error
     */
    private boolean isRetryableHttpStatus(int statusCode) {
        // 429 (Too Many Requests) - rate limited
        if (statusCode == 429) {
            return true;
        }
        // 5xx server errors
        if (statusCode >= 500 && statusCode < 600) {
            return true;
        }
        return false;
    }

    /**
     * Calculates the retry delay with exponential backoff and jitter.
     *
     * <p>Formula: {@code min(baseDelay * (2 ^ attempt), maxDelay) * (1 ± jitter)}</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Base delay: configurable via excalibur.llm.retry-base-delay-ms (default 100ms, min 10ms)</li>
     *   <li>Multiplier: 2x per attempt (RETRY_BACKOFF_MULTIPLIER)</li>
     *   <li>Max delay: 30 seconds (RETRY_MAX_DELAY_MS)</li>
     *   <li>Jitter: ±25% (RETRY_JITTER_FACTOR)</li>
     *   <li>Result is always positive and &lt;= max delay</li>
     * </ul>
     *
     * <h3>Example Delays (with default 100ms base)</h3>
     * <ul>
     *   <li>Attempt 0: ~100ms (75-125ms with jitter)</li>
     *   <li>Attempt 1: ~200ms (150-250ms with jitter)</li>
     *   <li>Attempt 2: ~400ms (300-500ms with jitter)</li>
     *   <li>Attempt 3: ~800ms (600-1000ms with jitter)</li>
     *   <li>Attempt 4: ~1600ms (1200-2000ms with jitter)</li>
     *   <li>Attempt 5+: capped at 30s with jitter</li>
     * </ul>
     *
     * @param attempt Current attempt number (0-based)
     * @return Delay in milliseconds
     */
    private long calculateRetryDelay(int attempt) {
        // Use configurable retry base delay from config
        long baseDelayMs = config.getRetryBaseDelay().toMillis();


        // Calculate exponential delay: baseDelay * (multiplier ^ attempt)
        double exponentialDelay = baseDelayMs *
                Math.pow(RETRY_BACKOFF_MULTIPLIER, attempt);
        // Cap at max delay
        long cappedDelay = Math.min((long) exponentialDelay, RETRY_MAX_DELAY_MS);

        // Apply jitter: ±25%
        double jitter = 1.0 + (RANDOM.nextDouble() * 2 - 1) * RETRY_JITTER_FACTOR;
        long delayWithJitter = (long) (cappedDelay * jitter);

        // Ensure minimum delay (at least configured base delay after jitter)
        return Math.max(delayWithJitter, baseDelayMs);
    }

    /**
     * Logs request details at DEBUG level for request/response tracing.
     *
     * <p>Includes: URL, HTTP method, headers (with correlation ID), and body preview.
     * Sensitive headers like Authorization are masked.</p>
     *
     * @param url Request URL
     * @param request HTTP request object
     * @param requestBody Request body string
     * @param correlationId Correlation ID for trace correlation
     */
    private void logRequest(String url, HttpRequest request, String requestBody, String correlationId) {
        StringBuilder logMsg = new StringBuilder(512);
        logMsg.append("LLM API Request [correlationId=").append(correlationId).append("]:\n");
        logMsg.append("  ").append(request.method()).append(" ").append(url).append("\n");
        logMsg.append("  Headers:\n");

        // Log headers, masking sensitive values
        request.headers().map().forEach((name, values) -> {
            for (String value : values) {
                if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                    logMsg.append("    ").append(name).append(": [REDACTED]\n");
                } else {
                    logMsg.append("    ").append(name).append(": ").append(value).append("\n");
                }
            }
        });

        // Log body preview (truncate to avoid overwhelming logs)
        // Domain correctness: body is valid UTF-8 JSON
        String bodyPreview = truncate(requestBody, 500);
        logMsg.append("  Body (").append(requestBody.length()).append(" bytes): ")
              .append(bodyPreview);

        LOG.debug(logMsg.toString());
    }

    /**
     * Logs response details at DEBUG level for request/response tracing.
     *
     * <p>Includes: status code, response headers, and body preview.</p>
     *
     * @param response HTTP response object
     * @param correlationId Correlation ID for trace correlation
     */
    private void logResponse(HttpResponse<String> response, String correlationId) {
        StringBuilder logMsg = new StringBuilder(512);
        logMsg.append("LLM API Response [correlationId=").append(correlationId).append("]:\n");
        logMsg.append("  Status: ").append(response.statusCode()).append("\n");
        logMsg.append("  Headers:\n");

        // Log response headers
        response.headers().map().forEach((name, values) -> {
            for (String value : values) {
                logMsg.append("    ").append(name).append(": ").append(value).append("\n");
            }
        });

        // Log body preview (truncate to avoid overwhelming logs)
        String body = response.body();
        int bodyLength = body != null ? body.length() : 0;
        String bodyPreview = body != null ? truncate(body, 500) : "null";
        logMsg.append("  Body (").append(bodyLength).append(" bytes): ")
              .append(bodyPreview);

        LOG.debug(logMsg.toString());
    }

    /**
     * Validates that an HTTP status code is in the valid range and maps errors to types.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Status code must be in valid HTTP range [100, 599] (validated by {@link HttpStatus})</li>
     *   <li>401/403 → AUTH_ERROR</li>
     *   <li>429 → RATE_LIMITED (retryable)</li>
     *   <li>400 → INVALID_REQUEST</li>
     *   <li>5xx → HTTP_ERROR (retryable)</li>
     * </ul>
     *
     * @param statusCode HTTP status code
     * @throws LLMException if status code indicates an error
     */
    private void validateHttpStatus(int statusCode) {
        // Domain correctness: HTTP status codes must be in [100, 599]
        if (!HttpStatus.isValid(statusCode)) {
            throw new LLMException(
                "Invalid HTTP status code: " + statusCode +
                ". Valid range is [" + HttpStatus.MIN_STATUS_CODE + ", " + HttpStatus.MAX_STATUS_CODE + "].",
                ErrorType.HTTP_ERROR
            );
        }

        if (HttpStatus.isError(statusCode)) {
            ErrorType errorType = mapHttpStatusToErrorType(statusCode);
            String message = "LLM API returned error status " + statusCode + " (" + HttpStatus.getReasonPhrase(statusCode) + ")";
            throw new LLMException(message, errorType, statusCode);
        }
    }

    /**
     * Maps HTTP status codes to LLM error types.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>401, 403 → AUTH_ERROR (authentication/authorization failure)</li>
     *   <li>429 → RATE_LIMITED (too many requests, retryable)</li>
     *   <li>400 → INVALID_REQUEST (bad request parameters)</li>
     *   <li>500-599 → HTTP_ERROR (server error, retryable)</li>
     *   <li>Other 4xx → HTTP_ERROR (client error, not retryable)</li>
     * </ul>
     *
     * @param statusCode HTTP status code
     * @return Appropriate error type
     */
    private ErrorType mapHttpStatusToErrorType(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> ErrorType.AUTH_ERROR;
            case 429 -> ErrorType.RATE_LIMITED;
            case 400 -> ErrorType.INVALID_REQUEST;
            default -> ErrorType.HTTP_ERROR;
        };
    }

    /**
     * Parses the embedding vector from an API response.
     *
     * <p>Extracts the embedding array from the JSON response using manual parsing
     * to avoid external dependencies.</p>
     *
     * <p>Expected response format:
     * <pre>{@code
     * {
     *   "object": "list",
     *   "data": [
     *     {
     *       "object": "embedding",
     *       "embedding": [0.1, -0.2, 0.3, ...],
     *       "index": 0
     *     }
     *   ],
     *   "model": "text-embedding-3-small",
     *   "usage": {...}
     * }
     * }</pre>
     *
     * @param responseBody JSON response body
     * @return Embedding vector as float array
     * @throws LLMException if parsing fails
     */
    private float[] parseEmbeddingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from embedding API", ErrorType.PARSE_ERROR);
        }

        // Find the "embedding" array in the response
        // We look for the pattern: "embedding": [ ... ]
        String embeddingKey = "\"embedding\"";
        int embeddingIndex = responseBody.indexOf(embeddingKey);

        if (embeddingIndex == -1) {
            LOG.error("Response does not contain 'embedding' field: {}", truncate(responseBody, 500));
            throw new LLMException("Response does not contain 'embedding' field", ErrorType.PARSE_ERROR);
        }

        // Find the opening bracket of the array
        int arrayStart = responseBody.indexOf('[', embeddingIndex);
        if (arrayStart == -1) {
            throw new LLMException("Could not find embedding array start", ErrorType.PARSE_ERROR);
        }

        // Find the matching closing bracket
        int arrayEnd = findMatchingBracket(responseBody, arrayStart);
        if (arrayEnd == -1) {
            throw new LLMException("Could not find embedding array end", ErrorType.PARSE_ERROR);
        }

        // Extract the array content
        String arrayContent = responseBody.substring(arrayStart + 1, arrayEnd);

        // Parse the float values
        return parseFloatArray(arrayContent);
    }

    /**
     * Finds the position of the matching closing bracket for an opening bracket.
     *
     * @param json JSON string
     * @param startPos Position of the opening bracket
     * @return Position of matching closing bracket, or -1 if not found
     */
    private int findMatchingBracket(String json, int startPos) {
        int depth = 1;
        boolean inString = false;
        boolean escape = false;

        for (int i = startPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Parses a comma-separated list of float values.
     *
     * @param arrayContent Content inside brackets, e.g., "0.1, -0.2, 0.3"
     * @return Float array
     * @throws LLMException if parsing fails
     */
    private float[] parseFloatArray(String arrayContent) {
        // Split by comma, handling whitespace
        String[] parts = arrayContent.split(",");

        if (parts.length == 0) {
            throw new LLMException("Empty embedding array", ErrorType.PARSE_ERROR);
        }

        float[] result = new float[parts.length];

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();

            if (part.isEmpty()) {
                throw new LLMException("Empty element in embedding array at index " + i, ErrorType.PARSE_ERROR);
            }

            try {
                result[i] = Float.parseFloat(part);
            } catch (NumberFormatException e) {
                throw new LLMException("Invalid float value in embedding array at index " + i + ": " + part,
                    ErrorType.PARSE_ERROR, e);
            }
        }

        return result;
    }

    /**
     * Validates that an embedding vector meets domain constraints.
     *
     * <p>Checks:</p>
     * <ul>
     *   <li>Non-null array</li>
     *   <li>Length in [1, maxDimension]</li>
     *   <li>All values are finite (not NaN or Infinite)</li>
     * </ul>
     *
     * @param embedding Embedding vector to validate
     * @throws LLMException if validation fails
     */
    private void validateEmbedding(float[] embedding) {
        if (embedding == null) {
            throw new LLMException("Embedding is null", ErrorType.PARSE_ERROR);
        }

        int length = embedding.length;

        if (length < MIN_EMBEDDING_DIMENSION || length > MAX_EMBEDDING_DIMENSION) {
            throw new LLMException(
                    "Embedding dimension out of range [" + MIN_EMBEDDING_DIMENSION + ", " +
                    MAX_EMBEDDING_DIMENSION + "]: " + length, ErrorType.PARSE_ERROR);
        }

        // Check all values are finite
        for (int i = 0; i < length; i++) {
            float value = embedding[i];

            if (Float.isNaN(value)) {
                throw new LLMException("Embedding contains NaN at index " + i, ErrorType.PARSE_ERROR);
            }

            if (Float.isInfinite(value)) {
                throw new LLMException("Embedding contains Infinite at index " + i, ErrorType.PARSE_ERROR);
            }
        }

        // Optional: validate against configured dimension
        int configuredDim = config.getEmbeddingDimension();
        if (length != configuredDim) {
            LOG.warn("Embedding dimension ({}) does not match configured dimension ({})",
                    length, configuredDim);
        }
    }

    /**
     * Truncates a string for logging purposes.
     *
     * @param s String to truncate
     * @param maxLength Maximum length
     * @return Truncated string
     */
    private String truncate(String s, int maxLength) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "...";
    }

    // ==================== Relevancy Scoring Methods ====================

    /**
     * Builds the JSON request body for a relevancy scoring request.
     *
     * <p>Constructs a chat completion request with a prompt that instructs the LLM
     * to score each candidate's relevance to the query.</p>
     *
     * @param query Query text
     * @param candidates List of candidate passages
     * @param model Model name to use
     * @return JSON request body as string
     */
    private String buildRelevancyRequestBody(String query, List<String> candidates, String model) {
        // Build the prompt with numbered candidates
        StringBuilder candidatesText = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                candidatesText.append('\n');
            }
            String escaped = escapeForPrompt(candidates.get(i));
            candidatesText.append(i + 1).append(". ").append(escaped);
        }

        // Format the prompt using the Phase 7 specification template
        String prompt = String.format(
                "You are a relevancy judge. Given a user query and a list of candidate passages, " +
                "score each passage from 0.0 to 1.0 based on how relevant it is to answering the query. " +
                "Return ONLY a JSON array of objects: [{\"index\": 0, \"score\": 0.85}, ...]. " +
                "Do not explain. Do not add commentary.\n\n" +
                "Query: %s\n\nCandidates:\n%s",
                query, candidatesText.toString());

        // Build the JSON request using JsonWriter for proper escaping
        // Temperature 0.0 for deterministic scoring
        String escapedModel = JsonWriter.escapeString(model);
        String escapedPrompt = JsonWriter.escapeString(prompt);

        return "{\"model\":\"" + escapedModel + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" +
                escapedPrompt + "\"}],\"temperature\":0.0}";
    }

    /**
     * Escapes text for safe inclusion in the LLM prompt.
     * Removes control characters but preserves newlines, carriage returns, and tabs.
     *
     * @param text Text to escape
     * @return Escaped text safe for prompt inclusion
     */
    private String escapeForPrompt(String text) {
        if (text == null) {
            return "";
        }
        // Remove control characters but preserve newlines, carriage returns, and tabs
        return text.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");
    }

    /**
     * Parses the relevancy scores from an API response.
     *
     * <p>Extracts the JSON array of scores from the chat completion response,
     * handling markdown code fences and other response artifacts.</p>
     *
     * <p>Expected content format:
     * <pre>{@code
     * [{"index": 0, "score": 0.85}, {"index": 1, "score": 0.42}, ...]
     * }</pre>
     *
     * @param responseBody JSON response body
     * @param candidates Original candidate list for validation
     * @return List of RelevancyScore objects
     * @throws LLMException if parsing fails
     */
    private List<RelevancyScore> parseRelevancyResponse(String responseBody, List<String> candidates) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from relevancy API", ErrorType.PARSE_ERROR);
        }

        // Extract the content from the chat completion response
        String content = extractMessageContent(responseBody);

        // Sanitize: strip markdown fences and extract JSON array
        String sanitized = responseSanitizer.sanitize(content);

        if (sanitized == null || sanitized.isBlank()) {
            LOG.error("Could not extract JSON array from response: {}", truncate(responseBody, 500));
            throw new LLMException("Could not extract JSON array from response", ErrorType.PARSE_ERROR);
        }

        // Parse the JSON array of scores
        return parseScoreArray(sanitized, candidates);
    }

    /**
     * Extracts the message content from a chat completion response.
     *
     * <p>Parses the OpenAI response format to extract the "content" field from
     * the first choice's message.</p>
     *
     * @param responseBody Full JSON response
     * @return Extracted content string
     * @throws LLMException if content cannot be found
     */
    private String extractMessageContent(String responseBody) {
        // Find "content" field in the response
        // Format: "content":"..."
        String contentKey = "\"content\"";
        int contentIndex = responseBody.indexOf(contentKey);
        if (contentIndex < 0) {
            throw new LLMException("No 'content' field in response", ErrorType.PARSE_ERROR);
        }

        // Find the opening quote of the content value
        int startQuote = responseBody.indexOf('"', contentIndex + contentKey.length());
        if (startQuote < 0) {
            throw new LLMException("Malformed content field", ErrorType.PARSE_ERROR);
        }

        // Find the closing quote (handling escaped quotes)
        int endQuote = findClosingQuote(responseBody, startQuote + 1);
        if (endQuote < 0) {
            throw new LLMException("Unclosed content string", ErrorType.PARSE_ERROR);
        }

        // Extract and unescape the content
        String rawContent = responseBody.substring(startQuote + 1, endQuote);
        return unescapeJsonString(rawContent);
    }

    /**
     * Finds the closing quote of a JSON string, handling escaped characters.
     *
     * @param json JSON string
     * @param start Starting position after opening quote
     * @return Position of closing quote, or -1 if not found
     */
    private int findClosingQuote(String json, int start) {
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Unescapes a JSON string value.
     *
     * <p>Handles standard JSON escape sequences including quotes, backslash,
     * newline, carriage return, tab, backspace, form feed, and unicode escapes.</p>
     *
     * @param escaped Escaped JSON string
     * @return Unescaped string
     */
    private String unescapeJsonString(String escaped) {
        if (escaped == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case 'u' -> {
                        if (i + 5 < escaped.length()) {
                            String hex = escaped.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Parses a JSON array of score objects.
     *
     * <p>Expected format: {@code [{"index": 0, "score": 0.85}, {"index": 1, "score": 0.42}]}</p>
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Scores are clamped to [0.0, 1.0] by {@link RelevancyScore} constructor</li>
     *   <li>Out-of-range indices are DISCARDED, not clamped: clamping index 999 to the last
     *       candidate would attribute the model's score to a document it never scored</li>
     *   <li>Candidate text is associated with each score</li>
     * </ul>
     *
     * @param jsonArray JSON array string
     * @param candidates Original candidate list for validation and text lookup
     * @return List of parsed RelevancyScore objects
     * @throws LLMException if parsing fails
     */
    private List<RelevancyScore> parseScoreArray(String jsonArray, List<String> candidates) {
        List<RelevancyScore> scores = new ArrayList<>();

        // Remove outer brackets
        String content = jsonArray.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            throw new LLMException("Response is not a JSON array", ErrorType.PARSE_ERROR);
        }

        content = content.substring(1, content.length() - 1).trim();

        if (content.isEmpty()) {
            return scores;
        }

        // One object at a time, reading each field on its own, so key order and any extra keys
        // the model added do not decide whether the reply is understood at all.
        boolean anyObjectRead = false;
        Matcher matcher = SCORE_OBJECT_PATTERN.matcher(content);
        while (matcher.find()) {
            String object = matcher.group();
            Matcher indexField = SCORE_INDEX_FIELD.matcher(object);
            Matcher scoreField = SCORE_VALUE_FIELD.matcher(object);
            if (!indexField.find() || !scoreField.find()) {
                LOG.warn("Score object carries no index/score pair: {}", object);
                continue;
            }

            try {
                int index = Integer.parseInt(indexField.group(1));
                double score = Double.parseDouble(scoreField.group(1));
                anyObjectRead = true;

                // Domain correctness: skip invalid indices instead of clamping,
                // which would silently attribute scores to wrong candidates
                if (index < 0 || index >= candidates.size()) {
                    LOG.warn("LLM returned invalid score index {} (candidates size {}), skipping",
                        index, candidates.size());
                    continue;
                }

                // Index is in range: the guard above skipped every other case.
                String candidateText = candidates.get(index);

                // RelevancyScore constructor clamps score to [0.0, 1.0]
                scores.add(new RelevancyScore(index, score, candidateText));
            } catch (NumberFormatException e) {
                LOG.warn("Failed to parse score object: {}", object);
                // Continue parsing other objects
            }
        }

        // A non-empty array in which nothing at all could be read is a reply that was not
        // understood, and saying so is what lets the caller fall back to the vector ranking.
        // Returning an empty list instead reported success, counted a success on the circuit
        // breaker, and answered the query with nothing.
        //
        // An object that was read and then discarded for naming a candidate that does not exist
        // is a different case: the reply was understood, and discarding is the documented
        // handling - clamping would attribute the model's score to a document it never saw.
        if (!anyObjectRead) {
            throw new LLMException(
                "Response is a JSON array but contains no readable score objects: " + content,
                ErrorType.PARSE_ERROR);
        }

        return scores;
    }
}
