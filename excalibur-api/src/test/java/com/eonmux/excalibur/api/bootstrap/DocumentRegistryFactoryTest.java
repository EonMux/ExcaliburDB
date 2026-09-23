package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.EndLine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry that has to outlive the process.
 *
 * <p>It was in memory only, and nothing said so. Two things stop working after a restart when it
 * is: a re-crawl reads every document as new, so it reingests the corpus beside the chunks already
 * there and tombstones none of them; and an erasure request reports every document as one this node
 * never held, because the chunk ids it would have removed were recorded nowhere else. Neither
 * announces itself - the node keeps answering, from a corpus that is quietly wrong.
 */
@DisplayName("Document registry factory")
class DocumentRegistryFactoryTest {

    private static DocumentMetadata document(String sourceId, String documentId, int chunks) {
        DocumentMetadata.Builder metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(documentId.hashCode())
            .contentLength(100);
        for (int i = 0; i < chunks; i++) {
            metadata.addChunkId(sourceId + ":" + documentId + ":chunk:" + i);
        }
        return metadata.build();
    }

    @Test
    @DisplayName("a document recorded before a restart is still known after one")
    void survivesARestart(@TempDir Path dataDir) throws Exception {
        DocumentRegistry first = DocumentRegistryFactory.open(dataDir);
        first.register(document("docs", "handbook.md", 3));
        first.persist();

        DocumentRegistry reopened = DocumentRegistryFactory.open(dataDir);

        assertThat(reopened.contains("docs", "handbook.md"))
            .as("without this, a re-crawl reingests it and an erasure cannot name its chunks")
            .isTrue();
        assertThat(reopened.get("docs", "handbook.md").getChunkIds())
            .containsExactly("docs:handbook.md:chunk:0", "docs:handbook.md:chunk:1",
                "docs:handbook.md:chunk:2");
    }

    @Test
    @DisplayName("the registry lives beside the data it describes")
    void writesUnderTheDataDirectory(@TempDir Path dataDir) throws Exception {
        DocumentRegistry registry = DocumentRegistryFactory.open(dataDir);
        registry.register(document("docs", "a.md", 1));
        registry.persist();

        assertThat(dataDir.resolve(DocumentRegistryFactory.FILE))
            .as("a registry somewhere else is one a copied data directory arrives without")
            .exists();
    }

    @Test
    @DisplayName("an erasure recorded before a restart stays erased")
    void erasureSurvivesARestart(@TempDir Path dataDir) throws Exception {
        DocumentRegistry first = DocumentRegistryFactory.open(dataDir);
        first.register(document("docs", "keep.md", 1));
        first.register(document("docs", "erased.md", 1));
        first.persist();

        DocumentRegistry second = DocumentRegistryFactory.open(dataDir);
        second.remove("docs", "erased.md");
        second.persist();

        DocumentRegistry third = DocumentRegistryFactory.open(dataDir);
        assertThat(third.contains("docs", "erased.md")).isFalse();
        assertThat(third.contains("docs", "keep.md")).isTrue();
    }

    @Test
    @DisplayName("a fresh data directory opens an empty registry rather than failing")
    void emptyDirectoryIsNotAnError(@TempDir Path dataDir) {
        assertThat(DocumentRegistryFactory.open(dataDir).size()).isZero();
    }

    @Test
    @DisplayName("a registry that exists and cannot be read fails the start")
    void unreadableRegistryIsFatal(@TempDir Path dataDir) throws Exception {
        // Starting empty is the one wrong answer: every document would read as new, so the next
        // re-crawl duplicates the corpus and orphans every chunk already written. A directory
        // where the file should be is the portable way to make it unreadable.
        Files.createDirectory(dataDir.resolve(DocumentRegistryFactory.FILE));

        assertThatThrownBy(() -> DocumentRegistryFactory.open(dataDir))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class);
    }

    @Test
    @DisplayName("a damaged record costs its own document and not the rest")
    void damagedRecordsAreSkipped(@TempDir Path dataDir) throws Exception {
        // Line-granular rather than all-or-nothing: a record this build cannot parse should cost
        // the document it describes, not every document the node ever indexed.
        DocumentRegistry first = DocumentRegistryFactory.open(dataDir);
        first.register(document("docs", "good.md", 1));
        first.persist();
        Path file = dataDir.resolve(DocumentRegistryFactory.FILE);
        // Among the records, not after the end marker. A line past the marker is what a file
        // truncated by a crash looks like, and that is refused rather than skipped - a different
        // case from the one under test here, so the count and the checksum move with the line.
        List<String> lines =
            new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.add(lines.size() - 1, "not a registry record");
        Files.writeString(file, sealed(lines.subList(0, lines.size() - 1), 2),
            StandardCharsets.UTF_8);

        DocumentRegistry reopened = DocumentRegistryFactory.open(dataDir);

        assertThat(reopened.contains("docs", "good.md")).isTrue();
        assertThat(reopened.size()).isEqualTo(1);
    }

    /**
     * Writes a registry holding one document, then takes away its header and end marker, which
     * is the file a build from before the format carried a version left behind.
     */
    private static Path headerlessRegistry(Path dataDir) throws Exception {
        DocumentRegistry first = DocumentRegistryFactory.open(dataDir);
        first.register(document("docs", "handbook.md", 2));
        first.persist();
        Path file = dataDir.resolve(DocumentRegistryFactory.FILE);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.remove(lines.size() - 1);
        lines.remove(0);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a registry in the format from before the header is rewritten as the node starts")
    void aHeaderlessRegistryIsUpgradedOnOpen(@TempDir Path dataDir) throws Exception {
        // Until it is rewritten, a truncated copy of this file cannot be told from a whole one,
        // and a node that is only queried after an upgrade would otherwise never rewrite it.
        Path file = headerlessRegistry(dataDir);

        DocumentRegistry opened = DocumentRegistryFactory.open(dataDir);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("\\#document-registry|" + DocumentRegistry.FORMAT_VERSION);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .isEqualTo(sealed(lines.subList(0, lines.size() - 1), 1));
        assertThat(opened.isModified()).isFalse();
        assertThat(DocumentRegistryFactory.open(dataDir).get("docs", "handbook.md").getChunkIds())
            .hasSize(2);
    }

    @Test
    @DisplayName("a registry in a version without a checksum is rewritten with one as the node starts")
    void aVersionOneRegistryIsUpgradedOnOpen(@TempDir Path dataDir) throws Exception {
        // Until it is rewritten, a record altered in place reads as a different, well-formed
        // record; the upgrade is what gives every record a checksum to be verified against.
        Path file = dataDir.resolve(DocumentRegistryFactory.FILE);
        Files.write(file, List.of("\\#document-registry|1",
            "docs|old.md|0|42|100|0|docs:old.md:chunk:0", "\\#end|1"), StandardCharsets.UTF_8);

        DocumentRegistry opened = DocumentRegistryFactory.open(dataDir);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("\\#document-registry|2");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .isEqualTo(sealed(lines.subList(0, lines.size() - 1), 1));
        assertThat(opened.isModified()).isFalse();
        assertThat(DocumentRegistryFactory.open(dataDir).get("docs", "old.md").getChunkIds())
            .containsExactly("docs:old.md:chunk:0");
    }

    /**
     * Renders lines followed by the closing line the registry's writer would put after them.
     *
     * @param lines Every line before the closing one, header included
     * @param count The count the closing line declares
     * @return The file's contents
     */
    private static String sealed(List<String> lines, int count) {
        StringBuilder body = new StringBuilder();
        lines.forEach(line -> body.append(line).append('\n'));
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(body.toString().getBytes(StandardCharsets.UTF_8));
        return body + EndLine.render(DocumentRegistry.END_MARKER, count, crc.getValue()) + "\n";
    }

    @Test
    @DisplayName("a registry already in the current format is not rewritten on open")
    void aCurrentRegistryIsLeftAlone(@TempDir Path dataDir) throws Exception {
        DocumentRegistry first = DocumentRegistryFactory.open(dataDir);
        first.register(document("docs", "a.md", 1));
        first.persist();
        Path file = dataDir.resolve(DocumentRegistryFactory.FILE);
        java.nio.file.attribute.FileTime written = java.nio.file.attribute.FileTime.fromMillis(0);
        Files.setLastModifiedTime(file, written);

        DocumentRegistryFactory.open(dataDir);

        assertThat(Files.getLastModifiedTime(file)).isEqualTo(written);
    }

    @Test
    @DisplayName("an upgrade that cannot be saved does not keep the node from starting")
    void anUpgradeThatCannotBeSavedIsNotFatal(@TempDir Path dataDir) throws Exception {
        // The records were read; only the rewrite failed. The next save that has a reason to
        // happen - an ingest, an erasure, shutdown - tries again, and reports its own failure.
        Path file = headerlessRegistry(dataDir);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> before;
        try {
            before = Files.getPosixFilePermissions(dataDir);
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("needs POSIX permissions");
            return;
        }
        Files.setPosixFilePermissions(dataDir,
            java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dataDir),
                "a user that ignores permissions cannot make the save fail");

            DocumentRegistry opened = DocumentRegistryFactory.open(dataDir);

            assertThat(opened.contains("docs", "handbook.md")).isTrue();
            assertThat(opened.isModified()).as("still owed to the next save").isTrue();
        } finally {
            Files.setPosixFilePermissions(dataDir, before);
        }
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8).get(0))
            .doesNotStartWith("\\#document-registry");
    }

    @Test
    @DisplayName("a data directory is required")
    void dataDirectoryIsRequired() {
        assertThatThrownBy(() -> DocumentRegistryFactory.open(null))
            .isInstanceOf(NullPointerException.class);
    }
}
