package com.eonmux.excalibur.ingest.source;

/**
 * Exception thrown when attempting to fetch a document that does not exist.
 *
 * <p>This exception indicates the requested document ID was not found in the
 * source. This may occur when:</p>
 * <ul>
 *   <li>The document was deleted after crawling</li>
 *   <li>The document ID is invalid or malformed</li>
 *   <li>The document was never present in the source</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read access (immutable after construction).</p>
 *
 * <h2>Handling</h2>
 * <p>Callers should typically treat this as a signal to tombstone the document
 * in the index, as the document has been removed from the source.</p>
 */
public class DocumentNotFoundException extends SourceException {

    private final String documentId;

    /**
     * Creates a new DocumentNotFoundException.
     *
     * @param documentId The document ID that was not found
     */
    public DocumentNotFoundException(String documentId) {
        super("Document not found: " + documentId, null, false);
        this.documentId = documentId;
    }

    /**
     * Creates a new DocumentNotFoundException with source context.
     *
     * @param documentId The document ID that was not found
     * @param sourceId   The source that was queried
     */
    public DocumentNotFoundException(String documentId, String sourceId) {
        super("Document not found: " + documentId + " in source: " + sourceId, sourceId, false);
        this.documentId = documentId;
    }

    /**
     * Creates a new DocumentNotFoundException with cause.
     *
     * @param documentId The document ID that was not found
     * @param sourceId   The source that was queried
     * @param cause      The underlying cause (e.g., 404 response)
     */
    public DocumentNotFoundException(String documentId, String sourceId, Throwable cause) {
        super("Document not found: " + documentId + " in source: " + sourceId, sourceId, false, cause);
        this.documentId = documentId;
    }

    /**
     * Returns the document ID that was not found.
     *
     * @return The missing document ID
     */
    public String getDocumentId() {
        return documentId;
    }
}
