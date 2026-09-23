package com.eonmux.excalibur.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.eonmux.excalibur.api.bootstrap.CoreComponents;
import com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.IngestionWiring;
import com.eonmux.excalibur.api.bootstrap.QueryPipelineFactory;
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
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph route end to end, over HTTP, on a node composed the way the application composes one.
 *
 * <p>Every other graph test stops at a seam: the endpoint tests hand the endpoint a pipeline built
 * in the test, the build tests stage facts by hand, and the extractor tests never reach a graph.
 * What none of them shows is that a document dropped into a directory becomes a fact a question
 * is answered from - that ingestion reaches the extractor, the extractor's reply reaches the
 * staging store, a build publishes it, and the query route answers from what was published at
 * the instant it was asked about.</p>
 *
 * <p>The chat model is the one stand-in: an OpenAI-compatible endpoint on loopback that answers
 * relation extraction with a fixed fact. Everything between it and the HTTP responses is the
 * production code.</p>
 */
@DisplayName("Graph route, ingested to answered")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class GraphRouteIntegrationTest {

    private static final String TOKEN = "graph-route-integration-token";
    private static final Duration WAIT = Duration.ofSeconds(90);

    /**
     * The fact the stand-in model extracts: dated, so an {@code as_of} before it and one after
     * it answer differently.
     */
    private static final String EXTRACTED = "[{\"subject\":\"Ada Lovelace\",\"relation\":\"wrote\","
        + "\"object\":\"Note G\",\"confidence\":0.95,\"valid_from\":\"1843-09-01\"}]";

    @TempDir
    Path dataDir;

    @TempDir
    Path documents;

    private HttpServer model;
    private final AtomicInteger extractionCalls = new AtomicInteger();

    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;
    private ExcaliburDBServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startNode() throws IOException {
        model = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        model.createContext("/v1/chat/completions", this::answerChat);
        model.start();

        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.llm.api-key", "integration-test-key");
        props.put("excalibur.llm.base-url", "http://127.0.0.1:" + model.getAddress().getPort());
        // Lexical ingestion and no entity vectors: the stand-in serves chat, not embeddings.
        props.put("excalibur.llm.embeddings-enabled", "false");
        props.put("excalibur.graph.entity-vectors-enabled", "false");
        props.put("excalibur.graph.extraction-enabled", "true");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        props.put(ApiSecurityConfig.KEY_ALLOWED_ROOTS, documents.toString());
        props.put(ApiSecurityConfig.KEY_AUTH_TOKEN, TOKEN);
        Config config = new Config(props);

        // As ExcaliburApplication composes a node: storage, visibility, query pipeline, graph
        // tenants, then the server with ingestion attached and the graph routes registered.
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
        server.start();
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

    /**
     * The stand-in chat model.
     *
     * <p>Relation extraction is recognised by its instruction and answered with the fixed fact.
     * Anything else the node asks a chat model - query expansion, on a node that ingests
     * lexically - is answered with an empty completion, which every caller treats as the model
     * having nothing to add.</p>
     */
    private void answerChat(HttpExchange exchange) throws IOException {
        String request;
        try (InputStream in = exchange.getRequestBody()) {
            request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String content = "";
        if (request.contains("You extract factual relationships")) {
            extractionCalls.incrementAndGet();
            content = EXTRACTED;
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

    /** Polls a GET route the way a client does, until the condition holds or time runs out. */
    private Object await(String path, Predicate<Object> condition, String what) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Reply reply = send("GET", path, null);
        while (!(reply.status() == 200 && condition.test(reply.json()))
                && System.nanoTime() < deadline) {
            Thread.sleep(100);
            reply = send("GET", path, null);
        }
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(condition.test(reply.json())).as("%s; last reply: %s", what, reply.body())
            .isTrue();
        return reply.json();
    }

    private static long number(Object json, String... path) {
        Object value = json;
        for (String field : path) {
            value = Json.at(value, field);
        }
        return value instanceof Number n ? n.longValue() : -1;
    }

    private Object ask(String question, String asOf) throws Exception {
        Reply reply = send("POST", "/v1/graph/query", "{\"query\":" + Json.write(question)
            + ",\"as_of\":" + Json.write(asOf) + "}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return reply.json();
    }

    @Test
    @DisplayName("a document ingested from a directory is answered from, as of when it held")
    void anIngestedFactIsAnsweredAsOfWhenItHeld() throws Exception {
        Files.writeString(documents.resolve("ada.txt"),
            "In September 1843 Ada Lovelace wrote Note G, the first published algorithm, as an "
                + "appendix to her translation of Menabrea's paper on the Analytical Engine.");

        Reply registered = send("POST", "/v1/ingest", "{\"source_type\":\"filesystem\","
            + "\"config\":{\"source_id\":\"notes\",\"root_path\":"
            + Json.write(documents.toString()) + ",\"included_extensions\":[\".txt\"]}}");
        assertThat(registered.status()).as(registered.body()).isBetween(200, 299);

        await("/v1/ingest/status?source_id=notes",
            status -> "idle".equals(Json.stringAt(status, "state"))
                && number(status, "entries_ingested") >= 1,
            "the source was crawled and its document ingested");
        await("/v1/stats", stats -> number(stats, "graph", "ingest", "factsStaged") >= 1,
            "the extracted fact was staged for the graph");
        assertThat(extractionCalls.get()).as("the extractor asked the chat model").isPositive();

        Reply build = send("POST", "/v1/graph/build", null);
        assertThat(build.status()).as(build.body()).isEqualTo(202);
        assertThat(Json.stringAt(build.json(), "extraction")).isEqualTo("active");
        Object built = await("/v1/graph/build/status",
            status -> !"running".equals(Json.stringAt(status, "state")),
            "the build finished");
        assertThat(Json.stringAt(built, "state")).as(Json.write(built)).isEqualTo("completed");
        assertThat(Json.at(built, "published")).isEqualTo(Boolean.TRUE);
        assertThat(number(built, "edges")).isEqualTo(1);

        Object after = ask("What did Ada Lovelace write?", "1850-01-01");
        assertThat(Json.stringAt(after, "status")).as(Json.write(after)).isEqualTo("answered");
        assertThat(Json.stringAt(after, "context"))
            .as("grounded in the fact the ingested document stated")
            .contains("Ada Lovelace")
            .contains("Note G");

        Object before = ask("What did Ada Lovelace write?", "1820-01-01");
        assertThat(Json.stringAt(before, "status"))
            .as("before the fact held there is nothing to answer from: %s", Json.write(before))
            .isEqualTo("declined");
        assertThat(String.valueOf(Json.at(before, "context"))).doesNotContain("Note G");
    }
}
