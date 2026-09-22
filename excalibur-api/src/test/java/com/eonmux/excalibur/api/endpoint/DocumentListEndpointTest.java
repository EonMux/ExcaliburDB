package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /v1/documents/list} - which documents this node holds.
 *
 * <p>The registry is what says which documents the node has and under which chunk ids, and it
 * decides change detection, erasure and reindexing. Nothing read it back. A caller could ask
 * for a chunk's text if it already knew the chunk id, and could erase a document if it already
 * knew the document id, but there was no request that produced either - the ids came from a
 * search result or from whatever crawled the source, and for anything ingested before a
 * restart, from nowhere at all.</p>
 */
@DisplayName("GET /v1/documents/list")
class DocumentListEndpointTest {

    private DocumentRegistry registry;
    private DocumentListEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        endpoint = new DocumentListEndpoint(registry);
    }

    private void hold(String sourceId, String documentId, List<String> chunkIds) {
        registry.register(DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .chunkIds(chunkIds)
            .contentLength(42)
            .contentHash(7)
            .lastModifiedMillis(1_000L)
            .indexTimeMillis(2_000L)
            .build());
    }

    private String listing(String query) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        endpoint.handle(get(query, body));
        return body.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null registry")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> new DocumentListEndpoint(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Listing")
    class Listing {

        @Test
        @DisplayName("an empty node answers with an empty page rather than an error")
        void emptyNode() throws IOException {
            assertThat(listing(""))
                .contains("\"documents\": []")
                .contains("\"total\": 0")
                .contains("\"returned\": 0");
        }

        @Test
        @DisplayName("each document carries what the registry knows about it")
        void reportsWhatTheRegistryHolds() throws IOException {
            hold("my-docs", "guide.md", List.of("chunk-1", "chunk-2"));

            String json = listing("");

            assertThat(json).contains("\"source_id\": \"my-docs\"");
            assertThat(json).contains("\"document_id\": \"guide.md\"");
            assertThat(json).contains("\"chunk_count\": 2");
            assertThat(json).contains("\"content_length\": 42");
            assertThat(json).contains("\"content_hash\": \"7\"");
            assertThat(json).contains("\"last_modified_ms\": 1000");
            assertThat(json).contains("\"indexed_at_ms\": 2000");
        }

        @Test
        @DisplayName("the chunk ids are there, because erasure and fetch are addressed by them")
        void namesTheChunks() throws IOException {
            hold("my-docs", "guide.md", List.of("chunk-1", "chunk-2"));

            assertThat(listing("")).contains("\"chunk_ids\": [\"chunk-1\", \"chunk-2\"]");
        }

        @Test
        @DisplayName("documents come back in a stable order, so a page means something")
        void stableOrder() throws IOException {
            hold("b-source", "second", List.of("c"));
            hold("a-source", "beta", List.of("c"));
            hold("a-source", "alpha", List.of("c"));

            String json = listing("");

            assertThat(json.indexOf("\"alpha\"")).isLessThan(json.indexOf("\"beta\""));
            assertThat(json.indexOf("\"beta\"")).isLessThan(json.indexOf("\"second\""));
        }

        @Test
        @DisplayName("a source filter narrows the page and the total with it")
        void filtersBySource() throws IOException {
            hold("a-source", "alpha", List.of("c"));
            hold("b-source", "beta", List.of("c"));

            String json = listing("?source_id=a-source");

            assertThat(json).contains("\"alpha\"").doesNotContain("\"beta\"");
            assertThat(json).contains("\"total\": 1");
        }

        @Test
        @DisplayName("a blank source filter means unfiltered, not the source called nothing")
        void blankSourceFilterIsNoFilter() throws IOException {
            hold("a-source", "alpha", List.of("c"));

            assertThat(listing("?source_id="))
                .as("a client templating an unset variable into the query would otherwise be "
                    + "told the node holds nothing")
                .contains("\"alpha\"")
                .contains("\"total\": 1");
        }

        @Test
        @DisplayName("the content hash is a string, because it does not fit a JSON number")
        void contentHashIsAString() throws IOException {
            registry.register(DocumentMetadata.builder()
                .sourceId("src").documentId("big-hash")
                .chunkIds(List.of("c"))
                .contentHash(9_007_199_254_740_993L)
                .build());

            assertThat(listing(""))
                .as("xxHash64 spans the whole positive long range, and a parser backed by "
                    + "doubles silently rounds anything past 2^53")
                .contains("\"content_hash\": \"9007199254740993\"");
        }

        @Test
        @DisplayName("a filter naming nothing is an empty page, not a 404")
        void unknownSourceIsEmpty() throws IOException {
            hold("a-source", "alpha", List.of("c"));

            assertThat(listing("?source_id=nothing-here"))
                .contains("\"documents\": []")
                .contains("\"total\": 0");
        }
    }

    @Nested
    @DisplayName("Paging")
    class Paging {

        private String cursorOf(String json) {
            int at = json.indexOf("\"next_cursor\": ");
            String tail = json.substring(at + "\"next_cursor\": ".length());
            if (tail.startsWith("null")) {
                return null;
            }
            return tail.substring(1, tail.indexOf('"', 1));
        }

        @Test
        @DisplayName("a page is bounded and says what the whole set holds")
        void limitBoundsThePage() throws IOException {
            for (int i = 0; i < 5; i++) {
                hold("src", "doc-" + i, List.of("c"));
            }

            String json = listing("?limit=2");

            assertThat(json).contains("\"total\": 5");
            assertThat(json).contains("\"returned\": 2");
            assertThat(json).contains("\"doc-0\"").contains("\"doc-1\"")
                .doesNotContain("\"doc-2\"");
            assertThat(cursorOf(json)).isNotNull();
        }

        @Test
        @DisplayName("the cursor walks the whole set exactly once")
        void theCursorWalksEverything() throws IOException {
            for (int i = 0; i < 5; i++) {
                hold("src", "doc-" + i, List.of("c"));
            }

            List<String> seen = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 10; page++) {
                String json = listing(cursor == null ? "?limit=2"
                    : "?limit=2&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                for (int i = 0; i < 5; i++) {
                    if (json.contains("\"doc-" + i + "\"")) {
                        seen.add("doc-" + i);
                    }
                }
                cursor = cursorOf(json);
                if (cursor == null) {
                    break;
                }
            }

            assertThat(seen)
                .as("every document exactly once, in order, and the walk terminates")
                .containsExactly("doc-0", "doc-1", "doc-2", "doc-3", "doc-4");
        }

        @Test
        @DisplayName("the last page says there is no next one")
        void theLastPageEndsTheWalk() throws IOException {
            hold("src", "only", List.of("c"));

            String json = listing("?limit=2");

            assertThat(json).contains("\"returned\": 1");
            assertThat(json).contains("\"next_cursor\": null");
        }

        @Test
        @DisplayName("a cursor past the last document is an empty page, not an error")
        void cursorPastTheEnd() throws IOException {
            hold("src", "only", List.of("c"));
            String cursor = cursorOf(listing("?limit=1"));
            assertThat(cursor).isNull();

            hold("src", "second", List.of("c"));
            cursor = cursorOf(listing("?limit=1"));

            String json = listing("?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
                + "&limit=5");
            assertThat(json).contains("\"total\": 2");
            assertThat(json).contains("\"returned\": 1");
            assertThat(json).contains("\"next_cursor\": null");
        }

        @Test
        @DisplayName("a limit above the ceiling is refused rather than quietly trimmed")
        void limitAboveCeilingRefused() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("?limit=" + (DocumentListEndpoint.MAX_LIMIT + 1), body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("limit");
        }

        @Test
        @DisplayName("a limit that is not a number is refused")
        void nonNumericLimitRefused() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("?limit=lots", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("a cursor this node did not issue is refused, not guessed at")
        void unreadableCursorRefused() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("?cursor=not-a-cursor", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("cursor");
        }

        @Test
        @DisplayName("a refusal quotes back only as much of the value as identifies it")
        void refusalDoesNotEchoTheWholeParameter() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("?limit=" + "9".repeat(4000), body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8).length())
                .as("the node must not be made to echo and log a parameter of any size")
                .isLessThan(400);
        }
    }

    @Nested
    @DisplayName("Bounding the response")
    class Bounding {

        @Test
        @DisplayName("a page stops at the chunk-id budget, however many documents that is")
        void chunkIdBudgetTrimsThePage() throws IOException {
            // A document may hold thousands of chunks, so a limit counted in documents bounds
            // nothing: the page a caller asked for could be hundreds of megabytes of chunk ids.
            int perDocument = 6_000;
            for (int i = 0; i < 4; i++) {
                List<String> chunks = new ArrayList<>(perDocument);
                for (int c = 0; c < perDocument; c++) {
                    chunks.add("src:doc-" + i + ":chunk:" + c);
                }
                hold("src", "doc-" + i, chunks);
            }

            String json = listing("?limit=4");

            int returned = Integer.parseInt(json.substring(
                json.indexOf("\"returned\": ") + "\"returned\": ".length()).split("[,}]")[0]);
            assertThat(returned)
                .as("the budget cut the page short of the four documents asked for")
                .isLessThan(4);
            assertThat(json).contains("\"total\": 4");
            assertThat(json).doesNotContain("\"next_cursor\": null");
        }

        @Test
        @DisplayName("one document over the budget on its own is still returned")
        void aSingleOversizedDocumentStillComesBack() throws IOException {
            List<String> chunks = new ArrayList<>();
            for (int c = 0; c < DocumentListEndpoint.MAX_CHUNK_IDS_PER_PAGE + 10; c++) {
                chunks.add("src:big:chunk:" + c);
            }
            hold("src", "big", chunks);

            assertThat(listing("?limit=5"))
                .as("a page that returned nothing would never advance, and the walk would "
                    + "never terminate")
                .contains("\"returned\": 1");
        }
    }

    @Nested
    @DisplayName("Method")
    class Method {

        @Test
        @DisplayName("only GET reads the listing")
        void refusesOtherMethods() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = get("", body);
            when(exchange.getRequestMethod()).thenReturn("POST");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    private HttpExchange get(String query, ByteArrayOutputStream body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/documents/list" + query));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }
}
