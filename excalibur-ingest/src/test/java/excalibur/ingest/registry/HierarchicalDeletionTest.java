package excalibur.ingest.registry;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for document update and deletion handling alongside the hierarchical Cuckoo filter.
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>Hierarchical deletion from segment to cluster to corpus filters, driven directly</li>
 *   <li>UpsertHandler segment-aware processing</li>
 *   <li>Error handling during tombstoning</li>
 * </ul>
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>A key explicitly deleted from the hierarchy is gone from every level</li>
 *   <li>Registry is properly updated for deleted documents</li>
 *   <li>Storage tombstoning is called for all chunks</li>
 *   <li>Tombstoning a document does <strong>not</strong> touch the filter hierarchy: it indexes
 *       terms, not chunk ids, and a Cuckoo delete matches fingerprints rather than keys</li>
 * </ul>
 */
class HierarchicalDeletionTest {

    private static final String SEGMENT_ID = "segment-1";
    private static final String CLUSTER_ID = "cluster-a";
    private static final String SOURCE_ID = "test-source";

    private HierarchicalCuckooFilter hierarchicalFilter;
    private DocumentRegistry registry;
    private List<String> tombstonedChunks;

    @BeforeEach
    void setUp() {
        hierarchicalFilter = new HierarchicalCuckooFilter(10000);
        hierarchicalFilter.registerSegment(SEGMENT_ID, CLUSTER_ID);
        registry = new DocumentRegistry();
        tombstonedChunks = new ArrayList<>();
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void testHierarchicalDeletionRemovesFromAllLevels() {
        String chunkId = "chunk-123";
        
        assertTrue(hierarchicalFilter.insert(toBytes(chunkId), SEGMENT_ID));
        
        assertTrue(hierarchicalFilter.mightContain(toBytes(chunkId)));
        assertTrue(hierarchicalFilter.mightContainInSegment(toBytes(chunkId), SEGMENT_ID));
        
        assertTrue(hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId), SEGMENT_ID));
        
        assertFalse(hierarchicalFilter.mightContain(toBytes(chunkId)));
        assertFalse(hierarchicalFilter.mightContainInSegment(toBytes(chunkId), SEGMENT_ID));
    }

    @Test
    void testHierarchicalDeletionBatch() {
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] item = toBytes("chunk-" + i);
            items.add(item);
            assertTrue(hierarchicalFilter.insert(item, SEGMENT_ID));
        }
        
        int deleted = hierarchicalFilter.deleteFromHierarchy(items, SEGMENT_ID);
        assertEquals(10, deleted);
        
        for (byte[] item : items) {
            assertFalse(hierarchicalFilter.mightContain(item));
        }
    }

    @Test
    void testDeletionWithoutSegmentIdSearchesAllSegments() {
        String chunkId = "chunk-orphan";
        assertTrue(hierarchicalFilter.insert(toBytes(chunkId), SEGMENT_ID));
        
        assertTrue(hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId)));
        
        assertFalse(hierarchicalFilter.mightContain(toBytes(chunkId)));
    }

    @Test
    void testUpsertHandlerWithSegmentTombstonesStorageOnly() {
        List<String> tombstoned = new ArrayList<>();
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstoned.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };
        
        hierarchicalFilter.insert(toBytes("chunk1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("chunk2"), SEGMENT_ID);
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, storageAdapter);
        
        DocumentMetadata oldMeta = DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId("doc1")
            .addChunkId("chunk1")
            .addChunkId("chunk2")
            .build();
        
        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(System.currentTimeMillis(), 9999L, 100);
        
        ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
            .addChanged("doc1", oldMeta, fetchedInfo)
            .build();
        
        UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);
        
        assertEquals(1, result.getDocumentsChanged());
        assertEquals(2, result.getChunksTombstoned());
        assertFalse(result.hasErrors());
        
        assertEquals(2, tombstoned.size());
        assertTrue(tombstoned.contains("chunk1"));
        assertTrue(tombstoned.contains("chunk2"));

        // The filter hierarchy is a term index; tombstoning must leave it alone.
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk1")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk2")));
    }

    @Test
    void testUpsertHandlerDeletesDocumentsWithSegment() {
        List<String> tombstoned = new ArrayList<>();
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstoned.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };
        
        hierarchicalFilter.insert(toBytes("chunk-del1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("chunk-del2"), SEGMENT_ID);
        
        DocumentMetadata deletedMeta = DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId("doc-deleted")
            .addChunkId("chunk-del1")
            .addChunkId("chunk-del2")
            .build();
        
        registry.register(deletedMeta);
        assertNotNull(registry.get(SOURCE_ID, "doc-deleted"));
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, storageAdapter);
        
        ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
            .addDeleted(deletedMeta)
            .build();
        
        UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);
        
        assertEquals(1, result.getDocumentsDeleted());
        assertEquals(2, result.getChunksTombstoned());
        assertFalse(result.hasErrors());
        
        assertNull(registry.get(SOURCE_ID, "doc-deleted"));

        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-del1")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-del2")));
    }

    @Test
    void testNoFalsePositivesAfterDeletion() {
        String chunkId = "chunk-fp-test";
        byte[] chunkBytes = toBytes(chunkId);
        
        assertTrue(hierarchicalFilter.insert(chunkBytes, SEGMENT_ID));
        assertTrue(hierarchicalFilter.mightContain(chunkBytes));
        
        assertTrue(hierarchicalFilter.deleteFromHierarchy(chunkBytes, SEGMENT_ID));
        
        assertFalse(hierarchicalFilter.mightContain(chunkBytes));
        assertFalse(hierarchicalFilter.mightContainInSegment(chunkBytes, SEGMENT_ID));
        
        CuckooFilter segmentFilter = hierarchicalFilter.getSegmentFilter(SEGMENT_ID);
        assertNotNull(segmentFilter);
        assertFalse(segmentFilter.contains(chunkBytes));
        
        CuckooFilter clusterFilter = hierarchicalFilter.getClusterFilter(CLUSTER_ID);
        assertNotNull(clusterFilter);
        assertFalse(clusterFilter.contains(chunkBytes));
    }

    @Test
    void testMixedOperationsWithHierarchicalDeletion() {
        List<String> tombstoned = new ArrayList<>();
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstoned.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };
        
        hierarchicalFilter.insert(toBytes("chunk-old1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("chunk-old2"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("chunk-del1"), SEGMENT_ID);
        
        DocumentMetadata changedMeta = DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId("doc-changed")
            .addChunkId("chunk-old1")
            .addChunkId("chunk-old2")
            .build();
        
        DocumentMetadata deletedMeta = DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId("doc-deleted")
            .addChunkId("chunk-del1")
            .build();
        registry.register(deletedMeta);
        
        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(System.currentTimeMillis(), 9999L, 100);
        
        ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
            .addChanged("doc-changed", changedMeta, fetchedInfo)
            .addDeleted(deletedMeta)
            .build();
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, storageAdapter);
        UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);
        
        assertEquals(1, result.getDocumentsChanged());
        assertEquals(1, result.getDocumentsDeleted());
        assertEquals(3, result.getChunksTombstoned());
        assertFalse(result.hasErrors());

        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-old1")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-old2")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-del1")));

        assertNull(registry.get(SOURCE_ID, "doc-deleted"));
    }

    @Test
    void testTombstoneChunkWithSegment() {
        List<String> tombstoned = new ArrayList<>();
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstoned.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };
        
        hierarchicalFilter.insert(toBytes("chunk-single"), SEGMENT_ID);
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, storageAdapter);
        
        assertTrue(handler.tombstoneChunk("chunk-single", SEGMENT_ID));
        
        assertEquals(1, tombstoned.size());
        assertEquals("chunk-single", tombstoned.get(0));
        assertTrue(hierarchicalFilter.mightContain(toBytes("chunk-single")));
    }

    @Test
    void testTombstoneChunksBatchWithSegment() {
        List<String> tombstoned = new ArrayList<>();
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstoned.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };
        
        hierarchicalFilter.insert(toBytes("batch-1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("batch-2"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("batch-3"), SEGMENT_ID);
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, storageAdapter);
        
        int count = handler.tombstoneChunks(List.of("batch-1", "batch-2", "batch-3"), SEGMENT_ID);
        
        assertEquals(3, count);
        assertEquals(3, tombstoned.size());

        assertTrue(hierarchicalFilter.mightContain(toBytes("batch-1")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("batch-2")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("batch-3")));
    }

    @Test
    void testDeletionErrorHandling() {
        UpsertHandler.StorageAdapter failingStorage = key -> {
            throw new RuntimeException("Storage error");
        };
        
        hierarchicalFilter.insert(toBytes("error-chunk"), SEGMENT_ID);
        
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, failingStorage);
        
        DocumentMetadata oldMeta = DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId("doc-error")
            .addChunkId("error-chunk")
            .build();
        
        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(System.currentTimeMillis(), 9999L, 100);
        
        ChangeSet changeSet = ChangeSet.builder(SOURCE_ID)
            .addChanged("doc-error", oldMeta, fetchedInfo)
            .build();
        
        UpsertHandler.UpsertResult result = handler.processChangesWithSegment(changeSet, SEGMENT_ID);
        
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        assertEquals("storage", result.getErrors().get(0).getComponent());
        assertEquals("error-chunk", result.getErrors().get(0).getChunkId());

        assertTrue(hierarchicalFilter.mightContain(toBytes("error-chunk")));
    }

    @Test
    void testNullArgumentsRejected() {
        UpsertHandler handler = new UpsertHandler(registry, null, hierarchicalFilter, null);
        
        assertThrows(NullPointerException.class, () -> 
            handler.tombstoneChunk(null, SEGMENT_ID));
        
        assertThrows(NullPointerException.class, () -> 
            handler.tombstoneChunk("chunk", null));
        
        assertThrows(NullPointerException.class, () -> 
            handler.tombstoneChunks(null, SEGMENT_ID));
        
        assertThrows(NullPointerException.class, () -> 
            handler.tombstoneChunks(List.of("chunk"), null));

        assertThrows(NullPointerException.class, () -> 
            handler.processChangesWithSegment(null, SEGMENT_ID));
        
        assertThrows(NullPointerException.class, () -> 
            handler.processChangesWithSegment(ChangeSet.builder(SOURCE_ID).build(), null));
    }

    @Test
    void testHierarchicalFilterDeletionIdempotent() {
        String chunkId = "idempotent-chunk";
        byte[] chunkBytes = toBytes(chunkId);
        
        assertTrue(hierarchicalFilter.insert(chunkBytes, SEGMENT_ID));
        
        assertTrue(hierarchicalFilter.deleteFromHierarchy(chunkBytes, SEGMENT_ID));
        
        assertFalse(hierarchicalFilter.deleteFromHierarchy(chunkBytes, SEGMENT_ID));
    }

    @Test
    void testSegmentToClusterMappingPreserved() {
        String segment2 = "segment-2";
        hierarchicalFilter.registerSegment(segment2, CLUSTER_ID);
        
        hierarchicalFilter.insert(toBytes("chunk-s1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("chunk-s2"), segment2);
        
        hierarchicalFilter.deleteFromHierarchy(toBytes("chunk-s1"), SEGMENT_ID);
        
        assertTrue(hierarchicalFilter.mightContainInSegment(toBytes("chunk-s2"), segment2));
    }

    @Test
    void testMultipleSegmentsIndependentDeletion() {
        String segment2 = "segment-2";
        String cluster2 = "cluster-b";
        hierarchicalFilter.registerSegment(segment2, cluster2);
        
        hierarchicalFilter.insert(toBytes("multi-chunk1"), SEGMENT_ID);
        hierarchicalFilter.insert(toBytes("multi-chunk2"), segment2);
        
        hierarchicalFilter.deleteFromHierarchy(toBytes("multi-chunk1"), SEGMENT_ID);
        
        assertFalse(hierarchicalFilter.mightContain(toBytes("multi-chunk1")));
        assertTrue(hierarchicalFilter.mightContain(toBytes("multi-chunk2")));
    }

    @Test
    void testDomainCorrectness_ChunkIdsAreUtf8Bytes() {
        String chunkId = "chunk-unicode-\u4e2d\u6587-\u03b1\u03b2\u03b3";
        byte[] chunkBytes = toBytes(chunkId);
        
        assertTrue(hierarchicalFilter.insert(chunkBytes, SEGMENT_ID));
        assertTrue(hierarchicalFilter.mightContain(chunkBytes));
        
        assertTrue(hierarchicalFilter.deleteFromHierarchy(chunkBytes, SEGMENT_ID));
        assertFalse(hierarchicalFilter.mightContain(chunkBytes));
    }

    @Test
    void testDomainCorrectness_EntryCountDecrements() {
        long initialCount = hierarchicalFilter.getTotalEntries();
        
        hierarchicalFilter.insert(toBytes("count-1"), SEGMENT_ID);
        assertEquals(initialCount + 1, hierarchicalFilter.getTotalEntries());
        
        hierarchicalFilter.insert(toBytes("count-2"), SEGMENT_ID);
        assertEquals(initialCount + 2, hierarchicalFilter.getTotalEntries());
        
        hierarchicalFilter.deleteFromHierarchy(toBytes("count-1"), SEGMENT_ID);
        assertEquals(initialCount + 1, hierarchicalFilter.getTotalEntries());
        
        hierarchicalFilter.deleteFromHierarchy(toBytes("count-2"), SEGMENT_ID);
        assertEquals(initialCount, hierarchicalFilter.getTotalEntries());
    }
}
