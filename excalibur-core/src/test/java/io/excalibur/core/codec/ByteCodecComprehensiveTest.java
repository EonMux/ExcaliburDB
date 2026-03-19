package io.excalibur.core.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ByteCodec to improve coverage.
 */
@DisplayName("ByteCodec Comprehensive Tests")
class ByteCodecComprehensiveTest {

    private ByteCodec codec;

    @BeforeEach
    void setUp() {
        codec = ByteCodec.getInstance();
    }

    @Nested
    @DisplayName("Singleton Tests")
    class SingletonTests {

        @Test
        @DisplayName("Should return same instance")
        void shouldReturnSameInstance() {
            ByteCodec c1 = ByteCodec.getInstance();
            ByteCodec c2 = ByteCodec.getInstance();
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
        @DisplayName("SizeOf should return minimum size (4 bytes for length prefix)")
        void sizeOfShouldReturnMinimumSize() {
            assertEquals(4, codec.sizeOf());
        }

        @Test
        @DisplayName("EncodedSize for empty array should be 4")
        void encodedSizeForEmptyArrayShouldBe4() {
            assertEquals(4, codec.encodedSize(new byte[0]));
        }

        @Test
        @DisplayName("EncodedSize for null should be 4")
        void encodedSizeForNullShouldBe4() {
            assertEquals(4, codec.encodedSize(null));
        }

        @Test
        @DisplayName("EncodedSize for non-empty array should include data")
        void encodedSizeForNonEmptyArrayShouldIncludeData() {
            byte[] data = new byte[100];
            assertEquals(104, codec.encodedSize(data)); // 4 + 100
        }
    }

    @Nested
    @DisplayName("Encode Tests")
    class EncodeTests {

        @Test
        @DisplayName("Should encode null as empty with length prefix")
        void shouldEncodeNullAsEmptyWithLengthPrefix() {
            byte[] encoded = codec.encode(null);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
            // Length should be 0 in little-endian
            assertEquals(0, encoded[0]);
            assertEquals(0, encoded[1]);
            assertEquals(0, encoded[2]);
            assertEquals(0, encoded[3]);
        }

        @Test
        @DisplayName("Should encode empty array")
        void shouldEncodeEmptyArray() {
            byte[] encoded = codec.encode(new byte[0]);
            assertNotNull(encoded);
            assertEquals(4, encoded.length);
        }

        @Test
        @DisplayName("Should encode single byte")
        void shouldEncodeSingleByte() {
            byte[] data = {42};
            byte[] encoded = codec.encode(data);
            assertNotNull(encoded);
            assertEquals(5, encoded.length); // 4 + 1
            // Check length prefix (little-endian)
            assertEquals(1, encoded[0]);
            assertEquals(0, encoded[1]);
            assertEquals(0, encoded[2]);
            assertEquals(0, encoded[3]);
            // Check data
            assertEquals(42, encoded[4]);
        }

        @Test
        @DisplayName("Should encode large array")
        void shouldEncodeLargeArray() {
            byte[] data = new byte[10000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }
            byte[] encoded = codec.encode(data);
            assertNotNull(encoded);
            assertEquals(10004, encoded.length); // 4 + 10000
        }

        @Test
        @DisplayName("Should use little-endian for length prefix")
        void shouldUseLittleEndianForLengthPrefix() {
            // 256 in little-endian is 0x00, 0x01, 0x00, 0x00
            byte[] data = new byte[256];
            byte[] encoded = codec.encode(data);
            assertEquals(0, encoded[0]); // low byte
            assertEquals(1, encoded[1]); // high byte
            assertEquals(0, encoded[2]);
            assertEquals(0, encoded[3]);
        }
    }

    @Nested
    @DisplayName("Decode Tests")
    class DecodeTests {

        @Test
        @DisplayName("Should decode empty with length prefix")
        void shouldDecodeEmptyWithLengthPrefix() {
            byte[] encoded = codec.encode(null);
            byte[] decoded = codec.decode(encoded);
            assertArrayEquals(new byte[0], decoded);
        }

        @Test
        @DisplayName("Should decode single byte")
        void shouldDecodeSingleByte() {
            byte[] data = {42};
            byte[] encoded = codec.encode(data);
            byte[] decoded = codec.decode(encoded);
            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Should decode with offset")
        void shouldDecodeWithOffset() {
            byte[] data = {1, 2, 3, 4, 5};
            byte[] encoded = codec.encode(data);

            // Create a buffer with padding
            byte[] buffer = new byte[20];
            System.arraycopy(encoded, 0, buffer, 5, encoded.length);

            byte[] decoded = codec.decode(buffer, 5);
            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Should throw on invalid data")
        void shouldThrowOnInvalidData() {
            // Less than 4 bytes
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(new byte[3])
            );
            assertTrue(e.getMessage().contains("Invalid encoded data"));
        }

        @Test
        @DisplayName("Should throw on invalid offset")
        void shouldThrowOnInvalidOffset() {
            byte[] data = {1, 2, 3, 4, 5};
            byte[] encoded = codec.encode(data);

            // Not enough bytes at offset
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(encoded, 3)
            );
            assertTrue(e.getMessage().contains("Invalid encoded data"));
        }

        @Test
        @DisplayName("Should return empty array for null data")
        void shouldReturnEmptyArrayForNullData() {
            // ByteCodec.decode(null) returns empty array, not exception
            byte[] decoded = codec.decode(null);
            assertArrayEquals(new byte[0], decoded);
        }

        @Test
        @DisplayName("Should throw on null data with offset")
        void shouldThrowOnNullDataWithOffset() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(null, 0)
            );
            assertTrue(e.getMessage().contains("Invalid data or offset"));
        }

        @Test
        @DisplayName("Should throw on negative offset")
        void shouldThrowOnNegativeOffset() {
            byte[] data = {1, 2, 3, 4, 5};
            byte[] encoded = codec.encode(data);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                codec.decode(encoded, -1)
            );
            assertTrue(e.getMessage().contains("Invalid data or offset"));
        }
    }

    @Nested
    @DisplayName("IsValid Tests")
    class IsValidTests {

        @Test
        @DisplayName("Should accept valid data")
        void shouldAcceptValidData() {
            byte[] data = {1, 2, 3, 4, 5};
            byte[] encoded = codec.encode(data);
            assertTrue(codec.isValid(encoded));
        }

        @Test
        @DisplayName("Should accept empty with length prefix")
        void shouldAcceptEmptyWithLengthPrefix() {
            byte[] encoded = codec.encode(new byte[0]);
            assertTrue(codec.isValid(encoded));
        }

        @Test
        @DisplayName("Should reject null")
        void shouldRejectNull() {
            assertFalse(codec.isValid(null));
        }

        @Test
        @DisplayName("Should reject less than 4 bytes (except empty)")
        void shouldRejectLessThan4Bytes() {
            // Empty array is valid (decodes to empty byte[])
            assertTrue(codec.isValid(new byte[0]));
            // Arrays with 1-3 bytes are invalid
            assertFalse(codec.isValid(new byte[1]));
            assertFalse(codec.isValid(new byte[2]));
            assertFalse(codec.isValid(new byte[3]));
        }

        @Test
        @DisplayName("Should accept exactly 4 bytes")
        void shouldAcceptExactly4Bytes() {
            assertTrue(codec.isValid(new byte[4]));
        }

        @Test
        @DisplayName("Should validate with offset")
        void shouldValidateWithOffset() {
            byte[] data = {1, 2, 3, 4, 5};
            byte[] encoded = codec.encode(data);

            byte[] buffer = new byte[20];
            System.arraycopy(encoded, 0, buffer, 5, encoded.length);

            assertTrue(codec.isValid(buffer, 5));
        }

        @Test
        @DisplayName("Should reject with insufficient offset")
        void shouldRejectWithInsufficientOffset() {
            byte[] buffer = new byte[10];
            // Only 3 bytes available at offset 7
            assertFalse(codec.isValid(buffer, 7));
        }

        @Test
        @DisplayName("Should reject null with offset")
        void shouldRejectNullWithOffset() {
            assertFalse(codec.isValid(null, 0));
        }

        @Test
        @DisplayName("Should reject negative offset")
        void shouldRejectNegativeOffset() {
            assertFalse(codec.isValid(new byte[10], -1));
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should round-trip various sizes")
        void shouldRoundTripVariousSizes() {
            int[] sizes = {0, 1, 4, 16, 64, 256, 1024, 4096};

            for (int size : sizes) {
                byte[] data = new byte[size];
                for (int i = 0; i < size; i++) {
                    data[i] = (byte) (i % 256);
                }

                byte[] encoded = codec.encode(data);
                byte[] decoded = codec.decode(encoded);

                assertArrayEquals(data, decoded, "Failed for size " + size);
            }
        }

        @Test
        @DisplayName("Should round-trip with all byte values")
        void shouldRoundTripWithAllByteValues() {
            byte[] data = new byte[256];
            for (int i = 0; i < 256; i++) {
                data[i] = (byte) i;
            }

            byte[] encoded = codec.encode(data);
            byte[] decoded = codec.decode(encoded);

            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Should round-trip with offset")
        void shouldRoundTripWithOffset() {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            byte[] encoded = codec.encode(data);

            byte[] buffer = new byte[50];
            System.arraycopy(encoded, 0, buffer, 10, encoded.length);

            byte[] decoded = codec.decode(buffer, 10);
            assertArrayEquals(data, decoded);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle length prefix at max int value boundary")
        void shouldHandleLengthPrefixAtMaxIntValueBoundary() {
            // Test with a large but reasonable size
            byte[] data = new byte[100000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }

            byte[] encoded = codec.encode(data);
            byte[] decoded = codec.decode(encoded);

            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Should handle byte array with max byte values")
        void shouldHandleByteArrayWithMaxByteValues() {
            byte[] data = new byte[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = Byte.MAX_VALUE;
            }

            byte[] encoded = codec.encode(data);
            byte[] decoded = codec.decode(encoded);

            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Should handle byte array with min byte values")
        void shouldHandleByteArrayWithMinByteValues() {
            byte[] data = new byte[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = Byte.MIN_VALUE;
            }

            byte[] encoded = codec.encode(data);
            byte[] decoded = codec.decode(encoded);

            assertArrayEquals(data, decoded);
        }
    }
}
