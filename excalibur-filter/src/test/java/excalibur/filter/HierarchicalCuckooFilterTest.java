package excalibur.filter;

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
 *   <li>Deletion from hierarchy removes from ALL levels (segment, cluster, corpus)</li>
 *   <li>No stale false positives after deletion</li>
 *   <li>Segment registration creates cluster filters as needed</li>
 *   <li>Candidate segment search prunes using hierarchy</li>
 * </ul>
 */
class HierarchicalCuckooFilterTest {

    private HierarchicalCuckooFilter hierarchy;
    private static final String CLUSTER_1 = "cluster-1";
    private static final String CLUSTER_2 = "cluster-2";
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
        @DisplayName("should register segment and create cluster filter")
        void shouldRegisterSegmentAndCreateCluster() {
            CuckooFilter segmentFilter = hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

            assertThat(segmentFilter).isNotNull();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1)).isSameAs(segmentFilter);
            assertThat(hierarchy.getClusterFilter(CLUSTER_1)).isNotNull();
            assertThat(hierarchy.getSegmentCount()).isEqualTo(1);
            assertThat(hierarchy.getClusterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should reuse cluster filter for multiple segments in same cluster")
        void shouldReuseClusterFilter() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            hierarchy.registerSegment(SEGMENT_2, CLUSTER_1);

            assertThat(hierarchy.getClusterFilter(CLUSTER_1)).isNotNull();
            assertThat(hierarchy.getSegmentCount()).isEqualTo(2);
            assertThat(hierarchy.getClusterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should create separate cluster filters for different clusters")
        void shouldCreateSeparateClusterFilters() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            hierarchy.registerSegment(SEGMENT_2, CLUSTER_2);

            assertThat(hierarchy.getClusterFilter(CLUSTER_1)).isNotSameAs(hierarchy.getClusterFilter(CLUSTER_2));
            assertThat(hierarchy.getClusterCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should throw on null segment ID")
        void shouldThrowOnNullSegmentId() {
            assertThatThrownBy(() -> hierarchy.registerSegment(null, CLUSTER_1))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on null cluster ID")
        void shouldThrowOnNullClusterId() {
            assertThatThrownBy(() -> hierarchy.registerSegment(SEGMENT_1, null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on empty segment ID")
        void shouldThrowOnEmptySegmentId() {
            assertThatThrownBy(() -> hierarchy.registerSegment("", CLUSTER_1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Insertion")
    class InsertionTests {

        @Test
        @DisplayName("should insert into all hierarchy levels")
        void shouldInsertIntoAllLevels() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

            byte[] item = toBytes("test-item-1");
            boolean inserted = hierarchy.insert(item, SEGMENT_1);

            assertThat(inserted).isTrue();
            assertThat(hierarchy.getCorpusFilter().contains(item)).isTrue();
            assertThat(hierarchy.getClusterFilter(CLUSTER_1).contains(item)).isTrue();
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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_1)).isTrue();
            assertThat(hierarchy.mightContainInSegment(item, SEGMENT_2)).isFalse();
        }

        @Test
        @DisplayName("should find candidate segments")
        void shouldFindCandidateSegments() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            hierarchy.registerSegment(SEGMENT_2, CLUSTER_1);
            hierarchy.registerSegment(SEGMENT_3, CLUSTER_2);

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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);

            boolean deleted = hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(deleted).isTrue();
            assertThat(hierarchy.getCorpusFilter().contains(item)).isFalse();
            assertThat(hierarchy.getClusterFilter(CLUSTER_1).contains(item)).isFalse();
            assertThat(hierarchy.getSegmentFilter(SEGMENT_1).contains(item)).isFalse();
        }

        @Test
        @DisplayName("should return false when deleting non-existent item")
        void shouldReturnFalseWhenDeletingNonExistent() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            byte[] item = toBytes("non-existent");

            boolean deleted = hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("should decrement total entries on deletion")
        void shouldDecrementTotalEntriesOnDeletion() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            byte[] item = toBytes("test-item");
            hierarchy.insert(item, SEGMENT_1);
            long countBefore = hierarchy.getTotalEntries();

            hierarchy.deleteFromHierarchy(item, SEGMENT_1);

            assertThat(hierarchy.getTotalEntries()).isEqualTo(countBefore - 1);
        }

        @Test
        @DisplayName("should delete from hierarchy without segment ID (searches all segments)")
        void shouldDeleteWithoutSegmentId() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
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
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

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
    @DisplayName("String Key Helpers")
    class StringKeyHelperTests {

        @Test
        @DisplayName("should insert string key")
        void shouldInsertStringKey() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);

            boolean inserted = hierarchy.insertKey("test-key", SEGMENT_1);

            assertThat(inserted).isTrue();
            assertThat(hierarchy.mightContainKey("test-key", SEGMENT_1)).isTrue();
        }

        @Test
        @DisplayName("should delete string key")
        void shouldDeleteStringKey() {
            hierarchy.registerSegment(SEGMENT_1, CLUSTER_1);
            hierarchy.insertKey("test-key", SEGMENT_1);

            boolean deleted = hierarchy.deleteKey("test-key", SEGMENT_1);

            assertThat(deleted).isTrue();
            assertThat(hierarchy.mightContainKey("test-key", SEGMENT_1)).isFalse();
        }
    }

    private static byte[] toBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
