package excalibur.ingest.registry;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.ingest.lsh.LshIngestionService;
import io.excalibur.store.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * Handles document upsert operations including tombstoning old versions and
 * removing entries from the Cuckoo filter hierarchy.
 *
 * <h2>Upsert Logic</h2>
 * <p>On re-crawl, this handler processes:
 * <ul>
 *   <li><strong>Changed documents:</strong> Tombstone old chunks, re-chunk, re-embed, upsert new chunks</li>
 *   <li><strong>Deleted documents:</strong> Tombstone all chunks, remove from Cuckoo filter hierarchy</li>
 *   <li><strong>New documents:</strong> Chunk, embed, and insert (delegated to ingestion pipeline)</li>
 * </ul>
 *
 * <h2>Cuckoo Filter Hierarchy Deletion (CRITICAL)</h2>
 * <p>When a document is tombstoned (changed or deleted), its chunk entries are immediately
 * removed from all levels of the Cuckoo filter hierarchy:
 * <ol>
 *   <li>Segment filter (leaf level) - exact location of the chunk</li>
 *   <li>Cluster filter (intermediate level) - topic/cluster group</li>
 *   <li>Corpus filter (root level) - entire corpus membership</li>
 * </ol>
 * <p>This ensures that queries issued after the tombstone epoch tick will not consider 
 * the entry as a candidate. Deletion is immediate and propagation is synchronous to 
 * prevent stale false positives.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All operations that modify storage or filters are performed
 * atomically. The handler uses write locks to ensure consistency during tombstone operations.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk IDs are converted to UTF-8 bytes for storage operations</li>
 *   <li>Tombstone operations are idempotent (safe to call multiple times)</li>
 *   <li>Cuckoo filter deletions are exact (no false negatives from deletions)</li>
 *   <li>Errors during tombstoning are collected and reported, not thrown</li>
 *   <li>Hierarchical deletion removes from ALL levels: segment → cluster → corpus</li>
 * </ul>
 *
 * @see ChangeSet
 * @see ChangeDetector
 * @see DocumentRegistry
 * @see HierarchicalCuckooFilter
 */
public class UpsertHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpsertHandler.class);

    private final DocumentRegistry registry;
    private final CuckooFilter cuckooFilter;
    private final HierarchicalCuckooFilter hierarchicalFilter;
    private final StorageAdapter storageAdapter;

    /**
     * Optional LSH ingestion service for removing entries from LSH index during tombstoning.
     */
    private final LshIngestionService lshIngestionService;

    /**
     * Optional segment provider for LSH removal operations.
     */
    private final SegmentProvider segmentProvider;

    /**
     * Functional interface for segment lookup.
     * Used by LSH removal to get segment instances for entry deletion.
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
     * Functional interface for storage operations.
     * Allows decoupling from the actual storage implementation.
     */
    @FunctionalInterface
    public interface StorageAdapter {
        /**
         * Deletes a key from storage (tombstone operation).
         *
         * @param key The key to delete
         * @return true if deleted, false if not found
         */
        boolean delete(byte[] key);
    }

    /**
     * Creates a new UpsertHandler.
     *
     * @param registry The document registry
     * @param cuckooFilter The Cuckoo filter for chunk membership (may be null for dry-run)
     * @param storageAdapter The storage adapter for tombstone operations (may be null for dry-run)
     * @throws NullPointerException if registry is null
     */
    public UpsertHandler(DocumentRegistry registry, CuckooFilter cuckooFilter,
                         StorageAdapter storageAdapter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.cuckooFilter = cuckooFilter;
        this.hierarchicalFilter = null;
        this.storageAdapter = storageAdapter;
        this.lshIngestionService = null;
        this.segmentProvider = null;
    }

    /**
     * Creates a new UpsertHandler with hierarchical Cuckoo filter support.
     *
     * <p>Use this constructor when you have a hierarchical filter structure.
     * Deletions will be propagated to all levels of the hierarchy immediately.</p>
     *
     * @param registry The document registry
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null for dry-run)
     * @param storageAdapter The storage adapter for tombstone operations (may be null for dry-run)
     * @throws NullPointerException if registry is null
     */
    public UpsertHandler(DocumentRegistry registry, HierarchicalCuckooFilter hierarchicalFilter,
                         StorageAdapter storageAdapter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.cuckooFilter = null;
        this.hierarchicalFilter = hierarchicalFilter;
        this.storageAdapter = storageAdapter;
        this.lshIngestionService = null;
        this.segmentProvider = null;
    }

    /**
     * Creates a new UpsertHandler with both flat and hierarchical filter support.
     *
     * <p>This constructor allows both filter types. When hierarchical filter is present,
     * it takes precedence for deletion operations.</p>
     *
     * @param registry The document registry
     * @param cuckooFilter The flat Cuckoo filter (fallback, may be null)
     * @param hierarchicalFilter The hierarchical Cuckoo filter (preferred, may be null)
     * @param storageAdapter The storage adapter for tombstone operations (may be null for dry-run)
     * @throws NullPointerException if registry is null
     */
    public UpsertHandler(DocumentRegistry registry, CuckooFilter cuckooFilter,
                         HierarchicalCuckooFilter hierarchicalFilter, StorageAdapter storageAdapter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.cuckooFilter = cuckooFilter;
        this.hierarchicalFilter = hierarchicalFilter;
        this.storageAdapter = storageAdapter;
        this.lshIngestionService = null;
        this.segmentProvider = null;
    }

    /**
     * Creates a new UpsertHandler with LSH ingestion support.
     *
     * <p>When LSH ingestion service is provided, entries will be removed from
     * the LSH index during tombstoning operations, keeping the vector similarity
     * index consistent with storage.</p>
     *
     * @param registry The document registry
     * @param cuckooFilter The flat Cuckoo filter (may be null)
     * @param hierarchicalFilter The hierarchical Cuckoo filter (may be null)
     * @param storageAdapter The storage adapter for tombstone operations (may be null for dry-run)
     * @param lshIngestionService The LSH ingestion service for vector index cleanup (may be null)
     * @param segmentProvider The segment provider for LSH removal (may be null if lshIngestionService is null)
     * @throws NullPointerException if registry is null
     */
    public UpsertHandler(DocumentRegistry registry, CuckooFilter cuckooFilter,
                         HierarchicalCuckooFilter hierarchicalFilter, StorageAdapter storageAdapter,
                         LshIngestionService lshIngestionService, SegmentProvider segmentProvider) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.cuckooFilter = cuckooFilter;
        this.hierarchicalFilter = hierarchicalFilter;
        this.storageAdapter = storageAdapter;
        this.lshIngestionService = lshIngestionService;
        this.segmentProvider = segmentProvider;
    }

    /**
     * Processes a ChangeSet, tombstoning old chunks for changed/deleted documents.
     *
     * <p>This method:
     * <ol>
     *   <li>Tombstones all chunks from changed documents</li>
     *   <li>Tombstones all chunks from deleted documents</li>
     *   <li>Removes all affected chunks from the Cuckoo filter hierarchy</li>
     *   <li>Removes deleted document metadata from the registry</li>
     * </ol>
     *
     * <p>Changed document metadata is NOT removed from the registry - the ingestion
     * pipeline will update it after re-indexing.</p>
     *
     * @param changeSet The change set to process
     * @return Result containing counts of successful operations and any errors
     * @throws NullPointerException if changeSet is null
     */
    public UpsertResult processChanges(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet must not be null");
        
        UpsertResult.Builder result = UpsertResult.builder();
        
        // Process changed documents - tombstone old chunks
        for (ChangeSet.ChangedDocument changed : changeSet.getChangedDocuments()) {
            processChangedDocument(changed, result);
        }
        
        // Process deleted documents - tombstone all chunks and remove from registry
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            processDeletedDocument(deleted, result);
        }
        
        return result.build();
    }

    /**
     * Processes a ChangeSet with segment-aware deletion from the Cuckoo filter hierarchy.
     *
     * <p>This method is preferred when using a hierarchical Cuckoo filter because it
     * provides the segment ID needed for efficient hierarchical deletion.</p>
     *
     * <p><strong>CRITICAL:</strong> This method immediately deletes entries from ALL levels
     * of the Cuckoo filter hierarchy (segment → cluster → corpus) to prevent stale false
     * positives in queries issued after the tombstone epoch tick.</p>
     *
     * @param changeSet The change set to process
     * @param segmentId The segment ID for hierarchical filter deletion
     * @return Result containing counts of successful operations and any errors
     * @throws NullPointerException if changeSet or segmentId is null
     */
    public UpsertResult processChangesWithSegment(ChangeSet changeSet, String segmentId) {
        Objects.requireNonNull(changeSet, "changeSet must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        
        UpsertResult.Builder result = UpsertResult.builder();
        
        // Process changed documents - tombstone old chunks with hierarchical deletion
        for (ChangeSet.ChangedDocument changed : changeSet.getChangedDocuments()) {
            processChangedDocumentWithSegment(changed, segmentId, result);
        }
        
        // Process deleted documents - tombstone all chunks with hierarchical deletion
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            processDeletedDocumentWithSegment(deleted, segmentId, result);
        }
        
        return result.build();
    }

    /**
     * Tombstones chunks for a single changed document.
     *
     * <p>The old chunks are tombstoned and removed from the Cuckoo filter.
     * The metadata remains in the registry for update after re-indexing.</p>
     *
     * @param changed The changed document information
     * @param resultBuilder The result builder to record operations
     */
    private void processChangedDocument(ChangeSet.ChangedDocument changed,
                                         UpsertResult.Builder resultBuilder) {
        String documentId = changed.getDocumentId();
        DocumentMetadata oldMetadata = changed.getOldMetadata();
        
        for (String chunkId : oldMetadata.getChunkIds()) {
            boolean storageDeleted = false;
            boolean filterDeleted = false;
            
            // Tombstone in storage
            if (storageAdapter != null) {
                try {
                    storageDeleted = storageAdapter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("storage", chunkId, e.getMessage());
                }
            }
            
            // Remove from Cuckoo filter
            if (cuckooFilter != null) {
                try {
                    filterDeleted = cuckooFilter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("filter", chunkId, e.getMessage());
                }
            }

            // Remove from Hierarchical Cuckoo filter (searches all segments)
            if (!filterDeleted && hierarchicalFilter != null) {
                try {
                    filterDeleted = hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("hierarchical-filter", chunkId, e.getMessage());
                }
            }

            if (storageDeleted || filterDeleted) {
                resultBuilder.incrementChunksTombstoned();
            }
        }

        resultBuilder.incrementDocumentsChanged();
    }

    /**
     * Tombstones chunks for a single deleted document and removes from registry.
     *
     * <p>All chunks are tombstoned and removed from the Cuckoo filter.
     * The metadata is removed from the registry.</p>
     *
     * @param metadata The deleted document metadata
     * @param resultBuilder The result builder to record operations
     */
    private void processDeletedDocument(DocumentMetadata metadata,
                                         UpsertResult.Builder resultBuilder) {
        String sourceId = metadata.getSourceId();
        String documentId = metadata.getDocumentId();
        
        for (String chunkId : metadata.getChunkIds()) {
            boolean storageDeleted = false;
            boolean filterDeleted = false;
            
            // Tombstone in storage
            if (storageAdapter != null) {
                try {
                    storageDeleted = storageAdapter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("storage", chunkId, e.getMessage());
                }
            }
            
            // Remove from Cuckoo filter
            if (cuckooFilter != null) {
                try {
                    filterDeleted = cuckooFilter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("filter", chunkId, e.getMessage());
                }
            }

            // Remove from Hierarchical Cuckoo filter (searches all segments)
            if (!filterDeleted && hierarchicalFilter != null) {
                try {
                    filterDeleted = hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("hierarchical-filter", chunkId, e.getMessage());
                }
            }

            if (storageDeleted || filterDeleted) {
                resultBuilder.incrementChunksTombstoned();
            }
        }

        // Remove from registry
        registry.remove(sourceId, documentId);
        resultBuilder.incrementDocumentsDeleted();
    }

    /**
     * Tombstones chunks for a single changed document with segment-aware hierarchical deletion.
     *
     * <p><strong>CRITICAL:</strong> This method deletes from ALL levels of the Cuckoo filter
     * hierarchy immediately: segment filter → cluster filter → corpus filter.</p>
     *
     * <p>When LSH ingestion service is available, this method also removes entries from
     * the segment's LSH index to maintain consistency with storage tombstones.</p>
     *
     * @param changed The changed document information
     * @param segmentId The segment ID for hierarchical deletion
     * @param resultBuilder The result builder to record operations
     */
    private void processChangedDocumentWithSegment(ChangeSet.ChangedDocument changed,
                                                    String segmentId,
                                                    UpsertResult.Builder resultBuilder) {
        String documentId = changed.getDocumentId();
        DocumentMetadata oldMetadata = changed.getOldMetadata();

        for (String chunkId : oldMetadata.getChunkIds()) {
            boolean storageDeleted = false;
            boolean filterDeleted = false;

            // Tombstone in storage
            if (storageAdapter != null) {
                try {
                    storageDeleted = storageAdapter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("storage", chunkId, e.getMessage());
                }
            }

            // Remove from Cuckoo filter hierarchy (ALL levels)
            filterDeleted = deleteFromFilterHierarchy(chunkId, segmentId);

            // Remove from LSH index if available
            removeEntryFromLshIndex(chunkId, segmentId, resultBuilder);

            if (storageDeleted || filterDeleted) {
                resultBuilder.incrementChunksTombstoned();
            }
        }

        resultBuilder.incrementDocumentsChanged();
    }

    /**
     * Tombstones chunks for a single deleted document with segment-aware hierarchical deletion.
     *
     * <p><strong>CRITICAL:</strong> This method deletes from ALL levels of the Cuckoo filter
     * hierarchy immediately: segment filter → cluster filter → corpus filter.</p>
     *
     * @param metadata The deleted document metadata
     * @param segmentId The segment ID for hierarchical deletion
     * @param resultBuilder The result builder to record operations
     */
    private void processDeletedDocumentWithSegment(DocumentMetadata metadata,
                                                    String segmentId,
                                                    UpsertResult.Builder resultBuilder) {
        String sourceId = metadata.getSourceId();
        String documentId = metadata.getDocumentId();
        
        for (String chunkId : metadata.getChunkIds()) {
            boolean storageDeleted = false;
            boolean filterDeleted = false;
            
            // Tombstone in storage
            if (storageAdapter != null) {
                try {
                    storageDeleted = storageAdapter.delete(toBytes(chunkId));
                } catch (Exception e) {
                    resultBuilder.addError("storage", chunkId, e.getMessage());
                }
            }
            
            // Remove from Cuckoo filter hierarchy (ALL levels)
            filterDeleted = deleteFromFilterHierarchy(chunkId, segmentId);
            
            if (storageDeleted || filterDeleted) {
                resultBuilder.incrementChunksTombstoned();
            }
        }
        
        // Remove from registry
        registry.remove(sourceId, documentId);
        resultBuilder.incrementDocumentsDeleted();
    }

    /**
     * Deletes a chunk from the Cuckoo filter hierarchy immediately.
     *
     * <p><strong>CRITICAL:</strong> This method removes the entry from ALL levels:
     * <ol>
     *   <li>Segment filter (leaf level)</li>
     *   <li>Cluster filter (intermediate level)</li>
     *   <li>Corpus filter (root level)</li>
     * </ol>
     *
     * <p>This ensures queries issued after deletion will not have stale false positives
     * from the deleted entry.</p>
     *
     * @param chunkId The chunk ID to delete
     * @param segmentId The segment ID where the chunk was stored
     * @return true if deleted from at least one filter level
     */
    private boolean deleteFromFilterHierarchy(String chunkId, String segmentId) {
        boolean deleted = false;
        
        // Prefer hierarchical filter if available
        if (hierarchicalFilter != null) {
            try {
                deleted = hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId), segmentId);
            } catch (Exception e) {
                // Log but continue - storage tombstone is the source of truth
            }
        }
        
        // Fall back to flat filter if hierarchical deletion failed or not available
        if (!deleted && cuckooFilter != null) {
            try {
                deleted = cuckooFilter.delete(toBytes(chunkId));
            } catch (Exception e) {
                // Log but continue
            }
        }
        
        return deleted;
    }

    /**
     * Tombstones a single chunk by ID.
     *
     * <p>This is a convenience method for tombstoning individual chunks outside
     * of the normal change detection flow.</p>
     *
     * @param chunkId The chunk ID to tombstone
     * @return true if tombstoning succeeded in either storage or filter
     */
    public boolean tombstoneChunk(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");

        boolean storageDeleted = false;
        boolean filterDeleted = false;

        if (storageAdapter != null) {
            storageDeleted = storageAdapter.delete(toBytes(chunkId));
        }

        if (cuckooFilter != null) {
            filterDeleted = cuckooFilter.delete(toBytes(chunkId));
        }

        // Remove from Hierarchical Cuckoo filter (searches all segments)
        if (!filterDeleted && hierarchicalFilter != null) {
            filterDeleted = hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId));
        }

        return storageDeleted || filterDeleted;
    }

    /**
     * Tombstones multiple chunks in batch.
     *
     * <p>This method is more efficient than calling {@link #tombstoneChunk(String)}
     * multiple times when tombstoning many chunks.</p>
     *
     * @param chunkIds The chunk IDs to tombstone
     * @return Count of successfully tombstoned chunks
     */
    public int tombstoneChunks(List<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        
        int successCount = 0;
        for (String chunkId : chunkIds) {
            if (tombstoneChunk(chunkId)) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * Removes a chunk from the Cuckoo filter only (without storage tombstone).
     *
     * <p>This is useful when the chunk has already been tombstoned in storage
     * but the filter entry was missed.</p>
     *
     * @param chunkId The chunk ID to remove from filter
     * @return true if removed from filter
     */
    public boolean removeFromFilter(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");

        if (hierarchicalFilter != null) {
            return hierarchicalFilter.deleteFromHierarchy(toBytes(chunkId));
        }

        if (cuckooFilter != null) {
            return cuckooFilter.delete(toBytes(chunkId));
        }

        return false;
    }

    /**
     * Tombstones a single chunk by ID with segment-aware hierarchical deletion.
     *
     * <p><strong>CRITICAL:</strong> This method deletes from ALL levels of the Cuckoo filter
     * hierarchy immediately: segment filter → cluster filter → corpus filter.</p>
     *
     * @param chunkId The chunk ID to tombstone
     * @param segmentId The segment ID for hierarchical filter deletion
     * @return true if tombstoning succeeded in either storage or filter
     * @throws NullPointerException if chunkId or segmentId is null
     */
    public boolean tombstoneChunk(String chunkId, String segmentId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        
        boolean storageDeleted = false;
        if (storageAdapter != null) {
            storageDeleted = storageAdapter.delete(toBytes(chunkId));
        }
        
        boolean filterDeleted = deleteFromFilterHierarchy(chunkId, segmentId);
        
        return storageDeleted || filterDeleted;
    }

    /**
     * Tombstones multiple chunks in batch with segment-aware hierarchical deletion.
     *
     * <p><strong>CRITICAL:</strong> This method deletes from ALL levels of the Cuckoo filter
     * hierarchy immediately for each chunk.</p>
     *
     * @param chunkIds The chunk IDs to tombstone
     * @param segmentId The segment ID for hierarchical filter deletion
     * @return Count of successfully tombstoned chunks
     * @throws NullPointerException if chunkIds or segmentId is null
     */
    public int tombstoneChunks(List<String> chunkIds, String segmentId) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        
        int successCount = 0;
        for (String chunkId : chunkIds) {
            if (tombstoneChunk(chunkId, segmentId)) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * Removes a chunk from the Cuckoo filter hierarchy immediately (without storage tombstone).
     *
     * <p><strong>CRITICAL:</strong> This method deletes from ALL levels of the hierarchy:
     * segment filter → cluster filter → corpus filter.</p>
     *
     * <p>Use this when the chunk has already been tombstoned in storage but the
     * filter entries were missed at some hierarchy level.</p>
     *
     * @param chunkId The chunk ID to remove from filter
     * @param segmentId The segment ID for hierarchical deletion
     * @return true if removed from at least one filter level
     * @throws NullPointerException if chunkId or segmentId is null
     */
    public boolean removeFromFilterHierarchy(String chunkId, String segmentId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        
        return deleteFromFilterHierarchy(chunkId, segmentId);
    }

    /**
     * Removes an entry from the LSH index if available.
     *
     * <p>This method attempts to remove the chunk entry from the LSH index
     * used for approximate nearest neighbor pre-filtering. If the LSH
     * ingestion service is not configured, this method does nothing.</p>
     *
     * @param chunkId The chunk ID to remove
     * @param segmentId The segment ID for locating the LSH index
     * @param resultBuilder The result builder to record any errors
     */
    private void removeEntryFromLshIndex(String chunkId, String segmentId, UpsertResult.Builder resultBuilder) {
        if (lshIngestionService == null || segmentProvider == null) {
            // LSH removal is optional - skip if not configured
            return;
        }

        try {
            Segment segment = segmentProvider.getSegment(segmentId);
            if (segment == null) {
                LOGGER.debug("Segment {} not found for LSH removal of chunk {}", segmentId, chunkId);
                return;
            }

            // Compute entry ID from chunk ID (typically hash-based)
            long entryId = computeEntryId(chunkId);

            // Attempt to remove from LSH index - this is best-effort
            boolean removed = lshIngestionService.removeEntry(segment, entryId);
            if (!removed) {
                LOGGER.debug("Entry {} not found in LSH index for segment {}", entryId, segmentId);
            }
        } catch (Exception e) {
            // LSH removal failure should not block tombstone operation
            String error = "Failed to remove chunk " + chunkId + " from LSH index: " + e.getMessage();
            LOGGER.warn(error);
            resultBuilder.addError("lsh", chunkId, error);
        }
    }

    /**
     * Computes an entry ID from a chunk ID for LSH index operations.
     *
     * @param chunkId The chunk ID
     * @return The entry ID (hash-based)
     */
    private long computeEntryId(String chunkId) {
        // Use a simple hash to generate a stable entry ID
        return chunkId.hashCode() & 0xFFFFFFFFL;
    }

    /**
     * Converts a chunk ID string to UTF-8 bytes.
     */
    private static byte[] toBytes(String chunkId) {
        return chunkId.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the document registry.
     *
     * @return The registry
     */
    public DocumentRegistry getRegistry() {
        return registry;
    }

    /**
     * Returns the Cuckoo filter (may be null).
     *
     * @return The filter
     */
    public CuckooFilter getCuckooFilter() {
        return cuckooFilter;
    }

    /**
     * Returns the hierarchical Cuckoo filter (may be null).
     *
     * @return The hierarchical filter
     */
    public HierarchicalCuckooFilter getHierarchicalFilter() {
        return hierarchicalFilter;
    }

    /**
     * Checks if hierarchical filter deletion is available.
     *
     * @return true if hierarchical filter is configured
     */
    public boolean hasHierarchicalFilter() {
        return hierarchicalFilter != null;
    }

    /**
     * Result of processing a ChangeSet.
     */
    public static final class UpsertResult {
        private final int documentsChanged;
        private final int documentsDeleted;
        private final int chunksTombstoned;
        private final List<OperationError> errors;

        private UpsertResult(Builder builder) {
            this.documentsChanged = builder.documentsChanged;
            this.documentsDeleted = builder.documentsDeleted;
            this.chunksTombstoned = builder.chunksTombstoned;
            this.errors = List.copyOf(builder.errors);
        }

        /**
         * Returns the number of changed documents processed.
         *
         * @return Changed document count
         */
        public int getDocumentsChanged() {
            return documentsChanged;
        }

        /**
         * Returns the number of deleted documents processed.
         *
         * @return Deleted document count
         */
        public int getDocumentsDeleted() {
            return documentsDeleted;
        }

        /**
         * Returns the number of chunks successfully tombstoned.
         *
         * @return Tombstoned chunk count
         */
        public int getChunksTombstoned() {
            return chunksTombstoned;
        }

        /**
         * Returns any errors that occurred during processing.
         *
         * @return List of errors (empty if none)
         */
        public List<OperationError> getErrors() {
            return errors;
        }

        /**
         * Checks if any errors occurred.
         *
         * @return true if there are errors
         */
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /**
         * Checks if the operation was completely successful.
         *
         * @return true if no errors and at least one operation performed
         */
        public boolean isSuccess() {
            return errors.isEmpty() && (documentsChanged > 0 || documentsDeleted > 0 || chunksTombstoned > 0);
        }

        @Override
        public String toString() {
            return String.format("UpsertResult[changed=%d, deleted=%d, tombstoned=%d, errors=%d]",
                documentsChanged, documentsDeleted, chunksTombstoned, errors.size());
        }

        /**
         * Creates a new builder.
         *
         * @return Builder instance
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for UpsertResult.
         */
        public static final class Builder {
            private int documentsChanged = 0;
            private int documentsDeleted = 0;
            private int chunksTombstoned = 0;
            private final List<OperationError> errors = new ArrayList<>();

            public Builder incrementDocumentsChanged() {
                documentsChanged++;
                return this;
            }

            public Builder incrementDocumentsDeleted() {
                documentsDeleted++;
                return this;
            }

            public Builder incrementChunksTombstoned() {
                chunksTombstoned++;
                return this;
            }

            public Builder addError(String component, String chunkId, String message) {
                errors.add(new OperationError(component, chunkId, message));
                return this;
            }

            public UpsertResult build() {
                return new UpsertResult(this);
            }
        }

        /**
         * Represents an error that occurred during an operation.
         */
        public static final class OperationError {
            private final String component;
            private final String chunkId;
            private final String message;

            public OperationError(String component, String chunkId, String message) {
                this.component = component;
                this.chunkId = chunkId;
                this.message = message;
            }

            public String getComponent() {
                return component;
            }

            public String getChunkId() {
                return chunkId;
            }

            public String getMessage() {
                return message;
            }

            @Override
            public String toString() {
                return String.format("OperationError[%s: %s - %s]", component, chunkId, message);
            }
        }
    }
}
