package com.eonmux.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SegmentLayout}.
 */
@DisplayName("SegmentLayout Tests")
class SegmentLayoutTest {

    @Nested
    @DisplayName("Predefined Layouts")
    class PredefinedLayoutsTests {

        @Test
        @DisplayName("BYTE layout should have correct properties")
        void byteLayoutShouldHaveCorrectProperties() {
            assertEquals(1, SegmentLayout.BYTE.byteSize());
            assertEquals(Byte.class, SegmentLayout.BYTE.carrier());
            assertNotNull(SegmentLayout.BYTE.byteOrder());
        }

        @Test
        @DisplayName("INT layout should have correct properties")
        void intLayoutShouldHaveCorrectProperties() {
            assertEquals(4, SegmentLayout.INT.byteSize());
            assertEquals(Integer.class, SegmentLayout.INT.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.INT.byteOrder());
        }

        @Test
        @DisplayName("INT_UNALIGNED layout should have correct properties")
        void intUnalignedLayoutShouldHaveCorrectProperties() {
            assertEquals(4, SegmentLayout.INT_UNALIGNED.byteSize());
            assertEquals(Integer.class, SegmentLayout.INT_UNALIGNED.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.INT_UNALIGNED.byteOrder());
        }

        @Test
        @DisplayName("LONG layout should have correct properties")
        void longLayoutShouldHaveCorrectProperties() {
            assertEquals(8, SegmentLayout.LONG.byteSize());
            assertEquals(Long.class, SegmentLayout.LONG.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.LONG.byteOrder());
        }

        @Test
        @DisplayName("LONG_UNALIGNED layout should have correct properties")
        void longUnalignedLayoutShouldHaveCorrectProperties() {
            assertEquals(8, SegmentLayout.LONG_UNALIGNED.byteSize());
            assertEquals(Long.class, SegmentLayout.LONG_UNALIGNED.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.LONG_UNALIGNED.byteOrder());
        }

        @Test
        @DisplayName("FLOAT layout should have correct properties")
        void floatLayoutShouldHaveCorrectProperties() {
            assertEquals(4, SegmentLayout.FLOAT.byteSize());
            assertEquals(Float.class, SegmentLayout.FLOAT.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.FLOAT.byteOrder());
        }

        @Test
        @DisplayName("FLOAT_UNALIGNED layout should have correct properties")
        void floatUnalignedLayoutShouldHaveCorrectProperties() {
            assertEquals(4, SegmentLayout.FLOAT_UNALIGNED.byteSize());
            assertEquals(Float.class, SegmentLayout.FLOAT_UNALIGNED.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.FLOAT_UNALIGNED.byteOrder());
        }

        @Test
        @DisplayName("DOUBLE layout should have correct properties")
        void doubleLayoutShouldHaveCorrectProperties() {
            assertEquals(8, SegmentLayout.DOUBLE.byteSize());
            assertEquals(Double.class, SegmentLayout.DOUBLE.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.DOUBLE.byteOrder());
        }

        @Test
        @DisplayName("DOUBLE_UNALIGNED layout should have correct properties")
        void doubleUnalignedLayoutShouldHaveCorrectProperties() {
            assertEquals(8, SegmentLayout.DOUBLE_UNALIGNED.byteSize());
            assertEquals(Double.class, SegmentLayout.DOUBLE_UNALIGNED.carrier());
            assertEquals(ByteOrder.BIG_ENDIAN, SegmentLayout.DOUBLE_UNALIGNED.byteOrder());
        }
    }

    @Nested
    @DisplayName("withOrder")
    class WithOrderTests {

        @Test
        @DisplayName("withOrder should create new layout with different byte order")
        void withOrderShouldCreateNewLayout() {
            SegmentLayout original = SegmentLayout.INT;
            SegmentLayout modified = original.withOrder(ByteOrder.LITTLE_ENDIAN);

            assertNotSame(original, modified);
            assertEquals(ByteOrder.BIG_ENDIAN, original.byteOrder());
            assertEquals(ByteOrder.LITTLE_ENDIAN, modified.byteOrder());
        }

        @Test
        @DisplayName("withOrder should preserve size and carrier")
        void withOrderShouldPreserveSizeAndCarrier() {
            SegmentLayout modified = SegmentLayout.LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

            assertEquals(8, modified.byteSize());
            assertEquals(Long.class, modified.carrier());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain size")
        void toStringShouldContainSize() {
            String result = SegmentLayout.INT.toString();
            assertTrue(result.contains("size=4"));
        }

        @Test
        @DisplayName("toString should contain carrier name")
        void toStringShouldContainCarrierName() {
            String result = SegmentLayout.LONG.toString();
            assertTrue(result.contains("Integer") || result.contains("Long"));
        }

        @Test
        @DisplayName("toString should contain byte order")
        void toStringShouldContainByteOrder() {
            String result = SegmentLayout.DOUBLE.toString();
            assertTrue(result.contains("BIG_ENDIAN") || result.contains("LITTLE_ENDIAN"));
        }

        @Test
        @DisplayName("toString should have correct format")
        void toStringShouldHaveCorrectFormat() {
            String result = SegmentLayout.INT.toString();
            assertTrue(result.startsWith("SegmentLayout["));
            assertTrue(result.endsWith("]"));
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("All layouts should have positive byte sizes")
        void allLayoutsShouldHavePositiveByteSizes() {
            assertTrue(SegmentLayout.BYTE.byteSize() > 0);
            assertTrue(SegmentLayout.INT.byteSize() > 0);
            assertTrue(SegmentLayout.LONG.byteSize() > 0);
            assertTrue(SegmentLayout.FLOAT.byteSize() > 0);
            assertTrue(SegmentLayout.DOUBLE.byteSize() > 0);
        }

        @Test
        @DisplayName("Numeric type sizes should be valid")
        void numericTypeSizesShouldBeValid() {
            // Standard sizes for Java primitives
            assertEquals(1, SegmentLayout.BYTE.byteSize());
            assertEquals(4, SegmentLayout.INT.byteSize());
            assertEquals(8, SegmentLayout.LONG.byteSize());
            assertEquals(4, SegmentLayout.FLOAT.byteSize());
            assertEquals(8, SegmentLayout.DOUBLE.byteSize());
        }

        @Test
        @DisplayName("All layouts should have non-null byte order")
        void allLayoutsShouldHaveNonNullByteOrder() {
            assertNotNull(SegmentLayout.BYTE.byteOrder());
            assertNotNull(SegmentLayout.INT.byteOrder());
            assertNotNull(SegmentLayout.LONG.byteOrder());
            assertNotNull(SegmentLayout.FLOAT.byteOrder());
            assertNotNull(SegmentLayout.DOUBLE.byteOrder());
        }
    }
}
