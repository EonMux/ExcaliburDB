package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.retrieval.SteppedReindex;
import com.eonmux.excalibur.api.retrieval.VisibleChunkText;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.visibility.DocumentAtomicityFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A fused answer given while a reindex rewrites a document shows one version of it or none.
 *
 * <p>The vector half is filtered by the document visibility checker inside the pipeline; the
 * pipeline here stands in for it by answering from what storage holds, filtered by that same
 * filter, so what is under test is the half the pipeline does not own: the BM25 hits fused
 * beside it, and the text each fused result is returned with.</p>
 */
@DisplayName("A hybrid answer during a reindex")
class HybridReindexVisibilityTest extends QueryEndpointTestBase {

    @TempDir
    Path dataDir;

    private SteppedReindex node;

    @AfterEach
    void closeNode() throws Exception {
        if (node != null) {
            node.close();
        }
    }

    @Test
    @DisplayName("never fuses the versions of a document that shrinks")
    void shrinkingDocument() throws Exception {
        node = new SteppedReindex(dataDir);
        node.put("a.txt", "olda", 7);
        node.put("b.txt", "bystander", 2);
        node.reindex();
        Map<String, String> old = node.storedVersionOf("a.txt");
        Map<String, String> bystander = node.storedVersionOf("b.txt");

        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(dense()));
        QueryEndpoint hybrid = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient);
        hybrid.setLexicalQueryService(new LexicalQueryService(node.index(), null, List.of())
            .withVisibility(node.checker()));
        hybrid.setChunkText(VisibleChunkText.over(node.storedText(), node.checker()));

        node.put("a.txt", "newa", 2);
        List<SteppedReindex.Observation> observations =
            node.observeReindex(() -> ask(hybrid));
        Map<String, String> replacement = node.storedVersionOf("a.txt");

        assertThat(replacement.size()).as("precondition: the new version is shorter")
            .isLessThan(old.size());
        for (SteppedReindex.Observation observation : observations) {
            Map<String, String> seen = SteppedReindex.ofDocument("a.txt", observation.returned());
            if (!seen.isEmpty() && !SteppedReindex.partOf(seen, old)
                    && !SteppedReindex.partOf(seen, replacement)) {
                fail("At '%s' a fused answer held part of each version:%n  seen: %s%n"
                        + "  old: %s%n  new: %s", observation.point(), seen,
                    new TreeMap<>(old), new TreeMap<>(replacement));
            }
            assertThat(SteppedReindex.partOf(
                    SteppedReindex.ofDocument("b.txt", observation.returned()), bystander))
                .as("a document nobody is rewriting keeps its text at '%s'", observation.point())
                .isTrue();
        }
    }

    /** The pipeline's answer: every stored chunk, filtered as its rerank stage filters. */
    private QueryResponse dense() {
        List<QueryResultItem> items = new ArrayList<>();
        float score = 0.99f;
        for (String chunkId : node.storedChunks().keySet()) {
            items.add(new QueryResultItem(chunkId, score, null, 1L, items.size()));
            score -= 0.001f;
        }
        List<QueryResultItem> visible = new DocumentAtomicityFilter(node.checker())
            .filterPartialDocuments(items, Long.MAX_VALUE);
        return new QueryResponse(visible, IndexCoverage.builder()
            .totalSegments(1).activeSegments(1).segmentsSearched(1)
            .estimatedCorpusCoverage(1.0).degraded(false).build(), 1L, false, false);
    }

    private Map<String, String> ask(QueryEndpoint hybrid) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = createMockExchangeWithBody("POST",
            "{\"query\": \"" + SteppedReindex.COMMON + "\", \"top_k\": 60, "
                + "\"include_text\": true}", body);
        hybrid.handle(exchange);
        JsonNode json = new ObjectMapper().readTree(body.toString(StandardCharsets.UTF_8));
        assertThat(json.path("retrieval").asText()).isEqualTo("hybrid");
        Map<String, String> returned = new LinkedHashMap<>();
        for (JsonNode result : json.get("results")) {
            JsonNode text = result.get("text");
            returned.put(result.get("document_id").asText(),
                text == null || text.isNull() ? null : text.asText());
        }
        return returned;
    }
}
