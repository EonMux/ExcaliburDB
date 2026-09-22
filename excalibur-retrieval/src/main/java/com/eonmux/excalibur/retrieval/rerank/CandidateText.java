package com.eonmux.excalibur.retrieval.rerank;

/**
 * Supplies the text a reranking model should judge for a candidate.
 *
 * <p>Separate from the ranker because what a candidate "is" as text depends on the corpus — a
 * chunk, a title plus a snippet, a serialised subgraph — and the reranker should not have to know.
 */
@FunctionalInterface
public interface CandidateText {

    /**
     * Text for {@code documentId}, or null if there is none.
     *
     * <p>A candidate with no text cannot be judged. The reranker keeps it at its existing rank
     * rather than dropping it or sending an empty string, because an empty string is not a
     * statement that the document is irrelevant.
     */
    String textOf(long documentId);
}
