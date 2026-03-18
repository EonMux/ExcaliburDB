package excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying that slot positions are computed correctly as
 * {@code hash % capacity} where capacity is a power of 2.
 *
 * <p>These tests verify the critical domain constraint:
 * <ul>
 *   <li>Slot positions MUST be computed as {@code hash % capacity}</li>
 *   <li>Capacity MUST be a power of 2</li>
 *   <li>When capacity is power of 2, {@code hash % capacity} is equivalent to {@code hash & (capacity - 1)}</li>
 * </ul>
 *
 * <h2>Mathematical Background</h2>
 * <p>For any non-negative integer hash and any power-of-2 capacity C:
 * <pre>
 *   hash % C == hash & (C - 1)
 * </pre>
 *
 * <p>This is because:
 * <ul>
 *   <li>Power of 2 values have exactly one bit set: C = 2^n</li>
 *   <li>C - 1 has bits 0 through n-1 set (all lower bits)</li>
 *   <li>ANDing with (C - 1) extracts the lower n bits, which equals hash mod C</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Slot positions computed as: {@code hash & (capacity - 1)}</li>
 *   <li>Capacity in range [1024, 1048576] and power of 2</li>
 *   <li>Result always in range [0, capacity - 1]</li>
 *   <li>Hash values normalized to [0, Long.MAX_VALUE]</li>
 * </ul>
 */
@DisplayName("Slot Position Computation Tests")
class SlotPositionComputationTest {

    // ==================== Mathematical Equivalence Tests ====================

    @Nested
    @DisplayName("Mathematical Equivalence: hash % capacity == hash & (capacity - 1)")
    class MathematicalEquivalenceTests {

        @Test
        @DisplayName("Verify equivalence for hash = 0")
        void testEquivalenceHashZero() {
            for (int capacity : getAllPowerOf2Capacities()) {
                long hash = 0L;
                int moduloResult = (int) (hash % capacity);
                int bitwiseResult = (int) (hash & (capacity - 1L));

                assertEquals(moduloResult, bitwiseResult,
                    "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                    "computeSlotIndex should match modulo result");
            }
        }

        @Test
        @DisplayName("Verify equivalence for hash = capacity - 1")
        void testEquivalenceHashCapacityMinusOne() {
            for (int capacity : getAllPowerOf2Capacities()) {
                long hash = capacity - 1L;
                int moduloResult = (int) (hash % capacity);
                int bitwiseResult = (int) (hash & (capacity - 1L));

                assertEquals(moduloResult, bitwiseResult,
                    "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                    "computeSlotIndex should match modulo result");
            }
        }

        @Test
        @DisplayName("Verify equivalence for hash = capacity")
        void testEquivalenceHashEqualsCapacity() {
            for (int capacity : getAllPowerOf2Capacities()) {
                long hash = capacity;
                int moduloResult = (int) (hash % capacity);
                int bitwiseResult = (int) (hash & (capacity - 1L));

                assertEquals(moduloResult, bitwiseResult,
                    "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                assertEquals(0, moduloResult, "hash % capacity should be 0 when hash equals capacity");
                assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                    "computeSlotIndex should match modulo result");
            }
        }

        @Test
        @DisplayName("Verify equivalence for hash = capacity + 1")
        void testEquivalenceHashCapacityPlusOne() {
            for (int capacity : getAllPowerOf2Capacities()) {
                long hash = capacity + 1L;
                int moduloResult = (int) (hash % capacity);
                int bitwiseResult = (int) (hash & (capacity - 1L));

                assertEquals(moduloResult, bitwiseResult,
                    "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                assertEquals(1, moduloResult, "hash % capacity should be 1 when hash equals capacity + 1");
                assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                    "computeSlotIndex should match modulo result");
            }
        }

        @Test
        @DisplayName("Verify equivalence for large hash values")
        void testEquivalenceLargeHash() {
            for (int capacity : getAllPowerOf2Capacities()) {
                // Test with large positive hash values
                long[] testHashes = {
                    Long.MAX_VALUE,
                    Long.MAX_VALUE - 1,
                    Long.MAX_VALUE / 2,
                    Integer.MAX_VALUE * 2L,
                    capacity * 1000L,
                    capacity * 1000L + 1,
                    capacity * 1000L - 1
                };

                for (long hash : testHashes) {
                    int moduloResult = (int) (hash % capacity);
                    int bitwiseResult = (int) (hash & (capacity - 1L));

                    assertEquals(moduloResult, bitwiseResult,
                        "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                    assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                        "computeSlotIndex should match modulo result for hash=" + hash);
                }
            }
        }

        @Test
        @DisplayName("Verify equivalence for random hash values")
        void testEquivalenceRandomHashes() {
            Random random = new Random(42); // Fixed seed for reproducibility

            for (int capacity : getAllPowerOf2Capacities()) {
                for (int i = 0; i < 1000; i++) {
                    // Generate non-negative random hash
                    long hash = random.nextLong() & Long.MAX_VALUE;

                    int moduloResult = (int) (hash % capacity);
                    int bitwiseResult = (int) (hash & (capacity - 1L));

                    assertEquals(moduloResult, bitwiseResult,
                        "hash % capacity should equal hash & (capacity - 1) for hash=" + hash + ", capacity=" + capacity);
                    assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                        "computeSlotIndex should match modulo result for hash=" + hash);
                }
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 3, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 255, 256,
                              511, 512, 1023, 1024, 2047, 2048, 4095, 4096, 8191, 8192})
        @DisplayName("Verify equivalence across sequential hash values")
        void testEquivalenceSequentialHashes(long hash) {
            // For MIN_SEGMENT_CAPACITY (1024)
            int capacity = SegmentConfig.MIN_SEGMENT_CAPACITY;

            int moduloResult = (int) (hash % capacity);
            int bitwiseResult = (int) (hash & (capacity - 1L));

            assertEquals(moduloResult, bitwiseResult,
                "hash % capacity should equal hash & (capacity - 1) for hash=" + hash);
            assertEquals(moduloResult, SegmentConfig.computeSlotIndex(hash, capacity),
                "computeSlotIndex should match modulo result");
        }
    }

    // ==================== Range Constraint Tests ====================

    @Nested
    @DisplayName("Range Constraints")
    class RangeConstraintTests {

        @Test
        @DisplayName("Slot index must be in range [0, capacity - 1]")
        void testSlotIndexRange() {
            Random random = new Random(12345);

            for (int capacity : getAllPowerOf2Capacities()) {
                for (int i = 0; i < 1000; i++) {
                    long hash = random.nextLong() & Long.MAX_VALUE;
                    int slotIndex = SegmentConfig.computeSlotIndex(hash, capacity);

                    assertTrue(slotIndex >= 0,
                        "Slot index must be non-negative: " + slotIndex);
                    assertTrue(slotIndex < capacity,
                        "Slot index must be less than capacity: slotIndex=" + slotIndex + ", capacity=" + capacity);
                }
            }
        }

        @Test
        @DisplayName("Slot index 0 is reachable")
        void testSlotIndexZeroReachable() {
            for (int capacity : getAllPowerOf2Capacities()) {
                // hash = 0 should produce slot 0
                assertEquals(0, SegmentConfig.computeSlotIndex(0, capacity));

                // hash = capacity should produce slot 0
                assertEquals(0, SegmentConfig.computeSlotIndex(capacity, capacity));

                // hash = capacity * 2 should produce slot 0
                assertEquals(0, SegmentConfig.computeSlotIndex(capacity * 2L, capacity));

                // hash = capacity * n should produce slot 0 for any n
                for (int n = 1; n <= 10; n++) {
                    assertEquals(0, SegmentConfig.computeSlotIndex((long) capacity * n, capacity),
                        "hash=" + ((long) capacity * n) + " should map to slot 0");
                }
            }
        }

        @Test
        @DisplayName("Slot index capacity-1 is reachable")
        void testSlotIndexCapacityMinusOneReachable() {
            for (int capacity : getAllPowerOf2Capacities()) {
                // hash = capacity - 1 should produce slot capacity - 1
                assertEquals(capacity - 1, SegmentConfig.computeSlotIndex(capacity - 1, capacity));

                // hash = capacity * 2 - 1 should produce slot capacity - 1
                assertEquals(capacity - 1, SegmentConfig.computeSlotIndex(capacity * 2L - 1, capacity));

                // hash = capacity * n - 1 should produce slot capacity - 1 for any n
                for (int n = 1; n <= 10; n++) {
                    assertEquals(capacity - 1, SegmentConfig.computeSlotIndex((long) capacity * n - 1, capacity),
                        "hash=" + ((long) capacity * n - 1) + " should map to slot " + (capacity - 1));
                }
            }
        }

        @Test
        @DisplayName("All slots are reachable")
        void testAllSlotsReachable() {
            for (int capacity : getAllPowerOf2Capacities()) {
                boolean[] reached = new boolean[capacity];

                // Try to reach each slot with hash = slot index
                for (int slot = 0; slot < capacity; slot++) {
                    int result = SegmentConfig.computeSlotIndex(slot, capacity);
                    assertEquals(slot, result, "hash=" + slot + " should map to slot " + slot);
                    reached[slot] = true;
                }

                // Verify all slots were reached
                for (int slot = 0; slot < capacity; slot++) {
                    assertTrue(reached[slot], "Slot " + slot + " should be reachable");
                }
            }
        }

        @Test
        @DisplayName("Distribution should be uniform for random hashes")
        void testUniformDistribution() {
            int capacity = 1024;
            int[] slotCounts = new int[capacity];
            Random random = new Random(98765);
            int iterations = 100000;

            for (int i = 0; i < iterations; i++) {
                long hash = random.nextLong() & Long.MAX_VALUE;
                int slot = SegmentConfig.computeSlotIndex(hash, capacity);
                slotCounts[slot]++;
            }

            // Expected count per slot
            double expectedCount = (double) iterations / capacity;

            // Check that each slot count is within reasonable bounds (±50%)
            for (int slot = 0; slot < capacity; slot++) {
                double deviation = Math.abs(slotCounts[slot] - expectedCount) / expectedCount;
                assertTrue(deviation < 0.5,
                    "Slot " + slot + " count " + slotCounts[slot] + " deviates " + (deviation * 100) +
                    "% from expected " + expectedCount);
            }
        }
    }

    // ==================== Capacity Power-of-2 Constraint Tests ====================

    @Nested
    @DisplayName("Capacity Power-of-2 Constraint")
    class CapacityPowerOf2Tests {

        @Test
        @DisplayName("MIN_SEGMENT_CAPACITY must be power of 2")
        void testMinCapacityPowerOf2() {
            assertTrue(SegmentConfig.isPowerOf2(SegmentConfig.MIN_SEGMENT_CAPACITY),
                "MIN_SEGMENT_CAPACITY must be power of 2");
            assertEquals(1024, SegmentConfig.MIN_SEGMENT_CAPACITY,
                "MIN_SEGMENT_CAPACITY should be 1024");
        }

        @Test
        @DisplayName("MAX_SEGMENT_CAPACITY must be power of 2")
        void testMaxCapacityPowerOf2() {
            assertTrue(SegmentConfig.isPowerOf2(SegmentConfig.MAX_SEGMENT_CAPACITY),
                "MAX_SEGMENT_CAPACITY must be power of 2");
            assertEquals(1 << 20, SegmentConfig.MAX_SEGMENT_CAPACITY,
                "MAX_SEGMENT_CAPACITY should be 2^20 = 1048576");
        }

        @Test
        @DisplayName("All valid capacities must be power of 2")
        void testAllValidCapacitiesPowerOf2() {
            // Test all power-of-2 values in valid range
            for (int capacity = SegmentConfig.MIN_SEGMENT_CAPACITY;
                 capacity <= SegmentConfig.MAX_SEGMENT_CAPACITY;
                 capacity <<= 1) {

                assertTrue(SegmentConfig.isPowerOf2(capacity),
                    "Capacity " + capacity + " must be power of 2");
                final int finalCapacity = capacity;
                assertDoesNotThrow(() -> SegmentConfig.validateCapacity(finalCapacity),
                    "Capacity " + capacity + " should be valid");
            }
        }

        @Test
        @DisplayName("Non-power-of-2 values should be rejected")
        void testNonPowerOf2Rejected() {
            int[] invalidCapacities = {
                1023, 1025, 1500, 2000, 3000, 5000, 10000, 100000,
                SegmentConfig.MIN_SEGMENT_CAPACITY + 1,
                SegmentConfig.MAX_SEGMENT_CAPACITY - 1
            };

            for (int capacity : invalidCapacities) {
                if (capacity >= SegmentConfig.MIN_SEGMENT_CAPACITY &&
                    capacity <= SegmentConfig.MAX_SEGMENT_CAPACITY) {
                    assertFalse(SegmentConfig.isPowerOf2(capacity),
                        "Capacity " + capacity + " should not be power of 2");
                    assertThrows(IllegalArgumentException.class,
                        () -> SegmentConfig.validateCapacity(capacity),
                        "Non-power-of-2 capacity " + capacity + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("roundToValidCapacity should always return power of 2")
        void testRoundToValidCapacityReturnsPowerOf2() {
            Random random = new Random(11111);

            for (int i = 0; i < 1000; i++) {
                int requested = random.nextInt(SegmentConfig.MAX_SEGMENT_CAPACITY * 2);
                int rounded = SegmentConfig.roundToValidCapacity(requested);

                assertTrue(SegmentConfig.isPowerOf2(rounded),
                    "roundToValidCapacity(" + requested + ") = " + rounded + " should be power of 2");
                assertTrue(rounded >= SegmentConfig.MIN_SEGMENT_CAPACITY,
                    "Rounded capacity should be >= MIN_SEGMENT_CAPACITY");
                assertTrue(rounded <= SegmentConfig.MAX_SEGMENT_CAPACITY,
                    "Rounded capacity should be <= MAX_SEGMENT_CAPACITY");
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -1024, Integer.MIN_VALUE})
        @DisplayName("Negative or zero requested capacities should return minimum")
        void testRoundToValidCapacityNegativeOrZero(int requested) {
            int result = SegmentConfig.roundToValidCapacity(requested);
            assertEquals(SegmentConfig.MIN_SEGMENT_CAPACITY, result,
                "Negative or zero requested capacity should return minimum");
            assertTrue(SegmentConfig.isPowerOf2(result),
                "Result should be power of 2");
        }
    }

    // ==================== Bitwise Operation Correctness Tests ====================

    @Nested
    @DisplayName("Bitwise Operation Correctness")
    class BitwiseOperationTests {

        @Test
        @DisplayName("capacity - 1 should have all lower bits set")
        void testCapacityMinusOneBits() {
            for (int capacity : getAllPowerOf2Capacities()) {
                int mask = capacity - 1;

                // For power of 2: 2^n - 1 has bits 0 to n-1 set
                // Verify: mask + 1 should equal capacity (no bits above position n-1)
                assertEquals(capacity, mask + 1,
                    "(capacity - 1) + 1 should equal capacity");

                // mask & capacity should be 0 (no overlapping bits)
                assertEquals(0, mask & capacity,
                    "(capacity - 1) & capacity should be 0");

                // mask should be all 1s in lower bits
                // For 1024 = 2^10, mask = 1023 = 0x3FF (10 bits set)
                int bitCount = Integer.bitCount(mask);
                int expectedBits = (int) (Math.log(capacity) / Math.log(2));
                assertEquals(expectedBits, bitCount,
                    "capacity - 1 should have log2(capacity) bits set");
            }
        }

        @Test
        @DisplayName("AND with mask extracts correct number of bits")
        void testAndExtractsCorrectBits() {
            for (int capacity : getAllPowerOf2Capacities()) {
                int mask = capacity - 1;
                int maxSlot = capacity - 1;

                // Test with all 1s in hash (all bits set)
                long allOnesHash = Long.MAX_VALUE; // 0x7FFFFFFFFFFFFFFF
                int result = SegmentConfig.computeSlotIndex(allOnesHash, capacity);
                assertEquals(maxSlot, result,
                    "Hash with all bits set should map to max slot");

                // Test with alternating bit pattern
                long altHash = 0x5555555555555555L;
                result = SegmentConfig.computeSlotIndex(altHash, capacity);
                assertTrue(result >= 0 && result <= maxSlot,
                    "Result must be in valid range");

                // Test with another alternating pattern
                altHash = 0xAAAAAAAAAAAAAAAAL & Long.MAX_VALUE; // Ensure non-negative
                result = SegmentConfig.computeSlotIndex(altHash, capacity);
                assertTrue(result >= 0 && result <= maxSlot,
                    "Result must be in valid range");
            }
        }

        @Test
        @DisplayName("Wrapping behavior: slot index wraps at capacity boundary")
        void testWrappingBehavior() {
            for (int capacity : getAllPowerOf2Capacities()) {
                // Just below capacity boundary
                int slot1 = SegmentConfig.computeSlotIndex(capacity - 1, capacity);
                assertEquals(capacity - 1, slot1);

                // At capacity boundary - should wrap to 0
                int slot2 = SegmentConfig.computeSlotIndex(capacity, capacity);
                assertEquals(0, slot2);

                // Just above capacity boundary - should be 1
                int slot3 = SegmentConfig.computeSlotIndex(capacity + 1, capacity);
                assertEquals(1, slot3);

                // Verify continuity across boundary
                assertEquals(slot1, capacity - 1);
                assertEquals(0, slot2);
                assertEquals(1, slot3);
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Returns all valid power-of-2 capacities for testing.
     */
    private int[] getAllPowerOf2Capacities() {
        int min = SegmentConfig.MIN_SEGMENT_CAPACITY;
        int max = SegmentConfig.MAX_SEGMENT_CAPACITY;
        int count = 0;

        // Count how many power-of-2 values we have
        for (int c = min; c <= max; c <<= 1) {
            count++;
        }

        // Build array
        int[] capacities = new int[count];
        int idx = 0;
        for (int c = min; c <= max; c <<= 1) {
            capacities[idx++] = c;
        }

        return capacities;
    }
}
