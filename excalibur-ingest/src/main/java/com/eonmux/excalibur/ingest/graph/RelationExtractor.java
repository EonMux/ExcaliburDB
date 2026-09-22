package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.staging.ExtractedTriple;

import java.util.List;

/**
 * Turns a document's text into the facts it asserts.
 *
 * <p>This is the seam between ingestion, which knows about documents, and the graph, which knows
 * about entities and relations. Everything downstream of it - the edge store, traversal, the
 * subgraph a model is shown - exists to serve facts that something had to produce first, and this
 * is the only thing that produces them.</p>
 *
 * <h2>What an implementation owes its caller</h2>
 * <ul>
 *   <li><b>Determinism.</b> The same text yields the same facts. A re-ingest that produced
 *       different triples would change the graph's shape for no reason an operator could point
 *       at, and would make an erasure request unrepeatable.</li>
 *   <li><b>Honest confidence.</b> The confidence on a triple's {@code Provenance} is what
 *       abstention and erasure-by-extractor act on. An extractor that reports 100 for everything
 *       removes the ability to tell a curated claim from a guess, which is the whole point of
 *       storing provenance.</li>
 *   <li><b>Failure that says so.</b> Returning an empty list means "this text asserts nothing";
 *       throwing means "I could not tell". Ingestion treats the two differently, and an extractor
 *       that swallows its own failure into an empty list makes a broken model look like a corpus
 *       of prose.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be safe for concurrent use: ingestion runs one virtual thread per
 * document.</p>
 *
 * @author ExcaliburDB
 */
@FunctionalInterface
public interface RelationExtractor {

    /**
     * The facts a document's text asserts.
     *
     * @param documentKey  The source-qualified document key, for provenance and tracing
     * @param text         The document's text
     * @return The facts, possibly empty; never null
     * @throws ExtractionException if the text could not be examined
     */
    List<ExtractedTriple> extract(String documentKey, String text);

    /** An extractor that finds nothing, for a deployment that indexes text and not facts. */
    static RelationExtractor none() {
        return (documentKey, text) -> List.of();
    }

    /**
     * Signals that a document's facts could not be determined.
     *
     * <p>Distinct from finding none: ingestion records the document either way, but only this
     * says the graph is now missing something it should have had.</p>
     */
    class ExtractionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message What went wrong
         * @param cause   The underlying failure, or null
         */
        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * @param message What went wrong
         */
        public ExtractionException(String message) {
            super(message);
        }
    }
}
