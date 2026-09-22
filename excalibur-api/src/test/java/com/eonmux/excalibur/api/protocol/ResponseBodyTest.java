package com.eonmux.excalibur.api.protocol;

import com.sun.net.httpserver.HttpServer;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response writing against a real {@code com.sun.net.httpserver}.
 *
 * <p>The HEAD behaviour cannot be tested against a mocked exchange: it is the JDK's server
 * that closes the response stream for a HEAD and rejects the write, and that rejection is
 * the failure - a 200 goes out, the handler then throws {@code stream closed}, and the
 * wrapper logs it as an error. A load balancer probing with HEAD produces one of those per
 * probe from a server that is perfectly healthy.</p>
 */
@DisplayName("Response body")
class ResponseBodyTest {

    private static final String BODY = "{\"live\": true}";

    private HttpServer server;
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            try {
                ResponseBody.sendJson(exchange, 200, BODY);
            } catch (Throwable t) {
                handlerFailure.set(t);
                throw t;
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("a HEAD probe is answered without the handler failing")
    void headIsAnsweredCleanly() throws Exception {
        HttpResponse<Void> response = send("HEAD", HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(handlerFailure.get())
            .as("writing a body to a HEAD response throws and is logged as a server error")
            .isNull();
    }

    @Test
    @DisplayName("a HEAD response reports the length its GET would return")
    void headReportsContentLength() throws Exception {
        HttpResponse<Void> response = send("HEAD", HttpResponse.BodyHandlers.discarding());

        assertThat(response.headers().firstValue("Content-Length"))
            .contains(String.valueOf(BODY.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    @DisplayName("GET still returns the body")
    void getStillReturnsTheBody() throws Exception {
        HttpResponse<String> response = send("GET", HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(BODY);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(handlerFailure.get()).isNull();
    }

    private <T> HttpResponse<T> send(String method, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(10))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, handler);
        }
    }
}
