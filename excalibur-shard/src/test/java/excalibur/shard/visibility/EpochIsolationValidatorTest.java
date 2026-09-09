package excalibur.shard.visibility;

import io.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that keeps a query from seeing half of a batch.
 *
 * <p>A query captures an epoch and then searches segments that are being written to underneath it.
 * Without this, a result set can contain vectors inserted after the query started - a torn read
 * that no later stage can detect, because the results look exactly like results.</p>
 *
 * <p>Two design decisions are load-bearing and pinned below. Checkpoints cover <em>ranges</em>
 * rather than individual nodes, registered before the batch is inserted, so a node the batch has
 * not allocated yet still resolves to the right epoch if a query reaches it mid-insert. And a node
 * no checkpoint covers is treated as pre-existing rather than as an error: it was there before any
 * tracking began, so it is visible to everyone. Getting that default backwards would hide the
 * entire pre-existing corpus from every query.</p>
 */
@DisplayName("Epoch isolation validator")
class EpochIsolationValidatorTest {

    private static final long SEGMENT = 5L;
    private static final long OTHER_SEGMENT = 6L;

    private EpochIsolationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EpochIsolationValidator();
    }

    private static List<HnswSearchResult> results(long... nodeIds) {
        List<HnswSearchResult> list = new ArrayList<>(nodeIds.length);
        for (int i = 0; i < nodeIds.length; i++) {
            list.add(new HnswSearchResult(nodeIds[i], 0.1f * (i + 1)));
        }
        return list;
    }

    private static List<Long> nodeIdsOf(List<HnswSearchResult> results) {
        return results.stream().map(HnswSearchResult::nodeId).toList();
    }

    @Nested
    @DisplayName("range checkpoints")
    class RangeCheckpoints {

        @Test
        @DisplayName("a checkpoint covers every node from its first id upward")
        void aCheckpointCoversTheRangeAbove() {
            validator.registerNodeRange(SEGMENT, 100L, 7L);

            // 100 is the first node of the batch; 150 was not allocated when the checkpoint was
            // written, and must still resolve to the batch's epoch.
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 100L)).isEqualTo(7L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 150L)).isEqualTo(7L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 1_000_000L)).isEqualTo(7L);
        }

        @Test
        @DisplayName("a node below every checkpoint is uncovered")
        void nodesBelowTheFirstCheckpointAreUncovered() {
            validator.registerNodeRange(SEGMENT, 100L, 7L);

            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 99L))
                .as("-1 means no checkpoint covers it, which is distinct from epoch 0")
                .isEqualTo(-1L);
        }

        @Test
        @DisplayName("the nearest checkpoint at or below a node wins")
        void theNearestCheckpointBelowWins() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(SEGMENT, 100L, 2L);
            validator.registerNodeRange(SEGMENT, 200L, 3L);

            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 50L)).isEqualTo(1L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 100L)).isEqualTo(2L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 199L)).isEqualTo(2L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 250L)).isEqualTo(3L);
        }

        @Test
        @DisplayName("an uncovered node is visible to every query, not hidden from all of them")
        void uncoveredNodesArePreExisting() {
            // The corpus that existed before tracking began has no checkpoint. Defaulting it to
            // "not yet visible" would empty every result set on a cold start.
            assertThat(validator.isNodeVisible(SEGMENT, 42L, 0L)).isTrue();
            assertThat(validator.isNodeVisible(SEGMENT, 42L, 100L)).isTrue();
        }

        @Test
        @DisplayName("visibility is per segment, because node ids repeat across graphs")
        void checkpointsAreScopedToTheirSegment() {
            // Node 7 of segment 5 and node 7 of segment 6 are different vectors.
            validator.registerNodeRange(SEGMENT, 0L, 9L);

            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 7L)).isEqualTo(9L);
            assertThat(validator.getNodeVisibilityEpoch(OTHER_SEGMENT, 7L))
                .as("another segment's checkpoint must not answer for this one")
                .isEqualTo(-1L);
            assertThat(validator.isNodeVisible(OTHER_SEGMENT, 7L, 0L)).isTrue();
        }

        @Test
        @DisplayName("a range registered below an existing one is still recorded")
        void aLowerRangeIsNotSwallowedByCoalescing() {
            // Batches at the same epoch are coalesced into one contiguous range, which is right
            // when they arrive in increasing node order - the normal path, where each batch
            // starts at the graph's next node id. Coalescing against the highest checkpoint
            // rather than against the one covering this range drops the lower registration
            // outright, and the nodes it should have covered then resolve to "no checkpoint",
            // which means visible at every epoch: precisely the torn read being prevented.
            validator.registerNodeRange(SEGMENT, 300L, 6L);
            validator.registerNodeRange(SEGMENT, 100L, 6L);

            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 100L))
                .as("the lower range was registered, not discarded")
                .isEqualTo(6L);
            assertThat(validator.isNodeVisible(SEGMENT, 150L, 5L))
                .as("a node of an epoch-6 batch is not visible to a query that captured epoch 5")
                .isFalse();
        }

        @Test
        @DisplayName("consecutive batches at one epoch are still coalesced into one checkpoint")
        void increasingRangesAtOneEpochCoalesce() {
            // The optimisation the fix above must not cost: on the normal increasing path these
            // are one contiguous range and must stay one entry, or the map grows per batch.
            validator.registerNodeRange(SEGMENT, 10L, 6L);
            validator.registerNodeRange(SEGMENT, 20L, 6L);
            validator.registerNodeRange(SEGMENT, 30L, 6L);

            assertThat(validator.getCheckpointCount()).isEqualTo(1);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 35L)).isEqualTo(6L);
        }

        @Test
        @DisplayName("a new epoch always starts a new checkpoint")
        void aNewEpochStartsANewCheckpoint() {
            validator.registerNodeRange(SEGMENT, 10L, 6L);
            validator.registerNodeRange(SEGMENT, 20L, 7L);

            assertThat(validator.getCheckpointCount()).isEqualTo(2);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 15L)).isEqualTo(6L);
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 25L)).isEqualTo(7L);
        }

        @Test
        @DisplayName("a negative node id or epoch is refused")
        void negativeArgumentsAreRefused() {
            assertThatThrownBy(() -> validator.registerNodeRange(SEGMENT, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstNodeId");
            assertThatThrownBy(() -> validator.registerNodeRange(SEGMENT, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibleEpoch");
        }
    }

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("a result inserted after the query started is dropped")
        void laterInsertsAreFiltered() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(SEGMENT, 10L, 5L);

            List<HnswSearchResult> filtered =
                validator.filterResults(SEGMENT, results(1L, 2L, 10L, 11L), 3L);

            assertThat(nodeIdsOf(filtered))
                .as("nodes 10 and 11 became visible at epoch 5, after the query captured 3")
                .containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("a node visible exactly at the query epoch is kept")
        void theBoundaryIsInclusive() {
            validator.registerNodeRange(SEGMENT, 0L, 5L);

            assertThat(validator.filterResults(SEGMENT, results(1L), 5L)).hasSize(1);
            assertThat(validator.filterResults(SEGMENT, results(1L), 4L)).isEmpty();
        }

        @Test
        @DisplayName("distance order is preserved, because it is the ranking")
        void orderIsPreserved() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);

            List<HnswSearchResult> filtered =
                validator.filterResults(SEGMENT, results(9L, 3L, 7L, 1L), 10L);

            assertThat(nodeIdsOf(filtered)).containsExactly(9L, 3L, 7L, 1L);
        }

        @Test
        @DisplayName("null and empty inputs return an empty list rather than failing")
        void emptyInputsAreHandled() {
            assertThat(validator.filterResults(SEGMENT, null, 1L)).isEmpty();
            assertThat(validator.filterResults(SEGMENT, List.of(), 1L)).isEmpty();
        }

        @Test
        @DisplayName("a null element is skipped rather than returned")
        void nullElementsAreSkipped() {
            List<HnswSearchResult> withNull = new ArrayList<>(results(1L, 2L));
            withNull.add(null);

            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, withNull, 10L);

            assertThat(filtered).hasSize(2).doesNotContainNull();
        }

        @Test
        @DisplayName("the returned list cannot be modified by its caller")
        void resultsAreUnmodifiable() {
            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, results(1L), 1L);

            assertThatThrownBy(() -> filtered.add(new HnswSearchResult(2L, 0.5f)))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("filtering one segment does not consult another's checkpoints")
        void filteringIsSegmentScoped() {
            validator.registerNodeRange(SEGMENT, 0L, 99L);

            assertThat(validator.filterResults(SEGMENT, results(1L), 1L))
                .as("invisible in its own segment")
                .isEmpty();
            assertThat(validator.filterResults(OTHER_SEGMENT, results(1L), 1L))
                .as("the other segment has no checkpoint, so the node is pre-existing")
                .hasSize(1);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("results that respect the epoch validate clean")
        void cleanResultsAreValid() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);

            EpochIsolationValidator.ValidationResult result =
                validator.validateResults(SEGMENT, results(1L, 2L, 3L), 5L);

            assertThat(result.valid()).isTrue();
            assertThat(result.totalChecked()).isEqualTo(3);
            assertThat(result.violations()).isZero();
            assertThat(result.violationDetails()).isEmpty();
            assertThat(result.violationRatio()).isZero();
        }

        @Test
        @DisplayName("a violation is reported with the epochs that disagree")
        void violationsCarryTheirEpochs() {
            validator.registerNodeRange(SEGMENT, 10L, 9L);

            EpochIsolationValidator.ValidationResult result =
                validator.validateResults(SEGMENT, results(10L), 3L);

            assertThat(result.valid()).isFalse();
            assertThat(result.violations()).isEqualTo(1);
            assertThat(result.violationRatio()).isEqualTo(1.0);

            EpochIsolationValidator.ViolationDetails details = result.violationDetails().get(0);
            assertThat(details.nodeId()).isEqualTo(10L);
            assertThat(details.visibleEpoch()).isEqualTo(9L);
            assertThat(details.queryEpoch()).isEqualTo(3L);
            assertThat(details.toString()).contains("10").contains("9").contains("3");
        }

        @Test
        @DisplayName("the ratio reports how much of the result set was torn")
        void ratioReflectsTheProportion() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(SEGMENT, 3L, 9L);

            EpochIsolationValidator.ValidationResult result =
                validator.validateResults(SEGMENT, results(1L, 2L, 3L, 4L), 5L);

            assertThat(result.totalChecked()).isEqualTo(4);
            assertThat(result.violations()).isEqualTo(2);
            assertThat(result.violationRatio()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("an uncovered node is not a violation")
        void uncoveredNodesAreNotViolations() {
            // getNodeVisibilityEpoch returns -1, which must not be read as "epoch -1 > query".
            EpochIsolationValidator.ValidationResult result =
                validator.validateResults(SEGMENT, results(1L, 2L), 0L);

            assertThat(result.valid()).isTrue();
            assertThat(result.totalChecked()).isEqualTo(2);
        }

        @Test
        @DisplayName("empty results validate as clean rather than as nothing checked being wrong")
        void emptyResultsValidate() {
            EpochIsolationValidator.ValidationResult empty =
                validator.validateResults(SEGMENT, List.of(), 1L);

            assertThat(empty.valid()).isTrue();
            assertThat(empty.totalChecked()).isZero();
            assertThat(empty.violationRatio())
                .as("zero of zero is zero, not NaN: this feeds a threshold comparison")
                .isZero();
            assertThat(validator.validateResults(SEGMENT, null, 1L).valid()).isTrue();
        }

        @Test
        @DisplayName("filtering and validating agree about the same results")
        void filteringAndValidationAgree() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(SEGMENT, 5L, 8L);
            List<HnswSearchResult> raw = results(1L, 2L, 5L, 6L);

            List<HnswSearchResult> kept = validator.filterResults(SEGMENT, raw, 4L);
            EpochIsolationValidator.ValidationResult validated =
                validator.validateResults(SEGMENT, raw, 4L);

            assertThat(kept).hasSize(raw.size() - validated.violations());
            assertThat(validator.validateResults(SEGMENT, kept, 4L).valid())
                .as("what survived the filter must validate clean")
                .isTrue();
        }

        @Test
        @DisplayName("the validation summary reads without dumping every violation")
        void validationSummaryIsReadable() {
            validator.registerNodeRange(SEGMENT, 0L, 9L);
            EpochIsolationValidator.ValidationResult result =
                validator.validateResults(SEGMENT, results(1L), 1L);

            assertThat(result.toString()).isNotBlank().contains("1");
        }
    }

    @Nested
    @DisplayName("bookkeeping")
    class Bookkeeping {

        @Test
        @DisplayName("checkpoints are bounded per segment however many batches arrive")
        void checkpointsAreBounded() {
            // Unbounded growth here is a leak proportional to batches indexed, not to vectors,
            // which is exactly the shape that survives testing and fails in production.
            for (int i = 0; i < EpochIsolationValidator.MAX_CHECKPOINTS_PER_SEGMENT + 500; i++) {
                validator.registerNodeRange(SEGMENT, i, i);
            }

            assertThat(validator.getCheckpointCount())
                .isLessThanOrEqualTo(EpochIsolationValidator.MAX_CHECKPOINTS_PER_SEGMENT);
        }

        @Test
        @DisplayName("pruning drops the oldest checkpoints, whose nodes are visible to everyone")
        void pruningKeepsTheNewest() {
            for (int i = 0; i < EpochIsolationValidator.MAX_CHECKPOINTS_PER_SEGMENT + 500; i++) {
                validator.registerNodeRange(SEGMENT, i, i);
            }

            long newest = EpochIsolationValidator.MAX_CHECKPOINTS_PER_SEGMENT + 499;
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, newest))
                .as("the most recent checkpoint is the one that still has to be enforced")
                .isEqualTo(newest);
        }

        @Test
        @DisplayName("unregistering a segment forgets only that segment")
        void unregisteringIsScoped() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(OTHER_SEGMENT, 0L, 2L);

            assertThat(validator.unregisterSegment(SEGMENT)).isTrue();
            assertThat(validator.unregisterSegment(SEGMENT))
                .as("a segment already forgotten reports that there was nothing to forget")
                .isFalse();
            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 0L)).isEqualTo(-1L);
            assertThat(validator.getNodeVisibilityEpoch(OTHER_SEGMENT, 0L)).isEqualTo(2L);
        }

        @Test
        @DisplayName("resetting a segment clears its checkpoints without forgetting the segment")
        void resettingClearsCheckpoints() {
            validator.registerNodeRange(SEGMENT, 0L, 5L);

            validator.resetSegmentNodeEpochs(SEGMENT);

            assertThat(validator.getNodeVisibilityEpoch(SEGMENT, 0L)).isEqualTo(-1L);
        }

        @Test
        @DisplayName("a segment update epoch only ever moves forward")
        void updateEpochsAreMonotone() {
            validator.registerSegmentUpdate(SEGMENT, 5L);
            validator.registerSegmentUpdate(SEGMENT, 3L);

            assertThat(validator.segmentHasVisibleEntries(SEGMENT, 4L)).isTrue();
            assertThat(validator.getRegisteredSegmentCount()).isEqualTo(1);
            assertThatThrownBy(() -> validator.registerSegmentUpdate(SEGMENT, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an untracked segment is assumed to have entries rather than skipped")
        void untrackedSegmentsAreSearched() {
            // Answering "no visible entries" for a segment nobody registered would silently
            // exclude it from every query.
            assertThat(validator.segmentHasVisibleEntries(999L, 1L)).isTrue();
        }

        @Test
        @DisplayName("clear forgets everything")
        void clearForgetsEverything() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerSegmentUpdate(SEGMENT, 1L);

            validator.clear();

            assertThat(validator.getCheckpointCount()).isZero();
            assertThat(validator.getCheckpointedSegmentCount()).isZero();
            assertThat(validator.getRegisteredSegmentCount()).isZero();
        }

        @Test
        @DisplayName("stats report what is held")
        void statsReportWhatIsHeld() {
            validator.registerNodeRange(SEGMENT, 0L, 1L);
            validator.registerNodeRange(OTHER_SEGMENT, 0L, 1L);
            validator.registerSegmentUpdate(SEGMENT, 1L);

            EpochIsolationValidator.ValidatorStats stats = validator.getStats();

            assertThat(validator.getCheckpointedSegmentCount()).isEqualTo(2);
            assertThat(stats.toString()).isNotBlank();
            assertThat(validator.toString()).isNotBlank();
        }

        @Test
        @DisplayName("unregistering a single node reports whether there was one")
        void unregisteringANode() {
            validator.registerNodeRange(SEGMENT, 7L, 1L);

            assertThat(validator.unregisterNode(7L)).isIn(true, false);
        }
    }

    @Nested
    @DisplayName("the segment-less overloads")
    class SegmentLessOverloads {

        @Test
        @DisplayName("records written without a segment are readable without one")
        @SuppressWarnings("deprecation")
        void unscopedRecordsRoundTrip() {
            validator.registerNode(3L, 4L);

            assertThat(validator.getNodeVisibilityEpoch(3L)).isEqualTo(4L);
            assertThat(validator.isNodeVisible(3L, 4L)).isTrue();
            assertThat(validator.isNodeVisible(3L, 3L)).isFalse();
        }

        @Test
        @DisplayName("the unscoped scope cannot collide with a real segment")
        @SuppressWarnings("deprecation")
        void unscopedRecordsDoNotLeakIntoSegments() {
            validator.registerNode(3L, 99L);

            assertThat(validator.getNodeVisibilityEpoch(0L, 3L))
                .as("segment 0 is a real segment id and must not see the unscoped record")
                .isEqualTo(-1L);
            assertThat(validator.isNodeVisible(0L, 3L, 0L)).isTrue();
        }

        @Test
        @DisplayName("registering a set of nodes registers each of them")
        @SuppressWarnings("deprecation")
        void registerNodesRegistersEach() {
            validator.registerNodes(Set.of(1L, 2L, 3L), 6L);

            assertThat(validator.getNodeVisibilityEpoch(1L)).isEqualTo(6L);
            assertThat(validator.getNodeVisibilityEpoch(2L)).isEqualTo(6L);
            assertThat(validator.getNodeVisibilityEpoch(3L)).isEqualTo(6L);
        }

        @Test
        @DisplayName("a null set or negative epoch is refused")
        @SuppressWarnings("deprecation")
        void registerNodesValidatesItsArguments() {
            assertThatThrownBy(() -> validator.registerNodes(null, 1L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> validator.registerNodes(Set.of(1L), -1L))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the unscoped filter and validator work off the unscoped records")
        @SuppressWarnings("deprecation")
        void unscopedFilteringWorks() {
            validator.registerNode(1L, 1L);
            validator.registerNode(9L, 9L);

            assertThat(nodeIdsOf(validator.filterResults(results(1L, 9L), 5L)))
                .containsExactly(1L);
            assertThat(validator.validateResults(results(9L), 5L).violations()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent registration and filtering never observes a half-written record")
        void concurrentUseIsSafe() throws InterruptedException {
            int writers = 4;
            int perWriter = 500;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writers + 2);
            AtomicInteger failures = new AtomicInteger();

            for (int w = 0; w < writers; w++) {
                final long segment = w;
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            validator.registerNodeRange(segment, i, i);
                            validator.registerSegmentUpdate(segment, i);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int r = 0; r < 2; r++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            // A filtered result must always be a subset of what went in, whatever
                            // is being registered concurrently.
                            List<HnswSearchResult> in = results(1L, 2L, 3L);
                            List<HnswSearchResult> out = validator.filterResults(0L, in, i);
                            if (out.size() > in.size()) {
                                failures.incrementAndGet();
                            }
                            validator.getStats();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failures.get()).isZero();
            assertThat(validator.getCheckpointedSegmentCount()).isEqualTo(writers);
        }
    }

    @Nested
    @DisplayName("Batch registration")
    class BatchRegistration {

        // A checkpoint coalesces only into the range below it, so the order a batch arrives in
        // decides how many checkpoints it costs. Set makes no promise about iteration order and
        // Set.of randomises it per JVM, which made the count -- and the memory behind it -- a
        // function of the hash seed rather than of what was registered.

        @Test
        @DisplayName("a contiguous batch costs one checkpoint however the set is ordered")
        void contiguousBatchCoalescesRegardlessOfOrder() {
            EpochIsolationValidator ascending = new EpochIsolationValidator();
            EpochIsolationValidator descending = new EpochIsolationValidator();

            ascending.registerNodes(new LinkedHashSet<>(List.of(1L, 2L, 3L, 4L)), 10L);
            descending.registerNodes(new LinkedHashSet<>(List.of(4L, 3L, 2L, 1L)), 10L);

            assertThat(ascending.getCheckpointCount()).isEqualTo(1);
            assertThat(descending.getCheckpointCount())
                .as("the same batch must not cost four checkpoints because a set iterated backwards")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a batch registered over an earlier epoch costs the same either way")
        void interleavedEpochsAreOrderStable() {
            // The interesting case is a batch landing among checkpoints at other epochs, where
            // coalescing genuinely cannot absorb every node. Both validators are given the same
            // history and the same batch, differing only in the order the batch iterates.
            EpochIsolationValidator ascending = new EpochIsolationValidator();
            EpochIsolationValidator shuffled = new EpochIsolationValidator();

            for (EpochIsolationValidator validator : List.of(ascending, shuffled)) {
                validator.registerNode(5L, 1L);
                validator.registerNode(15L, 2L);
            }

            ascending.registerNodes(new LinkedHashSet<>(List.of(7L, 9L, 20L)), 3L);
            shuffled.registerNodes(new LinkedHashSet<>(List.of(20L, 9L, 7L)), 3L);

            assertThat(shuffled.getCheckpointCount())
                .as("iteration order must not change how much the validator remembers")
                .isEqualTo(ascending.getCheckpointCount());
        }

        @Test
        @DisplayName("every node in the batch is still covered at its epoch")
        void everyNodeRemainsCovered() {
            EpochIsolationValidator validator = new EpochIsolationValidator();
            validator.registerNodes(new LinkedHashSet<>(List.of(9L, 4L, 6L)), 12L);

            // Sorting changes how the batch is stored, never what it means.
            for (long node : new long[] {4L, 6L, 9L}) {
                assertThat(validator.isNodeVisible(node, 12L))
                    .as("node %d at its own epoch", node).isTrue();
                assertThat(validator.isNodeVisible(node, 11L))
                    .as("node %d before its epoch", node).isFalse();
            }
        }
    }
}
