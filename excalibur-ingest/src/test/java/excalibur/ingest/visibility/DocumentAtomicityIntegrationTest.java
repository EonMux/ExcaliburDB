package excalibur.ingest.visibility;

import excalibur.ingest.registry.DocumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for document atomicity guarantee.
 *
 * <p>These tests verify that the document-atomic visibility contract
 * is maintained: queries never see partially-ingested documents.</p>
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>Basic atomicity - single document with multiple chunks</li>
 *   <li>Concurrent atomicity - multiple documents ingested concurrently</li>
 *   <li>Epoch isolation - queries see consistent snapshots</li>
 *   <li>Partial filtering - incomplete documents are filtered out</li>
 * </ul>
 *
 * <p>Note: Tests for DocumentAtomicityFilter with QueryResultItem are in
 * the query module to avoid circular dependencies.</p>
 */
class DocumentAtomicityIntegrationTest {

    private DocumentVisibilityRegistry registry;
    private DocumentVisibilityTracker tracker;

    @BeforeEach
    void setUp() {
        registry = new DocumentVisibilityRegistry();
        tracker = new DocumentVisibilityTracker(new DocumentRegistry());
    }

    @Test
    void testDocumentNotVisibleBeforeRegistration() {
        Set<String> chunkIds = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        long queryEpoch = 10L;

        assertFalse(registry.isVisibleAt("doc1", queryEpoch));
        assertFalse(tracker.isDocumentFullyVisible("doc1", queryEpoch));
    }

    @Test
    void testDocumentVisibleAfterRegistration() {
        Set<String> chunkIds = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        long visibleEpoch = 5L;
        
        registry.registerDocument("doc1", chunkIds, visibleEpoch);

        assertTrue(registry.isVisibleAt("doc1", 5L));
        assertTrue(registry.isVisibleAt("doc1", 10L));
        assertTrue(registry.isVisibleAt("doc1", 100L));
        assertFalse(registry.isVisibleAt("doc1", 4L));
        assertFalse(registry.isVisibleAt("doc1", 0L));
    }

    @Test
    void testChunkVisibilityFollowsDocumentVisibility() {
        Set<String> chunkIds = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        long visibleEpoch = 10L;
        
        registry.registerDocument("doc1", chunkIds, visibleEpoch);

        assertTrue(registry.isChunkVisibleAt("doc1:chunk:0", 10L));
        assertTrue(registry.isChunkVisibleAt("doc1:chunk:1", 10L));
        assertTrue(registry.isChunkVisibleAt("doc1:chunk:2", 10L));
        assertFalse(registry.isChunkVisibleAt("doc1:chunk:0", 9L));
    }

    @Test
    void testFilterVisibleChunks() {
        Set<String> doc1Chunks = Set.of("doc1:chunk:0", "doc1:chunk:1");
        Set<String> doc2Chunks = Set.of("doc2:chunk:0", "doc2:chunk:1", "doc2:chunk:2");
        Set<String> doc3Chunks = Set.of("doc3:chunk:0");

        registry.registerDocument("doc1", doc1Chunks, 5L);
        registry.registerDocument("doc2", doc2Chunks, 10L);

        Set<String> allChunks = new HashSet<>();
        allChunks.addAll(doc1Chunks);
        allChunks.addAll(doc2Chunks);
        allChunks.addAll(doc3Chunks);

        Set<String> visibleAtEpoch5 = registry.filterVisibleChunks(allChunks, 5L);
        assertEquals(2, visibleAtEpoch5.size());
        assertTrue(visibleAtEpoch5.containsAll(doc1Chunks));
        assertFalse(visibleAtEpoch5.containsAll(doc2Chunks));

        Set<String> visibleAtEpoch10 = registry.filterVisibleChunks(allChunks, 10L);
        assertEquals(5, visibleAtEpoch10.size());
        assertTrue(visibleAtEpoch10.containsAll(doc1Chunks));
        assertTrue(visibleAtEpoch10.containsAll(doc2Chunks));
        assertFalse(visibleAtEpoch10.contains("doc3:chunk:0"));
    }

    @Test
    void testTombstoneRemovesVisibility() {
        Set<String> chunkIds = Set.of("doc1:chunk:0", "doc1:chunk:1");
        long visibleEpoch = 5L;
        
        registry.registerDocument("doc1", chunkIds, visibleEpoch);
        assertTrue(registry.isVisibleAt("doc1", 10L));

        boolean removed = registry.tombstoneDocument("doc1");
        assertTrue(removed);
        
        assertFalse(registry.isVisibleAt("doc1", 10L));
        assertFalse(registry.isChunkVisibleAt("doc1:chunk:0", 10L));
    }

    @Test
    void testReplaceDocumentAtomically() {
        Set<String> oldChunks = Set.of("doc1:chunk:0", "doc1:chunk:1");
        Set<String> newChunks = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        
        registry.registerDocument("doc1", oldChunks, 5L);
        
        assertEquals(2, registry.getChunksForDocument("doc1").size());
        assertTrue(registry.isVisibleAt("doc1", 5L));

        registry.replaceDocument("doc1", newChunks, 15L);
        
        assertEquals(3, registry.getChunksForDocument("doc1").size());
        assertTrue(registry.isVisibleAt("doc1", 15L));
        assertTrue(registry.isVisibleAt("doc1", 20L));
        assertFalse(registry.isVisibleAt("doc1", 10L));
    }

    @Test
    void testNoPartialDocumentsInResults() {
        Set<String> doc1Chunks = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        Set<String> doc2Chunks = Set.of("doc2:chunk:0", "doc2:chunk:1");
        
        registry.registerDocument("doc1", doc1Chunks, 5L);

        Set<String> allChunks = new HashSet<>();
        allChunks.addAll(doc1Chunks);
        allChunks.addAll(doc2Chunks);

        Set<String> filtered = registry.filterVisibleChunks(allChunks, 10L);

        assertTrue(filtered.containsAll(doc1Chunks));
        assertFalse(filtered.contains("doc2:chunk:0"));
        assertFalse(filtered.contains("doc2:chunk:1"));
    }

    @Test
    void testEpochIsolation() {
        Set<String> doc1Chunks = Set.of("doc1:chunk:0", "doc1:chunk:1");
        Set<String> doc2Chunks = Set.of("doc2:chunk:0", "doc2:chunk:1");
        
        registry.registerDocument("doc1", doc1Chunks, 5L);

        Set<String> chunksAtEpoch5 = registry.filterVisibleChunks(
            new HashSet<>() {{ addAll(doc1Chunks); addAll(doc2Chunks); }},
            5L
        );
        assertEquals(2, chunksAtEpoch5.size());

        registry.registerDocument("doc2", doc2Chunks, 10L);

        Set<String> chunksAtEpoch5After = registry.filterVisibleChunks(
            new HashSet<>() {{ addAll(doc1Chunks); addAll(doc2Chunks); }},
            5L
        );
        assertEquals(2, chunksAtEpoch5After.size());
        assertTrue(chunksAtEpoch5After.containsAll(doc1Chunks));
        assertFalse(chunksAtEpoch5After.contains("doc2:chunk:0"));

        Set<String> chunksAtEpoch10 = registry.filterVisibleChunks(
            new HashSet<>() {{ addAll(doc1Chunks); addAll(doc2Chunks); }},
            10L
        );
        assertEquals(4, chunksAtEpoch10.size());
    }

    @Test
    void testMultipleDocumentsDifferentEpochs() {
        for (int i = 0; i < 10; i++) {
            Set<String> chunks = new HashSet<>();
            for (int j = 0; j <= i; j++) {
                chunks.add("doc" + i + ":" + j);
            }
            registry.registerDocument("doc" + i, chunks, i * 10L);
        }

        for (int epoch = 0; epoch <= 100; epoch += 10) {
            Set<String> allChunks = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                for (int j = 0; j <= i; j++) {
                    allChunks.add("doc" + i + ":" + j);
                }
            }
            
            Set<String> visible = registry.filterVisibleChunks(allChunks, epoch);
            
            for (int i = 0; i < 10; i++) {
                String docId = "doc" + i;
                boolean shouldBeVisible = (i * 10L) <= epoch;
                assertEquals(shouldBeVisible, registry.isVisibleAt(docId, epoch),
                    "Document " + docId + " visibility at epoch " + epoch);
            }
        }
    }

    @Test
    void testDocumentCountTracking() {
        assertEquals(0, registry.getDocumentCount());
        
        registry.registerDocument("doc1", Set.of("doc1:chunk:0"), 1L);
        assertEquals(1, registry.getDocumentCount());
        
        registry.registerDocument("doc2", Set.of("doc2:chunk:0", "doc2:chunk:1"), 2L);
        assertEquals(2, registry.getDocumentCount());
        
        registry.tombstoneDocument("doc1");
        assertEquals(1, registry.getDocumentCount());
    }

    @Test
    void testChunkCountTracking() {
        assertEquals(0, registry.getTotalChunkCount());
        
        registry.registerDocument("doc1", Set.of("doc1:chunk:0", "doc1:chunk:1"), 1L);
        assertEquals(2, registry.getTotalChunkCount());
        
        registry.registerDocument("doc2", Set.of("doc2:chunk:0", "doc2:chunk:1", "doc2:chunk:2"), 2L);
        assertEquals(5, registry.getTotalChunkCount());
        
        registry.tombstoneDocument("doc1");
        assertEquals(3, registry.getTotalChunkCount());
    }

    @Test
    void testStatistics() {
        registry.registerDocument("doc1", Set.of("doc1:chunk:0", "doc1:chunk:1"), 1L);
        registry.registerDocument("doc2", Set.of("doc2:chunk:0", "doc2:chunk:1", "doc2:chunk:2"), 2L);
        registry.registerDocument("doc3", Set.of("doc3:chunk:0"), 3L);

        DocumentVisibilityRegistry.VisibilityStats stats = registry.getStats();
        
        assertEquals(3, stats.documentCount());
        assertEquals(6, stats.chunkCount());
        assertEquals(2.0, stats.avgChunksPerDocument(), 0.01);
    }

    @Test
    void testClearRegistry() {
        registry.registerDocument("doc1", Set.of("doc1:chunk:0"), 1L);
        registry.registerDocument("doc2", Set.of("doc2:chunk:0"), 2L);
        
        assertEquals(2, registry.getDocumentCount());
        
        registry.clear();
        
        assertEquals(0, registry.getDocumentCount());
        assertEquals(0, registry.getTotalChunkCount());
        assertFalse(registry.isVisibleAt("doc1", 10L));
        assertFalse(registry.isVisibleAt("doc2", 10L));
    }

    @Test
    void testGetDocumentForChunk() {
        registry.registerDocument("doc1", Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 1L);

        assertEquals("doc1", registry.getDocumentForChunk("doc1:chunk:0"));
        assertEquals("doc1", registry.getDocumentForChunk("doc1:chunk:1"));
        assertEquals("doc1", registry.getDocumentForChunk("doc1:chunk:2"));
        assertNull(registry.getDocumentForChunk("doc2:chunk:0"));
    }

    @Test
    void testGetChunksForDocument() {
        Set<String> originalChunks = Set.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2");
        registry.registerDocument("doc1", originalChunks, 1L);

        Set<String> retrieved = registry.getChunksForDocument("doc1");
        
        assertEquals(3, retrieved.size());
        assertTrue(retrieved.containsAll(originalChunks));
        
        Set<String> empty = registry.getChunksForDocument("nonexistent");
        assertTrue(empty.isEmpty());
    }

    @Test
    void testNullDocumentId() {
        assertThrows(NullPointerException.class, 
            () -> registry.registerDocument(null, Set.of("chunk"), 1L));
        assertThrows(NullPointerException.class,
            () -> registry.isVisibleAt(null, 1L));
        assertThrows(NullPointerException.class,
            () -> registry.tombstoneDocument(null));
    }

    @Test
    void testNullChunkIds() {
        assertThrows(NullPointerException.class,
            () -> registry.registerDocument("doc1", (Set<String>) null, 1L));
        assertThrows(NullPointerException.class,
            () -> registry.isChunkVisibleAt(null, 1L));
    }

    @Test
    void testNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerDocument("doc1", Set.of("chunk"), -1L));
    }

    @Test
    void testTrackerWithRegistry() {
        tracker.registerDocument(
            "doc1", List.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 5L);
        
        assertTrue(tracker.isDocumentFullyVisible("doc1", 5L));
        assertTrue(tracker.isDocumentFullyVisible("doc1", 10L));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 4L));
        
        assertTrue(tracker.isChunkVisible("doc1:chunk:0", 5L));
        assertTrue(tracker.isChunkVisible("doc1:chunk:1", 5L));
        assertTrue(tracker.isChunkVisible("doc1:chunk:2", 5L));
    }

    @Test
    void testTrackerFilterPartialDocuments() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1"), 5L);
        
        List<String> chunks = new ArrayList<>();
        chunks.add("doc1:chunk:0");
        chunks.add("doc1:chunk:1");
        chunks.add("doc2:chunk:0");
        
        List<String> filtered = tracker.filterPartialDocuments(chunks, 5L);
        
        assertEquals(2, filtered.size());
        assertTrue(filtered.contains("doc1:chunk:0"));
        assertTrue(filtered.contains("doc1:chunk:1"));
        assertFalse(filtered.contains("doc2:chunk:0"));
    }

    @Test
    void testAllChunksVisibleAtSameEpoch() {
        String docId = "atomicDoc";
        List<String> chunkIds = List.of(docId + ":chunk:0", docId + ":chunk:1", docId + ":chunk:2", docId + ":chunk:3");
        
        long commitEpoch = 10L;
        tracker.registerDocument(docId, chunkIds, commitEpoch);
        
        for (String chunkId : chunkIds) {
            assertFalse(tracker.isChunkVisible(chunkId, commitEpoch - 1),
                "Chunk " + chunkId + " should not be visible before commit epoch");
            assertTrue(tracker.isChunkVisible(chunkId, commitEpoch),
                "Chunk " + chunkId + " should be visible at commit epoch");
        }
        
        List<String> allChunks = new ArrayList<>(chunkIds);
        List<String> visibleBeforeCommit = tracker.filterPartialDocuments(allChunks, commitEpoch - 1);
        assertEquals(0, visibleBeforeCommit.size(), "No chunks should be visible before commit");
        
        List<String> visibleAtCommit = tracker.filterPartialDocuments(allChunks, commitEpoch);
        assertEquals(4, visibleAtCommit.size(), "All chunks should be visible at commit");
    }
}
