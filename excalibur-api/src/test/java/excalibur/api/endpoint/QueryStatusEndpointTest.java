package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That an operator can see what this node is running.
 *
 * <p>{@code DELETE /v1/query/{id}} could cancel a query since the registry was written, and
 * there was no way to learn an id you had not issued yourself - so the one operation for a
 * query that will not finish was reachable only by the caller who was already waiting on it.
 * Ingestion and reindex both had status routes; queries did not, while the registry had
 * tracked every figure this route serves and had no callers at all.</p>
 */
@DisplayName("Query status")
class QueryStatusEndpointTest {

    private QueryRegistry registry;
    private QueryStatusEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry();
        endpoint = new QueryStatusEndpoint(registry);
    }

    @Test
    @DisplayName("reports an idle node as running nothing")
    void reportsAnIdleNode() throws IOException {
        Answer answer = send("GET");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"active\": 0");
        assertThat(answer.body()).contains("\"registered\": 0");
        assertThat(answer.body()).contains("\"cancelled\": 0");
        assertThat(answer.body()).contains("\"queries\": []");
    }

    @Test
    @DisplayName("names each query in flight, with an id that can be cancelled")
    void namesTheQueriesInFlight() throws IOException {
        registry.register("q-1", new CancellationToken(), "corr-1");
        registry.register("q-2", new CancellationToken(), "corr-2");
        registry.cancel("q-2");

        String body = send("GET").body();
        assertThat(body).contains("\"active\": 2");
        assertThat(body).contains("\"registered\": 2");
        assertThat(body).contains("\"cancelled\": 1");
        assertThat(body).contains("\"query_id\": \"q-1\"");
        assertThat(body).contains("\"correlation_id\": \"corr-2\"");
        assertThat(body).contains("\"cancelled\": true");
        assertThat(body).contains("\"duration_ms\": ");
    }

    @Test
    @DisplayName("puts the longest-running query first, which is the one being looked for")
    void ordersByAge() throws IOException, InterruptedException {
        registry.register("younger", new CancellationToken());
        Thread.sleep(15);
        // Registered later, so it has been running for less time; it must sort second.
        registry.register("youngest", new CancellationToken());

        String body = send("GET").body();
        assertThat(body.indexOf("\"younger\"")).isLessThan(body.indexOf("\"youngest\""));
    }

    @Test
    @DisplayName("refuses a method this route does not serve")
    void refusesOtherMethods() throws IOException {
        assertThat(send("DELETE").status()).isEqualTo(405);
    }

    private record Answer(int status, String body) {
    }

    private Answer send(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query/status"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);

        endpoint.handle(exchange);

        var status = forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(),
            org.mockito.ArgumentMatchers.anyLong());
        return new Answer(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }
}
