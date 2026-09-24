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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit namespace creation, end to end through the routes a client calls.
 *
 * <p>With {@code excalibur.graph.require-explicit-namespaces} on, a typo in the namespace header
 * must be refused on every route that would otherwise create a tenant - and the one that does
 * create them must be reachable, guarded, and enough to make the others accept the name.</p>
 */
@DisplayName("Namespace creation routes")
class NamespaceCreationRoutesTest {

    private static final String TOKEN = "test-bearer-token";

    @TempDir
    Path root;

    private ExcaliburDBServer server;
    private GraphTenants tenants;
    private HttpClient client;
    private Path documents;

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

        documents = Files.createDirectories(root.resolve("documents"));
        tenants = new GraphTenants(root.resolve("graphs"), new Config(new HashMap<>(Map.of(
            "excalibur.graph.ram-buffer-mb", "4.0",
            "excalibur.graph.require-explicit-namespaces", "true",
            "excalibur.graph.max-namespaces", "2"))),
            MappingBudget.detect(), 4);

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .allowIngestRoot(documents)
            .build());
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
    @DisplayName("a namespace nobody created is refused, until PUT creates it")
    void explicitCreationGatesTheOtherRoutes() throws Exception {
        HttpResponse<String> typo = ingest("docs", "acmee");
        assertThat(typo.statusCode()).isEqualTo(404);
        assertThat(typo.body()).contains("\"code\": \"namespace_not_found\"");

        HttpResponse<String> build = send("POST", "/v1/graph/build", "acmee");
        assertThat(build.statusCode()).isEqualTo(404);
        assertThat(build.body()).contains("\"code\": \"namespace_not_found\"");

        assertThat(send("PUT", "/v1/graph/namespaces/acme", null).statusCode()).isEqualTo(201);
        assertThat(ingest("docs", "acme").statusCode()).isEqualTo(200);
        assertThat(ingest("other", null).statusCode())
            .as("the shared namespace needs no creating")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("creating needs a token, and the cap is answered with its own code")
    void creatingIsGuardedAndCapped() throws Exception {
        assertThat(anonymous("PUT", "/v1/graph/namespaces/acme").statusCode()).isEqualTo(401);

        assertThat(send("PUT", "/v1/graph/namespaces/one", null).statusCode()).isEqualTo(201);
        assertThat(send("PUT", "/v1/graph/namespaces/two", null).statusCode()).isEqualTo(201);
        HttpResponse<String> third = send("PUT", "/v1/graph/namespaces/three", null);

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.body()).contains("\"code\": \"namespace_limit_reached\"");
    }

    @Test
    @DisplayName("asserting facts into a namespace nobody created is refused, and writes nothing")
    void assertingFactsNeedsACreatedNamespace() throws Exception {
        String fact = "{\"subject\": \"Ada Lovelace\", \"relation\": \"worked_with\", "
            + "\"object\": \"Charles Babbage\"}";

        HttpResponse<String> typo = send("POST", "/v1/graph/facts", "acmee", fact);

        assertThat(typo.statusCode()).isEqualTo(404);
        assertThat(typo.body()).contains("\"code\": \"namespace_not_found\"");
        assertThat(root.resolve("graphs").resolve("acmee")).doesNotExist();

        assertThat(send("PUT", "/v1/graph/namespaces/acme", null).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/v1/graph/facts", "acme", fact).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("creating a namespace is audited as graph.namespace.create, naming it")
    void creatingIsAudited() throws Exception {
        Logger audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        audit.addAppender(events);
        try {
            assertThat(send("PUT", "/v1/graph/namespaces/acme", null).statusCode())
                .isEqualTo(201);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<ILoggingEvent> seen;
            do {
                synchronized (events) {
                    seen = List.copyOf(events.list);
                }
            } while (seen.isEmpty() && System.nanoTime() < deadline);

            assertThat(seen).singleElement().satisfies(event -> {
                Map<String, Object> pairs = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
                assertThat(pairs).containsEntry("audit_action", "graph.namespace.create")
                    .containsEntry("audit_outcome", "success")
                    .containsEntry("audit_targets", Map.of("namespace", "acme"));
            });
        } finally {
            audit.detachAppender(events);
        }
    }

    @Test
    @DisplayName("the listing pages through the query string")
    void theListingPages() throws Exception {
        send("PUT", "/v1/graph/namespaces/one", null);

        HttpResponse<String> first = send("GET", "/v1/graph/namespaces?limit=1", null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"namespace\": \"one\"", "\"total\": 2",
            "\"next_cursor\": \"one\"");
        HttpResponse<String> second =
            send("GET", "/v1/graph/namespaces?limit=1&cursor=one", null);
        assertThat(second.body()).contains("\"namespace\": \"shared\"", "\"next_cursor\": null");
    }

    private HttpResponse<String> ingest(String sourceId, String namespace) throws Exception {
        String body = "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \""
            + sourceId + "\", \"root_path\": \"" + documents + "\"}}";
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getPort() + "/v1/ingest"))
            .header("Authorization", "Bearer " + TOKEN)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10));
        if (namespace != null) {
            request.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String namespace)
            throws Exception {
        return send(method, path, namespace, null);
    }

    private HttpResponse<String> send(String method, String path, String namespace,
                                      String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .header("Authorization", "Bearer " + TOKEN)
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10));
        if (namespace != null) {
            request.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> anonymous(String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
