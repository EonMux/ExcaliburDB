package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.graph.GraphIngestSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tells the graph about documents ingestion has published, so facts can be extracted from them.
 *
 * <p>Optional throughout: a deployment that indexes text without extracting facts never sets a
 * sink, and every report is then a no-op.</p>
 *
 * <h2>One document's reports, in publication order</h2>
 * <p>A report runs after the document's lock is released, because extraction may call a model
 * and take seconds. Two versions of one document published close together can therefore reach
 * the sink in either order, and a replacement withdraws everything staged from the document
 * before staging its own facts - so a late report of the older version would withdraw the newer
 * version's facts and stage stale ones in their place. Each publication is therefore numbered
 * under the document's lock ({@link #published}), and its report ({@link #report}) runs under a
 * per-document lock of this reporter's and is skipped outright when a later version has been
 * published meanwhile: that version's own report replaces whatever this one would have
 * staged. Reports of different documents never wait for each other.</p>
 *
 * <h2>Every path that changes a document</h2>
 * <p>A document is published by its first ingestion, by a client posting it again, and by a
 * re-crawl that finds it new or changed; a re-crawl that finds it gone removes it. All of them
 * report through one reporter - the orchestrator hands its own to the reindexer - because the
 * ordering above holds only among changes one reporter has numbered: a re-crawl and a re-post of
 * the same document can publish moments apart, and two reporters would each take their own
 * version for the latest.</p>
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

    /** Numbers publications, so the latest version of a document can be told apart. */
    private final AtomicLong publications = new AtomicLong();

    /**
     * The documents with a report outstanding.
     *
     * <p>An entry lives only while some publication of its document has not been reported, so
     * the map is as large as the reports in flight rather than the corpus.</p>
     */
    private final ConcurrentHashMap<String, Reports> outstanding = new ConcurrentHashMap<>();

    /**
     * One document's outstanding reports.
     *
     * <p>A {@link ReentrantLock} rather than a monitor, because a report may block on a model
     * call and ingestion runs one virtual thread per document: a virtual thread blocking inside
     * {@code synchronized} pins its carrier.</p>
     */
    private static final class Reports {
        final ReentrantLock lock = new ReentrantLock();
        /** The number of the latest publication; written only inside {@code compute}. */
        volatile long latest;
        /** Publications not yet reported; read and written only inside {@code compute}. */
        int pending;
    }

    /** What happened to a document, which decides what its report asks of the sink. */
    private enum Change {
        /** A version was published and the node held none before it. */
        PUBLISHED,
        /** A version was published over one the node already held. */
        REPLACED,
        /** The document was removed, and no version of it remains. */
        REMOVED
    }

    /**
     * A change to a document waiting to be reported to the graph: a version published, or the
     * document removed.
     *
     * <p>Obtained from {@link #published} or {@link #removed} under the document's lock, and
     * handed to {@link #report} exactly once.</p>
     */
    static final class Publication {
        private final String sourceId;
        private final String documentKey;
        private final Change change;
        private final long number;
        private final Reports reports;

        private Publication(String sourceId, String documentKey, Change change, long number,
                            Reports reports) {
            this.sourceId = sourceId;
            this.documentKey = documentKey;
            this.change = change;
            this.number = number;
            this.reports = reports;
        }

        /** The source that ingested the document, which its key alone cannot name. */
        String sourceId() {
            return sourceId;
        }

        /** The source-qualified document key. */
        String documentKey() {
            return documentKey;
        }

        /** Whether this version replaced one the node already held. */
        boolean replacedEarlierVersion() {
            return change == Change.REPLACED;
        }

        /** Whether this records the document's removal rather than a version of it. */
        boolean removal() {
            return change == Change.REMOVED;
        }
    }

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
     * Records that a version of a document has been published, for a report to follow.
     *
     * <p>Called under the document's lock, which is what makes the numbering the order the
     * versions were published in. Every publication obtained here must be passed to
     * {@link #report}, which releases it.</p>
     *
     * @param sourceId               The source that ingested the document
     * @param documentKey            The source-qualified document key
     * @param replacedEarlierVersion Whether the node already held a version of the document
     * @return The publication to report
     */
    Publication published(String sourceId, String documentKey, boolean replacedEarlierVersion) {
        return numbered(sourceId, documentKey,
            replacedEarlierVersion ? Change.REPLACED : Change.PUBLISHED);
    }

    /**
     * Records that a document has been removed, for a report to follow.
     *
     * <p>Called under the document's lock, like {@link #published}, and numbered in the same
     * sequence: the removal is the latest thing to have happened to the document until a version
     * is published over it, and a report of an earlier version still in flight is then skipped
     * rather than staging facts about text the corpus no longer holds. The publication obtained
     * here must be passed to {@link #report}.</p>
     *
     * @param sourceId    The source that ingested the document
     * @param documentKey The source-qualified document key
     * @return The removal to report
     */
    Publication removed(String sourceId, String documentKey) {
        return numbered(sourceId, documentKey, Change.REMOVED);
    }

    /** Numbers a change to a document and holds it outstanding until it is reported. */
    private Publication numbered(String sourceId, String documentKey, Change change) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        long number = publications.incrementAndGet();
        Reports reports = outstanding.compute(documentKey, (key, existing) -> {
            Reports held = existing == null ? new Reports() : existing;
            held.pending++;
            held.latest = number;
            return held;
        });
        return new Publication(sourceId, documentKey, change, number, reports);
    }

    /**
     * Tells the graph about a document that is now published.
     *
     * <p>After publication, deliberately. A fact staged for a document whose write then failed
     * would be a fact about something the corpus does not contain, and nothing later would
     * retract it. Never throws: the document is already published and counted by the time this
     * runs, so failing here would report a loss that did not happen.</p>
     *
     * <p>A version a later one has already superseded is not reported at all; see the class
     * documentation.</p>
     *
     * <p>Handed the text that was chunked rather than the fetched bytes, so facts are extracted
     * from exactly what was indexed and a document is never decoded twice - which for a PDF
     * would be a second conversion, OCR and all.</p>
     *
     * @param publication     What {@link #published} or {@link #removed} returned
     * @param text            The version's text as it was chunked; null for a removal, which
     *                        has no content
     * @param statedAtMillis  The document's own date, which its facts are ordered by: its
     *                        modification time, or 0 when unknown
     * @throws IllegalArgumentException if a published version comes without its text
     */
    void report(Publication publication, String text, long statedAtMillis) {
        Objects.requireNonNull(publication, "publication must not be null");
        if (text == null && !publication.removal()) {
            throw new IllegalArgumentException("a published version of "
                + publication.documentKey + " must be reported with its text");
        }
        Reports reports = publication.reports;
        reports.lock.lock();
        try {
            if (publication.number == reports.latest) {
                deliver(publication, text, statedAtMillis);
            }
        } finally {
            reports.lock.unlock();
            outstanding.computeIfPresent(publication.documentKey,
                (key, held) -> --held.pending == 0 ? null : held);
        }
    }

    /**
     * Hands one publication to the sink.
     *
     * @param publication    The version to report
     * @param text           Its text as it was chunked, or null for a removal
     * @param statedAtMillis Its own date, or 0 when unknown
     */
    private void deliver(Publication publication, String text, long statedAtMillis) {
        GraphIngestSink sink = this.graphSink;
        if (sink == null) {
            return;
        }
        String sourceId = publication.sourceId;
        String documentKey = publication.documentKey;
        try {
            // With the document's own date, which is what its facts are ordered by when a later
            // document gives a single-valued relation a different value. The text is the one
            // that was chunked, markup stripped, so facts are extracted from what the document
            // says rather than from its tags.
            switch (publication.change) {
                case PUBLISHED -> sink.onDocumentPublished(sourceId, documentKey,
                    text, statedAtMillis);
                case REPLACED -> sink.onDocumentReplaced(sourceId, documentKey,
                    text, statedAtMillis);
                case REMOVED -> sink.onDocumentRemoved(sourceId, documentKey);
            }
        } catch (RuntimeException e) {
            long failed = failures.incrementAndGet();
            if (publication.removal()) {
                // A sink that stages withdraws through something that owes what it cannot
                // withdraw, and absorbs the failure there. One that throws instead has broken its
                // contract, and nothing here can put the obligation anywhere: the document has no
                // registry entry left for an erasure to name, and is not reported again.
                LOGGER.error("The graph sink threw withdrawing the facts of {}, which is removed; "
                    + "nothing will ask for them again, so they stay staged for every later build "
                    + "to publish ({} such report(s) so far): {}", documentKey, failed,
                    e.toString(), e);
            } else {
                LOGGER.error("The graph did not take the facts of {}, which is published and will "
                    + "not be extracted again unless it changes ({} such report(s) so far): {}",
                    documentKey, failed, e.toString(), e);
            }
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
