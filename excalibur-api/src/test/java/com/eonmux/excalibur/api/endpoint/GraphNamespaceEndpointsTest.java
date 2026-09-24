package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two routes that make a namespace something an operator can see and get rid of.
 *
 * <p>A namespace comes into being because somebody put its name in a header. Nothing listed what
 * that had created and nothing removed one, so an offboarded tenant's graph, staged facts and
 * erasure log stayed on the node for the life of the deployment - and an operator could not even
 * find out. These two routes close that, and between them they have one property that matters
 * more than any other: the namespace in a URL path is a string a client chose, and it must never
 * be able to point the delete at anything but a namespace directory under the graph root.</p>
 */
@DisplayName("Graph namespace endpoints")
class GraphNamespaceEndpointsTest {

    private Path root;
    private GraphTenants tenants;
    private GraphNamespacesEndpoint list;
    private GraphNamespaceDeleteEndpoint delete;
    private GraphNamespaceCreateEndpoint put;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        open(Map.of());
    }

    /** Reopens the registry over the same root with extra configuration. */
    private void open(Map<String, String> extra) throws IOException {
        if (tenants != null) {
            tenants.close();
        }
        Map<String, String> props = new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"));
        props.putAll(extra);
        tenants = new GraphTenants(root, new Config(props), MappingBudget.detect(), 4);
        list = new GraphNamespacesEndpoint(tenants);
        delete = new GraphNamespaceDeleteEndpoint(tenants);
        put = new GraphNamespaceCreateEndpoint(tenants);
    }

    @AfterEach
    void tearDown() {
        if (tenants != null) {
            tenants.close();
        }
    }

    /** One captured HTTP response. */
    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /** Creates a namespace the way a request does, and stages one fact into it. */
    private void create(String namespace) throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease =
                 tenants.borrow(NamespaceId.of(namespace))) {
            lease.resource().staging().append(ExtractedTriple.alwaysValid(
                "Ada", "knows", "Babbage", from(1)));
            lease.resource().staging().flush();
        }
    }

    private Response call(HttpHandler handler, String method, String path) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(out);

        handler.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> namespacesOf(Response response) {
        return (List<Object>) Json.at(response.json(), "namespaces");
    }

    private static Object entryFor(Response response, String namespace) {
        return namespacesOf(response).stream()
            .filter(each -> namespace.equals(Json.stringAt(each, "namespace")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(namespace + " is not in the listing"));
    }

    @Nested
    @DisplayName("GET /v1/graph/namespaces")
    class Listing {

        @Test
        @DisplayName("every namespace on disk is listed, resident or not")
        void everyNamespaceIsListed() throws IOException {
            create("acme");
            assertThat(tenants.invalidate(NamespaceId.of("acme"))).isTrue();
            create("beta");

            Response response = call(list, "GET", "/v1/graph/namespaces");

            assertThat(response.status()).isEqualTo(200);
            assertThat(namespacesOf(response)).hasSize(3);
            assertThat(Json.at(response.json(), "count")).isEqualTo(3L);
            assertThat(Json.at(response.json(), "total")).isEqualTo(3L);
            assertThat(Json.at(response.json(), "next_cursor"))
                .as("everything fits on one page, so there is nothing to continue from")
                .isNull();
            assertThat(Json.at(entryFor(response, "acme"), "open"))
                .as("an evicted namespace exists exactly as much as a resident one")
                .isEqualTo(Boolean.FALSE);
            assertThat(Json.at(entryFor(response, "beta"), "open")).isEqualTo(Boolean.TRUE);
            assertThat(Json.stringAt(entryFor(response, "shared"), "namespace"))
                .isEqualTo("shared");
        }

        @Test
        @DisplayName("each entry says enough for an operator to act on it")
        void eachEntryCarriesWhatAnOperatorNeeds() throws IOException {
            create("acme");
            try (NamespaceRegistry.Lease<GraphTenant> lease =
                     tenants.borrow(NamespaceId.of("acme"))) {
                lease.resource().erasureService().submit(new ErasureScope.Entity(0));
            }

            Object acme = entryFor(call(list, "GET", "/v1/graph/namespaces"), "acme");

            assertThat(Json.at(acme, "built")).isEqualTo(Boolean.FALSE);
            assertThat(Json.at(acme, "staged_facts")).isEqualTo(1L);
            assertThat(Json.at(acme, "outstanding_erasures")).isEqualTo(1L);
            assertThat(Json.at(acme, "error")).isNull();
        }

        @Test
        @DisplayName("a count that could not be read is reported as unknown, never as zero")
        void anUnreadableCountIsNotZero() throws IOException {
            create("acme");
            assertThat(tenants.invalidate(NamespaceId.of("acme"))).isTrue();
            Files.write(root.resolve("acme")
                    .resolve("graph" + GraphTenant.STAGING_SUFFIX)
                    .resolve(GraphTenant.ERASURE_LOG),
                "not an erasure log".getBytes(StandardCharsets.UTF_8));

            Object acme = entryFor(call(list, "GET", "/v1/graph/namespaces"), "acme");

            assertThat(Json.at(acme, "outstanding_erasures"))
                .as("zero reads as safe to offboard, and this node cannot say that")
                .isNull();
            assertThat(Json.stringAt(acme, "error")).contains("erasure log");
        }

        @Test
        @DisplayName("only GET lists")
        void onlyGetLists() throws IOException {
            assertThat(call(list, "POST", "/v1/graph/namespaces").status()).isEqualTo(405);
        }

        @Test
        @DisplayName("a limit pages the inventory, and next_cursor walks it to the end")
        void pagesWalkTheInventory() throws IOException {
            for (String name : List.of("acme", "beta", "gamma")) {
                tenants.create(NamespaceId.of(name));
            }

            Response first = call(list, "GET", "/v1/graph/namespaces?limit=2");
            assertThat(first.status()).isEqualTo(200);
            assertThat(namespacesOf(first)).extracting(each -> Json.stringAt(each, "namespace"))
                .containsExactly("acme", "beta");
            assertThat(Json.at(first.json(), "count")).isEqualTo(2L);
            assertThat(Json.at(first.json(), "total")).isEqualTo(4L);
            assertThat(Json.stringAt(first.json(), "next_cursor")).isEqualTo("beta");

            Response second = call(list, "GET", "/v1/graph/namespaces?limit=2&cursor=beta");
            assertThat(namespacesOf(second)).extracting(each -> Json.stringAt(each, "namespace"))
                .containsExactly("gamma", "shared");
            assertThat(Json.at(second.json(), "next_cursor")).isNull();
        }

        @Test
        @DisplayName("a limit or cursor it cannot use is a 400, not a guess")
        void badPagingIsRefused() throws IOException {
            for (String query : List.of("limit=0", "limit=1001", "limit=ten", "cursor=..%2Fetc",
                "cursor=UPPER")) {
                Response response = call(list, "GET", "/v1/graph/namespaces?" + query);

                assertThat(response.status()).as(query).isEqualTo(400);
                assertThat(Json.stringAt(response.json(), "code")).isEqualTo("invalid_request");
            }
            assertThat(call(list, "GET", "/v1/graph/namespaces?limit=1000").status())
                .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("PUT /v1/graph/namespaces/{namespace}")
    class Creating {

        @Test
        @DisplayName("creating one answers 201, and again answers 200 with nothing created")
        void createIsIdempotent() throws IOException {
            Response created = call(put, "PUT", "/v1/graph/namespaces/acme");

            assertThat(created.status()).isEqualTo(201);
            assertThat(Json.stringAt(created.json(), "namespace")).isEqualTo("acme");
            assertThat(Json.at(created.json(), "created")).isEqualTo(Boolean.TRUE);
            assertThat(root.resolve("acme")).isDirectory();
            assertThat(entryFor(call(list, "GET", "/v1/graph/namespaces"), "acme")).isNotNull();

            Response again = call(put, "PUT", "/v1/graph/namespaces/acme/");
            assertThat(again.status()).isEqualTo(200);
            assertThat(Json.at(again.json(), "created")).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("the shared namespace always exists, so creating it creates nothing")
        void theSharedNamespaceExists() throws IOException {
            Response response = call(put, "PUT", "/v1/graph/namespaces/shared");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "created")).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("a name that is not a namespace is refused before it reaches the disk")
        void anUnusableNameIsRefused() throws IOException {
            for (String hostile : List.of("/v1/graph/namespaces/..",
                "/v1/graph/namespaces/%2e%2e%2foutside", "/v1/graph/namespaces/UPPER",
                "/v1/graph/namespaces/a/b", "/v1/graph/namespaces//")) {
                Response response = call(put, "PUT", hostile);

                assertThat(response.status()).as(hostile).isEqualTo(400);
                assertThat(Json.stringAt(response.json(), "code")).isEqualTo("invalid_namespace");
            }
            assertThat(root.resolveSibling("outside")).doesNotExist();
        }

        @Test
        @DisplayName("past the cap, creating is refused with its own code")
        void theCapIsAnswered() throws IOException {
            open(Map.of("excalibur.graph.max-namespaces", "1"));
            assertThat(call(put, "PUT", "/v1/graph/namespaces/first").status()).isEqualTo(201);

            Response refused = call(put, "PUT", "/v1/graph/namespaces/second");

            assertThat(refused.status()).isEqualTo(429);
            assertThat(Json.stringAt(refused.json(), "code"))
                .isEqualTo("namespace_limit_reached");
            assertThat(root.resolve("second")).doesNotExist();
            assertThat(call(put, "PUT", "/v1/graph/namespaces/first").status())
                .as("an existing namespace is not a new one")
                .isEqualTo(200);
        }

        @Test
        @DisplayName("creating is the one way in when explicit creation is required")
        void explicitCreationIsTheWayIn() throws IOException {
            open(Map.of("excalibur.graph.require-explicit-namespaces", "true"));

            assertThat(call(put, "PUT", "/v1/graph/namespaces/acme").status()).isEqualTo(201);
            create("acme");
        }

        @Test
        @DisplayName("only PUT creates, and only with a namespace named")
        void theRouteIsNarrow() throws IOException {
            assertThat(call(put, "GET", "/v1/graph/namespaces/acme").status()).isEqualTo(405);
            assertThat(call(put, "PUT", "/v1/graph/namespaces").status()).isEqualTo(405);
            assertThat(root.resolve("acme")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("DELETE /v1/graph/namespaces/{namespace}")
    class Dropping {

        @Test
        @DisplayName("dropping one says exactly what it destroyed")
        void droppingSaysWhatWentWithIt() throws IOException {
            create("acme");

            Response response = call(delete, "DELETE", "/v1/graph/namespaces/acme");

            assertThat(response.status()).isEqualTo(200);
            Object json = response.json();
            assertThat(Json.stringAt(json, "namespace")).isEqualTo("acme");
            assertThat(Json.at(json, "dropped")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "was_open")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "had_graph")).isEqualTo(Boolean.FALSE);
            assertThat(Json.at(json, "staged_facts")).isEqualTo(1L);
            assertThat(Json.at(json, "discarded_erasures")).isEqualTo(0L);
            assertThat((Long) Json.at(json, "files_deleted")).isPositive();
            assertThat((Long) Json.at(json, "bytes_deleted")).isPositive();

            assertThat(root.resolve("acme")).doesNotExist();
        }

        @Test
        @DisplayName("a namespace that does not exist is a 404")
        void anAbsentNamespaceIs404() throws IOException {
            Response response = call(delete, "DELETE", "/v1/graph/namespaces/never-existed");

            assertThat(response.status()).isEqualTo(404);
            assertThat(Json.stringAt(response.json(), "error")).contains("never-existed");
        }

        @Test
        @DisplayName("the shared namespace is a 409, because it cannot be in that state")
        void theSharedNamespaceIs409() throws IOException {
            Response response = call(delete, "DELETE", "/v1/graph/namespaces/shared");

            assertThat(response.status())
                .as("409, not 403: no credential would make this allowed, and not 404: it is "
                    + "plainly there. The resource exists and cannot be in the requested state")
                .isEqualTo(409);
            assertThat(root.resolve("shared")).isDirectory();
        }

        @Test
        @DisplayName("only DELETE drops, and only with a namespace named")
        void theRouteIsNarrow() throws IOException {
            create("acme");

            assertThat(call(delete, "GET", "/v1/graph/namespaces/acme").status()).isEqualTo(405);
            assertThat(call(delete, "DELETE", "/v1/graph/namespaces").status())
                .as("a DELETE on the collection would be 'drop every tenant on this node'")
                .isEqualTo(405);
            assertThat(root.resolve("acme")).isDirectory();
        }
    }

    @Nested
    @DisplayName("refusing a namespace that is not one")
    class Containment {

        @Test
        @DisplayName("a path that tries to escape the graph root is refused, not resolved")
        void aTraversalIsRefused() throws IOException {
            Path sibling = root.resolveSibling("outside-the-root");
            Files.createDirectories(sibling);
            Files.writeString(sibling.resolve("keepme.txt"), "untouched");

            for (String hostile : new String[] {
                "/v1/graph/namespaces/..",
                "/v1/graph/namespaces/../outside-the-root",
                "/v1/graph/namespaces/%2e%2e%2foutside-the-root",
                "/v1/graph/namespaces/acme/deeper",
                "/v1/graph/namespaces/%2fetc%2fpasswd",
                "/v1/graph/namespaces/UPPER",
                "/v1/graph/namespaces/" + "x".repeat(200)}) {

                Response response = call(delete, "DELETE", hostile);

                assertThat(response.status())
                    .as("'%s' must never be resolved into a directory", hostile)
                    .isEqualTo(400);
            }

            assertThat(sibling.resolve("keepme.txt"))
                .as("nothing outside the graph root is reachable from this route")
                .exists();
            assertThat(root).isDirectory();
        }

        @Test
        @DisplayName("an empty namespace segment is a bad request rather than the collection")
        void anEmptySegmentIsRefused() throws IOException {
            assertThat(call(delete, "DELETE", "/v1/graph/namespaces//").status())
                .isEqualTo(400);
        }
    }
}
