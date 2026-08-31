package io.excalibur.core.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the stored chunk layout.
 *
 * <p>The layout was previously written and read by five independent pieces of code, with no test
 * that any writer and any reader agreed. The round-trip cases here are the point: they are what a
 * change to the format has to survive, and what nothing checked before.
 */
@DisplayName("ChunkPayloadCodec")
class ChunkPayloadCodecTest {

    private static final float[] VECTOR = {1.5f, -2.25f, 0.0f, 3.75f};

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("text and embedding come back exactly as written")
        void textAndEmbedding() {
            byte[] value = ChunkPayloadCodec.encode("hello world", VECTOR);

            assertThat(ChunkPayloadCodec.embeddingOf(value)).containsExactly(VECTOR);
            assertThat(ChunkPayloadCodec.textOf(value)).isEqualTo("hello world");
            assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(VECTOR.length);
        }

        @Test
        @DisplayName("a vector-only value reports no text rather than empty text")
        void vectorOnly() {
            byte[] value = ChunkPayloadCodec.encode(VECTOR);

            assertThat(ChunkPayloadCodec.embeddingOf(value)).containsExactly(VECTOR);
            assertThat(ChunkPayloadCodec.textOf(value)).isNull();
            assertThat(ChunkPayloadCodec.textBytesOf(value)).isNull();
        }

        @Test
        @DisplayName("empty text is written and read back as empty, not as absent")
        void emptyText() {
            byte[] value = ChunkPayloadCodec.encode("", VECTOR);

            // Byte-identical to the vector-only form, so it necessarily reads back as no text.
            // Worth pinning: a caller distinguishing "" from null would be relying on something
            // the format cannot represent.
            assertThat(value).isEqualTo(ChunkPayloadCodec.encode(VECTOR));
            assertThat(ChunkPayloadCodec.textOf(value)).isNull();
        }

        @Test
        @DisplayName("multi-byte text survives the round trip")
        void multiByteText() {
            String text = "naïve café 日本語 🜲";
            byte[] value = ChunkPayloadCodec.encode(text, VECTOR);

            assertThat(ChunkPayloadCodec.textOf(value)).isEqualTo(text);
        }

        @Test
        @DisplayName("special float values survive the round trip")
        void specialFloats() {
            float[] special = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.MIN_VALUE, Float.MAX_VALUE, -0.0f};
            byte[] value = ChunkPayloadCodec.encode("x", special);

            float[] decoded = ChunkPayloadCodec.embeddingOf(value);
            assertThat(decoded).hasSize(special.length);
            assertThat(Float.isNaN(decoded[0])).isTrue();
            assertThat(decoded[1]).isEqualTo(Float.POSITIVE_INFINITY);
            assertThat(decoded[2]).isEqualTo(Float.NEGATIVE_INFINITY);
            assertThat(decoded[3]).isEqualTo(Float.MIN_VALUE);
            assertThat(decoded[4]).isEqualTo(Float.MAX_VALUE);
            assertThat(Float.floatToRawIntBits(decoded[5]))
                .isEqualTo(Float.floatToRawIntBits(-0.0f));
        }
    }

    @Nested
    @DisplayName("The layout on disk")
    class Layout {

        @Test
        @DisplayName("is little-endian, dimension first, text last")
        void isAsDocumented() {
            // Spelled out by hand rather than through the codec, so a change to the byte order or
            // field order fails here rather than round-tripping happily against itself.
            byte[] value = ChunkPayloadCodec.encode("hi", new float[] {1.0f, 2.0f});

            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(buffer.getInt()).isEqualTo(2);
            assertThat(buffer.getFloat()).isEqualTo(1.0f);
            assertThat(buffer.getFloat()).isEqualTo(2.0f);

            byte[] tail = new byte[buffer.remaining()];
            buffer.get(tail);
            assertThat(new String(tail, StandardCharsets.UTF_8)).isEqualTo("hi");
            assertThat(value).hasSize(4 + 2 * 4 + 2);
        }

        @Test
        @DisplayName("a value written by hand decodes the same way")
        void handWrittenValueDecodes() {
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 3).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(1);
            buffer.putFloat(7.5f);
            buffer.put("abc".getBytes(StandardCharsets.UTF_8));

            assertThat(ChunkPayloadCodec.embeddingOf(buffer.array())).containsExactly(7.5f);
            assertThat(ChunkPayloadCodec.textOf(buffer.array())).isEqualTo("abc");
        }
    }

    @Nested
    @DisplayName("Untrusted input")
    class UntrustedInput {

        @Test
        @DisplayName("null and truncated values decode to nothing")
        void nullAndShort() {
            for (byte[] value : new byte[][] {null, new byte[0], new byte[3]}) {
                assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(-1);
                assertThat(ChunkPayloadCodec.embeddingOf(value)).isNull();
                assertThat(ChunkPayloadCodec.textOf(value)).isNull();
            }
        }

        @Test
        @DisplayName("a negative dimension is refused, not used as a length")
        void negativeDimension() {
            byte[] value = headerOf(-1, 64);

            assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(-1);
            assertThat(ChunkPayloadCodec.embeddingOf(value)).isNull();
            assertThat(ChunkPayloadCodec.textOf(value)).isNull();
        }

        @Test
        @DisplayName("a dimension large enough to overflow the offset arithmetic is refused")
        void overflowingDimension() {
            // 4 + dimension * 4 wraps negative for a dimension near Integer.MAX_VALUE. A reader
            // that computed the text offset from it would compare a length against a negative
            // number, pass, and then allocate from a wild size.
            byte[] value = headerOf(Integer.MAX_VALUE, 64);

            assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(-1);
            assertThat(ChunkPayloadCodec.embeddingOf(value)).isNull();
            assertThat(ChunkPayloadCodec.textOf(value)).isNull();
        }

        @Test
        @DisplayName("a dimension the value is too short for is refused")
        void dimensionLongerThanTheValue() {
            byte[] value = headerOf(10, 4 + 5 * Float.BYTES);

            assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(-1);
            assertThat(ChunkPayloadCodec.embeddingOf(value)).isNull();
        }

        @Test
        @DisplayName("zero is not a dimension")
        void zeroDimension() {
            assertThat(ChunkPayloadCodec.dimensionOf(headerOf(0, 64))).isEqualTo(-1);
        }

        @Test
        @DisplayName("a vector of another width is not returned to a caller expecting one")
        void wrongExpectedDimension() {
            byte[] value = ChunkPayloadCodec.encode("x", VECTOR);

            assertThat(ChunkPayloadCodec.embeddingOf(value, VECTOR.length)).containsExactly(VECTOR);
            assertThat(ChunkPayloadCodec.embeddingOf(value, VECTOR.length + 1)).isNull();
            assertThat(ChunkPayloadCodec.embeddingOf(value, 0)).isNull();
        }

        private static byte[] headerOf(int dimension, int totalLength) {
            byte[] value = new byte[Math.max(totalLength, 4)];
            ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).putInt(dimension);
            return value;
        }
    }

    @Nested
    @DisplayName("Encoding refuses what it cannot represent")
    class EncodingLimits {

        @Test
        @DisplayName("a null argument is rejected")
        void nulls() {
            assertThatThrownBy(() -> ChunkPayloadCodec.encode(null, VECTOR))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ChunkPayloadCodec.encode("x", null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ChunkPayloadCodec.encode(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an empty embedding is rejected rather than written as dimension zero")
        void emptyEmbedding() {
            // Zero is the one dimension no decoder accepts, so writing it would produce a value
            // that reads back as unreadable.
            assertThatThrownBy(() -> ChunkPayloadCodec.encode("x", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an embedding beyond the readable dimension is rejected at write time")
        void oversizedEmbedding() {
            assertThatThrownBy(() ->
                ChunkPayloadCodec.encode(new float[ChunkPayloadCodec.MAX_DIMENSION + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ChunkPayloadCodec.MAX_DIMENSION));
        }

        @Test
        @DisplayName("the largest readable dimension is writable")
        void maximumDimensionRoundTrips() {
            byte[] value = ChunkPayloadCodec.encode(new float[ChunkPayloadCodec.MAX_DIMENSION]);

            assertThat(ChunkPayloadCodec.dimensionOf(value))
                .isEqualTo(ChunkPayloadCodec.MAX_DIMENSION);
        }
    }

    @Nested
    @DisplayName("UTF-8 truncation")
    class Truncation {

        @Test
        @DisplayName("cuts on a character boundary rather than mid-sequence")
        void cutsOnBoundary() {
            byte[] text = "aé".getBytes(StandardCharsets.UTF_8); // 1 + 2 bytes

            // 2 lands inside the two-byte 'é'; the cut walks back to keep only "a".
            assertThat(new String(ChunkPayloadCodec.truncateUtf8(text, 2), StandardCharsets.UTF_8))
                .isEqualTo("a");
            assertThat(new String(ChunkPayloadCodec.truncateUtf8(text, 3), StandardCharsets.UTF_8))
                .isEqualTo("aé");
        }

        @Test
        @DisplayName("text within budget is returned unchanged")
        void withinBudget() {
            byte[] text = "abc".getBytes(StandardCharsets.UTF_8);

            assertThat(ChunkPayloadCodec.truncateUtf8(text, 3)).isSameAs(text);
            assertThat(ChunkPayloadCodec.truncateUtf8(null, 3)).isNull();
        }

        @Test
        @DisplayName("a non-positive budget is a programming error")
        void nonPositiveBudget() {
            assertThatThrownBy(() -> ChunkPayloadCodec.truncateUtf8(new byte[4], 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
