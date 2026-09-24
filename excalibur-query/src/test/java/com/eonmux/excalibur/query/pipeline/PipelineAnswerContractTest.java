package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.SegmentQueryTask;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the pipeline promises about an answer beyond its ranking: whether every result can be
 * named, whether retrieval ran out of candidates, and what a withdrawn query hands back.
 */
@DisplayName("Pipeline answer contract")
class PipelineAnswerContractTest {

    private ResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        StorageEngine storage = mock(StorageEngine.class);
        SegmentCatalog catalog = mock(SegmentCatalog.class);
        when(storage.getCatalog()).thenReturn(catalog);
        when(catalog.getAll()).thenReturn(Map.of());
        assembler = new ResponseAssembler(new CoverageCounts(storage), 0.95, null);
    }

    private static QueryRequest request(int topK, boolean namedOnly) {
        return QueryRequest.builder()
            .queryText("q")
            .queryVector(new float[] {0.1f, 0.2f})
            .topK(topK)
            .requireNamedResults(namedOnly)
            .build();
    }

    /** One segment answering with the given hits; a null name is a node with no mapping. */
    private static PipelineContext searched(QueryRequest request, List<String> names) {
        List<HnswSearchResult> hits = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            hits.add(new HnswSearchResult(i, 0.1f * (i + 1), names.get(i)));
        }
        SegmentQueryTask.SegmentQueryResult segment =
            new SegmentQueryTask.SegmentQueryResult(7L, hits, 0, 1, true, null);
        return new PipelineContext(request, new CancellationToken(), 1L, List.of(), 0)
            .withSegmentResults(List.of(segment), 0, 0);
    }

    private static RerankStage stage() {
        return new RerankStage(L2Distance.getInstance(), 0.5, null, r -> false);
    }

    private static List<String> named(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("src:doc" + i + ".md");
        }
        return names;
    }

    @Nested
    @DisplayName("Results that cannot be named")
    class Unnamed {

        @Test
        @DisplayName("are dropped and counted when the caller can only use named results")
        void droppedForACallerThatNeedsNames() {
            List<String> names = new ArrayList<>();
            names.add(null);
            names.add("src:a.md");
            names.add("");
            names.add("src:b.md");

            PipelineContext ranked = stage().rerank(searched(request(2, true), names),
                new CancellationToken());

            assertThat(ranked.mergedResults)
                .extracting(QueryResultItem::documentIdStr)
                .as("the page is filled from the results that have a name")
                .containsExactly("src:a.md", "src:b.md");
            assertThat(ranked.unnamedResultsDropped).isEqualTo(2);
        }

        @Test
        @DisplayName("are kept for an embedded caller that reads node and segment ids")
        void keptForAnEmbeddedCaller() {
            List<String> names = new ArrayList<>();
            names.add(null);
            names.add("src:a.md");

            PipelineContext ranked = stage().rerank(searched(request(5, false), names),
                new CancellationToken());

            assertThat(ranked.mergedResults).hasSize(2);
            assertThat(ranked.unnamedResultsDropped).isZero();
        }

        @Test
        @DisplayName("make the answer degraded and are counted, because the node found something "
            + "it cannot return")
        void degradeTheAnswer() {
            PipelineContext ranked = searched(request(2, true), named(1))
                .withRanking(List.of(new QueryResultItem("src:a.md", 0.9f, null, 7L, 0L)),
                    true, 3);
            long before = Metrics.getUnnamedResultsDropped();

            QueryResponse response = assembler.buildResponse(ranked, 2L);

            assertThat(response.coverage().degraded()).isTrue();
            assertThat(Metrics.getUnnamedResultsDropped() - before).isGreaterThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Whether retrieval ran out of candidates")
    class Exhaustion {

        @Test
        @DisplayName("is reported when the merge came back shallower than it was asked")
        void shallowMergeIsExhausted() {
            PipelineContext ranked = stage().rerank(searched(request(5, true), named(3)),
                new CancellationToken());

            assertThat(ranked.retrievalExhausted).isTrue();
            assertThat(assembler.buildResponse(ranked, 1L).retrievalExhausted()).isTrue();
        }

        @Test
        @DisplayName("is not reported when the merge filled every candidate slot")
        void fullMergeIsNotExhausted() {
            // Stage 3 asks the merge for twice the depth, so twenty hits fill it for ten.
            PipelineContext ranked = stage().rerank(searched(request(10, true), named(20)),
                new CancellationToken());

            assertThat(ranked.retrievalExhausted).isFalse();
            assertThat(assembler.buildResponse(ranked, 1L).retrievalExhausted()).isFalse();
        }

        @Test
        @DisplayName("is not reported when a segment filled the depth it was asked for")
        void aSegmentThatFilledItsDepthIsNotExhausted() {
            // A segment answers with at most the depth, so one segment filling it leaves the
            // merge shallower than twice the depth although that segment may hold more.
            PipelineContext ranked = stage().rerank(searched(request(5, true), named(5)),
                new CancellationToken());

            assertThat(ranked.retrievalExhausted).isFalse();
        }

        @Test
        @DisplayName("is not claimed by an answer a deadline cut short")
        void neverClaimedOnTimeout() {
            PipelineContext ranked = stage().rerank(searched(request(5, true), named(3)),
                new CancellationToken());

            QueryResponse response = assembler.handlePipelineError(new TimeoutException("late"),
                request(5, true), 3L, new CancellationToken(), ranked);

            assertThat(response.results()).as("a timeout still answers with what it reached")
                .isNotEmpty();
            assertThat(response.retrievalExhausted()).isFalse();
        }
    }

    @Nested
    @DisplayName("A withdrawn query")
    class Withdrawn {

        @Test
        @DisplayName("hands back nothing, including what earlier stages had already ranked")
        void answersWithNothing() {
            PipelineContext ranked = stage().rerank(searched(request(5, false), named(3)),
                new CancellationToken()).withLlmScored(true);

            QueryResponse response = assembler.handlePipelineError(
                new CancellationException("withdrawn"), request(5, false), 3L,
                new CancellationToken(), ranked);

            assertThat(response.cancelled()).isTrue();
            assertThat(response.timedOut()).isFalse();
            assertThat(response.results())
                .as("a withdrawn question is not answered, not even in part")
                .isEmpty();
            assertThat(response.llmScored()).isFalse();
            assertThat(response.coverage().degraded()).isTrue();
        }
    }
}
