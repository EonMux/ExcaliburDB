package com.eonmux.excalibur.ingest.source;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an HTTP fetch is allowed to do to the thread that calls it, and what it must preserve.
 *
 * <p>Three separate ways a crawl went wrong quietly: a body that never finished arriving pinned
 * the ingest thread with no deadline; a page's declared encoding was parsed off the header and
 * discarded, so every consumer decoded it as UTF-8; and the configured content-type filter was
 * stored and never consulted.</p>
 */
@DisplayName("HTTP fetch policy")
class HttpSourceFetchPolicyTest {

    private HttpServer server;
    private String baseUrl;
    private final CountDownLatch releaseStalledBody = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        // Announces a length it never delivers, then waits. A real server that dies mid-response
        // behaves the same way from the client's side.
        server.createContext("/stalls.html", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 10_000);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("partial".getBytes(StandardCharsets.UTF_8));
                out.flush();
                releaseStalledBody.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        server.createContext("/latin1.html", exchange ->
            respond(exchange, "text/html; charset=ISO-8859-1", "Café Ada", StandardCharsets.ISO_8859_1));

        server.createContext("/utf8.html", exchange ->
            respond(exchange, "text/html; charset=utf-8", "Café Ada", StandardCharsets.UTF_8));

        server.createContext("/undeclared.html", exchange ->
            respond(exchange, "text/html", "Café Ada", StandardCharsets.UTF_8));

        server.createContext("/binary.bin", exchange ->
            respond(exchange, "application/octet-stream", "not text", StandardCharsets.UTF_8));

        // Links whose paths and queries carry percent escapes that must survive normalisation.
        server.createContext("/", exchange -> respond(exchange, "text/html",
            "<a href=\"/docs/my%20file.html\">spaced</a>"
                + "<a href=\"/docs/a%2Fb.html\">encoded slash</a>"
                + "<a href=\"/search?q=a%26b%3Dc\">encoded ampersand</a>",
            StandardCharsets.UTF_8));

        // Echoes back the raw path it was asked for, so a test can see exactly what the crawler
        // requested rather than what it recorded.
        server.createContext("/docs/", exchange ->
            respond(exchange, "text/plain", exchange.getRequestURI().getRawPath(),
                StandardCharsets.UTF_8));

        server.start();
    }

    @AfterEach
    void tearDown() {
        releaseStalledBody.countDown();
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                String contentType, String body, Charset charset) throws IOException {
        byte[] bytes = body.getBytes(charset);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpSource source(java.util.function.UnaryOperator<HttpSource.Builder> customise) {
        return customise.apply(HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("fetch-policy")
            .baseUrl(baseUrl)
            ).build();
    }

    @Test
    @DisplayName("a body that stops arriving fails within the configured timeout")
    void stalledBodyIsBounded() {
        try (HttpSource source = source(b -> b.timeoutSeconds(1))) {
            long start = System.nanoTime();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/stalls.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("Timed out");

            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the read is bounded by the configured timeout, not by the server closing")
                .isLessThan(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("a declared charset is honoured")
    void declaredCharsetIsHonoured() throws Exception {
        try (HttpSource source = source(b -> b)) {
            FetchedDocument latin1 = source.fetch(baseUrl + "/latin1.html");

            assertThat(latin1.getCharset()).isEqualTo(StandardCharsets.ISO_8859_1);
            assertThat(latin1.getText())
                .as("decoding these bytes as UTF-8 substitutes U+FFFD silently")
                .isEqualTo("Café Ada")
                .doesNotContain("�");
        }
    }

    @Test
    @DisplayName("a UTF-8 declaration and no declaration both decode as UTF-8")
    void utf8IsTheDefault() throws Exception {
        try (HttpSource source = source(b -> b)) {
            assertThat(source.fetch(baseUrl + "/utf8.html").getText()).isEqualTo("Café Ada");
            assertThat(source.fetch(baseUrl + "/undeclared.html").getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the configured content-type filter is enforced")
    void contentTypeFilterIsEnforced() {
        try (HttpSource source = source(b -> b.includedContentTypes("text/html"))) {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/binary.bin"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("Content type not accepted");
        }
    }

    @Test
    @DisplayName("a type inside the filter is still fetched")
    void includedTypeIsFetched() throws Exception {
        try (HttpSource source = source(b -> b.includedContentTypes("text/html"))) {
            assertThat(source.fetch(baseUrl + "/utf8.html").getContentType()).isEqualTo("text/html");
        }
    }

    @Test
    @DisplayName("percent escapes survive URL normalisation")
    void percentEscapesSurviveNormalisation() throws Exception {
        try (HttpSource source = source(b -> b.maxDepth(1))) {
            java.util.List<String> found = source.crawl().toList();

            // Normalising through the decoded path turned "%20" into a space, which URI.create
            // then rejects - so the page left the crawl with only a debug line. Decoded "%2F"
            // and "%26" are worse: they change which resource the URL names.
            assertThat(found)
                .contains(baseUrl + "/docs/my%20file.html")
                .contains(baseUrl + "/docs/a%2Fb.html")
                .contains(baseUrl + "/search?q=a%26b%3Dc");
        }
    }

    @Test
    @DisplayName("a URL with an escaped space reaches the server escaped")
    void escapedSpaceCanBeFetched() throws Exception {
        try (HttpSource source = source(b -> b.maxDepth(1))) {
            assertThat(source.fetch(baseUrl + "/docs/my%20file.html").getText())
                .as("the request carries the escape, not a raw space")
                .isEqualTo("/docs/my%20file.html");
        }
    }
}