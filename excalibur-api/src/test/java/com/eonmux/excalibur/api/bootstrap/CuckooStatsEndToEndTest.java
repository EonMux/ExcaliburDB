package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * The membership-filter figures on {@code /v1/stats} describe the filters ingestion fills.
 *
 * <p>Every field of the {@code cuckoo} block used to read zero on a busy node: segments were
 * registered with no filter, so the metrics worker sampled nothing, and the pending-deletion
 * count read a queue nothing ever fed. This drives a real node - bootstrap, ingestion wiring,
 * HTTP server - through ingestion and a segment tombstone, and reads the figures back the way
 * an operator does.</p>
 */
@DisplayName("Cuckoo filter figures on /v1/stats")
class CuckooStatsEndToEndTest {

    private static final String TOKEN = "cuckoo-stats-token";

    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;
    private ExcaliburDBServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (attachment != null) {
            if (attachment.lexicalErasureSweep() != null) {
                attachment.lexicalErasureSweep().close();
            }
            if (attachment.chunkIndex() != null) {
                attachment.chunkIndex().close();
            }
        }
        if (tenants != null) {
            tenants.close();
        }
        if (core != null) {
            try {
                core.indexOrchestrator().close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            core.storageEngine().close();
            if (core.arena() != null) {
                core.arena().close();
            }
        }
    }

    private void startNode(Path dataDir) throws IOException {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        Config config = new Config(props);
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            DocumentRegistryFactory.open(dataDir),
            core.indexOrchestrator().getVisibilityTracker());
        tenants = new GraphTenants(dataDir.resolve("graph"), config, MappingBudget.detect(), 4);
        server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            mock(QueryPipeline.class, Answers.RETURNS_SELF));
        attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
            core.indexOrchestrator(), core.hierarchicalFilter(), tracker, tenants)
            .attachTo(server);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphSupport(tenants);
        server.start();
    }

    @Test
    @DisplayName("ingestion shows as a real false-positive rate, and a tombstone as pending work")
    void figuresFollowIngestionAndTombstoning(@TempDir Path dataDir) throws IOException {
        startNode(dataDir);
        JsonNode before = stats().path("cuckoo");
        assertThat(before.path("avgFpRate").asDouble()).isZero();
        assertThat(before.path("maxFpRate").asDouble()).isZero();

        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                documents.append(',');
            }
            documents.append("{\"document_id\": \"doc-").append(i).append(".md\", \"text\": \"")
                .append("document ").append(i).append(" discusses topic").append(i)
                .append(" alongside keyword").append(i * 7).append(" and subject").append(i * 13)
                .append("\", \"content_type\": \"text/plain\"}");
        }
        int status = post("/v1/documents",
            "{\"source_id\": \"cuckoo-stats\", \"documents\": [" + documents + "]}");
        assertThat(status).isBetween(200, 299);

        HierarchicalCuckooFilter hierarchy = core.hierarchicalFilter();
        List<CuckooFilter> populated = new ArrayList<>();
        long busiest = -1;
        long busiestCount = 0;
        for (Long segmentId : core.storageEngine().getSegments().keySet()) {
            CuckooFilter level = hierarchy.getSegmentFilter(String.valueOf(segmentId));
            if (level != null && level.count() > 0 && !populated.contains(level)) {
                populated.add(level);
                if (level.count() > busiestCount) {
                    busiest = segmentId;
                    busiestCount = level.count();
                }
            }
        }
        assertThat(populated).as("ingestion filed terms under the segments it wrote").isNotEmpty();
        double expectedAverage = populated.stream()
            .mapToDouble(CuckooFilter::estimateFpRate).average().orElseThrow();
        double expectedMax = populated.stream()
            .mapToDouble(CuckooFilter::estimateFpRate).max().orElseThrow();

        JsonNode afterIngest = stats().path("cuckoo");
        assertThat(afterIngest.path("avgFpRate").asDouble())
            .isPositive()
            .isCloseTo(expectedAverage, within(expectedAverage * 1e-9));
        assertThat(afterIngest.path("maxFpRate").asDouble())
            .isCloseTo(expectedMax, within(expectedMax * 1e-9));
        assertThat(afterIngest.path("pendingDeletions").asInt())
            .as("nothing is tombstoned yet")
            .isZero();

        var metadata = core.storageEngine().getCatalog().get(busiest);
        if (metadata.getLifecycleState() != LifecycleState.ACTIVE) {
            if (metadata.getLifecycleState() == LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing();
            }
            if (metadata.getLifecycleState() == LifecycleState.INDEXING) {
                metadata.transitionToWarming();
            }
            metadata.transitionToActive();
        }
        core.storageEngine().tombstoneSegment(busiest);

        // The maintenance worker may sweep between the tombstone and the read, which moves
        // the entries from one count to the other; together they are what the segment held.
        JsonNode afterTombstone = stats().path("cuckoo");
        assertThat(afterTombstone.path("pendingDeletions").asLong()
                + afterTombstone.path("totalDeletions").asLong())
            .as("a tombstoned segment's entries are pending until swept, then deleted")
            .isEqualTo(busiestCount);
    }

    private JsonNode stats() throws IOException {
        HttpURLConnection connection = open("/v1/stats");
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            return new ObjectMapper().readTree(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private int post(String path, String body) throws IOException {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path).toURL().openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        return connection;
    }
}
