package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
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

    @Test
    @DisplayName("a source's last crawl is reported after the restart as it was before it")
    void theLastCrawlSurvivesTheRestart() throws Exception {
        // No ingestion pipeline on this node, so the crawl that registration starts ends in
        // 'unconfigured' with what it found counted as pending: a finished run with counts, an
        // error and a time, which is everything the readback has to carry across.
        java.nio.file.Files.writeString(allowedRoot.resolve("a.md"), "# a");
        java.nio.file.Files.writeString(allowedRoot.resolve("b.md"), "# b");
        server = startNode();
        post(server.getPort(), "/v1/ingest", """
            {"source_type": "filesystem", \
            "config": {"source_id": "project-docs", "root_path": "%s"}}""".formatted(allowedRoot.toString().replace("\\", "\\\\")));
        String before = awaitStatus(server.getPort(), "\"state\": \"unconfigured\"");

        server.stop(0);
        server = startNode();

        assertThat(get(server.getPort(), "/v1/ingest/status?source_id=project-docs"))
            .as("the restart does not reset the counts, the error or the time")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a replaced configuration keeps the source's history and is what the restart restores")
    void aReplacedConfigurationSurvivesTheRestart() throws Exception {
        java.nio.file.Files.writeString(allowedRoot.resolve("a.md"), "# a");
        Path narrower = java.nio.file.Files.createDirectory(allowedRoot.resolve("narrower"));
        server = startNode();
        post(server.getPort(), "/v1/ingest", """
            {"source_type": "filesystem", \
            "config": {"source_id": "project-docs", "root_path": "%s"}}""".formatted(allowedRoot.toString().replace("\\", "\\\\")));
        String before = awaitStatus(server.getPort(), "\"state\": \"unconfigured\"");

        int replaced = send(server.getPort(), "/v1/ingest", "PUT", """
            {"source_type": "filesystem", \
            "config": {"source_id": "project-docs", "root_path": "%s"}}""".formatted(narrower.toString().replace("\\", "\\\\")))
            .getFirst();

        assertThat(replaced).isEqualTo(200);
        assertThat(get(server.getPort(), "/v1/ingest/status?source_id=project-docs"))
            .as("a replacement starts no crawl and clears no history")
            .isEqualTo(before);
        assertThat(get(server.getPort(), "/v1/ingest")).contains(narrower.toString());

        server.stop(0);
        server = startNode();

        assertThat(server.getIngestSourceRegistry().isRegistered("project-docs")).isTrue();
        assertThat(get(server.getPort(), "/v1/ingest")).contains(narrower.toString());
        assertThat(SourceCatalog.open(dataDirectory).all())
            .singleElement()
            .satisfies(descriptor -> assertThat(descriptor.config())
                .containsEntry("root_path", narrower.toString()));
    }

    /** Polls a source's status until it contains {@code expected}, and returns it. */
    private static String awaitStatus(int port, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        String status = "";
        while (System.nanoTime() < deadline) {
            status = get(port, "/v1/ingest/status?source_id=project-docs");
            if (status.contains(expected)) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("status never reached " + expected + ": " + status);
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
        node.withSourceStatus(SourceStatusFile.open(dataDirectory));
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
