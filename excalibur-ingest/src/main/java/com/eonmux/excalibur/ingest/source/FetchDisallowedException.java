package com.eonmux.excalibur.ingest.source;

/**
 * Thrown when the publisher of a document forbids fetching it - for an HTTP source, when the
 * origin's {@code robots.txt} disallows the URL or a URL it redirects to.
 *
 * <h2>Why it is not an ordinary failure</h2>
 * <p>Nothing went wrong: the source asked, and the answer was no. A run that counted these among
 * the documents it failed to ingest would report a site that keeps crawlers out of its drafts as
 * a broken crawl, and change detection that kept the indexed version - as it does for a fetch
 * that failed - would go on serving a page its publisher has since asked crawlers to leave
 * alone. So callers tell it apart: it is counted as disallowed, and a registered document it is
 * raised for is treated as one the source no longer lists.</p>
 *
 * <p>Not retryable: asking again within the same run gets the same answer.</p>
 */
public class FetchDisallowedException extends SourceException {

    private final String documentId;

    /**
     * Creates the exception.
     *
     * @param documentId The document that may not be fetched
     * @param sourceId   The source it belongs to
     * @param reason     Which URL was refused, and by what
     */
    public FetchDisallowedException(String documentId, String sourceId, String reason) {
        super("Fetching " + documentId + " is disallowed: " + reason, sourceId, false);
        this.documentId = documentId;
    }

    /**
     * Returns the document that may not be fetched.
     *
     * @return The document id
     */
    public String getDocumentId() {
        return documentId;
    }
}
