package com.eonmux.excalibur.retrieval.rerank;

/**
 * Supplies uncompressed vectors for rerank.
 *
 * <p>Separate from whatever holds the compressed ones. The approximate index answers "which
 * hundred documents are worth looking at" from quantised vectors that fit in memory; this answers
 * "what is the real distance to this one" from the full-precision vector on disk. Those are
 * different access patterns over different data, and conflating them is what forces a system to
 * choose between an index that fits and an answer that is right.
 */
@FunctionalInterface
public interface VectorSource {

    /**
     * The full-precision vector for a document.
     *
     * <p>Returning null is legitimate and means the document has no stored vector — it may have
     * been reached by a lexical or graph signal alone. Rerank keeps such a document rather than
     * dropping it, because its absence from vector space says nothing about its relevance.
     *
     * @param documentId the document
     * @return its vector, or null if there is none
     */
    float[] vectorOf(long documentId);
}
