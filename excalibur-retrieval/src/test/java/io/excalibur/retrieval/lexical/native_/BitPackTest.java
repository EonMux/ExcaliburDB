package io.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The packing every postings block is stored in.
 *
 * <p>An error here is not a wrong answer, it is a corrupt index: a block that unpacks to different
 * numbers than were packed returns documents that do not contain the term, with scores computed
 * from another document's frequencies. Nothing downstream can detect that, so the round trip is
 * pinned exhaustively at every width rather than sampled.
 */
@DisplayName("Bit packing")
class BitPackTest {

    @Test
    @DisplayName("round trips at every width from 1 to 32")
    void roundTripsAtEveryWidth() {
        Random random = new Random(31);
        for (int bits = 1; bits <= 32; bits++) {
            int count = 128;
            int[] values = new int[count];
            long max = bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
            for (int i = 0; i < count; i++) {
                values[i] = (int) (Math.abs(random.nextLong()) % (max + 1));
            }
            byte[] packed = new byte[BitPack.packedBytes(count, bits) + 8];
            BitPack.pack(values, count, bits, packed, 3);
            int[] out = new int[count];
            BitPack.unpack(packed, 3, bits, count, out);

            assertThat(out).as("width %d", bits).containsExactly(values);
        }
    }

    @Test
    @DisplayName("bitsFor covers the value it is asked about")
    void bitsForIsSufficient() {
        assertThat(BitPack.bitsFor(0)).isEqualTo(1);
        assertThat(BitPack.bitsFor(1)).isEqualTo(1);
        assertThat(BitPack.bitsFor(2)).isEqualTo(2);
        assertThat(BitPack.bitsFor(255)).isEqualTo(8);
        assertThat(BitPack.bitsFor(256)).isEqualTo(9);
        assertThat(BitPack.bitsFor(Integer.MAX_VALUE)).isEqualTo(31);
    }

    @Test
    @DisplayName("a block of zeroes still round trips")
    void allZeroes() {
        int[] values = new int[64];
        byte[] packed = new byte[BitPack.packedBytes(64, 1)];
        BitPack.pack(values, 64, 1, packed, 0);
        int[] out = new int[64];
        BitPack.unpack(packed, 0, 1, 64, out);
        assertThat(out).containsOnly(0);
    }

    @Test
    @DisplayName("packing is dense: no more bytes than the width requires")
    void packingIsDense() {
        // The whole point of a fixed width is that the block costs exactly this much.
        assertThat(BitPack.packedBytes(128, 5)).isEqualTo(80);
        assertThat(BitPack.packedBytes(1, 1)).isEqualTo(1);
        assertThat(BitPack.packedBytes(8, 8)).isEqualTo(8);
    }

    @Test
    @DisplayName("an impossible width is refused rather than silently truncating")
    void invalidWidthRefused() {
        assertThatThrownBy(() -> BitPack.pack(new int[] {1}, 1, 0, new byte[8], 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BitPack.pack(new int[] {1}, 1, 33, new byte[8], 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
