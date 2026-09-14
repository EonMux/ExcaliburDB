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

        assertThat(TermFilterCascade.filterCovers(empty, 0L)).isFalse();
        assertThat(TermFilterCascade.survivesTermFilter(empty, 0L, Set.of())).isTrue();
        assertThat(TermFilterCascade.survivesTermFilter(empty, 7L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a registered but unpopulated segment is not covered")
    void registeredButEmptySegmentHasNoVerdict() {
        // Registration allocates the per-segment filter; it says nothing about the terms in it.
        HierarchicalCuckooFilter filter = filterWith(0L);

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a populated segment that matched nothing is excluded")
    void populatedSegmentIsExcludedOnAMiss() {
        // The case the filter exists for: it holds this segment's terms, and none of them
        // are the query's.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice", "quarterly");

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isTrue();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of())).isFalse();
    }

    @Test
    @DisplayName("a populated segment that matched is kept")
    void populatedSegmentIsKeptOnAHit() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of("0"))).isTrue();
    }

    @Test
    @DisplayName("a segment the filter has never heard of is kept, even beside one it has")
    void unknownSegmentSurvivesBesideAKnownOne() {
        // A mixed node: one segment published since startup, others recovered from disk. The
        // recovered ones must not disappear because a newer one happens to be in the filter.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of())).isFalse();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 1L, Set.of())).isTrue();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 99L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a segment whose filter ran out of room excludes nothing")
    void aSaturatedSegmentHasNoVerdict() {
        // The undersized-filter case. Terms were recorded right up to the point the segment's
        // filter filled, so it holds some of what the segment holds and reads as authoritative
        // - and every term it had no room for now prunes the segment that contains it. The
        // documents are in storage and the query answers with nothing.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 1_000, 8);
        filter.registerSegment("0", "cluster-0");
        boolean refused = false;
        for (int i = 0; i < 4096 && !refused; i++) {
            refused = !filter.insert(("term-" + i).getBytes(StandardCharsets.UTF_8), "0");
        }
        assertThat(refused).as("the segment filter has to fill for this test to mean anything")
            .isTrue();

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a full corpus level excludes nothing anywhere")
    void aSaturatedCorpusHasNoVerdict() {
        // The corpus filter is the first level consulted, so a term it could not record is
        // invisible in every segment - not only the one being written when it filled.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(8L, 13, 1_000_000, 1_000_000);
        filter.registerSegment("0", "cluster-0");
        filter.registerSegment("1", "cluster-0");
        filter.insert("invoice".getBytes(StandardCharsets.UTF_8), "1");
        boolean refused = false;
        for (int i = 0; i < 4096 && !refused; i++) {
            refused = !filter.insert(("term-" + i).getBytes(StandardCharsets.UTF_8), "0");
        }
        assertThat(refused).as("the corpus filter has to fill for this test to mean anything")
            .isTrue();

        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of())).isTrue();
        assertThat(TermFilterCascade.survivesTermFilter(filter, 1L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("no filter at all excludes nothing")
    void nullFilterHasNoVerdict() {
        assertThat(TermFilterCascade.filterCovers(null, 0L)).isFalse();
        assertThat(TermFilterCascade.survivesTermFilter(null, 0L, Set.of())).isTrue();
    }
}
