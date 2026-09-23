package com.eonmux.excalibur.store.format;

/**
 * Computes the same CRC32 as {@link java.util.zip.CRC32} over a few bytes held in registers.
 *
 * <p>For the fifteen bytes a slot checksum covers, the JDK's intrinsic spends most of its time
 * on the fixed cost of the call rather than on the bytes: the covered words have to be spilled
 * into a per-thread array first, and the call costs about as much as checksumming a hundred
 * bytes. A slot checksum is paid on every hit and on every slot a miss walked, so this is a
 * table-driven "slicing-by-8" CRC that works on the words exactly as they were read from the
 * mapping, with nothing written to memory. It is the standard reflected CRC-32 (polynomial
 * {@code 0xEDB88320}, initial value and final complement {@code 0xFFFFFFFF}); the values it
 * produces are the values {@code CRC32} produces and the values already stored on disk.</p>
 *
 * <p>Slicing-by-8 folds eight bytes per step: table {@code k} holds the effect of a byte
 * followed by {@code k} zero bytes, so the eight bytes of a word can be looked up
 * independently and combined with exclusive-or instead of being fed through one at a
 * time.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
final class Crc32Words {

    /** The reflected CRC-32 polynomial, as {@link java.util.zip.CRC32} uses it. */
    private static final int POLYNOMIAL = 0xEDB88320;

    /** Entries per table: one per byte value. */
    private static final int TABLE_SIZE = 256;

    /** Tables stacked end to end: table {@code k} starts at {@code k * TABLE_SIZE}. */
    private static final int[] TABLES = buildTables();

    /** Mask selecting one byte. */
    private static final int BYTE_MASK = 0xFF;

    /** Not instantiable: this class is a single computation. */
    private Crc32Words() {
    }

    /**
     * Computes the CRC32 of eight little-endian bytes followed by the low seven bytes of a
     * second word.
     *
     * @param first  The first eight bytes, first byte in the low-order position
     * @param second The next seven bytes in its low-order positions; the top byte is ignored
     * @return The CRC32 of the fifteen bytes, as {@link java.util.zip.CRC32} computes it
     */
    static int of15(long first, long second) {
        int crc = ~0;
        int low = crc ^ (int) first;
        int high = (int) (first >>> Integer.SIZE);
        crc = table(7, low) ^ table(6, low >>> 8) ^ table(5, low >>> 16) ^ table(4, low >>> 24)
            ^ table(3, high) ^ table(2, high >>> 8) ^ table(1, high >>> 16)
            ^ table(0, high >>> 24);

        // Seven bytes: the register is still consumed by the first four, and the remaining
        // three are one table fewer from the end each, exactly as a shorter slice.
        low = crc ^ (int) second;
        high = (int) (second >>> Integer.SIZE);
        crc = table(6, low) ^ table(5, low >>> 8) ^ table(4, low >>> 16) ^ table(3, low >>> 24)
            ^ table(2, high) ^ table(1, high >>> 8) ^ table(0, high >>> 16);
        return ~crc;
    }

    /**
     * Looks up the low byte of a value in one of the slicing tables.
     *
     * @param table Which table, 0 to 7
     * @param value The value whose low byte is looked up
     * @return The table entry
     */
    private static int table(int table, int value) {
        return TABLES[table * TABLE_SIZE + (value & BYTE_MASK)];
    }

    /**
     * Builds the eight slicing tables.
     *
     * @return The tables, stacked end to end
     */
    private static int[] buildTables() {
        int[] tables = new int[8 * TABLE_SIZE];
        for (int value = 0; value < TABLE_SIZE; value++) {
            int crc = value;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
            }
            tables[value] = crc;
        }
        for (int table = 1; table < 8; table++) {
            for (int value = 0; value < TABLE_SIZE; value++) {
                int previous = tables[(table - 1) * TABLE_SIZE + value];
                tables[table * TABLE_SIZE + value] =
                    (previous >>> Byte.SIZE) ^ tables[previous & BYTE_MASK];
            }
        }
        return tables;
    }
}
