package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.processor.TextContent;
import com.eonmux.excalibur.ingest.source.DocumentValidators;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.HttpSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A re-crawl of an HTTP source asks the server whether each indexed page is still current, with
 * the validators the server gave for it, and takes {@code 304 Not Modified} as unchanged without
 * transferring the page again - including after a restart, which is when the validators can only
 * have come from the registry file.
 */
@DisplayName("Conditional re-crawl")
class ConditionalRecrawlTest {

    private static final String SOURCE_ID = "site";

    /** A Monday, the 2nd: a single-digit day is what a loose HTTP date writes wrongly. */
    private static final Instant PUBLISHED = Instant.parse("2026-02-02T09:30:00Z");

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.ENGLISH)
        .withZone(ZoneOffset.UTC);

    @TempDir
    Path dir;

    private HttpServer server;
    private String baseUrl;

    /** How many times each path sent its body. A 304 sends none. */
    private final Map<String, AtomicInteger> bodiesSent = new ConcurrentHashMap<>();

    /** Every request's conditional headers, per path, in arrival order. */
    private final Map<String, List<String>> conditionsSeen = new ConcurrentHashMap<>();

    private final AtomicInteger changingVersion = new AtomicInteger(1);

    private final AtomicInteger rotatingTag = new AtomicInteger(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/", exchange -> send(exchange, 200, Map.of("Content-Type",
            "text/html"), "<a href=\"/tagged.txt\">t</a><a href=\"/dated.txt\">d</a>"
                + "<a href=\"/changing.txt\">c</a><a href=\"/ignores.txt\">i</a>"
            + "<a href=\"/rotating.txt\">r</a>"));

        // Keeps an entity tag, and honours If-None-Match.
        server.createContext("/tagged.txt", exchange -> {
            record(exchange);
            String etag = "W/\"tagged-1\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                send(exchange, 304, Map.of("ETag", etag), null);
            } else {
                send(exchange, 200, Map.of("Content-Type", "text/plain", "ETag", etag),
                    "a page named by its tag");
            }
        });

        // Keeps only a modification time, and honours If-Modified-Since.
        server.createContext("/dated.txt", exchange -> {
            record(exchange);
            String since = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            if (since != null && !Instant.from(HTTP_DATE.parse(since)).isBefore(PUBLISHED)) {
                send(exchange, 304, Map.of(), null);
            } else {
                send(exchange, 200, Map.of("Content-Type", "text/plain",
                    "Last-Modified", HTTP_DATE.format(PUBLISHED)), "a page known by its date");
            }
        });

        // Its tag moves with its content.
        server.createContext("/changing.txt", exchange -> {
            record(exchange);
            String etag = "\"v" + changingVersion.get() + "\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                send(exchange, 304, Map.of("ETag", etag), null);
            } else {
                send(exchange, 200, Map.of("Content-Type", "text/plain", "ETag", etag),
                    "content, version " + changingVersion.get());
            }
        });

        // Names the same content by a new tag whenever it is redeployed.
        server.createContext("/rotating.txt", exchange -> {
            record(exchange);
            String etag = "\"deploy-" + rotatingTag.get() + "\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                send(exchange, 304, Map.of("ETag", etag), null);
            } else {
                send(exchange, 200, Map.of("Content-Type", "text/plain", "ETag", etag),
                    "the same words under every deployment");
            }
        });

        // Sends a tag, but never answers a condition.
        server.createContext("/ignores.txt", exchange -> {
            record(exchange);
            send(exchange, 200, Map.of("Content-Type", "text/plain", "ETag", "\"same\""),
                "always sent in full");
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void record(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
        String ims = exchange.getRequestHeaders().getFirst("If-Modified-Since");
        conditionsSeen.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>())
            .add("If-None-Match=" + inm + "; If-Modified-Since=" + ims);
    }

    private void send(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        bodiesSent.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new AtomicInteger())
            .incrementAndGet();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpSource source() {
        return HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId(SOURCE_ID)
            .baseUrl(baseUrl + "/")
            .maxDepth(1)
            .crawlDelayMillis(0)
            .build();
    }

    private int bodies(String path) {
        AtomicInteger count = bodiesSent.get(path);
        return count == null ? 0 : count.get();
    }

    /** Ingests every page once, recording what the registry would record, and saves it. */
    private void firstCrawl(Path registryFile) throws Exception {
        firstCrawl(registryFile, true);
    }

    /**
     * Ingests every page once and saves the registry.
     *
     * @param withTags false to record no entity tags, as a registry written before they were
     *                 kept has none
     */
    private void firstCrawl(Path registryFile, boolean withTags) throws Exception {
        DocumentRegistry registry = new DocumentRegistry(registryFile);
        try (HttpSource source = source(); Stream<String> ids = source.crawl()) {
            for (String id : ids.toList()) {
                FetchedDocument doc = source.fetch(id);
                byte[] content = doc.getContent();
                registry.register(DocumentMetadata.builder()
                    .sourceId(SOURCE_ID)
                    .documentId(id)
                    .lastModifiedMillis(doc.getLastModifiedMillis())
                    .entityTag(withTags ? doc.getEntityTag() : null)
                    .decoding(TextContent.decodingOf(doc))
                    .contentHash(DocumentRegistry.computeContentHash(content))
                    .contentLength(content.length)
                    .chunkIds(List.of(id + "#0"))
                    .indexTimeMillis(System.currentTimeMillis())
                    .build());
            }
        }
        registry.persist();
    }

    @Test
    @DisplayName("after a restart, unchanged pages are answered with 304 and only a changed one is refetched")
    void recrawlAfterRestartUsesPersistedValidators() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        firstCrawl(registryFile);
        assertThat(bodies("/tagged.txt")).isEqualTo(1);
        assertThat(bodies("/dated.txt")).isEqualTo(1);
        assertThat(bodies("/changing.txt")).isEqualTo(1);

        changingVersion.set(2);
        // A new registry over the same file: the validators can only have come from disk.
        ChangeDetector detector = new ChangeDetector(new DocumentRegistry(registryFile));
        ChangeSet changes;
        try (HttpSource source = source()) {
            changes = detector.detectChanges(source);
        }

        assertThat(changes.getNewDocuments()).isEmpty();
        assertThat(changes.getDeletedDocuments()).isEmpty();
        assertThat(changes.getChangedDocuments())
            .extracting(ChangeSet.ChangedDocument::getDocumentId)
            .containsExactly(baseUrl + "/changing.txt");

        assertThat(bodies("/tagged.txt"))
            .as("a 304 transfers nothing, so the page was sent once, on the first crawl")
            .isEqualTo(1);
        assertThat(bodies("/dated.txt")).isEqualTo(1);
        assertThat(bodies("/changing.txt"))
            .as("the changed page is fetched again in full")
            .isEqualTo(2);

        assertThat(conditionsSeen.get("/tagged.txt")).last().asString()
            .isEqualTo("If-None-Match=W/\"tagged-1\"; If-Modified-Since=null");
        assertThat(conditionsSeen.get("/dated.txt")).last().asString()
            .isEqualTo("If-None-Match=null; If-Modified-Since=Mon, 02 Feb 2026 09:30:00 GMT");
        assertThat(conditionsSeen.get("/changing.txt")).last().asString()
            .isEqualTo("If-None-Match=\"v1\"; If-Modified-Since=null");
    }

    @Test
    @DisplayName("a server that ignores the conditions costs a full fetch, and an unchanged page is still unchanged")
    void aServerIgnoringConditionsIsComparedByContent() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        firstCrawl(registryFile);

        ChangeDetector detector = new ChangeDetector(new DocumentRegistry(registryFile));
        ChangeSet changes;
        try (HttpSource source = source()) {
            changes = detector.detectChanges(source);
        }

        assertThat(changes.getChangedDocuments()).isEmpty();
        assertThat(bodies("/ignores.txt")).isEqualTo(2);
        assertThat(conditionsSeen.get("/ignores.txt")).last().asString()
            .startsWith("If-None-Match=\"same\"");
    }

    @Test
    @DisplayName("a page recorded without a tag learns it from an unchanged 200, and is asked conditionally after a restart")
    void anUnchangedFetchRecordsTheTagItBrought() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        // What a registry written before entity tags were kept holds: the right content, no tag.
        firstCrawl(registryFile, false);

        DocumentRegistry registry = new DocumentRegistry(registryFile);
        try (HttpSource source = source()) {
            assertThat(new ChangeDetector(registry).detectChanges(source).getChangedDocuments())
                .isEmpty();
        }
        assertThat(registry.get(SOURCE_ID, baseUrl + "/tagged.txt").getEntityTag())
            .as("the tag the unchanged page came back with is recorded")
            .isEqualTo("W/\"tagged-1\"");
        assertThat(registry.isModified()).as("and the next save writes it").isTrue();
        registry.persist();

        try (HttpSource source = source()) {
            new ChangeDetector(new DocumentRegistry(registryFile)).detectChanges(source);
        }
        assertThat(conditionsSeen.get("/tagged.txt")).last().asString()
            .isEqualTo("If-None-Match=W/\"tagged-1\"; If-Modified-Since=null");
        assertThat(bodies("/tagged.txt"))
            .as("sent in full twice - the first crawl and the one without a tag - then 304")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("a tag that moves while the content does not is followed, so the next re-crawl can be answered 304")
    void aRotatedTagIsFollowed() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        firstCrawl(registryFile);
        rotatingTag.set(2);

        DocumentRegistry registry = new DocumentRegistry(registryFile);
        ChangeDetector detector = new ChangeDetector(registry);
        try (HttpSource source = source()) {
            assertThat(detector.detectChanges(source).getChangedDocuments())
                .as("a new tag over the same bytes is not a change")
                .isEmpty();
            assertThat(registry.get(SOURCE_ID, baseUrl + "/rotating.txt").getEntityTag())
                .isEqualTo("\"deploy-2\"");

            detector.detectChanges(source);
        }
        assertThat(conditionsSeen.get("/rotating.txt")).last().asString()
            .isEqualTo("If-None-Match=\"deploy-2\"; If-Modified-Since=null");
        assertThat(bodies("/rotating.txt")).isEqualTo(2);
    }

    @Test
    @DisplayName("validators that did not move leave the registry with nothing to save")
    void unchangedValidatorsAreNotRewritten() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        firstCrawl(registryFile);

        DocumentRegistry registry = new DocumentRegistry(registryFile);
        try (HttpSource source = source()) {
            new ChangeDetector(registry).detectChanges(source);
        }
        assertThat(registry.isModified()).isFalse();
    }

    @Test
    @DisplayName("a single-document check asks the same conditional question")
    void singleDocumentCheck() throws Exception {
        Path registryFile = dir.resolve("registry.dat");
        firstCrawl(registryFile);
        ChangeDetector detector = new ChangeDetector(new DocumentRegistry(registryFile));

        try (HttpSource source = source()) {
            assertThat(detector.hasChanged(source, baseUrl + "/tagged.txt")).isFalse();
            changingVersion.set(3);
            assertThat(detector.hasChanged(source, baseUrl + "/changing.txt")).isTrue();
        }
        assertThat(bodies("/tagged.txt")).isEqualTo(1);
    }

    @Test
    @DisplayName("fetchIfChanged answers empty on 304 and the full document otherwise")
    void fetchIfChangedDirectly() throws Exception {
        try (HttpSource source = source()) {
            String url = baseUrl + "/changing.txt";
            FetchedDocument first = source.fetch(url);
            assertThat(first.getEntityTag()).isEqualTo("\"v1\"");
            assertThat(conditionsSeen.get("/changing.txt")).last().asString()
                .as("an ordinary fetch asks unconditionally")
                .isEqualTo("If-None-Match=null; If-Modified-Since=null");

            assertThat(source.fetchIfChanged(url, first.validators())).isEmpty();

            changingVersion.set(2);
            Optional<FetchedDocument> second = source.fetchIfChanged(url, first.validators());
            assertThat(second).isPresent();
            assertThat(second.get().getText()).isEqualTo("content, version 2");
            assertThat(second.get().getEntityTag()).isEqualTo("\"v2\"");

            assertThat(source.fetchIfChanged(url, DocumentValidators.NONE))
                .as("with nothing to ask with, the fetch is unconditional")
                .isPresent();
        }
    }
}
