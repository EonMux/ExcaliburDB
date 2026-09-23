package com.eonmux.excalibur.ingest.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Handles document upsert operations: tombstoning the chunks of changed and deleted documents
 * and keeping the registry in step with them.
 *
 * <h2>Upsert Logic</h2>
 * <p>On re-crawl, this handler processes:
 * <ul>
 *   <li><strong>Changed documents:</strong> Tombstone old chunks, re-chunk, re-embed, upsert new chunks</li>
 *   <li><strong>Deleted documents:</strong> Tombstone all chunks, remove the registry entry</li>
 *   <li><strong>New documents:</strong> Chunk, embed, and insert (delegated to ingestion pipeline)</li>
 * </ul>
 *
 * <h2>Why Tombstoning Does Not Touch the LSH Index</h2>
 * <p>Removing a document's entries from the LSH index would be unsound. A segment's LSH index is
 * keyed by HNSW node id, because the nodes it names are measured in the segment's graph, and
 * node ids are assigned by the integration worker rather than by ingestion. This handler holds
 * chunk ids and cannot derive a node id from one, so any id it computed would name an unrelated
 * node and evict a live vector's entry.</p>
 * <p>Nothing is lost by leaving it: the index only names extra nodes for a search to measure
 * beside its graph walk, and {@code HnswGraph} skips a named node that is no longer active, so a
 * stale entry costs one node read and yields no result. The entry itself is reclaimed when the
 * segment is compacted.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All operations that modify storage are performed atomically.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk IDs are converted to UTF-8 bytes for storage operations</li>
 *   <li>Tombstone operations are idempotent (safe to call multiple times)</li>
 *   <li>Errors during tombstoning are collected and reported, not thrown</li>
 *   <li>Re-indexing tombstones only the chunk ids the new version did not overwrite</li>
 * </ul>
 *
 * @see ChangeSet
 * @see ChangeDetector
 * @see DocumentRegistry
 */
public class UpsertHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpsertHandler.class);

    private final DocumentRegistry registry;
    private final StorageAdapter storageAdapter;

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
     * @param storageAdapter The storage adapter for tombstone operations (may be null for dry-run)
     * @throws NullPointerException if registry is null
     */
    public UpsertHandler(DocumentRegistry registry, StorageAdapter storageAdapter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageAdapter = storageAdapter;
    }

    /**
     * Processes a ChangeSet, tombstoning old chunks for changed/deleted documents.
     *
     * <p>This method:
     * <ol>
     *   <li>Tombstones all chunks from changed documents</li>
     *   <li>Tombstones all chunks from deleted documents</li>
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
     * Tombstones only the documents that disappeared from the source.
     *
     * <p>Re-indexing must not use {@link #processChanges(ChangeSet)}, because that also removes
     * the previous version of every <em>changed</em> document before its replacement has been
     * fetched and embedded. If the replacement then fails - the embedding endpoint is down, the
     * circuit breaker is open, the fetch throws - the document is left absent in both its old and
     * its new form, with nothing to restore it. Deletions have no such replacement, so they are
     * safe to process up front; changed documents are handled by
     * {@link #tombstoneObsoleteChunks(List, Collection)} after their new version is published.</p>
     *
     * @param changeSet The change set whose deletions should be processed
     * @return Result containing counts of successful operations and any errors
     * @throws NullPointerException if changeSet is null
     */
    public UpsertResult processDeletions(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet must not be null");

        UpsertResult.Builder result = UpsertResult.builder();
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            processDeletedDocument(deleted, result);
        }
        return result.build();
    }

    /**
     * Tombstones one document that disappeared from its source and removes its registry entry.
     *
     * <p>What {@link #processDeletions(ChangeSet)} does for each deleted document, for a caller
     * that has to remove them one at a time: under each document's lock, having first checked
     * that the entry it is removing is still the one change detection read. Removing a batch
     * without that lock removes whatever a concurrent re-post has since written under the same
     * deterministic chunk ids, along with its registry entry.</p>
     *
     * @param metadata The registry entry of the document to remove
     * @return Result containing counts of successful operations and any errors
     * @throws NullPointerException if metadata is null
     */
    public UpsertResult removeDocument(DocumentMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");

        UpsertResult.Builder result = UpsertResult.builder();
        processDeletedDocument(metadata, result);
        return result.build();
    }

    /**
     * Tombstones the chunks of a document's previous version that its new version did not
     * overwrite.
     *
     * <p>Chunk ids are deterministic ({@code sourceId:documentId:chunk:index}), so re-indexing a
     * document overwrites chunk 0..n-1 in place. Only the surplus tail - present when the new
     * version produced fewer chunks than the old one - has to be removed. Tombstoning the whole
     * old id list after the write would delete the new version's own chunks.</p>
     *
     * @param oldChunkIds The chunk ids recorded for the previous version (must not be null)
     * @param newChunkIds The chunk ids just published for the new version (must not be null)
     * @return The number of obsolete chunks tombstoned
     * @throws NullPointerException if either argument is null
     */
    public int tombstoneObsoleteChunks(List<String> oldChunkIds, Collection<String> newChunkIds) {
        Objects.requireNonNull(oldChunkIds, "oldChunkIds must not be null");
        Objects.requireNonNull(newChunkIds, "newChunkIds must not be null");

        if (storageAdapter == null || oldChunkIds.isEmpty()) {
            return 0;
        }

        Set<String> retained = new HashSet<>(newChunkIds);
        int tombstoned = 0;
        for (String chunkId : oldChunkIds) {
            if (retained.contains(chunkId)) {
                continue;
            }
            try {
                if (storageAdapter.delete(toBytes(chunkId))) {
                    tombstoned++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to tombstone obsolete chunk {}: {}", chunkId, e.getMessage());
            }
        }
        return tombstoned;
    }

    /**
     * Tombstones chunks for a single changed document.
     *
     * <p>The old chunks are tombstoned in storage. The metadata remains in the registry for
     * update after re-indexing.</p>
     *
     * @param changed The changed document information
     * @param resultBuilder The result builder to record operations
     */
    private void processChangedDocument(ChangeSet.ChangedDocument changed,
                                         UpsertResult.Builder resultBuilder) {
        String documentId = changed.getDocumentId();
        DocumentMetadata oldMetadata = changed.getOldMetadata();
        
        tombstoneChunks(oldMetadata.getChunkIds(), resultBuilder);
        resultBuilder.incrementDocumentsChanged();
    }

    /**
     * Tombstones every chunk id in storage, recording one error per failure.
     */
    private void tombstoneChunks(List<String> chunkIds, UpsertResult.Builder resultBuilder) {
        if (storageAdapter == null) {
            return;
        }

        for (String chunkId : chunkIds) {
            try {
                if (storageAdapter.delete(toBytes(chunkId))) {
                    resultBuilder.incrementChunksTombstoned();
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to tombstone chunk {}: {}", chunkId, e.getMessage());
                resultBuilder.addError("storage", chunkId, e.getMessage());
            }
        }
    }

    /**
     * Tombstones chunks for a single deleted document and removes from registry.
     *
     * <p>All chunks are tombstoned and the metadata is removed from the registry.</p>
     *
     * @param metadata The deleted document metadata
     * @param resultBuilder The result builder to record operations
     */
    private void processDeletedDocument(DocumentMetadata metadata,
                                         UpsertResult.Builder resultBuilder) {
        String sourceId = metadata.getSourceId();
        String documentId = metadata.getDocumentId();
        
        tombstoneChunks(metadata.getChunkIds(), resultBuilder);

        // Remove from registry
        registry.remove(sourceId, documentId);
        resultBuilder.incrementDocumentsDeleted();
    }

    /**
     * Tombstones a single chunk by ID.
     *
     * <p>This is a convenience method for tombstoning individual chunks outside
     * of the normal change detection flow.</p>
     *
     * @param chunkId The chunk ID to tombstone
     * @return true if the chunk was present in storage and removed
     */
    public boolean tombstoneChunk(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");

        return storageAdapter != null && storageAdapter.delete(toBytes(chunkId));
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
