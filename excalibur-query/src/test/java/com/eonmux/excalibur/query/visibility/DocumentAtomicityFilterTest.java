package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.QueryResultItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DocumentAtomicityFilter.
 */
class DocumentAtomicityFilterTest {

    private TestVisibilityChecker testChecker;
    private DocumentAtomicityFilter filter;

    @BeforeEach
    void setUp() {
        testChecker = new TestVisibilityChecker();
        filter = new DocumentAtomicityFilter(testChecker);
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should filter partial documents")
    void testFilterPartialDocuments() {
        testChecker.setVisible("1", true);
        testChecker.setVisible("2", false);

        List<QueryResultItem> results = List.of(
            createResult(1L, 0.1),
            createResult(2L, 0.2)
        );

        List<QueryResultItem> filtered = filter.filterPartialDocuments(results, 1L);

        assertEquals(1, filtered.size());
        assertEquals(1L, filtered.get(0).documentId());
    }

    @Test
    @DisplayName("DocumentAtomicityFilter passes through results with no document identity")
    void testResultsWithoutDocumentIdPassThrough() {
        testChecker.setVisible("1", true);

        // No string document ID: the atomicity model has no opinion on this result.
        // Synthesising one from the numeric node ID would guarantee a lookup miss and
        // silently drop a legitimate match.
        QueryResultItem noIdentity = new QueryResultItem(7L, 0.5f);

        assertTrue(filter.isResultVisible(noIdentity, 1L));
        assertEquals(1, filter.filterPartialDocuments(List.of(noIdentity), 1L).size());
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should return empty list for empty input")
    void testFilterEmptyList() {
        List<QueryResultItem> filtered = filter.filterPartialDocuments(Collections.emptyList(), 1L);
        assertTrue(filtered.isEmpty());
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should reject null results")
    void testFilterNullResults() {
        assertThrows(NullPointerException.class, () ->
            filter.filterPartialDocuments(null, 1L));
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should reject negative epoch")
    void testFilterNegativeEpoch() {
        List<QueryResultItem> results = List.of(createResult(1L, 0.1));
        assertThrows(IllegalArgumentException.class, () ->
            filter.filterPartialDocuments(results, -1L));
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should filter chunk IDs")
    void testFilterChunkIds() {
        testChecker.setVisible("chunk1", true);
        testChecker.setVisible("chunk2", false);

        List<String> chunkIds = List.of("chunk1", "chunk2");
        List<String> filtered = filter.filterChunkIds(chunkIds, 1L);

        assertEquals(1, filtered.size());
        assertEquals("chunk1", filtered.get(0));
    }

    @Test
    @DisplayName("a result named by its chunk is judged by the document the chunk belongs to")
    void chunkNamedResultsAreJudgedByTheirDocument() {
        // Visibility is published per document; a result names the chunk. Looking the chunk id
        // up as though it were a document would find nothing tracked and wave every chunk of a
        // half-published document through.
        testChecker.setVisible("src:done.md", true);
        testChecker.setVisible("src:half.md", false);

        QueryResultItem ofPublished = new QueryResultItem("src:done.md:chunk:3", 0.9f, null, 0, 1);
        QueryResultItem ofPartial = new QueryResultItem("src:half.md:chunk:0", 0.8f, null, 0, 2);
        QueryResultItem documentNamed = new QueryResultItem("src:done.md", 0.7f, null, 0, 3);

        assertTrue(filter.isResultVisible(ofPublished, 1L));
        assertFalse(filter.isResultVisible(ofPartial, 1L));
        assertTrue(filter.isResultVisible(documentNamed, 1L),
            "a graph an older build attributed by document names the document itself");
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should check result visibility")
    void testIsResultVisible() {
        testChecker.setVisible("1", true);
        testChecker.setVisible("2", false);

        QueryResultItem visibleResult = createResult(1L, 0.1);
        QueryResultItem hiddenResult = createResult(2L, 0.2);

        assertTrue(filter.isResultVisible(visibleResult, 1L));
        assertFalse(filter.isResultVisible(hiddenResult, 1L));
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should verify atomicity")
    void testVerifyAtomicity() {
        testChecker.setVisible("1", true);
        testChecker.setVisible("2", false);
        testChecker.setVisible("3", true);

        List<QueryResultItem> results = List.of(
            createResult(1L, 0.1),
            createResult(2L, 0.2),
            createResult(3L, 0.3)
        );

        DocumentAtomicityFilter.AtomicityVerification verification =
            filter.verifyAtomicity(results, 1L);

        assertFalse(verification.valid());
        assertEquals(3, verification.totalCount());
        assertEquals(1, verification.partialCount());
        assertEquals(2, verification.atomicCount());
        assertEquals(2.0/3.0, verification.atomicRatio(), 0.001);
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should compute stats")
    void testComputeStats() {
        DocumentAtomicityFilter.FilterStats stats = filter.computeStats(100, 80);

        assertEquals(100, stats.resultsBefore());
        assertEquals(80, stats.resultsAfter());
        assertEquals(20, stats.filteredCount());
        assertEquals(0.2, stats.filterRate(), 0.001);
        assertTrue(stats.hadFiltering());
        assertEquals(0.8, stats.retentionRate(), 0.001);
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should compute stats with no filtering")
    void testComputeStatsNoFiltering() {
        DocumentAtomicityFilter.FilterStats stats = filter.computeStats(100, 100);

        assertEquals(0, stats.filteredCount());
        assertFalse(stats.hadFiltering());
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should validate stats parameters")
    void testComputeStatsValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            filter.computeStats(-1, 0));

        assertThrows(IllegalArgumentException.class, () ->
            filter.computeStats(0, -1));

        assertThrows(IllegalArgumentException.class, () ->
            filter.computeStats(50, 100)); // after > before
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should return visibility checker")
    void testGetVisibilityChecker() {
        assertNotNull(filter.getVisibilityChecker());
        assertSame(testChecker, filter.getVisibilityChecker());
    }

    @Test
    @DisplayName("AtomicityVerification should validate construction")
    void testAtomicityVerificationValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            new DocumentAtomicityFilter.AtomicityVerification(true, -1, 0, 1L));

        assertThrows(IllegalArgumentException.class, () ->
            new DocumentAtomicityFilter.AtomicityVerification(true, 0, -1, 1L));

        assertThrows(IllegalArgumentException.class, () ->
            new DocumentAtomicityFilter.AtomicityVerification(true, 0, 0, -1L));

        assertThrows(IllegalArgumentException.class, () ->
            new DocumentAtomicityFilter.AtomicityVerification(true, 5, 10, 1L)); // partial > total
    }

    @Test
    @DisplayName("AtomicityVerification should have correct toString")
    void testAtomicityVerificationToString() {
        DocumentAtomicityFilter.AtomicityVerification verification =
            new DocumentAtomicityFilter.AtomicityVerification(true, 10, 2, 5L);

        String str = verification.toString();
        assertNotNull(str);
        assertTrue(str.contains("valid=true"));
        assertTrue(str.contains("total=10"));
        assertTrue(str.contains("partial=2"));
    }

    @Test
    @DisplayName("FilterStats should have correct toString")
    void testFilterStatsToString() {
        DocumentAtomicityFilter.FilterStats stats =
            new DocumentAtomicityFilter.FilterStats(100, 80, 20, 0.2, true);

        String str = stats.toString();
        assertNotNull(str);
        assertTrue(str.contains("before=100"));
        assertTrue(str.contains("after=80"));
    }

    @Test
    @DisplayName("DocumentAtomicityFilter should reject null checker")
    void testNullChecker() {
        assertThrows(NullPointerException.class, () ->
            new DocumentAtomicityFilter((DocumentVisibilityChecker) null));
    }

    /**
     * Builds a result that carries an explicit string document identity, which is what
     * the filter looks up. The numeric ID is the HNSW node ID and lives in a different
     * namespace, so it is never used as a document identity.
     */
    private QueryResultItem createResult(long documentId, double score) {
        return new QueryResultItem(
            documentId, String.valueOf(documentId), (float) score, null, 0L, documentId);
    }

    /**
     * Test implementation of DocumentVisibilityChecker.
     */
    private static class TestVisibilityChecker implements DocumentVisibilityChecker {
        @Override
        public long currentEpoch() {
            // A test double with no epoch of its own. Unbounded keeps these tests asserting the
            // completeness behaviour they were written for, rather than an epoch bound.
            return Long.MAX_VALUE;
        }

        private java.util.Map<String, Boolean> visibility = new java.util.HashMap<>();

        void setVisible(String documentId, boolean visible) {
            visibility.put(documentId, visible);
        }

        @Override
        public boolean isDocumentVisible(String documentId, long queryEpoch) {
            return visibility.getOrDefault(documentId, false);
        }

        @Override
        public boolean isChunkVisible(String chunkId, long queryEpoch) {
            return visibility.getOrDefault(chunkId, false);
        }

        @Override
        public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
            return chunkIds.stream()
                .filter(id -> visibility.getOrDefault(id, false))
                .toList();
        }

        @Override
        public Set<String> getVisibleDocuments(long queryEpoch) {
            return visibility.entrySet().stream()
                .filter(e -> e.getValue())
                .map(e -> e.getKey())
                .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public long getCommitEpoch(String documentId) {
            return 1L;
        }

        @Override
        public int getChunkCount(String documentId) {
            return 1;
        }

        @Override
        public int getDocumentCount() {
            return visibility.size();
        }
    }
}
