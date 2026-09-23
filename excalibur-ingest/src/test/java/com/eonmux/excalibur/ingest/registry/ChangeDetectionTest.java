package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the change detection system.
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>DocumentRegistry: Registration, retrieval, persistence</li>
 *   <li>ChangeDetector: New, changed, and deleted document detection</li>
 *   <li>ChangeSet: Result composition and chunk ID collection</li>
 *   <li>UpsertHandler: Tombstoning and filter deletion</li>
 * </ul>
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Content hashes are positive 64-bit values</li>
 *   <li>Timestamps are non-negative epoch milliseconds</li>
 *   <li>Chunk ID lists are never null</li>
 *   <li>Change detection correctly identifies modifications</li>
 * </ul>
 */
class ChangeDetectionTest {

    private DocumentRegistry registry;
    private ChangeDetector detector;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        detector = new ChangeDetector(registry);
    }

    // ==================== DocumentRegistry Tests ====================

    @Test
    void testRegisterAndGet() {
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(12345L)
            .contentLength(1000)
            .lastModifiedMillis(System.currentTimeMillis())
            .addChunkId("chunk1")
            .addChunkId("chunk2")
            .build();

        registry.register(metadata);

        DocumentMetadata retrieved = registry.get("test-source", "doc1");
        assertNotNull(retrieved);
        assertEquals("test-source", retrieved.getSourceId());
        assertEquals("doc1", retrieved.getDocumentId());
        assertEquals(12345L, retrieved.getContentHash());
        assertEquals(1000, retrieved.getContentLength());
        assertEquals(2, retrieved.getChunkCount());
        assertTrue(retrieved.getChunkIds().contains("chunk1"));
        assertTrue(retrieved.getChunkIds().contains("chunk2"));
    }

    @Test
    void testRegistrySize() {
        assertEquals(0, registry.size());

        registry.register(createMetadata("source1", "doc1"));
        registry.register(createMetadata("source1", "doc2"));
        registry.register(createMetadata("source2", "doc1"));

        assertEquals(3, registry.size());
        assertEquals(2, registry.size("source1"));
        assertEquals(1, registry.size("source2"));
    }

    @Test
    void testRemoveDocument() {
        registry.register(createMetadata("source1", "doc1"));
        registry.register(createMetadata("source1", "doc2"));

        DocumentMetadata removed = registry.remove("source1", "doc1");
        assertNotNull(removed);
        assertEquals("doc1", removed.getDocumentId());

        assertNull(registry.get("source1", "doc1"));
        assertNotNull(registry.get("source1", "doc2"));
        assertEquals(1, registry.size());
    }

    @Test
    void testRemoveBySource() {
        registry.register(createMetadata("source1", "doc1"));
        registry.register(createMetadata("source1", "doc2"));
        registry.register(createMetadata("source2", "doc1"));

        int removed = registry.removeBySource("source1");
        assertEquals(2, removed);
        assertEquals(0, registry.size("source1"));
        assertEquals(1, registry.size("source2"));
    }

    @Test
    void testContentHashIsPositive() {
        byte[] content = "test content for hashing".getBytes(StandardCharsets.UTF_8);
        long hash = DocumentRegistry.computeContentHash(content);

        // Domain constraint: hash must be positive
        assertTrue(hash >= 0, "Hash must be non-negative: " + hash);
        assertTrue(hash <= Long.MAX_VALUE, "Hash must fit in positive long range: " + hash);
    }

    @Test
    void testContentHashIsDeterministic() {
        byte[] content = "same content".getBytes(StandardCharsets.UTF_8);
        long hash1 = DocumentRegistry.computeContentHash(content);
        long hash2 = DocumentRegistry.computeContentHash(content);

        assertEquals(hash1, hash2, "Same content must produce same hash");
    }

    @Test
    void testContentHashDiffersForDifferentContent() {
        long hash1 = DocumentRegistry.computeContentHash("content1".getBytes());
        long hash2 = DocumentRegistry.computeContentHash("content2".getBytes());

        assertNotEquals(hash1, hash2, "Different content must produce different hashes");
    }

    @Test
    void testPersistence(@TempDir Path tempDir) throws IOException {
        Path registryFile = tempDir.resolve("registry.dat");
        
        DocumentRegistry persistentRegistry = new DocumentRegistry(registryFile);
        
        persistentRegistry.register(createMetadata("source1", "doc1", 1000L, 100));
        persistentRegistry.register(createMetadata("source1", "doc2", 2000L, 200));
        persistentRegistry.persist();

        // Load into new registry
        DocumentRegistry loadedRegistry = new DocumentRegistry(registryFile);
        
        assertEquals(2, loadedRegistry.size());
        assertNotNull(loadedRegistry.get("source1", "doc1"));
        assertNotNull(loadedRegistry.get("source1", "doc2"));
    }

    // ==================== ChangeSet Tests ====================

    @Test
    void testChangeSetBuilder() {
        DocumentMetadata oldMeta = createMetadata("source1", "doc1", 1000L, 100);
        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(2000L, 9999L, 150);

        ChangeSet changeSet = ChangeSet.builder("source1")
            .addNew("newDoc1")
            .addNew("newDoc2")
            .addChanged("doc1", oldMeta, fetchedInfo)
            .addDeleted(createMetadata("source1", "oldDoc"))
            .totalCrawled(10)
            .totalRegistered(20)
            .build();

        assertEquals(2, changeSet.getNewCount());
        assertEquals(1, changeSet.getChangedCount());
        assertEquals(1, changeSet.getDeletedCount());
        assertEquals(10, changeSet.getTotalCrawled());
        assertEquals(20, changeSet.getTotalRegistered());
        assertTrue(changeSet.hasChanges());
    }

    @Test
    void testChangeSetNoChanges() {
        ChangeSet changeSet = ChangeSet.builder("source1")
            .totalCrawled(10)
            .totalRegistered(10)
            .build();

        assertEquals(0, changeSet.getNewCount());
        assertEquals(0, changeSet.getChangedCount());
        assertEquals(0, changeSet.getDeletedCount());
        assertFalse(changeSet.hasChanges());
    }

    @Test
    void testChunksToTombstone() {
        DocumentMetadata oldMeta1 = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc1")
            .addChunkId("chunk1")
            .addChunkId("chunk2")
            .build();
        
        DocumentMetadata oldMeta2 = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc2")
            .addChunkId("chunk3")
            .build();

        DocumentMetadata deletedMeta = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc3")
            .addChunkId("chunk4")
            .addChunkId("chunk5")
            .build();

        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(2000L, 9999L, 150);

        ChangeSet changeSet = ChangeSet.builder("source1")
            .addChanged("doc1", oldMeta1, fetchedInfo)
            .addChanged("doc2", oldMeta2, fetchedInfo)
            .addDeleted(deletedMeta)
            .build();

        List<String> chunksToTombstone = changeSet.getChunksToTombstone();
        
        assertEquals(5, chunksToTombstone.size());
        assertTrue(chunksToTombstone.contains("chunk1"));
        assertTrue(chunksToTombstone.contains("chunk2"));
        assertTrue(chunksToTombstone.contains("chunk3"));
        assertTrue(chunksToTombstone.contains("chunk4"));
        assertTrue(chunksToTombstone.contains("chunk5"));
    }

    // ==================== ChangeDetector Tests ====================

    @Test
    void testDetectNewDocuments() throws SourceException {
        Source source = new MockSource("test-source", 
            List.of("doc1", "doc2", "doc3"),
            docId -> new FetchedDocument(docId, 
                ("content for " + docId).getBytes(), 
                "text/plain", 
                System.currentTimeMillis()));

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(3, changes.getNewCount());
        assertEquals(0, changes.getChangedCount());
        assertEquals(0, changes.getDeletedCount());
        assertTrue(changes.getNewDocuments().contains("doc1"));
        assertTrue(changes.getNewDocuments().contains("doc2"));
        assertTrue(changes.getNewDocuments().contains("doc3"));
    }

    @Test
    void testDetectDeletedDocuments() throws SourceException {
        byte[] doc1Content = "content for doc1".getBytes(StandardCharsets.UTF_8);
        byte[] doc2Content = "content for doc2".getBytes(StandardCharsets.UTF_8);
        byte[] doc3Content = "content for doc3".getBytes(StandardCharsets.UTF_8);
        
        long doc1Hash = DocumentRegistry.computeContentHash(doc1Content);
        long doc2Hash = DocumentRegistry.computeContentHash(doc2Content);
        long doc3Hash = DocumentRegistry.computeContentHash(doc3Content);
        
        registry.register(DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(doc1Hash)
            .contentLength(doc1Content.length)
            .lastModifiedMillis(1000L)
            .build());
        registry.register(DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc2")
            .contentHash(doc2Hash)
            .contentLength(doc2Content.length)
            .lastModifiedMillis(1000L)
            .build());
        registry.register(DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc3")
            .contentHash(doc3Hash)
            .contentLength(doc3Content.length)
            .lastModifiedMillis(1000L)
            .build());

        Source source = new MockSource("test-source", 
            List.of("doc1", "doc2"),
            docId -> {
                byte[] content = docId.equals("doc1") ? doc1Content : doc2Content;
                return new FetchedDocument(docId, content, "text/plain", 1000L);
            });

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(0, changes.getNewCount());
        assertEquals(0, changes.getChangedCount());
        assertEquals(1, changes.getDeletedCount());
        assertEquals("doc3", changes.getDeletedDocuments().get(0).getDocumentId());
    }

    @Test
    void testDetectChangedDocumentsByHash() throws SourceException {
        // Register with one content hash
        byte[] oldContent = "old content".getBytes(StandardCharsets.UTF_8);
        long oldHash = DocumentRegistry.computeContentHash(oldContent);
        
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(oldHash)
            .contentLength(oldContent.length)
            .lastModifiedMillis(1000L)
            .addChunkId("chunk1")
            .build();
        registry.register(metadata);

        // Fetch with different content (different hash)
        byte[] newContent = "new content that is different".getBytes(StandardCharsets.UTF_8);
        Source source = new MockSource("test-source", 
            List.of("doc1"),
            docId -> new FetchedDocument(docId, newContent, "text/plain", 1000L));

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(0, changes.getNewCount());
        assertEquals(1, changes.getChangedCount());
        assertEquals(0, changes.getDeletedCount());
        
        ChangeSet.ChangedDocument changed = changes.getChangedDocuments().get(0);
        assertEquals("doc1", changed.getDocumentId());
        assertEquals("chunk1", changed.getOldMetadata().getChunkIds().get(0));
    }

    @Test
    void testDetectChangedDocumentsByTimestamp() throws SourceException {
        // Register with old timestamp
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(12345L)
            .contentLength(100)
            .lastModifiedMillis(1000L)
            .build();
        registry.register(metadata);

        // Fetch with newer timestamp
        Source source = new MockSource("test-source", 
            List.of("doc1"),
            docId -> new FetchedDocument(docId, 
                "content".getBytes(), 
                "text/plain", 
                2000L)); // Newer timestamp

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(1, changes.getChangedCount());
    }

    @Test
    void testDetectChangedDocumentsByLength() throws SourceException {
        // Register with one length
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(12345L)
            .contentLength(100)
            .lastModifiedMillis(1000L)
            .build();
        registry.register(metadata);

        // Fetch with different length
        Source source = new MockSource("test-source", 
            List.of("doc1"),
            docId -> new FetchedDocument(docId, 
                "short".getBytes(), // Different length
                "text/plain", 
                1000L));

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(1, changes.getChangedCount());
    }

    @Test
    void testUnchangedDocumentsNotReported() throws SourceException {
        byte[] content = "same content".getBytes(StandardCharsets.UTF_8);
        long hash = DocumentRegistry.computeContentHash(content);
        
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(hash)
            .contentLength(content.length)
            .lastModifiedMillis(1000L)
            .build();
        registry.register(metadata);

        // Fetch with same content, length, and timestamp
        Source source = new MockSource("test-source", 
            List.of("doc1"),
            docId -> new FetchedDocument(docId, content, "text/plain", 1000L));

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(0, changes.getNewCount());
        assertEquals(0, changes.getChangedCount());
        assertEquals(0, changes.getDeletedCount());
        assertFalse(changes.hasChanges());
    }

    @Test
    void testMixedChanges() throws SourceException {
        // Pre-populate registry with some documents
        byte[] doc1Content = "doc1 content".getBytes();
        registry.register(DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("doc1")
            .contentHash(DocumentRegistry.computeContentHash(doc1Content))
            .contentLength(doc1Content.length)
            .lastModifiedMillis(1000L)
            .addChunkId("chunk1")
            .build());
        
        registry.register(createMetadata("test-source", "doc2")); // Will be deleted
        
        // doc3 is new (not in registry)
        
        // doc1 will be changed (different content)
        byte[] newDoc1Content = "doc1 new content".getBytes();

        Source source = new MockSource("test-source", 
            List.of("doc1", "doc3"), // doc2 missing = deleted
            docId -> {
                if (docId.equals("doc1")) {
                    return new FetchedDocument(docId, newDoc1Content, "text/plain", 2000L);
                }
                return new FetchedDocument(docId, ("content for " + docId).getBytes(), "text/plain", 1000L);
            });

        ChangeSet changes = detector.detectChanges(source);

        assertEquals(1, changes.getNewCount()); // doc3
        assertEquals(1, changes.getChangedCount()); // doc1
        assertEquals(1, changes.getDeletedCount()); // doc2
        assertTrue(changes.hasChanges());
    }

    @Test
    void testIncrementalDetection() throws SourceException {
        // Pre-populate registry
        for (int i = 0; i < 100; i++) {
            registry.register(createMetadata("test-source", "doc" + i));
        }

        Source source = new MockSource("test-source", 
            List.of("doc0", "doc1", "doc2", "doc3", "doc4"), // 95 deleted
            docId -> new FetchedDocument(docId, 
                ("content for " + docId).getBytes(), 
                "text/plain", 
                System.currentTimeMillis()));

        List<ChangeSet> batches = new java.util.ArrayList<>();
        ChangeSet finalChanges = detector.detectChangesIncremental(source, 2, batches::add);

        // Should have 3 batches: [doc0, doc1], [doc2, doc3], [doc4]
        assertEquals(3, batches.size());
        
        // Final changeset should contain deleted documents
        assertEquals(95, finalChanges.getDeletedCount());
    }

    @Test
    void testHasChangedMethod() throws SourceException {
        byte[] content = "test content".getBytes();
        long hash = DocumentRegistry.computeContentHash(content);
        
        registry.register(DocumentMetadata.builder()
            .sourceId("test-source")
            .documentId("existing-doc")
            .contentHash(hash)
            .contentLength(content.length)
            .lastModifiedMillis(1000L)
            .build());

        MockSource source = new MockSource("test-source", 
            List.of("existing-doc", "new-doc"),
            docId -> new FetchedDocument(docId, content, "text/plain", 1000L));

        // New document
        assertTrue(detector.hasChanged(source, "new-doc"));

        // Existing document, unchanged
        assertFalse(detector.hasChanged(source, "existing-doc"));

        // Existing document, changed content
        source.setFetchFunction(docId -> 
            new FetchedDocument(docId, "different content".getBytes(), "text/plain", 2000L));
        assertTrue(detector.hasChanged(source, "existing-doc"));
    }

    // ==================== UpsertHandler Tests ====================

    @Test
    void testUpsertHandlerTombstonesChunks() {
        // Track tombstoned chunks
        List<String> tombstonedChunks = new java.util.ArrayList<>();
        
        UpsertHandler.StorageAdapter storageAdapter = key -> {
            tombstonedChunks.add(new String(key, StandardCharsets.UTF_8));
            return true;
        };

        UpsertHandler handler = new UpsertHandler(registry, (CuckooFilter) null, storageAdapter);

        DocumentMetadata oldMeta = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc1")
            .addChunkId("chunk1")
            .addChunkId("chunk2")
            .build();

        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(2000L, 9999L, 100);

        ChangeSet changeSet = ChangeSet.builder("source1")
            .addChanged("doc1", oldMeta, fetchedInfo)
            .build();

        UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

        assertEquals(1, result.getDocumentsChanged());
        assertEquals(2, result.getChunksTombstoned());
        assertEquals(2, tombstonedChunks.size());
        assertTrue(tombstonedChunks.contains("chunk1"));
        assertTrue(tombstonedChunks.contains("chunk2"));
    }

    @Test
    void testUpsertHandlerRemovesFromRegistry() {
        DocumentMetadata deletedMeta = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc1")
            .addChunkId("chunk1")
            .build();

        registry.register(deletedMeta);
        assertNotNull(registry.get("source1", "doc1"));

        UpsertHandler handler = new UpsertHandler(registry, (CuckooFilter) null, key -> true);

        ChangeSet changeSet = ChangeSet.builder("source1")
            .addDeleted(deletedMeta)
            .build();

        UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

        assertEquals(1, result.getDocumentsDeleted());
        assertNull(registry.get("source1", "doc1")); // Removed from registry
    }

    @Test
    void testUpsertHandlerCollectsErrors() {
        UpsertHandler.StorageAdapter failingStorage = key -> {
            throw new RuntimeException("Storage error");
        };

        UpsertHandler handler = new UpsertHandler(registry, (CuckooFilter) null, failingStorage);

        DocumentMetadata oldMeta = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc1")
            .addChunkId("chunk1")
            .build();

        DocumentMetadata.FetchedDocumentInfo fetchedInfo = 
            new DocumentMetadata.FetchedDocumentInfo(2000L, 9999L, 100);

        ChangeSet changeSet = ChangeSet.builder("source1")
            .addChanged("doc1", oldMeta, fetchedInfo)
            .build();

        UpsertHandler.UpsertResult result = handler.processChanges(changeSet);

        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        assertEquals("storage", result.getErrors().get(0).getComponent());
        assertEquals("chunk1", result.getErrors().get(0).getChunkId());
    }

    @Test
    void testTombstoneSingleChunk() {
        List<String> tombstonedChunks = new java.util.ArrayList<>();
        UpsertHandler handler = new UpsertHandler(registry, (CuckooFilter) null, key -> {
            tombstonedChunks.add(new String(key, StandardCharsets.UTF_8));
            return true;
        });

        boolean result = handler.tombstoneChunk("test-chunk");

        assertTrue(result);
        assertEquals(1, tombstonedChunks.size());
        assertEquals("test-chunk", tombstonedChunks.get(0));
    }

    @Test
    void testTombstoneMultipleChunks() {
        List<String> tombstonedChunks = new java.util.ArrayList<>();
        UpsertHandler handler = new UpsertHandler(registry, (CuckooFilter) null, key -> {
            tombstonedChunks.add(new String(key, StandardCharsets.UTF_8));
            return true;
        });

        int count = handler.tombstoneChunks(List.of("chunk1", "chunk2", "chunk3"));

        assertEquals(3, count);
        assertEquals(3, tombstonedChunks.size());
    }

    // ==================== DocumentMetadata Tests ====================

    @Test
    void testMetadataIsChangedMethod() {
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId("source1")
            .documentId("doc1")
            .contentHash(1000L)
            .contentLength(100)
            .lastModifiedMillis(1000L)
            .build();

        // Same values - not changed
        DocumentMetadata.FetchedDocumentInfo sameInfo = 
            new DocumentMetadata.FetchedDocumentInfo(1000L, 1000L, 100);
        assertFalse(metadata.isChanged(sameInfo));

        // Different timestamp - changed
        DocumentMetadata.FetchedDocumentInfo newerTimestamp = 
            new DocumentMetadata.FetchedDocumentInfo(2000L, 1000L, 100);
        assertTrue(metadata.isChanged(newerTimestamp));

        // Different length - changed
        DocumentMetadata.FetchedDocumentInfo differentLength = 
            new DocumentMetadata.FetchedDocumentInfo(1000L, 1000L, 200);
        assertTrue(metadata.isChanged(differentLength));

        // Different hash - changed
        DocumentMetadata.FetchedDocumentInfo differentHash = 
            new DocumentMetadata.FetchedDocumentInfo(1000L, 2000L, 100);
        assertTrue(metadata.isChanged(differentHash));

        // Null fetched info - changed
        assertTrue(metadata.isChanged(null));
    }

    // ==================== Helper Methods ====================

    private DocumentMetadata createMetadata(String sourceId, String documentId) {
        return createMetadata(sourceId, documentId, 12345L, 100);
    }

    private DocumentMetadata createMetadata(String sourceId, String documentId, 
                                            long hash, int length) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(hash)
            .contentLength(length)
            .lastModifiedMillis(System.currentTimeMillis())
            .build();
    }

    // ==================== Mock Source ====================

    private static class MockSource implements Source {
        private final String sourceId;
        private final List<String> documentIds;
        private java.util.function.Function<String, FetchedDocument> fetchFunction;

        MockSource(String sourceId, List<String> documentIds,
                   java.util.function.Function<String, FetchedDocument> fetchFunction) {
            this.sourceId = sourceId;
            this.documentIds = documentIds;
            this.fetchFunction = fetchFunction;
        }

        void setFetchFunction(java.util.function.Function<String, FetchedDocument> fn) {
            this.fetchFunction = fn;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "mock";
        }

        @Override
        public Stream<String> crawl() {
            return documentIds.stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            return fetchFunction.apply(documentId);
        }

        @Override
        public void close() {
        }
    }
}
