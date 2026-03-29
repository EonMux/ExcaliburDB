package excalibur.api.endpoint;

import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import io.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Background worker for simulating ingestion status updates in stub mode.
 *
 * <p>IngestionWorker is used when the full ingestion pipeline (IngestionOrchestrator)
 * is not configured. It performs a lightweight crawl to count documents and updates
 * the status registry with progress information.</p>
 *
 * <h2>Usage</h2>
 * <p>When a source is registered via POST /v1/ingest and no IngestionOrchestrator
 * is configured, this worker runs in a background virtual thread to:</p>
 * <ol>
 *   <li>Crawl the source to count documents</li>
 *   <li>Report progress through the status registry</li>
 *   <li>Mark the source as idle when complete</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Progress values are clamped to [0.0, 1.0]</li>
 *   <li>Entry counts are non-negative</li>
 *   <li>State transitions follow: idle → crawling → embedding → indexing → idle</li>
 *   <li>Errors are captured and reported</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. Each instance should be used by a single thread.
 * However, the status updates are thread-safe via the IngestionStatus class.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class IngestionWorker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionWorker.class);

    private final Source source;
    private final IngestStatusEndpoint.IngestionStatusRegistry statusRegistry;
    private final AtomicBoolean cancelled;

    /**
     * Creates a new IngestionWorker.
     *
     * @param source The source to crawl (must not be null)
     * @param statusRegistry The status registry for progress updates (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public IngestionWorker(Source source, IngestStatusEndpoint.IngestionStatusRegistry statusRegistry) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "StatusRegistry cannot be null");
        this.cancelled = new AtomicBoolean(false);
    }

    /**
     * Cancels the ingestion worker.
     *
     * <p>After cancellation, the worker will complete its current phase
     * and then stop, setting an error status.</p>
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Checks if the worker has been cancelled.
     *
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void run() {
        String sourceId = source.getSourceId();
        IngestStatusEndpoint.IngestionStatus status = statusRegistry.getOrCreate(sourceId);

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {

            LOGGER.info("Starting stub ingestion worker");

            try {
                // Phase 1: Crawling - discover document count
                StructuredLoggingHelper.setStage("crawling");
                status.update("crawling", 0.0, 0, 0);
                LOGGER.debug("Phase 1: Crawling source");

                if (checkCancelled(status)) return;

                long documentCount = countDocuments();

                if (documentCount == 0) {
                    StructuredLoggingHelper.setStage("idle");
                    status.update("idle", 1.0, 0, 0);
                    LOGGER.info("No documents found, ingestion complete");
                    return;
                }

                LOGGER.info("Discovered {} documents", documentCount);

                if (checkCancelled(status)) return;

                // Phase 2: Embedding simulation
                // In stub mode, we just simulate progress since we don't have an LLM client
                StructuredLoggingHelper.setStage("embedding");
                status.update("embedding", 0.0, 0, documentCount);
                LOGGER.debug("Phase 2: Simulating embedding");

                long processedCount = 0;
                long batchSize = Math.max(1, documentCount / 10); // Update progress every 10%

                for (long i = 0; i < documentCount && !cancelled.get(); i++) {
                    processedCount++;

                    // Update progress periodically
                    if (processedCount % batchSize == 0 || processedCount == documentCount) {
                        double progress = (double) processedCount / documentCount;
                        status.update("embedding", progress, processedCount, documentCount - processedCount);
                    }

                    // Small delay to simulate work (only in stub mode for demonstration)
                    if (documentCount < 1000) {
                        Thread.sleep(10);
                    }
                }

                if (checkCancelled(status)) return;

                // Phase 3: Indexing simulation
                StructuredLoggingHelper.setStage("indexing");
                status.update("indexing", 0.9, documentCount, 0);
                LOGGER.debug("Phase 3: Simulating indexing");

                // Brief pause to show indexing state
                Thread.sleep(100);

                if (checkCancelled(status)) return;

                // Complete
                StructuredLoggingHelper.setStage("idle");
                status.update("idle", 1.0, documentCount, 0);
                LOGGER.info("Stub ingestion complete: {} documents", documentCount);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status.setError("Ingestion interrupted");
                LOGGER.info("Ingestion interrupted");

            } catch (SourceException e) {
                String error = "Source error: " + e.getMessage();
                status.setError(error);
                LOGGER.error("{}", error, e);

            } catch (Exception e) {
                String error = "Ingestion failed: " + e.getMessage();
                status.setError(error);
                LOGGER.error("{}", error, e);

            } finally {
                // Ensure source is closed
                try {
                    source.close();
                } catch (IOException e) {
                    LOGGER.debug("Error closing source: {}", e.getMessage());
                }
            }
        }
        // MDC context is automatically cleared when ctx.close() is called
    }

    /**
     * Counts the number of documents in the source.
     *
     * @return The document count
     * @throws SourceException if crawling fails
     */
    private long countDocuments() throws SourceException {
        try (Stream<String> crawlStream = source.crawl()) {
            return crawlStream.count();
        }
    }

    /**
     * Checks if the worker has been cancelled and sets error status if so.
     *
     * @param status The current status
     * @return true if cancelled
     */
    private boolean checkCancelled(IngestStatusEndpoint.IngestionStatus status) {
        if (cancelled.get()) {
            status.setError("Ingestion cancelled");
            LOGGER.info("[{}] Ingestion cancelled by request", source.getSourceId());
            return true;
        }
        return false;
    }
}
