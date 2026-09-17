package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.mcp.Json;

import io.excalibur.config.Config;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.tenancy.MappingBudget;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

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
import java.util.HashMap;
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
        runner = replacement;
        build = new GraphBuildEndpoint(runner);
        status = new GraphBuildStatusEndpoint(runner);
        cancel = new GraphBuildCancelEndpoint(runner);
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
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
        try (NamespaceRegistry.Lease<excalibur.api.bootstrap.GraphTenant> lease =
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
        @DisplayName("a namespace that cannot be opened fails the build, and the acceptance "
            + "describes no filesystem")
        void anUnopenableNamespaceFailsTheBuild() throws IOException {
            // A regular file where the namespace's directory belongs. The registry wraps the
            // failure as an UncheckedIOException, which is not an IOException - so a runner
            // that caught only the latter would lose the run with no state to read back.
            Files.writeString(root.resolve("acme"), "not a directory");

            Response accepted = call(build, "POST", "acme");

            assertThat(accepted.status()).isEqualTo(202);
            assertThat(accepted.body())
                .as("the path it will fail on belongs in the log, not in the acceptance")
                .doesNotContain("acme/graph");
            Object json = awaitState("acme", GraphBuildRunner.STATE_FAILED);
            assertThat(Json.stringAt(json, "error")).isNotBlank();
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
            assertThatThrownBy(() -> new GraphBuildStatusEndpoint(null))
                .isInstanceOf(NullPointerException.class);
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
    }

    /** A build that waits at the door, so the state while one is running can be exercised. */
    private static GraphBuildRunner.Build gate(CountDownLatch entered, CountDownLatch release) {
        return (namespace, cancelRequested) -> {
            entered.countDown();
            await(release);
            return new excalibur.api.bootstrap.GraphBuildService.BuildOutcome(
                false, 0, 0, 0, 0, 0, 0);
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
