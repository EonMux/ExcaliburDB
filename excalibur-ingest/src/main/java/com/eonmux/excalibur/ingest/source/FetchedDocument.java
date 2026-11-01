package com.eonmux.excalibur.ingest.source;

import com.eonmux.excalibur.core.hash.XxHash64;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 *   <li>Entity tag: the validator an HTTP server named this version by, or null where the
 *       source has no such thing</li>
 * </ul>
 */
public final class FetchedDocument {

    private final String documentId;
    private final byte[] content;
    private final String contentType;
    private final Charset charset;
    private final long lastModifiedMillis;
    private final String entityTag;

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
        this(documentId, content, contentType, StandardCharsets.UTF_8, lastModifiedMillis);
    }

    /**
     * Creates a new FetchedDocument with the encoding its bytes are in.
     *
     * <p>The source knows the encoding - an HTTP {@code Content-Type} names it, a file may carry
     * a byte-order mark - and nothing downstream can recover it once the bytes are separated from
     * it. Decoding as UTF-8 regardless does not fail on a windows-1252 page: it substitutes
     * U+FFFD for every non-ASCII byte, so the terms indexed and the embeddings computed from them
     * are silently wrong and the document cannot be found by its own words.</p>
     *
     * @param documentId        Unique identifier for this document (non-null, non-empty)
     * @param content           Raw byte content of the document (defensively copied)
     * @param contentType       MIME type (e.g., "text/plain", "text/html")
     * @param charset           The encoding {@code content} is in; UTF-8 when null
     * @param lastModifiedMillis Last modification timestamp in epoch millis (0 if unknown)
     * @throws IllegalArgumentException if documentId is null or empty, or content is null
     */
    public FetchedDocument(String documentId, byte[] content, String contentType,
                           Charset charset, long lastModifiedMillis) {
        this(documentId, content, contentType, charset, lastModifiedMillis, null);
    }

    /**
     * Creates a new FetchedDocument carrying the entity tag its server named this version by.
     *
     * <p>The tag is what makes the next re-crawl cheap: sent back as {@code If-None-Match}, it
     * lets the server answer {@code 304 Not Modified} instead of the body, and it is exact where
     * {@code Last-Modified} has one-second resolution and is often absent from generated pages.
     * It is kept verbatim, weak prefix and quotes included, because that is the form the server
     * compares it in.</p>
     *
     * @param documentId        Unique identifier for this document (non-null, non-empty)
     * @param content           Raw byte content of the document (defensively copied)
     * @param contentType       MIME type (e.g., "text/plain", "text/html")
     * @param charset           The encoding {@code content} is in; UTF-8 when null
     * @param lastModifiedMillis Last modification timestamp in epoch millis (0 if unknown)
     * @param entityTag         The server's entity tag for this version, or null for none
     * @throws IllegalArgumentException if documentId is null or empty, or content is null
     */
    public FetchedDocument(String documentId, byte[] content, String contentType,
                           Charset charset, long lastModifiedMillis, String entityTag) {
        this(requireContent(content).clone(), documentId, contentType, charset,
            lastModifiedMillis, entityTag);
    }

    /**
     * Creates a FetchedDocument over an array the caller hands over rather than shares.
     *
     * <p>For a source that has just read the bytes into an array nothing else holds. The public
     * constructors copy, which is what keeps a caller's later writes out of the document; an array
     * no one else can reach needs no such protection, and copying it costs one more document's
     * worth of heap at the moment the document is largest - a gigabyte for the largest PDF this
     * node reads.</p>
     *
     * @param documentId        Unique identifier for this document (non-null, non-empty)
     * @param content           The bytes, owned by the document from here on
     * @param contentType       MIME type, or null for unknown
     * @param charset           The encoding {@code content} is in; UTF-8 when null
     * @param lastModifiedMillis Last modification timestamp in epoch millis (0 if unknown)
     * @param entityTag         The server's entity tag for this version, or null for none
     * @return The document
     * @throws IllegalArgumentException if documentId is null or empty, or content is null
     */
    static FetchedDocument adopting(String documentId, byte[] content, String contentType,
                                    Charset charset, long lastModifiedMillis, String entityTag) {
        return new FetchedDocument(requireContent(content), documentId, contentType, charset,
            lastModifiedMillis, entityTag);
    }

    /** Every constructor ends here, with an array that is the document's own. */
    private FetchedDocument(byte[] owned, String documentId, String contentType,
                            Charset charset, long lastModifiedMillis, String entityTag) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("Document ID must be non-null and non-empty");
        }
        this.documentId = documentId;
        this.content = owned;
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        this.lastModifiedMillis = Math.max(0, lastModifiedMillis);
        this.entityTag = entityTag == null || entityTag.isBlank() ? null : entityTag;
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
     * Returns the content as a read-only view, without copying it.
     *
     * <p>For a reader that only reads, such as a PDF parser walking a document of hundreds of
     * megabytes. {@link #getContent()} copies, which for those is a second document's worth
     * of heap for nothing. The view cannot write through to the content, so the document stays
     * immutable.</p>
     *
     * @return A read-only buffer over the content, positioned at its start
     */
    public ByteBuffer contentView() {
        return ByteBuffer.wrap(content).asReadOnlyBuffer();
    }

    /**
     * The content's hash, as the registry records it to tell one version from another.
     *
     * <p>Computed over the content in place, so a large document is not copied to be hashed.</p>
     *
     * @return The XXH64 hash, seed 0, of the content
     */
    public long contentHash() {
        return XxHash64.hash(content);
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
     * Returns the encoding the content bytes are in.
     *
     * @return The charset (never null; UTF-8 when the source did not declare one)
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Returns the content decoded with the encoding the source declared.
     *
     * <p>The one place the bytes become text, so no consumer has to assume an encoding the
     * source already told us. A leading byte-order mark is dropped; see
     * {@link TextEncoding#decode}.</p>
     *
     * @return The decoded text
     */
    public String getText() {
        return TextEncoding.decode(content, charset);
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
     * Returns the entity tag the source's server named this version by.
     *
     * @return The tag exactly as the server sent it, or null when there was none
     */
    public String getEntityTag() {
        return entityTag;
    }

    /**
     * What the source reported about this version, in the form a later re-crawl asks with.
     *
     * @return The last-modified time and entity tag this fetch carried
     */
    public DocumentValidators validators() {
        return new DocumentValidators(lastModifiedMillis, entityTag);
    }

    /**
     * Checks if the last modification time is known.
     *
     * @return true if lastModifiedMillis &gt; 0
     */
    public boolean hasKnownLastModified() {
        return lastModifiedMillis > 0;
    }

    private static byte[] requireContent(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("Content must be non-null (use empty array for empty documents)");
        }
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FetchedDocument that = (FetchedDocument) o;
        return lastModifiedMillis == that.lastModifiedMillis &&
               Objects.equals(documentId, that.documentId) &&
               Objects.equals(contentType, that.contentType) &&
               Objects.equals(charset, that.charset) &&
               Objects.equals(entityTag, that.entityTag) &&
               java.util.Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(documentId, contentType, charset, lastModifiedMillis,
            entityTag);
        result = 31 * result + java.util.Arrays.hashCode(content);
        return result;
    }

    @Override
    public String toString() {
        return "FetchedDocument{" +
               "documentId='" + documentId + '\'' +
               ", contentLength=" + content.length +
               ", contentType='" + contentType + '\'' +
               ", charset=" + charset +
               ", lastModifiedMillis=" + lastModifiedMillis +
               ", entityTag=" + entityTag +
               '}';
    }
}
