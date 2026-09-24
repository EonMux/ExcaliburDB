package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.source.Source;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /v1/documents} - feeding a node documents directly instead of a target to crawl.
 */
@DisplayName("DocumentUpsertEndpoint")
class DocumentUpsertEndpointTest {

    private IngestionOrchestrator orchestrator;
    private SourceRegistry crawlSources;
    private InlineSourceIds suppliedSources;
    private DocumentUpsertEndpoint endpoint;
    private ByteArrayOutputStream body;

    @BeforeEach
    void setUp() {
        orchestrator = mock(IngestionOrchestrator.class);
        crawlSources = mock(SourceRegistry.class);
        when(crawlSources.isRegistered(anyString())).thenReturn(false);
        when(orchestrator.ingestNow(any(Source.class)))
            .thenReturn(new IngestionOrchestrator.IngestionOutcome(1, 3, 0, 0, false));
        suppliedSources = new InlineSourceIds(256);
        endpoint = new DocumentUpsertEndpoint(orchestrator, crawlSources, suppliedSources, 4);
        body = new ByteArrayOutputStream();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null orchestrator")
        void rejectsNullOrchestrator() {
            assertThatThrownBy(() -> new DocumentUpsertEndpoint(null, crawlSources, suppliedSources, 4))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects a non-positive document bound")
        void rejectsNonPositiveBound() {
            assertThatThrownBy(() -> new DocumentUpsertEndpoint(orchestrator, crawlSources, suppliedSources, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Ingesting supplied documents")
    class Ingesting {

        /**
         * The whole point of the route: a node that can only crawl cannot be fed by a client
         * that holds the documents but has nowhere the node is allowed to read them from.
         */
        @Test
        @DisplayName("ingests the documents the request carries and reports what it did")
        void ingestsSuppliedDocuments() throws IOException {
            when(orchestrator.ingestNow(any(Source.class)))
                .thenReturn(new IngestionOrchestrator.IngestionOutcome(2, 7, 0, 0, false));

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}, "
                + "{\"document_id\": \"b.md\", \"text\": \"beta\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"documents_ingested\": 2");
            assertThat(json).contains("\"chunks_ingested\": 7");
            assertThat(json).contains("\"source_id\": \"notes\"");
        }

        @Test
        @DisplayName("hands the pipeline the text and identifiers it was given")
        void buildsTheSourceFromTheRequest() throws IOException {
            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            ArgumentCaptor<Source> captor = ArgumentCaptor.forClass(Source.class);
            verify(orchestrator).ingestNow(captor.capture());
            Source source = captor.getValue();
            assertThat(source.getSourceId()).isEqualTo("notes");
            assertThat(source.getSourceType()).isEqualTo(InlineSource.TYPE);
        }

        /**
         * Documents posted under a crawled source's id are deleted the next time that crawl is
         * reindexed, because the crawl does not list them. Refusing is the only answer that does
         * not lose data later for a reason the caller could not have seen.
         */
        @Test
        @DisplayName("refuses a source id a crawl already owns, and says why")
        void refusesACrawlSourceId() throws IOException {
            when(crawlSources.isRegistered("docs")).thenReturn(true);

            HttpExchange exchange = post("{\"source_id\": \"docs\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(409), anyLong());
            verify(orchestrator, never()).ingestNow(any(Source.class));
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("docs");
        }

        @Test
        @DisplayName("answers 409 when the same source is already being ingested")
        void reportsAConcurrentRun() throws IOException {
            when(orchestrator.ingestNow(any(Source.class)))
                .thenThrow(new IllegalStateException("Ingestion is already running for source: notes"));

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(409), anyLong());
        }

        /**
         * Every other route answers a stopping node 503 {@code shutting_down}. A 409 would tell
         * the caller to wait for a run over its source to finish and resend to this node, when
         * what it should do is send the batch to another one.
         */
        @Test
        @DisplayName("answers 503 shutting_down when the node is stopping")
        void reportsShutdown() throws IOException {
            when(orchestrator.ingestNow(any(Source.class)))
                .thenThrow(new IngestionOrchestrator.ShuttingDownException());

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(503), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"code\": \"shutting_down\"");
        }

        @Test
        @DisplayName("names a concurrent run as a conflict")
        void namesAConcurrentRunAsAConflict() throws IOException {
            when(orchestrator.ingestNow(any(Source.class)))
                .thenThrow(new IllegalStateException("Ingestion is already running for source: notes"));

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"code\": \"conflict\"");
        }

        /**
         * A run that failed outright is not a run that ingested nothing, and answering 200 with
         * zero counts would make the two look the same to a caller.
         */
        @Test
        @DisplayName("answers 500 when the ingestion run itself failed")
        void reportsAFailedRun() throws IOException {
            when(orchestrator.ingestNow(any(Source.class)))
                .thenThrow(new IngestionOrchestrator.IngestionFailedException(
                    "Ingestion failed: embedding endpoint refused", new IllegalStateException()));

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(500), anyLong());
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("refuses a request naming no source")
        void refusesNoSourceId() throws IOException {
            endpoint.handle(post("{\"documents\": [{\"document_id\": \"a\", \"text\": \"x\"}]}"));

            assertThat(body.toString(StandardCharsets.UTF_8)).contains("source_id");
        }

        @Test
        @DisplayName("refuses a request carrying no documents")
        void refusesNoDocuments() throws IOException {
            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": []}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("refuses a document with no text, rather than indexing an empty one")
        void refusesATextlessDocument() throws IOException {
            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("text");
        }

        @Test
        @DisplayName("refuses text that is not text, rather than indexing it")
        void refusesTextThatIsNotText() throws IOException {
            // A caller holding its own content can be told what is wrong with it. A crawl can
            // only pass a file over and count it, so this is the one path where the same rule
            // gets to be an answer rather than a statistic.
            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"al\\u0000pha\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            verify(orchestrator, never()).ingestNow(any(Source.class));
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("a.md");
        }

        @Test
        @DisplayName("refuses more documents than one request may carry, and names the bound")
        void refusesTooManyDocuments() throws IOException {
            StringBuilder request = new StringBuilder("{\"source_id\": \"notes\", \"documents\": [");
            for (int i = 0; i < 5; i++) {
                request.append(i > 0 ? ", " : "")
                    .append("{\"document_id\": \"d").append(i).append("\", \"text\": \"x\"}");
            }
            HttpExchange exchange = post(request.append("]}").toString());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("4");
        }

        @Test
        @DisplayName("refuses the same document identifier twice in one request")
        void refusesDuplicateDocumentIds() throws IOException {
            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"alpha\"}, "
                + "{\"document_id\": \"a.md\", \"text\": \"beta\"}]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("refuses a method other than POST")
        void refusesOtherMethods() throws IOException {
            HttpExchange exchange = mock(HttpExchange.class);
            when(exchange.getRequestMethod()).thenReturn("PUT");
            when(exchange.getRequestURI()).thenReturn(URI.create("/v1/documents"));
            when(exchange.getResponseBody()).thenReturn(body);
            when(exchange.getResponseHeaders()).thenReturn(new Headers());

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    @Nested
    @DisplayName("Recording supplied sources")
    class RecordingSuppliedSources {

        @Test
        @DisplayName("records the id a batch was supplied under")
        void recordsTheSourceId() throws IOException {
            endpoint.handle(post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"x\"}]}"));

            assertThat(suppliedSources.isSupplied("notes")).isTrue();
        }

        @Test
        @DisplayName("records nothing for a request that was refused")
        void recordsNothingForARefusedRequest() throws IOException {
            endpoint.handle(post("{\"source_id\": \"notes\", \"documents\": []}"));

            assertThat(suppliedSources.isSupplied("notes")).isFalse();
        }

        @Test
        @DisplayName("answers 429 past the supplied-source bound rather than taking the batch")
        void refusesPastTheBound() throws IOException {
            DocumentUpsertEndpoint bounded = new DocumentUpsertEndpoint(
                orchestrator, crawlSources, new InlineSourceIds(1), 4);
            bounded.handle(post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"x\"}]}"));

            HttpExchange exchange = post("{\"source_id\": \"articles\", \"documents\": ["
                + "{\"document_id\": \"b.md\", \"text\": \"y\"}]}");
            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(429), anyLong());
        }

        @Test
        @DisplayName("refuses a batch for an id a crawl claimed while the request was running")
        void refusesACrawlThatWonTheRace() throws IOException {
            // The check before the claim sees nothing; the crawl registers; the check after it
            // sees the crawl. Without the second check both would proceed and the crawl's first
            // pass would delete what this request had just written.
            when(crawlSources.isRegistered("notes")).thenReturn(false).thenReturn(true);

            HttpExchange exchange = post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"x\"}]}");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(409), anyLong());
            verify(orchestrator, never()).ingestNow(any(Source.class));
        }

        @Test
        @DisplayName("records nothing for an id that is already a crawl source")
        void recordsNothingForACrawlSource() throws IOException {
            when(crawlSources.isRegistered("notes")).thenReturn(true);

            endpoint.handle(post("{\"source_id\": \"notes\", \"documents\": ["
                + "{\"document_id\": \"a.md\", \"text\": \"x\"}]}"));

            assertThat(suppliedSources.isSupplied("notes")).isFalse();
        }
    }

    private HttpExchange post(String requestBody) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/documents"));
        when(exchange.getRequestBody())
            .thenReturn(new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(body);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        return exchange;
    }
}
