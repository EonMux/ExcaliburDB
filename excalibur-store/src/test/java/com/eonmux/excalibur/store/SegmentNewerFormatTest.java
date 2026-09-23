package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;
import com.eonmux.excalibur.store.format.UnsupportedSegmentFormatException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A segment file written in a format newer than this build reads.
 *
 * <p>Such a file has a sound data region header, so nothing about it looks damaged; the only
 * thing wrong is that this build does not know its layout. Reading it as the header-less
 * legacy format would parse the header as an entry and every record after it at the wrong
 * width, and an empty one would be restamped as this build's format over whatever the newer
 * build put in its slot table.</p>
 */
class SegmentNewerFormatTest {

    private static final long CAPACITY = 4L * 1024 * 1024;

    private static final int NEWER = SegmentFormat.CURRENT.version() + 1;

    @TempDir
    Path dir;

    @Test
    void aSegmentWithEntriesIsRefusedAndLeftAsItWas() throws IOException {
        Path file = dir.resolve("segment_0.dat");
        createSegment(file, 16);
        restampVersion(file, NEWER);
        byte[] before = Files.readAllBytes(file);

        assertRefused(file);

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void anEmptySegmentIsRefusedRatherThanRestampedAsThisBuildsFormat() throws IOException {
        Path file = dir.resolve("segment_0.dat");
        createSegment(file, 0);
        restampVersion(file, NEWER);
        byte[] before = Files.readAllBytes(file);

        assertRefused(file);

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    private static void assertRefused(Path file) {
        assertThatThrownBy(() -> {
            try (Arena arena = Arena.ofShared();
                 Segment ignored = Segment.openExisting(file, arena)) {
                // Opening is the whole test.
            }
        })
            .isInstanceOf(UnsupportedSegmentFormatException.class)
            .hasMessageContaining(file.toString())
            .hasMessageContaining("version " + NEWER);
    }

    private static void createSegment(Path file, int entries) throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(file, CAPACITY, arena)) {
            for (int i = 0; i < entries; i++) {
                segment.put(("key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("value-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Rewrites a segment's data region header to name another version, soundly.
     *
     * @param file    The segment file
     * @param version The version the header is to name
     * @throws IOException if the file cannot be rewritten
     */
    private static void restampVersion(Path file, int version) throws IOException {
        int slotCount = Segment.deriveSlotCountForRecovery(Files.size(file));
        long base = (long) slotCount * SegmentLayout.SLOT_SIZE;
        ByteBuffer header = ByteBuffer.allocate(SegmentDataHeader.SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(SegmentDataHeader.MAGIC).putInt(version).putInt(0);
        CRC32 crc = new CRC32();
        crc.update(header.array(), 0, 12);
        header.putInt((int) crc.getValue()).flip();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(header, base);
        }
    }
}
