package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.retrieval.ChunkTextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /v1/documents} - reading back the text behind an identifier a search returned.
 */
@DisplayName("DocumentFetchEndpoint")
class DocumentFetchEndpointTest {

    private static final Map<String, String> CORPUS = Map.of(
        "docs:guide.md:chunk:0", "the first passage",
        "docs:guide.md:chunk:1", "the second passage");

    private DocumentFetchEndpoint endpoint;
    private ByteArrayOutputStream body;

    @BeforeEach
    void setUp() {
        endpoint = new DocumentFetchEndpoint(ChunkTextResolver.over(CORPUS::get, 4096), 8);
        body = new ByteArrayOutputStream();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null resolver")
        void rejectsNullResolver() {
            assertThatThrownBy(() -> new DocumentFetchEndpoint(null, 8))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects a non-positive request bound")
        void rejectsNonPositiveBound() {
            assertThatThrownBy(() ->
                new DocumentFetchEndpoint(ChunkTextResolver.over(CORPUS::get, 10), 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Fetching one document")
    class FetchOne {

        @Test
        @DisplayName("returns the stored text for an identifier named in the query string")
        void returnsTextForOne() throws IOException {
            HttpExchange exchange = get("/v1/documents?document_id=docs%3Aguide.md%3Achunk%3A0");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"text\": \"the first passage\"");
            assertThat(json).contains("\"documents_not_found\": 0");
        }

        /**
         * The identifier is the whole request here, so a request without one is not a request
         * for everything - it is a client bug, and 400 is what lets the author see it.
         */
        /**
         * A chunk id is built from a document path, and '+' is an ordinary character in one.
         * Form decoding turns it into a space, so the lookup misses and is reported as a chunk
         * the node does not hold - indistinguishable from an erased one.
         */
        @Test
        @DisplayName("treats a plus in an identifier as a plus, not a space")
        void plusIsNotASpace() throws IOException {
            DocumentFetchEndpoint over = new DocumentFetchEndpoint(
                ChunkTextResolver.over(Map.of("docs:c++notes.md:chunk:0", "about C++")::get, 4096), 8);
            HttpExchange exchange = get("/v1/documents?document_id=docs:c++notes.md:chunk:0");

            over.handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("about C++")
                .contains("\"documents_not_found\": 0");
        }

        @Test
        @DisplayName("refuses a GET that names no identifier")
        void refusesAGetWithNoId() throws IOException {
            HttpExchange exchange = get("/v1/documents");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("document_id");
        }

        /**
         * A chunk the corpus does not hold is reported rather than omitted. A caller that asked
         * for three identifiers and got two back cannot tell which one was dropped.
         */
        @Test
        @DisplayName("reports a document it does not hold rather than omitting it")
        void reportsAMissingDocument() throws IOException {
            HttpExchange exchange = get("/v1/documents?document_id=nothing");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"document_id\": \"nothing\"");
            assertThat(json).contains("\"text\": null");
            assertThat(json).contains("\"documents_not_found\": 1");
        }
    }

    @Nested
    @DisplayName("Fetching a page of documents")
    class FetchMany {

        /**
         * A search returns k identifiers at once, so resolving them one request at a time turns
         * one answer into k round trips.
         */
        @Test
        @DisplayName("returns text for every identifier a POST names, in the order asked")
        void returnsTextForMany() throws IOException {
            HttpExchange exchange = post("{\"document_ids\": "
                + "[\"docs:guide.md:chunk:1\", \"docs:guide.md:chunk:0\"]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json.indexOf("the second passage"))
                .as("the reply pairs text with identifiers in the order the caller asked")
                .isLessThan(json.indexOf("the first passage"));
        }

        @Test
        @DisplayName("refuses more identifiers than one request may name, and names the bound")
        void refusesTooMany() throws IOException {
            StringBuilder ids = new StringBuilder("{\"document_ids\": [");
            for (int i = 0; i < 9; i++) {
                ids.append(i > 0 ? ", " : "").append("\"id").append(i).append('"');
            }
            HttpExchange exchange = post(ids.append("]}").toString());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("8");
        }

        /**
         * Without this the bound is on how many identifiers are named rather than on how much
         * text comes back: one chunk named a thousand times returns its text a thousand times.
         */
        @Test
        @DisplayName("refuses the same identifier twice in one batch")
        void refusesDuplicateIds() throws IOException {
            HttpExchange exchange = post("{\"document_ids\": "
                + "[\"docs:guide.md:chunk:0\", \"docs:guide.md:chunk:0\"]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("refuses a POST naming no identifiers")
        void refusesAnEmptyPost() throws IOException {
            HttpExchange exchange = post("{\"document_ids\": []}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    @Nested
    @DisplayName("Method routing")
    class MethodRouting {

        @Test
        @DisplayName("refuses a method that is neither GET nor POST")
        void refusesOtherMethods() throws IOException {
            HttpExchange exchange = exchange("DELETE", "/v1/documents", "");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    private HttpExchange get(String path) {
        return exchange("GET", path, "");
    }

    private HttpExchange post(String requestBody) {
        return exchange("POST", "/v1/documents/fetch", requestBody);
    }

    private HttpExchange exchange(String method, String path, String requestBody) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestBody())
            .thenReturn(new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(body);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        return exchange;
    }
}
