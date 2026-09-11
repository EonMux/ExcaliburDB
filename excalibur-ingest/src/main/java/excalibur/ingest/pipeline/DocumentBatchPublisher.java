package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityRegistry;
import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.core.text.TermExtractor;
import io.excalibur.store.Segment;
import io.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
     * Optional index orchestrator for backpressure checking.
     * When set, publications will block if the HNSW queue is overloaded.
     */
    private final IndexOrchestrator orchestrator;

    /**
     * Optional LSH ingestion service for vector indexing.
     * When set, vectors will be inserted into segment LSH indices during publication.
     */


    /**
     * Default backpressure timeout in milliseconds (30 seconds).
     */
    public static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000;

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
     * Functional interface for segment lookup.
     * Used by LSH ingestion to get segment instances for vector insertion.
     */
    @FunctionalInterface
    public interface SegmentProvider {
        /**
         * Gets a segment by its ID.
         *
         * @param segmentId The segment ID
         * @return The segment, or null if not found
         */
        Segment getSegment(String segmentId);
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
        this(visibilityTracker, documentRegistry, visibilityRegistry, cuckooFilter, null, null,
             writeLogAdapter, publicationLock, null);
    }

    /**
     * Creates a new DocumentBatchPublisher with backpressure support.
     *
     * @param visibilityTracker The visibility tracker for epoch management
     * @param documentRegistry The document registry for metadata tracking
     * @param visibilityRegistry The visibility registry for document-atomic visibility (may be null)
     * @param cuckooFilter The Cuckoo filter for chunk membership (may be null)
     * @param writeLogAdapter The write log adapter for fsync operations
     * @param publicationLock Lock for serializing publications (may be null)
     * @param orchestrator The index orchestrator for backpressure checking (may be null)
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            DocumentVisibilityRegistry visibilityRegistry,
            CuckooFilter cuckooFilter,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock,
            IndexOrchestrator orchestrator) {
        this(visibilityTracker, documentRegistry, visibilityRegistry, cuckooFilter, null, null,
             writeLogAdapter, publicationLock, orchestrator);
    }

    /**
     * The one constructor that assigns the fields; every public constructor funnels into it.
     *
     * <p>Having a single canonical form is what lets {@link #withTargetSegment(long)} rebind the
     * segment without having to know which filter flavour this publisher was built with.</p>
     */
    private DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            DocumentVisibilityRegistry visibilityRegistry,
            CuckooFilter cuckooFilter,
            HierarchicalCuckooFilter hierarchicalFilter,
            String segmentId,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock,
            IndexOrchestrator orchestrator) {
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.documentRegistry = Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
        this.visibilityRegistry = visibilityRegistry;
        this.cuckooFilter = cuckooFilter;
        this.hierarchicalFilter = hierarchicalFilter;
        this.segmentId = segmentId;
        this.writeLogAdapter = writeLogAdapter;
        this.publicationLock = publicationLock;
        this.orchestrator = orchestrator;
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
        this(visibilityTracker, documentRegistry, null, cuckooFilter, writeLogAdapter, publicationLock, null);
    }

    /**
     * Creates a new DocumentBatchPublisher with backpressure support (legacy compatibility).
     *
     * @param visibilityTracker The visibility tracker for epoch management
     * @param documentRegistry The document registry for metadata tracking
     * @param cuckooFilter The Cuckoo filter for chunk membership (may be null)
     * @param writeLogAdapter The write log adapter for fsync operations
     * @param publicationLock Lock for serializing publications (may be null)
     * @param orchestrator The index orchestrator for backpressure checking (may be null)
     * @throws NullPointerException if visibilityTracker or documentRegistry is null
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            CuckooFilter cuckooFilter,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock,
            IndexOrchestrator orchestrator) {
        this(visibilityTracker, documentRegistry, null, cuckooFilter,
             writeLogAdapter, publicationLock, orchestrator);
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
        this(visibilityTracker, documentRegistry, visibilityRegistry, hierarchicalFilter,
             segmentId, writeLogAdapter, publicationLock, null);
    }

    /**
     * Creates a new DocumentBatchPublisher with hierarchical filter and backpressure support.
     *
     * @param visibilityTracker The visibility tracker
     * @param documentRegistry The document registry
     * @param visibilityRegistry The visibility registry for document-atomic visibility (may be null)
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null)
     * @param segmentId The segment ID for hierarchical filter operations (required if hierarchicalFilter is set)
     * @param writeLogAdapter The write log adapter
     * @param publicationLock Lock for serializing publications
     * @param orchestrator The index orchestrator for backpressure checking (may be null)
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            DocumentVisibilityRegistry visibilityRegistry,
            HierarchicalCuckooFilter hierarchicalFilter,
            String segmentId,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock,
            IndexOrchestrator orchestrator) {
        this(visibilityTracker, documentRegistry, visibilityRegistry, null, hierarchicalFilter,
             segmentId, writeLogAdapter, publicationLock, orchestrator);
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
        this(visibilityTracker, documentRegistry, null, hierarchicalFilter,
             segmentId, writeLogAdapter, publicationLock, null);
    }

    /**
     * Creates a new DocumentBatchPublisher with hierarchical filter and backpressure support (legacy compatibility).
     *
     * @param visibilityTracker The visibility tracker
     * @param documentRegistry The document registry
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null)
     * @param segmentId The segment ID for hierarchical filter operations (required if hierarchicalFilter is set)
     * @param writeLogAdapter The write log adapter
     * @param publicationLock Lock for serializing publications
     * @param orchestrator The index orchestrator for backpressure checking (may be null)
     */
    public DocumentBatchPublisher(
            VisibilityTracker visibilityTracker,
            DocumentRegistry documentRegistry,
            HierarchicalCuckooFilter hierarchicalFilter,
            String segmentId,
            WriteLogAdapter writeLogAdapter,
            Lock publicationLock,
            IndexOrchestrator orchestrator) {
        this(visibilityTracker, documentRegistry, null, hierarchicalFilter,
             segmentId, writeLogAdapter, publicationLock, orchestrator);
    }

    /**
     * Creates a new DocumentBatchPublisher bound to a specific target segment.
     *
     * <p>This factory method is used during rolling replacement reindexing to create
     * a publisher that writes all data to a newly created segment. The returned
     * publisher has the same configuration as this publisher but with a different
     * target segment ID for hierarchical filter operations.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>The target segment must be in INDEXING state or later</li>
     *   <li>The target segment must exist in the segment catalog</li>
     *   <li>Publications to the new segment will use its hierarchical filter</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // During rolling replacement:
     * long newSegmentId = segmentCatalog.create(sourceId, volumePath).getSegmentId();
     * DocumentBatchPublisher segmentPublisher = basePublisher.withTargetSegment(newSegmentId);
     * // All publications via segmentPublisher go to newSegmentId
     * }</pre>
     *
     * @param targetSegmentId The segment ID to bind publications to
     * @return A new DocumentBatchPublisher bound to the target segment
     * @throws IllegalArgumentException if targetSegmentId is negative
     */
    public DocumentBatchPublisher withTargetSegment(long targetSegmentId) {
        if (targetSegmentId < 0) {
            throw new IllegalArgumentException("targetSegmentId must be non-negative: " + targetSegmentId);
        }

        // Same configuration, bound to a different segment. The segment id is carried whichever
        // filter flavour this publisher uses: it is also what selects the segment whose LSH index
        // the chunks' vectors are inserted into, so dropping it here would silently unbind the
        // copy from the segment the caller asked for.
        return new DocumentBatchPublisher(
            this.visibilityTracker,
            this.documentRegistry,
            this.visibilityRegistry,
            this.cuckooFilter,
            this.hierarchicalFilter,
            String.valueOf(targetSegmentId),
            this.writeLogAdapter,
            this.publicationLock,
            this.orchestrator
        );
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
        // Check backpressure from orchestrator if available
        if (orchestrator != null && orchestrator.isBackpressured()) {
            LOGGER.debug("Backpressure detected, waiting for release before publishing document: {}",
                buffer.getDocumentId());

            if (!orchestrator.awaitBackpressureRelease(DEFAULT_BACKPRESSURE_TIMEOUT_MS)) {
                long queueDepth = orchestrator.getHnswQueueDepth();
                int queueLimit = orchestrator.getHnswQueueDepthLimit();
                throw new PublicationException(String.format(
                    "HNSW queue backpressure timeout. Queue depth: %d, limit: %d",
                    queueDepth, queueLimit));
            }

            LOGGER.debug("Backpressure released, proceeding with publication for document: {}",
                buffer.getDocumentId());
        }

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

            // The throughput counter the IngestionThroughput MBean reports. It had no writer
            // outside the tests, so a server that had ingested millions of documents reported
            // zero entries, zero rate and a zero peak - and any alert keyed on it never fired.
            io.excalibur.metrics.Metrics.recordIngestEntries(chunkCount);

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
     * Inserts terms from chunk text into the Cuckoo filter hierarchy.
     *
     * <p><strong>Term-Based Filtering (Hierarchical Cuckoo Filter):</strong></p>
     * <p>For hierarchical Cuckoo filters, this method extracts significant terms
     * from each chunk's text content and inserts each term into the filter.
     * This allows the query pipeline to find which segments might contain
     * documents with those terms, enabling efficient segment pruning.</p>
     *
     * <p><strong>Key Matching:</strong></p>
     * <ul>
     *   <li><strong>Ingestion:</strong> Extracts terms from chunk text, inserts each term</li>
     *   <li><strong>Query:</strong> Extracts terms from query text, finds segments containing those terms</li>
     * </ul>
     *
     * <p><strong>Segment ID Format:</strong></p>
     * <p>The segmentId passed to this publisher must match the format used by the query pipeline.
     * Use {@code String.valueOf(longSegmentId)} to ensure consistency.</p>
     *
     * @param buffer The buffer containing chunk entries
     * @return List of chunk IDs that failed to insert (empty if all succeeded)
     */
    private List<String> insertIntoFilters(DocumentBatchBuffer buffer) {
        List<String> failed = new ArrayList<>();
        Set<String> allTermsInserted = new HashSet<>();

        for (DocumentBatchBuffer.ChunkEntry entry : buffer.getEntries()) {
            String chunkId = entry.chunkId();
            TextChunk textChunk = entry.textChunk();

            if (hierarchicalFilter != null) {
                // Term-based insertion for hierarchical filter
                if (segmentId == null) {
                    LOGGER.warn("Cannot insert into hierarchical filter without segmentId, chunk: {}", chunkId);
                    failed.add(chunkId);
                    continue;
                }

                if (textChunk == null || textChunk.getText() == null || textChunk.getText().isEmpty()) {
                    LOGGER.debug("Skipping chunk with no text content for hierarchical filter: {}", chunkId);
                    continue;
                }

                // Distinct terms: the filter answers whether a segment holds a term, so a
                // term occurring forty times in a chunk is one fact, not forty. Inserting it
                // once per occurrence is also what the cleanup path cannot undo - it removes
                // each of a segment's distinct terms once.
                Set<String> terms = TermExtractor.extractUniqueTerms(textChunk.getText());
                if (terms.isEmpty()) {
                    LOGGER.debug("No significant terms extracted from chunk: {}", chunkId);
                    continue;
                }

                // Insert each term into the hierarchical filter
                int failedTerms = 0;
                for (String term : terms) {
                    byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                    try {
                        if (!hierarchicalFilter.insert(termBytes, segmentId)) {
                            failedTerms++;
                            LOGGER.trace("Failed to insert term '{}' into hierarchical filter for chunk {}",
                                term, chunkId);
                        } else {
                            allTermsInserted.add(term);
                        }
                    } catch (Exception e) {
                        failedTerms++;
                        LOGGER.warn("Exception inserting term '{}' into hierarchical filter for chunk {}: {}",
                            term, chunkId, e.getMessage());
                    }
                }

                // Consider chunk failed if ALL terms failed to insert
                if (failedTerms == terms.size()) {
                    LOGGER.warn("All {} terms failed to insert for chunk {}", terms.size(), chunkId);
                    failed.add(chunkId);
                }

            } else if (cuckooFilter != null) {
                // For segment-level Cuckoo filter, also use term-based insertion
                // to enable the query to find relevant segments
                if (textChunk == null || textChunk.getText() == null || textChunk.getText().isEmpty()) {
                    LOGGER.debug("Skipping chunk with no text content for Cuckoo filter: {}", chunkId);
                    continue;
                }

                Set<String> terms = TermExtractor.extractUniqueTerms(textChunk.getText());
                if (terms.isEmpty()) {
                    continue;
                }

                int failedTerms = 0;
                for (String term : terms) {
                    byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                    try {
                        // Chunks of one segment share terms, so the per-chunk set is not
                        // enough on its own to keep one slot per term per segment.
                        if (!cuckooFilter.insertIfAbsent(termBytes)) {
                            failedTerms++;
                        }
                    } catch (Exception e) {
                        failedTerms++;
                        LOGGER.warn("Exception inserting term '{}' into Cuckoo filter: {}", term, e.getMessage());
                    }
                }

                if (failedTerms == terms.size()) {
                    failed.add(chunkId);
                }
            }
            // If neither filter is configured, all chunks succeed
        }

        if (!allTermsInserted.isEmpty()) {
            LOGGER.debug("Inserted {} unique terms from {} chunks into filter for segment {}",
                allTermsInserted.size(), buffer.getChunkCount(), segmentId);
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
        // Check backpressure from orchestrator if available
        if (orchestrator != null && orchestrator.isBackpressured()) {
            LOGGER.debug("Backpressure detected, waiting for release before bulk publishing {} documents",
                buffers.size());

            if (!orchestrator.awaitBackpressureRelease(DEFAULT_BACKPRESSURE_TIMEOUT_MS)) {
                long queueDepth = orchestrator.getHnswQueueDepth();
                int queueLimit = orchestrator.getHnswQueueDepthLimit();
                throw new PublicationException(String.format(
                    "HNSW queue backpressure timeout during bulk publish. Queue depth: %d, limit: %d",
                    queueDepth, queueLimit));
            }

            LOGGER.debug("Backpressure released, proceeding with bulk publication");
        }

        long epochBefore = visibilityTracker.currentEpoch();
        int totalChunks = 0;
        List<String> failedDocuments = new ArrayList<>();

        try {
            for (int i = 0; i < buffers.size(); i++) {
                DocumentBatchBuffer buffer = buffers.get(i);
                List<String> failed = insertIntoFilters(buffer);
                if (!failed.isEmpty()) {
                    failedDocuments.add(buffer.getDocumentId());
                    LOGGER.warn("Document {} is not published: {} of its {} chunks could not be "
                        + "inserted into the filters, and a document is published whole or not "
                        + "at all", buffer.getDocumentId(), failed.size(), buffer.getChunkCount());
                    continue;
                }
                // Only what is published is counted. A refused document's chunks are not in the
                // index, and counting them reports a corpus larger than the one that exists.
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
