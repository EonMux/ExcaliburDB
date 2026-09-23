package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge-density warning, which reads directed out-edges: HNSW edges are directed, and a
 * healthy graph holds many that are not returned.
 */
@DisplayName("HNSW edge-density check")
class HnswGraphEdgeDensityTest {

    @Test
    @DisplayName("fewer than two out-edges per node warns")
    void shouldWarnOnFewOutEdges() {
        assertThat(codes(10, 15)).containsExactly("LOW_EDGE_DENSITY");
    }

    @Test
    @DisplayName("two or more out-edges per node is healthy")
    void shouldAcceptTwoOutEdgesPerNode() {
        assertThat(codes(10, 20)).isEmpty();
    }

    @Test
    @DisplayName("nodes with no edges at all warn rather than being skipped")
    void shouldWarnWhenThereAreNoEdges() {
        assertThat(codes(10, 0)).containsExactly("LOW_EDGE_DENSITY");
    }

    @Test
    @DisplayName("a graph too small to hold two out-edges per node is judged by what it can hold")
    void shouldJudgeATinyGraphByItsSize() {
        assertThat(codes(2, 2)).isEmpty();
        assertThat(codes(1, 0)).isEmpty();
        assertThat(codes(2, 1)).containsExactly("LOW_EDGE_DENSITY");
    }

    @Test
    @DisplayName("a graph with no active nodes is not judged")
    void shouldSkipAnEmptyGraph() {
        assertThat(codes(0, 0)).isEmpty();
    }

    private static List<String> codes(long activeNodes, long directedEdges) {
        List<GraphDiagnosticIssue> issues = new ArrayList<>();
        HnswGraphChecks.reportEdgeDensity(activeNodes, directedEdges, issues);
        return issues.stream().map(GraphDiagnosticIssue::code).toList();
    }
}
