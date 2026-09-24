package com.eonmux.excalibur.ingest.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for UpsertHandler tombstoning.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Deleted documents have every chunk tombstoned in storage</li>
 *   <li>Changed documents lose only the chunks their new version did not overwrite</li>
 *   <li>Deleted document metadata is removed from the registry</li>
 *   <li>Storage failures are collected as errors rather than thrown</li>
 * </ul>
 */
class UpsertHandlerDeletionTest {

    private DocumentRegistry registry;
    private TestStorageAdapter storageAdapter;

    private static final String SOURCE_ID = "test-source";
    private static final String DOCUMENT_ID = "doc-1";

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        storageAdapter = new TestStorageAdapter();
    }

    @Nested
    @DisplayName("Storage Tombstoning")
    class StorageTombstoningTests {

        @Test
        @DisplayName("should tombstone every chunk of a deleted document")
        void shouldTombstoneDeletedDocumentChunks() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1", "chunk-2");

            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            UpsertHandler.UpsertResult result = handler.removeDocument(metadata);

            assertThat(result.getDocumentsDeleted()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            assertThat(result.isSuccess()).isTrue();
            assertThat(storageAdapter.deletedKeys).containsExactlyInAnyOrder("chunk-1", "chunk-2");
            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNull();
        }

        @Test
        @DisplayName("should remove the registry entry without storage in dry-run mode")
        void shouldRemoveRegistryEntryWithoutStorage() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1");

            UpsertHandler handler = new UpsertHandler(registry, null);

            UpsertHandler.UpsertResult result = handler.removeDocument(metadata);

            assertThat(result.getDocumentsDeleted()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isZero();
            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNull();
        }

        @Test
        @DisplayName("should tombstone a single chunk")
        void shouldTombstoneSingleChunk() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            assertThat(handler.tombstoneChunk("chunk-1")).isTrue();
            assertThat(storageAdapter.deletedKeys).containsExactly("chunk-1");
        }

        @Test
        @DisplayName("should tombstone multiple chunks in batch")
        void shouldTombstoneMultipleChunksInBatch() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            int count = handler.tombstoneChunks(List.of("chunk-1", "chunk-2"));

            assertThat(count).isEqualTo(2);
            assertThat(storageAdapter.deletedKeys).containsExactly("chunk-1", "chunk-2");
        }
    }

    @Nested
    @DisplayName("Obsolete Chunk Retirement")
    class ObsoleteChunkTests {

        @Test
        @DisplayName("should tombstone only the chunks the new version did not overwrite")
        void shouldTombstoneOnlySurplusTail() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            int tombstoned = handler.tombstoneObsoleteChunks(
                List.of("doc:chunk:0", "doc:chunk:1", "doc:chunk:2"),
                List.of("doc:chunk:0", "doc:chunk:1"));

            assertThat(tombstoned).isEqualTo(1);
            assertThat(storageAdapter.deletedKeys).containsExactly("doc:chunk:2");
        }

        @Test
        @DisplayName("should tombstone nothing when the new version reuses every chunk id")
        void shouldTombstoneNothingWhenFullyOverwritten() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            int tombstoned = handler.tombstoneObsoleteChunks(
                List.of("doc:chunk:0", "doc:chunk:1"),
                List.of("doc:chunk:0", "doc:chunk:1", "doc:chunk:2"));

            assertThat(tombstoned).isZero();
            assertThat(storageAdapter.deletedKeys).isEmpty();
        }

        @Test
        @DisplayName("should leave the changed document's registry entry for the pipeline to update")
        void shouldKeepChangedDocumentMetadata() {
            DocumentMetadata oldMetadata =
                createAndRegisterDocument(DOCUMENT_ID, "doc:chunk:0", "doc:chunk:1");
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            handler.tombstoneObsoleteChunks(oldMetadata.getChunkIds(), List.of("doc:chunk:0"));

            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isEqualTo(oldMetadata);
        }

        @Test
        @DisplayName("should count only the obsolete chunks storage actually removed")
        void shouldCountOnlyRemovedChunks() {
            TestStorageAdapter partial = new TestStorageAdapter() {
                @Override
                public boolean delete(byte[] key) {
                    String id = new String(key, StandardCharsets.UTF_8);
                    if (id.endsWith(":2")) {
                        throw new RuntimeException("Storage error");
                    }
                    return super.delete(key);
                }
            };
            UpsertHandler handler = new UpsertHandler(registry, partial);

            int tombstoned = handler.tombstoneObsoleteChunks(
                List.of("doc:chunk:0", "doc:chunk:1", "doc:chunk:2"),
                List.of("doc:chunk:0"));

            assertThat(tombstoned).isEqualTo(1);
            assertThat(partial.deletedKeys).containsExactly("doc:chunk:1");
        }

        @Test
        @DisplayName("should tombstone nothing without a storage adapter")
        void shouldTombstoneNothingWithoutStorage() {
            UpsertHandler handler = new UpsertHandler(registry, null);

            assertThat(handler.tombstoneObsoleteChunks(List.of("a", "b"), List.of())).isZero();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should collect errors without throwing")
        void shouldCollectErrors() {
            String chunkId = "error-chunk";
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);

            TestStorageAdapter failingStorage = new TestStorageAdapter() {
                @Override
                public boolean delete(byte[] key) {
                    throw new RuntimeException("Storage error");
                }
            };

            UpsertHandler handler = new UpsertHandler(registry, failingStorage);

            UpsertHandler.UpsertResult result = handler.removeDocument(metadata);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getComponent()).isEqualTo("storage");
            assertThat(result.getErrors().get(0).getChunkId()).isEqualTo(chunkId);
        }

        @Test
        @DisplayName("should throw on null metadata")
        void shouldThrowOnNullMetadata() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            assertThatThrownBy(() -> handler.removeDocument(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on null chunk id lists")
        void shouldThrowOnNullChunkIdLists() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            assertThatThrownBy(() -> handler.tombstoneObsoleteChunks(null, List.of()))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> handler.tombstoneObsoleteChunks(List.of(), null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    private DocumentMetadata createAndRegisterDocument(String docId, String... chunkIds) {
        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId(docId)
            .sourceId(SOURCE_ID)
            .chunkIds(Arrays.asList(chunkIds))
            .build();
        registry.register(metadata);
        return metadata;
    }

    private static class TestStorageAdapter implements UpsertHandler.StorageAdapter {
        final List<String> deletedKeys = new java.util.ArrayList<>();

        @Override
        public boolean delete(byte[] key) {
            deletedKeys.add(new String(key, StandardCharsets.UTF_8));
            return true;
        }
    }
}
