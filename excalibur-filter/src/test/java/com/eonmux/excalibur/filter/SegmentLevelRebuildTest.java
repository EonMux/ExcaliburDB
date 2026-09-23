package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refilling a segment's level from the segment, and the coverage it withholds meanwhile.
 *
 * <p>A level filled only by writes stops describing its segment when entries arrive another way -
 * a split, a restart, a compaction. Stage 1 orders on {@code coversSegment}, trusting a level
 * that claims coverage to speak for its segment, so between the moment a level stops describing
 * its segment and the moment it has been refilled, it must not claim to.</p>
 */
@DisplayName("Segment level rebuild")
class SegmentLevelRebuildTest {

    private static byte[] term(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a stale level does not cover its segment, even with terms in it")
    void staleLevelDoesNotCover() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        long generation = filter.beginSegmentRebuild("7");
        filter.insert(term("alpha"), "7");
        filter.completeSegmentRebuild("7", generation);
        assertThat(filter.coversSegment("7")).isTrue();

        filter.markSegmentStale("7");

        assertThat(filter.isSegmentStale("7")).isTrue();
        assertThat(filter.coversSegment("7")).isFalse();
        assertThat(filter.mightContainInSegment(term("alpha"), "7"))
            .as("marking stale leaves the level's contents alone")
            .isTrue();
    }

    @Test
    @DisplayName("a mark made before the segment has a level still applies once it has one")
    void markPrecedesTheLevel() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        filter.markSegmentStale("9");

        filter.registerSegment("9");
        filter.insert(term("beta"), "9");

        assertThat(filter.coversSegment("9")).isFalse();
    }

    @Test
    @DisplayName("a rebuild empties the level, and completing it restores coverage")
    void rebuildRefillsAndRestoresCoverage() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        filter.registerSegment("3");
        filter.insert(term("gone"), "3");
        CuckooFilter level = filter.getSegmentFilter("3");
        long entriesBefore = filter.getTotalEntries();

        long generation = filter.beginSegmentRebuild("3");

        assertThat(filter.getSegmentFilter("3")).as("emptied in place").isSameAs(level);
        assertThat(level.count()).isZero();
        assertThat(filter.getTotalEntries()).isEqualTo(entriesBefore - 1);
        assertThat(filter.coversSegment("3")).isFalse();

        filter.insert(term("kept"), "3");
        assertThat(filter.completeSegmentRebuild("3", generation)).isTrue();

        assertThat(filter.coversSegment("3")).isTrue();
        assertThat(filter.isSegmentStale("3")).isFalse();
        assertThat(filter.mightContainInSegment(term("kept"), "3")).isTrue();
        assertThat(filter.mightContainInSegment(term("gone"), "3")).isFalse();
    }

    @Test
    @DisplayName("a rebuild of a segment with no level creates one")
    void rebuildCreatesAMissingLevel() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);

        long generation = filter.beginSegmentRebuild("12");

        assertThat(filter.getSegmentFilter("12")).isNotNull();
        filter.insert(term("delta"), "12");
        assertThat(filter.completeSegmentRebuild("12", generation)).isTrue();
        assertThat(filter.coversSegment("12")).isTrue();
    }

    @Test
    @DisplayName("a mark made during a rebuild is not cleared by it")
    void laterMarkSurvivesAnEarlierRebuild() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        long generation = filter.beginSegmentRebuild("5");
        filter.insert(term("epsilon"), "5");

        filter.markSegmentStale("5");

        assertThat(filter.completeSegmentRebuild("5", generation)).isFalse();
        assertThat(filter.coversSegment("5")).isFalse();

        long next = filter.beginSegmentRebuild("5");
        filter.insert(term("epsilon"), "5");
        assertThat(filter.completeSegmentRebuild("5", next)).isTrue();
        assertThat(filter.coversSegment("5")).isTrue();
    }

    @Test
    @DisplayName("a rebuild drops a refusal recorded against the old contents")
    void rebuildClearsSaturation() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(100_000, 13, 4);
        filter.registerSegment("1");
        for (int i = 0; i < 200; i++) {
            filter.insert(term("t" + i), "1");
        }
        assertThat(filter.saturatedSegmentCount()).isEqualTo(1);

        long generation = filter.beginSegmentRebuild("1");
        filter.insert(term("only"), "1");
        filter.completeSegmentRebuild("1", generation);

        assertThat(filter.saturatedSegmentCount()).isZero();
        assertThat(filter.coversSegment("1")).isTrue();
    }

    @Test
    @DisplayName("isSegmentStale is false for null and for ids never marked")
    void staleQueries() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        assertThat(filter.isSegmentStale(null)).isFalse();
        assertThat(filter.isSegmentStale("0")).isFalse();
        assertThatThrownBy(() -> filter.markSegmentStale(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> filter.completeSegmentRebuild(null, 1))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("clearing a filter empties it and leaves it usable")
    void clearEmptiesTheFilter() {
        CuckooFilter filter = CuckooFilter.create(1_000);
        for (int i = 0; i < 100; i++) {
            filter.insert(term("v" + i));
        }

        filter.clear();

        assertThat(filter.count()).isZero();
        int reported = 0;
        for (int i = 0; i < 100; i++) {
            if (filter.contains(term("v" + i))) {
                reported++;
            }
        }
        assertThat(reported).isZero();
        assertThat(filter.insert(term("again"))).isTrue();
        assertThat(filter.contains(term("again"))).isTrue();
    }

    @Test
    @DisplayName("a filter over a read-only image refuses to be cleared")
    void readOnlyFilterRefusesClear() {
        CuckooFilter source = CuckooFilter.create(100);
        source.insert(term("x"));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = arena.allocate(source.byteSize());
            source.writeTo(image, 0);
            CuckooFilter readOnly = CuckooFilter.readFrom(image.asReadOnly(), 0);

            assertThatThrownBy(readOnly::clear).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
