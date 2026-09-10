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
    @DisplayName("a source id longer than one could ever be registered under is refused")
    void oversizedSourceIdIsRefused() throws IOException {
        String sourceId = "x".repeat(Source.MAX_ID_LENGTH + 1);

        Response response = delete("{\"source_id\": \"" + sourceId + "\"}");

        assertThat(response.status())
            .as("registration bounds the id, so nothing this long is being crawled; 400 names the "
                + "bound where a 404 would only say it was not found")
            .isEqualTo(400);
        assertThat(response.body())
            .contains(String.valueOf(Source.MAX_ID_LENGTH))
            .doesNotContain(sourceId);
    }

    @Test
    @DisplayName("a deregistration that could not be recorded is not reported as done")
    void anUnrecordableDeregistrationIsRefused() throws IOException {
        // The source is still registered and still on the crawl schedule. A 200 here would tell
        // the caller a crawl had stopped that starts itself again at the next boot.
        SourceRegistry refusing = mock(SourceRegistry.class);
        when(refusing.remove("project-docs"))
            .thenThrow(new SourceCatalogException("catalog is unwritable", new IOException()));
        endpoint = new SourceDeregisterEndpoint(refusing);

        Response response = delete("{\"source_id\": \"project-docs\"}");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body())
            .as("the reply names no path on this node")
            .doesNotContain("catalog is unwritable");
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
