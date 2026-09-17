package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import excalibur.api.bootstrap.GraphTenant;
import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.mcp.Json;

import io.excalibur.config.Config;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
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

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        Config config = new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
        tenants = new GraphTenants(root, config, MappingBudget.detect(), 4);
        list = new GraphNamespacesEndpoint(tenants);
        delete = new GraphNamespaceDeleteEndpoint(tenants);
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
