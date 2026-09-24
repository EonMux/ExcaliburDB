package com.eonmux.excalibur.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.eonmux.excalibur.api.bootstrap.CoreComponents;
import com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.IngestionWiring;
import com.eonmux.excalibur.api.bootstrap.QueryPipelineFactory;
import com.eonmux.excalibur.api.bootstrap.ReindexerFactory;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The served graph follows the source without anybody asking for a build, over HTTP, on a node
 * composed the way the application composes one.
 *
 * <p>A crawl that replaced or removed a document withdrew its facts from staging, and nothing
 * else: {@code /v1/graph/query} went on answering with what the document used to say until an
 * operator thought to {@code POST /v1/graph/build}, while {@code /v1/query} reflected the change
 * at once. Here nothing calls the build route; the node's automatic build is what brings the
 * graph up to date - checking every second, with no debounce, so the test waits on seconds
 * rather than the minute a deployment would.</p>
 *
 * <p>The chat model is the one stand-in: an OpenAI-compatible endpoint on loopback that extracts
 * from each version of the document the fact that version states. Everything between it and the
 * HTTP responses is the production code.</p>
 */
@DisplayName("Automatic graph builds")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class AutomaticGraphBuildIntegrationTest {

    private static final String TOKEN = "automatic-graph-build-integration-token";
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String QUESTION = "What did Ada Lovelace write?";

    private static final String FIRST_VERSION = "In September 1843 Ada Lovelace wrote Note G, the "
        + "first published algorithm, as an appendix to her translation of Menabrea's paper.";
    private static final String FIRST_FACT = "[{\"subject\":\"Ada Lovelace\",\"relation\":\"wrote\","
        + "\"object\":\"Note G\",\"confidence\":0.95,\"valid_from\":\"1843-09-01\"}]";

    private static final String SECOND_VERSION = "In 1843 Ada Lovelace wrote the Bernoulli table, "
        + "a sequence of operations for computing Bernoulli numbers on the Analytical Engine.";
    private static final String SECOND_FACT = "[{\"subject\":\"Ada Lovelace\",\"relation\":"
        + "\"wrote\",\"object\":\"Bernoulli table\",\"confidence\":0.95,"
        + "\"valid_from\":\"1843-09-01\"}]";

    @TempDir
    Path dataDir;

    @TempDir
    Path documents;

    private HttpServer model;
    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;
    private ExcaliburDBServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Starts a node with the given automatic build settings.
     *
     * @param autoBuild The {@code excalibur.graph.auto-build-*} settings
     */
    private void startNode(Map<String, String> autoBuild) throws IOException {
        model = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        model.createContext("/v1/chat/completions", this::answerChat);
        model.start();

        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.llm.api-key", "integration-test-key");
        props.put("excalibur.llm.base-url", "http://127.0.0.1:" + model.getAddress().getPort());
        props.put("excalibur.llm.embeddings-enabled", "false");
        props.put("excalibur.graph.entity-vectors-enabled", "false");
        props.put("excalibur.graph.extraction-enabled", "true");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        props.put(ApiSecurityConfig.KEY_ALLOWED_ROOTS, documents.toString());
        props.put(ApiSecurityConfig.KEY_AUTH_TOKEN, TOKEN);
        props.putAll(autoBuild);
        Config config = new Config(props);

        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            DocumentRegistryFactory.open(dataDir), core.indexOrchestrator().getVisibilityTracker());
        QueryPipeline queryPipeline = QueryPipelineFactory.create(config, core.storageEngine(),
            core.indexOrchestrator(), new DocumentVisibilityTrackerAdapter(tracker));
        tenants = new GraphTenants(GraphRagFactory.tenantRootFor(config, dataDir), config,
            MappingBudget.detect(), config.graph().getMaxOpenNamespaces());

        server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            queryPipeline);
        server.withSecurity(ApiSecurityConfig.fromConfig(config));
        server.withSourceCatalog(SourceCatalog.open(dataDir));
        attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
            core.indexOrchestrator(), tracker, tenants)
            .attachTo(server);
        server.withDocumentRegistry(tracker.getRegistry());
        server.withGraphSupport(tenants);
        server.withReindexer(ReindexerFactory.create(config, core.storageEngine(),
            core.volumeManager(), core.indexOrchestrator(),
            tracker, attachment.chunkIndex()));
        server.start();
    }

    /** Checking every second and building as soon as a change is seen. */
    private static Map<String, String> eager() {
        return Map.of(
            "excalibur.graph.auto-build-interval-seconds", "1",
            "excalibur.graph.auto-build-debounce-seconds", "0");
    }

    @AfterEach
    void stopNode() {
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
                // best effort; the storage engine below is what holds the files
            }
            core.storageEngine().close();
            if (core.arena() != null) {
                core.arena().close();
            }
        }
        if (model != null) {
            model.stop(0);
        }
    }

    /** The stand-in chat model: each version of the document yields the fact it states. */
    private void answerChat(HttpExchange exchange) throws IOException {
        String request;
        try (InputStream in = exchange.getRequestBody()) {
            request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String content = "";
        if (request.contains("You extract factual relationships")) {
            content = request.contains("Bernoulli numbers") ? SECOND_FACT : FIRST_FACT;
        }
        byte[] body = ("{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":0,"
            + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":" + Json.write(content) + "},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private record Reply(int status, Object json, String body) {
    }

    private Reply send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getPort() + path))
            .header("Authorization", "Bearer " + TOKEN)
            .timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Object json = response.body().isBlank() ? null : Json.parse(response.body());
        return new Reply(response.statusCode(), json, response.body());
    }

    /** Sends a request until its reply satisfies the condition, or time runs out. */
    private Reply await(String method, String path, String body, Predicate<Reply> condition,
                        String what) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Reply reply = send(method, path, body);
        while (!condition.test(reply) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            reply = send(method, path, body);
        }
        assertThat(condition.test(reply)).as("%s; last reply: %s", what, reply.body()).isTrue();
        return reply;
    }

    private Object awaitGet(String path, Predicate<Object> condition, String what)
            throws Exception {
        return await("GET", path, null,
            reply -> reply.status() == 200 && condition.test(reply.json()), what).json();
    }

    private static Object at(Object json, String... path) {
        Object value = json;
        for (String field : path) {
            value = Json.at(value, field);
        }
        return value;
    }

    private static long number(Object json, String... path) {
        return at(json, path) instanceof Number n ? n.longValue() : -1;
    }

    private static String graphQuery() {
        return "{\"query\":" + Json.write(QUESTION) + ",\"as_of\":\"1850-01-01\"}";
    }

    /** Waits until the graph answers the question with context satisfying the condition. */
    private String awaitContext(Predicate<String> condition, String what) throws Exception {
        Reply reply = await("POST", "/v1/graph/query", graphQuery(),
            candidate -> candidate.status() == 200
                && condition.test(String.valueOf(Json.at(candidate.json(), "context"))),
            what);
        return String.valueOf(Json.at(reply.json(), "context"));
    }

    private void registerSource() throws Exception {
        Reply registered = send("POST", "/v1/ingest", "{\"source_type\":\"filesystem\","
            + "\"config\":{\"source_id\":\"notes\",\"root_path\":"
            + Json.write(documents.toString()) + ",\"included_extensions\":[\".txt\"]}}");
        assertThat(registered.status()).as(registered.body()).isBetween(200, 299);
        awaitGet("/v1/ingest/status?source_id=notes",
            status -> "idle".equals(Json.stringAt(status, "state"))
                && number(status, "entries_ingested") >= 1,
            "the source was crawled and its document ingested");
    }

    private void reindex() throws Exception {
        long requested = System.currentTimeMillis();
        Reply accepted = send("POST", "/v1/reindex", "{\"source_id\":\"notes\"}");
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
        Object finished = awaitGet("/v1/reindex/status?source_id=notes",
            status -> "idle".equals(Json.stringAt(status, "state"))
                && number(status, "finished_at_ms") >= requested,
            "the reindex finished");
        assertThat(Json.at(finished, "last_error")).as(Json.write(finished)).isNull();
    }

    /** The shared namespace's entry in the namespace listing. */
    private Object sharedNamespace() throws Exception {
        Reply listing = send("GET", "/v1/graph/namespaces", null);
        assertThat(listing.status()).as(listing.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Object> namespaces = (List<Object>) Json.at(listing.json(), "namespaces");
        return namespaces.stream()
            .filter(entry -> "shared".equals(Json.stringAt(entry, "namespace")))
            .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an ingested, replaced and removed document reach the graph with no build asked "
        + "for")
    void theGraphFollowsTheSource() throws Exception {
        startNode(eager());
        Path file = documents.resolve("ada.txt");
        Files.writeString(file, FIRST_VERSION);
        registerSource();

        assertThat(awaitContext(context -> context.contains("Note G"),
            "the first version's fact was published")).contains("Note G");

        Files.writeString(file, SECOND_VERSION);
        Files.setLastModifiedTime(file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000));
        reindex();

        assertThat(awaitContext(context -> context.contains("Bernoulli table")
                && !context.contains("Note G"),
            "the replacement was published and the old fact dropped"))
            .contains("Bernoulli table")
            .doesNotContain("Note G");

        Files.delete(file);
        reindex();

        await("POST", "/v1/graph/query", graphQuery(), reply -> reply.status() == 503,
            "a graph built only from the removed document is no longer served");

        Object status = awaitGet("/v1/graph/build/status",
            json -> Boolean.FALSE.equals(at(json, "freshness", "behind_staging"))
                && !"running".equals(Json.stringAt(at(json, "automatic"), "state")),
            "the graph is current with its staging");
        assertThat(Json.stringAt(status, "state"))
            .as("every build was automatic; none was asked for")
            .isEqualTo("idle");
        assertThat(Json.stringAt(at(status, "automatic"), "last_result")).isEqualTo("completed");
        assertThat(Json.stringAt(at(status, "automatic"), "state")).isEqualTo("current");
        assertThat(at(sharedNamespace(), "behind_staging")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("with automatic builds disabled the graph waits for a build to be asked for")
    void disabledWaitsForABuild() throws Exception {
        startNode(Map.of(
            "excalibur.graph.auto-build-enabled", "false",
            "excalibur.graph.auto-build-interval-seconds", "1",
            "excalibur.graph.auto-build-debounce-seconds", "0"));
        Files.writeString(documents.resolve("ada.txt"), FIRST_VERSION);
        registerSource();
        awaitGet("/v1/stats", stats -> number(stats, "graph", "ingest", "factsStaged") >= 1,
            "the fact was staged");

        Object status = awaitGet("/v1/graph/build/status",
            json -> Boolean.TRUE.equals(at(json, "freshness", "behind_staging")),
            "the graph is reported behind its staging");
        assertThat(Json.stringAt(at(status, "automatic"), "state")).isEqualTo("disabled");
        assertThat(at(status, "automatic", "enabled")).isEqualTo(Boolean.FALSE);
        assertThat(number(status, "freshness", "staged_changes_since_ms")).isPositive();
        assertThat(at(sharedNamespace(), "behind_staging")).isEqualTo(Boolean.TRUE);

        // Several passes' worth of the interval a scheduler would have used.
        Thread.sleep(3_000);
        assertThat(send("POST", "/v1/graph/query", graphQuery()).status())
            .as("nothing built the graph")
            .isEqualTo(503);

        Reply build = send("POST", "/v1/graph/build", null);
        assertThat(build.status()).as(build.body()).isEqualTo(202);
        awaitContext(context -> context.contains("Note G"), "the asked-for build published");
        assertThat(at(sharedNamespace(), "behind_staging")).isEqualTo(Boolean.FALSE);
    }
}
