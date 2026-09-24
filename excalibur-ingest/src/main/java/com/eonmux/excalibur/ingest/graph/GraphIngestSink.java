package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import java.io.IOException;
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
 * <h2>The source a document came from</h2>
 * <p>Every call names the source that ingested the document as well as its key. The key is the
 * source id and the document id joined by a colon, and a source id may itself contain colons, so
 * the key alone cannot say which source it came from: {@code team:wiki:home.md} is document
 * {@code wiki:home.md} of source {@code team} as readily as document {@code home.md} of source
 * {@code team:wiki}. A sink that decides anything by source - which tenant's graph a document's
 * facts belong in - is handed the source rather than left to guess it from the key.</p>
 *
 * <h2>Replacing and removing a version</h2>
 * <p>A document published over a version this node already held is reported through
 * {@link #onDocumentReplaced} rather than {@link #onDocumentPublished}. Everything staged from a
 * document carries the same source id whatever version it was extracted from, so the earlier
 * version's facts and snippets stay staged beside the new ones unless they are withdrawn - and
 * the next graph build then publishes what the document used to say as though it still said it.
 * Two methods rather than a flag, so a sink that stages anything has to say what replacing a
 * version means to it. A document removed outright - its source no longer lists it - is reported
 * through {@link #onDocumentRemoved}, which withdraws the same way and stages nothing.</p>
 *
 * <p>Each withdrawal is asked for once. A removed document is never reported again, nor is an
 * unchanged one, and a removed document has no registry entry left for an erasure request to
 * resolve - so a sink that cannot withdraw when asked has to keep the withdrawal owed until it
 * can, as {@link StagedWithdrawals} does, rather than log it and move on.</p>
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
     * <p>The document's own date is what its facts are recorded as stated on: a filesystem
     * source's modification time, an HTTP source's {@code Last-Modified}. It is what orders two
     * documents' conflicting values of a single-valued relation, so a re-crawl that finds a
     * page changed supersedes what the page used to say, and a batch ingested on one day is
     * ordered by when its documents were written rather than by the order it was crawled in.</p>
     *
     * @param sourceId           The source that ingested the document
     * @param documentKey        The source-qualified document key
     * @param text               The document's text
     * @param lastModifiedMillis When the document was last modified, in epoch milliseconds, or
     *                           0 when its source does not say; its facts are then recorded as
     *                           stated on the day they are staged
     */
    void onDocumentPublished(String sourceId, String documentKey, String text,
                             long lastModifiedMillis);

    /**
     * Records a published document that replaced a version this node already held.
     *
     * <p>Withdraws whatever was staged from the earlier version, then records this one as
     * {@link #onDocumentPublished} would. The earlier version's facts are withdrawn whether or not
     * any are found in the new one: they describe text the corpus no longer holds.</p>
     *
     * <p>Must not throw, for the reason {@link #onDocumentPublished} must not. The caller calls
     * this for one document at a time and in the order its versions were published, so the
     * withdrawal cannot remove a later version's facts.</p>
     *
     * @param sourceId           The source that ingested the document
     * @param documentKey        The source-qualified document key
     * @param text               The new version's text
     * @param lastModifiedMillis When the new version was last modified, or 0 when unknown
     */
    void onDocumentReplaced(String sourceId, String documentKey, String text,
                            long lastModifiedMillis);

    /**
     * Withdraws whatever was staged from a document this node no longer holds.
     *
     * <p>Called when a re-crawl finds that a source has stopped listing a document and removes
     * it. What was extracted from it describes text the corpus no longer contains, exactly as an
     * earlier version's facts do once it is replaced, so it is withdrawn the same way; only a
     * new version is missing, and so nothing is staged in its place.</p>
     *
     * <p>Must not throw, for the reason {@link #onDocumentPublished} must not: the document is
     * already removed by the time this runs. The caller orders it with the document's
     * publications, so a removal cannot withdraw a later version's facts.</p>
     *
     * @param sourceId    The source that ingested the document
     * @param documentKey The source-qualified document key
     */
    void onDocumentRemoved(String sourceId, String documentKey);

    /**
     * Records a published document whose date is unknown.
     *
     * @param sourceId    The source that ingested the document
     * @param documentKey The source-qualified document key
     * @param text        The document's text
     */
    default void onDocumentPublished(String sourceId, String documentKey, String text) {
        onDocumentPublished(sourceId, documentKey, text, 0L);
    }

    /**
     * Forces what this sink has staged so far to the device.
     *
     * <p>Called before ingestion writes the document registry, which is what stops a document
     * being extracted again: a registry that reaches the disk ahead of the facts extracted from
     * the documents it lists records those facts as taken when a crash or a power loss has lost
     * them. Unlike {@link #onDocumentPublished} this may throw - the caller decides what a
     * failure costs - and a sink that holds nothing in memory does nothing, which is the
     * default.</p>
     *
     * @throws IOException if what was staged could not be forced
     */
    default void sync() throws IOException {
    }

    /** A sink for a deployment that indexes text and does not build a graph. */
    static GraphIngestSink none() {
        return NoGraph.INSTANCE;
    }

    /** Stages nothing, so a replaced version has nothing to withdraw. */
    enum NoGraph implements GraphIngestSink {
        /** The one instance. */
        INSTANCE;

        @Override
        public void onDocumentPublished(String sourceId, String documentKey, String text,
                                        long lastModifiedMillis) {
            // No graph is built, so nothing is staged.
        }

        @Override
        public void onDocumentReplaced(String sourceId, String documentKey, String text,
                                       long lastModifiedMillis) {
            // Nothing was staged from the earlier version, so there is nothing to withdraw.
        }

        @Override
        public void onDocumentRemoved(String sourceId, String documentKey) {
            // Nothing was staged from the document, so there is nothing to withdraw.
        }
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
