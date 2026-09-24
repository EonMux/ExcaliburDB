package com.eonmux.excalibur.api.endpoint;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eonmux.excalibur.api.StubExchange;
import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;
import com.eonmux.excalibur.tenancy.MappingBudget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /v1/erasure/{request_id}} - what a document erasure's 202 promised, read back.
 */
@DisplayName("Document erasure status endpoint")
class DocumentErasureStatusEndpointTest {

    /** The name of an erased document, which nothing written after its erasure may carry. */
    private static final String DOCUMENT = "salary-review-jane-doe.md";

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @TempDir
    Path dataDir;

    @TempDir
    Path graphRoot;

    private final Map<String, String> storage = new LinkedHashMap<>();
    private DocumentRegistry registry;
    private DocumentErasureService service;
    private GraphTenants tenants;
    private ErasureReceiptFile receipts;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DocumentRegistry();
        service = new DocumentErasureService(registry,
            key -> storage.remove(new String(key, StandardCharsets.UTF_8)) != null, null, null);
        tenants = openTenants();
        receipts = ErasureReceiptFile.open(dataDir);
    }

    @AfterEach
    void tearDown() {
        if (tenants != null) {
            tenants.close();
        }
    }

    private GraphTenants openTenants() throws IOException {
        // A policy that leaves an obligation queued until asked, so "pending" is observable.
        Config config = new Config(new HashMap<>(Map.of(
            "excalibur.graph.ram-buffer-mb", "4.0",
            "excalibur.graph.erasure-tombstone-ratio", "1.0",
            "excalibur.graph.erasure-max-latency-seconds", "86400")));
        return new GraphTenants(graphRoot, config, MappingBudget.detect(), 2);
    }

    private void ingest(String documentId) {
        String chunkId = "docs:" + documentId + ":chunk:0";
        storage.put(chunkId, "text");
        registry.register(DocumentMetadata.builder().sourceId("docs").documentId(documentId)
            .contentHash(documentId.hashCode()).contentLength(10).addChunkId(chunkId).build());
    }

    /** Stages and builds a fact extracted from one document, and one from another. */
    private void extractedFrom(String documentId) throws IOException {
        long provenanceId = LlmRelationExtractor.sourceIdOf("docs:" + documentId);
        GraphStagingStore staging = tenants.shared().staging();
        staging.append(ExtractedTriple.alwaysValid("Ada Lovelace", "knows", "Charles Babbage",
            new Provenance(SourceType.DOCUMENT, provenanceId, 90, 1)));
        staging.append(ExtractedTriple.alwaysValid("Charles Babbage", "knows", "Ada Lovelace",
            new Provenance(SourceType.DOCUMENT, 7, 90, 1)));
        staging.flush();
        assertThat(tenants.shared().buildService().build().published()).isTrue();
    }

    private StubExchange erase(String body) throws IOException {
        StubExchange exchange = new StubExchange("POST", "/v1/erasure", body);
        new DocumentErasureEndpoint(service, null, null, tenants, receipts,
            Clock.fixed(NOW, ZoneOffset.UTC)).handle(exchange);
        return exchange;
    }

    private String receiptOf(StubExchange posted) {
        return Json.stringAt(Json.parse(posted.responseText()), "request_id");
    }

    private StubExchange status(String path, DocumentErasureService lexical) throws IOException {
        StubExchange exchange = new StubExchange("GET", path, "");
        new DocumentErasureStatusEndpoint(receipts, lexical, tenants).handle(exchange);
        return exchange;
    }

    private Object statusOf(String id) throws IOException {
        StubExchange exchange = status("/v1/erasure/" + id, service);
        assertThat(exchange.getResponseCode()).as(exchange.responseText()).isEqualTo(200);
        return Json.parse(exchange.responseText());
    }

    private static Object remaining(Object json, String field) {
        return Json.at(Json.at(json, "remaining"), field);
    }

    private static Object firstGraph(Object json) {
        return ((List<?>) Json.at(json, "graph")).get(0);
    }

    @Test
    @DisplayName("a queued graph half reads back pending, then discharged once compacted, "
        + "and the erased document is named nowhere")
    void pendingThenDischarged() throws IOException {
        ingest(DOCUMENT);
        extractedFrom(DOCUMENT);

        StubExchange posted = erase("{\"source_id\": \"docs\", \"document_id\": \""
            + DOCUMENT + "\"}");
        assertThat(posted.getResponseCode()).isEqualTo(202);
        String id = receiptOf(posted);
        assertThat(id).isNotBlank();

        Object pending = statusOf(id);
        assertThat(Json.stringAt(pending, "state")).isEqualTo("pending");
        assertThat(Json.at(pending, "requested_at_ms")).isEqualTo(NOW.toEpochMilli());
        assertThat(Json.at(pending, "discharged_at_ms")).isNull();
        assertThat(Json.at(pending, "documents_erased")).isEqualTo(1L);
        assertThat(remaining(pending, "graph_requests_pending")).isEqualTo(1L);
        assertThat(remaining(pending, "lexical_reclamation_pending"))
            .isEqualTo(Boolean.FALSE);

        tenants.shared().erasureService().compactNow();

        StubExchange after = status("/v1/erasure/" + id, service);
        Object discharged = Json.parse(after.responseText());
        assertThat(Json.stringAt(discharged, "state")).isEqualTo("discharged");
        assertThat((Long) Json.at(discharged, "discharged_at_ms")).isPositive();
        assertThat(after.responseText()).doesNotContain(DOCUMENT);
        assertThat(Files.readString(dataDir.resolve(ErasureReceiptFile.FILE)))
            .doesNotContain(DOCUMENT);
    }

    @Test
    @DisplayName("a receipt and the obligations it points at survive a restart")
    void survivesRestart() throws IOException {
        ingest(DOCUMENT);
        extractedFrom(DOCUMENT);
        String id = receiptOf(erase("{\"source_id\": \"docs\", \"document_id\": \""
            + DOCUMENT + "\"}"));

        tenants.close();
        tenants = openTenants();
        receipts = ErasureReceiptFile.open(dataDir);

        Object json = statusOf(id);
        assertThat(Json.stringAt(json, "state")).isEqualTo("pending");
        assertThat(Json.stringAt(firstGraph(json), "namespace")).isEqualTo("shared");
        assertThat(Json.at(firstGraph(json), "pending")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an erasure that owed nothing is discharged as of when it answered")
    void nothingOwed() throws IOException {
        String id = receiptOf(erase("{\"source_id\": \"docs\", \"document_id\": \"gone.md\"}"));

        Object json = statusOf(id);

        assertThat(Json.stringAt(json, "state")).isEqualTo("discharged");
        assertThat(Json.at(json, "discharged_at_ms")).isEqualTo(NOW.toEpochMilli());
        assertThat(Json.at(json, "documents_not_found")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an owed reclamation is applied until the index owes nothing that old")
    void appliedUntilReclaimed() throws IOException {
        receipts.record(new ErasureReceiptFile.Receipt("lex", NOW.toEpochMilli(), "docs",
            "document", 1, 0, 0, 0, false, true, List.of()));
        DocumentErasureService lexical = mock(DocumentErasureService.class);

        when(lexical.oldestOutstandingErasure()).thenReturn(Optional.of(NOW.minusSeconds(5)));
        Object owed = Json.parse(status("/v1/erasure/lex", lexical).responseText());
        assertThat(Json.stringAt(owed, "state")).isEqualTo("applied");
        assertThat(remaining(owed, "lexical_reclamation_pending"))
            .isEqualTo(Boolean.TRUE);

        when(lexical.oldestOutstandingErasure()).thenReturn(Optional.of(NOW.plusSeconds(5)));
        Object later = Json.parse(status("/v1/erasure/lex", lexical).responseText());
        assertThat(Json.stringAt(later, "state"))
            .as("only a newer obligation is outstanding")
            .isEqualTo("discharged");
        assertThat(Json.at(later, "discharged_at_ms"))
            .as("the index does not record when it reclaimed")
            .isNull();

        when(lexical.oldestOutstandingErasure()).thenReturn(Optional.empty());
        assertThat(Json.stringAt(Json.parse(status("/v1/erasure/lex", lexical).responseText()),
            "state")).isEqualTo("discharged");
    }

    @Test
    @DisplayName("an erasure that could not finish every document reads back incomplete")
    void incomplete() throws IOException {
        receipts.record(new ErasureReceiptFile.Receipt("inc", 1L, "docs", "source", 1, 2, 0, 5,
            true, false, List.of()));

        Object json = statusOf("inc");

        assertThat(Json.stringAt(json, "state")).isEqualTo("incomplete");
        assertThat(remaining(json, "documents_incomplete")).isEqualTo(2L);
        assertThat(Json.at(json, "source_deregistered")).isEqualTo(Boolean.TRUE);
        assertThat(Json.at(json, "discharged_at_ms")).isNull();
    }

    @Test
    @DisplayName("obligations in a namespace dropped since are discharged with it")
    void droppedNamespace() throws IOException {
        receipts.record(new ErasureReceiptFile.Receipt("drop", 1L, "docs", "document", 1, 0, 0,
            0, false, false, List.of(new ErasureReceiptFile.GraphRequests("acme",
                List.of(1L, 2L)))));

        Object json = statusOf("drop");

        assertThat(Json.stringAt(json, "state")).isEqualTo("discharged");
        assertThat(Json.at(firstGraph(json), "namespace_dropped")).isEqualTo(Boolean.TRUE);
        assertThat(Json.at(firstGraph(json), "discharged")).isEqualTo(2L);
        assertThat(Json.at(json, "discharged_at_ms")).isNull();
    }

    @Test
    @DisplayName("an unknown id is a 404, a malformed one a 400, and only GET is served")
    void refusals() throws IOException {
        assertThat(status("/v1/erasure/nope", service).getResponseCode()).isEqualTo(404);
        for (String path : new String[] {"/v1/erasure/", "/v1/erasure/a/b",
            "/v1/erasure/bad%20id", "/v1/erasure/" + "x".repeat(65)}) {
            assertThat(status(path, service).getResponseCode()).as(path).isEqualTo(400);
        }
        StubExchange post = new StubExchange("POST", "/v1/erasure/x", "");
        new DocumentErasureStatusEndpoint(receipts, service, tenants).handle(post);
        assertThat(post.getResponseCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("an erasure whose receipt cannot be written is still reported, without an id")
    void receiptFailureDoesNotHideTheErasure() throws IOException {
        Path notADirectory = Files.writeString(dataDir.resolve("occupied"), "x");
        receipts = ErasureReceiptFile.open(notADirectory);
        ingest(DOCUMENT);

        StubExchange posted = erase("{\"source_id\": \"docs\", \"document_id\": \""
            + DOCUMENT + "\"}");

        assertThat(posted.getResponseCode())
            .as("the graph half is still queued, exactly as it would be with a receipt")
            .isEqualTo(202);
        assertThat(Json.at(Json.parse(posted.responseText()), "request_id")).isNull();
        assertThat(Json.at(Json.parse(posted.responseText()), "documents_erased")).isEqualTo(1L);
    }

    @Test
    @DisplayName("the audit event counts the documents named and names none of them")
    void auditNamesNoDocument() throws IOException {
        ingest(DOCUMENT);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Logger audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        audit.addAppender(events);
        StubExchange exchange = new StubExchange("POST", "/v1/erasure",
            "{\"source_id\": \"docs\", \"document_ids\": [\"" + DOCUMENT + "\", \"x.md\"]}");
        try {
            AuditLog.audited("erasure.documents", new DocumentErasureEndpoint(service, null,
                null, tenants, receipts, Clock.systemUTC())).handle(exchange);
        } finally {
            audit.detachAppender(events);
        }

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getKeyValuePairs().toString()).doesNotContain(DOCUMENT);
            assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("audit_targets");
                assertThat(new HashMap<Object, Object>((Map<?, ?>) pair.value))
                    .containsEntry("source_id", "docs")
                    .containsEntry("scope", "document")
                    .containsEntry("documents_named", 2)
                    .containsEntry("request_id", receiptOf(exchange));
            });
        });
    }
}
