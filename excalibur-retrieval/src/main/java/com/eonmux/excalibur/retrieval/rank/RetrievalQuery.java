package com.eonmux.excalibur.retrieval.rank;

import com.eonmux.excalibur.retrieval.expand.SearchFormulation;

import java.util.List;
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
 * <p>A query may also carry the formulations its text was already widened into. A lexical ranker
 * given them searches with them instead of expanding the text itself, which is how one expansion,
 * resolved once inside the request's budget, serves every ranking a search runs - rather than each
 * one asking a chat model again, and possibly being answered differently.
 *
 * @param text         the query as written, if available
 * @param embedding    the query vector, if one was computed; not copied, so callers must not
 *                     mutate it
 * @param formulations the formulations the text was widened into, the text itself first, or null
 *                     when it has not been widened and a ranker that widens it is to do so
 */
public record RetrievalQuery(String text, float[] embedding, List<SearchFormulation> formulations) {

    /**
     * Copies the formulations, when there are any, so a query cannot change under a ranker.
     */
    public RetrievalQuery {
        formulations = formulations == null ? null : List.copyOf(formulations);
    }

    /** A text-only query, for lexical signals. */
    public static RetrievalQuery ofText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("query text must not be blank");
        }
        return new RetrievalQuery(text, null, null);
    }

    /** A vector-only query, for dense signals. */
    public static RetrievalQuery ofEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("query embedding must not be empty");
        }
        return new RetrievalQuery(null, embedding, null);
    }

    /** A query carrying both representations, which is what a hybrid search needs. */
    public static RetrievalQuery of(String text, float[] embedding) {
        if ((text == null || text.isBlank()) && (embedding == null || embedding.length == 0)) {
            throw new IllegalArgumentException(
                "a query needs text, an embedding, or both; it was given neither");
        }
        return new RetrievalQuery(text, embedding, null);
    }

    /**
     * This query, already widened into the formulations a lexical ranker is to search with.
     *
     * @param widened The formulations, the text itself first; empty searches nothing
     * @return A copy carrying them
     * @throws IllegalArgumentException if widened is null or this query has no text to have
     *                                  widened
     */
    public RetrievalQuery expandedTo(List<SearchFormulation> widened) {
        if (widened == null) {
            throw new IllegalArgumentException("formulations must not be null");
        }
        if (textIfPresent().isEmpty()) {
            throw new IllegalArgumentException("a query with no text cannot carry formulations");
        }
        return new RetrievalQuery(text, embedding, widened);
    }

    /** The text, if this query has any. */
    public Optional<String> textIfPresent() {
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * The formulations the text was already widened into, if it was.
     *
     * @return The formulations, the text itself first, or empty when a ranker is to widen the
     *         text itself
     */
    public Optional<List<SearchFormulation>> formulationsIfPresent() {
        return Optional.ofNullable(formulations);
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
