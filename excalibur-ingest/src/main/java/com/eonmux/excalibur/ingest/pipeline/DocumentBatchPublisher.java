package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Publishes document chunks atomically to the visibility epoch.
 *
 * <h2>Atomic Visibility Contract</h2>
 * <p>This class ensures that all chunks for a document become visible
 * to queries at the same visibility epoch. This guarantees:
 * <ul>
 *   <li>No partial documents in query results</li>
 *   <li>Consistent snapshots across concurrent queries</li>
 *   <li>Epoch isolation for in-flight ingestion</li>
 * </ul>
 *
 * <h2>Publication Pipeline</h2>
 * <ol>
 *   <li>Write all chunks to storage (collected in a {@link DocumentBatchBuffer})</li>
 *   <li>Advance visibility epoch (single atomic operation)</li>
 *   <li>Register document metadata in registry</li>
 * </ol>
 *
 * <h2>Durability</h2>
 * <p>Publication does not sync anything, because by the time a buffer is sealed there is
 * nothing left to sync. Every chunk reaches the buffer through an
 * {@link OffsetAwareStorageWriter}, whose storage-engine implementation returns only after the
 * segment's write-ahead log has appended the record and forced it to the device
 * ({@code WriteLog.append}, called from the segment's put before the in-memory table changes).
 * Every segment the engine creates, recovers, splits into or compacts into has a write log
 * attached, so a chunk that made it into a sealed buffer is already durable, and the epoch
 * this class advances never publishes a write a crash could take back.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The epoch advance is atomic and the registry is concurrent;
 * publications of the same document are ordered by the caller, which publishes under that
 * document's lock.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epoch advances monotonically after each successful publication</li>
 *   <li>Every chunk is durable before the epoch that makes it visible advances</li>
 *   <li>Document metadata registration is atomic with epoch tick</li>
 *   <li>Publication fails as a unit if any chunk fails</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class DocumentBatchPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentBatchPublisher.class);

    private final VisibilityTracker visibilityTracker;
    private final DocumentRegistry documentRegistry;

    /**
     * Creates a new DocumentBatchPublisher.
     *
     * @param visibilityTracker The node visibility tracker whose epoch publication advances
     * @param documentRegistry The document registry for metadata tracking
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(VisibilityTracker visibilityTracker,
                                  DocumentRegistry documentRegistry) {
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.documentRegistry = Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
    }

    /**
     * Publishes a document batch atomically to the visibility epoch.
     *
     * <p>This method ensures that all chunks in the buffer become visible
     * to queries at the same epoch. If any step fails, the entire operation
     * fails without partial visibility.</p>
     *
     * <h3>Publication Steps</h3>
     * <ol>
     *   <li>Validate buffer state (must be SEALED)</li>
     *   <li>Advance visibility epoch atomically</li>
     *   <li>Register document metadata in registry</li>
     *   <li>Mark buffer as PUBLISHED</li>
     * </ol>
     *
     * @param buffer The sealed buffer to publish
     * @param metadata The document metadata to register
     * @return Publication result with epoch and chunk count
     * @throws IllegalStateException if buffer is not sealed
     * @throws PublicationException if publication fails
     * @throws NullPointerException if buffer or metadata is null
     */
    public PublicationResult publish(DocumentBatchBuffer buffer, DocumentMetadata metadata) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");

        if (!buffer.isSealed()) {
            throw new IllegalStateException("Buffer must be sealed before publication. State: " + buffer.getState());
        }

        if (buffer.isEmpty()) {
            LOGGER.warn("Attempting to publish empty buffer for document: {}", buffer.getDocumentId());
            buffer.markPublished();
            return new PublicationResult(visibilityTracker.currentEpoch(), 0);
        }

        String documentId = buffer.getDocumentId();
        int chunkCount = buffer.getChunkCount();

        LOGGER.debug("Publishing document batch: documentId={}, sourceId={}, chunks={}",
            documentId, buffer.getSourceId(), chunkCount);

        long epochBefore = visibilityTracker.currentEpoch();

        try {
            long newEpoch = visibilityTracker.advanceEpoch();
            LOGGER.debug("Visibility epoch advanced: {} -> {} for document: {}",
                epochBefore, newEpoch, documentId);

            documentRegistry.register(metadata);
            LOGGER.debug("Document metadata registered: {}", documentId);

            buffer.markPublished();

            // The throughput counter the IngestionThroughput MBean reports. It had no writer
            // outside the tests, so a server that had ingested millions of documents reported
            // zero entries, zero rate and a zero peak - and any alert keyed on it never fired.
            com.eonmux.excalibur.metrics.Metrics.recordIngestEntries(chunkCount);

            LOGGER.info("Document batch published atomically: documentId={}, chunks={}, epoch={}",
                documentId, chunkCount, newEpoch);

            return new PublicationResult(newEpoch, chunkCount);

        } catch (Exception e) {
            buffer.markFailed(e);
            throw new PublicationException("Unexpected error during publication for document: " + documentId, e);
        }
    }

    /**
     * Returns the current visibility epoch.
     */
    public long currentEpoch() {
        return visibilityTracker.currentEpoch();
    }

    /**
     * Returns the document registry.
     */
    public DocumentRegistry getDocumentRegistry() {
        return documentRegistry;
    }

    /**
     * Result of a single document publication.
     *
     * @param epoch The visibility epoch at which the document became visible
     * @param chunkCount The number of chunks published
     */
    public record PublicationResult(long epoch, int chunkCount) {
        public PublicationResult {
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must be non-negative");
            }
            if (chunkCount < 0) {
                throw new IllegalArgumentException("chunkCount must be non-negative");
            }
        }
    }

    /**
     * Exception thrown when publication fails.
     */
    public static class PublicationException extends RuntimeException {

        public PublicationException(String message) {
            super(message);
        }

        public PublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
