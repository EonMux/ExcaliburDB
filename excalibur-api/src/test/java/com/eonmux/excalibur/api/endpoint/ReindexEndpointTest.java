package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.ingest.source.Source;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for ReindexEndpoint and related classes.
 */
class ReindexEndpointTest {

    private InMemorySourceRegistry sourceRegistry;
    private ReindexEndpoint.InMemoryReindexStatusRegistry statusRegistry;

    @BeforeEach
    void setUp() {
        sourceRegistry = new InMemorySourceRegistry();
        statusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
    }

    /** A source that is nothing but its id, which is all the registry reads here. */
    private static Source sourceNamed(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    @Test
    @DisplayName("ReindexStatus should have correct initial state")
    void testReindexStatusInitialState() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        assertEquals("test-source", status.sourceId());
        assertEquals("idle", status.state());
        assertEquals(0.0, status.progress());
        assertEquals(0L, status.startTimeMs());
        assertEquals(0L, status.entriesProcessed());
        assertEquals(0L, status.entriesTotal());
        assertNull(status.lastError());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should start correctly")
    void testReindexStatusStart() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        status.start();

        assertEquals("crawling", status.state());
        assertEquals(0.0, status.progress());
        assertTrue(status.startTimeMs() > 0);
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should update state correctly")
    void testReindexStatusUpdateState() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.updateState("embedding", 0.5, 500, 1000);

        assertEquals("embedding", status.state());
        assertEquals(0.5, status.progress());
        assertEquals(500L, status.entriesProcessed());
        assertEquals(1000L, status.entriesTotal());
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should complete correctly")
    void testReindexStatusComplete() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.complete();

        assertEquals("idle", status.state());
        assertEquals(1.0, status.progress());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should fail correctly")
    void testReindexStatusFail() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.fail("Connection timeout");

        assertEquals("failed", status.state(),
            "a run that stopped without finishing is not idle");
        assertEquals("Connection timeout", status.lastError());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus isInProgress should return true for active states")
    void testReindexStatusInProgress() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        status.updateState("crawling", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("embedding", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("indexing", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("idle", 0.0, 0, 0);
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("the source registry registers and reports what it holds")
    void testSourceRegistryRegisterAndCheck() {
        assertFalse(sourceRegistry.isRegistered("test-source"));

        sourceRegistry.register(sourceNamed("test-source"));
        assertTrue(sourceRegistry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("a removed source is no longer registered")
    void testSourceRegistryUnregister() {
        sourceRegistry.register(sourceNamed("test-source"));
        sourceRegistry.remove("test-source");

        assertFalse(sourceRegistry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should start reindex")
    void testStatusRegistryStartReindex() {
        statusRegistry.startReindex("test-source");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertNotNull(status);
        assertEquals("crawling", status.state());
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should update progress")
    void testStatusRegistryUpdateProgress() {
        statusRegistry.startReindex("test-source");
        statusRegistry.updateProgress("test-source", "embedding", 0.5, 500, 1000);

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("embedding", status.state());
        assertEquals(0.5, status.progress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should complete reindex")
    void testStatusRegistryCompleteReindex() {
        statusRegistry.startReindex("test-source");
        statusRegistry.completeReindex("test-source");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("idle", status.state());
        assertEquals(1.0, status.progress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should fail reindex")
    void testStatusRegistryFailReindex() {
        statusRegistry.startReindex("test-source");
        statusRegistry.failReindex("test-source", "Test error");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("failed", status.state(),
            "a run that stopped without finishing is not idle; /v1/reindex/status serves this");
        assertEquals("Test error", status.lastError());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should get all statuses")
    void testStatusRegistryGetAllStatuses() {
        statusRegistry.startReindex("source-1");
        statusRegistry.startReindex("source-2");

        java.util.Map<String, ReindexEndpoint.ReindexStatus> all = statusRegistry.getAllStatuses();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("ReindexEndpoint should be created with default constructor")
    void testEndpointCreation() {
        ReindexEndpoint endpoint = new ReindexEndpoint();
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("ReindexEndpoint should be created with custom registries")
    void testEndpointWithCustomRegistries() {
        ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry);
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("ReindexEndpoint should reject null registries")
    void testEndpointNullRegistries() {
        assertThrows(NullPointerException.class, () ->
            new ReindexEndpoint(null, statusRegistry));
        assertThrows(NullPointerException.class, () ->
            new ReindexEndpoint(sourceRegistry, null));
    }

    @Test
    @DisplayName("a source id longer than one could be registered under is refused")
    void oversizedSourceIdIsRefused() throws IOException {
        String sourceId = "x".repeat(Source.MAX_ID_LENGTH + 1);

        Response response = post("{\"source_id\": \"" + sourceId + "\"}");

        assertThat(response.status())
            .as("registration bounds the id, so no source is registered under one this long")
            .isEqualTo(400);
        assertThat(response.body())
            .contains("source_id")
            .contains(String.valueOf(Source.MAX_ID_LENGTH))
            .doesNotContain(sourceId);
    }

    /** One captured HTTP response. */
    private record Response(int status, String body) {}

    private Response post(String requestBody) throws IOException {
        ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry);
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/reindex"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), responseBody.toString(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("Cancelling a reindex")
    class Cancelling {

        /**
         * A rolling replacement that hangs holds its source's claim for the life of the process,
         * and every later reindex of that source is answered 409 forever. Without a way to stop
         * it, the only recovery is restarting the node.
         */
        @Test
        @DisplayName("asks a running reindex to stop")
        void cancelsARunningReindex() throws IOException {
            com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
                new com.eonmux.excalibur.api.reindex.ReindexCoordinator();
            sourceRegistry.register(sourceNamed("docs"));
            coordinator.tryAcquire("docs");
            ReindexEndpoint endpoint =
                new ReindexEndpoint(sourceRegistry, statusRegistry, coordinator);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

            endpoint.handle(exchange("DELETE", "{\"source_id\": \"docs\"}", body));

            assertThat(body.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"cancelled\": true");
        }

        @Test
        @DisplayName("reports that nothing was running for a source with no reindex in flight")
        void nothingToCancel() throws IOException {
            com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
                new com.eonmux.excalibur.api.reindex.ReindexCoordinator();
            sourceRegistry.register(sourceNamed("docs"));
            ReindexEndpoint endpoint =
                new ReindexEndpoint(sourceRegistry, statusRegistry, coordinator);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

            endpoint.handle(exchange("DELETE", "{\"source_id\": \"docs\"}", body));

            assertThat(body.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"cancelled\": false");
        }

        /**
         * The claim used to be released by the submitted task's own finally. A virtual-thread
         * executor skips the task body entirely when the future is cancelled before the thread
         * is scheduled, so that finally never ran and the claim was never released: every later
         * reindex of the source answered 409 for the life of the process. That is the exact
         * condition this endpoint's DELETE exists to clear, produced by using it.
         */
        @Test
        @DisplayName("a reindex cancelled before it starts still releases its claim")
        void cancellingBeforeTheRunStartsReleasesTheClaim() throws Exception {
            com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
                new com.eonmux.excalibur.api.reindex.ReindexCoordinator();
            sourceRegistry.register(sourceNamed("docs"));
            ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry,
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class),
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.source.SourceResolver.class),
                null, null, coordinator, null);

            // Repeated, because the leak depends on the cancel landing before the run thread is
            // scheduled. One iteration would pass by luck; twenty will not.
            for (int attempt = 0; attempt < 20; attempt++) {
                endpoint.handle(exchange("POST", "{\"source_id\": \"docs\"}",
                    new java.io.ByteArrayOutputStream()));
                endpoint.handle(exchange("DELETE", "{\"source_id\": \"docs\"}",
                    new java.io.ByteArrayOutputStream()));

                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (coordinator.isRunning("docs") && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
                assertThat(coordinator.isRunning("docs"))
                    .as("attempt %d left the source claimed with nothing running", attempt)
                    .isFalse();
            }
            endpoint.close();
        }

        @Test
        @DisplayName("a drain with nothing running says so, and refuses later work")
        void anIdleDrainIsClean() {
            ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry,
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class),
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.source.SourceResolver.class),
                null, null, new com.eonmux.excalibur.api.reindex.ReindexCoordinator(), null);

            assertThat(endpoint.drain()).isTrue();
            assertThat(endpoint.drain()).as("a second drain has nothing left to wait for").isTrue();
        }

        /**
         * A reindex spends its time writing through channels every writer of a segment shares,
         * and an interrupt that lands on one of those writes closes the channel for the life of
         * the process. So a DELETE reaches the run only as its stop request.
         */
        @Test
        @DisplayName("a DELETE reaches the running reindex as a stop request, never an interrupt")
        void cancellingNeverInterruptsTheRun() throws Exception {
            BlockingRun run = new BlockingRun();
            ReindexEndpoint endpoint = run.endpoint();

            endpoint.handle(exchange("POST", "{\"source_id\": \"docs\"}",
                new java.io.ByteArrayOutputStream()));
            assertThat(run.started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            endpoint.handle(exchange("DELETE", "{\"source_id\": \"docs\"}",
                new java.io.ByteArrayOutputStream()));

            assertThat(run.finished.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(run.sawStop).as("the run read its stop request").isTrue();
            assertThat(run.interrupted).as("the run's thread was never interrupted").isFalse();
            assertThat(endpoint.drain()).isTrue();
        }

        @Test
        @DisplayName("a drain stops the running reindex cooperatively, waits for it, then refuses more")
        void drainStopsTheRunAndRefusesMore() throws Exception {
            BlockingRun run = new BlockingRun();
            ReindexEndpoint endpoint = run.endpoint();
            endpoint.handle(exchange("POST", "{\"source_id\": \"docs\"}",
                new java.io.ByteArrayOutputStream()));
            assertThat(run.started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThat(endpoint.drain())
                .as("the run ended within the drain, so nothing is left writing to storage")
                .isTrue();
            assertThat(run.sawStop).isTrue();
            assertThat(run.interrupted).isFalse();
            assertThat(run.coordinator.isRunning("docs")).isFalse();

            endpoint.handle(exchange("POST", "{\"source_id\": \"docs\"}",
                new java.io.ByteArrayOutputStream()));
            org.mockito.Mockito.verify(lastExchange)
                .sendResponseHeaders(org.mockito.ArgumentMatchers.eq(503),
                    org.mockito.ArgumentMatchers.anyLong());
        }

        /**
         * A claim taken a moment before a shutdown's drain closed the run executor. The request
         * has done nothing wrong and the server has no fault: the node is going down, which is
         * what a claim refused a moment later is told, and the run it would have started was
         * stopped by that, not failed.
         */
        @Test
        @DisplayName("a run the closing executor refuses is answered 503 and recorded cancelled")
        void aRunRefusedByTheClosingExecutor() throws Exception {
            sourceRegistry.register(sourceNamed("docs"));
            java.util.concurrent.atomic.AtomicReference<ReindexEndpoint> endpoint =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean drained =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<Thread> drainer =
                new java.util.concurrent.atomic.AtomicReference<>();
            ReindexEndpoint.InMemoryReindexStatusRegistry statuses =
                new ReindexEndpoint.InMemoryReindexStatusRegistry() {
                    @Override
                    public void startReindex(String sourceId) {
                        super.startReindex(sourceId);
                        // The drain closes the executor and then waits for this request's
                        // claim, so once it is waiting the executor is certainly closed.
                        Thread thread = Thread.ofPlatform().start(
                            () -> drained.set(endpoint.get().drain()));
                        drainer.set(thread);
                        long deadline = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                        while (thread.getState() != Thread.State.TIMED_WAITING
                                && thread.getState() != Thread.State.WAITING
                                && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                    }
                };
            com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
                new com.eonmux.excalibur.api.reindex.ReindexCoordinator();
            endpoint.set(new ReindexEndpoint(sourceRegistry, statuses,
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class),
                org.mockito.Mockito.mock(com.eonmux.excalibur.ingest.source.SourceResolver.class),
                null, null, coordinator, null));

            endpoint.get().handle(exchange("POST", "{\"source_id\": \"docs\"}",
                new java.io.ByteArrayOutputStream()));

            org.mockito.Mockito.verify(lastExchange)
                .sendResponseHeaders(org.mockito.ArgumentMatchers.eq(503),
                    org.mockito.ArgumentMatchers.anyLong());
            assertThat(statuses.getStatus("docs").state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_CANCELLED);
            assertThat(coordinator.isRunning("docs")).as("the claim was released").isFalse();
            drainer.get().join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10));
            assertThat(drained).as("the drain had nothing left to wait for").isTrue();
        }

        @Test
        @DisplayName("refuses a method that is neither POST nor DELETE")
        void refusesOtherMethods() throws IOException {
            ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

            endpoint.handle(exchange("PUT", "{}", body));

            org.mockito.Mockito.verify(lastExchange)
                .sendResponseHeaders(org.mockito.ArgumentMatchers.eq(405),
                    org.mockito.ArgumentMatchers.anyLong());
        }
    }

    private com.sun.net.httpserver.HttpExchange lastExchange;

    private com.sun.net.httpserver.HttpExchange exchange(
            String method, String requestBody, java.io.ByteArrayOutputStream responseBody) {
        com.sun.net.httpserver.HttpExchange exchange =
            org.mockito.Mockito.mock(com.sun.net.httpserver.HttpExchange.class);
        org.mockito.Mockito.when(exchange.getRequestMethod()).thenReturn(method);
        org.mockito.Mockito.when(exchange.getRequestURI())
            .thenReturn(java.net.URI.create("/v1/reindex"));
        org.mockito.Mockito.when(exchange.getRequestBody()).thenReturn(
            new java.io.ByteArrayInputStream(
                requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(exchange.getResponseBody()).thenReturn(responseBody);
        org.mockito.Mockito.when(exchange.getResponseHeaders())
            .thenReturn(new com.sun.net.httpserver.Headers());
        this.lastExchange = exchange;
        return exchange;
    }
    /**
     * A reindex of {@code docs} that runs until it is asked to stop, recording how it was asked.
     */
    private final class BlockingRun {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean sawStop =
            new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean interrupted =
            new java.util.concurrent.atomic.AtomicBoolean();
        final com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
            new com.eonmux.excalibur.api.reindex.ReindexCoordinator();

        ReindexEndpoint endpoint() throws Exception {
            Source source = sourceNamed("docs");
            sourceRegistry.register(source);
            com.eonmux.excalibur.ingest.source.SourceResolver resolver =
                mock(com.eonmux.excalibur.ingest.source.SourceResolver.class);
            when(resolver.resolve("docs")).thenReturn(source);
            com.eonmux.excalibur.ingest.pipeline.DocumentReindexer reindexer =
                mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class);
            when(reindexer.reindexAll(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    com.eonmux.excalibur.core.util.StopSignal stop = invocation.getArgument(2);
                    started.countDown();
                    try {
                        long deadline = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                        while (!stop.isRequested() && System.nanoTime() < deadline) {
                            java.util.concurrent.locks.LockSupport.parkNanos(
                                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
                        }
                        sawStop.set(stop.isRequested());
                        interrupted.set(Thread.currentThread().isInterrupted());
                    } finally {
                        finished.countDown();
                    }
                    throw new com.eonmux.excalibur.ingest.pipeline.ReindexCancelledException(
                        "stopped between documents");
                });
            return new ReindexEndpoint(sourceRegistry, statusRegistry, reindexer, resolver,
                null, null, coordinator, null);
        }
    }
}
