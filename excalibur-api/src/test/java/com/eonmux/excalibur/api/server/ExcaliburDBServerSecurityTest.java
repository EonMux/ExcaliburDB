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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for the API's unauthenticated attack surface.
 *
 * <p>Every endpoint except the liveness probe must require a bearer token, ingest
 * targets must be allow-listed, and a refusal must not describe the server.</p>
 */
@DisplayName("ExcaliburDBServer security")
class ExcaliburDBServerSecurityTest {

    private static final String TOKEN = "test-bearer-token";

    @TempDir
    Path allowedRoot;

    @TempDir
    Path forbiddenRoot;

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private ExcaliburDBServer server;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        SegmentCatalog segmentCatalog = new SegmentCatalog();

        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        var visibilityTracker = new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog);
        var epochTickScheduler = mock(EpochTickScheduler.class);

        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(epochTickScheduler);
        when(epochTickScheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(indexOrchestrator.getHnswQueueDepth()).thenReturn(0L);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
        when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(0L);

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .allowIngestRoot(allowedRoot)
            .allowIngestHost("docs.example.com")
            .build());
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        @Test
        @DisplayName("liveness probe stays unauthenticated")
        void healthIsPublic() throws IOException {
            assertThat(get("/health", null).getResponseCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("stats requires a bearer token")
        void statsRequiresToken() throws IOException {
            HttpURLConnection conn = get("/v1/stats", null);

            assertThat(conn.getResponseCode()).isEqualTo(401);
            assertThat(conn.getHeaderField("WWW-Authenticate")).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("stats rejects a wrong token")
        void statsRejectsWrongToken() throws IOException {
            assertThat(get("/v1/stats", "Bearer nope").getResponseCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("stats accepts the configured token")
        void statsAcceptsToken() throws IOException {
            assertThat(get("/v1/stats", "Bearer " + TOKEN).getResponseCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("ingest requires a bearer token")
        void ingestRequiresToken() throws IOException {
            HttpURLConnection conn = post("/v1/ingest", null,
                "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"s\", "
                    + "\"root_path\": \"" + allowedRoot + "\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("reindex requires a bearer token")
        void reindexRequiresToken() throws IOException {
            assertThat(post("/v1/reindex", null, "{\"source_id\": \"s\"}").getResponseCode())
                .isEqualTo(401);
        }

        @Test
        @DisplayName("the 401 body says only that authentication failed")
        void refusalLeaksNothing() throws IOException {
            HttpURLConnection conn = get("/v1/stats", null);

            assertThat(conn.getResponseCode()).isEqualTo(401);
            assertThat(readError(conn)).isEqualTo("{\"error\": \"Authentication failed\"}");
        }
    }

    @Nested
    @DisplayName("Ingest target allow-lists")
    class IngestTargets {

        @Test
        @DisplayName("accepts a root inside the allow-list")
        void acceptsAllowedRoot() throws IOException {
            HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"allowed\", "
                    + "\"root_path\": \"" + allowedRoot + "\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("refuses a root outside the allow-list")
        void refusesForeignRoot() throws IOException {
            HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"foreign\", "
                    + "\"root_path\": \"" + forbiddenRoot + "\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(403);
            assertThat(readError(conn)).doesNotContain(forbiddenRoot.toString());
        }

        @Test
        @DisplayName("refuses a traversal out of the allow-list")
        void refusesTraversal() throws IOException {
            String traversal = allowedRoot.resolve("..").toString();

            HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"traversal\", "
                    + "\"root_path\": \"" + traversal + "\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("refuses a URL whose host is not allow-listed")
        void refusesForeignHost() throws IOException {
            HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                "{\"source_type\": \"http\", \"config\": {\"source_id\": \"ssrf\", "
                    + "\"base_url\": \"http://169.254.169.254/latest/meta-data/\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("refuses a loopback URL even when the host is allow-listed")
        void refusesLoopbackHost() throws IOException {
            server.stop(0);
            server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            server.withSecurity(ApiSecurityConfig.builder()
                .authToken(TOKEN)
                .allowIngestHost("127.0.0.1")
                .build());
            server.start();
            serverPort = server.getPort();

            HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                "{\"source_type\": \"http\", \"config\": {\"source_id\": \"loopback\", "
                    + "\"base_url\": \"http://127.0.0.1:9200/_cluster/health\"}}");

            assertThat(conn.getResponseCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("refuses a duplicate source ID without starting a second crawl")
        void refusesDuplicateSource() throws IOException {
            String body = "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"dup\", "
                + "\"root_path\": \"" + allowedRoot + "\"}}";

            assertThat(post("/v1/ingest", "Bearer " + TOKEN, body).getResponseCode()).isEqualTo(200);
            assertThat(post("/v1/ingest", "Bearer " + TOKEN, body).getResponseCode()).isEqualTo(409);
        }
    }

    @Nested
    @DisplayName("Bind address")
    class BindAddress {

        @Test
        @DisplayName("refuses to start on a non-loopback address with no token")
        void refusesUnauthenticatedPublicBind() {
            ExcaliburDBServer exposed =
                new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            exposed.withSecurity(ApiSecurityConfig.builder().bindAddress("0.0.0.0").build());

            assertThatThrownBy(exposed::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-token");
            assertThat(exposed.isRunning()).isFalse();
        }

        @Test
        @DisplayName("binds loopback by default")
        void bindsLoopbackByDefault() throws IOException {
            assertThat(ApiSecurityConfig.defaults().bindInetAddress().isLoopbackAddress()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rate limiting")
    class RateLimiting {

        @Test
        @DisplayName("answers 429 with Retry-After once the mutate budget is spent")
        void refusesOverMutateBudget() throws IOException {
            server.stop(0);
            server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            server.withSecurity(ApiSecurityConfig.builder()
                .authToken(TOKEN)
                .allowIngestRoot(allowedRoot)
                .mutateBudget(1.0, 2.0)
                .build());
            server.start();
            serverPort = server.getPort();

            int lastStatus = 0;
            String retryAfter = null;
            for (int i = 0; i < 5; i++) {
                HttpURLConnection conn = post("/v1/ingest", "Bearer " + TOKEN,
                    "{\"source_type\": \"filesystem\", \"config\": {\"source_id\": \"rl-" + i + "\", "
                        + "\"root_path\": \"" + allowedRoot + "\"}}");
                lastStatus = conn.getResponseCode();
                retryAfter = conn.getHeaderField("Retry-After");
            }

            assertThat(lastStatus).isEqualTo(429);
            assertThat(retryAfter).isNotNull();
        }

        @Test
        @DisplayName("a method the router does not serve is charged against the budget on /v1/query")
        void refusesOverBudgetOnAnUnroutedQueryMethod() throws IOException {
            restartWithReadBudget();

            assertThat(statuses("GET", "/v1/query", 6))
                .as("a method-router fall-through answers 405 without reaching either branch's "
                    + "guard, so an anonymous caller could hold the connection open at line rate "
                    + "unless the fall-through is guarded too")
                .startsWith(405, 405)
                .endsWith(429);
        }

        @Test
        @DisplayName("a method the router does not serve is charged against the budget on /v1/ingest")
        void refusesOverBudgetOnAnUnroutedIngestMethod() throws IOException {
            restartWithReadBudget();

            // OPTIONS rather than GET: /v1/ingest serves four verbs - POST registers, PUT replaces,
            // GET reads the registrations back, DELETE stops crawling - so each of them reaches
            // a guarded handler and answers 401 rather than falling through. OPTIONS is a method
            // the route genuinely does not serve, which is what this is about.
            assertThat(statuses("OPTIONS", "/v1/ingest", 6))
                .startsWith(405, 405)
                .endsWith(429);
        }

        @Test
        @DisplayName("the refusal carries Retry-After, as every other refusal does")
        void theUnroutedMethodRefusalCarriesRetryAfter() throws IOException {
            restartWithReadBudget();

            HttpURLConnection conn = null;
            for (int i = 0; i < 6; i++) {
                conn = get("/v1/query", null);
                conn.getResponseCode();
            }

            assertThat(conn.getResponseCode()).isEqualTo(429);
            assertThat(conn.getHeaderField("Retry-After")).isNotNull();
        }

        private void restartWithReadBudget() throws IOException {
            server.stop(0);
            server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            server.withSecurity(ApiSecurityConfig.builder()
                .authToken(TOKEN)
                .allowIngestRoot(allowedRoot)
                .readBudget(1.0, 2.0)
                .build());
            server.start();
            serverPort = server.getPort();
        }

        private int[] statuses(String method, String path, int count) throws IOException {
            int[] codes = new int[count];
            for (int i = 0; i < count; i++) {
                codes[i] = open(method, path, null, null).getResponseCode();
            }
            return codes;
        }
    }

    private HttpURLConnection get(String path, String authorization) throws IOException {
        return open("GET", path, authorization, null);
    }

    private HttpURLConnection post(String path, String authorization, String body) throws IOException {
        return open("POST", path, authorization, body);
    }

    private HttpURLConnection open(String method, String path, String authorization, String body)
            throws IOException {
        var url = URI.create("http://127.0.0.1:" + serverPort + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (authorization != null) {
            conn.setRequestProperty("Authorization", authorization);
        }
        if (body != null) {
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        conn.connect();
        return conn;
    }

    private String readError(HttpURLConnection conn) throws IOException {
        try (var is = conn.getErrorStream()) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
