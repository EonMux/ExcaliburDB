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
            assertThat(SegmentManifest.read(dir).entries())
                .containsExactly(new SegmentManifest.Entry("seg-0", 1, 10, 2));
        }
    }
}
