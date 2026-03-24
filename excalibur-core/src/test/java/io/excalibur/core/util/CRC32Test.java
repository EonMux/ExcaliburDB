package io.excalibur.core.util;

import io.excalibur.core.checksum.CRC32Checksum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CRC32 utility.
 */
@DisplayName("CRC32 Tests")
class CRC32Test {

    @Nested
    @DisplayName("IEEE 802.3 Standard Test Vectors")
    class StandardTestVectors {

        @Test
        @DisplayName("Should match known CRC32 test vector: Hello World")
        void shouldMatchHelloWorldTestVector() {
            byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
            assertThat(CRC32.crc32(data)).isEqualTo(0x4A17B156L);
        }

        @Test
        @DisplayName("Should match known CRC32 test vector: empty")
        void shouldMatchEmptyTestVector() {
            assertThat(CRC32.crc32(new byte[0])).isEqualTo(0x00000000L);
        }

        @Test
        @DisplayName("Should match known CRC32 test vector: hello")
        void shouldMatchHelloTestVector() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            assertThat(CRC32.crc32(data)).isEqualTo(0x3610A686L);
        }

        @Test
        @DisplayName("Should match known CRC32 test vector: pangram")
        void shouldMatchPangramTestVector() {
            byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
            assertThat(CRC32.crc32(data)).isEqualTo(0x414FA339L);
        }

        @Test
        @DisplayName("Should match known CRC32 test vector: single byte 0x00")
        void shouldMatchSingleByteZeroTestVector() {
            assertThat(CRC32.crc32(new byte[]{0x00})).isEqualTo(0xD202EF8DL);
        }

        @Test
        @DisplayName("Should match known CRC32 test vector: single byte 0xFF")
        void shouldMatchSingleByteFFTestVector() {
            assertThat(CRC32.crc32(new byte[]{(byte) 0xFF})).isEqualTo(0xFF000000L);
        }

        @Test
        @DisplayName("Should match CRC32Checksum implementation")
        void shouldMatchCRC32Checksum() {
            byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
            assertThat(CRC32.crc32(data)).isEqualTo(CRC32Checksum.compute(data));
        }

        @Test
        @DisplayName("Should match CRC32Checksum for empty input")
        void shouldMatchCRC32ChecksumEmpty() {
            assertThat(CRC32.crc32(new byte[0])).isEqualTo(CRC32Checksum.compute(new byte[0]));
        }

        @Test
        @DisplayName("Should match CRC32Checksum for various inputs")
        void shouldMatchCRC32ChecksumVariousInputs() {
            String[] testInputs = {
                "a",
                "ab",
                "abc",
                "123456789",
                "Hello World",
                "The quick brown fox jumps over the lazy dog",
                new String(new char[1000]).replace('\0', 'x') // Long string
            };

            for (String input : testInputs) {
                byte[] data = input.getBytes(StandardCharsets.UTF_8);
                assertThat(CRC32.crc32(data))
                    .as("CRC32 mismatch for input: " + input.substring(0, Math.min(20, input.length())) + "...")
                    .isEqualTo(CRC32Checksum.compute(data));
            }
        }
    }

    @Nested
    @DisplayName("ByteArray Tests")
    class ByteArrayTests {

        @Test
        @DisplayName("Should compute CRC32 for single byte")
        void shouldComputeSingleByte() {
            byte[] data = {0x41};
            long crc = CRC32.crc32(data);
            assertThat(crc).isNotZero();
        }

        @Test
        @DisplayName("Should compute CRC32 for known string")
        void shouldComputeKnownString() {
            byte[] data = "123456789".getBytes();
            long crc = CRC32.crc32(data);
            assertThat(crc).isNotZero();
        }

        @Test
        @DisplayName("Should compute CRC32 for longer data")
        void shouldComputeLongerData() {
            byte[] data = "The quick brown fox jumps over the lazy dog".getBytes();
            long crc = CRC32.crc32(data);
            assertThat(crc).isNotZero();
        }

        @Test
        @DisplayName("Should produce consistent results")
        void shouldProduceConsistentResults() {
            byte[] data = "test data for consistency".getBytes();
            long crc1 = CRC32.crc32(data);
            long crc2 = CRC32.crc32(data);
            assertThat(crc1).isEqualTo(crc2);
        }
    }

    @Nested
    @DisplayName("ByteArray with Offset Tests")
    class ByteArrayOffsetTests {

        @Test
        @DisplayName("Should compute with offset and length")
        void shouldComputeWithOffsetAndLength() {
            byte[] data = "XX123456789YY".getBytes();
            long crc = CRC32.crc32(data, 2, 9);
            // "123456789" has CRC32 = 0xCBF43926L
            assertThat(crc).isEqualTo(0xCBF43926L);
        }

        @Test
        @DisplayName("Should compute partial array")
        void shouldComputePartialArray() {
            byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
            // "Hello" has CRC32 = 0xF7D18982L
            long crc = CRC32.crc32(data, 0, 5);
            assertThat(crc).isEqualTo(0xF7D18982L);
        }

        @Test
        @DisplayName("Should produce same result as full array when offset=0, length=array.length")
        void shouldMatchFullArray() {
            byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
            assertThat(CRC32.crc32(data, 0, data.length)).isEqualTo(CRC32.crc32(data));
        }

        @Test
        @DisplayName("Should throw NullPointerException for null data")
        void shouldThrowForNullData() {
            assertThatThrownBy(() -> CRC32.crc32((byte[]) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null");
        }

        @Test
        @DisplayName("Should throw NullPointerException for null data with offset")
        void shouldThrowForNullDataWithOffset() {
            assertThatThrownBy(() -> CRC32.crc32((byte[]) null, 0, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null");
        }

        @Test
        @DisplayName("Should throw IndexOutOfBoundsException for negative offset")
        void shouldThrowForNegativeOffset() {
            byte[] data = "test".getBytes();
            assertThatThrownBy(() -> CRC32.crc32(data, -1, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should throw IndexOutOfBoundsException for negative length")
        void shouldThrowForNegativeLength() {
            byte[] data = "test".getBytes();
            assertThatThrownBy(() -> CRC32.crc32(data, 0, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should throw IndexOutOfBoundsException for offset+length exceeds array")
        void shouldThrowForExceedingLength() {
            byte[] data = "test".getBytes();
            assertThatThrownBy(() -> CRC32.crc32(data, 0, 100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("MemorySegment Tests")
    class MemorySegmentTests {

        @Test
        @DisplayName("Should compute CRC32 for MemorySegment")
        void shouldComputeForMemorySegment() {
            byte[] data = "123456789".getBytes(StandardCharsets.UTF_8);
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
            long crc = CRC32.crc32(segment);
            // "123456789" has CRC32 = 0xCBF43926L
            assertThat(crc).isEqualTo(0xCBF43926L);
        }

        @Test
        @DisplayName("Should match byte array CRC32 for same data")
        void shouldMatchByteArrayCrc32() {
            byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));

            assertThat(CRC32.crc32(segment)).isEqualTo(CRC32.crc32(data));
        }

        @Test
        @DisplayName("Should compute with offset and length")
        void shouldComputeWithOffsetAndLength() {
            byte[] data = "XX123456789YY".getBytes(StandardCharsets.UTF_8);
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));

            long crc = CRC32.crc32(segment, 2, 9);
            // "123456789" has CRC32 = 0xCBF43926L
            assertThat(crc).isEqualTo(0xCBF43926L);
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> CRC32.crc32((java.lang.foreign.MemorySegment) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null segment with offset")
        void shouldRejectNullSegmentWithOffset() {
            assertThatThrownBy(() -> CRC32.crc32((java.lang.foreign.MemorySegment) null, 0, 5))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject invalid range")
        void shouldRejectInvalidRange() {
            byte[] data = "short".getBytes();
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
            assertThatThrownBy(() -> CRC32.crc32(segment, 0, 100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should reject negative offset")
        void shouldRejectNegativeOffset() {
            byte[] data = "test".getBytes();
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
            assertThatThrownBy(() -> CRC32.crc32(segment, -1, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should reject negative length")
        void shouldRejectNegativeLength() {
            byte[] data = "test".getBytes();
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
            assertThatThrownBy(() -> CRC32.crc32(segment, 0, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("Determinism Tests")
    class DeterminismTests {

        @Test
        @DisplayName("Should produce same result for same input")
        void shouldProduceSameResultForSameInput() {
            byte[] data1 = "test data".getBytes();
            byte[] data2 = "test data".getBytes();
            assertThat(CRC32.crc32(data1)).isEqualTo(CRC32.crc32(data2));
        }

        @Test
        @DisplayName("Should produce different result for different input")
        void shouldProduceDifferentResultForDifferentInput() {
            byte[] data1 = "test data 1".getBytes();
            byte[] data2 = "test data 2".getBytes();
            assertThat(CRC32.crc32(data1)).isNotEqualTo(CRC32.crc32(data2));
        }
    }
}
