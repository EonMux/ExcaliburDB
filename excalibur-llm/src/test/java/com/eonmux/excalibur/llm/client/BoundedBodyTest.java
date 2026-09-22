package com.eonmux.excalibur.llm.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a response body that the request timeout does not bound.
 *
 * <p>A peer that keeps sending, slowly, never trips a request timeout, and a body handler that
 * accumulates without a limit ends the process with an OutOfMemoryError rather than reporting a
 * bad response. The endpoint is operator-configured, but a proxy in front of it - or an inference
 * server behaving badly - is exactly the case a timeout does not cover.</p>
 */
@DisplayName("Bounded response bodies")
class BoundedBodyTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/small", exchange -> respond(exchange, "hello", true));
        server.createContext("/declared-large", exchange -> {
            byte[] body = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // Chunked: the length is not declared, so the cap has to bite as bytes arrive.
        server.createContext("/chunked-large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) {
                    out.write("y".repeat(1024).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException e) {
                // The client cancelled: expected once the cap is reached.
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body,
                                boolean declareLength) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, declareLength ? bytes.length : 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("a body within the limit is returned intact")
    void bodyWithinTheLimitIsReturned() throws Exception {
        assertThat(get("/small", 1024).body()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a declared length above the limit is refused before reading")
    void declaredLengthAboveTheLimitIsRefused() {
        assertThatThrownBy(() -> get("/declared-large", 1024))
            .hasRootCauseInstanceOf(BoundedBody.BodyTooLargeException.class);
    }

    @Test
    @DisplayName("a chunked body is cut off at the limit")
    void chunkedBodyIsCutOff() {
        assertThatThrownBy(() -> get("/chunked-large", 2048))
            .hasRootCauseInstanceOf(BoundedBody.BodyTooLargeException.class);
    }

    @Test
    @DisplayName("the limit must be positive")
    void limitMustBePositive() {
        assertThatThrownBy(() -> BoundedBody.ofString(0, StandardCharsets.UTF_8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpResponse<String> get(String path, long maxBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, BoundedBody.ofString(maxBytes, StandardCharsets.UTF_8));
        }
    }
}
