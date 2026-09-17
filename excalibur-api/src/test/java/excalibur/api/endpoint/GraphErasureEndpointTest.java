package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.mcp.Json;

import io.excalibur.config.Config;
import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.tenancy.MappingBudget;

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
 * {@code POST /v1/graph/erasure} - the one endpoint whose job is to destroy data.
 *
 * <p>Two properties carry the weight. The first is that an obligation this endpoint reports as
 * accepted is durable: a client told "accepted" and then losing the request is the failure nothing
 * downstream would ever notice, because the data simply stays. The second is that a request it
 * cannot read exactly is refused rather than guessed at - a scope read wrongly does not fail
 * safely, it erases something the caller did not ask about.</p>
 */
@DisplayName("Graph erasure endpoint")
class GraphErasureEndpointTest {

    private Path root;
    private GraphTenants tenants;
    private GraphErasureEndpoint endpoint;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        Config config = new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
        tenants = new GraphTenants(root, config, MappingBudget.detect(), 4);
        endpoint = new GraphErasureEndpoint(tenants);
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

    private Response post(String body) throws IOException {
        return call("POST", body, null);
    }

    private Response call(String method, String body, String namespace) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Headers requestHeaders = new Headers();
        if (namespace != null) {
            requestHeaders.set(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/erasure"));
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(out);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("accepting an obligation")
    class Accepting {

        @Test
        @DisplayName("an entity-scoped request is accepted and given an id")
        void entityScopeIsAccepted() throws IOException {
            Response response = post("{\"scope\": \"entity\", \"entity_id\": 42}");

            assertThat(response.status())
                .as("202: recorded, not necessarily yet carried out")
                .isEqualTo(202);
            Object json = response.json();
            assertThat(Json.at(json, "accepted")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(json, "request_id")).isInstanceOf(Long.class);
            assertThat(Json.stringAt(json, "namespace")).isNotNull();
            assertThat(Json.stringAt(json, "detail")).isNotNull();
        }

        @Test
        @DisplayName("a source-scoped request is accepted")
        void sourceScopeIsAccepted() throws IOException {
            Response response = post(
                "{\"scope\": \"source\", \"source_type\": \"DOCUMENT\", \"source_id\": 1234}");

            assertThat(response.status()).isEqualTo(202);
            assertThat(Json.at(response.json(), "accepted")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("an extractor-scoped request is accepted")
        void extractorScopeIsAccepted() throws IOException {
            Response response = post("{\"scope\": \"extractor\", \"version\": 7}");

            assertThat(response.status()).isEqualTo(202);
            assertThat(Json.at(response.json(), "accepted")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("scope names are matched without regard to case or surrounding space")
        void scopeNamesAreNormalised() throws IOException {
            assertThat(post("{\"scope\": \"  ENTITY \", \"entity_id\": 1}").status())
                .isEqualTo(202);
            assertThat(post("{\"scope\": \"Source\", \"source_type\": \"document\", "
                + "\"source_id\": 1}").status()).isEqualTo(202);
        }

        @Test
        @DisplayName("every request is counted as outstanding until a compaction satisfies it")
        void obligationsAccumulate() throws IOException {
            long first = (Long) Json.at(post(
                "{\"scope\": \"entity\", \"entity_id\": 1}").json(), "outstanding");
            long second = (Long) Json.at(post(
                "{\"scope\": \"entity\", \"entity_id\": 2}").json(), "outstanding");

            assertThat(second)
                .as("an accepted obligation that is not yet carried out stays counted")
                .isGreaterThan(first);
        }

        @Test
        @DisplayName("request ids are distinct, so a client can refer to its own request")
        void requestIdsAreDistinct() throws IOException {
            Object a = Json.at(post("{\"scope\": \"entity\", \"entity_id\": 1}").json(),
                "request_id");
            Object b = Json.at(post("{\"scope\": \"entity\", \"entity_id\": 2}").json(),
                "request_id");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("compact:true against a graph that was never built says so rather than "
            + "reporting a rewrite")
        void compactNowOnAnUnbuiltGraphIsHonest() throws IOException {
            // The obligation is still recorded - it has to be, because the graph may be built
            // later and the data would then appear. What must not happen is the endpoint
            // answering "compacted" for a rewrite that never ran: the caller asked precisely so
            // it could say the data is gone.
            Response response = post(
                "{\"scope\": \"entity\", \"entity_id\": 42, \"compact\": true}");

            assertThat(response.status()).isEqualTo(202);
            assertThat(Json.at(response.json(), "accepted")).isEqualTo(Boolean.TRUE);
            assertThat(Json.at(response.json(), "compacted")).isEqualTo(Boolean.FALSE);
            assertThat(Json.at(response.json(), "edges_removed")).isEqualTo(0L);
            assertThat(Json.stringAt(response.json(), "detail"))
                .as("the reason the rewrite did not run is reported, not hidden")
                .contains("no graph has been built");
            assertThat(Json.at(response.json(), "outstanding"))
                .as("an obligation that could not be discharged stays outstanding")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("the numeric fields are numbers, not quoted strings")
        void numericFieldsAreNumbers() throws IOException {
            Object json = post("{\"scope\": \"entity\", \"entity_id\": 42}").json();

            assertThat(Json.at(json, "request_id")).isInstanceOf(Long.class);
            assertThat(Json.at(json, "edges_removed")).isInstanceOf(Long.class);
            assertThat(Json.at(json, "requests_satisfied")).isInstanceOf(Long.class);
            assertThat(Json.at(json, "requests_withheld"))
                .as("a request the node could not reach stays outstanding, and a caller under "
                    + "an obligation has to be able to see that rather than read the silence "
                    + "as completion")
                .isInstanceOf(Long.class);
            assertThat(Json.at(json, "outstanding")).isInstanceOf(Long.class);
            assertThat(Json.at(json, "accepted")).isInstanceOf(Boolean.class);
            assertThat(Json.at(json, "compacted")).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("without compact:true the policy decides, and the answer says which happened")
        void compactionIsReportedEitherWay() throws IOException {
            Response response = post("{\"scope\": \"entity\", \"entity_id\": 42}");

            assertThat(Json.at(response.json(), "compacted")).isInstanceOf(Boolean.class);
        }
    }

    @Nested
    @DisplayName("refusing a request it cannot read")
    class Refusing {

        @Test
        @DisplayName("a GET is refused")
        void onlyPostIsAllowed() throws IOException {
            Response response = call("GET", "", null);

            assertThat(response.status()).isEqualTo(405);
            assertThat(Json.stringAt(response.json(), "error")).contains("POST");
        }

        @Test
        @DisplayName("a request naming no scope is refused")
        void missingScopeIsRefused() throws IOException {
            Response response = post("{\"entity_id\": 42}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("scope");
        }

        @Test
        @DisplayName("a request naming an unknown scope is refused")
        void unknownScopeIsRefused() throws IOException {
            Response response = post("{\"scope\": \"everything\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("everything");
        }

        @Test
        @DisplayName("a blank scope is refused rather than treated as absent")
        void blankScopeIsRefused() throws IOException {
            assertThat(post("{\"scope\": \"   \"}").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("each scope is refused when its own required field is missing")
        void missingScopeFieldsAreRefused() throws IOException {
            // A scope accepted without its target would be an obligation to erase nothing,
            // reported to the client as accepted.
            Response entity = post("{\"scope\": \"entity\"}");
            assertThat(entity.status()).isEqualTo(400);
            assertThat(Json.stringAt(entity.json(), "error")).contains("entity_id");

            Response source = post("{\"scope\": \"source\", \"source_type\": \"DOCUMENT\"}");
            assertThat(source.status()).isEqualTo(400);
            assertThat(Json.stringAt(source.json(), "error")).contains("source_id");

            Response extractor = post("{\"scope\": \"extractor\"}");
            assertThat(extractor.status()).isEqualTo(400);
            assertThat(Json.stringAt(extractor.json(), "error")).contains("version");
        }

        @Test
        @DisplayName("a source scope with no source_type is refused")
        void missingSourceTypeIsRefused() throws IOException {
            Response response = post("{\"scope\": \"source\", \"source_id\": 1}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("source_type");
        }

        @Test
        @DisplayName("an unknown source_type is refused and the known ones are listed")
        void unknownSourceTypeIsRefused() throws IOException {
            Response response = post(
                "{\"scope\": \"source\", \"source_type\": \"SPREADSHEET\", \"source_id\": 1}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error"))
                .contains("SPREADSHEET")
                .contains("DOCUMENT");
        }

        @Test
        @DisplayName("an extractor version outside the range the store holds is refused, "
            + "not silently truncated")
        void anOutOfRangeExtractorVersionIsRefused() throws IOException {
            // The store keeps the version in 32 bits. Narrowing 2^32 + 1 gives 1, so a request
            // that names a version this node has never heard of would be accepted as an
            // obligation to erase everything version 1 ever extracted - destructively, and with
            // "accepted": true reported to the caller.
            Response response = post("{\"scope\": \"extractor\", \"version\": 4294967297}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("version");
        }

        @Test
        @DisplayName("a negative extractor version is refused")
        void aNegativeExtractorVersionIsRefused() throws IOException {
            Response response = post("{\"scope\": \"extractor\", \"version\": -3}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("version");
        }

        @Test
        @DisplayName("the largest version the store can hold is still accepted")
        void theBoundaryItselfIsNotRefused() throws IOException {
            assertThat(post("{\"scope\": \"extractor\", \"version\": 2147483647}").status())
                .isEqualTo(202);
        }

        @Test
        @DisplayName("a namespace that cannot be opened is answered, and answered without "
            + "describing the filesystem")
        void anUnopenableNamespaceIsStillAnswered() throws IOException {
            // A regular file where the namespace's directory belongs. The registry wraps the
            // failure as an UncheckedIOException, which is not an IOException - so a handler
            // that catches only the latter never answers at all, and the caller sees the
            // connection close rather than a status.
            Files.writeString(root.resolve("acme"), "not a directory");

            Response response = call("POST", "{\"scope\": \"entity\", \"entity_id\": 1}", "acme");

            assertThat(response.status()).isEqualTo(500);
            assertThat(Json.stringAt(response.json(), "error"))
                .as("the path it failed on belongs in the log, not in the response")
                .isEqualTo("Internal Server Error");
        }

        @Test
        @DisplayName("an error message quoting the caller's text stays valid JSON")
        void errorMessagesAreEscaped() throws IOException {
            Response response = post("{\"scope\": \"we\\\"ird\\\\\"}");

            assertThat(response.status()).isEqualTo(400);
            // Parsing at all is the assertion: an unescaped quote would end the error string
            // early and leave the rest of the body as garbage.
            assertThat(Json.stringAt(response.json(), "error")).contains("we\"ird");
        }
    }

    @Nested
    @DisplayName("namespaces")
    class Namespaces {

        @Test
        @DisplayName("no header means the shared namespace")
        void noHeaderIsShared() throws IOException {
            Response response = call("POST", "{\"scope\": \"entity\", \"entity_id\": 1}", null);

            assertThat(response.status()).isEqualTo(202);
            assertThat(Json.stringAt(response.json(), "namespace"))
                .isEqualTo(io.excalibur.tenancy.NamespaceId.SHARED.value());
        }

        @Test
        @DisplayName("a request is erased from the namespace it names, and no other")
        void obligationsAreScopedToTheirNamespace() throws IOException {
            call("POST", "{\"scope\": \"entity\", \"entity_id\": 1}", "tenant-a");
            call("POST", "{\"scope\": \"entity\", \"entity_id\": 2}", "tenant-a");
            Response b = call("POST", "{\"scope\": \"entity\", \"entity_id\": 3}", "tenant-b");

            assertThat(Json.stringAt(b.json(), "namespace")).isEqualTo("tenant-b");
            assertThat(Json.at(b.json(), "outstanding"))
                .as("tenant-b carries only its own obligation, not tenant-a's two")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("an unusable namespace is refused rather than silently shared")
        void invalidNamespaceIsRefused() throws IOException {
            Response response = call("POST", "{\"scope\": \"entity\", \"entity_id\": 1}",
                "../escape");

            assertThat(response.status())
                .as("a namespace that could escape its directory must not fall back to shared")
                .isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("naming a person rather than an integer")
    class ByName {

        /** A term of the person's name, as the segment files hold it. */
        private static final String NAME_TERM = "quazzlethorpe";

        /** Stages one person's facts and prose in the shared namespace and builds the graph. */
        private int buildSharedGraph() throws IOException {
            GraphStagingStore staging = tenants.shared().staging();
            int ada = staging.entityIdOf("Ada Quazzlethorpe");
            staging.append(ExtractedTriple.alwaysValid("Ada Quazzlethorpe", "knows",
                "Bob Ordinary", new Provenance(SourceType.DOCUMENT, 42, 90, 1)));
            staging.appendEntityText(ada, "Ada Quazzlethorpe designed the engine",
                new Provenance(SourceType.DOCUMENT, 42, 90, 1));
            staging.flush();
            assertThat(tenants.shared().buildService().build().published()).isTrue();
            return ada;
        }

        @Test
        @DisplayName("an entity named rather than numbered is erased from the served graph")
        void aNamedEntityIsErased() throws IOException {
            // The id is internal and nothing hands one out, so requiring it meant "erase
            // everything about this person" could only be asked by somebody who had already
            // seen them in an answer and read the id out of its free text.
            Path directory = tenants.shared().directory();
            buildSharedGraph();
            assertThat(anyFileContains(directory, NAME_TERM)).isTrue();

            Response response = post(
                "{\"scope\": \"entity\", \"entity_name\": \"Ada Quazzlethorpe\", "
                    + "\"compact\": true}");

            assertThat(response.status()).isEqualTo(202);
            assertThat(Json.at(response.json(), "requests_satisfied")).isEqualTo(1L);
            assertThat(anyFileContains(directory, NAME_TERM))
                .as("the person the caller named is the person whose bytes went")
                .isFalse();
        }

        @Test
        @DisplayName("the name is matched exactly, not the way a search would match it")
        void aPartialNameResolvesToNobody() throws IOException {
            buildSharedGraph();

            Response response = post(
                "{\"scope\": \"entity\", \"entity_name\": \"Quazzlethorpe\"}");

            assertThat(response.status())
                .as("an erasure that accepted near matches would erase the wrong person")
                .isEqualTo(404);
            assertThat(Json.stringAt(response.json(), "error")).contains("Quazzlethorpe");
        }

        @Test
        @DisplayName("a name is resolved before the graph has ever been built")
        void aStagedNameResolves() throws IOException {
            GraphStagingStore staging = tenants.shared().staging();
            staging.entityIdOf("Ada Quazzlethorpe");
            staging.flush();

            Response response = post(
                "{\"scope\": \"entity\", \"entity_name\": \"ada quazzlethorpe\"}");

            assertThat(response.status())
                .as("an obligation about somebody whose facts are staged is just as real")
                .isEqualTo(202);
            assertThat(Json.at(response.json(), "accepted")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("a name two entities share is refused with the ids to choose between")
        void anAmbiguousNameIsRefused() throws IOException {
            // A graph built before a name was re-interned serves the old id under it while
            // staging has assigned a new one. Picking either would be erasing somebody on a
            // guess, so the caller is handed both and asked which.
            int ada = buildSharedGraph();
            new LabelDictionaryWriter()
                .put(ada, "Ada Quazzlethorpe")
                .put(ada + 50, "Ada Quazzlethorpe")
                .write(tenants.shared().directory(), "entities");

            Response response = post(
                "{\"scope\": \"entity\", \"entity_name\": \"Ada Quazzlethorpe\"}");

            assertThat(response.status()).isEqualTo(409);
            assertThat(Json.at(response.json(), "candidates"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);
        }

        @Test
        @DisplayName("a name and an id together are refused rather than reconciled")
        void bothIsRefused() throws IOException {
            Response response = post(
                "{\"scope\": \"entity\", \"entity_id\": 3, \"entity_name\": \"Ada\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("not both");
        }

        @Test
        @DisplayName("neither a name nor an id names nobody, and says so")
        void neitherIsRefused() throws IOException {
            Response response = post("{\"scope\": \"entity\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("entity_name");
        }
    }

    /** Whether any file at or under {@code root} holds {@code text} as bytes. */
    private static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                byte[] haystack = Files.readAllBytes(path);
                outer:
                for (int i = 0; i + needle.length <= haystack.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (haystack[i + j] != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
