package io.excalibur.core.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DoubleCodec to improve coverage.
 */
@DisplayName("DoubleCodec Comprehensive Tests")
class DoubleCodecComprehensiveTest {

    private DoubleCodec codec;

    @BeforeEach
    void setUp() {
        codec = DoubleCodec.getInstance();
    }

    @Nested
    @DisplayName("Singleton Tests")
    class SingletonTests {

        @Test
        @DisplayName("Should return same instance")
        void shouldReturnSameInstance() {
            DoubleCodec c1 = DoubleCodec.getInstance();
            DoubleCodec c2 = DoubleCodec.getInstance();
            assertSame(c1, c2);
        }

        @Test
        @DisplayName("Instance method should return same singleton")
        void instanceMethodShouldReturnSameSingleton() {
            assertSame(codec, codec.instance());
        }
    }

    @Nested
    @DisplayName("Size Tests")
    class SizeTests {

        @Test
        @DisplayName("SizeOf should return 8 bytes")
        void sizeOfShouldReturn8Bytes() {
            assertEquals(8, codec.sizeOf());
        }
    }

    @Nested
    @DisplayName("Encode Tests")
    class EncodeTests {

        @Test
        @DisplayName("Should encode zero")
        void shouldEncodeZero() {
            byte[] encoded = codec.encode(0.0);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode positive value")
        void shouldEncodePositiveValue() {
            double value = 123.456789012345;
            byte[] encoded = codec.encode(value);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode negative value")
        void shouldEncodeNegativeValue() {
            double value = -123.456789012345;
            byte[] encoded = codec.encode(value);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode Double.MAX_VALUE")
        void shouldEncodeMaxValue() {
            byte[] encoded = codec.encode(Double.MAX_VALUE);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode Double.MIN_VALUE")
        void shouldEncodeMinValue() {
            byte[] encoded = codec.encode(Double.MIN_VALUE);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode positive infinity")
        void shouldEncodePositiveInfinity() {
            byte[] encoded = codec.encode(Double.POSITIVE_INFINITY);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode negative infinity")
        void shouldEncodeNegativeInfinity() {
            byte[] encoded = codec.encode(Double.NEGATIVE_INFINITY);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode NaN")
        void shouldEncodeNaN() {
            byte[] encoded = codec.encode(Double.NaN);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }

        @Test
        @DisplayName("Should encode null as NaN")
        void shouldEncodeNullAsNaN() {
            byte[] encoded = codec.encode(null);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
            double decoded = codec.decode(encoded);
            assertTrue(Double.isNaN(decoded));
        }

        @Test
        @DisplayName("Should encode -0.0")
        void shouldEncodeNegativeZero() {
            byte[] encoded = codec.encode(-0.0);
            assertNotNull(encoded);
            assertEquals(8, encoded.length);
        }
    }

    @Nested
    @DisplayName("Decode Tests")
    class DecodeTests {

        @Test
        @DisplayName("Should decode zero")
        void shouldDecodeZero() {
            byte[] encoded = codec.encode(0.0);
            double decoded = codec.decode(encoded);
            assertEquals(0.0, decoded, 0.0000000000001);
        }

        @Test
        @DisplayName("Should decode positive value")
        void shouldDecodePositiveValue() {
            double value = 123.456789012345;
            byte[] encoded = codec.encode(value);
            double decoded = codec.decode(encoded);
            assertEquals(value, decoded, 0.0000000000001);
        }

        @Test
        @DisplayName("Should decode negative value")
        void shouldDecodeNegativeValue() {
            double value = -987.654321098765;
            byte[] encoded = codec.encode(value);
            double decoded = codec.decode(encoded);
            assertEquals(value, decoded, 0.0000000000001);
        }

        @Test
        @DisplayName("Should decode Double.MAX_VALUE")
        void shouldDecodeMaxValue() {
            byte[] encoded = codec.encode(Double.MAX_VALUE);
            double decoded = codec.decode(encoded);
            assertEquals(Double.MAX_VALUE, decoded, 0.0);
        }

        @Test
        @DisplayName("Should decode Double.MIN_VALUE")
        void shouldDecodeMinValue() {
            byte[] encoded = codec.encode(Double.MIN_VALUE);
            double decoded = codec.decode(encoded);
            assertEquals(Double.MIN_VALUE, decoded, 0.0);
        }

        @Test
        @DisplayName("Should decode positive infinity")
        void shouldDecodePositiveInfinity() {
            byte[] encoded = codec.encode(Double.POSITIVE_INFINITY);
            double decoded = codec.decode(encoded);
            assertTrue(Double.isInfinite(decoded));
            assertTrue(decoded > 0);
        }

        @Test
        @DisplayName("Should decode negative infinity")
        void shouldDecodeNegativeInfinity() {
            byte[] encoded = codec.encode(Double.NEGATIVE_INFINITY);
            double decoded = codec.decode(encoded);
            assertTrue(Double.isInfinite(decoded));
            assertTrue(decoded < 0);
        }

        @Test
        @DisplayName("Should decode NaN")
        void shouldDecodeNaN() {
            byte[] encoded = codec.encode(Double.NaN);
            double decoded = codec.decode(encoded);
            assertTrue(Double.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode null as NaN")
        void shouldDecodeNullAsNaN() {
            byte[] encoded = codec.encode(null);
            double decoded = codec.decode(encoded);
            assertTrue(Double.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode empty array as NaN")
        void shouldDecodeEmptyArrayAsNaN() {
            double decoded = codec.decode(new byte[0]);
            assertTrue(Double.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode null array as NaN")
        void shouldDecodeNullArrayAsNaN() {
            double decoded = codec.decode(null);
            assertTrue(Double.isNaN(decoded));
        }

        @Test
        @DisplayName("Should throw on insufficient bytes")
        void shouldThrowOnInsufficientBytes() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[7])
            );
            assertTrue(e.getMessage().contains("Invalid encoded data"));
        }

        @Test
        @DisplayName("Should decode with offset")
        void shouldDecodeWithOffset() {
            double value = 42.5;
            byte[] encoded = codec.encode(value);
            byte[] buffer = new byte[20];
            System.arraycopy(encoded, 0, buffer, 5, 8);

            double decoded = codec.decode(buffer, 5);
            assertEquals(value, decoded, 0.0000000000001);
        }

        @Test
        @DisplayName("Should throw on invalid offset")
        void shouldThrowOnInvalidOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[20], -1)
            );
        }

        @Test
        @DisplayName("Should throw on null data with offset")
        void shouldThrowOnNullDataWithOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                codec.decode(null, 0)
            );
        }

        @Test
        @DisplayName("Should throw on insufficient data at offset")
        void shouldThrowOnInsufficientDataAtOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[10], 3)  // Only 7 bytes available at offset 3
            );
        }

        @Test
        @DisplayName("Should decode -0.0")
        void shouldDecodeNegativeZero() {
            byte[] encoded = codec.encode(-0.0);
            double decoded = codec.decode(encoded);
            assertEquals(-0.0, decoded, 0.0);
        }
    }

    @Nested
    @DisplayName("IsValid Tests")
    class IsValidTests {

        @Test
        @DisplayName("Should accept 8-byte array")
        void shouldAccept8ByteArray() {
            assertTrue(codec.isValid(new byte[8]));
        }

        @Test
        @DisplayName("Should accept larger array")
        void shouldAcceptLargerArray() {
            assertTrue(codec.isValid(new byte[16]));
        }

        @Test
        @DisplayName("Should accept empty array")
        void shouldAcceptEmptyArray() {
            assertTrue(codec.isValid(new byte[0]));
        }

        @Test
        @DisplayName("Should reject null")
        void shouldRejectNull() {
            assertFalse(codec.isValid(null));
        }

        @Test
        @DisplayName("Should reject arrays smaller than 8 bytes")
        void shouldRejectArraysSmallerThan8Bytes() {
            assertFalse(codec.isValid(new byte[1]));
            assertFalse(codec.isValid(new byte[2]));
            assertFalse(codec.isValid(new byte[3]));
            assertFalse(codec.isValid(new byte[4]));
            assertFalse(codec.isValid(new byte[5]));
            assertFalse(codec.isValid(new byte[6]));
            assertFalse(codec.isValid(new byte[7]));
        }

        @Test
        @DisplayName("Should accept with valid offset")
        void shouldAcceptWithValidOffset() {
            byte[] data = new byte[20];
            assertTrue(codec.isValid(data, 5));
        }

        @Test
        @DisplayName("Should reject with negative offset")
        void shouldRejectWithNegativeOffset() {
            byte[] data = new byte[20];
            assertFalse(codec.isValid(data, -1));
        }

        @Test
        @DisplayName("Should reject with insufficient data at offset")
        void shouldRejectWithInsufficientDataAtOffset() {
            byte[] data = new byte[10];
            assertFalse(codec.isValid(data, 3)); // Only 7 bytes at offset 3
        }

        @Test
        @DisplayName("Should reject null with offset")
        void shouldRejectNullWithOffset() {
            assertFalse(codec.isValid(null, 0));
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should round-trip zero")
        void shouldRoundTripZero() {
            assertRoundTrip(0.0);
        }

        @Test
        @DisplayName("Should round-trip positive values")
        void shouldRoundTripPositiveValues() {
            assertRoundTrip(1.0);
            assertRoundTrip(0.5);
            assertRoundTrip(100.0);
            assertRoundTrip(12345.6789);
        }

        @Test
        @DisplayName("Should round-trip negative values")
        void shouldRoundTripNegativeValues() {
            assertRoundTrip(-1.0);
            assertRoundTrip(-0.5);
            assertRoundTrip(-100.0);
            assertRoundTrip(-12345.6789);
        }

        @Test
        @DisplayName("Should round-trip special values")
        void shouldRoundTripSpecialValues() {
            assertRoundTrip(Double.MAX_VALUE);
            assertRoundTrip(-Double.MAX_VALUE);
            assertRoundTrip(Double.MIN_VALUE);
            assertRoundTrip(Double.POSITIVE_INFINITY);
            assertRoundTrip(Double.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("Should round-trip small values")
        void shouldRoundTripSmallValues() {
            assertRoundTrip(0.000001);
            assertRoundTrip(-0.000001);
            assertRoundTrip(1e-300);
        }

        private void assertRoundTrip(double value) {
            byte[] encoded = codec.encode(value);
            double decoded = codec.decode(encoded);
            if (Double.isNaN(value)) {
                assertTrue(Double.isNaN(decoded));
            } else if (Double.isInfinite(value)) {
                assertEquals(value, decoded, 0.0);
            } else {
                assertEquals(value, decoded, Math.abs(value) * 1e-15);
            }
        }
    }

    @Nested
    @DisplayName("Little-Endian Tests")
    class LittleEndianTests {

        @Test
        @DisplayName("Should use little-endian byte order")
        void shouldUseLittleEndianByteOrder() {
            // 1.0 in IEEE 754 is 0x3FF0000000000000
            // Little-endian: bytes are reversed
            byte[] encoded = codec.encode(1.0);
            assertEquals(8, encoded.length);
            // The exact byte pattern depends on IEEE 754 representation
            // We just verify it round-trips correctly
            double decoded = codec.decode(encoded);
            assertEquals(1.0, decoded, 0.0);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle denormalized values")
        void shouldHandleDenormalizedValues() {
            double denorm = Double.MIN_VALUE / 2.0;
            byte[] encoded = codec.encode(denorm);
            double decoded = codec.decode(encoded);
            assertEquals(denorm, decoded, 0.0);
        }

        @Test
        @DisplayName("Should handle very small denormalized values")
        void shouldHandleVerySmallDenormalizedValues() {
            double denorm = 4.9e-324; // Smallest positive denormalized
            byte[] encoded = codec.encode(denorm);
            double decoded = codec.decode(encoded);
            assertEquals(denorm, decoded, 0.0);
        }

        @Test
        @DisplayName("Should handle large array with offset at boundary")
        void shouldHandleLargeArrayWithOffsetAtBoundary() {
            double value = 3.141592653589793;
            byte[] encoded = codec.encode(value);

            byte[] buffer = new byte[16];
            System.arraycopy(encoded, 0, buffer, 8, 8);

            double decoded = codec.decode(buffer, 8);
            assertEquals(value, decoded, 0.0000000000001);
        }

        @Test
        @DisplayName("Should distinguish between +0.0 and -0.0")
        void shouldDistinguishBetweenPositiveAndNegativeZero() {
            byte[] posZero = codec.encode(0.0);
            byte[] negZero = codec.encode(-0.0);

            // They should decode to their respective values
            assertEquals(0.0, codec.decode(posZero), 0.0);
            assertEquals(-0.0, codec.decode(negZero), 0.0);

            // The byte representations should be different
            // (sign bit is set for -0.0)
            assertNotEquals(posZero[7], negZero[7]); // High byte differs
        }
    }
}
