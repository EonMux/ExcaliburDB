package com.eonmux.excalibur.ingest.processor;

/**
 * Thrown when the configured PDF converter fails on a document.
 *
 * <p>A failure of the program rather than of the document: it could not be started, exited
 * non-zero, ran past its timeout or wrote more than a document may hold. The document is left
 * unregistered, so the next crawl tries it again; a PDF the converter reads and finds no text in
 * is not this, but an empty document.</p>
 */
public final class PdfConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What failed, naming the document
     */
    public PdfConversionException(String message) {
        super(message);
    }

    /**
     * @param message What failed, naming the document
     * @param cause   The I/O failure beneath it
     */
    public PdfConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
