package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
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
 *   <li>The Cuckoo filter hierarchy is left untouched - it indexes terms, not chunk ids,
 *       so deleting a chunk id from it would evict an unrelated term's fingerprint</li>
 * </ul>
 */
class UpsertHandlerDeletionTest {

    private DocumentRegistry registry;
    private HierarchicalCuckooFilter hierarchicalFilter;
    private TestStorageAdapter storageAdapter;

    private static final String SOURCE_ID = "test-source";
    private static final String CLUSTER_ID = "cluster-1";
    private static final String SEGMENT_ID = "segment-1";
    private static final String DOCUMENT_ID = "doc-1";

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        hierarchicalFilter = new HierarchicalCuckooFilter(100_000);
        storageAdapter = new TestStorageAdapter();

        hierarchicalFilter.registerSegment(SEGMENT_ID, CLUSTER_ID);
    }

    @Nested
    @DisplayName("Storage Tombstoning")
    class StorageTombstoningTests {

        @Test
        @DisplayName("should tombstone every chunk of a deleted document")
        void shouldTombstoneDeletedDocumentChunks() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1", "chunk-2");

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

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

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            DocumentMetadata.FetchedDocumentInfo fetchedInfo = new DocumentMetadata.FetchedDocumentInfo(
                System.currentTimeMillis(), 999L, 5000L);

            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addChanged(DOCUMENT_ID, oldMetadata, fetchedInfo)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(result.getDocumentsChanged()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            assertThat(storageAdapter.deletedKeys)
                .containsExactlyInAnyOrder("old-chunk-1", "old-chunk-2");
        }

        @Test
        @DisplayName("should keep changed-document metadata for the ingestion pipeline to update")
        void shouldKeepChangedDocumentMetadata() {
            DocumentMetadata oldMetadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1");

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            DocumentMetadata.FetchedDocumentInfo fetchedInfo = new DocumentMetadata.FetchedDocumentInfo(
                System.currentTimeMillis(), 999L, 5000L);

            handler.processChangesWithSegment(ChangeSet.builder(SOURCE_ID)
                .addChanged(DOCUMENT_ID, oldMetadata, fetchedInfo)
                .build(), SEGMENT_ID);

            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNotNull();
        }

        @Test
        @DisplayName("should tombstone a single chunk with segment ID")
        void shouldTombstoneSingleChunkWithSegmentId() {
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            assertThat(handler.tombstoneChunk("chunk-1", SEGMENT_ID)).isTrue();
            assertThat(storageAdapter.deletedKeys).containsExactly("chunk-1");
        }

        @Test
        @DisplayName("should tombstone multiple chunks in batch")
        void shouldTombstoneMultipleChunksInBatch() {
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            int count = handler.tombstoneChunks(List.of("chunk-1", "chunk-2"), SEGMENT_ID);

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
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            int tombstoned = handler.tombstoneObsoleteChunks(
                List.of("doc:chunk:0", "doc:chunk:1", "doc:chunk:2"),
                List.of("doc:chunk:0", "doc:chunk:1"));

            assertThat(tombstoned).isEqualTo(1);
            assertThat(storageAdapter.deletedKeys).containsExactly("doc:chunk:2");
        }

        @Test
        @DisplayName("should tombstone nothing when the new version reuses every chunk id")
        void shouldTombstoneNothingWhenFullyOverwritten() {
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            int tombstoned = handler.tombstoneObsoleteChunks(
                List.of("doc:chunk:0", "doc:chunk:1"),
                List.of("doc:chunk:0", "doc:chunk:1", "doc:chunk:2"));

            assertThat(tombstoned).isZero();
            assertThat(storageAdapter.deletedKeys).isEmpty();
        }
    }

    @Nested
    @DisplayName("Filter Hierarchy Is Not Touched")
    class FilterHierarchyUntouchedTests {

        /**
         * The filter hierarchy is a term index: {@code DocumentBatchPublisher} inserts one entry
         * per term, never a chunk id. A Cuckoo delete clears the first slot whose fingerprint
         * matches, without comparing keys, so deleting a never-inserted chunk id evicts whichever
         * term happens to collide - turning a harmless false positive into a permanent false
         * negative for every document in the segment.
         */
        @Test
        @DisplayName("tombstoning a deleted document leaves the segment's terms intact")
        void deletedDocumentLeavesTermsIntact() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1", "chunk-2");
            hierarchicalFilter.insertKey("shared-term", SEGMENT_ID);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            handler.processChangesWithSegment(ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build(), SEGMENT_ID);

            assertThat(hierarchicalFilter.mightContainKey("shared-term", SEGMENT_ID))
                .as("a term shared with live documents must survive a tombstone")
                .isTrue();
            assertThat(hierarchicalFilter.getTotalEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("tombstoning a single chunk leaves the segment's terms intact")
        void singleChunkTombstoneLeavesTermsIntact() {
            hierarchicalFilter.insertKey("shared-term", SEGMENT_ID);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            handler.tombstoneChunk("chunk-1", SEGMENT_ID);

            assertThat(hierarchicalFilter.mightContainKey("shared-term", SEGMENT_ID)).isTrue();
            assertThat(hierarchicalFilter.getTotalEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("a flat Cuckoo filter is left alone as well")
        void flatFilterLeftAlone() {
            CuckooFilter flatFilter = CuckooFilter.create(1000);
            flatFilter.insert(toBytes("shared-term"));

            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1");

            UpsertHandler handler = new UpsertHandler(registry, flatFilter, storageAdapter);
            UpsertHandler.UpsertResult result = handler.processChanges(ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build());

            assertThat(result.getChunksTombstoned()).isEqualTo(1);
            assertThat(flatFilter.contains(toBytes("shared-term"))).isTrue();
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

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, failingStorage);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getComponent()).isEqualTo("storage");
        }

        @Test
        @DisplayName("should throw on null change set")
        void shouldThrowOnNullChangeSet() {
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            assertThatThrownBy(() -> handler.processChangesWithSegment(null, SEGMENT_ID))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on null segment ID")
        void shouldThrowOnNullSegmentId() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1");
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID).addDeleted(metadata).build();

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            assertThatThrownBy(() -> handler.processChangesWithSegment(changeSet, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Handler Configuration")
    class HandlerConfigurationTests {

        @Test
        @DisplayName("should expose hierarchical filter")
        void shouldExposeHierarchicalFilter() {
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            assertThat(handler.getHierarchicalFilter()).isSameAs(hierarchicalFilter);
        }

        @Test
        @DisplayName("should report hierarchical filter availability")
        void shouldReportHierarchicalFilterAvailability() {
            UpsertHandler withHierarchical = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            UpsertHandler withFlat = new UpsertHandler(registry, CuckooFilter.create(1000), storageAdapter);

            assertThat(withHierarchical.hasHierarchicalFilter()).isTrue();
            assertThat(withFlat.hasHierarchicalFilter()).isFalse();
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

    private static byte[] toBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
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
