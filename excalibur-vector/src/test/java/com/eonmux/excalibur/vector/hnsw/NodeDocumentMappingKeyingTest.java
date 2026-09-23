package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a node-to-document mapping's keys name, and how that survives being written down.
 *
 * <p>The entry layout of a version 2 file and a version 3 file is identical; only the version
 * says whether the strings in it are document keys or chunk ids. So the version written has to
 * follow what the entries actually are, in both directions, or a reload reads them under the
 * wrong meaning.</p>
 */
@DisplayName("Node attribution keying")
class NodeDocumentMappingKeyingTest {

    @TempDir
    Path tempDir;

    private static int versionOnDisk(Path file) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN)
            .getInt(Long.BYTES);
    }

    /** A mapping written the way a build that attributed nodes to documents wrote it. */
    private Path legacyFile(Map<Long, String> entries) throws IOException {
        Path file = tempDir.resolve("legacy.docmap");
        NodeDocumentMapping mapping = new NodeDocumentMapping(file);
        mapping.useKeying(NodeDocumentMapping.Keying.DOCUMENT);
        entries.forEach(mapping::register);
        mapping.persist();
        return file;
    }

    @Nested
    @DisplayName("indexes")
    class Indexes {

        @Test
        @DisplayName("a chunk's nodes are found by the chunk, and by its document")
        void chunksAndTheirDocument() {
            NodeDocumentMapping mapping = new NodeDocumentMapping();
            mapping.register(1, "src:a.md:chunk:0");
            mapping.register(2, "src:a.md:chunk:0");
            mapping.register(3, "src:a.md:chunk:1");
            mapping.register(4, "src:a.md2:chunk:0");

            assertThat(mapping.getNodesForChunk("src:a.md:chunk:0")).containsExactlyInAnyOrder(1L, 2L);
            assertThat(mapping.getNodesForChunk("src:a.md:chunk:1")).containsExactly(3L);
            assertThat(mapping.getNodesForDocument("src:a.md"))
                .as("a document whose name another document's begins with is a different one")
                .containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(mapping.getNodesForChunk(null)).isEmpty();
            assertThat(mapping.getNodesForChunk("src:unknown:chunk:0")).isEmpty();
        }

        @Test
        @DisplayName("re-registering and removing a node moves it between index entries")
        void registrationChangesAreIndexed() {
            NodeDocumentMapping mapping = new NodeDocumentMapping();
            mapping.register(1, "src:a.md:chunk:0");
            mapping.register(1, "src:b.md:chunk:0");

            assertThat(mapping.getNodesForChunk("src:a.md:chunk:0")).isEmpty();
            assertThat(mapping.getNodesForDocument("src:a.md")).isEmpty();
            assertThat(mapping.getNodesForDocument("src:b.md")).containsExactly(1L);

            mapping.remove(1);
            assertThat(mapping.getNodesForDocument("src:b.md")).isEmpty();
            assertThat(mapping.getNodesForChunk("src:b.md:chunk:0")).isEmpty();

            mapping.register(2, "src:c.md:chunk:0");
            mapping.clear();
            assertThat(mapping.getNodesForDocument("src:c.md")).isEmpty();
        }

        @Test
        @DisplayName("a document-keyed mapping answers by document and by no chunk")
        void documentKeyedMappingAnswersByDocument() {
            NodeDocumentMapping mapping = new NodeDocumentMapping();
            mapping.useKeying(NodeDocumentMapping.Keying.DOCUMENT);
            mapping.register(1, "src:a.md");

            assertThat(mapping.getNodesForDocument("src:a.md")).containsExactly(1L);
            assertThat(mapping.getNodesForChunk("src:a.md:chunk:0")).isEmpty();
        }
    }

    @Nested
    @DisplayName("on disk")
    class OnDisk {

        @Test
        @DisplayName("a new mapping names chunks and is written at version 3")
        void newMappingIsChunkKeyed() throws IOException {
            Path file = tempDir.resolve("new.docmap");
            NodeDocumentMapping mapping = new NodeDocumentMapping(file);
            mapping.register(0, "src:a.md:chunk:0");
            mapping.persist();

            assertThat(mapping.keying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            assertThat(versionOnDisk(file)).isEqualTo(NodeDocumentMapping.VERSION);

            NodeDocumentMapping reloaded = NodeDocumentMapping.createOrLoad(file, true);
            assertThat(reloaded.keying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            assertThat(reloaded.getNodesForChunk("src:a.md:chunk:0")).containsExactly(0L);
        }

        @Test
        @DisplayName("a document-keyed file stays at version 2 until it is re-keyed")
        void legacyFileKeepsItsVersionUntilRekeyed() throws IOException {
            Path file = legacyFile(Map.of(0L, "src:a.md", 1L, "src:a.md"));
            assertThat(versionOnDisk(file)).isEqualTo(NodeDocumentMapping.VERSION_DOCUMENT_KEYED);

            NodeDocumentMapping loaded = NodeDocumentMapping.createOrLoad(file, true);
            assertThat(loaded.keying()).isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
            loaded.persist();
            assertThat(versionOnDisk(file))
                .as("persisting document keys at version 3 would present them as chunk ids")
                .isEqualTo(NodeDocumentMapping.VERSION_DOCUMENT_KEYED);

            loaded.rekeyToChunks(Map.of(0L, "src:a.md:chunk:0"));
            assertThat(loaded.keying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            assertThat(loaded.getAllMappings()).containsExactly(Map.entry(0L, "src:a.md:chunk:0"));
            loaded.persist();
            assertThat(versionOnDisk(file)).isEqualTo(NodeDocumentMapping.VERSION);

            NodeDocumentMapping reloaded = NodeDocumentMapping.createOrLoad(file, true);
            assertThat(reloaded.keying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            assertThat(reloaded.getNodesForDocument("src:a.md")).containsExactly(0L);
        }

        @Test
        @DisplayName("an append must agree with the keying the file declares")
        void appendsAgreeWithTheFile() throws IOException {
            Path file = legacyFile(Map.of(0L, "src:a.md"));

            NodeDocumentMapping legacy = NodeDocumentMapping.createOrLoad(file, true);
            legacy.appendMapping(1, "src:b.md");

            NodeDocumentMapping chunkKeyed = new NodeDocumentMapping(file);
            assertThatThrownBy(() -> chunkKeyed.appendMapping(2, "src:c.md:chunk:0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version 2");
        }

        @Test
        @DisplayName("both checksummed versions verify, and a document-keyed one says so")
        void bothVersionsVerify() throws IOException {
            Path legacy = legacyFile(Map.of(0L, "src:a.md"));
            IntegrityReport legacyReport = NodeDocumentMapping.verifyIntegrity(legacy);
            assertThat(legacyReport.status()).isEqualTo(IntegrityStatus.INTACT);
            assertThat(legacyReport.detail()).contains("name documents rather than chunks");

            Path current = tempDir.resolve("current.docmap");
            NodeDocumentMapping mapping = new NodeDocumentMapping(current);
            mapping.register(0, "src:a.md:chunk:0");
            mapping.persist();
            IntegrityReport currentReport = NodeDocumentMapping.verifyIntegrity(current);
            assertThat(currentReport.status()).isEqualTo(IntegrityStatus.INTACT);
            assertThat(currentReport.detail()).doesNotContain("documents");
        }

        @Test
        @DisplayName("an entry naming nothing is refused as corrupt rather than registered")
        void emptyKeyIsCorrupt() throws IOException {
            Path file = tempDir.resolve("v1.docmap");
            ByteBuffer bytes = ByteBuffer.allocate(NodeDocumentMapping.HEADER_SIZE_V1 + 12)
                .order(ByteOrder.LITTLE_ENDIAN);
            bytes.putLong(0, NodeDocumentMapping.MAGIC);
            bytes.putInt(Long.BYTES, NodeDocumentMapping.VERSION_V1);
            bytes.putLong(16, 1L);
            bytes.putLong(NodeDocumentMapping.HEADER_SIZE_V1, 7L);
            bytes.putInt(NodeDocumentMapping.HEADER_SIZE_V1 + Long.BYTES, 0);
            Files.write(file, bytes.array());

            NodeDocumentMapping mapping = new NodeDocumentMapping(file);
            assertThatThrownBy(mapping::load).isInstanceOf(IOException.class)
                .hasMessageContaining("empty or invalid key");
        }

        @Test
        @DisplayName("a version 1 file is read as document-keyed")
        void versionOneIsDocumentKeyed() throws IOException {
            Path file = tempDir.resolve("v1-valid.docmap");
            byte[] key = "src:a.md".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer bytes = ByteBuffer.allocate(NodeDocumentMapping.HEADER_SIZE_V1 + 12 + key.length)
                .order(ByteOrder.LITTLE_ENDIAN);
            bytes.putLong(0, NodeDocumentMapping.MAGIC);
            bytes.putInt(Long.BYTES, NodeDocumentMapping.VERSION_V1);
            bytes.putLong(16, 1L);
            bytes.position(NodeDocumentMapping.HEADER_SIZE_V1);
            bytes.putLong(3L).putInt(key.length).put(key);
            Files.write(file, bytes.array());

            NodeDocumentMapping mapping = NodeDocumentMapping.createOrLoad(file, true);
            assertThat(mapping.keying()).isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
            assertThat(mapping.getNodesForDocument("src:a.md")).containsExactly(3L);
            assertThat(mapping.toString()).contains("DOCUMENT");
        }
    }
}
