package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the mapping file's verification entry point and its handling of older files.
 */
@DisplayName("NodeDocumentMapping verification")
class NodeDocumentMappingVerificationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a persisted mapping verifies as intact")
    void persistedMappingVerifiesIntact() throws IOException {
        Path mappingPath = tempDir.resolve("sealed.docmap");
        NodeDocumentMapping mapping = new NodeDocumentMapping(mappingPath);
        mapping.register(1, "doc-a");
        mapping.persist();

        assertThat(NodeDocumentMapping.verifyIntegrity(mappingPath).status())
            .isEqualTo(IntegrityStatus.INTACT);
    }

    @Test
    @DisplayName("appending leaves the mapping unsealed until it is compacted")
    void appendedMappingIsUnsealedUntilCompacted() throws IOException {
        Path mappingPath = tempDir.resolve("appended.docmap");
        NodeDocumentMapping mapping = new NodeDocumentMapping(mappingPath);
        mapping.register(1, "doc-a");
        mapping.persist();

        mapping.appendMapping(2, "doc-b");
        assertThat(NodeDocumentMapping.verifyIntegrity(mappingPath).status())
            .isEqualTo(IntegrityStatus.UNSEALED);

        mapping.register(2, "doc-b");
        mapping.compact();
        assertThat(NodeDocumentMapping.verifyIntegrity(mappingPath).status())
            .isEqualTo(IntegrityStatus.INTACT);
    }

    @Test
    @DisplayName("a mapping written before checksums existed still loads, named as unprotected")
    void olderMappingStillLoads() throws IOException {
        Path mappingPath = tempDir.resolve("legacy.docmap");
        Files.write(mappingPath, legacyMapping());

        assertThat(NodeDocumentMapping.verifyIntegrity(mappingPath).status())
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);

        NodeDocumentMapping loaded = new NodeDocumentMapping(mappingPath);
        loaded.load();
        assertThat(loaded.getDocumentId(7)).isEqualTo("doc-legacy");
    }

    @Test
    @DisplayName("a sealed mapping at a version this build does not read is unreadable, not intact")
    void newerMappingIsUnreadableRatherThanIntact() throws IOException {
        Path mappingPath = tempDir.resolve("newer.docmap");
        NodeDocumentMapping mapping = new NodeDocumentMapping(mappingPath);
        mapping.register(1, "chunk-a");
        mapping.persist();
        restampVersion(mappingPath, NodeDocumentMapping.VERSION + 1);

        var report = NodeDocumentMapping.verifyIntegrity(mappingPath);

        assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
        assertThat(report.detail())
            .contains("format version " + (NodeDocumentMapping.VERSION + 1))
            .contains("versions 1 to " + NodeDocumentMapping.VERSION)
            .doesNotContain("name documents");
        assertThatThrownBy(new NodeDocumentMapping(mappingPath)::load)
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a mapping claiming a version below any ever written is unreadable, not legacy")
    void mappingBelowTheOldestVersionIsUnreadable() throws IOException {
        Path mappingPath = tempDir.resolve("zero.docmap");
        byte[] bytes = legacyMapping();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0);
        Files.write(mappingPath, bytes);

        assertThat(NodeDocumentMapping.verifyIntegrity(mappingPath).status())
            .isEqualTo(IntegrityStatus.UNREADABLE);
    }

    @Test
    @DisplayName("the format name says what the entries name at each version")
    void formatNameSaysWhatTheEntriesName() {
        assertThat(NodeMappingIntegrity.FORMAT)
            .contains("chunk")
            .contains("document")
            .doesNotContain("node-to-document mapping");
    }

    /**
     * Rewrites a sealed mapping's version field and its header checksum, so the header vouches
     * for the new version the way a newer build's writer would have made it.
     */
    private static void restampVersion(Path mappingPath, int version) throws IOException {
        byte[] bytes = Files.readAllBytes(mappingPath);
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        view.putInt(8, version);
        view.putLong(NodeMappingIntegrity.OFFSET_HEADER_CRC,
            IndexChecksum.of(bytes, 0, NodeMappingIntegrity.HEADER_COVERED_BYTES));
        Files.write(mappingPath, bytes);
    }

    /**
     * Builds a version 1 mapping file: a 24-byte header and one entry, no checksum.
     */
    private static byte[] legacyMapping() {
        byte[] documentId = "doc-legacy".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(24 + 12 + documentId.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(0x4E4F44454D415053L);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putLong(1L);
        buffer.putLong(7L);
        buffer.putInt(documentId.length);
        buffer.put(documentId);
        return buffer.array();
    }
}
