package com.eonmux.excalibur.retrieval.lexical;

/**
 * The two constants BM25 is defined by, carried as one value.
 *
 * <p>They travel together because they are only meaningful together. A document's score depends on
 * {@code k1} and {@code b} jointly, and - in the engine's own index - so does the per-block impact
 * bound that decides which blocks a query may skip without reading. Passing them as two loose
 * doubles invites a call site that threads one and forgets the other, which is the failure this
 * type exists to make impossible.
 *
 * <p>Set by {@code excalibur.ingest.bm25-k1} and {@code excalibur.ingest.bm25-b}. Neither is a
 * knob to reach for without a query set to measure against: the published defaults are the values
 * the formula was tuned with, and moving them usually makes retrieval worse rather than better.
 *
 * @param k1 Term-frequency saturation: how much a repeated term keeps earning. Zero makes term
 *           frequency irrelevant; large values make the score nearly linear in it.
 * @param b  Length normalisation, as a proportion. Zero ignores document length entirely; one
 *           normalises fully against the mean length of the collection.
 */
public record Bm25Parameters(double k1, double b) {

    /** Term-frequency saturation, as the original formulation fixes it. */
    public static final double DEFAULT_K1 = 1.2;

    /** Length normalisation, as the original formulation fixes it. */
    public static final double DEFAULT_B = 0.75;

    /** What a node that has not been told otherwise scores with. */
    public static final Bm25Parameters DEFAULT = new Bm25Parameters(DEFAULT_K1, DEFAULT_B);

    public Bm25Parameters {
        if (!Double.isFinite(k1) || k1 < 0) {
            throw new IllegalArgumentException(
                "k1 must be a finite, non-negative number, got " + k1);
        }
        if (!Double.isFinite(b) || b < 0 || b > 1) {
            throw new IllegalArgumentException("b must be in [0, 1], got " + b);
        }
        // Folds negative zero onto zero. An index records the pair it was built with and refuses
        // to open under a different one, and -0.0 compares unequal to 0.0 - so without this a
        // deployment could be locked out of its own index by the sign of a zero.
        k1 = k1 + 0.0;
        b = b + 0.0;
    }

    /**
     * The length-normalised floor a term frequency saturates against.
     *
     * <p>Query-independent, and the same for every term in a document, so the scorer computes it
     * once per document rather than once per posting.
     *
     * @param documentLength        Tokens in the document
     * @param averageDocumentLength Mean tokens per document across the collection
     */
    public double lengthNorm(int documentLength, double averageDocumentLength) {
        return k1 * (1 - b + b * documentLength / averageDocumentLength);
    }

    /**
     * The query-independent half of one posting's BM25 score.
     *
     * <p>One definition, used both to score a posting and to bound what a block of postings can
     * score. The two must agree exactly: the scorer stops scanning a term whose bound has fallen
     * below the current top-k, so a bound computed even slightly differently would let it skip a
     * block that holds a matching document.
     *
     * @param termFrequency Occurrences of the term in the document
     * @param lengthNorm    What {@link #lengthNorm} returned for that document
     */
    public double saturation(int termFrequency, double lengthNorm) {
        return termFrequency * (k1 + 1) / (termFrequency + lengthNorm);
    }

    /**
     * The most {@link #saturation} can ever return, whatever the frequency or the document.
     *
     * <p>Approached as a term is repeated without limit, so it bounds any posting at all. It is
     * the bound to fall back on when the ingredients of a tighter one are not available - a block
     * whose recorded frequency has hit the ceiling of its field, or an index written before the
     * headers carried them - and it is deliberately loose: a query using it prunes almost nothing
     * and returns exactly the right answer, which is the correct way round for the two to fail.
     */
    public double maximumSaturation() {
        return k1 + 1;
    }

    /** Both values in one phrase, for a message that has to name two pairs. */
    public String describe() {
        return "k1=" + k1 + ", b=" + b;
    }
}
