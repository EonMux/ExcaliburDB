package excalibur.ingest.registry;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for UpsertHandler hierarchical Cuckoo filter deletion.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Deleted documents are tombstoned in storage</li>
 *   <li>Deleted entries are removed from ALL filter levels immediately</li>
 *   <li>No stale false positives after deletion</li>
 *   <li>Document metadata is removed from registry</li>
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
    @DisplayName("Hierarchical Filter Deletion")
    class HierarchicalDeletionTests {

        @Test
        @DisplayName("should delete document chunks from all filter levels")
        void shouldDeleteFromAllFilterLevels() {
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, "chunk-1", "chunk-2");
            insertChunksIntoFilter("chunk-1", "chunk-2");

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(result.getDocumentsDeleted()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            
            assertChunksNotInFilter("chunk-1", "chunk-2");
            assertThat(registry.get(SOURCE_ID, DOCUMENT_ID)).isNull();
        }

        @Test
        @DisplayName("should delete changed document old chunks from all filter levels")
        void shouldDeleteChangedDocumentChunksFromAllLevels() {
            DocumentMetadata oldMetadata = createAndRegisterDocument(DOCUMENT_ID, "old-chunk-1", "old-chunk-2");
            insertChunksIntoFilter("old-chunk-1", "old-chunk-2");

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            
            DocumentMetadata.FetchedDocumentInfo fetchedInfo = new DocumentMetadata.FetchedDocumentInfo(
                System.currentTimeMillis(), 999L, 5000L);
            
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addChanged(DOCUMENT_ID, oldMetadata, fetchedInfo)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(result.getDocumentsChanged()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(2);
            
            assertChunksNotInFilter("old-chunk-1", "old-chunk-2");
        }

        @Test
        @DisplayName("should tombstone single chunk with segment ID")
        void shouldTombstoneSingleChunkWithSegmentId() {
            insertChunksIntoFilter("chunk-1");
            DocumentMetadata metadata = createDocument(DOCUMENT_ID, "chunk-1");
            registry.register(metadata);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            boolean tombstoned = handler.tombstoneChunk("chunk-1", SEGMENT_ID);

            assertThat(tombstoned).isTrue();
            assertChunksNotInFilter("chunk-1");
        }

        @Test
        @DisplayName("should tombstone multiple chunks in batch")
        void shouldTombstoneMultipleChunksInBatch() {
            insertChunksIntoFilter("chunk-1", "chunk-2", "chunk-3");
            DocumentMetadata metadata = createDocument(DOCUMENT_ID, "chunk-1", "chunk-2", "chunk-3");
            registry.register(metadata);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            int count = handler.tombstoneChunks(List.of("chunk-1", "chunk-2"), SEGMENT_ID);

            assertThat(count).isEqualTo(2);
            assertChunksNotInFilter("chunk-1", "chunk-2");
            assertChunksInFilter("chunk-3");
        }

        @Test
        @DisplayName("should remove from filter hierarchy without storage tombstone")
        void shouldRemoveFromFilterHierarchyWithoutStorage() {
            insertChunksIntoFilter("chunk-1");
            
            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            boolean removed = handler.removeFromFilterHierarchy("chunk-1", SEGMENT_ID);

            assertThat(removed).isTrue();
            assertChunksNotInFilter("chunk-1");
            assertThat(storageAdapter.deletedKeys).isEmpty();
        }
    }

    @Nested
    @DisplayName("No False Positives After Deletion")
    class NoFalsePositivesTests {

        @Test
        @DisplayName("should not have false positives in corpus filter after deletion")
        void shouldNotHaveFalsePositivesInCorpus() {
            String chunkId = "unique-chunk-for-fp-test";
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);
            insertChunksIntoFilter(chunkId);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(hierarchicalFilter.getCorpusFilter().contains(toBytes(chunkId))).isFalse();
        }

        @Test
        @DisplayName("should not have false positives in cluster filter after deletion")
        void shouldNotHaveFalsePositivesInCluster() {
            String chunkId = "unique-chunk-for-cluster-fp-test";
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);
            insertChunksIntoFilter(chunkId);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(hierarchicalFilter.getClusterFilter(CLUSTER_ID).contains(toBytes(chunkId))).isFalse();
        }

        @Test
        @DisplayName("should not have false positives in segment filter after deletion")
        void shouldNotHaveFalsePositivesInSegment() {
            String chunkId = "unique-chunk-for-segment-fp-test";
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);
            insertChunksIntoFilter(chunkId);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(hierarchicalFilter.getSegmentFilter(SEGMENT_ID).contains(toBytes(chunkId))).isFalse();
        }

        @Test
        @DisplayName("should isolate deletions to specific chunks")
        void shouldIsolateDeletions() {
            String deletedChunk = "chunk-to-delete";
            String keptChunk = "chunk-to-keep";
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, deletedChunk, keptChunk);
            insertChunksIntoFilter(deletedChunk, keptChunk);

            UpsertHandler handler = new UpsertHandler(registry, hierarchicalFilter, storageAdapter);

            handler.tombstoneChunk(deletedChunk, SEGMENT_ID);

            assertChunksNotInFilter(deletedChunk);
            assertChunksInFilter(keptChunk);
        }
    }

    @Nested
    @DisplayName("Fallback to Flat Filter")
    class FlatFilterFallbackTests {

        @Test
        @DisplayName("should fall back to flat filter when hierarchical not available")
        void shouldFallBackToFlatFilter() {
            CuckooFilter flatFilter = CuckooFilter.create(1000);
            String chunkId = "flat-filter-chunk";
            flatFilter.insert(toBytes(chunkId));
            
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);

            UpsertHandler handler = new UpsertHandler(registry, flatFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

            assertThat(result.getChunksTombstoned()).isEqualTo(1);
            assertThat(flatFilter.contains(toBytes(chunkId))).isFalse();
        }

        @Test
        @DisplayName("should prefer hierarchical filter when both available")
        void shouldPreferHierarchicalFilter() {
            CuckooFilter flatFilter = CuckooFilter.create(1000);
            String chunkId = "both-filters-chunk";
            
            hierarchicalFilter.insertKey(chunkId, SEGMENT_ID);
            flatFilter.insert(toBytes(chunkId));
            
            DocumentMetadata metadata = createAndRegisterDocument(DOCUMENT_ID, chunkId);

            UpsertHandler handler = new UpsertHandler(registry, flatFilter, hierarchicalFilter, storageAdapter);
            ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
                .addDeleted(metadata)
                .build();

            UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);

            assertThat(result.getChunksTombstoned()).isEqualTo(1);
            assertThat(hierarchicalFilter.mightContainKey(chunkId, SEGMENT_ID)).isFalse();
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
            insertChunksIntoFilter(chunkId);
            
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

    private DocumentMetadata createDocument(String docId, String... chunkIds) {
        return DocumentMetadata.builder()
            .documentId(docId)
            .sourceId(SOURCE_ID)
            .chunkIds(Arrays.asList(chunkIds))
            .build();
    }

    private void insertChunksIntoFilter(String... chunkIds) {
        for (String chunkId : chunkIds) {
            hierarchicalFilter.insertKey(chunkId, SEGMENT_ID);
        }
    }

    private void assertChunksNotInFilter(String... chunkIds) {
        for (String chunkId : chunkIds) {
            assertThat(hierarchicalFilter.mightContainKey(chunkId, SEGMENT_ID))
                .as("Chunk '%s' should not be in filter", chunkId)
                .isFalse();
        }
    }

    private void assertChunksInFilter(String... chunkIds) {
        for (String chunkId : chunkIds) {
            assertThat(hierarchicalFilter.mightContainKey(chunkId, SEGMENT_ID))
                .as("Chunk '%s' should be in filter", chunkId)
                .isTrue();
        }
    }

    private static byte[] toBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static class TestStorageAdapter implements UpsertHandler.StorageAdapter {
        final List<byte[]> deletedKeys = new java.util.ArrayList<>();

        @Override
        public boolean delete(byte[] key) {
            deletedKeys.add(key);
            return true;
        }
    }
}
