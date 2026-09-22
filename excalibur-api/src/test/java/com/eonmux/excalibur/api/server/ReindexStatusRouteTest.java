package com.eonmux.excalibur.api.server;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That {@code GET /v1/reindex/status} is reachable on a running server.
 *
 * <p>It shares a prefix with {@code /v1/reindex}, which answers 405 to anything but POST. The
 * JDK's HTTP server dispatches on the longest matching context, so the status route wins - but
 * that is a property of the server, not of this code, and a unit test of the handler would pass
 * just as happily if the route were shadowed and every real request got a 405. This drives the
 * actual socket.</p>
 */
@DisplayName("GET /v1/reindex/status route")
class ReindexStatusRouteTest {

    private static final String TOKEN = "test-bearer-token";

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

    @Test
    @DisplayName("is served rather than shadowed by the /v1/reindex context")
    void isServedRatherThanShadowed() throws IOException {
        Response response = get("/v1/reindex/status", TOKEN);

        assertThat(response.status())
            .as("405 here means the /v1/reindex POST-only handler answered instead")
            .isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"sources\": []}");
    }

    @Test
    @DisplayName("is charged against the read budget, so polling cannot starve writes")
    void requiresAToken() throws IOException {
        assertThat(get("/v1/reindex/status", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a source nobody has reindexed is 404, and POST is still the only way in")
    void unknownSourceAndWrongMethod() throws IOException {
        assertThat(get("/v1/reindex/status?source_id=nothing", TOKEN).status()).isEqualTo(404);
        assertThat(get("/v1/reindex", TOKEN).status())
            .as("the mutating route keeps refusing GET")
            .isEqualTo(405);
    }

    private record Response(int status, String body) {
    }

    private Response get(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod("GET");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }
}
