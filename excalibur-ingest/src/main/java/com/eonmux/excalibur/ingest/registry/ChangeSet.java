package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.ListingGaps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the result of change detection between a crawl and the document registry.
 *
 * <h2>Categories</h2>
 * <p>A ChangeSet categorizes documents into six groups:
 * <ul>
 *   <li><strong>New documents:</strong> Documents that exist in the crawl but not in the registry</li>
 *   <li><strong>Changed documents:</strong> Documents that exist in both but have different content
 *       (see {@link DocumentMetadata#isChanged})</li>
 *   <li><strong>Deleted documents:</strong> Documents that exist in the registry but not in a
 *       complete crawl, and listed documents the source answered for as gone or forbidden</li>
 *   <li><strong>Unchecked documents:</strong> Documents in both that the source could not be
 *       asked about - a timeout, a server error, a {@code 304} to a request that asked
 *       nothing. Whether they changed is unknown, so their indexed version is kept; they are
 *       listed so the run can report them as failures rather than as unchanged</li>
 *   <li><strong>Refused documents:</strong> Documents in both whose source answered, and
 *       answered with a version that cannot be indexed - grown past the size limit, turned into
 *       a content type the source excludes, a {@code 403}. Unlike an unchecked document the
 *       question was answered, so they are failures with a reason rather than unknowns; their
 *       indexed version is kept all the same, as for any document a run fails on</li>
 *   <li><strong>Kept unlisted documents:</strong> Documents in the registry and not in the
 *       crawl, when the crawl reported {@linkplain #getListingGaps() gaps} in its listing. A
 *       listing that could not see everything cannot say they are gone, so they are kept as
 *       they are indexed rather than deleted, and listed so the run can say so. Neither
 *       crawled nor failed: they were not asked about at all</li>
 * </ul>
 * <p>Every other document in both is unchanged: its source confirmed the indexed version is
 * current.</p>
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
    private final List<String> uncheckedDocuments;
    private final List<RefusedDocument> refusedDocuments;
    private final List<String> keptUnlistedDocuments;
    private final ListingGaps listingGaps;
    private final int totalCrawled;
    private final int totalRegistered;

    private ChangeSet(Builder builder) {
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        this.newDocuments = List.copyOf(builder.newDocuments);
        this.changedDocuments = List.copyOf(builder.changedDocuments);
        this.deletedDocuments = List.copyOf(builder.deletedDocuments);
        this.uncheckedDocuments = List.copyOf(builder.uncheckedDocuments);
        this.refusedDocuments = List.copyOf(builder.refusedDocuments);
        this.keptUnlistedDocuments = List.copyOf(builder.keptUnlistedDocuments);
        this.listingGaps = builder.listingGaps;
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
     * Returns the registered documents whose source could not say whether they changed.
     *
     * <p>Each is kept as it is indexed, and the reason is in the log.</p>
     *
     * @return Unmodifiable list of document IDs, in crawl order
     */
    public List<String> getUncheckedDocuments() {
        return uncheckedDocuments;
    }

    /**
     * Returns the registered documents whose source answered with a version that cannot be
     * indexed.
     *
     * @return Unmodifiable list, in crawl order
     */
    public List<RefusedDocument> getRefusedDocuments() {
        return refusedDocuments;
    }

    /**
     * Returns the registered documents the crawl did not list and could not be taken as deleted,
     * because its listing had gaps.
     *
     * <p>Each is kept as it is indexed. Empty whenever the listing was complete: a document it
     * missed then is deleted.</p>
     *
     * @return Unmodifiable list of document IDs, in registry order
     */
    public List<String> getKeptUnlistedDocuments() {
        return keptUnlistedDocuments;
    }

    /**
     * Returns where the crawl's listing could not see, as its source reported.
     *
     * @return The gaps, {@link ListingGaps#NONE} for a complete listing
     */
    public ListingGaps getListingGaps() {
        return listingGaps;
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
     * Returns the count of documents that could not be checked.
     *
     * @return Unchecked document count
     */
    public int getUncheckedCount() {
        return uncheckedDocuments.size();
    }

    /**
     * Returns the count of documents whose current version the source refused.
     *
     * @return Refused document count
     */
    public int getRefusedCount() {
        return refusedDocuments.size();
    }

    /**
     * Returns the count of documents kept because the listing that missed them had gaps.
     *
     * @return Kept unlisted document count
     */
    public int getKeptUnlistedCount() {
        return keptUnlistedDocuments.size();
    }

    /**
     * Returns how many registered documents the source confirmed are current.
     *
     * <p>What the crawl listed less what is new, changed, refused or could not be checked. A
     * document kept unlisted is not in it: it was never among what the crawl listed.</p>
     *
     * @return Unchanged document count
     */
    public int getUnchangedCount() {
        return Math.max(0, totalCrawled - getNewCount() - getChangedCount() - getUncheckedCount()
            - getRefusedCount());
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
     * Checks if there is anything for a run to act on or report: a new, changed, deleted,
     * unchecked, refused or kept unlisted document.
     *
     * @return true if there are any
     */
    public boolean hasChanges() {
        return !newDocuments.isEmpty() || !changedDocuments.isEmpty() || !deletedDocuments.isEmpty()
            || !uncheckedDocuments.isEmpty() || !refusedDocuments.isEmpty()
            || !keptUnlistedDocuments.isEmpty();
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
        return String.format("ChangeSet[sourceId=%s, new=%d, changed=%d, deleted=%d, unchecked=%d, "
                + "refused=%d, keptUnlisted=%d, crawled=%d, registered=%d, gaps=%d]",
            sourceId, getNewCount(), getChangedCount(), getDeletedCount(), getUncheckedCount(),
            getRefusedCount(), getKeptUnlistedCount(), totalCrawled, totalRegistered,
            listingGaps.count());
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
     * A registered document whose source answered with a version that cannot be indexed.
     *
     * @param documentId The document
     * @param reason     What the source answered
     */
    public record RefusedDocument(String documentId, String reason) {

        /**
         * @throws NullPointerException if either argument is null
         */
        public RefusedDocument {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
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
        private final List<String> uncheckedDocuments = new ArrayList<>();
        private final List<RefusedDocument> refusedDocuments = new ArrayList<>();
        private final List<String> keptUnlistedDocuments = new ArrayList<>();
        private ListingGaps listingGaps = ListingGaps.NONE;
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
         * Adds a registered document the source could not be asked about.
         *
         * @param documentId The document ID
         * @return This builder
         */
        public Builder addUnchecked(String documentId) {
            Objects.requireNonNull(documentId, "documentId must not be null");
            uncheckedDocuments.add(documentId);
            return this;
        }

        /**
         * Adds a registered document whose source answered with a version that cannot be
         * indexed.
         *
         * @param documentId The document ID
         * @param reason     What the source answered, for the run's report
         * @return This builder
         */
        public Builder addRefused(String documentId, String reason) {
            refusedDocuments.add(new RefusedDocument(documentId, reason));
            return this;
        }

        /**
         * Adds a registered document the crawl did not list and that is kept, because the
         * listing had gaps.
         *
         * @param documentId The document ID
         * @return This builder
         */
        public Builder addKeptUnlisted(String documentId) {
            Objects.requireNonNull(documentId, "documentId must not be null");
            keptUnlistedDocuments.add(documentId);
            return this;
        }

        /**
         * Sets where the crawl's listing could not see.
         *
         * @param gaps What the source reported
         * @return This builder
         * @throws NullPointerException if gaps is null
         */
        public Builder listingGaps(ListingGaps gaps) {
            this.listingGaps = Objects.requireNonNull(gaps, "gaps must not be null");
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
