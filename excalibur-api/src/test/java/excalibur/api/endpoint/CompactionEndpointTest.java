package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.shard.CompactionReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the on-demand compaction route and the runner behind it.
 */
class CompactionEndpointTest {

    private static final CompactionReport A_RUN =
        new CompactionReport(7, 3, 1, 4, 2, 0, Duration.ofMillis(250));

    private record Response(int status, String body) { }

    private static Response call(CompactionEndpoint endpoint, String method, String body)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/compact"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), responseBody.toString(StandardCharsets.UTF_8));
    }

    private static Response status(CompactionStatusEndpoint endpoint) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/compact/status"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(responseBody);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> code = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(code.capture(), anyLong());
        return new Response(code.getValue(), responseBody.toString(StandardCharsets.UTF_8));
    }

    /** Waits for the runner to leave the running state, so assertions do not race the run. */
    private static void awaitIdle(CompactionRunner runner) throws InterruptedException {
        for (int i = 0; i < 400 && runner.snapshot().isRunning(); i++) {
            Thread.sleep(5);
        }
        assertThat(runner.snapshot().isRunning()).isFalse();
    }

    @Nested
    @DisplayName("Starting a run")
    class Starting {

        @Test
        @DisplayName("a request is accepted and the work happens behind it")
        void accepted() throws Exception {
            AtomicBoolean ran = new AtomicBoolean();
            try (CompactionRunner runner = new CompactionRunner(force -> {
                ran.set(true);
                return A_RUN;
            })) {
                Response response = call(new CompactionEndpoint(runner), "POST", "{}");

                assertThat(response.status()).isEqualTo(202);
                assertThat(response.body()).contains("\"state\": \"running\"");
                awaitIdle(runner);
                assertThat(ran).isTrue();
            }
        }

        @Test
        @DisplayName("force reaches the compactor")
        void forceIsCarried() throws Exception {
            AtomicBoolean forced = new AtomicBoolean();
            try (CompactionRunner runner = new CompactionRunner(force -> {
                forced.set(force);
                return A_RUN;
            })) {
                Response response = call(new CompactionEndpoint(runner), "POST",
                    "{\"force\": true}");

                assertThat(response.status()).isEqualTo(202);
                awaitIdle(runner);
                assertThat(forced).isTrue();
            }
        }

        /**
         * A second run while the first is rewriting the same segments would do no damage - the
         * segment lock serialises them - but it would report a run that mostly skipped, which
         * reads as "there was nothing to reclaim" when in fact another run was reclaiming it.
         */
        @Test
        @DisplayName("a second request while one is running is refused")
        void oneAtATime() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            try (CompactionRunner runner = new CompactionRunner(force -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return A_RUN;
            })) {
                CompactionEndpoint endpoint = new CompactionEndpoint(runner);
                assertThat(call(endpoint, "POST", "{}").status()).isEqualTo(202);

                Response second = call(endpoint, "POST", "{}");
                assertThat(second.status()).isEqualTo(409);
                assertThat(second.body()).contains("already");

                release.countDown();
                awaitIdle(runner);
            }
        }

        @Test
        @DisplayName("a non-boolean force is refused rather than read as false")
        void forceMustBeABoolean() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                Response response = call(new CompactionEndpoint(runner), "POST",
                    "{\"force\": \"yes\"}");

                assertThat(response.status()).isEqualTo(400);
                assertThat(response.body()).contains("force");
                assertThat(runner.snapshot().state()).isEqualTo("idle");
            }
        }

        /**
         * Every other route refuses an oversized body with 413. Reading a prefix instead would
         * silently drop whatever the caller wrote past it, so a truncated {@code force} reads
         * as absent and the run goes ahead unforced.
         */
        @Test
        @DisplayName("an oversized body is refused rather than truncated")
        void oversizedBodyIsRefused() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                String padding = "x".repeat(2 * 1024 * 1024);
                Response response = call(new CompactionEndpoint(runner), "POST",
                    "{\"pad\": \"" + padding + "\", \"force\": true}");

                assertThat(response.status()).isEqualTo(413);
                assertThat(runner.snapshot().state()).isEqualTo("idle");
            }
        }

        /**
         * A client keying on {@code running} to decide whether to poll must not be told
         * {@code completed} just because the corpus was small enough to finish first.
         */
        @Test
        @DisplayName("the accepted response describes the run it started, however fast it ends")
        void acceptedResponseAlwaysSaysRunning() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                CompactionEndpoint endpoint = new CompactionEndpoint(runner);
                for (int i = 0; i < 20; i++) {
                    awaitIdle(runner);
                    Response response = call(endpoint, "POST", "{}");
                    assertThat(response.status()).isEqualTo(202);
                    assertThat(response.body()).contains("\"state\": \"running\"");
                }
                awaitIdle(runner);
            }
        }

        @Test
        @DisplayName("a status that reads completed is a status the next request is accepted on")
        void aCompletedStatusMeansTheNextRunIsAccepted() throws Exception {
            // Two pieces of state for one fact let them disagree: a caller polling the status
            // until it read "completed" and then posting again was answered 409 "already in
            // progress", which is a loop that fails intermittently and only under load.
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                CompactionEndpoint endpoint = new CompactionEndpoint(runner);
                for (int i = 0; i < 200; i++) {
                    assertThat(call(endpoint, "POST", "{}").status()).isEqualTo(202);
                    while (runner.snapshot().isRunning()) {
                        Thread.onSpinWait();
                    }
                    assertThat(runner.start(false))
                        .as("attempt %d: the status said the run was over", i)
                        .isNotNull();
                    while (runner.snapshot().isRunning()) {
                        Thread.onSpinWait();
                    }
                }
            }
        }

        @Test
        @DisplayName("any method but POST is refused")
        void postOnly() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                assertThat(call(new CompactionEndpoint(runner), "GET", "").status())
                    .isEqualTo(405);
            }
        }
    }

    @Nested
    @DisplayName("Reading the result back")
    class Readback {

        @Test
        @DisplayName("nothing has run yet")
        void idle() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                Response response = status(new CompactionStatusEndpoint(runner));

                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body()).contains("\"state\": \"idle\"");
            }
        }

        @Test
        @DisplayName("a finished run reports everything it examined, reclaimed and failed on")
        void completed() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> A_RUN)) {
                call(new CompactionEndpoint(runner), "POST", "{}");
                awaitIdle(runner);

                Response response = status(new CompactionStatusEndpoint(runner));

                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body())
                    .contains("\"state\": \"completed\"")
                    .contains("\"segments_considered\": 7")
                    .contains("\"segments_compacted\": 3")
                    .contains("\"segments_failed\": 1")
                    .contains("\"segments_skipped\": 3")
                    .contains("\"graphs_considered\": 4")
                    .contains("\"graphs_compacted\": 2")
                    .contains("\"graphs_skipped\": 2")
                    .contains("\"graphs_failed\": 0")
                    .contains("\"duration_ms\": 250");
            }
        }

        /**
         * A run that threw has to say so. Reporting the previous run's counts, or an empty
         * report, would tell an operator the space was reclaimed when it was not.
         */
        @Test
        @DisplayName("a run that threw is reported as failed, with its reason")
        void failed() throws Exception {
            try (CompactionRunner runner = new CompactionRunner(force -> {
                throw new IllegalStateException("segment 3 is closed");
            })) {
                call(new CompactionEndpoint(runner), "POST", "{}");
                awaitIdle(runner);

                Response response = status(new CompactionStatusEndpoint(runner));

                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body())
                    .contains("\"state\": \"failed\"")
                    .contains("segment 3 is closed");
            }
        }
    }
}
