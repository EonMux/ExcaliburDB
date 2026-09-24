package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.SegmentQueryTask;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The atomicity filter runs whenever the tracker holds anything to withhold.
 *
 * <p>A restarted node recovers the documents a previous process left in flight and has
 * published nothing yet, so its tracker counts no committed documents at all. Skipping the filter
 * on that count served every half-written document the journal exists to hide, until the first
 * publication of the new process switched the filter back on.</p>
 */
@DisplayName("Rerank over a tracker holding only documents in flight")
class RerankInFlightOnlyTest {

    @Test
    @DisplayName("withholds the document in flight and keeps the one the tracker never saw")
    void withholdsTheInFlightDocument() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        tracker.beginIngestion("src:torn.md", 1);
        RerankStage stage = new RerankStage(L2Distance.getInstance(), 0.5,
            new DocumentVisibilityTrackerAdapter(tracker), null, request -> false);

        PipelineContext ranked = stage.rerank(
            searched(List.of("src:torn.md:chunk:0", "src:kept.md:chunk:0")),
            new CancellationToken());

        assertThat(tracker.getDocumentCount()).as("precondition: nothing is published").isZero();
        assertThat(ranked.mergedResults).extracting(QueryResultItem::documentIdStr)
            .containsExactly("src:kept.md:chunk:0");
    }

    private static PipelineContext searched(List<String> names) {
        List<HnswSearchResult> hits = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            hits.add(new HnswSearchResult(i, 0.1f * (i + 1), names.get(i)));
        }
        QueryRequest request = QueryRequest.builder()
            .queryText("q")
            .queryVector(new float[] {0.1f, 0.2f})
            .topK(5)
            .requireNamedResults(true)
            .build();
        return new PipelineContext(request, new CancellationToken(), 1L, List.of(), 0)
            .withSegmentResults(List.of(
                new SegmentQueryTask.SegmentQueryResult(7L, hits, 0, 1, true, null)), 0, 0);
    }
}
