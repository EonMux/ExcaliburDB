package com.eonmux.excalibur.vector.hnsw;

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
