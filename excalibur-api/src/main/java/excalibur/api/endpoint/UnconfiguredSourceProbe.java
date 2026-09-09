package excalibur.api.endpoint;

import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import io.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reports what a node without an ingestion pipeline can honestly say about a registered source.
 *
 * <h2>Why this exists</h2>
 * <p>A node starts in stub mode when it has no embedding endpoint to call - no API key, or a
 * pipeline that failed to wire - and {@code POST /v1/ingest} still accepts sources, because
 * registration is bookkeeping and does not need a model. What it must not do is describe that as an
 * ingestion. Nothing is chunked, embedded or indexed, and a status that counts the crawled files as
 * {@code entries_ingested} and settles on {@code idle} at {@code progress: 1.0} is indistinguishable
 * from a run that worked - which leaves the one configuration mistake that stops the product
 * working looking exactly like success.</p>
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

    /** What the status reports, and what an operator has to change to make it go away. */
    static final String REASON =
        "This node has no ingestion pipeline configured, so no document was chunked, embedded or "
        + "indexed. Set excalibur.llm.api-key (or EXCALIBUR_LLM_API_KEY) to an OpenAI-compatible "
        + "endpoint and restart; the startup log records why the pipeline was not wired.";

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
                status.setError("Source probe failed: " + e.getMessage());
                LOGGER.error("failed to probe source: {}", e.getMessage(), e);
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
