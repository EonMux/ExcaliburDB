package io.excalibur.vector.hnsw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for NodeDocumentMapping - bidirectional mapping between HNSW node IDs and document IDs.
 */
@DisplayName("NodeDocumentMapping")
class NodeDocumentMappingTest {

    private NodeDocumentMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new NodeDocumentMapping();
    }

    @Nested
    @DisplayName("Forward Mapping (nodeId -> documentId)")
    class ForwardMapping {

        @Test
        @DisplayName("registers and retrieves document ID for node")
        void registersAndRetrieves() {
            mapping.register(1L, "doc-1");
            assertThat(mapping.getDocumentId(1L)).isEqualTo("doc-1");
        }

        @Test
        @DisplayName("returns null for unregistered node")
        void returnsNullForUnregistered() {
            assertThat(mapping.getDocumentId(999L)).isNull();
        }

        @Test
        @DisplayName("updates mapping when re-registering")
        void updatesOnReregister() {
            mapping.register(1L, "doc-1");
            mapping.register(1L, "doc-2");
            assertThat(mapping.getDocumentId(1L)).isEqualTo("doc-2");
        }
    }

    @Nested
    @DisplayName("Reverse Mapping (documentId -> nodeIds)")
    class ReverseMapping {

        @Test
        @DisplayName("returns all nodes for a document with multiple chunks")
        void returnsAllNodesForDocument() {
            mapping.register(1L, "doc-1");
            mapping.register(2L, "doc-1");
            mapping.register(3L, "doc-1");
            mapping.register(4L, "doc-2");

            Set<Long> doc1Nodes = mapping.getNodesForDocument("doc-1");
            assertThat(doc1Nodes).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(doc1Nodes).hasSize(3);

            Set<Long> doc2Nodes = mapping.getNodesForDocument("doc-2");
            assertThat(doc2Nodes).containsExactly(4L);
            assertThat(doc2Nodes).hasSize(1);
        }

        @Test
        @DisplayName("returns empty set for unknown document")
        void returnsEmptyForUnknown() {
            mapping.register(1L, "doc-1");
            assertThat(mapping.getNodesForDocument("unknown")).isEmpty();
        }

        @Test
        @DisplayName("returns empty set for null document ID")
        void returnsEmptyForNull() {
            mapping.register(1L, "doc-1");
            assertThat(mapping.getNodesForDocument(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Persistence")
    class PersistenceTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("persists and loads mappings")
        void persistsAndLoads() throws IOException {
            Path mappingFile = tempDir.resolve("mapping.dat");
            NodeDocumentMapping persistent = NodeDocumentMapping.createOrLoad(mappingFile, false);

            persistent.register(1L, "doc-1");
            persistent.register(2L, "doc-1");
            persistent.register(3L, "doc-2");
            persistent.persist();

            // Load into new instance
            NodeDocumentMapping loaded = NodeDocumentMapping.createOrLoad(mappingFile, true);
            assertThat(loaded.getDocumentId(1L)).isEqualTo("doc-1");
            assertThat(loaded.getDocumentId(2L)).isEqualTo("doc-1");
            assertThat(loaded.getDocumentId(3L)).isEqualTo("doc-2");
            assertThat(loaded.getNodesForDocument("doc-1")).containsExactlyInAnyOrder(1L, 2L);
        }
    }
}
