package excalibur.ingest.graph;

import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;

import java.util.List;

/**
 * What ingestion tells the graph about a document it has just published.
 *
 * <p>An interface rather than a class so that ingestion does not depend on how the graph is built,
 * and so a deployment that indexes text without extracting facts wires {@link #none()} and pays
 * nothing. Ingestion calls this after the document is durably written and visible - never before,
 * because a fact staged for a document whose publication then failed would be a fact about
 * something the corpus does not contain.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be safe for concurrent use: one virtual thread per document.</p>
 *
 * @author ExcaliburDB
 */
public interface GraphIngestSink {

    /**
     * Records a published document in the graph.
     *
     * <p>Must not throw. Ingestion calls this after the document is already published and
     * counted, so a failure here cannot be signalled by failing the document - it would report
     * a document lost that is in fact fully searchable. An implementation logs and returns.</p>
     *
     * @param documentKey The source-qualified document key
     * @param text        The document's text
     */
    void onDocumentPublished(String documentKey, String text);

    /** A sink for a deployment that indexes text and does not build a graph. */
    static GraphIngestSink none() {
        return (documentKey, text) -> { };
    }

    /**
     * What a sink did with a document, for tests and for the ingest log line.
     *
     * @param facts     how many facts were staged
     * @param indexed   whether the document's text reached the lexical index
     */
    record Outcome(int facts, boolean indexed) {
    }

    /**
     * Stages the facts an extractor finds, and records the document's text for lexical retrieval.
     *
     * <p>The two halves are deliberately independent. Lexical retrieval works from the document
     * text alone and is what a hybrid query's BM25 signal reads; the facts are what traversal
     * walks. A deployment can have the first without the second - extraction is expensive and may
     * be off - and a failure of one must not cost the other, so each is attempted separately.</p>
     */
    interface Staging extends GraphIngestSink {

        /**
         * The facts staged so far and what they were extracted from.
         *
         * @return The staging store
         */
        GraphStagingStore staging();

        /**
         * Records facts directly, bypassing extraction.
         *
         * <p>For a caller that already has them: a bulk import, a curated knowledge base, or a
         * test.</p>
         *
         * @param triples The facts
         * @return How many were staged
         */
        int stage(List<ExtractedTriple> triples);
    }
}
