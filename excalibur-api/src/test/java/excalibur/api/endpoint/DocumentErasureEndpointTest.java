package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import excalibur.api.mcp.Json;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.source.Source;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /v1/erasure} - removing a document because someone asked.
 *
 * <p>Two properties carry the weight. A request that cannot be read exactly is refused rather than
 * guessed at, because an identifier read wrongly does not fail safely - it erases something the
 * caller did not name. And the reply distinguishes the removals it performed from the ones it
 * could not, so a caller under an obligation is never left inferring that a 200 means the content
 * is gone.
 */
@DisplayName("Document erasure endpoint")
class DocumentErasureEndpointTest {

    private final Map<String, String> storage = new LinkedHashMap<>();
    private DocumentRegistry registry;
    private InMemorySourceRegistry sources;
    private DocumentErasureEndpoint endpoint;

    @BeforeEach
    void setUp() {
        storage.clear();
        registry = new DocumentRegistry();
        sources = new InMemorySourceRegistry();
        UpsertHandler.StorageAdapter adapter = storageAdapter();
        // No lexical index: this test is about the endpoint's contract, and the service's own
        // tests cover what an index adds to it.
        endpoint = new DocumentErasureEndpoint(
            new DocumentErasureService(registry, adapter, null, null), sources);
    }

    /** Erasure's reach into storage: this test's map stands in for the chunks on disk. */
    private UpsertHandler.StorageAdapter storageAdapter() {
        return key -> storage.remove(new String(key, StandardCharsets.UTF_8)) != null;
    }

    /** Registers a crawlable source, so a source-scoped erasure has one to take out of the crawl. */
    private void registerSource(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        sources.register(source);
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

    /** One captured HTTP response. */
    private record Response(int status, String body) {
        Object json() {
            return Json.parse(body);
        }
    }

    private Response post(String body) throws IOException {
        return call("POST", body);
    }

    private Response call(String method, String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/erasure"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
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
    @DisplayName("erasing")
    class Erasing {

        @Test
        @DisplayName("a named document is removed and the counts reported")
        void erasesOneDocument() throws IOException {
            ingest("docs", "handbook.md", 3);

            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"handbook.md\"}");

            assertThat(response.status())
                .as("200: the content is out of storage by the time this replies")
                .isEqualTo(200);
            Object json = response.json();
            assertThat(Json.at(json, "documents_erased")).isEqualTo(1L);
            assertThat(Json.at(json, "chunks_erased")).isEqualTo(3L);
            assertThat(Json.at(json, "documents_not_found")).isEqualTo(0L);
            assertThat(Json.stringAt(json, "source_id")).isEqualTo("docs");
            assertThat(Json.stringAt(json, "detail")).isNotNull();
            assertThat(storage).isEmpty();
        }

        @Test
        @DisplayName("several documents can be named at once")
        void erasesSeveralDocuments() throws IOException {
            ingest("docs", "a.md", 2);
            ingest("docs", "b.md", 1);
            ingest("docs", "c.md", 1);

            Response response = post(
                "{\"source_id\": \"docs\", \"document_ids\": [\"a.md\", \"b.md\"]}");

            assertThat(Json.at(response.json(), "documents_erased")).isEqualTo(2L);
            assertThat(Json.at(response.json(), "chunks_erased")).isEqualTo(3L);
            assertThat(storage).containsOnlyKeys("docs:c.md:chunk:0");
        }

        @Test
        @DisplayName("a document this node never held is reported as not found")
        void reportsWhatItNeverHad() throws IOException {
            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"ghost.md\"}");

            assertThat(response.status())
                .as("not a 404: the request was understood and acted on, and found nothing")
                .isEqualTo(200);
            assertThat(Json.at(response.json(), "documents_erased")).isEqualTo(0L);
            assertThat(Json.at(response.json(), "documents_not_found")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a node with no lexical index says so rather than claiming a compaction")
        void reportsWhatItCannotReclaim() throws IOException {
            ingest("docs", "a.md", 1);

            Response response = post(
                "{\"source_id\": \"docs\", \"document_id\": \"a.md\", \"compact\": true}");

            assertThat(Json.at(response.json(), "compacted")).isEqualTo(Boolean.FALSE);
            assertThat(Json.stringAt(response.json(), "detail")).contains("no lexical index");
        }
    }

    @Nested
    @DisplayName("erasing a whole source")
    class SourceScope {

        @Test
        @DisplayName("every document of the source goes, without the caller naming any")
        void erasesTheWholeSource() throws IOException {
            ingest("docs", "a.md", 2);
            ingest("docs", "b.md", 1);
            ingest("other", "c.md", 1);

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}");

            assertThat(response.status()).isEqualTo(200);
            Object json = response.json();
            assertThat(Json.stringAt(json, "scope")).isEqualTo("source");
            assertThat(Json.at(json, "documents_erased")).isEqualTo(2L);
            assertThat(Json.at(json, "documents_remaining")).isEqualTo(0L);
            assertThat(storage).containsOnlyKeys("other:c.md:chunk:0");
        }

        @Test
        @DisplayName("the source is taken out of the crawl, and the reply says so")
        void deregistersTheSource() throws IOException {
            // Erasing every document while leaving the source scheduled is not an erasure, it is
            // a pause: the next crawl restores the corpus.
            registerSource("docs");
            ingest("docs", "a.md", 1);

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}");

            assertThat(Json.at(response.json(), "source_deregistered")).isEqualTo(Boolean.TRUE);
            assertThat(sources.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("a deregistration that could not be recorded erases nothing")
        void anUnrecordableDeregistrationErasesNothing() throws IOException {
            // The source comes out of the crawl first precisely so nothing restores what is
            // being erased. When that step fails the documents have to stay: erasing them and
            // leaving the source scheduled reports an obligation as met and then undoes it.
            SourceRegistry refusing = mock(SourceRegistry.class);
            when(refusing.remove("docs"))
                .thenThrow(new SourceCatalogException("catalog is unwritable", new IOException()));
            endpoint = new DocumentErasureEndpoint(
                new DocumentErasureService(registry, storageAdapter(), null, null), refusing);
            ingest("docs", "a.md", 1);

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}");

            assertThat(response.status()).isEqualTo(503);
            assertThat(storage).as("the documents are still there to erase on a retry").isNotEmpty();
            assertThat(response.body())
                .as("the reply names no path on this node")
                .doesNotContain("catalog is unwritable");
        }

        @Test
        @DisplayName("a source that was not registered is reported as such, not as an error")
        void unregisteredSourceIsReported() throws IOException {
            ingest("docs", "a.md", 1);

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "source_deregistered")).isEqualTo(Boolean.FALSE);
            assertThat(Json.at(response.json(), "documents_erased")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a document-scoped erasure leaves the source alone")
        void documentScopeDoesNotDeregister() throws IOException {
            registerSource("docs");
            ingest("docs", "a.md", 1);
            ingest("docs", "b.md", 1);

            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"a.md\"}");

            assertThat(Json.stringAt(response.json(), "scope")).isEqualTo("document");
            assertThat(Json.at(response.json(), "source_deregistered")).isEqualTo(Boolean.FALSE);
            assertThat(sources.isRegistered("docs"))
                .as("erasing one document is not a reason to stop crawling the rest")
                .isTrue();
        }

        @Test
        @DisplayName("naming a source and a document at once is refused rather than guessed")
        void mixedScopeIsRefused() throws IOException {
            // A stale field beside a new one must not erase a whole source by accident.
            ingest("docs", "a.md", 1);

            Response response = post(
                "{\"source_id\": \"docs\", \"scope\": \"source\", \"document_id\": \"a.md\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("cannot be combined");
            assertThat(storage).as("nothing was erased on the way to being refused").hasSize(1);
        }

        @Test
        @DisplayName("everything is not reachable by leaving an identifier out")
        void omissionIsNotSourceScope() throws IOException {
            ingest("docs", "a.md", 1);

            Response response = post("{\"source_id\": \"docs\"}");

            assertThat(response.status())
                .as("a request that meant everything by omission is one a client sends by accident")
                .isEqualTo(400);
            assertThat(storage).hasSize(1);
        }

        @Test
        @DisplayName("an unknown scope is refused")
        void unknownScopeIsRefused() throws IOException {
            Response response = post("{\"source_id\": \"docs\", \"scope\": \"everything\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("document or source");
        }
    }

    @Nested
    @DisplayName("refusing what it cannot read")
    class Refusing {

        @Test
        @DisplayName("a request naming no document is refused")
        void documentIsRequired() throws IOException {
            Response response = post("{\"source_id\": \"docs\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("document_id");
        }

        @Test
        @DisplayName("a request naming no source is refused")
        void sourceIsRequired() throws IOException {
            Response response = post("{\"document_id\": \"a.md\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("source_id");
        }

        @Test
        @DisplayName("a blank identifier is refused rather than treated as a name")
        void blankIdentifiersAreRefused() throws IOException {
            assertThat(post("{\"source_id\": \"  \", \"document_id\": \"a.md\"}").status())
                .isEqualTo(400);
            assertThat(post("{\"source_id\": \"docs\", \"document_id\": \"\"}").status())
                .isEqualTo(400);
            assertThat(post("{\"source_id\": \"docs\", \"document_ids\": []}").status())
                .isEqualTo(400);
        }

        @Test
        @DisplayName("a source id longer than one could be registered under is refused")
        void oversizedSourceIdIsRefused() throws IOException {
            String sourceId = "x".repeat(Source.MAX_ID_LENGTH + 1);

            Response response = post(
                "{\"source_id\": \"" + sourceId + "\", \"document_id\": \"a.md\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error"))
                .contains("source_id")
                .contains(String.valueOf(Source.MAX_ID_LENGTH));
        }

        @Test
        @DisplayName("more documents than one request may carry is refused, not truncated")
        void tooManyDocumentsAreRefused() throws IOException {
            // Truncating would erase a prefix of the list and report success, leaving the caller
            // believing the rest had gone too.
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i <= DocumentErasureEndpoint.MAX_DOCUMENTS; i++) {
                ids.append(i == 0 ? "" : ", ").append('"').append("doc").append(i).append('"');
            }
            Response response = post(
                "{\"source_id\": \"docs\", \"document_ids\": [" + ids + "]}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(Json.stringAt(response.json(), "error")).contains("at most");
        }

        @Test
        @DisplayName("anything but POST is refused")
        void onlyPostErases() throws IOException {
            assertThat(call("GET", "").status()).isEqualTo(405);
            assertThat(call("DELETE", "").status()).isEqualTo(405);
        }
    }
}
