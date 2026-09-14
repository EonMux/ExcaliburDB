package excalibur.ingest.pipeline;

import excalibur.ingest.graph.GraphIngestSink;
import excalibur.ingest.source.FetchedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the graph about documents ingestion has published, so facts can be extracted from them.
 *
 * <p>Optional throughout: a deployment that indexes text without extracting facts never sets a
 * sink, and every report is then a no-op.</p>
 */
final class GraphReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphReporter.class);

    /**
     * Where published documents are reported so the graph can be built from them.
     *
     * <p>Settable after construction: the graph stack is opened by the API layer, which is built
     * after the orchestrator that owns this reporter.</p>
     */
    private volatile GraphIngestSink graphSink;

    /**
     * Sets where published documents are reported so a graph can be built from them.
     *
     * <p>Set after construction because the graph stack belongs to the API layer, which is
     * assembled after this orchestrator. Null disables it, which is the state of any deployment
     * that indexes text and does not extract facts.</p>
     *
     * @param graphSink The sink, or null for none
     */
    void setSink(GraphIngestSink graphSink) {
        this.graphSink = graphSink;
        LOGGER.info("Graph ingest sink {}", graphSink == null ? "disabled" : "enabled");
    }

    /**
     * Tells the graph about a document that is now published.
     *
     * <p>After publication, deliberately. A fact staged for a document whose write then failed
     * would be a fact about something the corpus does not contain, and nothing later would
     * retract it. Never throws: the document is already published and counted by the time this
     * runs, so failing here would report a loss that did not happen.</p>
     *
     * @param documentKey The source-qualified document key
     * @param doc         The fetched document, decoded with the encoding its source declared
     */
    void report(String documentKey, FetchedDocument doc) {
        GraphIngestSink sink = this.graphSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onDocumentPublished(documentKey, doc.getText());
        } catch (RuntimeException e) {
            LOGGER.warn("Graph sink failed for {}: {}", documentKey, e.toString());
        }
    }
}
