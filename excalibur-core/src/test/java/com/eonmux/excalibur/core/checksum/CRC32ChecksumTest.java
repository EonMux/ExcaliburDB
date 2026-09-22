package com.eonmux.excalibur.core.checksum;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CRC32Checksum} against independently known values.
 *
 * <p>The vectors come from Python's {@code binascii.crc32}, which matters: a checksum tested only
 * against its own output verifies that it is deterministic, not that it is CRC-32. The write-ahead
 * log stores these values on disk, so an implementation that is self-consistent but wrong would
 * accept its own corrupt records and reject everyone else's.
 *
 * <p>Replaces a {@code main()}-based program that printed these same comparisons and was never run
 * by the suite.
 */
@DisplayName("CRC32Checksum")
class CRC32ChecksumTest {

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("matches known CRC-32 vectors")
    void knownVectors() {
        assertThat(CRC32Checksum.compute(utf8("Hello World")) & 0xFFFFFFFFL)
            .isEqualTo(0x4A17B156L);
        assertThat(CRC32Checksum.compute(new byte[0]) & 0xFFFFFFFFL)
            .isEqualTo(0x00000000L);
        assertThat(CRC32Checksum.compute(new byte[] { 0x00 }) & 0xFFFFFFFFL)
            .isEqualTo(0xD202EF8DL);
    }

    @Test
    @DisplayName("the String overload agrees with the byte[] one")
    void stringOverloadAgrees() {
        assertThat(CRC32Checksum.compute("Hello World"))
            .isEqualTo(CRC32Checksum.compute(utf8("Hello World")));
    }

    @Test
    @DisplayName("a range checksums the same as that range copied out")
    void rangeMatchesCopy() {
        byte[] framed = utf8("XXHello WorldYY");
        byte[] exact = utf8("Hello World");

        assertThat(CRC32Checksum.compute(framed, 2, exact.length))
            .as("the WAL checksums a record inside a larger buffer, never a copy of it")
            .isEqualTo(CRC32Checksum.compute(exact));
    }

    @Test
    @DisplayName("verify accepts the right checksum and rejects a corrupted byte")
    void verifyDetectsCorruption() {
        byte[] data = utf8("Hello World");
        long checksum = CRC32Checksum.compute(data);

        assertThat(CRC32Checksum.verify(data, checksum)).isTrue();

        data[0] ^= 0x01;
        assertThat(CRC32Checksum.verify(data, checksum))
            .as("a single flipped bit is exactly what this exists to catch")
            .isFalse();
    }

    @Test
    @DisplayName("the bundled self-check passes")
    void selfCheckPasses() {
        // The same idiom XxHash64Test uses against XxHash64.verifyTestVectors().
        assertThat(CRC32Checksum.verifyTestVectors())
            .as("the implementation's own vectors must agree with it")
            .isTrue();
    }

    @Test
    @DisplayName("verify over a range accepts the right checksum and rejects corruption")
    void verifyRange() {
        byte[] framed = utf8("XXHello WorldYY");
        int offset = 2;
        int length = utf8("Hello World").length;
        long checksum = CRC32Checksum.compute(framed, offset, length);

        assertThat(CRC32Checksum.verify(framed, offset, length, checksum)).isTrue();

        framed[offset + 3] ^= 0x01;
        assertThat(CRC32Checksum.verify(framed, offset, length, checksum)).isFalse();
    }

    @Test
    @DisplayName("corruption outside the range does not affect it")
    void corruptionOutsideRangeIsIgnored() {
        // The WAL checksums one record inside a shared buffer; a neighbouring record changing
        // must not invalidate this one.
        byte[] framed = utf8("XXHello WorldYY");
        int offset = 2;
        int length = utf8("Hello World").length;
        long checksum = CRC32Checksum.compute(framed, offset, length);

        framed[0] ^= 0xFF;
        framed[framed.length - 1] ^= 0xFF;

        assertThat(CRC32Checksum.verify(framed, offset, length, checksum)).isTrue();
    }

    @Test
    @DisplayName("incremental update over a range matches the whole input")
    void updateRangeMatchesWholeInput() {
        byte[] framed = utf8("ZZWorld");
        long incremental = CRC32Checksum.update(
            CRC32Checksum.compute(utf8("Hello ")), framed, 2, 5);

        assertThat(incremental).isEqualTo(CRC32Checksum.compute(utf8("Hello World")));
    }

    @Test
    @DisplayName("a zero-length range is the identity")
    void zeroLengthRange() {
        byte[] data = utf8("Hello World");
        long full = CRC32Checksum.compute(data);

        assertThat(CRC32Checksum.compute(data, 0, 0)).isEqualTo(CRC32Checksum.compute(new byte[0]));
        assertThat(CRC32Checksum.update(full, data, 0, 0)).isEqualTo(full);
    }

    @Test
    @DisplayName("incremental update matches computing over the whole input")
    void updateMatchesWholeInput() {
        byte[] first = utf8("Hello ");
        byte[] second = utf8("World");

        long incremental = CRC32Checksum.update(CRC32Checksum.compute(first), second);

        assertThat(incremental).isEqualTo(CRC32Checksum.compute(utf8("Hello World")));
    }
}
