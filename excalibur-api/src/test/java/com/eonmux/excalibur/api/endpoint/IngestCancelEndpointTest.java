package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DELETE /v1/ingest/run} - stops a crawl and leaves the source registered.
 *
 * <p>Stopping a run and cancelling a registration were one operation. An operator whose crawl is
 * hammering a site, or has been running for hours against a root that turned out to be far
 * bigger than expected, had one lever: {@code DELETE /v1/ingest}, which also takes the source out
 * of the catalog, out of the status registry and out of the re-crawl schedule. Getting it back
 * afterwards means re-registering it, which starts the same crawl again from the beginning.</p>
 */
@DisplayName("DELETE /v1/ingest/run")
class IngestCancelEndpointTest {

    private RecordingRegistry registry;
    private IngestCancelEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new RecordingRegistry();
        endpoint = new IngestCancelEndpoint(registry);
    }

    private String delete(String body) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        endpoint.handle(exchange("DELETE", body, response));
        return response.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null registry")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> new IngestCancelEndpoint(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Stopping a run")
    class Stopping {

        @Test
        @DisplayName("stops the run and keeps the registration")
        void stopsWithoutDeregistering() throws IOException {
            registry.add("busy-crawl");
            registry.running("busy-crawl");

            String json = delete("{\"source_id\": \"busy-crawl\"}");

            assertThat(json).contains("\"stopped\": true");
            assertThat(json)
                .as("the whole point is that the source survives the stop")
                .contains("\"still_registered\": true");
            assertThat(registry.isRegistered("busy-crawl")).isTrue();
            assertThat(registry.stopped()).containsExactly("busy-crawl");
        }

        @Test
        @DisplayName("says so plainly when nothing was running")
        void nothingRunningIsAnAnswer() throws IOException {
            registry.add("idle-source");

            String json = delete("{\"source_id\": \"idle-source\"}");

            assertThat(json)
                .as("the state the caller was asking for is the state it now has, which is "
                    + "an answer and not an error")
                .contains("\"stopped\": false");
            assertThat(registry.isRegistered("idle-source")).isTrue();
        }

        @Test
        @DisplayName("an unregistered source is 404, not a quiet success")
        void unknownSourceIs404() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = exchange("DELETE",
                "{\"source_id\": \"never-registered\"}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }
    }

    @Nested
    @DisplayName("Method and argument handling")
    class Refusals {

        @Test
        @DisplayName("refuses anything but DELETE")
        void refusesOtherMethods() throws IOException {
            HttpExchange exchange = exchange("POST", "{}", new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("requires a source id")
        void requiresASourceId() throws IOException {
            HttpExchange exchange = exchange("DELETE", "{}", new ByteArrayOutputStream());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    private HttpExchange exchange(String method, String body, ByteArrayOutputStream response)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/ingest/run"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(response);
        return exchange;
    }

    /** A registry that records what was asked of it and removes nothing by surprise. */
    private static final class RecordingRegistry implements SourceRegistry {

        private final Set<String> sources = new HashSet<>();
        private final Set<String> inFlight = new HashSet<>();
        private final Set<String> stopped = new HashSet<>();

        void add(String sourceId) {
            sources.add(sourceId);
        }

        void running(String sourceId) {
            inFlight.add(sourceId);
        }

        Set<String> stopped() {
            return stopped;
        }

        @Override
        public void register(Source source) {
            sources.add(source.getSourceId());
        }

        @Override
        public void replace(Source source, SourceDescriptor descriptor) {
            throw new AssertionError("stopping a run must not replace the source");
        }

        @Override
        public void replace(Source source, SourceDescriptor descriptor, Admission admission) {
            throw new AssertionError("stopping a run must not replace the source");
        }

        @Override
        public boolean isRegistered(String sourceId) {
            return sources.contains(sourceId);
        }

        @Override
        public Source get(String sourceId) {
            return null;
        }

        @Override
        public boolean remove(String sourceId) {
            throw new AssertionError("stopping a run must not deregister the source");
        }

        @Override
        public boolean stopIngestion(String sourceId) {
            stopped.add(sourceId);
            return inFlight.remove(sourceId);
        }

        @Override
        public Set<String> getAllSourceIds() {
            return Set.copyOf(sources);
        }
    }
}
