package com.eonmux.excalibur.ingest.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How an HTTP source honours the robots.txt of the site it crawls.
 */
@DisplayName("HTTP source and robots.txt")
class HttpSourceRobotsTest {

    private HttpServer server;
    private String baseUrl;

    /** Every path the server was asked for, in order. */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private volatile int robotsStatus = 200;
    private volatile String robotsBody = "";
    private volatile String robotsLocation;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            requested.add(query == null ? path : path + "?" + query);
            route(exchange, path);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void route(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/robots.txt" -> {
                if (robotsLocation != null) {
                    redirect(exchange, robotsLocation);
                } else {
                    respond(exchange, robotsStatus, "text/plain", robotsBody);
                }
            }
            case "/elsewhere/robots.txt" -> respond(exchange, 200, "text/plain", robotsBody);
            case "/" -> respond(exchange, 200, "text/html", links(
                "/public/a.html", "/private/b.html", "/private/open/c.html",
                "/moved.html", "/search?q=cats"));
            case "/moved.html" -> redirect(exchange, baseUrl + "/private/b.html");
            case "/moved-in-policy.html" -> redirect(exchange, baseUrl + "/public/a.html");
            default -> respond(exchange, 200, "text/html", "<html><body>" + path + "</body></html>");
        }
    }

    private static String links(String... hrefs) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String href : hrefs) {
            html.append("<a href=\"").append(href).append("\">x</a>");
        }
        return html.append("</body></html>").toString();
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private HttpSource.Builder builder() {
        return HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("robots-test")
            .baseUrl(baseUrl + "/")
            .maxDepth(2)
            .crawlDelayMillis(0)
            .timeoutSeconds(5);
    }

    private static List<String> crawl(HttpSource source) throws SourceException {
        try (Stream<String> urls = source.crawl()) {
            return urls.toList();
        }
    }

    private long robotsRequests() {
        return requested.stream().filter(p -> p.endsWith("robots.txt")).count();
    }

    @Test
    @DisplayName("a disallowed path is neither requested nor listed, and is counted")
    void disallowedPathIsSkipped() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            List<String> urls = crawl(source);

            assertThat(urls).contains(baseUrl + "/public/a.html")
                .noneMatch(url -> url.contains("/private/"));
            assertThat(requested).noneMatch(path -> path.startsWith("/private/"));
            // /private/b.html, /private/open/c.html, and /moved.html, which redirects into it.
            assertThat(source.getDisallowedCount()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a longer allow reopens part of a disallowed directory")
    void allowOverridesDisallow() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\nAllow: /private/open/\n";

        try (HttpSource source = builder().build()) {
            List<String> urls = crawl(source);

            assertThat(urls).contains(baseUrl + "/private/open/c.html")
                .doesNotContain(baseUrl + "/private/b.html");
        }
    }

    @Test
    @DisplayName("a query-matching rule keeps the query URL out")
    void queryRule() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /search?q=\n";

        try (HttpSource source = builder().build()) {
            assertThat(crawl(source)).doesNotContain(baseUrl + "/search?q=cats");
        }
    }

    @Test
    @DisplayName("the group named for the configured user agent is the one obeyed")
    void configuredUserAgentSelectsTheGroup() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /\n\nUser-agent: TestBot\nDisallow: /private/\n";

        try (HttpSource source = builder().userAgent("TestBot/3.1").build()) {
            assertThat(crawl(source)).contains(baseUrl + "/public/a.html")
                .doesNotContain(baseUrl + "/private/b.html");
        }
    }

    @Test
    @DisplayName("a missing robots.txt allows everything")
    void notFoundAllowsEverything() throws Exception {
        robotsStatus = 404;
        robotsBody = "User-agent: *\nDisallow: /\n";

        try (HttpSource source = builder().build()) {
            assertThat(crawl(source)).contains(baseUrl + "/private/b.html", baseUrl + "/public/a.html");
            assertThat(source.getDisallowedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a robots.txt answering 5xx fails the crawl and requests nothing else")
    void serverErrorFailsTheCrawl() throws Exception {
        robotsStatus = 503;

        try (HttpSource source = builder().build()) {
            assertThatThrownBy(source::crawl)
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("robots.txt")
                .hasMessageContaining("503")
                .hasMessageContaining("RFC 9309");
            assertThat(requested).containsExactly("/robots.txt");
        }
    }

    @Test
    @DisplayName("a document on an origin whose robots.txt is unreachable is not fetched")
    void serverErrorRefusesAFetch() throws Exception {
        robotsStatus = 500;

        try (HttpSource source = builder().build()) {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/public/a.html"))
                .isInstanceOf(SourceException.class)
                .isNotInstanceOf(FetchDisallowedException.class)
                .hasMessageContaining("robots.txt");
            assertThat(requested).containsExactly("/robots.txt");
            assertThat(source.getDisallowedCount()).isZero();
        }
    }

    @Test
    @DisplayName("an unreachable host for robots.txt fails the crawl too")
    void networkFailureFailsTheCrawl() throws Exception {
        String deadUrl;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadUrl = "http://localhost:" + socket.getLocalPort() + "/";
        }

        try (HttpSource source = builder().baseUrl(deadUrl).build()) {
            assertThatThrownBy(source::crawl)
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("robots.txt");
        }
    }

    @Test
    @DisplayName("a disallowed base URL yields an empty crawl, never requesting it")
    void disallowedBase() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /\n";

        try (HttpSource source = builder().build()) {
            assertThat(crawl(source)).isEmpty();
            assertThat(requested).containsExactly("/robots.txt");
            assertThat(source.getDisallowedCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a fetch redirected to a disallowed path stops before the second request")
    void redirectToDisallowedPath() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/moved.html"))
                .isInstanceOf(FetchDisallowedException.class)
                .hasMessageContaining("/private/b.html");
            assertThat(requested).containsExactly("/robots.txt", "/moved.html");
            assertThat(source.getDisallowedCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a fetch of a disallowed URL, conditional or not, sends nothing to it")
    void disallowedFetch() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/private/b.html"))
                .isInstanceOf(FetchDisallowedException.class);
            assertThatThrownBy(() -> source.fetchIfChanged(baseUrl + "/private/b.html",
                    new DocumentValidators(0L, "\"v1\"")))
                .isInstanceOf(FetchDisallowedException.class)
                .satisfies(e -> assertThat(((FetchDisallowedException) e).getDocumentId())
                    .isEqualTo(baseUrl + "/private/b.html"));
            assertThat(requested).containsExactly("/robots.txt");
        }
    }

    @Test
    @DisplayName("an allowed redirect is followed as before")
    void allowedRedirect() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            FetchedDocument doc = source.fetch(baseUrl + "/moved-in-policy.html");

            assertThat(new String(doc.getContent(), StandardCharsets.UTF_8)).contains("/public/a.html");
        }
    }

    @Test
    @DisplayName("robots.txt is read once per crawl run, and again by the next run")
    void readOncePerRun() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            crawl(source);
            source.fetch(baseUrl + "/public/a.html");
            assertThat(robotsRequests()).isEqualTo(1);

            robotsBody = "User-agent: *\nDisallow: /public/\n";
            List<String> second = crawl(source);

            assertThat(robotsRequests()).isEqualTo(2);
            assertThat(second).contains(baseUrl + "/private/b.html")
                .doesNotContain(baseUrl + "/public/a.html");
        }
    }

    @Test
    @DisplayName("each run counts only what it kept out")
    void countIsPerRun() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            crawl(source);
            assertThat(source.getDisallowedCount()).isPositive();

            robotsBody = "";
            crawl(source);
            assertThat(source.getDisallowedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a redirect of robots.txt on the same host is followed")
    void robotsRedirectIsFollowed() throws Exception {
        robotsLocation = baseUrl + "/elsewhere/robots.txt";
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            assertThat(crawl(source)).doesNotContain(baseUrl + "/private/b.html")
                .contains(baseUrl + "/public/a.html");
        }
    }

    @Test
    @DisplayName("a redirect of robots.txt off the allowed hosts is not followed, and fails the crawl")
    void robotsRedirectOffPolicy() throws Exception {
        robotsLocation = "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere/robots.txt";

        try (HttpSource source = builder().build()) {
            assertThatThrownBy(source::crawl)
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("outside the source policy");
            assertThat(requested).containsExactly("/robots.txt");
        }
    }

    @Test
    @DisplayName("Crawl-delay slows a crawl the configured pace would not")
    void crawlDelaySlows() throws Exception {
        robotsBody = "User-agent: *\nCrawl-delay: 0.4\n";

        try (HttpSource source = builder().build()) {
            long start = System.nanoTime();
            source.fetch(baseUrl + "/public/a.html");
            source.fetch(baseUrl + "/public/a.html");
            source.fetch(baseUrl + "/public/a.html");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Three page requests after the robots.txt one: at least two full gaps between them.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(800);
        }
    }

    @Test
    @DisplayName("without a Crawl-delay only the configured pace applies")
    void noCrawlDelay() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /private/\n";

        try (HttpSource source = builder().build()) {
            long start = System.nanoTime();
            source.fetch(baseUrl + "/public/a.html");
            source.fetch(baseUrl + "/public/a.html");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(800);
        }
    }

    @Test
    @DisplayName("a source told not to respect robots.txt never asks for it")
    void optOut() throws Exception {
        robotsBody = "User-agent: *\nDisallow: /\n";

        try (HttpSource source = builder().respectRobotsTxt(false).build()) {
            assertThat(source.isRespectingRobotsTxt()).isFalse();
            assertThat(crawl(source)).contains(baseUrl + "/private/b.html");
            source.fetch(baseUrl + "/private/b.html");

            assertThat(robotsRequests()).isZero();
            assertThat(source.getDisallowedCount()).isZero();
        }
    }

    @Test
    @DisplayName("robots.txt is respected unless a source says otherwise")
    void respectedByDefault() {
        try (HttpSource source = builder().build()) {
            assertThat(source.isRespectingRobotsTxt()).isTrue();
        }
    }
}
