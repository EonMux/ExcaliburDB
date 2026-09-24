package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;

import com.eonmux.excalibur.retrieval.expand.CachingQueryExpander;
import com.eonmux.excalibur.retrieval.expand.Expansion;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.expand.SearchFormulation;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an explained answer says about the search that actually ran.
 *
 * <p>On a node that widens queries, the search is not the one that was typed. A caller looking
 * at results with no idea the query was widened cannot tell a good expansion from one that went
 * somewhere else entirely, which is the usual reason a specific query comes back with generic
 * answers.</p>
 *
 * <p>The expansion is a generation from a chat model, so it must be reported from what the
 * search used rather than by asking again: a second call bills the operator twice and can
 * return different terms from the ones that ran. A replayed answer carries the expansion it was
 * searched with for the same reason.</p>
 */
@DisplayName("Query expansion reporting")
class QueryExpansionReportTest {

    private static final String CHUNK_ID = "kb:oom.txt:chunk:0";

    private static final String PASSAGE =
        "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded";

    private final QueryPipeline queryPipeline = mock(QueryPipeline.class);

    /** A node with a lexical index, chunk storage, and the expander its index was given. */
    private QueryEndpoint lexicalNode(Path dir, QueryExpander expander) throws Exception {
        return lexicalNode(dir, expander, null);
    }

    /** The same node, answering repeated questions from {@code cache} when it may. */
    private QueryEndpoint lexicalNode(Path dir, QueryExpander expander, QueryResultCache cache)
            throws Exception {
        ChunkLexicalIndex index = ChunkLexicalIndex.open(dir, 8.0);
        index.add(CHUNK_ID, PASSAGE);
        index.refresh();
        QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
        endpoint.setLexicalQueryService(new LexicalQueryService(index, expander, List.of()));
        endpoint.setChunkText(Map.of(CHUNK_ID, PASSAGE)::get);
        endpoint.setQueryResultCache(cache);
        when(queryPipeline.corpusVersion(anyLong())).thenReturn(new CorpusVersion(3L, 0xABCDL, 2));
        return endpoint;
    }

    private String answer(QueryEndpoint endpoint, String requestJson) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(requestJson.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(body);
        endpoint.handle(exchange);
        return body.toString(StandardCharsets.UTF_8);
    }

    /** An expander that widens every query the same way, and counts how often it is asked. */
    private static QueryExpander widening(AtomicInteger calls) {
        return QueryExpander.infallible(query -> {
            calls.incrementAndGet();
            return List.of(SearchFormulation.original(query),
                new SearchFormulation("SIGKILL", 0.9),
                new SearchFormulation("cgroup memory limit", 0.8));
        });
    }

    /** An expander whose model never answers, and which counts how often it is asked. */
    private static QueryExpander failing(AtomicInteger calls) {
        return (query, budget) -> {
            calls.incrementAndGet();
            return Expansion.fellBack(query);
        };
    }

    @Test
    @DisplayName("an explained answer names the terms the query was widened with")
    void namesTheExpansionTerms(@TempDir Path dir) throws Exception {
        QueryExpander expander = CachingQueryExpander.over(widening(new AtomicInteger()));

        String json = answer(lexicalNode(dir, expander),
            "{\"query\": \"why was my process killed\", \"explain\": true}");

        assertThat(json).contains("\"query_expansion\": {\"status\": \"applied\"");
        assertThat(json).contains("{\"term\": \"SIGKILL\", \"weight\": 0.90}");
        assertThat(json)
            .as("the typed query is not one of the terms it was widened with")
            .doesNotContain("{\"term\": \"why was my process killed\"");
    }

    /**
     * Reporting an expansion must not generate a second one: that is both a second bill and a
     * different answer from the one that was searched. No caching decorator here, so a second
     * call would be counted.
     */
    @Test
    @DisplayName("reporting the expansion does not run it again")
    void reportingCostsNothing(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = widening(calls);

        answer(lexicalNode(dir, expander),
            "{\"query\": \"why was my process killed\", \"explain\": true}");

        assertThat(calls.get())
            .as("the search expanded the query; the report read what the search used")
            .isEqualTo(1);
    }

    /** A node that widens nothing says so, rather than leaving the caller to infer it. */
    @Test
    @DisplayName("a node whose expander adds nothing reports the query as searched unwidened")
    void anUnwidenedQuerySaysSo(@TempDir Path dir) throws Exception {
        QueryExpander expander = CachingQueryExpander.over(QueryExpander.identity());

        String json = answer(lexicalNode(dir, expander),
            "{\"query\": \"OOM killer\", \"explain\": true}");

        assertThat(json).contains("\"query_expansion\": {\"status\": \"not_applied\"");
    }

    /**
     * "Not widened" and "cannot say" are different facts. A node with no expander wired has
     * not established the first, and reporting an empty term list under the first would say
     * it had.
     */
    @Test
    @DisplayName("a node with no expander wired says it cannot report the expansion")
    void noExpanderIsUnreported(@TempDir Path dir) throws Exception {
        String json = answer(lexicalNode(dir, null),
            "{\"query\": \"OOM killer\", \"explain\": true}");

        assertThat(json).contains("\"query_expansion\": {\"status\": \"unreported\"");
        assertThat(json).contains("\"terms\": []");
    }

    /**
     * A replayed answer is explained from the expansion it was searched with, which the cache
     * kept alongside it, rather than by asking the model what the query widens to now.
     */
    @Test
    @DisplayName("a replayed answer is explained without expanding the query again")
    void aReplayedAnswerIsExplainedFromWhatItKept(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        QueryEndpoint node = lexicalNode(dir, widening(calls), new QueryResultCache(16));
        String request = "{\"query\": \"why was my process killed\", \"explain\": true}";

        answer(node, request);
        String replayed = answer(node, request);

        assertThat(replayed).contains("\"from_cache\": true");
        assertThat(replayed).contains("\"query_expansion\": {\"status\": \"applied\"");
        assertThat(replayed).contains("{\"term\": \"SIGKILL\", \"weight\": 0.90}");
        assertThat(calls.get())
            .as("the search expanded the query once; the replay read what it kept")
            .isEqualTo(1);
    }

    /**
     * A query the model could not widen was searched as typed. The answer says so, in a status
     * of its own, and is never replayed: the next caller is owed the widened search.
     */
    @Test
    @DisplayName("an expansion that fell back is reported as such and never cached")
    void aFallbackIsReportedAndNotCached(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        QueryEndpoint node = lexicalNode(dir, failing(calls), new QueryResultCache(16));
        String request = "{\"query\": \"OOM killer\", \"explain\": true}";

        String first = answer(node, request);
        String second = answer(node, request);

        assertThat(first).contains("\"query_expansion\": {\"status\": \"fell_back\"");
        assertThat(first).contains("\"terms\": []");
        assertThat(second)
            .as("an answer searched without its expansion is not held for the next caller")
            .contains("\"from_cache\": false")
            .contains("\"query_expansion\": {\"status\": \"fell_back\"");
        assertThat(calls.get()).isEqualTo(2);
    }
}
