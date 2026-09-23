package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.DocumentValidators;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for an indexed document.
 *
 * <p>Tracks information needed to detect changes during re-crawl:
 * <ul>
 *   <li>Document ID - unique identifier within the source</li>
 *   <li>Source ID - identifier of the source this document came from</li>
 *   <li>Last modified timestamp - when the document was last modified</li>
 *   <li>Content hash - hash of the document content for change detection</li>
 *   <li>Chunk IDs - identifiers of the chunks this document was split into</li>
 *   <li>Content length - size of the original document in bytes</li>
 *   <li>Index time - when this document was indexed</li>
 *   <li>Entity tag - the validator the source's server named this version by, if any</li>
 * </ul>
 *
 * <h2>Change Detection</h2>
 * <p>A document is considered changed if any of these conditions are true:
 * <ul>
 *   <li>Last modified timestamp is newer than the stored timestamp</li>
 *   <li>Content hash differs (for sources that support hashing)</li>
 *   <li>Content length differs</li>
 * </ul>
 *
 * <p>The timestamp and the entity tag have a second use: they are what a re-crawl sends back to
 * the source as {@link #validators()}, so a source that can ask its origin conditionally learns
 * the document is unchanged without transferring it again.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the metadata
 * cannot be modified. Chunk IDs list is defensively copied.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>documentId: non-null, non-empty string</li>
 *   <li>sourceId: non-null, non-empty string</li>
 *   <li>lastModifiedMillis: non-negative epoch milliseconds (0 = unknown)</li>
 *   <li>contentHash: non-negative hash value (0 = not computed)</li>
 *   <li>contentLength: non-negative byte count</li>
 *   <li>chunkIds: immutable list of non-null strings</li>
 *   <li>entityTag: the tag exactly as the source reported it, or null for none</li>
 * </ul>
 */
public final class DocumentMetadata {

    private final String documentId;
    private final String sourceId;
    private final long lastModifiedMillis;
    private final long contentHash;
    private final long contentLength;
    private final List<String> chunkIds;
    private final long indexTimeMillis;
    private final String entityTag;

    private DocumentMetadata(Builder builder) {
        this.documentId = Objects.requireNonNull(builder.documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        this.lastModifiedMillis = Math.max(0, builder.lastModifiedMillis);
        this.contentHash = Math.max(0, builder.contentHash);
        this.contentLength = Math.max(0, builder.contentLength);
        this.chunkIds = List.copyOf(builder.chunkIds);
        this.indexTimeMillis = builder.indexTimeMillis > 0 
            ? builder.indexTimeMillis 
            : System.currentTimeMillis();
        this.entityTag = builder.entityTag == null || builder.entityTag.isBlank()
            ? null : builder.entityTag;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public Instant getLastModified() {
        return Instant.ofEpochMilli(lastModifiedMillis);
    }

    public long getContentHash() {
        return contentHash;
    }

    public long getContentLength() {
        return contentLength;
    }

    public List<String> getChunkIds() {
        return chunkIds;
    }

    public long getIndexTimeMillis() {
        return indexTimeMillis;
    }

    public Instant getIndexTime() {
        return Instant.ofEpochMilli(indexTimeMillis);
    }

    /**
     * The entity tag the source's server named the indexed version by.
     *
     * @return The tag exactly as it was reported, or null when there was none
     */
    public String getEntityTag() {
        return entityTag;
    }

    /**
     * What the source reported about the indexed version, in the form it can be asked about again.
     *
     * @return The timestamp and entity tag this entry recorded
     */
    public DocumentValidators validators() {
        return new DocumentValidators(lastModifiedMillis, entityTag);
    }

    public int getChunkCount() {
        return chunkIds.size();
    }

    public boolean hasKnownLastModified() {
        return lastModifiedMillis > 0;
    }

    public boolean hasContentHash() {
        return contentHash != 0;
    }

    public boolean isChanged(FetchedDocumentInfo fetched) {
        if (fetched == null) {
            return true;
        }
        if (fetched.getLastModifiedMillis() > 0 && lastModifiedMillis > 0) {
            if (fetched.getLastModifiedMillis() > lastModifiedMillis) {
                return true;
            }
        }
        if (fetched.getContentLength() != contentLength) {
            return true;
        }
        if (fetched.getContentHash() != 0 && contentHash != 0) {
            if (fetched.getContentHash() != contentHash) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .documentId(documentId)
            .sourceId(sourceId)
            .lastModifiedMillis(lastModifiedMillis)
            .contentHash(contentHash)
            .contentLength(contentLength)
            .chunkIds(chunkIds)
            .indexTimeMillis(indexTimeMillis)
            .entityTag(entityTag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentMetadata that = (DocumentMetadata) o;
        return lastModifiedMillis == that.lastModifiedMillis &&
               contentHash == that.contentHash &&
               contentLength == that.contentLength &&
               indexTimeMillis == that.indexTimeMillis &&
               Objects.equals(documentId, that.documentId) &&
               Objects.equals(sourceId, that.sourceId) &&
               Objects.equals(chunkIds, that.chunkIds) &&
               Objects.equals(entityTag, that.entityTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, sourceId, lastModifiedMillis, contentHash,
                           contentLength, chunkIds, indexTimeMillis, entityTag);
    }

    @Override
    public String toString() {
        return "DocumentMetadata{" +
               "documentId='" + documentId + '\'' +
               ", sourceId='" + sourceId + '\'' +
               ", lastModified=" + Instant.ofEpochMilli(lastModifiedMillis) +
               ", contentHash=" + contentHash +
               ", contentLength=" + contentLength +
               ", chunkCount=" + chunkIds.size() +
               ", indexTime=" + Instant.ofEpochMilli(indexTimeMillis) +
               ", entityTag=" + entityTag +
               '}';
    }

    public static class Builder {
        private String documentId;
        private String sourceId;
        private long lastModifiedMillis;
        private long contentHash;
        private long contentLength;
        private List<String> chunkIds = new ArrayList<>();
        private long indexTimeMillis;
        private String entityTag;

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder lastModifiedMillis(long lastModifiedMillis) {
            this.lastModifiedMillis = lastModifiedMillis;
            return this;
        }

        public Builder lastModified(Instant lastModified) {
            this.lastModifiedMillis = lastModified != null ? lastModified.toEpochMilli() : 0;
            return this;
        }

        public Builder contentHash(long contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        public Builder chunkIds(List<String> chunkIds) {
            this.chunkIds = chunkIds != null ? new ArrayList<>(chunkIds) : new ArrayList<>();
            return this;
        }

        public Builder addChunkId(String chunkId) {
            if (chunkId != null && !chunkId.isEmpty()) {
                this.chunkIds.add(chunkId);
            }
            return this;
        }

        public Builder indexTimeMillis(long indexTimeMillis) {
            this.indexTimeMillis = indexTimeMillis;
            return this;
        }

        public Builder indexTime(Instant indexTime) {
            this.indexTimeMillis = indexTime != null ? indexTime.toEpochMilli() : System.currentTimeMillis();
            return this;
        }

        /**
         * Records the entity tag the source's server named this version by.
         *
         * @param entityTag The tag exactly as it was reported, or null for none
         * @return This builder
         */
        public Builder entityTag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        public DocumentMetadata build() {
            return new DocumentMetadata(this);
        }
    }

    /**
     * Information about a fetched document for change comparison.
     */
    public static final class FetchedDocumentInfo {
        private final long lastModifiedMillis;
        private final long contentHash;
        private final long contentLength;

        public FetchedDocumentInfo(long lastModifiedMillis, long contentHash, long contentLength) {
            this.lastModifiedMillis = Math.max(0, lastModifiedMillis);
            this.contentHash = contentHash;
            this.contentLength = Math.max(0, contentLength);
        }

        public long getLastModifiedMillis() {
            return lastModifiedMillis;
        }

        public long getContentHash() {
            return contentHash;
        }

        public long getContentLength() {
            return contentLength;
        }
    }
}
