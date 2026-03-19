package io.excalibur.core.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SegmentVarHandle utility.
 */
@DisplayName("SegmentVarHandle Tests")
class SegmentVarHandleTest {

    private MemorySegment segment;
    private static final int SEGMENT_SIZE = 1024;

    @BeforeEach
    void setUp() {
        segment = Arena.ofAuto().allocate(SEGMENT_SIZE);
    }

    @Nested
    @DisplayName("Int Read/Write Tests")
    class IntTests {

        @Test
        @DisplayName("Should write and read int")
        void shouldWriteAndReadInt() {
            SegmentVarHandle.writeInt(segment, 0, 12345);
            int value = SegmentVarHandle.readInt(segment, 0);
            assertThat(value).isEqualTo(12345);
        }

        @Test
        @DisplayName("Should write int at non-zero offset")
        void shouldWriteIntAtOffset() {
            SegmentVarHandle.writeInt(segment, 100, 999);
            assertThat(SegmentVarHandle.readInt(segment, 100)).isEqualTo(999);
        }

        @Test
        @DisplayName("Should reject null segment for int write")
        void shouldRejectNullSegmentForIntWrite() {
            assertThatThrownBy(() -> SegmentVarHandle.writeInt(null, 0, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should reject null segment for int read")
        void shouldRejectNullSegmentForIntRead() {
            assertThatThrownBy(() -> SegmentVarHandle.readInt(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }
    }

    @Nested
    @DisplayName("Long Read/Write Tests")
    class LongTests {

        @Test
        @DisplayName("Should write and read long")
        void shouldWriteAndReadLong() {
            SegmentVarHandle.writeLong(segment, 0, 9876543210L);
            assertThat(SegmentVarHandle.readLong(segment, 0)).isEqualTo(9876543210L);
        }

        @Test
        @DisplayName("Should reject null segment for long write")
        void shouldRejectNullSegmentForLongWrite() {
            assertThatThrownBy(() -> SegmentVarHandle.writeLong(null, 0, 1L))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null segment for long read")
        void shouldRejectNullSegmentForLongRead() {
            assertThatThrownBy(() -> SegmentVarHandle.readLong(null, 0))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Float Read/Write Tests")
    class FloatTests {

        @Test
        @DisplayName("Should write and read float")
        void shouldWriteAndReadFloat() {
            SegmentVarHandle.writeFloat(segment, 0, 3.14159f);
            assertThat(SegmentVarHandle.readFloat(segment, 0)).isEqualTo(3.14159f);
        }

        @Test
        @DisplayName("Should reject null segment for float operations")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> SegmentVarHandle.writeFloat(null, 0, 1.0f))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> SegmentVarHandle.readFloat(null, 0))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Double Read/Write Tests")
    class DoubleTests {

        @Test
        @DisplayName("Should write and read double")
        void shouldWriteAndReadDouble() {
            SegmentVarHandle.writeDouble(segment, 0, 3.14159265358979);
            assertThat(SegmentVarHandle.readDouble(segment, 0)).isEqualTo(3.14159265358979);
        }

        @Test
        @DisplayName("Should reject null segment for double operations")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> SegmentVarHandle.writeDouble(null, 0, 1.0))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> SegmentVarHandle.readDouble(null, 0))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Byte Read/Write Tests")
    class ByteTests {

        @Test
        @DisplayName("Should write and read byte")
        void shouldWriteAndReadByte() {
            SegmentVarHandle.writeByte(segment, 0, (byte) 127);
            assertThat(SegmentVarHandle.readByte(segment, 0)).isEqualTo((byte) 127);
        }

        @Test
        @DisplayName("Should reject null segment for byte operations")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> SegmentVarHandle.writeByte(null, 0, (byte) 1))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> SegmentVarHandle.readByte(null, 0))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Boolean Read/Write Tests")
    class BooleanTests {

        @Test
        @DisplayName("Should write and read true")
        void shouldWriteAndReadTrue() {
            SegmentVarHandle.writeBoolean(segment, 0, true);
            assertThat(SegmentVarHandle.readBoolean(segment, 0)).isTrue();
        }

        @Test
        @DisplayName("Should write and read false")
        void shouldWriteAndReadFalse() {
            SegmentVarHandle.writeBoolean(segment, 0, false);
            assertThat(SegmentVarHandle.readBoolean(segment, 0)).isFalse();
        }

        @Test
        @DisplayName("Should reject null segment for boolean operations")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> SegmentVarHandle.writeBoolean(null, 0, true))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> SegmentVarHandle.readBoolean(null, 0))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ByteArray Write Tests")
    class ByteArrayWriteTests {

        @Test
        @DisplayName("Should write byte array")
        void shouldWriteByteArray() {
            byte[] data = {10, 20, 30, 40, 50};
            SegmentVarHandle.write(segment, 0, data);

            assertThat(SegmentVarHandle.readByte(segment, 0)).isEqualTo((byte) 10);
            assertThat(SegmentVarHandle.readByte(segment, 1)).isEqualTo((byte) 20);
            assertThat(SegmentVarHandle.readByte(segment, 4)).isEqualTo((byte) 50);
        }

        @Test
        @DisplayName("Should reject null segment for byte array write")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> SegmentVarHandle.write(null, 0, new byte[5]))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should reject null data for byte array write")
        void shouldRejectNullData() {
            assertThatThrownBy(() -> SegmentVarHandle.write(segment, 0, (byte[]) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }
    }

    @Nested
    @DisplayName("MemorySegment Write Tests")
    class MemorySegmentWriteTests {

        @Test
        @DisplayName("Should reject null target segment")
        void shouldRejectNullTarget() {
            MemorySegment source = Arena.ofAuto().allocate(5);
            assertThatThrownBy(() -> SegmentVarHandle.write(null, source, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should reject null source segment")
        void shouldRejectNullSource() {
            assertThatThrownBy(() -> SegmentVarHandle.write(segment, (MemorySegment) null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
        }
    }
}
