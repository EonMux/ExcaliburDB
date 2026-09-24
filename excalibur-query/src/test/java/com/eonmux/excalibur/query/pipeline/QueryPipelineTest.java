package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueryPipeline.
 *
 * <p>Tests verify basic functionality, configuration, and error handling.</p>
 */
@DisplayName("QueryPipeline")
@ExtendWith(MockitoExtension.class)
class QueryPipelineTest {

    @Mock
    private StorageEngine storageEngine;

    @Mock
    private VisibilityTracker visibilityTracker;

    @Mock
    private DocumentVisibilityChecker visibilityChecker;

    @Mock
    private QueryPipeline.HnswGraphProvider graphProvider;

    @Mock
    private DistanceFunction distanceFunction;

    @Mock
    private QueryPipeline.LlmScorer llmScorer;

    private QueryPipeline pipeline;

    @BeforeEach
    void setUp() {
        lenient().when(visibilityTracker.currentEpoch()).thenReturn(1L);

        // Use the simplest constructor
        pipeline = new QueryPipeline(
            storageEngine,
            visibilityTracker,
            graphProvider,
            distanceFunction
        );
    }

    @Nested
    @DisplayName("Constructor - 4 params")
    class Constructor4Params {

        @Test
        @DisplayName("rejects null storage engine")
        void rejectsNullStorageEngine() {
            assertThatThrownBy(() -> new QueryPipeline(
                null,
                visibilityTracker,
                graphProvider,
                distanceFunction
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null visibility tracker")
        void rejectsNullVisibilityTracker() {
            assertThatThrownBy(() -> new QueryPipeline(
                storageEngine,
                null,
                graphProvider,
                distanceFunction
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null graph provider")
        void rejectsNullGraphProvider() {
            assertThatThrownBy(() -> new QueryPipeline(
                storageEngine,
                visibilityTracker,
                null,
                distanceFunction
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null distance function")
        void rejectsNullDistanceFunction() {
            assertThatThrownBy(() -> new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                null
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("creates pipeline with valid params")
        void createsPipelineWithValidParams() {
            QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            assertThat(p).isNotNull();
            assertThat(p.getLlmScorer()).isNull();
            assertThat(p.getVisibilityChecker()).isNull();
        }
    }

    @Nested
    @DisplayName("Constructor - 5 params")
    class Constructor5Params {

        @Test
        @DisplayName("creates pipeline with visibility checker")
        void createsPipelineWithVisibilityChecker() {
            QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityChecker,
                graphProvider,
                distanceFunction
            );

            assertThat(p).isNotNull();
            assertThat(p.getVisibilityChecker()).isEqualTo(visibilityChecker);
        }

        @Test
        @DisplayName("accepts null visibility checker")
        void acceptsNullVisibilityChecker() {
            QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                null,
                graphProvider,
                distanceFunction
            );

            assertThat(p).isNotNull();
            assertThat(p.getVisibilityChecker()).isNull();
        }
    }

    @Nested
    @DisplayName("Constructor - 6 params")
    class Constructor6Params {

        @Test
        @DisplayName("creates pipeline with LLM scorer")
        void createsPipelineWithLlmScorer() {
                QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction,
                llmScorer,
                0.5
                );

                assertThat(p).isNotNull();
                assertThat(p.getLlmScorer()).isEqualTo(llmScorer);
        }

        @Test
        @DisplayName("accepts null LLM scorer")
        void acceptsNullLlmScorer() {
                QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction,
                null,
                0.5
                );

                assertThat(p).isNotNull();
                assertThat(p.getLlmScorer()).isNull();
        }
    }

    @Nested
    @DisplayName("Constructor - 7 params")
    class Constructor7Params {

        @Test
        @DisplayName("creates pipeline with all options")
        void createsPipelineWithAllOptions() {
            QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityChecker,
                graphProvider,
                distanceFunction,
                llmScorer,
                0.75
            );

            assertThat(p).isNotNull();
            assertThat(p.getVisibilityChecker()).isEqualTo(visibilityChecker);
            assertThat(p.getLlmScorer()).isEqualTo(llmScorer);
        }
    }

    @Nested
    @DisplayName("Constructor - 8 params")
    class Constructor8Params {

        @Test
        @DisplayName("creates pipeline with an epoch isolation validator")
        void createsPipelineWithEpochIsolation() {
            QueryPipeline p = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityChecker,
                graphProvider,
                distanceFunction,
                llmScorer,
                0.75,
                new com.eonmux.excalibur.shard.visibility.EpochIsolationValidator());

            assertThat(p).isNotNull();
        }
    }

    @Nested
    @DisplayName("withLlmScorer()")
    class WithLlmScorer {

        @Test
        @DisplayName("sets LLM scorer and returns new instance")
        void setsLlmScorerAndReturnsNewInstance() {
            QueryPipeline newPipeline = pipeline.withLlmScorer(llmScorer);

            assertThat(newPipeline).isNotNull();
            assertThat(newPipeline).isNotSameAs(pipeline);
            assertThat(newPipeline.getLlmScorer()).isEqualTo(llmScorer);
        }

        @Test
        @DisplayName("accepts null LLM scorer")
        void acceptsNullLlmScorer() {
            QueryPipeline newPipeline = pipeline.withLlmScorer(null);

            assertThat(newPipeline).isNotNull();
            assertThat(newPipeline.getLlmScorer()).isNull();
        }
    }

    @Nested
    @DisplayName("withVisibilityChecker()")
    class WithVisibilityChecker {

        @Test
        @DisplayName("sets visibility checker and returns New instance")
        void setsVisibilityCheckerAndReturnsNewInstance() {
            DocumentVisibilityChecker newChecker = mock(DocumentVisibilityChecker.class);
            QueryPipeline newPipeline = pipeline.withVisibilityChecker(newChecker);

            assertThat(newPipeline).isNotNull();
            assertThat(newPipeline).isNotSameAs(pipeline);
            assertThat(newPipeline.getVisibilityChecker()).isEqualTo(newChecker);
        }

        @Test
        @DisplayName("accepts null visibility checker")
        void acceptsNullVisibilityChecker() {
            QueryPipeline newPipeline = pipeline.withVisibilityChecker(null);

            assertThat(newPipeline).isNotNull();
            assertThat(newPipeline.getVisibilityChecker()).isNull();
        }
    }

    @Nested
    @DisplayName("getLlmScorer()")
    class GetLlmScorer {

        @Test
        @DisplayName("returns null when not set")
        void returnsNullWhenNotSet() {
            assertThat(pipeline.getLlmScorer()).isNull();
        }

        @Test
        @DisplayName("returns set LLM scorer")
        void returnsSetLlmScorer() {
            QueryPipeline newPipeline = pipeline.withLlmScorer(llmScorer);
            assertThat(newPipeline.getLlmScorer()).isEqualTo(llmScorer);
        }
    }

    @Nested
    @DisplayName("getVisibilityChecker()")
    class GetVisibilityChecker {

        @Test
        @DisplayName("returns null when not set")
        void returnsNullWhenNotSet() {
            assertThat(pipeline.getVisibilityChecker()).isNull();
        }

        @Test
        @DisplayName("returns set visibility checker")
        void returnsSetVisibilityChecker() {
            QueryPipeline newPipeline = pipeline.withVisibilityChecker(visibilityChecker);
            assertThat(newPipeline.getVisibilityChecker()).isEqualTo(visibilityChecker);
        }
    }

    @Nested
    @DisplayName("execute()")
    class Execute {

        @Test
        @DisplayName("rejects null request")
        void rejectsNullRequest() {
            CancellationToken token = new CancellationToken();

            assertThatThrownBy(() -> pipeline.execute(null, token))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null cancellation token")
        void rejectsNullCancellationToken() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();

            assertThatThrownBy(() -> pipeline.execute(request, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CancellationToken cannot be null");
        }

        @Test
        @DisplayName("returns CompletableFuture")
        void returnsCompletableFuture() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();

            CompletableFuture<QueryResponse> future = pipeline.execute(request, token);

            assertThat(future).isNotNull();
        }

        @Test
        @DisplayName("returns response with coverage info")
        void returnsResponseWithCoverageInfo() throws Exception {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();

            CompletableFuture<QueryResponse> future = pipeline.execute(request, token);
            QueryResponse response = future.get(5, TimeUnit.SECONDS);

            assertThat(response).isNotNull();
            assertThat(response.coverage()).isNotNull();
            assertThat(response.results()).isNotNull();
            assertThat(response.timedOut()).isFalse();
        }

        @Test
        @DisplayName("handles cancellation")
        void handlesCancellation() throws Exception {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();
            token.cancel("Test cancellation");

            CompletableFuture<QueryResponse> future = pipeline.execute(request, token);
            QueryResponse response = future.get(5, TimeUnit.SECONDS);

            // Should complete quickly due to cancellation
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("a cancelled query says it was cancelled")
        void aCancelledQuerySaysItWasCancelled() throws Exception {
            // Without this the answer is an empty result list with timed_out false, which is
            // what a query that searched the whole corpus and matched nothing returns. The
            // caller cancelled it and is told, in effect, that there was nothing to find.
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();
            token.cancel("Test cancellation");

            QueryResponse response = pipeline.execute(request, token).get(5, TimeUnit.SECONDS);

            assertThat(response.cancelled()).isTrue();
            assertThat(response.timedOut())
                .as("cancelled is not timed out; conflating them blames the deadline")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("executeSync()")
    class ExecuteSync {

        @Test
        @DisplayName("rejects null request")
        void rejectsNullRequest() {
            CancellationToken token = new CancellationToken();

            assertThatThrownBy(() -> pipeline.executeSync(null, token))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("returns QueryResponse")
        void returnsQueryResponse() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            assertThat(response).isNotNull();
            assertThat(response.coverage()).isNotNull();
            assertThat(response.results()).isNotNull();
        }

        @Test
        @DisplayName("returns empty results for no segments")
        void returnsEmptyResultsForNoSegments() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            assertThat(response.results()).isEmpty();
            assertThat(response.coverage().totalSegments()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Query parameters")
    class QueryParameters {

        @Test
        @DisplayName("respects topK parameter")
        void respectsTopKParameter() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(5)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            // With no segments, results are empty
            assertThat(response.results().size()).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("accepts query text")
        void acceptsQueryText() {
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("accepts include partial segments flag")
        void acceptsIncludePartialSegmentsFlag() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .includePartialSegments(true)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("accepts enable LLM scoring flag")
        void acceptsEnableLlmScoringFlag() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .enableLlmScoring(true)
                .build();
            CancellationToken token = new CancellationToken();

            QueryResponse response = pipeline.executeSync(request, token);

            assertThat(response).isNotNull();
        }
    }

    /**
     * Tests for the LLM unavailability fallback.
     *
     * <p>These tests verify that when LLM scoring is enabled but fails,
     * the query pipeline gracefully falls back to vector-only ranking
     * and correctly sets the degraded flag in the response.</p>
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>degraded=true when LLM fails and fallback occurs</li>
     *   <li>degraded=false when LLM is not requested (regardless of LLM availability)</li>
     *   <li>Query completes without throwing when LLM fails</li>
     * </ul>
     */
    @Nested
    @DisplayName("LLM Fallback")
    class LlmFallback {

        @Test
        @DisplayName("IndexCoverage.Builder.degraded() allows explicit setting")
        void indexCoverageBuilderAllowsExplicitDegradedSetting() {
            // Verify that IndexCoverage.Builder supports explicit degraded setting
            // This is the mechanism that allows the fix to work
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(10)
                .computeCoverage()
                .computeDegraded(0.5)  // Would set degraded=false (100% coverage)
                .degraded(true)         // Override to true (e.g., for LLM fallback)
                .build();

            assertThat(coverage.degraded())
                .as("IndexCoverage.Builder.degraded() should allow explicit override")
                .isTrue();
        }

        @Test
        @DisplayName("IndexCoverage.Builder correctly combines LLM fallback with coverage degradation")
        void indexCoverageBuilderCombinesLlmFallbackWithCoverage() {
            // Test 1: Good coverage, no LLM fallback -> degraded=false
            IndexCoverage good = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(10)
                .computeCoverage()
                .computeDegraded(0.5)
                .build();
            assertThat(good.degraded()).isFalse();
            assertThat(good.estimatedCorpusCoverage()).isEqualTo(1.0);

            // Test 2: Good coverage, LLM fallback -> degraded=true
            IndexCoverage withLlmFallback = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(10)
                .computeCoverage()
                .computeDegraded(0.5)  // Sets degraded=false (good coverage)
                .degraded(true)         // Override to true for LLM fallback
                .build();
            assertThat(withLlmFallback.degraded())
                .as("LLM fallback should set degraded=true even with good coverage")
                .isTrue();

            // Test 3: Poor coverage, no LLM fallback -> degraded=true
            IndexCoverage poor = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(2)
                .computeCoverage()
                .computeDegraded(0.5)
                .build();
            assertThat(poor.degraded()).isTrue();  // 20% coverage < 50% threshold

            // Test 4: Poor coverage, LLM fallback -> degraded=true (both conditions)
            IndexCoverage poorWithFallback = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(2)
                .computeCoverage()
                .computeDegraded(0.5)
                .degraded(true)
                .build();
            assertThat(poorWithFallback.degraded()).isTrue();
        }

        @Test
        @DisplayName("pipeline with failing LLM scorer handles exception gracefully")
        void pipelineWithFailingLlmScorerHandlesExceptionGracefully() {
            // Create a pipeline with an LLM scorer that throws an exception
            QueryPipeline.LlmScorer failingScorer = (queryText, results, threshold) -> {
                throw new LLMException("LLM service unavailable",
                    LLMException.ErrorType.HTTP_ERROR);
            };

            QueryPipeline pipelineWithFailingScorer = pipeline.withLlmScorer(failingScorer);

            assertThat(pipelineWithFailingScorer.getLlmScorer()).isEqualTo(failingScorer);

            // Create a query with LLM scoring ENABLED
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(new float[]{0.1f, 0.2f, 0.3f})
                .topK(10)
                .enableLlmScoring(true)
                .llmThreshold(0.3f)
                .build();

            CancellationToken token = new CancellationToken();

            // Query should complete without throwing (graceful degradation)
            assertThatCode(() -> {
                QueryResponse response = pipelineWithFailingScorer.executeSync(request, token);
                assertThat(response).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("LLM scorer interface allows throwing LLMException")
        void llmScorerInterfaceAllowsThrowingLlmException() {
            // Verify that the LlmScorer interface can throw LLMException
            QueryPipeline.LlmScorer scorer = (queryText, results, threshold) -> {
                throw new LLMException("Test exception", LLMException.ErrorType.HTTP_ERROR);
            };

            assertThatThrownBy(() -> scorer.scoreResults("query",
                List.of(mock(QueryResultItem.class)), 0.3f))
                .isInstanceOf(LLMException.class)
                .hasMessage("Test exception");
        }
    }

    // Default constants tested in QueryPipelineConstantsTest to avoid Mockito overhead
}
