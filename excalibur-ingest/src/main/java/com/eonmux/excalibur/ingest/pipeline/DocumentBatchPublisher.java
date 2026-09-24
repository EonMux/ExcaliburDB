package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.VisibilityEpochClock;
import com.eonmux.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
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
 *   <li>Mark the document in flight ({@link #begin}), before the first chunk is written</li>
 *   <li>Write all chunks to storage (collected in a {@link DocumentBatchBuffer})</li>
 *   <li>Advance visibility epoch (single atomic operation)</li>
 *   <li>Register the document with the {@link DocumentVisibilityTracker} at that epoch, which
 *       releases the mark, and its metadata in the registry</li>
 * </ol>
 *
 * <h2>Why the document tracker and not the epoch alone</h2>
 * <p>Advancing the epoch publishes nothing by itself. A chunk is reachable by a query's tail scan
 * the moment it is written, and chunk ids are the document key and an index, so a new version is
 * written over the chunk ids of the one it replaces. What the atomicity filter excludes a
 * half-written document on is its entry in the {@link DocumentVisibilityTracker}: in flight from
 * {@link #begin} until {@link #publish}, and committed at the epoch publication advances to. The
 * ingestion pipeline registers through the same tracker, so a document is published by one
 * mechanism whichever path wrote it. Built without one, this class advances the epoch and records
 * the registry entry only, and a query can then see a document part-written.</p>
 *
 * <h2>Durability</h2>
 * <p>Publication does not sync anything, because by the time a buffer is sealed there is
 * nothing left to sync. Every chunk reaches the buffer through an
 * {@link OffsetAwareStorageWriter}, whose storage-engine implementation returns only after the
 * segment's write-ahead log has appended the record and forced it to the device
 * ({@code WriteLog.append}, called from the segment's put before the in-memory table changes).
 * Every segment the engine creates, recovers, splits into or compacts into has a write log
 * attached, so a chunk that made it into a sealed buffer is already durable, and the epoch
 * this class advances never publishes a write a crash could take back. The in-flight mark is
 * durable before {@link #begin} returns, so a crash between the two leaves the document hidden
 * rather than half-served; see {@link DocumentVisibilityTracker}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The epoch advance is atomic and the registry is concurrent;
 * the marks and publications of the same document are ordered by the caller, which makes them
 * under that document's lock.</p>
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

    private final VisibilityEpochClock clock;
    private final DocumentRegistry documentRegistry;

    /** Where documents are marked in flight and published, or null for no document tracking. */
    private final DocumentVisibilityTracker documents;

    /**
     * Creates a publisher that advances the epoch and records the registry entry, and tracks no
     * document's visibility.
     *
     * <p>A query can see a document this publisher writes part-written; see the class
     * documentation. Production wiring uses
     * {@link #DocumentBatchPublisher(DocumentVisibilityTracker)}.</p>
     *
     * @param visibilityTracker The node visibility tracker whose epoch publication advances
     * @param documentRegistry The document registry for metadata tracking
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(VisibilityTracker visibilityTracker,
                                  DocumentRegistry documentRegistry) {
        this.clock = VisibilityEpochClock.of(
            Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null"));
        this.documentRegistry = Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
        this.documents = null;
    }

    /**
     * Creates a publisher that publishes each document through the document visibility tracker
     * the ingestion pipeline publishes through.
     *
     * <p>Epochs are the tracker's, which production builds on the node's visibility clock, and the
     * registry is the one the tracker owns.</p>
     *
     * @param documents The document visibility tracker
     * @throws NullPointerException if documents is null
     */
    public DocumentBatchPublisher(DocumentVisibilityTracker documents) {
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.documentRegistry = documents.getRegistry();
        this.clock = new VisibilityEpochClock() {
            @Override
            public long currentEpoch() {
                return documents.currentEpoch();
            }

            @Override
            public long advanceEpoch() {
                return documents.advanceEpoch();
            }
        };
    }

    /**
     * Marks a document in flight, before the first of its chunks is written.
     *
     * <p>From here until {@link #publish} or {@link #abandon} the atomicity filter hides the
     * document, including a version of it published earlier: its chunks are about to be
     * overwritten in place. Returns only once the mark is durable. No-op without a document
     * tracker.</p>
     *
     * @param sourceId   The source the document belongs to
     * @param documentId The document
     * @param chunkIds   The chunk ids about to be written
     * @throws com.eonmux.excalibur.ingest.visibility.InFlightJournalException if the mark cannot
     *         be made durable, in which case nothing may be written
     */
    public void begin(String sourceId, String documentId, Collection<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (documents == null) {
            return;
        }
        String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
        documents.beginIngestion(documentKey, chunkIds.size());
        for (String chunkId : chunkIds) {
            documents.addPendingChunk(documentKey, chunkId);
        }
    }

    /**
     * Releases the mark of a document that will not be published, once nothing it wrote is left
     * that a query could mistake for a version of it.
     *
     * <p>No-op without a document tracker.</p>
     *
     * @param sourceId   The source the document belongs to
     * @param documentId The document
     */
    public void abandon(String sourceId, String documentId) {
        if (documents != null) {
            documents.abortIngestion(ChunkIdentifiers.documentKey(sourceId, documentId));
        }
    }

    /**
     * Stops tracking a document whose chunks have been removed, mark and published entry alike.
     *
     * <p>No-op without a document tracker.</p>
     *
     * @param sourceId   The source the document belonged to
     * @param documentId The document
     */
    public void forget(String sourceId, String documentId) {
        if (documents != null) {
            documents.removeDocument(ChunkIdentifiers.documentKey(sourceId, documentId));
        }
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
     *   <li>Register the document with the document tracker at that epoch, releasing its
     *       in-flight mark</li>
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
            // Nothing was written, so a mark begun for it hides nothing and must not outlive this.
            abandon(buffer.getSourceId(), buffer.getDocumentId());
            buffer.markPublished();
            return new PublicationResult(clock.currentEpoch(), 0);
        }

        String documentId = buffer.getDocumentId();
        int chunkCount = buffer.getChunkCount();

        LOGGER.debug("Publishing document batch: documentId={}, sourceId={}, chunks={}",
            documentId, buffer.getSourceId(), chunkCount);

        long epochBefore = clock.currentEpoch();

        try {
            long newEpoch = clock.advanceEpoch();
            LOGGER.debug("Visibility epoch advanced: {} -> {} for document: {}",
                epochBefore, newEpoch, documentId);

            if (documents != null) {
                documents.registerFromMetadata(metadata, newEpoch);
            }
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
        return clock.currentEpoch();
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
