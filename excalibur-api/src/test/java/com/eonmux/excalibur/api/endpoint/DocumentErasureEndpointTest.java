package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.tenancy.MappingBudget;

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
    private InlineSourceIds suppliedSources;

    @BeforeEach
    void setUp() {
        storage.clear();
        registry = new DocumentRegistry();
        sources = new InMemorySourceRegistry();
        UpsertHandler.StorageAdapter adapter = storageAdapter();
        // No lexical index: this test is about the endpoint's contract, and the service's own
        // tests cover what an index adds to it.
        suppliedSources = new InlineSourceIds(256);
        endpoint = new DocumentErasureEndpoint(
            new DocumentErasureService(registry, adapter, null, null), sources, suppliedSources);
    }

    /** Erasure's reach into storage: this test's map stands in for the chunks on disk. */
    private UpsertHandler.StorageAdapter storageAdapter() {
        return key -> storage.remove(new String(key, StandardCharsets.UTF_8)) != null;
    }

    /** A storage reach that will not release one named chunk, whatever it is asked. */
    private UpsertHandler.StorageAdapter refusing(String unremovableChunkId) {
        return key -> {
            String chunkId = new String(key, StandardCharsets.UTF_8);
            if (unremovableChunkId.equals(chunkId)) {
                throw new IllegalStateException("segment is read-only");
            }
            return storage.remove(chunkId) != null;
        };
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
    @DisplayName("the graph half of an erasure")
    class TheGraphHalf {

        /** A term of the prose the erased document contributed to the graph. */
        private static final String TERM = "zymurgic";

        @TempDir
        private Path graphRoot;

        private GraphTenants tenants;

        @BeforeEach
        void openGraph() throws IOException {
            // A policy that fires only when the whole store is tombstoned and not before a
            // day has passed, so a request left to the policy really is left to it. The
            // default would compact a two-edge store the moment one edge was erased, and the
            // test would be asserting the default rather than the behaviour.
            Config config = new Config(new HashMap<>(Map.of(
                "excalibur.graph.ram-buffer-mb", "4.0",
                "excalibur.graph.erasure-tombstone-ratio", "1.0",
                "excalibur.graph.erasure-max-latency-seconds", "86400")));
            tenants = new GraphTenants(graphRoot, config, MappingBudget.detect(), 2);
            endpoint = new DocumentErasureEndpoint(
                new DocumentErasureService(registry, storageAdapter(), null, null),
                sources, suppliedSources, tenants);
        }

        @AfterEach
        void closeGraph() {
            if (tenants != null) {
                tenants.close();
            }
        }

        /** Stages the facts an extractor would have produced from one document, and builds. */
        private void extractedFrom(String sourceId, String documentId) throws IOException {
            long provenanceId = LlmRelationExtractor.sourceIdOf(sourceId + ":" + documentId);
            Provenance origin = new Provenance(SourceType.DOCUMENT, provenanceId, 90, 1);
            GraphStagingStore staging = tenants.shared().staging();
            int ada = staging.entityIdOf("Ada Lovelace");
            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "knows", "Charles Babbage", origin));
            staging.appendEntityText(ada, "Ada Lovelace studied zymurgic notation", origin);
            // A second document's fact, so erasing the first does not tombstone the whole
            // store: an erasure is ordinarily a fraction of a graph, not all of it.
            staging.append(ExtractedTriple.alwaysValid("Charles Babbage", "knows",
                "Ada Lovelace", new Provenance(SourceType.DOCUMENT, 7, 90, 1)));
            staging.flush();
            assertThat(tenants.shared().buildService().build().published()).isTrue();
        }

        @Test
        @DisplayName("erasing a document withdraws what was extracted from it into the graph")
        void theGraphLosesWhatTheDocumentContributed() throws IOException {
            // The corpus half removed the chunks, the postings and the registry entry, and left
            // every fact extracted from the document - plus up to 2 KB of its text per entity
            // mentioned - in the graph and in the staging log the next build replays.
            ingest("docs", "a.md", 2);
            extractedFrom("docs", "a.md");
            Path served = tenants.shared().directory();
            assertThat(anyFileContains(served, TERM)).isTrue();

            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"a.md\", "
                + "\"compact\": true}");

            assertThat(Json.at(response.json(), "graph_requests_submitted")).isEqualTo(1L);
            assertThat(Json.at(response.json(), "graph_requests_satisfied")).isEqualTo(1L);
            assertThat(anyFileContains(served, TERM))
                .as("the prose the withdrawn document contributed has to go with it")
                .isFalse();
        }

        @Test
        @DisplayName("a graph obligation still queued is a 202, not a 200")
        void aQueuedGraphHalfIsNotReportedComplete() throws IOException {
            // The corpus half is done and the graph half is recorded and not yet carried out.
            // Answering 200 would tell a caller under an obligation that the erasure was
            // complete when a derivation of the withdrawn text is still being served.
            ingest("docs", "a.md", 1);
            extractedFrom("docs", "a.md");

            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"a.md\"}");

            assertThat(response.status())
                .as("accepted, and explicitly not yet complete")
                .isEqualTo(202);
            assertThat(Json.at(response.json(), "graph_erasure_complete"))
                .isEqualTo(Boolean.FALSE);
            assertThat(Json.at(response.json(), "graph_outstanding")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a document this node never held submits no graph obligation")
        void anAbsentDocumentSubmitsNothing() throws IOException {
            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"gone.md\"}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "graph_requests_submitted")).isEqualTo(0L);
            assertThat(Json.at(response.json(), "graph_erasure_complete"))
                .as("nothing was erased, so nothing about it is outstanding")
                .isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("a whole source withdraws each of its documents from the graph")
        void aSourceScopeWithdrawsEveryDocument() throws IOException {
            ingest("docs", "a.md", 1);
            ingest("docs", "b.md", 1);
            extractedFrom("docs", "a.md");

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\", "
                + "\"compact\": true}");

            assertThat(Json.at(response.json(), "graph_requests_submitted"))
                .as("one per document actually erased, which the caller never named")
                .isEqualTo(2L);
            assertThat(anyFileContains(tenants.shared().directory(), TERM)).isFalse();
        }

        /** Whether any file at or under {@code root} holds {@code text} as bytes. */
        private boolean anyFileContains(Path root, String text) throws IOException {
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

    @Nested
    @DisplayName("releasing a supplied source's id")
    class ReleasingASuppliedId {

        @Test
        @DisplayName("forgets an id once everything supplied under it is erased")
        void forgetsAnErasedSource() throws IOException {
            // Otherwise the refusal outlives what it protects: POST /v1/ingest would go on
            // refusing a crawl under this id on the grounds that it holds supplied documents
            // the node no longer holds, with no way to clear it short of a restart.
            suppliedSources.remember("notes");
            ingest("notes", "a.md", 2);

            Response response = post("{\"source_id\": \"notes\", \"scope\": \"source\"}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(suppliedSources.isSupplied("notes")).isFalse();
        }

        @Test
        @DisplayName("keeps the id when only some documents were erased")
        void keepsTheIdForADocumentScopedErasure() throws IOException {
            // The source still holds the rest of what was supplied, so a crawl registered under
            // its id would still delete them.
            suppliedSources.remember("notes");
            ingest("notes", "a.md", 1);
            ingest("notes", "b.md", 1);

            Response response = post(
                "{\"source_id\": \"notes\", \"document_ids\": [\"a.md\"]}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(suppliedSources.isSupplied("notes")).isTrue();
        }
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
        @DisplayName("a document it could not fully erase is a failure, not a partial success")
        void reportsWhatItCouldNotErase() throws IOException {
            ingest("docs", "a.md", 2);
            endpoint = new DocumentErasureEndpoint(
                new DocumentErasureService(registry, refusing("docs:a.md:chunk:1"), null, null),
                sources, suppliedSources);

            Response response = post("{\"source_id\": \"docs\", \"document_id\": \"a.md\"}");

            assertThat(response.status())
                .as("the caller asked for content to be gone and some of it is not; a 200 here "
                    + "would end a loop that has not finished its obligation")
                .isEqualTo(500);
            Object json = response.json();
            assertThat(Json.at(json, "documents_incomplete")).isEqualTo(1L);
            assertThat(Json.at(json, "chunks_not_removed")).isEqualTo(1L);
            assertThat(Json.at(json, "documents_erased")).isEqualTo(0L);
            assertThat(registry.contains("docs", "a.md"))
                .as("the entry is what names the chunks, so a repeat of the request still has "
                    + "something to erase")
                .isTrue();
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
        @DisplayName("a reindex in flight is stopped before the erasure, so nothing it writes survives")
        void anInFlightReindexRestoresNothing() throws Exception {
            // The race the deregistration exists to win. A re-crawl that is running when the
            // erasure arrives finishes the document it is on when asked to stop; if the erasure
            // did not wait for that, the document would land after the erasure had looked, and
            // the source would read as erased while still holding it.
            com.eonmux.excalibur.api.reindex.ReindexCoordinator coordinator =
                new com.eonmux.excalibur.api.reindex.ReindexCoordinator();
            ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
                new ReindexEndpoint.InMemoryReindexStatusRegistry();
            sources.setReindexCoordinator(coordinator);
            sources.setReindexStatusRegistry(reindexes);
            registerSource("docs");
            ingest("docs", "a.md", 2);

            com.eonmux.excalibur.api.reindex.ReindexCoordinator.Lease lease =
                coordinator.tryAcquire("docs");
            reindexes.startReindex("docs");
            java.util.concurrent.CountDownLatch asked = new java.util.concurrent.CountDownLatch(1);
            lease.onCancel(asked::countDown);
            Thread run = Thread.ofVirtual().start(() -> {
                try {
                    asked.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    Thread.sleep(50);
                    ingest("docs", "late.md", 1);
                    reindexes.cancelReindex("docs");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lease.release();
                }
            });

            Response response = post("{\"source_id\": \"docs\", \"scope\": \"source\"}");
            run.join();

            assertThat(response.status()).isEqualTo(200);
            assertThat(Json.at(response.json(), "documents_erased")).isEqualTo(2L);
            assertThat(Json.at(response.json(), "documents_remaining")).isEqualTo(0L);
            assertThat(storage).as("nothing the reindex wrote outlived the erasure").isEmpty();
            assertThat(registry.getBySource("docs")).isEmpty();
            assertThat(reindexes.getStatus("docs")).isNull();
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
