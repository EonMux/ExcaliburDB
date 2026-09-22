package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /v1/ingest} - what this node has been asked to crawl.
 *
 * <p>The node recorded every registration in full and read it back at exactly one moment:
 * startup. After a restart nobody could ask what a source pointed at, what it filtered, or
 * whether the node had managed to rebuild a crawler for it at all - and that last one is a
 * silent failure, because a registration whose target the allow-list no longer permits is kept
 * on file and simply never crawled again.</p>
 */
@DisplayName("GET /v1/ingest")
class SourceListEndpointTest {

    @TempDir
    Path dataDirectory;

    private InMemorySourceRegistry registry;
    private SourceCatalog catalog;
    private SourceListEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new InMemorySourceRegistry();
        catalog = SourceCatalog.open(dataDirectory);
        endpoint = new SourceListEndpoint(registry, catalog);
    }

    private String listing(String query) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        endpoint.handle(get(query, body));
        return body.toString(StandardCharsets.UTF_8);
    }

    private void record(String sourceId, Map<String, String> config) throws IOException {
        catalog.record(new SourceDescriptor("filesystem", sourceId, config));
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null registry")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> new SourceListEndpoint(null, catalog))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("accepts a node that keeps no catalog")
        void acceptsNoCatalog() {
            assertThatCode(() -> new SourceListEndpoint(registry, null))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Reading the catalog back")
    class Listing {

        @Test
        @DisplayName("names the namespace a source stages into")
        void reportsTheNamespace() throws IOException {
            // A source's namespace is fixed at registration and decides which tenant's graph
            // its facts land in. Reading it back out of the catalog file is not an answer an
            // operator should have to reach for, and "which tenant is this crawl feeding" is
            // the first question asked when a build comes up empty.
            catalog.record(new SourceDescriptor("filesystem", "tenant-docs",
                Map.of(SourceFactory.ROOT_PATH, "/srv/docs"),
                com.eonmux.excalibur.tenancy.NamespaceId.of("acme")));
            record("shared-docs", Map.of(SourceFactory.ROOT_PATH, "/srv/other"));

            String json = listing("?source_id=tenant-docs");
            assertThat(json).contains("\"namespace\": \"acme\"");

            assertThat(listing("?source_id=shared-docs"))
                .as("a registration that named none stages into the shared namespace, and "
                    + "saying so is more useful than omitting the field")
                .contains("\"namespace\": \"shared\"");
        }

        @Test
        @DisplayName("answers with what a source was registered as")
        void reportsTheRegistration() throws IOException {
            record("my-docs", Map.of(
                SourceFactory.ROOT_PATH, "/srv/docs",
                SourceFactory.INCLUDED_EXTENSIONS, ".md,.txt",
                SourceFactory.MAX_DEPTH, "4"));
            registry.restore(new StubSource("my-docs"));

            String json = listing("");

            assertThat(json).contains("\"source_id\": \"my-docs\"");
            assertThat(json).contains("\"source_type\": \"filesystem\"");
            assertThat(json).contains("\"root_path\": \"/srv/docs\"");
            assertThat(json).contains("\"included_extensions\": \".md,.txt\"");
            assertThat(json).contains("\"max_depth\": \"4\"");
        }

        @Test
        @DisplayName("says whether the node actually has a crawler for the registration")
        void reportsWhetherTheSourceIsLive() throws IOException {
            record("live", Map.of(SourceFactory.ROOT_PATH, "/srv/live"));
            record("orphaned", Map.of(SourceFactory.ROOT_PATH, "/srv/no-longer-allowed"));
            registry.restore(new StubSource("live"));

            String json = listing("?source_id=orphaned");

            assertThat(json)
                .as("a registration the node could not rebuild is never crawled again and "
                    + "nothing else on the API says so")
                .contains("\"registered\": false");
            assertThat(listing("?source_id=live")).contains("\"registered\": true");
        }

        @Test
        @DisplayName("wraps every source, and one named source is returned bare")
        void twoShapes() throws IOException {
            record("a", Map.of(SourceFactory.ROOT_PATH, "/srv/a"));
            record("b", Map.of(SourceFactory.ROOT_PATH, "/srv/b"));

            assertThat(listing("")).startsWith("{\"sources\": [").contains("\"a\"", "\"b\"");
            assertThat(listing("?source_id=a"))
                .startsWith("{\"source_id\"")
                .doesNotContain("\"sources\"");
        }

        @Test
        @DisplayName("a source nobody registered is 404")
        void unknownSourceIs404() throws IOException {
            HttpExchange exchange = get("?source_id=never-seen", new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }

        @Test
        @DisplayName("a node with no catalog still reports what it is crawling")
        void withoutACatalog() throws IOException {
            registry.restore(new StubSource("in-memory-only"));

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            new SourceListEndpoint(registry, null).handle(get("", body));

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"source_id\": \"in-memory-only\"");
            assertThat(json).contains("\"source_type\": \"stub\"");
            assertThat(json).contains("\"registered\": true");
        }
    }

    @Nested
    @DisplayName("What a read-back is allowed to echo")
    class Withholding {

        @Test
        @DisplayName("a key this node does not recognise is named but not quoted")
        void unknownKeysAreWithheld() throws IOException {
            record("hand-edited", Map.of(
                SourceFactory.ROOT_PATH, "/srv/docs",
                "authorization", "Bearer sk-not-yours"));

            String json = listing("?source_id=hand-edited");

            assertThat(json)
                .as("the catalog is a file on disk and a later release may put a credential "
                    + "in it; echoing whatever is there to every holder of a read token is "
                    + "the wrong default to be holding when that happens")
                .doesNotContain("sk-not-yours");
            assertThat(json).contains("\"withheld\": [\"authorization\"]");
            assertThat(json).contains("\"root_path\": \"/srv/docs\"");
        }

        @Test
        @DisplayName("a registration with nothing withheld says nothing about withholding")
        void nothingWithheld() throws IOException {
            record("plain", Map.of(SourceFactory.ROOT_PATH, "/srv/docs"));

            assertThat(listing("?source_id=plain")).doesNotContain("withheld");
        }
    }

    @Nested
    @DisplayName("Method and argument handling")
    class Refusals {

        @Test
        @DisplayName("refuses anything but GET")
        void refusesOtherMethods() throws IOException {
            HttpExchange exchange = get("", new ByteArrayOutputStream());
            when(exchange.getRequestMethod()).thenReturn("PUT");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("refuses a source id that is not one")
        void refusesAMalformedSourceId() throws IOException {
            HttpExchange exchange = get("?source_id=" + "x".repeat(4096),
                new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    private HttpExchange get(String query, ByteArrayOutputStream body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/ingest" + query));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }

    /** A source that exists only so the registry has something to hold. */
    private record StubSource(String sourceId) implements Source {

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "stub";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.empty();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return false;
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            throw new UnsupportedOperationException("not fetched in this test");
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
