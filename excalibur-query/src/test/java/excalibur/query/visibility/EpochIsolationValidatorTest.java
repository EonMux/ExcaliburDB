package excalibur.query.visibility;

import excalibur.shard.visibility.EpochIsolationValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EpochIsolationValidator}.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Node registration and visibility</li>
 *   <li>Result filtering based on epoch</li>
 *   <li>Validation of epoch isolation guarantees</li>
 *   <li>Edge cases: empty results, null handling</li>
 *   <li>Thread safety under concurrent access</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class EpochIsolationValidatorTest {

    private EpochIsolationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EpochIsolationValidator();
    }

    @Nested
    @DisplayName("Node Registration")
    class NodeRegistration {

        @Test
        @DisplayName("registers single node with visibility epoch")
        void registersSingleNode() {
            validator.registerNode(100L, 5L);

            assertThat(validator.isNodeVisible(100L, 5L)).isTrue();
            assertThat(validator.isNodeVisible(100L, 6L)).isTrue();
            assertThat(validator.isNodeVisible(100L, 10L)).isTrue();
            assertThat(validator.isNodeVisible(100L, 4L)).isFalse();
        }

        @Test
        @DisplayName("registers a batch of nodes with one visibility epoch")
        void registersMultipleNodes() {
            // A batch is registered by its FIRST node ID: an insertion batch allocates a
            // contiguous ascending block, so one checkpoint covers all of it. Registering
            // through the unordered Set overload cannot express that - the batch's lowest
            // ID is whichever element the set happens to yield first.
            long segmentId = 0L;
            validator.registerNodeRange(segmentId, 1L, 10L);

            for (long nodeId = 1L; nodeId <= 5L; nodeId++) {
                assertThat(validator.isNodeVisible(segmentId, nodeId, 10L)).isTrue();
                assertThat(validator.isNodeVisible(segmentId, nodeId, 11L)).isTrue();
                assertThat(validator.isNodeVisible(segmentId, nodeId, 9L)).isFalse();
            }

            // Node 0 predates the batch, so it is visible at every epoch
            assertThat(validator.isNodeVisible(segmentId, 0L, 9L)).isTrue();
        }

        @Test
        @DisplayName("throws exception for negative visibility epoch")
        void throwsForNegativeEpoch() {
            assertThatThrownBy(() -> validator.registerNode(1L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("throws exception for negative batch visibility epoch")
        void throwsForNegativeBatchEpoch() {
            assertThatThrownBy(() -> validator.registerNodes(Set.of(1L, 2L), -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("throws exception for null node IDs set")
        void throwsForNullNodeIdsSet() {
            assertThatThrownBy(() -> validator.registerNodes(null, 10L))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("updates visibility epoch on re-registration")
        void updatesOnReregistration() {
            validator.registerNode(100L, 5L);
            assertThat(validator.getNodeVisibilityEpoch(100L)).isEqualTo(5L);

            // Re-register with later epoch
            validator.registerNode(100L, 8L);
            assertThat(validator.getNodeVisibilityEpoch(100L)).isEqualTo(8L);
        }

        @Test
        @DisplayName("unregisters node")
        void unregistersNode() {
            validator.registerNode(100L, 5L);
            assertThat(validator.isNodeVisible(100L, 10L)).isTrue();

            boolean removed = validator.unregisterNode(100L);
            assertThat(removed).isTrue();

            // After unregistration, node should be visible (backward compatibility)
            assertThat(validator.isNodeVisible(100L, 10L)).isTrue();
            assertThat(validator.getNodeVisibilityEpoch(100L)).isEqualTo(-1L);
        }

        @Test
        @DisplayName("returns false when unregistering unknown node")
        void returnsFalseForUnknownNode() {
            boolean removed = validator.unregisterNode(999L);
            assertThat(removed).isFalse();
        }
    }

    @Nested
    @DisplayName("Segment Registration")
    class SegmentRegistration {

        @Test
        @DisplayName("registers segment update epoch")
        void registersSegmentUpdate() {
            validator.registerSegmentUpdate(1L, 10L);
            assertThat(validator.segmentHasVisibleEntries(1L, 10L)).isTrue();
        }

        @Test
        @DisplayName("throws for negative segment update epoch")
        void throwsForNegativeSegmentEpoch() {
            assertThatThrownBy(() -> validator.registerSegmentUpdate(1L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("keeps maximum epoch on multiple updates")
        void keepsMaximumEpoch() {
            validator.registerSegmentUpdate(1L, 5L);
            validator.registerSegmentUpdate(1L, 10L);
            validator.registerSegmentUpdate(1L, 3L); // Should not decrease

            // The segment should still be considered as having updates
            assertThat(validator.segmentHasVisibleEntries(1L, 5L)).isTrue();
        }
    }

    @Nested
    @DisplayName("Visibility Queries")
    class VisibilityQueries {

        @Test
        @DisplayName("returns true for unregistered nodes (backward compatibility)")
        void returnsTrueForUnregisteredNodes() {
            // Nodes not registered should be visible for backward compatibility
            assertThat(validator.isNodeVisible(999L, 0L)).isTrue();
            assertThat(validator.isNodeVisible(999L, 1000L)).isTrue();
        }

        @Test
        @DisplayName("returns correct visibility for registered nodes")
        void returnsCorrectVisibility() {
            validator.registerNode(100L, 50L);

            // Before visibility epoch
            assertThat(validator.isNodeVisible(100L, 49L)).isFalse();

            // At visibility epoch
            assertThat(validator.isNodeVisible(100L, 50L)).isTrue();

            // After visibility epoch
            assertThat(validator.isNodeVisible(100L, 51L)).isTrue();
            assertThat(validator.isNodeVisible(100L, 100L)).isTrue();
        }

        @Test
        @DisplayName("returns -1 for unregistered node visibility epoch")
        void returnsNegativeOneForUnregistered() {
            assertThat(validator.getNodeVisibilityEpoch(999L)).isEqualTo(-1L);
        }
    }

    @Nested
    @DisplayName("Result Filtering")
    class ResultFiltering {

        @Test
        @DisplayName("filters empty results")
        void filtersEmptyResults() {
            List<HnswSearchResult> empty = Collections.emptyList();
            List<HnswSearchResult> filtered = validator.filterResults(empty, 10L);

            assertThat(filtered).isEmpty();
        }

        @Test
        @DisplayName("filters null results list")
        void filtersNullResults() {
            List<HnswSearchResult> filtered = validator.filterResults(null, 10L);
            assertThat(filtered).isEmpty();
        }

        @Test
        @DisplayName("filters results based on visibility epoch")
        void filtersBasedOnVisibilityEpoch() {
            // Visibility is tracked as node-ID RANGES, not per node: a checkpoint at node
            // N says "every node from N upward became visible at this epoch", which is how
            // HNSW insertion allocates IDs. So node 4 inherits node 3's epoch, and node 0
            // - below every checkpoint - predates them all.
            validator.registerNode(1L, 5L);   // Nodes 1..1 visible at epoch 5+
            validator.registerNode(2L, 10L);  // Nodes 2..2 visible at epoch 10+
            validator.registerNode(3L, 15L);  // Nodes 3 and up visible at epoch 15+

            List<HnswSearchResult> results = new ArrayList<>();
            results.add(createResult(0L, 0.0f));
            results.add(createResult(1L, 0.1f));
            results.add(createResult(2L, 0.2f));
            results.add(createResult(3L, 0.3f));
            results.add(createResult(4L, 0.4f));

            // Query at epoch 8 sees the pre-existing node 0 and node 1
            List<HnswSearchResult> filtered8 = validator.filterResults(results, 8L);
            assertThat(filtered8.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(0L, 1L);

            // Query at epoch 12 additionally sees node 2
            List<HnswSearchResult> filtered12 = validator.filterResults(results, 12L);
            assertThat(filtered12.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(0L, 1L, 2L);

            // Query at epoch 20 sees everything, including node 4 which the last
            // checkpoint covers
            List<HnswSearchResult> filtered20 = validator.filterResults(results, 20L);
            assertThat(filtered20).hasSize(5);
        }

        @Test
        @DisplayName("preserves original ordering")
        void preservesOrdering() {
            validator.registerNode(1L, 5L);
            validator.registerNode(2L, 5L);
            validator.registerNode(3L, 5L);

            List<HnswSearchResult> results = new ArrayList<>();
            results.add(createResult(1L, 0.1f));
            results.add(createResult(2L, 0.2f));
            results.add(createResult(3L, 0.3f));

            List<HnswSearchResult> filtered = validator.filterResults(results, 10L);

            assertThat(filtered).hasSize(3);
            // Order should be preserved
            assertThat(filtered.get(0).nodeId()).isEqualTo(1L);
            assertThat(filtered.get(1).nodeId()).isEqualTo(2L);
            assertThat(filtered.get(2).nodeId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("handles null elements in results")
        void handlesNullElements() {
            validator.registerNode(1L, 5L);

            List<HnswSearchResult> results = new ArrayList<>();
            results.add(createResult(1L, 0.1f));
            results.add(null);
            results.add(createResult(2L, 0.2f));

            List<HnswSearchResult> filtered = validator.filterResults(results, 10L);

            // Should skip null and include non-null results
            assertThat(filtered).hasSize(2);
        }

        @Test
        @DisplayName("returns immutable list")
        void returnsImmutableList() {
            validator.registerNode(1L, 5L);
            List<HnswSearchResult> results = List.of(createResult(1L, 0.1f));
            List<HnswSearchResult> filtered = validator.filterResults(results, 10L);

            assertThatThrownBy(() -> filtered.add(createResult(2L, 0.2f)))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("validates empty results as valid")
        void validatesEmptyResults() {
            EpochIsolationValidator.ValidationResult result = validator.validateResults(Collections.emptyList(), 10L);

            assertThat(result.valid()).isTrue();
            assertThat(result.totalChecked()).isEqualTo(0);
            assertThat(result.violations()).isEqualTo(0);
        }

        @Test
        @DisplayName("validates null results as valid")
        void validatesNullResults() {
            EpochIsolationValidator.ValidationResult result = validator.validateResults(null, 10L);

            assertThat(result.valid()).isTrue();
            assertThat(result.totalChecked()).isEqualTo(0);
        }

        @Test
        @DisplayName("detects violations")
        void detectsViolations() {
            validator.registerNode(1L, 5L);
            validator.registerNode(2L, 15L); // Violation at epoch 10
            validator.registerNode(3L, 20L); // Violation at epoch 10

            List<HnswSearchResult> results = new ArrayList<>();
            results.add(createResult(1L, 0.1f));
            results.add(createResult(2L, 0.2f));
            results.add(createResult(3L, 0.3f));

            EpochIsolationValidator.ValidationResult validation = validator.validateResults(results, 10L);

            assertThat(validation.valid()).isFalse();
            assertThat(validation.totalChecked()).isEqualTo(3);
            assertThat(validation.violations()).isEqualTo(2);
            assertThat(validation.violationDetails()).hasSize(2);
        }

        @Test
        @DisplayName("provides violation details")
        void providesViolationDetails() {
            validator.registerNode(1L, 20L);

            List<HnswSearchResult> results = List.of(createResult(1L, 0.5f));
            EpochIsolationValidator.ValidationResult validation = validator.validateResults(results, 10L);

            assertThat(validation.valid()).isFalse();
            assertThat(validation.violationDetails()).hasSize(1);

            EpochIsolationValidator.ViolationDetails details = validation.violationDetails().get(0);
            assertThat(details.nodeId()).isEqualTo(1L);
            assertThat(details.visibleEpoch()).isEqualTo(20L);
            assertThat(details.queryEpoch()).isEqualTo(10L);
            assertThat(details.score()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("calculates violation ratio")
        void calculatesViolationRatio() {
            validator.registerNode(1L, 5L);
            validator.registerNode(2L, 15L);
            validator.registerNode(3L, 20L);
            validator.registerNode(4L, 5L);

            List<HnswSearchResult> results = new ArrayList<>();
            results.add(createResult(1L, 0.1f));
            results.add(createResult(2L, 0.2f));
            results.add(createResult(3L, 0.3f));
            results.add(createResult(4L, 0.4f));

            EpochIsolationValidator.ValidationResult validation = validator.validateResults(results, 10L);

            // 2 violations out of 4 = 50%
            assertThat(validation.violationRatio()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("returns zero ratio for empty results")
        void returnsZeroRatioForEmpty() {
            EpochIsolationValidator.ValidationResult validation = validator.validateResults(Collections.emptyList(), 10L);
            assertThat(validation.violationRatio()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("tracks visibility checkpoint count")
        void tracksCheckpointCount() {
            assertThat(validator.getCheckpointCount()).isEqualTo(0);

            validator.registerNode(1L, 5L);
            assertThat(validator.getCheckpointCount()).isEqualTo(1);

            // Nodes registered at one epoch form ONE contiguous range, so they cost a
            // single checkpoint no matter how many nodes they cover. That is what bounds
            // the validator's memory as a graph grows.
            validator.registerNodes(Set.of(2L, 3L, 4L), 10L);
            assertThat(validator.getCheckpointCount()).isEqualTo(2);

            validator.unregisterNode(1L);
            assertThat(validator.getCheckpointCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("tracks registered segment count")
        void tracksSegmentCount() {
            assertThat(validator.getRegisteredSegmentCount()).isEqualTo(0);

            validator.registerSegmentUpdate(1L, 5L);
            assertThat(validator.getRegisteredSegmentCount()).isEqualTo(1);

            validator.registerSegmentUpdate(2L, 10L);
            assertThat(validator.getRegisteredSegmentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("returns stats snapshot")
        void returnsStatsSnapshot() {
            validator.registerNode(1L, 5L);
            validator.registerNode(2L, 10L);
            validator.registerSegmentUpdate(1L, 5L);

            EpochIsolationValidator.ValidatorStats stats = validator.getStats();

            assertThat(stats.checkpoints()).isEqualTo(2);
            assertThat(stats.registeredSegments()).isEqualTo(1);
        }

        @Test
        @DisplayName("clears all registrations")
        void clearsAll() {
            validator.registerNode(1L, 5L);
            validator.registerSegmentUpdate(1L, 5L);

            validator.clear();

            assertThat(validator.getCheckpointCount()).isEqualTo(0);
            assertThat(validator.getRegisteredSegmentCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("handles concurrent node registrations")
        void handlesConcurrentRegistrations() throws Exception {
            int numThreads = 8;
            int nodesPerThread = 100;
            Thread[] threads = new Thread[numThreads];

            // Each thread owns a segment and registers ascending node IDs at ascending
            // epochs, which is exactly how the HNSW integration worker drives this: node
            // IDs are allocated in order within one graph.
            for (int t = 0; t < numThreads; t++) {
                final int segmentId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < nodesPerThread; i++) {
                        validator.registerNodeRange(segmentId, i, i);
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // Distinct epochs never coalesce, so every registration is retained
            assertThat(validator.getCheckpointCount()).isEqualTo(numThreads * nodesPerThread);
            assertThat(validator.getCheckpointedSegmentCount()).isEqualTo(numThreads);

            // Every registration resolves to the epoch it was registered with
            for (int t = 0; t < numThreads; t++) {
                for (int i = 0; i < nodesPerThread; i++) {
                    assertThat(validator.getNodeVisibilityEpoch(t, i)).isEqualTo(i);
                }
            }
        }

        @Test
        @DisplayName("handles concurrent reads and writes")
        void handlesConcurrentReadsWrites() throws Exception {
            int numReaders = 4;
            int numWriters = 2;
            int iterations = 100;
            Thread[] threads = new Thread[numReaders + numWriters];

            // Writers
            for (int t = 0; t < numWriters; t++) {
                final int writerId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < iterations; i++) {
                        validator.registerNode(writerId * 1000L + i, i);
                    }
                });
            }

            // Readers
            for (int t = 0; t < numReaders; t++) {
                final int readerId = t;
                threads[numWriters + t] = new Thread(() -> {
                    for (int i = 0; i < iterations; i++) {
                        validator.isNodeVisible(readerId * 1000L + i, 50L);
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // No exceptions means success
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("filtered results count is never greater than original")
        void filteredCountNeverGreater() {
            validator.registerNode(1L, 5L);
            validator.registerNode(2L, 15L);
            validator.registerNode(3L, 25L);

            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(createResult(i, i * 0.01f));
            }

            for (long epoch = 0; epoch < 30; epoch++) {
                List<HnswSearchResult> filtered = validator.filterResults(results, epoch);
                assertThat(filtered.size())
                    .as("Filtered count at epoch %d should be <= original", epoch)
                    .isLessThanOrEqualTo(results.size());
            }
        }

        @Test
        @DisplayName("visibility epoch is monotonically increasing for same node")
        void visibilityMonotonicallyIncreasing() {
            validator.registerNode(1L, 5L);
            assertThat(validator.isNodeVisible(1L, 5L)).isTrue();

            // Later registration with higher epoch
            validator.registerNode(1L, 10L);

            // Should now require higher epoch
            assertThat(validator.isNodeVisible(1L, 5L)).isFalse();
            assertThat(validator.isNodeVisible(1L, 10L)).isTrue();
        }

        @Test
        @DisplayName("epoch zero is valid")
        void epochZeroIsValid() {
            assertThatCode(() -> validator.registerNode(1L, 0L))
                .doesNotThrowAnyException();

            assertThat(validator.isNodeVisible(1L, 0L)).isTrue();
            assertThat(validator.isNodeVisible(1L, -1L)).isFalse(); // Negative epochs not allowed
        }
    }

    // ==================== Helper Methods ====================

    private HnswSearchResult createResult(long nodeId, float distance) {
        return new HnswSearchResult(nodeId, distance);
    }
}
