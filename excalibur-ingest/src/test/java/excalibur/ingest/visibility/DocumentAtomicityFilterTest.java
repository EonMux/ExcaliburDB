package excalibur.ingest.visibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DocumentVisibilityChecker interface and implementations.
 *
 * <p>Tests verify that the document-atomic visibility guarantee is enforced:
 * query results never contain chunks from documents that are not fully visible.</p>
 *
 * <p>Note: Tests for DocumentAtomicityFilter with QueryResultItem are in
 * the query module to avoid circular dependencies.</p>
 */
class DocumentAtomicityFilterTest {

    private DocumentVisibilityTracker tracker;
    private DocumentVisibilityChecker checker;

    @BeforeEach
    void setUp() {
        tracker = new DocumentVisibilityTracker();
        checker = new TrackerAdapter(tracker);
    }

    @Test
    void testIsDocumentVisible_nullDocumentId() {
        assertThrows(NullPointerException.class, () -> checker.isDocumentVisible(null, 0L));
    }

    @Test
    void testIsDocumentVisible_visibleDocument() {
        tracker.registerAtomicDocument(
            createBatch("1", 0L, 100L, "1:0"),
            5L
        );

        assertTrue(checker.isDocumentVisible("1", 10L));
    }

    @Test
    void testIsDocumentVisible_notYetVisible() {
        tracker.registerAtomicDocument(
            createBatch("1", 0L, 100L, "1:0"),
            10L
        );

        assertFalse(checker.isDocumentVisible("1", 5L));
    }

    @Test
    void testIsDocumentVisible_unregisteredDocument() {
        assertFalse(checker.isDocumentVisible("999", 10L));
    }

    @Test
    void testIsChunkVisible_filtersByVisibility() {
        tracker.registerAtomicDocument(
            createBatch("doc1", 0L, 100L, "doc1:0", "doc1:1", "doc1:2"),
            5L
        );

        assertTrue(checker.isChunkVisible("doc1:0", 10L));
        assertTrue(checker.isChunkVisible("doc1:1", 10L));
        assertTrue(checker.isChunkVisible("doc1:2", 10L));
        assertFalse(checker.isChunkVisible("doc2:0", 10L));
    }

    @Test
    void testFilterPartialDocuments_filtersByVisibility() {
        tracker.registerAtomicDocument(
            createBatch("doc1", 0L, 100L, "doc1:0", "doc1:1", "doc1:2"),
            5L
        );

        List<String> chunks = Arrays.asList("doc1:0", "doc1:1", "doc1:2", "doc2:0");

        List<String> filtered = checker.filterPartialDocuments(chunks, 10L);

        assertEquals(3, filtered.size());
        assertTrue(filtered.contains("doc1:0"));
        assertTrue(filtered.contains("doc1:1"));
        assertTrue(filtered.contains("doc1:2"));
        assertFalse(filtered.contains("doc2:0"));
    }

    @Test
    void testGetVisibleDocuments() {
        tracker.registerAtomicDocument(createBatch("1", 0L, 100L, "1:0"), 5L);
        tracker.registerAtomicDocument(createBatch("2", 100L, 200L, "2:0"), 10L);
        tracker.registerAtomicDocument(createBatch("3", 200L, 300L, "3:0"), 15L);

        assertEquals(1, checker.getVisibleDocuments(5L).size());
        assertEquals(2, checker.getVisibleDocuments(10L).size());
        assertEquals(3, checker.getVisibleDocuments(20L).size());
    }

    @Test
    void testGetCommitEpoch() {
        tracker.registerAtomicDocument(createBatch("1", 0L, 100L, "1:0"), 10L);

        assertEquals(10L, checker.getCommitEpoch("1"));
        assertEquals(-1L, checker.getCommitEpoch("999"));
    }

    @Test
    void testGetChunkCount() {
        tracker.registerAtomicDocument(createBatch("1", 0L, 100L, "1:0", "1:1"), 5L);

        assertEquals(2, checker.getChunkCount("1"));
        assertEquals(0, checker.getChunkCount("999"));
    }

    @Test
    void testGetDocumentCount() {
        assertEquals(0, checker.getDocumentCount());
        
        tracker.registerAtomicDocument(createBatch("1", 0L, 100L, "1:0"), 5L);
        assertEquals(1, checker.getDocumentCount());
        
        tracker.registerAtomicDocument(createBatch("2", 100L, 200L, "2:0"), 10L);
        assertEquals(2, checker.getDocumentCount());
    }

    @Test
    void testEpochIsolation() {
        tracker.registerAtomicDocument(createBatch("1", 0L, 100L, "1:0"), 5L);
        tracker.registerAtomicDocument(createBatch("2", 100L, 200L, "2:0"), 10L);

        List<String> chunks = Arrays.asList("1:0", "2:0");

        List<String> atEpoch5 = checker.filterPartialDocuments(chunks, 5L);
        assertEquals(1, atEpoch5.size());
        assertTrue(atEpoch5.contains("1:0"));

        List<String> atEpoch10 = checker.filterPartialDocuments(chunks, 10L);
        assertEquals(2, atEpoch10.size());
    }

    @Test
    void testNoPartialDocumentsVisible() {
        tracker.registerAtomicDocument(
            createBatch("1", 0L, 100L, "1:0", "1:1", "1:2"),
            5L
        );

        long commitEpoch = checker.getCommitEpoch("1");

        List<String> chunks = Arrays.asList("1:0", "1:1", "1:2");

        List<String> atCommit = checker.filterPartialDocuments(chunks, commitEpoch);
        assertEquals(3, atCommit.size());

        List<String> beforeCommit = checker.filterPartialDocuments(chunks, commitEpoch - 1);
        assertEquals(0, beforeCommit.size());
    }

    private excalibur.ingest.buffer.AtomicDocumentBatch createBatch(
            String documentId, long startOffset, long endOffset, String... chunkIds) {
        return new excalibur.ingest.buffer.AtomicDocumentBatch(
            documentId,
            startOffset,
            endOffset,
            Arrays.asList(chunkIds),
            Collections.nCopies(chunkIds.length, 1L)
        );
    }

    /**
     * Adapter to use DocumentVisibilityTracker as DocumentVisibilityChecker.
     */
    private static class TrackerAdapter implements DocumentVisibilityChecker {
        private final DocumentVisibilityTracker tracker;

        TrackerAdapter(DocumentVisibilityTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public boolean isDocumentVisible(String documentId, long queryEpoch) {
            return tracker.isDocumentFullyVisible(documentId, queryEpoch);
        }

        @Override
        public boolean isChunkVisible(String chunkId, long queryEpoch) {
            return tracker.isChunkVisible(chunkId, queryEpoch);
        }

        @Override
        public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
            return tracker.filterPartialDocuments(chunkIds, queryEpoch);
        }

        @Override
        public java.util.Set<String> getVisibleDocuments(long queryEpoch) {
            return tracker.getVisibleDocuments(queryEpoch);
        }

        @Override
        public long getCommitEpoch(String documentId) {
            return tracker.getCommitEpoch(documentId);
        }

        @Override
        public int getChunkCount(String documentId) {
            return tracker.getChunkCount(documentId);
        }

        @Override
        public int getDocumentCount() {
            return tracker.getDocumentCount();
        }
    }
}
