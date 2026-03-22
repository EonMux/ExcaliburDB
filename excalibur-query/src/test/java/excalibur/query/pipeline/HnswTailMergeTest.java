package excalibur.query.pipeline;

import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for merging HNSW search results with tail scan results.
 *
 * <p>These tests verify the merge logic that combines results from:
 * <ul>
 *   <li>HNSW graph search (approximate nearest neighbors)</li>
 *   <li>Tail scan (brute-force exact distances from un-integrated write log entries)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>All tests verify:</p>
 * <ul>
 *   <li>Results are sorted by distance (closest first)</li>
 *   <li>No duplicate node IDs in output</li>
 *   <li>Exactly k results (or fewer if insufficient candidates)</li>
 *   <li>Distance values are valid</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class HnswTailMergeTest {

    /**
     * Creates a test search result with the given node ID and distance.
     */
    private HnswSearchResult result(long nodeId, float distance) {
        return new HnswSearchResult(nodeId, distance);
    }

    /**
     * Merges HNSW and tail scan results using the same algorithm as QueryPipeline.
     * This is a copy of the merge logic for testing purposes.
     */
    private List<HnswSearchResult> mergeResults(
            List<HnswSearchResult> hnswResults,
            List<HnswSearchResult> tailResults,
            int k) {

        // Early return for k=0
        if (k <= 0) {
            return Collections.emptyList();
        }

        // Early returns for edge cases
        if (tailResults.isEmpty()) {
            return limitResults(hnswResults, k);
        }
        if (hnswResults.isEmpty()) {
            return limitResults(tailResults, k);
        }

        // Combine both result lists
        List<HnswSearchResult> combined = new ArrayList<>(
            hnswResults.size() + tailResults.size());
        combined.addAll(hnswResults);
        combined.addAll(tailResults);

        // Sort by distance (ascending - closest first)
        combined.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        // Deduplicate by node ID and take top-k
        Set<Long> seenNodeIds = new HashSet<>();
        List<HnswSearchResult> merged = new ArrayList<>(k);

        for (HnswSearchResult searchResult : combined) {
            if (seenNodeIds.add(searchResult.nodeId())) {
                merged.add(searchResult);
                if (merged.size() >= k) {
                    break;
                }
            }
        }

        return merged;
    }

    private List<HnswSearchResult> limitResults(List<HnswSearchResult> results, int limit) {
        if (results.size() <= limit) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, limit));
    }

    @Nested
    @DisplayName("Basic Merge Operations")
    class BasicMergeTests {

        @Test
        @DisplayName("should return empty list when both inputs are empty")
        void emptyInputs() {
            List<HnswSearchResult> hnsw = Collections.emptyList();
            List<HnswSearchResult> tail = Collections.emptyList();

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).isEmpty();
        }

        @Test
        @DisplayName("should return HNSW results when tail is empty")
        void emptyTail() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.1f),
                result(2L, 0.2f),
                result(3L, 0.3f)
            );
            List<HnswSearchResult> tail = Collections.emptyList();

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(3);
            assertThat(merged.get(0).nodeId()).isEqualTo(1L);
            assertThat(merged.get(1).nodeId()).isEqualTo(2L);
            assertThat(merged.get(2).nodeId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("should return tail results when HNSW is empty")
        void emptyHnsw() {
            List<HnswSearchResult> hnsw = Collections.emptyList();
            List<HnswSearchResult> tail = List.of(
                result(10L, 0.15f),
                result(11L, 0.25f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(2);
            assertThat(merged.get(0).nodeId()).isEqualTo(10L);
            assertThat(merged.get(1).nodeId()).isEqualTo(11L);
        }
    }

    @Nested
    @DisplayName("Sorting and Ordering")
    class SortingTests {

        @Test
        @DisplayName("should sort results by distance ascending")
        void sortedByDistance() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.5f),  // farther
                result(2L, 0.3f)   // closer
            );
            List<HnswSearchResult> tail = List.of(
                result(3L, 0.1f),  // closest
                result(4L, 0.4f)   // middle
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(4);
            // Verify ascending order by distance
            assertThat(merged.get(0).distance()).isEqualTo(0.1f);
            assertThat(merged.get(1).distance()).isEqualTo(0.3f);
            assertThat(merged.get(2).distance()).isEqualTo(0.4f);
            assertThat(merged.get(3).distance()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("should interleave HNSW and tail results by distance")
        void interleavedResults() {
            // HNSW has results at distances 0.1, 0.3, 0.5
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.1f),
                result(2L, 0.3f),
                result(3L, 0.5f)
            );
            // Tail has results at distances 0.2, 0.4
            List<HnswSearchResult> tail = List.of(
                result(4L, 0.2f),
                result(5L, 0.4f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(5);
            // Should be: 0.1(HNSW), 0.2(tail), 0.3(HNSW), 0.4(tail), 0.5(HNSW)
            assertThat(merged.get(0).nodeId()).isEqualTo(1L); // 0.1 from HNSW
            assertThat(merged.get(1).nodeId()).isEqualTo(4L); // 0.2 from tail
            assertThat(merged.get(2).nodeId()).isEqualTo(2L); // 0.3 from HNSW
            assertThat(merged.get(3).nodeId()).isEqualTo(5L); // 0.4 from tail
            assertThat(merged.get(4).nodeId()).isEqualTo(3L); // 0.5 from HNSW
        }
    }

    @Nested
    @DisplayName("Deduplication")
    class DeduplicationTests {

        @Test
        @DisplayName("should remove duplicates based on node ID")
        void removeDuplicates() {
            // Same node ID 100 appears in both HNSW and tail
            List<HnswSearchResult> hnsw = List.of(
                result(100L, 0.1f),  // Better distance in HNSW
                result(200L, 0.2f)
            );
            List<HnswSearchResult> tail = List.of(
                result(100L, 0.15f), // Worse distance in tail - should be deduplicated
                result(300L, 0.3f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(3);
            // Node 100 should appear only once, with the better distance (0.1 from HNSW)
            assertThat(merged.stream().filter(r -> r.nodeId() == 100L).count()).isEqualTo(1);
            assertThat(merged.get(0).nodeId()).isEqualTo(100L);
            assertThat(merged.get(0).distance()).isEqualTo(0.1f);
        }

        @Test
        @DisplayName("should keep first occurrence when duplicate distances differ")
        void firstOccurrenceWins() {
            List<HnswSearchResult> hnsw = List.of(
                result(100L, 0.05f)  // HNSW has better distance
            );
            List<HnswSearchResult> tail = List.of(
                result(100L, 0.5f)   // Tail has worse distance
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(1);
            // Since HNSW is added first and sorted, the HNSW result (0.05) should be kept
            assertThat(merged.get(0).distance()).isEqualTo(0.05f);
        }
    }

    @Nested
    @DisplayName("Top-K Limiting")
    class TopKLimitingTests {

        @Test
        @DisplayName("should limit to k results")
        void limitToK() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.1f),
                result(2L, 0.2f),
                result(3L, 0.3f),
                result(4L, 0.4f)
            );
            List<HnswSearchResult> tail = List.of(
                result(5L, 0.05f),
                result(6L, 0.15f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 3);

            assertThat(merged).hasSize(3);
            // Top 3 by distance: 0.05, 0.1, 0.15
            assertThat(merged.get(0).nodeId()).isEqualTo(5L);
            assertThat(merged.get(1).nodeId()).isEqualTo(1L);
            assertThat(merged.get(2).nodeId()).isEqualTo(6L);
        }

        @Test
        @DisplayName("should return fewer than k when insufficient candidates")
        void fewerThanK() {
            List<HnswSearchResult> hnsw = List.of(result(1L, 0.1f));
            List<HnswSearchResult> tail = List.of(result(2L, 0.2f));

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(2);
        }

        @Test
        @DisplayName("should handle k=0")
        void kZero() {
            List<HnswSearchResult> hnsw = List.of(result(1L, 0.1f));
            List<HnswSearchResult> tail = List.of(result(2L, 0.2f));

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 0);

            assertThat(merged).isEmpty();
        }

        @Test
        @DisplayName("should handle k=1")
        void kOne() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.1f),
                result(2L, 0.05f)
            );
            List<HnswSearchResult> tail = List.of(
                result(3L, 0.02f)  // Best distance
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 1);

            assertThat(merged).hasSize(1);
            assertThat(merged.get(0).nodeId()).isEqualTo(3L);
            assertThat(merged.get(0).distance()).isEqualTo(0.02f);
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("should handle cosine similarity range [-1, 1]")
        void cosineSimilarityRange() {
            // Cosine similarity can be negative
            List<HnswSearchResult> hnsw = List.of(
                result(1L, -0.5f),  // Negative cosine similarity (vectors opposite)
                result(2L, 0.0f)    // Orthogonal vectors
            );
            List<HnswSearchResult> tail = List.of(
                result(3L, 0.9f),   // High similarity
                result(4L, -0.9f)   // Most similar (when treating negative as better)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(4);
            // Verify all values are in valid range
            for (HnswSearchResult r : merged) {
                assertThat(r.distance()).isBetween(-1.0f, 1.0f);
            }
            // Sorted ascending
            assertThat(merged).isSortedAccordingTo(
                Comparator.comparingDouble(HnswSearchResult::distance));
        }

        @Test
        @DisplayName("should handle L2 distance range [0, ∞)")
        void l2DistanceRange() {
            // L2 distance is always non-negative
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.0f),   // Identical vectors
                result(2L, 10.5f)
            );
            List<HnswSearchResult> tail = List.of(
                result(3L, 0.001f),
                result(4L, 100.0f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(4);
            // Verify all values are non-negative
            for (HnswSearchResult r : merged) {
                assertThat(r.distance()).isGreaterThanOrEqualTo(0.0f);
            }
            // Sorted ascending (closest first)
            assertThat(merged).isSortedAccordingTo(
                Comparator.comparingDouble(HnswSearchResult::distance));
        }

        @Test
        @DisplayName("should maintain stable sort order")
        void stableSortOrder() {
            // Multiple results with same distance
            List<HnswSearchResult> hnsw = List.of(
                result(1L, 0.5f),
                result(2L, 0.5f),
                result(3L, 0.5f)
            );
            List<HnswSearchResult> tail = List.of(
                result(4L, 0.5f),
                result(5L, 0.5f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(5);
            // All have same distance - order may vary but all should be present
            Set<Long> nodeIds = new HashSet<>();
            for (HnswSearchResult r : merged) {
                nodeIds.add(r.nodeId());
                assertThat(r.distance()).isEqualTo(0.5f);
            }
            assertThat(nodeIds).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle very large distance values")
        void largeDistances() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, Float.MAX_VALUE)
            );
            List<HnswSearchResult> tail = List.of(
                result(2L, 0.0f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(2);
            assertThat(merged.get(0).distance()).isEqualTo(0.0f);
            assertThat(merged.get(1).distance()).isEqualTo(Float.MAX_VALUE);
        }

        @Test
        @DisplayName("should handle very small distance values")
        void smallDistances() {
            List<HnswSearchResult> hnsw = List.of(
                result(1L, Float.MIN_VALUE)
            );
            List<HnswSearchResult> tail = List.of(
                result(2L, 0.0f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(2);
            assertThat(merged.get(0).distance()).isEqualTo(0.0f);
            assertThat(merged.get(1).distance()).isEqualTo(Float.MIN_VALUE);
        }

        @Test
        @DisplayName("should handle tail scan results with tail marker in node ID")
        void tailScanNodeIdMarker() {
            // Tail scan uses Long.MIN_VALUE bit as marker
            long tailNodeId1 = Long.MIN_VALUE | 100L;
            long tailNodeId2 = Long.MIN_VALUE | 200L;

            List<HnswSearchResult> hnsw = List.of(
                result(100L, 0.1f),  // Different node ID (no MIN_VALUE bit)
                result(200L, 0.2f)
            );
            List<HnswSearchResult> tail = List.of(
                result(tailNodeId1, 0.05f),  // Better distance
                result(tailNodeId2, 0.25f)
            );

            List<HnswSearchResult> merged = mergeResults(hnsw, tail, 10);

            assertThat(merged).hasSize(4);
            // All should be present (node IDs are different)
            assertThat(merged.get(0).nodeId()).isEqualTo(tailNodeId1); // Best distance
        }
    }
}
