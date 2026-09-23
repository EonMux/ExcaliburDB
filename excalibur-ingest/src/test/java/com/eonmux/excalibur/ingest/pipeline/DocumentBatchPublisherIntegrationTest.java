package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityRegistry;
import com.eonmux.excalibur.store.VisibilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DocumentBatchPublisher with DocumentVisibilityRegistry.
 *
 * Tests the document-atomic visibility contract:
 * - Documents are registered in the visibility registry at publication time
 * - All chunks of a document become visible at the same epoch
 * - Queries can filter out partial documents using the registry
 */
class DocumentBatchPublisherIntegrationTest {

    private StubVisibilityTracker tracker;
    private DocumentRegistry docRegistry;
    private DocumentVisibilityRegistry visibilityRegistry;
    private DocumentBatchPublisher publisher;
    private AtomicLong epochCounter;
    private boolean syncCalled;

    @BeforeEach
    void setUp() {
        epochCounter = new AtomicLong(0);
        syncCalled = false;
        
        tracker = new StubVisibilityTracker(epochCounter);
        docRegistry = new DocumentRegistry();
        visibilityRegistry = new DocumentVisibilityRegistry();

        DocumentBatchPublisher.WriteLogAdapter writeLogAdapter = () -> {
            syncCalled = true;
        };

        publisher = new DocumentBatchPublisher(
            tracker,
            docRegistry,
            visibilityRegistry,
            writeLogAdapter,
            null
        );
    }

    @Test
    void testPublishRegistersInVisibilityRegistry() throws Exception {
        DocumentBatchBuffer buffer = new DocumentBatchBuffer("doc1", "source1");
        buffer.addChunk("doc1:0", createChunk("doc1", 0), 100);
        buffer.addChunk("doc1:1", createChunk("doc1", 1), 200);
        buffer.seal();

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId("doc1")
            .sourceId("source1")
            .build();

        publisher.publish(buffer, metadata);

        assertTrue(visibilityRegistry.isRegistered("doc1"));
        assertEquals(1, visibilityRegistry.getVisibleEpoch("doc1"));
        assertEquals(2, visibilityRegistry.getChunksForDocument("doc1").size());
        assertTrue(visibilityRegistry.isVisibleAt("doc1", 1));
        assertTrue(visibilityRegistry.isVisibleAt("doc1", 100));
        assertFalse(visibilityRegistry.isVisibleAt("doc1", 0));
    }

    @Test
    void testPublishWithoutVisibilityRegistry() throws Exception {
        publisher = new DocumentBatchPublisher(
            tracker,
            docRegistry,
            (DocumentVisibilityRegistry) null,
            () -> {},
            null
        );

        DocumentBatchBuffer buffer = new DocumentBatchBuffer("doc1", "source1");
        buffer.addChunk("doc1:0", createChunk("doc1", 0), 100);
        buffer.seal();

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId("doc1")
            .sourceId("source1")
            .build();

        publisher.publish(buffer, metadata);

        assertFalse(visibilityRegistry.isRegistered("doc1"));
    }

    @Test
    void testBulkPublishWithVisibilityRegistry() throws Exception {
        List<DocumentBatchBuffer> buffers = new ArrayList<>();
        List<DocumentMetadata> metadataList = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            DocumentBatchBuffer buffer = new DocumentBatchBuffer("doc" + i, "source1");
            buffer.addChunk("doc" + i + ":0", createChunk("doc" + i, 0), 100 + i);
            buffer.addChunk("doc" + i + ":1", createChunk("doc" + i, 1), 200 + i);
            buffer.seal();
            buffers.add(buffer);

            metadataList.add(DocumentMetadata.builder()
                .documentId("doc" + i)
                .sourceId("source1")
                .build());
        }

        publisher.publishBulk(buffers, metadataList);

        assertEquals(3, visibilityRegistry.getDocumentCount());
        for (int i = 0; i < 3; i++) {
            assertTrue(visibilityRegistry.isRegistered("doc" + i));
            assertEquals(2, visibilityRegistry.getChunksForDocument("doc" + i).size());
        }
    }

    @Test
    void testDocumentAtomicityAcrossEpochs() throws Exception {
        DocumentBatchBuffer buffer = new DocumentBatchBuffer("doc1", "source1");
        buffer.addChunk("doc1:0", createChunk("doc1", 0), 100);
        buffer.addChunk("doc1:1", createChunk("doc1", 1), 200);
        buffer.addChunk("doc1:2", createChunk("doc1", 2), 300);
        buffer.seal();

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId("doc1")
            .sourceId("source1")
            .build();

        publisher.publish(buffer, metadata);

        long visibleEpoch = visibilityRegistry.getVisibleEpoch("doc1");
        
        assertTrue(visibilityRegistry.isChunkVisibleAt("doc1:0", visibleEpoch));
        assertTrue(visibilityRegistry.isChunkVisibleAt("doc1:1", visibleEpoch));
        assertTrue(visibilityRegistry.isChunkVisibleAt("doc1:2", visibleEpoch));
        
        assertFalse(visibilityRegistry.isChunkVisibleAt("doc1:0", visibleEpoch - 1));
        assertFalse(visibilityRegistry.isChunkVisibleAt("doc1:1", visibleEpoch - 1));
        assertFalse(visibilityRegistry.isChunkVisibleAt("doc1:2", visibleEpoch - 1));
    }

    @Test
    void testReindexingUpdatesVisibility() throws Exception {
        DocumentBatchBuffer buffer1 = new DocumentBatchBuffer("doc1", "source1");
        buffer1.addChunk("doc1:0", createChunk("doc1", 0), 100);
        buffer1.seal();

        DocumentMetadata metadata1 = DocumentMetadata.builder()
            .documentId("doc1")
            .sourceId("source1")
            .build();

        publisher.publish(buffer1, metadata1);
        long firstEpoch = visibilityRegistry.getVisibleEpoch("doc1");

        visibilityRegistry.tombstoneDocument("doc1");

        DocumentBatchBuffer buffer2 = new DocumentBatchBuffer("doc1", "source1");
        buffer2.addChunk("doc1:0", createChunk("doc1", 0), 200);
        buffer2.addChunk("doc1:1", createChunk("doc1", 1), 300);
        buffer2.seal();

        DocumentMetadata metadata2 = DocumentMetadata.builder()
            .documentId("doc1")
            .sourceId("source1")
            .build();

        publisher.publish(buffer2, metadata2);
        long secondEpoch = visibilityRegistry.getVisibleEpoch("doc1");

        assertTrue(secondEpoch > firstEpoch);
        assertEquals(2, visibilityRegistry.getChunksForDocument("doc1").size());
    }

    @Test
    void testGetVisibilityRegistry() {
        assertSame(visibilityRegistry, publisher.getVisibilityRegistry());
    }

    @Test
    void testFilterVisibleDocumentsAfterPublication() throws Exception {
        publisher.publish(createBuffer("doc1", 2), createMetadata("doc1"));
        publisher.publish(createBuffer("doc2", 3), createMetadata("doc2"));
        publisher.publish(createBuffer("doc3", 1), createMetadata("doc3"));
        publisher.publish(createBuffer("doc4", 2), createMetadata("doc4"));

        Set<String> allDocs = Set.of("doc1", "doc2", "doc3", "doc4");
        
        Set<String> visibleAfter2 = visibilityRegistry.filterVisibleDocuments(allDocs, 2);
        assertTrue(visibleAfter2.contains("doc1"));
        assertTrue(visibleAfter2.contains("doc2"));
        assertFalse(visibleAfter2.contains("doc3"));
        assertFalse(visibleAfter2.contains("doc4"));
        
        Set<String> visibleAfter4 = visibilityRegistry.filterVisibleDocuments(allDocs, 4);
        assertEquals(4, visibleAfter4.size());
    }

    @Test
    void testNoPartialDocumentsVisible() throws Exception {
        publisher.publish(createBuffer("doc1", 3), createMetadata("doc1"));
        long doc1Epoch = visibilityRegistry.getVisibleEpoch("doc1");
        
        Set<String> doc1Chunks = visibilityRegistry.getChunksForDocument("doc1");
        assertEquals(3, doc1Chunks.size());
        
        for (String chunkId : doc1Chunks) {
            assertTrue(visibilityRegistry.isChunkVisibleAt(chunkId, doc1Epoch),
                "Chunk " + chunkId + " should be visible at epoch " + doc1Epoch);
            assertFalse(visibilityRegistry.isChunkVisibleAt(chunkId, doc1Epoch - 1),
                "Chunk " + chunkId + " should not be visible at epoch " + (doc1Epoch - 1));
        }
    }

    @Test
    void testConcurrentPublicationWithVisibilityRegistry() throws Exception {
        int numThreads = 5;
        int docsPerThread = 10;
        Thread[] threads = new Thread[numThreads];
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < docsPerThread; i++) {
                    String docId = "doc" + threadId + "_" + i;
                    try {
                        DocumentBatchBuffer buffer = createBuffer(docId, 2);
                        DocumentMetadata metadata = createMetadata(docId);
                        publisher.publish(buffer, metadata);
                    } catch (Exception e) {
                        fail("Publication failed for " + docId + ": " + e.getMessage());
                    }
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(numThreads * docsPerThread, visibilityRegistry.getDocumentCount());
        
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < docsPerThread; i++) {
                String docId = "doc" + t + "_" + i;
                assertTrue(visibilityRegistry.isRegistered(docId),
                    "Document " + docId + " should be registered");
                long epoch = visibilityRegistry.getVisibleEpoch(docId);
                assertTrue(epoch > 0, "Document " + docId + " should have a valid epoch");
            }
        }
    }

    @Test
    void testBulkPublishCountsEveryDocumentAndChunkAtOneEpoch() throws Exception {
        DocumentBatchBuffer first = createBuffer("first", 2);
        DocumentBatchBuffer second = createBuffer("second", 3);

        DocumentBatchPublisher.BulkPublicationResult result = publisher.publishBulk(
            List.of(first, second),
            List.of(createMetadata("first"), createMetadata("second")));

        assertEquals(2, result.documentCount());
        assertEquals(5, result.totalChunks());
        assertTrue(syncCalled, "the write log is synced before the epoch advances");
        assertEquals(result.epoch(), visibilityRegistry.getVisibleEpoch("first"));
        assertEquals(result.epoch(), visibilityRegistry.getVisibleEpoch("second"));
        assertTrue(first.isPublished());
        assertTrue(second.isPublished());
    }

    private TextChunk createChunk(String documentId, int chunkIndex) {
        return new TextChunk(
            documentId,
            "content for " + documentId + " chunk " + chunkIndex,
            10,
            0,
            10,
            chunkIndex
        );
    }

    private DocumentBatchBuffer createBuffer(String documentId, int chunkCount) {
        DocumentBatchBuffer buffer = new DocumentBatchBuffer(documentId, "source1");
        for (int i = 0; i < chunkCount; i++) {
            buffer.addChunk(documentId + ":" + i, createChunk(documentId, i), 100 + i);
        }
        buffer.seal();
        return buffer;
    }

    private DocumentMetadata createMetadata(String documentId) {
        return DocumentMetadata.builder()
            .documentId(documentId)
            .sourceId("source1")
            .build();
    }

    private static class StubVisibilityTracker implements VisibilityTracker {
        private final AtomicLong epochCounter;

        StubVisibilityTracker(AtomicLong epochCounter) {
            this.epochCounter = epochCounter;
        }

        @Override
        public long currentEpoch() {
            return epochCounter.get();
        }

        @Override
        public long captureEpoch() {
            return epochCounter.get();
        }

        @Override
        public long advanceEpoch() {
            return epochCounter.incrementAndGet();
        }

        @Override
        public void registerSegment(long segmentId, long epoch) {
        }

        @Override
        public void markTombstoned(long segmentId, long epoch) {
        }

        @Override
        public java.util.Set<Long> visibleSegments(long queryEpoch) {
            return java.util.Set.of();
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            return true;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return 0;
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            return Long.MAX_VALUE;
        }
    }
}
