package com.eonmux.excalibur.query.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vector half of a query recalls what its graphs can find, once every stage has had its say.
 *
 * <p>Each stage in front of the graph search is entitled to skip work, and none of them is
 * entitled to skip answers. A stage that does is invisible from the outside: the query succeeds,
 * coverage reads as complete, and the results are simply further away than they should be. Only
 * a comparison against exact search shows it, which is what this test makes, on a corpus small
 * enough to run on every build. {@code QueryPipelineRecallGateTest} makes the same comparison at
 * a size that measures rather than smoke-tests.</p>
 */
@DisplayName("Query pipeline vector recall")
class QueryPipelineVectorRecallTest {

    private static final int K = 10;

    /**
     * How far the pipeline may fall below its graphs. The pipeline searches each graph at
     * least as widely as the reference does, so this only absorbs ties at the k-th distance.
     */
    private static final double TOLERANCE = 0.02;

    @Test
    @DisplayName("a fully integrated multi-segment corpus is recalled as well as its graphs recall it")
    void pipelineRecallMatchesTheGraphs(@TempDir Path dir) throws Exception {
        try (VectorRecallCorpus corpus = new VectorRecallCorpus(
                dir, 4, 2_000, 32, VectorRecallCorpus.PRODUCTION_SHAPE, 20260923L)) {

            VectorRecallCorpus.Recall recall = corpus.measure(40, K);

            assertThat(recall.graphsAlone())
                .as("the fixture only means something while the graphs themselves recall well")
                .isGreaterThanOrEqualTo(0.9);
            assertThat(recall.pipeline())
                .as("pipeline recall@%d (graphs alone: %.3f)", K, recall.graphsAlone())
                .isGreaterThanOrEqualTo(recall.graphsAlone() - TOLERANCE);
        }
    }

    @Test
    @DisplayName("query text that shares no term with the nearest documents costs no recall")
    void queryTextSharingNoTermWithTheNeighboursCostsNoRecall(@TempDir Path dir) throws Exception {
        // The HTTP route hands the pipeline the question's text beside its embedding. The nearest
        // vectors sit in every segment, and only one segment's documents use the question's
        // words: the others say the same thing in other terms, which is what an embedding is
        // for. Recall must not depend on which words the question happened to use.
        try (VectorRecallCorpus corpus = new VectorRecallCorpus(
                dir, 4, 2_000, 32, VectorRecallCorpus.PRODUCTION_SHAPE, 20260923L)) {

            assertThat(corpus.termFilterCoversEverySegment())
                .as("the term filter has to speak for every segment for the text to be tested")
                .isTrue();

            VectorRecallCorpus.Recall recall =
                corpus.measure(40, K, VectorRecallCorpus.MISMATCHED_QUERY_TEXT);

            assertThat(recall.graphsAlone())
                .as("the fixture only means something while the graphs themselves recall well")
                .isGreaterThanOrEqualTo(0.9);
            assertThat(recall.pipeline())
                .as("pipeline recall@%d with query text present (graphs alone: %.3f)",
                    K, recall.graphsAlone())
                .isGreaterThanOrEqualTo(recall.graphsAlone() - TOLERANCE);
        }
    }
}
