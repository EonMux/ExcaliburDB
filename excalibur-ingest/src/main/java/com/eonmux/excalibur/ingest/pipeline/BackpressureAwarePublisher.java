package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator for DocumentBatchPublisher that checks HNSW queue backpressure.
 *
 * <p>This class wraps a {@link DocumentBatchPublisher} and ensures that
 * publication operations respect HNSW queue backpressure. When the HNSW
 * integration queue exceeds the configured depth limit, this publisher
 * blocks until the queue drains below the release threshold.</p>
 *
 * <h2>Backpressure Mechanism</h2>
 * <p>When the HNSW integration worker detects queue overflow (entries pending
 * integration exceed the configured limit), it sets a backpressure flag.
 * This publisher checks that flag before each publication and blocks
 * if necessary:</p>
 * <ol>
 *   <li>Check if backpressure is active via {@link IndexOrchestrator#isBackpressured()}</li>
 *   <li>If active, wait for release via {@link IndexOrchestrator#awaitBackpressureRelease(long)}</li>
 *   <li>Proceed with publication once backpressure is released</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong> It delegates to the
 * underlying DocumentBatchPublisher's thread-safety guarantees.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Default backpressure timeout: 30 seconds</li>
 *   <li>Backpressure releases when queue depth falls below 50% of limit</li>
 *   <li>Virtual thread friendly parking (no busy-waiting)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);
 * DocumentBatchPublisher basePublisher = new DocumentBatchPublisher(...);
 * BackpressureAwarePublisher publisher = new BackpressureAwarePublisher(
 *     orchestrator, basePublisher, 30000);
 *
 * // Will block if HNSW queue is full
 * PublicationResult result = publisher.publish(buffer, metadata);
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 8.0
 * @see IndexOrchestrator#isBackpressured()
 * @see IndexOrchestrator#awaitBackpressureRelease(long)
 * @see DocumentBatchPublisher
 */
public class BackpressureAwarePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackpressureAwarePublisher.class);

    /**
     * Default backpressure timeout in milliseconds.
     */
    public static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000;

    private final IndexOrchestrator orchestrator;
    private final DocumentBatchPublisher delegate;
    private final long backpressureTimeoutMs;

    /**
     * Creates a new BackpressureAwarePublisher with default timeout.
     *
     * @param orchestrator The index orchestrator for backpressure checking
     * @param delegate     The underlying publisher to delegate to
     * @throws NullPointerException if orchestrator or delegate is null
     */
    public BackpressureAwarePublisher(IndexOrchestrator orchestrator, DocumentBatchPublisher delegate) {
        this(orchestrator, delegate, DEFAULT_BACKPRESSURE_TIMEOUT_MS);
    }

    /**
     * Creates a new BackpressureAwarePublisher with custom timeout.
     *
     * @param orchestrator          The index orchestrator for backpressure checking
     * @param delegate              The underlying publisher to delegate to
     * @param backpressureTimeoutMs Maximum time to wait for backpressure release (ms)
     * @throws NullPointerException     if orchestrator or delegate is null
     * @throws IllegalArgumentException if backpressureTimeoutMs is negative
     */
    public BackpressureAwarePublisher(
            IndexOrchestrator orchestrator,
            DocumentBatchPublisher delegate,
            long backpressureTimeoutMs) {
        if (orchestrator == null) {
            throw new NullPointerException("orchestrator must not be null");
        }
        if (delegate == null) {
            throw new NullPointerException("delegate must not be null");
        }
        if (backpressureTimeoutMs < 0) {
            throw new IllegalArgumentException(
                "backpressureTimeoutMs must be non-negative, got: " + backpressureTimeoutMs);
        }

        this.orchestrator = orchestrator;
        this.delegate = delegate;
        this.backpressureTimeoutMs = backpressureTimeoutMs;
    }

    /**
     * Publishes a document batch atomically, respecting backpressure.
     *
     * <p>This method first checks if the HNSW integration queue is experiencing
     * backpressure. If so, it blocks until the queue drains below the release
     * threshold or the timeout expires. Once backpressure is cleared (or was
     * not active), it delegates to the underlying publisher.</p>
     *
     * @param buffer   The sealed buffer to publish
     * @param metadata The document metadata to register
     * @return Publication result with epoch and chunk count
     * @throws DocumentBatchPublisher.PublicationException if backpressure timeout is exceeded
     *         or publication fails
     * @throws IllegalStateException if buffer is not sealed
     * @throws NullPointerException if buffer or metadata is null
     */
    public DocumentBatchPublisher.PublicationResult publish(
            DocumentBatchBuffer buffer, DocumentMetadata metadata) {

        // Check and wait for backpressure before publishing
        if (orchestrator.isBackpressured()) {
            LOGGER.debug("Backpressure detected before publication, waiting up to {}ms",
                backpressureTimeoutMs);

            long startTime = System.currentTimeMillis();
            boolean released = orchestrator.awaitBackpressureRelease(backpressureTimeoutMs);
            long waitTime = System.currentTimeMillis() - startTime;

            if (!released) {
                long queueDepth = orchestrator.getHnswQueueDepth();
                int queueLimit = orchestrator.getHnswQueueDepthLimit();
                throw new DocumentBatchPublisher.PublicationException(
                    String.format("HNSW queue backpressure timeout after %dms. " +
                        "Queue depth: %d, limit: %d",
                        waitTime, queueDepth, queueLimit));
            }

            LOGGER.info("Backpressure released after {}ms, proceeding with publication", waitTime);
        }

        // Delegate to underlying publisher
        return delegate.publish(buffer, metadata);
    }

    /**
     * Publishes multiple document batches atomically, respecting backpressure.
     *
     * <p>This method checks backpressure once before the bulk operation.
     * All buffers are published in a single epoch once backpressure clears.</p>
     *
     * @param buffers       The sealed buffers to publish
     * @param metadataList  The corresponding document metadata
     * @return Bulk publication result with total chunks and epoch
     * @throws DocumentBatchPublisher.PublicationException if backpressure timeout is exceeded
     *         or publication fails
     * @throws IllegalStateException if any buffer is not sealed
     */
    public DocumentBatchPublisher.BulkPublicationResult publishBulk(
            java.util.List<DocumentBatchBuffer> buffers,
            java.util.List<DocumentMetadata> metadataList) {

        // Check and wait for backpressure before bulk publishing
        if (orchestrator.isBackpressured()) {
            LOGGER.debug("Backpressure detected before bulk publication, waiting up to {}ms",
                backpressureTimeoutMs);

            long startTime = System.currentTimeMillis();
            boolean released = orchestrator.awaitBackpressureRelease(backpressureTimeoutMs);
            long waitTime = System.currentTimeMillis() - startTime;

            if (!released) {
                long queueDepth = orchestrator.getHnswQueueDepth();
                int queueLimit = orchestrator.getHnswQueueDepthLimit();
                throw new DocumentBatchPublisher.PublicationException(
                    String.format("HNSW queue backpressure timeout after %dms during bulk publish. " +
                        "Queue depth: %d, limit: %d",
                        waitTime, queueDepth, queueLimit));
            }

            LOGGER.info("Backpressure released after {}ms, proceeding with bulk publication", waitTime);
        }

        // Delegate to underlying publisher
        return delegate.publishBulk(buffers, metadataList);
    }

    /**
     * Returns the underlying publisher delegate.
     *
     * <p>Use with caution - direct access bypasses backpressure checking.</p>
     *
     * @return The underlying DocumentBatchPublisher
     */
    public DocumentBatchPublisher getDelegate() {
        return delegate;
    }

    /**
     * Returns the index orchestrator used for backpressure checking.
     *
     * @return The IndexOrchestrator
     */
    public IndexOrchestrator getOrchestrator() {
        return orchestrator;
    }

    /**
     * Returns the configured backpressure timeout in milliseconds.
     *
     * @return Backpressure timeout
     */
    public long getBackpressureTimeoutMs() {
        return backpressureTimeoutMs;
    }

    /**
     * Returns the current visibility epoch from the underlying publisher.
     */
    public long currentEpoch() {
        return delegate.currentEpoch();
    }

    @Override
    public String toString() {
        return String.format("BackpressureAwarePublisher{timeoutMs=%d, queueDepth=%d, limit=%d}",
            backpressureTimeoutMs,
            orchestrator.getHnswQueueDepth(),
            orchestrator.getHnswQueueDepthLimit());
    }
}
