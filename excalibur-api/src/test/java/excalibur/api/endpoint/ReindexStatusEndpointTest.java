package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading back what a reindex did.
 *
 * <p>{@code POST /v1/reindex} answers 202 and runs in the background. Without this route the
 * verdict it records - completed, failed, and why - reaches the node's own log and nothing
 * else, so a caller can start a reindex and has no way to learn whether it worked. Ingestion
 * has had {@code /v1/ingest/status} for exactly this reason; the two do the same kind of work
 * and are reported the same way.</p>
 */
@DisplayName("GET /v1/reindex/status")
class ReindexStatusEndpointTest {

    private ReindexEndpoint.InMemoryReindexStatusRegistry registry;
    private ReindexStatusEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        endpoint = new ReindexStatusEndpoint(registry);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null registry")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> new ReindexStatusEndpoint(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Reading one source")
    class OneSource {

        @Test
        @DisplayName("reports a run that is still going")
        void reportsARunInProgress() throws IOException {
            registry.startReindex("docs");
            registry.updateProgress("docs", "embedding", 0.3, 3, 10);
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=docs", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"source_id\": \"docs\"");
            assertThat(json).contains("\"state\": \"embedding\"");
            assertThat(json).contains("\"progress\": 0.3000");
            assertThat(json).contains("\"documents_processed\": 3");
            assertThat(json).contains("\"documents_total\": 10");
            assertThat(json).contains("\"last_error\": null");
        }

        @Test
        @DisplayName("reports why a run failed")
        void reportsWhyARunFailed() throws IOException {
            registry.startReindex("docs");
            registry.failReindex("docs", "No volume available for reindex");
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=docs", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("the verdict is the whole reason this route exists")
                .contains("\"state\": \"failed\"");
            assertThat(json).contains("No volume available for reindex");
        }

        @Test
        @DisplayName("a completed run reads as idle at full progress with no error")
        void aCompletedRunReadsAsIdle() throws IOException {
            registry.startReindex("docs");
            registry.completeReindex("docs");
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=docs", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"state\": \"idle\"");
            assertThat(json).contains("\"progress\": 1.0000");
            assertThat(json).contains("\"last_error\": null");
        }

        @Test
        @DisplayName("a source that has never been reindexed is 404, not an invented idle run")
        void anUnknownSourceIs404() throws IOException {
            HttpExchange exchange = get("?source_id=never-seen", new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }

        @Test
        @DisplayName("a malformed source id is refused before it is looked up")
        void aMalformedSourceIdIsRefused() throws IOException {
            HttpExchange exchange = get("?source_id=" + "x".repeat(300), new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    @Nested
    @DisplayName("Reading every source")
    class AllSources {

        @Test
        @DisplayName("lists every source that has been reindexed")
        void listsEverySource() throws IOException {
            registry.startReindex("docs");
            registry.startReindex("wiki");
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"docs\"").contains("\"wiki\"");
            assertThat(json)
                .as("the same envelope /v1/ingest/status uses, so a client need not learn two")
                .startsWith("{\"sources\": [");
        }

        @Test
        @DisplayName("a node that has never reindexed anything answers an empty set, not an error")
        void anEmptyRegistryIsNotAnError() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).isEqualTo("{\"sources\": []}");
        }
    }

    @Nested
    @DisplayName("Method handling")
    class Methods {

        @Test
        @DisplayName("refuses anything but GET")
        void refusesAnythingButGet() throws IOException {
            HttpExchange exchange = mock(HttpExchange.class);
            when(exchange.getRequestMethod()).thenReturn("POST");
            when(exchange.getRequestURI()).thenReturn(URI.create("/v1/reindex/status"));
            when(exchange.getResponseHeaders()).thenReturn(new Headers());
            when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    @Nested
    @DisplayName("Telling a cancellation from a failure")
    class Cancellation {

        @Test
        @DisplayName("a cancelled run is not reported as a crash")
        void aCancelledRunSaysSo() throws IOException {
            registry.startReindex("docs");
            registry.cancelReindex("docs");
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=docs", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("DELETE /v1/reindex points the caller here to learn that the run has "
                    + "stopped; a deliberate stop reported as a failure is an incident report")
                .contains("\"cancelled\": true");
            assertThat(json).contains("\"state\": \"cancelled\"");
        }

        @Test
        @DisplayName("a failed run is not reported as a cancellation")
        void aFailedRunIsNotCancelled() throws IOException {
            registry.startReindex("docs");
            registry.failReindex("docs", "Reindex failed completely with 4 errors");
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=docs", body));

            assertThat(body.toString(StandardCharsets.UTF_8)).contains("\"cancelled\": false");
        }

        @Test
        @DisplayName("a run that has ended says when, and one still going does not")
        void endedRunsCarryAFinishTime() throws IOException {
            registry.startReindex("running");
            registry.updateProgress("running", "indexing", 0.5, 5, 10);
            registry.startReindex("done");
            registry.completeReindex("done");
            ByteArrayOutputStream running = new ByteArrayOutputStream();
            ByteArrayOutputStream done = new ByteArrayOutputStream();

            endpoint.handle(get("?source_id=running", running));
            endpoint.handle(get("?source_id=done", done));

            assertThat(running.toString(StandardCharsets.UTF_8))
                .as("a run with an end time that has not ended cannot be told from one that has")
                .contains("\"finished_at_ms\": 0");
            assertThat(done.toString(StandardCharsets.UTF_8))
                .doesNotContain("\"finished_at_ms\": 0");
        }
    }

    private HttpExchange get(String query, ByteArrayOutputStream body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/reindex/status" + query));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }
}
