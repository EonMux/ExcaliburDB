package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for HierarchicalCuckooFilter.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Deletion from hierarchy removes from both levels (segment, corpus)</li>
 *   <li>No stale false positives after deletion</li>
 *   <li>Segment registration creates one filter per segment, and nothing between them</li>
 *   <li>Candidate segment search prunes using hierarchy</li>
 * </ul>
 */
class HierarchicalCuckooFilterTest {

    private HierarchicalCuckooFilter hierarchy;
    private static final String SEGMENT_1 = "segment-1";
    private static final String SEGMENT_2 = "segment-2";
    private static final String SEGMENT_3 = "segment-3";

    @BeforeEach
    void setUp() {
        hierarchy = new HierarchicalCuckooFilter(1_000_000);
    }

    @Nested
    @DisplayName("Segment Registration")
    class SegmentRegistrationTests {

        @Test
        @DisplayName("should register a segment with a filter of its own")
        void shouldRegisterSegment() {
            CuckooFilter segmentFilter = hierarchy.registerSegment(SEGMENT_1);

            assertThat(segmentFilter).isNotNull();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1)).isSameAs(segmentFilter);
            assertThat(hierarchy.getSegmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("registering a segment twice keeps the filter it already has")
        void registeringTwiceKeepsTheFilter() {
            CuckooFilter first = hierarchy.registerSegment(SEGMENT_1);
            hierarchy.insert(toBytes("kept"), SEGMENT_1);

            assertThat(hierarchy.registerSegment(SEGMENT_1)).isSameAs(first);
            assertThat(hierarchy.mightContainInSegment(toBytes("kept"), SEGMENT_1)).isTrue();
        }

        @Test
        @DisplayName("gives each segment its own filter")
        void givesEachSegmentItsOwnFilter() {
            hierarchy.registerSegment(SEGMENT_1);
            hierarchy.registerSegment(SEGMENT_2);

            assertThat(hierarchy.getSegmentFilter(SEGMENT_1))
                .isNotSameAs(hierarchy.getSegmentFilter(SEGMENT_2));
            assertThat(hierarchy.getSegmentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("takes a filter built elsewhere, stale until a rebuild vouches for it")
        void registersAnExistingFilter() {
            CuckooFilter built =
                CuckooFilter.create(1_000, 13, hierarchy.getCorpusFilter().seed());
            built.insert(toBytes("prebuilt"));

            hierarchy.registerSegment(SEGMENT_1, built);

            assertThat(hierarchy.getSegmentFilter(SEGMENT_1)).isSameAs(built);
            assertThat(hierarchy.isSegmentStale(SEGMENT_1))
                .as("nothing says the supplied filter describes the segment")
                .isTrue();
            assertThat(hierarchy.coversSegment(SEGMENT_1)).isFalse();

            long generation = hierarchy.beginSegmentRebuild(SEGMENT_1);
            hierarchy.insert(toBytes("prebuilt"), SEGMENT_1);
            hierarchy.completeSegmentRebuild(SEGMENT_1, generation);
            assertThat(hierarchy.coversSegment(SEGMENT_1)).isTrue();
        }

        @Test
        @DisplayName("replacing a trusted level with a supplied filter withdraws the trust")
        void replacingATrustedLevelWithdrawsTrust() {
            trust(hierarchy, SEGMENT_1, "alpha");
            assertThat(hierarchy.coversSegment(SEGMENT_1)).isTrue();

            CuckooFilter built =
                CuckooFilter.create(1_000, 13, hierarchy.getCorpusFilter().seed());
            built.insert(toBytes("beta"));
            hierarchy.registerSegment(SEGMENT_1, built);

            assertThat(hierarchy.coversSegment(SEGMENT_1)).isFalse();
        }

        @Test
        @DisplayName("a level created by the first write is born stale")
        void aCreatedLevelIsBornStale() {
            // The restart case: a reopened segment's level is created by the first chunk
            // written after the restart, and knows only that chunk's terms.
            hierarchy.registerSegment(SEGMENT_1);
            hierarchy.insert(toBytes("alpha"), SEGMENT_1);

            assertThat(hierarchy.isSegmentStale(SEGMENT_1)).isTrue();
            assertThat(hierarchy.coversSegment(SEGMENT_1)).isFalse();
            assertThat(hierarchy.coverageStamp(SEGMENT_1))
                .isEqualTo(HierarchicalCuckooFilter.NOT_COVERED);
        }

        @Test
        @DisplayName("registering an id that already has a level leaves its trust alone")
        void reRegisteringKeepsTrust() {
            trust(hierarchy, SEGMENT_1, "alpha");
            long stamp = hierarchy.coverageStamp(SEGMENT_1);

            CuckooFilter again = hierarchy.registerSegment(SEGMENT_1);

            assertThat(again).isSameAs(hierarchy.getSegmentFilter(SEGMENT_1));
            assertThat(hierarchy.coverageStamp(SEGMENT_1)).isEqualTo(stamp);
        }

        @Test
        @DisplayName("unregistering an id withdraws its trust, and a rebuild of the old level "
            + "cannot restore it")
        void unregisteringWithdrawsTrust() {
            long generation = hierarchy.beginSegmentRebuild(SEGMENT_1);
            hierarchy.insert(toBytes("alpha"), SEGMENT_1);

            hierarchy.unregisterSegment(SEGMENT_1);

            assertThat(hierarchy.completeSegmentRebuild(SEGMENT_1, generation)).isFalse();
            hierarchy.registerSegment(SEGMENT_1);
            hierarchy.insert(toBytes("alpha"), SEGMENT_1);
            assertThat(hierarchy.coversSegment(SEGMENT_1)).isFalse();
        }

        @Test
        @DisplayName("refuses a filter that does not hash as the corpus level does")
        void refusesAFilterHashedDifferently() {
            long seed = hierarchy.getCorpusFilter().seed();

            assertThatThrownBy(() -> hierarchy.registerSegment(SEGMENT_1,
                    CuckooFilter.create(1_000, 13, seed + 1)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hierarchy.registerSegment(SEGMENT_1,
                    CuckooFilter.create(1_000, 16, seed)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hierarchy.registerSegment(SEGMENT_1,
                    CuckooFilter.create(100_000_000, 13, seed)))
                .as("more buckets than the corpus level")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses an existing filter without a segment or a filter")
        void refusesIncompleteExistingFilter() {
            CuckooFilter built = CuckooFilter.create(1_000, 13);

            assertThatThrownBy(() -> hierarchy.registerSegment(null, built))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> hierarchy.registerSegment(SEGMENT_1, (CuckooFilter) null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> hierarchy.registerSegment("", built))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should throw on null segment ID")
        void shouldThrowOnNullSegmentId() {
            assertThatThrownBy(() -> hierarchy.registerSegment(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on empty segment ID")
        void shouldThrowOnEmptySegmentId() {
            assertThatThrownBy(() -> hierarchy.registerSegment(""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Insertion")
    class InsertionTests {

        @Test
        @DisplayName("should insert into all hierarchy levels")
        void shouldInsertIntoAllLevels() {
            hierarchy.registerSegment(SEGMENT_1);

            byte[] item = toBytes("test-item-1");
            boolean inserted = hierarchy.insert(item, SEGMENT_1);

            assertThat(inserted).isTrue();
            assertThat(hierarchy.getCorpusFilter().contains(item)).isTrue();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item)).isTrue();
        }

        @Test
        @DisplayName("should throw on insert to unregistered segment")
        void shouldThrowOnInsertToUnregisteredSegment() {
            byte[] item = toBytes("test-item-1");

            assertThatThrownBy(() -> hierarchy.insert(item, "unknown-segment"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should track total entries")
        void shouldTrackTotalEntries() {
            hierarchy.registerSegment(SEGMENT_1);

            hierarchy.insert(toBytes("item-1"), SEGMENT_1);
            hierarchy.insert(toBytes("item-2"), SEGMENT_1);
            hierarchy.insert(toBytes("item-3"), SEGMENT_1);

            assertThat(hierarchy.getTotalEntries()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Membership Testing")
    class MembershipTests {

        @Test
        @DisplayName("should return false when item not in corpus filter")
        void shouldReturnFalseWhenNotInCorpus() {
            byte[] item = toBytes("non-existent");
            assertThat(hierarchy.mightContain(item)).isFalse();
        }

        @Test
        @DisplayName("should check full hierarchy for segment membership")
        void shouldCheckFullHierarchyForSegment() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_1)).isTrue();
            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_2)).isFalse();
        }

        @Test
        @DisplayName("should find candidate segments")
        void shouldFindCandidateSegments() {
            hierarchy.registerSegment(SEGMENT_1);
            hierarchy.registerSegment(SEGMENT_2);
            hierarchy.registerSegment(SEGMENT_3);

            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            List<String> candidates = hierarchy.findCandidateSegments(item);

            assertThat(candidates).containsExactly(SEGMENT_1);
            assertThat(candidates).doesNotContain(SEGMENT_2, SEGMENT_3);
        }
    }

    @Nested
    @DisplayName("Hierarchical Deletion")
    class HierarchicalDeletionTests {

        @Test
        @DisplayName("should delete from all hierarchy levels")
        void shouldDeleteFromAllLevels() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            boolean deleted = hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(deleted).isTrue();
            assertThat(hierarchy.getCorpusFilter().contains(item)).isFalse();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item)).isFalse();
        }

        @Test
        @DisplayName("should return false when deleting non-existent item")
        void shouldReturnFalseWhenDeletingNonExistent() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("non-existent");

            boolean deleted = hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("should decrement total entries on deletion")
        void shouldDecrementTotalEntriesOnDeletion() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);
            long countBefore = hierarchy.getTotalEntries();

            hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(hierarchy.getTotalEntries()).isEqualTo(countBefore - 1);
        }

        @Test
        @DisplayName("should delete from hierarchy without segment ID (searches all segments)")
        void shouldDeleteWithoutSegmentId() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            boolean deleted = hierarchy.deleteFromHierarchy(item);

            assertThat(deleted).isTrue();
            assertThat(hierarchy.getCorpusFilter().contains(item)).isFalse();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item)).isFalse();
        }

        @Test
        @DisplayName("should delete multiple items in batch")
        void shouldDeleteMultipleInBatch() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item1 = toBytes("item-1");
            byte[] item2 = toBytes("item-2");
            byte[] item3 = toBytes("item-3");
            hierarchy.insert(item1, SEGMENT_1);
            hierarchy.insert(item2, SEGMENT_1);
            hierarchy.insert(item3, SEGMENT_1);

            int deleted = hierarchy.deleteFromHierarchy(List.of(item1, item2), SEGMENT_1);

            assertThat(deleted).isEqualTo(2);
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item1)).isFalse();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item2)).isFalse();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item3)).isTrue();
        }

        @Test
        @DisplayName("should handle deletion from unknown segment gracefully")
        void shouldHandleDeletionFromUnknownSegment() {
            byte[] item = toBytes("test-item");

            boolean deleted = hierarchy.deleteFromHierarchy(item, "unknown-segment");

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("should throw on null item in delete")
        void shouldThrowOnNullItem() {
            hierarchy.registerSegment(SEGMENT_1);

            assertThatThrownBy(() -> hierarchy.deleteFromHierarchy((byte[]) null, SEGMENT_1))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("No False Positives After Deletion")
    class NoFalsePositivesTests {

        @Test
        @DisplayName("should not have false positives after deletion")
        void shouldNotHaveFalsePositivesAfterDeletion() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item = toBytes("unique-test-item-for-fp-check");
            hierarchy.insert(item, SEGMENT_1);

            // Verify it's found before deletion
            assertThat(hierarchy.mightContain(item)).isTrue();
            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_1)).isTrue();

            // Delete from hierarchy
            hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            // Should NOT be found at any level
            assertThat(hierarchy.mightContain(item)).isFalse();
            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_1)).isFalse();

            // Candidate search should not return the segment
            List<String> candidates = hierarchy.findCandidateSegments(item);
            assertThat(candidates).doesNotContain(SEGMENT_1);
        }

        @Test
        @DisplayName("should isolate deletions to specific item")
        void shouldIsolateDeletions() {
            hierarchy.registerSegment(SEGMENT_1);
            byte[] item1 = toBytes("item-1");
            byte[] item2 = toBytes("item-2");
            hierarchy.insert(item1, SEGMENT_1);
            hierarchy.insert(item2, SEGMENT_1);

            hierarchy.deleteFromHierarchy(item1, SEGMENT_1);

            // Item 1 should be gone
            assertThat(hierarchy.mightContainInSegment(item1, SEGMENT_1)).isFalse();
            // Item 2 should still be there
            assertThat(hierarchy.mightContainInSegment(item2, SEGMENT_1)).isTrue();
        }
    }

    @Nested
    @DisplayName("Segment Removal")
    class SegmentRemovalTests {

        @Test
        @DisplayName("should unregister segment")
        void shouldUnregisterSegment() {
            hierarchy.registerSegment(SEGMENT_1);

            boolean removed = hierarchy.unregisterSegment(SEGMENT_1);

            assertThat(removed).isTrue();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1)).isNull();
            assertThat(hierarchy.getSegmentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should return false when unregistering unknown segment")
        void shouldReturnFalseWhenUnregisteringUnknown() {
            boolean removed = hierarchy.unregisterSegment("unknown-segment");
            assertThat(removed).isFalse();
        }
    }

    @Nested
    @DisplayName("Saturation withdraws the level's authority")
    class SaturationTests {

        /** Fills a segment until the hierarchy refuses a term, and returns how many it took. */
        private int fillUntilRefused(HierarchicalCuckooFilter filter, String segment, int bound) {
            for (int i = 0; i < bound; i++) {
                if (!filter.insert(toBytes(segment + "-term-" + i), segment)) {
                    return i;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("a segment that refused a term can no longer answer for that segment")
        void aRefusedTermWithdrawsTheSegmentsVerdict() {
            // A cuckoo filter that cannot accept a term does not hold it, and a query for that
            // term would read the filter's silence as "not in this segment" and skip it. The
            // documents are in storage and the answer is empty, which is the one outcome a
            // pre-filter must never produce.
            HierarchicalCuckooFilter small = new HierarchicalCuckooFilter(1_000_000, 13, 8);
            trust(small, SEGMENT_1);
            trust(small, SEGMENT_2, "kept");

            assertThat(fillUntilRefused(small, SEGMENT_1, 4096))
                .as("the segment level has to fill for this test to mean anything")
                .isNotNegative();

            assertThat(small.coversSegment(SEGMENT_1)).isFalse();
            assertThat(small.coversSegment(SEGMENT_2))
                .as("one segment running out of room says nothing about another")
                .isTrue();
        }

        @Test
        @DisplayName("a full corpus level withdraws every segment's verdict")
        void aFullCorpusLevelWithdrawsEveryVerdict() {
            // The corpus filter is the first level findCandidateSegments consults, so a term it
            // has no room for is invisible everywhere - not only in the segment being written.
            // Spread over many segments, so the one level that holds every term is the one
            // that runs out of room first.
            HierarchicalCuckooFilter tiny = new HierarchicalCuckooFilter(8, 13, 8);
            tiny.registerSegment(SEGMENT_1);
            tiny.registerSegment(SEGMENT_2);
            tiny.insert(toBytes("kept"), SEGMENT_2);
            for (int i = 0; i < 4096 && !tiny.isSaturated(); i++) {
                String spread = "spread-" + (i % 64);
                tiny.registerSegment(spread);
                tiny.insert(toBytes("term-" + i), spread);
            }

            assertThat(tiny.isSaturated()).isTrue();
            assertThat(tiny.coversSegment(SEGMENT_1)).isFalse();
            assertThat(tiny.coversSegment(SEGMENT_2)).isFalse();
        }

        @Test
        @DisplayName("a populated segment that never refused anything is authoritative")
        void aPopulatedSegmentIsAuthoritative() {
            trust(hierarchy, SEGMENT_1, "alpha");

            assertThat(hierarchy.coversSegment(SEGMENT_1)).isTrue();
            assertThat(hierarchy.isSaturated()).isFalse();
        }

        @Test
        @DisplayName("a segment holding nothing has no verdict to give")
        void anEmptySegmentHasNoVerdict() {
            hierarchy.registerSegment(SEGMENT_1);

            assertThat(hierarchy.coversSegment(SEGMENT_1)).isFalse();
            assertThat(hierarchy.coversSegment("never-registered")).isFalse();
        }

        @Test
        @DisplayName("a segment rebuilt from scratch is trusted again")
        void aRebuiltSegmentIsTrustedAgain() {
            // A split replaces a segment with children, and a reindex re-registers one. The new
            // filter has refused nothing, so carrying the old mark would disable pruning for a
            // segment id forever.
            HierarchicalCuckooFilter small = new HierarchicalCuckooFilter(1_000_000, 13, 8);
            small.registerSegment(SEGMENT_1);
            assertThat(fillUntilRefused(small, SEGMENT_1, 4096)).isNotNegative();
            assertThat(small.coversSegment(SEGMENT_1)).isFalse();

            small.unregisterSegment(SEGMENT_1);
            trust(small, SEGMENT_1, "fresh");

            assertThat(small.coversSegment(SEGMENT_1)).isTrue();
        }
    }

    @Nested
    @DisplayName("String Key Helpers")
    class StringKeyHelperTests {

        @Test
        @DisplayName("should insert string key")
        void shouldInsertStringKey() {
            hierarchy.registerSegment(SEGMENT_1);

            boolean inserted = hierarchy.insertKey("test-key", SEGMENT_1);

            assertThat(inserted).isTrue();
            assertThat(hierarchy.mightContainKey("test-key", SEGMENT_1)).isTrue();
        }

        @Test
        @DisplayName("should delete string key")
        void shouldDeleteStringKey() {
            hierarchy.registerSegment(SEGMENT_1);
            hierarchy.insertKey("test-key", SEGMENT_1);

            boolean deleted = hierarchy.deleteKey("test-key", SEGMENT_1);

            assertThat(deleted).isTrue();
            assertThat(hierarchy.mightContainKey("test-key", SEGMENT_1)).isFalse();
        }
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("refuses a level that could hold nothing")
        void refusesEmptyLevels() {
            assertThatThrownBy(() -> new HierarchicalCuckooFilter(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HierarchicalCuckooFilter(1_000, 13, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HierarchicalCuckooFilter(CuckooFilter.create(1_000, 13), 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HierarchicalCuckooFilter(null, 100))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new HierarchicalCuckooFilter(8, 13, 1_000_000))
                .as("a segment sized past the corpus")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("every segment filter hashes with the corpus filter's seed")
        void segmentsShareTheCorpusSeed() {
            assertThat(hierarchy.registerSegment(SEGMENT_1).seed())
                .isEqualTo(hierarchy.getCorpusFilter().seed());
        }

        @Test
        @DisplayName("builds on a corpus filter it is given, at that filter's fingerprint width")
        void buildsOnAGivenCorpusFilter() {
            CuckooFilter corpus = CuckooFilter.create(10_000, 16);
            HierarchicalCuckooFilter built = new HierarchicalCuckooFilter(corpus, 100);

            built.registerSegment(SEGMENT_1);
            built.insert(toBytes("term"), SEGMENT_1);

            assertThat(built.getCorpusFilter()).isSameAs(corpus);
            assertThat(corpus.contains(toBytes("term"))).isTrue();
            assertThat(built.getSegmentFilter(SEGMENT_1).fingerprintBits()).isEqualTo(16);
            assertThat(built.segmentCapacity()).isEqualTo(100);
        }

        @Test
        @DisplayName("deleting a term keeps a corpus entry another segment's colliding term needs")
        void aCollisionSurvivesTheOtherTermsDelete() {
            // The corpus level stores a fingerprint once. Two different terms that land on the
            // same fingerprint and buckets there share that entry, so deleting it for one would
            // leave the other denied at the corpus level and its segment pruned.
            HierarchicalCuckooFilter small = new HierarchicalCuckooFilter(8, 8, 8);
            small.registerSegment(SEGMENT_1);
            small.registerSegment(SEGMENT_2);
            byte[] deleted = toBytes("deleted");
            small.insert(deleted, SEGMENT_1);
            byte[] survivor = null;
            for (int i = 0; survivor == null && i < 10_000_000; i++) {
                byte[] candidate = toBytes("candidate-" + i);
                if (small.getCorpusFilter().contains(candidate)) {
                    survivor = candidate;
                }
            }
            assertThat(survivor).as("a colliding term has to exist for this to mean anything")
                .isNotNull();
            small.insert(survivor, SEGMENT_2);

            assertThat(small.deleteFromHierarchy(deleted, SEGMENT_1)).isTrue();

            assertThat(small.getCorpusFilter().contains(survivor)).isTrue();
            assertThat(small.findCandidateSegments(survivor)).contains(SEGMENT_2);
        }

        @Test
        @DisplayName("describes itself by the two levels it has")
        void describesItsLevels() {
            hierarchy.registerSegment(SEGMENT_1);

            assertThat(hierarchy.toString())
                .startsWith("HierarchicalCuckooFilter[segments=1, entries=0,");
        }
    }

    /** Refills a segment's level with these terms and has the rebuild vouch for it. */
    private static void trust(HierarchicalCuckooFilter filter, String segment, String... terms) {
        long generation = filter.beginSegmentRebuild(segment);
        for (String term : terms) {
            filter.insert(toBytes(term), segment);
        }
        filter.completeSegmentRebuild(segment, generation);
    }

    private static byte[] toBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
