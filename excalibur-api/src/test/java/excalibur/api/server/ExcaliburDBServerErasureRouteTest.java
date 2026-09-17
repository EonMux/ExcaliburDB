package excalibur.api.server;

import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.Source;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.EpochTickScheduler;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code POST /v1/erasure} is actually reachable on a running server.
 *
 * <p>A route is registered by one line in one method, and every unit test of the endpoint passes
 * whether or not that line exists - which is how the graph routes once came to answer 404 on every
 * deployment. For an endpoint whose job is to destroy data on request, unreachable is the worst
 * possible failure: the caller is told nothing is there to answer them, and the data stays.
 */
@DisplayName("ExcaliburDBServer erasure route")
class ExcaliburDBServerErasureRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private final Map<String, String> storage = new LinkedHashMap<>();
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private DocumentRegistry registry;
    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() {
        SegmentCatalog catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        storage.clear();
        registry = new DocumentRegistry();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void ingest(String sourceId, String documentId, int chunks) {
        DocumentMetadata.Builder metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(documentId.hashCode())
            .contentLength(100);
        for (int i = 0; i < chunks; i++) {
            String chunkId = sourceId + ":" + documentId + ":chunk:" + i;
            storage.put(chunkId, "text " + i);
            metadata.addChunkId(chunkId);
        }
        registry.register(metadata.build());
    }

    private void startWithErasure() throws IOException {
        UpsertHandler.StorageAdapter adapter =
            key -> storage.remove(new String(key, StandardCharsets.UTF_8)) != null;
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withDocumentErasure(
            new DocumentErasureService(registry, adapter, null, null));
        server.start();
    }

    private void startWithoutErasure() throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    private record Response(int status, String body) {
    }

    private Response post(String body, String token) throws IOException {
        return call("POST", body, token);
    }

    private Response postInNamespace(String body, String namespace) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + "/v1/erasure")
            .toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        connection.setRequestProperty(GraphTenants.NAMESPACE_HEADER, namespace);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = stream == null
            ? ""
            : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, responseBody);
    }

    private Response call(String method, String body, String token) throws IOException {
        return call("/v1/erasure", method, body, token);
    }

    private Response call(String path, String method, String body, String token)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setDoOutput(!body.isEmpty());
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (!body.isEmpty()) {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = stream == null
            ? ""
            : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, responseBody);
    }

    @Test
    @DisplayName("erases a document over HTTP")
    void erasesOverHttp() throws Exception {
        startWithErasure();
        ingest("docs", "handbook.md", 2);

        Response response = post(
            "{\"source_id\": \"docs\", \"document_id\": \"handbook.md\"}", TOKEN);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("documents_erased").asInt()).isEqualTo(1);
        assertThat(body.get("chunks_erased").asInt()).isEqualTo(2);
        assertThat(storage)
            .as("the reply is only worth what actually left storage")
            .isEmpty();
        assertThat(registry.contains("docs", "handbook.md")).isFalse();
    }

    @Test
    @DisplayName("requires a bearer token like every other mutation")
    void requiresAToken() throws Exception {
        startWithErasure();
        ingest("docs", "handbook.md", 1);

        assertThat(post("{\"source_id\": \"docs\", \"document_id\": \"handbook.md\"}", null)
            .status()).isEqualTo(401);
        assertThat(post("{\"source_id\": \"docs\", \"document_id\": \"handbook.md\"}", "wrong")
            .status()).isEqualTo(401);
        assertThat(storage)
            .as("an unauthenticated request must not have erased anything on its way to being "
                + "refused")
            .hasSize(1);
    }

    @Test
    @DisplayName("a request naming a namespace is refused rather than erasing from the shared corpus")
    void refusesANamedNamespace() throws Exception {
        // The worst shape this mistake can take. A caller erasing "tenant-a's copy" of a
        // document would have had the shared one removed instead, told 200, and had no way to
        // tell - an erasure is not reversible, so being wrong here costs data that no retry
        // brings back.
        startWithErasure();
        ingest("docs", "handbook.md", 2);

        Response response = postInNamespace(
            "{\"source_id\": \"docs\", \"document_id\": \"handbook.md\"}", "tenant-a");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains(GraphTenants.NAMESPACE_HEADER);
        assertThat(storage)
            .as("a refused erasure must not have erased anything")
            .hasSize(2);
    }

    @Test
    @DisplayName("is 404 when the node was built without an erasure service")
    void notRegisteredWithoutAService() throws Exception {
        // Absent rather than present-and-answering-that-it-cannot: a caller under an obligation
        // gets something to escalate instead of a 200 that removed nothing.
        startWithoutErasure();

        assertThat(post("{\"source_id\": \"docs\", \"document_id\": \"a.md\"}", TOKEN).status())
            .isEqualTo(404);
    }

    @Test
    @DisplayName("a source-scoped erasure takes the source out of the crawl")
    void sourceScopeDeregisters() throws Exception {
        // The endpoint can only do this if the server hands it the source registry, which is one
        // line that every unit test of the endpoint passes without.
        startWithErasure();
        ingest("docs", "a.md", 1);
        ingest("docs", "b.md", 1);
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn("docs");
        server.getIngestSourceRegistry().register(source);

        Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}", TOKEN);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("documents_erased").asInt()).isEqualTo(2);
        assertThat(body.get("source_deregistered").asBoolean())
            .as("erasing a source while it stays scheduled is a pause, not an erasure")
            .isTrue();
        assertThat(server.getIngestSourceRegistry().isRegistered("docs")).isFalse();
        assertThat(storage).isEmpty();
    }

    @Test
    @DisplayName("anything but POST is refused")
    void onlyPostErases() throws Exception {
        startWithErasure();

        assertThat(call("GET", "", TOKEN).status()).isEqualTo(405);
    }

    @Test
    @DisplayName("a crawl can be stopped over HTTP without erasing what it indexed")
    void deregisterRouteIsReachable() throws Exception {
        // The alternative to this route is erasing the source, so an operator wanting a runaway
        // crawl to stop would have to destroy its output to do it.
        startWithErasure();
        ingest("docs", "a.md", 1);
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn("docs");
        server.getIngestSourceRegistry().register(source);

        Response response = call("/v1/ingest", "DELETE",
            "{\"source_id\": \"docs\"}", TOKEN);

        assertThat(response.status()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(response.body()).get("deregistered").asBoolean())
            .isTrue();
        assertThat(server.getIngestSourceRegistry().isRegistered("docs")).isFalse();
        assertThat(storage)
            .as("stopping a crawl must leave the documents it already indexed alone")
            .hasSize(1);
    }

    @Test
    @DisplayName("stopping a crawl needs a token like every other mutation")
    void deregisterRequiresAToken() throws Exception {
        startWithErasure();
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn("docs");
        server.getIngestSourceRegistry().register(source);

        assertThat(call("/v1/ingest", "DELETE", "{\"source_id\": \"docs\"}", null).status())
            .isEqualTo(401);
        assertThat(server.getIngestSourceRegistry().isRegistered("docs")).isTrue();
    }
}
