package com.eonmux.excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a node remembers about how each source's last crawl ended.
 *
 * <p>{@code GET /v1/ingest/status} is the answer to "is the index following this source?", and a
 * node that forgets it at every restart answers with zero counts and the restart's own time for
 * every source - a corpus that was crawled a minute ago and one last crawled before a restart
 * three weeks back read the same. Unlike the source catalog, what is kept here is a report rather
 * than a promise, so a file that cannot be read costs the history and never the start.</p>
 */
@DisplayName("Source status file")
class SourceStatusFileTest {

    private static SourceStatusFile.Entry entry(String sourceId, String lastError) {
        return new SourceStatusFile.Entry(sourceId, "idle", 1.0, 420, 0, 7, 3,
            1_730_000_000_000L, lastError);
    }

    @Test
    @DisplayName("what a run ended with survives the process, error included")
    void anEntrySurvivesTheProcess(@TempDir Path dataDir) throws IOException {
        SourceStatusFile file = SourceStatusFile.open(dataDir);
        SourceStatusFile.Entry clean = entry("docs", null);
        SourceStatusFile.Entry failed = new SourceStatusFile.Entry("site", "unconfigured", 0.0,
            0, 12, 0, 0, 1_730_000_000_123L, "No ingestion pipeline is configured");

        file.write(List.of(clean, failed));

        assertThat(SourceStatusFile.open(dataDir).loaded())
            .containsExactlyInAnyOrderEntriesOf(Map.of("docs", clean, "site", failed));
    }

    @Test
    @DisplayName("ids and errors carrying the format's own delimiters round-trip")
    void delimitersRoundTrip(@TempDir Path dataDir) throws IOException {
        SourceStatusFile.Entry awkward = entry("a|b=c\\d",
            "3 of 10 documents failed|see\\the log=now\nsecond line\r");

        SourceStatusFile.open(dataDir).write(List.of(awkward));

        assertThat(SourceStatusFile.open(dataDir).loaded()).containsExactly(
            Map.entry("a|b=c\\d", awkward));
    }

    @Test
    @DisplayName("a data directory from before the file existed opens with nothing remembered")
    void aMissingFileIsAnEmptyHistory(@TempDir Path dataDir) {
        SourceStatusFile file = SourceStatusFile.open(dataDir);

        assertThat(file.loaded()).isEmpty();
        assertThat(dataDir.resolve(SourceStatusFile.FILE))
            .as("opening writes nothing")
            .doesNotExist();
    }

    @Test
    @DisplayName("the file carries a versioned header and a record count")
    void theFormatIsMarked(@TempDir Path dataDir) throws IOException {
        SourceStatusFile.open(dataDir).write(List.of(entry("docs", null)));

        List<String> lines =
            Files.readAllLines(dataDir.resolve(SourceStatusFile.FILE), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("\\#source-status|2");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("\\#end|1");
        assertThat(lines).hasSize(3);
    }

    @Test
    @DisplayName("a file that lost its tail is not read as a shorter history")
    void aTruncatedFileIsSetAside(@TempDir Path dataDir) throws IOException {
        SourceStatusFile.open(dataDir).write(List.of(entry("a", null), entry("b", null)));
        Path path = dataDir.resolve(SourceStatusFile.FILE);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Files.write(path, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
    }

    @Test
    @DisplayName("a count that disagrees with the records is not trusted")
    void aCountThatDisagrees(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve(SourceStatusFile.FILE),
            "\\#source-status|1\ndocs|idle|1.0|1|0|0|5\n\\#end|2\n");

        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
    }

    @Test
    @DisplayName("a disallowed count is written in its own field")
    void theDisallowedCountIsWritten(@TempDir Path dataDir) throws IOException {
        SourceStatusFile.open(dataDir).write(List.of(entry("docs", "partial")));

        List<String> lines =
            Files.readAllLines(dataDir.resolve(SourceStatusFile.FILE), StandardCharsets.UTF_8);
        assertThat(lines.get(1)).isEqualTo("docs|idle|1.0|420|0|7|3|1730000000000|partial");
    }

    @Test
    @DisplayName("a file from the build before the disallowed count reads it as zero")
    void aVersionOneFileLoads(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve(SourceStatusFile.FILE),
            "\\#source-status|1\n"
                + "docs|idle|1.0|420|0|7|1730000000000\n"
                + "site|idle|1.0|3|0|0|1730000000001|2 of 5 documents were not ingested\n"
                + "\\#end|2\n");

        Map<String, SourceStatusFile.Entry> loaded = SourceStatusFile.open(dataDir).loaded();

        assertThat(loaded.get("docs")).isEqualTo(new SourceStatusFile.Entry(
            "docs", "idle", 1.0, 420, 0, 7, 0, 1_730_000_000_000L, null));
        assertThat(loaded.get("site").lastError()).isEqualTo("2 of 5 documents were not ingested");
        assertThat(loaded.get("site").disallowed()).isZero();
    }

    @Test
    @DisplayName("a version one record is not read with version two's fields")
    void recordsAreReadInTheirOwnVersion(@TempDir Path dataDir) throws IOException {
        // Eight fields: in version 1 the eighth is the error; read as version 2 it would be a
        // time that does not parse, and the history would be lost.
        Files.writeString(dataDir.resolve(SourceStatusFile.FILE),
            "\\#source-status|1\ndocs|idle|1.0|1|0|0|5|12\n\\#end|1\n");

        assertThat(SourceStatusFile.open(dataDir).loaded().get("docs").lastError())
            .isEqualTo("12");
    }

    @Test
    @DisplayName("a file from a newer build is not guessed at")
    void aNewerFormatIsNotRead(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve(SourceStatusFile.FILE),
            "\\#source-status|9\ndocs|idle|1.0|1|0|0|5\n\\#end|1\n");

        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
    }

    @Test
    @DisplayName("a file with no header, or an empty one, is not read")
    void anUnmarkedFileIsNotRead(@TempDir Path dataDir) throws IOException {
        Path path = dataDir.resolve(SourceStatusFile.FILE);
        Files.writeString(path, "docs|idle|1.0|1|0|0|5\n\\#end|1\n");
        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();

        Files.writeString(path, "");
        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();

        Files.writeString(path, "\\#source-status|x\n\\#end|0\n");
        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
    }

    @Test
    @DisplayName("a malformed record costs that source's history, not everyone's")
    void aMalformedRecordIsSkipped(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve(SourceStatusFile.FILE),
            "\\#source-status|1\n"
                + "good|idle|1.0|3|0|1|5\n"
                + "short|idle\n"
                + "nan|idle|not-a-number|3|0|1|5\n"
                + "negative|idle|1.0|-3|0|1|5\n"
                + "\\#end|4\n");

        assertThat(SourceStatusFile.open(dataDir).loaded()).containsOnlyKeys("good");
    }

    @Test
    @DisplayName("an unreadable file is a lost history, not a failed start")
    void anUnreadableFileDoesNotStopTheNode(@TempDir Path dataDir) throws IOException {
        Files.createDirectory(dataDir.resolve(SourceStatusFile.FILE));

        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
    }

    @Test
    @DisplayName("a rewrite is forced before it is renamed, and the directory after")
    void theRewriteIsForcedBeforeItIsPublished(@TempDir Path dataDir) throws IOException {
        List<String> steps = new ArrayList<>();
        Path path = dataDir.resolve(SourceStatusFile.FILE);
        SourceStatusFile file = new SourceStatusFile(path, new RecordingWriter(steps));

        file.write(List.of(entry("docs", null)));

        assertThat(steps).containsExactly(
            "force " + path.getFileName() + ".tmp",
            "publish",
            "forceDirectory " + dataDir.getFileName());
    }

    @Test
    @DisplayName("a rewrite that cannot be published leaves the previous file and no scratch")
    void aFailedRewriteLeavesThePreviousFile(@TempDir Path dataDir) throws IOException {
        Path path = dataDir.resolve(SourceStatusFile.FILE);
        SourceStatusFile.open(dataDir).write(List.of(entry("docs", null)));
        String before = Files.readString(path);
        SourceStatusFile failing = new SourceStatusFile(path, new SourceCatalog.CatalogWriter() {
            @Override
            public void force(Path file) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void publish(Path replacement, Path target) {
                throw new AssertionError("never reached");
            }

            @Override
            public void forceDirectory(Path directory) {
                throw new AssertionError("never reached");
            }
        });

        assertThatThrownBy(() -> failing.write(List.of(entry("other", null))))
            .isInstanceOf(IOException.class);

        assertThat(Files.readString(path)).isEqualTo(before);
        assertThat(dataDir.resolve(SourceStatusFile.FILE + ".tmp")).doesNotExist();
    }

    @Test
    @DisplayName("an entry refuses values the readback could never have reported")
    void anEntryIsValidated() {
        assertThatThrownBy(() -> new SourceStatusFile.Entry(null, "idle", 0, 0, 0, 0, 0, 0, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceStatusFile.Entry("docs", null, 0, 0, 0, 0, 0, 0, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceStatusFile.Entry("docs", "idle", 0, -1, 0, 0, 0, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceStatusFile.Entry("docs", "idle", 0, 0, 0, 0, -1, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceStatusFile.Entry("docs", "idle", 2.0, 0, 0, 0, 0, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Performs each step for real and notes that it happened, so the order is observable.
     */
    private record RecordingWriter(List<String> steps) implements SourceCatalog.CatalogWriter {

        private static final SourceCatalog.CatalogWriter REAL = SourceCatalog.fileSystemWriter();

        @Override
        public void force(Path path) throws IOException {
            steps.add("force " + path.getFileName());
            REAL.force(path);
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            steps.add("publish");
            REAL.publish(replacement, target);
        }

        @Override
        public void forceDirectory(Path directory) {
            steps.add("forceDirectory " + directory.getFileName());
            REAL.forceDirectory(directory);
        }
    }
}
