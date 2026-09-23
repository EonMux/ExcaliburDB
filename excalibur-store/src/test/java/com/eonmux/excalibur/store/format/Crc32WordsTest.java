package com.eonmux.excalibur.store.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link Crc32Words} to {@link CRC32}: the slot checksums it computes are compared
 * against values the JDK computed when the slots were written.
 */
@DisplayName("Crc32Words")
class Crc32WordsTest {

    @Test
    @DisplayName("of15 equals CRC32 over the fifteen little-endian bytes, for random words")
    void of15MatchesCrc32() {
        Random random = new Random(23);
        for (int i = 0; i < 100_000; i++) {
            long first = random.nextLong();
            long second = random.nextLong();
            assertEquals(reference(first, second), Crc32Words.of15(first, second),
                () -> Long.toHexString(first) + " " + Long.toHexString(second));
        }
    }

    @Test
    @DisplayName("of15 ignores the top byte of the second word")
    void topByteOfTheSecondWordIsIgnored() {
        long first = 0x0123456789ABCDEFL;
        long second = 0x00FEDCBA98765432L;
        assertEquals(Crc32Words.of15(first, second),
            Crc32Words.of15(first, second | 0xFF00000000000000L));
    }

    @Test
    @DisplayName("of15 handles all-zero and all-one words")
    void extremes() {
        assertEquals(reference(0L, 0L), Crc32Words.of15(0L, 0L));
        assertEquals(reference(-1L, -1L), Crc32Words.of15(-1L, -1L));
    }

    /**
     * Computes the reference value with the JDK.
     *
     * @param first  The first eight bytes
     * @param second The next seven bytes, low-order first
     * @return The CRC32 of the fifteen bytes
     */
    private static int reference(long first, long second) {
        ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(first).putLong(second);
        CRC32 crc = new CRC32();
        crc.update(bytes.array(), 0, 15);
        return (int) crc.getValue();
    }
}
