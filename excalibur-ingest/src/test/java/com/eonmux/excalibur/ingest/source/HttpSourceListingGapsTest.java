package com.eonmux.excalibur.ingest.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page whose links cannot be read makes the crawl's listing incomplete, and says so, rather
 * than passing for a page that links to nothing.
 *
 * <p>The site is a base page linking to a hub, which links to two leaves. Only the hub's answer
 * varies: when it could not be read, the leaves are missing from the listing without having
 * gone anywhere, and the source has to report the gap for change detection to keep them.</p>
 */
class HttpSourceListingGapsTest {

    private static final String HUB_LINKS =
        "<html><body><a href=\"/leaf-a.html\">a</a><a href=\"/leaf-b.html\">b</a></body></html>";

    private HttpServer server;
    private String baseUrl;
    private HttpSource source;

    /** What the hub answers, as a handler; replaced per test. */
    private final Map<String, Responder> pages = new ConcurrentHashMap<>();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            Responder page = pages.get(exchange.getRequestURI().getPath());
            try (exchange) {
                if (page == null) {
                    send(exchange, 404, "text/plain", "not here");
                } else {
                    page.respond(exchange);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        pages.put("/", exchange -> send(exchange, 200, "text/html",
            "<html><body><a href=\"/hub.html\">hub</a></body></html>"));
        pages.put("/leaf-a.html", exchange -> send(exchange, 200, "text/html", "leaf a"));
        pages.put("/leaf-b.html", exchange -> send(exchange, 200, "text/html", "leaf b"));

        source = HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("gaps")
            .baseUrl(baseUrl + "/")
            .maxDepth(3)
            .crawlDelayMillis(0)
            .timeout(Duration.ofMillis(500))
            .build();
    }

    @AfterEach
    void tearDown() {
        source.close();
        server.stop(0);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private List<String> crawl() throws SourceException {
        try (Stream<String> listing = source.crawl()) {
            return listing.toList();
        }
    }

    @Test
    void aHubThatReadsListsEverythingWithNoGaps() throws SourceException {
        pages.put("/hub.html", exchange -> send(exchange, 200, "text/html", HUB_LINKS));

        assertThat(crawl()).containsExactlyInAnyOrder(baseUrl + "/", baseUrl + "/hub.html",
            baseUrl + "/leaf-a.html", baseUrl + "/leaf-b.html");
        assertThat(source.getListingGaps().isComplete()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 408, 429, 401, 403, 407})
    void aHubThatDidNotAnswerForItselfIsAGap(int status) throws SourceException {
        pages.put("/hub.html", exchange -> send(exchange, status, "text/html", "no"));

        List<String> listed = crawl();

        assertThat(listed).doesNotContain(baseUrl + "/leaf-a.html", baseUrl + "/leaf-b.html");
        ListingGaps gaps = source.getListingGaps();
        assertThat(gaps.isComplete()).isFalse();
        assertThat(gaps.count()).isEqualTo(1);
        assertThat(gaps.sample()).containsExactly(new ListingGaps.Gap(baseUrl + "/hub.html",
            "links not read: HTTP " + status));
        assertThat(gaps.describe()).contains(baseUrl + "/hub.html", "HTTP " + status);
    }

    @Test
    void aHubThatTimesOutIsAGap() throws SourceException {
        pages.put("/hub.html", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "text/html", HUB_LINKS);
        });

        crawl();

        ListingGaps gaps = source.getListingGaps();
        assertThat(gaps.count()).isEqualTo(1);
        assertThat(gaps.sample().getFirst().location()).isEqualTo(baseUrl + "/hub.html");
        assertThat(gaps.sample().getFirst().reason()).startsWith("links not read: no answer");
    }

    @Test
    void aHubLargerThanTheSourceReadsIsAGap() throws SourceException {
        source.close();
        source = HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("gaps")
            .baseUrl(baseUrl + "/")
            .maxDepth(3)
            .crawlDelayMillis(0)
            .maxResponseSize(128)
            .build();
        pages.put("/hub.html", exchange ->
            send(exchange, 200, "text/html", HUB_LINKS + " ".repeat(256)));

        crawl();

        assertThat(source.getListingGaps().sample()).containsExactly(new ListingGaps.Gap(
            baseUrl + "/hub.html", "links not read: larger than 128 bytes"));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410, 400})
    void aHubThatAnsweredLinksToNothingAndIsNoGap(int status) throws SourceException {
        pages.put("/hub.html", exchange -> send(exchange, status, "text/html", "gone"));

        List<String> listed = crawl();

        assertThat(listed).doesNotContain(baseUrl + "/leaf-a.html");
        assertThat(source.getListingGaps()).isEqualTo(ListingGaps.NONE);
    }

    @Test
    void aHubThatIsNotHtmlLinksToNothingAndIsNoGap() throws SourceException {
        pages.put("/hub.html", exchange -> send(exchange, 200, "text/plain", HUB_LINKS));

        crawl();

        assertThat(source.getListingGaps().isComplete()).isTrue();
    }

    @Test
    void aBasePageThatFailsIsAGapThoughItIsStillListed() throws SourceException {
        pages.put("/", exchange -> send(exchange, 503, "text/html", "down"));

        assertThat(crawl()).containsExactly(baseUrl + "/");
        assertThat(source.getListingGaps().sample()).extracting(ListingGaps.Gap::location)
            .containsExactly(baseUrl + "/");
    }

    @Test
    void eachCrawlReportsOnlyItsOwnGaps() throws SourceException {
        pages.put("/hub.html", exchange -> send(exchange, 503, "text/html", "down"));
        crawl();
        assertThat(source.getListingGaps().isComplete()).isFalse();

        pages.put("/hub.html", exchange -> send(exchange, 200, "text/html", HUB_LINKS));
        crawl();

        assertThat(source.getListingGaps().isComplete()).isTrue();
    }
}
