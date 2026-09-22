package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Why a result was returned, on the paths that can say.
 *
 * <p>A fused score is a sum of reciprocal ranks. On its own it says a document ranked well and
 * nothing about why, which makes a hybrid ranking an oracle rather than something an operator
 * can debug. What is missing is each signal's own rank and score, which of the query's terms the
 * passage actually contains, and what the query was widened with before any of it ran.</p>
 *
 * <p>All of it costs a read per result, so it is asked for the way {@code include_text} is
 * asked for and is absent otherwise.</p>
 */
@DisplayName("Query result explanations")
class QueryExplainTest {

    /** The chunk both signals rank, as chunk storage holds it. */
    private static final String PASSAGE =
        "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded";

    private static final String CHUNK_ID = "kb:oom.txt:chunk:0";

    private QueryPipeline queryPipeline;
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
    }

    private static IndexCoverage whole() {
        return IndexCoverage.builder().totalSegments(1).activeSegments(1)
            .segmentsSearched(1).estimatedCorpusCoverage(1.0).build();
    }

    private QueryResponse vectorAnswer() {
        return new QueryResponse(
            List.of(new QueryResultItem(CHUNK_ID, 0.9f, null, 3L, 7L)), whole(), 12L, false, false);
    }

    /** A node with vector search and chunk storage, and no lexical index. */
    private QueryEndpoint vectorOnly() {
        QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient);
        endpoint.setChunkText(Map.of(CHUNK_ID, PASSAGE)::get);
        return endpoint;
    }

    /** A node with both signals and chunk storage. */
    private QueryEndpoint hybrid(Path dir) throws Exception {
        ChunkLexicalIndex index = ChunkLexicalIndex.open(dir, 8.0);
        index.add(CHUNK_ID, PASSAGE);
        index.refresh();
        QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient);
        endpoint.setLexicalQueryService(new LexicalQueryService(index, null, List.of()));
        endpoint.setChunkText(Map.of(CHUNK_ID, PASSAGE)::get);
        return endpoint;
    }

    private String answer(QueryEndpoint endpoint, String requestJson) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(requestJson, body);
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenReturn(CompletableFuture.completedFuture(vectorAnswer()));
        endpoint.handle(exchange);
        return body.toString(StandardCharsets.UTF_8);
    }

    private HttpExchange exchange(String requestJson, ByteArrayOutputStream body)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(requestJson.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }

    /**
     * The identifier-and-score answer is what every existing client parses, and an explanation
     * costs a storage read for every result. Nobody pays for it who did not ask.
     */
    @Test
    @DisplayName("nothing is explained unless the request asks")
    void nothingExplainedByDefault() throws IOException {
        String json = answer(vectorOnly(), "{\"query\": \"OOM killer\"}");

        assertThat(json).doesNotContain("\"explain\"");
        assertThat(json).doesNotContain("\"query_expansion\"");
    }

    /**
     * On the vector path there is one signal, and saying so explicitly is what stops a caller
     * inferring which signals ran from which fields happen to be present.
     */
    @Test
    @DisplayName("a vector answer names its one signal with that signal's own rank and score")
    void vectorSignalCarriesItsOwnRankAndScore() throws IOException {
        String json = answer(vectorOnly(), "{\"query\": \"OOM killer\", \"explain\": true}");

        assertThat(json).contains(
            "\"explain\": {\"signals\": [{\"signal\": \"vector\", \"rank\": 1, "
            + "\"score\": 0.900000}]");
    }

    /**
     * The query's own words, checked against the passage that came back. A dense hit that
     * contains none of them is a semantic match, and one that contains all of them is not -
     * a difference the fused score cannot express.
     */
    @Test
    @DisplayName("each result names the query terms its passage actually contains")
    void matchedTermsComeFromThePassage() throws IOException {
        String json = answer(vectorOnly(),
            "{\"query\": \"OOM killer zookeeper\", \"explain\": true}");

        assertThat(json).contains("\"matched_terms\": [\"oom\", \"killer\"]");
    }

    /**
     * The whole point on a hybrid node. Two signals produced this result and the fused score
     * is a rank sum: without each signal's own position and score there is no way to tell a
     * document both signals ranked third from one the vector half ranked first.
     */
    @Test
    @DisplayName("a fused result carries every contributing signal's own rank and score")
    void fusedResultCarriesEachSignalsContribution(@TempDir Path dir) throws Exception {
        String json = answer(hybrid(dir),
            "{\"query\": \"OOM killer SIGKILL\", \"top_k\": 3, \"explain\": true}");

        assertThat(json).contains("\"retrieval\": \"hybrid\"");
        assertThat(json).contains("{\"signal\": \"vector\", \"rank\": 1, \"score\": 0.900000}");
        assertThat(json)
            .as("the BM25 half reports its own rank and its own score, not the fused one")
            .containsPattern("\\{\"signal\": \"bm25\", \"rank\": 1, \"score\": [0-9]+\\.[0-9]+}");
    }

    /**
     * Response level, not per result: the query was widened once, before any document was
     * looked at, and repeating the same list on every result would say otherwise.
     */
    @Test
    @DisplayName("the expansion the query was widened with is reported once, for the answer")
    void expansionIsReportedForTheAnswerNotTheResult(@TempDir Path dir) throws Exception {
        String json = answer(hybrid(dir),
            "{\"query\": \"OOM killer\", \"top_k\": 3, \"explain\": true}");

        assertThat(json).contains("\"query_expansion\": {\"status\": ");
        assertThat(json.split("\"query_expansion\"", -1).length - 1)
            .as("one expansion block for the whole answer")
            .isEqualTo(1);
    }

    /**
     * Matched terms are read out of the passage, so a node with no chunk storage cannot produce
     * them. Answering with an empty list would say the passage contained none of the query's
     * words, which is a different statement from not having looked.
     */
    @Test
    @DisplayName("explain is refused on a node that keeps no chunk storage, and says why")
    void explainRefusedWithoutChunkStorage() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange("{\"query\": \"OOM\", \"explain\": true}", body);
        QueryEndpoint noStorage = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient);

        noStorage.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("explain");
    }

    /**
     * A term list whose size the caller sets by typing a longer query is a caller-controlled
     * allocation, per result. It gets a ceiling, and a query past it is refused rather than
     * quietly explained against some prefix of what was asked.
     */
    @Test
    @DisplayName("a query with more distinct terms than can be explained is refused")
    void tooManyTermsToExplainIsRefused() throws IOException {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            query.append("term").append(i).append(' ');
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(
            "{\"query\": \"" + query.toString().trim() + "\", \"explain\": true}", body);

        vectorOnly().handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("explain");
    }
}
