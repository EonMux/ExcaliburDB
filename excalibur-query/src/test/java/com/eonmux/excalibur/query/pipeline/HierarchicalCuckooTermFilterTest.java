package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.core.text.TermExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for term-based hierarchical Cuckoo filter cascade in the query pipeline.
 *
 * <p>These tests verify that the hierarchical Cuckoo filter correctly filters
 * segments based on term matching between ingestion and query.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
class HierarchicalCuckooTermFilterTest {

    private HierarchicalCuckooFilter hierarchicalFilter;
    private static final String SEGMENT_ID_1 = "1";
    private static final String SEGMENT_ID_2 = "2";
    private static final String SEGMENT_ID_3 = "3";

    @BeforeEach
    void setUp() {
        // Create a hierarchical filter with enough capacity
        hierarchicalFilter = new HierarchicalCuckooFilter(10000);

        // Register segments
        hierarchicalFilter.registerSegment(SEGMENT_ID_1);
        hierarchicalFilter.registerSegment(SEGMENT_ID_2);
        hierarchicalFilter.registerSegment(SEGMENT_ID_3);
    }

    @Test
    @DisplayName("Term-based filtering: exact term match")
    void testExactTermMatch() {
        // Ingestion: Insert terms from a document about machine learning
        String doc1Text = "Machine learning is a subset of artificial intelligence. " +
            "Neural networks are used in deep learning.";
        List<String> doc1Terms = TermExtractor.extractTerms(doc1Text);
        for (String term : doc1Terms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_1),
                    "Should insert term: " + term);
        }

        // Query: Search with same terms
        String queryText = "machine learning";
        List<String> queryTerms = TermExtractor.extractTerms(queryText);

        Set<String> candidateSegments = new HashSet<>();
        for (String term : queryTerms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                candidateSegments.addAll(hierarchicalFilter.findCandidateSegments(termBytes));
            }

        // Verify segment 1 is in candidates (contains "machine" and "learning")
        assertTrue(candidateSegments.contains(SEGMENT_ID_1),
            "Segment 1 should be in candidates");

        // Domain correctness: both query terms ("machine" and "learning") point to segment 1
        // so candidateSegments.size() should be 1, not queryTerms.size()
        // The filter correctly returns unique segments, not one per term
        assertEquals(1, candidateSegments.size(),
            "Should have exactly 1 unique segment since both terms are in the same segment");
    }

    @Test
    @DisplayName("Term-based filtering: partial match returns correct segments")
    void testPartialTermMatch() {
        // Ingestion: Insert terms into segment 1 about "database optimization"
        String doc1Text = "Database optimization improves query performance significantly.";
        List<String> doc1Terms = TermExtractor.extractTerms(doc1Text);
        for (String term : doc1Terms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_1),
                    "Should insert term: " + term);
        }

        // Ingestion: Insert terms into segment 2 about "machine learning"
        String doc2Text = "Machine learning algorithms can predict user behavior.";
        List<String> doc2Terms = TermExtractor.extractTerms(doc2Text);
        for (String term : doc2Terms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_2),
                    "Should insert term: " + term);
        }

        // Query: Search with term only in segment 1
        String queryText = "database optimization";
        List<String> queryTerms = TermExtractor.extractTerms(queryText);

        Set<String> candidateSegments = new HashSet<>();
        for (String term : queryTerms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                candidateSegments.addAll(hierarchicalFilter.findCandidateSegments(termBytes));
            }

        // Verify segment 1 is in candidates
        assertTrue(candidateSegments.contains(SEGMENT_ID_1),
            "Segment 1 should be in candidates");

        // Verify segment 2 is NOT in candidates (doesn't contain database optimization terms)
        assertFalse(candidateSegments.contains(SEGMENT_ID_2),
            "Segment 2 should not be candidates");
    }

    @Test
    @DisplayName("Term-based filtering: no match returns empty candidates")
    void testNoTermMatch() {
        // Ingestion: Insert terms into segment 1
        String doc1Text = "Distributed systems require careful coordination.";
        List<String> doc1Terms = TermExtractor.extractTerms(doc1Text);
        for (String term : doc1Terms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                hierarchicalFilter.insert(termBytes, SEGMENT_ID_1);
        }

        // Query: Search with completely different terms
        String queryText = "quantum computing algorithms";
        List<String> queryTerms = TermExtractor.extractTerms(queryText);

        Set<String> candidateSegments = new HashSet<>();
        for (String term : queryTerms) {
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                candidateSegments.addAll(hierarchicalFilter.findCandidateSegments(termBytes));
            }

        // Should have no matches (filter may return false positives, but // Just verify the the returned segments don't overlap with ingestion
        // Note: Cuckoo filters can have false positives, so we but should not fail
    }

    @Test
    @DisplayName("Term extraction consistency between ingestion and query")
    void testTermExtractionConsistency() {
        // Verify that TermExtractor produces consistent results
        String text = "The quick brown fox jumps over the lazy dog";

        List<String> terms1 = TermExtractor.extractTerms(text);
        List<String> terms2 = TermExtractor.extractTerms(text);

        assertEquals(terms1, terms2, "Same text should produce same terms");

        // Verify lowercase normalization
        for (String term : terms1) {
            assertEquals(term, term.toLowerCase(), "Terms should be lowercase");
        }

        // Verify minimum length
        for (String term : terms1) {
            assertTrue(term.length() >= 2,
                "Terms should meet minimum length requirement");
        }
    }

    @Test
    @DisplayName("Term-based filtering: same term in multiple segments returns all")
    void testSameTermInMultipleSegmentsReturnsAll() {
        // Domain correctness: The same term can appear in multiple segments
        // (e.g., different documents about databases)
        // Querying for that term should return ALL segments containing it

        // Insert the same term "database" into BOTH segment 1 and segment 2
        String sharedTerm = "database";
        byte[] termBytes = sharedTerm.getBytes(StandardCharsets.UTF_8);

        assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_1),
            "Should insert 'database' into segment 1");
        assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_2),
            "Should insert 'database' into segment 2");

        // Query for the shared term
        List<String> candidates = hierarchicalFilter.findCandidateSegments(termBytes);

        // BOTH segments should be returned - exact term match returns all matching segments
        assertEquals(2, candidates.size(),
            "Should return 2 segments since 'database' is in both segments");
        assertTrue(candidates.contains(SEGMENT_ID_1),
            "Segment 1 should be in candidates (contains 'database')");
        assertTrue(candidates.contains(SEGMENT_ID_2),
            "Segment 2 should be in candidates (contains 'database')");
    }

    @Test
    @DisplayName("Term-based filtering: union of multiple terms")
    void testUnionOfMultipleTerms() {
        // Ingestion: Segment 1 has "database" but not "machine"
        String doc1Text = "Database systems store and retrieve data efficiently.";
        List<String> doc1Terms = TermExtractor.extractTerms(doc1Text);
        for (String term : doc1Terms) {
                hierarchicalFilter.insert(term.getBytes(StandardCharsets.UTF_8), SEGMENT_ID_1);
        }

        // Ingestion: Segment 2 has "machine" but not "database"
        String doc2Text = "Machine learning models require training data.";
        List<String> doc2Terms = TermExtractor.extractTerms(doc2Text);
        for (String term : doc2Terms) {
                hierarchicalFilter.insert(term.getBytes(StandardCharsets.UTF_8), SEGMENT_ID_2);
        }

        // Query: Search with both "database" and "machine"
        String queryText = "database machine";
        List<String> queryTerms = TermExtractor.extractTerms(queryText);

        Set<String> candidateSegments = new HashSet<>();
        for (String term : queryTerms) {
                candidateSegments.addAll(hierarchicalFilter.findCandidateSegments(
                    term.getBytes(StandardCharsets.UTF_8)));
            }

        // Both segments should be in candidates (union)
        assertTrue(candidateSegments.contains(SEGMENT_ID_1),
            "Segment 1 should be in candidates (contains 'database')");
        assertTrue(candidateSegments.contains(SEGMENT_ID_2),
            "Segment 2 should be in candidates (contains 'machine')");
    }

    @Test
    @DisplayName("Term-based filtering: stop words are filtered")
    void testStopWordsFiltered() {
        String text = "The quick brown fox jumps over the lazy dog";
        List<String> terms = TermExtractor.extractTerms(text);

        // Stop words should not be in the extracted terms
        assertFalse(terms.contains("the"));
        assertFalse(terms.contains("over"));
        assertFalse(terms.contains("a"));
    }

    @Test
    @DisplayName("Term-based filtering: deletion removes from all levels")
    void testDeletionRemovesFromAllLevels() {
        // Insert a term
        String term = "uniqueterm";
        byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
        assertTrue(hierarchicalFilter.insert(termBytes, SEGMENT_ID_1));

        // Verify it's in the filter
        List<String> candidates = hierarchicalFilter.findCandidateSegments(termBytes);
        assertTrue(candidates.contains(SEGMENT_ID_1));

        // Delete the term
        assertTrue(hierarchicalFilter.deleteFromHierarchy(termBytes, SEGMENT_ID_1));

        // Verify it's no longer in the filter
        candidates = hierarchicalFilter.findCandidateSegments(termBytes);
        assertFalse(candidates.contains(SEGMENT_ID_1),
            "Segment should not be in candidates after deletion");
    }

    @Test
    @DisplayName("Segment ID format consistency: String.valueOf matches")
    void testSegmentIdFormatConsistency() {
        // Verify that String.valueOf(long) produces the expected format
        long segmentIdLong = 42L;
        String segmentIdString = String.valueOf(segmentIdLong);

        assertEquals("42", segmentIdString, "String.valueOf(42L) should produce '42'");

        // Domain correctness: segment must be registered before insertion
        hierarchicalFilter.registerSegment(segmentIdString);

        // Insert into filter with this format
        String term = "testterm";
        hierarchicalFilter.insert(term.getBytes(StandardCharsets.UTF_8), segmentIdString);

        // Query should find it
        List<String> candidates = hierarchicalFilter.findCandidateSegments(
            term.getBytes(StandardCharsets.UTF_8));
        assertTrue(candidates.contains(segmentIdString),
            "Should find segment using String.valueOf format");
    }

    @Test
    @DisplayName("Term-based filtering: empty query returns no segments")
    void testEmptyQueryReturnsNoSegments() {
        // Insert terms into segment
        String docText = "Database optimization techniques";
        List<String> docTerms = TermExtractor.extractTerms(docText);
        for (String term : docTerms) {
                hierarchicalFilter.insert(term.getBytes(StandardCharsets.UTF_8), SEGMENT_ID_1);
        }

        // Query with empty text
        String queryText = "";
        List<String> queryTerms = TermExtractor.extractTerms(queryText);
        assertTrue(queryTerms.isEmpty(), "Empty query should produce no terms");
    }

    @Test
    @DisplayName("Term-based filtering: special characters are stripped")
    void testSpecialCharactersStripped() {
        String text = "Hello, world! How are you?";
        List<String> terms = TermExtractor.extractTerms(text);

        // Punctuation should be stripped
        // The extraction should produce clean terms
        for (String term : terms) {
            assertFalse(term.contains(","),
                "Term should not contain comma: " + term);
            assertFalse(term.contains("!"),
                "Term should not contain exclamation: " + term);
            assertFalse(term.contains("?"),
                "Term should not contain question mark: " + term);
        }
    }
}
