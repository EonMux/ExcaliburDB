package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileInspection;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.LexicalStoreCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one per-file dispatch, which the directory survey and a snapshot's deep verification
 * both go through.
 *
 * <p>Two callers with a dispatch each would be two definitions of which checker a file
 * belongs to, and the first format added to one and not the other would be verified by
 * {@code excalibur-fsck} and waved through by {@code excalibur-backup verify --deep}, or the
 * reverse. These tests pin the survey's verdicts to the public dispatch's, so the two cannot
 * drift apart without one of them failing.</p>
 */
class FileInspectorTest {

    @Test
    void theSurveyReachesTheSameVerdictAsThePublicDispatch(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir, null).scan();

        assertThat(scan.files()).isNotEmpty();
        for (FileCheck surveyed : scan.files()) {
            FileCheck dispatched =
                FileInspector.inspect(surveyed.path(), surveyed.relative()).check();
            assertThat(dispatched).as(surveyed.relative()).isEqualTo(surveyed);
        }
    }

    @Test
    void theCatalogComesBackWithItsRows(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);

        FileInspection inspection =
            FileInspector.inspect(dataDir.resolve("catalog.dat"), "catalog.dat");

        assertThat(inspection.kind()).isEqualTo(FileKind.CATALOG);
        assertThat(inspection.check().status()).isEqualTo(CheckStatus.INTACT);
        assertThat(inspection.catalogRecords()).isNotEmpty();
        assertThat(inspection.routingSlots()).isEmpty();
    }

    @Test
    void theRoutingDirectoryComesBackWithItsSlots(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);

        FileInspection inspection =
            FileInspector.inspect(dataDir.resolve("routing.dir"), "routing.dir");

        assertThat(inspection.kind()).isEqualTo(FileKind.ROUTING_DIRECTORY);
        assertThat(inspection.routingSlots()).isNotEmpty();
        assertThat(inspection.catalogRecords()).isEmpty();
    }

    @Test
    void aFileWithNothingToVerifySaysSoRatherThanPassing(@TempDir Path tempDir)
            throws IOException {
        Path journal = tempDir.resolve("document-registry.in-flight");
        Files.writeString(journal, "begin|doc-1\n");

        FileInspection inspection =
            FileInspector.inspect(journal, "document-registry.in-flight");

        assertThat(inspection.kind()).isEqualTo(FileKind.IN_FLIGHT_JOURNAL);
        assertThat(inspection.check().status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(inspection.check().detail()).contains("neither a checksum nor a record count");
    }

    @Test
    void theRoutingSlotsAreACopy(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        FileInspection inspection =
            FileInspector.inspect(dataDir.resolve("routing.dir"), "routing.dir");

        int[] slots = inspection.routingSlots();
        slots[0] = -99;

        assertThat(inspection.routingSlots()[0]).isNotEqualTo(-99);
    }

    @Test
    void aDirectoryWithNoLexicalStoreHasNoLexicalVerdict(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        assertThat(FileInspector.lexicalStore(dataDir)).isEmpty();
    }

    @Test
    void anEmptyLexicalStoreDirectoryIsAStoreNeverWrittenNotAMissingManifest(
            @TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.createDirectories(dataDir.resolve(FileClassifier.LEXICAL_STORE_DIRECTORY)
            .resolve(".compact-0"));

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir, null).scan();

        assertThat(FileInspector.lexicalStore(dataDir))
            .as("a node that started and stopped without ingesting leaves exactly this")
            .isEmpty();
        assertThat(scan.files()).noneSatisfy(file -> assertThat(file.status().isDamage())
            .as(file.relative()).isTrue());
    }

    @Test
    void aLexicalStoreWithFilesAndNoManifestIsStillMissingItsManifest(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path store = Files.createDirectories(dataDir.resolve(
            FileClassifier.LEXICAL_STORE_DIRECTORY));
        Files.writeString(store.resolve("seg-0.keys"), "doc-1\n");

        LexicalStoreCheck.LexicalInspection inspection =
            FileInspector.lexicalStore(dataDir).orElseThrow();

        assertThat(inspection.manifest().status()).isEqualTo(CheckStatus.MISSING);
    }

    @Test
    void theLexicalStoreCoversItsManifestAndItsSegmentsButNotItsSidecars(
            @TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path store = Files.createDirectories(dataDir.resolve(
            FileClassifier.LEXICAL_STORE_DIRECTORY));
        Files.writeString(store.resolve("manifest"),
            "excalibur-lexical-store v2\nseg-0\t1\t10\t0\n#end\t1\n");

        Optional<LexicalStoreCheck.LexicalInspection> inspection =
            FileInspector.lexicalStore(dataDir);

        assertThat(inspection).isPresent();
        LexicalStoreCheck.LexicalInspection lexical = inspection.orElseThrow();
        assertThat(lexical.covers("chunk-index/manifest")).isTrue();
        assertThat(lexical.covers("chunk-index/seg-0/postings")).isTrue();
        assertThat(lexical.covers("chunk-index/seg-0.keys"))
            .as("a sidecar beside a segment is not inside it")
            .isFalse();
        assertThat(lexical.covers("chunk-index/seg-01/postings"))
            .as("a segment whose name merely begins with another's is not that segment")
            .isFalse();
        assertThat(lexical.verdicts()).extracting(FileCheck::relative)
            .containsExactly("chunk-index/manifest", "chunk-index/seg-0");
    }

    @Test
    void theSurveyFoldsTheLexicalStoreOverThePerFileVerdicts(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Path store = Files.createDirectories(dataDir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "excalibur-lexical-store v2\n#end\t0\n");
        Files.writeString(store.resolve("seg-0.keys"), "doc-1\n");

        List<FileCheck> lexical = new DataDirectoryScanner(dataDir, null).scan().files()
            .stream().filter(file -> file.relative().startsWith("chunk-index/")).toList();

        assertThat(lexical).extracting(FileCheck::relative)
            .containsExactlyInAnyOrder("chunk-index/manifest", "chunk-index/seg-0.keys");
        assertThat(lexical).filteredOn(file -> file.relative().equals("chunk-index/manifest"))
            .singleElement()
            .satisfies(file -> assertThat(file.format()).isEqualTo(LexicalStoreCheck.FORMAT));
    }
}
