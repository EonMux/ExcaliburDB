package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That the node reports the LLM relevancy breaker, not only the embedding one.
 *
 * <p>Stage 5 relevancy scoring is guarded by its own circuit breaker, configured by
 * {@code excalibur.llm.failure-threshold} and {@code excalibur.llm.cooldown-period-ms}. When it
 * opens, the pipeline keeps the vector ranking and the node keeps answering 200, so every
 * ranking it returns changes with nothing anywhere saying so. It is reported the way the
 * embedding breaker is: absent means there is no such component, which is a different statement
 * from a closed breaker.</p>
 */
@DisplayName("Node stats: the LLM relevancy circuit breaker")
class NodeStatsLlmBreakerTest {

    private SegmentCatalog segmentCatalog;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new VisibilityTracker(segmentCatalog));
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(Map.of());
        when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(0);
        when(indexOrchestrator.isRunning()).thenReturn(true);
    }

    @Test
    @DisplayName("is absent on a node with no LLM relevancy scorer")
    void absentWithoutAScorer() {
        assertThat(collector().collect().llmRelevancyBreaker()).isNull();
    }

    @Test
    @DisplayName("reports the state stage 5 is currently running under")
    void reportsTheState() {
        NodeStatsCollector collector = collector();
        collector.withLlmRelevancyBreaker(() -> "OPEN");
        assertThat(collector.collect().llmRelevancyBreaker()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("is independent of the embedding breaker: either can be open alone")
    void isIndependentOfTheEmbeddingBreaker() {
        NodeStatsCollector collector = collector();
        collector.withEmbeddingBreaker(() -> "CLOSED");
        collector.withLlmRelevancyBreaker(() -> "HALF_OPEN");

        NodeStats stats = collector.collect();
        assertThat(stats.embeddingBreaker()).isEqualTo("CLOSED");
        assertThat(stats.llmRelevancyBreaker()).isEqualTo("HALF_OPEN");
    }

    @Test
    @DisplayName("reads as absent, not as closed, when the breaker cannot be asked")
    void absentWhenTheBreakerCannotBeAsked() {
        NodeStatsCollector collector = collector();
        collector.withLlmRelevancyBreaker(() -> {
            throw new IllegalStateException("scorer closed");
        });
        // The rest of the snapshot is what most callers came for; one unreadable breaker
        // must not cost them the segment counts.
        NodeStats stats = collector.collect();
        assertThat(stats.llmRelevancyBreaker()).isNull();
        assertThat(stats.segments()).isNotNull();
    }

    @Test
    @DisplayName("a supplier that reports no breaker reports absence")
    void absentWhenTheSupplierAnswersNull() {
        NodeStatsCollector collector = collector();
        collector.withLlmRelevancyBreaker(() -> null);
        assertThat(collector.collect().llmRelevancyBreaker()).isNull();
    }

    private NodeStatsCollector collector() {
        return new NodeStatsCollector(indexOrchestrator, storageEngine, null);
    }
}
