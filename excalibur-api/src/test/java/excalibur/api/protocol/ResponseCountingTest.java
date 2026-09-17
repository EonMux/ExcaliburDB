package excalibur.api.protocol;

import com.sun.net.httpserver.HttpServer;
import io.excalibur.metrics.ResponseCounters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every response leaving this server is counted.
 *
 * <p>{@link ResponseBody#send} is the one place a body reaches a client, which makes it the
 * only place a count can be taken that a route added later cannot forget. Driven through a
 * real server rather than a mock, because the point of the choke point is that the paths
 * which bypass an endpoint - a HEAD with no body, a refusal written by the guard - are
 * counted too.</p>
 */
@DisplayName("Response counting")
class ResponseCountingTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        ResponseCounters.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> ResponseBody.sendJson(exchange, 200, "{}"));
        server.createContext("/refused",
            exchange -> ResponseBody.sendError(exchange, 429, "Rate limit exceeded"));
        server.createContext("/unauthenticated",
            exchange -> ResponseBody.sendError(exchange, 401, "Authentication failed"));
        server.createContext("/broken",
            exchange -> ResponseBody.sendError(exchange, 500, "Internal Server Error"));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("counts a successful response against its class")
    void countsSuccess() throws Exception {
        send("GET", "/ok");
        send("GET", "/ok");

        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SUCCESS)).isEqualTo(2);
        assertThat(ResponseCounters.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("counts a rate-limit refusal, which otherwise only reached the log")
    void countsThrottling() throws Exception {
        send("GET", "/refused");

        assertThat(ResponseCounters.rejections(ResponseCounters.Rejection.THROTTLED)).isEqualTo(1);
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.CLIENT_ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("counts an authentication failure separately from the rest of the 4xx")
    void countsAuthenticationFailure() throws Exception {
        send("GET", "/unauthenticated");

        assertThat(ResponseCounters.rejections(ResponseCounters.Rejection.UNAUTHENTICATED))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("counts a server error, which is what an error-rate alert fires on")
    void countsServerError() throws Exception {
        send("GET", "/broken");

        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SERVER_ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("counts a HEAD response, which carries no body to write")
    void countsHeadResponses() throws Exception {
        send("HEAD", "/ok");

        // The HEAD path returns before the body is written. Counting after that return would
        // make every load balancer probe invisible to the request-rate metric.
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SUCCESS)).isEqualTo(1);
    }

    private void send(String method, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }
}
