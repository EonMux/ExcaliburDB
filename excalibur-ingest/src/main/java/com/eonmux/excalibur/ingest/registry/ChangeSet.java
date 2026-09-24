package com.eonmux.excalibur.ingest.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the result of change detection between a crawl and the document registry.
 *
 * <h2>Categories</h2>
 * <p>A ChangeSet categorizes documents into three groups:
 * <ul>
 *   <li><strong>New documents:</strong> Documents that exist in the crawl but not in the registry</li>
 *   <li><strong>Changed documents:</strong> Documents that exist in both but have different content
 *       (see {@link DocumentMetadata#isChanged})</li>
 *   <li><strong>Deleted documents:</strong> Documents that exist in the registry but not in the crawl</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the ChangeSet cannot be modified.
 * All lists are defensively copied and wrapped in unmodifiable collections.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All lists are non-null (empty if no items)</li>
 *   <li>Document IDs are non-null and non-empty</li>
 *   <li>Old metadata for changed documents is preserved for tombstoning</li>
 *   <li>Counts are consistent with list sizes</li>
 * </ul>
 *
 * @see ChangeDetector
 * @see DocumentMetadata
 */
public final class ChangeSet {

    private final String sourceId;
    private final List<String> newDocuments;
    private final List<ChangedDocument> changedDocuments;
    private final List<DocumentMetadata> deletedDocuments;
    private final int totalCrawled;
    private final int totalRegistered;

    private ChangeSet(Builder builder) {
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        this.newDocuments = List.copyOf(builder.newDocuments);
        this.changedDocuments = List.copyOf(builder.changedDocuments);
        this.deletedDocuments = List.copyOf(builder.deletedDocuments);
        this.totalCrawled = builder.totalCrawled;
        this.totalRegistered = builder.totalRegistered;
    }

    /**
     * Returns the source ID for this change set.
     *
     * @return Source identifier
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * Returns the list of new document IDs (not previously indexed).
     *
     * @return Unmodifiable list of new document IDs
     */
    public List<String> getNewDocuments() {
        return newDocuments;
    }

    /**
     * Returns the list of changed documents with their old and new metadata.
     *
     * @return Unmodifiable list of changed documents
     */
    public List<ChangedDocument> getChangedDocuments() {
        return changedDocuments;
    }

    /**
     * Returns the list of deleted document metadata.
     *
     * <p>The returned metadata includes chunk IDs that need to be tombstoned
     * in the storage layer.</p>
     *
     * @return Unmodifiable list of deleted document metadata
     */
    public List<DocumentMetadata> getDeletedDocuments() {
        return deletedDocuments;
    }

    /**
     * Returns the count of new documents.
     *
     * @return New document count
     */
    public int getNewCount() {
        return newDocuments.size();
    }

    /**
     * Returns the count of changed documents.
     *
     * @return Changed document count
     */
    public int getChangedCount() {
        return changedDocuments.size();
    }

    /**
     * Returns the count of deleted documents.
     *
     * @return Deleted document count
     */
    public int getDeletedCount() {
        return deletedDocuments.size();
    }

    /**
     * Returns the total number of documents in the crawl.
     *
     * @return Total crawled count
     */
    public int getTotalCrawled() {
        return totalCrawled;
    }

    /**
     * Returns the total number of documents that were registered before this crawl.
     *
     * @return Total registered count
     */
    public int getTotalRegistered() {
        return totalRegistered;
    }

    /**
     * Checks if there are any changes (new, changed, or deleted).
     *
     * @return true if there are any changes
     */
    public boolean hasChanges() {
        return !newDocuments.isEmpty() || !changedDocuments.isEmpty() || !deletedDocuments.isEmpty();
    }

    /**
     * Returns all chunk IDs that need to be tombstoned.
     *
     * <p>This includes:
     * <ul>
     *   <li>Chunk IDs from changed documents (old versions need tombstoning)</li>
     *   <li>Chunk IDs from deleted documents</li>
     * </ul>
     *
     * @return List of chunk IDs to tombstone
     */
    public List<String> getChunksToTombstone() {
        List<String> chunks = new ArrayList<>();
        
        for (ChangedDocument changed : changedDocuments) {
            chunks.addAll(changed.getOldMetadata().getChunkIds());
        }
        
        for (DocumentMetadata deleted : deletedDocuments) {
            chunks.addAll(deleted.getChunkIds());
        }
        
        return Collections.unmodifiableList(chunks);
    }

    /**
     * Creates a new builder for constructing ChangeSet instances.
     *
     * @param sourceId The source identifier
     * @return A new builder
     */
    public static Builder builder(String sourceId) {
        return new Builder(sourceId);
    }

    @Override
    public String toString() {
        return String.format("ChangeSet[sourceId=%s, new=%d, changed=%d, deleted=%d, crawled=%d, registered=%d]",
            sourceId, getNewCount(), getChangedCount(), getDeletedCount(), totalCrawled, totalRegistered);
    }

    /**
     * Represents a document that has changed between crawls.
     */
    public static final class ChangedDocument {
        private final String documentId;
        private final DocumentMetadata oldMetadata;
        private final DocumentMetadata.FetchedDocumentInfo fetchedInfo;

        public ChangedDocument(String documentId, DocumentMetadata oldMetadata, 
                               DocumentMetadata.FetchedDocumentInfo fetchedInfo) {
            this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
            this.oldMetadata = Objects.requireNonNull(oldMetadata, "oldMetadata must not be null");
            this.fetchedInfo = Objects.requireNonNull(fetchedInfo, "fetchedInfo must not be null");
        }

        /**
         * Returns the document ID.
         *
         * @return Document identifier
         */
        public String getDocumentId() {
            return documentId;
        }

        /**
         * Returns the old metadata (from registry).
         *
         * <p>Use this to get chunk IDs for tombstoning.</p>
         *
         * @return Old document metadata
         */
        public DocumentMetadata getOldMetadata() {
            return oldMetadata;
        }

        /**
         * Returns information about the fetched document.
         *
         * <p>Use this for re-indexing the document.</p>
         *
         * @return Fetched document info
         */
        public DocumentMetadata.FetchedDocumentInfo getFetchedInfo() {
            return fetchedInfo;
        }

        @Override
        public String toString() {
            return String.format("ChangedDocument[id=%s, oldChunks=%d]", 
                documentId, oldMetadata.getChunkCount());
        }
    }

    /**
     * Builder for constructing ChangeSet instances.
     */
    public static final class Builder {
        private final String sourceId;
        private final List<String> newDocuments = new ArrayList<>();
        private final List<ChangedDocument> changedDocuments = new ArrayList<>();
        private final List<DocumentMetadata> deletedDocuments = new ArrayList<>();
        private int totalCrawled = 0;
        private int totalRegistered = 0;

        public Builder(String sourceId) {
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        }

        /**
         * Adds a new document ID.
         *
         * @param documentId The document ID
         * @return This builder
         */
        public Builder addNew(String documentId) {
            Objects.requireNonNull(documentId, "documentId must not be null");
            newDocuments.add(documentId);
            return this;
        }

        /**
         * Adds a changed document.
         *
         * @param documentId The document ID
         * @param oldMetadata The old metadata from registry
         * @param fetchedInfo Information about the fetched document
         * @return This builder
         */
        public Builder addChanged(String documentId, DocumentMetadata oldMetadata,
                                  DocumentMetadata.FetchedDocumentInfo fetchedInfo) {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(oldMetadata, "oldMetadata must not be null");
            Objects.requireNonNull(fetchedInfo, "fetchedInfo must not be null");
            changedDocuments.add(new ChangedDocument(documentId, oldMetadata, fetchedInfo));
            return this;
        }

        /**
         * Adds a deleted document.
         *
         * @param metadata The metadata from registry
         * @return This builder
         */
        public Builder addDeleted(DocumentMetadata metadata) {
            Objects.requireNonNull(metadata, "metadata must not be null");
            deletedDocuments.add(metadata);
            return this;
        }

        /**
         * Sets the total number of documents in the crawl.
         *
         * @param count Total crawled count
         * @return This builder
         */
        public Builder totalCrawled(int count) {
            this.totalCrawled = count;
            return this;
        }

        /**
         * Sets the total number of documents that were registered before this crawl.
         *
         * @param count Total registered count
         * @return This builder
         */
        public Builder totalRegistered(int count) {
            this.totalRegistered = count;
            return this;
        }

        /**
         * Builds the ChangeSet.
         *
         * @return A new ChangeSet instance
         */
        public ChangeSet build() {
            return new ChangeSet(this);
        }
    }
}
