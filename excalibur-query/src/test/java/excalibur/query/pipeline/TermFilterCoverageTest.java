package excalibur.query.pipeline;

import excalibur.filter.HierarchicalCuckooFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests when the term filter is allowed to exclude a segment.
 *
 * <p>The hierarchical filter is built in memory as documents are published and is not reloaded
 * at startup. On a fresh process it holds nothing, and stage 1 read that silence as "this term
 * is in no segment" — so every query carrying a text term returned zero results until the whole
 * corpus had been re-ingested. Nothing errored; the database simply answered that it knew
 * nothing. Stage 2 already applied the opposite rule to an LSH index that does not cover its
 * segment, which is the rule these tests pin down for stage 1.
 */
@DisplayName("Term filter coverage")
class TermFilterCoverageTest {

    private static HierarchicalCuckooFilter filterWith(long segmentId, String... terms) {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 1_000, 100);
        filter.registerSegment(String.valueOf(segmentId), "cluster-0");
        for (String term : terms) {
            filter.insert(term.getBytes(StandardCharsets.UTF_8), String.valueOf(segmentId));
        }
        return filter;
    }

    @Test
    @DisplayName("an empty filter excludes nothing")
    void emptyFilterHasNoVerdict() {
        // The restart case. Every segment must survive, or the node answers nothing at all.
        HierarchicalCuckooFilter empty = new HierarchicalCuckooFilter(10_000L, 13, 1_000, 100);

        assertThat(QueryPipeline.filterCovers(empty, 0L)).isFalse();
        assertThat(QueryPipeline.survivesTermFilter(empty, 0L, Set.of())).isTrue();
        assertThat(QueryPipeline.survivesTermFilter(empty, 7L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a registered but unpopulated segment is not covered")
    void registeredButEmptySegmentHasNoVerdict() {
        // Registration allocates the per-segment filter; it says nothing about the terms in it.
        HierarchicalCuckooFilter filter = filterWith(0L);

        assertThat(QueryPipeline.filterCovers(filter, 0L)).isFalse();
        assertThat(QueryPipeline.survivesTermFilter(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a populated segment that matched nothing is excluded")
    void populatedSegmentIsExcludedOnAMiss() {
        // The case the filter exists for: it holds this segment's terms, and none of them
        // are the query's.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice", "quarterly");

        assertThat(QueryPipeline.filterCovers(filter, 0L)).isTrue();
        assertThat(QueryPipeline.survivesTermFilter(filter, 0L, Set.of())).isFalse();
    }

    @Test
    @DisplayName("a populated segment that matched is kept")
    void populatedSegmentIsKeptOnAHit() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(QueryPipeline.survivesTermFilter(filter, 0L, Set.of("0"))).isTrue();
    }

    @Test
    @DisplayName("a segment the filter has never heard of is kept, even beside one it has")
    void unknownSegmentSurvivesBesideAKnownOne() {
        // A mixed node: one segment published since startup, others recovered from disk. The
        // recovered ones must not disappear because a newer one happens to be in the filter.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(QueryPipeline.survivesTermFilter(filter, 0L, Set.of())).isFalse();
        assertThat(QueryPipeline.survivesTermFilter(filter, 1L, Set.of())).isTrue();
        assertThat(QueryPipeline.survivesTermFilter(filter, 99L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("no filter at all excludes nothing")
    void nullFilterHasNoVerdict() {
        assertThat(QueryPipeline.filterCovers(null, 0L)).isFalse();
        assertThat(QueryPipeline.survivesTermFilter(null, 0L, Set.of())).isTrue();
    }
}
