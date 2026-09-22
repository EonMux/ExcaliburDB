package com.eonmux.excalibur.query.payload;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChunkValueCodec}.
 */
class ChunkValueCodecTest {

    private static byte[] encode(float[] vector, String text) {
        byte[] textBytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer
            .allocate(Integer.BYTES + vector.length * Float.BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(vector.length);
        for (float component : vector) {
            buffer.putFloat(component);
        }
        buffer.put(textBytes);
        return buffer.array();
    }

    @Test
    void returnsTheTextStoredAfterTheEmbedding() {
        byte[] value = encode(new float[]{1.0f, 2.0f, 3.0f}, "chunk body");

        assertThat(new String(ChunkValueCodec.textOf(value), StandardCharsets.UTF_8))
            .isEqualTo("chunk body");
    }

    @Test
    void returnsNullForAVectorOnlyValue() {
        assertThat(ChunkValueCodec.textOf(encode(new float[]{1.0f, 2.0f}, null))).isNull();
    }

    @Test
    void returnsNullForValuesTooShortToCarryAHeader() {
        assertThat(ChunkValueCodec.textOf(null)).isNull();
        assertThat(ChunkValueCodec.textOf(new byte[0])).isNull();
        assertThat(ChunkValueCodec.textOf(new byte[]{1, 2, 3})).isNull();
    }

    /**
     * A value that is not in the documented encoding must be rejected, not handed on as
     * "text": scoring a document against binary noise is worse than not scoring it.
     */
    @Test
    void rejectsAValueWhoseHeaderIsNotAPlausibleDimension() {
        byte[] plainText = "this is not an encoded chunk".getBytes(StandardCharsets.UTF_8);

        assertThat(ChunkValueCodec.textOf(plainText)).isNull();
    }

    @Test
    void rejectsANegativeDimension() {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(-4);

        assertThat(ChunkValueCodec.textOf(buffer.array())).isNull();
    }

    @Test
    void keepsTextThatFitsTheBudget() {
        byte[] text = "short".getBytes(StandardCharsets.UTF_8);

        assertThat(ChunkValueCodec.truncateUtf8(text, 64)).isSameAs(text);
        assertThat(ChunkValueCodec.truncateUtf8(null, 64)).isNull();
    }

    /**
     * Truncation must land on a character boundary: cutting a multi-byte character in half
     * would decode to a replacement character.
     */
    @Test
    void truncatesOnACharacterBoundary() {
        byte[] text = "aaaé".getBytes(StandardCharsets.UTF_8); // 3 + 2 bytes

        byte[] truncated = ChunkValueCodec.truncateUtf8(text, 4);

        assertThat(new String(truncated, StandardCharsets.UTF_8)).isEqualTo("aaa");
    }

    @Test
    void truncatesOnAnExactBoundaryWithoutLosingACharacter() {
        byte[] text = "aaaé".getBytes(StandardCharsets.UTF_8);

        assertThat(new String(ChunkValueCodec.truncateUtf8(text, 5), StandardCharsets.UTF_8))
            .isEqualTo("aaaé");
    }

    @Test
    void rejectsANonPositiveBudget() {
        assertThatThrownBy(() -> ChunkValueCodec.truncateUtf8(new byte[]{1}, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
