package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether a pipeline answer says its scores are a relevancy model's judgement.
 *
 * <p>The flag is what tells a caller how to read {@code score}, so it must follow what Stage 5
 * actually did - on a finished answer and on the partial one an interruption hands back - and
 * never be claimed by an answer a stage failed to produce.</p>
 */
@DisplayName("ResponseAssembler llm_scored")
class ResponseAssemblerLlmScoredTest {

    private ResponseAssembler assembler;
    private QueryRequest request;
    private PipelineContext context;

    @BeforeEach
    void setUp() {
        StorageEngine storage = mock(StorageEngine.class);
        SegmentCatalog catalog = mock(SegmentCatalog.class);
        when(storage.getCatalog()).thenReturn(catalog);
        when(catalog.getAll()).thenReturn(Map.of());
        assembler = new ResponseAssembler(new CoverageCounts(storage), 0.95, null);
        request = QueryRequest.builder()
            .queryText("q")
            .queryVector(new float[] {0.1f, 0.2f})
            .topK(2)
            .build();
        context = new PipelineContext(request, new CancellationToken(), 1L, List.of(), 0)
            .withMergedResults(List.of(new QueryResultItem("a", 0.8f, null, 1L, 1L)));
    }

    @Test
    @DisplayName("a finished answer says it was scored when Stage 5 scored it")
    void finishedAndScored() {
        QueryResponse response = assembler.buildResponse(context.withLlmScored(true), 3L);

        assertThat(response.llmScored()).isTrue();
    }

    @Test
    @DisplayName("a finished answer says it was not when Stage 5 did not run")
    void finishedUnscored() {
        assertThat(assembler.buildResponse(context, 3L).llmScored()).isFalse();
    }

    @Test
    @DisplayName("an interrupted answer keeps what the last completed stage did")
    void interruptedKeepsTheFlag() {
        QueryResponse response = assembler.handlePipelineError(new TimeoutException("late"),
            request, 3L, new CancellationToken(), context.withLlmScored(true));

        assertThat(response.timedOut()).isTrue();
        assertThat(response.llmScored()).isTrue();
    }

    @Test
    @DisplayName("a failed answer never claims to have been scored")
    void failedIsNeverScored() {
        QueryResponse response = assembler.handlePipelineError(
            new IllegalStateException("stage broke"), request, 3L, new CancellationToken(),
            context.withLlmScored(true));

        assertThat(response.results()).isEmpty();
        assertThat(response.llmScored()).isFalse();
    }
}
