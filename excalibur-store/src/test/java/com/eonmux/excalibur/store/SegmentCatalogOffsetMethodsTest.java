package com.eonmux.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for catalog-level offset methods in {@link SegmentCatalog}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Catalog convenience methods for lastIntegratedOffset</li>
 *   <li>Catalog convenience methods for writeLogHead</li>
 *   <li>Tail scan size calculation</li>
 *   <li>Domain correctness constraints</li>
 * </ul>
 */
@DisplayName("SegmentCatalog Offset Methods Tests")
class SegmentCatalogOffsetMethodsTest {

    private SegmentCatalog catalog;
    private long segmentId1;
    private long segmentId2;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        segmentId1 = 1L;
        segmentId2 = 2L;
        catalog.add(segmentId1, Path.of("/test/segment1"));
        catalog.add(segmentId2, Path.of("/test/segment2"));
    }

    // ==================== getLastIntegratedOffset Tests ====================

    @Nested
    @DisplayName("getLastIntegratedOffset")
    class GetLastIntegratedOffsetTests {

        @Test
        @DisplayName("should return 0 for newly created segment")
        void shouldReturnZeroForNewSegment() {
            assertThat(catalog.getLastIntegratedOffset(segmentId1)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return 0 for non-existent segment")
        void shouldReturnZeroForNonExistentSegment() {
            assertThat(catalog.getLastIntegratedOffset(999L)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return correct offset after update")
        void shouldReturnCorrectOffsetAfterUpdate() {
            catalog.setLastIntegratedOffset(segmentId1, 1000L);
            assertThat(catalog.getLastIntegratedOffset(segmentId1)).isEqualTo(1000L);
        }
    }

    // ==================== setLastIntegratedOffset Tests ====================

    @Nested
    @DisplayName("setLastIntegratedOffset")
    class SetLastIntegratedOffsetTests {

        @Test
        @DisplayName("should return true when offset is increased")
        void shouldReturnTrueWhenOffsetIncreased() {
            boolean result = catalog.setLastIntegratedOffset(segmentId1, 1000L);
            assertThat(result).isTrue();
            assertThat(catalog.getLastIntegratedOffset(segmentId1)).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return false when offset is not increased")
        void shouldReturnFalseWhenOffsetNotIncreased() {
            catalog.setLastIntegratedOffset(segmentId1, 1000L);
            boolean result = catalog.setLastIntegratedOffset(segmentId1, 500L);
            assertThat(result).isFalse();
            assertThat(catalog.getLastIntegratedOffset(segmentId1)).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return false for non-existent segment")
        void shouldReturnFalseForNonExistentSegment() {
            boolean result = catalog.setLastIntegratedOffset(999L, 1000L);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should throw for negative offset")
        void shouldThrowForNegativeOffset() {
            assertThatThrownBy(() -> catalog.setLastIntegratedOffset(segmentId1, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    // ==================== getWriteLogHead Tests ====================

    @Nested
    @DisplayName("getWriteLogHead")
    class GetWriteLogHeadTests {

        @Test
        @DisplayName("should return 0 for newly created segment")
        void shouldReturnZeroForNewSegment() {
            assertThat(catalog.getWriteLogHead(segmentId1)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return 0 for non-existent segment")
        void shouldReturnZeroForNonExistentSegment() {
            assertThat(catalog.getWriteLogHead(999L)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return correct offset after update")
        void shouldReturnCorrectOffsetAfterUpdate() {
            catalog.setWriteLogHead(segmentId1, 5000L);
            assertThat(catalog.getWriteLogHead(segmentId1)).isEqualTo(5000L);
        }
    }

    // ==================== setWriteLogHead Tests ====================

    @Nested
    @DisplayName("setWriteLogHead")
    class SetWriteLogHeadTests {

        @Test
        @DisplayName("should return true when offset is increased")
        void shouldReturnTrueWhenOffsetIncreased() {
            boolean result = catalog.setWriteLogHead(segmentId1, 5000L);
            assertThat(result).isTrue();
            assertThat(catalog.getWriteLogHead(segmentId1)).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should return false when offset is not increased")
        void shouldReturnFalseWhenOffsetNotIncreased() {
            catalog.setWriteLogHead(segmentId1, 5000L);
            boolean result = catalog.setWriteLogHead(segmentId1, 3000L);
            assertThat(result).isFalse();
            assertThat(catalog.getWriteLogHead(segmentId1)).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should return false for non-existent segment")
        void shouldReturnFalseForNonExistentSegment() {
            boolean result = catalog.setWriteLogHead(999L, 5000L);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should throw for negative offset")
        void shouldThrowForNegativeOffset() {
            assertThatThrownBy(() -> catalog.setWriteLogHead(segmentId1, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    // ==================== getTailScanSize Tests ====================

    @Nested
    @DisplayName("getTailScanSize")
    class GetTailScanSizeTests {

        @Test
        @DisplayName("should return 0 when writeLogHead equals lastIntegratedOffset")
        void shouldReturnZeroWhenEqual() {
            catalog.setWriteLogHead(segmentId1, 1000L);
            catalog.setLastIntegratedOffset(segmentId1, 1000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return correct difference when writeLogHead > lastIntegratedOffset")
        void shouldReturnCorrectDifference() {
            catalog.setWriteLogHead(segmentId1, 5000L);
            catalog.setLastIntegratedOffset(segmentId1, 2000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(3000L);
        }

        @Test
        @DisplayName("should return 0 when lastIntegratedOffset > writeLogHead (edge case)")
        void shouldReturnZeroWhenIntegratedExceedsHead() {
            // This shouldn't happen in practice, but we handle it gracefully
            catalog.setWriteLogHead(segmentId1, 2000L);
            catalog.setLastIntegratedOffset(segmentId1, 5000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return 0 for non-existent segment")
        void shouldReturnZeroForNonExistentSegment() {
            assertThat(catalog.getTailScanSize(999L)).isEqualTo(0L);
        }
    }

    // ==================== hasTailEntries Tests ====================

    @Nested
    @DisplayName("hasTailEntries")
    class HasTailEntriesTests {

        @Test
        @DisplayName("should return false when no tail entries")
        void shouldReturnFalseWhenNoTail() {
            catalog.setWriteLogHead(segmentId1, 1000L);
            catalog.setLastIntegratedOffset(segmentId1, 1000L);
            assertThat(catalog.hasTailEntries(segmentId1)).isFalse();
        }

        @Test
        @DisplayName("should return true when tail entries exist")
        void shouldReturnTrueWhenTailExists() {
            catalog.setWriteLogHead(segmentId1, 5000L);
            catalog.setLastIntegratedOffset(segmentId1, 2000L);
            assertThat(catalog.hasTailEntries(segmentId1)).isTrue();
        }

        @Test
        @DisplayName("should return false for non-existent segment")
        void shouldReturnFalseForNonExistentSegment() {
            assertThat(catalog.hasTailEntries(999L)).isFalse();
        }
    }

    // ==================== Live Indexing Scenario Tests ====================

    @Nested
    @DisplayName("Live Indexing Scenario")
    class LiveIndexingScenarioTests {

        @Test
        @DisplayName("should track tail scan correctly during ingestion")
        void shouldTrackTailScanDuringIngestion() {
            // Initial state: nothing ingested
            assertThat(catalog.getWriteLogHead(segmentId1)).isEqualTo(0L);
            assertThat(catalog.getLastIntegratedOffset(segmentId1)).isEqualTo(0L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(0L);

            // Write entries to log (writeLogHead advances)
            catalog.setWriteLogHead(segmentId1, 10000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(10000L);
            assertThat(catalog.hasTailEntries(segmentId1)).isTrue();

            // HNSW integration catches up (lastIntegratedOffset advances)
            catalog.setLastIntegratedOffset(segmentId1, 5000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(5000L);
            assertThat(catalog.hasTailEntries(segmentId1)).isTrue();

            // More writes (writeLogHead advances further)
            catalog.setWriteLogHead(segmentId1, 15000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(10000L);

            // HNSW catches up completely
            catalog.setLastIntegratedOffset(segmentId1, 15000L);
            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(0L);
            assertThat(catalog.hasTailEntries(segmentId1)).isFalse();
        }

        @Test
        @DisplayName("should handle multiple segments independently")
        void shouldHandleMultipleSegmentsIndependently() {
            // Segment 1: fully integrated
            catalog.setWriteLogHead(segmentId1, 10000L);
            catalog.setLastIntegratedOffset(segmentId1, 10000L);

            // Segment 2: has tail
            catalog.setWriteLogHead(segmentId2, 8000L);
            catalog.setLastIntegratedOffset(segmentId2, 3000L);

            assertThat(catalog.getTailScanSize(segmentId1)).isEqualTo(0L);
            assertThat(catalog.hasTailEntries(segmentId1)).isFalse();

            assertThat(catalog.getTailScanSize(segmentId2)).isEqualTo(5000L);
            assertThat(catalog.hasTailEntries(segmentId2)).isTrue();
        }

        @Test
        @DisplayName("should enforce domain constraint: offsets are non-negative")
        void shouldEnforceNonNegativeOffsets() {
            assertThatThrownBy(() -> catalog.setLastIntegratedOffset(segmentId1, -1L))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> catalog.setWriteLogHead(segmentId1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== SegmentMetadata WriteLogHead Tests ====================

    @Nested
    @DisplayName("SegmentMetadata WriteLogHead Methods")
    class SegmentMetadataWriteLogHeadTests {

        private SegmentCatalog.SegmentMetadata metadata;

        @BeforeEach
        void setUpMetadata() {
            metadata = catalog.get(segmentId1);
        }

        @Test
        @DisplayName("should initialize writeLogHead to 0")
        void shouldInitializeToZero() {
            assertThat(metadata.getWriteLogHead()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should set writeLogHead directly")
        void shouldSetDirectly() {
            metadata.setWriteLogHead(5000L);
            assertThat(metadata.getWriteLogHead()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should update writeLogHead with CAS")
        void shouldUpdateWithCAS() {
            boolean updated = metadata.compareAndSetWriteLogHead(1000L);
            assertThat(updated).isTrue();
            assertThat(metadata.getWriteLogHead()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should not decrease writeLogHead with CAS")
        void shouldNotDecreaseWithCAS() {
            metadata.setWriteLogHead(1000L);
            boolean updated = metadata.compareAndSetWriteLogHead(500L);
            assertThat(updated).isFalse();
            assertThat(metadata.getWriteLogHead()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should update writeLogHead to max")
        void shouldUpdateToMax() {
            metadata.setWriteLogHead(1000L);
            metadata.updateWriteLogHeadToMax(2000L);
            assertThat(metadata.getWriteLogHead()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("should not change when current is greater")
        void shouldNotChangeWhenCurrentGreater() {
            metadata.setWriteLogHead(2000L);
            metadata.updateWriteLogHeadToMax(1000L);
            assertThat(metadata.getWriteLogHead()).isEqualTo(2000L);
        }
    }

    // ==================== SegmentMetadata LastIntegratedOffset Domain Constraints ====================

    @Nested
    @DisplayName("SegmentMetadata lastIntegratedOffset Domain Constraints")
    class SegmentMetadataLastIntegratedOffsetDomainTests {

        private SegmentCatalog.SegmentMetadata metadata;

        @BeforeEach
        void setUpMetadata() {
            metadata = catalog.get(segmentId1);
        }

        @Test
        @DisplayName("should initialize lastIntegratedOffset to 0")
        void shouldInitializeToZero() {
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should throw for negative offset in setLastIntegratedOffset")
        void shouldThrowForNegativeOffset() {
            assertThatThrownBy(() -> metadata.setLastIntegratedOffset(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("should accept zero offset in setLastIntegratedOffset")
        void shouldAcceptZeroOffset() {
            metadata.setLastIntegratedOffset(0L);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should accept positive offset in setLastIntegratedOffset")
        void shouldAcceptPositiveOffset() {
            metadata.setLastIntegratedOffset(1000L);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return false for negative offset in compareAndSetLastIntegratedOffset")
        void shouldReturnFalseForNegativeInCAS() {
            boolean result = metadata.compareAndSetLastIntegratedOffset(-1L);
            assertThat(result).isFalse();
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should not update for negative offset in updateOffsetToMax")
        void shouldNotUpdateForNegativeInUpdateToMax() {
            metadata.setLastIntegratedOffset(1000L);
            metadata.updateOffsetToMax(-1L);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should enforce monotonic increase in compareAndSetLastIntegratedOffset")
        void shouldEnforceMonotonicInCAS() {
            metadata.setLastIntegratedOffset(1000L);

            // Attempt to decrease - should fail
            boolean result = metadata.compareAndSetLastIntegratedOffset(500L);
            assertThat(result).isFalse();
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);

            // Attempt to stay same - should fail
            result = metadata.compareAndSetLastIntegratedOffset(1000L);
            assertThat(result).isFalse();
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);

            // Increase - should succeed
            result = metadata.compareAndSetLastIntegratedOffset(1500L);
            assertThat(result).isTrue();
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1500L);
        }

        @Test
        @DisplayName("should enforce monotonic increase in updateOffsetToMax")
        void shouldEnforceMonotonicInUpdateToMax() {
            metadata.setLastIntegratedOffset(1000L);

            // Attempt to decrease - should be ignored
            metadata.updateOffsetToMax(500L);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);

            // Increase - should succeed
            metadata.updateOffsetToMax(2000L);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("should handle Long.MAX_VALUE correctly")
        void shouldHandleMaxValue() {
            metadata.setLastIntegratedOffset(Long.MAX_VALUE);
            assertThat(metadata.getLastIntegratedOffset()).isEqualTo(Long.MAX_VALUE);

            // Cannot increase beyond MAX_VALUE
            boolean result = metadata.compareAndSetLastIntegratedOffset(Long.MAX_VALUE);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should maintain non-negative guarantee under concurrent updates")
        void shouldMaintainNonNegativeUnderConcurrency() throws InterruptedException {
            final int numThreads = 10;
            final int iterations = 1000;
            final Thread[] threads = new Thread[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        long offset = (long) threadId * iterations + j + 1;
                        metadata.compareAndSetLastIntegratedOffset(offset);
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }

            // Offset should always be >= 0 and monotonically increased from 0
            assertThat(metadata.getLastIntegratedOffset()).isGreaterThanOrEqualTo(0L);
        }
    }
}
