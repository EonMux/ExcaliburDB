package com.eonmux.excalibur.llm.embedding;

import com.sun.net.httpserver.HttpServer;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.LLMException.ErrorType;
import com.eonmux.excalibur.llm.client.CircuitBreakerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the embedding path to failing fast once the provider has proved it is down.
 *
 * <p>Embedding is on the hot path of both ingestion and query: every chunk written and every
 * question asked calls it. A breaker that only guards the one method nobody calls leaves a dead
 * provider being retried by every one of those requests, each paying the full retry schedule
 * before giving up, and the node spends its threads waiting on a socket instead of refusing
 * work it already knows will fail.</p>
 */
@Timeout(value = 60)
class EmbeddingCircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 500;
    private volatile String body = "{\"error\":\"down\"}";
    private EmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "embed-breaker-test");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .failureThreshold(FAILURE_THRESHOLD)
            .cooldownPeriod(Duration.ofSeconds(60))
            .embeddingDimension(3)
            .build();
        client = new EmbeddingClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Drives the provider's failures until the breaker gives up on it. */
    private void tripTheBreaker(Runnable call) {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class, call::run);
        }
    }

    @Test
    @DisplayName("embed stops calling a provider that has failed the threshold")
    void embedFailsFastOnceOpen() {
        tripTheBreaker(() -> client.embed("hello"));

        int reached = requests.get();
        assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState(),
            "The default embedding call must feed the breaker, not bypass it");

        LLMException thrown = assertThrows(LLMException.class, () -> client.embed("hello"));
        assertEquals(ErrorType.CIRCUIT_OPEN, thrown.getErrorType());
        assertEquals(reached, requests.get(),
            "Nothing may reach a provider the breaker has given up on");
    }

    @Test
    @DisplayName("embedBatch stops calling a provider that has failed the threshold")
    void embedBatchFailsFastOnceOpen() {
        tripTheBreaker(() -> client.embedBatch(List.of("a", "b")));

        int reached = requests.get();
        assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState(),
            "Ingestion embeds in batches; that path needs the breaker most of all");

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.embedBatch(List.of("a", "b")));
        assertEquals(ErrorType.CIRCUIT_OPEN, thrown.getErrorType());
        assertEquals(reached, requests.get());
    }

    @Test
    @DisplayName("embedWithMetadata stops calling a provider that has failed the threshold")
    void embedWithMetadataFailsFastOnceOpen() {
        tripTheBreaker(() -> client.embedWithMetadata("hello"));

        int reached = requests.get();
        assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState());

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.embedWithMetadata("hello"));
        assertEquals(ErrorType.CIRCUIT_OPEN, thrown.getErrorType());
        assertEquals(reached, requests.get());
    }

    @Test
    @DisplayName("one failure is counted once, however the caller entered")
    void explicitBreakerEntryDoesNotDoubleCount() {
        // Two failures against a threshold of three must leave the circuit closed. If the
        // explicit entry point counted a failure and the call underneath counted it again, the
        // breaker would trip at half its configured threshold.
        assertThrows(LLMException.class, () -> client.embedWithCircuitBreaker("hello"));
        assertThrows(LLMException.class, () -> client.embedWithCircuitBreaker("hello"));

        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState(),
            "Two failures must not trip a breaker configured for three");
        assertEquals(2, client.getCircuitBreaker().getFailureCount());
    }

    @Test
    @DisplayName("a recovered provider closes the circuit again")
    void recoveryClosesTheCircuit() throws Exception {
        LLMClientConfig quick = LLMClientConfig.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .failureThreshold(FAILURE_THRESHOLD)
            .cooldownPeriod(Duration.ofMillis(150))
            .embeddingDimension(3)
            .build();
        EmbeddingClient quickClient = new EmbeddingClient(quick);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class, () -> quickClient.embed("hello"));
        }
        assertEquals(CircuitBreakerState.OPEN, quickClient.getCircuitBreaker().getState());

        status = 200;
        body = "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}";
        Thread.sleep(200);

        float[] embedding = quickClient.embed("hello");

        assertEquals(3, embedding.length);
        assertEquals(CircuitBreakerState.CLOSED, quickClient.getCircuitBreaker().getState(),
            "A successful probe must close the circuit so normal traffic resumes");
    }

    @Test
    @DisplayName("a successful call clears the failures that preceded it")
    void successResetsTheFailureCount() {
        assertThrows(LLMException.class, () -> client.embed("hello"));
        assertTrue(client.getCircuitBreaker().getFailureCount() > 0);

        status = 200;
        body = "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}";

        client.embed("hello");

        assertEquals(0, client.getCircuitBreaker().getFailureCount(),
            "Failures must be consecutive to mean anything");
    }
}
