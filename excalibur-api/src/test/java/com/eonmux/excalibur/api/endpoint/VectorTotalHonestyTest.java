package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A vector answer shorter than its page is exact only when the pipeline ran out of matches.
 *
 * <p>The route used to read a short vector ranking as the end of the matches whatever made it
 * short. It is not the end when the pipeline stopped for another reason - its depth ceiling, a
 * result it could not name, a result removed between search and rerank - and the pipeline says
 * which through {@link QueryResponse#retrievalExhausted()}. A fused answer is short only when
 * both halves are, so the vector half's word counts there too.</p>
 */
@DisplayName("Totals of a short vector answer")
class VectorTotalHonestyTest extends QueryEndpointTestBase {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("are a floor when the pipeline did not run out of matches")
    void shortButNotExhaustedIsAFloor() throws Exception {
        answerWith(false);

        JsonNode answer = ask(endpoint);

        assertThat(answer.path("result_total").path("value").asInt()).isEqualTo(2);
        assertThat(answer.path("result_total").path("relation").asText()).isEqualTo("at_least");
    }

    @Test
    @DisplayName("are exact when the pipeline ran out of matches")
    void shortAndExhaustedIsExact() throws Exception {
        answerWith(true);

        JsonNode answer = ask(endpoint);

        assertThat(answer.path("result_total").path("relation").asText()).isEqualTo("exact");
    }

    @Test
    @DisplayName("ask the pipeline for no deeper a refill than the node's max-top-k")
    void refillIsBoundedByMaxTopK() throws Exception {
        AtomicReference<QueryRequest> asked = answerWith(true);

        ask(endpoint);

        assertThat(asked.get().maxSearchDepth())
            .isEqualTo(com.eonmux.excalibur.api.security.ApiSecurityConfig.DEFAULT_MAX_TOP_K);
    }

    @Test
    @DisplayName("of a fused answer are a floor when the vector half did not run out")
    void fusedAnswerNeedsTheVectorHalfExhausted() throws Exception {
        answerWith(false);
        try (ChunkLexicalIndex index = ChunkLexicalIndex.open(dataDir.resolve("lexical"), 8.0)) {
            index.add("docs:c.txt:chunk:0", "shared words about near things");
            index.refresh();
            QueryEndpoint hybrid = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient);
            hybrid.setLexicalQueryService(new LexicalQueryService(index, null, List.of()));

            JsonNode answer = ask(hybrid);

            assertThat(answer.path("retrieval").asText()).isEqualTo("hybrid");
            assertThat(answer.path("result_total").path("relation").asText())
                .isEqualTo("at_least");
        }
    }

    private AtomicReference<QueryRequest> answerWith(boolean exhausted) {
        AtomicReference<QueryRequest> asked = new AtomicReference<>();
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenAnswer(invocation -> {
                asked.set(invocation.getArgument(0));
                return CompletableFuture.completedFuture(new QueryResponse(List.of(
                    new QueryResultItem("docs:a.txt:chunk:0", 0.9f, null, 1L, 0L),
                    new QueryResultItem("docs:b.txt:chunk:0", 0.8f, null, 1L, 1L)),
                    IndexCoverage.builder().totalSegments(1).activeSegments(1)
                        .segmentsSearched(1).estimatedCorpusCoverage(1.0).degraded(false)
                        .build(), 5L, false, false).withRetrievalExhausted(exhausted));
            });
        return asked;
    }

    private JsonNode ask(QueryEndpoint target) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        target.handle(createMockExchangeWithBody("POST",
            "{\"query\": \"shared near\", \"top_k\": 5, \"enable_llm_scoring\": false}", body));
        return new ObjectMapper().readTree(body.toString(StandardCharsets.UTF_8));
    }
}
