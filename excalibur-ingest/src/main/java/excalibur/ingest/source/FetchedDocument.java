package excalibur.ingest.source;

import java.util.Objects;

/**
 * Represents a document fetched from a source, containing raw bytes and content type.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the document
 * cannot be modified. The byte array is defensively copied on construction
 * and access to prevent external mutation.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Content: raw bytes of the document (never null, may be empty)</li>
 *   <li>Content-Type: MIME type string (e.g., "text/plain", "text/html", "application/pdf")</li>
 *   <li>Document ID: unique identifier within the source (non-null, non-empty)</li>
 *   <li>Timestamp: when the document was last modified (Instant.EPOCH if unknown)</li>
 * </ul>
 */
public final class FetchedDocument {

    private final String documentId;
    private final byte[] content;
    private final String contentType;
    private final long lastModifiedMillis;

    /**
     * Creates a new FetchedDocument.
     *
     * @param documentId        Unique identifier for this document (non-null, non-empty)
     * @param content           Raw byte content of the document (defensively copied)
     * @param contentType       MIME type (e.g., "text/plain", "text/html")
     * @param lastModifiedMillis Last modification timestamp in epoch millis (0 if unknown)
     * @throws IllegalArgumentException if documentId is null or empty, or content is null
     */
    public FetchedDocument(String documentId, byte[] content, String contentType, long lastModifiedMillis) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("Document ID must be non-null and non-empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content must be non-null (use empty array for empty documents)");
        }
        this.documentId = documentId;
        this.content = content.clone();
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.lastModifiedMillis = Math.max(0, lastModifiedMillis);
    }

    /**
     * Creates a FetchedDocument with unknown last modified time.
     *
     * @param documentId  Unique identifier for this document
     * @param content     Raw byte content
     * @param contentType MIME type
     */
    public FetchedDocument(String documentId, byte[] content, String contentType) {
        this(documentId, content, contentType, 0L);
    }

    /**
     * Returns the unique identifier for this document within its source.
     *
     * @return Document ID (never null or empty)
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Returns a copy of the raw byte content.
     *
     * <p>A copy is returned to preserve immutability. For large documents,
     * consider processing the content directly rather than holding the copy.</p>
     *
     * @return Copy of the content bytes (never null)
     */
    public byte[] getContent() {
        return content.clone();
    }

    /**
     * Returns the number of bytes in the document content.
     *
     * @return Content length in bytes (&ge; 0)
     */
    public int getContentLength() {
        return content.length;
    }

    /**
     * Returns the MIME type of the document content.
     *
     * @return Content type string (e.g., "text/plain", never null)
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Returns the last modification timestamp.
     *
     * @return Epoch milliseconds since 1970-01-01T00:00:00Z, or 0 if unknown
     */
    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    /**
     * Checks if the last modification time is known.
     *
     * @return true if lastModifiedMillis &gt; 0
     */
    public boolean hasKnownLastModified() {
        return lastModifiedMillis > 0;
    }

    /**
     * Checks if this document has a text-based content type.
     *
     * <p>Recognized text types: text/*, application/json, application/xml, application/javascript</p>
     *
     * @return true if content is likely text-based
     */
    public boolean isTextContent() {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("text/") ||
               lower.equals("application/json") ||
               lower.equals("application/xml") ||
               lower.equals("application/javascript") ||
               lower.endsWith("+json") ||
               lower.endsWith("+xml");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FetchedDocument that = (FetchedDocument) o;
        return lastModifiedMillis == that.lastModifiedMillis &&
               Objects.equals(documentId, that.documentId) &&
               Objects.equals(contentType, that.contentType) &&
               java.util.Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(documentId, contentType, lastModifiedMillis);
        result = 31 * result + java.util.Arrays.hashCode(content);
        return result;
    }

    @Override
    public String toString() {
        return "FetchedDocument{" +
               "documentId='" + documentId + '\'' +
               ", contentLength=" + content.length +
               ", contentType='" + contentType + '\'' +
               ", lastModifiedMillis=" + lastModifiedMillis +
               '}';
    }
}
