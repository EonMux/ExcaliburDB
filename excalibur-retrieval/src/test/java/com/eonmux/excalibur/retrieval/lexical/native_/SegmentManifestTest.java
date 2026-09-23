package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one file that decides what a store contains, and what happens when it is not all there.
 *
 * <p>The manifest is the sole authority on which segments belong to the store, and everything it
 * does not name is deleted on the next open. That makes a manifest which parses as valid while
 * naming fewer segments than it was written with the most destructive single failure this engine
 * has: the segments it forgot are removed, and nothing anywhere reports a loss. So these fix the
 * two halves of the defence - that a manifest which lost bytes is refused rather than believed,
 * and that the bytes reach the device before the rename that publishes them.
 */
@DisplayName("Segment manifest")
class SegmentManifestTest {

    private static SegmentManifest of(SegmentManifest.Entry... entries) {
        SegmentManifest manifest = SegmentManifest.empty();
        for (SegmentManifest.Entry entry : entries) {
            manifest.add(entry);
        }
        return manifest;
    }

    private static Path manifestFile(Path directory) {
        return directory.resolve("manifest");
    }

    private static List<String> linesOf(Path directory) throws IOException {
        return Files.readAllLines(manifestFile(directory), StandardCharsets.UTF_8);
    }

    private static void rewrite(Path directory, List<String> lines) throws IOException {
        Files.writeString(manifestFile(directory), String.join("\n", lines) + "\n",
            StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("every entry comes back exactly as it was written")
        void entriesSurvive(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0)).write(dir);

            assertThat(SegmentManifest.read(dir).entries()).containsExactly(
                new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0));
        }

        @Test
        @DisplayName("an empty store writes a manifest that reads back as empty")
        void emptySurvives(@TempDir Path dir) throws Exception {
            SegmentManifest.empty().write(dir);

            assertThat(SegmentManifest.read(dir).entries()).isEmpty();
            assertThat(SegmentManifest.read(dir).verified()).isTrue();
        }

        @Test
        @DisplayName("a store with no manifest at all is a new store, not a damaged one")
        void absentIsEmpty(@TempDir Path dir) throws Exception {
            assertThat(SegmentManifest.read(dir).entries()).isEmpty();
            assertThat(SegmentManifest.read(dir).verified()).isTrue();
        }

        @Test
        @DisplayName("nothing is left beside the manifest once it is published")
        void noTemporaryFileRemains(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0)).write(dir);

            try (var listing = Files.list(dir)) {
                assertThat(listing.map(path -> path.getFileName().toString()))
                    .containsExactly("manifest");
            }
        }
    }

    @Nested
    @DisplayName("a manifest that lost bytes")
    class Damaged {

        @Test
        @DisplayName("one whose write never reached the device is refused, not read as empty")
        void zeroLengthIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0),
                new SegmentManifest.Entry("seg-1", 2, 5, 0)).write(dir);
            Files.write(manifestFile(dir), new byte[0]);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("one cut short at a record boundary is refused, not read as a shorter store")
        void truncatedAtARecordBoundaryIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0),
                new SegmentManifest.Entry("seg-1", 2, 5, 0)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            // Exactly what a torn write leaves: whole records, and no sign in any of them that
            // the ones behind them are gone.
            lines.remove(lines.size() - 1);
            lines.remove(lines.size() - 1);
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("one whose declared count disagrees with its records is refused")
        void countDisagreementIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0),
                new SegmentManifest.Entry("seg-1", 2, 5, 0)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.remove(1);
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2");
        }

        @Test
        @DisplayName("one that does not start with the header is refused")
        void foreignHeaderIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(0, "some other file entirely");
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("one whose record lost a field is refused")
        void partialRecordIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 0)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(1, "seg-0\t1");
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir)).isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("the checksum on the end line")
    class Checksum {

        private byte[] bytesOf(Path dir) throws IOException {
            return Files.readAllBytes(manifestFile(dir));
        }

        @Test
        @DisplayName("is written at the current version and covers every byte before the end line")
        void coversEverythingBeforeTheEndLine(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2)).write(dir);
            List<String> lines = linesOf(dir);
            byte[] bytes = bytesOf(dir);
            String endLine = lines.get(lines.size() - 1);
            int covered = bytes.length - endLine.length() - 1;
            java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
            crc.update(bytes, 0, covered);

            assertThat(lines.get(0)).isEqualTo("excalibur-lexical-store v" + SegmentManifest.VERSION);
            assertThat(endLine).isEqualTo("#end\t1\t" + String.format("%08x", crc.getValue()));
            assertThat(SegmentManifest.read(dir).version()).isEqualTo(SegmentManifest.VERSION);
            assertThat(SegmentManifest.read(dir).verified()).isTrue();
        }

        @Test
        @DisplayName("refuses a manifest one of whose bytes changed, naming the file and the mismatch")
        void flippedByteIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0)).write(dir);
            byte[] bytes = bytesOf(dir);
            // The epoch of seg-1: still a well-formed record, so only the checksum can tell.
            int at = new String(bytes, StandardCharsets.UTF_8).indexOf("seg-1\t4") + 6;
            bytes[at] = '5';
            Files.write(manifestFile(dir), bytes);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(manifestFile(dir).toString())
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("refuses a manifest whose checksum field itself was altered")
        void alteredChecksumIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(lines.size() - 1, "#end\t1\t00000000");
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("refuses an end line that carries a count and no checksum")
        void endLineWithoutChecksumIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(lines.size() - 1, "#end\t1");
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(manifestFile(dir).toString());
        }

        @Test
        @DisplayName("refuses a checksum that is not eight hexadecimal digits")
        void malformedChecksumIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(lines.size() - 1, "#end\t1\tnot-hex!");
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not-hex!");
        }

        @Test
        @DisplayName("still refuses one cut short, which loses the end line with the checksum on it")
        void truncationIsStillRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0)).write(dir);
            byte[] bytes = bytesOf(dir);
            Files.write(manifestFile(dir), java.util.Arrays.copyOf(bytes, bytes.length / 2));

            assertThatThrownBy(() -> SegmentManifest.read(dir)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("refuses a manifest from a newer build, naming the version it found")
        void newerVersionIsRefused(@TempDir Path dir) throws Exception {
            of(new SegmentManifest.Entry("seg-0", 1, 10, 2)).write(dir);
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.set(0, "excalibur-lexical-store v" + (SegmentManifest.VERSION + 1));
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir))
                .isInstanceOfSatisfying(SegmentManifest.UnsupportedVersionException.class,
                    e -> assertThat(e.version()).isEqualTo(SegmentManifest.VERSION + 1))
                .hasMessageContaining("version " + (SegmentManifest.VERSION + 1));
        }
    }

    @Nested
    @DisplayName("a manifest from a build that counted its records and did not checksum them")
    class CountedWithoutChecksum {

        private void writeCounted(Path dir, String... records) throws IOException {
            StringBuilder text = new StringBuilder("excalibur-lexical-store v2\n");
            for (String record : records) {
                text.append(record).append('\n');
            }
            text.append("#end\t").append(records.length).append('\n');
            Files.writeString(manifestFile(dir), text.toString(), StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("is read as it always was, and is verified by its count")
        void stillReads(@TempDir Path dir) throws Exception {
            writeCounted(dir, "seg-0\t1\t10\t2", "seg-1\t4\t7\t0");

            SegmentManifest read = SegmentManifest.read(dir);

            assertThat(read.entries()).containsExactly(
                new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0));
            assertThat(read.verified()).isTrue();
            assertThat(read.version()).isEqualTo(2);
        }

        @Test
        @DisplayName("is still refused when its count disagrees with its records")
        void countStillChecked(@TempDir Path dir) throws Exception {
            writeCounted(dir, "seg-0\t1\t10\t2", "seg-1\t4\t7\t0");
            List<String> lines = new ArrayList<>(linesOf(dir));
            lines.remove(1);
            rewrite(dir, lines);

            assertThatThrownBy(() -> SegmentManifest.read(dir)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("is rewritten at the current version, with its checksum, by the next write")
        void rewritingUpgradesIt(@TempDir Path dir) throws Exception {
            writeCounted(dir, "seg-0\t1\t10\t2");

            SegmentManifest.read(dir).write(dir);

            assertThat(linesOf(dir).get(0))
                .isEqualTo("excalibur-lexical-store v" + SegmentManifest.VERSION);
            assertThat(linesOf(dir).get(linesOf(dir).size() - 1)).matches("#end\t1\t[0-9a-f]{8}");
            assertThat(SegmentManifest.read(dir).version()).isEqualTo(SegmentManifest.VERSION);
            assertThat(SegmentManifest.read(dir).entries())
                .containsExactly(new SegmentManifest.Entry("seg-0", 1, 10, 2));
        }
    }

    @Nested
    @DisplayName("a manifest from a build that had no count marker")
    class Legacy {

        private void writeLegacy(Path dir, String... records) throws IOException {
            StringBuilder text = new StringBuilder("excalibur-lexical-store v1\n");
            for (String record : records) {
                text.append(record).append('\n');
            }
            Files.writeString(manifestFile(dir), text.toString(), StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("it still opens, so an existing data directory is not made unreadable")
        void stillReads(@TempDir Path dir) throws Exception {
            writeLegacy(dir, "seg-0\t1\t10\t2", "seg-1\t4\t7\t0");

            assertThat(SegmentManifest.read(dir).entries()).containsExactly(
                new SegmentManifest.Entry("seg-0", 1, 10, 2),
                new SegmentManifest.Entry("seg-1", 4, 7, 0));
        }

        @Test
        @DisplayName("it is not verified, because nothing in it could say it was all there")
        void isNotVerified(@TempDir Path dir) throws Exception {
            writeLegacy(dir, "seg-0\t1\t10\t2");

            assertThat(SegmentManifest.read(dir).verified()).isFalse();
        }

        @Test
        @DisplayName("the first rewrite upgrades it, and the next read is verified")
        void rewritingUpgradesIt(@TempDir Path dir) throws Exception {
            writeLegacy(dir, "seg-0\t1\t10\t2");

            SegmentManifest.read(dir).write(dir);

            assertThat(SegmentManifest.read(dir).verified()).isTrue();
            assertThat(SegmentManifest.read(dir).version()).isEqualTo(SegmentManifest.VERSION);
            assertThat(SegmentManifest.read(dir).entries())
                .containsExactly(new SegmentManifest.Entry("seg-0", 1, 10, 2));
        }

        @Test
        @DisplayName("it reports the version it was read at")
        void reportsItsVersion(@TempDir Path dir) throws Exception {
            writeLegacy(dir, "seg-0\t1\t10\t2");

            assertThat(SegmentManifest.read(dir).version()).isEqualTo(1);
        }
    }
}
