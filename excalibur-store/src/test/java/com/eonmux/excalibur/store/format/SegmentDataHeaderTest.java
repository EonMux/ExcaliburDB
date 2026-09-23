package com.eonmux.excalibur.store.format;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link SegmentDataHeader#detect} makes of the bytes at the head of a data region.
 */
class SegmentDataHeaderTest {

    private static final long BASE = 64;

    @Test
    void theHeaderThisBuildWritesIsReadAsTheCurrentFormat() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(BASE + SegmentDataHeader.SIZE);
            SegmentDataHeader.write(mapped, BASE);

            assertThat(SegmentDataHeader.detect(mapped, BASE)).isEqualTo(SegmentFormat.CURRENT);
        }
    }

    @Test
    void aSoundHeaderNamingAnOlderChecksummedVersionIsReadAsThatVersion() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(BASE + SegmentDataHeader.SIZE);
            stamp(mapped, SegmentFormat.V2.version(), 0);

            assertThat(SegmentDataHeader.detect(mapped, BASE)).isEqualTo(SegmentFormat.V2);
        }
    }

    @Test
    void bytesThatAreNotAHeaderAreTheLegacyFormat() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(BASE + SegmentDataHeader.SIZE);
            mapped.set(SegmentLayout.INT_LAYOUT, BASE, 0x1234_5678);

            assertThat(SegmentDataHeader.detect(mapped, BASE)).isEqualTo(SegmentFormat.V1);
        }
    }

    @Test
    void aHeaderWhoseChecksumDoesNotMatchIsNotBelieved() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(BASE + SegmentDataHeader.SIZE);
            stamp(mapped, SegmentFormat.CURRENT.version() + 1, 1);

            assertThat(SegmentDataHeader.detect(mapped, BASE)).isEqualTo(SegmentFormat.V1);
        }
    }

    @Test
    void aSoundHeaderNamingAVersionThisBuildDoesNotKnowIsRefused() {
        int newer = SegmentFormat.CURRENT.version() + 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(BASE + SegmentDataHeader.SIZE);
            stamp(mapped, newer, 0);

            assertThatThrownBy(() -> SegmentDataHeader.detect(mapped, BASE))
                .isInstanceOfSatisfying(UnsupportedSegmentFormatException.class, refused -> {
                    assertThat(refused.version()).isEqualTo(newer);
                    assertThat(refused.oldest()).isEqualTo(SegmentFormat.V1.version());
                    assertThat(refused.newest()).isEqualTo(SegmentFormat.CURRENT.version());
                })
                .hasMessageContaining("version " + newer)
                .hasMessageContaining("newer build");
        }
    }

    /**
     * Writes a header naming any version, with its checksum off by a chosen amount.
     *
     * @param mapped  The memory to write into
     * @param version The version field
     * @param crcSkew Added to the correct checksum; zero writes a sound header
     */
    private static void stamp(MemorySegment mapped, int version, int crcSkew) {
        mapped.set(SegmentLayout.INT_LAYOUT, BASE, SegmentDataHeader.MAGIC);
        mapped.set(SegmentLayout.INT_LAYOUT, BASE + 4, version);
        mapped.set(SegmentLayout.INT_LAYOUT, BASE + 8, 0);
        CRC32 crc = new CRC32();
        crc.update(littleEndian(SegmentDataHeader.MAGIC));
        crc.update(littleEndian(version));
        crc.update(littleEndian(0));
        mapped.set(SegmentLayout.INT_LAYOUT, BASE + 12, (int) crc.getValue() + crcSkew);
    }

    private static byte[] littleEndian(int value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }
}
