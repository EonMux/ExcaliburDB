package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.StagingGraphIngestSink;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three routes that start, watch and stop a graph build.
 *
 * <p>{@code POST /v1/graph/build} accepts and answers; the build runs behind it, because
 * replaying every staged fact and indexing every entity is minutes on a real corpus and a
 * request held open for that long dies with the client's timeout while the work carries on
 * invisibly. {@code GET /v1/graph/build/status} is therefore the only way the outcome is ever
 * seen, and {@code DELETE /v1/graph/build} is how one started at the wrong moment is ended
 * without ending the node.</p>
 *
 * <p>Two things are pinned harder than the rest. A build that is stopped must publish nothing -
 * the graph is replaced by a rename, and the namespace goes on serving the graph it had. And
 * the empty case stays what it was: a node that has staged nothing keeps serving whatever it
 * already had, reported as {@code published: false} rather than as a successful build of
 * nothing.</p>
 */
@DisplayName("Graph build endpoints")
class GraphBuildEndpointTest {

    private Path root;
    private Config config;
    private GraphTenants tenants;
    private GraphBuildRunner runner;
    private GraphAutoBuilder autoBuilder;
    private GraphBuildEndpoint build;
    private GraphBuildStatusEndpoint status;
    private GraphBuildCancelEndpoint cancel;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        this.config = new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
        this.tenants = new GraphTenants(root, config, MappingBudget.detect(), 4);
        useRunner(new GraphBuildRunner(tenants));
    }

    private void useRunner(GraphBuildRunner replacement) {
        if (runner != null) {
            runner.close();
        }
        if (autoBuilder != null) {
            autoBuilder.close();
        }
        runner = replacement;
        // Never started: its passes run when a test calls for one. No debounce, so a pass
        // builds whatever is behind.
        GraphBuildRunner current = runner;
        autoBuilder = new GraphAutoBuilder(new GraphAutoBuilder.Settings(true,
            Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(900), 1,
            Duration.ofSeconds(60), Duration.ofSeconds(3600)), GraphAutoBuilder.over(tenants),
            current::extraction, id -> current.snapshot(id).isRunning(), Clock.systemUTC());
        build = new GraphBuildEndpoint(runner);
        status = new GraphBuildStatusEndpoint(runner, autoBuilder);
        cancel = new GraphBuildCancelEndpoint(runner, autoBuilder);
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
        if (autoBuilder != null) {
            autoBuilder.close();
        }
        if (tenants != null) {
            tenants.close();
        }
    }

    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private Response call(HttpHandler handler, String method, String namespace)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Headers requestHeaders = new Headers();
        if (namespace != null) {
            requestHeaders.set(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/build"));
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(out);

        handler.handle(exchange);

        ArgumentCaptor<Integer> code = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(code.capture(), anyLong());
        return new Response(code.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    /** Polls the status route the way a client does, until the build reaches a final state. */
    private Object awaitState(String namespace, String state) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Object json = call(status, "GET", namespace).json();
        while (!state.equals(Json.stringAt(json, "state")) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            json = call(status, "GET", namespace).json();
        }
        assertThat(Json.stringAt(json, "state")).isEqualTo(state);
        return json;
    }

    private void stage(NamespaceId namespace, String subject, String relation, String object)
            throws IOException {
        try (NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant> lease =
                 tenants.borrow(namespace)) {
            lease.resource().staging().append(ExtractedTriple.alwaysValid(subject, relation,
                object, Provenance.of(SourceType.DOCUMENT, 1, 0.9, 1)));
        }
    }

    private Path graphDirectoryOf(NamespaceId namespace) {
        return namespace.directoryUnder(root).resolve("graph");
    }

    @Nested
    @DisplayName("starting one")
    class Starting {

        @Test
        @DisplayName("a build is accepted rather than performed on the request thread")
        void postAccepts() throws IOException {
            Response response = call(build, "POST", null);

            assertThat(response.status())
                .as("a request held open for the length of a build dies with the client")
                .isEqualTo(202);
            Object json = response.json();
            assertThat(Json.stringAt(json, "state")).isEqualTo(GraphBuildRunner.STATE_RUNNING);
            assertThat(Json.stringAt(json, "namespace")).isEqualTo(NamespaceId.SHARED.value());
            assertThat(Json.at(json, "started_at_ms")).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("what the build did is read back from the status route")
        void theOutcomeIsReadBack() throws IOException {
            stage(NamespaceId.SHARED, "Ada", "knows", "Babbage");
            stage(NamespaceId.SHARED, "Babbage", "designed", "Engine");

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "edges")).isEqualTo(2L);
            assertThat(Json.at(json, "entities")).isEqualTo(3L);
            for (String field : new String[] {
                "state", "namespace", "started_at_ms", "finished_at_ms", "published", "edges",
                "entities", "relations", "entities_with_text", "entities_with_vectors",
                "staged_facts"}) {
                assertThat(Json.at(json, field)).as("field %s", field).isNotNull();
            }
        }

        @Test
        @DisplayName("a build reports the staged facts it left out for an outstanding erasure")
        void theWithheldFactsAreReported() throws IOException {
            try (NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant> lease =
                     tenants.borrow(NamespaceId.SHARED)) {
                List<Long> facts = lease.resource().staging().assertFacts(List.of(
                    com.eonmux.excalibur.graph.staging.AssertedFact.always("Ada", "knows",
                        "Babbage"),
                    com.eonmux.excalibur.graph.staging.AssertedFact.always("Babbage", "designed",
                        "Engine")));
                lease.resource().erasureService().submit(
                    new com.eonmux.excalibur.graph.erasure.ErasureScope.Source(
                        SourceType.USER_ASSERTED, facts.get(0)));
            }

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "edges")).isEqualTo(1L);
            assertThat(Json.at(json, "staged_facts")).isEqualTo(2L);
            assertThat(Json.at(json, "withheld_facts"))
                .as("the retracted fact is still staged, and was not published")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("a build with every staged fact owed to an erasure says so")
        void everyFactWithheldIsExplained() throws IOException {
            try (NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant> lease =
                     tenants.borrow(NamespaceId.SHARED)) {
                List<Long> facts = lease.resource().staging().assertFacts(List.of(
                    com.eonmux.excalibur.graph.staging.AssertedFact.always("Ada", "knows",
                        "Babbage")));
                lease.resource().erasureService().submit(
                    new com.eonmux.excalibur.graph.erasure.ErasureScope.Source(
                        SourceType.USER_ASSERTED, facts.get(0)));
            }

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published")).isEqualTo(Boolean.FALSE);
            assertThat(Json.at(json, "withheld_facts")).isEqualTo(1L);
            assertThat(Json.stringAt(json, "message")).contains("owed to an outstanding erasure");
        }

        @Test
        @DisplayName("building with nothing staged reports that nothing was published")
        void buildingNothingPublishesNothing() throws IOException {
            call(build, "POST", null);

            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published"))
                .as("an empty build must not claim to have replaced the served graph")
                .isEqualTo(Boolean.FALSE);
            assertThat(Json.at(json, "edges")).isEqualTo(0L);
            assertThat(Json.at(json, "staged_facts")).isEqualTo(0L);
        }

        @Test
        @DisplayName("a second build while one is running is refused, not raced")
        void onlyOneAtATime() throws IOException {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            GraphBuildRunner.Build gated = gate(entered, release);
            useRunner(new GraphBuildRunner(gated));

            assertThat(call(build, "POST", "acme").status()).isEqualTo(202);
            await(entered);

            Response refused = call(build, "POST", "other");

            assertThat(refused.status()).isEqualTo(409);
            assertThat(Json.stringAt(refused.json(), "error"))
                .as("the caller has to be told which tenant is holding the node")
                .contains("acme");

            release.countDown();
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);
        }

        @Test
        @DisplayName("a GET is refused")
        void onlyPostIsAllowed() throws IOException {
            Response response = call(build, "GET", null);

            assertThat(response.status()).isEqualTo(405);
            assertThat(Json.stringAt(response.json(), "error")).contains("POST");
        }

        @Test
        @DisplayName("an unusable namespace is refused rather than silently built as shared")
        void invalidNamespaceIsRefused() throws IOException {
            Response response = call(build, "POST", "../escape");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).isNotBlank();
        }

        @Test
        @DisplayName("a namespace that cannot be created is refused at once, and the answer "
            + "describes no filesystem")
        void anUncreatableNamespaceIsRefused() throws IOException {
            // A regular file where the namespace's directory belongs. The namespace is admitted
            // before the build is claimed, so creating it fails on this request rather than
            // behind a 202, and nothing is left claimed.
            Files.writeString(root.resolve("acme"), "not a directory");

            Response refused = call(build, "POST", "acme");

            assertThat(refused.status()).isEqualTo(500);
            assertThat(refused.body())
                .as("the path it failed on belongs in the log, not in the answer")
                .doesNotContain(root.toString());
            assertThat(Json.stringAt(call(status, "GET", "acme").json(), "state"))
                .isEqualTo(GraphBuildRunner.STATE_IDLE);
            assertThat(call(build, "POST", "beta").status())
                .as("the refusal claimed nothing, so the next build starts")
                .isEqualTo(202);
        }

        @Test
        @DisplayName("a build asked for during shutdown is 503, and creates no namespace")
        void shuttingDownCreatesNothing() throws IOException {
            runner.close();

            Response refused = call(build, "POST", "fresh");

            assertThat(refused.status()).isEqualTo(503);
            assertThat(Json.stringAt(refused.json(), "code")).isEqualTo("shutting_down");
            assertThat(root.resolve("fresh"))
                .as("a request that fails leaves no tenant behind").doesNotExist();
        }

        @Test
        @DisplayName("a namespace the node will not create is refused before a build is claimed")
        void aRefusedNamespaceClaimsNothing() throws IOException {
            tenants.close();
            tenants = new GraphTenants(root, new Config(new HashMap<>(Map.of(
                "excalibur.graph.ram-buffer-mb", "4.0",
                "excalibur.graph.require-explicit-namespaces", "true"))),
                MappingBudget.detect(), 4);
            useRunner(new GraphBuildRunner(tenants));

            Response refused = call(build, "POST", "acmee");

            assertThat(refused.status()).isEqualTo(404);
            assertThat(Json.stringAt(refused.json(), "code")).isEqualTo("namespace_not_found");
            assertThat(root.resolve("acmee")).doesNotExist();
            assertThat(Json.stringAt(call(status, "GET", "acmee").json(), "state"))
                .isEqualTo(GraphBuildRunner.STATE_IDLE);

            tenants.create(NamespaceId.of("acme"));
            assertThat(call(build, "POST", "acme").status()).isEqualTo(202);
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);
        }

        @Test
        @DisplayName("a build can be repeated without failing")
        void buildsAreRepeatable() throws IOException {
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(call(build, "POST", null).status()).isEqualTo(202);
                awaitState(null, GraphBuildRunner.STATE_COMPLETED);
            }
        }

        @Test
        @DisplayName("a null runner is refused at construction")
        void nullRunnerIsRefused() {
            assertThatThrownBy(() -> new GraphBuildEndpoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runner");
        }
    }

    @Nested
    @DisplayName("watching one")
    class Watching {

        @Test
        @DisplayName("a namespace that has never built reports idle")
        void idleBeforeAnything() throws IOException {
            Response response = call(status, "GET", "acme");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.stringAt(response.json(), "state"))
                .isEqualTo(GraphBuildRunner.STATE_IDLE);
            assertThat(Json.stringAt(response.json(), "namespace")).isEqualTo("acme");
        }

        @Test
        @DisplayName("one tenant's build is not another tenant's status")
        void statusIsPerNamespace() throws IOException {
            call(build, "POST", "acme");
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.stringAt(call(status, "GET", "other").json(), "state"))
                .isEqualTo(GraphBuildRunner.STATE_IDLE);
        }

        @Test
        @DisplayName("a POST to the status route is refused")
        void onlyGetIsAllowed() throws IOException {
            Response response = call(status, "POST", null);

            assertThat(response.status()).isEqualTo(405);
            assertThat(Json.stringAt(response.json(), "error")).contains("GET");
        }

        @Test
        @DisplayName("an unusable namespace is refused")
        void invalidNamespaceIsRefused() throws IOException {
            assertThat(call(status, "GET", "../escape").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("a null runner is refused at construction")
        void nullRunnerIsRefused() {
            assertThatThrownBy(() -> new GraphBuildStatusEndpoint(null, autoBuilder))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new GraphBuildStatusEndpoint(runner, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("saying whether the graph is current")
    class Freshness {

        /** Facts attributed to the document they came from, so removing it withdraws them. */
        private StagingGraphIngestSink sinkFor(NamespaceId namespace) {
            try (NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant> lease =
                     tenants.borrow(namespace)) {
                return new StagingGraphIngestSink(lease.resource().staging(),
                    (documentKey, text) -> java.util.List.of(ExtractedTriple.alwaysValid(
                        "Ada", "knows", "Babbage", Provenance.of(SourceType.DOCUMENT,
                            LlmRelationExtractor.sourceIdOf(documentKey), 0.9, 1))));
            }
        }

        /** The value at a dotted path through nested objects, or null. */
        private static Object path(Object json, String dotted) {
            Object value = json;
            for (String field : dotted.split("\\.")) {
                value = Json.at(value, field);
            }
            return value;
        }

        /** Waits for the namespace's automatic build to finish. */
        private Object awaitAutomatic(String namespace, String result) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Object json = call(status, "GET", namespace).json();
            while (!result.equals(Json.stringAt(Json.at(json, "automatic"), "last_result"))
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                json = call(status, "GET", namespace).json();
            }
            assertThat(Json.stringAt(Json.at(json, "automatic"), "last_result"))
                .isEqualTo(result);
            return json;
        }

        @Test
        @DisplayName("a namespace with nothing staged is current")
        void currentBeforeAnything() throws IOException {
            Object json = call(status, "GET", "acme").json();

            assertThat(path(json, "freshness.behind_staging")).isEqualTo(Boolean.FALSE);
            assertThat(path(json, "freshness.withheld")).isEqualTo(Boolean.FALSE);
            assertThat(path(json, "freshness.staged_changes_since_ms")).isNull();
            assertThat(path(json, "automatic.enabled")).isEqualTo(Boolean.TRUE);
            assertThat((String) path(json, "automatic.state"))
                .isEqualTo(GraphAutoBuilder.STATE_CURRENT);
            assertThat(path(json, "automatic.debounce_seconds")).isEqualTo(0L);
            assertThat(path(json, "automatic.max_delay_seconds")).isEqualTo(900L);
            assertThat(path(json, "automatic.last_result")).isNull();
            assertThat(root.resolve("acme")).as("asking creates nothing").doesNotExist();
        }

        @Test
        @DisplayName("staged facts no build has published read as behind until one does")
        void behindUntilBuilt() throws IOException {
            tenants.reportExtraction(GraphExtraction.ACTIVE);
            sinkFor(NamespaceId.of("acme"))
                .onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");

            Object behind = call(status, "GET", "acme").json();
            assertThat(path(behind, "freshness.behind_staging")).isEqualTo(Boolean.TRUE);
            assertThat((Long) path(behind, "freshness.staged_changes_since_ms")).isPositive();
            assertThat((Long) path(behind, "freshness.last_staged_change_ms")).isPositive();
            assertThat((String) path(behind, "automatic.state"))
                .isEqualTo(GraphAutoBuilder.STATE_PENDING);

            assertThat(autoBuilder.runPass()).containsExactly(NamespaceId.of("acme"));
            Object built = awaitAutomatic("acme", GraphAutoBuilder.RESULT_COMPLETED);

            assertThat(path(built, "freshness.behind_staging")).isEqualTo(Boolean.FALSE);
            assertThat((String) path(built, "automatic.state"))
                .isEqualTo(GraphAutoBuilder.STATE_CURRENT);
            assertThat((Long) path(built, "automatic.last_started_at_ms")).isPositive();
            assertThat((Long) path(built, "automatic.last_finished_at_ms")).isPositive();
            assertThat(path(built, "automatic.consecutive_failures")).isEqualTo(0L);
            assertThat(path(built, "automatic.consecutive_supersessions")).isEqualTo(0L);
            assertThat(Json.stringAt(built, "state"))
                .as("the top-level fields are the builds asked for, and none was")
                .isEqualTo(GraphBuildRunner.STATE_IDLE);
            assertThat(graphDirectoryOf(NamespaceId.of("acme"))).isDirectory();
        }

        @Test
        @DisplayName("an automatic build an erasure overtook is superseded, not failed")
        void aSupersededBuildIsNotAFailure() throws IOException {
            tenants.reportExtraction(GraphExtraction.ACTIVE);
            sinkFor(NamespaceId.of("acme"))
                .onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");
            GraphAutoBuilder.Namespaces real = GraphAutoBuilder.over(tenants);
            GraphAutoBuilder.Namespaces overtaken = new GraphAutoBuilder.Namespaces() {
                @Override
                public List<NamespaceId> list() throws IOException {
                    return real.list();
                }

                @Override
                public com.eonmux.excalibur.api.bootstrap.GraphFreshness freshness(
                        NamespaceId id) throws IOException {
                    return real.freshness(id);
                }

                @Override
                public java.util.Optional<com.eonmux.excalibur.api.bootstrap.GraphBuildService
                        .BuildOutcome> buildIfBehind(NamespaceId id,
                                                     java.util.function.BooleanSupplier cancel)
                        throws IOException {
                    throw new PublishRefusedException("erasure request(s) 1 were recorded "
                        + "while the graph was being built");
                }
            };
            autoBuilder.close();
            GraphBuildRunner current = runner;
            autoBuilder = new GraphAutoBuilder(new GraphAutoBuilder.Settings(true,
                Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(900), 1,
                Duration.ofSeconds(60), Duration.ofSeconds(3600)), overtaken,
                current::extraction, id -> current.snapshot(id).isRunning(), Clock.systemUTC());
            status = new GraphBuildStatusEndpoint(runner, autoBuilder);

            assertThat(autoBuilder.runPass()).containsExactly(NamespaceId.of("acme"));
            Object json = awaitAutomatic("acme", GraphAutoBuilder.RESULT_SUPERSEDED);

            assertThat((String) path(json, "automatic.state"))
                .as("due again, not backing off").isEqualTo(GraphAutoBuilder.STATE_PENDING);
            assertThat(path(json, "automatic.consecutive_failures")).isEqualTo(0L);
            assertThat(path(json, "automatic.consecutive_supersessions")).isEqualTo(1L);
            assertThat(path(json, "automatic.last_error")).isNull();
            assertThat(path(json, "automatic.next_attempt_at_ms")).isNull();
            assertThat(path(json, "freshness.behind_staging")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("automatic builds pause while extraction is missing its key, and say why")
        void pausedWithoutExtraction() throws IOException {
            tenants.reportExtraction(GraphExtraction.MISSING_API_KEY);
            stage(NamespaceId.of("acme"), "Ada", "knows", "Babbage");

            Object json = call(status, "GET", "acme").json();

            assertThat((String) path(json, "automatic.state"))
                .isEqualTo(GraphAutoBuilder.STATE_PAUSED);
            assertThat((String) path(json, "automatic.paused_reason"))
                .isEqualTo("missing_api_key");
            assertThat(autoBuilder.runPass()).isEmpty();
        }

        @Test
        @DisplayName("a build after every fact was withdrawn retires the graph, and says so")
        void aRetiredGraphIsExplained() throws IOException {
            tenants.reportExtraction(GraphExtraction.ACTIVE);
            StagingGraphIngestSink sink = sinkFor(NamespaceId.of("acme"));
            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");
            call(build, "POST", "acme");
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);

            sink.onDocumentRemoved("docs", "docs:ada");
            assertThat(path(call(status, "GET", "acme").json(), "freshness.behind_staging"))
                .isEqualTo(Boolean.TRUE);
            call(build, "POST", "acme");
            Object json = awaitState("acme", GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "retired")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "published")).isEqualTo(Boolean.FALSE);
            assertThat(Json.stringAt(json, "message")).contains("withdrawn")
                .doesNotContain("still serving");
            assertThat(path(json, "freshness.behind_staging")).isEqualTo(Boolean.FALSE);
            assertThat(graphDirectoryOf(NamespaceId.of("acme"))).doesNotExist();
        }
    }

    @Nested
    @DisplayName("saying whether ingestion extracts facts")
    class Extraction {

        @Test
        @DisplayName("extraction asked for without a key is warned about when a build is asked for")
        void aMissingKeyIsWarnedAbout() throws IOException {
            tenants.reportExtraction(GraphExtraction.MISSING_API_KEY);

            Object json = call(build, "POST", null).json();

            assertThat(Json.stringAt(json, "extraction")).isEqualTo("missing_api_key");
            assertThat(Json.stringAt(json, "warning"))
                .as("both settings are named, so the operator knows which one to change")
                .contains("excalibur.graph.extraction-enabled")
                .contains("excalibur.llm.api-key");
        }

        @Test
        @DisplayName("an empty build explains that extraction is inactive, and why")
        void anEmptyBuildSaysWhy() throws IOException {
            tenants.reportExtraction(GraphExtraction.MISSING_API_KEY);

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published")).isEqualTo(Boolean.FALSE);
            assertThat(Json.stringAt(json, "extraction")).isEqualTo("missing_api_key");
            assertThat(Json.stringAt(json, "message"))
                .contains("Nothing was staged")
                .contains("excalibur.llm.api-key")
                .contains("inactive");
        }

        @Test
        @DisplayName("an empty build on a node with extraction off says it is off")
        void anEmptyBuildWithExtractionOff() throws IOException {
            tenants.reportExtraction(GraphExtraction.DISABLED);

            Response accepted = call(build, "POST", null);
            assertThat(Json.stringAt(accepted.json(), "extraction")).isEqualTo("disabled");
            assertThat(Json.at(accepted.json(), "warning"))
                .as("a node deliberately not extracting is not warned about")
                .isNull();

            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);
            assertThat(Json.stringAt(json, "message"))
                .contains("excalibur.graph.extraction-enabled is false");
        }

        @Test
        @DisplayName("an empty build on a node that does extract says to ingest first")
        void anEmptyBuildWithExtractionActive() throws IOException {
            tenants.reportExtraction(GraphExtraction.ACTIVE);

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.stringAt(json, "extraction")).isEqualTo("active");
            assertThat(Json.stringAt(json, "message")).contains("Ingest documents");
        }

        @Test
        @DisplayName("a runner with no node behind it to say still explains an empty build")
        void anEmptyBuildWithoutAState() throws IOException {
            useRunner(new GraphBuildRunner((id, cancelRequested) -> tenants.build(id,
                cancelRequested)));

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "extraction")).isNull();
            assertThat(Json.stringAt(json, "message")).contains("LLM API key");
        }

        @Test
        @DisplayName("a node whose ingestion was never wired still says what extraction is")
        void anUnwiredNodeStillCarriesTheField() throws IOException {
            // Ingestion wiring is what upgrades the state to active. A node that never got that
            // far - its pipeline would not build - has to answer with the state it is in rather
            // than leave the field out, which is the answer a caller cannot act on.
            Object accepted = call(build, "POST", null).json();
            Object idle = call(status, "GET", "acme").json();

            assertThat(Json.stringAt(accepted, "extraction")).isEqualTo("disabled");
            assertThat(Json.stringAt(idle, "extraction")).isEqualTo("disabled");
        }

        @Test
        @DisplayName("extraction that could not be wired is warned about, with the reason to look")
        void unavailableExtractionIsWarnedAbout() throws IOException {
            tenants.close();
            tenants = new GraphTenants(root, new Config(new HashMap<>(Map.of(
                "excalibur.graph.ram-buffer-mb", "4.0",
                "excalibur.graph.extraction-enabled", "true",
                "excalibur.llm.api-key", "test-key"))), MappingBudget.detect(), 4);
            useRunner(new GraphBuildRunner(tenants));

            Object accepted = call(build, "POST", null).json();
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.stringAt(accepted, "extraction")).isEqualTo("unavailable");
            assertThat(Json.stringAt(accepted, "warning")).contains("startup log");
            assertThat(Json.stringAt(json, "message")).contains("Nothing was staged")
                .contains("startup log");
        }

        @Test
        @DisplayName("a build that published has nothing to explain")
        void aPublishedBuildHasNoMessage() throws IOException {
            tenants.reportExtraction(GraphExtraction.ACTIVE);
            stage(NamespaceId.SHARED, "Ada", "knows", "Babbage");

            call(build, "POST", null);
            Object json = awaitState(null, GraphBuildRunner.STATE_COMPLETED);

            assertThat(Json.at(json, "published")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "message")).isNull();
        }

        @Test
        @DisplayName("the state is on the status before any build")
        void idleCarriesTheState() throws IOException {
            tenants.reportExtraction(GraphExtraction.MISSING_API_KEY);

            Object json = call(status, "GET", "acme").json();

            assertThat(Json.stringAt(json, "state")).isEqualTo(GraphBuildRunner.STATE_IDLE);
            assertThat(Json.stringAt(json, "extraction")).isEqualTo("missing_api_key");
            assertThat(Json.stringAt(json, "warning")).isNotBlank();
        }
    }

    @Nested
    @DisplayName("stopping one")
    class Stopping {

        @Test
        @DisplayName("stopping when nothing is running is an answer, not an error")
        void nothingToCancel() throws IOException {
            Response response = call(cancel, "DELETE", null);

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "cancelled")).isEqualTo(Boolean.FALSE);
            assertThat(Json.stringAt(response.json(), "message")).isNotBlank();
        }

        @Test
        @DisplayName("a POST to the cancel route is refused")
        void onlyDeleteIsAllowed() throws IOException {
            Response response = call(cancel, "POST", null);

            assertThat(response.status()).isEqualTo(405);
            assertThat(Json.stringAt(response.json(), "error")).contains("DELETE");
        }

        @Test
        @DisplayName("an unusable namespace is refused")
        void invalidNamespaceIsRefused() throws IOException {
            assertThat(call(cancel, "DELETE", "../escape").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("a build that is stopped publishes nothing and leaves the served graph "
            + "readable")
        void stoppingLeavesTheGraphConsistent() throws IOException {
            NamespaceId namespace = NamespaceId.of("acme");
            stage(namespace, "Ada", "knows", "Babbage");
            stage(namespace, "Babbage", "designed", "Engine");
            call(build, "POST", "acme");
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);

            // Facts the cancelled build would have published, had it been allowed to finish.
            stage(namespace, "Ada", "authored", "Notes");
            stage(namespace, "Notes", "about", "Engine");

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GraphBuildRunner.Build real = GraphBuildRunner.over(tenants);
            // Held at the door so the stop is certain to arrive before the first staged fact
            // is replayed. What runs after the latch is the real build, checkpoints and all.
            useRunner(new GraphBuildRunner((id, cancelRequested) -> {
                entered.countDown();
                await(release);
                return real.build(id, cancelRequested);
            }));

            assertThat(call(build, "POST", "acme").status()).isEqualTo(202);
            await(entered);

            Response stopped = call(cancel, "DELETE", "acme");
            assertThat(stopped.status()).isEqualTo(200);
            assertThat(Json.at(stopped.json(), "cancelled")).isEqualTo(Boolean.TRUE);
            assertThat(Json.stringAt(stopped.json(), "message")).isNotBlank();
            release.countDown();

            Object json = awaitState("acme", GraphBuildRunner.STATE_CANCELLED);
            assertThat(Json.at(json, "published"))
                .as("a build publishes by a rename or not at all")
                .isEqualTo(Boolean.FALSE);

            try (MappedEdgeStore store = MappedEdgeStore.open(graphDirectoryOf(namespace))) {
                assertThat(store.edgeCount())
                    .as("the namespace goes on serving the graph it had")
                    .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a build the node can still finish is left alone by another namespace's "
            + "stop")
        void cancellationIsScopedToTheNamespace() throws IOException {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            useRunner(new GraphBuildRunner(gate(entered, release)));

            call(build, "POST", "acme");
            await(entered);

            Response response = call(cancel, "DELETE", "other");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "cancelled"))
                .as("every graph route is scoped by the namespace header")
                .isEqualTo(Boolean.FALSE);

            release.countDown();
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);
        }

        @Test
        @DisplayName("an automatic build in progress is stopped too, publishes nothing, and is "
            + "deferred rather than restarted")
        void anAutomaticBuildIsStopped() throws IOException {
            NamespaceId namespace = NamespaceId.of("acme");
            tenants.reportExtraction(GraphExtraction.ACTIVE);
            stage(namespace, "Ada", "knows", "Babbage");
            CountDownLatch entered = new CountDownLatch(1);
            GraphAutoBuilder.Namespaces real = GraphAutoBuilder.over(tenants);
            // Held until the stop arrives, then handed to the real build, which reads the stop
            // at its first boundary - so what is exercised is the real build's cancellation.
            GraphAutoBuilder.Namespaces held = new GraphAutoBuilder.Namespaces() {
                @Override
                public List<NamespaceId> list() throws IOException {
                    return real.list();
                }

                @Override
                public com.eonmux.excalibur.api.bootstrap.GraphFreshness freshness(
                        NamespaceId id) throws IOException {
                    return real.freshness(id);
                }

                @Override
                public java.util.Optional<com.eonmux.excalibur.api.bootstrap.GraphBuildService
                        .BuildOutcome> buildIfBehind(NamespaceId id,
                                                     java.util.function.BooleanSupplier stop)
                        throws IOException {
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!stop.getAsBoolean() && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    return real.buildIfBehind(id, stop);
                }
            };
            autoBuilder.close();
            GraphBuildRunner current = runner;
            autoBuilder = new GraphAutoBuilder(new GraphAutoBuilder.Settings(true,
                Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(900), 1,
                Duration.ofSeconds(60), Duration.ofSeconds(3600)), held,
                current::extraction, id -> current.snapshot(id).isRunning(), Clock.systemUTC());
            status = new GraphBuildStatusEndpoint(runner, autoBuilder);
            cancel = new GraphBuildCancelEndpoint(runner, autoBuilder);

            assertThat(autoBuilder.runPass()).containsExactly(namespace);
            await(entered);

            Response stopped = call(cancel, "DELETE", "acme");
            assertThat(stopped.status()).isEqualTo(200);
            assertThat(Json.at(stopped.json(), "cancelled")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(stopped.json(), "automatic")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(stopped.json(), "explicit")).isEqualTo(Boolean.FALSE);
            assertThat(Json.stringAt(stopped.json(), "message")).contains("900");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Object json = call(status, "GET", "acme").json();
            while (!GraphAutoBuilder.RESULT_CANCELLED.equals(
                    Json.stringAt(Json.at(json, "automatic"), "last_result"))
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                json = call(status, "GET", "acme").json();
            }
            Object automatic = Json.at(json, "automatic");
            assertThat(Json.stringAt(automatic, "last_result"))
                .isEqualTo(GraphAutoBuilder.RESULT_CANCELLED);
            assertThat(Json.stringAt(automatic, "state"))
                .isEqualTo(GraphAutoBuilder.STATE_DEFERRED);
            assertThat((Long) Json.at(automatic, "next_attempt_at_ms"))
                .isGreaterThan((Long) Json.at(automatic, "last_finished_at_ms"));
            assertThat(Json.at(automatic, "consecutive_failures")).isEqualTo(0L);
            assertThat(Json.at(Json.at(json, "freshness"), "behind_staging"))
                .as("what it would have published is still staged, and still owed")
                .isEqualTo(Boolean.TRUE);
            assertThat(Json.stringAt(json, "state"))
                .as("the top-level fields are the builds asked for, and none was")
                .isEqualTo(GraphBuildRunner.STATE_IDLE);
            assertThat(graphDirectoryOf(namespace)).as("nothing was published").doesNotExist();
            assertThat(autoBuilder.runPass()).as("not restarted on the next pass").isEmpty();
        }

        @Test
        @DisplayName("the answer says which build was asked to stop")
        void theAnswerNamesTheBuild() throws IOException {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            useRunner(new GraphBuildRunner(gate(entered, release)));
            call(build, "POST", "acme");
            await(entered);

            Object json = call(cancel, "DELETE", "acme").json();
            release.countDown();

            assertThat(Json.at(json, "cancelled")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "explicit")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "automatic")).isEqualTo(Boolean.FALSE);
            awaitState("acme", GraphBuildRunner.STATE_COMPLETED);
        }

        @Test
        @DisplayName("a null runner or automatic build is refused at construction")
        void nullCollaboratorsAreRefused() {
            assertThatThrownBy(() -> new GraphBuildCancelEndpoint(null, autoBuilder))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new GraphBuildCancelEndpoint(runner, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    /** A build that waits at the door, so the state while one is running can be exercised. */
    private static GraphBuildRunner.Build gate(CountDownLatch entered, CountDownLatch release) {
        return (namespace, cancelRequested) -> {
            entered.countDown();
            await(release);
            return new com.eonmux.excalibur.api.bootstrap.GraphBuildService.BuildOutcome(
                false, false, 0, 0, 0, 0, 0, 0, 0);
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the build");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
