package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The part of a passage the query actually matched.
 *
 * <p>Text on a result is the chunk clipped from its start, which on a chunk of any size is the
 * part of it least likely to contain the query. A search result has to show the matched region,
 * and it has to say where in the chunk that region came from and where inside it the terms are,
 * or the caller cannot highlight anything it was shown.</p>
 */
@DisplayName("Query-centred snippets")
class QuerySnippetTest {

    private static final String CHUNK_ID = "kb:oom.txt:chunk:0";

    /** A passage whose match is a long way past the point a clip from the start would reach. */
    private static final String PASSAGE =
        "Introduction. ".repeat(24)
        + "The kernel OOM killer sent SIGKILL to the process. "
        + "Afterwards. ".repeat(24);

    private QueryPipeline queryPipeline;
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
    }

    private QueryEndpoint endpointOver(String passage, int maxResultTextChars) {
        QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient, ApiSecurityConfig.builder()
                .maxResultTextChars(maxResultTextChars).build());
        endpoint.setChunkText(Map.of(CHUNK_ID, passage)::get);
        return endpoint;
    }

    private String answer(QueryEndpoint endpoint, String requestJson) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(requestJson, body);
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenReturn(CompletableFuture.completedFuture(new QueryResponse(
                List.of(new QueryResultItem(CHUNK_ID, 0.9f, null, 3L, 7L)),
                IndexCoverage.builder().totalSegments(1).activeSegments(1)
                    .segmentsSearched(1).estimatedCorpusCoverage(1.0).build(),
                12L, false, false)));
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

    /** The {@code text} of the first result, unescaped enough for these passages. */
    private static String textOf(String json) {
        Matcher matcher = Pattern.compile("\"text\": \"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).as("the answer carries result text").isTrue();
        return matcher.group(1);
    }

    private static int intField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\": (-?[0-9]+)").matcher(json);
        assertThat(matcher.find()).as("the answer carries " + field).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * The whole reason for the feature. A chunk clipped from the start shows the caller the
     * introduction of a document that matched on something four hundred characters further in.
     */
    @Test
    @DisplayName("the window returned is the part of the passage the query matched")
    void theWindowIsCentredOnTheMatch() throws IOException {
        String json = answer(endpointOver(PASSAGE, 4096),
            "{\"query\": \"OOM killer\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 120}");

        String text = textOf(json);
        assertThat(text).contains("OOM killer");
        assertThat(text.length()).isLessThanOrEqualTo(120);
        assertThat(json).contains("\"text_truncated\": true");
    }

    /**
     * A window without its offset is a quotation that cannot be located in the document it came
     * from, which is what a caller needs to open the source at the right place.
     */
    @Test
    @DisplayName("the window says where in the chunk it starts")
    void theWindowNamesItsOffset() throws IOException {
        String json = answer(endpointOver(PASSAGE, 4096),
            "{\"query\": \"OOM killer\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 120}");

        int offset = intField(json, "text_offset");
        assertThat(offset).isGreaterThan(0);
        assertThat(PASSAGE.substring(offset, offset + textOf(json).length()))
            .as("the offset locates the returned window in the chunk, verbatim")
            .isEqualTo(textOf(json));
    }

    /**
     * Locatable, not merely present. A caller shown a window has to be able to mark the terms
     * inside it without re-implementing the server's tokenisation and guessing at its rules.
     */
    @Test
    @DisplayName("the matched terms are located inside the window that was returned")
    void matchedTermsAreLocatable() throws IOException {
        String json = answer(endpointOver(PASSAGE, 4096),
            "{\"query\": \"OOM killer\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 120}");

        String text = textOf(json);
        Matcher highlight = Pattern.compile(
            "\\{\"start\": ([0-9]+), \"length\": ([0-9]+)}").matcher(json);
        assertThat(highlight.find()).as("the answer carries highlights").isTrue();
        int start = Integer.parseInt(highlight.group(1));
        int length = Integer.parseInt(highlight.group(2));
        assertThat(text.substring(start, start + length).toLowerCase())
            .isIn("oom", "killer");
    }

    /** A passage shorter than the window is returned whole, and says it was not clipped. */
    @Test
    @DisplayName("a passage that fits the window is returned whole and unclipped")
    void aShortPassageIsWhole() throws IOException {
        String json = answer(endpointOver("the OOM killer ran", 4096),
            "{\"query\": \"OOM killer\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 120}");

        assertThat(textOf(json)).isEqualTo("the OOM killer ran");
        assertThat(json).contains("\"text_truncated\": false");
        assertThat(intField(json, "text_offset")).isZero();
    }

    /**
     * The existing contract. A request that asks for text and nothing else gets the chunk from
     * its start, exactly as before, and none of the window's fields.
     */
    @Test
    @DisplayName("include_text on its own still returns the chunk from the start")
    void includeTextAloneIsUnchanged() throws IOException {
        String json = answer(endpointOver(PASSAGE, 4096),
            "{\"query\": \"OOM killer\", \"include_text\": true}");

        assertThat(textOf(json)).startsWith("Introduction.");
        assertThat(json).doesNotContain("\"text_offset\"");
        assertThat(json).doesNotContain("\"highlights\"");
    }

    /**
     * A snippet is a window onto text the caller did not ask for. Silently turning the text on
     * would hand back a field the request never requested; silently ignoring the flag would
     * answer a different question from the one that was asked.
     */
    @Test
    @DisplayName("a snippet asked for without text is refused")
    void snippetWithoutTextIsRefused() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(
            "{\"query\": \"OOM\", \"snippet\": true}", body);

        endpointOver(PASSAGE, 4096).handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("snippet");
    }

    /**
     * The window is a size the caller picks, so it is held to the same ceiling as the text it
     * is a window onto - and refused there rather than trimmed, so a caller asking for more
     * than the node serves is told instead of being quietly given less.
     */
    @Test
    @DisplayName("a window wider than the node's result-text ceiling is refused")
    void tooWideAWindowIsRefused() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(
            "{\"query\": \"OOM\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 5000}", body);

        endpointOver(PASSAGE, 256).handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("snippet_chars");
    }

    /** A window of nothing is not a window. */
    @Test
    @DisplayName("a window of zero characters is refused")
    void aZeroWindowIsRefused() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(
            "{\"query\": \"OOM\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 0}", body);

        endpointOver(PASSAGE, 4096).handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("snippet_chars");
    }

    /**
     * A query no part of the passage contains still has to return something: the result ranked,
     * and the caller asked for its text.
     */
    @Test
    @DisplayName("a passage matching no query term still returns its opening window")
    void noMatchStillReturnsAWindow() throws IOException {
        String json = answer(endpointOver(PASSAGE, 4096),
            "{\"query\": \"zookeeper\", \"include_text\": true, \"snippet\": true, "
            + "\"snippet_chars\": 120}");

        assertThat(textOf(json)).startsWith("Introduction.");
        assertThat(json).contains("\"highlights\": []");
        assertThat(intField(json, "text_offset")).isZero();
    }
}
