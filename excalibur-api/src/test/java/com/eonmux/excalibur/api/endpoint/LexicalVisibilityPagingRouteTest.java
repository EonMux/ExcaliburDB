package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pages of a BM25 answer that withholds documents stay contiguous, and the total stays true.
 *
 * <p>The route ranks as deep as the page reaches and slices the page off the end, reporting an
 * exact total when the ranking came back shorter than that depth. Withheld hits removed from a
 * ranking that was not refilled would therefore shift every later page, duplicate or skip hits
 * across a page boundary, and report an exact total that is simply too small.</p>
 */
@DisplayName("Paging a lexical answer that withholds documents")
class LexicalVisibilityPagingRouteTest extends QueryEndpointTestBase {

    private static final int DOCUMENTS = 10;

    /** Withheld documents, among the best-ranked so that every page is affected. */
    private static final int[] HIDDEN = {0, 2, 3, 6};

    @TempDir
    Path dataDir;

    private ChunkLexicalIndex index;
    private QueryEndpoint lexical;

    @BeforeEach
    void setUpIndex() throws Exception {
        index = ChunkLexicalIndex.open(dataDir.resolve("lexical"), 8.0);
        for (int d = 0; d < DOCUMENTS; d++) {
            index.add(chunkOf(d), textRanked(d));
        }
        index.refresh();
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            new DocumentRegistry(dataDir.resolve("document-registry")),
            new VisibilityTracker(new SegmentCatalog(dataDir.resolve("catalog"))));
        for (int d : HIDDEN) {
            tracker.beginIngestion(ChunkIdentifiers.documentKey("docs", nameOf(d)), 1);
        }
        lexical = new QueryEndpoint(queryPipeline, null, null, null, null);
        lexical.setLexicalQueryService(new LexicalQueryService(index, null, List.of())
            .withVisibility(new DocumentVisibilityTrackerAdapter(tracker)));
    }

    @AfterEach
    void closeIndex() throws Exception {
        index.close();
    }

    @Test
    @DisplayName("walks every visible hit once, in rank order, page by page")
    void pagesAreContiguous() throws Exception {
        List<String> walked = new ArrayList<>();
        Integer offset = 0;
        JsonNode last = null;
        while (offset != null) {
            last = ask(offset, 3);
            for (JsonNode result : last.get("results")) {
                walked.add(result.get("document_id").asText());
            }
            JsonNode next = last.path("page").path("next_offset");
            offset = next.isNull() || next.isMissingNode() ? null : next.asInt();
        }

        assertThat(walked).containsExactly(chunkOf(1), chunkOf(4), chunkOf(5), chunkOf(7),
            chunkOf(8), chunkOf(9));
    }

    @Test
    @DisplayName("fills a page from below the hits it withheld")
    void pageIsFull() throws Exception {
        JsonNode first = ask(0, 3);

        assertThat(first.path("page").path("returned").asInt()).isEqualTo(3);
        assertThat(first.path("result_total").path("relation").asText())
            .as("more visible matches remain past a full page").isEqualTo("at_least");
    }

    @Test
    @DisplayName("reports an exact total of the visible matches when the ranking runs out")
    void exactTotalCountsOnlyVisibleMatches() throws Exception {
        JsonNode all = ask(0, 50);

        assertThat(all.path("result_total").path("value").asInt())
            .isEqualTo(DOCUMENTS - HIDDEN.length);
        assertThat(all.path("result_total").path("relation").asText()).isEqualTo("exact");
    }

    private JsonNode ask(int offset, int topK) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = createMockExchangeWithBody("POST",
            "{\"query\": \"shared\", \"top_k\": " + topK + ", \"offset\": " + offset + "}", body);
        lexical.handle(exchange);
        JsonNode json = new ObjectMapper().readTree(body.toString(StandardCharsets.UTF_8));
        assertThat(json.path("retrieval").asText()).isEqualTo("lexical");
        return json;
    }

    private static String nameOf(int document) {
        return String.format("doc%02d", document);
    }

    private static String chunkOf(int document) {
        return ChunkIdentifiers.chunkId("docs", nameOf(document), 0);
    }

    /** Ten words, of which fewer are the query term the lower the document ranks. */
    private static String textRanked(int document) {
        StringBuilder text = new StringBuilder();
        int matches = DOCUMENTS - document;
        for (int w = 0; w < DOCUMENTS; w++) {
            text.append(w < matches ? "shared" : "filler" + (char) ('a' + document)
                + (char) ('a' + w)).append(' ');
        }
        return text.toString().trim();
    }
}
