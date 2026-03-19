package io.excalibur.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CRC32 utility.
 */
@DisplayName("CRC32 Tests")
class CRC32Test {

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
            assertThat(crc).isNotZero();
        }

        @Test
        @DisplayName("Should compute partial array")
        void shouldComputePartialArray() {
            byte[] data = "Hello World".getBytes();
            long crc = CRC32.crc32(data, 0, 5);
            assertThat(crc).isNotZero();
        }
    }

    @Nested
    @DisplayName("MemorySegment Tests")
    class MemorySegmentTests {

        @Test
        @DisplayName("Should compute CRC32 for MemorySegment")
        void shouldComputeForMemorySegment() {
            byte[] data = "123456789".getBytes();
            java.lang.foreign.MemorySegment segment = Arena.ofAuto().allocate(data.length);
            segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
            long crc = CRC32.crc32(segment);
            assertThat(crc).isNotZero();
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
