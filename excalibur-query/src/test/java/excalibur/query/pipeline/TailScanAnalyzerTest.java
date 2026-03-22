package excalibur.query.pipeline;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TailScanAnalyzer} and {@link TailScanRequirement}.
 *
 * <p>These tests verify the core comparison logic between lastIntegratedOffset
 * and writeLogHead for candidate segments.</p>
 *
 * @author ExcaliburDB
 */
class TailScanAnalyzerTest {

    private static final long SEGMENT_ID_1 = 1L;
    private static final long SEGMENT_ID_2 = 2L;
    private static final long SEGMENT_ID_3 = 3L;

    private TailScanAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TailScanAnalyzer();
    }

    // ==================== TailScanRequirement Tests ====================

    @Test
    @DisplayName("TailScanRequirement.compare: no tail scan when offsets equal")
    void testCompareEqualOffsets() {
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 1000, 1000);

        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(1000, req.lastIntegratedOffset());
        assertEquals(1000, req.writeLogHead());
        assertEquals(0, req.tailSize());
        assertFalse(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("TailScanRequirement.compare: tail scan needed when writeLogHead > lastIntegratedOffset")
    void testCompareTailScanNeeded() {
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 1000, 1500);

        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(1000, req.lastIntegratedOffset());
        assertEquals(1500, req.writeLogHead());
        assertEquals(500, req.tailSize());
        assertTrue(req.isTailScanNeeded());
        assertEquals(1000, req.tailScanStartOffset());
        assertEquals(1500, req.tailScanEndOffset());
    }

    @Test
    @DisplayName("TailScanRequirement.compare: no tail scan when lastIntegratedOffset > writeLogHead")
    void testCompareNoTailScanWhenIntegratedAhead() {
        // This shouldn't happen in practice, but we handle it gracefully
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 2000, 1000);

        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(2000, req.lastIntegratedOffset());
        assertEquals(1000, req.writeLogHead());
        assertEquals(0, req.tailSize());
        assertFalse(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("TailScanRequirement.compare: handles negative offsets (normalized to 0)")
    void testCompareNegativeOffsets() {
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, -100, -50);

        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(0, req.lastIntegratedOffset()); // Normalized to 0
        assertEquals(0, req.writeLogHead()); // Normalized to 0
        assertEquals(0, req.tailSize());
        assertFalse(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("TailScanRequirement.compare: handles zero offsets")
    void testCompareZeroOffsets() {
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 0, 0);

        assertEquals(0, req.tailSize());
        assertFalse(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("TailScanRequirement.noTailScan: creates requirement with zero tail")
    void testNoTailScan() {
        TailScanRequirement req = TailScanRequirement.noTailScan(SEGMENT_ID_1);

        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(0, req.lastIntegratedOffset());
        assertEquals(0, req.writeLogHead());
        assertEquals(0, req.tailSize());
        assertFalse(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("TailScanRequirement.exceedsBackpressureLimit: detects large tails")
    void testExceedsBackpressureLimit() {
        TailScanRequirement smallTail = TailScanRequirement.compare(SEGMENT_ID_1, 0, 1000);
        TailScanRequirement largeTail = TailScanRequirement.compare(SEGMENT_ID_2, 0, 10_000_001);

        assertFalse(smallTail.exceedsBackpressureLimit(1_000_000));
        assertTrue(largeTail.exceedsBackpressureLimit(10_000_000));
    }

    // ==================== TailScanRequirement Entry Count Tests ====================

    @Test
    @DisplayName("TailScanRequirement.estimatedEntryCount: calculates correctly")
    void testEstimatedEntryCount() {
        // 10,000 bytes / 100 bytes avg = 100 entries
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 0, 10000);
        assertEquals(100L, req.estimatedEntryCount());

        // 5,500 bytes / 100 bytes avg = 55 entries
        TailScanRequirement req2 = TailScanRequirement.compare(SEGMENT_ID_2, 0, 5500);
        assertEquals(55L, req2.estimatedEntryCount());

        // Zero tail = zero entries
        TailScanRequirement noTail = TailScanRequirement.compare(SEGMENT_ID_3, 1000, 1000);
        assertEquals(0L, noTail.estimatedEntryCount());
    }

    @Test
    @DisplayName("TailScanRequirement.exceedsEntryLimit: detects when limit exceeded")
    void testExceedsEntryLimit() {
        // 150K entries (15M bytes / 100)
        TailScanRequirement largeTail = TailScanRequirement.compare(SEGMENT_ID_1, 0, 15_000_000);

        assertTrue(largeTail.exceedsEntryLimit(100_000)); // 100K limit - exceeded
        assertFalse(largeTail.exceedsEntryLimit(150_000)); // 150K limit - equal, not exceeded
        assertFalse(largeTail.exceedsEntryLimit(200_000)); // 200K limit - not exceeded
    }

    @Test
    @DisplayName("TailScanRequirement.exceedsEntryLimit: handles zero and negative limits")
    void testExceedsEntryLimitEdgeCases() {
        TailScanRequirement req = TailScanRequirement.compare(SEGMENT_ID_1, 0, 15_000_000);

        // Zero or negative limits mean no limit enforced
        assertFalse(req.exceedsEntryLimit(0));
        assertFalse(req.exceedsEntryLimit(-1000));
    }

    @Test
    @DisplayName("TailScanRequirement.exceedsDefaultEntryLimit: checks against 100K default")
    void testExceedsDefaultEntryLimit() {
        // 50K entries (5M bytes)
        TailScanRequirement smallTail = TailScanRequirement.compare(SEGMENT_ID_1, 0, 5_000_000);
        assertFalse(smallTail.exceedsDefaultEntryLimit());

        // 150K entries (15M bytes)
        TailScanRequirement largeTail = TailScanRequirement.compare(SEGMENT_ID_2, 0, 15_000_000);
        assertTrue(largeTail.exceedsDefaultEntryLimit());
    }

    @Test
    @DisplayName("TailScanRequirement constants: default values are correct")
    void testTailScanRequirementConstants() {
        assertEquals(100, TailScanRequirement.DEFAULT_AVG_ENTRY_SIZE);
        assertEquals(100_000, TailScanRequirement.DEFAULT_ENTRY_LIMIT);
    }

    // ==================== TailScanAnalyzer Tests ====================

    @Test
    @DisplayName("analyzeSegments: empty list returns empty result")
    void testAnalyzeEmptyList() {
        List<TailScanRequirement> results = analyzer.analyzeSegments(Collections.emptyList());

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("analyzeSegments: analyzes single segment needing tail scan")
    void testAnalyzeSingleSegmentNeedingTailScan() {
        // Create mock segment and graph
        Segment segment = mock(Segment.class);
        HnswGraph graph = mock(HnswGraph.class);

        when(segment.getWriteLogHead()).thenReturn(2000L);
        when(graph.getLastIntegratedOffset()).thenReturn(1000L);

        SegmentCandidate candidate = new SegmentCandidate(
            SEGMENT_ID_1, segment, graph, LifecycleState.ACTIVE);

        List<TailScanRequirement> results = analyzer.analyzeSegments(List.of(candidate));

        assertEquals(1, results.size());
        TailScanRequirement req = results.get(0);
        assertEquals(SEGMENT_ID_1, req.segmentId());
        assertEquals(1000, req.lastIntegratedOffset());
        assertEquals(2000, req.writeLogHead());
        assertEquals(1000, req.tailSize());
        assertTrue(req.isTailScanNeeded());
    }

    @Test
    @DisplayName("analyzeSegments: handles null segment gracefully")
    void testAnalyzeNullSegment() {
        HnswGraph graph = mock(HnswGraph.class);
        when(graph.getLastIntegratedOffset()).thenReturn(1000L);

        SegmentCandidate candidate = new SegmentCandidate(
            SEGMENT_ID_1, null, graph, LifecycleState.ACTIVE);

        List<TailScanRequirement> results = analyzer.analyzeSegments(List.of(candidate));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isTailScanNeeded());
    }

    @Test
    @DisplayName("analyzeSegments: handles null graph gracefully")
    void testAnalyzeNullGraph() {
        Segment segment = mock(Segment.class);
        when(segment.getWriteLogHead()).thenReturn(2000L);

        SegmentCandidate candidate = new SegmentCandidate(
            SEGMENT_ID_1, segment, null, LifecycleState.ACTIVE);

        List<TailScanRequirement> results = analyzer.analyzeSegments(List.of(candidate));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isTailScanNeeded());
    }

    @Test
    @DisplayName("analyzeSegments: multiple segments with mixed requirements")
    void testAnalyzeMultipleSegments() {
        // Segment 1: needs tail scan (1000 -> 2000)
        Segment segment1 = mock(Segment.class);
        HnswGraph graph1 = mock(HnswGraph.class);
        when(segment1.getWriteLogHead()).thenReturn(2000L);
        when(graph1.getLastIntegratedOffset()).thenReturn(1000L);

        // Segment 2: no tail scan needed (equal offsets)
        Segment segment2 = mock(Segment.class);
        HnswGraph graph2 = mock(HnswGraph.class);
        when(segment2.getWriteLogHead()).thenReturn(3000L);
        when(graph2.getLastIntegratedOffset()).thenReturn(3000L);

        // Segment 3: no tail scan needed (integrated ahead)
        Segment segment3 = mock(Segment.class);
        HnswGraph graph3 = mock(HnswGraph.class);
        when(segment3.getWriteLogHead()).thenReturn(1000L);
        when(graph3.getLastIntegratedOffset()).thenReturn(4000L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment1, graph1, LifecycleState.ACTIVE),
            new SegmentCandidate(SEGMENT_ID_2, segment2, graph2, LifecycleState.INDEXING),
            new SegmentCandidate(SEGMENT_ID_3, segment3, graph3, LifecycleState.WARMING)
        );

        List<TailScanRequirement> results = analyzer.analyzeSegments(candidates);

        assertEquals(3, results.size());

        // Segment 1 needs tail scan
        assertTrue(results.get(0).isTailScanNeeded());
        assertEquals(1000, results.get(0).tailSize());

        // Segment 2 no tail scan
        assertFalse(results.get(1).isTailScanNeeded());
        assertEquals(0, results.get(1).tailSize());

        // Segment 3 no tail scan (integrated ahead)
        assertFalse(results.get(2).isTailScanNeeded());
        assertEquals(0, results.get(2).tailSize());
    }

    // ==================== AnalysisResult Tests ====================

    @Test
    @DisplayName("analyzeWithSummary: computes correct statistics")
    void testAnalyzeWithSummary() {
        // Segment 1: needs tail scan (1000 bytes)
        Segment segment1 = mock(Segment.class);
        HnswGraph graph1 = mock(HnswGraph.class);
        when(segment1.getWriteLogHead()).thenReturn(2000L);
        when(graph1.getLastIntegratedOffset()).thenReturn(1000L);

        // Segment 2: no tail scan
        Segment segment2 = mock(Segment.class);
        HnswGraph graph2 = mock(HnswGraph.class);
        when(segment2.getWriteLogHead()).thenReturn(3000L);
        when(graph2.getLastIntegratedOffset()).thenReturn(3000L);

        // Segment 3: needs tail scan (500 bytes)
        Segment segment3 = mock(Segment.class);
        HnswGraph graph3 = mock(HnswGraph.class);
        when(segment3.getWriteLogHead()).thenReturn(3500L);
        when(graph3.getLastIntegratedOffset()).thenReturn(3000L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment1, graph1, LifecycleState.ACTIVE),
            new SegmentCandidate(SEGMENT_ID_2, segment2, graph2, LifecycleState.INDEXING),
            new SegmentCandidate(SEGMENT_ID_3, segment3, graph3, LifecycleState.WARMING)
        );

        TailScanAnalyzer.AnalysisResult result = analyzer.analyzeWithSummary(candidates);

        // Verify counts
        assertEquals(3, result.totalSegments());
        assertEquals(2, result.segmentsNeedingTailScan());
        assertEquals(1, result.segmentsWithNoTail());

        // Verify total tail size
        assertEquals(1500, result.totalTailSize());

        // Verify max tail
        assertEquals(1000, result.maxTailSize());
        assertEquals(SEGMENT_ID_1, result.segmentIdWithMaxTail());

        // Verify coverage ratio
        assertEquals(2.0 / 3.0, result.tailScanCoverageRatio(), 0.001);
    }

    @Test
    @DisplayName("analyzeWithSummary: empty candidates returns zero statistics")
    void testAnalyzeWithSummaryEmpty() {
        TailScanAnalyzer.AnalysisResult result = analyzer.analyzeWithSummary(Collections.emptyList());

        assertEquals(0, result.totalSegments());
        assertEquals(0, result.segmentsNeedingTailScan());
        assertEquals(0, result.totalTailSize());
        assertEquals(0.0, result.tailScanCoverageRatio(), 0.001);
    }

    @Test
    @DisplayName("filterSegmentsNeedingTailScan: returns only segments needing tail scan")
    void testFilterSegmentsNeedingTailScan() {
        // Segment 1: needs tail scan
        Segment segment1 = mock(Segment.class);
        HnswGraph graph1 = mock(HnswGraph.class);
        when(segment1.getWriteLogHead()).thenReturn(2000L);
        when(graph1.getLastIntegratedOffset()).thenReturn(1000L);

        // Segment 2: no tail scan
        Segment segment2 = mock(Segment.class);
        HnswGraph graph2 = mock(HnswGraph.class);
        when(segment2.getWriteLogHead()).thenReturn(3000L);
        when(graph2.getLastIntegratedOffset()).thenReturn(3000L);

        SegmentCandidate candidate1 = new SegmentCandidate(
            SEGMENT_ID_1, segment1, graph1, LifecycleState.ACTIVE);
        SegmentCandidate candidate2 = new SegmentCandidate(
            SEGMENT_ID_2, segment2, graph2, LifecycleState.ACTIVE);

        List<SegmentCandidate> filtered = analyzer.filterSegmentsNeedingTailScan(
            List.of(candidate1, candidate2));

        assertEquals(1, filtered.size());
        assertEquals(SEGMENT_ID_1, filtered.get(0).segmentId());
    }

    @Test
    @DisplayName("computeTotalTailSize: sums tail sizes correctly")
    void testComputeTotalTailSize() {
        // Segment 1: 500 byte tail
        Segment segment1 = mock(Segment.class);
        HnswGraph graph1 = mock(HnswGraph.class);
        when(segment1.getWriteLogHead()).thenReturn(1500L);
        when(graph1.getLastIntegratedOffset()).thenReturn(1000L);

        // Segment 2: 300 byte tail
        Segment segment2 = mock(Segment.class);
        HnswGraph graph2 = mock(HnswGraph.class);
        when(segment2.getWriteLogHead()).thenReturn(800L);
        when(graph2.getLastIntegratedOffset()).thenReturn(500L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment1, graph1, LifecycleState.ACTIVE),
            new SegmentCandidate(SEGMENT_ID_2, segment2, graph2, LifecycleState.ACTIVE)
        );

        long totalSize = analyzer.computeTotalTailSize(candidates);

        assertEquals(800, totalSize);
    }

    // ==================== Backpressure Entry Limit Tests ====================

    @Test
    @DisplayName("analyzeWithSummary: tracks estimated entry count")
    void testAnalyzeWithSummaryEntryCount() {
        // Segment 1: 10,000 byte tail = ~100 entries (at 100 bytes avg)
        Segment segment1 = mock(Segment.class);
        HnswGraph graph1 = mock(HnswGraph.class);
        when(segment1.getWriteLogHead()).thenReturn(10000L);
        when(graph1.getLastIntegratedOffset()).thenReturn(0L);

        // Segment 2: 5,000 byte tail = ~50 entries
        Segment segment2 = mock(Segment.class);
        HnswGraph graph2 = mock(HnswGraph.class);
        when(segment2.getWriteLogHead()).thenReturn(5000L);
        when(graph2.getLastIntegratedOffset()).thenReturn(0L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment1, graph1, LifecycleState.ACTIVE),
            new SegmentCandidate(SEGMENT_ID_2, segment2, graph2, LifecycleState.ACTIVE)
        );

        TailScanAnalyzer.AnalysisResult result = analyzer.analyzeWithSummary(candidates);

        // Total tail size = 15,000 bytes, estimated entries = 150
        assertEquals(15000L, result.totalTailSize());
        assertEquals(150L, result.estimatedEntryCount());
    }

    @Test
    @DisplayName("analyzeWithSummary: detects when entry limit is exceeded")
    void testAnalyzeWithSummaryEntryLimitExceeded() {
        // Create a large tail that exceeds 100K entries
        // 100K entries * 100 bytes = 10,000,000 bytes
        Segment segment = mock(Segment.class);
        HnswGraph graph = mock(HnswGraph.class);
        when(segment.getWriteLogHead()).thenReturn(15_000_000L);
        when(graph.getLastIntegratedOffset()).thenReturn(0L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment, graph, LifecycleState.ACTIVE)
        );

        TailScanAnalyzer.AnalysisResult result = analyzer.analyzeWithSummary(candidates);

        // 15M bytes / 100 bytes = 150K entries > 100K limit
        assertTrue(result.exceedsEntryLimit());
        assertEquals(150_000L, result.estimatedEntryCount());
        assertEquals(100_000, result.entryLimit());
    }

    @Test
    @DisplayName("analyzeWithSummary: entry limit not exceeded for small tail")
    void testAnalyzeWithSummaryEntryLimitNotExceeded() {
        Segment segment = mock(Segment.class);
        HnswGraph graph = mock(HnswGraph.class);
        when(segment.getWriteLogHead()).thenReturn(1_000_000L); // 10K entries
        when(graph.getLastIntegratedOffset()).thenReturn(0L);

        List<SegmentCandidate> candidates = List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment, graph, LifecycleState.ACTIVE)
        );

        TailScanAnalyzer.AnalysisResult result = analyzer.analyzeWithSummary(candidates);

        assertFalse(result.exceedsEntryLimit());
        assertEquals(10_000L, result.estimatedEntryCount());
    }

    @Test
    @DisplayName("exceedsEntryLimit method: returns correct value based on candidates")
    void testExceedsEntryLimitMethod() {
        // Small tail
        Segment smallSegment = mock(Segment.class);
        HnswGraph smallGraph = mock(HnswGraph.class);
        when(smallSegment.getWriteLogHead()).thenReturn(1000L);
        when(smallGraph.getLastIntegratedOffset()).thenReturn(0L);

        assertFalse(analyzer.exceedsEntryLimit(List.of(
            new SegmentCandidate(SEGMENT_ID_1, smallSegment, smallGraph, LifecycleState.ACTIVE)
        )));

        // Large tail
        Segment largeSegment = mock(Segment.class);
        HnswGraph largeGraph = mock(HnswGraph.class);
        when(largeSegment.getWriteLogHead()).thenReturn(15_000_000L);
        when(largeGraph.getLastIntegratedOffset()).thenReturn(0L);

        assertTrue(analyzer.exceedsEntryLimit(List.of(
            new SegmentCandidate(SEGMENT_ID_1, largeSegment, largeGraph, LifecycleState.ACTIVE)
        )));
    }

    @Test
    @DisplayName("estimateTotalEntryCount: estimates correctly based on tail size")
    void testEstimateTotalEntryCount() {
        // 5,000 bytes = 50 entries at 100 bytes avg
        Segment segment = mock(Segment.class);
        HnswGraph graph = mock(HnswGraph.class);
        when(segment.getWriteLogHead()).thenReturn(5000L);
        when(graph.getLastIntegratedOffset()).thenReturn(0L);

        long estimate = analyzer.estimateTotalEntryCount(List.of(
            new SegmentCandidate(SEGMENT_ID_1, segment, graph, LifecycleState.ACTIVE)
        ));

        assertEquals(50L, estimate);
    }

    @Test
    @DisplayName("custom entry limit constructor")
    void testCustomEntryLimitConstructor() {
        TailScanAnalyzer customAnalyzer = new TailScanAnalyzer(50_000);

        // Create tail that exceeds 50K but not 100K
        Segment segment = mock(Segment.class);
        HnswGraph graph = mock(HnswGraph.class);
        when(segment.getWriteLogHead()).thenReturn(7_500_000L); // 75K entries
        when(graph.getLastIntegratedOffset()).thenReturn(0L);

        TailScanAnalyzer.AnalysisResult result = customAnalyzer.analyzeWithSummary(
            List.of(new SegmentCandidate(SEGMENT_ID_1, segment, graph, LifecycleState.ACTIVE))
        );

        assertTrue(result.exceedsEntryLimit());
        assertEquals(50_000, result.entryLimit());
    }
}
