package com.eonmux.excalibur.vector.hnsw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrity tests for the persisted node-to-document mapping.
 *
 * <p>A flipped byte in this file re-attributes a vector to another document. Erasure and
 * document-level filtering both work from it, so a corrupt mapping deletes or exposes the
 * wrong document without anything in the answer showing it.</p>
 */
@DisplayName("NodeDocumentMapping file integrity")
class NodeDocumentMappingIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a flipped payload byte is refused on load")
    void flippedPayloadByteIsRefused() throws IOException {
        Path mappingPath = writeMapping("flip.docmap");

        flipByte(mappingPath, mappingPath.toFile().length() - 3);

        NodeDocumentMapping reloaded = new NodeDocumentMapping(mappingPath);
        assertThatThrownBy(reloaded::load)
            .isInstanceOf(IOException.class)
            .hasMessageContaining(mappingPath.toString());
    }

    @Test
    @DisplayName("an intact mapping still round-trips")
    void intactMappingRoundTrips() throws IOException {
        Path mappingPath = writeMapping("intact.docmap");

        NodeDocumentMapping reloaded = new NodeDocumentMapping(mappingPath);
        reloaded.load();

        assertThat(reloaded.size()).isEqualTo(8);
        assertThat(reloaded.getDocumentId(3)).isEqualTo("doc-3");
    }

    private Path writeMapping(String name) throws IOException {
        Path mappingPath = tempDir.resolve(name);
        NodeDocumentMapping mapping = new NodeDocumentMapping(mappingPath);
        for (int i = 0; i < 8; i++) {
            mapping.register(i, "doc-" + i);
        }
        mapping.persist();
        return mappingPath;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            int original = handle.readUnsignedByte();
            handle.seek(offset);
            handle.writeByte(original ^ 0x40);
        }
    }
}
