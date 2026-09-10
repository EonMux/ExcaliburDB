package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SegmentLifecycleManager class.
 */
@DisplayName("SegmentLifecycleManager Tests")
class SegmentLifecycleManagerTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private SegmentLifecycleManager manager;
    private final AtomicLong segmentIdGenerator = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        manager = new SegmentLifecycleManager(catalog);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    private long createSegment() {
        long segmentId = segmentIdGenerator.incrementAndGet();
        catalog.add(segmentId, tempDir.resolve("segment-" + segmentId));
        return segmentId;
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with default settings")
        void shouldCreateWithDefaultSettings() {
            assertThat(manager.getCheckIntervalMs()).isEqualTo(SegmentLifecycleManager.DEFAULT_CHECK_INTERVAL_MS);
            assertThat(manager.getTombstoneThreshold()).isEqualTo(SegmentLifecycleManager.DEFAULT_TOMBSTONE_THRESHOLD);
            assertThat(manager.getMinEntriesForCompaction()).isEqualTo(SegmentLifecycleManager.DEFAULT_MIN_ENTRIES_FOR_COMPACTION);
            assertThat(manager.getEpochLagThreshold()).isEqualTo(SegmentLifecycleManager.DEFAULT_EPOCH_LAG_THRESHOLD);
            assertThat(manager.getCatalog()).isEqualTo(catalog);
        }

        @Test
        @DisplayName("Should create with custom settings")
        void shouldCreateWithCustomSettings() {
            SegmentLifecycleManager customManager = new SegmentLifecycleManager(
                catalog, 1000, 0.5, 50, 5
            );

            assertThat(customManager.getCheckIntervalMs()).isEqualTo(1000);
            assertThat(customManager.getTombstoneThreshold()).isEqualTo(0.5);
            assertThat(customManager.getMinEntriesForCompaction()).isEqualTo(50);
            assertThat(customManager.getEpochLagThreshold()).isEqualTo(5);

            customManager.close();
        }

        @Test
        @DisplayName("Should reject null catalog")
        void shouldRejectNullCatalog() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Catalog cannot be null");
        }

        @Test
        @DisplayName("Should reject zero check interval")
        void shouldRejectZeroCheckInterval() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, 0, 0.3, 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Check interval must be positive");
        }

        @Test
        @DisplayName("Should reject negative check interval")
        void shouldRejectNegativeCheckInterval() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, -1, 0.3, 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Check interval must be positive");
        }

        @Test
        @DisplayName("Should reject negative tombstone threshold")
        void shouldRejectNegativeTombstoneThreshold() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, 1000, -0.1, 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tombstone threshold must be between 0 and 1");
        }

        @Test
        @DisplayName("Should reject tombstone threshold greater than 1")
        void shouldRejectTombstoneThresholdGreaterThanOne() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, 1000, 1.5, 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tombstone threshold must be between 0 and 1");
        }

        @Test
        @DisplayName("Should reject negative min entries for compaction")
        void shouldRejectNegativeMinEntriesForCompaction() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, 1000, 0.3, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Min entries for compaction cannot be negative");
        }

        @Test
        @DisplayName("Should reject negative epoch lag threshold")
        void shouldRejectNegativeEpochLagThreshold() {
            assertThatThrownBy(() -> new SegmentLifecycleManager(catalog, 1000, 0.3, 100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Epoch lag threshold cannot be negative");
        }
    }

    @Nested
    @DisplayName("Start/Stop Tests")
    class StartStopTests {

        @Test
        @DisplayName("Should not be running initially")
        void shouldNotBeRunningInitially() {
            assertThat(manager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Should start successfully")
        void shouldStartSuccessfully() {
            manager.start();
            assertThat(manager.isRunning()).isTrue();
        }

        @Test
        @DisplayName("Should stop successfully")
        void shouldStopSuccessfully() {
            manager.start();
            manager.stop();
            assertThat(manager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Should handle double start")
        void shouldHandleDoubleStart() {
            manager.start();
            manager.start();
            assertThat(manager.isRunning()).isTrue();
        }

        @Test
        @DisplayName("Should handle double stop")
        void shouldHandleDoubleStop() {
            manager.start();
            manager.stop();
            manager.stop();
            assertThat(manager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Should close successfully")
        void shouldCloseSuccessfully() {
            manager.start();
            manager.close();
            assertThat(manager.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("Callback Tests")
    class CallbackTests {

        @Test
        @DisplayName("Should set compaction callback")
        void shouldSetCompactionCallback() {
            SegmentLifecycleManager.CompactionCallback callback = segmentId -> true;
            manager.setCompactionCallback(callback);
            // No exception expected
        }

        @Test
        @DisplayName("Should set reclamation callback")
        void shouldSetReclamationCallback() {
            SegmentLifecycleManager.ReclamationCallback callback = segmentId -> true;
            manager.setReclamationCallback(callback);
            // No exception expected
        }
    }

    @Nested
    @DisplayName("Lifecycle State Tests")
    class LifecycleStateTests {

        @Test
        @DisplayName("Should return null for non-existent segment")
        void shouldReturnNullForNonExistentSegment() {
            LifecycleState state = manager.getLifecycleState(999L);
            assertThat(state).isNull();
        }

        @Test
        @DisplayName("Should return lifecycle state for existing segment")
        void shouldReturnLifecycleStateForExistingSegment() {
            long segmentId = createSegment();

            LifecycleState state = manager.getLifecycleState(segmentId);
            // add() calls transitionToIndexing(), so state should be INDEXING
            assertThat(state).isEqualTo(LifecycleState.INDEXING);
        }
    }

    @Nested
    @DisplayName("Constants Tests")
    class ConstantsTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            assertThat(SegmentLifecycleManager.DEFAULT_CHECK_INTERVAL_MS).isEqualTo(5000);
            assertThat(SegmentLifecycleManager.DEFAULT_TOMBSTONE_THRESHOLD).isEqualTo(0.3);
            assertThat(SegmentLifecycleManager.DEFAULT_MIN_ENTRIES_FOR_COMPACTION).isEqualTo(100);
            assertThat(SegmentLifecycleManager.DEFAULT_EPOCH_LAG_THRESHOLD).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Reclamation Check Tests")
    class ReclamationCheckTests {

        @Test
        @DisplayName("Should check reclamation candidates with epoch")
        void shouldCheckReclamationCandidatesWithEpoch() {
            // Create a segment - starts in INDEXING state
            long segmentId = createSegment();

            // Transition to ACTIVE state first (required before tombstoning)
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            // Tombstone the segment with epoch
            manager.tombstone(segmentId, 0L);

            // Check for reclamation with current epoch > threshold
            manager.checkForReclamationCandidates(15L);

            // Segment should be purged since 15 - 0 >= 10 (epochLagThreshold)
            assertThat(catalog.get(segmentId)).isNull();
        }

        @Test
        @DisplayName("Should not purge segment below epoch threshold")
        void shouldNotPurgeSegmentBelowEpochThreshold() {
            long segmentId = createSegment();

            // Transition to ACTIVE state first
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            manager.tombstone(segmentId, 5L);

            // Check with current epoch below threshold
            manager.checkForReclamationCandidates(10L);

            // Segment should still exist since 10 - 5 < 10
            assertThat(catalog.get(segmentId)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Tombstone Tests")
    class TombstoneTests {

        @Test
        @DisplayName("Should tombstone segment without epoch")
        void shouldTombstoneSegmentWithoutEpoch() {
            long segmentId = createSegment();

            // Transition to ACTIVE state first (required before tombstoning)
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            manager.tombstone(segmentId);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.TOMBSTONED);
        }

        @Test
        @DisplayName("Should tombstone segment with epoch")
        void shouldTombstoneSegmentWithEpoch() {
            long segmentId = createSegment();

            // Transition to ACTIVE state first (required before tombstoning)
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            manager.tombstone(segmentId, 100L);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.TOMBSTONED);

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            assertThat(metadata.getTombstoneEpoch()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("Purge Tests")
    class PurgeTests {

        @Test
        @DisplayName("Should purge tombstoned segment")
        void shouldPurgeTombstonedSegment() {
            long segmentId = createSegment();

            // Transition to ACTIVE state first
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            manager.tombstone(segmentId);
            manager.purge(segmentId);

            assertThat(catalog.get(segmentId)).isNull();
        }
    }

    @Nested
    @DisplayName("TryAdvance Tests")
    class TryAdvanceTests {

        @Test
        @DisplayName("Should return null for non-existent segment")
        void shouldReturnNullForNonExistentSegment() {
            LifecycleState state = manager.tryAdvance(999L);
            assertThat(state).isNull();
        }

        @Test
        @DisplayName("Should advance from INDEXING to WARMING")
        void shouldAdvanceFromIndexing() {
            long segmentId = createSegment();

            // Segment starts in INDEXING after add()
            LifecycleState newState = manager.tryAdvance(segmentId);

            assertThat(newState).isEqualTo(LifecycleState.WARMING);
        }

        @Test
        @DisplayName("Should advance from WARMING to ACTIVE")
        void shouldAdvanceFromWarming() {
            long segmentId = createSegment();
            manager.tryAdvance(segmentId); // INDEXING -> WARMING

            LifecycleState newState = manager.tryAdvance(segmentId);

            assertThat(newState).isEqualTo(LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("Should not advance from ACTIVE")
        void shouldNotAdvanceFromActive() {
            long segmentId = createSegment();
            manager.tryAdvance(segmentId); // INDEXING -> WARMING
            manager.tryAdvance(segmentId); // WARMING -> ACTIVE

            LifecycleState newState = manager.tryAdvance(segmentId);

            assertThat(newState).isEqualTo(LifecycleState.ACTIVE);
        }
    }

    @Nested
    @DisplayName("Lifecycle Transition Tests")
    class LifecycleTransitionTests {

        @Test
        @DisplayName("Should complete indexing")
        void shouldCompleteIndexing() {
            long segmentId = createSegment();

            manager.completeIndexing(segmentId);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.WARMING);
        }

        @Test
        @DisplayName("Should complete warmup")
        void shouldCompleteWarmup() {
            long segmentId = createSegment();
            manager.completeIndexing(segmentId); // INDEXING -> WARMING

            manager.completeWarmup(segmentId);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("Should begin compaction")
        void shouldBeginCompaction() {
            long segmentId = createSegment();
            manager.completeIndexing(segmentId);
            manager.completeWarmup(segmentId); // -> ACTIVE

            manager.beginCompaction(segmentId);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.COMPACTING);
        }

        @Test
        @DisplayName("Should complete compaction")
        void shouldCompleteCompaction() {
            long segmentId = createSegment();
            manager.completeIndexing(segmentId);
            manager.completeWarmup(segmentId);
            manager.beginCompaction(segmentId);

            manager.completeCompaction(segmentId);

            LifecycleState state = manager.getLifecycleState(segmentId);
            assertThat(state).isEqualTo(LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("Should throw for completeIndexing on non-existent segment")
        void shouldThrowForCompleteIndexingOnNonExistent() {
            assertThatThrownBy(() -> manager.completeIndexing(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment not found");
        }

        @Test
        @DisplayName("Should throw for completeWarmup on non-existent segment")
        void shouldThrowForCompleteWarmupOnNonExistent() {
            assertThatThrownBy(() -> manager.completeWarmup(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment not found");
        }

        @Test
        @DisplayName("Should throw for beginCompaction on non-existent segment")
        void shouldThrowForBeginCompactionOnNonExistent() {
            assertThatThrownBy(() -> manager.beginCompaction(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment not found");
        }
    }

    @Nested
    @DisplayName("Getters Tests")
    class GettersTests {

        @Test
        @DisplayName("Should get catalog")
        void shouldGetCatalog() {
            assertThat(manager.getCatalog()).isSameAs(catalog);
        }

        @Test
        @DisplayName("Should get check interval")
        void shouldGetCheckInterval() {
            assertThat(manager.getCheckIntervalMs()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("Should get tombstone threshold")
        void shouldGetTombstoneThreshold() {
            assertThat(manager.getTombstoneThreshold()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("Should get min entries for compaction")
        void shouldGetMinEntriesForCompaction() {
            assertThat(manager.getMinEntriesForCompaction()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should get epoch lag threshold")
        void shouldGetEpochLagThreshold() {
            assertThat(manager.getEpochLagThreshold()).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("Indexing Completion Tests")
    class IndexingCompletionTests {

        @Test
        @DisplayName("Should advance a segment whose index has caught up")
        void shouldAdvanceCaughtUpSegment() {
            long segmentId = createSegment();
            manager.setIndexingCompletionProbe(id -> true);

            manager.checkForIndexingCompletion();

            assertThat(manager.getLifecycleState(segmentId)).isEqualTo(LifecycleState.WARMING);
        }

        @Test
        @DisplayName("Should leave a segment whose index is still behind")
        void shouldLeaveLaggingSegment() {
            long segmentId = createSegment();
            manager.setIndexingCompletionProbe(id -> false);

            manager.checkForIndexingCompletion();

            assertThat(manager.getLifecycleState(segmentId)).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should leave every segment alone when no probe is installed")
        void shouldLeaveEverySegmentAloneWithoutAProbe() {
            long segmentId = createSegment();

            manager.checkForIndexingCompletion();

            assertThat(manager.getLifecycleState(segmentId)).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should only offer segments that are still indexing to the probe")
        void shouldOnlyOfferIndexingSegments() {
            long indexing = createSegment();
            long active = createSegment();
            catalog.completeIndexing(active);
            catalog.completeWarmup(active);

            List<Long> offered = new CopyOnWriteArrayList<>();
            manager.setIndexingCompletionProbe(id -> {
                offered.add(id);
                return false;
            });

            manager.checkForIndexingCompletion();

            assertThat(offered).containsExactly(indexing);
        }

        @Test
        @DisplayName("Should keep sweeping after a probe throws")
        void shouldKeepSweepingAfterAProbeThrows() {
            // A probe reads live engine state, so it can fail for one segment - a segment
            // retired between the catalog listing and the read - while every other segment is
            // still judgeable. Letting that abort the sweep retires the transition for the rest
            // of the tick, and the same segment is likely to fail again on the next one.
            long first = createSegment();
            long second = createSegment();
            manager.setIndexingCompletionProbe(id -> {
                if (id == first) {
                    throw new IllegalStateException("segment went away");
                }
                return true;
            });

            manager.checkForIndexingCompletion();

            assertThat(manager.getLifecycleState(first)).isEqualTo(LifecycleState.INDEXING);
            assertThat(manager.getLifecycleState(second)).isEqualTo(LifecycleState.WARMING);
        }

        @Test
        @DisplayName("Should be driven by the periodic lifecycle check")
        void shouldBeDrivenByThePeriodicCheck() throws InterruptedException {
            // Nothing in production calls checkForIndexingCompletion directly. If the scheduled
            // sweep does not run it, a live-ingested segment never leaves INDEXING, and
            // compaction, PQ training and scheduled reindexing - which all select on later
            // states - never see a candidate.
            SegmentLifecycleManager periodic = new SegmentLifecycleManager(catalog, 20, 0.3, 100, 10);
            try {
                long segmentId = createSegment();
                periodic.setIndexingCompletionProbe(id -> true);
                periodic.start();

                assertThat(awaitState(periodic, segmentId, LifecycleState.WARMING))
                    .as("segment %d should have been advanced by the periodic check", segmentId)
                    .isTrue();
            } finally {
                periodic.close();
            }
        }

        private boolean awaitState(SegmentLifecycleManager target, long segmentId,
                                   LifecycleState expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (target.getLifecycleState(segmentId) == expected) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }
    }
}
