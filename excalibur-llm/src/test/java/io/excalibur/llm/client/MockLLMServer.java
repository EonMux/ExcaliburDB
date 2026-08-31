package io.excalibur.llm.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Configurable mock HTTP server for LLM API testing.
 *
 * <p>Provides a lightweight HTTP server that can be configured to return
 * various response types including success, error, and malformed JSON responses.
 * This enables comprehensive testing of the {@link LLMHttpClient} without
 * requiring an actual LLM backend.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP status codes: valid range [100, 599] (enforced via HttpStatus)</li>
 *   <li>Response delay: non-negative, bounded at 30 seconds max</li>
 *   <li>Embedding vectors: all values finite (not NaN/Inf), dimension in [1, 4096]</li>
 *   <li>Relevancy scores: each score in [0.0, 1.0] (clamped by RelevancyScore)</li>
 *   <li>Request tracking: thread-safe, concurrent access supported</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is managed via thread-safe collections
 * and atomic variables. Multiple test threads can interact with the same server
 * instance safely.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * MockLLMServer server = MockLLMServer.create();
 * server.start();
 *
 * // Configure success response for embeddings
 * server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f}));
 *
 * // Configure error response for chat completions
 * server.setChatHandler(server.errorHandler(500, "Internal Server Error"));
 *
 * // Test your client
 * LLMHttpClient client = new LLMHttpClient(config);
 * float[] embedding = client.generateEmbedding("test");
 *
 * // Verify request tracking
 * assertEquals(1, server.getEmbeddingRequestCount());
 *
 * server.stop();
 * }</pre>
 *
 * @see LLMHttpClient
 * @see LLMClientConfig
 */
public class MockLLMServer implements AutoCloseable {

    // Endpoint paths
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    // Maximum delay allowed (30 seconds) - prevents tests from hanging indefinitely
    private static final long MAX_DELAY_MS = 30_000L;

    // Longest a non-responding handler may hold its worker thread before releasing it.
    // Bounded so the pool always drains; tests that exercise client-side timeouts use
    // client timeouts far shorter than this.
    private static final long TIMEOUT_HANDLER_PARK_MS = 10_000L;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    // Server instance
    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;

    // Request tracking (thread-safe)
    private final AtomicInteger embeddingRequestCount = new AtomicInteger(0);
    private final AtomicInteger chatRequestCount = new AtomicInteger(0);
    private final CopyOnWriteArrayList<CapturedRequest> capturedEmbeddingRequests = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CapturedRequest> capturedChatRequests = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> lastRequestHeaders = new ConcurrentHashMap<>();

    // Current handlers
    private volatile HttpHandler embeddingHandler;
    private volatile HttpHandler chatHandler;

    /**
     * Creates a new mock server instance.
     *
     * <p>The server is created but not started. Call {@link #start()} to begin
     * accepting connections.</p>
     *
     * @return New MockLLMServer instance
     * @throws IOException if server cannot be created
     */
    public static MockLLMServer create() throws IOException {
        return new MockLLMServer();
    }

    private MockLLMServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        // A dedicated daemon-thread pool, never the default executor. The default runs handlers
        // inline on the server's non-daemon dispatcher thread, so a handler that blocks (see
        // timeoutHandler) parks that thread permanently, stop() cannot reclaim it, and the JVM
        // never exits. Daemon threads also let concurrent requests overlap, which the default
        // single-threaded dispatch cannot do.
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-llm-server-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
        this.port = server.getAddress().getPort();

        // Set default handlers that return success
        this.embeddingHandler = defaultSuccessEmbeddingHandler();
        this.chatHandler = defaultSuccessChatHandler();

        // Register contexts with dynamic dispatch to current handlers
        server.createContext(EMBEDDINGS_PATH, exchange -> {
            embeddingRequestCount.incrementAndGet();
            captureRequest(exchange, capturedEmbeddingRequests);
            embeddingHandler.handle(exchange);
        });

        server.createContext(CHAT_COMPLETIONS_PATH, exchange -> {
            chatRequestCount.incrementAndGet();
            captureRequest(exchange, capturedChatRequests);
            chatHandler.handle(exchange);
        });
    }

    /**
     * Starts the mock server.
     *
     * @return This server instance for method chaining
     */
    public MockLLMServer start() {
        server.start();
        return this;
    }

    /**
     * Stops the mock server.
     *
     * <p>Allows a short delay for pending exchanges to complete.</p>
     */
    public void stop() {
        server.stop(1);
        // The server does not own the executor it was given, so shut it down here. Handlers still
        // parked (timeoutHandler) are interrupted rather than waited for; their threads are daemons
        // and cannot keep the JVM alive either way.
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the port the server is listening on.
     *
     * @return Server port
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the base URL for the mock server.
     *
     * <p>Use this to configure the {@link LLMClientConfig}:</p>
     * <pre>{@code
     * LLMClientConfig config = LLMClientConfig.builder()
     *     .baseUrl(server.getBaseUrl())
     *     .build();
     * }</pre>
     *
     * @return Base URL string (e.g., "http://localhost:12345")
     */
    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    // ==================== Request Tracking ====================

    /**
     * Returns the number of embedding requests received.
     *
     * @return Embedding request count
     */
    public int getEmbeddingRequestCount() {
        return embeddingRequestCount.get();
    }

    /**
     * Returns the number of chat completion requests received.
     *
     * @return Chat request count
     */
    public int getChatRequestCount() {
        return chatRequestCount.get();
    }

    /**
     * Returns the total number of requests received.
     *
     * @return Total request count
     */
    public int getTotalRequestCount() {
        return embeddingRequestCount.get() + chatRequestCount.get();
    }

    /**
     * Returns the last captured request headers.
     *
     * <p>Note: Header names are preserved in their original case from the HTTP request.</p>
     *
     * @return Map of header names to values
     */
    public Map<String, String> getLastRequestHeaders() {
        return new ConcurrentHashMap<>(lastRequestHeaders);
    }

    /**
     * Returns a header value by name (case-insensitive lookup).
     *
     * @param name Header name to look up
     * @return Header value, or null if not found
     */
    public String getLastRequestHeader(String name) {
        if (name == null) return null;
        // Try exact match first
        String value = lastRequestHeaders.get(name);
        if (value != null) return value;
        // Try case-insensitive match
        for (Map.Entry<String, String> entry : lastRequestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Returns all captured embedding requests.
     *
     * @return List of captured requests
     */
    public List<CapturedRequest> getCapturedEmbeddingRequests() {
        return new ArrayList<>(capturedEmbeddingRequests);
    }

    /**
     * Returns all captured chat requests.
     *
     * @return List of captured requests
     */
    public List<CapturedRequest> getCapturedChatRequests() {
        return new ArrayList<>(capturedChatRequests);
    }

    /**
     * Resets all request tracking counters and captured data.
     */
    public void reset() {
        embeddingRequestCount.set(0);
        chatRequestCount.set(0);
        capturedEmbeddingRequests.clear();
        capturedChatRequests.clear();
        lastRequestHeaders.clear();
    }

    private void captureRequest(HttpExchange exchange, List<CapturedRequest> targetList) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });

            CapturedRequest request = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                headers,
                body
            );

            targetList.add(request);
            lastRequestHeaders.putAll(headers);
        } catch (IOException e) {
            // Ignore capture errors - don't fail the test
        }
    }

    // ==================== Handler Configuration ====================

    /**
     * Sets the handler for embedding requests.
     *
     * @param handler HTTP handler for embedding endpoint
     */
    public void setEmbeddingHandler(HttpHandler handler) {
        this.embeddingHandler = handler;
    }

    /**
     * Sets the handler for chat completion requests.
     *
     * @param handler HTTP handler for chat endpoint
     */
    public void setChatHandler(HttpHandler handler) {
        this.chatHandler = handler;
    }

    /**
     * Sets both handlers to use the same response configuration.
     *
     * <p>Convenience method for tests that want both endpoints
     * to behave the same way.</p>
     *
     * @param handler HTTP handler for both endpoints
     */
    public void setBothHandlers(HttpHandler handler) {
        this.embeddingHandler = handler;
        this.chatHandler = handler;
    }

    // ==================== Success Response Handlers ====================

    /**
     * Creates a handler that returns a successful embedding response.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Embedding dimension: validated to be in [1, 4096]</li>
     *   <li>All values: validated to be finite (not NaN/Inf)</li>
     *   <li>HTTP status: 200 OK</li>
     *   <li>Response format: OpenAI-compatible JSON</li>
     * </ul>
     *
     * @param embedding The embedding vector to return
     * @return HTTP handler for successful embedding response
     * @throws IllegalArgumentException if embedding is invalid
     */
    public HttpHandler successEmbeddingHandler(float[] embedding) {
        validateEmbedding(embedding);
        return exchange -> sendEmbeddingResponse(exchange, embedding, 200);
    }

    /**
     * Creates a handler that returns a successful chat completion response
     * with the given content in the message.
     *
     * @param content The content string to include in the response
     * @return HTTP handler for successful chat response
     */
    public HttpHandler successChatHandler(String content) {
        return exchange -> sendChatResponse(exchange, content, 200);
    }

    /**
     * Creates a handler that returns a successful relevancy scoring response.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>Scores: each score is clamped to [0.0, 1.0] by RelevancyScore</li>
     *   <li>Indices: correspond to original candidate order</li>
     *   <li>HTTP status: 200 OK</li>
     * </ul>
     *
     * @param scores Array of relevancy scores (0.0 to 1.0)
     * @return HTTP handler for successful relevancy response
     */
    public HttpHandler successRelevancyHandler(double... scores) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) json.append(", ");
            // Clamp score to [0.0, 1.0] for domain correctness
            double clampedScore = Math.max(0.0, Math.min(1.0, scores[i]));
            json.append("{\"index\": ").append(i).append(", \"score\": ").append(clampedScore).append("}");
        }
        json.append("]");
        return successChatHandler(json.toString());
    }

    /**
     * Creates a handler that returns a successful relevancy response with markdown fence.
     *
     * <p>Useful for testing the ResponseSanitizer.</p>
     *
     * @param scores Array of relevancy scores
     * @return HTTP handler that returns markdown-wrapped JSON
     */
    public HttpHandler successRelevancyWithMarkdownHandler(double... scores) {
        StringBuilder json = new StringBuilder("```json\n[");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) json.append(", ");
            double clampedScore = Math.max(0.0, Math.min(1.0, scores[i]));
            json.append("{\"index\": ").append(i).append(", \"score\": ").append(clampedScore).append("}");
        }
        json.append("]\n```");
        return successChatHandler(json.toString());
    }

    /**
     * Creates a handler that returns a successful relevancy response with commentary.
     *
     * <p>Useful for testing the ResponseSanitizer's extraction logic.</p>
     *
     * @param scores Array of relevancy scores
     * @return HTTP handler that returns JSON with surrounding commentary
     */
    public HttpHandler successRelevancyWithCommentaryHandler(double... scores) {
        StringBuilder json = new StringBuilder("Here are the relevancy scores:\n[");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) json.append(", ");
            double clampedScore = Math.max(0.0, Math.min(1.0, scores[i]));
            json.append("{\"index\": ").append(i).append(", \"score\": ").append(clampedScore).append("}");
        }
        json.append("]\nLet me know if you need more details!");
        return successChatHandler(json.toString());
    }

    // ==================== Error Response Handlers ====================

    /**
     * Creates a handler that returns an HTTP error response.
     *
     * <p>Domain Correctness:</p>
     * <ul>
     *   <li>HTTP status: validated to be in [100, 599]</li>
     *   <li>Response body: JSON error object with message</li>
     * </ul>
     *
     * @param statusCode HTTP status code (e.g., 500, 401, 429)
     * @param message Error message to include
     * @return HTTP handler for error response
     * @throws IllegalArgumentException if status code is invalid
     */
    public HttpHandler errorHandler(int statusCode, String message) {
        validateStatusCode(statusCode);
        return exchange -> sendErrorResponse(exchange, statusCode, message);
    }

    /**
     * Creates a handler that returns a 500 Internal Server Error.
     *
     * @return HTTP handler for 500 error
     */
    public HttpHandler internalServerErrorHandler() {
        return errorHandler(500, "Internal Server Error");
    }

    /**
     * Creates a handler that returns a 401 Unauthorized error.
     *
     * @return HTTP handler for 401 error
     */
    public HttpHandler unauthorizedHandler() {
        return errorHandler(401, "Unauthorized - Invalid API key");
    }

    /**
     * Creates a handler that returns a 403 Forbidden error.
     *
     * @return HTTP handler for 403 error
     */
    public HttpHandler forbiddenHandler() {
        return errorHandler(403, "Forbidden - Access denied");
    }

    /**
     * Creates a handler that returns a 429 Rate Limited error.
     *
     * @return HTTP handler for 429 error
     */
    public HttpHandler rateLimitedHandler() {
        return errorHandler(429, "Rate limit exceeded - Please retry after 60 seconds");
    }

    /**
     * Creates a handler that returns a 400 Bad Request error.
     *
     * @return HTTP handler for 400 error
     */
    public HttpHandler badRequestHandler() {
        return errorHandler(400, "Bad Request - Invalid parameters");
    }

    /**
     * Creates a handler that returns a 503 Service Unavailable error.
     *
     * @return HTTP handler for 503 error
     */
    public HttpHandler serviceUnavailableHandler() {
        return errorHandler(503, "Service Unavailable - Please try again later");
    }

    // ==================== Malformed Response Handlers ====================

    /**
     * Creates a handler that returns malformed JSON (invalid syntax).
     *
     * <p>Useful for testing error handling of parse failures.</p>
     *
     * @return HTTP handler for malformed JSON response
     */
    public HttpHandler malformedJsonHandler() {
        return exchange -> {
            String response = "{not valid json at all";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    /**
     * Creates a handler that returns an empty response body.
     *
     * @return HTTP handler for empty response
     */
    public HttpHandler emptyResponseHandler() {
        return exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        };
    }

    /**
     * Creates a handler that returns a response with missing required fields.
     *
     * <p>For embeddings: missing "embedding" field</p>
     *
     * @return HTTP handler for incomplete embedding response
     */
    public HttpHandler missingEmbeddingFieldHandler() {
        return exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    /**
     * Creates a handler that returns a response with missing content field.
     *
     * <p>For chat completions: missing "content" field</p>
     *
     * @return HTTP handler for incomplete chat response
     */
    public HttpHandler missingContentFieldHandler() {
        return exchange -> {
            String response = "{\"choices\":[{\"message\":{}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    /**
     * Creates a handler that returns an embedding with invalid (NaN) values.
     *
     * <p>Domain Correctness: Tests validation of non-finite values.</p>
     *
     * @return HTTP handler for embedding with NaN
     */
    public HttpHandler embeddingWithNaNHandler() {
        return exchange -> {
            // Return an embedding with NaN value - this should be rejected by validation
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,\"NaN\",0.3],\"index\":0}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    /**
     * Creates a handler that returns an embedding with Infinity value.
     *
     * <p>Domain Correctness: Tests validation of non-finite values.</p>
     *
     * @return HTTP handler for embedding with Infinity
     */
    public HttpHandler embeddingWithInfinityHandler() {
        return exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,\"Infinity\",0.3],\"index\":0}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    // ==================== Delayed Response Handlers ====================

    /**
     * Creates a handler that delays the response by the specified amount.
     *
     * <p>Domain Correctness: delay is bounded to MAX_DELAY_MS (30 seconds).</p>
     *
     * @param baseHandler The underlying handler to execute after delay
     * @param delayMs Delay in milliseconds (non-negative, max 30000)
     * @return HTTP handler with delayed response
     * @throws IllegalArgumentException if delay is negative or exceeds maximum
     */
    public HttpHandler delayedHandler(HttpHandler baseHandler, long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay must be non-negative, got: " + delayMs);
        }
        if (delayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException(
                "Delay must not exceed " + MAX_DELAY_MS + "ms, got: " + delayMs);
        }

        return exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            baseHandler.handle(exchange);
        };
    }

    /**
     * Creates a handler that never responds (for timeout testing).
     *
     * <p>The handler parks its worker thread for a bounded interval and then returns without
     * writing a response, so the client observes a read timeout. The park is bounded because
     * nothing wakes this thread otherwise: closing the client socket does not interrupt a thread
     * blocked in {@link Object#wait()}, and no other code notifies the exchange. An unbounded wait
     * here leaks a server worker per test.</p>
     *
     * @return HTTP handler that sends no response
     */
    public HttpHandler timeoutHandler() {
        return exchange -> {
            synchronized (exchange) {
                try {
                    exchange.wait(TIMEOUT_HANDLER_PARK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Deliberately no response: the client is expected to time out first.
        };
    }

    // ==================== Sequence Handlers ====================

    /**
     * Creates a handler that returns different responses in sequence.
     *
     * <p>Useful for testing retry logic or circuit breaker behavior.</p>
     *
     * @param handlers Array of handlers to invoke in sequence (cycles if exhausted)
     * @return HTTP handler that sequences through responses
     */
    public HttpHandler sequenceHandler(HttpHandler... handlers) {
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("Must provide at least one handler");
        }

        final AtomicInteger index = new AtomicInteger(0);
        return exchange -> {
            int current = index.getAndIncrement() % handlers.length;
            handlers[current].handle(exchange);
        };
    }

    /**
     * Creates a handler that fails N times then succeeds.
     *
     * <p>Convenience method for testing circuit breaker recovery.</p>
     *
     * @param failureCount Number of times to fail before succeeding
     * @param successHandler Handler to use after failures
     * @return HTTP handler that fails then succeeds
     */
    public HttpHandler failThenSucceedHandler(int failureCount, HttpHandler successHandler) {
        final AtomicInteger failures = new AtomicInteger(0);
        return exchange -> {
            if (failures.getAndIncrement() < failureCount) {
                sendErrorResponse(exchange, 500, "Temporary failure");
            } else {
                successHandler.handle(exchange);
            }
        };
    }

    // ==================== Custom Response Builder ====================

    /**
     * Creates a custom response handler using a builder pattern.
     *
     * @return New response builder
     */
    public ResponseBuilder responseBuilder() {
        return new ResponseBuilder();
    }

    /**
     * Builder for custom HTTP responses.
     */
    public class ResponseBuilder {
        private int statusCode = 200;
        private String body = "";
        private String contentType = "application/json";
        private long delayMs = 0;
        private final ConcurrentHashMap<String, String> headers = new ConcurrentHashMap<>();

        public ResponseBuilder statusCode(int code) {
            this.statusCode = code;
            return this;
        }

        public ResponseBuilder body(String body) {
            this.body = body;
            return this;
        }

        public ResponseBuilder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public ResponseBuilder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public ResponseBuilder delay(long delayMs) {
            this.delayMs = delayMs;
            return this;
        }

        public HttpHandler build() {
            return exchange -> {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                exchange.getResponseHeaders().set("Content-Type", contentType);
                headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            };
        }
    }

    // ==================== Private Implementation ====================

    private HttpHandler defaultSuccessEmbeddingHandler() {
        return successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
    }

    private HttpHandler defaultSuccessChatHandler() {
        return successChatHandler("[{\"index\": 0, \"score\": 0.5}]");
    }

    private void sendEmbeddingResponse(HttpExchange exchange, float[] embedding, int statusCode) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("],\"index\":0}],\"model\":\"test-embedding-model\",\"usage\":{\"prompt_tokens\":10,\"total_tokens\":10}}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendChatResponse(HttpExchange exchange, String content, int statusCode) throws IOException {
        String escaped = escapeForJson(content);
        String response = "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        String escaped = escapeForJson(message);
        String response = "{\"error\":{\"message\":\"" + escaped + "\",\"type\":\"error\",\"code\":" + statusCode + "}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String escapeForJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("Embedding cannot be null");
        }
        if (embedding.length < 1 || embedding.length > 4096) {
            throw new IllegalArgumentException(
                "Embedding dimension must be in [1, 4096], got: " + embedding.length);
        }
        for (int i = 0; i < embedding.length; i++) {
            if (Float.isNaN(embedding[i])) {
                throw new IllegalArgumentException("Embedding contains NaN at index " + i);
            }
            if (Float.isInfinite(embedding[i])) {
                throw new IllegalArgumentException("Embedding contains Infinite at index " + i);
            }
        }
    }

    private void validateStatusCode(int statusCode) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException(
                "HTTP status code must be in [100, 599], got: " + statusCode);
        }
    }

    // ==================== Captured Request Data Class ====================

    /**
     * Represents a captured HTTP request for testing verification.
     */
    public static final class CapturedRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers;
        private final String body;

        CapturedRequest(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = new ConcurrentHashMap<>(headers);
            this.body = body;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public Map<String, String> getHeaders() { return new ConcurrentHashMap<>(headers); }
        public String getBody() { return body; }

        /**
         * Returns a header value by name (case-insensitive lookup).
         *
         * @param name Header name to look up
         * @return Header value, or null if not found
         */
        public String getHeader(String name) {
            if (name == null) return null;
            // Try exact match first
            String value = headers.get(name);
            if (value != null) return value;
            // Try case-insensitive match
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "CapturedRequest{" +
                "method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", headers=" + headers.size() +
                ", bodyLength=" + (body != null ? body.length() : 0) +
                '}';
        }
    }
}
