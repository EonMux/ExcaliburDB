package com.eonmux.excalibur.api.embedded;

import com.eonmux.excalibur.ingest.processor.TextContent;

/**
 * One document supplied to {@link ExcaliburEmbedded#supply}.
 *
 * <p>Held to the rules {@code POST /v1/documents} holds a supplied document to, for the same
 * reasons: empty text produces no chunks and would be reported as ingested while no query could
 * find it, and text carrying U+0000 is what a crawl treats as the mark of a binary file.</p>
 *
 * @param documentId         Names the document within its source; supplying the same id again
 *                           replaces what the node holds under it
 * @param text               The document's text
 * @param contentType        Its media type; {@code text/plain} when null or blank
 * @param lastModifiedMillis Its own date in epoch millis, or 0 when unknown
 */
public record EmbeddedDocument(String documentId, String text, String contentType,
                               long lastModifiedMillis) {

    /** The media type a document is read as when none is given. */
    public static final String DEFAULT_CONTENT_TYPE = "text/plain";

    /**
     * @throws IllegalArgumentException if the id is null or empty, or the text is null, empty or
     *                                  not text
     */
    public EmbeddedDocument {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("A document needs a non-empty documentId");
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(
                "Document '" + documentId + "' needs non-empty text");
        }
        if (!TextContent.isText(text)) {
            throw new IllegalArgumentException(
                "Document '" + documentId + "' has text that is not text");
        }
        contentType = contentType == null || contentType.isBlank()
            ? DEFAULT_CONTENT_TYPE : contentType;
    }

    /**
     * A plain-text document with no known date.
     *
     * @param documentId Names the document within its source
     * @param text       Its text
     * @return The document
     * @throws IllegalArgumentException as the canonical constructor does
     */
    public static EmbeddedDocument of(String documentId, String text) {
        return new EmbeddedDocument(documentId, text, DEFAULT_CONTENT_TYPE, 0L);
    }
}
