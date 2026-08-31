package excalibur.llm.embedding;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.LLMException.ErrorType;
import io.excalibur.llm.client.CircuitBreaker;
import io.excalibur.llm.client.CircuitBreakerState;
import io.excalibur.llm.json.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Dedicated client for generating embeddings via the OpenAI-compatible `/v1/embeddings` endpoint.
 *
 * <p>This client provides a clean interface for converting text into vector embeddings
 * that can be used for semantic search, similarity comparison, and other NLP tasks.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Single text embedding generation</li>
 *   <li>Batch embedding generation for multiple texts</li>
 *   <li>Optional circuit breaker for resilience</li>
 *   <li>Exponential backoff retry logic</li>
 *   <li>Configurable model and dimensionality</li>
 *   <li>Correlation ID support for distributed tracing</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The internal {@link HttpClient} is immutable and can be
 * safely shared across threads. The {@link CircuitBreaker} is also thread-safe.
 * All methods can be called concurrently from multiple threads.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Input text: non-null, non-empty strings</li>
 *   <li>Output embeddings: non-null float arrays with length in [1, 4096]</li>
 *   <li>All embedding values: finite (not NaN or Infinite)</li>
 *   <li>HTTP timeout: positive value from configuration</li>
 *   <li>Retry delay: exponentially increasing with jitter, capped at 30 seconds</li>
 *   <li>Batch size: at least 1, no artificial upper limit (API-dependent)</li>
 * </ul>
 *
 * @see LLMClientConfig
 * @see CircuitBreaker
 * @see EmbeddingResponse
 */
public final class EmbeddingClient {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingClient.class);

    // HTTP headers
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String JSON_CONTENT_TYPE = "application/json";

    // Endpoint path
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    // Dimension constraints
    private static final int MIN_EMBEDDING_DIMENSION = 1;
    private static final int MAX_EMBEDDING_DIMENSION = 4096;

    // Retry configuration (base delay is configurable via LLMClientConfig.getRetryBaseDelay())
    private static final long RETRY_MAX_DELAY_MS = 30_000L;
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;
    private static final double RETRY_JITTER_FACTOR = 0.25;
    private static final RandomGenerator RANDOM = RandomGeneratorFactory.getDefault().create();

    // HTTP client and configuration
    private final HttpClient httpClient;
    private final LLMClientConfig config;
    private final CircuitBreaker circuitBreaker;

    /**
     * Creates a new EmbeddingClient with the given configuration.
     *
     * <p>Initializes an internal circuit breaker with configuration from
     * {@link LLMClientConfig#getFailureThreshold()} and
     * {@link LLMClientConfig#getCooldownPeriod()}.</p>
     *
     * @param config Client configuration (must not be null)
     * @throws NullPointerException if config is null
     */
    public EmbeddingClient(LLMClientConfig config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.httpClient = createHttpClient(config.getTimeout());
        this.circuitBreaker = new CircuitBreaker(
            config.getFailureThreshold(),
            config.getCooldownPeriod(),
            "embedding-client"
        );

        LOG.debug("EmbeddingClient initialized with baseUrl={}, model={}, dimension={}",
                config.getBaseUrl(), config.getEmbeddingModel(), config.getEmbeddingDimension());
    }

    /**
     * Creates a new EmbeddingClient with the given configuration and circuit breaker.
     *
     * <p>Use this constructor when you need to share a circuit breaker across
     * multiple client instances or provide a custom circuit breaker configuration.</p>
     *
     * @param config Client configuration (must not be null)
     * @param circuitBreaker Circuit breaker for resilience (must not be null)
     * @throws NullPointerException if config or circuitBreaker is null
     */
    public EmbeddingClient(LLMClientConfig config, CircuitBreaker circuitBreaker) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "CircuitBreaker cannot be null");
        this.httpClient = createHttpClient(config.getTimeout());

        LOG.debug("EmbeddingClient initialized with custom circuit breaker, baseUrl={}, model={}",
                config.getBaseUrl(), config.getEmbeddingModel());
    }

    // ==================== Public API ====================

    /**
     * Generates an embedding vector for the given text.
     *
     * <p>This method does NOT use circuit breaker protection. For circuit breaker
     * protection, use {@link #embedWithCircuitBreaker(String)}.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Input: non-null, non-empty string</li>
     *   <li>Output: non-null float array with length in [1, 4096]</li>
     *   <li>All values in output are finite (not NaN or Infinite)</li>
     * </ul>
     *
     * @param text The text to embed (must not be null or empty)
     * @return Embedding vector as float array
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public float[] embed(String text) {
        return embed(text, null);
    }

    /**
     * Generates an embedding vector for the given text with optional correlation ID.
     *
     * @param text The text to embed (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing (may be null or empty)
     * @return Embedding vector as float array
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if the API call fails or returns invalid data
     */
    public float[] embed(String text, String correlationId) {
        Objects.requireNonNull(text, "Text cannot be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }

        LOG.debug("Generating embedding for text of length {} characters, correlationId={}",
                text.length(), correlationId);

        // Build request
        String requestBody = buildEmbeddingRequestBody(text);
        String url = config.getBaseUrl() + EMBEDDINGS_PATH;

        // Execute request with retry
        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);

        // Parse and validate response
        float[] embedding = parseEmbeddingResponse(response.body());
        validateEmbedding(embedding);

        LOG.debug("Generated embedding of dimension {}, correlationId={}", embedding.length, correlationId);
        return embedding;
    }

    /**
     * Generates an embedding with circuit breaker protection.
     *
     * <p>If the circuit breaker is open, this method fails fast with an
     * {@link LLMException} of type {@link ErrorType#CIRCUIT_OPEN}.</p>
     *
     * @param text The text to embed (must not be null or empty)
     * @return Embedding vector as float array
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if circuit is open or API call fails
     */
    public float[] embedWithCircuitBreaker(String text) {
        return embedWithCircuitBreaker(text, null);
    }

    /**
     * Generates an embedding with circuit breaker protection and correlation ID.
     *
     * @param text The text to embed (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing
     * @return Embedding vector as float array
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if circuit is open or API call fails
     */
    public float[] embedWithCircuitBreaker(String text, String correlationId) {
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            LOG.warn("Circuit breaker is OPEN, failing fast (correlationId={})", correlationId);
            throw new LLMException(
                "Circuit breaker is open - embedding endpoint appears unavailable",
                ErrorType.CIRCUIT_OPEN
            );
        }

        try {
            float[] embedding = embed(text, correlationId);
            circuitBreaker.recordSuccess();
            return embedding;
        } catch (LLMException e) {
            if (shouldCountAsFailure(e)) {
                circuitBreaker.recordFailure();
                LOG.debug("Recorded circuit breaker failure: {} (correlationId={})",
                        e.getErrorType(), correlationId);
            }
            throw e;
        }
    }

    /**
     * Generates embeddings for multiple texts in a batch.
     *
     * <p>This method sends all texts in a single API call, which is more efficient
     * than calling {@link #embed(String)} multiple times for large batches.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Input: non-null, non-empty list of non-null, non-empty strings</li>
     *   <li>Output: non-null list of float arrays, same size as input list</li>
     *   <li>Each embedding has length in [1, 4096] and all values are finite</li>
     *   <li>Order is preserved: result[i] corresponds to texts[i]</li>
     * </ul>
     *
     * @param texts List of texts to embed (must not be null or empty)
     * @return List of embedding vectors, one per input text, in the same order
     * @throws NullPointerException if texts is null or contains null elements
     * @throws IllegalArgumentException if texts is empty or contains empty strings
     * @throws LLMException if the API call fails or returns invalid data
     */
    public List<float[]> embedBatch(List<String> texts) {
        return embedBatch(texts, null);
    }

    /**
     * Generates embeddings for multiple texts in a batch with correlation ID.
     *
     * @param texts List of texts to embed (must not be null or empty)
     * @param correlationId Optional correlation ID for request tracing
     * @return List of embedding vectors, one per input text, in the same order
     * @throws NullPointerException if texts is null or contains null elements
     * @throws IllegalArgumentException if texts is empty or contains empty strings
     * @throws LLMException if the API call fails or returns invalid data
     */
    public List<float[]> embedBatch(List<String> texts, String correlationId) {
        Objects.requireNonNull(texts, "Texts cannot be null");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("Texts cannot be empty");
        }

        // Validate all texts before making the API call
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null) {
                throw new NullPointerException("Text at index " + i + " cannot be null");
            }
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Text at index " + i + " cannot be empty");
            }
        }

        LOG.debug("Generating batch embeddings for {} texts, correlationId={}",
                texts.size(), correlationId);

        // Build batch request
        String requestBody = buildBatchEmbeddingRequestBody(texts);
        String url = config.getBaseUrl() + EMBEDDINGS_PATH;

        // Execute request
        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);

        // Parse and validate response
        List<float[]> embeddings = parseBatchEmbeddingResponse(response.body(), texts.size());

        // Validate each embedding
        for (int i = 0; i < embeddings.size(); i++) {
            validateEmbedding(embeddings.get(i));
        }

        LOG.debug("Generated {} batch embeddings, correlationId={}", embeddings.size(), correlationId);
        return embeddings;
    }

    /**
     * Generates a rich embedding response with metadata.
     *
     * <p>Returns an {@link EmbeddingResponse} containing the embedding vector,
     * token usage information, and model name.</p>
     *
     * @param text The text to embed
     * @return EmbeddingResponse with embedding and metadata
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if the API call fails
     */
    public EmbeddingResponse embedWithMetadata(String text) {
        return embedWithMetadata(text, null);
    }

    /**
     * Generates a rich embedding response with metadata and correlation ID.
     *
     * @param text The text to embed
     * @param correlationId Optional correlation ID for tracing
     * @return EmbeddingResponse with embedding and metadata
     * @throws NullPointerException if text is null
     * @throws IllegalArgumentException if text is empty
     * @throws LLMException if the API call fails
     */
    public EmbeddingResponse embedWithMetadata(String text, String correlationId) {
        Objects.requireNonNull(text, "Text cannot be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }

        LOG.debug("Generating embedding with metadata for text of length {}, correlationId={}",
                text.length(), correlationId);

        String requestBody = buildEmbeddingRequestBody(text);
        String url = config.getBaseUrl() + EMBEDDINGS_PATH;

        HttpResponse<String> response = executeRequest(url, requestBody, correlationId);

        return parseEmbeddingResponseWithMetadata(response.body());
    }

    // ==================== Configuration Accessors ====================

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
     * @return Circuit breaker instance (never null)
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Returns the embedding model name configured for this client.
     *
     * @return Embedding model name
     */
    public String getModel() {
        return config.getEmbeddingModel();
    }

    /**
     * Returns the expected embedding dimension.
     *
     * @return Expected dimension in range [1, 4096]
     */
    public int getDimension() {
        return config.getEmbeddingDimension();
    }

    // ==================== Private Implementation ====================

    private HttpClient createHttpClient(Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(30);
        return HttpClient.newBuilder()
                .connectTimeout(effectiveTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private String buildEmbeddingRequestBody(String text) {
        String escapedText = JsonWriter.escapeString(text);
        String escapedModel = JsonWriter.escapeString(config.getEmbeddingModel());
        return "{\"model\":\"" + escapedModel + "\",\"input\":\"" + escapedText + "\"}";
    }

    private String buildBatchEmbeddingRequestBody(List<String> texts) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"model\":\"");
        sb.append(JsonWriter.escapeString(config.getEmbeddingModel()));
        sb.append("\",\"input\":[");

        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"');
            sb.append(JsonWriter.escapeString(texts.get(i)));
            sb.append('"');
        }

        sb.append("]}");
        return sb.toString();
    }

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

                // Add correlation ID header if provided
                if (correlationId != null && !correlationId.isBlank()) {
                    requestBuilder.header(CORRELATION_ID_HEADER, correlationId);
                }

                HttpRequest request = requestBuilder.build();

                if (LOG.isDebugEnabled() && attempt > 0) {
                    LOG.debug("Retry attempt {} of {} (correlationId={})",
                            attempt, maxRetries, correlationId);
                }

                // Bounded for the same reason as the chat client: an embedding reply that keeps
                // arriving is not covered by the request timeout.
                HttpResponse<String> response = httpClient.send(request,
                        excalibur.llm.client.BoundedBody.ofString(
                                config.getMaxResponseBytes(), StandardCharsets.UTF_8));

                int statusCode = response.statusCode();

                // Handle HTTP errors
                if (isErrorStatus(statusCode)) {
                    if (isRetryableStatus(statusCode)) {
                        lastException = new LLMException(
                                "Embedding API returned error status " + statusCode,
                                mapStatusToErrorType(statusCode), statusCode);
                        LOG.warn("Retryable HTTP error {} on attempt {}/{} (correlationId={})",
                                statusCode, attempt + 1, maxRetries + 1, correlationId);
                    } else {
                        throw new LLMException(
                                "Embedding API returned error status " + statusCode,
                                mapStatusToErrorType(statusCode), statusCode);
                    }
                } else {
                    return response;
                }

            } catch (HttpTimeoutException e) {
                lastException = new LLMException(
                        "Embedding API request timed out after " + config.getTimeout().toMillis() + "ms",
                        ErrorType.NETWORK, e);
                LOG.warn("Request timeout on attempt {}/{} (correlationId={})",
                        attempt + 1, maxRetries + 1, correlationId);

            } catch (IOException e) {
                lastException = new LLMException(
                        "IO error during embedding API call: " + e.getMessage(),
                        ErrorType.NETWORK, e);
                LOG.warn("IO error on attempt {}/{}: {} (correlationId={})",
                        attempt + 1, maxRetries + 1, e.getMessage(), correlationId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("Embedding API call interrupted", ErrorType.NETWORK, e);
            }

            // Retry delay
            if (attempt < maxRetries) {
                long delayMs = calculateRetryDelay(attempt);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LLMException("Interrupted during retry delay", ErrorType.NETWORK, e);
                }
            }
        }

        LOG.error("All {} retry attempts exhausted for embedding request (correlationId={})",
                maxRetries + 1, correlationId);

        // Domain correctness: ensure we always throw a documented exception
        // This should never be null in practice, but defensive programming ensures
        // we throw a documented LLMException rather than NullPointerException
        if (lastException == null) {
            throw new LLMException(
                "Embedding request failed after " + (maxRetries + 1) + " attempts with unknown error",
                ErrorType.NETWORK);
        }
        throw lastException;
    }

    private float[] parseEmbeddingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from embedding API", ErrorType.PARSE_ERROR);
        }

        // Find "embedding" array in response
        String embeddingKey = "\"embedding\"";
        int embeddingIndex = responseBody.indexOf(embeddingKey);

        if (embeddingIndex == -1) {
            LOG.error("Response does not contain 'embedding' field: {}", truncate(responseBody, 500));
            throw new LLMException("Response does not contain 'embedding' field", ErrorType.PARSE_ERROR);
        }

        int arrayStart = responseBody.indexOf('[', embeddingIndex);
        if (arrayStart == -1) {
            throw new LLMException("Could not find embedding array start", ErrorType.PARSE_ERROR);
        }

        int arrayEnd = findMatchingBracket(responseBody, arrayStart);
        if (arrayEnd == -1) {
            throw new LLMException("Could not find embedding array end", ErrorType.PARSE_ERROR);
        }

        String arrayContent = responseBody.substring(arrayStart + 1, arrayEnd);
        return parseFloatArray(arrayContent);
    }

    private List<float[]> parseBatchEmbeddingResponse(String responseBody, int expectedCount) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from embedding API", ErrorType.PARSE_ERROR);
        }

        // Find "data" array
        String dataKey = "\"data\"";
        int dataIndex = responseBody.indexOf(dataKey);
        if (dataIndex == -1) {
            throw new LLMException("Response does not contain 'data' field", ErrorType.PARSE_ERROR);
        }

        // Parse all embedding objects from the data array
        List<float[]> embeddings = new ArrayList<>(expectedCount);

        int pos = responseBody.indexOf('[', dataIndex);
        if (pos == -1) {
            throw new LLMException("Could not find data array start", ErrorType.PARSE_ERROR);
        }

        int dataEnd = findMatchingBracket(responseBody, pos);
        if (dataEnd == -1) {
            throw new LLMException("Could not find data array end", ErrorType.PARSE_ERROR);
        }

        String dataArray = responseBody.substring(pos + 1, dataEnd);

        // Parse each embedding object
        int currentPos = 0;
        while (currentPos < dataArray.length()) {
            int embIdx = dataArray.indexOf("\"embedding\"", currentPos);
            if (embIdx == -1) break;

            int arrStart = dataArray.indexOf('[', embIdx);
            if (arrStart == -1) break;

            int arrEnd = findMatchingBracket(dataArray, arrStart);
            if (arrEnd == -1) break;

            String arrContent = dataArray.substring(arrStart + 1, arrEnd);
            embeddings.add(parseFloatArray(arrContent));

            currentPos = arrEnd + 1;
        }

        if (embeddings.size() != expectedCount) {
            throw new LLMException(
                    "Embedding count mismatch: expected " + expectedCount +
                    ", got " + embeddings.size(), ErrorType.PARSE_ERROR);
        }

        return embeddings;
    }

    private EmbeddingResponse parseEmbeddingResponseWithMetadata(String responseBody) {
        float[] embedding = parseEmbeddingResponse(responseBody);
        validateEmbedding(embedding);

        // Parse usage if available
        int totalTokens = 0;
        String usageKey = "\"usage\"";
        int usageIdx = responseBody.indexOf(usageKey);
        if (usageIdx != -1) {
            String totalTokensKey = "\"total_tokens\"";
            int tokensIdx = responseBody.indexOf(totalTokensKey, usageIdx);
            if (tokensIdx != -1) {
                int colonIdx = responseBody.indexOf(':', tokensIdx);
                if (colonIdx != -1) {
                    int start = colonIdx + 1;
                    while (start < responseBody.length() && !Character.isDigit(responseBody.charAt(start))) {
                        start++;
                    }
                    int end = start;
                    while (end < responseBody.length() && Character.isDigit(responseBody.charAt(end))) {
                        end++;
                    }
                    if (start < end) {
                        try {
                            totalTokens = Integer.parseInt(responseBody.substring(start, end));
                        } catch (NumberFormatException e) {
                            // Keep default of 0
                        }
                    }
                }
            }
        }

        return new EmbeddingResponse(embedding, config.getEmbeddingModel(), totalTokens);
    }

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

    private float[] parseFloatArray(String arrayContent) {
        String[] parts = arrayContent.split(",");
        if (parts.length == 0 || (parts.length == 1 && parts[0].trim().isEmpty())) {
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
                throw new LLMException(
                        "Invalid float value in embedding array at index " + i + ": " + part,
                        ErrorType.PARSE_ERROR, e);
            }
        }

        return result;
    }

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

        for (int i = 0; i < length; i++) {
            float value = embedding[i];
            if (Float.isNaN(value)) {
                throw new LLMException("Embedding contains NaN at index " + i, ErrorType.PARSE_ERROR);
            }
            if (Float.isInfinite(value)) {
                throw new LLMException("Embedding contains Infinite at index " + i, ErrorType.PARSE_ERROR);
            }
        }

        // Enforce that embedding dimension matches configured dimensionality
        // Domain correctness: embeddings must be float arrays of configured dimensionality
        int configuredDim = config.getEmbeddingDimension();
        if (length != configuredDim) {
            throw new LLMException(
                    "Embedding dimension (" + length + ") does not match configured dimensionality (" +
                    configuredDim + "). " +
                    "Ensure the embedding model '" + config.getEmbeddingModel() + "' produces " +
                    configuredDim + "-dimensional vectors, or update excalibur.llm.embeddings-dimensions.",
                    ErrorType.PARSE_ERROR);
        }
    }

    private boolean isErrorStatus(int statusCode) {
        return statusCode >= 400;
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private ErrorType mapStatusToErrorType(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> ErrorType.AUTH_ERROR;
            case 429 -> ErrorType.RATE_LIMITED;
            case 400 -> ErrorType.INVALID_REQUEST;
            default -> ErrorType.HTTP_ERROR;
        };
    }

    private boolean shouldCountAsFailure(LLMException e) {
        return switch (e.getErrorType()) {
            case NETWORK, HTTP_ERROR, RATE_LIMITED, AUTH_ERROR, PARSE_ERROR, INVALID_REQUEST -> true;
            case CIRCUIT_OPEN -> false;
        };
    }

    private long calculateRetryDelay(int attempt) {
        long baseDelayMs = config.getRetryBaseDelay().toMillis();
        double exponentialDelay = baseDelayMs * Math.pow(RETRY_BACKOFF_MULTIPLIER, attempt);
        long cappedDelay = Math.min((long) exponentialDelay, RETRY_MAX_DELAY_MS);
        double jitter = 1.0 + (RANDOM.nextDouble() * 2 - 1) * RETRY_JITTER_FACTOR;
        long delayWithJitter = (long) (cappedDelay * jitter);
        // Ensure minimum delay (at least configured base delay after jitter)
        return Math.max(delayWithJitter, baseDelayMs);
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "null";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength) + "...";
    }

    @Override
    public String toString() {
        return "EmbeddingClient{" +
                "model='" + config.getEmbeddingModel() + '\'' +
                ", dimension=" + config.getEmbeddingDimension() +
                ", baseUrl='" + config.getBaseUrl() + '\'' +
                ", circuitBreakerState=" + circuitBreaker.getState() +
                '}';
    }
}
