package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the graph's answer becomes a ranking the evaluator can score.
 *
 * <p>The graph states facts, and a fact knows the document it was extracted from, not the passage.
 * So an answer cites documents, at the place its first fact from each holds in the answer. Pinned
 * here because it is the one step between the graph route and a score that the production code
 * does not already decide.
 */
@DisplayName("Graph citations")
class GraphCitationsTest {

    private EvaluationCorpus corpus;

    @BeforeEach
    void readCorpus(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("ada.txt"), "Ada Lovelace wrote Note G.");
        Files.writeString(root.resolve("babbage.txt"), "Charles Babbage designed the engine.");
        corpus = EvaluationCorpus.read(root, new Config(Map.of()).ingest());
    }

    private static SubgraphEdge factFrom(String documentId, long index) {
        return factFrom(SourceType.DOCUMENT, documentId, index);
    }

    private static SubgraphEdge factFrom(SourceType type, String documentId, long index) {
        return new SubgraphEdge(1, 2, 3, index, Integer.MIN_VALUE, TemporalFilter.OPEN,
            Provenance.of(type, LlmRelationExtractor.sourceIdOf(document(documentId)), 0.9, 1),
            1);
    }

    private static String document(String documentId) {
        return ChunkIdentifiers.documentKey(EvaluationCorpus.SOURCE_ID, documentId);
    }

    @Test
    @DisplayName("documents are cited in the order the answer states their facts, once each")
    void citationOrder() {
        List<String> cited = GraphCitations.documentsCitedBy(List.of(
            factFrom("babbage.txt", 0), factFrom("ada.txt", 1), factFrom("babbage.txt", 2)),
            corpus);

        assertThat(cited).containsExactly(document("babbage.txt"), document("ada.txt"));
    }

    @Test
    @DisplayName("a fact from anything but a document of this corpus cites nothing")
    void otherProvenanceCitesNothing() {
        assertThat(GraphCitations.documentsCitedBy(List.of(
            factFrom(SourceType.CONVERSATION, "ada.txt", 0),
            factFrom("elsewhere.txt", 1),
            new SubgraphEdge(1, 2, 3, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                Provenance.UNKNOWN, 1)), corpus))
            .isEmpty();
    }
}
