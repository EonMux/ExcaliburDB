package excalibur.shard.allocator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VolumeManager}.
 */
@DisplayName("VolumeManager Tests")
class VolumeManagerTest {

    private VolumeManager manager;

    @TempDir
    Path tempDir;

    private Path vol1;
    private Path vol2;
    private Path vol3;

    @BeforeEach
    void setUp() throws IOException {
        manager = new VolumeManager();
        vol1 = tempDir.resolve("vol1");
        vol2 = tempDir.resolve("vol2");
        vol3 = tempDir.resolve("vol3");
    }

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    // ==================== Constructor Tests ====================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create manager with defaults")
        void shouldCreateWithDefaults() {
            VolumeManager defaultManager = new VolumeManager();
            assertThat(defaultManager.getVolumeCount()).isZero();
        }

        @Test
        @DisplayName("should create manager with custom virtual nodes")
        void shouldCreateWithCustomVirtualNodes() {
            VolumeManager customManager = new VolumeManager(null, 100);
            assertThat(customManager.getVolumeCount()).isZero();
        }
    }

    // ==================== Volume Registration Tests ====================

    @Nested
    @DisplayName("registerVolume()")
    class RegisterVolumeTests {

        @Test
        @DisplayName("should register volume and create directory")
        void shouldRegisterAndCreateDirectory() throws IOException {
            Path newVol = tempDir.resolve("new-vol");
            assertThat(Files.exists(newVol)).isFalse();

            String volumeId = manager.registerVolume(newVol);

            assertThat(volumeId).isNotNull();
            assertThat(Files.exists(newVol)).isTrue();
            assertThat(Files.isDirectory(newVol)).isTrue();
        }

        @Test
        @DisplayName("should register existing directory")
        void shouldRegisterExistingDirectory() throws IOException {
            Files.createDirectories(vol1);

            String volumeId = manager.registerVolume(vol1);

            assertThat(volumeId).isNotNull();
            assertThat(manager.hasVolume(vol1)).isTrue();
        }

        @Test
        @DisplayName("should return same ID for duplicate registration")
        void shouldReturnSameIdForDuplicate() throws IOException {
            String id1 = manager.registerVolume(vol1);
            String id2 = manager.registerVolume(vol1);

            assertThat(id1).isEqualTo(id2);
            assertThat(manager.getVolumeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should reject null path")
        void shouldRejectNullPath() {
            assertThatThrownBy(() -> manager.registerVolume(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Volume path cannot be null");
        }

        @Test
        @DisplayName("should register multiple volumes")
        void shouldRegisterMultiple() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);
            manager.registerVolume(vol3);

            assertThat(manager.getVolumeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should register volumes from list")
        void shouldRegisterFromList() throws IOException {
            List<String> ids = manager.registerVolumes(List.of(vol1, vol2, vol3));

            assertThat(ids).hasSize(3);
            assertThat(manager.getVolumeCount()).isEqualTo(3);
        }
    }

    // ==================== Volume Unregistration Tests ====================

    @Nested
    @DisplayName("unregisterVolume()")
    class UnregisterVolumeTests {

        @Test
        @DisplayName("should unregister volume")
        void shouldUnregisterVolume() throws IOException {
            manager.registerVolume(vol1);
            boolean removed = manager.unregisterVolume(vol1);

            assertThat(removed).isTrue();
            assertThat(manager.hasVolume(vol1)).isFalse();
        }

        @Test
        @DisplayName("should return false for non-existent volume")
        void shouldReturnFalseForNonExistent() {
            boolean removed = manager.unregisterVolume(vol1);
            assertThat(removed).isFalse();
        }

        @Test
        @DisplayName("should reject null path")
        void shouldRejectNullPath() {
            assertThatThrownBy(() -> manager.unregisterVolume(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Volume path cannot be null");
        }

        @Test
        @DisplayName("should clear segment assignments when unregistering")
        void shouldClearSegmentAssignments() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            // Assign many segments to ensure some go to each volume
            int segmentsOnVol1 = 0;
            int segmentsOnVol2 = 0;
            for (long segId = 1; segId <= 100; segId++) {
                Path assigned = manager.assignSegmentToVolume(segId);
                if (assigned.equals(vol1)) segmentsOnVol1++;
                else if (assigned.equals(vol2)) segmentsOnVol2++;
            }

            // Unregister vol1
            manager.unregisterVolume(vol1);

            // Verify vol1 is no longer registered
            assertThat(manager.hasVolume(vol1)).isFalse();

            // Verify vol2 still exists
            assertThat(manager.hasVolume(vol2)).isTrue();

            // Verify segments that were on vol2 are still assigned
            // (We can't know exactly which ones were on vol1 vs vol2 without tracking,
            // but we can verify vol2 still has assignments)
            int remainingAssignments = 0;
            for (long segId = 1; segId <= 100; segId++) {
                if (manager.getVolumeForSegment(segId) != null) {
                    remainingAssignments++;
                }
            }

            // At least the segments that were on vol2 should still be assigned
            assertThat(remainingAssignments).isEqualTo(segmentsOnVol2);
        }
    }

    // ==================== Segment Assignment Tests ====================

    @Nested
    @DisplayName("assignSegmentToVolume()")
    class AssignSegmentTests {

        @Test
        @DisplayName("should return null when no volumes registered")
        void shouldReturnNullWhenNoVolumes() {
            Path result = manager.assignSegmentToVolume(1L);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should assign segment to a volume")
        void shouldAssignSegment() throws IOException {
            manager.registerVolume(vol1);

            Path result = manager.assignSegmentToVolume(1L);

            assertThat(result).isEqualTo(vol1);
        }

        @Test
        @DisplayName("should assign consistently")
        void shouldAssignConsistently() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            Path result1 = manager.assignSegmentToVolume(100L);
            Path result2 = manager.assignSegmentToVolume(100L);

            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("should distribute segments across volumes")
        void shouldDistributeAcrossVolumes() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);
            manager.registerVolume(vol3);

            Set<Path> usedVolumes = new HashSet<>();
            for (long i = 0; i < 100; i++) {
                usedVolumes.add(manager.assignSegmentToVolume(i));
            }

            assertThat(usedVolumes).hasSize(3);
        }

        @Test
        @DisplayName("should track segment-to-volume mapping")
        void shouldTrackMapping() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            Path assigned = manager.assignSegmentToVolume(42L);
            Path retrieved = manager.getVolumeForSegment(42L);

            assertThat(retrieved).isEqualTo(assigned);
        }

        @Test
        @DisplayName("should track volume-to-segments mapping")
        void shouldTrackVolumeMapping() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            // Assign multiple segments
            for (long i = 0; i < 100; i++) {
                manager.assignSegmentToVolume(i);
            }

            List<Long> vol1Segments = manager.getSegmentsForVolume(vol1);
            List<Long> vol2Segments = manager.getSegmentsForVolume(vol2);

            // Both volumes should have some segments
            assertThat(vol1Segments.size() + vol2Segments.size()).isEqualTo(100);
        }
    }

    // ==================== Reassignment Tests ====================

    @Nested
    @DisplayName("reassignSegment()")
    class ReassignTests {

        @Test
        @DisplayName("should reassign segment to new volume")
        void shouldReassignSegment() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            // Initial assignment
            Path initial = manager.assignSegmentToVolume(1L);

            // Reassign
            Path reassigned = manager.reassignSegment(1L);

            // After reassignment, the segment should still be assigned
            assertThat(reassigned).isNotNull();
        }

        @Test
        @DisplayName("should update volume-to-segment mapping on reassign")
        void shouldUpdateMappingOnReassign() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            manager.assignSegmentToVolume(1L);
            manager.reassignSegment(1L);

            // Total segment count should remain 1
            assertThat(manager.getTotalSegmentCount()).isEqualTo(1);
        }
    }

    // ==================== Statistics Tests ====================

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("should get statistics")
        void shouldGetStatistics() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            for (long i = 0; i < 50; i++) {
                manager.assignSegmentToVolume(i);
            }

            VolumeManager.Statistics stats = manager.getStatistics();

            assertThat(stats.volumeCount()).isEqualTo(2);
            assertThat(stats.totalSegments()).isEqualTo(50);
        }

        @Test
        @DisplayName("should get segment distribution")
        void shouldGetSegmentDistribution() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            for (long i = 0; i < 100; i++) {
                manager.assignSegmentToVolume(i);
            }

            Map<Path, Integer> distribution = manager.getSegmentDistribution();

            assertThat(distribution.size()).isEqualTo(2);
            int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
            assertThat(total).isEqualTo(100);
        }
    }

    // ==================== Utility Tests ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityTests {

        @Test
        @DisplayName("should check if volume exists")
        void shouldCheckIfVolumeExists() throws IOException {
            assertThat(manager.hasVolume(vol1)).isFalse();
            manager.registerVolume(vol1);
            assertThat(manager.hasVolume(vol1)).isTrue();
        }

        @Test
        @DisplayName("should get all volumes")
        void shouldGetAllVolumes() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            List<ConsistentHashRing.VolumeInfo> volumes = manager.getVolumes();

            assertThat(volumes).hasSize(2);
        }

        @Test
        @DisplayName("should get hash ring")
        void shouldGetHashRing() {
            ConsistentHashRing ring = manager.getHashRing();
            assertThat(ring).isNotNull();
        }

        @Test
        @DisplayName("should clear all data")
        void shouldClearAll() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);
            manager.assignSegmentToVolume(1L);
            manager.assignSegmentToVolume(2L);

            manager.clear();

            assertThat(manager.getVolumeCount()).isZero();
            assertThat(manager.getTotalSegmentCount()).isZero();
        }
    }

    // ==================== Thread Safety Tests ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("should handle concurrent assignments")
        void shouldHandleConcurrentAssignments() throws Exception {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);

            Thread[] threads = new Thread[10];
            final Path[] results = new Path[1000];

            for (int t = 0; t < threads.length; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        int idx = threadId * 100 + i;
                        results[idx] = manager.assignSegmentToVolume(idx);
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }

            // All assignments should be valid
            for (int i = 0; i < 1000; i++) {
                assertThat(results[i]).isIn(vol1, vol2);
            }
        }

        @Test
        @DisplayName("should handle concurrent registration and assignment")
        void shouldHandleConcurrentRegistrationAndAssignment() throws Exception {
            Thread[] threads = new Thread[10];
            final Exception[] errors = new Exception[10];

            for (int t = 0; t < threads.length; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    try {
                        if (threadId % 3 == 0) {
                            // Register volumes
                            manager.registerVolume(tempDir.resolve("vol" + threadId));
                        } else {
                            // Assign segments
                            for (int i = 0; i < 50; i++) {
                                manager.assignSegmentToVolume(threadId * 100 + i);
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

    // ==================== Distribution Quality Tests ====================

    @Nested
    @DisplayName("Distribution Quality")
    class DistributionQualityTests {

        @Test
        @DisplayName("should have good distribution with multiple volumes")
        void shouldHaveGoodDistribution() throws IOException {
            manager.registerVolume(vol1);
            manager.registerVolume(vol2);
            manager.registerVolume(vol3);

            ConsistentHashRing.DistributionStats stats = manager.computeDistribution(10000);

            // Coefficient of variation should be less than 20%
            assertThat(stats.coefficientOfVariation()).isLessThan(0.2);
        }
    }
}
