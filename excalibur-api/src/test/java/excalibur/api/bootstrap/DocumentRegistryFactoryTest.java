package excalibur.api.bootstrap;

import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;

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
        // case from the one under test here, so the count moves with the line.
        List<String> lines =
            new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.add(lines.size() - 1, "not a registry record");
        lines.set(lines.size() - 1, "\\#end|2");
        Files.write(file, lines, StandardCharsets.UTF_8);

        DocumentRegistry reopened = DocumentRegistryFactory.open(dataDir);

        assertThat(reopened.contains("docs", "good.md")).isTrue();
        assertThat(reopened.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a data directory is required")
    void dataDirectoryIsRequired() {
        assertThatThrownBy(() -> DocumentRegistryFactory.open(null))
            .isInstanceOf(NullPointerException.class);
    }
}
