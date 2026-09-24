package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.HttpSource;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A re-crawl of a website whose pages could not all be read for links leaves the index intact.
 *
 * <p>The crawl used to read a page it could not fetch as a page with no links, so everything
 * reachable only through it dropped out of the listing, and change detection - which infers
 * deletions by subtraction - removed it all. The base page is itself a listed document, so one
 * {@code 503} from it on a re-crawl emptied the source's index. This runs the whole path: a real
 * crawl of a local site, the real reindexer, and the status an operator polls.</p>
 */
@DisplayName("A re-crawl over a page that could not be read")
class RecrawlOverUnreadablePageTest {

    private static final String SOURCE_ID = "site";

    private HttpServer server;
    private String baseUrl;
    private HttpSource source;
    private DocumentRegistry registry;
    private RecordingWriter writer;
    private ReindexEndpoint.InMemoryReindexStatusRegistry statuses;
    private ReindexRun run;

    /** What each path answers; a path missing here is a 404. */
    private final Map<String, Page> site = new ConcurrentHashMap<>();

    private record Page(int status, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::answer);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        site.put("/", new Page(200, "<html><body>Home of the handbook. "
            + "<a href=\"/hub.html\">chapters</a></body></html>"));
        site.put("/hub.html", new Page(200, "<html><body>The chapters. "
            + "<a href=\"/install.html\">install</a> <a href=\"/upgrade.html\">upgrade</a>"
            + "</body></html>"));
        site.put("/install.html", new Page(200, "<html><body>How to install it.</body></html>"));
        site.put("/upgrade.html", new Page(200, "<html><body>How to upgrade it.</body></html>"));

        source = HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId(SOURCE_ID)
            .baseUrl(baseUrl + "/")
            .maxDepth(3)
            .crawlDelayMillis(0)
            .timeoutSeconds(5)
            .build();
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, writer), registry, writer);
        statuses = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        run = new ReindexRun(reindexer, id -> source, null, null, statuses, null);
    }

    @AfterEach
    void tearDown() {
        source.close();
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            Page page = site.getOrDefault(exchange.getRequestURI().getPath(),
                new Page(404, "not here"));
            byte[] bytes = page.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(page.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private ReindexEndpoint.ReindexStatus reindex() {
        statuses.startReindex(SOURCE_ID);
        run.execute(SOURCE_ID);
        return statuses.getStatus(SOURCE_ID);
    }

    private void assertEveryPageIsStillIndexed() {
        for (String path : new String[] {"/", "/hub.html", "/install.html", "/upgrade.html"}) {
            assertThat(registry.get(SOURCE_ID, baseUrl + path))
                .as("%s is still registered", path)
                .isNotNull();
        }
        assertThat(writer.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("a 503 from the base page removes nothing, and says which page failed")
    void aFailingBasePageRemovesNothing() {
        ReindexEndpoint.ReindexStatus first = reindex();
        assertThat(first.lastError()).as("precondition: a clean first crawl").isNull();
        assertEveryPageIsStillIndexed();

        site.put("/", new Page(503, "down for maintenance"));
        ReindexEndpoint.ReindexStatus recrawl = reindex();

        assertEveryPageIsStillIndexed();
        assertThat(recrawl.documentsKeptUnlisted()).isEqualTo(3);
        assertThat(recrawl.documentsUnchecked())
            .as("the base page itself could not be asked whether it changed")
            .isEqualTo(1);
        assertThat(recrawl.lastError())
            .contains("listing was incomplete")
            .contains("3 documents it did not list were kept")
            .contains(baseUrl + "/ (links not read: HTTP 503)");
    }

    @Test
    @DisplayName("a 503 from a hub keeps what only it led to, and the run otherwise completes")
    void aFailingHubKeepsWhatItLedTo() {
        reindex();

        site.put("/hub.html", new Page(503, "overloaded"));
        ReindexEndpoint.ReindexStatus recrawl = reindex();

        assertEveryPageIsStillIndexed();
        assertThat(recrawl.state()).isEqualTo("idle");
        assertThat(recrawl.documentsKeptUnlisted()).isEqualTo(2);
        assertThat(recrawl.lastError()).contains(baseUrl + "/hub.html (links not read: HTTP 503)");
    }

    @Test
    @DisplayName("once the listing is whole again, a page that has gone is removed")
    void aCompleteListingStillRemovesWhatHasGone() {
        reindex();
        site.put("/hub.html", new Page(503, "overloaded"));
        reindex();

        site.put("/hub.html", new Page(200, "<html><body>The chapters. "
            + "<a href=\"/install.html\">install</a></body></html>"));
        site.remove("/upgrade.html");
        ReindexEndpoint.ReindexStatus recrawl = reindex();

        assertThat(registry.get(SOURCE_ID, baseUrl + "/upgrade.html")).isNull();
        assertThat(registry.get(SOURCE_ID, baseUrl + "/install.html")).isNotNull();
        assertThat(recrawl.documentsKeptUnlisted()).isZero();
        assertThat(recrawl.lastError()).isNull();
    }

    @Test
    @DisplayName("a hub that is gone takes what only it led to with it")
    void aGoneHubIsAnAnswer() {
        reindex();

        site.remove("/hub.html");
        ReindexEndpoint.ReindexStatus recrawl = reindex();

        assertThat(registry.get(SOURCE_ID, baseUrl + "/hub.html")).isNull();
        assertThat(registry.get(SOURCE_ID, baseUrl + "/install.html")).isNull();
        assertThat(registry.get(SOURCE_ID, baseUrl + "/upgrade.html")).isNull();
        assertThat(registry.get(SOURCE_ID, baseUrl + "/")).isNotNull();
        assertThat(recrawl.documentsKeptUnlisted()).isZero();
    }

    /** A storage writer that keeps each document's first chunk, so removals can be seen. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> chunks = new ConcurrentHashMap<>();

        int size() {
            return chunks.size();
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            chunks.put(chunkId, content);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return chunks.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }
}
