package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The routing contract, swept over every path the node registers.
 *
 * <p>USAGE promises that a method a registered path does not serve is answered 405 on every path,
 * before authentication, with an {@code Allow} header naming the methods it does serve - and that
 * a wrong method is charged to the read budget, never the mutate one. Each route used to be
 * checked by hand where somebody remembered to, and the ones nobody remembered broke it: a DELETE
 * of the namespace collection, a DELETE of {@code /v1/query} with no id and a GET of a namespace
 * all reached a guarded handler and answered an anonymous caller 401.</p>
 *
 * <p>So the paths are not listed here. They are read back from the registrar, on a node with every
 * optional route turned on, and every one of them - and one segment beneath it, where the
 * collection routes keep their members - is sent every method without a token.</p>
 */
@DisplayName("Route method sweep")
class RouteMethodSweepTest {

    private static final String TOKEN = "test-bearer-token";

    /** Every method a client might send; HttpClient can send any of them. */
    private static final List<String> METHODS =
        List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");

    /** The routes a probe reads without a token. */
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/health/ready");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExcaliburDBServer server;
    private GraphTenants tenants;
    private HttpClient client;
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        start(100_000);
    }

    /**
     * Starts a node with every optional route.
     *
     * @param mutateBurst How many mutations the budget holds; the read budget cannot be spent
     */
    private void start(double mutateBurst) throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        tenants = new GraphTenants(root.resolve("graphs-" + mutateBurst),
            new Config(new HashMap<>()),
            MappingBudget.detect(), 4);
        DocumentRegistry registry = new DocumentRegistry();

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class, Answers.RETURNS_SELF));
        server.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .readBudget(100_000, 100_000)
            .mutateBudget(0.001, mutateBurst)
            .build());
        server.withGraphSupport(tenants);
        server.withDocumentRegistry(registry);
        server.withDocumentErasure(new DocumentErasureService(registry, key -> false, null, null));
        server.start();
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
    @DisplayName("the sweep covers the whole route table, optional routes included")
    void theRouteTableIsComplete() {
        assertThat(server.registeredPaths()).contains(
            "/health", "/health/ready", "/metrics", "/v1/stats", "/v1/query",
            "/v1/documents", "/v1/documents/list", "/v1/erasure", "/v1/ingest",
            "/v1/graph/query", "/v1/graph/namespaces", "/v1/graph/build/status",
            "/v1/graph/erasure", "/v1/graph/entities", "/v1/graph/relations",
            "/v1/graph/facts");
    }

    @Test
    @DisplayName("the collections and members that landed with the read-backs answer 405 too")
    void theLandedRoutesRefuseBeforeAuthentication() throws Exception {
        Map<String, String> allowByRequest = new LinkedHashMap<>();
        allowByRequest.put("GET /v1/erasure", "POST");
        allowByRequest.put("PUT /v1/erasure/receipt-1", "GET");
        allowByRequest.put("PUT /v1/graph/erasure", "GET, POST");
        allowByRequest.put("DELETE /v1/graph/erasure/5", "GET");
        allowByRequest.put("GET /v1/graph/facts", "DELETE, POST");
        allowByRequest.put("POST /v1/graph/facts/7", "DELETE");
        allowByRequest.put("DELETE /v1/graph/entities", "GET");
        allowByRequest.put("POST /v1/graph/entities/1", "GET");
        allowByRequest.put("DELETE /v1/graph/relations", "GET");
        allowByRequest.put("DELETE /v1/graph/entities/1/edges", "GET");
        allowByRequest.put("PATCH /v1/ingest", "DELETE, GET, POST, PUT");

        for (Map.Entry<String, String> entry : allowByRequest.entrySet()) {
            String[] request = entry.getKey().split(" ");
            HttpResponse<String> response = anonymous(request[0], request[1]);
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).as(entry.getKey())
                .contains(entry.getValue());
            assertCoded(request[0], request[1], response);
        }
        assertThat(anonymous("GET", "/v1/graph/entities/1/edges").statusCode())
            .as("a served method on a deep entity path reaches authentication")
            .isEqualTo(401);
        assertThat(anonymous("GET", "/v1/graph/erasure").statusCode())
            .as("listing the obligations is served, and guarded")
            .isEqualTo(401);
        assertThat(anonymous("DELETE", "/v1/graph/facts").statusCode())
            .as("retracting by triple is served, and guarded")
            .isEqualTo(401);
        assertThat(anonymous("DELETE", "/v1/graph/facts/7/again").statusCode())
            .as("anything deeper than a member is not a route")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("every registered path answers an unserved method 405 with Allow, before auth")
    void everyUnservedMethodIsRefusedBeforeAuthentication() throws Exception {
        List<String> checked = new ArrayList<>();
        for (String path : server.registeredPaths()) {
            if ("/".equals(path)) {
                continue;
            }
            assertThat(sweep(path, false)).as("%s serves no method at all", path).isTrue();
            checked.add(path);
            // The collection routes keep their members one segment down. Every other context
            // answers 404 there for every method, which sweep() accepts.
            sweep(path + "/probe-id", true);
        }
        assertThat(checked).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("the paths the contract was broken on now answer 405")
    void theReportedViolationsAreFixed() throws Exception {
        assertThat(anonymous("DELETE", "/v1/graph/namespaces").statusCode()).isEqualTo(405);
        assertThat(anonymous("DELETE", "/v1/query").statusCode()).isEqualTo(405);
        assertThat(anonymous("GET", "/v1/graph/namespaces/acme").statusCode()).isEqualTo(405);
        assertThat(anonymous("POST", "/health").statusCode()).isEqualTo(405);
        HttpResponse<String> health = anonymous("PUT", "/health");
        assertThat(health.headers().firstValue("Allow")).contains("GET");
    }

    @Test
    @DisplayName("a wrong method on a mutating route is charged to the read budget")
    void aWrongMethodCostsARead() throws Exception {
        // A mutate budget of one request: a wrong method charged to it would show up as a 429 on
        // the first mutation afterwards.
        server.stop(0);
        tenants.close();
        start(1);

        for (int i = 0; i < 5; i++) {
            assertThat(anonymous("GET", "/v1/compact").statusCode()).isEqualTo(405);
            assertThat(anonymous("GET", "/v1/graph/namespaces/acme").statusCode()).isEqualTo(405);
            assertThat(anonymous("POST", "/v1/graph/namespaces").statusCode()).isEqualTo(405);
        }

        // The mutate budget holds one request. Had any 405 above been charged to it, this would
        // be refused 429 rather than reaching authentication.
        assertThat(anonymous("DELETE", "/v1/graph/namespaces/acme").statusCode())
            .isEqualTo(401);
    }

    /**
     * Sends every method to one path and checks what came back is consistent.
     *
     * @param path       The path
     * @param mayBeAbsent Whether every method answering 404 is acceptable
     * @return Whether the path served at least one method
     */
    private boolean sweep(String path, boolean mayBeAbsent) throws Exception {
        Map<String, HttpResponse<String>> byMethod = new LinkedHashMap<>();
        for (String method : METHODS) {
            byMethod.put(method, anonymous(method, path));
        }

        if (byMethod.values().stream().allMatch(response -> response.statusCode() == 404)) {
            assertThat(mayBeAbsent).as("%s is registered and answers 404 to every method", path)
                .isTrue();
            byMethod.forEach((method, response) -> assertCoded(method, path, response));
            return false;
        }

        Set<String> allowed = null;
        for (Map.Entry<String, HttpResponse<String>> entry : byMethod.entrySet()) {
            String method = entry.getKey();
            HttpResponse<String> response = entry.getValue();
            assertCoded(method, path, response);
            if (response.statusCode() != 405) {
                continue;
            }
            Set<String> allow = allowOf(response, method, path);
            assertThat(allow).as("%s %s names itself in Allow", method, path).doesNotContain(method);
            if (allowed == null) {
                allowed = allow;
            } else {
                assertThat(allow).as("every 405 on %s names the same methods", path)
                    .isEqualTo(allowed);
            }
        }
        assertThat(allowed).as("%s refuses no method, so its table is unchecked", path).isNotNull();

        for (String method : allowed) {
            HttpResponse<String> response = byMethod.get(method);
            if (response == null) {
                continue;
            }
            int status = response.statusCode();
            if (PUBLIC_PATHS.contains(path)) {
                assertThat(status).as("%s %s", method, path).isIn(200, 503);
            } else {
                assertThat(status).as("%s %s must reach authentication and stop there", method, path)
                    .isEqualTo(401);
            }
        }
        for (String method : METHODS) {
            if (!allowed.contains(method)) {
                assertThat(byMethod.get(method).statusCode())
                    .as("%s %s is not in Allow %s", method, path, allowed).isEqualTo(405);
            }
        }
        return true;
    }

    private static Set<String> allowOf(HttpResponse<String> response, String method, String path) {
        String header = response.headers().firstValue("Allow").orElse(null);
        assertThat(header).as("405 for %s %s carries Allow", method, path).isNotBlank();
        return Arrays.stream(header.split(","))
            .map(String::trim)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Every error body is an envelope naming its code. A HEAD response has no body to check, and a
     * probe's 503 is a readiness report rather than an error.
     */
    private static void assertCoded(String method, String path, HttpResponse<String> response) {
        if (response.statusCode() < 400 || "HEAD".equals(method)) {
            return;
        }
        try {
            JsonNode body = JSON.readTree(response.body());
            if (PUBLIC_PATHS.contains(path) && body.has("ready")) {
                return;
            }
            assertThat(body.path("error").isTextual())
                .as("%s %s answered %d without an error envelope: %s", method, path,
                    response.statusCode(), response.body())
                .isTrue();
            assertThat(body.path("code").isTextual())
                .as("%s %s answered %d without a code: %s", method, path,
                    response.statusCode(), response.body())
                .isTrue();
            if (response.statusCode() == 405) {
                assertThat(body.get("code").asText()).isEqualTo("method_not_allowed");
            }
        } catch (IOException e) {
            throw new AssertionError(method + " " + path + " answered a body that is not JSON: "
                + response.body(), e);
        }
    }

    private HttpResponse<String> anonymous(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(10))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
