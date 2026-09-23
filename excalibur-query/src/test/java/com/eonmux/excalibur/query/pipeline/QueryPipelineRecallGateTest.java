package com.eonmux.excalibur.query.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: the query pipeline's vector recall@10 against brute force, end to end.
 *
 * <p>{@code HnswRecallValidationTest} holds the graph to its recall target, and says nothing
 * about the stages in front of it. Every one of those is entitled to skip work and none to skip
 * answers, and a stage that skips answers fails nothing: the query succeeds, coverage reads as
 * complete, and the results are simply further away than they should be. This gate measures the
 * answer a caller actually gets - every stage from the epoch snapshot to the rerank, over a corpus
 * spread across several segments whose graphs, LSH indexes and term filter levels are fully
 * integrated, under the LSH shape a node runs with by default - against exact search over the
 * same vectors. It is measured twice: with vector-only queries, and with each query also
 * carrying text phrased in words the documents nearest it do not use, which is how the HTTP route
 * runs every query and the case in which a stage reading the text could cost answers.</p>
 *
 * <p>Two bounds are asserted. Against the graphs searched directly and merged, the pipeline may
 * fall short only by ties at the k-th distance: it searches each graph at least as widely, and
 * nothing it adds can displace a result except with a closer one. And an absolute floor, so that a
 * change degrading the graphs and the pipeline together cannot pass by keeping them in step.</p>
 */
@Tag("benchmark")
@DisplayName("Query pipeline recall gate")
class QueryPipelineRecallGateTest {

    private static final int K = 10;
    private static final int SEGMENTS = 8;
    private static final int VECTORS = 20_000;
    private static final int DIMENSION = 64;
    private static final int QUERIES = 200;

    /** How far the pipeline may fall below its own graphs, for ties at the k-th distance. */
    private static final double TOLERANCE_BELOW_GRAPHS = 0.01;

    /** The floor under the pipeline's recall@10, whatever the graphs manage. */
    private static final double RECALL_FLOOR = 0.95;

    @Test
    @DisplayName("pipeline recall@10 over eight integrated segments matches its graphs'")
    void pipelineRecallGate(@TempDir Path dir) throws Exception {
        assertRecallGate(dir, null, "vector only");
    }

    /**
     * The same gate with the query's text present, as the HTTP route always sends it, phrased in
     * words only one segment's documents use while its nearest vectors are in all of them.
     */
    @Test
    @DisplayName("pipeline recall@10 with query text the neighbours do not share matches its graphs'")
    void pipelineRecallGateWithMismatchedQueryText(@TempDir Path dir) throws Exception {
        assertRecallGate(dir, VectorRecallCorpus.MISMATCHED_QUERY_TEXT, "with query text");
    }

    private static void assertRecallGate(Path dir, String queryText, String label)
            throws Exception {
        try (VectorRecallCorpus corpus = new VectorRecallCorpus(
                dir, SEGMENTS, VECTORS, DIMENSION, VectorRecallCorpus.PRODUCTION_SHAPE, 7L)) {

            assertThat(corpus.termFilterCoversEverySegment())
                .as("the term filter has to speak for every segment for the gate to test it")
                .isTrue();

            VectorRecallCorpus.Recall recall = corpus.measure(QUERIES, K, queryText);

            System.out.printf("Pipeline recall@%d (%s): %.4f; graphs alone: %.4f (%d queries, "
                    + "%d vectors over %d segments)%n",
                K, label, recall.pipeline(), recall.graphsAlone(), recall.queries(), VECTORS,
                SEGMENTS);

            assertThat(recall.pipeline())
                .as("pipeline recall@%d against its graphs' %.4f", K, recall.graphsAlone())
                .isGreaterThanOrEqualTo(recall.graphsAlone() - TOLERANCE_BELOW_GRAPHS);
            assertThat(recall.pipeline())
                .as("pipeline recall@%d against the floor", K)
                .isGreaterThanOrEqualTo(RECALL_FLOOR);
        }
    }
}
