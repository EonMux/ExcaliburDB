package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagResult;
import com.eonmux.excalibur.retrieval.serialize.SerializedSubgraph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the graph route's answer into a ranking the evaluator can score.
 *
 * <p>The graph does not answer with passages. It answers with facts, and each fact knows the
 * document it was extracted from - extraction runs over a whole document - so what an answer can
 * honestly cite is a document. The mapping is:
 * <ol>
 *   <li>Take the facts the served context states, in the order it states them. That order is the
 *       route's own ranking, and only the facts that fit its token budget are in it, so what is
 *       scored is exactly what a caller of {@code /v1/graph/query} is shown.</li>
 *   <li>Map each fact's provenance to its document. A fact from anything but a document of this
 *       corpus cites nothing.</li>
 *   <li>Keep each document once, at the place of its first fact.</li>
 * </ol>
 * An answer the route declined, or one that needs the caller to disambiguate, cites nothing: it
 * gave the caller no facts.
 */
final class GraphCitations {

    private GraphCitations() {
    }

    /** The documents an answer cites, best first, or none when it stated no facts. */
    static List<String> documentsCitedBy(GraphRagResult result, EvaluationCorpus corpus) {
        return result.context()
            .map(SerializedSubgraph::edges)
            .map(edges -> documentsCitedBy(edges, corpus))
            .orElse(List.of());
    }

    /** The documents a sequence of stated facts cites, best first. */
    static List<String> documentsCitedBy(List<SubgraphEdge> edges, EvaluationCorpus corpus) {
        Set<String> documents = new LinkedHashSet<>();
        for (SubgraphEdge edge : edges) {
            String document = corpus.documentOf(edge.provenance());
            if (document != null) {
                documents.add(document);
            }
        }
        return List.copyOf(documents);
    }
}
