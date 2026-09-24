package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.LLMException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call its caller abandoned is not evidence against the endpoint.
 *
 * <p>A query with a deadline interrupts the model call it no longer has time for, and a cancelled
 * query interrupts the one in flight. Either is the caller's decision, made however healthy the
 * endpoint is; counting it against the breaker would let a burst of short deadlines open the
 * circuit on a model that was answering fine.</p>
 */
@Timeout(value = 60)
class LLMHttpClientInterruptBreakerTest {

    private MockLLMServer server;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(30))
            .maxRetries(0)
            .failureThreshold(1)
            .cooldownPeriod(Duration.ofSeconds(60))
            .embeddingDimension(3)
            .build();
        client = new LLMHttpClient(config);
        server.setBothHandlers(server.delayedHandler(
            server.successEmbeddingHandler(new float[] {1f, 2f, 3f}), 20_000));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("an interrupted embedding is not recorded against the embedding breaker")
    void interruptedEmbeddingIsNotAFailure() throws Exception {
        LLMException thrown = interrupted(() -> client.generateEmbedding("hello"));

        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertEquals(0, client.getEmbeddingCircuitBreaker().getFailureCount());
        assertEquals(CircuitBreakerState.CLOSED, client.getEmbeddingCircuitBreaker().getState());
    }

    @Test
    @DisplayName("an interrupted rerank call is not recorded against the chat breaker")
    void interruptedRerankIsNotAFailure() throws Exception {
        LLMException thrown = interrupted(
            () -> client.scoreRelevancyWithCircuitBreaker("hello", List.of("a"), null));

        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertEquals(0, client.getCircuitBreaker().getFailureCount());
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
    }

    /** Runs {@code call} on its own thread, interrupts it mid-request, and returns what it threw. */
    private LLMException interrupted(Runnable call) throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            worker.set(Thread.currentThread());
            call.run();
        }, runnable -> Thread.ofVirtual().start(runnable));

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.getTotalRequestCount() == 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertTrue(server.getTotalRequestCount() > 0, "the request never reached the server");
        worker.get().interrupt();

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> running.get(10, TimeUnit.SECONDS));
        return assertInstanceOf(LLMException.class, failure.getCause());
    }
}
