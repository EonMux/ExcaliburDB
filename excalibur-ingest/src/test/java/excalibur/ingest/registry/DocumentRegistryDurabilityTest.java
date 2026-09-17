package excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
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
 * What the registry file is allowed to say after a crash, and what it must refuse to say.
 *
 * <p>The registry is the node's only map from a document to the chunk ids it was written under.
 * A file that lost its tail is a file of whole, well-formed records that simply omits documents:
 * it loads cleanly, and the documents it dropped are then neither erasable nor reindexable while
 * their chunks stay in storage and stay queryable. Nothing about the shorter file says so, which
 * is why these tests are about what the loader refuses rather than about what it parses.</p>
 */
class DocumentRegistryDurabilityTest {

    @Test
    @DisplayName("a registry cut short by a crash is not read as a shorter registry")
    void aTruncatedRegistryIsNotReadAsAShorterOne(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("document-registry");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "one.md"));
        registry.register(metadata("src", "two.md"));
        registry.register(metadata("src", "three.md"));
        registry.persist();

        // What a host crash leaves when the rename reached the disk and the tail of the contents
        // did not: whole records, correctly named file, and one document simply gone.
        List<String> whole = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.write(file, whole.subList(0, whole.size() - 2), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .as("a registry missing a document must not load as a registry that never held it")
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class);
    }

    @Test
    @DisplayName("a registry from a build that wrote no version is still openable")
    void aRegistryWithoutAVersionStillOpens(@TempDir Path tempDir) throws IOException {
        // Exactly what an older build left behind: records, no header, no end marker.
        Path file = tempDir.resolve("document-registry");
        Files.write(file, List.of("src|old.md|1000|4242|128|2000|src:old.md:chunk:0"),
            StandardCharsets.UTF_8);

        DocumentRegistry registry = new DocumentRegistry(file);

        assertThat(registry.get("src", "old.md")).isNotNull();
        assertThat(registry.get("src", "old.md").getChunkIds())
            .containsExactly("src:old.md:chunk:0");
    }

    @Test
    @DisplayName("a registry written by a newer build is refused rather than reinterpreted")
    void aRegistryFromANewerBuildIsRefused(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("document-registry");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "one.md"));
        registry.persist();

        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(0, "\\#document-registry|99");
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
            .hasMessageContaining("99");
    }

    @Test
    @DisplayName("a registry whose end marker disagrees with what follows it is not loaded")
    void aCountThatDisagreesIsFatal(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("document-registry");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "one.md"));
        registry.register(metadata("src", "two.md"));
        registry.persist();

        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(lines.size() - 1, "\\#end|3");
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
            .hasMessageContaining("declares 3 record(s) and carries 2");
    }

    @Test
    @DisplayName("a registry with a header and nothing else is not loaded")
    void aHeaderWithNoMarkerIsFatal(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("document-registry");
        Files.write(file, List.of("\\#document-registry|1"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
            .hasMessageContaining("cut short");
    }

    @Test
    @DisplayName("a registry from an older build is rewritten in the current format")
    void aRegistryWithoutAVersionIsMigratedOnTheNextSave(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("document-registry");
        Files.write(file, List.of("src|old.md|1000|4242|128|2000|src:old.md:chunk:0"),
            StandardCharsets.UTF_8);

        DocumentRegistry registry = new DocumentRegistry(file);
        // Nothing is registered or erased first: an upgraded node that is quiet for a week must
        // still end up with a file the next crash can be detected against.
        registry.persist();

        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8))
            .containsExactly(
                "\\#document-registry|1",
                "src|old.md|1000|4242|128|2000|src:old.md:chunk:0",
                "\\#end|1");
    }

    @Test
    @DisplayName("a save is forced before it is published, and the directory after")
    void theSaveIsForcedBeforeItIsPublished(@TempDir Path tempDir) throws IOException {
        // A crash cannot be staged, so what is pinned here is the order the three steps happen
        // in. Publishing an unforced replacement names a registry whose contents may never have
        // left the page cache, and forcing the file without forcing the directory leaves the
        // rename itself free to be lost.
        List<String> steps = new ArrayList<>();
        Path file = tempDir.resolve("document-registry");
        DocumentRegistry registry = new DocumentRegistry(file, new RecordingWriter(steps));
        registry.register(metadata("src", "one.md"));

        registry.persist();

        assertThat(steps).containsExactly("force", "publish", "forceDirectory");
    }

    @Test
    @DisplayName("a save that cannot be forced fails rather than reporting success")
    void aFailedForceFailsTheSave(@TempDir Path tempDir) {
        // Every caller goes on to behave as though its documents are recorded. A force that did
        // not happen is a registry that may not be there after a power cut, and saying nothing
        // about it is how a node loses the map from a document to its chunks.
        Path file = tempDir.resolve("document-registry");
        DocumentRegistry registry = new DocumentRegistry(file, new RefusingWriter());
        registry.register(metadata("src", "one.md"));

        assertThatThrownBy(registry::persist)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("this disk does not sync");
        assertThat(Files.exists(file))
            .as("and nothing is published, so the previous registry is what a reader still sees")
            .isFalse();
    }

    /**
     * Performs each step for real and notes that it happened, so the order is observable.
     */
    private record RecordingWriter(List<String> steps) implements DocumentRegistry.RegistryWriter {

        private static final DocumentRegistry.RegistryWriter REAL =
            DocumentRegistry.fileSystemWriter();

        @Override
        public void force(Path path) throws IOException {
            steps.add("force");
            REAL.force(path);
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            steps.add("publish");
            REAL.publish(replacement, target);
        }

        @Override
        public void forceDirectory(Path directory) {
            steps.add("forceDirectory");
            REAL.forceDirectory(directory);
        }
    }

    /** A disk that will not make anything durable, which is a save that must not be believed. */
    private static final class RefusingWriter implements DocumentRegistry.RegistryWriter {

        @Override
        public void force(Path path) throws IOException {
            throw new IOException("this disk does not sync");
        }

        @Override
        public void publish(Path replacement, Path target) {
            throw new AssertionError("a replacement that was never forced must not be published");
        }

        @Override
        public void forceDirectory(Path directory) {
            throw new AssertionError("nothing was published, so there is no rename to force");
        }
    }

    private static DocumentMetadata metadata(String sourceId, String documentId) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(1000L)
            .contentHash(4242L)
            .contentLength(128)
            .indexTimeMillis(2000L)
            .chunkIds(List.of(sourceId + ":" + documentId + ":chunk:0"))
            .build();
    }
}
