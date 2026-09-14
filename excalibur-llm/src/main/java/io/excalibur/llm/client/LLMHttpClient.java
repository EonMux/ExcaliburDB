package io.excalibur.llm.client;

import excalibur.llm.client.CorrelationId;
import excalibur.llm.client.SafeUrl;
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
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

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
 * inside (e.g., {@code ```json\n[{"score": 0.5}]\n```}), the {@link ChatResponseParser}
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
 * @see ChatResponseParser
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

    /**
     * Token ceiling for {@link #complete}, which is used for short constrained generations.
     *
     * <p>Generous enough for a list of search formulations and small enough that a model which
     * starts writing prose is cut off rather than billed for it.</p>
     */
    private static final int COMPLETION_MAX_TOKENS = 512;

    /** Default request timeout in milliseconds */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** Maximum embedding dimension supported */
    private static final int MAX_EMBEDDING_DIMENSION = 4096;

    /** Minimum embedding dimension */
    private static final int MIN_EMBEDDING_DIMENSION = 1;

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

    /**
     * Guards {@code /v1/embeddings}, separately from the breaker guarding chat completions.
     *
     * <p>They are different endpoints, usually different models and often different
     * deployments behind one base URL, and either can be saturated, reloading or missing while
     * the other serves normally. One breaker across both would couple the failure domains in
     * both directions: a reranker that fell over would stop ingestion, and an embedding model
     * being swapped would stop reranking. Each outage would become two.</p>
     *
     * <p>The cost of separating them is that a base URL which is wholly unreachable has to be
     * discovered twice, once per endpoint. That is a few failed requests, against an outage
     * that would otherwise be twice as wide.</p>
     */
    private final CircuitBreaker embeddingCircuitBreaker;

    private final ChatResponseParser responseParser;

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

        // Initialize circuit breakers from config, one per endpoint
        this.circuitBreaker = new CircuitBreaker(
            config.getFailureThreshold(),
            config.getCooldownPeriod(),
            "llm-chat"
        );
        this.embeddingCircuitBreaker = newEmbeddingCircuitBreaker(config);

        this.responseParser = new ChatResponseParser();

        LOG.debug("LLMHttpClient initialized with baseUrl={}, embeddingModel={}, circuitBreaker={}",
                SafeUrl.redact(config.getBaseUrl()), config.getEmbeddingModel(), circuitBreaker);
    }

    /**
     * Creates a new LLMHttpClient with the given configuration and circuit breaker.
     *
     * <p>Use this constructor when you need to share a circuit breaker across
     * multiple client instances or provide a custom circuit breaker configuration.</p>
     *
     * <p>The supplied breaker guards chat completions only. Embeddings keep a breaker of their
     * own, built from the configuration, because sharing one across two endpoints that fail
     * independently makes each outage twice as wide.</p>
     *
     * @param config Client configuration (must not be null)
     * @param circuitBreaker Circuit breaker guarding chat completions (must not be null)
     * @throws IllegalArgumentException if config or circuitBreaker is null
     */
    public LLMHttpClient(LLMClientConfig config, CircuitBreaker circuitBreaker) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "CircuitBreaker cannot be null");
        this.embeddingCircuitBreaker = newEmbeddingCircuitBreaker(config);
        this.httpClient = createHttpClient(config.getTimeout());
        this.responseParser = new ChatResponseParser();

        LOG.debug("LLMHttpClient initialized with baseUrl={}, embeddingModel={}, providedCircuitBreaker",
                SafeUrl.redact(config.getBaseUrl()), config.getEmbeddingModel());
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

        if (!embeddingCircuitBreaker.allowRequest()) {
            LOG.warn("Circuit breaker '{}' is {}, failing fast (correlationId={})",
                    embeddingCircuitBreaker.getName(), embeddingCircuitBreaker.getState(),
                    correlationId);
            throw new LLMException(
                "Circuit breaker is open - the embeddings endpoint appears unavailable",
                ErrorType.CIRCUIT_OPEN);
        }

        try {
            float[] embedding = generateEmbeddingUnguarded(text, correlationId);
            embeddingCircuitBreaker.recordSuccess();
            return embedding;
        } catch (LLMException e) {
            if (shouldCountAsFailure(e)) {
                embeddingCircuitBreaker.recordFailure();
                LOG.debug("Recorded embedding circuit breaker failure: {} (correlationId={})",
                        e.getErrorType(), correlationId);
            }
            throw e;
        }
    }

    /**
     * Generates an embedding without consulting the circuit breaker.
     *
     * <p>Split out so that the breaker's bookkeeping reads as one thing and the call as
     * another; every caller reaches this through {@link #generateEmbedding(String, String)}.</p>
     *
     * @param text          The text to embed
     * @param correlationId Correlation id for tracing, or null
     * @return The embedding vector
     * @throws LLMException if the call fails or the reply cannot be read
     */
    private float[] generateEmbeddingUnguarded(String text, String correlationId) {
        LOG.debug("Generating embedding for text of length {} characters, correlationId={}",
                text.length(), correlationId);

        String requestBody = buildEmbeddingRequestBody(text, config.getEmbeddingModel());
        String url = config.getBaseUrl() + EMBEDDINGS_PATH;

        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);
        validateHttpStatus(response.statusCode());

        float[] embedding = parseEmbeddingResponse(response.body());
        validateEmbedding(embedding);

        LOG.debug("Generated embedding of dimension {}, correlationId={}",
                embedding.length, correlationId);
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
        List<RelevancyScore> scores = responseParser.parseRelevancyResponse(response.body(), candidates);

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

        // chat_template_kwargs.enable_thinking=false, and a token ceiling.
        //
        // Reasoning models spend their budget thinking before they answer, which is the wrong
        // trade for the short constrained generations this method is used for. Measured against
        // a Qwen3 27B endpoint on a query-expansion prompt: 4.78s and an empty answer with
        // thinking on, 2.02s and a complete one with it off, because every token of an 8-token
        // budget went to reasoning. Servers that do not recognise the field ignore it, so this
        // is safe against endpoints that never had a thinking mode.
        String requestBody = "{\"model\":\"" + JsonWriter.escapeString(config.getChatModel())
            + "\",\"messages\":["
            + "{\"role\":\"system\",\"content\":\"" + JsonWriter.escapeString(instruction) + "\"},"
            + "{\"role\":\"user\",\"content\":\"" + JsonWriter.escapeString(input) + "\"}"
            + "],\"temperature\":0.0"
            + ",\"max_tokens\":" + COMPLETION_MAX_TOKENS
            + ",\"chat_template_kwargs\":{\"enable_thinking\":false}}";

        HttpResponse<String> response = executeRequest(
            config.getBaseUrl() + CHAT_COMPLETIONS_PATH, requestBody, correlationId);
        validateHttpStatus(response.statusCode());
        return responseParser.extractMessageContent(response.body());
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
     * Returns the circuit breaker guarding chat completions.
     *
     * <p>Useful for monitoring circuit breaker state via JMX or metrics.</p>
     *
     * @return Circuit breaker instance (never null)
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Returns the circuit breaker guarding the embeddings endpoint.
     *
     * <p>Distinct from {@link #getCircuitBreaker()}: a deployment watching for provider trouble
     * has to watch both, because either endpoint can be down while the other is healthy.</p>
     *
     * @return Circuit breaker instance (never null)
     */
    public CircuitBreaker getEmbeddingCircuitBreaker() {
        return embeddingCircuitBreaker;
    }

    /**
     * Builds the breaker guarding the embeddings endpoint.
     *
     * <p>Configured from the same thresholds as the chat breaker, because the question they
     * answer - how many consecutive failures mean this endpoint is down - has the same answer
     * for both. Only the counting is separate.</p>
     *
     * @param config Client configuration
     * @return A breaker for the embeddings endpoint
     */
    private static CircuitBreaker newEmbeddingCircuitBreaker(LLMClientConfig config) {
        return new CircuitBreaker(
            config.getFailureThreshold(),
            config.getCooldownPeriod(),
            "llm-embeddings"
        );
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
     * but valid JSON inside, the {@link ChatResponseParser} extracts the JSON.
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

                // Validated at the point of use: the id came from the API boundary, and an
                // unchecked value here is a header-injection attempt or an unbounded write.
                String safeCorrelationId = CorrelationId.sanitize(correlationId);
                if (safeCorrelationId != null) {
                    requestBuilder.header(CORRELATION_ID_HEADER, safeCorrelationId);
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
                        "LLM API request timed out after " + config.getTimeout().toMillis()
                            + "ms to " + SafeUrl.redact(url),
                        ErrorType.NETWORK, e);
                LOG.warn("Request timeout on attempt {}/{} (correlationId={})",
                        attempt + 1, maxRetries + 1, correlationId);

            } catch (IOException e) {
                // Domain correctness: IO error is a network-level failure (retryable)
                lastException = new LLMException(
                        "IO error during LLM API call to " + SafeUrl.redact(url) + ": "
                            + e.getMessage(),
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
                maxRetries + 1, SafeUrl.redact(url), correlationId);

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
        logMsg.append("  ").append(request.method()).append(" ")
              .append(SafeUrl.redact(url)).append("\n");
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
        // Numbered from zero, because that is what the reply is read as. The list used to be
        // numbered from one while the worked example below showed {"index": 0}, so a model that
        // followed the numbering it could see scored every passage one place to the left: the
        // first candidate was never scored, and the last index fell out of range and was
        // dropped. The bound check on the way back catches the overflow and nothing else.
        StringBuilder candidatesText = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                candidatesText.append('\n');
            }
            String escaped = escapeForPrompt(candidates.get(i));
            candidatesText.append(i).append(". ").append(escaped);
        }

        // Format the prompt using the Phase 7 specification template
        String prompt = String.format(
                "You are a relevancy judge. Given a user query and a list of candidate passages, " +
                "score each passage from 0.0 to 1.0 based on how relevant it is to answering the query. " +
                "Return ONLY a JSON array of objects: [{\"index\": 0, \"score\": 0.85}, ...], " +
                "where index is the number the candidate is listed under below. " +
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

}
