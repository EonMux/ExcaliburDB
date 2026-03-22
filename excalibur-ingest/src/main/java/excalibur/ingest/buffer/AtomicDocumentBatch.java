package excalibur.ingest.buffer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an atomically committed batch of chunks for a single document.
 *
 * <p>This class captures the write log offset range for a document's chunks,
 * enabling document-atomic visibility in the query pipeline.</p>
 *
 * <h2>Document Atomicity</h2>
 * <p>An AtomicDocumentBatch represents a single atomic unit of visibility:
 * <ul>
 *   <li>All chunks in the batch are visible at the same epoch</li>
 *   <li>Queries never see partial documents (some chunks visible, others not)</li>
 *   <li>Crash recovery can identify complete documents by their offset range</li>
 * </ul>
 *
 * <h2>Offset Range Semantics</h2>
 * <p>The offset range [startOffset, endOffset) represents the contiguous region
 * in the write log containing all chunks for this document:</p>
 * <ul>
 *   <li>startOffset: The write log position before the first chunk was written</li>
 *   <li>endOffset: The write log position after the last chunk was written</li>
 *   <li>All chunks have contiguous offsets within this range</li>
 * </ul>
 *
 * <h2>Visibility Epoch Integration</h2>
 * <p>The offset range is used by the visibility tracker to determine when a
 * document becomes visible:</p>
 * <pre>{@code
 * AtomicDocumentBatch batch = buffer.commitDocument(documentId);
 * writeLog.sync(); // Ensure durability
 * visibilityTracker.advanceEpoch(); // Make visible at next epoch tick
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. All fields are final and the
 * chunk IDs and entry IDs lists are unmodifiable.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>documentId: non-null, non-empty string</li>
 *   <li>startOffset: non-negative, less than endOffset</li>
 *   <li>endOffset: non-negative, greater than startOffset</li>
 *   <li>chunkIds: immutable list of non-null strings</li>
 *   <li>entryIds: immutable list of non-negative longs</li>
 *   <li>chunkIds.size() == entryIds.size()</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class AtomicDocumentBatch {

    private final String documentId;
    private final long startOffset;
    private final long endOffset;
    private final List<String> chunkIds;
    private final List<Long> entryIds;

    /**
     * Creates a new AtomicDocumentBatch.
     *
     * @param documentId The document ID (must not be null or empty)
     * @param startOffset The write log offset before the first chunk (must be &gt;= 0)
     * @param endOffset The write log offset after the last chunk (must be &gt; startOffset)
     * @param chunkIds The list of chunk IDs (must not be null)
     * @param entryIds The list of write log entry IDs (must not be null)
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if validation fails
     */
    public AtomicDocumentBatch(
            String documentId,
            long startOffset,
            long endOffset,
            List<String> chunkIds,
            List<Long> entryIds) {
        
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        Objects.requireNonNull(entryIds, "entryIds must not be null");

        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be non-negative: " + startOffset);
        }
        if (endOffset < 0) {
            throw new IllegalArgumentException("endOffset must be non-negative: " + endOffset);
        }
        if (endOffset <= startOffset) {
            throw new IllegalArgumentException(
                "endOffset must be greater than startOffset: start=" + startOffset + ", end=" + endOffset);
        }
        if (chunkIds.size() != entryIds.size()) {
            throw new IllegalArgumentException(
                "chunkIds and entryIds must have same size: chunks=" + chunkIds.size() + 
                ", entries=" + entryIds.size());
        }

        this.documentId = documentId;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.chunkIds = Collections.unmodifiableList(chunkIds);
        this.entryIds = Collections.unmodifiableList(entryIds);
    }

    /**
     * Returns the document ID.
     *
     * @return The document identifier
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Returns the write log offset before the first chunk was written.
     *
     * <p>This is the lower bound of the offset range [startOffset, endOffset).</p>
     *
     * @return The start offset (non-negative)
     */
    public long getStartOffset() {
        return startOffset;
    }

    /**
     * Returns the write log offset after the last chunk was written.
     *
     * <p>This is the upper bound of the offset range [startOffset, endOffset).</p>
     *
     * @return The end offset (non-negative, greater than startOffset)
     */
    public long getEndOffset() {
        return endOffset;
    }

    /**
     * Returns the size of the offset range in bytes.
     *
     * @return The byte size of this batch in the write log
     */
    public long getByteSize() {
        return endOffset - startOffset;
    }

    /**
     * Returns the list of chunk IDs in document order.
     *
     * <p>Chunk IDs follow the format: documentId:chunkIndex</p>
     *
     * @return Immutable list of chunk IDs
     */
    public List<String> getChunkIdsList() {
        return chunkIds;
    }

    /**
     * Returns the list of chunk IDs in document order.
     *
     * <p>Alias for {@link #getChunkIdsList()} for backward compatibility.</p>
     *
     * @return Immutable list of chunk IDs
     */
    public List<String> getChunkIdList() {
        return chunkIds;
    }

    /**
     * Returns the list of write log entry IDs in document order.
     *
     * <p>Entry IDs correspond one-to-one with chunk IDs.</p>
     *
     * @return Immutable list of entry IDs
     */
    public List<Long> getEntryIdList() {
        return entryIds;
    }

    /**
     * Returns the number of chunks in this batch.
     *
     * @return Chunk count
     */
    public int getChunkCount() {
        return chunkIds.size();
    }

    /**
     * Returns the first chunk ID.
     *
     * @return The first chunk ID, or null if batch is empty
     */
    public String getFirstChunkId() {
        return chunkIds.isEmpty() ? null : chunkIds.get(0);
    }

    /**
     * Returns the last chunk ID.
     *
     * @return The last chunk ID, or null if batch is empty
     */
    public String getLastChunkId() {
        return chunkIds.isEmpty() ? null : chunkIds.get(chunkIds.size() - 1);
    }

    /**
     * Returns the first entry ID.
     *
     * @return The first entry ID, or -1 if batch is empty
     */
    public long getFirstEntryId() {
        return entryIds.isEmpty() ? -1 : entryIds.get(0);
    }

    /**
     * Returns the last entry ID.
     *
     * @return The last entry ID, or -1 if batch is empty
     */
    public long getLastEntryId() {
        return entryIds.isEmpty() ? -1 : entryIds.get(entryIds.size() - 1);
    }

    /**
     * Checks if this batch contains a specific offset.
     *
     * @param offset The offset to check
     * @return true if offset is in range [startOffset, endOffset)
     */
    public boolean containsOffset(long offset) {
        return offset >= startOffset && offset < endOffset;
    }

    /**
     * Checks if this batch is empty (contains no chunks).
     *
     * @return true if chunk count is zero
     */
    public boolean isEmpty() {
        return chunkIds.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AtomicDocumentBatch that = (AtomicDocumentBatch) o;
        return startOffset == that.startOffset &&
               endOffset == that.endOffset &&
               Objects.equals(documentId, that.documentId) &&
               Objects.equals(chunkIds, that.chunkIds) &&
               Objects.equals(entryIds, that.entryIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, startOffset, endOffset, chunkIds, entryIds);
    }

    @Override
    public String toString() {
        return String.format(
            "AtomicDocumentBatch{documentId='%s', offsets=[%d, %d), chunks=%d, size=%d bytes}",
            documentId, startOffset, endOffset, chunkIds.size(), getByteSize());
    }

    /**
     * Creates a builder for constructing AtomicDocumentBatch instances.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for AtomicDocumentBatch.
     */
    public static final class Builder {
        private String documentId;
        private long startOffset;
        private long endOffset;
        private List<String> chunkIds = Collections.emptyList();
        private List<Long> entryIds = Collections.emptyList();

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder startOffset(long startOffset) {
            this.startOffset = startOffset;
            return this;
        }

        public Builder endOffset(long endOffset) {
            this.endOffset = endOffset;
            return this;
        }

        public Builder chunkIds(List<String> chunkIds) {
            this.chunkIds = chunkIds != null ? chunkIds : Collections.emptyList();
            return this;
        }

        public Builder entryIds(List<Long> entryIds) {
            this.entryIds = entryIds != null ? entryIds : Collections.emptyList();
            return this;
        }

        public AtomicDocumentBatch build() {
            return new AtomicDocumentBatch(documentId, startOffset, endOffset, chunkIds, entryIds);
        }
    }
}
