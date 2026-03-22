package excalibur.ingest.visibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DocumentVisibilityRegistry.
 * 
 * Tests document-atomic visibility guarantees:
 * - Documents become visible at a specific epoch
 * - Queries never see partial documents
 * - Chunk-to-document mapping is correct
 */
class DocumentVisibilityRegistryTest {

    private DocumentVisibilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentVisibilityRegistry();
    }

    @Test
    void testConstructor() {
        assertEquals(0, registry.getDocumentCount());
        assertEquals(0, registry.getTotalChunkCount());
    }

    @Test
    void testRegisterDocument_nullDocumentId() {
        Set<String> chunks = Set.of("chunk1");
        assertThrows(NullPointerException.class, () -> 
            registry.registerDocument((String) null, chunks, 1));
    }

    @Test
    void testRegisterDocument_nullChunkIds() {
        assertThrows(NullPointerException.class, () -> 
            registry.registerDocument("doc1", (Set<String>) null, 1));
    }

    @Test
    void testRegisterDocument_negativeEpoch() {
        Set<String> chunks = Set.of("chunk1");
        assertThrows(IllegalArgumentException.class, () -> 
            registry.registerDocument("doc1", chunks, -1));
    }

    @Test
    void testRegisterDocument_singleChunk() {
        Set<String> chunks = Set.of("doc1:0");
        registry.registerDocument("doc1", chunks, 5);
        
        assertEquals(1, registry.getDocumentCount());
        assertEquals(1, registry.getTotalChunkCount());
        assertTrue(registry.isRegistered("doc1"));
        assertEquals(5, registry.getVisibleEpoch("doc1"));
    }

    @Test
    void testRegisterDocument_multipleChunks() {
        Set<String> chunks = Set.of("doc1:0", "doc1:1", "doc1:2");
        registry.registerDocument("doc1", chunks, 10);
        
        assertEquals(1, registry.getDocumentCount());
        assertEquals(3, registry.getTotalChunkCount());
        assertTrue(registry.isRegistered("doc1"));
        assertEquals(10, registry.getVisibleEpoch("doc1"));
        assertEquals(3, registry.getChunksForDocument("doc1").size());
    }

    @Test
    void testRegisterDocument_withCollection() {
        List<String> chunks = List.of("doc1:0", "doc1:1");
        registry.registerDocument("doc1", chunks, 3);
        
        assertEquals(1, registry.getDocumentCount());
        assertEquals(2, registry.getTotalChunkCount());
    }

    @Test
    void testRegisterDocument_multipleDocuments() {
        registry.registerDocument("doc1", Set.of("doc1:0", "doc1:1"), 5);
        registry.registerDocument("doc2", Set.of("doc2:0"), 7);
        registry.registerDocument("doc3", Set.of("doc3:0", "doc3:1", "doc3:2"), 9);
        
        assertEquals(3, registry.getDocumentCount());
        assertEquals(6, registry.getTotalChunkCount());
    }

    @Test
    void testIsVisibleAt_nullDocumentId() {
        assertThrows(NullPointerException.class, () -> registry.isVisibleAt(null, 1));
    }

    @Test
    void testIsVisibleAt_notRegistered() {
        assertFalse(registry.isVisibleAt("nonexistent", 1));
    }

    @Test
    void testIsVisibleAt_exactEpoch() {
        registry.registerDocument("doc1", Set.of("doc1:0"), 5);
        
        assertTrue(registry.isVisibleAt("doc1", 5));
    }

    @Test
    void testIsVisibleAt_laterEpoch() {
        registry.registerDocument("doc1", Set.of("doc1:0"), 5);
        
        assertTrue(registry.isVisibleAt("doc1", 6));
        assertTrue(registry.isVisibleAt("doc1", 10));
        assertTrue(registry.isVisibleAt("doc1", 100));
    }

    @Test
    void testIsVisibleAt_earlierEpoch() {
        registry.registerDocument("doc1", Set.of("doc1:0"), 5);
        
        assertFalse(registry.isVisibleAt("doc1", 0));
        assertFalse(registry.isVisibleAt("doc1", 4));
    }

    @Test
    void testIsVisibleAt_epochZero() {
        registry.registerDocument("doc1", Set.of("doc1:0"), 0);
        
        assertTrue(registry.isVisibleAt("doc1", 0));
        assertTrue(registry.isVisibleAt("doc1", 1));
    }

    @Test
    void testIsChunkVisibleAt_nullChunkId() {
        assertThrows(NullPointerException.class, () -> registry.isChunkVisibleAt(null, 1));
    }

    @Test
    void testIsChunkVisibleAt_chunkNotRegistered() {
        assertFalse(registry.isChunkVisibleAt("nonexistent", 1));
    }

    @Test
    void testIsChunkVisibleAt_visibleDocument() {
        registry.registerDocument("doc1", Set.of("doc1:0", "doc1:1"), 5);
        
        assertTrue(registry.isChunkVisibleAt("doc1:0", 5));
        assertTrue(registry.isChunkVisibleAt("doc1:1", 5));
        assertTrue(registry.isChunkVisibleAt("doc1:0", 10));
        assertFalse(registry.isChunkVisibleAt("doc1:0", 4));
    }

    @Test
    void testGetDocumentForChunk() {
        registry.registerDocument("doc1", Set.of("chunk1", "chunk2"), 5);
        
        assertEquals("doc1", registry.getDocumentForChunk("chunk1"));
        assertEquals("doc1", registry.getDocumentForChunk("chunk2"));
        assertNull(registry.getDocumentForChunk("nonexistent"));
    }

    @Test
    void testGetVisibleEpoch_notRegistered() {
        assertEquals(-1, registry.getVisibleEpoch("nonexistent"));
    }

    @Test
    void testGetChunksForDocument_notRegistered() {
        Set<String> chunks = registry.getChunksForDocument("nonexistent");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testFilterVisibleChunks() {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 5);
        registry.registerDocument("doc2", Set.of("c3", "c4"), 10);
        registry.registerDocument("doc3", Set.of("c5"), 3);
        
        Set<String> allChunks = Set.of("c1", "c2", "c3", "c4", "c5");
        
        Set<String> visibleAt5 = registry.filterVisibleChunks(allChunks, 5);
        assertTrue(visibleAt5.contains("c1"));
        assertTrue(visibleAt5.contains("c2"));
        assertTrue(visibleAt5.contains("c5"));
        assertFalse(visibleAt5.contains("c3"));
        assertFalse(visibleAt5.contains("c4"));
        
        Set<String> visibleAt10 = registry.filterVisibleChunks(allChunks, 10);
        assertEquals(5, visibleAt10.size());
    }

    @Test
    void testFilterVisibleDocuments() {
        registry.registerDocument("doc1", Set.of("c1"), 5);
        registry.registerDocument("doc2", Set.of("c2"), 10);
        registry.registerDocument("doc3", Set.of("c3"), 3);
        
        Set<String> allDocs = Set.of("doc1", "doc2", "doc3");
        
        Set<String> visibleAt5 = registry.filterVisibleDocuments(allDocs, 5);
        assertTrue(visibleAt5.contains("doc1"));
        assertTrue(visibleAt5.contains("doc3"));
        assertFalse(visibleAt5.contains("doc2"));
        
        Set<String> visibleAt10 = registry.filterVisibleDocuments(allDocs, 10);
        assertEquals(3, visibleAt10.size());
    }

    @Test
    void testTombstoneDocument_null() {
        assertThrows(NullPointerException.class, () -> registry.tombstoneDocument(null));
    }

    @Test
    void testTombstoneDocument_notRegistered() {
        assertFalse(registry.tombstoneDocument("nonexistent"));
    }

    @Test
    void testTombstoneDocument_success() {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 5);
        
        assertTrue(registry.tombstoneDocument("doc1"));
        
        assertFalse(registry.isRegistered("doc1"));
        assertEquals(-1, registry.getVisibleEpoch("doc1"));
        assertNull(registry.getDocumentForChunk("c1"));
        assertNull(registry.getDocumentForChunk("c2"));
        assertEquals(0, registry.getDocumentCount());
        assertEquals(0, registry.getTotalChunkCount());
    }

    @Test
    void testTombstoneDocument_partial() {
        registry.registerDocument("doc1", Set.of("c1"), 5);
        registry.registerDocument("doc2", Set.of("c2"), 7);
        
        assertTrue(registry.tombstoneDocument("doc1"));
        
        assertEquals(1, registry.getDocumentCount());
        assertTrue(registry.isRegistered("doc2"));
        assertFalse(registry.isRegistered("doc1"));
    }

    @Test
    void testReplaceDocument_nullDocumentId() {
        assertThrows(NullPointerException.class, () -> 
            registry.replaceDocument(null, Set.of("c1"), 1));
    }

    @Test
    void testReplaceDocument_nullChunkIds() {
        assertThrows(NullPointerException.class, () -> 
            registry.replaceDocument("doc1", null, 1));
    }

    @Test
    void testReplaceDocument_negativeEpoch() {
        assertThrows(IllegalArgumentException.class, () -> 
            registry.replaceDocument("doc1", Set.of("c1"), -1));
    }

    @Test
    void testReplaceDocument_newDocument() {
        registry.replaceDocument("doc1", Set.of("c1", "c2"), 5);
        
        assertTrue(registry.isRegistered("doc1"));
        assertEquals(5, registry.getVisibleEpoch("doc1"));
        assertEquals(2, registry.getChunksForDocument("doc1").size());
    }

    @Test
    void testReplaceDocument_existingDocument() {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 5);
        
        registry.replaceDocument("doc1", Set.of("c3", "c4", "c5"), 10);
        
        assertEquals(10, registry.getVisibleEpoch("doc1"));
        assertEquals(3, registry.getChunksForDocument("doc1").size());
        assertTrue(registry.getChunksForDocument("doc1").contains("c3"));
        assertFalse(registry.getChunksForDocument("doc1").contains("c1"));
        assertNull(registry.getDocumentForChunk("c1"));
        assertEquals("doc1", registry.getDocumentForChunk("c3"));
    }

    @Test
    void testGetAllDocumentIds() {
        registry.registerDocument("doc1", Set.of("c1"), 1);
        registry.registerDocument("doc2", Set.of("c2"), 2);
        registry.registerDocument("doc3", Set.of("c3"), 3);
        
        Set<String> ids = registry.getAllDocumentIds();
        assertEquals(3, ids.size());
        assertTrue(ids.contains("doc1"));
        assertTrue(ids.contains("doc2"));
        assertTrue(ids.contains("doc3"));
    }

    @Test
    void testClear() {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 5);
        registry.registerDocument("doc2", Set.of("c3"), 7);
        
        registry.clear();
        
        assertEquals(0, registry.getDocumentCount());
        assertEquals(0, registry.getTotalChunkCount());
        assertFalse(registry.isRegistered("doc1"));
        assertFalse(registry.isRegistered("doc2"));
    }

    @Test
    void testGetStats() {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 5);
        registry.registerDocument("doc2", Set.of("c3", "c4", "c5"), 7);
        
        DocumentVisibilityRegistry.VisibilityStats stats = registry.getStats();
        
        assertEquals(2, stats.documentCount());
        assertEquals(5, stats.chunkCount());
        assertEquals(2.5, stats.avgChunksPerDocument(), 0.01);
    }

    @Test
    void testGetStats_empty() {
        DocumentVisibilityRegistry.VisibilityStats stats = registry.getStats();
        
        assertEquals(0, stats.documentCount());
        assertEquals(0, stats.chunkCount());
        assertEquals(0.0, stats.avgChunksPerDocument(), 0.01);
    }

    @Test
    void testDocumentAtomicity_simulation() {
        registry.registerDocument("doc1", Set.of("doc1:0", "doc1:1", "doc1:2"), 100);
        
        long queryEpoch = 100;
        
        assertTrue(registry.isChunkVisibleAt("doc1:0", queryEpoch));
        assertTrue(registry.isChunkVisibleAt("doc1:1", queryEpoch));
        assertTrue(registry.isChunkVisibleAt("doc1:2", queryEpoch));
        
        long earlyQueryEpoch = 99;
        
        assertFalse(registry.isChunkVisibleAt("doc1:0", earlyQueryEpoch));
        assertFalse(registry.isChunkVisibleAt("doc1:1", earlyQueryEpoch));
        assertFalse(registry.isChunkVisibleAt("doc1:2", earlyQueryEpoch));
    }

    @Test
    void testConcurrentRegistration() throws InterruptedException {
        int numThreads = 10;
        int docsPerThread = 100;
        Thread[] threads = new Thread[numThreads];
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < docsPerThread; i++) {
                    String docId = "doc" + threadId + "_" + i;
                    Set<String> chunks = Set.of(docId + ":0", docId + ":1");
                    registry.registerDocument(docId, chunks, threadId * 100 + i);
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(numThreads * docsPerThread, registry.getDocumentCount());
        assertEquals(numThreads * docsPerThread * 2, registry.getTotalChunkCount());
    }

    @Test
    void testConcurrentVisibilityChecks() throws InterruptedException {
        registry.registerDocument("doc1", Set.of("c1", "c2"), 50);
        
        int numReaders = 20;
        Thread[] readers = new Thread[numReaders];
        boolean[] results = new boolean[numReaders];
        
        for (int i = 0; i < numReaders; i++) {
            final int idx = i;
            final long queryEpoch = idx < 10 ? 60 : 40;
            readers[i] = new Thread(() -> {
                results[idx] = registry.isVisibleAt("doc1", queryEpoch);
            });
        }
        
        for (Thread reader : readers) {
            reader.start();
        }
        
        for (Thread reader : readers) {
            reader.join();
        }
        
        for (int i = 0; i < 10; i++) {
            assertTrue(results[i], "Query at epoch 60 should see document");
        }
        for (int i = 10; i < 20; i++) {
            assertFalse(results[i], "Query at epoch 40 should not see document");
        }
    }

    @Test
    void testToString() {
        registry.registerDocument("doc1", Set.of("c1"), 5);
        String str = registry.toString();
        assertTrue(str.contains("documents=1"));
        assertTrue(str.contains("chunks=1"));
    }
}
