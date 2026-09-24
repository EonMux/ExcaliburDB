package com.eonmux.excalibur.ingest.source;

/**
 * Thrown when a source answers for a document with a version this node cannot index: a body
 * past the size limit, a content type the source excludes, a client error such as {@code 403},
 * a redirect that leaves the source's policy, a path that is not a regular file.
 *
 * <h2>Why it is not an ordinary failure</h2>
 * <p>A timeout, a {@code 5xx} or an I/O error says nothing about the document: the question was
 * not answered, and a re-crawl reports it as unchecked. Here it was answered. The document is
 * still a failure - it is not indexed - but a known one with a reason, so change detection
 * reports it among the documents the run failed on rather than among those it could not ask
 * about. The indexed version, if any, is kept either way.</p>
 *
 * <p>Not retryable: asking again gets the same answer until the document itself changes.</p>
 */
public class DocumentRefusedException extends SourceException {

    /**
     * Creates the exception.
     *
     * @param message  What the source answered, naming the document
     * @param sourceId The source the document belongs to
     */
    public DocumentRefusedException(String message, String sourceId) {
        super(message, sourceId, false);
    }
}
