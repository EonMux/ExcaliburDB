package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That {@code /health} says whether the worker behind it is alive.
 *
 * <p>{@code IndexOrchestrator.isRunning()} appeared in neither {@code /health} nor
 * {@code /v1/stats}. If the HNSW integration worker dies the queue grows without bound while
 * coverage stays at 1.0 and health stays {@code healthy}, so the node reports itself in
 * perfect condition while quietly indexing nothing.</p>
 *
 * <p>The field is additive; {@code live}, {@code ready}, {@code healthy} and the status code
 * keep the meanings they are documented with. The verdict a probe acts on is
 * {@code /health/ready}'s, which does treat a dead worker as a reason to leave the
 * rotation.</p>
 */
@DisplayName("Health: the index worker")
class HealthIndexWorkerTest {

    private IndexOrchestrator indexOrchestrator;
    private SegmentCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(new VisibilityTracker(catalog));
        when(indexOrchestrator.getCatalog()).thenReturn(catalog);
        when(indexOrchestrator.isRunning()).thenReturn(true);
    }

    @Test
    @DisplayName("reports a live worker")
    void reportsALiveWorker() throws IOException {
        assertThat(body(new HealthEndpoint(indexOrchestrator)))
            .contains("\"indexerRunning\": true");
    }

    @Test
    @DisplayName("reports a dead worker, which nothing else on this node would")
    void reportsADeadWorker() throws IOException {
        when(indexOrchestrator.isRunning()).thenReturn(false);

        String body = body(new HealthEndpoint(indexOrchestrator));
        assertThat(body).contains("\"indexerRunning\": false");
        // Unchanged: these are what /health is documented to mean, and a client that parses
        // them must keep reading the same thing.
        assertThat(body).contains("\"live\": true");
        assertThat(body).contains("\"ready\": ");
        assertThat(body).contains("\"healthy\": ");
    }

    @Test
    @DisplayName("omits the field when the endpoint was built without an orchestrator")
    void omitsTheFieldWithoutAnOrchestrator() throws IOException {
        HealthEndpoint endpoint = new HealthEndpoint(
            new VisibilityTracker(catalog), catalog, 0.5);

        assertThat(body(endpoint)).doesNotContain("indexerRunning");
    }

    private static String body(HealthEndpoint endpoint) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/health"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);
        endpoint.handle(exchange);
        return out.toString(StandardCharsets.UTF_8);
    }
}
