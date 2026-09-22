package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A term that appears in many segments.
 *
 * <p>The ordinary case for any real corpus, and the one the hierarchy used to break on. The
 * shared filters above the segments were given one entry per (term, segment) pair, and a cuckoo filter
 * stores a fingerprint in one of two buckets holding a fixed handful of entries each — so the
 * ninth segment's insert failed, and the rollback removed the term from that segment's own
 * filter as well. The segment stopped being a candidate for a term it contains.
 *
 * <p>A false negative is the one answer a membership filter must never give: the query path
 * prunes segments on it, so the documents simply stop being findable.
 */
@DisplayName("Hierarchical filter, shared terms")
class HierarchicalFilterSharedTermTest {

    private static final byte[] TERM = "invoice".getBytes(StandardCharsets.UTF_8);

    private static HierarchicalCuckooFilter filter(int segments) {
        HierarchicalCuckooFilter filter =
            new HierarchicalCuckooFilter(100_000L, 13, 1_000);
        for (int i = 0; i < segments; i++) {
            filter.registerSegment(String.valueOf(i));
        }
        return filter;
    }

    @Test
    @DisplayName("a term in far more than a bucket's worth of segments is found in all of them")
    void termInManySegmentsIsFoundEverywhere() {
        int segments = 64;
        HierarchicalCuckooFilter filter = filter(segments);

        for (int i = 0; i < segments; i++) {
            assertThat(filter.insert(TERM, String.valueOf(i)))
                .as("insert into segment %s", i)
                .isTrue();
        }

        List<String> found = filter.findCandidateSegments(TERM);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            expected.add(String.valueOf(i));
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("a failed insert never removes a term the segment already had")
    void insertDoesNotDamageWhatIsAlreadyThere() {
        // The rollback was the damaging half: a shared-level failure took the segment's own
        // entry with it, so a term that was successfully stored became unfindable.
        HierarchicalCuckooFilter filter = filter(32);
        for (int i = 0; i < 32; i++) {
            filter.insert(TERM, String.valueOf(i));
        }

        for (int i = 0; i < 32; i++) {
            assertThat(filter.mightContainInSegment(TERM, String.valueOf(i)))
                .as("segment %s still has the term", i)
                .isTrue();
        }
    }

    @Test
    @DisplayName("deleting from one segment leaves the term findable in the others")
    void deletingOneSegmentKeepsTheRest() {
        // The other half of set semantics: the shared entry is now shared, so removing one
        // segment's copy must not take it away from everyone.
        HierarchicalCuckooFilter filter = filter(16);
        for (int i = 0; i < 16; i++) {
            filter.insert(TERM, String.valueOf(i));
        }

        assertThat(filter.deleteFromHierarchy(TERM, "3")).isTrue();

        assertThat(filter.mightContainInSegment(TERM, "3")).isFalse();
        assertThat(filter.mightContain(TERM))
            .as("fifteen segments still have it, so the corpus must still say so")
            .isTrue();
        assertThat(filter.findCandidateSegments(TERM)).hasSize(15).doesNotContain("3");
    }

    @Test
    @DisplayName("deleting the last segment's copy retires the term from the corpus")
    void deletingTheLastCopyRetiresTheTerm() {
        HierarchicalCuckooFilter filter = filter(3);
        for (int i = 0; i < 3; i++) {
            filter.insert(TERM, String.valueOf(i));
        }

        for (int i = 0; i < 3; i++) {
            assertThat(filter.deleteFromHierarchy(TERM, String.valueOf(i))).isTrue();
        }

        assertThat(filter.findCandidateSegments(TERM)).isEmpty();
    }

    @Test
    @DisplayName("deleting a term from one segment does not retire it from another")
    void segmentsAreIndependent() {
        HierarchicalCuckooFilter filter =
            new HierarchicalCuckooFilter(100_000L, 13, 1_000);
        filter.registerSegment("0");
        filter.registerSegment("1");

        filter.insert(TERM, "0");
        filter.insert(TERM, "1");

        assertThat(filter.deleteFromHierarchy(TERM, "0")).isTrue();

        assertThat(filter.mightContain(TERM))
            .as("segment 1 still holds it, so the corpus entry must stay")
            .isTrue();
        assertThat(filter.findCandidateSegments(TERM)).containsExactly("1");
    }
}
