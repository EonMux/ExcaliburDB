package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.ingest.source.Source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code GET /v1/ingest/status} actually puts on the wire.
 *
 * <p>The existing tests cover the status object; these cover the handler, and specifically the
 * property that the body is always parseable JSON. That is not a given here, because the body is
 * assembled by {@code String.format} rather than by a serialiser: the numbers are formatted
 * under a locale, the strings are interpolated into quotes, and a value that escapes either of
 * those breaks the whole response rather than one field. A client cannot recover from a body it
 * cannot parse, so every field that can carry arbitrary text or an unusual number is round-tripped
 * through a real parser below.</p>
 */
@DisplayName("Ingest status endpoint")
class IngestStatusEndpointHandlerTest {

    /** One captured HTTP response. */
    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private static Response call(IngestStatusEndpoint endpoint, String method, String uri)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(uri));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), body.toString(StandardCharsets.UTF_8));
    }

    private static IngestStatusEndpoint.InMemoryIngestionStatusRegistry registryWith(String... sourceIds) {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        for (String sourceId : sourceIds) {
            registry.getOrCreate(sourceId);
        }
        return registry;
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("a POST is refused")
        void onlyGetIsAllowed() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith()),
                "POST", "/v1/ingest/status");

            assertThat(response.status()).isEqualTo(405);
            assertThat(Json.stringAt(response.json(), "error")).isEqualTo("Method Not Allowed");
        }

        @Test
        @DisplayName("a lowercase get is still a GET")
        void methodMatchIsCaseInsensitive() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith()),
                "get", "/v1/ingest/status");

            assertThat(response.status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a source id longer than one could be registered under is refused")
        void oversizedSourceIdIsRefused() throws IOException {
            String sourceId = "x".repeat(Source.MAX_ID_LENGTH + 1);

            Response response = call(new IngestStatusEndpoint(registryWith("known")),
                "GET", "/v1/ingest/status?source_id=" + sourceId);

            assertThat(response.status())
                .as("registration bounds the id, so no source is under one this long")
                .isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error"))
                .contains("source_id")
                .contains(String.valueOf(Source.MAX_ID_LENGTH))
                .doesNotContain(sourceId);
        }

        @Test
        @DisplayName("an unknown source is a 404, not an empty success")
        void unknownSourceIsNotFound() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith("known")),
                "GET", "/v1/ingest/status?source_id=missing");

            assertThat(response.status()).isEqualTo(404);
            assertThat(Json.stringAt(response.json(), "error")).contains("missing");
        }
    }

    @Nested
    @DisplayName("body shape")
    class BodyShape {

        @Test
        @DisplayName("the status says when the source last did anything")
        void theStatusCarriesATime() throws IOException {
            long before = System.currentTimeMillis();
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith();
            registry.getOrCreate("my-docs").update("crawling", 0.5, 10, 10);

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=my-docs");

            Object timestamp = Json.at(response.json(), "last_updated_ms");
            assertThat(timestamp)
                .as("an idle source with no time on it cannot be told from one crawled "
                    + "a minute ago, so 'is the index following the source?' is unanswerable")
                .isNotNull();
            assertThat(((Number) timestamp).longValue())
                .isGreaterThanOrEqualTo(before)
                .isLessThanOrEqualTo(System.currentTimeMillis());
        }

        @Test
        @DisplayName("with no source_id every source is listed")
        void allSourcesAreListed() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("a", "b", "c");

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status");

            assertThat(response.status()).isEqualTo(200);
            Object sources = Json.at(response.json(), "sources");
            assertThat(sources).isInstanceOf(List.class);
            assertThat((List<?>) sources).hasSize(3);
        }

        @Test
        @DisplayName("an empty registry still returns a well-formed empty list")
        void emptyRegistryIsStillJson() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith()),
                "GET", "/v1/ingest/status");

            assertThat(response.status()).isEqualTo(200);
            assertThat((List<?>) Json.at(response.json(), "sources")).isEmpty();
        }

        @Test
        @DisplayName("a single source is returned as one object, not a list")
        void singleSourceIsAnObject() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("docs");
            registry.getStatus("docs").update("embedding", 0.5, 10, 20);

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=docs");

            Object json = response.json();
            assertThat(Json.stringAt(json, "source_id")).isEqualTo("docs");
            assertThat(Json.stringAt(json, "state")).isEqualTo("embedding");
            assertThat(Json.at(json, "entries_ingested")).isEqualTo(10L);
            assertThat(Json.at(json, "entries_pending")).isEqualTo(20L);
            assertThat(Json.at(json, "entries_skipped")).isEqualTo(0L);
            assertThat(Json.at(json, "last_error")).isNull();
        }

        @Test
        @DisplayName("the files a run passed over as not text are counted, without being errors")
        void skippedIsCountedAndIsNotAnError() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("docs");
            IngestStatusEndpoint.IngestionStatus status = registry.getStatus("docs");
            status.update("idle", 1.0, 120, 0);
            status.setSkipped(500);

            Object json = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=docs").json();

            assertThat(Json.at(json, "entries_skipped")).isEqualTo(500L);
            assertThat(Json.at(json, "last_error")).isNull();
        }

        @Test
        @DisplayName("pages robots.txt kept out are counted apart from skipped, without errors")
        void disallowedIsCountedApart() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("site");
            IngestStatusEndpoint.IngestionStatus status = registry.getStatus("site");
            status.update("idle", 1.0, 80, 0);
            status.setSkipped(3);
            status.setDisallowed(37);

            Object json = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=site").json();

            assertThat(Json.at(json, "entries_disallowed")).isEqualTo(37L);
            assertThat(Json.at(json, "entries_skipped")).isEqualTo(3L);
            assertThat(Json.at(json, "last_error")).isNull();
        }

        @Test
        @DisplayName("a negative disallowed count is reported as none, and a reset clears it")
        void disallowedIsNeverNegativeAndResets() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("site");
            IngestStatusEndpoint.IngestionStatus status = registry.getStatus("site");
            IngestStatusEndpoint endpoint = new IngestStatusEndpoint(registry);

            status.setDisallowed(-2);
            assertThat(Json.at(call(endpoint, "GET", "/v1/ingest/status?source_id=site").json(),
                "entries_disallowed")).isEqualTo(0L);

            status.setDisallowed(5);
            status.setUnconfigured("no pipeline", 1);
            assertThat(Json.at(call(endpoint, "GET", "/v1/ingest/status?source_id=site").json(),
                "entries_disallowed")).isEqualTo(0L);
        }

        @Test
        @DisplayName("a negative skipped count is reported as none")
        void negativeSkippedIsZero() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("docs");
            registry.getStatus("docs").setSkipped(-7);

            Object json = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=docs").json();

            assertThat(Json.at(json, "entries_skipped")).isEqualTo(0L);
        }

        @Test
        @DisplayName("every state a source can be in serialises")
        void everyStateSerialises() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("s");
            IngestStatusEndpoint.IngestionStatus status = registry.getStatus("s");
            IngestStatusEndpoint endpoint = new IngestStatusEndpoint(registry);

            status.setError("fetch failed");
            assertThat(Json.stringAt(
                call(endpoint, "GET", "/v1/ingest/status?source_id=s").json(), "last_error"))
                .isEqualTo("fetch failed");

            status.setUnconfigured("no pipeline", 42);
            Object unconfigured = call(endpoint, "GET", "/v1/ingest/status?source_id=s").json();
            assertThat(Json.stringAt(unconfigured, "state"))
                .isEqualTo(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED);
            assertThat(Json.at(unconfigured, "entries_pending")).isEqualTo(42L);
            assertThat(Json.at(unconfigured, "entries_ingested")).isEqualTo(0L);
            assertThat(Json.at(unconfigured, "entries_skipped")).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("the body stays parseable")
    class BodyStaysParseable {

        @Test
        @DisplayName("an error message full of quotes and backslashes does not break the body")
        void awkwardErrorTextIsEscaped() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("s");
            String message = "path \"C:\\docs\" is\nnot readable\ttry again";
            registry.getStatus("s").setError(message);

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=s");

            assertThat(Json.stringAt(response.json(), "last_error")).isEqualTo(message);
        }

        @Test
        @DisplayName("a source id full of quotes does not break the body")
        void awkwardSourceIdIsEscaped() throws IOException {
            String sourceId = "wei\"rd\\source";
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith(sourceId);

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status");

            List<?> sources = (List<?>) Json.at(response.json(), "sources");
            assertThat(Json.stringAt(sources.get(0), "source_id")).isEqualTo(sourceId);
        }

        @Test
        @DisplayName("a NaN progress does not put a bare NaN in the body")
        void nanProgressIsNotEmitted() throws IOException {
            // Math.max(0.0, Math.min(1.0, NaN)) is NaN - every comparison against NaN is false,
            // so the arithmetic clamp passes it straight through. Formatted with %.4f that
            // yields a bare NaN token, which is not JSON: the client loses the entire response,
            // not one field.
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("s");
            registry.getStatus("s").update("embedding", Double.NaN, 1, 1);

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=s");

            assertThat(response.body()).doesNotContain("NaN");
            assertThat(Json.at(response.json(), "progress")).isEqualTo(0.0d);
        }

        @Test
        @DisplayName("an infinite progress clamps rather than printing Infinity")
        void infiniteProgressIsClamped() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("s");
            IngestStatusEndpoint.IngestionStatus status = registry.getStatus("s");
            IngestStatusEndpoint endpoint = new IngestStatusEndpoint(registry);

            status.update("embedding", Double.POSITIVE_INFINITY, 1, 1);
            Response high = call(endpoint, "GET", "/v1/ingest/status?source_id=s");
            assertThat(high.body()).doesNotContain("Infinity");
            assertThat(Json.at(high.json(), "progress")).isEqualTo(1.0d);

            status.update("embedding", Double.NEGATIVE_INFINITY, 1, 1);
            Response low = call(endpoint, "GET", "/v1/ingest/status?source_id=s");
            assertThat(low.body()).doesNotContain("Infinity");
            assertThat(Json.at(low.json(), "progress")).isEqualTo(0.0d);
        }

        @Test
        @DisplayName("progress is written with a dot whatever the default locale is")
        void progressUsesADecimalPoint() throws IOException {
            // Under a locale whose decimal separator is a comma, "0,7300" ends the JSON value
            // early and the rest of the object becomes garbage.
            java.util.Locale original = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY);
                IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("s");
                registry.getStatus("s").update("embedding", 0.73, 1, 1);

                Response response = call(new IngestStatusEndpoint(registry),
                    "GET", "/v1/ingest/status?source_id=s");

                assertThat(response.body()).contains("0.7300");
                assertThat(Json.at(response.json(), "progress")).isEqualTo(0.73d);
            } finally {
                java.util.Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("query parsing")
    class QueryParsing {

        @Test
        @DisplayName("a url-encoded source id is decoded before lookup")
        void encodedSourceIdIsDecoded() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("my docs/a&b");

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?source_id=my+docs%2Fa%26b");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.stringAt(response.json(), "source_id")).isEqualTo("my docs/a&b");
        }

        @Test
        @DisplayName("other parameters are ignored rather than confused for the source id")
        void otherParametersAreIgnored() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("docs");

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?verbose=true&source_id=docs&limit=10");

            assertThat(Json.stringAt(response.json(), "source_id")).isEqualTo("docs");
        }

        @Test
        @DisplayName("a parameter whose name merely ends in source_id is not matched")
        void prefixedParameterNamesDoNotMatch() throws IOException {
            IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry = registryWith("docs");

            Response response = call(new IngestStatusEndpoint(registry),
                "GET", "/v1/ingest/status?not_source_id=docs");

            assertThat((List<?>) Json.at(response.json(), "sources"))
                .as("no source_id was given, so every source is listed")
                .hasSize(1);
        }

        @Test
        @DisplayName("a bare source_id with no value looks up the empty id")
        void bareParameterIsAnEmptyValue() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith("docs")),
                "GET", "/v1/ingest/status?source_id");

            assertThat(response.status())
                .as("the empty id is a lookup that misses, not a request for everything")
                .isEqualTo(404);
        }

        @Test
        @DisplayName("an empty query string lists everything")
        void emptyQueryListsEverything() throws IOException {
            Response response = call(new IngestStatusEndpoint(registryWith("docs")),
                "GET", "/v1/ingest/status?");

            assertThat((List<?>) Json.at(response.json(), "sources")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("the no-argument constructor brings its own registry")
        void defaultConstructorWorks() throws IOException {
            Response response = call(new IngestStatusEndpoint(), "GET", "/v1/ingest/status");

            assertThat(response.status()).isEqualTo(200);
            assertThat((List<?>) Json.at(response.json(), "sources")).isEmpty();
        }

        @Test
        @DisplayName("a null registry is refused at construction")
        void nullRegistryIsRefused() {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new IngestStatusEndpoint(null)))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
