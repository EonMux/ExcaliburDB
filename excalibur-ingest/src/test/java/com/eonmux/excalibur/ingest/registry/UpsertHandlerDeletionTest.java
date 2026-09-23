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
 *   <li>Deleted and changed documents have every chunk tombstoned in storage</li>
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
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

            assertThat(result.getDocumentsDeleted()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            assertThat(storageAdapter.deletedKeys).containsExactlyInAnyOrder("chunk-1", "chunk-2");
            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNull();
        }

        @Test
        @DisplayName("should tombstone the old chunks of a changed document")
        void shouldTombstoneChangedDocumentChunks() {
            DocumentMetadata oldMetadata =
                createAndRegisterDocument(DOCUMENT_ID, "old-chunk-1", "old-chunk-2");

            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            DocumentMetadata.FetchedDocumentInfo fetchedInfo = new DocumentMetadata.FetchedDocumentInfo(
                System.currentTimeMillis(), 999L, 5000L);

            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addChanged(DOCUMENT_ID, oldMetadata, fetchedInfo)
                .build();

            UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

            assertThat(result.getDocumentsChanged()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            assertThat(storageAdapter.deletedKeys)
                .containsExactlyInAnyOrder("old-chunk-1", "old-chunk-2");
        }

        @Test
        @DisplayName("should keep changed-document metadata for the ingestion pipeline to update")
        void shouldKeepChangedDocumentMetadata() {
            DocumentMetadata oldMetadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1");

            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);
            DocumentMetadata.FetchedDocumentInfo fetchedInfo = new DocumentMetadata.FetchedDocumentInfo(
                System.currentTimeMillis(), 999L, 5000L);

            handler.processChanges(ChangeSet.builder(SOURCE_ID)
                .addChanged(DOCUMENT_ID, oldMetadata, fetchedInfo)
                .build());

            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNotNull();
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
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getComponent()).isEqualTo("storage");
        }

        @Test
        @DisplayName("should throw on null change set")
        void shouldThrowOnNullChangeSet() {
            UpsertHandler handler = new UpsertHandler(registry, storageAdapter);

            assertThatThrownBy(() -> handler.processChanges(null))
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
