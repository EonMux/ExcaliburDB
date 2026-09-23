package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That a route which cannot honour a namespace says so instead of serving the shared one.
 *
 * <p>{@value GraphTenants#NAMESPACE_HEADER} scopes a request to one tenant's graph. The corpus
 * routes are not namespaced - one node holds one corpus - and they used to read the header,
 * ignore it, and answer 200 from the shared index. That is the worst shape a tenancy mistake can
 * take: a caller that asked for tenant A is handed tenant-neutral data and has no way to tell,
 * so an isolation bug looks exactly like a working request. Refusing is the only answer that
 * cannot be mistaken for isolation.</p>
 */
@DisplayName("Namespace header on routes that cannot honour it")
class NamespaceHeaderRouteTest {

    private static final String TOKEN = "test-bearer-token";
    private static final String OTHER_TENANT = "tenant-a";

    private ExcaliburDBServer server;

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

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // /v1/erasure is the other route in this set and is registered only on a node built with
    // an erasure service, which this harness has none of; it is covered where that service
    // exists, in ExcaliburDBServerErasureRouteTest.
    @Test
    @DisplayName("a corpus route refuses a request that names another namespace")
    void corpusRoutesRefuseANamedNamespace() throws IOException {
        List<String[]> routes = List.of(
            new String[] {"POST", "/v1/query"},
            new String[] {"GET", "/v1/stats"},
            new String[] {"GET", "/v1/documents"},
            new String[] {"POST", "/v1/documents/fetch"},
            new String[] {"POST", "/v1/reindex"},
            new String[] {"GET", "/v1/reindex/status"},
            new String[] {"POST", "/v1/compact"},
            new String[] {"DELETE", "/v1/compact"},
            new String[] {"GET", "/v1/compact/status"});

        for (String[] route : routes) {
            Response response = send(route[0], route[1], OTHER_TENANT);
            assertThat(response.status())
                .as("%s %s served a namespace it does not have", route[0], route[1])
                .isEqualTo(400);
            assertThat(response.body())
                .as("%s %s should name the header it refused", route[0], route[1])
                .contains(GraphTenants.NAMESPACE_HEADER);
        }
    }

    @Test
    @DisplayName("naming the shared namespace is not a mistake")
    void namingTheSharedNamespaceIsAccepted() throws IOException {
        // The shared index is what these routes serve, so a caller that names it has been
        // told the truth. Refusing it would break a client that sets the header on every
        // request it makes.
        assertThat(send("GET", "/v1/stats", "shared").status()).isEqualTo(200);
        assertThat(send("GET", "/v1/reindex/status", "shared").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a probe carrying the header is still a probe")
    void probesAreUnaffected() throws IOException {
        // Liveness and readiness describe the process, not a tenant's data, and a probe that
        // started failing because a sidecar added a header would take the node out of rotation
        // for a reason that has nothing to do with its health.
        assertThat(send("GET", "/health", OTHER_TENANT).status()).isEqualTo(200);
        assertThat(send("GET", "/health/ready", OTHER_TENANT).status()).isNotEqualTo(400);
    }

    @Test
    @DisplayName("no header at all is the shared index, as it always was")
    void noHeaderIsUnchanged() throws IOException {
        assertThat(send("GET", "/v1/stats", null).status()).isEqualTo(200);
    }

    private record Response(int status, String body) {
    }

    private Response send(String method, String path, String namespace) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (namespace != null) {
            connection.setRequestProperty(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }
}
