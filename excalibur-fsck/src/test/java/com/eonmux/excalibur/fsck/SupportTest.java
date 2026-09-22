package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.report.Json;
import com.eonmux.excalibur.fsck.repair.Quarantine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pieces the rest of the tool leans on: naming, escaping and setting files aside.
 */
class SupportTest {

    @Test
    void everyFileNameTheDirectoryHoldsIsClassified() {
        assertThat(FileClassifier.classify("catalog.dat")).isEqualTo(FileKind.CATALOG);
        assertThat(FileClassifier.classify("routing.dir"))
            .isEqualTo(FileKind.ROUTING_DIRECTORY);
        assertThat(FileClassifier.classify("segment_7.dat")).isEqualTo(FileKind.SEGMENT_DATA);
        assertThat(FileClassifier.classify("segment_7.wal"))
            .isEqualTo(FileKind.WRITE_AHEAD_LOG);
        assertThat(FileClassifier.classify("segment_7.hnsw")).isEqualTo(FileKind.HNSW_GRAPH);
        assertThat(FileClassifier.classify("segment_7.hnsw.docmap"))
            .isEqualTo(FileKind.HNSW_DOCUMENT_MAP);
        assertThat(FileClassifier.classify("segment_7.hnsw.pqcb"))
            .isEqualTo(FileKind.PQ_CODEBOOK);
        assertThat(FileClassifier.classify("lsh_table_3.dat")).isEqualTo(FileKind.LSH_TABLE);
        assertThat(FileClassifier.classify("document-registry"))
            .isEqualTo(FileKind.DOCUMENT_REGISTRY);
        assertThat(FileClassifier.classify("document-registry.in-flight"))
            .isEqualTo(FileKind.IN_FLIGHT_JOURNAL);
        assertThat(FileClassifier.classify("source-catalog"))
            .isEqualTo(FileKind.SOURCE_CATALOG);
        assertThat(FileClassifier.classify("chunk-index/seg-0/terms.dict"))
            .isEqualTo(FileKind.LEXICAL_STORE_PART);
        assertThat(FileClassifier.classify("shared/graph-staging/triples.log"))
            .isEqualTo(FileKind.GRAPH_STAGING_LOG);
        assertThat(FileClassifier.classify("mystery")).isEqualTo(FileKind.UNRECOGNISED);
    }

    @Test
    void halfFinishedWritesAreRecognisedWhereverTheyAre() {
        assertThat(FileClassifier.classify("routing.dir.tmp"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.classify("segment_3.dat.compact"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.classify("document-registry.new"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.classify("document-registry.new.17"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.classify("chunk-index/.compact-seg-2"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
    }

    @Test
    void aSegmentNumberIsReadFromEveryNameThatCarriesOne() {
        assertThat(FileClassifier.segmentNumber("segment_12.dat")).isEqualTo(12);
        assertThat(FileClassifier.segmentNumber("segment_12.wal")).isEqualTo(12);
        assertThat(FileClassifier.segmentNumber("segment_12.hnsw.pqcb")).isEqualTo(12);
        assertThat(FileClassifier.segmentNumber("catalog.dat")).isEqualTo(-1);
    }

    @Test
    void jsonStringsAreEscapedSoTheDocumentStaysParseable() {
        assertThat(Json.string(null)).isEqualTo("null");
        assertThat(Json.string("plain")).isEqualTo("\"plain\"");
        assertThat(Json.string("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(Json.string("line\nbreak\ttab\r\b\f"))
            .isEqualTo("\"line\\nbreak\\ttab\\r\\b\\f\"");
        assertThat(Json.string("\u0001")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void quarantineKeepsBothCopiesWhenANameIsTakenTwice(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Quarantine quarantine = new Quarantine(dir.resolve("q"));
        Files.writeString(store.resolve("segment_0.dat"), "first");
        Path first = quarantine.take(store.resolve("segment_0.dat"), "segment_0.dat");
        Files.writeString(store.resolve("segment_0.dat"), "second");

        Path second = quarantine.take(store.resolve("segment_0.dat"), "segment_0.dat");

        assertThat(second).isNotEqualTo(first);
        assertThat(Files.readString(first)).isEqualTo("first");
        assertThat(Files.readString(second)).isEqualTo("second");
    }

    @Test
    void quarantineFlattensNestedPathsIntoOneDirectory(@TempDir Path dir) throws IOException {
        Path nested = Files.createDirectories(dir.resolve("data/chunk-index"));
        Files.writeString(nested.resolve("manifest"), "x");
        Quarantine quarantine = new Quarantine(dir.resolve("q"));

        Path taken = quarantine.take(nested.resolve("manifest"), "chunk-index/manifest");

        assertThat(taken.getFileName()).hasToString("chunk-index_manifest");
        assertThat(quarantine.directory()).isEqualTo(dir.resolve("q"));
    }

    @Test
    void anUnknownOptionIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> FsckOptionsParser.parse(new String[] { "/tmp", "--repare" }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--repare");
    }

    @Test
    void aSecondDataDirectoryIsRefused() {
        assertThatThrownBy(() -> FsckOptionsParser.parse(new String[] { "/a", "/b" }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("second");
    }

    @Test
    void aFlagThatNeedsAValueIsRefusedWithoutOne() {
        assertThatThrownBy(() ->
            FsckOptionsParser.parse(new String[] { "/tmp", "--quarantine-dir" }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a value");
    }

    @Test
    void quarantineDefaultsBesideTheDirectoryRatherThanInsideIt() {
        FsckOptions options = FsckOptionsParser.parse(new String[] { "/var/lib/excalibur" });

        assertThat(options.repair()).isFalse();
        assertThat(options.json()).isFalse();
        assertThat(options.strict()).isFalse();
        assertThat(options.quarantineDirectory().getParent())
            .as("a quarantine inside the data directory ends up in the next backup")
            .isEqualTo(Path.of("/var/lib"));
    }

    @Test
    void everyFlagIsUnderstood() {
        FsckOptions options = FsckOptionsParser.parse(new String[] {
            "--json", "/data", "--repair", "--strict", "--quarantine-dir=/tmp/q" });

        assertThat(options.dataDirectory()).isEqualTo(Path.of("/data"));
        assertThat(options.repair()).isTrue();
        assertThat(options.json()).isTrue();
        assertThat(options.strict()).isTrue();
        assertThat(options.quarantineDirectory()).isEqualTo(Path.of("/tmp/q"));
    }

    @Test
    void usageNamesEveryExitCode() {
        assertThat(FsckOptionsParser.usage())
            .contains("--repair", "--json", "--strict", "--quarantine-dir")
            .contains("0  nothing damaged", "1  damage", "2  the command line", "3  the check");
        assertThat(FsckExit.CLEAN).isZero();
        assertThat(FsckExit.DAMAGE).isEqualTo(1);
        assertThat(FsckExit.USAGE).isEqualTo(2);
        assertThat(FsckExit.FAILED).isEqualTo(3);
    }
}
