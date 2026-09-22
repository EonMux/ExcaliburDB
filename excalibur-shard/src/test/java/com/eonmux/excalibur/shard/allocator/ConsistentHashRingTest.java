package com.eonmux.excalibur.shard.allocator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ConsistentHashRing}.
 */
@DisplayName("ConsistentHashRing Tests")
class ConsistentHashRingTest {

    private ConsistentHashRing ring;
    private static final Path VOL1 = Path.of("/data/vol1");
    private static final Path VOL2 = Path.of("/data/vol2");
    private static final Path VOL3 = Path.of("/data/vol3");

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
    }

    // ==================== Constructor Tests ====================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create ring with default virtual nodes")
        void shouldCreateWithDefaultVirtualNodes() {
            ConsistentHashRing defaultRing = new ConsistentHashRing();
            assertThat(defaultRing.getVolumeCount()).isZero();
        }

        @Test
        @DisplayName("should create ring with custom virtual nodes")
        void shouldCreateWithCustomVirtualNodes() {
            ConsistentHashRing customRing = new ConsistentHashRing(100);
            assertThat(customRing.getVolumeCount()).isZero();
        }

        @Test
        @DisplayName("should reject virtual nodes below minimum")
        void shouldRejectBelowMinimum() {
            assertThatThrownBy(() -> new ConsistentHashRing(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Virtual nodes per volume must be between");
        }

        @Test
        @DisplayName("should reject virtual nodes above maximum")
        void shouldRejectAboveMaximum() {
            assertThatThrownBy(() -> new ConsistentHashRing(2000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Virtual nodes per volume must be between");
        }
    }

    // ==================== Add Volume Tests ====================

    @Nested
    @DisplayName("addVolume()")
    class AddVolumeTests {

        @Test
        @DisplayName("should add volume to ring")
        void shouldAddVolume() {
            boolean added = ring.addVolume("vol1", VOL1);
            assertThat(added).isTrue();
            assertThat(ring.getVolumeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should add multiple virtual nodes")
        void shouldAddMultipleVirtualNodes() {
            ring.addVolume("vol1", VOL1);
            // Default is 150 virtual nodes
            assertThat(ring.getRingSize()).isEqualTo(150);
        }

        @Test
        @DisplayName("should return false for duplicate volume")
        void shouldReturnFalseForDuplicate() {
            ring.addVolume("vol1", VOL1);
            boolean addedAgain = ring.addVolume("vol1", VOL1);
            assertThat(addedAgain).isFalse();
        }

        @Test
        @DisplayName("should reject null volume ID")
        void shouldRejectNullVolumeId() {
            assertThatThrownBy(() -> ring.addVolume(null, VOL1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Volume ID cannot be null");
        }

        @Test
        @DisplayName("should reject empty volume ID")
        void shouldRejectEmptyVolumeId() {
            assertThatThrownBy(() -> ring.addVolume("", VOL1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Volume ID cannot be empty");
        }

        @Test
        @DisplayName("should reject null volume path")
        void shouldRejectNullVolumePath() {
            assertThatThrownBy(() -> ring.addVolume("vol1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Volume path cannot be null");
        }
    }

    // ==================== Remove Volume Tests ====================

    @Nested
    @DisplayName("removeVolume()")
    class RemoveVolumeTests {

        @Test
        @DisplayName("should remove volume from ring")
        void shouldRemoveVolume() {
            ring.addVolume("vol1", VOL1);
            boolean removed = ring.removeVolume("vol1");
            assertThat(removed).isTrue();
            assertThat(ring.getVolumeCount()).isZero();
            assertThat(ring.getRingSize()).isZero();
        }

        @Test
        @DisplayName("should return false for non-existent volume")
        void shouldReturnFalseForNonExistent() {
            boolean removed = ring.removeVolume("nonexistent");
            assertThat(removed).isFalse();
        }

        @Test
        @DisplayName("should reject null volume ID")
        void shouldRejectNullVolumeId() {
            assertThatThrownBy(() -> ring.removeVolume(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Volume ID cannot be null");
        }
    }

    // ==================== Segment Assignment Tests ====================

    @Nested
    @DisplayName("assignSegment()")
    class AssignSegmentTests {

        @Test
        @DisplayName("should return null when no volumes registered")
        void shouldReturnNullWhenNoVolumes() {
            Path result = ring.assignSegment(1L);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should assign segment to a volume")
        void shouldAssignSegment() {
            ring.addVolume("vol1", VOL1);
            Path result = ring.assignSegment(1L);
            assertThat(result).isEqualTo(VOL1);
        }

        @Test
        @DisplayName("should assign segments consistently")
        void shouldAssignConsistently() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);

            // Same segment ID should always get same assignment
            Path result1 = ring.assignSegment(100L);
            Path result2 = ring.assignSegment(100L);
            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("should distribute segments across volumes")
        void shouldDistributeAcrossVolumes() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);
            ring.addVolume("vol3", VOL3);

            Set<Path> usedVolumes = new HashSet<>();
            for (long i = 0; i < 100; i++) {
                usedVolumes.add(ring.assignSegment(i));
            }

            // All three volumes should receive some segments
            assertThat(usedVolumes).hasSize(3);
        }
    }

    // ==================== Virtual Node Distribution Tests ====================

    @Nested
    @DisplayName("Virtual Node Distribution")
    class DistributionTests {

        @Test
        @DisplayName("should have good distribution with default virtual nodes")
        void shouldHaveGoodDistribution() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);
            ring.addVolume("vol3", VOL3);

            ConsistentHashRing.DistributionStats stats = ring.computeDistribution(10000);

            // Standard deviation should be less than 20% of mean
            assertThat(stats.coefficientOfVariation()).isLessThan(0.2);
        }

        @Test
        @DisplayName("should get volume node positions")
        void shouldGetVolumeNodePositions() {
            ring.addVolume("vol1", VOL1);

            List<Long> positions = ring.getVolumeNodePositions("vol1");

            // Should have 150 positions (default virtual nodes)
            assertThat(positions).hasSize(150);

            // Positions should be sorted
            for (int i = 1; i < positions.size(); i++) {
                assertThat(positions.get(i)).isGreaterThan(positions.get(i - 1));
            }
        }
    }

    // ==================== Consistency Tests ====================

    @Nested
    @DisplayName("Consistency")
    class ConsistencyTests {

        @Test
        @DisplayName("should maintain assignment after adding volume")
        void shouldMaintainAssignmentAfterAdd() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);

            // Record initial assignments
            Path[] initial = new Path[100];
            for (long i = 0; i < 100; i++) {
                initial[(int) i] = ring.assignSegment(i);
            }

            // Add a new volume
            ring.addVolume("vol3", VOL3);

            // Most assignments should remain the same (only ~1/3 should change)
            int unchanged = 0;
            for (long i = 0; i < 100; i++) {
                if (ring.assignSegment(i) == initial[(int) i]) {
                    unchanged++;
                }
            }

            // With 3 volumes, approximately 2/3 of assignments should be unchanged
            // (the new volume only takes 1/3 of the ring)
            assertThat((double) unchanged / 100).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("should maintain assignment after removing volume")
        void shouldMaintainAssignmentAfterRemove() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);
            ring.addVolume("vol3", VOL3);

            // Record assignments that go to vol1 or vol2
            Set<Long> vol1AndVol2Segments = new HashSet<>();
            for (long i = 0; i < 1000; i++) {
                Path assigned = ring.assignSegment(i);
                if (assigned == VOL1 || assigned == VOL2) {
                    vol1AndVol2Segments.add(i);
                }
            }

            // Remove vol3
            ring.removeVolume("vol3");

            // Segments that were assigned to vol1 or vol2 should still be there
            for (Long segmentId : vol1AndVol2Segments) {
                Path assigned = ring.assignSegment(segmentId);
                assertThat(assigned).isIn(VOL1, VOL2);
            }
        }
    }

    // ==================== Thread Safety Tests ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("should handle concurrent assignments")
        void shouldHandleConcurrentAssignments() throws InterruptedException {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);

            Thread[] threads = new Thread[10];
            Path[] results = new Path[1000];

            for (int t = 0; t < threads.length; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        int idx = threadId * 100 + i;
                        results[idx] = ring.assignSegment(idx);
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }

            // Verify all assignments were made
            for (int i = 0; i < 1000; i++) {
                assertThat(results[i]).isIn(VOL1, VOL2);
            }
        }

        @Test
        @DisplayName("should handle concurrent add and assign")
        void shouldHandleConcurrentAddAndAssign() throws InterruptedException {
            Thread[] threads = new Thread[10];
            Exception[] errors = new Exception[10];

            for (int t = 0; t < threads.length; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    try {
                        if (threadId % 2 == 0) {
                            // Add volumes
                            ring.addVolume("vol" + threadId, Path.of("/data/vol" + threadId));
                        } else {
                            // Assign segments
                            for (int i = 0; i < 100; i++) {
                                ring.assignSegment(threadId * 100 + i);
                            }
                        }
                    } catch (Exception e) {
                        errors[threadId] = e;
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }

            // No exceptions should have occurred
            for (Exception e : errors) {
                assertThat(e).isNull();
            }
        }
    }

    // ==================== Utility Tests ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityTests {

        @Test
        @DisplayName("should check if volume exists")
        void shouldCheckIfVolumeExists() {
            assertThat(ring.hasVolume("vol1")).isFalse();
            ring.addVolume("vol1", VOL1);
            assertThat(ring.hasVolume("vol1")).isTrue();
        }

        @Test
        @DisplayName("should get all volumes")
        void shouldGetAllVolumes() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);

            List<ConsistentHashRing.VolumeInfo> volumes = ring.getVolumes();
            assertThat(volumes).hasSize(2);
        }

        @Test
        @DisplayName("should clear all volumes")
        void shouldClearAllVolumes() {
            ring.addVolume("vol1", VOL1);
            ring.addVolume("vol2", VOL2);

            ring.clear();

            assertThat(ring.getVolumeCount()).isZero();
            assertThat(ring.getRingSize()).isZero();
        }
    }

    // ==================== VolumeInfo Tests ====================

    @Nested
    @DisplayName("VolumeInfo")
    class VolumeInfoTests {

        @Test
        @DisplayName("should create VolumeInfo")
        void shouldCreateVolumeInfo() {
            ConsistentHashRing.VolumeInfo info = new ConsistentHashRing.VolumeInfo("test", VOL1);

            assertThat(info.volumeId()).isEqualTo("test");
            assertThat(info.volumePath()).isEqualTo(VOL1);
        }

        @Test
        @DisplayName("should implement equals based on volumeId")
        void shouldImplementEquals() {
            ConsistentHashRing.VolumeInfo info1 = new ConsistentHashRing.VolumeInfo("test", VOL1);
            ConsistentHashRing.VolumeInfo info2 = new ConsistentHashRing.VolumeInfo("test", VOL2);
            ConsistentHashRing.VolumeInfo info3 = new ConsistentHashRing.VolumeInfo("other", VOL1);

            assertThat(info1).isEqualTo(info2);
            assertThat(info1).isNotEqualTo(info3);
        }

        @Test
        @DisplayName("should implement hashCode based on volumeId")
        void shouldImplementHashCode() {
            ConsistentHashRing.VolumeInfo info1 = new ConsistentHashRing.VolumeInfo("test", VOL1);
            ConsistentHashRing.VolumeInfo info2 = new ConsistentHashRing.VolumeInfo("test", VOL2);

            assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
        }
    }

    // ==================== DistributionStats Tests ====================

    @Nested
    @DisplayName("DistributionStats")
    class DistributionStatsTests {

        @Test
        @DisplayName("should compute coefficient of variation")
        void shouldComputeCoefficientOfVariation() {
            ConsistentHashRing.DistributionStats stats =
                new ConsistentHashRing.DistributionStats(90, 110, 100, 10.0);

            assertThat(stats.minCount()).isEqualTo(90);
            assertThat(stats.maxCount()).isEqualTo(110);
            assertThat(stats.meanCount()).isEqualTo(100);
            assertThat(stats.stdDev()).isEqualTo(10.0);
            assertThat(stats.coefficientOfVariation()).isEqualTo(0.1);
        }

        @Test
        @DisplayName("should handle zero mean")
        void shouldHandleZeroMean() {
            ConsistentHashRing.DistributionStats stats =
                new ConsistentHashRing.DistributionStats(0, 0, 0, 0.0);

            assertThat(stats.coefficientOfVariation()).isEqualTo(0.0);
        }
    }
}
