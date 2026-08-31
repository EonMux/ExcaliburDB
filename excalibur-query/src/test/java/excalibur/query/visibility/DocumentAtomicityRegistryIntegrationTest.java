package excalibur.query.visibility;

import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.ingest.visibility.DocumentVisibilityRegistry;
import excalibur.query.QueryResultItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for document atomicity using DocumentVisibilityRegistry.
 *
 * <p>Tests verify that the document-atomic visibility guarantee is enforced
 * when using DocumentVisibilityRegistry with DocumentAtomicityFilter.</p>
 *
 * <h2>Document Atomicity Guarantee</h2>
 * <p>A document is considered "atomically visible" when:</p>
 * <ul>
 *   <li>All its chunks are registered together</li>
 *   <li>It becomes visible at a specific epoch</li>
 *   <li>Queries at epoch E see either ALL chunks or NONE (never partial)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
class DocumentAtomicityRegistryIntegrationTest {

    private DocumentVisibilityRegistry registry;
    private DocumentAtomicityFilter filter;

    @BeforeEach
    void setUp() {
        registry = new DocumentVisibilityRegistry();
        filter = new DocumentAtomicityFilter((DocumentVisibilityChecker) registry);
    }

    @Test
    void testConstructorWithRegistry() {
        DocumentVisibilityChecker checker = registry;
        DocumentAtomicityFilter filterFromChecker = new DocumentAtomicityFilter(checker);
        assertNotNull(filterFromChecker);
        assertSame(checker, filterFromChecker.getVisibilityChecker());
    }

    @Test
    void testFilterPartialDocuments_emptyResults() {
        List<QueryResultItem> filtered = filter.filterPartialDocuments(Collections.emptyList(), 10L);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void testFilterPartialDocuments_unknownDocumentsPassThrough() {
        QueryResultItem r1 = createResult(1L, 0.5f);
        QueryResultItem r2 = createResult(2L, 0.4f);

        List<QueryResultItem> filtered = filter.filterPartialDocuments(Arrays.asList(r1, r2), 10L);

        assertEquals(2, filtered.size(),
            "A document this registry has never seen is not the same as one it knows to be "
                + "incomplete. The registry is built in memory as documents are published, so "
                + "after a restart everything written earlier is unknown -- excluding those "
                + "would drop them from every result once one new document arrives.");
    }

    @Test
    void testFilterPartialDocuments_tombstonedIsExcludedNotForgotten() {
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.tombstoneDocument("1");

        List<QueryResultItem> filtered =
            filter.filterPartialDocuments(List.of(createResult(1L, 0.5f)), 10L);

        assertTrue(filtered.isEmpty(),
            "A deleted document is known and deliberately invisible; forgetting it entirely "
                + "would make it indistinguishable from an unknown one and bring it back");
    }

    @Test
    void testFilterPartialDocuments_resultWithoutDocumentIdPassesThrough() {
        registry.registerDocument("1", List.of("1:0"), 5L);

        // A result with no document identity - a write-log tail-scan hit whose key could
        // not be decoded, for instance - is not something the atomicity model can classify.
        // Dropping it would be a false negative rather than a guarantee.
        QueryResultItem noIdentity = new QueryResultItem(99L, 0.9f, null, -1L, 99L);

        List<QueryResultItem> filtered = filter.filterPartialDocuments(List.of(noIdentity), 10L);

        assertEquals(1, filtered.size());
    }

    @Test
    void testFilterPartialDocuments_allVisible() {
        registry.registerDocument("1", List.of("1:0", "1:1"), 5L);
        registry.registerDocument("2", List.of("2:0", "2:1"), 5L);

        QueryResultItem r1 = createResult(1L, 0.5f);
        QueryResultItem r2 = createResult(2L, 0.4f);

        List<QueryResultItem> filtered = filter.filterPartialDocuments(Arrays.asList(r1, r2), 10L);

        assertEquals(2, filtered.size());
    }

    @Test
    void testFilterPartialDocuments_mixedVisibility() {
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.registerDocument("2", List.of("2:0"), 15L);

        QueryResultItem visible = createResult(1L, 0.5f);
        QueryResultItem notYetVisible = createResult(2L, 0.4f);

        List<QueryResultItem> filtered = filter.filterPartialDocuments(
            Arrays.asList(visible, notYetVisible), 10L);

        assertEquals(1, filtered.size());
        assertEquals(1L, filtered.get(0).documentId());
    }

    @Test
    void testFilterPartialDocuments_epochBasedVisibility() {
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.registerDocument("2", List.of("2:0"), 10L);
        registry.registerDocument("3", List.of("3:0"), 15L);

        QueryResultItem r1 = createResult(1L, 0.5f);
        QueryResultItem r2 = createResult(2L, 0.4f);
        QueryResultItem r3 = createResult(3L, 0.3f);

        List<QueryResultItem> all = Arrays.asList(r1, r2, r3);

        List<QueryResultItem> atEpoch5 = filter.filterPartialDocuments(all, 5L);
        assertEquals(1, atEpoch5.size());
        assertEquals(1L, atEpoch5.get(0).documentId());

        List<QueryResultItem> atEpoch10 = filter.filterPartialDocuments(all, 10L);
        assertEquals(2, atEpoch10.size());

        List<QueryResultItem> atEpoch20 = filter.filterPartialDocuments(all, 20L);
        assertEquals(3, atEpoch20.size());
    }

    @Test
    void testFilterPartialDocuments_tombstonedDocumentNotVisible() {
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.registerDocument("2", List.of("2:0"), 5L);

        registry.tombstoneDocument("2");

        QueryResultItem r1 = createResult(1L, 0.5f);
        QueryResultItem r2 = createResult(2L, 0.4f);

        List<QueryResultItem> filtered = filter.filterPartialDocuments(Arrays.asList(r1, r2), 10L);

        assertEquals(1, filtered.size());
        assertEquals(1L, filtered.get(0).documentId());
    }

    @Test
    void testVerifyAtomicity() {
        // Document 2 is registered but not yet visible at the query epoch, which is what the
        // verification is meant to count. An unregistered document would not be: the registry
        // has no opinion about it.
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.registerDocument("2", List.of("2:0"), 15L);

        QueryResultItem visible = createResult(1L, 0.5f);
        QueryResultItem partial = createResult(2L, 0.4f);

        DocumentAtomicityFilter.AtomicityVerification verification =
            filter.verifyAtomicity(Arrays.asList(visible, partial), 10L);

        assertFalse(verification.valid());
        assertEquals(2, verification.totalCount());
        assertEquals(1, verification.partialCount());
        assertEquals(1, verification.atomicCount());
        assertEquals(0.5, verification.atomicRatio(), 0.01);
    }

    @Test
    void testIsResultVisible() {
        registry.registerDocument("1", List.of("1:0"), 5L);
        registry.registerDocument("2", List.of("2:0"), 15L);

        QueryResultItem visible = createResult(1L, 0.5f);
        QueryResultItem notYetVisible = createResult(2L, 0.4f);
        QueryResultItem unknown = createResult(3L, 0.3f);

        assertTrue(filter.isResultVisible(visible, 10L));
        assertFalse(filter.isResultVisible(notYetVisible, 10L), "registered, epoch not reached");
        assertFalse(filter.isResultVisible(visible, 3L), "query started before the commit");
        assertTrue(filter.isResultVisible(unknown, 10L), "the registry has no opinion about it");
    }

    @Test
    void testFilterChunkIds() {
        registry.registerDocument("doc1", List.of("doc1:0", "doc1:1", "doc1:2"), 5L);

        List<String> chunks = Arrays.asList("doc1:0", "doc1:1", "doc1:2", "doc2:0");

        List<String> filtered = filter.filterChunkIds(chunks, 10L);

        assertEquals(3, filtered.size());
        assertTrue(filtered.contains("doc1:0"));
        assertTrue(filtered.contains("doc1:1"));
        assertTrue(filtered.contains("doc1:2"));
        assertFalse(filtered.contains("doc2:0"));
    }

    @Test
    void testComputeStats() {
        DocumentAtomicityFilter.FilterStats stats = filter.computeStats(100, 75);

        assertEquals(100, stats.resultsBefore());
        assertEquals(75, stats.resultsAfter());
        assertEquals(25, stats.filteredCount());
        assertEquals(0.25, stats.filterRate(), 0.01);
        assertTrue(stats.hadFiltering());
    }

    @Test
    void testGetVisibilityChecker() {
        DocumentVisibilityChecker checker = filter.getVisibilityChecker();
        assertNotNull(checker);
        assertSame(registry, checker);
    }

    @Test
    void testNullArguments() {
        assertThrows(NullPointerException.class, () -> filter.filterPartialDocuments(null, 10L));
        assertThrows(NullPointerException.class, () -> filter.isResultVisible(null, 10L));
        assertThrows(IllegalArgumentException.class, () -> filter.filterPartialDocuments(List.of(), -1L));
    }

    /**
     * Builds a result carrying an explicit string document identity - the only identity
     * the filter consults. The numeric field is the HNSW node ID, which lives in a
     * different namespace and is never treated as a document ID.
     */
    private QueryResultItem createResult(long documentId, float score) {
        return new QueryResultItem(
            documentId, String.valueOf(documentId), score, null, -1L, documentId);
    }
}
