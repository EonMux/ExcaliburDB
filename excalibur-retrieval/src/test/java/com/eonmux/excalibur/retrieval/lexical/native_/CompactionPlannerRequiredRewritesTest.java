package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Segments a compaction has to rewrite for what they are rather than for what they hold.
 *
 * <p>The policy judges tombstones and segment counts, and neither moves for a segment that
 * cannot be opened or one written in an older format: a damaged segment with nothing erased in
 * it, or an old one on a store that never erases, would otherwise be left as it is for good.</p>
 */
@DisplayName("A compaction plan with required rewrites")
class CompactionPlannerRequiredRewritesTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static SegmentManifest.Entry clean(String name, long epoch) {
        return new SegmentManifest.Entry(name, epoch, 10, 0);
    }

    private static CompactionPlanner planner(int mergeFanIn) {
        return new CompactionPlanner(CompactionPolicy.standard(0.2, Duration.ofHours(1), 16),
            0.2, Duration.ofHours(1), 16, mergeFanIn);
    }

    @Test
    @DisplayName("nothing required and nothing due plans nothing")
    void nothingRequired() {
        List<SegmentManifest.Entry> segments = List.of(clean("s1", 1), clean("s2", 2));

        assertThat(planner(8).plan(segments, Optional.empty(), NOW, 5,
            CompactionPlanner.RequiredRewrites.NONE)).isEmpty();
    }

    @Test
    @DisplayName("a damaged segment is rebuilt though the policy sees nothing due")
    void damagedSegmentIsPlannedWithoutThePolicy() {
        List<SegmentManifest.Entry> segments = List.of(clean("s1", 1), clean("s2", 2));

        Optional<CompactionPlan> plan = planner(8).plan(segments, Optional.empty(), NOW, 5,
            new CompactionPlanner.RequiredRewrites(Set.of("s2"), Set.of()));

        assertThat(plan).isPresent();
        assertThat(plan.get().groups())
            .as("rewritten alone, which keeps its epoch and so changes no reader's view")
            .containsExactly(List.of("s2"));
        assertThat(plan.get().reason()).contains("1 damaged segment(s)");
    }

    @Test
    @DisplayName("every damaged segment is rebuilt in one pass, above the floor as well")
    void everyDamagedSegmentAtOnce() {
        List<SegmentManifest.Entry> segments = List.of(
            clean("s1", 1), clean("s2", 2), clean("s3", 9));

        CompactionPlan plan = planner(2).plan(segments, Optional.empty(), NOW, 5,
            new CompactionPlanner.RequiredRewrites(Set.of("s1", "s2", "s3"), Set.of()))
            .orElseThrow();

        assertThat(plan.groups()).as("not bounded by the fan-in: each is a wrong answer now")
            .containsExactlyInAnyOrder(List.of("s1"), List.of("s2"), List.of("s3"));
    }

    @Test
    @DisplayName("outdated segments are upgraded a fan-in's worth at a time, oldest first")
    void outdatedSegmentsAreBounded() {
        List<SegmentManifest.Entry> segments = List.of(
            clean("s1", 1), clean("s2", 2), clean("s3", 3), clean("s4", 4));

        CompactionPlan plan = planner(2).plan(segments, Optional.empty(), NOW, 0,
            new CompactionPlanner.RequiredRewrites(Set.of(), Set.of("s4", "s2", "s1")))
            .orElseThrow();

        assertThat(plan.groups()).containsExactly(List.of("s1"), List.of("s2"));
        assertThat(plan.reason()).contains("2 of 3 segment(s) written in an older format");
    }

    @Test
    @DisplayName("a required rewrite joins what the policy chose rather than displacing it")
    void joinsThePolicysPlan() {
        List<SegmentManifest.Entry> segments = List.of(
            new SegmentManifest.Entry("erased", 1, 10, 8), clean("damaged", 2),
            clean("old", 3));

        CompactionPlan plan = planner(8).plan(segments, Optional.empty(), NOW, 0,
            new CompactionPlanner.RequiredRewrites(Set.of("damaged"), Set.of("old")))
            .orElseThrow();

        assertThat(plan.groups()).containsExactlyInAnyOrder(
            List.of("erased"), List.of("damaged"), List.of("old"));
        assertThat(plan.reason()).startsWith("due: ");
    }

    @Test
    @DisplayName("a segment the policy already rewrites is not named twice")
    void notNamedTwice() {
        List<SegmentManifest.Entry> segments = List.of(
            new SegmentManifest.Entry("s1", 1, 10, 5), new SegmentManifest.Entry("s2", 1, 10, 5));

        CompactionPlan plan = planner(8).plan(segments, Optional.empty(), NOW, 5,
            new CompactionPlanner.RequiredRewrites(Set.of("s1"), Set.of("s2"))).orElseThrow();

        assertThat(plan.groups()).as("merged below the floor, and that rewrite serves both")
            .containsExactly(List.of("s1", "s2"));
    }

    @Test
    @DisplayName("a forced plan takes every required rewrite, including a lone clean segment")
    void forcedPlanTakesThem() {
        List<SegmentManifest.Entry> segments = List.of(clean("s1", 1), clean("s2", 9),
            clean("s3", 9));

        CompactionPlan plan = planner(8).forcedPlan(segments, 3,
            new CompactionPlanner.RequiredRewrites(Set.of("s1"), Set.of("s2", "s3")));

        assertThat(plan.groups()).containsExactlyInAnyOrder(
            List.of("s1"), List.of("s2"), List.of("s3"));
    }

    @Test
    @DisplayName("a name the store does not hold is ignored rather than planned")
    void unknownNamesIgnored() {
        List<SegmentManifest.Entry> segments = List.of(clean("s1", 1));

        assertThat(planner(8).plan(segments, Optional.empty(), NOW, 5,
            new CompactionPlanner.RequiredRewrites(Set.of("gone"), Set.of("also-gone"))))
            .isEmpty();
    }

    @Test
    @DisplayName("a segment both damaged and outdated is counted as damaged")
    void damagedWins() {
        CompactionPlanner.RequiredRewrites both =
            new CompactionPlanner.RequiredRewrites(Set.of("s1"), Set.of("s1", "s2"));

        assertThat(both.outdated()).containsExactly("s2");
        assertThat(both.isEmpty()).isFalse();
        assertThat(CompactionPlanner.RequiredRewrites.NONE.isEmpty()).isTrue();
        assertThatThrownBy(() -> new CompactionPlanner.RequiredRewrites(null, Set.of()))
            .isInstanceOf(NullPointerException.class);
    }
}
