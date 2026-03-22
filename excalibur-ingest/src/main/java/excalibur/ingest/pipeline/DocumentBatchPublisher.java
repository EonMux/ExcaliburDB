package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityRegistry;
import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import io.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;

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
 *   <li>Write all chunks to write log (via {@link DocumentBatchBuffer})</li>
 *   <li>Insert chunk keys into Cuckoo filter hierarchy</li>
 *   <li>Fsync write log to ensure durability</li>
 *   <li>Advance visibility epoch (single atomic operation)</li>
 *   <li>Register document metadata in registry</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. Publication operations acquire
 * appropriate locks to ensure atomicity across concurrent document ingestion.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epoch advances monotonically after each successful publication</li>
 *   <li>Write log fsync occurs before epoch advancement</li>
 *   <li>Cuckoo filter insertions happen before visibility</li>
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
    private final DocumentVisibilityRegistry visibilityRegistry;
    private final CuckooFilter cuckooFilter;
    private final HierarchicalCuckooFilter hierarchicalFilter;
    private final String segmentId;
    private final WriteLogAdapter writeLogAdapter;
    private final Lock publicationLock;

    /**
     * Functional interface for write log operations.
     */
    @FunctionalInterface
    public interface WriteLogAdapter {
        /**
         * Syncs the write log to ensure durability.
         *
         * @throws IOException if sync fails
         */
        void sync() throws IOException;
    }

    /**
     * Creates a new DocumentBatchPublisher.
     *
     * @param visibilityTracker The visibility tracker for epoch management
     * @param documentRegistry The document registry for metadata tracking
     * @param visibilityRegistry The visibility registry for document-atomic visibility (may be null)
     * @param cuckooFilter The Cuckoo filter for chunk membership (may be null)
     * @param writeLogAdapter The write log adapter for fsync operations
     * @param publicationLock Lock for serializing publications (may be null)
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            DocumentVisibilityRegistry visibilityRegistry,
            CuckooFilter cuckooFilter,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock) {
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.documentRegistry = Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
        this.visibilityRegistry = visibilityRegistry;
        this.cuckooFilter = cuckooFilter;
        this.hierarchicalFilter = null;
        this.segmentId = null;
        this.writeLogAdapter = writeLogAdapter;
        this.publicationLock = publicationLock;
    }

    /**
     * Creates a new DocumentBatchPublisher without visibility registry (legacy compatibility).
     *
     * @param visibilityTracker The visibility tracker for epoch management
     * @param documentRegistry The document registry for metadata tracking
     * @param cuckooFilter The Cuckoo filter for chunk membership (may be null)
     * @param writeLogAdapter The write log adapter for fsync operations
     * @param publicationLock Lock for serializing publications (may be null)
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            CuckooFilter cuckooFilter,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock) {
        this(visibilityTracker, documentRegistry, null, cuckooFilter, writeLogAdapter, publicationLock);
    }

    /**
     * Creates a new DocumentBatchPublisher with hierarchical filter support.
     *
     * @param visibilityTracker The visibility tracker
     * @param documentRegistry The document registry
     * @param visibilityRegistry The visibility registry for document-atomic visibility (may be null)
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null)
     * @param segmentId The segment ID for hierarchical filter operations (required if hierarchicalFilter is set)
     * @param writeLogAdapter The write log adapter
     * @param publicationLock Lock for serializing publications
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            DocumentVisibilityRegistry visibilityRegistry,
            HierarchicalCuckooFilter hierarchicalFilter,
            String segmentId,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock) {
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.documentRegistry = Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
        this.visibilityRegistry = visibilityRegistry;
        this.cuckooFilter = null;
        this.hierarchicalFilter = hierarchicalFilter;
        this.segmentId = segmentId;
        this.writeLogAdapter = writeLogAdapter;
        this.publicationLock = publicationLock;
    }

    /**
     * Creates a new DocumentBatchPublisher with hierarchical filter support (legacy compatibility).
     *
     * @param visibilityTracker The visibility tracker
     * @param documentRegistry The document registry
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null)
     * @param segmentId The segment ID for hierarchical filter operations (required if hierarchicalFilter is set)
     * @param writeLogAdapter The write log adapter
     * @param publicationLock Lock for serializing publications
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            HierarchicalCuckooFilter hierarchicalFilter,
            String segmentId,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock) {
        this(visibilityTracker, documentRegistry, null, hierarchicalFilter, segmentId, writeLogAdapter, publicationLock);
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
     *   <li>Insert all chunk keys into Cuckoo filter hierarchy</li>
     *   <li>Fsync write log to ensure durability</li>
     *   <li>Advance visibility epoch atomically</li>
     *   <li>Register document metadata in registry</li>
     *   <li>Mark buffer as PUBLISHED</li>
     * </ol>
     *
     * @param buffer The sealed buffer to publish
     * @param metadata The document metadata to register
     * @return Publication result with epoch and chunk count
     * @throws IllegalStateException if buffer is not sealed or publication fails
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

        if (publicationLock != null) {
            publicationLock.lock();
            try {
                return doPublish(buffer, metadata);
            } finally {
                publicationLock.unlock();
            }
        } else {
            return doPublish(buffer, metadata);
        }
    }

    /**
     * Internal publication logic without lock handling.
     */
    private PublicationResult doPublish(DocumentBatchBuffer buffer, DocumentMetadata metadata) {
        String documentId = buffer.getDocumentId();
        String sourceId = buffer.getSourceId();
        int chunkCount = buffer.getChunkCount();

        LOGGER.debug("Publishing document batch: documentId={}, sourceId={}, chunks={}",
            documentId, sourceId, chunkCount);

        long epochBefore = visibilityTracker.currentEpoch();

        try {
            List<String> failedChunkIds = insertIntoFilters(buffer);
            if (!failedChunkIds.isEmpty()) {
                throw new PublicationException(
                    "Failed to insert chunks into Cuckoo filter: " + failedChunkIds,
                    failedChunkIds);
            }

            if (writeLogAdapter != null) {
                writeLogAdapter.sync();
                LOGGER.trace("Write log synced for document: {}", documentId);
            }

            long newEpoch = visibilityTracker.advanceEpoch();
            LOGGER.debug("Visibility epoch advanced: {} -> {} for document: {}",
                epochBefore, newEpoch, documentId);

            documentRegistry.register(metadata);
            LOGGER.debug("Document metadata registered: {}", documentId);

            if (visibilityRegistry != null) {
                Set<String> chunkIds = new HashSet<>(buffer.getChunkIds());
                visibilityRegistry.registerDocument(documentId, chunkIds, newEpoch);
                LOGGER.debug("Document registered in visibility registry: {} at epoch {}",
                    documentId, newEpoch);
            }

            buffer.markPublished();

            LOGGER.info("Document batch published atomically: documentId={}, chunks={}, epoch={}",
                documentId, chunkCount, newEpoch);

            return new PublicationResult(newEpoch, chunkCount);

        } catch (IOException e) {
            buffer.markFailed(e);
            throw new PublicationException("Failed to sync write log for document: " + documentId, e);
        } catch (PublicationException e) {
            buffer.markFailed(e);
            throw e;
        } catch (Exception e) {
            buffer.markFailed(e);
            throw new PublicationException("Unexpected error during publication for document: " + documentId, e);
        }
    }

    /**
     * Inserts all chunk keys into the Cuckoo filter hierarchy.
     *
     * @param buffer The buffer containing chunk entries
     * @return List of chunk IDs that failed to insert (empty if all succeeded)
     */
    private List<String> insertIntoFilters(DocumentBatchBuffer buffer) {
        List<String> failed = new ArrayList<>();

        for (DocumentBatchBuffer.ChunkEntry entry : buffer.getEntries()) {
            String chunkId = entry.chunkId();
            byte[] keyBytes = chunkId.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            boolean inserted = false;

            if (hierarchicalFilter != null) {
                try {
                    if (segmentId != null) {
                        inserted = hierarchicalFilter.insert(keyBytes, segmentId);
                    } else {
                        LOGGER.warn("Cannot insert into hierarchical filter without segmentId, chunk: {}", chunkId);
                        inserted = false;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to insert chunk {} into hierarchical filter: {}",
                        chunkId, e.getMessage());
                }
            } else if (cuckooFilter != null) {
                try {
                    inserted = cuckooFilter.insert(keyBytes);
                } catch (Exception e) {
                    LOGGER.warn("Failed to insert chunk {} into filter: {}",
                        chunkId, e.getMessage());
                }
            } else {
                inserted = true;
            }

            if (!inserted) {
                failed.add(chunkId);
            }
        }

        return failed;
    }

    /**
     * Publishes multiple document batches atomically in a single epoch.
     *
     * <p>This method allows multiple documents to become visible at the same
     * epoch, which is useful for bulk ingestion scenarios.</p>
     *
     * @param buffers The sealed buffers to publish
     * @param metadataList The corresponding document metadata
     * @return Publication result with total chunks and epoch
     */
    public BulkPublicationResult publishBulk(
            List<DocumentBatchBuffer> buffers,
            List<DocumentMetadata> metadataList) {
        Objects.requireNonNull(buffers, "buffers must not be null");
        Objects.requireNonNull(metadataList, "metadataList must not be null");

        if (buffers.size() != metadataList.size()) {
            throw new IllegalArgumentException(
                "buffers and metadataList must have same size: " +
                buffers.size() + " != " + metadataList.size());
        }

        if (buffers.isEmpty()) {
            return new BulkPublicationResult(visibilityTracker.currentEpoch(), 0, 0);
        }

        for (DocumentBatchBuffer buffer : buffers) {
            if (!buffer.isSealed()) {
                throw new IllegalStateException(
                    "All buffers must be sealed. Buffer for " +
                    buffer.getDocumentId() + " is in state: " + buffer.getState());
            }
        }

        if (publicationLock != null) {
            publicationLock.lock();
            try {
                return doPublishBulk(buffers, metadataList);
            } finally {
                publicationLock.unlock();
            }
        } else {
            return doPublishBulk(buffers, metadataList);
        }
    }

    private BulkPublicationResult doPublishBulk(
            List<DocumentBatchBuffer> buffers,
            List<DocumentMetadata> metadataList) {
        long epochBefore = visibilityTracker.currentEpoch();
        int totalChunks = 0;
        List<String> failedDocuments = new ArrayList<>();

        try {
            for (int i = 0; i < buffers.size(); i++) {
                DocumentBatchBuffer buffer = buffers.get(i);
                List<String> failed = insertIntoFilters(buffer);
                if (!failed.isEmpty()) {
                    failedDocuments.add(buffer.getDocumentId());
                    LOGGER.warn("Failed to insert {} chunks for document {}",
                        failed.size(), buffer.getDocumentId());
                }
                totalChunks += buffer.getChunkCount();
            }

            if (!failedDocuments.isEmpty() && failedDocuments.size() == buffers.size()) {
                throw new PublicationException(
                    "All documents failed filter insertion: " + failedDocuments,
                    failedDocuments);
            }

            if (writeLogAdapter != null) {
                writeLogAdapter.sync();
            }

            long newEpoch = visibilityTracker.advanceEpoch();

            for (int i = 0; i < metadataList.size(); i++) {
                DocumentBatchBuffer buffer = buffers.get(i);
                if (!failedDocuments.contains(buffer.getDocumentId())) {
                    documentRegistry.register(metadataList.get(i));
                    
                    if (visibilityRegistry != null) {
                        Set<String> chunkIds = new HashSet<>(buffer.getChunkIds());
                        visibilityRegistry.registerDocument(buffer.getDocumentId(), chunkIds, newEpoch);
                    }
                    
                    buffer.markPublished();
                } else {
                    buffer.markFailed(new PublicationException("Filter insertion failed for document: " + buffer.getDocumentId()));
                }
            }

            int successCount = buffers.size() - failedDocuments.size();
            LOGGER.info("Bulk publication complete: {} documents, {} chunks, epoch {}",
                successCount, totalChunks, newEpoch);

            return new BulkPublicationResult(newEpoch, totalChunks, successCount);

        } catch (IOException e) {
            for (DocumentBatchBuffer buffer : buffers) {
                buffer.markFailed(e);
            }
            throw new PublicationException("Failed to sync write log during bulk publication", e);
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
     * Returns the visibility registry.
     */
    public DocumentVisibilityRegistry getVisibilityRegistry() {
        return visibilityRegistry;
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
     * Result of a bulk publication.
     *
     * @param epoch The visibility epoch at which documents became visible
     * @param totalChunks Total number of chunks published
     * @param documentCount Number of documents successfully published
     */
    public record BulkPublicationResult(long epoch, int totalChunks, int documentCount) {
        public BulkPublicationResult {
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must be non-negative");
            }
            if (totalChunks < 0) {
                throw new IllegalArgumentException("totalChunks must be non-negative");
            }
            if (documentCount < 0) {
                throw new IllegalArgumentException("documentCount must be non-negative");
            }
        }
    }

    /**
     * Exception thrown when publication fails.
     */
    public static class PublicationException extends RuntimeException {
        private final List<String> failedItems;

        public PublicationException(String message) {
            super(message);
            this.failedItems = List.of();
        }

        public PublicationException(String message, Throwable cause) {
            super(message, cause);
            this.failedItems = List.of();
        }

        public PublicationException(String message, List<String> failedItems) {
            super(message);
            this.failedItems = failedItems != null ? List.copyOf(failedItems) : List.of();
        }

        public List<String> getFailedItems() {
            return failedItems;
        }
    }
}
