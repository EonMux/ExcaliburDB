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
 * That a path is served only by the route it names.
 *
 * <p>The JDK's HTTP server dispatches on the longest <em>prefix</em>, not on equality, so a
 * context registered at {@code /v1/stats} also receives {@code /v1/statsfoo} and every other
 * path beginning with those characters. Left alone, a client with a typo in its URL is answered
 * as though it had none: {@code GET /healthz} returns the health document, and a caller
 * integrating against a path this server does not have is told nothing. The documented contract
 * is that anything else answers 404, and these tests drive the socket to hold it to that.</p>
 */
@DisplayName("Route path exactness")
class RoutePathExactnessTest {

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
    @DisplayName("a path that merely starts with a route's name is not that route")
    void aPrefixIsNotTheRoute() throws IOException {
        assertThat(send("GET", "/healthz", TOKEN).status())
            .as("a typo in a liveness probe's URL must not read as healthy")
            .isEqualTo(404);
        assertThat(send("GET", "/v1/statsfoo", TOKEN).status()).isEqualTo(404);
        assertThat(send("POST", "/v1/queryfoo", TOKEN).status()).isEqualTo(404);
        assertThat(send("POST", "/v1/ingestion", TOKEN).status()).isEqualTo(404);
        assertThat(send("GET", "/v1/reindex/statuses", TOKEN).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the routes themselves still answer")
    void theRoutesThemselvesStillAnswer() throws IOException {
        assertThat(send("GET", "/health", TOKEN).status()).isEqualTo(200);
        assertThat(send("GET", "/v1/reindex/status", TOKEN).status()).isEqualTo(200);
        assertThat(send("GET", "/v1/ingest/status", TOKEN).status()).isEqualTo(200);
        assertThat(send("GET", "/v1/stats", TOKEN).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a trailing slash is the same path")
    void aTrailingSlashIsTheSamePath() throws IOException {
        assertThat(send("GET", "/health/", TOKEN).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("cancellation still reaches its query id under the query route")
    void cancellationKeepsItsSubPath() throws IOException {
        // DELETE /v1/query/{id} carries something after the context, so exactness must not
        // close it. An id nobody registered is the handler's 404, whose code says so - a
        // different answer from the route not existing. The collection itself serves POST only,
        // so a DELETE with no id is a method it does not serve, and deeper paths are not routes.
        Response unknown = send("DELETE", "/v1/query/unknown-id", TOKEN);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).contains("\"code\": \"query_not_found\"");
        assertThat(send("DELETE", "/v1/query", TOKEN).status()).isEqualTo(405);
        Response deeper = send("DELETE", "/v1/query/unknown-id/more", TOKEN);
        assertThat(deeper.status()).isEqualTo(404);
        assertThat(deeper.body()).contains("\"code\": \"not_found\"");
    }

    @Test
    @DisplayName("/health serves the method the docs give it, and refuses the rest")
    void healthServesOnlyGet() throws IOException {
        // Every other route answers 405 to a method it does not serve. This one answered 200
        // to all of them, so a client could POST to it forever and read that as agreement.
        assertThat(send("GET", "/health", TOKEN).status()).isEqualTo(200);
        assertThat(send("POST", "/health", TOKEN).status()).isEqualTo(405);
        assertThat(send("DELETE", "/health", TOKEN).status()).isEqualTo(405);
    }

    @Test
    @DisplayName("the batch read serves the method the docs give it, and refuses the rest")
    void documentFetchServesOnlyPost() throws IOException {
        // The endpoint behind this path also understands GET, because it is the same object
        // serving GET /v1/documents. Left routed by path alone it published a second,
        // undocumented way to read one chunk, on the route whose whole reason to exist is
        // reading several - and whose own 405 message names the other one.
        assertThat(send("GET", "/v1/documents/fetch", TOKEN).status()).isEqualTo(405);
        assertThat(send("DELETE", "/v1/documents/fetch", TOKEN).status()).isEqualTo(405);
        assertThat(send("POST", "/v1/documents/fetch", TOKEN).status())
            .as("a POST with no body is the handler's 400, which proves it was reached")
            .isEqualTo(400);
    }

    private record Response(int status, String body) {
    }

    private Response send(String method, String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod(method);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
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
