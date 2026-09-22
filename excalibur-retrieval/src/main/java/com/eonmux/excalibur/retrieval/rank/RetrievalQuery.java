package com.eonmux.excalibur.retrieval.rank;

import java.util.Optional;

/**
 * What every ranker is asked about.
 *
 * <p>Carries both the text and the embedding because the signals need different things: BM25 reads
 * the text, vector search reads the embedding, and a model reranker reads the text again. Passing
 * one object rather than a parameter per signal is what lets a new ranker be added without changing
 * the call site of every existing one.
 *
 * <p>Either representation may be absent. A ranker that needs one it was not given reports that it
 * cannot run, rather than inventing a substitute.
 *
 * @param text      the query as written, if available
 * @param embedding the query vector, if one was computed; not copied, so callers must not mutate it
 */
public record RetrievalQuery(String text, float[] embedding) {

    /** A text-only query, for lexical signals. */
    public static RetrievalQuery ofText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("query text must not be blank");
        }
        return new RetrievalQuery(text, null);
    }

    /** A vector-only query, for dense signals. */
    public static RetrievalQuery ofEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("query embedding must not be empty");
        }
        return new RetrievalQuery(null, embedding);
    }

    /** A query carrying both representations, which is what a hybrid search needs. */
    public static RetrievalQuery of(String text, float[] embedding) {
        if ((text == null || text.isBlank()) && (embedding == null || embedding.length == 0)) {
            throw new IllegalArgumentException(
                "a query needs text, an embedding, or both; it was given neither");
        }
        return new RetrievalQuery(text, embedding);
    }

    /** The text, if this query has any. */
    public Optional<String> textIfPresent() {
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /** Whether this query carries an embedding. */
    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }

    /**
     * The embedding, or a failure naming the ranker that needed it.
     *
     * <p>Named so the error says which signal could not run, rather than surfacing as a
     * NullPointerException somewhere inside a distance loop.
     */
    public float[] requireEmbedding(String rankerName) {
        if (!hasEmbedding()) {
            throw new IllegalArgumentException(
                rankerName + " needs a query embedding, but this query carries none");
        }
        return embedding;
    }
}
