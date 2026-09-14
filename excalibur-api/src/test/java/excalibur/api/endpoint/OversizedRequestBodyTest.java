package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.api.protocol.RequestBody;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.llm.embedding.EmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What every endpoint answers when the body is larger than {@link RequestBody#MAX_REQUEST_SIZE}.
 *
 * <p>One condition, one status. 400 tells a caller its request was malformed and not to send it
 * again; 413 tells it the request was well-formed and too big, which is the difference between
 * giving up and splitting the payload. Answering 413 on {@code /v1/sources} and 400 on
 * {@code /v1/query} for the same one megabyte leaves a client no status it can branch on.
 */
@DisplayName("An oversized request body")
class OversizedRequestBodyTest {

    private static final String TOO_LARGE = "{\"query\": \"" + "a".repeat(RequestBody.MAX_REQUEST_SIZE) + "\"}";

    @Test
    @DisplayName("is refused with 413 by the query endpoint")
    void queryEndpoint() throws IOException {
        QueryEndpoint endpoint = new QueryEndpoint(
            mock(QueryPipeline.class), null, null, null, mock(EmbeddingClient.class));

        endpoint.handle(post("/v1/query"));

        verify(exchange).sendResponseHeaders(eq(413), anyLong());
    }

    @Test
    @DisplayName("is refused with 413 by the ingest endpoint")
    void ingestEndpoint() throws IOException {
        IngestEndpoint endpoint = new IngestEndpoint(new InMemorySourceRegistry());

        endpoint.handle(post("/v1/sources"));

        verify(exchange).sendResponseHeaders(eq(413), anyLong());
    }

    @Test
    @DisplayName("is refused with 413 by the reindex endpoint")
    void reindexEndpoint() throws IOException {
        ReindexEndpoint endpoint = new ReindexEndpoint(
            new InMemorySourceRegistry(),
            new ReindexEndpoint.InMemoryReindexStatusRegistry());

        endpoint.handle(post("/v1/reindex"));

        verify(exchange).sendResponseHeaders(eq(413), anyLong());
    }

    @Test
    @DisplayName("is refused with 413 by the source deregister endpoint")
    void sourceDeregisterEndpoint() throws IOException {
        SourceDeregisterEndpoint endpoint =
            new SourceDeregisterEndpoint(new InMemorySourceRegistry());

        endpoint.handle(request("DELETE", "/v1/sources"));

        verify(exchange).sendResponseHeaders(eq(413), anyLong());
    }

    private HttpExchange exchange;

    private HttpExchange post(String path) throws IOException {
        return request("POST", path);
    }

    private HttpExchange request(String method, String path) throws IOException {
        exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(TOO_LARGE.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }
}
