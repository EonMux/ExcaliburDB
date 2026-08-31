package io.excalibur.retrieval.rank;

/**
 * A candidate and the score one ranker gave it.
 *
 * <p>The score is that ranker's own quantity — a BM25 score, a negated distance, a fused
 * reciprocal-rank sum — and is meaningful only against other scores from the same source. Nothing
 * here compares one ranker's scores with another's; that is exactly what fusion exists to avoid.
 *
 * @param documentId the candidate, as an internal integer id rather than a string, so that
 *                   everything after entity linking works on integers
 * @param score      this ranker's score, higher meaning better
 */
public record ScoredDocument(long documentId, double score) implements Comparable<ScoredDocument> {

    public ScoredDocument {
        if (Double.isNaN(score)) {
            // NaN would sort inconsistently and silently corrupt any ordering it entered.
            throw new IllegalArgumentException("score must not be NaN for document " + documentId);
        }
    }

    /**
     * Orders best first.
     *
     * <p>Ties break on document id so that a ranked list is deterministic. Without it, two
     * candidates with identical scores could swap places between runs, which makes a reciprocal
     * rank — and therefore a fused result — depend on sort stability rather than on the data.
     */
    @Override
    public int compareTo(ScoredDocument other) {
        int byScore = Double.compare(other.score, this.score);
        return byScore != 0 ? byScore : Long.compare(this.documentId, other.documentId);
    }
}
