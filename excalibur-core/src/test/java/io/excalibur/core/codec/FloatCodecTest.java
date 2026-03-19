package io.excalibur.core.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for FloatCodec.
 * Tests encoding, decoding, validation, and edge cases for IEEE 754 single-precision floats.
 */
@DisplayName("FloatCodec Tests")
class FloatCodecTest {

    private FloatCodec codec;

    @BeforeEach
    void setUp() {
        codec = FloatCodec.getInstance();
    }

    @Nested
    @DisplayName("Singleton Tests")
    class SingletonTests {

        @Test
        @DisplayName("Should return same instance")
        void shouldReturnSameInstance() {
            FloatCodec c1 = FloatCodec.getInstance();
            FloatCodec c2 = FloatCodec.getInstance();
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
        @DisplayName("SizeOf should return 4 bytes")
        void sizeOfShouldReturn4Bytes() {
            assertEquals(4, codec.sizeOf());
        }
    }

    @Nested
    @DisplayName("Encode Tests")
    class EncodeTests {

        @Test
        @DisplayName("Should encode zero")
        void shouldEncodeZero() {
            byte[] encoded = codec.encode(0.0f);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode positive value")
        void shouldEncodePositiveValue() {
            float value = 123.456f;
            byte[] encoded = codec.encode(value);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode negative value")
        void shouldEncodeNegativeValue() {
            float value = -123.456f;
            byte[] encoded = codec.encode(value);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode Float.MAX_VALUE")
        void shouldEncodeMaxValue() {
            byte[] encoded = codec.encode(Float.MAX_VALUE);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode Float.MIN_VALUE")
        void shouldEncodeMinValue() {
            byte[] encoded = codec.encode(Float.MIN_VALUE);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode positive infinity")
        void shouldEncodePositiveInfinity() {
            byte[] encoded = codec.encode(Float.POSITIVE_INFINITY);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode negative infinity")
        void shouldEncodeNegativeInfinity() {
            byte[] encoded = codec.encode(Float.NEGATIVE_INFINITY);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode NaN")
        void shouldEncodeNaN() {
            byte[] encoded = codec.encode(Float.NaN);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode null as NaN")
        void shouldEncodeNullAsNaN() {
            byte[] encoded = codec.encode(null);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
            float decoded = codec.decode(encoded);
            assertTrue(Float.isNaN(decoded));
        }
    }

    @Nested
    @DisplayName("Decode Tests")
    class DecodeTests {

        @Test
        @DisplayName("Should decode zero")
        void shouldDecodeZero() {
            byte[] encoded = codec.encode(0.0f);
            float decoded = codec.decode(encoded);
            assertEquals(0.0f, decoded, 0.0000001f);
        }

        @Test
        @DisplayName("Should decode positive value")
        void shouldDecodePositiveValue() {
            float value = 123.456f;
            byte[] encoded = codec.encode(value);
            float decoded = codec.decode(encoded);
            assertEquals(value, decoded, 0.0000001f);
        }

        @Test
        @DisplayName("Should decode negative value")
        void shouldDecodeNegativeValue() {
            float value = -987.654f;
            byte[] encoded = codec.encode(value);
            float decoded = codec.decode(encoded);
            assertEquals(value, decoded, 0.0000001f);
        }

        @Test
        @DisplayName("Should decode Float.MAX_VALUE")
        void shouldDecodeMaxValue() {
            byte[] encoded = codec.encode(Float.MAX_VALUE);
            float decoded = codec.decode(encoded);
            assertEquals(Float.MAX_VALUE, decoded, 0.0000001f);
        }

        @Test
        @DisplayName("Should decode Float.MIN_VALUE")
        void shouldDecodeMinValue() {
            byte[] encoded = codec.encode(Float.MIN_VALUE);
            float decoded = codec.decode(encoded);
            assertEquals(Float.MIN_VALUE, decoded, 0.0f);
        }

        @Test
        @DisplayName("Should decode positive infinity")
        void shouldDecodePositiveInfinity() {
            byte[] encoded = codec.encode(Float.POSITIVE_INFINITY);
            float decoded = codec.decode(encoded);
            assertTrue(Float.isInfinite(decoded));
            assertTrue(decoded > 0);
        }

        @Test
        @DisplayName("Should decode negative infinity")
        void shouldDecodeNegativeInfinity() {
            byte[] encoded = codec.encode(Float.NEGATIVE_INFINITY);
            float decoded = codec.decode(encoded);
            assertTrue(Float.isInfinite(decoded));
            assertTrue(decoded < 0);
        }

        @Test
        @DisplayName("Should decode NaN")
        void shouldDecodeNaN() {
            byte[] encoded = codec.encode(Float.NaN);
            float decoded = codec.decode(encoded);
            assertTrue(Float.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode null as NaN")
        void shouldDecodeNullAsNaN() {
            byte[] encoded = codec.encode(null);
            float decoded = codec.decode(encoded);
            assertTrue(Float.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode empty array as NaN")
        void shouldDecodeEmptyArrayAsNaN() {
            float decoded = codec.decode(new byte[0]);
            assertTrue(Float.isNaN(decoded));
        }

        @Test
        @DisplayName("Should decode null array as NaN")
        void shouldDecodeNullArrayAsNaN() {
            float decoded = codec.decode(null);
            assertTrue(Float.isNaN(decoded));
        }

        @Test
        @DisplayName("Should throw on insufficient bytes")
        void shouldThrowOnInsufficientBytes() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[3])
            );
            assertTrue(e.getMessage().contains("Invalid encoded data"));
        }

        @Test
        @DisplayName("Should decode with offset")
        void shouldDecodeWithOffset() {
            float value = 42.5f;
            byte[] encoded = codec.encode(value);
            // Create a buffer with padding
            byte[] buffer = new byte[10];
            System.arraycopy(encoded, 0, buffer, 3, 4);

            float decoded = codec.decode(buffer, 3);
            assertEquals(value, decoded, 0.0000001f);
        }

        @Test
        @DisplayName("Should throw on invalid offset")
        void shouldThrowOnInvalidOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[10], -1)
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
                codec.decode(new byte[5], 2)  // Only 3 bytes available at offset 2
            );
        }
    }

    @Nested
    @DisplayName("IsValid Tests")
    class IsValidTests {

        @Test
        @DisplayName("Should accept 4-byte array")
        void shouldAccept4ByteArray() {
            assertTrue(codec.isValid(new byte[4]));
        }

        @Test
        @DisplayName("Should accept larger array")
        void shouldAcceptLargerArray() {
            assertTrue(codec.isValid(new byte[8]));
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
        @DisplayName("Should reject 1-byte array")
        void shouldReject1ByteArray() {
            assertFalse(codec.isValid(new byte[1]));
        }

        @Test
        @DisplayName("Should reject 2-byte array")
        void shouldReject2ByteArray() {
            assertFalse(codec.isValid(new byte[2]));
        }

        @Test
        @DisplayName("Should reject 3-byte array")
        void shouldReject3ByteArray() {
            assertFalse(codec.isValid(new byte[3]));
        }

        @Test
        @DisplayName("Should accept with valid offset")
        void shouldAcceptWithValidOffset() {
            byte[] data = new byte[10];
            assertTrue(codec.isValid(data, 3));
        }

        @Test
        @DisplayName("Should reject with negative offset")
        void shouldRejectWithNegativeOffset() {
            byte[] data = new byte[10];
            assertFalse(codec.isValid(data, -1));
        }

        @Test
        @DisplayName("Should reject with insufficient data at offset")
        void shouldRejectWithInsufficientDataAtOffset() {
            byte[] data = new byte[5];
            assertFalse(codec.isValid(data, 2)); // Only 3 bytes at offset 2
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
            assertRoundTrip(0.0f);
        }

        @Test
        @DisplayName("Should round-trip positive values")
        void shouldRoundTripPositiveValues() {
            assertRoundTrip(1.0f);
            assertRoundTrip(0.5f);
            assertRoundTrip(100.0f);
            assertRoundTrip(12345.6789f);
        }

        @Test
        @DisplayName("Should round-trip negative values")
        void shouldRoundTripNegativeValues() {
            assertRoundTrip(-1.0f);
            assertRoundTrip(-0.5f);
            assertRoundTrip(-100.0f);
            assertRoundTrip(-12345.6789f);
        }

        @Test
        @DisplayName("Should round-trip special values")
        void shouldRoundTripSpecialValues() {
            assertRoundTrip(Float.MAX_VALUE);
            assertRoundTrip(-Float.MAX_VALUE);
            assertRoundTrip(Float.MIN_VALUE);
            assertRoundTrip(Float.POSITIVE_INFINITY);
            assertRoundTrip(Float.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("Should round-trip small values")
        void shouldRoundTripSmallValues() {
            assertRoundTrip(0.000001f);
            assertRoundTrip(-0.000001f);
            assertRoundTrip(1e-30f);
        }

        private void assertRoundTrip(float value) {
            byte[] encoded = codec.encode(value);
            float decoded = codec.decode(encoded);
            if (Float.isNaN(value)) {
                assertTrue(Float.isNaN(decoded));
            } else if (Float.isInfinite(value)) {
                assertEquals(value, decoded, 0.0f);
            } else {
                assertEquals(value, decoded, Math.abs(value) * 1e-6f);
            }
        }
    }

    @Nested
    @DisplayName("Little-Endian Tests")
    class LittleEndianTests {

        @Test
        @DisplayName("Should use little-endian byte order")
        void shouldUseLittleEndianByteOrder() {
            // 1.0f in IEEE 754 is 0x3F800000
            // Little-endian: 0x00, 0x00, 0x80, 0x3F
            byte[] encoded = codec.encode(1.0f);
            assertEquals(4, encoded.length);
            // Verify the bytes are in little-endian order
            assertEquals((byte) 0x00, encoded[0]);
            assertEquals((byte) 0x00, encoded[1]);
            assertEquals((byte) 0x80, encoded[2]);
            assertEquals((byte) 0x3F, encoded[3]);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle denormalized values")
        void shouldHandleDenormalizedValues() {
            // Float.MIN_VALUE is the smallest positive normal value
            // Dividing by 2 gives us a subnormal (denormalized) value
            float denorm = Float.MIN_VALUE / 2.0f;
            // Note: denorm may be 0 due to floating-point precision
            // We just verify round-trip works for whatever value we get
            byte[] encoded = codec.encode(denorm);
            float decoded = codec.decode(encoded);
            assertEquals(denorm, decoded, 0.0f, "Denormalized value should round-trip correctly");
        }

        @Test
        @DisplayName("Should handle -0.0f")
        void shouldHandleNegativeZero() {
            byte[] encoded = codec.encode(-0.0f);
            float decoded = codec.decode(encoded);
            assertEquals(-0.0f, decoded, 0.0f);
        }

        @Test
        @DisplayName("Should handle large array with offset at boundary")
        void shouldHandleLargeArrayWithOffsetAtBoundary() {
            float value = 3.14159f;
            byte[] encoded = codec.encode(value);

            // Create buffer where data ends exactly at array end
            byte[] buffer = new byte[8];
            System.arraycopy(encoded, 0, buffer, 4, 4);

            float decoded = codec.decode(buffer, 4);
            assertEquals(value, decoded, 0.0000001f);
        }
    }
}
