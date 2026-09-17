package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
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
 * That an operator can tell a load balancer to stop sending this node traffic.
 *
 * <p>The node keeps serving everything it already holds while drained - the point is to empty
 * the in-flight requests before a restart or a manual compaction, not to refuse work. Only
 * what readiness reports changes.</p>
 */
@DisplayName("Drain")
class DrainEndpointTest {

    private ServiceReadiness readiness;
    private DrainEndpoint endpoint;

    @BeforeEach
    void setUp() {
        readiness = new ServiceReadiness();
        endpoint = new DrainEndpoint(readiness);
    }

    @Test
    @DisplayName("a POST takes the node out of rotation")
    void postDrains() throws IOException {
        Answer answer = send("POST", "/v1/drain");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"draining\": true");
        assertThat(readiness.isDraining()).isTrue();
    }

    @Test
    @DisplayName("a POST records the reason an operator gave for the query string")
    void postRecordsTheReason() throws IOException {
        send("POST", "/v1/drain?reason=rolling%20restart");

        assertThat(readiness.reason()).isEqualTo("rolling restart");
    }

    @Test
    @DisplayName("a DELETE puts the node back into rotation")
    void deleteUndrains() throws IOException {
        readiness.beginDrain("maintenance");

        Answer answer = send("DELETE", "/v1/drain");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"draining\": false");
        assertThat(readiness.isDraining()).isFalse();
    }

    @Test
    @DisplayName("is idempotent, because a retried drain must not be an error")
    void isIdempotent() throws IOException {
        assertThat(send("POST", "/v1/drain").status()).isEqualTo(200);
        assertThat(send("POST", "/v1/drain").status()).isEqualTo(200);
        assertThat(send("DELETE", "/v1/drain").status()).isEqualTo(200);
        assertThat(send("DELETE", "/v1/drain").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("refuses a method that is neither")
    void refusesOtherMethods() throws IOException {
        assertThat(send("GET", "/v1/drain").status()).isEqualTo(405);
    }

    @Test
    @DisplayName("refuses a reason longer than a log line can usefully carry")
    void refusesAnOversizedReason() throws IOException {
        Answer answer = send("POST", "/v1/drain?reason=" + "x".repeat(400));

        assertThat(answer.status()).isEqualTo(400);
        assertThat(readiness.isDraining())
            .as("a refused request must not half-apply")
            .isFalse();
    }

    private record Answer(int status, String body) {
    }

    private Answer send(String method, String path) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
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
