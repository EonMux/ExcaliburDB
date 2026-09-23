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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A re-crawl keeps the graph in step with the source, over HTTP, on a node composed the way the
 * application composes one.
 *
 * <p>A document that changes between crawls has to stop being answered for with what it used to
 * say, and one its source drops has to stop being answered for at all. Ingestion withdrew an
 * earlier version's facts when a client posted a document again, and a reindex - the path a
 * crawled source actually changes by, on {@code POST /v1/reindex} or on its timer - did not tell
 * the graph anything: the facts of the version first ingested stayed staged, and every build
 * published them.</p>
 *
 * <p>The chat model is the one stand-in: an OpenAI-compatible endpoint on loopback that extracts
 * from each version of the document the fact that version states. Everything between it and the
 * HTTP responses is the production code.</p>
 */
@DisplayName("Graph facts across a reindex")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ReindexGraphIntegrationTest {

    private static final String TOKEN = "reindex-graph-integration-token";
    private static final Duration WAIT = Duration.ofSeconds(90);

    /** What the first version of the document says, and so what is extracted from it. */
    private static final String FIRST_VERSION = "In September 1843 Ada Lovelace wrote Note G, the "
        + "first published algorithm, as an appendix to her translation of Menabrea's paper.";
    private static final String FIRST_FACT = "[{\"subject\":\"Ada Lovelace\",\"relation\":\"wrote\","
        + "\"object\":\"Note G\",\"confidence\":0.95,\"valid_from\":\"1843-09-01\"}]";

    /** What the second version says instead. */
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
        // tenants, the server with ingestion attached and the graph routes registered, and then
        // the reindexer - attached after ingestion, as the application attaches it.
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
            tracker.getRegistry(), attachment.chunkIndex()));
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
     * <p>Relation extraction is recognised by its instruction and answered with the fact the
     * version of the document it was sent states. Anything else - query expansion, on a node
     * that ingests lexically - is answered with an empty completion.</p>
     */
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

    /** Reindexes the source and waits for the run to finish. */
    private void reindex() throws Exception {
        long requested = System.currentTimeMillis();
        Reply accepted = send("POST", "/v1/reindex", "{\"source_id\":\"notes\"}");
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
        Object finished = await("/v1/reindex/status?source_id=notes",
            status -> "idle".equals(Json.stringAt(status, "state"))
                && number(status, "finished_at_ms") >= requested,
            "the reindex finished");
        assertThat(Json.at(finished, "last_error")).as(Json.write(finished)).isNull();
    }

    /** Builds the graph from what is staged now, and waits for the build to publish. */
    private Object build() throws Exception {
        Reply build = send("POST", "/v1/graph/build", null);
        assertThat(build.status()).as(build.body()).isEqualTo(202);
        Object built = await("/v1/graph/build/status",
            status -> !"running".equals(Json.stringAt(status, "state")),
            "the build finished");
        assertThat(Json.stringAt(built, "state")).as(Json.write(built)).isEqualTo("completed");
        return built;
    }

    private String context(String question) throws Exception {
        Reply reply = send("POST", "/v1/graph/query", "{\"query\":" + Json.write(question)
            + ",\"as_of\":\"1850-01-01\"}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return String.valueOf(Json.at(reply.json(), "context"));
    }

    /** How many facts the shared namespace holds staged for the next build. */
    private long staged() throws IOException {
        return tenants.shared().staging().stagedCount();
    }

    @Test
    @DisplayName("a changed document's facts replace its earlier ones, and a removed one's go")
    void aReindexKeepsTheGraphInStepWithTheSource() throws Exception {
        Path file = documents.resolve("ada.txt");
        Files.writeString(file, FIRST_VERSION);
        Reply registered = send("POST", "/v1/ingest", "{\"source_type\":\"filesystem\","
            + "\"config\":{\"source_id\":\"notes\",\"root_path\":"
            + Json.write(documents.toString()) + ",\"included_extensions\":[\".txt\"]}}");
        assertThat(registered.status()).as(registered.body()).isBetween(200, 299);
        await("/v1/ingest/status?source_id=notes",
            status -> "idle".equals(Json.stringAt(status, "state"))
                && number(status, "entries_ingested") >= 1,
            "the source was crawled and its document ingested");
        await("/v1/stats", stats -> number(stats, "graph", "ingest", "factsStaged") >= 1,
            "the first version's fact was staged");
        build();
        assertThat(context("What did Ada Lovelace write?")).contains("Note G");

        // A later modification time as well as new content, as an edit leaves a file.
        Files.writeString(file, SECOND_VERSION);
        Files.setLastModifiedTime(file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000));
        reindex();

        assertThat(staged())
            .as("the second version's fact, in place of the first's rather than beside it")
            .isEqualTo(1);
        Object rebuilt = build();
        assertThat(number(rebuilt, "edges")).isEqualTo(1);
        assertThat(context("What did Ada Lovelace write?"))
            .as("the graph answers with what the document says now")
            .contains("Bernoulli table")
            .doesNotContain("Note G");

        Files.delete(file);
        reindex();

        assertThat(staged())
            .as("a document its source dropped leaves nothing staged for the next build")
            .isZero();
        assertThat(number(await("/v1/stats",
                stats -> number(stats, "graph", "ingest", "withdrawalFailures") == 0,
                "no withdrawal failed"), "graph", "ingest", "withdrawalFailures"))
            .isZero();
    }
}
