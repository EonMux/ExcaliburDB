package excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SegmentConfig validation methods.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>key_hash validation and normalization</li>
 *   <li>Capacity validation</li>
 *   <li>Distance validation</li>
 *   <li>Load factor computation</li>
 * </ul>
 */
@DisplayName("SegmentConfig Tests")
class SegmentConfigTest {

    // ==================== Key Hash Validation Tests ====================

    @Nested
    @DisplayName("Key Hash Validation")
    class KeyHashValidationTests {

        @Test
        @DisplayName("Should accept zero as valid key_hash")
        void testValidateKeyHashZero() {
            assertEquals(0L, SegmentConfig.validateKeyHash(0L));
        }

        @Test
        @DisplayName("Should accept Long.MAX_VALUE as valid key_hash")
        void testValidateKeyHashMaxValue() {
            assertEquals(Long.MAX_VALUE, SegmentConfig.validateKeyHash(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("Should accept positive values as valid key_hash")
        void testValidateKeyHashPositive() {
            assertEquals(1L, SegmentConfig.validateKeyHash(1L));
            assertEquals(12345L, SegmentConfig.validateKeyHash(12345L));
            assertEquals(Long.MAX_VALUE - 1, SegmentConfig.validateKeyHash(Long.MAX_VALUE - 1));
        }

        @Test
        @DisplayName("Should reject negative values as invalid key_hash")
        void testValidateKeyHashNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateKeyHash(-1L));

            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateKeyHash(Long.MIN_VALUE));

            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateKeyHash(-12345L));
        }

        @Test
        @DisplayName("isValidKeyHash should return true for valid hashes")
        void testIsValidKeyHashValid() {
            assertTrue(SegmentConfig.isValidKeyHash(0L));
            assertTrue(SegmentConfig.isValidKeyHash(1L));
            assertTrue(SegmentConfig.isValidKeyHash(Long.MAX_VALUE));
            assertTrue(SegmentConfig.isValidKeyHash(Long.MAX_VALUE / 2));
        }

        @Test
        @DisplayName("isValidKeyHash should return false for invalid hashes")
        void testIsValidKeyHashInvalid() {
            assertFalse(SegmentConfig.isValidKeyHash(-1L));
            assertFalse(SegmentConfig.isValidKeyHash(Long.MIN_VALUE));
            assertFalse(SegmentConfig.isValidKeyHash(-12345L));
        }
    }

    // ==================== Key Hash Normalization Tests ====================

    @Nested
    @DisplayName("Key Hash Normalization")
    class KeyHashNormalizationTests {

        @Test
        @DisplayName("Should preserve positive hash values")
        void testNormalizePositiveHash() {
            assertEquals(0L, SegmentConfig.normalizeKeyHash(0L));
            assertEquals(1L, SegmentConfig.normalizeKeyHash(1L));
            assertEquals(Long.MAX_VALUE, SegmentConfig.normalizeKeyHash(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("Should convert negative hash values to positive")
        void testNormalizeNegativeHash() {
            // Long.MIN_VALUE has only the sign bit set
            // After masking with Long.MAX_VALUE, it becomes 0
            assertEquals(0L, SegmentConfig.normalizeKeyHash(Long.MIN_VALUE));

            // -1 in binary is all 1s (0xFFFFFFFFFFFFFFFF)
            // After masking, it becomes Long.MAX_VALUE (0x7FFFFFFFFFFFFFFF)
            assertEquals(Long.MAX_VALUE, SegmentConfig.normalizeKeyHash(-1L));

            // -2 in binary is 0xFFFFFFFFFFFFFFFE
            // After masking, it becomes 0x7FFFFFFFFFFFFFFE = Long.MAX_VALUE - 1
            assertEquals(Long.MAX_VALUE - 1, SegmentConfig.normalizeKeyHash(-2L));
        }

        @Test
        @DisplayName("Normalized hash should always be valid")
        void testNormalizedHashIsValid() {
            // Test boundary values
            long[] testValues = {
                Long.MIN_VALUE,
                Long.MIN_VALUE + 1,
                -1L,
                -2L,
                -Long.MAX_VALUE,
                0L,
                1L,
                Long.MAX_VALUE / 2,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE
            };

            for (long rawHash : testValues) {
                long normalized = SegmentConfig.normalizeKeyHash(rawHash);
                assertTrue(SegmentConfig.isValidKeyHash(normalized),
                    "Normalized hash should be valid for input: " + rawHash);
                assertTrue(normalized >= 0 && normalized <= Long.MAX_VALUE,
                    "Normalized hash should be in range [0, Long.MAX_VALUE] for input: " + rawHash);
            }
        }

        @Test
        @DisplayName("Normalization should be idempotent for already-valid hashes")
        void testNormalizationIdempotent() {
            long validHash = 123456789L;
            assertEquals(validHash, SegmentConfig.normalizeKeyHash(validHash));
            assertEquals(validHash, SegmentConfig.normalizeKeyHash(SegmentConfig.normalizeKeyHash(validHash)));
        }

        @Test
        @DisplayName("Normalization preserves lower 63 bits")
        void testNormalizationPreservesLower63Bits() {
            // The normalization should only affect the sign bit
            // Lower 63 bits should be preserved
            for (int i = 0; i < 100; i++) {
                long testValue = i * 0x0123456789ABCDEFL;
                long normalized = SegmentConfig.normalizeKeyHash(testValue);
                // Lower 63 bits should match
                assertEquals(testValue & Long.MAX_VALUE, normalized);
            }
        }
    }

    // ==================== Capacity Validation Tests ====================

    @Nested
    @DisplayName("Capacity Validation")
    class CapacityValidationTests {

        @Test
        @DisplayName("Should accept valid power-of-2 capacities")
        void testValidateCapacityValid() {
            assertEquals(1024, SegmentConfig.validateCapacity(1024));
            assertEquals(2048, SegmentConfig.validateCapacity(2048));
            assertEquals(1048576, SegmentConfig.validateCapacity(1048576));
        }

        @Test
        @DisplayName("Should reject capacities below minimum")
        void testValidateCapacityBelowMinimum() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateCapacity(512));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateCapacity(0));
        }

        @Test
        @DisplayName("Should reject capacities above maximum")
        void testValidateCapacityAboveMaximum() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateCapacity(2097152)); // 2M
        }

        @Test
        @DisplayName("Should reject non-power-of-2 capacities")
        void testValidateCapacityNonPowerOf2() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateCapacity(1500));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateCapacity(10000));
        }
    }

    // ==================== Distance Validation Tests ====================

    @Nested
    @DisplayName("Distance Validation")
    class DistanceValidationTests {

        @Test
        @DisplayName("Should accept valid distances in range [0, 127]")
        void testValidateDistanceValid() {
            // validateDistance throws on invalid input, returns void on valid
            assertDoesNotThrow(() -> SegmentConfig.validateDistance(0));
            assertDoesNotThrow(() -> SegmentConfig.validateDistance(64));
            assertDoesNotThrow(() -> SegmentConfig.validateDistance(127));
        }

        @Test
        @DisplayName("Should reject negative distances")
        void testValidateDistanceNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateDistance(-1));
        }

        @Test
        @DisplayName("Should reject distances above 127")
        void testValidateDistanceAboveMax() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateDistance(128));
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateDistance(255));
        }
    }

    // ==================== Utility Method Tests ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodTests {

        @Test
        @DisplayName("isPowerOf2 should correctly identify powers of 2")
        void testIsPowerOf2() {
            assertTrue(SegmentConfig.isPowerOf2(1));
            assertTrue(SegmentConfig.isPowerOf2(2));
            assertTrue(SegmentConfig.isPowerOf2(4));
            assertTrue(SegmentConfig.isPowerOf2(1024));
            assertTrue(SegmentConfig.isPowerOf2(1048576));

            assertFalse(SegmentConfig.isPowerOf2(0));
            assertFalse(SegmentConfig.isPowerOf2(3));
            assertFalse(SegmentConfig.isPowerOf2(1000));
            assertFalse(SegmentConfig.isPowerOf2(-4));
        }

        @Test
        @DisplayName("calculateLoadFactor should compute correct ratio")
        void testCalculateLoadFactor() {
            assertEquals(0.0, SegmentConfig.calculateLoadFactor(0, 1000), 0.0001);
            assertEquals(0.5, SegmentConfig.calculateLoadFactor(500, 1000), 0.0001);
            assertEquals(0.7, SegmentConfig.calculateLoadFactor(700, 1000), 0.0001);
            assertEquals(1.0, SegmentConfig.calculateLoadFactor(1000, 1000), 0.0001);
        }

        @Test
        @DisplayName("computeSlotIndex should compute correct slot for power-of-2 capacity")
        void testComputeSlotIndex() {
            int slotCount = 1024;

            // Hash 0 should map to slot 0
            assertEquals(0, SegmentConfig.computeSlotIndex(0, slotCount));

            // Hash equal to slotCount should wrap to 0
            assertEquals(0, SegmentConfig.computeSlotIndex(slotCount, slotCount));

            // Hash 1 should map to slot 1
            assertEquals(1, SegmentConfig.computeSlotIndex(1, slotCount));

            // Large hash should be properly modded
            assertEquals(123, SegmentConfig.computeSlotIndex(slotCount + 123, slotCount));
        }

        @Test
        @DisplayName("roundToValidCapacity should round to nearest power of 2")
        void testRoundToValidCapacity() {
            assertEquals(1024, SegmentConfig.roundToValidCapacity(500));
            assertEquals(1024, SegmentConfig.roundToValidCapacity(1024));
            assertEquals(2048, SegmentConfig.roundToValidCapacity(1500));
            assertEquals(1048576, SegmentConfig.roundToValidCapacity(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("calculateEntrySize should compute correct total size")
        void testCalculateEntrySize() {
            // ENTRY_FIXED_OVERHEAD = 18 (8 + 4 + 4 + 1 + 1)
            assertEquals(18, SegmentConfig.calculateEntrySize(0, 0));
            assertEquals(28, SegmentConfig.calculateEntrySize(5, 5));
            assertEquals(118, SegmentConfig.calculateEntrySize(50, 50));
        }
    }

    // ==================== Tombstone Validation Tests ====================

    @Nested
    @DisplayName("Tombstone Validation")
    class TombstoneValidationTests {

        @Test
        @DisplayName("validateTombstone should accept 0 (TOMBSTONE_LIVE)")
        void testValidateTombstoneLive() {
            assertDoesNotThrow(() -> SegmentConfig.validateTombstone(SegmentConfig.TOMBSTONE_LIVE));
            assertDoesNotThrow(() -> SegmentConfig.validateTombstone((byte) 0));
        }

        @Test
        @DisplayName("validateTombstone should accept 1 (TOMBSTONE_DELETED)")
        void testValidateTombstoneDeleted() {
            assertDoesNotThrow(() -> SegmentConfig.validateTombstone(SegmentConfig.TOMBSTONE_DELETED));
            assertDoesNotThrow(() -> SegmentConfig.validateTombstone((byte) 1));
        }

        @Test
        @DisplayName("validateTombstone should reject 2")
        void testValidateTombstoneInvalid2() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateTombstone((byte) 2));
        }

        @Test
        @DisplayName("validateTombstone should reject -1")
        void testValidateTombstoneInvalidNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateTombstone((byte) -1));
        }

        @Test
        @DisplayName("validateTombstone should reject 127")
        void testValidateTombstoneInvalid127() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateTombstone((byte) 127));
        }

        @Test
        @DisplayName("validateTombstone should reject -128 (Byte.MIN_VALUE)")
        void testValidateTombstoneInvalidMin() {
            assertThrows(IllegalArgumentException.class, () ->
                SegmentConfig.validateTombstone(Byte.MIN_VALUE));
        }

        @Test
        @DisplayName("validateTombstone should reject all invalid positive values")
        void testValidateTombstoneInvalidPositive() {
            // Use int loop to avoid byte overflow (byte 127 + 1 = -128)
            for (int i = 2; i <= 127; i++) {
                final byte tombstone = (byte) i;
                assertThrows(IllegalArgumentException.class, () ->
                    SegmentConfig.validateTombstone(tombstone),
                    "Expected rejection for tombstone value: " + i);
            }
        }

        @Test
        @DisplayName("validateTombstone should reject all invalid negative values")
        void testValidateTombstoneInvalidNegativeRange() {
            // Use int loop to avoid byte overflow (byte -128 - 1 = 127)
            for (int i = -1; i >= Byte.MIN_VALUE; i--) {
                final byte tombstone = (byte) i;
                assertThrows(IllegalArgumentException.class, () ->
                    SegmentConfig.validateTombstone(tombstone),
                    "Expected rejection for tombstone value: " + i);
            }
        }

        @Test
        @DisplayName("isValidTombstone should return true for 0")
        void testIsValidTombstoneLive() {
            assertTrue(SegmentConfig.isValidTombstone(SegmentConfig.TOMBSTONE_LIVE));
            assertTrue(SegmentConfig.isValidTombstone((byte) 0));
        }

        @Test
        @DisplayName("isValidTombstone should return true for 1")
        void testIsValidTombstoneDeleted() {
            assertTrue(SegmentConfig.isValidTombstone(SegmentConfig.TOMBSTONE_DELETED));
            assertTrue(SegmentConfig.isValidTombstone((byte) 1));
        }

        @Test
        @DisplayName("isValidTombstone should return false for 2")
        void testIsValidTombstoneInvalid2() {
            assertFalse(SegmentConfig.isValidTombstone((byte) 2));
        }

        @Test
        @DisplayName("isValidTombstone should return false for -1")
        void testIsValidTombstoneInvalidNegative() {
            assertFalse(SegmentConfig.isValidTombstone((byte) -1));
        }

        @Test
        @DisplayName("isValidTombstone should return false for all invalid values")
        void testIsValidTombstoneInvalidRange() {
            // Test all values except 0 and 1
            for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
                if (i != 0 && i != 1) {
                    assertFalse(SegmentConfig.isValidTombstone((byte) i),
                        "Expected false for tombstone value: " + i);
                }
            }
        }

        @Test
        @DisplayName("TOMBSTONE_LIVE constant should be 0")
        void testTombstoneLiveConstant() {
            assertEquals(0, SegmentConfig.TOMBSTONE_LIVE);
        }

        @Test
        @DisplayName("TOMBSTONE_DELETED constant should be 1")
        void testTombstoneDeletedConstant() {
            assertEquals(1, SegmentConfig.TOMBSTONE_DELETED);
        }

        @Test
        @DisplayName("Error message should contain the invalid value")
        void testTombstoneErrorMessage() {
            try {
                SegmentConfig.validateTombstone((byte) 42);
                fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("42"));
                assertTrue(e.getMessage().contains("0"));
                assertTrue(e.getMessage().contains("1"));
            }
        }
    }
}
