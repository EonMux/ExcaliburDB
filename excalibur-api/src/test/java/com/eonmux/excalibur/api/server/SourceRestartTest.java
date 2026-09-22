package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A registered source has to still be registered after the node is restarted.
 *
 * <p>Registration is what a caller does once and expects to hold: the source is re-crawled on a
 * schedule from then on. Held only in memory it lasted until the process did, so a restarted node
 * kept serving the documents it had already indexed and never looked at their source again -
 * with {@code /v1/ingest/status} reporting no sources, which reads as "none were ever
 * registered" rather than "the registrations were lost".</p>
 */
@DisplayName("Source registration across a restart")
class SourceRestartTest {

    private static final String TOKEN = "test-bearer-token";

    @TempDir
    Path dataDirectory;

    @TempDir
    Path allowedRoot;

    private ExcaliburDBServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a source registered before the restart is registered after it")
    void aRegisteredSourceSurvivesTheRestart() throws IOException {
        server = startNode();
        int status = post(server.getPort(), "/v1/ingest", """
            {"source_type": "filesystem", \
            "config": {"source_id": "project-docs", "root_path": "%s"}}""".formatted(allowedRoot.toString().replace("\\", "\\\\")));
        assertThat(status).isEqualTo(200);

        server.stop(0);
        server = startNode();

        assertThat(get(server.getPort(), "/v1/ingest/status")).contains("project-docs");
        assertThat(server.getIngestSourceRegistry().isRegistered("project-docs")).isTrue();
    }

    @Test
    @DisplayName("a deregistered source stays deregistered after it")
    void aDeregisteredSourceStaysGone() throws IOException {
        server = startNode();
        post(server.getPort(), "/v1/ingest", """
            {"source_type": "filesystem", \
            "config": {"source_id": "project-docs", "root_path": "%s"}}""".formatted(allowedRoot.toString().replace("\\", "\\\\")));
        assertThat(delete(server.getPort(), "/v1/ingest", """
            {"source_id": "project-docs"}""")).isEqualTo(200);

        server.stop(0);
        server = startNode();

        assertThat(server.getIngestSourceRegistry().isRegistered("project-docs")).isFalse();
    }

    private ExcaliburDBServer startNode() throws IOException {
        SegmentCatalog segmentCatalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        QueryPipeline queryPipeline = mock(QueryPipeline.class);
        EpochTickScheduler epochTickScheduler = mock(EpochTickScheduler.class);

        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(epochTickScheduler);
        when(epochTickScheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);

        ExcaliburDBServer node =
            new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        node.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .allowIngestRoot(allowedRoot)
            .build());
        node.withSourceCatalog(SourceCatalog.open(dataDirectory));
        node.start();
        return node;
    }

    private static int post(int port, String path, String body) throws IOException {
        return send(port, path, "POST", body).getFirst();
    }

    private static int delete(int port, String path, String body) throws IOException {
        return send(port, path, "DELETE", body).getFirst();
    }

    private static String get(int port, String path) throws IOException {
        HttpURLConnection connection = open(port, path, "GET");
        try (var stream = connection.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static java.util.List<Integer> send(int port, String path, String method, String body)
            throws IOException {
        HttpURLConnection connection = open(port, path, method);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return java.util.List.of(connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(int port, String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
            URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        return connection;
    }
}
