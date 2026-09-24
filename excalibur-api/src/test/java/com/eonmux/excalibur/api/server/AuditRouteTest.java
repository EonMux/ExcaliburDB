package com.eonmux.excalibur.api.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The audit trail as a running node writes it: registered on the mutating routes, outside the
 * guard, and nowhere else.
 */
@DisplayName("Audit routes")
class AuditRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private ExcaliburDBServer server;
    private ListAppender<ILoggingEvent> events;
    private Logger audit;

    @BeforeEach
    void setUp() throws IOException {
        audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        events = new ListAppender<>();
        events.start();
        audit.addAppender(events);

        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getCatalog()).thenReturn(catalog);
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    @AfterEach
    void tearDown() {
        audit.detachAppender(events);
        if (server != null) {
            server.stop(0);
        }
    }

    private record Answer(int status, String requestId) {
    }

    private Answer call(String method, String path, String body, String token,
                        String requestId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path).toURL().openConnection();
        connection.setRequestMethod(method);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (requestId != null) {
            connection.setRequestProperty(AuditLog.REQUEST_ID_HEADER, requestId);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        String id = connection.getHeaderField(AuditLog.REQUEST_ID_HEADER);
        connection.disconnect();
        return new Answer(status, id);
    }

    /**
     * The events recorded so far, once at least {@code expected} have arrived.
     *
     * <p>An event is written when the route returns, which can be after the caller has read
     * its response, so reading the appender straight after a call races the write.</p>
     */
    private List<Map<String, Object>> recorded(int expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        List<ILoggingEvent> seen;
        while (true) {
            // The appender adds under its own monitor; copying under it sees a whole list.
            synchronized (events) {
                seen = List.copyOf(events.list);
            }
            if (seen.size() >= expected || System.nanoTime() > deadline) {
                break;
            }
            Thread.onSpinWait();
        }
        return seen.stream()
            .map(event -> event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)))
            .toList();
    }

    @Test
    @DisplayName("a drain and an undrain are each recorded, under the caller's request id")
    void drainIsAudited() throws IOException {
        Answer drained = call("POST", "/v1/drain", null, TOKEN, "op-42");
        // Waited for before the next call, so the two events land in the order they were made.
        recorded(1);
        call("DELETE", "/v1/drain", null, TOKEN, null);

        assertThat(drained.status()).isEqualTo(200);
        assertThat(drained.requestId()).isEqualTo("op-42");
        List<Map<String, Object>> recorded = recorded(2);
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0)).containsEntry("audit_action", "node.drain")
            .containsEntry("audit_outcome", "success")
            .containsEntry("request_id", "op-42")
            .containsEntry("client_address", "127.0.0.1");
        assertThat(recorded.get(1)).containsEntry("audit_action", "node.undrain");
    }

    @Test
    @DisplayName("each request on a route is recorded under its own id and its own targets")
    void requestsOnOneRouteShareNothing() throws IOException {
        // The JDK keeps an exchange's attributes on its route, shared by every request to it;
        // what one request settled there must not be what the next is recorded under.
        Answer first = call("DELETE", "/v1/ingest", "{\"source_id\": \"docs\"}", TOKEN, "a-1");
        recorded(1);
        Answer second = call("DELETE", "/v1/ingest", "{\"source_id\": \"notes\"}", TOKEN,
            "b-2");
        recorded(2);
        Answer third = call("DELETE", "/v1/ingest", "{}", TOKEN, null);

        assertThat(first.requestId()).isEqualTo("a-1");
        assertThat(second.requestId()).isEqualTo("b-2");
        assertThat(third.requestId()).isNotNull().isNotIn("a-1", "b-2");
        List<Map<String, Object>> recorded = recorded(3);
        assertThat(recorded).hasSize(3);
        assertThat(recorded.get(0)).containsEntry("request_id", "a-1")
            .containsEntry("audit_targets", Map.of("source_id", "docs"));
        assertThat(recorded.get(1)).containsEntry("request_id", "b-2")
            .containsEntry("audit_targets", Map.of("source_id", "notes"));
        assertThat(recorded.get(2)).containsEntry("request_id", third.requestId())
            .containsEntry("audit_targets", Map.of());
    }

    @Test
    @DisplayName("an attempt without a token is recorded as unauthenticated")
    void refusedAttemptsAreAudited() throws IOException {
        Answer refused = call("POST", "/v1/drain", null, null, null);

        assertThat(refused.status()).isEqualTo(401);
        assertThat(recorded(1)).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("audit_action", "node.drain")
            .containsEntry("audit_outcome", "unauthenticated"));
    }

    @Test
    @DisplayName("a deregistration names the source it was about")
    void deregistrationNamesItsSource() throws IOException {
        Answer missing = call("DELETE", "/v1/ingest", "{\"source_id\": \"docs\"}", TOKEN, null);

        assertThat(missing.status()).isEqualTo(404);
        assertThat(recorded(1)).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("audit_action", "source.deregister")
            .containsEntry("audit_outcome", "rejected")
            .containsEntry("audit_targets", Map.of("source_id", "docs")));
    }

    @Test
    @DisplayName("a replacement of a source's configuration is recorded as an update of it")
    void replacementIsAudited() throws IOException {
        Answer refused = call("PUT", "/v1/ingest", "{\"source_type\": \"filesystem\", "
            + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"/nowhere\"}}", TOKEN, null);

        assertThat(refused.status()).as("no root is allow-listed on this node").isEqualTo(403);
        assertThat(recorded(1)).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("audit_action", "source.update")
            .containsEntry("audit_targets", Map.of("source_id", "docs",
                "source_type", "filesystem", "namespace", "shared")));
    }

    @Test
    @DisplayName("reads are not audited, and a method a path does not serve is not either")
    void readsAreNotAudited() throws IOException {
        call("GET", "/v1/stats", null, TOKEN, null);
        call("GET", "/v1/drain", null, TOKEN, null);
        // An audited call after them: once its event is in, any event the reads wrote would be too.
        call("DELETE", "/v1/drain", null, TOKEN, null);

        assertThat(recorded(1)).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("audit_action", "node.undrain"));
    }
}
