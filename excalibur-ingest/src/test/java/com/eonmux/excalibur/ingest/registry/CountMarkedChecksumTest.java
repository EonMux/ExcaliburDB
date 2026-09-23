package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.SourceDescriptor;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checksum each count-marked file closes with, and what each reader does when it fails.
 *
 * <p>The count catches a file that lost its tail; it cannot catch a record altered in place,
 * which reads as a different but perfectly well-formed record. Each case here alters a byte the
 * count cannot see, and requires the reader to answer exactly as it answers any other file it
 * will not take: the registry and the catalog refuse to open, the status history is set
 * aside.</p>
 */
class CountMarkedChecksumTest {

    @Nested
    @DisplayName("document-registry")
    class Registry {

        @Test
        @DisplayName("a save closes with the count and the checksum of every byte before it")
        void aSaveIsChecksummed(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("document-registry");
            DocumentRegistry registry = new DocumentRegistry(file);
            registry.register(metadata("src", "one.md"));
            registry.persist();

            String written = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String body = written.substring(0, written.lastIndexOf(DocumentRegistry.END_MARKER));

            assertThat(lines.get(0)).isEqualTo("\\#document-registry|2");
            assertThat(DocumentRegistry.FORMAT_VERSION).isEqualTo(2);
            assertThat(lines.get(lines.size() - 1))
                .isEqualTo("\\#end|1|" + EndLine.hex(Sealed.crc(body)));
            assertThat(new DocumentRegistry(file).get("src", "one.md"))
                .isEqualTo(registry.get("src", "one.md"));
        }

        @Test
        @DisplayName("a record altered in place is refused, naming the checksum")
        void aFlippedByteIsRefused(@TempDir Path dir) throws IOException {
            Path file = saved(dir);
            Sealed.flipInSecondLine(file, 0);

            assertThatThrownBy(() -> new DocumentRegistry(file))
                .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("checksum does not match");
        }

        @Test
        @DisplayName("a registry cut short is still refused as one")
        void aTruncatedRegistryIsRefused(@TempDir Path dir) throws IOException {
            Path file = saved(dir);
            byte[] bytes = Files.readAllBytes(file);
            Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 12));

            assertThatThrownBy(() -> new DocumentRegistry(file))
                .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
                .hasMessageContaining("cut short");
        }

        @Test
        @DisplayName("a checksummed registry whose closing line lost its checksum is refused")
        void aClosingLineWithoutAChecksumIsRefused(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("document-registry");
            Files.writeString(file, "\\#document-registry|2\n\\#end|0\n");

            assertThatThrownBy(() -> new DocumentRegistry(file))
                .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
                .hasMessageContaining("cut short");
        }

        @Test
        @DisplayName("a version 1 registry is read, and rewritten in version 2 at the next save")
        void aVersionOneRegistryIsUpgraded(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("document-registry");
            Files.write(file, List.of("\\#document-registry|1",
                "src|old.md|1000|4242|128|2000|src:old.md:chunk:0", "\\#end|1"),
                StandardCharsets.UTF_8);

            DocumentRegistry registry = new DocumentRegistry(file);
            assertThat(registry.get("src", "old.md")).isNotNull();
            assertThat(registry.isModified())
                .as("a registry with nothing to check its records against owes a save")
                .isTrue();
            registry.persist();

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines.get(0)).isEqualTo("\\#document-registry|2");
            assertThat(lines.get(2)).startsWith("\\#end|1|");
            assertThat(new DocumentRegistry(file).get("src", "old.md").getChunkIds())
                .containsExactly("src:old.md:chunk:0");
        }

        @Test
        @DisplayName("a version above the newest is refused")
        void aNewerVersionIsRefused(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("document-registry");
            Sealed.write(file, List.of("\\#document-registry|3"), DocumentRegistry.END_MARKER, 0);

            assertThatThrownBy(() -> new DocumentRegistry(file))
                .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
                .hasMessageContaining("format version 3");
        }

        private Path saved(Path dir) throws IOException {
            Path file = dir.resolve("document-registry");
            DocumentRegistry registry = new DocumentRegistry(file);
            registry.register(metadata("src", "one.md"));
            registry.register(metadata("src", "two.md"));
            registry.persist();
            return file;
        }
    }

    @Nested
    @DisplayName("source-catalog")
    class Catalog {

        @Test
        @DisplayName("a rewrite closes with the count and the checksum of every byte before it")
        void aRewriteIsChecksummed(@TempDir Path dir) throws IOException {
            SourceCatalog catalog = SourceCatalog.open(dir);
            catalog.record(new SourceDescriptor("filesystem", "a", Map.of("root_path", "/a")));
            Path file = dir.resolve(SourceCatalog.FILE);

            String written = Files.readString(file, StandardCharsets.UTF_8);
            String body = written.substring(0, written.lastIndexOf(SourceCatalog.END_MARKER));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

            assertThat(lines.get(0)).isEqualTo("\\#source-catalog|3");
            assertThat(SourceCatalog.FORMAT_VERSION).isEqualTo(3);
            assertThat(lines.get(lines.size() - 1))
                .isEqualTo("\\#end|1|" + EndLine.hex(Sealed.crc(body)));
            assertThat(SourceCatalog.open(dir).all()).containsExactlyElementsOf(catalog.all());
        }

        @Test
        @DisplayName("a record altered in place is refused, naming the checksum")
        void aFlippedByteIsRefused(@TempDir Path dir) throws IOException {
            SourceCatalog.open(dir).record(
                new SourceDescriptor("filesystem", "a", Map.of("root_path", "/a")));
            Path file = dir.resolve(SourceCatalog.FILE);
            Sealed.flipInSecondLine(file, 12);

            assertThatThrownBy(() -> SourceCatalog.open(dir))
                .isInstanceOf(SourceCatalog.CatalogLoadException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("checksum does not match");
        }

        @Test
        @DisplayName("a catalog cut short is still refused as one")
        void aTruncatedCatalogIsRefused(@TempDir Path dir) throws IOException {
            SourceCatalog catalog = SourceCatalog.open(dir);
            catalog.record(new SourceDescriptor("filesystem", "a", Map.of("root_path", "/a")));
            catalog.record(new SourceDescriptor("filesystem", "b", Map.of("root_path", "/b")));
            Path file = dir.resolve(SourceCatalog.FILE);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

            assertThatThrownBy(() -> SourceCatalog.open(dir))
                .isInstanceOf(SourceCatalog.CatalogLoadException.class)
                .hasMessageContaining("cut short");
        }

        @Test
        @DisplayName("a version 2 catalog is read, and rewritten in version 3 by the next change")
        void aVersionTwoCatalogIsUpgraded(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(SourceCatalog.FILE);
            Files.write(file, List.of("\\#source-catalog|2", "filesystem|old|acme|root_path=/a",
                "\\#end|1"), StandardCharsets.UTF_8);

            SourceCatalog catalog = SourceCatalog.open(dir);
            assertThat(catalog.all()).extracting(SourceDescriptor::sourceId).containsExactly("old");
            catalog.record(new SourceDescriptor("filesystem", "new", Map.of("root_path", "/b")));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines.get(0)).isEqualTo("\\#source-catalog|3");
            assertThat(lines.get(lines.size() - 1)).startsWith("\\#end|2|");
            assertThat(SourceCatalog.open(dir).all()).extracting(SourceDescriptor::sourceId)
                .containsExactlyInAnyOrder("old", "new");
        }

        @Test
        @DisplayName("a version above the newest is refused")
        void aNewerVersionIsRefused(@TempDir Path dir) throws IOException {
            Sealed.write(dir.resolve(SourceCatalog.FILE), List.of("\\#source-catalog|4"),
                SourceCatalog.END_MARKER, 0);

            assertThatThrownBy(() -> SourceCatalog.open(dir))
                .isInstanceOf(SourceCatalog.CatalogLoadException.class)
                .hasMessageContaining("format version 4");
        }
    }

    @Nested
    @DisplayName("source-status")
    class Status {

        private final SourceStatusFile.Entry entry =
            new SourceStatusFile.Entry("docs", "idle", 1.0, 3, 0, 1, 2, 1000L, "boom");

        @Test
        @DisplayName("a rewrite closes with the count and the checksum of every byte before it")
        void aRewriteIsChecksummed(@TempDir Path dir) throws IOException {
            SourceStatusFile.open(dir).write(List.of(entry));
            Path file = dir.resolve(SourceStatusFile.FILE);

            String written = Files.readString(file, StandardCharsets.UTF_8);
            String body = written.substring(0, written.lastIndexOf(SourceStatusFile.END_MARKER));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

            assertThat(lines.get(0)).isEqualTo("\\#source-status|3");
            assertThat(SourceStatusFile.FORMAT_VERSION).isEqualTo(3);
            assertThat(lines.get(lines.size() - 1))
                .isEqualTo("\\#end|1|" + EndLine.hex(Sealed.crc(body)));
            assertThat(SourceStatusFile.open(dir).loaded()).containsExactly(Map.entry("docs", entry));
        }

        @Test
        @DisplayName("a record altered in place is set aside, as any history it will not read is")
        void aFlippedByteIsSetAside(@TempDir Path dir) throws IOException {
            SourceStatusFile.open(dir).write(List.of(entry));
            // The ingested count: '3' becomes '2', a record that parses and says the wrong thing.
            Sealed.flipInSecondLine(dir.resolve(SourceStatusFile.FILE), "docs|idle|1.0|".length());

            assertThat(SourceStatusFile.open(dir).loaded()).isEmpty();
        }

        @Test
        @DisplayName("a history cut short is still set aside")
        void aTruncatedHistoryIsSetAside(@TempDir Path dir) throws IOException {
            SourceStatusFile.open(dir).write(List.of(entry));
            Path file = dir.resolve(SourceStatusFile.FILE);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

            assertThat(SourceStatusFile.open(dir).loaded()).isEmpty();
        }

        @Test
        @DisplayName("a version 2 history is read, and rewritten in version 3 when a run ends")
        void aVersionTwoHistoryIsUpgraded(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(SourceStatusFile.FILE);
            Files.writeString(file,
                "\\#source-status|2\ndocs|idle|1.0|3|0|1|2|1000|boom\n\\#end|1\n");

            SourceStatusFile status = SourceStatusFile.open(dir);
            assertThat(status.loaded()).containsExactly(Map.entry("docs", entry));
            status.write(new ArrayList<>(status.loaded().values()));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines.get(0)).isEqualTo("\\#source-status|3");
            assertThat(lines.get(lines.size() - 1)).startsWith("\\#end|1|");
        }

        @Test
        @DisplayName("a version above the newest is set aside")
        void aNewerVersionIsSetAside(@TempDir Path dir) throws IOException {
            Sealed.write(dir.resolve(SourceStatusFile.FILE),
                List.of("\\#source-status|4", "docs|idle|1.0|3|0|1|2|1000"),
                SourceStatusFile.END_MARKER, 1);

            assertThat(SourceStatusFile.open(dir).loaded()).isEmpty();
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
