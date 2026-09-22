package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reports what a node without an ingestion pipeline can honestly say about a registered source.
 *
 * <h2>Why this exists</h2>
 * <p>A node reaches this state when its ingestion pipeline failed to wire at startup, and
 * {@code POST /v1/ingest} still accepts sources, because registration is bookkeeping that does not
 * need a pipeline. What it must not do is describe that as an ingestion. Nothing is chunked, stored
 * or indexed, and a status that counts the crawled files as {@code entries_ingested} and settles on
 * {@code idle} at {@code progress: 1.0} is indistinguishable from a run that worked - which leaves
 * a broken node looking exactly like a healthy one.</p>
 *
 * <p>A missing API key is not this state. That node has a pipeline: documents are chunked, stored
 * and term-indexed, and only the vector signal is absent.</p>
 *
 * <h2>Why it still crawls</h2>
 * <p>The crawl is the part that is real. It resolves the root, applies the extension filters and
 * counts what is there, which separates "this node cannot ingest" from "this path is wrong" - two
 * problems with different fixes that would otherwise arrive as the same silence. The count becomes
 * {@code entries_pending}: those documents exist, and they are waiting on configuration.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe; one instance per run. The status it writes to is.</p>
 *
 * @author ExcaliburDB
 */
public final class UnconfiguredSourceProbe implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnconfiguredSourceProbe.class);

    /** What the status reports, and where an operator finds the reason for it. */
    static final String REASON =
        "This node has no ingestion pipeline, so no document was chunked, stored or indexed. The "
        + "pipeline failed to wire at startup: the startup log records why, under \"Failed to "
        + "configure ingestion pipeline\". Fix that and restart.";

    private final Source source;
    private final IngestStatusEndpoint.IngestionStatus status;

    /**
     * Creates a probe for one registered source.
     *
     * <p>Takes the status entry rather than the registry holding it, because the registry has
     * already created that entry by the time a probe is started. Looking it up again here means
     * looking it up on a background thread, at a moment the caller no longer controls - and
     * {@code getOrCreate} on a source that has since been removed puts the entry back, in a
     * registry that will never be asked to remove it again. That leaves {@code /v1/ingest/status}
     * reporting a source no request can name, for the life of the process.</p>
     *
     * <p>Writing to a detached entry instead is harmless: the object is no longer the one the
     * endpoint reads, so a probe that finishes after its source was removed reports nothing.</p>
     *
     * @param source The source to crawl
     * @param status The status entry to report the outcome on
     */
    public UnconfiguredSourceProbe(Source source,
                                   IngestStatusEndpoint.IngestionStatus status) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.status = Objects.requireNonNull(status, "Status cannot be null");
    }

    @Override
    public void run() {
        String sourceId = source.getSourceId();

        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {
            try {
                // Crawling is reported while it runs because a large root takes real time, and a
                // status stuck at the pre-crawl value would read as a hung request.
                StructuredLoggingHelper.setStage("crawling");
                status.update("crawling", 0.0, 0, 0);

                long discovered = countDocuments();

                StructuredLoggingHelper.setStage(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED);
                status.setUnconfigured(REASON, discovered);
                LOGGER.warn("source is reachable and holds {} documents, but this node has no "
                    + "ingestion pipeline - nothing was indexed", discovered);

            } catch (SourceException e) {
                // A different failure with a different fix: the source itself cannot be read.
                status.setError("Source error: " + e.getMessage());
                LOGGER.error("failed to crawl source: {}", e.getMessage(), e);

            } catch (RuntimeException e) {
                // Unlike the SourceException above, this is not a fact about the caller's own
                // source: it is whatever went wrong inside the node, and its message names
                // internals a readback of /v1/ingest/status has no business carrying.
                status.setError("Source probe failed (" + e.getClass().getSimpleName()
                    + "); see the node's log for the reason");
                LOGGER.error("failed to probe source", e);
            }
            // The Source is owned by the registry that published it and is reused by every later
            // reindex of the same source. Closing it here is what leaves the registry handing out
            // an already-closed crawler.
        }
    }

    private long countDocuments() throws SourceException {
        try (Stream<String> crawl = source.crawl()) {
            return crawl.count();
        }
    }
}
