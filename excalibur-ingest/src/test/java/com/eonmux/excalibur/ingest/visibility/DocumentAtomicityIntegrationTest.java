package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the document atomicity guarantee as the
 * {@link DocumentVisibilityTracker} provides it.
 *
 * <p>These tests verify that the document-atomic visibility contract
 * is maintained: queries never see partially-ingested documents.</p>
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>Basic atomicity - single document with multiple chunks</li>
 *   <li>Epoch isolation - queries see consistent snapshots</li>
 *   <li>Partial filtering - incomplete documents are filtered out</li>
 * </ul>
 *
 * <p>Note: Tests for DocumentAtomicityFilter with QueryResultItem are in
 * the query module to avoid circular dependencies.</p>
 */
class DocumentAtomicityIntegrationTest {

    private DocumentVisibilityTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DocumentVisibilityTracker(new DocumentRegistry());
    }

    @Test
    void testDocumentNotVisibleBeforeRegistration() {
        assertFalse(tracker.isDocumentTracked("doc1"));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 10L));
        assertFalse(tracker.isChunkVisible("doc1:chunk:0", 10L));
    }

    @Test
    void testDocumentVisibleFromItsCommitEpoch() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 5L);

        assertTrue(tracker.isDocumentFullyVisible("doc1", 5L));
        assertTrue(tracker.isDocumentFullyVisible("doc1", 10L));
        assertTrue(tracker.isDocumentFullyVisible("doc1", 100L));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 4L));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 0L));
        assertEquals(5L, tracker.getCommitEpoch("doc1"));
        assertEquals(3, tracker.getChunkCount("doc1"));
    }

    @Test
    void testChunkVisibilityFollowsDocumentVisibility() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 10L);

        assertTrue(tracker.isChunkVisible("doc1:chunk:0", 10L));
        assertTrue(tracker.isChunkVisible("doc1:chunk:1", 10L));
        assertTrue(tracker.isChunkVisible("doc1:chunk:2", 10L));
        assertFalse(tracker.isChunkVisible("doc1:chunk:0", 9L));
    }

    @Test
    void testRemovedDocumentIsNoLongerVisible() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1"), 5L);
        assertTrue(tracker.isDocumentFullyVisible("doc1", 10L));

        assertTrue(tracker.removeDocument("doc1"));
        assertFalse(tracker.removeDocument("doc1"));

        assertFalse(tracker.isDocumentFullyVisible("doc1", 10L));
        assertFalse(tracker.isChunkVisible("doc1:chunk:0", 10L));
        assertEquals(-1L, tracker.getCommitEpoch("doc1"));
        assertEquals(0, tracker.getChunkCount("doc1"));
    }

    @Test
    void testReregistrationReplacesTheDocumentAtItsNewEpoch() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1"), 5L);
        assertEquals(2, tracker.getChunkCount("doc1"));

        tracker.registerDocument("doc1",
            List.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 15L);

        assertEquals(3, tracker.getChunkCount("doc1"));
        assertEquals(1, tracker.getDocumentCount());
        assertTrue(tracker.isDocumentFullyVisible("doc1", 15L));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 10L));
    }

    @Test
    void testEpochIsolation() {
        List<String> doc1Chunks = List.of("doc1:chunk:0", "doc1:chunk:1");
        List<String> doc2Chunks = List.of("doc2:chunk:0", "doc2:chunk:1");
        List<String> allChunks = new ArrayList<>(doc1Chunks);
        allChunks.addAll(doc2Chunks);

        tracker.registerDocument("doc1", doc1Chunks, 5L);
        assertEquals(doc1Chunks, tracker.filterPartialDocuments(allChunks, 5L));

        tracker.registerDocument("doc2", doc2Chunks, 10L);

        assertEquals(doc1Chunks, tracker.filterPartialDocuments(allChunks, 5L),
            "a query at epoch 5 must not start seeing a document committed at 10");
        assertEquals(allChunks, tracker.filterPartialDocuments(allChunks, 10L));
    }

    @Test
    void testMultipleDocumentsDifferentEpochs() {
        for (int i = 0; i < 10; i++) {
            List<String> chunks = new ArrayList<>();
            for (int j = 0; j <= i; j++) {
                chunks.add("doc" + i + ":chunk:" + j);
            }
            tracker.registerDocument("doc" + i, chunks, i * 10L);
        }

        for (int epoch = 0; epoch <= 100; epoch += 10) {
            for (int i = 0; i < 10; i++) {
                String docId = "doc" + i;
                boolean shouldBeVisible = (i * 10L) <= epoch;
                assertEquals(shouldBeVisible, tracker.isDocumentFullyVisible(docId, epoch),
                    "Document " + docId + " visibility at epoch " + epoch);
            }
            assertEquals(Math.min(10, epoch / 10 + 1), tracker.getVisibleDocuments(epoch).size());
        }
    }

    @Test
    void testPublishedDocumentBeingRewrittenIsHiddenUntilItsNewVersionIsPublished() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1"), 1L);

        // A new version overwrites chunk ids in place, so while it is being written storage holds
        // part of each version: the committed entry describes chunks that are no longer there.
        tracker.beginIngestion("doc1", 3);

        assertTrue(tracker.isDocumentTracked("doc1"));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 10L));
        assertFalse(tracker.isChunkVisible("doc1:chunk:0", 10L));
        assertTrue(tracker.filterPartialDocuments(List.of("doc1:chunk:0"), 10L).isEmpty());
        assertFalse(tracker.getVisibleDocuments(10L).contains("doc1"));

        tracker.registerDocument("doc1", List.of("doc1:chunk:0", "doc1:chunk:1", "doc1:chunk:2"), 5L);

        assertTrue(tracker.isDocumentFullyVisible("doc1", 5L));
        assertFalse(tracker.isDocumentFullyVisible("doc1", 4L));
        assertTrue(tracker.getVisibleDocuments(5L).contains("doc1"));
    }

    @Test
    void testDocumentCountAndStatistics() {
        assertEquals(0, tracker.getDocumentCount());

        tracker.registerDocument("doc1", List.of("doc1:chunk:0"), 1L);
        tracker.registerDocument("doc2", List.of("doc2:chunk:0", "doc2:chunk:1"), 2L);
        tracker.beginIngestion("doc3", 1);

        DocumentVisibilityTracker.VisibilityStats stats = tracker.getStats();
        assertEquals(2, stats.documentCount());
        assertEquals(1, stats.pendingCount());
        assertEquals(tracker.currentEpoch(), stats.currentEpoch());

        tracker.removeDocument("doc1");
        assertEquals(1, tracker.getDocumentCount());
    }

    @Test
    void testClear() {
        tracker.registerDocument("doc1", List.of("doc1:chunk:0"), 1L);
        tracker.registerDocument("doc2", List.of("doc2:chunk:0"), 2L);
        tracker.beginIngestion("doc3", 1);

        tracker.clear();

        assertEquals(0, tracker.getDocumentCount());
        assertEquals(0, tracker.getPendingDocumentCount());
        assertFalse(tracker.isDocumentFullyVisible("doc1", 10L));
        assertFalse(tracker.isDocumentFullyVisible("doc2", 10L));
    }

    @Test
    void testNullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> tracker.registerDocument(null, List.of("chunk"), 1L));
        assertThrows(NullPointerException.class,
            () -> tracker.registerDocument("doc1", null, 1L));
        assertThrows(NullPointerException.class,
            () -> tracker.isDocumentFullyVisible(null, 1L));
        assertThrows(NullPointerException.class,
            () -> tracker.isChunkVisible(null, 1L));
        assertThrows(NullPointerException.class,
            () -> tracker.removeDocument(null));
    }

    @Test
    void testNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
            () -> tracker.registerDocument("doc1", List.of("chunk"), -1L));
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
