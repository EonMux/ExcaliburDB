package excalibur.query.scorer;

import excalibur.query.QueryResultItem;
import excalibur.query.pipeline.QueryPipeline.LlmScorer;
import io.excalibur.llm.RelevancyScore;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LlmRelevancyScorer.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Correct extraction of candidate text from QueryResultItem payloads</li>
 *   <li>Proper integration with LLMHttpClient for relevancy scoring</li>
 *   <li>Threshold filtering works correctly</li>
 *   <li>Error handling for LLM failures</li>
 *   <li>Domain correctness for scores and filtering</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LlmRelevancyScorerTest {

    @Mock
    private LLMHttpClient llmClient;

    private LlmRelevancyScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new LlmRelevancyScorer(llmClient);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null LLM client")
        void rejectsNullClient() {
            assertThatThrownBy(() -> new LlmRelevancyScorer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("LLMHttpClient cannot be null");
        }

        @Test
        @DisplayName("creates scorer with valid client")
        void createsWithValidClient() {
            assertThatCode(() -> new LlmRelevancyScorer(llmClient))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("scoreResults method")
    class ScoreResultsTests {

        @Test
        @DisplayName("returns empty list for empty input")
        void returnsEmptyForEmptyInput() {
            List<QueryResultItem> result = scorer.scoreResults("test query", Collections.emptyList(), 0.5f);

            assertThat(result).isEmpty();
            // No LLM call should be made
            verifyNoInteractions(llmClient);
        }

        @Test
        @DisplayName("rejects null query text")
        void rejectsNullQueryText() {
            List<QueryResultItem> items = List.of(
                new QueryResultItem(1L, 0.5f, "test".getBytes(StandardCharsets.UTF_8), 1L, 1L)
            );

            assertThatThrownBy(() -> scorer.scoreResults(null, items, 0.5f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queryText cannot be null");
        }

        @Test
        @DisplayName("rejects null results")
        void rejectsNullResults() {
            assertThatThrownBy(() -> scorer.scoreResults("test query", null, 0.5f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("results cannot be null");
        }

        @Test
        @DisplayName("extracts payload text and calls LLM")
        void extractsPayloadAndCallsLlm() throws Exception {
            // Setup
            String queryText = "What is machine learning?";
            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "Machine learning is a subset of AI.", 1L, 1L),
                createItem(2L, 0.7f, "Python is a programming language.", 1L, 2L)
            );

            List<RelevancyScore> llmScores = List.of(
                new RelevancyScore(0, 0.95),  // First item is highly relevant
                new RelevancyScore(1, 0.3)    // Second item is less relevant
            );

            when(llmClient.scoreRelevancyWithCircuitBreaker(eq(queryText), anyList(), any())).thenReturn(llmScores);

            // Execute
            List<QueryResultItem> result = scorer.scoreResults(queryText, items, 0.5f);

            // Verify LLM was called with correct candidates
            verify(llmClient).scoreRelevancyWithCircuitBreaker(eq(queryText), argThat(candidates -> {
                List<String> c = (List<String>) candidates;
                return c.size() == 2 &&
                    c.get(0).equals("Machine learning is a subset of AI.") &&
                    c.get(1).equals("Python is a programming language.");
            }), any());

            // Verify results - only first item passes threshold of 0.5
            assertThat(result).hasSize(1);
            assertThat(result.get(0).documentId()).isEqualTo(1L);
            assertThat(result.get(0).score()).isEqualTo(0.95f);
        }

        @Test
        @DisplayName("filters results below threshold")
        void filtersBelowThreshold() throws Exception {
            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "Result 1", 1L, 1L),
                createItem(2L, 0.7f, "Result 2", 1L, 2L),
                createItem(3L, 0.6f, "Result 3", 1L, 3L)
            );

            List<RelevancyScore> llmScores = List.of(
                new RelevancyScore(0, 0.9),   // Above threshold
                new RelevancyScore(1, 0.4),   // Below threshold
                new RelevancyScore(2, 0.7)    // Above threshold
            );

            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any())).thenReturn(llmScores);

            List<QueryResultItem> result = scorer.scoreResults("query", items, 0.5f);

            // Only items with score >= 0.5 should be included
            assertThat(result).hasSize(2);
            assertThat(result.stream().map(QueryResultItem::documentId).toList())
                .containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("sorts results by score descending")
        void sortsByScoreDescending() throws Exception {
            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "Result 1", 1L, 1L),
                createItem(2L, 0.7f, "Result 2", 1L, 2L),
                createItem(3L, 0.6f, "Result 3", 1L, 3L)
            );

            List<RelevancyScore> llmScores = List.of(
                new RelevancyScore(0, 0.6),
                new RelevancyScore(1, 0.9),   // Highest score
                new RelevancyScore(2, 0.75)
            );

            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any())).thenReturn(llmScores);

            List<QueryResultItem> result = scorer.scoreResults("query", items, 0.5f);

            // Results should be sorted by LLM score descending
            assertThat(result).hasSize(3);
            assertThat(result.get(0).documentId()).isEqualTo(2L); // score 0.9
            assertThat(result.get(0).score()).isEqualTo(0.9f);
            assertThat(result.get(1).documentId()).isEqualTo(3L); // score 0.75
            assertThat(result.get(1).score()).isEqualTo(0.75f);
            assertThat(result.get(2).documentId()).isEqualTo(1L); // score 0.6
            assertThat(result.get(2).score()).isEqualTo(0.6f);
        }

        @Test
        @DisplayName("handles items without payload")
        void handlesItemsWithoutPayload() throws Exception {
            List<QueryResultItem> items = List.of(
                new QueryResultItem(1L, 0.8f, null, 1L, 1L),  // No payload
                createItem(2L, 0.7f, "Valid content", 1L, 2L)
            );

            List<RelevancyScore> llmScores = List.of(
                new RelevancyScore(0, 0.5),
                new RelevancyScore(1, 0.8)
            );

            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any())).thenReturn(llmScores);

            scorer.scoreResults("query", items, 0.3f);

            // Verify LLM was called with empty string for null payload
            verify(llmClient).scoreRelevancyWithCircuitBreaker(anyString(), argThat(candidates -> {
                List<String> c = (List<String>) candidates;
                return c.size() == 2 &&
                    c.get(0).isEmpty() &&
                    c.get(1).equals("Valid content");
            }), any());
        }

        @Test
        @DisplayName("preserves original metadata in scored results")
        void preservesOriginalMetadata() throws Exception {
            QueryResultItem original = createItem(42L, 0.8f, "content", 10L, 100L);

            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any()))
                .thenReturn(List.of(new RelevancyScore(0, 0.9)));

            List<QueryResultItem> result = scorer.scoreResults("query", List.of(original), 0.5f);

            assertThat(result).hasSize(1);
            QueryResultItem scored = result.get(0);
            assertThat(scored.documentId()).isEqualTo(42L);     // Preserved
            assertThat(scored.segmentId()).isEqualTo(10L);      // Preserved
            assertThat(scored.nodeId()).isEqualTo(100L);        // Preserved
            assertThat(scored.score()).isEqualTo(0.9f);         // Updated
        }

        @Test
        @DisplayName("propagates LLM exception")
        void propagatesLlmException() throws Exception {
            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "content", 1L, 1L)
            );

            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any()))
                .thenThrow(new LLMException("LLM unavailable", LLMException.ErrorType.NETWORK));

            assertThatThrownBy(() -> scorer.scoreResults("query", items, 0.5f))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("LLM unavailable");
        }

        @Test
        @DisplayName("clamps threshold to valid range [0.0, 1.0]")
        void clampsThreshold() throws Exception {
            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "content", 1L, 1L)
            );

            // Score of 0.5 should pass even with out-of-range thresholds after clamping
            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any()))
                .thenReturn(List.of(new RelevancyScore(0, 0.5)));

            // Threshold > 1.0 is clamped to 1.0, so score 0.5 should NOT pass (0.5 < 1.0)
            List<QueryResultItem> result = scorer.scoreResults("query", items, 2.0f);
            assertThat(result).isEmpty(); // 0.5 < 1.0 so filtered out

            // Threshold < 0.0 is clamped to 0.0, so all scores >= 0 should pass
            when(llmClient.scoreRelevancyWithCircuitBreaker(anyString(), anyList(), any()))
                .thenReturn(List.of(new RelevancyScore(0, 0.5)));

            result = scorer.scoreResults("query", items, -1.0f);
            assertThat(result).hasSize(1); // 0.5 >= 0.0 so passes
        }
    }

    @Nested
    @DisplayName("LlmScorerFactory tests")
    class FactoryTests {

        @Test
        @DisplayName("creates scorer from client")
        void createsFromClient() {
            LlmScorer scorerFromFactory = LlmScorerFactory.fromClient(llmClient);
            assertThat(scorerFromFactory).isNotNull();
            assertThat(scorerFromFactory).isInstanceOf(LlmRelevancyScorer.class);
        }

        @Test
        @DisplayName("rejects null client in factory")
        void rejectsNullClientInFactory() {
            assertThatThrownBy(() -> LlmScorerFactory.fromClient(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("LLMHttpClient cannot be null");
        }

        @Test
        @DisplayName("creates no-op scorer that returns input unchanged")
        void createsNoOpScorer() {
            LlmScorer noOp = LlmScorerFactory.noOp();

            List<QueryResultItem> items = List.of(
                createItem(1L, 0.8f, "content", 1L, 1L)
            );

            // No-op scorer should return items unchanged
            List<QueryResultItem> result = noOp.scoreResults("query", items, 0.5f);
            assertThat(result).isSameAs(items);
        }
    }

    // Helper method to create QueryResultItem with payload
    private QueryResultItem createItem(long docId, float score, String payload, long segmentId, long nodeId) {
        return new QueryResultItem(docId, score, payload.getBytes(StandardCharsets.UTF_8), segmentId, nodeId);
    }
}
