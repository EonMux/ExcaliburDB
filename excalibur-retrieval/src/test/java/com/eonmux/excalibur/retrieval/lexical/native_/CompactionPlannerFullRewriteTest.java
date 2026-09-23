package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan an operator's forced compaction runs: every live segment, whatever the policy thinks.
 */
@DisplayName("A full-rewrite compaction plan")
class CompactionPlannerFullRewriteTest {

    private static SegmentManifest.Entry clean(String name, long epoch) {
        return new SegmentManifest.Entry(name, epoch, 10, 0);
    }

    @Test
    @DisplayName("names every segment, clean ones included")
    void namesEverySegment() {
        CompactionPlanner planner = new CompactionPlanner(
            CompactionPolicy.standard(0.2, Duration.ofHours(1), 16), 0.2, Duration.ofHours(1), 16, 2);
        List<SegmentManifest.Entry> segments = List.of(
            clean("s1", 1), clean("s2", 2), clean("s3", 3),
            clean("s4", 8), new SegmentManifest.Entry("s5", 9, 10, 4));

        CompactionPlan plan = planner.fullPlan(segments, 3);

        assertThat(plan.groups())
            .as("below the floor, merged in fan-in groups; the odd one out is rewritten alone")
            .contains(List.of("s1", "s2"), List.of("s3"))
            .as("above the floor, each rewritten alone so no reader's epoch changes")
            .contains(List.of("s4"), List.of("s5"))
            .hasSize(4);
        assertThat(plan.segmentsRewritten()).isEqualTo(5);
    }

    @Test
    @DisplayName("the forced plan, by contrast, leaves clean segments it cannot merge alone")
    void forcedPlanSkipsWhatAFullRewriteTakes() {
        CompactionPlanner planner = CompactionPlanner.standard();
        List<SegmentManifest.Entry> segments = List.of(clean("s1", 1), clean("s2", 9));

        assertThat(planner.forcedPlan(segments, 3).segmentsRewritten()).isZero();
        assertThat(planner.fullPlan(segments, 3).segmentsRewritten()).isEqualTo(2);
    }

    @Test
    @DisplayName("a full rewrite drops a chunk whose text storage no longer holds")
    void fullRewriteSweepsOrphans(@TempDir Path dir) throws Exception {
        Map<String, String> held = new LinkedHashMap<>();
        held.put("kept", "ordinary retained content");
        held.put("orphan", "content whose stored chunk was replaced");
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(held, 1);
            held.remove("orphan");

            CompactionResult forced = store.compactNow(held::get, 5);
            assertThat(forced.segmentsRewritten())
                .as("one clean segment is nothing the forced plan will touch")
                .isZero();
            assertThat(store.liveDocuments(9)).isEqualTo(2);

            CompactionResult full = store.compact(held::get, store.fullPlan(5));

            assertThat(full.performed()).isTrue();
            assertThat(full.segmentsRewritten()).isEqualTo(1);
            assertThat(full.documentsRemoved()).isEqualTo(1);
            assertThat(store.liveDocuments(9)).isEqualTo(1);
        }
    }
}
