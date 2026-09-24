package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That every graph route a tenanted node claims to serve is actually registered.
 *
 * <p>The endpoint classes behind these routes are unit-tested on their own, which proves what
 * each answers and nothing about whether a request can reach it. Two of them were written,
 * tested and never registered, so the surface an operator was told about and the surface the
 * server had drifted apart with every test still passing. These drive the socket instead: if a
 * route is dropped from {@link EndpointRegistrar}, one of them answers 404 and says so.</p>
 */
@DisplayName("Graph routes on a tenanted node")
class TenantedGraphRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private ExcaliburDBServer server;
    private GraphTenants tenants;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        tenants = new GraphTenants(root, new Config(new HashMap<>()), MappingBudget.detect(), 4);
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphSupport(tenants);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (tenants != null) {
            tenants.close();
        }
    }

    @Test
    @DisplayName("every graph route is reachable, and none of them is a 404")
    void everyGraphRouteIsRegistered() throws IOException {
        // 404 is the one answer that means "this route does not exist". Each of these may
        // legitimately answer 200, 503 or 405 depending on what the node holds; none of them
        // may answer 404, which is what an unregistered endpoint looks like from outside.
        assertThat(send("POST", "/v1/graph/query", "{\"query\":\"Who is Ada Lovelace?\"}").status())
            .isNotEqualTo(404);
        assertThat(send("POST", "/v1/graph/build", "").status()).isNotEqualTo(404);
        assertThat(send("POST", "/v1/graph/erasure",
            "{\"scope\":\"source\",\"source_type\":\"DOCUMENT\",\"source_id\":1}")
            .status()).isNotEqualTo(404);
        assertThat(send("GET", "/v1/graph/namespaces", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an erasure request reads back by its id, and the collection still refuses GET")
    void erasureStatusIsRouted() throws IOException {
        Response accepted = send("POST", "/v1/graph/erasure",
            "{\"scope\":\"source\",\"source_type\":\"DOCUMENT\",\"source_id\":1}");
        String id = accepted.body().replaceAll("(?s).*\"request_id\": (\\d+).*", "$1");

        Response status = send("GET", "/v1/graph/erasure/" + id, null);

        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body()).contains("\"state\": \"pending\"");
        assertThat(send("GET", "/v1/graph/erasure", null).status()).isEqualTo(405);
        assertThat(send("POST", "/v1/graph/erasure/" + id, "{}").status()).isEqualTo(405);
        assertThat(send("GET", "/v1/graph/erasure/424242", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the namespace inventory names the shared namespace")
    void theInventoryNamesShared() throws IOException {
        Response response = send("GET", "/v1/graph/namespaces", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"shared\"");
    }

    @Test
    @DisplayName("the shared namespace cannot be dropped")
    void sharedCannotBeDropped() throws IOException {
        // Every unnamespaced request lands in it and the node holds it open for the life of
        // the process, so a request to remove it is a conflict rather than a permission
        // problem - and answering 404 would say it was not there.
        Response response = send("DELETE", "/v1/graph/namespaces/shared", null);

        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("dropping a namespace that does not exist is a 404")
    void unknownNamespaceIsNotFound() throws IOException {
        assertThat(send("DELETE", "/v1/graph/namespaces/never-created", null).status())
            .isEqualTo(404);
    }

    @Test
    @DisplayName("the inventory needs a token like every other read")
    void theInventoryNeedsAToken() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + "/v1/graph/namespaces")
            .toURL().openConnection();
        connection.setRequestMethod("GET");

        assertThat(connection.getResponseCode()).isEqualTo(401);
        connection.disconnect();
    }

    private record Response(int status, String body) {
    }

    private Response send(String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (body != null && !body.isEmpty()) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = stream == null
            ? ""
            : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, responseBody);
    }
}
