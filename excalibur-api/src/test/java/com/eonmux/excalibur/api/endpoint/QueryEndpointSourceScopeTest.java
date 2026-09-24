package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.core.text.ChunkSources;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code source_ids} on {@code POST /v1/query}: validated, carried to every signal, and refused
 * with its reason by a node that cannot tell one source's chunks from another's.
 */
@DisplayName("QueryEndpoint source_ids")
class QueryEndpointSourceScopeTest extends QueryEndpointTestBase {

    /** Attribution as the registry gives it; {@code team:wiki} extends {@code team}. */
    private static final ChunkSources SOURCES = chunkId -> Map.of(
        "team:guide.md:chunk:0", "team",
        "team:wiki:home.md:chunk:0", "team:wiki",
        "other:notes.md:chunk:0", "other").get(chunkId);

    @Nested
    @DisplayName("on the vector route")
    class VectorRoute {

        @Test
        @DisplayName("hands the pipeline the scope the request named")
        void carriesTheScope() throws Exception {
            when(queryPipeline.getChunkSources()).thenReturn(SOURCES);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));
            HttpExchange exchange = createMockExchange("POST",
                "{\"query\": \"q\", \"source_ids\": [\"team\", \"other\", \"team\"]}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(sent.capture(), any(CancellationToken.class));
            assertThat(sent.getValue().sourceScope())
                .isEqualTo(SourceScope.of(List.of("other", "team")));
        }

        @Test
        @DisplayName("leaves a request that names no source unscoped")
        void unscopedByDefault() throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));

            endpoint.handle(createMockExchange("POST", "{\"query\": \"q\"}"));

            ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(sent.capture(), any(CancellationToken.class));
            assertThat(sent.getValue().sourceScope()).isEqualTo(SourceScope.ALL);
        }

        @Test
        @DisplayName("refuses with 503 and the reason when the node cannot attribute chunks")
        void refusesWithoutAttribution() throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"q\", \"source_ids\": [\"team\"]}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(503), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("not_configured")
                .contains("document registry");
            verify(queryPipeline, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("refuses an empty list rather than reading it as every source")
        void refusesEmpty() throws Exception {
            assertRefused("{\"query\": \"q\", \"source_ids\": []}", "at least one source");
        }

        @Test
        @DisplayName("refuses a blank id")
        void refusesBlank() throws Exception {
            assertRefused("{\"query\": \"q\", \"source_ids\": [\"team\", \" \"]}", "blank");
        }

        @Test
        @DisplayName("refuses an element that is not a string")
        void refusesNonString() throws Exception {
            assertRefused("{\"query\": \"q\", \"source_ids\": [\"team\", 3]}", "source_ids");
        }

        @Test
        @DisplayName("refuses an id longer than any source may have")
        void refusesOverLong() throws Exception {
            assertRefused("{\"query\": \"q\", \"source_ids\": [\"" + "s".repeat(257) + "\"]}",
                "at most 256");
        }

        @Test
        @DisplayName("refuses a body it cannot walk rather than reading source_ids as absent")
        void refusesAMalformedBody() throws Exception {
            // The missing comma leaves 'query' readable and source_ids unreachable: read
            // leniently, the request would run over every source.
            assertRefused("{\"query\": \"q\" \"source_ids\": [\"team\"]}", "well-formed");
            assertRefused("{\"query\": \"q\", \"source_ids\": [\"team\"]} trailing",
                "well-formed");
        }

        @Test
        @DisplayName("refuses more sources than one scope may name")
        void refusesTooMany() throws Exception {
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i <= SourceScope.MAX_SOURCES; i++) {
                ids.append(i == 0 ? "" : ",").append("\"s").append(i).append('"');
            }
            assertRefused("{\"query\": \"q\", \"source_ids\": [" + ids + "]}",
                "at most " + SourceScope.MAX_SOURCES);
        }

        @Test
        @DisplayName("treats an explicit null as no scope")
        void nullIsUnscoped() throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));
            HttpExchange exchange =
                createMockExchange("POST", "{\"query\": \"q\", \"source_ids\": null}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        private void assertRefused(String request, String reason) throws Exception {
            when(queryPipeline.getChunkSources()).thenReturn(SOURCES);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", request, body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains(reason);
            verify(queryPipeline, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("on the hybrid route")
    class HybridRoute {

        @Test
        @DisplayName("confines both signals: the pipeline gets the scope, and BM25 applies it")
        void confinesBothSignals(@TempDir Path dir) throws Exception {
            when(queryPipeline.getChunkSources()).thenReturn(SOURCES);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"kernel memory\", \"source_ids\": [\"team\"]}", body);

            try (ChunkLexicalIndex index = LexicalRoute.indexed(dir)) {
                QueryEndpoint hybrid = new QueryEndpoint(queryPipeline, null, null, null,
                    embeddingClient);
                hybrid.setLexicalQueryService(new LexicalQueryService(index, null, List.of())
                    .withChunkSources(SOURCES));
                hybrid.handle(exchange);
            }

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(sent.capture(), any(CancellationToken.class));
            assertThat(sent.getValue().sourceScope()).isEqualTo(SourceScope.of(List.of("team")));
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"retrieval\": \"hybrid\"")
                .contains("team:guide.md:chunk:0")
                .doesNotContain("team:wiki:home.md:chunk:0")
                .doesNotContain("other:notes.md:chunk:0");
        }
    }

    @Nested
    @DisplayName("on the lexical route")
    class LexicalRoute {

        @Test
        @DisplayName("answers from the named source only, told apart from a longer id")
        void answersFromTheScope(@TempDir Path dir) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"kernel memory\", \"source_ids\": [\"team\"]}", body);

            try (ChunkLexicalIndex index = indexed(dir)) {
                when(queryPipeline.getChunkSources()).thenReturn(SOURCES);
                lexicalOnly(index).handle(exchange);
            }

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("team:guide.md:chunk:0")
                .doesNotContain("team:wiki:home.md:chunk:0")
                .doesNotContain("other:notes.md:chunk:0");
        }

        @Test
        @DisplayName("refuses with 503 when the node cannot attribute chunks")
        void refusesWithoutAttribution(@TempDir Path dir) throws Exception {
            HttpExchange exchange = createMockExchange("POST",
                "{\"query\": \"kernel memory\", \"source_ids\": [\"team\"]}");

            try (ChunkLexicalIndex index = indexed(dir)) {
                lexicalOnly(index).handle(exchange);
            }

            verify(exchange).sendResponseHeaders(eq(503), anyLong());
        }

        private QueryEndpoint lexicalOnly(ChunkLexicalIndex index) {
            QueryEndpoint lexical = new QueryEndpoint(queryPipeline, null, null, null, null);
            lexical.setLexicalQueryService(new LexicalQueryService(index, null, List.of())
                .withChunkSources(queryPipeline.getChunkSources()));
            return lexical;
        }

        static ChunkLexicalIndex indexed(Path dir) throws Exception {
            ChunkLexicalIndex index = ChunkLexicalIndex.open(dir, 8.0);
            index.add("other:notes.md:chunk:0", "kernel memory kernel memory kernel memory");
            index.add("team:wiki:home.md:chunk:0", "kernel memory kernel memory");
            index.add("team:guide.md:chunk:0", "kernel memory and more about other things");
            index.refresh();
            return index;
        }
    }
}
