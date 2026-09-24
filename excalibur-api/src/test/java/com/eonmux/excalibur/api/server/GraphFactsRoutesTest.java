package com.eonmux.excalibur.api.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asserting and retracting facts through the routes a client calls, the real router included.
 *
 * <p>The endpoint's own tests hand it an exchange directly, so they cannot see which methods the
 * router lets through to it. Retracting by subject, relation and object is a {@code DELETE} of
 * the collection, and it is only reachable if the collection's method table serves it.</p>
 */
@DisplayName("Graph facts routes")
class GraphFactsRoutesTest {

    private static final String TOKEN = "test-bearer-token";

    private static final String FACT = "{\"subject\": \"Ada Lovelace\", \"relation\": "
        + "\"worked with\", \"object\": \"Charles Babbage\"}";

    private static final String TRIPLE = "/v1/graph/facts?subject=Ada%20Lovelace"
        + "&relation=worked_with&object=Charles%20Babbage";

    @TempDir
    Path root;

    private ExcaliburDBServer server;
    private GraphTenants tenants;
    private HttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        tenants = new GraphTenants(root.resolve("graphs"), new Config(new HashMap<>(Map.of(
            "excalibur.graph.ram-buffer-mb", "4.0"))), MappingBudget.detect(), 4);
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphSupport(tenants);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (tenants != null) {
            tenants.close();
        }
    }

    @Test
    @DisplayName("a DELETE of the collection retracts every asserted fact stating the triple")
    void retractingByTripleIsRouted() throws Exception {
        assertThat(send("POST", "/v1/graph/facts", FACT).statusCode()).isEqualTo(201);

        HttpResponse<String> retracted = send("DELETE", TRIPLE, null);

        assertThat(retracted.statusCode()).isEqualTo(202);
        assertThat(retracted.body()).contains("\"accepted\":true", "\"fact_ids\":[");
    }

    @Test
    @DisplayName("a triple nothing states is a 404 through the router too")
    void anUnknownTripleIsNotFound() throws Exception {
        HttpResponse<String> response = send("DELETE", TRIPLE, null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\": \"fact_not_found\"");
    }

    @Test
    @DisplayName("retracting by triple needs a token")
    void retractingByTripleIsGuarded() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getPort() + TRIPLE))
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a retraction by triple is audited as graph.facts.retract, without the triple")
    void retractingByTripleIsAudited() throws Exception {
        assertThat(send("POST", "/v1/graph/facts", FACT).statusCode()).isEqualTo(201);
        Logger audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        audit.addAppender(events);
        try {
            assertThat(send("DELETE", TRIPLE, null).statusCode()).isEqualTo(202);

            // The assertion's own audit event is written once its response has gone, so it can
            // land after the appender is attached; only the retraction's events are read.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<ILoggingEvent> seen;
            do {
                synchronized (events) {
                    seen = events.list.stream()
                        .filter(event -> event.getKeyValuePairs().stream().anyMatch(pair ->
                            "audit_action".equals(pair.key)
                                && "graph.facts.retract".equals(pair.value)))
                        .toList();
                }
            } while (seen.isEmpty() && System.nanoTime() < deadline);

            assertThat(seen).singleElement().satisfies(event -> {
                Map<String, Object> pairs = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
                assertThat(pairs).containsEntry("audit_action", "graph.facts.retract")
                    .containsEntry("audit_outcome", "accepted")
                    .containsEntry("http_path", "/v1/graph/facts");
                assertThat(pairs.get("audit_targets")).asInstanceOf(
                        InstanceOfAssertFactories.MAP)
                    .containsEntry("namespace", "shared")
                    .containsEntry("retract_by", "triple")
                    .containsKeys("fact_ids", "request_ids");
                assertThat(event.getFormattedMessage()).doesNotContain("Lovelace", "Babbage");
            });
        } finally {
            audit.detachAppender(events);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .header("Authorization", "Bearer " + TOKEN)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
