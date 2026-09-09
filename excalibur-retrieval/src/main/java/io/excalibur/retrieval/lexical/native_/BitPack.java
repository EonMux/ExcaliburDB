package io.excalibur.retrieval.lexical.native_;

/**
 * Fixed-width bit packing for a block of small integers.
 *
 * <h2>Why fixed width per block</h2>
 * <p>Doc ids in a postings row ascend, so their gaps are small and similar to their neighbours.
 * Choosing one width for a whole block and packing every value to it costs a few wasted bits on
 * the smallest gaps and buys a decoder with no branches and no per-value length prefix - which is
 * what makes a block decodable in a tight loop rather than a byte at a time. Variable-length
 * encodings are smaller on paper and slower in practice for exactly that reason.</p>
 *
 * <p>Values are written most-significant-bit first so a block's bytes read the same on any
 * machine, matching the big-endian convention the rest of the mapped files use.</p>
 */
public final class BitPack {

    private BitPack() {
        throw new AssertionError("Utility class");
    }

    /** Bits needed to represent every value in {@code [0, max]}. */
    public static int bitsFor(int max) {
        if (max <= 0) {
            // Still one bit: a block of all-zero gaps is legal and must round-trip.
            return 1;
        }
        return 32 - Integer.numberOfLeadingZeros(max);
    }

    /** Bytes a packed block occupies. */
    public static int packedBytes(int count, int bits) {
        return (count * bits + 7) / 8;
    }

    /**
     * Packs {@code count} values at {@code bits} each.
     *
     * @param values Values, none negative and none needing more than {@code bits}
     * @param count  How many to pack
     * @param bits   Width per value, 1 to 32
     * @param out    Destination
     * @param offset Where to start writing
     * @return Bytes written
     */
    public static int pack(int[] values, int count, int bits, byte[] out, int offset) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits must be 1..32: " + bits);
        }
        int bytes = packedBytes(count, bits);
        java.util.Arrays.fill(out, offset, offset + bytes, (byte) 0);
        long bitPos = 0;
        for (int i = 0; i < count; i++) {
            long value = values[i] & 0xFFFFFFFFL;
            for (int b = bits - 1; b >= 0; b--) {
                if (((value >>> b) & 1L) != 0) {
                    int index = offset + (int) (bitPos >>> 3);
                    out[index] |= (byte) (0x80 >>> (bitPos & 7));
                }
                bitPos++;
            }
        }
        return bytes;
    }

    /**
     * Unpacks {@code count} values of {@code bits} each.
     *
     * @param in     Source
     * @param offset Where the packed block starts
     * @param bits   Width per value
     * @param count  How many to read
     * @param out    Destination, at least {@code count} long
     */
    public static void unpack(byte[] in, int offset, int bits, int count, int[] out) {
        // A 64-bit accumulator rather than a loop over individual bits. Decoding is on the query
        // path and runs once per posting in every block that is actually read, so the difference
        // between one shift per value and `bits` shifts and branches per value is the difference
        // between the block codec being free and being the cost of the query.
        long buffer = 0;
        int buffered = 0;
        int pos = offset;
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        for (int i = 0; i < count; i++) {
            while (buffered < bits) {
                buffer = (buffer << 8) | (in[pos++] & 0xFFL);
                buffered += 8;
            }
            int shift = buffered - bits;
            out[i] = (int) ((buffer >>> shift) & mask);
            buffered = shift;
            buffer &= shift == 0 ? 0L : (1L << shift) - 1;
        }
    }
}
