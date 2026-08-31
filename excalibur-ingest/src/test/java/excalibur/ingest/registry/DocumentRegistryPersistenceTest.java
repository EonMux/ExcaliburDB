package excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests for the persisted document registry.
 *
 * <p>Document ids are filesystem paths and absolute URLs, so the characters the record format
 * uses as delimiters occur inside them. These tests pin that such ids survive a save and reload
 * unchanged, and that an unreadable registry is not quietly reported as an empty one.</p>
 */
class DocumentRegistryPersistenceTest {

    @Test
    @DisplayName("document ids containing the field delimiter round-trip")
    void pipeInDocumentIdRoundTrips(@TempDir Path tempDir) throws IOException {
        String documentId = "reports/2024|q1|final.txt";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, documentId + ":chunk:0"));
        registry.persist();

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size()).isEqualTo(1);
        DocumentMetadata loaded = reloaded.get("src", documentId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getDocumentId()).isEqualTo(documentId);
        assertThat(loaded.getChunkIds()).containsExactly(documentId + ":chunk:0");
    }

    @Test
    @DisplayName("document ids containing the chunk delimiter and a backslash round-trip")
    void commaAndBackslashRoundTrip(@TempDir Path tempDir) throws IOException {
        String documentId = "notes/a,b\\c.md";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, documentId + ":chunk:0", documentId + ":chunk:1"));
        registry.persist();

        DocumentMetadata loaded = new DocumentRegistry(file).get("src", documentId);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getChunkIds())
            .containsExactly(documentId + ":chunk:0", documentId + ":chunk:1");
    }

    @Test
    @DisplayName("a newline inside a document id cannot forge a second record")
    void newlineCannotForgeRecord(@TempDir Path tempDir) throws IOException {
        String documentId = "evil\nforged|doc|0|0|0|0|forged:chunk:0";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, "src:chunk:0"));
        registry.persist();

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size())
            .as("one registered document must reload as exactly one record")
            .isEqualTo(1);
        assertThat(reloaded.get("src", documentId)).isNotNull();
    }

    @Test
    @DisplayName("a registry file that cannot be read fails loudly")
    void unreadableRegistryFails(@TempDir Path tempDir) throws IOException {
        // A directory where a file is expected: readable path, unreadable content.
        Path file = tempDir.resolve("registry.dat");
        Files.createDirectory(file);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
            .hasMessageContaining("registry.dat");
    }

    @Test
    @DisplayName("a malformed record is skipped without discarding the rest of the file")
    void malformedRecordIsSkipped(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "good-doc", "src:good-doc:chunk:0"));
        registry.persist();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.add("this is not a record");
        Files.write(file, lines, StandardCharsets.UTF_8);

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.get("src", "good-doc")).isNotNull();
    }

    private static DocumentMetadata metadata(String sourceId, String documentId, String... chunkIds) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(1000L)
            .contentHash(4242L)
            .contentLength(128)
            .indexTimeMillis(2000L)
            .chunkIds(List.of(chunkIds))
            .build();
    }
}
