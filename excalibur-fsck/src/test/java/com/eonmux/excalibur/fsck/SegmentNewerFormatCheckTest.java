package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.SegmentDataCheck;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment file a newer build wrote, as the checker sees it: not readable by this build, and
 * not shown to be damaged either - so reported, and never moved aside.
 */
class SegmentNewerFormatCheckTest {

    private static final int NEWER = SegmentFormat.CURRENT.version() + 1;

    @Test
    void aSegmentInANewerFormatIsUnreadableRatherThanReadAsTheLegacyOne(@TempDir Path dir)
            throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = DataDirectoryFixture.segmentFiles(dataDir).get(0);
        restampVersion(segment, NEWER);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(check.version()).isEqualTo("v" + NEWER);
        assertThat(check.detail())
            .contains("version " + NEWER)
            .contains("newer build");
    }

    @Test
    void aSegmentInANewerFormatIsLeftForTheOperator(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = DataDirectoryFixture.segmentFiles(dataDir).get(0);
        restampVersion(segment, NEWER);
        String name = segment.getFileName().toString();

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir, null).scan());

        assertThat(plan.actions())
            .describedAs("a sound file this build cannot read is not damage to repair")
            .noneMatch(action -> action.relative().endsWith(name));
        assertThat(plan.needsOperator()).anyMatch(note -> note.contains(name));
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
