package excalibur.fsck;

import excalibur.fsck.check.CheckStatus;
import excalibur.fsck.check.DataDirectoryScan;
import excalibur.fsck.check.FileCheck;
import excalibur.fsck.check.SegmentDataCheck;
import excalibur.fsck.report.TextReportWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code --strict} is owed about a file whose status is {@code INTACT}.
 *
 * <p>A segment written before slot checksums existed has every entry proven and every slot
 * pointer cross-checked, so calling it anything but intact would be a false statement about
 * what was found. Its slot table is nonetheless unproven, and {@code --strict} is the mode
 * an operator uses to ask for proof rather than for the absence of doubt. The two facts are
 * carried separately so that neither has to be bent to fit the other.</p>
 */
class UnverifiedContentTest {

    @Test
    void aLegacySegmentIsIntactWithItsSlotTableStillUnproven(@TempDir Path dir)
            throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = occupiedSegment(dataDir);
        DataDirectoryFixture.downgradeSegmentToV2(segment);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.version()).isEqualTo("v2");
        assertThat(check.detail()).contains("no slot checksum");
        assertThat(check.unverifiedContent())
            .describedAs("its slot table carries nothing to weigh against")
            .isTrue();
    }

    @Test
    void anEmptyLegacySegmentIsUnprovenToo(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = emptySegment(dataDir);
        DataDirectoryFixture.downgradeSegmentToV2(segment);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.unverifiedContent())
            .describedAs("it has nothing unproven in it today and no way to prove "
                + "anything tomorrow, which strict must not pass over in silence")
            .isTrue();
    }

    @Test
    void aCurrentSegmentHasNothingLeftUnproven(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        Path segment = occupiedSegment(dataDir);

        FileCheck check = SegmentDataCheck.check(segment, segment.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.unverifiedContent()).isFalse();
    }

    @Test
    void anIntactFileWithUnprovenContentCountsAsUnverified(@TempDir Path dir) {
        FileCheck partly = new FileCheck(dir.resolve("segment_0.dat"), "segment_0.dat",
            SegmentDataCheck.FORMAT, "v2", CheckStatus.INTACT, "entries proven, slots not",
            1024).withUnverifiedContent();
        DataDirectoryScan scan = new DataDirectoryScan(dir, List.of(partly), List.of(),
            List.of(), new int[0]);

        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.hasUnverified())
            .describedAs("strict mode exists to surface exactly this")
            .isTrue();
    }

    @Test
    void anIntactFileWithNothingUnprovenDoesNot(@TempDir Path dir) {
        FileCheck proven = new FileCheck(dir.resolve("segment_0.dat"), "segment_0.dat",
            SegmentDataCheck.FORMAT, "v3", CheckStatus.INTACT, "everything proven", 1024);
        DataDirectoryScan scan = new DataDirectoryScan(dir, List.of(proven), List.of(),
            List.of(), new int[0]);

        assertThat(scan.hasUnverified()).isFalse();
    }

    @Test
    void theSummaryCountsWhatWasNotProven(@TempDir Path dir) {
        FileCheck partly = new FileCheck(dir.resolve("segment_0.dat"), "segment_0.dat",
            SegmentDataCheck.FORMAT, "v2", CheckStatus.INTACT, "entries proven, slots not",
            1024).withUnverifiedContent();
        DataDirectoryScan scan = new DataDirectoryScan(dir, List.of(partly), List.of(),
            List.of(), new int[0]);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TextReportWriter.writeScan(scan, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);

        assertThat(text)
            .describedAs("the sentence that claims what was not shown to be sound must "
                + "cover a file that was only partly proven")
            .contains("1 only partly verified");
        assertThat(text).contains("NOT shown to be sound");
    }

    @Test
    void strictFailsOnALegacySegmentWhileAPlainRunPasses(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 24);
        DataDirectoryFixture.downgradeSegmentToV2(
            DataDirectoryFixture.segmentFiles(dataDir).get(0));

        assertThat(run(dataDir))
            .describedAs("an unproven slot table is not damage, so it must not fail a "
                + "deploy by default")
            .isZero();
        assertThat(run(dataDir, "--strict")).isNotZero();
    }

    /**
     * Returns a segment file in a directory that holds at least one entry.
     *
     * @param dataDir The data directory
     * @return The segment file
     * @throws IOException if none does
     */
    private static Path occupiedSegment(Path dataDir) throws IOException {
        for (Path segment : DataDirectoryFixture.segmentFiles(dataDir)) {
            if (SegmentDataCheck.check(segment, segment.getFileName().toString())
                    .detail().startsWith("0 of 0")) {
                continue;
            }
            return segment;
        }
        throw new IOException("No segment under " + dataDir + " holds an entry");
    }

    /**
     * Returns a segment file in a directory whose slot table is empty.
     *
     * @param dataDir The data directory
     * @return The segment file
     * @throws IOException if none is empty
     */
    private static Path emptySegment(Path dataDir) throws IOException {
        for (Path segment : DataDirectoryFixture.segmentFiles(dataDir)) {
            if (SegmentDataCheck.check(segment, segment.getFileName().toString())
                    .detail().startsWith("0 of 0")) {
                return segment;
            }
        }
        throw new IOException("Every segment under " + dataDir + " holds an entry");
    }

    /**
     * Runs the command over a directory and returns its exit code.
     *
     * @param dataDir The directory
     * @param extra   Further arguments
     * @return The exit code
     */
    private static int run(Path dataDir, String... extra) {
        String[] args = new String[extra.length + 1];
        args[0] = dataDir.toString();
        System.arraycopy(extra, 0, args, 1, extra.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return ExcaliburFsck.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
