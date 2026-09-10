package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.mcp.Json;

import io.excalibur.config.Config;
import io.excalibur.tenancy.MappingBudget;
import io.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /v1/graph/build} - publishing staged facts as the graph being served.
 *
 * <p>The case worth pinning is the empty one. A node that has staged nothing must keep serving
 * whatever graph it already had: replacing it with an empty store would turn every subsequent
 * question into a refusal, and the request that did it would have answered 200. So "nothing was
 * published" has to be reported as its own outcome rather than as a successful build of nothing.</p>
 */
@DisplayName("Graph build endpoint")
class GraphBuildEndpointTest {

    private Path root;
    private GraphTenants tenants;
    private GraphBuildEndpoint endpoint;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        Config config = new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
        tenants = new GraphTenants(root, config, MappingBudget.detect(), 4);
        endpoint = new GraphBuildEndpoint(tenants);
    }

    @AfterEach
    void tearDown() {
        if (tenants != null) {
            tenants.close();
        }
    }

    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private Response call(String method, String namespace) throws IOException {
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

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a namespace that cannot be opened is answered, and answered without "
        + "describing the filesystem")
    void anUnopenableNamespaceIsStillAnswered() throws IOException {
        // A regular file where the namespace's directory belongs. The registry wraps the
        // failure as an UncheckedIOException, which is not an IOException - so a handler that
        // catches only the latter never answers at all, and the caller sees the connection
        // close rather than a status.
        Files.writeString(root.resolve("acme"), "not a directory");

        Response response = call("POST", "acme");

        assertThat(response.status()).isEqualTo(500);
        assertThat(Json.stringAt(response.json(), "error"))
            .as("the path it failed on belongs in the log, not in the response")
            .isEqualTo("Internal Server Error");
    }

    @Test
    @DisplayName("a GET is refused")
    void onlyPostIsAllowed() throws IOException {
        Response response = call("GET", null);

        assertThat(response.status()).isEqualTo(405);
        assertThat(Json.stringAt(response.json(), "error")).contains("POST");
    }

    @Test
    @DisplayName("building with nothing staged reports that nothing was published")
    void buildingNothingPublishesNothing() throws IOException {
        Response response = call("POST", null);

        assertThat(response.status()).isEqualTo(200);
        Object json = response.json();
        assertThat(Json.at(json, "published"))
            .as("an empty build must not claim to have replaced the served graph")
            .isEqualTo(Boolean.FALSE);
        assertThat(Json.at(json, "staged_facts")).isEqualTo(0L);
        assertThat(Json.at(json, "edges")).isEqualTo(0L);
        assertThat(Json.at(json, "entities")).isEqualTo(0L);
    }

    @Test
    @DisplayName("the response reports every count the build produced")
    void everyCountIsReported() throws IOException {
        Object json = call("POST", null).json();

        // A missing field reads as null through the parser, so this pins the shape a client
        // decodes rather than only the two or three values this particular build happened to set.
        for (String field : new String[] {
            "published", "edges", "entities", "relations",
            "entities_with_text", "entities_with_vectors", "staged_facts", "namespace"}) {
            assertThat(Json.at(json, field)).as("field %s", field).isNotNull();
        }
        assertThat(Json.at(json, "published")).isInstanceOf(Boolean.class);
        assertThat(Json.at(json, "edges")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("no header builds the shared namespace")
    void noHeaderIsShared() throws IOException {
        Response response = call("POST", null);

        assertThat(Json.stringAt(response.json(), "namespace"))
            .isEqualTo(NamespaceId.SHARED.value());
    }

    @Test
    @DisplayName("a build names the namespace it built")
    void namespaceIsEchoed() throws IOException {
        assertThat(Json.stringAt(call("POST", "tenant-a").json(), "namespace"))
            .isEqualTo("tenant-a");
        assertThat(Json.stringAt(call("POST", "tenant-b").json(), "namespace"))
            .isEqualTo("tenant-b");
    }

    @Test
    @DisplayName("an unusable namespace is refused rather than silently built as shared")
    void invalidNamespaceIsRefused() throws IOException {
        Response response = call("POST", "../escape");

        assertThat(response.status()).isEqualTo(400);
        assertThat(Json.stringAt(response.json(), "error")).isNotBlank();
    }

    @Test
    @DisplayName("a build can be repeated without failing")
    void buildsAreRepeatable() throws IOException {
        // Every build invalidates the tenant so the next request reopens what was published.
        // Repeating it is how a caller recovers from a build it did not observe the result of.
        assertThat(call("POST", null).status()).isEqualTo(200);
        assertThat(call("POST", null).status()).isEqualTo(200);
        assertThat(call("POST", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a null tenants registry is refused at construction")
    void nullTenantsIsRefused() {
        assertThatThrownBy(() -> new GraphBuildEndpoint(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("tenants");
    }
}
