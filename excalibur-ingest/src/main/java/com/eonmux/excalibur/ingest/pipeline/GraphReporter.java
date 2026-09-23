package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.graph.GraphIngestSink;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

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
     * Published documents whose report to the graph threw.
     *
     * <p>Counted because the document is published regardless and nothing else records that
     * its facts are missing: a sink refusing every report - a graph closed under an ingestion
     * run that outlived its drain - is otherwise a graph that quietly stops growing.</p>
     */
    private final AtomicLong failures = new AtomicLong();

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
            // With the document's own date, which is what its facts are ordered by when a later
            // document gives a single-valued relation a different value.
            sink.onDocumentPublished(documentKey, doc.getText(), doc.getLastModifiedMillis());
        } catch (RuntimeException e) {
            long failed = failures.incrementAndGet();
            LOGGER.error("The graph did not take the facts of {}, which is published and will not "
                + "be extracted again unless it changes ({} such document(s) so far): {}",
                documentKey, failed, e.toString(), e);
        }
    }

    /**
     * How many published documents the graph refused.
     *
     * @return The count since this reporter was created
     */
    long failures() {
        return failures.get();
    }

    /**
     * Forces what the sink has staged to the device, before the caller records the documents
     * it came from as ingested.
     *
     * <p>Never throws, for the same reason {@link #report} does not: the registry write this
     * precedes has to happen regardless, since skipping it would make every document of the run
     * look new to the next crawl. A failure is logged, and the facts are then only as durable as
     * the page cache.</p>
     *
     * @return true when there was nothing to force or it was forced
     */
    boolean sync() {
        GraphIngestSink sink = this.graphSink;
        if (sink == null) {
            return true;
        }
        try {
            sink.sync();
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Staged graph facts could not be forced to disk before the document "
                + "registry was written; a crash or power loss before they are loses them, and "
                + "their documents will not be extracted again: {}", e.toString(), e);
            return false;
        }
    }
}
