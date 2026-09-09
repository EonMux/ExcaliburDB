package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import excalibur.api.mcp.Json;
import excalibur.ingest.source.Source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DELETE /v1/ingest} - stopping a crawl without destroying what it produced.
 *
 * <p>The reason this is its own route: the other way to stop crawling a source is to erase it, and
 * an operator who only wants a runaway or stale crawl to stop should not have to reach for the
 * button that also deletes. So the property under test is as much what it leaves alone as what it
 * removes.
 */
@DisplayName("Source deregister endpoint")
class SourceDeregisterEndpointTest {

    private InMemorySourceRegistry sources;
    private IngestStatusEndpoint.IngestionStatusRegistry statuses;
    private SourceDeregisterEndpoint endpoint;

    @BeforeEach
    void setUp() {
        sources = new InMemorySourceRegistry();
        statuses = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        sources.setStatusRegistry(statuses);
        endpoint = new SourceDeregisterEndpoint(sources);
    }

    private void register(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        sources.register(source);
    }

    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private Response call(String method, String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/ingest"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(out);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    private Response delete(String body) throws IOException {
        return call("DELETE", body);
    }

    @Test
    @DisplayName("a registered source stops being crawled")
    void deregistersASource() throws IOException {
        register("project-docs");

        Response response = delete("{\"source_id\": \"project-docs\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(Json.at(response.json(), "deregistered")).isEqualTo(Boolean.TRUE);
        assertThat(sources.isRegistered("project-docs")).isFalse();
        assertThat(statuses.getStatus("project-docs"))
            .as("its status entry goes with it, or /v1/ingest/status reports it forever")
            .isNull();
    }

    @Test
    @DisplayName("the documents it already indexed are kept, and the reply says so")
    void keepsTheDocuments() throws IOException {
        // The whole reason this is not the erasure route.
        register("project-docs");

        Response response = delete("{\"source_id\": \"project-docs\"}");

        assertThat(Json.at(response.json(), "documents_kept")).isEqualTo(Boolean.TRUE);
        assertThat(Json.stringAt(response.json(), "detail")).contains("/v1/erasure");
    }

    @Test
    @DisplayName("a source this node is not crawling is a 404, not a quiet success")
    void unknownSourceIsNotFound() throws IOException {
        Response response = delete("{\"source_id\": \"never-registered\"}");

        assertThat(response.status())
            .as("a caller who misspelled a source id must not be told the crawl stopped")
            .isEqualTo(404);
        assertThat(Json.stringAt(response.json(), "error")).contains("never-registered");
    }

    @Test
    @DisplayName("a request naming no source is refused")
    void sourceIsRequired() throws IOException {
        assertThat(delete("{}").status()).isEqualTo(400);
        assertThat(delete("{\"source_id\": \"  \"}").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("anything but DELETE is refused")
    void onlyDeleteDeregisters() throws IOException {
        register("project-docs");

        assertThat(call("POST", "{\"source_id\": \"project-docs\"}").status()).isEqualTo(405);
        assertThat(sources.isRegistered("project-docs"))
            .as("a refused request must not have deregistered anything on its way to being refused")
            .isTrue();
    }
}
